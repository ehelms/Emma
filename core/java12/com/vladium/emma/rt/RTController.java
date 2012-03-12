/* Copyright (C) 2005 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: RTController.java,v 1.1 2005/06/21 02:39:35 vlad_r Exp $
 */
package com.vladium.emma.rt;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.LinkedList;

import com.vladium.emma.IAppConstants;
import com.vladium.emma.ctl.ControlRequest;
import com.vladium.emma.data.ICoverageData;
import com.vladium.emma.rt.rpc.Request;
import com.vladium.emma.rt.rpc.Response;
import com.vladium.logging.ILogLevels;
import com.vladium.logging.Logger;
import com.vladium.util.IFileLock;
import com.vladium.util.Property;

// ----------------------------------------------------------------------------
/**
 * A remotely accessible controller for {@link com.vladium.emma.rt.RT}.<P>
 * 
 * Lifecycle model:
 * <ol>
 *     <li>an instance is {@link #RTController(int) created};</li>
 *     <li>the instance is {@link #start() started}. This creates the actual
 *         request TCP listen port and the necessary request handling threads.</li>
 *     <li>the same instance can then {@link #execute(Request) execute} any number of RPC requests;</li>
 *     <li>the controller is (asyncronously) {@link #shutdown() shut down} and can be
 *         garbage collected.</li>
 * </ol>
 * 
 * Threading model: {@link #execute(Request)} is called serially from an instance of
 * ExecuteThread (this thread serializes RPC requests that arrive asynchronously
 * on the listen port). This executor thread is different from the threads that
 * call {@link #start()} or {@link #shutdown()}. {@link #shutdown()} can be called
 * asynchronously with respect to request execution and in general will abort the
 * current request (possibly resulting in errors seen on the client side).<P>
 * 
 * NOTE: this design does not use RMI on purpose, to have complete control over
 * the threading model and satisfy the following constraints:
 * <ul>
 *     <li>at most two threads are (re)used for all request queueing and
 *         processing;</li>
 *     <li>at most one request is processed at any given moment;</li>
 *     <li>at most two socket connections are actively marshalling data.</li>
 * </ul> 
 * 
 * @see com.vladium.emma.rt.RTControllerClientProxy
 * 
 * @author Vlad Roubtsov, (C) 2005
 */
final class RTController
{
    // public: ................................................................
    
    // protected: .............................................................

    // package: ...............................................................
    
    RTController (final int port)
    {
        if (port < 0 || port > 0xFFFF)
            throw new IllegalArgumentException ("port must be in [1, 65535] range: " + port);
        
        m_RT = RT.class;
        m_port = port;
    }
    
    // lifecycle API:
    
    /**
     * Creates a server socket listening on {@link RT#PROPERTY_RT_CONTROL_PORT control} port
     * and allocates/starts the request listening and execution threads.
     */
    synchronized void start () throws IOException
    {
        if (m_ssocket != null)
            throw new IllegalStateException ("runtime controller already started");
        
        // use method-scoped loggers everywhere in RT:
        final Logger log = Logger.getLogger ();
        
        log.verbose ("starting runtime controller ...");
        
        // TODO: handle repeated bind attempts
        m_ssocket = new ServerSocket (m_port);
        
        final ThreadGroup controllerThreadGroup = new ThreadGroup (IAppConstants.APP_NAME + " runtime thread group");
        controllerThreadGroup.setDaemon (true);
        
        m_queue = new Queue (); // TODO: set max capacity?
        
        final ListenThread listener = new ListenThread (this, m_ssocket, m_queue);
        final Thread listenThread = new Thread (controllerThreadGroup, listener, IAppConstants.APP_NAME + " runtime listen thread");
        listenThread.setDaemon (true);
        m_listener = listener;
        
        final ExecuteThread executor = new ExecuteThread (this, m_queue);
        final Thread executeThread = new Thread (controllerThreadGroup, executor, IAppConstants.APP_NAME + " runtime execute thread");
        executeThread.setDaemon (true);
        m_executor = executor;
        
        m_listenThread = listenThread;
        executeThread.start ();
        m_executeThread = executeThread;
        listenThread.start ();
        
        log.info ("runtime controller started on port [" + m_port + "]");
    }
    
    /**
     * Closes the controller listen socket, closes all request queue response sockets,
     * and terminates the request listening and execution threads.<P>
     * 
     * NOTE: the thread termination paradigm here is the "least common denominator"
     * type that is suitable for pre-NIO JVMs. Threads are cooperatively joined on
     * but the socket I/O operations may get interrupted asynchronously (possibly
     * resulting in errors seen by {@link RTControllerClientProxy}).  
     */
    synchronized void shutdown ()
    {
        if (m_ssocket != null) // no-op if start() has not been invoked
        {
            // use method-scoped loggers everywhere in RT:
            final Logger log = Logger.getLogger ();
            final String method = "shutdown";

            log.verbose ("shutting down runtime controller ...");

            // signal shutdown for the listen thread:
            
            m_listener.signalShutdown (); // switch the thread into shutting down mode
            m_listenThread.interrupt ();
            
            // close the server socket (on older JVMs this will unblock the accept()):
            try { m_ssocket.close (); } catch (Exception ignore) { }
            log.trace1 (method, "runtime control port socket closed");

            // join on the listen thread:
            try
            {
                m_listenThread.join (THREAD_JOIN_TIMEOUT); // don't block indefinitely
            }
            catch (InterruptedException ignore) { ignore.printStackTrace (System.out); }
            m_listenThread = null;
            m_listener = null;
            log.trace1 (method, "listen thread terminated");
            
            // signal shutdown for the execute thread:
            
            m_executor.signalShutdown (); // switch the thread into shutting down mode
            m_executeThread.interrupt ();
            
            // close the current response socket (on older JVMs this will unblock the I/O):
            final Socket currentRequestSocket = m_executor.currentRequestSocket ();
            if (currentRequestSocket != null)
            {
                try { currentRequestSocket.close (); } catch (Exception ignore) { }
            }
            
            // join on the execute thread:
            try
            {
                m_executeThread.join (THREAD_JOIN_TIMEOUT); // don't block indefinitely
            }
            catch (InterruptedException ignore) { ignore.printStackTrace (System.out); }
            m_executeThread = null;
            m_executor = null;
            log.trace1 (method, "execute thread terminated");
            
            // abort any remaining requests:
            while (! m_queue.isEmpty ())
            {
                final RequestDescriptor rd;
                try
                {
                    rd = (RequestDescriptor) m_queue.dequeue ();
                    try { rd.m_socket.close (); } catch (Exception ignore) { ignore.printStackTrace (System.out); }
                }
                catch (InterruptedException ignore) { ignore.printStackTrace (System.out); }
            }
            m_queue = null;
            log.trace1 (method, "request queue aborted");
            
            m_ssocket = null;
            log.verbose ("runtime controller shut down");
        }
    }
    
    // command processing:
    
    /**
     * This method is not synchronized by design, to make the lifecycle API asynchronous
     * with respect to request execution.<P>
     * 
     * NOTE: since the request queue is handled by a single executor thread,
     * all requests are executed serially (but usually on a different thread
     * from the one that executes start()/shutdown()).
     */
    Response execute (final Request request)
    {
        final String [] args = request.getArgs ();
        final int ID = request.getID ();

        try
        {
            switch (ID)
            {
                case ControlRequest.ID_TEST_PING:
                {
                    final int delay = Integer.parseInt (args [0]);
                    Thread.sleep (delay);
                    
                    return new Response (ID, new Integer (delay));
                }
                // break;

                
                case ControlRequest.ID_GET_COVERAGE:
                {
                    ICoverageData cdata = RT.getCoverageData ();
                    
                    // note: we could avoid the extra memory hit of shallow data cloning
                    // here (by having Response serialize on cdata.lock() in write());
                    // however, cdata marshalling time is unbounded (e.g. the receiving
                    // client could be slow) and it would be bad to block new class
                    // loading for so long.
                    
                    // by comparison, shallow copy is always fast and not dependent on
                    // the speed of external socket/file I/O, so ultimately this
                    // feels like a safer choice; note that we have already made similar
                    // decisions in the RT exit hook, etc
                    
                    // [also note that RTController allows at most one request to
                    // be processed at a time as well]
                    
                    if (cdata != null)
                    {
                        cdata = cdata.shallowCopy ();
                    
                        final boolean disableShutdownHook = Property.toBoolean (args [2]);
                        if (disableShutdownHook)
                        {
                            // TODO: this should be done after data has been safely marshalled back to the client
                            RT.reset (new RTSettings.SetActions (RTSettings.FIELD_NEW_IF_NULL,
                                                                 RTSettings.FIELD_NEW_IF_NULL,
                                                                 RTSettings.FIELD_NULL,
                                                                 RTSettings.FIELD_NEW_IF_NULL));
                        }
                    }
                    
                    return new Response (ID, cdata);  // marshall cdata back
                }
                // break;

                
                case ControlRequest.ID_DUMP_COVERAGE:
                {
                    final ICoverageData cdata = RT.getCoverageData ();
                    String trace = null; // see TODO below
                    
                    if (cdata != null)
                    {
                        // unlike ID_GET_COVERAGE case, the client can send null
                        // parameter values to indicate that the defaults should come
                        // from the server JVM:
                        
                        final File outFile = args [0] != null
                            ? new File (args [0])
                            : RT.getCoverageOutFile ();
                            
                        final boolean outMerge = args [1] != null
                            ? Property.toBoolean (args [1])
                            : RT.getCoverageOutMerge ();
    
                        final boolean disableShutdownHook = args [2] != null
                            ? Property.toBoolean (args [2])
                            : true;
                             
                        final IFileLock outLock =  RT.getCoverageOutFileLock (outFile);
                        
                        // note: similary to the ID_GET_COVERAGE case above, cdata
                        // is shallowly cloned by the following method:
                        
                        final long start = System.currentTimeMillis (); // see TODO below
                        RTCoverageDataPersister.dumpCoverageData (cdata, true, outFile, outMerge, outLock);
                        final long end = System.currentTimeMillis (); // see TODO below
                        
                        // TODO: record log trace properly and send that back
                        trace = "runtime coverage data remotely " + (outMerge ? "merged into" : "written to") + " [" + outFile.getAbsolutePath () + "] {in " + (end - start) + " ms}";
                        
                        if (disableShutdownHook)
                        {
                            RT.reset (new RTSettings.SetActions (RTSettings.FIELD_NEW_IF_NULL,
                                                                 RTSettings.FIELD_NEW_IF_NULL,
                                                                 RTSettings.FIELD_NULL,
                                                                 RTSettings.FIELD_NEW_IF_NULL));
                        }
                    }
                    
                    // TODO: send back logger trace
                    return new Response (ID, trace); // send back an empty response to indicate successful completion
                }
                // break;
                
                
                case ControlRequest.ID_RESET_COVERAGE:
                {
                    ICoverageData cdata = RT.getCoverageData ();
                    int size = 0;
                    
                    final long start = System.currentTimeMillis ();
                    
                    if (cdata != null)
                    {
                        // note: here we don't do shallow cloning on the assumption
                        // that zeroing in-place is not a very lengthy operation
                        // (it is purely in-memory and can't block on socket/file I/O)
                        
                        // TODO: verify that the scale of the overhead here matches these expectations 
                        
                        synchronized (cdata.lock ())
                        {
                            size = cdata.size ();
                            if (size > 0) cdata.reset ();
                        }
                    }
                    
                    if (size > 0)
                    {
                        final long end = System.currentTimeMillis ();
                        
                        return new Response (ID, "coverage reset for " + size + " classes {in " + (end - start) + " ms}");
                    }
                    else
                    {
                        return new Response (ID, "coverage reset for " + size + " classes");
                    }
                }
                // break;
                
    
                default: throw new IllegalStateException ("invalid request ID " + ID);
         
            } // end of switch
        }
        catch (Throwable t)
        {
            return new Response (ID, t); // marshall the error back to the client 
        }
    }
    
    void reportError (final String msg, final Throwable t)
    {
        // use method-scoped loggers everywhere in RT:
        final Logger log = Logger.getLogger ();
        
        log.log (ILogLevels.SEVERE, msg, t);
    }

    // private: ...............................................................
    
    /*
     * 
     */
    private static final class RequestDescriptor
    {
        RequestDescriptor (final long timestamp,
                           final Socket socket, final Request request)
        {
            m_timestamp = timestamp;
            m_socket = socket;
            m_request = request;
        }
        
        final long m_timestamp;
        final Socket m_socket;
        final Request m_request;
        
    } // end of nested class
    
    /*
     * Single producer/single consumer request buffer. 
     */
    private static final class Queue
    {
        Queue ()
        {
            m_queue = new LinkedList ();
        }
    
        void enqueue (final Object item)
        {
            if (item == null)
                throw new IllegalArgumentException ("null input: item");
            
            synchronized (this)
            {
                m_queue.addFirst (item);
                
                notify (); // single consumer
            }
        }
        
        Object dequeue () throws InterruptedException
        {
            synchronized (this)
            {
                while (m_queue.isEmpty ())
                {
                    wait (); // throws InterruptedException
                }
                
                return m_queue.removeLast ();
            }
        }
        
        synchronized boolean isEmpty ()
        {
            return m_queue.isEmpty ();
        }
        
    
        private final LinkedList m_queue; 
    
    } // end of nested class
    
    
    private static final class ListenThread implements Runnable
    {
        // Runnable:
        
        public void run ()
        {
            // use method-scoped loggers everywhere in RT:
            final Logger log = Logger.getLogger ();
            final boolean trace1 = log.atTRACE1 ();
            final String method = "run";
            
            while (! (shutdownSignalled () || Thread.interrupted ()))
            {
                // read and enqueue a new request:
                
                DataInputStream in = null;
                try
                {
                    final Socket s = m_ssocket.accept ();
                    final long timestamp = System.currentTimeMillis ();
                    
                    in = new DataInputStream (new BufferedInputStream (s.getInputStream (), INPUT_IO_BUF_SIZE));
                    
                    final Request request = Request.read (in);
                    in = null;
                    
                    m_queue.enqueue (new RequestDescriptor (timestamp, s, request));
                    if (trace1) log.trace1 (method, "[" + timestamp + "] enqueued new request " + request.getID () + " @" + s.getInetAddress () + ":" + s.getPort ());
                }
                catch (Throwable t)
                {
                    if (! shutdownSignalled ())
                    {
                        m_controller.reportError ("exception while accepting a controller request", t);
                    }
                }
            }
        }

        synchronized void signalShutdown ()
        {
            m_shuttingDown = true;
        }
            
        private synchronized boolean shutdownSignalled ()
        {
            return m_shuttingDown;
        }
        

        ListenThread (final RTController controller, final ServerSocket ssocket, final Queue queue)
        {
            m_controller = controller;
            m_ssocket = ssocket;
            m_queue = queue;
        }
        
        
        private final RTController m_controller; // keep the controller pinned in memory
        private final ServerSocket m_ssocket;
        private final Queue m_queue;
        
        private boolean m_shuttingDown;
        
        private static final int INPUT_IO_BUF_SIZE = 4 * 1024;
        
    } // end of nested class
    
    
    private static final class ExecuteThread implements Runnable
    {
        // Runnable:
        
        public void run ()
        {
            // use method-scoped loggers everywhere in RT:
            final Logger log = Logger.getLogger ();
            final boolean trace1 = log.atTRACE1 ();
            final String method = "run";
            
            while (! (shutdownSignalled () || Thread.interrupted ()))
            {
                // dequeue and handle a request:
                
                RequestDescriptor rd = null;
                try
                {
                    try
                    {
                        rd = (RequestDescriptor) m_queue.dequeue ();
                        if (trace1) log.trace1 (method, "dequeued request " + rd.m_request.getID ());
                    }
                    catch (InterruptedException ie)
                    {
                        return; // controller shutdown
                    }
                    
                    final Response response = m_controller.execute (rd.m_request);

                    // to ensure I/O interruptibility in old JVMs, expose the current request's socket: 
                    synchronized (this)
                    {
                        m_socket = rd.m_socket;
                    }
                    
                    DataOutputStream out = null;
                    try
                    {
                        out = new DataOutputStream (new BufferedOutputStream (m_socket.getOutputStream (), OUTPUT_IO_BUF_SIZE));
                        
                        Response.write (response, out);
                        out.flush ();
                    }
                    finally
                    {
                        if (out != null) try { out.close (); } catch (Exception ignore) { }
                    }
                    
                    // unset the current request socket:
                    synchronized (this)
                    {
                        m_socket = null;
                    }
                }
                catch (Throwable t)
                {
                    if (! shutdownSignalled ())
                    {
                        m_controller.reportError ("exception while processing a controller request", t);
                    }
                }
                finally
                {
                    if (rd != null)
                    {
                        try
                        {
                            rd.m_socket.close ();
                        }
                        catch (Exception ignore) { }
                    }
                }
            }
        }
        
        synchronized Socket currentRequestSocket ()
        {
            return m_socket;
        }
        
        synchronized void signalShutdown ()
        {
            m_shuttingDown = true;
        }
            
        private synchronized boolean shutdownSignalled ()
        {
            return m_shuttingDown;
        }
        

        ExecuteThread (final RTController controller, final Queue queue)
        {
            m_controller = controller;
            m_queue = queue;
        }
        
        private final RTController m_controller;
        private final Queue m_queue;
        
        private Socket m_socket;
        private boolean m_shuttingDown;
        
        private static final int OUTPUT_IO_BUF_SIZE = 32 * 1024;
        
    } // end of nested class
    
    
    private final int m_port;
    
    private Class m_RT; // keep our RT class pinned in memory (this is actually unnecessary if the controller is created by RT itself)
    private ServerSocket m_ssocket; // socket for listening for new command requests
    private Queue m_queue;
    private ListenThread m_listener;
    private ExecuteThread m_executor;
    private Thread m_listenThread;
    private Thread m_executeThread;
    
    private static final long THREAD_JOIN_TIMEOUT   =   30 * 1000; 
    
} // end of class
// ----------------------------------------------------------------------------
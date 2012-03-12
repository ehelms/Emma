
package com.vladium.emma.rt;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;

import com.vladium.emma.rt.rpc.Request;
import com.vladium.emma.rt.rpc.Response;
import com.vladium.logging.Logger;

// ----------------------------------------------------------------------------
/**
 * RTControllerClientProxy is a client-side interface to {@link com.vladium.emma.rt.RTController}.
 * It abstracts away the details of remotely communicating with our instrumentation
 * runtime, {@link com.vladium.emma.rt.RT}.<P>
 * 
 * Lifecycle model:
 * <ol>
 *     <li>an instance is acquired using the {@link #create(String, int) Factory} method;</li>
 *     <li>the same instance can then {@link #execute(Request)} several RPC requests.
 *         A TCP socket connection is only active when a request is being processed.</li>
 * </ol>
 * 
 * @see com.vladium.emma.ctl.CtlProcessor
 * @see com.vladium.emma.ctl.ControlRequest
 * @see com.vladium.emma.rt.rpc.Request
 * 
 * @author Vlad Roubtsov, (C) 2005
 */
public
final class RTControllerClientProxy
{
    // public: ................................................................
    
    /**
     * Factory method for creating a client proxy that will communicate with
     * {@link com.vladium.emma.rt.RTController} at a given TCP socket host/port
     * endpoint.
     * 
     * @param host server host name in {@link InetAddress#getByName(java.lang.String)} format
     * [may not be null]
     * @param port server port [must be in must be in [1, 65535] range]
     * 
     * @return a new proxy instance 
     */
    public static RTControllerClientProxy create (final String host, final int port)
        throws UnknownHostException
    {
        if (host == null)
            throw new IllegalArgumentException ("null input: host");
        if (port < 0 || port > 0xFFFF)
            throw new IllegalArgumentException ("port must be in [1, 65535] range: " + port);
        
        return new RTControllerClientProxy (host, port);
    }
    
    /**
     * Dispatches a given RPC request and blocks until a response is returned from
     * the server or the RPC layer errors out. Creates a new TCP socket connection
     * on each invocation. 
     * 
     * @param request RPC request [may not be null]
     * @return RPC response [never null]
     * 
     * @throws IOException on any RPC failure
     */
    public Response execute (final Request request)
        throws IOException
    {
        if (request == null)
            throw new IllegalArgumentException ("null input: request");
        
        final Logger log = m_log;
        
        final boolean trace1 = log.atTRACE1 ();
        final String method = "execute";
        
        final Socket socket = new Socket (m_addr, m_port);
        try
        {
            DataOutputStream out = new DataOutputStream (new BufferedOutputStream (socket.getOutputStream (), OUTPUT_IO_BUF_SIZE));
                
            Request.write (request, out);
            out.flush ();
            out = null;
            if (trace1) log.trace1 (method, "request [" + request + "] sent"); 

            DataInputStream in = null;
            try
            {
                in = new DataInputStream (new BufferedInputStream (socket.getInputStream (), INPUT_IO_BUF_SIZE));
                
                if (trace1) log.trace1 (method, "reading response ...");
                final Response response = Response.read (in);
                if (trace1) log.trace1 (method, "response received [" + response + "]");
                
                return response;
            }
            catch (ClassNotFoundException cnfe)
            {
                throw new IOException ("failure deserializing response object: " + cnfe.getMessage ());
            }
            finally
            {
                if (in != null) try { in.close (); } catch (Exception ignore) { ignore.printStackTrace (); }
            }
        }
        finally
        {
            try
            {
                socket.close ();
            }
            catch (Exception ignore) { ignore.printStackTrace (); }
        }
    }
    
    // protected: .............................................................

    // package: ...............................................................
    
    // private: ...............................................................
    
    private RTControllerClientProxy (final String host, final int port)
        throws UnknownHostException
    {
        m_port = port;
        m_addr = InetAddress.getByName (host);
        
        m_log = Logger.getLogger ();
    }
    
    private final int m_port;
    private final InetAddress m_addr;
    private final Logger m_log; // this class is instantiated and used on a single thread
    
    private static final int INPUT_IO_BUF_SIZE = 32 * 1024;
    private static final int OUTPUT_IO_BUF_SIZE = 4 * 1024;

} // end of class
// ----------------------------------------------------------------------------
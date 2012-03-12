/* Copyright (C) 2005 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: CtlProcessor.java,v 1.1 2005/06/21 01:58:01 vlad_r Exp $
 */
package com.vladium.emma.ctl;

import java.io.File;
import java.io.IOException;
import java.net.UnknownHostException;

import com.vladium.logging.Logger;
import com.vladium.util.IProperties;
import com.vladium.util.Property;
import com.vladium.util.asserts.$assert;
import com.vladium.util.exception.Exceptions;
import com.vladium.emma.IAppConstants;
import com.vladium.emma.IAppErrorCodes;
import com.vladium.emma.EMMARuntimeException;
import com.vladium.emma.Processor;
import com.vladium.emma.data.DataFactory;
import com.vladium.emma.data.ICoverageData;
import com.vladium.emma.rt.RT;
import com.vladium.emma.rt.RTControllerClientProxy;
import com.vladium.emma.rt.rpc.Response;

// ----------------------------------------------------------------------------
/**
 * This Processor implementation acts as a remote control bean ("control console")
 * for the instrumentation {@link com.vladium.emma.rt.RT runtime}. It hides all
 * details of our simple socket-based RPC mechanism and data marshalling.<P>
 * 
 * The interface exposed by CtlProcessor is not interactive. However, since
 * each Processor instance is re-run()able, an interactive console app could be
 * easily built on top of one.
 * 
 * @author Vlad Roubtsov, (C) 2005
 */
public
final class CtlProcessor extends Processor
                         implements IAppErrorCodes
{
    // public: ................................................................
    
    // TODO: exception error codes
    
    /**
     * Factory method for this processor.
     * 
     * @return a processor instance [always returns a new instance]
     */
    public static CtlProcessor create ()
    {
        return new CtlProcessor ();
    }

    /**
     * Overrides the RT connection string to be used. The string is in the form
     * "[host][:][port]" where all components are optional. If the colon separator
     * is not present, the string will be tried as a port number first. White space
     * around tokens is ignored.
     * 
     * @param connection [null/empty means to use the default connection settings]
     */
    public synchronized final void setConnectionString (final String connection)
    {
        if ((connection == null) || (connection.length () == 0))
            m_connectionString = null;
        else
            m_connectionString = connection;
    }
    
    /**
     * Sets the control command sequence to be executed. {@link ControlRequest} 
     * entries in 'sequence' are allowed to have some arguments set to 'null' which
     * indicates that they should be filled in from defaults for this tool. 
     * 
     * @param sequence [null is equivalent to an empty array]
     */
    public synchronized final void setCommandSequence (ControlRequest [] sequence)
    {
        if ((sequence == null) || (sequence.length == 0))
            m_commandSequence = ControlRequest.EMPTY_CONTROL_REQUEST_ARRAY;
        else
        {
            sequence = (ControlRequest []) sequence.clone ();
            for (int c = 0; c < sequence.length; ++ c)
            {
                if (sequence [c] == null)
                    throw new IllegalArgumentException ("null input: sequence[" + c + "]");
            }
            
            m_commandSequence = sequence;
        }
    }
    
    // protected: .............................................................

    
    protected void validateState ()
    {
        super.validateState ();
        
        // [m_connectionString can be null]
        // [m_commandSequence can be empty]
    }
    
    
    protected void _run (final IProperties toolProperties)
    {
        final Logger log = m_log;

        final boolean info = m_log.atINFO ();
        final boolean verbose = m_log.atVERBOSE ();
        final boolean trace1 = m_log.atTRACE1 ();
        final String method = "_run";
        
        if (verbose)
        {
            log.verbose (IAppConstants.APP_VERBOSE_BUILD_ID);
            
            // [assertion: m_commandSequence != null]
            log.verbose ("control command sequence:");
            log.verbose ("{");
            for (int c = 0; c < m_commandSequence.length; ++ c)
            {
                log.verbose ("  " + m_commandSequence [c]);
            }
            log.verbose ("}");
        }
        else
        {
            log.info ("processing control command sequence ...");
        }
        
        // bail out early is the sequence is empty:
        if (m_commandSequence.length == 0) return;
        
        // get control connection settings:
        final String host;
        final int port;
        {
            String _host = null, _port = null;
            
            if (m_connectionString != null)
            {
                final String [] tokens = parseConnectionString (m_connectionString);
                
                _host = tokens [0];
                _port = tokens [1];
            }

            // load property defaults as needed: 
                
            if (_host == null)
                _host = toolProperties.getProperty (RT.PROPERTY_RT_CONTROL_HOST,
                                                    RT.DEFAULT_RT_CONTROL_HOST);
               
            if (_port == null)
                _port = toolProperties.getProperty (RT.PROPERTY_RT_CONTROL_PORT,
                                                    Integer.toString (RT.DEFAULT_RT_CONTROL_PORT));
            
            if ($assert.ENABLED)
            {
                $assert.ASSERT (_host != null, "_host = null");
                $assert.ASSERT (_port != null, "_port = null");
            }
                                                    
            try
            {
                port = Integer.parseInt (_port);
                
                if (port < 0 || port > 0xFFFF)
                    throw new IllegalArgumentException ("control port must be in [1, 65535] range: " + port);
            }
            catch (NumberFormatException nfe)
            {
                throw new IllegalArgumentException ("malformed control port number: " + _port);
            }
            
            host = _host;
        }
                
        RuntimeException failure = null;
        try
        {
            if (verbose) log.info ("connecting to [" + host + ":" + port + "] ...");
            final RTControllerClientProxy proxy;
            try
            {
                proxy = RTControllerClientProxy.create (host, port);
            }
            catch (UnknownHostException nhe)
            {
                throw new EMMARuntimeException ("could not connect to [" + host + "]", nhe);
            }
            
            for (int c = 0; c < m_commandSequence.length; ++ c)
            {
                final ControlRequest command = m_commandSequence [c];
                final String prefix = command.getName () + ": ";
                
                command.populateDefaultArgs (toolProperties); // fill in default arguments
                
                if (verbose)
                {
                    log.verbose ("executing [" + command.getName () + "] with arguments:");
                    log.verbose ("{");
                    final String [] args = command.getArgs ();
                    for (int a = 0; a < args.length; ++ a)
                    {
                        log.verbose ("  " + (args [a] != null ? args [a] : "<null>"));
                    }
                    log.verbose ("}");
                }
                else
                {
                    log.info ("executing [" + command + "] ...");
                }
                
                // do the RPC part of command handling:
                
                final long start = info ? System.currentTimeMillis () : 0;
                
                final Response response;
                try
                {
                    response = proxy.execute (command);
                    
                    if (trace1)
                    {
                        final long cend = System.currentTimeMillis ();
                        
                        log.trace1 (method, prefix + "RPC call completed in " + (cend - start) + " ms");
                    }
                }
                catch (IOException ioe)
                {
                    throw new EMMARuntimeException (prefix + "RPC failure while executing [" + command.getName () + "]", ioe);
                }
                
                
                // check for marshalled server errors:
                
                final Object data = response.getData ();
                if (data instanceof Throwable)
                {
                    // server-side error, bail out:
                    
                    throw new EMMARuntimeException (prefix + "server-side failure:", (Throwable) data);
                }
                
                // do the client-local part of command handling:
                
                final String [] args = command.getArgs ();
                switch (response.getID ())
                {
                    case ControlRequest.ID_GET_COVERAGE:
                    {
                        // consistenly with our overall client tool behavior, we
                        // don't do output file locking here:
                         
                        final ICoverageData cdata = (ICoverageData) data;

                        if ((cdata == null) || cdata.isEmpty ())
                        {
                            log.info (prefix + "no output created (no coverage data has been collected by the server VM yet)");
                        }
                        else
                        {
                            final File cdataOutFile = new File (args [0]);
                            final boolean cdataOutMerge = Property.toBoolean (args [1]);
                            
                            // persist cdata:
                            try
                            {
                                if (verbose) log.verbose (prefix + "coverage data contains " + cdata.size () + " entries");
                                
                                final long sstart = info ? System.currentTimeMillis () : 0;
                                DataFactory.persist (cdata, cdataOutFile, cdataOutMerge);
                                
                                if (log.atINFO ())
                                {
                                    final long send = System.currentTimeMillis ();
                                    
                                    log.info (prefix + "local copy of coverage data " + (cdataOutMerge ? "merged into" : "written to") + " [" + cdataOutFile.getAbsolutePath () + "] {in " + (send - sstart) + " ms}");
                                }
                            }
                            catch (IOException ioe)
                            {
                                throw new EMMARuntimeException (OUT_IO_FAILURE, new Object [] {cdataOutFile.getAbsolutePath ()}, ioe);
                            }
                        }
                    }
                    break;
                     
                    case ControlRequest.ID_DUMP_COVERAGE:
                    {
                        log.info (prefix + data);
                    }
                    break;
                    
                    
                    case ControlRequest.ID_RESET_COVERAGE:
                    {
                        log.info (prefix + data);
                    }
                    break;
                     
                } // end of switch
                
                // done:
                
                if (info)
                {
                    final long end = System.currentTimeMillis ();
                    
                    log.info (prefix + "command completed in " + (end - start) + " ms");
                }
            }
            
            log.info ("control command sequence complete");
        }
        catch (SecurityException se)
        {
            failure = new EMMARuntimeException (SECURITY_RESTRICTION, new String [] {IAppConstants.APP_NAME}, se);
        }
        catch (RuntimeException re)
        {
            failure = re;
        }
        finally
        {
            reset ();
        }
        
        if (failure != null)
        {
            if (Exceptions.unexpectedFailure (failure, EXPECTED_FAILURES))
            {
                throw new EMMARuntimeException (UNEXPECTED_FAILURE,
                                                new Object [] {failure.toString (), IAppConstants.APP_BUG_REPORT_LINK},
                                                failure);
            }
            else
                throw failure;
        }
    }


    // package: ...............................................................
    
    // private: ...............................................................
    
    
    private CtlProcessor ()
    {
        m_commandSequence = ControlRequest.EMPTY_CONTROL_REQUEST_ARRAY;
    }
    
    
    private void reset ()
    {
        // nothing to reset yet (no run()-scoped state in this processor)
    }
    
    /**
     * Parses a string in the form "[host][:][port]" where all components are
     * optional but at least one must be present.
     * 
     * @param s [may not be null/empty, not checked by this method]
     */
    private static String [] parseConnectionString (String s)
    {
        s = s.trim ();
        String host = "", port = "";
        
        final int firstColon = s.indexOf (':');
        if (firstColon < 0)
        {
            try
            {
                Integer.parseInt (s);
                port = s;
            }
            catch (NumberFormatException nfe)
            {
                host = s;
            }
        }
        else
        {
            host = s.substring (0, firstColon).trim ();
            port = s.substring (firstColon + 1).trim ();
        }
        
        final String [] result = new String [2];
        result [0] = host.length () != 0 ? host : null;
        result [1] = port.length () != 0 ? port : null;
        
        return result;
    }
    
    
    // caller-settable state [scoped to this runner instance]:

    private String m_connectionString; // never empty, can be null (means to use the default connection settings)
    private ControlRequest [] m_commandSequence; // never null, can be empty

    // internal run()-scoped state:
    
    
    private static final Class [] EXPECTED_FAILURES; // set in <clinit>
    
    static
    {
        EXPECTED_FAILURES = new Class []
        {
            EMMARuntimeException.class,
            IllegalArgumentException.class,
            IllegalStateException.class,
        };
    }

} // end of class
// ----------------------------------------------------------------------------
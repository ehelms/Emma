/* Copyright (C) 2004 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: SocketMutex.java,v 1.1 2005/05/01 12:03:54 vlad_r Exp $
 */
package com.vladium.util;

import java.net.DatagramSocket;
import java.net.SocketException;

import com.vladium.emma.IAppConstants;

// ----------------------------------------------------------------------------
/**
 * A UDP-socket based mutex used for J2SE versions that do not support NIO
 * file locking. 
 * 
 * @author Vlad Roubtsov, (C) 2005
 */
final class SocketMutex extends AbstractFileLock
{
    // public: ................................................................
    
    // IFileLock:
    
    public synchronized void release ()
    {
        if (m_socket != null)
        {
            m_socket.close ();
            m_socket = null;
        }
    }
    
    // AbstractFileLock:
    
    public String toString ()
    {
        return "socket mutex {timeout: " +  m_timeout + ", retries: " + m_retries + ", port: " + m_port + "}"; 
    }
    
    // protected: .............................................................
    
    // AbstractFileLock:

    /**
     * Simulates a machine-global mutex by creating a UDP socket on the specified
     * port. 
     */
    protected void tryAcquire ()
        throws FileLockException
    {
        if (m_socket != null)
            throw new FileLockException (LOCK_ACQUIRED_ALREADY);
        
        final DatagramSocket socket;
        try
        {
            socket = new DatagramSocket (m_port);
        }
        catch (SocketException se)
        {
            throw new FileLockException (LOCK_ACQUISITION_FAILURE);
        }
        catch (SecurityException se)
        {
            throw new FileLockException (LOCK_ACQUISITION_SECURITY_FAILURE, new String [] {IAppConstants.APP_NAME}, se);
        }
        
        m_socket = socket;
    }
        
    // package: ...............................................................
    
    SocketMutex (final long timeout, final int retries, final int port)
    {
        super (timeout, retries);
        
        if (port < 0 || port > 0xFFFF)
            throw new IllegalArgumentException ("port must be in [1, 65535] range: " + port); 
                    
        m_port = port;
    }
    
    // private: ...............................................................
    
    private final int m_port;
    private DatagramSocket m_socket;

} // end of class
// ----------------------------------------------------------------------------
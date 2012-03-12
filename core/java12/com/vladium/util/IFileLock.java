/* Copyright (C) 2004 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: IFileLock.java,v 1.1 2005/05/01 12:03:54 vlad_r Exp $
 */
package com.vladium.util;

import java.io.File;

import com.vladium.util.exception.AbstractException;

// ----------------------------------------------------------------------------
/**
 * A simple abstraction for implementing mutual exclusivity across multiple
 * OS processes on the same physical host.<P>
 * 
 * Lifecycle: create() -&gt; (acquire() -&gt; release() -&gt;)* discard<P>  
 * MT-safety: a mutex instance is safe for use by multiple concurrent threads.
 * 
 * @author Vlad Roubtsov, (C) 2005
 */
public
interface IFileLock
{
    // public: ................................................................
    
    // error codes:
    
    /** [none] */
    String LOCK_ACQUIRED_ALREADY                = "LOCK_ACQUIRED_ALREADY";
    
    /** [none] */
    String LOCK_ACQUISITION_FAILURE             = "LOCK_ACQUISITION_FAILURE";
    
    /** [none] */
    String LOCK_ACQUISITION_SECURITY_FAILURE    = "LOCK_ACQUISITION_SECURITY_FAILURE";
    
    /** [none] */
    String LOCK_ACQUISITION_TIMEOUT             = "LOCK_ACQUISITION_TIMEOUT";
    
    /**
     * Attempts to acquire this lock until either the lock is successfully acquired
     * or the maximum time has elapsed or the maximum number of attempts has been
     * exceeded.<P>
     * 
     * This method blocks until it either succeeds or errors out or the calling
     * thread is interrupted. 
     * 
     * @throws FileLockException if this lock has already been acquired.
     * @throws FileLockException if the lock could not be acquired.
     * @throws InterruptedException if the calling thread is interrupted.
     */
    void acquire () throws FileLockException, InterruptedException;
    
    /**
     * Releases this lock. This method may be called multiple times (it has
     * no effect on an unacquired/released lock).
     */
    void release ();
    
    abstract class Factory
    {
        /**
         * Creates a new lock for the canonical filepath represented by 'file'.<P>
         * 
         * NOTE: it is not guaranteed that more than one lock instance can be
         * acquired within the same JVM process at any given moment (e.g., if
         * locking is simulated via sockets), although more than one instance
         * can always be created.
         * 
         * @param file [may not be null]
         * @param portbase [must be in [1, 65280] range]
         * @param timeout [a non-positive value indicates no timeout]
         * @param retries [a non-positive value indicates no retries]
         */
        public static IFileLock create (final File file, final int portbase, final long timeout, final int retries)
        {
            if (file == null)
                throw new IllegalArgumentException ("null input: file");
            if (portbase < 0 || portbase > 0xFFFF - 255)
                throw new IllegalArgumentException ("port must be in [1, 65280] range: " + portbase);
            
            // TODO: the API allows J2SDK 1.4 file locking-based implementation
            // on supporting JVMs (not used at the moment)
            
            final File canonicalFile = Files.canonicalizeFile (file);
            final int portoffset = Math.abs (canonicalFile.hashCode ()) % 255;
            
            return new SocketMutex (timeout, retries, portbase + portoffset);
        }
        
    } // end of nested class
    
    static final class FileLockException extends AbstractException
    {
        /**
         * Constructs an exception with given error message/code and null cause.
         *
         * @param message the detail message [can be null]
         */
        FileLockException (final String message)
        {
            super (message);
        }
        
        /**
         * Constructs an exception with given error message/code and null cause.
         *   
         * @param message the detail message [can be null]
         * @param arguments message format parameters [can be null or empty]
         *
         * @see java.text.MessageFormat
         */
        FileLockException (final String message, final Object [] arguments)
        {
            super (message, arguments);
        }
        
        /**
         * Constructs an exception with null error message/code and given cause.
         *
         * @param cause the cause [nested exception] [can be null]
         */
        FileLockException (final Throwable cause)
        {
            super (cause);
        }
        
        /**
         * Constructs an exception with given error message/code and given cause.
         *
         * @param message the detail message [can be null]
         * @param cause the cause [nested exception] [can be null]
         */
        FileLockException (final String message, final Throwable cause)
        {
            super (message, cause);
        }
        
        /**
         * Constructs an exception with given error message/code and given cause.
         *
         * @param message the detail message [can be null]
         * @param arguments message format parameters [can be null or empty]
         * @param cause the cause [nested exception] [can be null]
         *
         * @see java.text.MessageFormat
         */
        FileLockException (final String message, final Object [] arguments, final Throwable cause)
        {
            super (message, arguments, cause);
        }
        
    } // end of nested class

} // end of interface
// ----------------------------------------------------------------------------
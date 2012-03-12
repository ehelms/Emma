/* Copyright (C) 2004 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: AbstractFileLock.java,v 1.1 2005/05/01 12:03:54 vlad_r Exp $
 */
package com.vladium.util;

import java.util.Random;

// ----------------------------------------------------------------------------
/**
 * Base class for all {@link IFileLock} implementations. Implements common
 * retry logic in {@link #acquire()} and delegates actual lock resource
 * acquisition to {@link #tryAcquire()}.
 * 
 * @author Vlad Roubtsov, (C) 2005
 */
abstract class AbstractFileLock implements IFileLock
{
    // public: ................................................................
    
    // Object:
    
    public abstract String toString (); // force toString() overrides
    
    // IFileLock:
    
    public synchronized final void acquire ()
        throws IFileLock.FileLockException, InterruptedException
    {
        try
        {
            tryAcquire ();
            return;
        }
        catch (IFileLock.FileLockException fle)
        {
            final String errorCode = fle.getErrorCode ();
            
            if (LOCK_ACQUISITION_SECURITY_FAILURE.equals (errorCode) ||
                LOCK_ACQUIRED_ALREADY.equals (errorCode))
                throw fle; // error out
            
            if ((m_timeout < 1) || (m_retries < 1))
                throw fle; // no retries requested

            // retry logic implements "exponential backout": retry delays are
            // roughly doubling for each retry (such that the total delay time
            // is approximately m_timeout)
            
            final double D = Math.pow (2, m_retries) - 1.0;
            final long end = System.currentTimeMillis () + m_timeout;
            final Random rand = new Random (end + hashCode ());
             
            for (int r = 0; r < m_retries; ++ r)
            {
                final long delay = Math.max (1, (long) ((m_timeout * Math.pow (2, r)) / D) + rand.nextInt (10) - 5);
                
                try
                {
                    Thread.sleep (delay);
                }
                catch (InterruptedException ie)
                {
                    throw ie; // re-throw
                }
                                
                try
                {
                    tryAcquire ();
                    return;
                }
                catch (IFileLock.FileLockException fle2)
                {
                    final String errorCode2 = fle2.getErrorCode ();
            
                    if (LOCK_ACQUISITION_SECURITY_FAILURE.equals (errorCode2) ||
                        LOCK_ACQUIRED_ALREADY.equals (errorCode2))
                        throw fle; // error out
                }
                
                if (System.currentTimeMillis () >= end)
                    break;
            }
            
            throw new FileLockException (LOCK_ACQUISITION_TIMEOUT);
        }
    }
    
    // protected: .............................................................

    protected AbstractFileLock (final long timeout, final int retries)
    {
        m_timeout = timeout;
        m_retries = retries;
    }
    
    /**
     * Called by {@link #acquire()} in an attempt to acquire this lock once.
     * This method does not block and throws an IFileLock.FileLockException
     * on any failure.<P>
     * 
     * NOTE: failures with error codes {@link IFileLock#LOCK_ACQUIRED_ALREADY}
     * and {@link IFileLock#LOCK_ACQUISITION_SECURITY_FAILURE} abort any further
     * retries.
     * 
     * @throws IFileLock.FileLockException
     */
    protected abstract void tryAcquire ()
        throws IFileLock.FileLockException;

    /*
     * Help avoid resource leakage.
     */
    protected final void finalize ()
    {
        release ();
    }
    

    protected final long m_timeout;
    protected final int m_retries;
    
    // package: ...............................................................
    
    // private: ...............................................................

} // end of class
// ----------------------------------------------------------------------------
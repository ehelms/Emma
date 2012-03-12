/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: RTCoverageDataPersister.java,v 1.4 2005/06/21 02:39:36 vlad_r Exp $
 */
package com.vladium.emma.rt;

import java.io.File;

import com.vladium.emma.IAppConstants;
import com.vladium.emma.data.DataFactory;
import com.vladium.emma.data.ICoverageData;
import com.vladium.logging.ILogLevels;
import com.vladium.logging.Logger;
import com.vladium.util.IFileLock;
import com.vladium.util.exception.AbstractException;

// ----------------------------------------------------------------------------
/**
 * A static helper API for coverage data I/O used by {@link com.vladium.emma.rt.RT},
 * {@link com.vladium.emma.rt.RTExitHook}, and {@link com.vladium.emma.rt.RTController}.
 * 
 * @author Vlad Roubtsov, (C) 2004
 */
abstract
class RTCoverageDataPersister
{
    // public: ................................................................
    
    // protected: .............................................................

    // package: ...............................................................
    
    /*
     * Stateless package-private method shared by RT, RTExitHook, and RTController
     * for coverage data persistence.
     * 
     * This method was moved out of RT class after build 4120 in order to decrease
     * classloading dependency set for RTExitHook (FR SF978671).
     */
    static void dumpCoverageData (final ICoverageData cdata, final boolean useSnapshot,
                                  final File outFile, final boolean merge, final IFileLock lock)
    {
        try
        {
            if (cdata != null)
            {
                // use method-scoped loggers everywhere in RT:
                final Logger log = Logger.getLogger ();
                final boolean info = log.atINFO ();
                final boolean trace1 = log.atTRACE1 ();
                
                boolean dump = true;
                
                final String filePath = (info || trace1) ? outFile.getAbsolutePath () : null;
                
                final long start = info ? System.currentTimeMillis () : 0;
                {
                    final ICoverageData cdataView = useSnapshot ? cdata.shallowCopy () : cdata;
                    
                    synchronized (Object.class) // fake a JVM-global critical section when multilply loaded RT's write to the same file
                    {
                        try
                        {
                            // acquire a host-global file lock:
                            if (lock != null) 
                            {
                                if (trace1) log.trace1 ("dumpCoverageData", "locking coverage output file [" + filePath + "] using " + lock + " ...");
                                else if (info) log.info ("locking coverage output file [" + filePath + "] ...");
                                
                                try
                                {
                                    dump = false;
                                    lock.acquire ();
                                    dump = true;
                                }
                                catch (AbstractException ae)
                                {
                                    log.log (ILogLevels.SEVERE, "lock for coverage data file [" + filePath + "] could not be acquired: " + lock, ae);
                                }
                            }
                            
                            // dump coverage data:
                            if (dump)
                            {
                                DataFactory.persist (cdataView, outFile, merge);
                            }
                        }
                        finally
                        {
                            // release the host-global file lock:
                            if (lock != null)
                            {
                                lock.release ();
                                if (trace1 && dump) log.trace1 ("dumpCoverageData", "output file unlocked");
                            }
                        }
                    }
                }
                
                if (dump)
                {
                    if (info)
                    {
                        final long end = System.currentTimeMillis ();
                        
                        log.info ("runtime coverage data " + (merge ? "merged into" : "written to") + " [" + filePath + "] {in " + (end - start) + " ms}");
                    }
                }
                else
                {
                    log.log (ILogLevels.SEVERE, "coverage data dump aborted", true);
                }
            }
        }
        catch (Throwable t)
        {
            // log
            t.printStackTrace ();
            
            // TODO: do better chaining in JRE 1.4+
            throw new RuntimeException (IAppConstants.APP_NAME + " failed to dump coverage data: " + t.toString ());
        }
    }
    
    // private: ...............................................................

} // end of class
// ----------------------------------------------------------------------------
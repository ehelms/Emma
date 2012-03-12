/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: RT.java,v 1.6 2006/01/28 22:55:13 vlad_r Exp $
 */
package com.vladium.emma.rt;

import java.io.File;

import com.vladium.logging.Logger;
import com.vladium.util.IFileLock;
import com.vladium.util.IProperties;
import com.vladium.util.Property;
import com.vladium.util.exit.ExitHookManager;
import com.vladium.emma.IAppConstants;
import com.vladium.emma.EMMAProperties;
import com.vladium.emma.data.ICoverageData;
import com.vladium.emma.data.DataFactory;

// ----------------------------------------------------------------------------
/**
 * RT class is the hub through which instrumented bytecode communicates with
 * our runtime. A typical sequence of events for an instrumented class is:
 * <ol>
 *   <li>on first class use (which is usually the static initializer but can be
 *       any other method in some rare cases) it creates its basic block coverage
 *       map array;</li>
 *   <li>the class then registers its basic block coverage map array with RT via
 *       {@link #r(boolean[][], String, long)};</li>
 *   <li>the class' bytecode executes and modifies entries in the basic block
 *       coverage map;</li>
 *   <li>all of the current coverage data is maintained in the field s_cdata
 *       accessible via {@link #getCoverageData ()}.</li>
 * </ol>
 * 
 * This class is meant to be a global singleton. Obviously, this is not true in
 * the presence of multiple classloading. In some cases, if RT is multiply loaded
 * it will function fine. However, if a runtime {@link com.vladium.emma.rt.RTController controller}
 * is employed (default), multiple RT instances will conflict on the same controller
 * TCP port.
 * 
 * @see com.vladium.emma.rt.RTController
 * @see com.vladium.emma.rt.RTControllerClientProxy
 * 
 * @author Vlad Roubtsov, (C) 2003
 */
public
abstract class RT implements IAppConstants
{
    // public: ................................................................
    
    // properties and defaults for coverage output file locking:
    
    public static final String PROPERTY_RT_FILELOCK_FLAG        = "rt.filelock";
    
    public static final String PREFIX_RT_FILELOCK               = "rt.filelock.";
    public static final String PROPERTY_RT_FILELOCK_PORTBASE    = PREFIX_RT_FILELOCK + "portbase";
    public static final String PROPERTY_RT_FILELOCK_MAX_TIME    = PREFIX_RT_FILELOCK + "maxtime";
    public static final String PROPERTY_RT_FILELOCK_RETRIES     = PREFIX_RT_FILELOCK + "retries";
    public static final int DEFAULT_RT_FILELOCK_PORTBASE        = 59141;
    public static final long DEFAULT_RT_FILELOCK_MAX_TIME       = 120 * 1000; // in milliseconds
    public static final int DEFAULT_RT_FILELOCK_RETRIES         = 11;

    // properties and defaults for RTController:
    
    public static final String PROPERTY_RT_CONTROL_FLAG         = "rt.control";
    
    public static final String PREFIX_RT_CONTROL                = "rt.control.";
    public static final String PROPERTY_RT_CONTROL_HOST         = PREFIX_RT_CONTROL + "host";
    public static final String PROPERTY_RT_CONTROL_PORT         = PREFIX_RT_CONTROL + "port";
    public static final String DEFAULT_RT_CONTROL_HOST          = "localhost";
    public static final int DEFAULT_RT_CONTROL_PORT             = 47653;
    
    
    /**
     * This method is internal to our framework and should not be called by external
     * tools. 
     */
    public static synchronized void reset (final RTSettings.SetActions actions)
    {
        boolean [/* if current!=null, cleanup and set to null; if current==null, create new */] steps;
        
        // if requested, reload the app properties [to accomodate thread context classloader rearrangements]:
        
        steps = RTSettings.SetActions.mapSetAction (actions.m_appPropertiesAction);
        
        if (steps [0]) // null out the field?
        {
            s_appProperties = null;
        }
        
        if (steps [1] && (s_appProperties == null)) // set the field to new value?
        {
            // [avoid the call context tricks at runtime in case security causes problems,
            // use an explicit caller parameter for getAppProperties()]
            
            ClassLoader loader = RT.class.getClassLoader ();
            if (loader == null) loader = ClassLoader.getSystemClassLoader (); 
            
            IProperties appProperties = null;
            try
            {
                appProperties = EMMAProperties.getAppProperties (loader);
                
                if ((appProperties == null) || appProperties.isEmpty ())
                {
                    System.err.println (IAppConstants.APP_NAME + ": could not read any application properties");
                }
            }
            catch (Error e)
            {
                throw e; // re-throw
            }
            catch (Throwable t)
            {
                // TODO: handle better
                t.printStackTrace (System.err);
            }
            
            s_appProperties = appProperties;
        }


        // if requested, create and set up new coverage data:
        
        steps = RTSettings.SetActions.mapSetAction (actions.m_coverageDataAction);
        
        if (steps [0]) // null out the field?
        {
            s_cdata = null;
        }
        
        if (steps [1] && (s_cdata == null)) // set the field to new value?
        {
            s_cdata = DataFactory.newCoverageData ();
            
           // use method-scoped loggers in RT:
            final Logger log = Logger.getLogger ();
            if (log.atINFO ())
            {
                log.info ("collecting runtime coverage data ...");
            }
        }
        

        // if requested, create and set up a new exit hook: 
        
        if (EXIT_HOOK_MANAGER != null)
        {
            steps = RTSettings.SetActions.mapSetAction (actions.m_exitHookAction);
        
            if (steps [0] && (s_exitHook != null)) // null out the field?
            {
                // note: no attempt is made to execute the existing hook, so its
                // coverage data may be simply discarded
            
                EXIT_HOOK_MANAGER.removeExitHook (s_exitHook);
                s_exitHook = null;
            }
            
            if (steps [1] && (s_exitHook == null)) // set the field to new value?
            {
                final SecurityManager sm = System.getSecurityManager ();
                if (sm != null)
                {
                    // bail out if the security permissions are too restrictive:
                    
                    try
                    {
                        sm.checkPropertyAccess ("user.dir"); // read access
                        sm.checkPropertyAccess ("file.encoding"); // read access
                    }
                    catch (SecurityException se)
                    {
                        throw new Error (IAppConstants.APP_NAME + ": current security permissions are too restrictive for file I/O, aborting");
                    }
                }
                
                // configure the new exit hook with the currently active coverage data and dump file settings:
                final File outFile = getCoverageOutFile ();
                final Runnable exitHook = new RTExitHook (RT.class, s_cdata, outFile, getCoverageOutMerge (), getCoverageOutFileLock (outFile));

                // FR SF978671: fault all classes that we might need to do coverage
                // data dumping (this forces classdefs to be loaded into classloader
                // class cache and allows output file writing to succeed even if
                // the RT classloader is some component loader (e.g, in a J2EE container)
                // that gets invalidated by the time the exit hook thread is run:
                
                RTExitHook.createClassLoaderClosure ();
                
                if (EXIT_HOOK_MANAGER.addExitHook (exitHook))
                {
                    s_exitHook = exitHook;
                }
                // else TODO: log/warn
            }
        }
        
        
        // if requested, shut down the controller:
        
        steps = RTSettings.SetActions.mapSetAction (actions.m_controllerAction);
        
        if (steps [0] && (s_controller != null)) // null out the field?
        {
            s_controller.shutdown (); // this can block
            s_controller = null;
        }
        
        if (steps [1] && (s_controller == null)) // set the field to new value?
        {
            final RTController controller = getRTController ();
            if (controller != null)
            {
                Error failure = null;
                Throwable cause = null;
                try
                {
                    controller.start ();
                    s_controller = controller;
                }
                catch (SecurityException se)
                {
                    cause = se;
                    failure = new Error (IAppConstants.APP_NAME + ": current security permissions are too restrictive for socket I/O, aborting");
                }
                catch (Exception e)
                {
                    cause = e;
                    failure = new Error (IAppConstants.APP_NAME + ": runtime controller could not be started, aborting");
                }
                
                if (failure != null)
                {
                    cause.printStackTrace (System.err);
                    
                    // disable the exit hook since we are about to ask the VM to terminate with no real coverage collected:
                    
                    if (EXIT_HOOK_MANAGER != null)
                    {
                        if (s_exitHook != null)
                        {
                            EXIT_HOOK_MANAGER.removeExitHook (s_exitHook);
                            s_exitHook = null;
                        }
                    }
                    
                    throw failure;
                }
            }
        }
    }
    
    /**
     * This method is what the instrumented bytecode hooks into in order to register
     * itself with our runtime (this happens only once per class lifetime in the
     * JVM, usually in the clinit method).
     * 
     * @param coverage basic block coverage map created by an instrumented class [not null]
     * @param classVMName class name (JVM format), used as the key of this coverage entry 
     * @param stamp class bytecode signature [used to detect coverage data/metadata inconsistencies]
     */
    public static void r (final boolean [][] coverage, final String classVMName, final long stamp)
    {
        // note that we use class names, not the actual Class objects, as the keys here. This
        // is not the best possible solution because it is not capable of supporting
        // multiply (re)loaded classes within the same app, but the rest of the toolkit
        // isn't designed to support this anyway. Furthermore, this does not interfere
        // with class unloading.

        final ICoverageData cdata = getCoverageData (); // need to use accessor for JMM reasons

        // ['cdata' can be null if a previous call to dumpCoverageData() disabled data collection]
        
        if (cdata != null)
        {
            synchronized (cdata.lock ())
            {
                // TODO: could something useful be communicated back to the class
                // by returning something here [e.g., unique class ID (solves the
                // issues of class name collisions and class reloading) or RT.class
                // (to prevent RT reloading)]
                
                cdata.addClass (coverage, classVMName, stamp);
            }
        }
    }

    /**
     * This synchronized getter is used by the run processor to get a handle
     * to the same coverage data as being used by the runtime.
     * 
     * @return current coverage data handle [can be null depending on previous calls to reset()]
     */
    public static synchronized ICoverageData getCoverageData ()
    {
        return s_cdata;
    }
    
    /**
     * This synchronized getter is used by the run processor to get a handle
     * to the same application properties as being used by the runtime.
     * 
     * @return current application properties [can be null depending on previous calls to reset() and/or success of loading the properties]
     */
    public static synchronized IProperties getAppProperties ()
    {
        return s_appProperties;
    }
    
    /**
     * Public API for forcing coverage data dump.
     * 
     * @param outFile output file handle [if null, the default as determined by app properties will be used]
     * @param merge output merge flag
     * @param stopDataCollection [if true, no more coverage data will be collected and this method
     * will no-op on subsequent invocations] 
     * 
     * @deprecated use {@link com.vladium.emma.ctl.CtlProcessor} instead
     */
    public static synchronized void dumpCoverageData (File outFile, final boolean merge, final boolean stopDataCollection)
    {
        outFile = outFile != null ? outFile : getCoverageOutFile ();
        
        ICoverageData cdata = s_cdata; // no need to use accessor
        if (stopDataCollection) s_cdata = null; // TODO: log this NOTE: this does not really stop data collection, merely prevents new class registration
        
        RTCoverageDataPersister.dumpCoverageData (cdata, ! stopDataCollection, outFile, merge, getCoverageOutFileLock (outFile));
    }
    
    /**
     * Equivalent to <code>dumpCoverageData(outFile, app_default_for_merge_flag, stopDataCollection)</code>
     * 
     * @deprecated use {@link com.vladium.emma.ctl.CtlProcessor} instead
     */
    public static synchronized void dumpCoverageData (File outFile, final boolean stopDataCollection)
    {
        outFile = outFile != null ? outFile : getCoverageOutFile ();
        
        ICoverageData cdata = s_cdata; // no need to use accessor
        if (stopDataCollection) s_cdata = null; // TODO: log this NOTE: this does not really stop data collection, merely prevents new class registration
        
        RTCoverageDataPersister.dumpCoverageData (cdata, ! stopDataCollection, outFile, getCoverageOutMerge (), getCoverageOutFileLock (outFile));
    }
    
    // protected: .............................................................

    // package: ...............................................................
    
    /*
     * A safe (never fails) method for figuring out the coverage data filename
     * based on the known app properties and their defaults.
     */
    static File getCoverageOutFile ()
    {
        final IProperties appProperties = getAppProperties (); // sync accessor
        if (appProperties != null)
        {
            final String property = appProperties.getProperty (EMMAProperties.PROPERTY_COVERAGE_DATA_OUT_FILE,
                                                               EMMAProperties.DEFAULT_COVERAGE_DATA_OUT_FILE);
            return new File (property);
        }
        
        return new File (EMMAProperties.DEFAULT_COVERAGE_DATA_OUT_FILE); 
    }
    
    /*
     * A safe (never fails) method for figuring out the coverage output merge flag
     * based on the known app properties and their defaults.
     */
    static boolean getCoverageOutMerge ()
    {
        final IProperties appProperties = getAppProperties (); // sync accessor
        if (appProperties != null)
        {
            // [Boolean.toString (boolean) is J2SDK 1.4+]
            
            final String property = appProperties.getProperty (EMMAProperties.PROPERTY_COVERAGE_DATA_OUT_MERGE,
                                                               EMMAProperties.DEFAULT_COVERAGE_DATA_OUT_MERGE.toString ());
            return Property.toBoolean (property);
        }
        
        return EMMAProperties.DEFAULT_COVERAGE_DATA_OUT_MERGE.booleanValue ();
    }
    
    /**
     * A safe (never fails) method for figuring out the coverage output file lock setup
     * based on the known app properties and their defaults.
     * 
     * @return [null indicates no file locking should be attempted]
     */
    static IFileLock getCoverageOutFileLock (final File outFile)
    {
        boolean enableLocking = true;
        
        final IProperties appProperties = getAppProperties (); // sync accessor
        if (appProperties != null)
        {
            final String property = appProperties.getProperty (PROPERTY_RT_FILELOCK_FLAG, "true");
            enableLocking = Property.toBoolean (property);
        }
        
        if (enableLocking)
        {
            long timeout = DEFAULT_RT_FILELOCK_MAX_TIME;
            int retries = DEFAULT_RT_FILELOCK_RETRIES;
            int portbase = DEFAULT_RT_FILELOCK_PORTBASE;
            
            if (appProperties != null)
            {
                String property = appProperties.getProperty (PROPERTY_RT_FILELOCK_MAX_TIME);
                if (property != null)
                {
                    try
                    {
                        timeout = Long.parseLong (property);
                    }
                    catch (NumberFormatException ignore)
                    {
                        System.err.println ("ignoring malformed [" + PROPERTY_RT_FILELOCK_MAX_TIME + "] value: " + property);
                    }
                }
                
                property = appProperties.getProperty (PROPERTY_RT_FILELOCK_RETRIES);
                if (property != null)
                {
                    try
                    {
                        retries = Integer.parseInt (property);
                    }
                    catch (NumberFormatException ignore)
                    {
                        System.err.println ("ignoring malformed [" + PROPERTY_RT_FILELOCK_RETRIES + "] value: " + property);
                    }
                }
                
                property = appProperties.getProperty (PROPERTY_RT_FILELOCK_PORTBASE);
                if (property != null)
                {
                    try
                    {
                        portbase = Integer.parseInt (property);
                    }
                    catch (NumberFormatException ignore)
                    {
                        System.err.println ("ignoring malformed [" + PROPERTY_RT_FILELOCK_PORTBASE + "] value: " + property);
                    }
                }
            }
            
            return IFileLock.Factory.create (outFile, portbase, timeout, retries);
        }
        
        return null; // disable locking
    }

    // private: ...............................................................
    
    
    private RT () {} // prevent subclassing 
       
    /**
     * A safe (never fails) method for creating the runtime controller
     * based on the known app properties and their defaults.
     * 
     * @return [null indicates no controller should be configured]
     */
    private static RTController getRTController ()
    {
        boolean enableController = true;
        
        final IProperties appProperties = getAppProperties (); // sync accessor
        if (appProperties != null)
        {
            final String property = appProperties.getProperty (PROPERTY_RT_CONTROL_FLAG, "false");
            enableController = Property.toBoolean (property);
        }
        
        if (enableController)
        {
            int port = DEFAULT_RT_CONTROL_PORT;
            
            if (appProperties != null)
            {
                final String property = appProperties.getProperty (PROPERTY_RT_CONTROL_PORT);
                if (property != null)
                {
                    try
                    {
                        port = Integer.parseInt (property);
                    }
                    catch (NumberFormatException ignore)
                    {
                        System.err.println ("ignoring malformed [" + PROPERTY_RT_CONTROL_PORT + "] value: " + property);
                    }
                }
            }
            
            return new RTController (port);
        }
        
        return null; // disable runtime control
    }
    
        
    private static ICoverageData s_cdata;
    private static RTController s_controller;
    private static Runnable s_exitHook;
    private static IProperties s_appProperties; // TODO: this is better off as java.util.Properties

    private static final ExitHookManager EXIT_HOOK_MANAGER; // set in <clinit>
    
    private static final boolean DEBUG = false;
    
    static
    {
        if (DEBUG) System.out.println ("RT[" + System.identityHashCode (RT.class) + "]::<clinit>: loaded by " + RT.class.getClassLoader ());
        
        ExitHookManager temp = null;
        try
        {
            temp = ExitHookManager.getSingleton ();
        }
        catch (Throwable t)
        {
            // TODO: handle better
            t.printStackTrace (System.err);
        }
        EXIT_HOOK_MANAGER = temp;

        
        if (RTSettings.isStandaloneMode ())
        {
            if (DEBUG) System.out.println ("RT::<clinit>: STANDALONE MODE");
            
            // load app props, create coverage data, register an exit hook, launch the rt controller:
            reset (new RTSettings.SetActions (RTSettings.FIELD_NEW, RTSettings.FIELD_NEW, RTSettings.FIELD_NEW, RTSettings.FIELD_NEW));            
        }
        else
        {
            // load app props only (the run processor takes care of coverage data and runtime controller):
            reset (new RTSettings.SetActions (RTSettings.FIELD_NEW, RTSettings.FIELD_NULL, RTSettings.FIELD_NULL, RTSettings.FIELD_NULL));
        }
    }

} // end of class
// ----------------------------------------------------------------------------
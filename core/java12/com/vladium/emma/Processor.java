/* Copyright (C) 2004 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: Processor.java,v 1.3 2005/05/08 18:41:39 vlad_r Exp $
 */
package com.vladium.emma;

import java.util.Properties;

import com.vladium.logging.Logger;
import com.vladium.util.IProperties;
import com.vladium.util.asserts.$assert;

// ----------------------------------------------------------------------------
/**
 * A Processor is a bean-like object that encapsulates the core functionality
 * of a "tool" in our tool set. All our command line tools and ANT tasks are mere
 * front-end shells that accept user input before passing it on to an instance
 * of some core processor. Processors are designed for programmatic integration
 * with new/external tools.<P>
 * 
 * Processor lifecycle model is as follows:
 * <ol>
 *     <li>an instance is acquired using <code>create()</code> factory method
 *         specific to each concrete Processor class;</li>
 *     <li>the instance is configured using various <code>set*()</code> methods.
 *         Note that Processor attributes vary regarding whether they are required
 *         or optional (have defaults);</li>
 *     <li>the instance is executed via {@link #run()}.</li>
 * </ol>
 * A Processor instance is re-<code>run()</code>able (note that all state set via set
 * <code>set*()</code> methods is retained through each run).<P>
 * 
 * MT-safety: a Processor instance is safe for concurrent access from multiple
 * concurrent threads (all public Processor methods are synchronized). 
 * 
 * @author Vlad Roubtsov, (C) 2004
 */
public
abstract class Processor
{
    // public: ................................................................

    /**
     * Executes this Processor's unit of work as the following sequence:
     * <ol>
     *     <li>calls {@link #validateState()} to ensure all required attributes
     *         have been set;</li>
     *     <li>creates Processor-specific {@link IProperties properties} by combining
     *         {@link EMMAProperties#getAppProperties() app-global} properties with
     *         property overrides for this Processor instance;</li>
     *     <li>pushes a new {@link Logger} on the thread-local stack and sets it
     *         as {@link #m_log} field;</li>
     *     <li>calls {@link #_run(IProperties)} with Processor-specific properties;</li>
     *     <li>pops the thread-local Logger stack and clears {@link #m_log}.</li>
     * </ol>
     */
    public synchronized void run ()
    {
        validateState ();
        
        // load tool properties:
        final IProperties toolProperties;
        {
            final IProperties appProperties = EMMAProperties.getAppProperties ();
            
            toolProperties = IProperties.Factory.combine (m_propertyOverrides, appProperties);
        }
        if ($assert.ENABLED) $assert.ASSERT (toolProperties != null, "toolProperties is null"); // can be empty, though

        final Logger current = Logger.getLogger ();
        final Logger log = AppLoggers.create (m_appName, toolProperties, current);
        
        if (log.atTRACE1 ())
        {
            log.trace1 ("run", "complete tool properties:");
            toolProperties.list (log.getWriter ());
        }
        
        try
        {
            Logger.push (log);
            m_log = log;
        
            _run (toolProperties);
        }
        finally
        {
            if (m_log != null)
            {
                Logger.pop (m_log);
                m_log = null;
            }
        }
    }

    /**
     * Each processor traces its execution via logging calls to {@link #m_log}.
     * Trace output is prefixed with a string that can be customized via this
     * setter.
     * 
     * @param appName [null means no prefix in tracing]
     */
    public synchronized final void setAppName (final String appName)
    {
        m_appName = appName;
    }
    
    /**
     * Adds optional property overrides for this Processor instance.
     * 
     * @param overrides [may be null (unsets the previous overrides)]
     */
    public synchronized final void setPropertyOverrides (final Properties overrides)
    {
        m_propertyOverrides = EMMAProperties.wrap (overrides);
    }
    
    /**
     * Adds optional property overrides for this Processor instance. This method
     * accepts our custom {@link IProperties} class and is used by stock tools.
     * External tool integration can use {@link #setPropertyOverrides(Properties)},
     * which provides equivalent functionality.
     * 
     * @param overrides [may be null (unsets the previous overrides)]
     */
    public synchronized final void setPropertyOverrides (final IProperties overrides)
    {
        m_propertyOverrides = overrides;
    }
    
    // protected: .............................................................
    
    
    protected Processor ()
    {
        // not publicly instantiable
    }

    /**
     * This method is implemented by each concrete Processor class to perform
     * tool-specific processing.
     * 
     * @param toolProperties properties incorporating app-global setting and
     * overrides set optionally via {@link #setPropertyOverrides(Properties)} or
     * {@link #setPropertyOverrides(IProperties)} [never null]
     */
    protected abstract void _run (IProperties toolProperties);

    /**
     * Validates attribute settings before each {@link #_run(IProperties)}.<P>
     * 
     * Overridden implementations should chain to <code>super.validateState()</code>.
     * 
     * @throws IllegalStateException on invalid settings state (e.g., missing
     * settings for required attributes)
     */
    protected void validateState ()
    {
        // no Processor state needs validation
        
        // [m_appName allowed to be null]
        // [m_propertyOverrides allowed to be null]
    }
    
    
    protected String m_appName; // used as logging prefix, can be null
    protected IProperties m_propertyOverrides; // user override; can be null/empty for run()
    protected Logger m_log; // not null only within run()

    // package: ...............................................................
    
    // private: ...............................................................

} // end of class
// ----------------------------------------------------------------------------
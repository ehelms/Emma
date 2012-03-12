/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: RTSettings.java,v 1.2 2005/06/21 02:39:36 vlad_r Exp $
 */
package com.vladium.emma.rt;

import com.vladium.util.asserts.$assert;

// ----------------------------------------------------------------------------
/**
 * Conceptually, this class is an extention of class RT. This is a separate class,
 * however, to help load RT in a lazy manner (before RT's clinit executes).
 * 
 * @author Vlad Roubtsov, (C) 2003
 */
public
abstract class RTSettings
{
    // public: ................................................................
    
    /** Set the field only if it is currently null. */
    public static final int FIELD_NEW_IF_NULL       = 0;
    /** Unconditionally set the field to a new value. */
    public static final int FIELD_NEW               = 1;
    /** Unconditionally null out the field. */
    public static final int FIELD_NULL              = 2;
    
    /**
     * This cryptic class holds a set of actions for (re)setting several fields
     * in class RT. See {@link RT#reset(RTSettings.SetActions)}.
     * 
     * @see #mapSetAction(int)  
     */
    public static final class SetActions
    {
        public SetActions (final int appPropertiesAction,
                           final int coverageDataAction,
                           final int exitHookAction,
                           final int controllerAction)
        {
            m_appPropertiesAction = appPropertiesAction;
            m_coverageDataAction = coverageDataAction;
            m_exitHookAction = exitHookAction;
            m_controllerAction = controllerAction;
        }
        
        /**
         * Translates a field set action into a sequence of two elementary steps:
         * <ol>
         *   <li>if the field is not null, clean up the current instace (if needed)</li>
         *   <li>if the field is null, set it to a new instance</li>
         * </ol>
         * 
         * @param action [one of FIELD_*** constants]
         * @return a boolean array of size two indicating whether the corresponding
         * elementary step should be performed or skipped
         */
        public static boolean [] mapSetAction (final int action)
        {
            if ($assert.ENABLED) $assert.ASSERT (action >= FIELD_NEW_IF_NULL && FIELD_NULL <= 3);
            
            return FIELD_SET_STEPS [action];
        }
        
        final int m_appPropertiesAction;
        final int m_coverageDataAction;
        final int m_exitHookAction;
        final int m_controllerAction;
        
        
        private static final boolean [][] FIELD_SET_STEPS; // set in <clinit>
        
        static
        {
            FIELD_SET_STEPS = new boolean [3][];
            
            FIELD_SET_STEPS [FIELD_NEW_IF_NULL] = new boolean [] {false, true};
            FIELD_SET_STEPS [FIELD_NEW]         = new boolean [] {true, true};
            FIELD_SET_STEPS [FIELD_NULL]        = new boolean [] {true, false};
        }
        
    } // end of nested class
    
    
    /**
     * Returns 'true' if RT should assume standalone mode. This is essentially
     * the same as offline instrumentation mode: instrumented bytecode is 
     * running outside of {@link com.vladium.emma.run.RunProcessor run processor}
     * control. 
     */
    public static synchronized boolean isStandaloneMode ()
    {
        return ! s_not_standalone;
    }
    
    /**
     * To speed up RT bootstrapping and avoid redundant static RT initialization,
     * this method could be called with 'false' argument before RT class is loaded.
     */
    public static synchronized void setStandaloneMode (final boolean standalone)
    {
        s_not_standalone = ! standalone;
    }
    
    // protected: .............................................................

    // package: ...............................................................
    
    // private: ...............................................................
    
    
    private RTSettings () {} // prevent subclassing
        
    private static boolean s_not_standalone; // default value must ensure 'true' return from isStandaloneMode()

} // end of class
// ----------------------------------------------------------------------------
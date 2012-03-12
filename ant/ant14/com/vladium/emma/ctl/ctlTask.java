/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: ctlTask.java,v 1.1 2005/06/21 01:53:38 vlad_r Exp $
 */
package com.vladium.emma.ctl;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Task;

import com.vladium.emma.ant.NestedTask;
import com.vladium.emma.ant.SuppressableTask;
import com.vladium.util.IConstants;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2005
 */
public
final class ctlTask extends NestedTask
{
    // public: ................................................................
    
    public static final class commandElement
    {
        public final void setName (final String name)
        {
            if (m_name != null)
                throw (BuildException) newBuildException (m_task.getTaskName ()
                        + ": command name already set", m_task.getLocation()).fillInStackTrace ();
            
            m_name = name;
        }
        
        public final void setArgs (final String args)
        {
            if (m_args != null)
                throw (BuildException) newBuildException (m_task.getTaskName ()
                        + ": command arguments already set", m_task.getLocation()).fillInStackTrace ();
            
            m_args = ctlCommand.tokenize (args, ",", true);
        }
        
        public final void addText (final String cmd)
        {
            if ((cmd != null) && (cmd.trim ().length () > 0))
            {
                if ((m_name != null) || (m_args != null))
                    throw (BuildException) newBuildException (m_task.getTaskName ()
                            + ": command name/arguments already set", m_task.getLocation()).fillInStackTrace ();
                
                final String [] _command = ctlCommand.tokenize (cmd, ",", true);
                if (_command.length > 1)
                {
                    m_name = _command [0];
                    m_args = new String [_command.length - 1];
                    System.arraycopy (_command, 1, m_args, 0, m_args.length);
                }
                else
                {
                    m_name = _command [0];
                    m_args = IConstants.EMPTY_STRING_ARRAY;
                }
            }
        }

        
        commandElement (final Task task)
        {
            if (task == null) throw new IllegalArgumentException ("null input: task");
            
            m_task = task;
        }
        
        final String getName ()
        {
            return m_name;
        }
        
        final String [] getArgs ()
        {
            return m_args;
        }
        
        
        private final Task m_task;
        private String m_name;
        private String [] m_args;
        
    } // end of nested class
    
    
    public ctlTask (final SuppressableTask parent)
    {
        super (parent);
        
        m_commands = new ArrayList ();
    }
    
    public void execute () throws BuildException
    {
        if (isEnabled ())
        {
            final CtlProcessor processor = CtlProcessor.create ();
            
            processor.setConnectionString (m_connectionString);
            processor.setCommandSequence (getCommands ());
            processor.setPropertyOverrides (getTaskSettings ());
            
            processor.run ();
        }
    }
    
    // connection string attribute:
    
    public final void setConnect (final String connectionString)
    {
        m_connectionString = connectionString;
    }
    
    // filter element:
    
    public final commandElement createCommand ()
    {
        final commandElement command = new commandElement (this);
        m_commands.add (command);
        
        return command;
    }
    
    // protected: .............................................................

    // package: ...............................................................
    
    // private: ...............................................................

    private ControlRequest [] getCommands ()
    {
        final List /* ControlRequest */ _result = new ArrayList (m_commands.size ()); 
        
        int r = 0;
        for (Iterator i = m_commands.iterator (); i.hasNext (); ++ r)
        {
            final commandElement command = (commandElement) i.next ();
            
            final String name = command.getName ();
            final String [] args = command.getArgs ();
            
            if (name == null)
            {
                if (args != null)
                    throw (BuildException) newBuildException (getTaskName ()
                            + ": command name must be specified", location).fillInStackTrace ();
                else
                    continue; // skip empty command elements
            }
            
            _result.add (ControlRequest.create (name, args));
        }
        
        final ControlRequest [] result = new ControlRequest [_result.size ()];
        _result.toArray (result);
        
        return result;
    }

    
    private String m_connectionString;
    private List /* commandElement */ m_commands; // never null

} // end of class
// ----------------------------------------------------------------------------
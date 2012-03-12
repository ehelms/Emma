/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: Command.java,v 1.3 2005/06/21 01:56:37 vlad_r Exp $
 */
package com.vladium.emma;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.util.Properties;

import com.vladium.logging.ILogLevels;
import com.vladium.util.ClassLoaderResolver;
import com.vladium.util.IConstants;
import com.vladium.util.Property;
import com.vladium.util.Strings;
import com.vladium.util.XProperties;
import com.vladium.util.args.IOptsParser;

// ----------------------------------------------------------------------------
/**
 * Base class for all our command line tools (Commands). Commands use the following
 * simple pluggability pattern:
 * <ul>
 *  <li>each concrete Command implementation is expected to be in a class
 *      with full Java name <em>prefix</em>.<em>name</em>.<em>name</em><code>Command</code>,
 *      where <em>prefix</em> is {@link com.vladium.emma.IAppConstants#APP_PACKAGE} and
 *      <em>name</em> is the canonical Command name;</li>
 * 
 *  <li>each concrete Command implementation should be a public class with a public
 *      constructor with <code>(String, String[])</code> signature;</li>
 * 
 *  <li>each Command can rely on a usage classpath resource named
 *      <em>prefix</em>.<em>name</em>.<em>name</em><code>_usage.res</code>.</li>
 * </ul>
 * 
 * This pattern allows adding new commands (subclasses) without introducing
 * compile time coupling to this base class and its Factory {@link #create(String, String, String[]) method}.
 * 
 * @author Vlad Roubtsov, (C) 2003
 */
public
abstract class Command
{
    // public: ................................................................
    
    /**
     * Factory method for Commands. See the class javadoc for the pluggability
     * pattern implemented by this method.
     * 
     * @param name canonical command name [may not be null or empty]
     * @param usageName command's usage (as seen on the command line) name [may not be null or empty]
     * @param args command's command line args [null is equivalent to an empty array]
     * 
     * @return a new Command instance ready to be {@link #run() run}.
     * 
     * @throws EMMARuntimeException if a Command with canonical name 'name' could not be instantiated
     */
    public static Command create (final String name, final String usageName, final String [] args)
    {
        if ((name == null) || (name.length () == 0))
            throw new IllegalArgumentException ("null/empty input: name");
        if ((usageName == null) || (usageName.length () == 0))
            throw new IllegalArgumentException ("null/empty input: usageName");
        
        ClassLoader loader;
        try
        {
            loader = ClassLoaderResolver.getClassLoader ();
        }
        catch (Throwable t)
        {
            loader = Command.class.getClassLoader ();
        }
        
        return instantiate (name, usageName, args, loader);
    }

    /**
     * Implemented by subclasses to handle this command's unit of work and command
     * line arguments. This method may not return because it might call
     * {@link #exit(boolean, String, Throwable, int)}.
     */
    public abstract void run ();
    
    // protected: .............................................................

    /**
     * A public version of this constructor is called by the Factory method {@link #create(String, String, String[])}.
     * Subclasses must keep this constructor signature and make it public.
     */
    protected Command (final String usageName, final String [] args)
    {
        m_usageCommandName = usageName;
        m_args = args != null ? (String []) args.clone () : IConstants.EMPTY_STRING_ARRAY;
        
        m_out = new PrintWriter (System.out, true);
    }
    
    /**
     * Implemented by actual Command classes to specify the string that
     * should follow command name prefix when dumping usage. See command classes
     * for examples.
     */
    protected abstract String usageArgsMsg ();

    /**
     * Returns this command's canonical name (e.g, runCommand can be mapped to "emma run"
     * or "emmarun" command line tools but its canonical name is simply "run").
     * Note that canonical names are derived from full Java class names according to
     * the pluggability pattern explained in the class javadoc comment.
     */
    protected final String getCanonicalCommandName ()
    {
        final String clsName = getClass ().getName ();
        final int lastDot = clsName.lastIndexOf ('.');
        
        return clsName.substring (lastDot + 1, clsName.length () - 7); // strip full class name prefix and "Command" suffix
    }

    /**
     * Returns a prefix used for usage-related error messaging. This prefix
     * is derived from the end user command line string for this command
     * (e.g, "emma run" or "emmarun").
     */
    protected final String usageMsgPrefix ()
    {
        return m_usageCommandName + " usage: ";
    }
    
    /**
     * Loads the options parser associated with this command. The associated usage
     * resource is found using the pluggability pattern explained in the class
     * javadoc comment.
     *  
     * @param loader classloader to use for loading the usage resource [may not be null]
     */
    protected final IOptsParser getOptParser (final ClassLoader loader)
    {
        final String canonicalToolName = getCanonicalCommandName ();
        final String usageResourceName = IAppConstants.APP_PACKAGE.replace ('.', '/') + "/" + canonicalToolName + "/" + canonicalToolName + "_usage.res"; 
        
        return IOptsParser.Factory.create (usageResourceName, loader, usageMsgPrefix (), USAGE_OPT_NAMES);
    } 
    
    /*
     * The intended "vertical" inheritance order for all user-specified options:
     *     (1) dedicated command line options,
     *     (2) -D option overrides,
     *     (3) -properties file overrides.
     */
    /**
     * A helper method for handling a number of options common to all Commands.
     * Concrete Command classes should call this method first on every option
     * and handle the option themselves only if this method returns 'false'.
     * 
     * @param opt option to handle [may not be null]
     * @return true if 'opt' was recognized and handled
     */
    protected final boolean processOpt (final IOptsParser.IOpt opt)
    {
        final String on = opt.getCanonicalName ();
        
        if ("exit".equals (on)) // 'exit' should always be first in this else-if chain
        {
            m_exit = getOptionalBooleanOptValue (opt);
            return true;
        }
        else if ("p".equals (on))
        {
            m_propertyFile = new File (opt.getFirstValue ());
            return true;
        }
        else if ("verbose".equals (on))
        {
            setPropertyOverride (AppLoggers.PROPERTY_VERBOSITY_LEVEL, ILogLevels.VERBOSE_STRING);
            return true;
        }
        else if ("quiet".equals (on))
        {
            setPropertyOverride (AppLoggers.PROPERTY_VERBOSITY_LEVEL, ILogLevels.WARNING_STRING);
            return true;
        }
        else if ("silent".equals (on))
        {
            setPropertyOverride (AppLoggers.PROPERTY_VERBOSITY_LEVEL, ILogLevels.SEVERE_STRING);
            return true;
        }
        else if ("debug".equals (on))
        {
            if (opt.getValueCount () == 0)
                setPropertyOverride (AppLoggers.PROPERTY_VERBOSITY_LEVEL, ILogLevels.TRACE1_STRING);
            else
                setPropertyOverride (AppLoggers.PROPERTY_VERBOSITY_LEVEL, opt.getFirstValue ());
            
            return true;
        }
        else if ("debugcls".equals (on))
        {
            setPropertyOverride (AppLoggers.PROPERTY_VERBOSITY_FILTER, Strings.toListForm (Strings.merge (opt.getValues (), COMMA_DELIMITERS, true), ',')); 
            return true;
        }
        
        return false;
    }

    /**
     * This method processes all -D property overrides specified on the command line.
     * To ensure that all -D overrides take precedence over all -properties overrides,
     * this method must be called before {@link #processFilePropertyOverrides()}.
     * 
     * @param parsedopts [may not be null]
     */
    protected final void processCmdPropertyOverrides (final IOptsParser.IOpts parsedopts)
    {
        final IOptsParser.IOpt [] popts = parsedopts.getOpts (EMMAProperties.GENERIC_PROPERTY_OVERRIDE_PREFIX);
        if ((popts != null) && (popts.length != 0))
        {
            final Properties cmdOverrides = new XProperties ();
            
            for (int o = 0; o < popts.length; ++ o)
            {
                final IOptsParser.IOpt opt = popts [o];
                final String on = opt.getName ().substring (opt.getPatternPrefix ().length ());
                
                // TODO: support mergeable prefixed opts?
                
                cmdOverrides.setProperty (on, opt.getFirstValue ());
            }
            
            m_propertyOverrides = Property.combine (m_propertyOverrides, cmdOverrides);
        }
    }
    
    /**
     * This method processes the -properties override if it was specified. It
     * should be called after all individual options have been handled via a loop that
     * calls {@link #processOpt(IOptsParser.IOpt)}, because that will ensure that
     * m_propertyFile has been set (otherwise, this method is a no-op). To
     * ensure that file overrides have lower priority than named property overrides,
     * this method should be called after {@link #processCmdPropertyOverrides(IOptsParser.IOpts)}.<P>
     * 
     * This method calls {@link #exit(boolean, String, Throwable, int)} on any file I/O error.
     *  
     * @return false if the file could not be read due to an IOException
     */
    protected final boolean processFilePropertyOverrides ()
    {
        if (m_propertyFile != null)
        {
            final Properties fileOverrides;
            
            try
            {
                fileOverrides = Property.getPropertiesFromFile (m_propertyFile);
            }
            catch (IOException ioe)
            {
                exit (true, "property override file [" + m_propertyFile.getAbsolutePath () + "] could not be read", ioe, RC_USAGE);
                return false;
            }
            
            m_propertyOverrides = Property.combine (m_propertyOverrides, fileOverrides); 
        }
        
        return true;
    }

    
    /**
     * A helper method to emit usage-related messages before a call to {@link #exit(boolean, String, Throwable, int)}.
     * 
     * @param msg message to precede detailed usage text [if null, no such message is emitted] 
     * @param parser options parser instance used to emit detailed usage text [if null, no text is emitted]  
     * @param level usage text detail level [@see IOptsParser#usage(PrintWriter, int, int)]
     */
    protected final void usageexit (final String msg, final IOptsParser parser, final int level)
    {
        if (msg != null)
        {
            m_out.print (usageMsgPrefix ());
            m_out.println (msg);
        }
        
        if (parser != null)
        {
            m_out.println ();
            m_out.print (usageMsgPrefix ());
            m_out.println (m_usageCommandName + " " + usageArgsMsg () + ",");
            m_out.println ("  where options include:");
            m_out.println ();
            parser.usage (m_out, level, STDOUT_WIDTH);            
        }
        
        m_out.println ();
        exit (true, null, null, RC_USAGE);
    }
    
    /**
     * A helper method to terminate a Command execution with correct -exit semantics.
     * If rc != RC_OK, this message will terminate either by a call to System.exit(rc)
     * or by throwing an exception, depending on {@link #m_exit} setting.   
     * 
     * @param showBuildID flag to indicate that pre-exit message should be prefixed with {@link IAppConstants#APP_USAGE_BUILD_ID}
     * @param msg message to precede exit exception stack traces, if any [ignored if null]
     * @param t exit exception [ignored if null, will be wrapped in an EMMARuntimeException if not an instance of that already] 
     * @param rc one of <code>RC_xxx</code> constants
     */
    protected final void exit (final boolean showBuildID, final String msg, final Throwable t, final int rc)
        throws EMMARuntimeException
    {
        if (showBuildID)
        {
            m_out.println (IAppConstants.APP_USAGE_BUILD_ID);
        }
        
        if (msg != null)
        {
            m_out.print (m_usageCommandName + ": "); m_out.println (msg);
        }

        if (rc != RC_OK)
        {
            // error exit:
            
            //if ((showBuildID) || (msg != null)) m_out.println ();
            
            if (m_exit)
            {
                if (t != null) t.printStackTrace (m_out);
                System.exit (rc);
            }
            else
            {
                if (t instanceof EMMARuntimeException)
                    throw (EMMARuntimeException) t;
                else if (t != null)
                    throw msg != null ? new EMMARuntimeException (msg, t) : new EMMARuntimeException ("unexpected failure: ", t);
            }
        }
        else
        {
            // normal exit: 't' is ignored
            
            if (m_exit)
            {
                System.exit (0);
            }
        }
    }

    
    /**
     * A helper method to convert a command line option to a boolean.
     * 
     * @param opt [may not be null]
     * @return true if the option has no values [e.g., '-f'] or if its first
     * value evaluates to 'true' according to {@link Property#toBoolean(String)}.
     */
    protected static boolean getOptionalBooleanOptValue (final IOptsParser.IOpt opt)
    {
        if (opt.getValueCount () == 0)
            return true;
        else
        {
            final String v = opt.getFirstValue ().toLowerCase ();
         
            return Property.toBoolean (v);
        }
    }
    
    /**
     * A helper method to convert a command line option to a value list
     * 
     * @param opt [may not be null]
     * @param delimiters [@see Strings#mergeAT(String[], String, boolean)]
     * @param processAtFiles [@see Strings#mergeAT(String[], String, boolean)]

     * @throws IOException on any at-file I/O errors
     */
    protected static String [] getListOptValue (final IOptsParser.IOpt opt, final String delimiters, final boolean processAtFiles)
        throws IOException
    {
        return Strings.mergeAT (opt.getValues (), delimiters, processAtFiles);
    }
    

    protected final String m_usageCommandName; // this Command's name as specified by the end user (on the command line)
    protected final String [] m_args; // array of command-line arguments for this Command [never null, can be empty]
    protected final PrintWriter m_out; // stdout writer used by Command code (this is independent from Logger tracing done by Processors by design)
    
    protected File m_propertyFile; // set when '-p'|props|properties' option is processed by processOpt()  
    protected Properties m_propertyOverrides; // set by processCmdPropertyOverrides() [combines the effects of '-p'|props|properties' and '-D' property overrides]
    protected boolean m_exit; // 'true' if '-exit' has been requested on the command line [set by processOpt()] 
    
    protected static final String COMMA_DELIMITERS    = "," + Strings.WHITE_SPACE;
    protected static final String PATH_DELIMITERS     = ",".concat (File.pathSeparator);

    protected static final String [] USAGE_OPT_NAMES = new String [] {"h", "help"};
    protected static final int STDOUT_WIDTH = 80;    
    
    // return codes used with System.exit():
    
    protected static final int RC_OK          = 0;
    protected static final int RC_USAGE       = 1;
    protected static final int RC_UNEXPECTED  = 2;

    // package: ...............................................................
    
    // private: ...............................................................

    /*
     * Lazily instantiates m_propertyOverrides if necessary.
     */
    private void setPropertyOverride (final String key, final String value)
    {
        Properties propertyOverrides = m_propertyOverrides;
        if (propertyOverrides == null)
        {
            m_propertyOverrides = propertyOverrides = new XProperties ();
        }
        
        propertyOverrides.setProperty (key, value);
    }
    
    /*
     * Instantiate a Command according to the pluggability pattern and using 'loader'
     * as the class loader.
     */
    private static Command instantiate (final String name, final String usageName, final String [] args,
                                        final ClassLoader loader)
        throws EMMARuntimeException
    {
        final String clsName = IAppConstants.APP_PACKAGE + "." + name + "." + name + "Command";
        
        try
        {
            final Class cls = Class.forName (clsName, true, loader);
            
            final Constructor constructor = cls.getConstructor (PARAMETER_TYPES);
            final Object _command = constructor.newInstance (new Object [] {usageName, args});
            
            return (Command) _command;
        }
        catch (Exception e)
        {
            throw new EMMARuntimeException (IAppErrorCodes.INVALID_COMMAND_NAME, new String [] {IAppConstants.APP_NAME, name}, e);
        }
        catch (ExceptionInInitializerError eiie)
        {
            throw new EMMARuntimeException (IAppErrorCodes.INVALID_COMMAND_NAME, new String [] {IAppConstants.APP_NAME, name}, eiie);
        }
    }

    
    private static final Class [] PARAMETER_TYPES = new Class [] { String.class, String [].class }; 

} // end of class
// ----------------------------------------------------------------------------
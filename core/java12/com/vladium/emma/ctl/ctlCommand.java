/* Copyright (C) 2005 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: ctlCommand.java,v 1.2 2006/01/15 20:00:09 vlad_r Exp $
 */
package com.vladium.emma.ctl;

import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;

import com.vladium.util.ClassLoaderResolver;
import com.vladium.util.IConstants;
import com.vladium.util.args.IOptsParser;
import com.vladium.emma.Command;
import com.vladium.emma.IAppConstants;
import com.vladium.emma.EMMARuntimeException;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2005
 */
public
final class ctlCommand extends Command
{
    // public: ................................................................

    public ctlCommand (final String usageToolName, final String [] args)
    {
        super (usageToolName, args);
    }

    public synchronized void run ()
    {
        ClassLoader loader;
        try
        {
            loader = ClassLoaderResolver.getClassLoader ();
        }
        catch (Throwable t)
        {
            loader = getClass ().getClassLoader ();
        }
        
        try
        {
            // process 'args':
            {
                final IOptsParser parser = getOptParser (loader);
                final IOptsParser.IOpts parsedopts = parser.parse (m_args);
                
                final int usageRequestLevel = parsedopts.usageRequestLevel ();
                
                // check if usage is requested before checking args parse errors etc:

                if (usageRequestLevel > 0)
                {
                    usageexit (null, parser, usageRequestLevel);
                    return;
                }
                
                final IOptsParser.IOpt [] opts = parsedopts.getOpts ();
                
                if (opts == null) // this means there were args parsing errors
                {
                    parsedopts.error (m_out, STDOUT_WIDTH);
                    usageexit (null, parser, IOptsParser.SHORT_USAGE);
                    return;
                }
                
                // [assertion: args parsed Ok]
                
                // version flag is handled as a special case:
                
                if (parsedopts.hasArg ("v"))
                {
                    usageexit (null, null, usageRequestLevel);
                    return;
                }
                
                // process parsed args:

//                try
//                {
                    for (int o = 0; o < opts.length; ++ o)
                    {
                        final IOptsParser.IOpt opt = opts [o];
                        final String on = opt.getCanonicalName ();
                        
                        if (! processOpt (opt))
                        {
                            if ("c".equals (on))
                            {
                                final String [] commands = opt.getValues ();
                                m_commands = new ControlRequest [commands.length];
                                
                                for (int c = 0; c < commands.length; ++ c)
                                {
                                    final String [] _command = tokenize (commands [c], ",", true);
                                    final String [] args;
                                    if (_command.length > 1)
                                    {
                                        args = new String [_command.length - 1];
                                        System.arraycopy (_command, 1, args, 0, args.length);
                                    }
                                    else
                                    {
                                        args = IConstants.EMPTY_STRING_ARRAY;
                                    }
                                    
                                    m_commands [c] = ControlRequest.create (_command [0], args);
                                }
                            }
                            else if ("a".equals (on))
                            {
                                m_connectionString = opt.getFirstValue ();
                            }
                        }
                    }

                    // process prefixed opts:
                    
                    processCmdPropertyOverrides (parsedopts);
                    
                    // user '-props' file property overrides:
                    
                    if (! processFilePropertyOverrides ()) return;
//                }
//                catch (IOException ioe)
//                {
//                    throw new EMMARuntimeException (IAppErrorCodes.ARGS_IO_FAILURE, ioe);
//                }
                
                // handle cmd line-level defaults:
                {
                }
            }
            
            // run the reporter:
            {
                final CtlProcessor processor = CtlProcessor.create ();
                processor.setAppName (IAppConstants.APP_NAME); // for log prefixing

                processor.setConnectionString (m_connectionString);
                processor.setCommandSequence (m_commands);
                processor.setPropertyOverrides (m_propertyOverrides);
                
                processor.run ();
            }
        }
        catch (EMMARuntimeException yre)
        {
            // TODO: see below
            
            exit (true, yre.getMessage (), yre, RC_UNEXPECTED); // does not return
            return;
        }
        catch (Throwable t)
        {
            // TODO: embed: OS/JVM fingerprint, build #, etc
            // TODO: save stack trace in a file and prompt user to send it to ...
            
            exit (true, "unexpected failure: ", t, RC_UNEXPECTED); // does not return
            return;
        }

        exit (false, null, null, RC_OK);
    }    

    /**
     * A helper method for tokenizing a string based on given delimiters. Unlike
     * the usual StringTokenizer behavior, this does not merge consequitive
     * tokenizers into one.
     * 
     * @param s input string [may not be null]
     * @param delimiters string of delimiters [may not be null]
     * @param trim if 'true', all tokens will be white space-trimmed before
     *        returned
     * @return an array of tokens [nulls returned for missing tokens]
     */
    public static String [] tokenize (final String s, final String delimiters, final boolean trim)
    {
        // BUG_SF1275523: made this method public to avoid known J2SE bugs with access checking during reflection 
         
        final StringTokenizer tokenizer = new StringTokenizer (s, delimiters, true);
        final List _result = new ArrayList (tokenizer.countTokens ());
        
        boolean delimiter = true;
        
        for (int i = 0; tokenizer.hasMoreTokens (); ++ i)
        {
            final String t = tokenizer.nextToken ();
            
            if ((t.length () == 1) && (delimiters.indexOf (t.charAt (0)) >= 0))
            {
                if (delimiter)
                    _result.add (null); // default placeholder
                else
                    delimiter = true;
            }
            else
            {
                delimiter = false;
                _result.add (trim ? t.trim () : t);
            }
        }
        
        final String [] result = new String [_result.size ()];
        _result.toArray (result);
        
        return result;
    }
    
    // protected: .............................................................


    protected String usageArgsMsg ()
    {
        return "[options]";
    }

    // package: ...............................................................
 
    // private: ...............................................................

    
    private String m_connectionString;
    private ControlRequest [] m_commands;
    
} // end of class
// ----------------------------------------------------------------------------
/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: emma.java,v 1.4 2005/06/21 01:54:56 vlad_r Exp $
 */
import com.vladium.emma.IAppConstants;
import com.vladium.emma.Command;
import com.vladium.emma.EMMARuntimeException;

// ----------------------------------------------------------------------------
/**
 * "Umbrella" command line tool (launcher of other command line tools in our
 * suite).
 * 
 * @author Vlad Roubtsov, (C) 2003
 */
public
final class emma
{
    // public: ................................................................
    
    // TODO: set m_out consistently with LoggerInit    
    
    public static void main (final String [] args)
        throws EMMARuntimeException
    {
        // TODO: proper usage, arg validation, etc
        
        if ((args.length == 0) || args [0].startsWith ("-h"))
        {
            System.out.println (USAGE);
            return;
        }
        
        if (args [0].startsWith ("-v"))
        {
            System.out.println ();
            System.out.println (IAppConstants.APP_USAGE_BUILD_ID);
            return;
        }
        
        final String commandName = args [0];
        final String [] commandArgs = new String [args.length - 1];
        System.arraycopy (args, 1, commandArgs, 0, commandArgs.length);
        
        final Command command = Command.create (commandName, IAppConstants.APP_NAME_LC + " " + commandName, commandArgs);
        command.run ();
    }
    
    // protected: .............................................................

    // package: ...............................................................
    
    // private: ...............................................................
    
    
    private static final String EOL = System.getProperty ("line.separator", "\n"); 
    
    private static final String USAGE =
    IAppConstants.APP_NAME_LC + " usage: " + IAppConstants.APP_NAME_LC + " <command> [command options]," + EOL +
    "  where <command> is one of:" + EOL +
    EOL +
    "   run     application runner {same as '" + IAppConstants.APP_NAME_LC + "run' tool};" + EOL +
    "   instr   offline instrumentation processor;" + EOL +
    "   ctl     remote control processor;" + EOL +
    "   merge   offline data file merge processor." + EOL +
    "   report  offline report generator;" + EOL +
    EOL +
    "  {use '<command> -h' to see usage help for a given command}" + EOL +
    EOL +
    IAppConstants.APP_USAGE_BUILD_ID;

} // end of class
// ----------------------------------------------------------------------------
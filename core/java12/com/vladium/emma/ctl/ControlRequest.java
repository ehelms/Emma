
package com.vladium.emma.ctl;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import com.vladium.emma.EMMAProperties;
import com.vladium.emma.EMMARuntimeException;
import com.vladium.emma.rt.rpc.Request;
import com.vladium.util.IProperties;
import com.vladium.util.Property;

// ----------------------------------------------------------------------------
/**
 * ControlRequest represents a runtime control command. Each such object has
 * a numeric ID, a user-friendly name, and an array of String arguments.
 * ControlRequest extends {@link com.vladium.emma.rt.rpc.Request} in order to
 * decouple request creation and argument validation from details of RPC data
 * marshalling.<P>
 * 
 * Null argument values are legal and indicate that this argument slot needs
 * default value processing (either on the client or the server side, depending
 * on the command). 
 * 
 * @author Vlad Roubtsov, (C) 2005
 */
public
final class ControlRequest extends Request
{
    // public: ................................................................

    /**
     * Ping command for internal testing. Arguments:
     * <dl>
     *     <dt>delay</dt><dd>server-side delay (ms) before replying</dd>
     * </dl>
     */
    public static final String COMMAND_TEST_PING                = "test.ping";
    public static final int ID_TEST_PING                        = 0;
    
    /**
     * Request to get (download) runtime coverage data. Arguments:
     * <dl>
     *     <dt>local pathname</dt><dd>optional local pathname to use for
     * persisting downloaded coverage data by the client JVM</dd>
     *     <dt>true|false</dt><dd>optional merge flag</dd>
     *     <dt>true|false</dt><dd>optional flag to indicate whether to disable
     * coverage dump exit hook</dd>
     * </dl>
     */
    public static final String COMMAND_GET_COVERAGE             = "coverage.get";
    public static final int ID_GET_COVERAGE                     = 1;
    
    /**
     * Request to dump runtime coverage data by the server JVM. Arguments:
     * <dl>
     *     <dt>remote pathname</dt><dd>optional remote pathname to use for
     * dumping coverage data by the server JVM</dd>
     *     <dt>true|false</dt><dd>optional merge flag</dd>
     *     <dt>true|false</dt><dd>optional flag to indicate whether to disable
     * coverage dump exit hook</dd>
     * </dl>
     */
    public static final String COMMAND_DUMP_COVERAGE            = "coverage.dump";
    public static final int ID_DUMP_COVERAGE                    = 2;
    
    /**
     * Request to dump runtime coverage data by the server JVM. Arguments: none
     */
    public static final String COMMAND_RESET_COVERAGE           = "coverage.reset";
    public static final int ID_RESET_COVERAGE                   = 3;


    /**
     * Factory method for control requests.
     * 
     * @param name [may not be null]
     * @param args [null is equivalent to an empty array]
     * @return a new ControlRequest instance with specified arguments
     * 
     * @throws EMMARuntimeException if 'name' is not a valid control request
     * @throws EMMARuntimeException if 'args' contains more arguments than allowed
     * for a given control request
     */
    public static ControlRequest create (final String name, final String [] args)
    {
        if (name == null)
            throw new IllegalArgumentException ("null input: name");
        
        final ControlRequestDescriptor descriptor = (ControlRequestDescriptor) ID_MAP.get (name);
        if (descriptor == null)
            // TODO: error code?
            throw new EMMARuntimeException ("unknown control command [" + name + "]");
        
        if ((args != null) && (args.length > descriptor.m_argCount))
            // TODO: error code?
            throw new EMMARuntimeException ("too many arguments for [" + name + "], usage: " + descriptor.usage ());
        
        final String [] args2 = new String [descriptor.m_argCount];
        if (args != null)
            System.arraycopy (args, 0, args2, 0, args.length);
        
        return new ControlRequest (descriptor, args2);
    }
    
    /**
     * @return user-friendly name of this command [never null]
     */
    public final String getName ()
    {
        return m_descriptor.m_name;
    }
    
    // Object:
    
    public String toString ()
    {
        final StringBuffer s = new StringBuffer ();
        
        s.append (getName ());
        s.append (" (");
        
        final String [] args = getArgs ();
        for (int a = 0, aLimit = args.length; a < aLimit; ++ a)
        {
            if (a != 0) s.append (',');
            final String arg = args [a];
            if (arg != null) s.append (arg);
        }
        
        s.append (')');
        
        return s.toString ();
    }
    
    // protected: .............................................................

    // package: ...............................................................

    /**
     * Package-private method used by {@link CtlProcessor} for default argument
     * processing.
     */
    void populateDefaultArgs (final IProperties properties)
    {
        if (properties != null)
        {
            m_descriptor.populateRequestArgs (this, properties);
        }
    }
    
    /*
     * Package-private accessor used by ControlRequestDescriptor.
     */
    String [] getMutableArgs2 ()
    {
        return getMutableArgs ();
    }

    
    static final ControlRequest [] EMPTY_CONTROL_REQUEST_ARRAY = new ControlRequest [0];
    
    // private: ...............................................................
    
    /**
     * A ControlRequestDescriptor object exists for each valid control command and
     * helps process argument setting in a command-specific manner.
     */
    private static abstract class ControlRequestDescriptor
    {
        protected abstract void populateRequestArgs (final ControlRequest request, final IProperties properties);
        protected abstract String usage ();
        
        ControlRequestDescriptor (final String name, final int ID, final int argCount)
        {
            if (name == null)
                throw new IllegalArgumentException ("null input: name");
            
            m_name = name;
            m_ID = ID;
            m_argCount = argCount;
        }
        
        
        final String m_name;
        final int m_ID;
        final int m_argCount;
        
    } // end of nested class
    
    
    private static final class TESTPING extends ControlRequestDescriptor
    {
        // ControlRequestDescriptor:
        
        public void populateRequestArgs (final ControlRequest request, final IProperties properties)
        {
            // [assertion: args != null]
            // [assertion: properties != null]
        }
        
        public String usage ()
        {
            return m_name + ", <delay>";
        }
        
        
        TESTPING (final String name, final int ID, final int argCount)
        {
            super (name, ID, argCount);
        }

    } // end of nested class

    
    private static final class GETCOVERAGE extends ControlRequestDescriptor
    {
        // ControlRequestDescriptor:
        
        public void populateRequestArgs (final ControlRequest request, final IProperties properties)
        {
            // [assertion: args != null]
            // [assertion: properties != null]
            
            final String [] args = request.getMutableArgs2 ();
            
            // some arg entries could be null, indicating that those should
            // be filled in based on default client values in 'properties'
            
            if (args [0] == null)
            {
                args [0] = properties.getProperty (EMMAProperties.PROPERTY_COVERAGE_DATA_OUT_FILE,
                                                   EMMAProperties.DEFAULT_COVERAGE_DATA_OUT_FILE);
            }
            
            if (args [1] == null)
            {
                args [1] = properties.getProperty (EMMAProperties.PROPERTY_COVERAGE_DATA_OUT_MERGE,
                                                   EMMAProperties.DEFAULT_COVERAGE_DATA_OUT_MERGE.toString ());
            }
            else
            {
                args [1] = Property.toBoolean (args [1]) ? "true" : "false";
            }
            
            if (args [2] == null)
            {
                args [2] = "true";
            }
            else
            {
                args [2] = Property.toBoolean (args [2]) ? "true" : "false";
            }
        }
        
        public String usage ()
        {
            return m_name + ", [<local pathname>], [yes|no], [yes|no]";
        }
        
        
        GETCOVERAGE (final String name, final int ID, final int argCount)
        {
            super (name, ID, argCount);
        }
        
    } // end of nested class
    
    
    private static final class DUMPCOVERAGE extends ControlRequestDescriptor
    {
        // ControlRequestDescriptor:
        
        public void populateRequestArgs (final ControlRequest request, final IProperties properties)
        {
            // [assertion: args != null]
            // [assertion: properties != null]

            final String [] args = request.getMutableArgs2 ();
            
            // some arg entries could be null; however this command does
            // nothing about that on the client: if pathname and/or merge flag
            // are not specified here, the defaults will have to come from the server
            // JVM properties
            
            // this approach seems Ok for the case when client and server
            // are running on different platforms
            
            if (args [2] == null)
            {
                args [2] = "true";
            }
            else
            {
                args [2] = Property.toBoolean (args [2]) ? "true" : "false";
            }
        }
        
        public String usage ()
        {
            return m_name + ", [<remote pathname>], [yes|no], [yes|no]";
        }
        
        
        DUMPCOVERAGE (final String name, final int ID, final int argCount)
        {
            super (name, ID, argCount);
        }
        
    } // end of nested class
        

    private static final class RESETCOVERAGE extends ControlRequestDescriptor
    {
        // ControlRequestDescriptor:
        
        public void populateRequestArgs (final ControlRequest request, final IProperties properties)
        {
            // [assertion: args != null]
            // [assertion: properties != null]
        }
        
        public String usage ()
        {
            return m_name;
        }
        
        
        RESETCOVERAGE (final String name, final int ID, final int argCount)
        {
            super (name, ID, argCount);
        }

    } // end of nested class

    
    private ControlRequest (final ControlRequestDescriptor descriptor, final String [] args)
    {
        super (descriptor.m_ID, args);
        
        m_descriptor = descriptor;
    }
    
    
    private final ControlRequestDescriptor m_descriptor;
    
    private static final Map /* name:String->ControlRequestDescriptor */  ID_MAP; // populated in <clinit>
    
    static
    {
        final Map map = new HashMap ();

        if (map.put (COMMAND_TEST_PING, new TESTPING (COMMAND_TEST_PING, ID_TEST_PING, 1)) != null)
            throw new Error ("ID map error");
        
        if (map.put (COMMAND_GET_COVERAGE, new GETCOVERAGE (COMMAND_GET_COVERAGE, ID_GET_COVERAGE, 3)) != null)
            throw new Error ("ID map error");
        
        if (map.put (COMMAND_DUMP_COVERAGE, new DUMPCOVERAGE (COMMAND_DUMP_COVERAGE, ID_DUMP_COVERAGE, 3)) != null)
            throw new Error ("ID map error");
        
        if (map.put (COMMAND_RESET_COVERAGE, new RESETCOVERAGE (COMMAND_RESET_COVERAGE, ID_RESET_COVERAGE, 0)) != null)
            throw new Error ("ID map error");
        
        ID_MAP = Collections.unmodifiableMap (map);
    }
    
} // end of class
// ----------------------------------------------------------------------------
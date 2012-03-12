
package com.vladium.emma.rt.rpc;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

// ----------------------------------------------------------------------------
/**
 * An RPC request object, containing a int ID and an array of String arguments.
 * 
 * @author Vlad Roubtsov, (C) 2005
 */
public
class Request
{
    // public: ................................................................
    
    public Request (final int ID, final String [] args)
    {
        if (args == null)
            throw new IllegalArgumentException ("null input: args");
        
        m_ID = ID;
        m_args = args;
    }
    
    public final int getID ()
    {
        return m_ID;
    }
    
    public final String [] getArgs ()
    {
        return (String []) m_args.clone ();
    }

    // Object:
    
    public String toString ()
    {
        final StringBuffer s = new StringBuffer ();
        
        s.append (m_ID);
        s.append (" (");
        
        for (int a = 0, aLimit = m_args.length; a < aLimit; ++ a)
        {
            if (a != 0) s.append (',');
            final String arg = m_args [a];
            if (arg != null) s.append (arg);
        }
        
        s.append (')');
        
        return s.toString ();
    }

    // custom serialization API:
    
    public static Request read (final DataInput in)
        throws IOException
    {
        final int ID = in.readByte ();
        
        final int argLength = in.readInt ();
        final String [] args = new String [argLength];
        
        for (int a = 0; a < argLength; ++ a)
        {
            final byte argFlag = in.readByte ();
            if (argFlag != 0) args [a] =  in.readUTF ();
        }
        
        return new Request (ID, args);
    }
    
    public static void write (final Request request, final DataOutput out)
        throws IOException
    {
        out.writeByte (request.m_ID);
        
        final String [] args = request.m_args;
        final int argLength = args.length;
        
        out.writeInt (argLength);
        for (int a = 0; a < argLength; ++ a)
        {
            final String arg = args [a];
            if (arg != null)
            {
                out.writeByte (1);
                out.writeUTF (arg);
            }
            else
            {
                out.writeByte (0);
            }
        }
    }
    
    // protected: .............................................................
    
    protected final String [] getMutableArgs ()
    {
        return m_args;
    }

    // package: ...............................................................
    
    // private: ...............................................................
    
    private final int m_ID;
    private final String [] m_args; // never null

} // end of class
// ----------------------------------------------------------------------------
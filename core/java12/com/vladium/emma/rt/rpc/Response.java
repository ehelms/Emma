
package com.vladium.emma.rt.rpc;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;

// ----------------------------------------------------------------------------
/**
 * An RPC response object, containing an int ID (matching its request ID) and
 * a Serializable payload object. 
 * 
 * @author Vlad Roubtsov, (C) 2005
 */
public
class Response
{
    // public: ................................................................
    
    public Response (final int ID, final Serializable data)
    {
        m_ID = ID;
        m_data = data;
    }
    
    public final int getID ()
    {
        return m_ID;
    }
    
    public final Serializable getData ()
    {
        return m_data; // note: no defensive clone
    }

    // custom serialization API (uses Java serialization for the payload):
    
    public static Response read (final DataInputStream in)
        throws IOException, ClassNotFoundException
    {
        final int ID = in.readByte ();
        
        final ObjectInputStream oin = new ObjectInputStream (in);
        final Object data = oin.readObject ();
        
        return new Response (ID, (Serializable) data);
    }
    
    public static void write (final Response response, final DataOutputStream out)
        throws IOException
    {
        out.writeByte (response.m_ID);
        
        final ObjectOutputStream oout = new ObjectOutputStream (out);
        final Serializable data = response.m_data;
        
        oout.writeObject (data);
    }
    
    // protected: .............................................................

    // package: ...............................................................
    
    // private: ...............................................................
    
    private final int m_ID;
    private final Serializable m_data; // could be null

} // end of class
// ----------------------------------------------------------------------------
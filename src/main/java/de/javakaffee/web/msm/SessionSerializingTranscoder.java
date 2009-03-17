/*
 * $Id: $ (c)
 * Copyright 2009 freiheit.com technologies GmbH
 *
 * Created on Mar 17, 2009
 *
 * This file contains unpublished, proprietary trade secret information of
 * freiheit.com technologies GmbH. Use, transcription, duplication and
 * modification are strictly prohibited without prior written consent of
 * freiheit.com technologies GmbH.
 */
package de.javakaffee.web.msm;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import net.spy.memcached.transcoders.SerializingTranscoder;

import org.apache.catalina.Loader;
import org.apache.catalina.Manager;
import org.apache.catalina.session.StandardSession;
import org.apache.catalina.util.CustomObjectInputStream;

/**
 * TODO: DESCRIBE ME<br>
 * Created on: Mar 17, 2009<br>
 * 
 * @author <a href="mailto:martin.grotzke@freiheit.com">Martin Grotzke</a>
 * @version $Id$
 */
public class SessionSerializingTranscoder extends SerializingTranscoder {

    private final Manager _manager;

    public SessionSerializingTranscoder(Manager manager) {
        _manager = manager;
    }

    /* (non-Javadoc)
     * @see net.spy.memcached.transcoders.BaseSerializingTranscoder#serialize(java.lang.Object)
     */
    @Override
    protected byte[] serialize( Object o ) {
        if(o == null) {
            throw new NullPointerException("Can't serialize null");
        }
//        byte[] rv=null;
//        try {
//            ByteArrayOutputStream bos=new ByteArrayOutputStream();
//            ObjectOutputStream os=new ObjectOutputStream(bos);
//            os.writeObject(o);
//            os.close();
//            bos.close();
//            rv=bos.toByteArray();
//        } catch(IOException e) {
//            throw new IllegalArgumentException("Non-serializable object", e);
//        }
//        return rv;
        
        ByteArrayOutputStream bos = null;
        ObjectOutputStream oos = null;
        try {
            bos = new ByteArrayOutputStream();
            oos = new ObjectOutputStream( new BufferedOutputStream( bos ) );
        } catch ( IOException e ) {
            if ( bos != null ) {
                try {
                    bos.close();
                } catch ( IOException f ) {
                }
            }
            if ( oos != null ) {
                try {
                    oos.close();
                } catch ( IOException f ) {
                }
            }
            throw new IllegalArgumentException("Non-serializable object", e);
        }

        try {
            ( (StandardSession) o ).writeObjectData( oos );
            return bos.toByteArray();
        } catch ( IOException e ) {
            throw new IllegalArgumentException("Non-serializable object", e);
        } finally {
            try {
                bos.close();
            } catch ( IOException e ) {
            }
            try {
                oos.close();
            } catch ( IOException e ) {
            }
        }

    }

    /**
     * Get the object represented by the given serialized bytes.
     */
    @Override
    protected Object deserialize( byte[] in ) {
        ByteArrayInputStream bis = null;
        ObjectInputStream ois = null;
        Loader loader = null;
        ClassLoader classLoader = null;
        try {
            bis = new ByteArrayInputStream( in );
            if ( _manager.getContainer() != null )
                loader = _manager.getContainer().getLoader();
            if ( loader != null )
                classLoader = loader.getClassLoader();
            if ( classLoader != null )
                ois = new CustomObjectInputStream( bis, classLoader );
            else
                ois = new ObjectInputStream( bis );
        } catch ( IOException e ) {
            getLogger().warn( "Caught IOException decoding %d bytes of data",
                    in.length, e );
            if ( bis != null ) {
                try {
                    bis.close();
                } catch ( IOException f ) {
                }
                bis = null;
            }
            if ( ois != null ) {
                try {
                    ois.close();
                } catch ( IOException f ) {
                }
                ois = null;
            }
            throw new RuntimeException( "Caught IOException decoding data", e );
        }

        try {
            
            StandardSession session = (StandardSession)_manager.createEmptySession();
            session.readObjectData( ois );
            session.setManager( _manager );
            return session;
        } catch ( ClassNotFoundException e ) {
            getLogger().warn( "Caught CNFE decoding %d bytes of data",
                    in.length, e );
            throw new RuntimeException( "Caught CNFE decoding data", e );
        } catch ( IOException e ) {
            getLogger().warn( "Caught IOException decoding %d bytes of data",
                    in.length, e );
            throw new RuntimeException( "Caught IOException decoding data", e );
        } finally {
            // Close the input stream
            if ( bis != null ) {
                try {
                    bis.close();
                } catch ( IOException f ) {
                }
            }
            if ( ois != null ) {
                try {
                    ois.close();
                } catch ( IOException f ) {
                }
            }
        }
    }

}

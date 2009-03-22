/*
 * Copyright 2009 Martin Grotzke
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an &quot;AS IS&quot; BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package de.javakaffee.web.msm;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import net.spy.memcached.transcoders.SerializingTranscoder;
import net.spy.memcached.transcoders.Transcoder;

import org.apache.catalina.Loader;
import org.apache.catalina.Manager;
import org.apache.catalina.Session;
import org.apache.catalina.session.StandardSession;
import org.apache.catalina.util.CustomObjectInputStream;

/**
 * A {@link Transcoder} that serializes catalina {@link Session}s using the
 * serialization of {@link StandardSession}.
 * 
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
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
            oos = new ObjectOutputStream( bos );
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

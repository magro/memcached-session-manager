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
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;

import org.apache.catalina.Loader;
import org.apache.catalina.Manager;
import org.apache.catalina.session.StandardSession;
import org.apache.catalina.util.CustomObjectInputStream;

/**
 * A {@link net.spy.memcached.transcoders.Transcoder} that serializes catalina
 * {@link StandardSession}s using java serialization (and the serialization of
 * {@link StandardSession} via {@link StandardSession#writeObjectData(ObjectOutputStream)}
 * and {@link StandardSession#readObjectData(ObjectInputStream)}).
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 * @version $Id$
 */
public class JavaSessionSerializationTranscoder extends SessionTranscoder {

    private final Manager _manager;

    /**
     * Constructor.
     *
     * @param manager
     *            the manager
     */
    public JavaSessionSerializationTranscoder( final Manager manager ) {
        _manager = manager;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected byte[] serialize( final Object o ) {
        if ( o == null ) {
            throw new NullPointerException( "Can't serialize null" );
        }

        ByteArrayOutputStream bos = null;
        ObjectOutputStream oos = null;
        try {
            bos = new ByteArrayOutputStream();
            oos = new ObjectOutputStream( bos );
            ( (StandardSession) o ).writeObjectData( oos );
            return bos.toByteArray();
        } catch ( final IOException e ) {
            throw new IllegalArgumentException( "Non-serializable object", e );
        } finally {
            closeSilently( bos );
            closeSilently( oos );
        }

    }

    private void closeSilently( final OutputStream os ) {
        if ( os != null ) {
            try {
                os.close();
            } catch ( final IOException f ) {
                // fail silently
            }
        }
    }

    private void closeSilently( final InputStream is ) {
        if ( is != null ) {
            try {
                is.close();
            } catch ( final IOException f ) {
                // fail silently
            }
        }
    }

    /**
     * Get the object represented by the given serialized bytes.
     *
     * @param in
     *            the bytes to deserialize
     * @return the resulting object
     */
    @Override
    protected MemcachedBackupSession deserialize( final byte[] in ) {
        ByteArrayInputStream bis = null;
        ObjectInputStream ois = null;
        try {
            bis = new ByteArrayInputStream( in );
            ois = createObjectInputStream( bis );
            final MemcachedBackupSession session = (MemcachedBackupSession) _manager.createEmptySession();
            session.readObjectData( ois );
            session.setManager( _manager );
            return session;
        } catch ( final ClassNotFoundException e ) {
            getLogger().warn( "Caught CNFE decoding %d bytes of data", in.length, e );
            throw new RuntimeException( "Caught CNFE decoding data", e );
        } catch ( final IOException e ) {
            getLogger().warn( "Caught IOException decoding %d bytes of data", in.length, e );
            throw new RuntimeException( "Caught IOException decoding data", e );
        } finally {
            closeSilently( bis );
            closeSilently( ois );
        }
    }

    private ObjectInputStream createObjectInputStream( final ByteArrayInputStream bis ) throws IOException {
        final ObjectInputStream ois;
        Loader loader = null;
        ClassLoader classLoader = null;
        if ( _manager.getContainer() != null ) {
            loader = _manager.getContainer().getLoader();
        }
        if ( loader != null ) {
            classLoader = loader.getClassLoader();
        }
        if ( classLoader != null ) {
            ois = new CustomObjectInputStream( bis, classLoader );
        } else {
            ois = new ObjectInputStream( bis );
        }
        return ois;
    }

}

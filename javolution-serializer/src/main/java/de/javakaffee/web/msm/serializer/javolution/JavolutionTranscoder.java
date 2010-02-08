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
package de.javakaffee.web.msm.serializer.javolution;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.logging.Level;
import java.util.logging.Logger;

import javolution.xml.XMLFormat;
import javolution.xml.XMLObjectReader;
import javolution.xml.XMLObjectWriter;
import javolution.xml.XMLReferenceResolver;
import javolution.xml.stream.XMLStreamException;
import net.spy.memcached.transcoders.SerializingTranscoder;

import org.apache.catalina.Loader;
import org.apache.catalina.Manager;

import de.javakaffee.web.msm.MemcachedBackupSessionManager.MemcachedBackupSession;

/**
 * A {@link net.spy.memcached.transcoders.Transcoder} that serializes catalina
 * {@link org.apache.catalina.session.StandardSession}s using <a
 * href="http://javolution.org/">Javolutions</a> <a href="http://javolution.org/target/site/apidocs/javolution/xml/package-summary.html#package_description"
 * > xml data binding</a> facilities.
 * <p>
 * Objects are serialized to/from xml using javolutions built in
 * {@link javolution.xml.XMLFormat}s for standard types, custom/user types are bound using the
 * {@link ReflectionFormat}.
 * </p>
 * <p>
 * Additionally it's worth to note that cyclic dependencies are supported.
 * </p>
 * 
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class JavolutionTranscoder extends SerializingTranscoder {

    static final String REFERENCE_ATTRIBUTE_ID = "__id";
    static final String REFERENCE_ATTRIBUTE_REF_ID = "__ref";

    private static final Logger LOG = Logger.getLogger( JavolutionTranscoder.class.getName() );

    private final Manager _manager;
    private final ReflectionBinding _xmlBinding;

    /**
     * Constructor.
     * 
     * @param manager
     *            the manager
     */
    public JavolutionTranscoder( final Manager manager ) {
        this( manager, false );
    }

    /**
     * Constructor.
     * 
     * @param manager
     *            the manager
     * @param copyCollectionsForSerialization
     *            specifies, if iterating over collection elements shall be done
     *            on a copy of the collection or on the collection itself
     * @param customFormats a list of custom {@link XMLFormat}s or <code>null</code>.
     */
    public JavolutionTranscoder( final Manager manager, final boolean copyCollectionsForSerialization,
            final XMLFormat<?> ... customFormats ) {
        super.setCompressionThreshold( 1000 * 1000 );
        _manager = manager;
        final Loader loader = _manager.getContainer().getLoader();
        _xmlBinding = new ReflectionBinding( loader.getClassLoader(), copyCollectionsForSerialization, customFormats );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected byte[] serialize( final Object o ) {
        if ( o == null ) {
            throw new NullPointerException( "Can't serialize null" );
        }

        XMLObjectWriter writer = null;
        try {
            final ByteArrayOutputStream bos = new ByteArrayOutputStream();
            writer = XMLObjectWriter.newInstance( bos );
            final XMLReferenceResolver xmlReferenceResolver = new XMLReferenceResolver();
            xmlReferenceResolver.setIdentifierAttribute( REFERENCE_ATTRIBUTE_ID );
            xmlReferenceResolver.setReferenceAttribute( REFERENCE_ATTRIBUTE_REF_ID );
            writer.setReferenceResolver( xmlReferenceResolver );
            writer.setBinding( _xmlBinding );
            writer.write( o, "session" );
            writer.flush();
            // getLogger().info( "Returning deserialized:\n" + new String( bos.toByteArray() ) );
            return bos.toByteArray();
        } catch ( final Exception e ) {
            LOG.log( Level.SEVERE, "caught exception", e );
            throw new IllegalArgumentException( "Could not serialize object", e );
        } finally {
            closeSilently( writer );
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
    protected Object deserialize( final byte[] in ) {
        // getLogger().info( "Loading serialized:\n" + new String( in ) );
        XMLObjectReader reader = null;
        try {
            final ByteArrayInputStream bis = new ByteArrayInputStream( in );
            reader = XMLObjectReader.newInstance( bis );
            final XMLReferenceResolver xmlReferenceResolver = new XMLReferenceResolver();
            xmlReferenceResolver.setIdentifierAttribute( REFERENCE_ATTRIBUTE_ID );
            xmlReferenceResolver.setReferenceAttribute( REFERENCE_ATTRIBUTE_REF_ID );
            reader.setReferenceResolver( xmlReferenceResolver );
            reader.setBinding( _xmlBinding );
            if ( !reader.hasNext() ) {
                throw new IllegalStateException( "reader has no input" );
            }
            final MemcachedBackupSession session = reader.read( "session", MemcachedBackupSession.class );
            session.setManager( _manager );
            session.doAfterDeserialization();
            return session;
        } catch ( final RuntimeException e ) {
            getLogger().warn( "Caught Exception decoding %d bytes of data", in.length, e );
            throw e;
        } catch ( final XMLStreamException e ) {
            getLogger().warn( "Caught Exception decoding %d bytes of data", in.length, e );
            throw new RuntimeException( e );
        } finally {
            closeSilently( reader );
        }
    }

    private void closeSilently( final XMLObjectWriter stream ) {
        if ( stream != null ) {
            try {
                stream.close();
            } catch ( final XMLStreamException e ) {
                // fail silently
            }
        }
    }

    private void closeSilently( final XMLObjectReader stream ) {
        if ( stream != null ) {
            try {
                stream.close();
            } catch ( final XMLStreamException e ) {
                // fail silently
            }
        }
    }

}

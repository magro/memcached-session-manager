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
import java.io.File;
import java.io.FileOutputStream;
import java.util.Map;

import javolution.xml.XMLObjectReader;
import javolution.xml.XMLObjectWriter;
import javolution.xml.XMLReferenceResolver;
import javolution.xml.stream.XMLStreamException;

import org.apache.catalina.Loader;
import org.apache.catalina.Manager;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

import de.javakaffee.web.msm.MemcachedBackupSession;
import de.javakaffee.web.msm.SessionAttributesTranscoder;
import de.javakaffee.web.msm.SessionTranscoder;

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
public class JavolutionTranscoder extends SessionTranscoder implements SessionAttributesTranscoder {

    static final String REFERENCE_ATTRIBUTE_ID = "__id";
    static final String REFERENCE_ATTRIBUTE_REF_ID = "__ref";

    private static final Log LOG = LogFactory.getLog( JavolutionTranscoder.class );

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
     * @param customFormats a list of {@link CustomXMLFormat}s or <code>null</code>.
     */
    public JavolutionTranscoder( final Manager manager, final boolean copyCollectionsForSerialization,
            final CustomXMLFormat<?> ... customFormats ) {
        _manager = manager;
        final Loader loader = _manager.getContainer().getLoader();
        _xmlBinding = new ReflectionBinding( loader.getClassLoader(), copyCollectionsForSerialization, customFormats );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public byte[] serializeAttributes( final MemcachedBackupSession session, final Map<String, Object> attributes ) {
        return doSerialize( attributes, "attributes" );
    }

    /**
     * This is there just for testing, so that we can serialize sessions using
     * the former serialization strategy (the whole session, not just attribtes).
     * @param session the session to serialize.
     * @return the serialized session data
     */
    @Override
    protected byte[] serialize( final Object session ) {
        return doSerialize( session, "session" );
    }

    private byte[] doSerialize( final Object object, final String name ) {
        if ( object == null ) {
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
            writer.write( object, name );
            writer.flush();

            if ( LOG.isDebugEnabled() ) {
                LOG.debug( "Returning serialized data:\n" + new String( bos.toByteArray() ) );
                final File tmpFile = File.createTempFile( "session-" + System.identityHashCode( object ) + "-", ".tmp", new File( "/tmp" ) );
                final FileOutputStream out = new FileOutputStream( tmpFile );
                out.write( bos.toByteArray() );
                out.close();
                LOG.debug( "Wrote content to file " + tmpFile.getAbsolutePath() );
            }
            // getLogger().info( "Returning deserialized:\n" + new String( bos.toByteArray() ) );

            return bos.toByteArray();
        } catch ( final Exception e ) {
            LOG.error( "caught exception", e );
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
    public Map<String, Object> deserializeAttributes( final byte[] in ) {
        return doDeserialize( in, "attributes" );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected MemcachedBackupSession deserialize( final byte[] in ) {
        /* "session" is exactly the name that was used by the former transcoder.
         * We need to use the same name so that we can deserialize old session data.
         */
        final MemcachedBackupSession result = doDeserialize( in, "session" );
        result.setManager( _manager );
        result.doAfterDeserialization();
        return result;
    }

    private <T> T doDeserialize( final byte[] in, final String name ) {
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
            return reader.read( name );
        } catch ( final RuntimeException e ) {
            LOG.warn( "Caught Exception decoding "+ in.length +" bytes of data", e );
            throw e;
        } catch ( final XMLStreamException e ) {
            LOG.warn( "Caught Exception decoding "+ in.length +" bytes of data", e );
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

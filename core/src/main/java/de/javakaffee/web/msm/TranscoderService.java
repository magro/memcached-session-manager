/*
 * Copyright 2010 Martin Grotzke
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
import java.io.UnsupportedEncodingException;
import java.security.Principal;
import java.util.Arrays;
import java.util.Map;

import org.apache.catalina.Manager;
import org.apache.catalina.Realm;
import org.apache.catalina.Session;
import org.apache.catalina.authenticator.Constants;
import org.apache.catalina.ha.session.SerializablePrincipal;
import org.apache.catalina.realm.GenericPrincipal;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

import de.javakaffee.web.msm.MemcachedSessionService.SessionManager;

/**
 * This service is responsible for serializing/deserializing session data
 * so that this can be stored in / loaded from memcached.
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class TranscoderService {

    private static final Log LOG = LogFactory.getLog( TranscoderService.class );

    private static final short CURRENT_VERSION = 1;

    static final int NUM_BYTES = 8 // creationTime: long
            + 8 // lastAccessedTime: long
            + 4 // maxInactiveInterval: int
            + 1 // isNew: boolean
            + 1 // isValid: boolean
            + 8 // thisAccessedTime
            + 8; // lastBackupTime

    private final SessionAttributesTranscoder _attributesTranscoder;

    /**
     * Creates a new {@link TranscoderService}.
     *
     * @param attributesTranscoder the {@link SessionAttributesTranscoder} strategy to use.
     */
    public TranscoderService( final SessionAttributesTranscoder attributesTranscoder ) {
        _attributesTranscoder = attributesTranscoder;
    }

    /**
     * Serialize the given session to a byte array. This is a shortcut for
     * <code><pre>
     * final byte[] attributesData = serializeAttributes( session, session.getAttributes() );
     * serialize( session, attributesData );
     * </pre></code>
     * The returned byte array can be deserialized using {@link #deserialize(byte[], Realm, Manager)}.
     *
     * @see #serializeAttributes(MemcachedBackupSession, Map)
     * @see #serialize(MemcachedBackupSession, byte[])
     * @see #deserialize(byte[], Realm, Manager)
     * @param session the session to serialize.
     * @return the serialized session data.
     */
    public byte[] serialize( final MemcachedBackupSession session ) {
        final byte[] attributesData = serializeAttributes( session, session.getAttributesInternal() );
        return serialize( session, attributesData );
    }

    /**
     * Deserialize session data that was serialized using {@link #serialize(MemcachedBackupSession)}
     * (or a combination of {@link #serializeAttributes(MemcachedBackupSession, Map)} and
     * {@link #serialize(MemcachedBackupSession, byte[])}).
     * <p>
     * Note: the returned session already has the manager set and
     * {@link MemcachedBackupSession#doAfterDeserialization()} is invoked. Additionally
     * the attributes hash is set (via {@link MemcachedBackupSession#setDataHashCode(int)}).
     * </p>
     *
     * @param data the byte array of the serialized session and its session attributes. Can be <code>null</code>.
     * @param realm the realm that is used to reconstruct the principal if there was any stored in the session.
     * @param manager the manager to set on the deserialized session.
     *
     * @return the deserialized {@link MemcachedBackupSession}
     *  or <code>null</code> if the provided <code>byte[] data</code> was <code>null</code>.
     */
    public MemcachedBackupSession deserialize( final byte[] data, final SessionManager manager ) {
        if ( data == null ) {
            return null;
        }
        try {
            final DeserializationResult deserializationResult = deserializeSessionFields( data, manager );
            final byte[] attributesData = deserializationResult.getAttributesData();
            final Map<String, Object> attributes = deserializeAttributes( attributesData );
            final MemcachedBackupSession session = deserializationResult.getSession();
            session.setAttributesInternal( attributes );
            session.setDataHashCode( Arrays.hashCode( attributesData ) );
            session.setManager( manager );
            session.doAfterDeserialization();
            return session;
        } catch( final InvalidVersionException e ) {
            LOG.info( "Got session data from memcached with an unsupported version: " + e.getVersion() );
            // for versioning probably there will be changes in the design,
            // with the first change and version 2 we'll see what we need
            return null;
        }
    }

    /**
     * Serialize the given session attributes to a byte array, this is delegated
     * to {@link SessionAttributesTranscoder#serializeAttributes(MemcachedBackupSession, Map)} (using
     * the {@link SessionAttributesTranscoder} provided in the constructor of this class).
     *
     * @param session the session that owns the given attributes.
     * @param attributes the attributes to serialize.
     * @return a byte array representing the serialized attributes.
     *
     * @see de.javakaffee.web.msm.SessionAttributesTranscoder#serializeAttributes(MemcachedBackupSession, Map)
     */
    public byte[] serializeAttributes( final MemcachedBackupSession session, final Map<String, Object> attributes ) {
        return _attributesTranscoder.serializeAttributes( session, attributes );
    }



    /**
     * Deserialize the given byte array to session attributes, this is delegated
     * to {@link SessionAttributesTranscoder#deserializeAttributes(byte[])} (using
     * the {@link SessionAttributesTranscoder} provided in the constructor of this class).
     *
     * @param data the serialized attributes
     * @return the deserialized attributes
     *
     * @see de.javakaffee.web.msm.SessionAttributesTranscoder#deserializeAttributes(byte[])
     */
    public Map<String, Object> deserializeAttributes( final byte[] data ) {
        return _attributesTranscoder.deserializeAttributes( data );
    }

    /**
     * Serialize session fields to a byte[] and create a byte[] containing both the
     * serialized byte[] of the session fields and the provided byte[] of the serialized
     * session attributes.
     *
     * @param session its fields will be serialized to a byte[]
     * @param attributesData the serialized session attributes (e.g. from {@link #serializeAttributes(MemcachedBackupSession, Map)})
     * @return a byte[] containing both the serialized session fields and the provided serialized session attributes
     */
    public byte[] serialize( final MemcachedBackupSession session, final byte[] attributesData ) {
        final byte[] sessionData = serializeSessionFields( session );
        final byte[] result = new byte[ sessionData.length + attributesData.length ];
        System.arraycopy( sessionData, 0, result, 0, sessionData.length );
        System.arraycopy( attributesData, 0, result, sessionData.length, attributesData.length );
        return result;
    }

    // ---------------------  private/protected helper methods  -------------------


    static byte[] serializeSessionFields( final MemcachedBackupSession session ) {

        final byte[] idData = serializeId( session.getIdInternal() );

        final byte[] principalData = session.getPrincipal() != null ? serializePrincipal( session.getPrincipal() ) : null;
        final int principalDataLength = principalData != null ? principalData.length : 0;

        final int sessionFieldsDataLength = 2 // short value for the version
        // the following might change with other versions, refactoring needed then
                + 2 // short value that stores the dataLength
                + NUM_BYTES // bytes that store all session attributes but the id
                + 2 // short value that stores the idData length
                + idData.length // the number of bytes for the id
                + 2 // short value for the authType
                + 2 // short value that stores the principalData length
                + principalDataLength; // the number of bytes for the principal
        final byte[] data = new byte[sessionFieldsDataLength];

        int idx = 0;
        idx = encodeNum( CURRENT_VERSION, data, idx, 2 );
        idx = encodeNum( sessionFieldsDataLength, data, idx, 2 );
        idx = encodeNum( session.getCreationTimeInternal(), data, idx, 8 );
        idx = encodeNum( session.getLastAccessedTimeInternal(), data, idx, 8 );
        idx = encodeNum( session.getMaxInactiveInterval(), data, idx, 4 );
        idx = encodeBoolean( session.isNewInternal(), data, idx );
        idx = encodeBoolean( session.isValidInternal(), data, idx );
        idx = encodeNum( session.getThisAccessedTimeInternal(), data, idx, 8 );
        idx = encodeNum( session.getLastBackupTime(), data, idx, 8 );
        idx = encodeNum( idData.length, data, idx, 2 );
        idx = copy( idData, data, idx );
        idx = encodeNum( AuthType.valueOfValue( session.getAuthType() ).getId(), data, idx, 2 );
        idx = encodeNum( principalDataLength, data, idx, 2 );
        copy( principalData, data, idx );

        return data;
    }

    static DeserializationResult deserializeSessionFields( final byte[] data, final SessionManager manager ) throws InvalidVersionException {
        final MemcachedBackupSession result = manager.newMemcachedBackupSession();

        final short version = (short) decodeNum( data, 0, 2 );

        if ( version != CURRENT_VERSION ) {
            throw new InvalidVersionException( "The version " + version + " does not match the current version " + CURRENT_VERSION, version );
        }

        final short sessionFieldsDataLength = (short) decodeNum( data, 2, 2 );

        result.setCreationTimeInternal( decodeNum( data, 4, 8 ) );
        result.setLastAccessedTimeInternal( decodeNum( data, 12, 8 ) );
        result.setMaxInactiveInterval( (int) decodeNum( data, 20, 4 ) );
        result.setIsNewInternal( decodeBoolean( data, 24 ) );
        result.setIsValidInternal( decodeBoolean( data, 25 ) );
        result.setThisAccessedTimeInternal( decodeNum( data, 26, 8 ) );
        result.setLastBackupTime( decodeNum( data, 34, 8 ) );

        final short idLength = (short) decodeNum( data, 42, 2 );
        result.setIdInternal( decodeString( data, 44, idLength ) );

        final short authTypeId = (short)decodeNum( data, 44 + idLength, 2 );
        result.setAuthType( AuthType.valueOfId( authTypeId ).getValue() );

        final int currentIdx = 44 + idLength + 2;
        final short principalDataLength = (short) decodeNum( data, currentIdx, 2 );
        if ( principalDataLength > 0 ) {
            final byte[] principalData = new byte[principalDataLength];
            System.arraycopy( data, currentIdx + 2, principalData, 0, principalDataLength );
            result.setPrincipal( deserializePrincipal( principalData, manager ) );
        }

        final byte[] attributesData = new byte[ data.length - sessionFieldsDataLength ];
        System.arraycopy( data, sessionFieldsDataLength, attributesData, 0, data.length - sessionFieldsDataLength );

        return new DeserializationResult( result, attributesData );
    }

    static class DeserializationResult {
        private final MemcachedBackupSession _session;
        private final byte[] _attributesData;
        DeserializationResult( final MemcachedBackupSession session, final byte[] attributesData ) {
            _session = session;
            _attributesData = attributesData;
        }
        /**
         * @return the session with fields initialized apart from the attributes.
         */
        MemcachedBackupSession getSession() {
            return _session;
        }
        /**
         * The serialized session attributes.
         * @return the byte array representing the serialized session attributes.
         */
        byte[] getAttributesData() {
            return _attributesData;
        }
    }

    private static byte[] serializeId( final String id ) {
        try {
            return id.getBytes( "UTF-8" );
        } catch ( final UnsupportedEncodingException e ) {
            throw new RuntimeException( e );
        }
    }

    private static byte[] serializePrincipal( final Principal principal ) {
        ByteArrayOutputStream bos = null;
        ObjectOutputStream oos = null;
        try {
            bos = new ByteArrayOutputStream();
            oos = new ObjectOutputStream( bos );
            SerializablePrincipal.writePrincipal((GenericPrincipal) principal, oos );
            oos.flush();
            return bos.toByteArray();
        } catch ( final IOException e ) {
            throw new IllegalArgumentException( "Non-serializable object", e );
        } finally {
            closeSilently( bos );
            closeSilently( oos );
        }
    }

    private static Principal deserializePrincipal( final byte[] data, final SessionManager manager ) {
        ByteArrayInputStream bis = null;
        ObjectInputStream ois = null;
        try {
            bis = new ByteArrayInputStream( data );
            ois = new ObjectInputStream( bis );
            return manager.readPrincipal( ois );
        } catch ( final IOException e ) {
            throw new IllegalArgumentException( "Could not deserialize principal", e );
        } catch ( final ClassNotFoundException e ) {
            throw new IllegalArgumentException( "Could not deserialize principal", e );
        } finally {
            closeSilently( bis );
            closeSilently( ois );
        }
    }

    /**
     * Convert a number to bytes (with length of maxBytes) and write bytes into
     * the provided byte[] data starting at the specified beginIndex.
     *
     * @param num
     *            the number to encode
     * @param data
     *            the byte array into that the number is encoded
     * @param beginIndex
     *            the beginning index of data where to start encoding,
     *            inclusive.
     * @param maxBytes
     *            the number of bytes to store for the number
     * @return the next beginIndex (<code>beginIndex + maxBytes</code>).
     */
    public static int encodeNum( final long num, final byte[] data, final int beginIndex, final int maxBytes ) {
        for ( int i = 0; i < maxBytes; i++ ) {
            final int pos = maxBytes - i - 1; // the position of the byte in the number
            final int idx = beginIndex + pos; // the index in the data array
            data[idx] = (byte) ( ( num >> ( 8 * i ) ) & 0xff );
        }
        return beginIndex + maxBytes;
    }

    public static long decodeNum( final byte[] data, final int beginIndex, final int numBytes ) {
        long result = 0;
        for ( int i = 0; i < numBytes; i++ ) {
            final byte b = data[beginIndex + i];
            result = ( result << 8 ) | ( b < 0
                ? 256 + b
                : b );
        }
        return result;
    }

    /**
     * Encode a boolean that can be decoded with {@link #decodeBoolean(byte[], int)}.
     * @param b the boolean value
     * @param data the byte array where to write the encoded byte(s) to
     * @param index the start index in the byte array for writing.
     * @return the incremented index that can be used next.
     */
    private static int encodeBoolean( final boolean b, final byte[] data, final int index ) {
        data[index] = (byte) ( b
            ? '1'
            : '0' );
        return index + 1;
    }

    private static boolean decodeBoolean( final byte[] in, final int index ) {
        return in[index] == '1';
    }

    private static String decodeString( final byte[] data, final int beginIndex, final int length ) {
        try {
            final byte[] idData = new byte[length];
            System.arraycopy( data, beginIndex, idData, 0, length );
            return new String( idData, "UTF-8" );
        } catch ( final UnsupportedEncodingException e ) {
            throw new RuntimeException( e );
        }
    }

    protected static int copy( final byte[] src, final byte[] dest, final int destBeginIndex ) {
        if ( src == null ) {
            return destBeginIndex;
        }
        System.arraycopy( src, 0, dest, destBeginIndex, src.length );
        return destBeginIndex + src.length;
    }

    private static void closeSilently( final OutputStream os ) {
        if ( os != null ) {
            try {
                os.close();
            } catch ( final IOException f ) {
                // fail silently
            }
        }
    }

    private static void closeSilently( final InputStream is ) {
        if ( is != null ) {
            try {
                is.close();
            } catch ( final IOException f ) {
                // fail silently
            }
        }
    }

    /**
     * The enum representing id/string mappings for the {@link Session#getAuthType()}
     * with values defined in {@link Constants}.
     */
    private static enum AuthType {

        NONE( (short)0, null ),
        BASIC( (short)1, Constants.BASIC_METHOD ),
        CLIENT_CERT( (short)2, Constants.CERT_METHOD ),
        DIGEST( (short)3, Constants.DIGEST_METHOD ),
        FORM( (short)4, Constants.FORM_METHOD );

        private final short _id;
        private final String _value;

        private AuthType( final short id, final String value ) {
            _id = id;
            _value = value;
        }

        static AuthType valueOfId( final short id ) {
            for( final AuthType authType : values() ) {
                if ( id == authType._id ) {
                    return authType;
                }
            }
            throw new IllegalArgumentException( "No AuthType found for id " + id );
        }

        static AuthType valueOfValue( final String value ) {
            for( final AuthType authType : values() ) {
                if ( value == null && authType._value == null
                        || value != null && value.equals( authType._value )) {
                    return authType;
                }
            }
            throw new IllegalArgumentException( "No AuthType found for value " + value );
        }

        /**
         * @return the id
         */
        short getId() {
            return _id;
        }

        /**
         * @return the value
         */
        String getValue() {
            return _value;
        }

    }

}

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

import java.io.UnsupportedEncodingException;
import java.util.Date;
import java.util.Map;


/**
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class TranscoderService {

    static final int NUM_BYTES = 8 // creationTime: long
            + 8 // lastAccessedTime: long
            + 4 // maxInactiveInterval: int
            + 1 // isNew: boolean
            + 1 // isValid: boolean
            + 8; // thisAccessedTime

    private final SessionAttributesTranscoder _attributesTranscoder;

    /**
     * @param createTranscoder
     */
    public TranscoderService( final SessionAttributesTranscoder attributesTranscoder ) {
        _attributesTranscoder = attributesTranscoder;
    }

    public static void main( final String[] args ) throws UnsupportedEncodingException {
        System.out.println( "42 as byte[]: " + Integer.toBinaryString( Integer.MAX_VALUE ) );
        System.out.println( "date: " + new Date( Integer.MAX_VALUE ).toString() );
        System.out.println( "diff: " + ( System.currentTimeMillis() - Integer.MAX_VALUE ) );
        System.out.println( "length of foo.getBytes, foobar.getBytes: " + "foo".getBytes().length + ", "
                + "foobar".getBytes().length );

        //Long.valueOf( 42L )
    }

    /**
     * Serialize the given session to a byte array. This is a shortcut for
     * <pre><code>final byte[] attributesData = serializeAttributes( session, session.getAttributes() );
serialize( session, attributesData );
     * </code></pre>
     * The returned byte array can be deserialized using {@link #deserialize(byte[])}.
     *
     * @see #serializeAttributes(MemcachedBackupSession, Map)
     * @see #serialize(MemcachedBackupSession, byte[])
     * @see #deserialize(byte[])
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
     * Note: the returned session does not yet have the manager set neither was
     * {@link MemcachedBackupSession#doAfterDeserialization()} invoked.
     * </p>
     * @param data the byte array of the serialized session and its session attributes.
     * @return the deserialized {@link MemcachedBackupSession}.
     */
    public MemcachedBackupSession deserialize( final byte[] data ) {
        final DeserializationResult deserializationResult = deserializeSessionFields( data );
        final Map<String, Object> attributes = _attributesTranscoder.deserialize( deserializationResult.getAttributesData() );
        final MemcachedBackupSession result = deserializationResult.getSession();
        result.setAttributesInternal( attributes );
        return result;
    }

    /**
     * @param session
     * @param attributes
     * @return
     * @see de.javakaffee.web.msm.SessionAttributesTranscoder#serialize(MemcachedBackupSession, Map)
     */
    public byte[] serializeAttributes( final MemcachedBackupSession session, final Map<String, Object> attributes ) {
        return _attributesTranscoder.serialize( session, attributes );
    }



    /**
     * @param data
     * @return
     * @see de.javakaffee.web.msm.SessionAttributesTranscoder#deserialize(byte[])
     */
    public Map<String, Object> deserializeAttributes( final byte[] data ) {
        return _attributesTranscoder.deserialize( data );
    }

    /**
     * @param session
     * @param attributesData
     * @return
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
        byte[] idData = null;
        try {
            idData = session.getIdInternal().getBytes( "UTF-8" );
        } catch ( final UnsupportedEncodingException e ) {
            throw new RuntimeException( e );
        }
        final int sessionFieldsDataLength = 2 // short value that stores the dataLength
                + NUM_BYTES // bytes that store all session attributes but the id
                + idData.length; // the number of bytes for the id
        final byte[] data = new byte[sessionFieldsDataLength];

        int idx = 0;
        idx = encodeNum( sessionFieldsDataLength, data, idx, 2 );
        idx = encodeNum( session.getCreationTimeInternal(), data, idx, 8 );
        idx = encodeNum( session.getLastAccessedTimeInternal(), data, idx, 8 );
        idx = encodeNum( session.getMaxInactiveInterval(), data, idx, 4 );
        idx = encodeBoolean( session.isNewInternal(), data, idx );
        idx = encodeBoolean( session.isValidInternal(), data, idx );
        idx = encodeNum( session.getThisAccessedTimeInternal(), data, idx, 8 );
        copy( idData, data, idx );

        return data;
    }

    static DeserializationResult deserializeSessionFields( final byte[] data ) {
        final MemcachedBackupSession result = new MemcachedBackupSession();

        final short sessionFieldsDataLength = (short) decodeNum( data, 0, 2 );

        result.setCreationTimeInternal( decodeNum( data, 2, 8 ) );
        result.setLastAccessedTimeInternal( decodeNum( data, 10, 8 ) );
        result.setMaxInactiveInterval( (int) decodeNum( data, 18, 4 ) );
        result.setIsNewInternal( decodeBoolean( data, 22 ) );
        result.setIsValidInternal( decodeBoolean( data, 23 ) );
        result.setThisAccessedTimeInternal( decodeNum( data, 24, 8 ) );

        final int currentIdx = 32; // 24 + 8
        final int idLength = sessionFieldsDataLength - currentIdx;
        result.setIdInternal( decodeString( data, 32, idLength ) );

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
    private static int encodeNum( final long num, final byte[] data, final int beginIndex, final int maxBytes ) {
        for ( int i = 0; i < maxBytes; i++ ) {
            final int pos = maxBytes - i - 1; // the position of the byte in the number
            final int idx = beginIndex + pos; // the index in the data array
            data[idx] = (byte) ( ( num >> ( 8 * i ) ) & 0xff );
        }
        return beginIndex + maxBytes;
    }

    private static long decodeNum( final byte[] data, final int beginIndex, final int numBytes ) {
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

    protected static void copy( final byte[] src, final byte[] dest, final int destBeginIndex ) {
        System.arraycopy( src, 0, dest, destBeginIndex, src.length );
    }

}

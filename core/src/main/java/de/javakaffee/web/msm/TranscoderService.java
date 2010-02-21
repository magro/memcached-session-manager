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
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Date;

import org.apache.catalina.Session;

import de.javakaffee.web.msm.MemcachedBackupSessionManager.MemcachedBackupSession;

/**
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class TranscoderService {

//    private static enum SessionAttribute {
//            /*
//             * stream.writeObject(new Long(creationTime));
//             * stream.writeObject(new Long(lastAccessedTime));
//             * stream.writeObject(new Integer(maxInactiveInterval));
//             * stream.writeObject(new Boolean(isNew)); stream.writeObject(new
//             * Boolean(isValid)); stream.writeObject(new
//             * Long(thisAccessedTime)); stream.writeObject(id);
//             */
//
//            CREATION_TIME( long.class ),
//            LAST_ACCESSED_TIME( long.class ),
//            MAX_INACTIVE_INTERVAL( int.class ),
//            IS_NEW( boolean.class ),
//            IS_VALID( boolean.class ),
//            THIS_ACCESSED_TIME( long.class ),
//            ID( String.class );
//
//        private final Class<?> _type;
//
//        /**
//         * The number of bytes required for all elements of this enum that have
//         * types with a known length of the serialized byte[]. The {@link #ID}
//         * is excluded, as the length of the serialized byte[] depends on the
//         * length of the id String.
//         */
//        static final int NUM_BYTES = numBytesForFixedLengthAttributes();
//
//        private SessionAttribute( final Class<?> type ) {
//            _type = type;
//        }
//
//        /**
//         * The number of bytes required for all elements of this enum that have
//         * types with a known length of the serialized byte[]. The {@link #ID}
//         * is excluded, as the length of the serialized byte[] depends on the
//         * length of the id String.
//         *
//         * @return the number of bytes required for all elements but {@link #ID}
//         *         .
//         */
//        static int numBytesForFixedLengthAttributes() {
//            int result = 0;
//            for ( final SessionAttribute item : values() ) {
//                if ( item._type == long.class ) {
//                    result += 8;
//                } else if ( item._type == boolean.class ) {
//                    result += 1;
//                } else if ( item._type == int.class ) {
//                    result += 4;
//                } else if ( item._type == String.class && item == ID ) {
//                    // ignore, we don't know what to add
//                } else {
//                    throw new IllegalArgumentException( "The enumeration " + SessionAttribute.class.getSimpleName()
//                            + " contains a field with a type that is not yet supported by this method - just fix this!" );
//                }
//            }
//            return result;
//        }
//
//    }

    static final int NUM_BYTES = 8 // creationTime: long
    + 8 // lastAccessedTime: long
    + 4 // maxInactiveInterval: int
    + 1 // isNew: boolean
    + 1 // isValid: boolean
    + 8; // thisAccessedTime

    /**
     * @param session
     * @param data
     */
    public TranscoderService( final Session session, final byte[] data ) {
        // TODO Auto-generated constructor stub
    }

    /**
     * @param session
     * @param transcoder
     */
    public static void backupIfModified( final MemcachedBackupSession session, final Serializer serializer ) {
        final byte[] data = serializer.serialize( session );
        final int dataHashCode = Arrays.hashCode( data );
        if ( session.getDataHashCode() != dataHashCode ) {
            new TranscoderService( session, data );
        }
        Arrays.hashCode( data );
    }

    public static byte[] intToByteArray( final int value ) {
        final byte[] b = new byte[4];
        for ( int i = 0; i < 4; i++ ) {
            final int offset = ( b.length - 1 - i ) * 8;
            b[i] = (byte) ( ( value >>> offset ) & 0xFF );
            System.out.println( "have byte " + b[i] );
        }
        return b;
    }

    public static byte[] longToByteArray( final long value ) {
        final byte[] b = new byte[8];
        for ( int i = 0; i < 8; i++ ) {
            final int offset = ( b.length - 1 - i ) * 8;
            b[i] = (byte) ( ( value >>> offset ) & 0xFF );
            System.out.println( "have byte " + b[i] );
        }
        return b;
    }

    /**
     *
     */
    public void execute() {
        // TODO Auto-generated method stub

    }

    public static void main( final String[] args ) throws UnsupportedEncodingException {
        System.out.println( "42 as byte[]: " + intToByteArray( Integer.MAX_VALUE ) );
        System.out.println( "42 as byte[]: " + Integer.toBinaryString( Integer.MAX_VALUE ) );
        System.out.println( "date: " + new Date( Integer.MAX_VALUE ).toString() );
        System.out.println( "diff: " + ( System.currentTimeMillis() - Integer.MAX_VALUE ) );
        System.out.println( "length of foo.getBytes, foobar.getBytes: " + "foo".getBytes().length + ", "
                + "foobar".getBytes().length );

        final int n = 100000;
        final String tmpl = "1264468503706abcdefg";

        long start = System.currentTimeMillis();
        for ( int i = 0; i < n; i++ ) {
            new String( tmpl ).getBytes( "UTF-8" );
        }
        System.out.println( "String.getBytes took " + ( System.currentTimeMillis() - start ) + " msec." );

        final Charset charset = Charset.forName( "UTF-8" );
        start = System.currentTimeMillis();
        for ( int i = 0; i < n; i++ ) {
            charset.encode( new String( tmpl ) );
        }
        System.out.println( "Charset.encode took " + ( System.currentTimeMillis() - start ) + " msec." );

        start = System.currentTimeMillis();
        for ( int i = 0; i < n; i++ ) {
            getBytesFast( new String( tmpl ) );
        }
        System.out.println( "getBytesFast took " + ( System.currentTimeMillis() - start ) + " msec." );

        //Long.valueOf( 42L )
    }

    static byte[] serialize( final MemcachedBackupSession session ) {
        byte[] idData = null;
        try {
            idData = session.getIdInternal().getBytes( "UTF-8" );
        } catch ( final UnsupportedEncodingException e ) {
            throw new RuntimeException( e );
        }
        final int dataLength = 2    // short value that stores the dataLength
                + NUM_BYTES         // bytes that store all session attributes but the id
                + idData.length;    // the number of bytes for the id
        final byte[] data = new byte[dataLength];

        int idx = 0;
        idx = encodeNum( dataLength, data, idx, 2 );
        idx = encodeNum( session.getCreationTimeInternal(), data, idx, 8 );
        idx = encodeNum( session.getLastAccessedTimeInternal(), data, idx, 8 );
        idx = encodeNum( session.getMaxInactiveInterval(), data, idx, 4 );
        idx = encodeBoolean( session.isNewInternal(), data, idx );
        idx = encodeBoolean( session.isValidInternal(), data, idx );
        idx = encodeNum( session.getThisAccessedTimeInternal(), data, idx, 8 );
        copy( idData, data, idx );

        return data;
    }

    static MemcachedBackupSession deserialize( final byte[] data ) {
        final MemcachedBackupSession result = new MemcachedBackupSession();

        final short dataLength = (short) decodeNum( data, 0, 2 );

        result.setCreationTimeInternal( decodeNum( data, 2, 8 ) );
        result.setLastAccessedTimeInternal( decodeNum( data, 10, 8 ) );
        result.setMaxInactiveInterval( (int)decodeNum( data, 18, 4 ) );
        result.setIsNewInternal( decodeBoolean( data, 22 ) );
        result.setIsValidInternal( decodeBoolean( data, 23 ) );
        result.setThisAccessedTimeInternal( decodeNum( data, 24, 8 ) );

        final int currentIdx = 32; // 24 + 8
        final int idLength = dataLength - currentIdx;
        result.setIdInternal( decodeString( data, 32, idLength ) );

        return result;
    }

    public byte[] encodeNum( final long l, final int maxBytes ) {
        final byte[] rv = new byte[maxBytes];
        for ( int i = 0; i < rv.length; i++ ) {
            final int pos = rv.length - i - 1;
            rv[pos] = (byte) ( ( l >> ( 8 * i ) ) & 0xff );
        }
        return rv;
    }

    /**
     * Convert a number to bytes (with length of maxBytes) and write bytes into
     * the provided byte[] data starting at the specified beginIndex.
     *
     * @param num the number to encode
     * @param data the byte array into that the number is encoded
     * @param beginIndex the beginning index of data where to start encoding, inclusive.
     * @param maxBytes the number of bytes to store for the number
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

    public byte[] encodeLong( final long l ) {
        return encodeNum( l, 8 );
    }

    public long decodeLong( final byte[] b ) {
        long rv = 0;
        for ( final byte i : b ) {
            rv = ( rv << 8 ) | ( i < 0
                ? 256 + i
                : i );
        }
        return rv;
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

    public byte[] encodeInt( final int in ) {
        return encodeNum( in, 4 );
    }

    public int decodeInt( final byte[] in ) {
        assert in.length <= 4 : "Too long to be an int (" + in.length + ") bytes";
        return (int) decodeLong( in );
    }

    public byte[] encodeByte( final byte in ) {
        return new byte[] { in };
    }

    public byte decodeByte( final byte[] in ) {
        assert in.length <= 1 : "Too long for a byte";
        byte rv = 0;
        if ( in.length == 1 ) {
            rv = in[0];
        }
        return rv;
    }

    public byte[] encodeBoolean( final boolean b ) {
        final byte[] rv = new byte[1];
        rv[0] = (byte) ( b
            ? '1'
            : '0' );
        return rv;
    }

    /**
     *
     * @param b
     * @param data
     * @param index
     * @return the incremented index
     */
    public static int encodeBoolean( final boolean b, final byte[] data, final int index ) {
        data[index] = (byte) ( b
            ? '1'
            : '0' );
        return index + 1;
    }

    public static boolean decodeBoolean( final byte[] in, final int index ) {
        return in[index] == '1';
    }

    protected byte[] encodeString( final String in ) {
        try {
            return in.getBytes( "UTF-8" );
        } catch ( final UnsupportedEncodingException e ) {
            throw new RuntimeException( e );
        }
    }

    protected String decodeString( final byte[] data ) {
        try {
            return data != null
                ? new String( data, "UTF-8" )
                : null;
        } catch ( final UnsupportedEncodingException e ) {
            throw new RuntimeException( e );
        }
    }

    protected static String decodeString( final byte[] data, final int beginIndex, final int length ) {
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

    /**
     * A fast version of String.getBytes() as described here:
     * http://java.sun.com/developer/technicalArticles/Programming/Performance/
     * <p>
     * This might be obsolete with jdk7 as there are performance improvements in
     * String.getBytes implemented for some default charsets. (According to
     * http://blogs.sun.com/xuemingshen/entry/faster_new_string_bytes_cs)
     * </p>
     * <p>
     * Here follows a very simple comparison and the output:
     * <hr/>
     *
     * <pre>
     * <code>
     * final int n = 100000;
     * final String tmpl = "1264468503706abcdefg";
     *
     * long start = System.currentTimeMillis();
     * for ( int i = 0; i < n; i++ ) {
     *     new String( tmpl ).getBytes( "UTF-8" );
     * }
     * System.out.println( "String.getBytes took " + (System.currentTimeMillis() - start) + " msec.");
     *
     * start = System.currentTimeMillis();
     * for ( int i = 0; i < n; i++ ) {
     *     getBytesFast( new String( tmpl ) );
     * }
     * System.out.println( "getBytesFast took " + (System.currentTimeMillis() - start) + " msec.");
     * </code>
     * </pre>
     *
     * <hr/>
     *
     * <pre>
     * <code>
     * &gt; String.getBytes took 198 msec.
     * &gt; getBytesFast took 60 msec.
     * </code>
     * </pre>
     *
     * </p>
     *
     * @param str
     * @return
     */
    static byte[] getBytesFast( final String str ) {
        final char buffer[] = new char[str.length()];
        final int length = str.length();
        str.getChars( 0, length, buffer, 0 );
        final byte b[] = new byte[length];
        for ( int j = 0; j < length; j++ ) {
            final int val = buffer[j];
            b[j] = (byte) buffer[j];
        }
        return b;
    }

}

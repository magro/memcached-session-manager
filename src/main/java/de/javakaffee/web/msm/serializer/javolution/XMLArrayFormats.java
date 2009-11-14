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

import java.lang.reflect.Array;

import javolution.xml.XMLFormat;
import javolution.xml.stream.XMLStreamException;

/**
 * A class that collects different {@link XMLFormat} implementations for arrays.
 * 
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class XMLArrayFormats {
    
    static final XMLFormat<byte[]> BYTE_ARRAY_FORMAT = new XMLFormat<byte[]>() {
        
        @Override
        public byte[] newInstance( final Class<byte[]> clazz, final javolution.xml.XMLFormat.InputElement input ) throws XMLStreamException {
            try {
                final int length = input.getAttribute( "length", 0 );
                return (byte[]) Array.newInstance( byte.class, length );
            } catch ( final Exception e ) {
                throw new XMLStreamException( e );
            }
        }
        
        @Override
        public void read( final javolution.xml.XMLFormat.InputElement input, final byte[] array ) throws XMLStreamException {
            int i = 0;
            while ( input.hasNext() ) {
                array[i++] = input.getNext();
            }
        }
        
        @Override
        public final void write( final byte[] array, final javolution.xml.XMLFormat.OutputElement output ) throws XMLStreamException {
            output.setAttribute("length", array.length );
            for( final byte item : array ) {
                output.add( item );
            }
        }
        
    };

    static final XMLFormat<char[]> CHAR_ARRAY_FORMAT = new XMLFormat<char[]>() {
        
        @Override
        public char[] newInstance( final Class<char[]> clazz, final javolution.xml.XMLFormat.InputElement input ) throws XMLStreamException {
            try {
                final int length = input.getAttribute( "length", 0 );
                return (char[]) Array.newInstance( char.class, length );
            } catch ( final Exception e ) {
                throw new XMLStreamException( e );
            }
        }
        
        @Override
        public void read( final javolution.xml.XMLFormat.InputElement input, final char[] array ) throws XMLStreamException {
            int i = 0;
            while ( input.hasNext() ) {
                array[i++] = input.getNext();
            }
        }
        
        @Override
        public final void write( final char[] array, final javolution.xml.XMLFormat.OutputElement output ) throws XMLStreamException {
            output.setAttribute("length", array.length );
            for( final char item : array ) {
                output.add( item );
            };
        }
        
    };

    static final XMLFormat<short[]> SHORT_ARRAY_FORMAT = new XMLFormat<short[]>() {
        
        @Override
        public short[] newInstance( final Class<short[]> clazz, final javolution.xml.XMLFormat.InputElement input ) throws XMLStreamException {
            try {
                final int length = input.getAttribute( "length", 0 );
                return (short[]) Array.newInstance( short.class, length );
            } catch ( final Exception e ) {
                throw new XMLStreamException( e );
            }
        }
        
        @Override
        public void read( final javolution.xml.XMLFormat.InputElement input, final short[] array ) throws XMLStreamException {
            int i = 0;
            while ( input.hasNext() ) {
                array[i++] = input.getNext();
            }
        }
        
        @Override
        public final void write( final short[] array, final javolution.xml.XMLFormat.OutputElement output ) throws XMLStreamException {
            output.setAttribute("length", array.length );
            for( final short item : array ) {
                output.add( item );
            };
        }
        
    };

    static final XMLFormat<int[]> INT_ARRAY_FORMAT = new XMLFormat<int[]>() {
        
        @Override
        public int[] newInstance( final Class<int[]> clazz, final javolution.xml.XMLFormat.InputElement input ) throws XMLStreamException {
            try {
                final int length = input.getAttribute( "length", 0 );
                return (int[]) Array.newInstance( int.class, length );
            } catch ( final Exception e ) {
                throw new XMLStreamException( e );
            }
        }
        
        @Override
        public void read( final javolution.xml.XMLFormat.InputElement input, final int[] array ) throws XMLStreamException {
            int i = 0;
            while ( input.hasNext() ) {
                array[i++] = input.getNext();
            }
        }
        
        @Override
        public final void write( final int[] array, final javolution.xml.XMLFormat.OutputElement output ) throws XMLStreamException {
            output.setAttribute("length", array.length );
            for( final int item : array ) {
                output.add( item );
            };
        }
        
    };

    static final XMLFormat<long[]> LONG_ARRAY_FORMAT = new XMLFormat<long[]>() {
        
        @Override
        public long[] newInstance( final Class<long[]> clazz, final javolution.xml.XMLFormat.InputElement input ) throws XMLStreamException {
            try {
                final int length = input.getAttribute( "length", 0 );
                return (long[]) Array.newInstance( long.class, length );
            } catch ( final Exception e ) {
                throw new XMLStreamException( e );
            }
        }
        
        @Override
        public void read( final javolution.xml.XMLFormat.InputElement input, final long[] array ) throws XMLStreamException {
            int i = 0;
            while ( input.hasNext() ) {
                array[i++] = input.getNext();
            }
        }
        
        @Override
        public final void write( final long[] array, final javolution.xml.XMLFormat.OutputElement output ) throws XMLStreamException {
            output.setAttribute("length", array.length );
            for( final long item : array ) {
                output.add( item );
            };
        }
        
    };

    static final XMLFormat<float[]> FLOAT_ARRAY_FORMAT = new XMLFormat<float[]>() {
        
        @Override
        public float[] newInstance( final Class<float[]> clazz, final javolution.xml.XMLFormat.InputElement input ) throws XMLStreamException {
            try {
                final int length = input.getAttribute( "length", 0 );
                return (float[]) Array.newInstance( float.class, length );
            } catch ( final Exception e ) {
                throw new XMLStreamException( e );
            }
        }
        
        @Override
        public void read( final javolution.xml.XMLFormat.InputElement input, final float[] array ) throws XMLStreamException {
            int i = 0;
            while ( input.hasNext() ) {
                array[i++] = input.getNext();
            }
        }
        
        @Override
        public final void write( final float[] array, final javolution.xml.XMLFormat.OutputElement output ) throws XMLStreamException {
            output.setAttribute("length", array.length );
            for( final float item : array ) {
                output.add( item );
            };
        }
        
    };

    static final XMLFormat<double[]> DOUBLE_ARRAY_FORMAT = new XMLFormat<double[]>() {
        
        @Override
        public double[] newInstance( final Class<double[]> clazz, final javolution.xml.XMLFormat.InputElement input ) throws XMLStreamException {
            try {
                final int length = input.getAttribute( "length", 0 );
                return (double[]) Array.newInstance( double.class, length );
            } catch ( final Exception e ) {
                throw new XMLStreamException( e );
            }
        }
        
        @Override
        public void read( final javolution.xml.XMLFormat.InputElement input, final double[] array ) throws XMLStreamException {
            int i = 0;
            while ( input.hasNext() ) {
                array[i++] = input.getNext();
            }
        }
        
        @Override
        public final void write( final double[] array, final javolution.xml.XMLFormat.OutputElement output ) throws XMLStreamException {
            output.setAttribute("length", array.length );
            for( final double item : array ) {
                output.add( item );
            };
        }
        
    };
    
}
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

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import javolution.text.CharArray;
import javolution.xml.XMLFormat;
import javolution.xml.sax.Attributes;
import javolution.xml.stream.XMLStreamException;
import javolution.xml.stream.XMLStreamReader;
import sun.reflect.ReflectionFactory;

/**
 * An {@link XMLFormat} that provides the binding for a certain class to to/from
 * xml based on reflection.
 * <p>
 * When serializing an object to xml, the values of the declared fields are read
 * (including inherited fields) from the object. Fields marked as
 * <code>transient</code> or <code>static</code> are omitted.
 * </p>
 * <p>
 * During deserialization, first all attributes contained in the xml are read
 * and written to the object. Afterwards the fields that are bound to elements
 * are checked for contained xml elements and in this case the values are
 * written to the object.
 * </p>
 * 
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class ReflectionFormat<T> extends XMLFormat<T> {

    private static final Logger LOG = Logger.getLogger( ReflectionFormat.class.getName() );
    
    private static final Map<Class<?>, XMLNumberFormat<?>> _numberFormats = new ConcurrentHashMap<Class<?>, XMLNumberFormat<?>>();
    private static final ReflectionFactory REFLECTION_FACTORY = ReflectionFactory.getReflectionFactory();
    private static final Object[] INITARGS = new Object[0];

    private final Constructor<T> _constructor;
    private final Collection<Field> _attributes;
    private final Collection<Field> _elements;
    private final Map<String, Field> _attributesMap;

    /**
     * Creates a new instance for the provided class.
     * 
     * @param clazz
     *            the Class that is supported by this {@link XMLFormat}.
     * @param classLoader 
     */
    @SuppressWarnings( "unchecked" )
    public ReflectionFormat( final Class<T> clazz, final ClassLoader classLoader ) {

        try {
            _constructor = REFLECTION_FACTORY.newConstructorForSerialization(clazz, Object.class.getDeclaredConstructor(new Class[0]));
            _constructor.setAccessible( true );
        } catch ( final SecurityException e ) {
            throw new RuntimeException( e );
        } catch ( final NoSuchMethodException e ) {
            throw new RuntimeException( e );
        }
        

        final AttributesAndElements fields = allFields( clazz );

        _attributes = fields.attributes;
        _elements = fields.elements;

        _attributesMap = new ConcurrentHashMap<String, Field>( _attributes.size() + 1 );
        for ( final Field attribute : _attributes ) {
            _attributesMap.put( attribute.getName(), attribute );
        }
    }

    private AttributesAndElements allFields( final Class<T> cls ) {
        final AttributesAndElements result = new AttributesAndElements();
        Class<? super T> clazz = cls;
        while ( clazz != null ) {
            addDeclaredFields( clazz, result );
            clazz = clazz.getSuperclass();
        }
        return result;
    }

    private void addDeclaredFields( final Class<? super T> clazz, final AttributesAndElements result ) {
        final Field[] declaredFields = clazz.getDeclaredFields();
        for ( final Field field : declaredFields ) {
            if ( !Modifier.isTransient( field.getModifiers() ) && !Modifier.isStatic( field.getModifiers() ) ) {
                field.setAccessible( true );
                result.add( field );
            }
        }
    }

    static class AttributesAndElements {
        private final Collection<Field> attributes;
        private final Collection<Field> elements;

        AttributesAndElements() {
            attributes = new ArrayList<Field>();
            elements = new ArrayList<Field>();
        }

        void add( final Field field ) {
            if ( isAttribute( field ) ) {
                attributes.add( field );
            } else {
                elements.add( field );
            }
        }
    }

    protected static boolean isAttribute( final Field field ) {
        return isAttribute( field.getType() );
    }

    protected static boolean isAttribute( final Class<?> clazz ) {
        return clazz.isPrimitive() || clazz == String.class || Number.class.isAssignableFrom( clazz ) || clazz.isEnum();
    }
    
    /**
     * {@inheritDoc}
     */
    @Override
    public T newInstance( final Class<T> clazz, final javolution.xml.XMLFormat.InputElement xml ) throws XMLStreamException {
        try {
            return _constructor.newInstance( INITARGS );
        } catch ( final Exception e ) {
            throw new XMLStreamException( e );
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void read( final javolution.xml.XMLFormat.InputElement input, final T obj ) throws XMLStreamException {
        readAttributes( input, obj );
        readElements( input, obj );
    }

    private void readAttributes( final javolution.xml.XMLFormat.InputElement input, final T obj ) throws XMLStreamException {
        final Attributes attributes = input.getAttributes();
        for ( int i = 0; i < attributes.getLength(); i++ ) {
            final CharArray name = attributes.getLocalName( i );
            if ( !name.equals( "class" ) && !name.equals( JavolutionTranscoder.REF_ID ) ) {
                final Field field = _attributesMap.get( name.toString() );
                if ( field != null ) {
                    setFieldFromAttribute( obj, field, input );
                } else {
                    LOG.warning( "Did not find field " + name + ", attribute value is " + attributes.getValue( i ) );
                }
            }
        }
    }

    private void readElements( final javolution.xml.XMLFormat.InputElement input, final T obj ) {
        for ( final Field field : _elements ) {
            final XMLStreamReader reader = input.getStreamReader();
            reader.getEventType();

            try {
                final Object value = input.get( field.getName() );
                field.set( obj, value );
            } catch ( final Exception e ) {
                LOG.log( Level.SEVERE, "Could not set field value for field " + field, e );
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void write( final T obj, final javolution.xml.XMLFormat.OutputElement output ) throws XMLStreamException {
        writeAttributes( obj, output );
        writeElements( obj, output );
    }

    private void writeAttributes( final T obj, final javolution.xml.XMLFormat.OutputElement output ) {
        for ( final Field field : _attributes ) {
            setAttributeFromField( obj, field, output );
        }
    }

    private void writeElements( final T obj, final javolution.xml.XMLFormat.OutputElement output ) {
        for ( final Field field : _elements ) {
            writeElement( obj, field, output );
        }
    }

    @SuppressWarnings( "unchecked" )
    private void writeElement( final T obj, final Field field, final javolution.xml.XMLFormat.OutputElement output ) {
        try {
            final Object object = field.get( obj );
            if ( object != null ) {
                if ( field.getType().isArray() ) {
                    addArray( object, field.getName(), output );
                } else if ( Collection.class.isAssignableFrom( field.getType() ) ) {
                    output.add( (Collection<?>) object, field.getName(), (Class<Collection<?>>) object.getClass() );
                } else if ( Map.class.isAssignableFrom( field.getType() ) ) {
                    output.add( (Map<?, ?>) object, field.getName(), (Class<Map<?, ?>>) object.getClass() );
                } else {
                    output.add( object, field.getName() );
                }
            }
        } catch ( final Exception e ) {
            LOG.log( Level.SEVERE, "Could not write element for field.", e );
        }
    }
    
    private void addArray( final Object obj, final String name, final OutputElement output ) throws XMLStreamException {
        final Class<?> cls = obj.getClass();
        if ( cls == int[].class ) {
            output.add( (int[])obj, name, int[].class );
        }
        else if ( cls == long[].class ) {
            output.add( (long[])obj, name, long[].class );
        }
        else if ( cls == short[].class ) {
            output.add( (short[])obj, name, short[].class );
        }
        else if ( cls == float[].class ) {
            output.add( (float[])obj, name, float[].class );
        }
        else if ( cls == double[].class ) {
            output.add( (double[])obj, name, double[].class );
        }
        else if ( cls == char[].class ) {
            output.add( (char[])obj, name, char[].class );
        }
        else if ( cls == byte[].class ) {
            output.add( (byte[])obj, name, byte[].class );
        }
        else {
            output.add( (Object[])obj, name, Object[].class );
        }
        
    }

    private void setAttributeFromField( final T obj, final Field field, final javolution.xml.XMLFormat.OutputElement output ) {
        try {
            
            if ( field.getType().isPrimitive() ) {
                if ( field.getType() == boolean.class ) {
                    output.setAttribute( field.getName(), field.getBoolean( obj ) );
                } else if ( field.getType() == int.class ) {
                    output.setAttribute( field.getName(), field.getInt( obj ) );
                } else if ( field.getType() == long.class ) {
                    output.setAttribute( field.getName(), field.getLong( obj ) );
                } else if ( field.getType() == float.class ) {
                    output.setAttribute( field.getName(), field.getFloat( obj ) );
                } else if ( field.getType() == double.class ) {
                    output.setAttribute( field.getName(), field.getDouble( obj ) );
                } else if ( field.getType() == byte.class ) {
                    output.setAttribute( field.getName(), field.getByte( obj ) );
                } else if ( field.getType() == char.class ) {
                    output.setAttribute( field.getName(), field.getChar( obj ) );
                } else if ( field.getType() == short.class ) {
                    output.setAttribute( field.getName(), field.getShort( obj ) );
                }
            } else {

                final Object object = field.get( obj );
                
                if ( object != null ) {
                    if ( field.getType() == String.class || Number.class.isAssignableFrom( field.getType() ) ) {
                        output.setAttribute( field.getName(), object.toString() );
                    } else if ( field.getType().isEnum() ) {
                        output.setAttribute( field.getName(), ( (Enum<?>) object ).name() );
                    } else {
                        throw new IllegalArgumentException( "Not yet supported as attribute: " + field.getType() );
                    }
                }
            }

        } catch ( final Exception e ) {
            LOG.log( Level.SEVERE, "Could not set attribute from field value.", e );
        }
    }

    private void setFieldFromAttribute( final T obj, final Field field, final javolution.xml.XMLFormat.InputElement input ) {
        
        try {

            final String fieldName = field.getName();
            if ( field.getType().isPrimitive() ) {

                if ( field.getType() == boolean.class ) {
                    field.setBoolean( obj, input.getAttribute( fieldName, false ) );
                } else if ( field.getType() == int.class ) {
                    field.setInt( obj, input.getAttribute( fieldName, 0 ) );
                } else if ( field.getType() == long.class ) {
                    field.setLong( obj, input.getAttribute( fieldName, (long) 0 ) );
                } else if ( field.getType() == float.class ) {
                    field.setFloat( obj, input.getAttribute( fieldName, (float) 0 ) );
                } else if ( field.getType() == double.class ) {
                    field.setDouble( obj, input.getAttribute( fieldName, (double) 0 ) );
                } else if ( field.getType() == byte.class ) {
                    field.setByte( obj, input.getAttribute( fieldName, (Byte) null ) );
                } else if ( field.getType() == char.class ) {
                    field.setChar( obj, (char) input.getAttribute( fieldName, (char) 0 ) );
                } else if ( field.getType() == short.class ) {
                    field.setShort( obj, input.getAttribute( fieldName, (Short) null ) );
                }
            } else if ( field.getType().isEnum() ) {
                final String value = input.getAttribute( fieldName, (String) null );
                if ( value != null ) {
                    @SuppressWarnings( "unchecked" )
                    final Enum enumValue = Enum.valueOf( field.getType().asSubclass( Enum.class ), value );
                    field.set( obj, enumValue );
                }
            } else {

                final Object object = input.getAttribute( fieldName );

                if ( object != null ) {
                    if ( field.getType() == String.class ) {
                        field.set( obj, input.getAttribute( fieldName, (String) null ) );
                    } else if ( field.getType().isAssignableFrom( Boolean.class ) ) {
                        field.set( obj, input.getAttribute( fieldName, (Boolean) null ) );
                    } else if ( field.getType().isAssignableFrom( Integer.class ) ) {
                        field.set( obj, input.getAttribute( fieldName, (Integer) null ) );
                    } else if ( field.getType().isAssignableFrom( Long.class ) ) {
                        field.set( obj, input.getAttribute( fieldName, (Long) null ) );
                    } else if ( field.getType().isAssignableFrom( Short.class ) ) {
                        field.set( obj, input.getAttribute( fieldName, (Short) null ) );
                    } else if ( field.getType().isAssignableFrom( Double.class ) ) {
                        field.set( obj, input.getAttribute( fieldName, (Double) null ) );
                    } else if ( field.getType().isAssignableFrom( Float.class ) ) {
                        field.set( obj, input.getAttribute( fieldName, (Float) null ) );
                    } else if ( field.getType().isAssignableFrom( Byte.class ) ) {
                        field.set( obj, input.getAttribute( fieldName, (Byte) null ) );
                    } else if ( Number.class.isAssignableFrom( field.getType() ) ) {
                        final XMLNumberFormat<?> format = getNumberFormat( field.getType() );
                        field.set( obj, format.newInstanceFromAttribute( input, fieldName ) );
                    } else {
                        throw new IllegalArgumentException( "Not yet supported as attribute: " + field.getType() );
                    }
                }
            }

        } catch ( final Exception e ) {
            LOG.log( Level.SEVERE, "Caught exception when trying to set field from attribute.", e );
        }
    }
    
    @SuppressWarnings( "unchecked" )
    static <T> XMLNumberFormat<T> getNumberFormat( final Class<T> clazz ) {
        XMLNumberFormat<?> result = _numberFormats.get( clazz );
        if ( result == null ) {
            result = createNumberFormat( clazz );
            _numberFormats.put( clazz, result );
        }
        return (XMLNumberFormat<T>) result;
    }
    
    @SuppressWarnings( "unchecked" )
    static <T> XMLNumberFormat<T> createNumberFormat( final Class<T> clazz ) {
        try {
            for( final Constructor<?> constructor : clazz.getConstructors() ) {
                final Class<?>[] parameterTypes = constructor.getParameterTypes();
                if ( parameterTypes.length == 1 ) {
                    if ( parameterTypes[0] == long.class ) {
                        return new XMLNumberLongFormat<T>( (Constructor<T>) constructor );
                    }
                    if ( parameterTypes[0] == int.class ) {
                        return new XMLNumberIntFormat<T>( (Constructor<T>) constructor );
                    }
                }
            }
        } catch ( final Exception e ) {
            throw new RuntimeException( e );
        }
        throw new IllegalArgumentException( "No suitable constructor found for class " + clazz.getName() + ".\n" +
                "Available constructors: " + clazz.getConstructors() );
    }
    
    /**
     * The base class for number formats.
     * 
     * @param <T> the number type.
     */
    static abstract class XMLNumberFormat<T> extends XMLFormat<T> {
        
        private final Constructor<T> _constructor;

        public XMLNumberFormat( final Constructor<T> constructor ) {
            _constructor = constructor;
        }
        
        /**
         * Creates a new instance from the associated constructor. The provided class is ignored, just
         * the provided {@link InputElement} is used to read the value which will be passed to the constructor.
         * 
         * @param clazz can be null for this {@link XMLFormat} implementation
         * @param xml the input element for the object to create.
         * @return a new number instance.
         */
        @Override
        public T newInstance( final Class<T> clazz, final javolution.xml.XMLFormat.InputElement xml ) throws XMLStreamException {
            return newInstanceFromAttribute( xml, "value" );
        }

        /**
         * Creates a new instance from an already associated constructor. The provided {@link InputElement} is
         * used to read the value from the attribute with the provided name. The value read will be passed to the
         * constructor of the object to create.
         * 
         * @param xml the input element for the object to create.
         * @param name the attribute name to read the value from.
         * @return a new number instance.
         */
        public T newInstanceFromAttribute( final javolution.xml.XMLFormat.InputElement xml, final String name )
            throws XMLStreamException {
            final Object value = getAttribute( name, xml );
            try {
                return _constructor.newInstance( value );
            } catch ( final Exception e ) {
                throw new XMLStreamException( e );
            }
        }
        
        protected abstract Object getAttribute( String name, InputElement xml ) throws XMLStreamException;

        /**
         * Does not perform anything, as the number is already created in {@link #newInstance(Class, javolution.xml.XMLFormat.InputElement)}.
         * 
         * @param xml the input element
         * @param the obj the created number object
         */
        @Override
        public void read( final javolution.xml.XMLFormat.InputElement xml, final T obj ) throws XMLStreamException {
            // nothing to do...
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void write( final T obj, final javolution.xml.XMLFormat.OutputElement xml ) throws XMLStreamException {
            xml.setAttribute( "value", obj.toString() );
        }
        
    }
    
    static class XMLNumberIntFormat<T> extends XMLNumberFormat<T> {
        
        public XMLNumberIntFormat( final Constructor<T> constructor ) {
            super( constructor );
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public Object getAttribute( final String name, final javolution.xml.XMLFormat.InputElement xml ) throws XMLStreamException {
            return xml.getAttribute( name, 0 );
        }
        
    }
    
    static class XMLNumberLongFormat<T> extends XMLNumberFormat<T> {
        
        public XMLNumberLongFormat( final Constructor<T> constructor ) {
            super( constructor );
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public Object getAttribute( final String name, final javolution.xml.XMLFormat.InputElement xml ) throws XMLStreamException {
            return xml.getAttribute( name, 0L );
        }
        
    }

}
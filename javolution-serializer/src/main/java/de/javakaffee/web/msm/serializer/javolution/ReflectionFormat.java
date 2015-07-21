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
import java.util.Arrays;
import java.util.Collection;
import java.util.Currency;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javolution.text.CharArray;
import javolution.text.TypeFormat;
import javolution.xml.XMLFormat;
import javolution.xml.sax.Attributes;
import javolution.xml.stream.XMLStreamException;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

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
 * @param <T> the type that is read/written by this {@link XMLFormat}.
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class ReflectionFormat<T> extends XMLFormat<T> {

    private static final Log LOG = LogFactory.getLog( ReflectionFormat.class );

    private static final Map<Class<?>, XMLNumberFormat<?>> NUMBER_FORMATS = new ConcurrentHashMap<Class<?>, XMLNumberFormat<?>>();
    private static final ReflectionFactory REFLECTION_FACTORY = ReflectionFactory.getReflectionFactory();
    private static final Object[] INITARGS = new Object[0];

    private final Constructor<T> _constructor;
    private final AttributeHandler[] _attributes;
    private final Field[] _elements;
    private final Map<String, Field> _attributesMap;

    /**
     * Creates a new instance for the provided class.
     *
     * @param clazz
     *            the Class that is supported by this {@link XMLFormat}.
     * @param classLoader
     *            the {@link ClassLoader} that is used to load user types.
     */
    @SuppressWarnings( "unchecked" )
    public ReflectionFormat( final Class<T> clazz, final ClassLoader classLoader ) {
        super( null );
        try {
            _constructor =
                    (Constructor<T>)REFLECTION_FACTORY.newConstructorForSerialization( clazz, Object.class.getDeclaredConstructor( new Class[0] ) );
            _constructor.setAccessible( true );
        } catch ( final SecurityException e ) {
            throw new RuntimeException( e );
        } catch ( final NoSuchMethodException e ) {
            throw new RuntimeException( e );
        }

        final AttributesAndElements fields = allFields( clazz );

        _attributes = fields.attributes.toArray( new AttributeHandler[fields.attributes.size()] );
        _elements = fields.elements.toArray( new Field[fields.elements.size()] );

        // no concurrency support required here, as we'll only read from the map
        _attributesMap = new HashMap<String, Field>( _attributes.length + 1 );
        for ( final AttributeHandler attribute : _attributes ) {
            _attributesMap.put( attribute._field.getName(), attribute._field );
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

    /**
     * A helper class to collect fields that are serialized as elements and
     * fields (or {@link AttributeHandler}s for fields) that are serialized
     * as attributes.
     */
    static class AttributesAndElements {
        private final Collection<AttributeHandler> attributes;
        private final Collection<Field> elements;

        AttributesAndElements() {
            attributes = new ArrayList<AttributeHandler>();
            elements = new ArrayList<Field>();
        }

        void add( final Field field ) {
            if ( isAttribute( field ) ) {
                final Class<?> fieldType = field.getType();
                if ( fieldType.isPrimitive() ) {

                    if ( fieldType == boolean.class ) {
                        attributes.add( new BooleanAttributeHandler( field ) );
                    } else if ( fieldType == int.class ) {
                        attributes.add( new IntAttributeHandler( field ) );
                    } else if ( fieldType == long.class ) {
                        attributes.add( new LongAttributeHandler( field ) );
                    } else if ( fieldType == float.class ) {
                        attributes.add( new FloatAttributeHandler( field ) );
                    } else if ( fieldType == double.class ) {
                        attributes.add( new DoubleAttributeHandler( field ) );
                    } else if ( fieldType == byte.class ) {
                        attributes.add( new ByteAttributeHandler( field ) );
                    } else if ( fieldType == char.class ) {
                        attributes.add( new CharAttributeHandler( field ) );
                    } else if ( fieldType == short.class ) {
                        attributes.add( new ShortAttributeHandler( field ) );
                    }
                } else {

                    if ( fieldType == String.class || fieldType == Character.class || fieldType == Boolean.class
                            || Number.class.isAssignableFrom( fieldType )
                            || fieldType == Currency.class ) {
                        attributes.add( new ToStringAttributeHandler( field ) );
                    } else if ( fieldType.isEnum() ) {
                        attributes.add( new EnumAttributeHandler( field ) );
                    } else {
                        throw new IllegalArgumentException( "Not yet supported as attribute: " + fieldType );
                    }

                }

            } else {
                elements.add( field );
            }
        }
    }

    protected static boolean isAttribute( final Field field ) {
        return isAttribute( field.getType() );
    }

    protected static boolean isAttribute( final Class<?> clazz ) {
        return clazz.isPrimitive() || clazz.isEnum() || clazz == String.class || clazz == Boolean.class || clazz == Integer.class
                || clazz == Long.class || clazz == Short.class || clazz == Double.class || clazz == Float.class
                || clazz == Character.class || clazz == Byte.class || clazz == Currency.class;
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
            if ( !name.equals( "class" ) && !name.equals( JavolutionTranscoder.REFERENCE_ATTRIBUTE_ID ) ) {
                final Field field = _attributesMap.get( name.toString() );
                if ( field != null ) {
                    setFieldFromAttribute( obj, field, input );
                } else {
                    LOG.warn( "Did not find field " + name + ", attribute value is " + attributes.getValue( i ) );
                }
            }
        }
    }

    private void readElements( final javolution.xml.XMLFormat.InputElement input, final T obj ) {
        for ( final Field field : _elements ) {
            try {
                final Object value = input.get( field.getName() );
                field.set( obj, value );
            } catch ( final Exception e ) {
                LOG.error( "Could not set field value for field " + field, e );
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
        for ( final AttributeHandler handler : _attributes ) {
            try {
                handler.writeAttribute( obj, output );
            } catch ( final Exception e ) {
                LOG.error( "Could not set attribute from field value.", e );
            }
        }
    }

    private void writeElements( final T obj, final javolution.xml.XMLFormat.OutputElement output ) {
        for ( final Field field : _elements ) {
            try {
                final Object object = field.get( obj );
                if ( object != null ) {
                    output.add( object, field.getName() );
                }
            } catch ( final Exception e ) {
                LOG.error( "Could not write element for field.", e );
            }
        }
    }

    static abstract class AttributeHandler {
        protected final Field _field;

        public AttributeHandler( final Field field ) {
            _field = field;
        }

        abstract void writeAttribute( final Object obj, final XMLFormat.OutputElement output ) throws IllegalArgumentException,
            XMLStreamException, IllegalAccessException;
    }

    static final class BooleanAttributeHandler extends AttributeHandler {
        public BooleanAttributeHandler( final Field field ) {
            super( field );
        }

        @Override
        void writeAttribute( final Object obj, final XMLFormat.OutputElement output ) throws IllegalArgumentException,
            XMLStreamException, IllegalAccessException {
            output.setAttribute( _field.getName(), _field.getBoolean( obj ) );
        }
    }

    static final class IntAttributeHandler extends AttributeHandler {
        public IntAttributeHandler( final Field field ) {
            super( field );
        }

        @Override
        void writeAttribute( final Object obj, final XMLFormat.OutputElement output ) throws IllegalArgumentException,
            XMLStreamException, IllegalAccessException {
            output.setAttribute( _field.getName(), _field.getInt( obj ) );
        }
    }

    static final class LongAttributeHandler extends AttributeHandler {
        public LongAttributeHandler( final Field field ) {
            super( field );
        }

        @Override
        void writeAttribute( final Object obj, final XMLFormat.OutputElement output ) throws IllegalArgumentException,
            XMLStreamException, IllegalAccessException {
            output.setAttribute( _field.getName(), _field.getLong( obj ) );
        }
    }

    static final class FloatAttributeHandler extends AttributeHandler {
        public FloatAttributeHandler( final Field field ) {
            super( field );
        }

        @Override
        void writeAttribute( final Object obj, final XMLFormat.OutputElement output ) throws IllegalArgumentException,
            XMLStreamException, IllegalAccessException {
            output.setAttribute( _field.getName(), _field.getFloat( obj ) );
        }
    }

    static final class DoubleAttributeHandler extends AttributeHandler {
        public DoubleAttributeHandler( final Field field ) {
            super( field );
        }

        @Override
        void writeAttribute( final Object obj, final XMLFormat.OutputElement output ) throws IllegalArgumentException,
            XMLStreamException, IllegalAccessException {
            output.setAttribute( _field.getName(), _field.getDouble( obj ) );
        }
    }

    static final class ByteAttributeHandler extends AttributeHandler {
        public ByteAttributeHandler( final Field field ) {
            super( field );
        }

        @Override
        void writeAttribute( final Object obj, final XMLFormat.OutputElement output ) throws IllegalArgumentException,
            XMLStreamException, IllegalAccessException {
            output.setAttribute( _field.getName(), _field.getByte( obj ) );
        }
    }

    static final class CharAttributeHandler extends AttributeHandler {
        public CharAttributeHandler( final Field field ) {
            super( field );
        }

        @Override
        void writeAttribute( final Object obj, final XMLFormat.OutputElement output ) throws IllegalArgumentException,
            XMLStreamException, IllegalAccessException {
            output.setAttribute( _field.getName(), _field.getChar( obj ) );
        }
    }

    static final class ShortAttributeHandler extends AttributeHandler {
        public ShortAttributeHandler( final Field field ) {
            super( field );
        }

        @Override
        void writeAttribute( final Object obj, final XMLFormat.OutputElement output ) throws IllegalArgumentException,
            XMLStreamException, IllegalAccessException {
            output.setAttribute( _field.getName(), _field.getShort( obj ) );
        }
    }

    static abstract class ObjectAttributeHandler extends AttributeHandler {
        public ObjectAttributeHandler( final Field field ) {
            super( field );
        }

        @Override
        void writeAttribute( final Object obj, final XMLFormat.OutputElement output ) throws IllegalArgumentException,
            XMLStreamException, IllegalAccessException {
            final Object object = _field.get( obj );
            if ( object != null ) {
                add( object, output );
            }
        }

        abstract void add( Object object, OutputElement output ) throws XMLStreamException;
    }

    static final class ToStringAttributeHandler extends ObjectAttributeHandler {
        public ToStringAttributeHandler( final Field field ) {
            super( field );
        }

        @Override
        void add( final Object object, final OutputElement output ) throws XMLStreamException {
            output.setAttribute( _field.getName(), object.toString() );
        }
    }

    static final class EnumAttributeHandler extends ObjectAttributeHandler {
        public EnumAttributeHandler( final Field field ) {
            super( field );
        }

        @Override
        void add( final Object object, final OutputElement output ) throws XMLStreamException {
            output.setAttribute( _field.getName(), ( (Enum<?>) object ).name() );
        }
    }

    @edu.umd.cs.findbugs.annotations.SuppressWarnings( "REC_CATCH_EXCEPTION" )
    private void setFieldFromAttribute( final T obj, final Field field, final javolution.xml.XMLFormat.InputElement input ) {

        try {

            final String fieldName = field.getName();
            final Class<?> fieldType = field.getType();
            if ( fieldType.isPrimitive() ) {

                if ( fieldType == boolean.class ) {
                    field.setBoolean( obj, input.getAttribute( fieldName, false ) );
                } else if ( fieldType == int.class ) {
                    field.setInt( obj, input.getAttribute( fieldName, 0 ) );
                } else if ( fieldType == long.class ) {
                    field.setLong( obj, input.getAttribute( fieldName, (long) 0 ) );
                } else if ( fieldType == float.class ) {
                    field.setFloat( obj, input.getAttribute( fieldName, (float) 0 ) );
                } else if ( fieldType == double.class ) {
                    field.setDouble( obj, input.getAttribute( fieldName, (double) 0 ) );
                } else if ( fieldType == byte.class ) {
                    field.setByte( obj, input.getAttribute( fieldName, (byte) 0 ) );
                } else if ( fieldType == char.class ) {
                    field.setChar( obj, input.getAttribute( fieldName, (char) 0 ) );
                } else if ( fieldType == short.class ) {
                    field.setShort( obj, input.getAttribute( fieldName, (short) 0 ) );
                }
            } else if ( fieldType.isEnum() ) {
                final String value = input.getAttribute( fieldName, (String) null );
                if ( value != null ) {
                    @SuppressWarnings( "unchecked" )
                    final Enum<?> enumValue = Enum.valueOf( fieldType.asSubclass( Enum.class ), value );
                    field.set( obj, enumValue );
                }
            } else {

                final CharArray object = input.getAttribute( fieldName );

                if ( object != null ) {
                    if ( fieldType == String.class ) {
                        field.set( obj, getAttribute( input, fieldName, (String) null ) );
                    } else if ( fieldType.isAssignableFrom( Boolean.class ) ) {
                        field.set( obj, getAttribute( input, fieldName, (Boolean) null ) );
                        field.set( obj, getAttribute( input, fieldName, (Boolean) null ) );
                    } else if ( fieldType.isAssignableFrom( Integer.class ) ) {
                        field.set( obj, getAttribute( input, fieldName, (Integer) null ) );
                    } else if ( fieldType.isAssignableFrom( Long.class ) ) {
                        field.set( obj, getAttribute( input, fieldName, (Long) null ) );
                    } else if ( fieldType.isAssignableFrom( Short.class ) ) {
                        field.set( obj, getAttribute( input, fieldName, (Short) null ) );
                    } else if ( fieldType.isAssignableFrom( Double.class ) ) {
                        field.set( obj, getAttribute( input, fieldName, (Double) null ) );
                    } else if ( fieldType.isAssignableFrom( Float.class ) ) {
                        field.set( obj, getAttribute( input, fieldName, (Float) null ) );
                    } else if ( fieldType.isAssignableFrom( Byte.class ) ) {
                        field.set( obj, getAttribute( input, fieldName, (Byte) null ) );
                    } else if ( fieldType.isAssignableFrom( Character.class ) ) {
                        field.set( obj, getAttribute( input, fieldName, (Character) null ) );
                    } else if ( Number.class.isAssignableFrom( fieldType ) ) {
                        @SuppressWarnings( "unchecked" )
                        final XMLNumberFormat<?> format = getNumberFormat( (Class<? extends Number>) fieldType );
                        field.set( obj, format.newInstanceFromAttribute( input, fieldName ) );
                    } else if ( fieldType == Currency.class ) {
                        field.set( obj, Currency.getInstance( object.toString() ) );
                    } else {
                        throw new IllegalArgumentException( "Not yet supported as attribute: " + fieldType );
                    }
                }
            }

        } catch ( final Exception e ) {
            try {
                LOG.error( "Caught exception when trying to set field ("+ field +") from attribute ("+ input.getAttribute( field.getName() )+").", e );
            } catch ( final XMLStreamException e1 ) {
                // fail silently
            }
        }
    }

    private String getAttribute( final InputElement input, final String name, final String defaultValue ) throws XMLStreamException {
        final CharArray value = input.getAttribute( name );
        return value != null ? value.toString() : defaultValue;
    }

    private Boolean getAttribute( final InputElement input, final String name, final Boolean defaultValue ) throws XMLStreamException {
        final CharArray value = input.getAttribute( name );
        return value != null ? Boolean.valueOf( value.toBoolean() ) : defaultValue;
    }

    private Integer getAttribute( final InputElement input, final String name, final Integer defaultValue ) throws XMLStreamException {
        final CharArray value = input.getAttribute( name );
        return value != null ? Integer.valueOf( value.toInt() ) : defaultValue;
    }

    private Long getAttribute( final InputElement input, final String name, final Long defaultValue ) throws XMLStreamException {
        final CharArray value = input.getAttribute( name );
        return value != null ? Long.valueOf( value.toLong() ) : defaultValue;
    }

    private Short getAttribute( final InputElement input, final String name, final Short defaultValue ) throws XMLStreamException {
        final CharArray value = input.getAttribute( name );
        return value != null ? Short.valueOf( TypeFormat.parseShort( value ) ) : defaultValue;
    }

    private Float getAttribute( final InputElement input, final String name, final Float defaultValue ) throws XMLStreamException {
        final CharArray value = input.getAttribute( name );
        return value != null ? Float.valueOf( value.toFloat() ) : defaultValue;
    }

    private Double getAttribute( final InputElement input, final String name, final Double defaultValue ) throws XMLStreamException {
        final CharArray value = input.getAttribute( name );
        return value != null ? Double.valueOf( value.toDouble() ) : defaultValue;
    }

    private Byte getAttribute( final InputElement input, final String name, final Byte defaultValue ) throws XMLStreamException {
        final CharArray value = input.getAttribute( name );
        return value != null ? Byte.valueOf( TypeFormat.parseByte( value ) ) : defaultValue;
    }

    private Character getAttribute( final InputElement input, final String name, final Character defaultValue ) throws XMLStreamException {
        final CharArray value = input.getAttribute( name );
        if ( value != null ) {
            if ( value.length() > 1 ) {
                throw new XMLStreamException( "The attribute '" + name + "' of type Character has illegal value (length > 1): " + value );
            }
            return Character.valueOf( value.charAt( 0 ) );
        }
        return defaultValue;
    }

    /**
     * Used to determine, if the given class can be serialized using the
     * {@link XMLNumberFormat}.
     *
     * @param clazz
     *            the class that is to be checked
     * @return
     */
    static boolean isNumberFormat( final Class<?> clazz ) {
        return Number.class.isAssignableFrom( clazz );
    }

    static XMLNumberFormat<?> getNumberFormat( final Class<? extends Number> clazz ) {
        XMLNumberFormat<? extends Number> result = NUMBER_FORMATS.get( clazz );
        if ( result == null ) {
            result = createNumberFormat( clazz );
            NUMBER_FORMATS.put( clazz, result );
        }
        return result;
    }

    @SuppressWarnings( "unchecked" )
    static <T extends Number> XMLNumberFormat<T> createNumberFormat( final Class<T> clazz ) {
        try {
            for ( final Constructor<?> constructor : clazz.getConstructors() ) {
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
        throw new IllegalArgumentException( "No suitable constructor found for class " + clazz.getName() + ".\n"
                + "Available constructors: " + Arrays.toString( clazz.getConstructors() ) );
    }

    /**
     * The base class for number formats.
     *
     * @param <T>
     *            the number type.
     */
    static abstract class XMLNumberFormat<T extends Number> extends XMLFormat<T> {

        private final Constructor<T> _constructor;

        public XMLNumberFormat( final Constructor<T> constructor ) {
            super( null );
            _constructor = constructor;
        }

        /**
         * Creates a new instance from the associated constructor. The provided
         * class is ignored, just the provided {@link InputElement} is used to
         * read the value which will be passed to the constructor.
         *
         * @param clazz
         *            can be null for this {@link XMLFormat} implementation
         * @param xml
         *            the input element for the object to create.
         * @return a new number instance.
         */
        @Override
        public T newInstance( final Class<T> clazz, final javolution.xml.XMLFormat.InputElement xml ) throws XMLStreamException {
            return newInstanceFromAttribute( xml, "value" );
        }

        /**
         * Creates a new instance from an already associated constructor. The
         * provided {@link InputElement} is used to read the value from the
         * attribute with the provided name. The value read will be passed to
         * the constructor of the object to create.
         *
         * @param xml
         *            the input element for the object to create.
         * @param name
         *            the attribute name to read the value from.
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
         * Does not perform anything, as the number is already created in
         * {@link #newInstance(Class, javolution.xml.XMLFormat.InputElement)}.
         *
         * @param xml
         *            the input element
         * @param the
         *            obj the created number object
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
            xml.setAttribute( "value", obj.longValue() );
        }

    }

    static class XMLNumberIntFormat<T extends Number> extends XMLNumberFormat<T> {

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

    static class XMLNumberLongFormat<T extends Number> extends XMLNumberFormat<T> {

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
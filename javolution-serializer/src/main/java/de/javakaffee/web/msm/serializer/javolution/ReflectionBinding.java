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
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.GregorianCalendar;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import javolution.lang.Reflection;
import javolution.text.CharArray;
import javolution.xml.XMLBinding;
import javolution.xml.XMLFormat;
import javolution.xml.stream.XMLStreamException;
import javolution.xml.stream.XMLStreamReader;
import javolution.xml.stream.XMLStreamWriter;
import sun.reflect.ReflectionFactory;

/**
 * An {@link XMLBinding} that provides class bindings based on reflection.
 * 
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class ReflectionBinding extends XMLBinding {
    
    private static final long serialVersionUID = -7047053153745571559L;

    private static final Logger _log = Logger.getLogger( ReflectionBinding.class.getName() );
    
    private static final ReflectionFactory REFLECTION_FACTORY = ReflectionFactory.getReflectionFactory();
    private static final Object[] INITARGS = new Object[0];
    
    private static final XMLCalendarFormat CALENDAR_FORMAT = new XMLCalendarFormat();

    private final Map<Class<?>, XMLFormat<?>> _formats = new ConcurrentHashMap<Class<?>, XMLFormat<?>>();

    private final ClassLoader _classLoader;
    private final XMLEnumFormat _enumFormat;
    private final XMLArrayFormat _arrayFormat;
    private final XMLCollectionFormat _collectionFormat;
    private final XMLMapFormat _mapFormat;
    private final XMLJdkProxyFormat _jdkProxyFormat;
    private final XMLFormat<?>[] _customFormats;

    public ReflectionBinding( final ClassLoader classLoader ) {
        this( classLoader, false, null );
    }

    public ReflectionBinding( final ClassLoader classLoader, final boolean copyCollectionsForSerialization,
            final XMLFormat<?>[] customFormats ) {
        _classLoader = classLoader;
        _enumFormat = new XMLEnumFormat( classLoader );
        _arrayFormat = new XMLArrayFormat( classLoader );
        _collectionFormat = new XMLCollectionFormat( copyCollectionsForSerialization );
        _mapFormat = new XMLMapFormat( copyCollectionsForSerialization );
        _jdkProxyFormat = new XMLJdkProxyFormat( classLoader );
        _customFormats = customFormats;
        
        Reflection.getInstance().add( classLoader );
    }

    /**
     * {@inheritDoc}
     */
    @SuppressWarnings( "unchecked" )
    @Override
    protected void writeClass( Class cls, final XMLStreamWriter writer, final boolean useAttributes ) throws XMLStreamException {
        if ( Proxy.isProxyClass( cls ) ) {
            cls = Proxy.class;
        }
        if ( useAttributes ) {
            writer.writeAttribute( "class", cls.getName() );
        } else {
            writer.writeStartElement( cls.getName() );
        }
    }

    /**
     * {@inheritDoc}
     */
    @SuppressWarnings( "unchecked" )
    @Override
    protected Class readClass( final XMLStreamReader reader, final boolean useAttributes ) throws XMLStreamException {
        final CharArray className = useAttributes
            ? reader.getAttributeValue( null, "class" )
            : reader.getLocalName();
        try {
            return Class.forName( className.toString(), true, _classLoader );
        } catch ( final ClassNotFoundException e ) {
            throw new XMLStreamException( e );
        }
    }

    @SuppressWarnings( "unchecked" )
    @Override
    public XMLFormat<?> getFormat( final Class cls ) throws XMLStreamException {

        XMLFormat<?> xmlFormat = _formats.get( cls );
        if ( xmlFormat != null ) {
            return xmlFormat;
        }

        if ( cls.isPrimitive()
                || cls == String.class
                || cls == Boolean.class
                || cls == Integer.class
                || cls == Long.class
                || cls == Short.class
                || cls == Double.class
                || cls == Float.class
                || cls == Character.class
                || cls == Byte.class
                || cls == Class.class ) {
            return super.getFormat( cls );
        } else if ( cls.isArray() ) {
            return getArrayFormat( cls );
        } else if ( Collection.class.isAssignableFrom( cls )
                && Modifier.isPublic( cls.getModifiers() ) ) {
            // the check for the private modifier is required, so that
            // lists like Arrays.ArrayList are handled by the ReflectionFormat
            return _collectionFormat;
        }  else if ( Map.class.isAssignableFrom( cls )
                && Modifier.isPublic( cls.getModifiers() ) ) {
            return _mapFormat;
        } else if ( cls.isEnum() ) {
            return _enumFormat;
        } else if ( Calendar.class.isAssignableFrom( cls ) ) {
            return CALENDAR_FORMAT;
        } else if ( Proxy.isProxyClass( cls ) || cls == Proxy.class ) {
            /* the Proxy.isProxyClass check is required for serialization,
             * Proxy.class is required for deserialization
             */
            return _jdkProxyFormat;
        } else if ( ( xmlFormat = getCustomFormat( cls ) ) != null ) {
            return xmlFormat;
        } else {
            if ( xmlFormat == null ) {
                if ( ReflectionFormat.isNumberFormat( cls ) ) {
                    xmlFormat = ReflectionFormat.getNumberFormat( cls );
                } else {
                    xmlFormat = new ReflectionFormat( cls, _classLoader );
                }
                _formats.put( cls, xmlFormat );
            }
            return xmlFormat;
        }
    }
    
    private XMLFormat<?> getCustomFormat( final Class<?> cls ) {
        if ( _customFormats == null ) {
            return null;
        }
        for( XMLFormat<?> xmlFormat : _customFormats ) {
            if ( xmlFormat.getBoundClass() == cls ) {
                return xmlFormat;
            }
        }
        return null;
    }

    @SuppressWarnings( "unchecked" )
    private XMLFormat getArrayFormat( final Class cls ) {
        if ( cls == int[].class ) {
            return XMLArrayFormats.INT_ARRAY_FORMAT;
        } else if ( cls == long[].class ) {
            return XMLArrayFormats.LONG_ARRAY_FORMAT;
        } else if ( cls == short[].class ) {
            return XMLArrayFormats.SHORT_ARRAY_FORMAT;
        } else if ( cls == float[].class ) {
            return XMLArrayFormats.FLOAT_ARRAY_FORMAT;
        } else if ( cls == double[].class ) {
            return XMLArrayFormats.DOUBLE_ARRAY_FORMAT;
        } else if ( cls == char[].class ) {
            return XMLArrayFormats.CHAR_ARRAY_FORMAT;
        } else if ( cls == byte[].class ) {
            return XMLArrayFormats.BYTE_ARRAY_FORMAT;
        } else {
            return _arrayFormat;
        }
    }

    static class XMLEnumFormat extends XMLFormat<Enum<?>> {

        private final ClassLoader _classLoader;

        public XMLEnumFormat( final ClassLoader classLoader ) {
            super( null );
            _classLoader = classLoader;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public Enum<?> newInstance( final Class<Enum<?>> clazz, final javolution.xml.XMLFormat.InputElement xml ) throws XMLStreamException {
            final String value = xml.getAttribute( "value" ).toString();
            final String clazzName = xml.getAttribute( "type" ).toString();
            try {
                @SuppressWarnings( "unchecked" )
                final Enum<?> enumValue = Enum.valueOf( Class.forName( clazzName, true, _classLoader ).asSubclass( Enum.class ), value );
                return enumValue;
            } catch ( final ClassNotFoundException e ) {
                throw new XMLStreamException( e );
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void read( final javolution.xml.XMLFormat.InputElement xml, final Enum<?> object ) throws XMLStreamException {
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void write( final Enum<?> object, final javolution.xml.XMLFormat.OutputElement xml ) throws XMLStreamException {
            xml.setAttribute( "value", object.name() );
            xml.setAttribute( "type", object.getClass().getName() );
        }

    }

    public static class XMLArrayFormat extends XMLFormat<Object[]> {

        private final ClassLoader _classLoader;

        public XMLArrayFormat( final ClassLoader classLoader ) {
            super( null );
            _classLoader = classLoader;
        }

        /**
         * {@inheritDoc}
         */
        @SuppressWarnings( "unchecked" )
        @Override
        public Object[] newInstance( final Class clazz, final javolution.xml.XMLFormat.InputElement input ) throws XMLStreamException {
            try {
                final String componentType = input.getAttribute( "componentType", (String) null );
                final int length = input.getAttribute( "length", 0 );
                return (Object[]) Array.newInstance( Class.forName( componentType, false, _classLoader ), length );
            } catch ( final Exception e ) {
                _log.log( Level.SEVERE, "caught exception", e );
                throw new XMLStreamException( e );
            }
        }

        @Override
        public void read( final javolution.xml.XMLFormat.InputElement input, final Object[] array ) throws XMLStreamException {
            int i = 0;
            while ( input.hasNext() ) {
                array[i++] = input.getNext();
            }
        }

        @Override
        public final void write( final Object[] array, final javolution.xml.XMLFormat.OutputElement output )
            throws XMLStreamException {
            output.setAttribute( "type", "array" );
            output.setAttribute( "componentType", array.getClass().getComponentType().getName() );
            output.setAttribute( "length", array.length );
            writeElements( array, output );
        }

        public void writeElements( final Object[] array, final javolution.xml.XMLFormat.OutputElement output )
            throws XMLStreamException {
            for ( final Object item : array ) {
                output.add( item );
            }
        }

    }
    
    public static class XMLCollectionFormat extends XMLFormat<Collection<Object>> {
        
        private final boolean _copyForWrite;

        protected XMLCollectionFormat( final boolean copyForWrite ) {
            super( null );
            _copyForWrite = copyForWrite;
        }
        
        /**
         * {@inheritDoc}
         */
        @SuppressWarnings( "unchecked" )
        @Override
        public Collection<Object> newInstance( final Class<Collection<Object>> cls, final javolution.xml.XMLFormat.InputElement xml )
            throws XMLStreamException {
            if ( Modifier.isPrivate( cls.getModifiers() ) ) {
                try {
                    final Constructor<?> constructor = REFLECTION_FACTORY.newConstructorForSerialization( cls, Object.class.getDeclaredConstructor( new Class[0] ) );
                    constructor.setAccessible( true );
                    return (Collection<Object>) constructor.newInstance( INITARGS );
                } catch ( final Exception e ) {
                    throw new XMLStreamException( e );
                }
            }
            else {
                return super.newInstance( cls, xml );
            }
        }

        @Override
        public void read( final javolution.xml.XMLFormat.InputElement xml, final Collection<Object> obj ) throws XMLStreamException {
            while ( xml.hasNext() ) {
                obj.add( xml.getNext() );
            }
        }

        @Override
        public void write( final Collection<Object> obj, final javolution.xml.XMLFormat.OutputElement xml )
            throws XMLStreamException {
            for ( final Object item : _copyForWrite ? new ArrayList<Object>( obj ) : obj ) {
                xml.add( item );
            }
        }

    }
    
    public static class XMLMapFormat extends XMLFormat<Map<Object,Object>> {
        
        private final boolean _copyForWrite;

        protected XMLMapFormat( final boolean copyForWrite ) {
            super( null );
            _copyForWrite = copyForWrite;
        }
        
        /**
         * {@inheritDoc}
         */
        @SuppressWarnings( "unchecked" )
        @Override
        public Map<Object, Object> newInstance( final Class<Map<Object, Object>> cls, final javolution.xml.XMLFormat.InputElement xml )
            throws XMLStreamException {
            if ( Modifier.isPrivate( cls.getModifiers() ) ) {
                try {
                    final Constructor<?> constructor = REFLECTION_FACTORY.newConstructorForSerialization( cls, Object.class.getDeclaredConstructor( new Class[0] ) );
                    constructor.setAccessible( true );
                    return (Map<Object, Object>) constructor.newInstance( INITARGS );
                } catch ( final Exception e ) {
                    throw new XMLStreamException( e );
                }
            }
            else {
                return super.newInstance( cls, xml );
            }
        }

        @Override
        public void read( final javolution.xml.XMLFormat.InputElement xml, final Map<Object,Object> obj ) throws XMLStreamException {
            while ( xml.hasNext() ) {
                obj.put(xml.get("k"), xml.get("v"));
            }
        }

        @Override
        public void write( final Map<Object,Object> obj, final javolution.xml.XMLFormat.OutputElement xml )
            throws XMLStreamException {
            final Set<Entry<Object, Object>> entrySet = _copyForWrite ? new LinkedHashMap<Object, Object>( obj ).entrySet() : obj.entrySet();
            for ( final Map.Entry<Object, Object> entry : entrySet ) {
                xml.add( entry.getKey(), "k" );
                xml.add( entry.getValue(), "v" );
            }
        }

    }

    /**
     * An {@link XMLFormat} for {@link Calendar} that serialized those calendar
     * fields that contain actual data (these fields also are used by
     * {@link Calendar#equals(Object)}.
     */
    public static class XMLCalendarFormat extends XMLFormat<Calendar> {

        private final Field _zoneField;

        public XMLCalendarFormat() {
            super( Calendar.class );
            try {
                _zoneField = Calendar.class.getDeclaredField( "zone" );
                _zoneField.setAccessible( true );
            } catch ( final Exception e ) {
                throw new RuntimeException( e );
            }
        }

        @Override
        public Calendar newInstance( final Class<Calendar> clazz, final javolution.xml.XMLFormat.InputElement arg1 ) throws XMLStreamException {
            if ( clazz.equals( GregorianCalendar.class ) ) {
                return GregorianCalendar.getInstance();
            }
            throw new IllegalArgumentException( "Calendar of type " + clazz.getName()
                    + " not yet supported. Please submit an issue so that it will be implemented." );
        }

        @Override
        public void read( final javolution.xml.XMLFormat.InputElement xml, final Calendar obj ) throws XMLStreamException {
            /* check if we actually need to set the timezone, as
             * TimeZone.getTimeZone is synchronized, so we might prevent this
             */
            final String timeZoneId = xml.getAttribute( "tz", "" );
            if ( !getTimeZone( obj ).getID().equals( timeZoneId ) ) {
                obj.setTimeZone( TimeZone.getTimeZone( timeZoneId ) );
            }
            obj.setMinimalDaysInFirstWeek( xml.getAttribute( "minimalDaysInFirstWeek", -1 ) );
            obj.setFirstDayOfWeek( xml.getAttribute( "firstDayOfWeek", -1 ) );
            obj.setLenient( xml.getAttribute( "lenient", true ) );
            obj.setTimeInMillis( xml.getAttribute( "timeInMillis", -1L ) );
        }

        @Override
        public void write( final Calendar obj, final javolution.xml.XMLFormat.OutputElement xml ) throws XMLStreamException {

            if ( !obj.getClass().equals( GregorianCalendar.class ) ) {
                throw new IllegalArgumentException( "Calendar of type " + obj.getClass().getName()
                        + " not yet supported. Please submit an issue so that it will be implemented." );
            }

            xml.setAttribute( "timeInMillis", obj.getTimeInMillis() );
            xml.setAttribute( "lenient", obj.isLenient() );
            xml.setAttribute( "firstDayOfWeek", obj.getFirstDayOfWeek() );
            xml.setAttribute( "minimalDaysInFirstWeek", obj.getMinimalDaysInFirstWeek() );
            xml.setAttribute( "tz", getTimeZone( obj ).getID() );
        }

        private TimeZone getTimeZone( final Calendar obj ) throws XMLStreamException {
            /* access the timezone via the field, to prevent cloning of the tz */
            try {
                return (TimeZone) _zoneField.get( obj );
            } catch ( final Exception e ) {
                throw new XMLStreamException( e );
            }
        }

    }

    public static final class XMLJdkProxyFormat extends XMLFormat<Object> {

        private final ClassLoader _classLoader;

        public XMLJdkProxyFormat( final ClassLoader classLoader ) {
            super( null );
            _classLoader = classLoader;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean isReferenceable() {
            return false;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public Object newInstance( final Class<Object> clazz, final javolution.xml.XMLFormat.InputElement input )
            throws XMLStreamException {
            final InvocationHandler invocationHandler = input.get( "handler" );
            final Class<?>[] interfaces = getInterfaces( input );
            return Proxy.newProxyInstance( _classLoader, interfaces, invocationHandler );
        }

        private Class<?>[] getInterfaces( final javolution.xml.XMLFormat.InputElement input ) throws XMLStreamException {
            final String[] interfaceNames = input.get( "interfaces" );
            if ( interfaceNames != null ) {
                try {
                    final Class<?>[] interfaces = new Class<?>[interfaceNames.length];
                    for ( int i = 0; i < interfaceNames.length; i++ ) {
                        interfaces[i] = Class.forName( interfaceNames[i], true, _classLoader );
                    }
                    return interfaces;
                } catch ( final ClassNotFoundException e ) {
                    throw new XMLStreamException( e );
                }
            }
            return new Class<?>[0];
        }

        @Override
        public void read( final javolution.xml.XMLFormat.InputElement input, final Object obj ) throws XMLStreamException {
            // nothing to do
        }

        @Override
        public final void write( final Object obj, final javolution.xml.XMLFormat.OutputElement output ) throws XMLStreamException {
            final InvocationHandler invocationHandler = Proxy.getInvocationHandler( obj );
            output.add( invocationHandler, "handler" );
            final String[] interfaceNames = getInterfaceNames( obj );
            output.add( interfaceNames, "interfaces" );
        }

        private String[] getInterfaceNames( final Object obj ) {
            final Class<?>[] interfaces = obj.getClass().getInterfaces();
            if ( interfaces != null ) {
                final String[] interfaceNames = new String[interfaces.length];
                for ( int i = 0; i < interfaces.length; i++ ) {
                    interfaceNames[i] = interfaces[i].getName();
                }
                return interfaceNames;
            }
            return new String[0];
        }

    }

}
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
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import javolution.xml.XMLBinding;
import javolution.xml.XMLFormat;
import javolution.xml.stream.XMLStreamException;

/**
 * An {@link XMLBinding} that provides class bindings based on reflection.
 * 
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class ReflectionBinding extends XMLBinding {
    
    private static final Logger _log = Logger.getLogger( ReflectionBinding.class.getName() );
    
    private final Map<Class<?>, XMLFormat<?>> _formats = new ConcurrentHashMap<Class<?>, XMLFormat<?>>();

    private final ClassLoader _classLoader;
    private final XMLEnumFormat _enumFormat;
    private final XMLArrayFormat _arrayFormat;
    
    public ReflectionBinding(final ClassLoader classLoader) {
        _classLoader = classLoader;
        _enumFormat = new XMLEnumFormat( classLoader );
        _arrayFormat = new XMLArrayFormat( classLoader );
    }

    @SuppressWarnings( "unchecked" )
    @Override
    public <T> XMLFormat<T> getFormat(final Class<T> cls) {
        
        final XMLFormat<T> format = super.getFormat( cls );
        
        if ( cls.isPrimitive() || cls.equals( String.class ) || Number.class.isAssignableFrom( cls )
                || Map.class.isAssignableFrom( cls ) || Collection.class.isAssignableFrom( cls ) ) {
            return format;
        }
        else if ( cls.isArray() ) {
            return (XMLFormat<T>) _arrayFormat;
        }
        else if ( cls.isEnum() ) {
            return (XMLFormat<T>) _enumFormat;
        }
        else {
            XMLFormat<?> xmlFormat = _formats.get( cls );
            if ( xmlFormat == null ) {
                xmlFormat = new ReflectionFormat( cls, _classLoader );
                _formats.put( cls, xmlFormat );
            }
            return (XMLFormat<T>) xmlFormat;
        }
    }
    
    static class XMLEnumFormat extends XMLFormat<Enum<?>> {

        private final ClassLoader _classLoader;
        
        public XMLEnumFormat( final ClassLoader classLoader ) {
            _classLoader = classLoader;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public Enum<?> newInstance( final Class<Enum<?>> clazz, final javolution.xml.XMLFormat.InputElement xml ) throws XMLStreamException {
            final String value = xml.getAttribute( "value", (String)null );
            final String clazzName = xml.getAttribute( "type", (String)null );
            try {
                @SuppressWarnings( "unchecked" )
                final Enum<?> enumValue = Enum.valueOf( Class.forName( clazzName ).asSubclass( Enum.class ), value );
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
    
    public static class XMLArrayFormat extends XMLFormat<Object> {

        private final ClassLoader _classLoader;
        
        public XMLArrayFormat(final ClassLoader classLoader) {
            _classLoader = classLoader;
        }
        
        /**
         * {@inheritDoc}
         */
        @SuppressWarnings( "unchecked" )
        @Override
        public Object newInstance( final Class clazz, final javolution.xml.XMLFormat.InputElement input ) throws XMLStreamException {
            try {
                final String componentType = input.getAttribute( "componentType", (String)null );
                final int length = input.getAttribute( "length", 0 );
                return Array.newInstance( Class.forName( componentType, true, _classLoader ) , length );
            } catch ( final Exception e ) {
                _log.log( Level.SEVERE, "caught exception", e );
                throw new XMLStreamException( e );
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void read( final javolution.xml.XMLFormat.InputElement input, final Object obj ) throws XMLStreamException {

            final Object[] arr = (Object[]) obj;
            int i = 0;
            while ( input.hasNext() ) {
                arr[i++] = input.getNext();
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void write( final Object obj, final javolution.xml.XMLFormat.OutputElement output ) throws XMLStreamException {
            final Object[] array = (Object[]) obj;
            output.setAttribute( "type", "array" );
            output.setAttribute( "componentType", obj.getClass().getComponentType().getName() );
            output.setAttribute("length", array.length);
            for( final Object item : array ) {
                output.add( item );
            }
        }
        
    }
    
    public static class XMLCollectionFormat extends XMLFormat<Collection<Object>> {
        
        @Override
        public void read( final javolution.xml.XMLFormat.InputElement xml, final Collection<Object> obj ) throws XMLStreamException {
            while ( xml.hasNext() ) {
                obj.add( xml.getNext() );
            }
        }
        
        @Override
        public void write( final Collection<Object> obj, final javolution.xml.XMLFormat.OutputElement xml ) throws XMLStreamException {
            for( final Object item : obj ) {
                xml.add( item );
            }
        }
        
    }
    
}
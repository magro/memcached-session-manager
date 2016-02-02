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
package de.javakaffee.web.msm.serializer.javolution;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javolution.xml.XMLFormat;
import javolution.xml.stream.XMLStreamException;

import org.hibernate.collection.internal.AbstractPersistentCollection;

/**
 * A {@link CustomXMLFormat} that handles hibernate mapped collections (subclasses
 * of {@link AbstractPersistentCollection}).
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class HibernateCollectionsXMLFormat extends CustomXMLFormat<AbstractPersistentCollection> {

    private final Map<Class<? extends AbstractPersistentCollection>, XMLFormat<AbstractPersistentCollection>> _formats = new ConcurrentHashMap<Class<? extends AbstractPersistentCollection>, XMLFormat<AbstractPersistentCollection>>();

    @Override
    public boolean canConvert( final Class<?> cls ) {
        return AbstractPersistentCollection.class.isAssignableFrom( cls );
    }

    @Override
    public void read( final XMLFormat.InputElement input, final AbstractPersistentCollection obj ) throws XMLStreamException {
        getFormat( obj.getClass() ).read( input, obj );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void write( final AbstractPersistentCollection obj, final javolution.xml.XMLFormat.OutputElement output ) throws XMLStreamException {
        getFormat( obj.getClass() ).write( obj, output );
    }

    @SuppressWarnings( "unchecked" )
    private XMLFormat<AbstractPersistentCollection> getFormat( final Class<? extends AbstractPersistentCollection> clazz ) {
        XMLFormat<AbstractPersistentCollection> format = _formats.get( clazz );
        if ( format == null ) {
            format = new ReflectionFormat( clazz, clazz.getClassLoader() );
            _formats.put( clazz, format );
        }
        return format;
    }

}

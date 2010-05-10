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
package de.javakaffee.web.msm.serializer.kryo;

import org.hibernate.collection.AbstractPersistentCollection;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.serialize.FieldSerializer;

/**
 * {@link SerializerFactory} that supports hibernate persistent collections.
 * It creates a {@link FieldSerializer} for subclasses of {@link AbstractPersistentCollection}.
 * 
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class HibernateCollectionsSerializerFactory implements SerializerFactory {
    
    private final Kryo _kryo;

    public HibernateCollectionsSerializerFactory( final Kryo kryo ) {
        _kryo = kryo;
    }

    @Override
    public Serializer newSerializer( final Class<?> type ) {
        if ( AbstractPersistentCollection.class.isAssignableFrom( type ) ) {
            return new FieldSerializer( _kryo, type );
        }
        return null;
    }
    
}
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

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.serializers.FieldSerializer;

/**
 * {@link SerializerFactory} that supports hibernate persistent collections (Hibernate 3 and Nibernate 4).
 * <p/>
 * It creates a {@link FieldSerializer} for subclasses of {@link AbstractPersistentCollection}.
 * <p/>
 * If Hibernate is not in the classpath, this {@link SerializerFactory} is no-op.
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class HibernateCollectionsSerializerFactory implements SerializerFactory {

    private static Class<?> HIBERNATE_ABSTRACT_COLLECTION_CLASS;

    static {
        try {
            HIBERNATE_ABSTRACT_COLLECTION_CLASS = Class.forName("org.hibernate.collection.AbstractPersistentCollection");
        } catch (ClassNotFoundException e) {
            try {
                HIBERNATE_ABSTRACT_COLLECTION_CLASS = Class.forName("org.hibernate.collection.internal.AbstractPersistentCollection");
            } catch (ClassNotFoundException e2) {
                HIBERNATE_ABSTRACT_COLLECTION_CLASS = null;
            }
        }
    }

    private final Kryo _kryo;

    public HibernateCollectionsSerializerFactory( final Kryo kryo ) {
        _kryo = kryo;
    }

    @Override
    public Serializer newSerializer( final Class<?> type ) {
        if( HIBERNATE_ABSTRACT_COLLECTION_CLASS == null ) {
            return null;
        } else if ( HIBERNATE_ABSTRACT_COLLECTION_CLASS.isAssignableFrom( type ) ) {
            return new FieldSerializer( _kryo, type );
        }
        return null;
    }

}
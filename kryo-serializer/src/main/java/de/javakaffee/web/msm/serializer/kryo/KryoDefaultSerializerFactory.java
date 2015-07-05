/*
 * Copyright 2014 Marcus Thiesen
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

/**
 * Interface for creating default Serializers for Kryo.
 *
 * @author Marcus Thiesen (marcus.thiesen@freiheit.com) (initial creation)
 */
public interface KryoDefaultSerializerFactory {

    /**
     * Should return the Serializer used by Kryo when
     * {@link Kryo#newDefaultSerializer} is called.
     */
    public Serializer newDefaultSerializer( Kryo kryo, Class<?> type );

    static class SerializerFactoryAdapter implements com.esotericsoftware.kryo.factories.SerializerFactory {

        private final KryoDefaultSerializerFactory delegate;

        public SerializerFactoryAdapter(KryoDefaultSerializerFactory delegate) {
            this.delegate = delegate;
        }

        @Override
        public Serializer makeSerializer(Kryo kryo, Class<?> type) {
            return delegate.newDefaultSerializer(kryo, type);
        }
    }

}

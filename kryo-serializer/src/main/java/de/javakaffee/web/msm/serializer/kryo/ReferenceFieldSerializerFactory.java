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
import com.esotericsoftware.kryo.serialize.ReferenceFieldSerializer;

/**
 * Default Serializer used by memcached-session-manager.
 * Creates a {@link ReferenceFieldSerializer} which does not ignores synthetic fields.
 *
 * @author Marcus Thiesen (marcus.thiesen@freiheit.com) (initial creation)
 */
public class ReferenceFieldSerializerFactory implements KryoDefaultSerializerFactory {

    @Override
    public Serializer newDefaultSerializer( final Kryo kryo, final Class<?> type ) {
        final ReferenceFieldSerializer result = new ReferenceFieldSerializer( kryo, type );
        result.setIgnoreSyntheticFields( false );
        return result;
    }

}

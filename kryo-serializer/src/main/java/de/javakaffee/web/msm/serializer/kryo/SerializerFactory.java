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

/**
 * This factory creates a new {@link Serializer} for a given class. It is
 * used in {@link Kryo#newDefaultSerializer(Class)}.
 * 
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public interface SerializerFactory {

    /**
     * Returns a serializer for the specified type or <code>null</code> if the type is not supported
     * by this factory.
     * 
     * @param type the type a serializer shall be created for.
     * @return a {@link Serializer} implementation or <code>null</code>.
     */
    Serializer newSerializer( final Class<?> type );
    
}

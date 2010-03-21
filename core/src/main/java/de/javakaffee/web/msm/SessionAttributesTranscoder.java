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
package de.javakaffee.web.msm;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.catalina.session.StandardSession;


/**
 * The {@link SessionAttributesTranscoder} is responsible for serializing/deserializing the
 * session attributes map to/from a <code>byte</code> array.
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public interface SessionAttributesTranscoder {

    /**
     * Serialize the given attributes to a byte array. The provided session is the
     * session the attributes were retrieved from. The serialized byte[] can be
     * deserialized using {@link #deserializeAttributes(byte[])}.
     *
     * @param session the session that owns the given attributes.
     * @param attributes the attributes to serialize.
     * @return a byte array representing the serialized attributes.
     */
    byte[] serializeAttributes( final MemcachedBackupSession session, final Map<String, Object> attributes );

    /**
     * Deserialize the given byte array to session attributes. The map implementation
     * should be a {@link ConcurrentHashMap} as this is the implementation currently used
     * by the {@link StandardSession}.
     *
     * @param data the serialized attributes
     * @return the deserialized attributes
     */
    Map<String, Object> deserializeAttributes( final byte[] data );

}

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
package de.javakaffee.web.msm;

import net.spy.memcached.transcoders.SerializingTranscoder;

/**
 * A subclass of {@link SerializingTranscoder} so that the protected method
 * {@link #deserialize} is visible locally.
 * <p>
 * This {@link SessionTranscoder} is used to deserialize
 * sessions that are still stored in memcached with the old serialization
 * format (the whole session was serialized by the serialization strategy,
 * not only attributes).
 * </p>
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public abstract class SessionTranscoder extends SerializingTranscoder {

    /**
     * Deserialize the session from the given bytes.
     *
     * @param in the serialized session data
     * @return the deserialized {@link MemcachedBackupSession}
     */
    @Override
    protected abstract MemcachedBackupSession deserialize( final byte[] in );

}

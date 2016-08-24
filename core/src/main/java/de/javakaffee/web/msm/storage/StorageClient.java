/*
 * Copyright 2016 Markus Ellinger
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
package de.javakaffee.web.msm.storage;

import java.io.IOException;

/**
 * Abstracts access to a memcached service, or another service, which provides a protocol similar to memcached.
 *
 * @author <a href="mailto:markus@ellinger.it">Markus Ellinger</a>
 */
public interface StorageClient {
    /**
     * <p>
     * Adds an object to the cache iff it does not exist already.
     * </p>
     * <p>
     * The expiration is defined according to the memcached protocol as follows: The actual value sent may either be
     * Unix time (number of seconds since January 1, 1970, as a 32-bit value), or a number of seconds starting from
     * current time. In the latter case, this number of seconds may not exceed 60*60*24*30
     * (number of seconds in 30 days); if the number sent by a client is larger than that, the server will consider it
     * to be real Unix time value rather than an offset from current time.
     * </p>
     * 
     * @param key object key
     * @param exp object expiration
     * @param o object bytes to store
     * 
     * @return a boolean indicating whether the object was actually added
     * 
     * @throws IOException if there is a problem communicating with the service
     */
    boolean add(String key, int exp, byte[] o) throws IOException;
    
    /**
     * <p>
     * Sets an object in the cache regardless of any existing value.
     * </p>
     * <p>
     * The expiration is defined according to the memcached protocol as follows: The actual value sent may either be
     * Unix time (number of seconds since January 1, 1970, as a 32-bit value), or a number of seconds starting from
     * current time. In the latter case, this number of seconds may not exceed 60*60*24*30
     * (number of seconds in 30 days); if the number sent by a client is larger than that, the server will consider it
     * to be real Unix time value rather than an offset from current time.
     * </p>
     * 
     * @param key object key
     * @param exp object expiration
     * @param o object bytes to store
     * 
     * @return a boolean indicating whether the operation was successful
     * 
     * @throws IOException if there is a problem communicating with the service
     */
    boolean set(String key, int exp, byte[] o) throws IOException;
    
    /**
     * Gets a single key.
     * 
     * @param key object key
     * 
     * @return object bytes or <code>null</code> if the key is not found
     * 
     * @throws IOException if there is a problem communicating with the service
     */
    byte[] get(String key) throws IOException;

    /**
     * Deletes the given key from the cache.
     * 
     * @param key object key
     * 
     * @return a boolean indicating whether the operation was successful
     * 
     * @throws IOException if there is a problem communicating with the service
     */
    boolean delete(String key) throws IOException;

   /**
     * Shuts this client down immediately.
     */
    void shutdown();
}
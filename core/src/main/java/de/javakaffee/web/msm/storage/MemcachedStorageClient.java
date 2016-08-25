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

import java.util.concurrent.Future;

import net.spy.memcached.CachedData;
import net.spy.memcached.MemcachedClient;
import net.spy.memcached.transcoders.Transcoder;

/**
 * Storage client backed by a {@link MemcachedClient} instance.
 */
public class MemcachedStorageClient implements StorageClient {
    private MemcachedClient _memcached;
    
    /**
     * Creates a <code>MemcachedStorageClient</code> instance with the given memcached client.
     * 
     * @param memcached underlying memcached client
     */
    public MemcachedStorageClient(MemcachedClient memcached) {
        if (memcached == null)
            throw new NullPointerException("Param \"memcached\" may not be null");
        
        _memcached = memcached;
    }
    
    /**
     * Get underlying memcached client instance.
     */
    public MemcachedClient getMemcachedClient() {
        return _memcached;
    }

    @Override
    public Future<Boolean> add(String key, int exp, byte[] o) {
        return _memcached.add(key, exp, o, ByteArrayTranscoder.INSTANCE);
    }

    @Override
    public Future<Boolean> set(String key, int exp, byte[] o) {
        return _memcached.set(key, exp, o, ByteArrayTranscoder.INSTANCE);
    }
    
    @Override
    public byte[] get(String key) {
        return _memcached.get(key, ByteArrayTranscoder.INSTANCE);
    }
    
    @Override
    public Future<Boolean> delete(String key) {
        return _memcached.delete(key);
    }

    @Override
    public void shutdown() {
        _memcached.shutdown();
    }

    /**
     * Transcoder used by this class to store the byte array data.
     */
    public static class ByteArrayTranscoder implements Transcoder<byte[]> {
        /**
         * Transcoder singleton instance.
         */
        public static final ByteArrayTranscoder INSTANCE = new ByteArrayTranscoder();
        
        @Override
        public boolean asyncDecode(CachedData d) {
            return false;
        }
        
        @Override
        public byte[] decode(CachedData d) {
            return d.getData();
        }
        
        @Override
        public CachedData encode(byte[] o) {
            return new CachedData(0, o, getMaxSize());
        }
        
        @Override
        public int getMaxSize() {
            return CachedData.MAX_SIZE;
        }
    }
}

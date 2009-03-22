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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * An LRUCache that supports a maximum number of cache entries and
 * a time to live for them. The TTL is measured from insertion time to access time.
 * 
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 * @version $Id$
 * @param <K> the type of the key
 * @param <V> the type of the value
 */
public class LRUCache<K,V> {
    
    private final int _size;
    private final long _ttl;
    private final LinkedHashMap<K, ManagedItem<V>> _map;

    public LRUCache( int size ) {
        this(size, -1);
    }

    /**
     * Create a new LRUCache with a maximum number of cache entries and
     * a specified time to live for cache entries.
     * The TTL is measured from insertion time to access time.
     * @param size the maximum number of cached items
     * @param ttlInMillis the time to live in milli seconds. Specify -1 for no limit
     */
    public LRUCache(int size, long ttlInMillis) {
        _size = size;
        _ttl = ttlInMillis;
        _map = new LinkedHashMap<K, ManagedItem<V>>( size / 2, 0.75f, true );
    }

    public void put( K key, V value ) {
        _map.put( key, new ManagedItem<V>( value, System.currentTimeMillis() ) );
        while ( _map.size() > _size ) {
            _map.remove( _map.keySet().iterator().next() );
        }
    }

    public V get( K key ) {
        final ManagedItem<V> item = _map.get( key );
        if ( item == null ) {
            return null;
        }
        if ( _ttl > -1 && System.currentTimeMillis() - item.insertionTime > _ttl ) {
            _map.remove( key );
            return null;
        }
        return item.value;
    }
    
    public List<K> getKeys() {
        return new ArrayList<K>( _map.keySet() );
    }
    
    private static final class ManagedItem<T> {
        final T value;
        final long insertionTime;
        public ManagedItem(T value, long accessTime) {
            this.value = value;
            this.insertionTime = accessTime;
        }
    }

}

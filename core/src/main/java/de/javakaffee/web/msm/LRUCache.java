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

import java.util.AbstractList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.RandomAccess;

/**
 * An LRUCache that supports a maximum number of cache entries and a time to
 * live for them. The TTL is measured from insertion time to access time.
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 * @version $Id$
 * @param <K>
 *            the type of the key
 * @param <V>
 *            the type of the value
 */
public class LRUCache<K, V> {

    private final int _size;
    private final long _ttl;
    private final LinkedHashMap<K, ManagedItem<V>> _map;

    /**
     * Creates a new instance with the given maximum size.
     *
     * @param size
     *            the number of items to keep at max
     */
    public LRUCache( final int size ) {
        this( size, -1 );
    }

    /**
     * Create a new LRUCache with a maximum number of cache entries and a
     * specified time to live for cache entries. The TTL is measured from
     * insertion time to access time.
     *
     * @param size
     *            the maximum number of cached items
     * @param ttlInMillis
     *            the time to live in milli seconds. Specify -1 for no limit
     */
    public LRUCache( final int size, final long ttlInMillis ) {
        _size = size;
        _ttl = ttlInMillis;
        _map = new LinkedHashMap<K, ManagedItem<V>>( size / 2, 0.75f, true );
    }

    /**
     * Removes all cache entries.
     */
    public void clear() {
        synchronized ( _map ) {
            _map.clear();
        }
    }

    /**
     * Put the key and value.
     *
     * @param key
     *            the key
     * @param value
     *            the value
     * @return the previously associated value or <code>null</code>.
     */
    public V put( final K key, final V value ) {
        synchronized ( _map ) {
            final ManagedItem<V> previous = _map.put( key, new ManagedItem<V>( value, System.currentTimeMillis() ) );
            while ( _map.size() > _size ) {
                _map.remove( _map.keySet().iterator().next() );
            }
            return previous != null
                ? previous._value
                : null;
        }
    }

    /**
     * If the specified key is not already associated with a value or if it's
     * associated with a different value, associate it with the given value.
     * This is equivalent to
     *
     * <pre>
     * <code> if (map.get(key) == null || !map.get(key).equals(value))
     *    return map.put(key, value);
     * else
     *    return map.get(key);
     * </code>
     * </pre>
     *
     * except that the action is performed atomically.
     *
     * @param key
     *            the key to associate the value with.
     * @param value
     *            the value to associate with the provided key.
     * @return the previous value associated with the specified key, or null if
     *         there was no mapping for the key
     */
    public V putIfDifferent( final K key, final V value ) {
        synchronized ( _map ) {
            final ManagedItem<V> item = _map.get( key );
            if ( item == null || item._value == null || !item._value.equals( value ) ) {
                return put( key, value );
            } else {
                return item._value;
            }
        }
    }

    /**
     * Removes the mapping for the specified key from this map if present.
     *
     * @param  key key whose mapping is to be removed from the map
     * @return
     * @return the previous value associated with <tt>key</tt>, or
     *         <tt>null</tt> if there was no mapping for <tt>key</tt>.
     *         (A <tt>null</tt> return can also indicate that the map
     *         previously associated <tt>null</tt> with <tt>key</tt>.)
     */
    public V remove( final K key ) {
        synchronized ( _map ) {
            final ManagedItem<V> removed = _map.remove( key );
            return removed != null ? removed._value : null;
        }
    }

    /**
     * Returns the value that was stored to the given key.
     *
     * @param key
     *            the key
     * @return the stored value or <code>null</code>
     */
    public V get( final K key ) {
        synchronized ( _map ) {
            final ManagedItem<V> item = _map.get( key );
            if ( item == null ) {
                return null;
            }
            if ( _ttl > -1 && System.currentTimeMillis() - item._insertionTime > _ttl ) {
                _map.remove( key );
                return null;
            }
            return item._value;
        }
    }

    /**
     * Determines if the given key is cached without "touching" this key.
     *
     * @param key
     *            the key
     * @return <code>true</code> if the given key is present in the underlying map, otherwise <code>false</code>.
     */
    public boolean containsKey( final K key ) {
        synchronized ( _map ) {
            return _map.containsKey( key );
        }
    }

    /**
     * The list of all keys, whose order is the order in which its entries were last accessed,
     * from least-recently accessed to most-recently.
     *
     * @return a new list.
     */
    public List<K> getKeys() {
        synchronized ( _map ) {
            return new java.util.ArrayList<K>( _map.keySet() );
        }
    }

    /**
     * The keys sorted by the given value comparator.
     *
     * @return the underlying set, see {@link LinkedHashMap#keySet()}.
     */
    public List<K> getKeysSortedByValue( final Comparator<V> comparator ) {
        synchronized ( _map ) {
            @SuppressWarnings( "unchecked" )
            final
            Entry<K, ManagedItem<V>>[] a = _map.entrySet().toArray( new Map.Entry[_map.size()] );
            final Comparator<Entry<K, ManagedItem<V>>> c = new Comparator<Entry<K, ManagedItem<V>>>() {

                @Override
                public int compare( final Entry<K, ManagedItem<V>> o1, final Entry<K, ManagedItem<V>> o2 ) {
                    return comparator.compare( o1.getValue()._value, o2.getValue()._value );
                }

            };
            Arrays.sort(a, c);
            return new LRUCache.ArrayList<K, V>( a );
        }
    }

    /**
     * Stores a value with the timestamp this value was added to the cache.
     *
     * @param <T>
     *            the type of the value
     */
    private static final class ManagedItem<T> {
        private final T _value;
        private final long _insertionTime;

        private ManagedItem( final T value, final long accessTime ) {
            _value = value;
            _insertionTime = accessTime;
        }
    }

    /**
     * An array list over the backed array of keys and managed items.
     * @author Martin Grotzke
     *
     * @param <E>
     * @param <V>
     */
    private static class ArrayList<E, V> extends AbstractList<E> implements RandomAccess {
        private final Entry<E, ManagedItem<V>>[] a;

        ArrayList( final Entry<E, ManagedItem<V>>[] array ) {
            if ( array == null ) {
                throw new NullPointerException();
            }
            a = array;
        }

        @Override
        public int size() {
            return a.length;
        }

        @Override
        public <T> T[] toArray( final T[] a ) {
            throw new UnsupportedOperationException( "Not implemented." );
        }

        @Override
        public E get( final int index ) {
            return a[index].getKey();
        }

        @Override
        public E set( final int index, final E element ) {
            throw new UnsupportedOperationException( "Not implemented." );
        }

        @Override
        public int indexOf( final Object o ) {
            if ( o == null ) {
                for ( int i = 0; i < a.length; i++ ) {
                    if ( a[i] == null || a[i].getKey() == null ) {
                        return i;
                    }
                }
            }
            else {
                for ( int i = 0; i < a.length; i++ ) {
                    if ( o.equals( a[i].getKey() ) ) {
                        return i;
                    }
                }
            }
            return -1;
        }

        @Override
        public boolean contains( final Object o ) {
            return indexOf( o ) != -1;
        }
    }

}

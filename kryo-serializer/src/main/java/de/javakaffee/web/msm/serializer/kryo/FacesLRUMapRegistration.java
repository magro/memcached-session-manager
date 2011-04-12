/*
 * Copyright 2011 Martin Grotzke
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

import static com.esotericsoftware.minlog.Log.TRACE;
import static com.esotericsoftware.minlog.Log.trace;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.Map.Entry;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.serialize.IntSerializer;
import com.esotericsoftware.kryo.serialize.SimpleSerializer;
import com.sun.faces.util.LRUMap;

/**
 * A {@link KryoCustomization} that registers a custom serializer for
 * mojarras {@link LRUMap}.
 * 
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class FacesLRUMapRegistration implements KryoCustomization {

    @Override
    public void customize( final Kryo kryo ) {
        kryo.register( LRUMap.class, new LRUMapSerializer( kryo ) );
    }
    
    static class LRUMapSerializer extends SimpleSerializer<LRUMap<?, ?>> {
        
        private static final Field MAX_CAPACITY_FIELD;
        
        static {
            try {
                MAX_CAPACITY_FIELD = LRUMap.class.getDeclaredField( "maxCapacity" );
                MAX_CAPACITY_FIELD.setAccessible( true );
            } catch ( final Exception e ) {
                throw new RuntimeException( "The LRUMap seems to have changed, could not access expected field.", e );
            }
        }
        
        private final Kryo _kryo;

        /**
         * Constructor.
         */
        public LRUMapSerializer( final Kryo kryo ) {
            _kryo = kryo;
        }

        @Override
        public LRUMap<?, ?> read( final ByteBuffer buffer ) {
            final int maxCapacity = IntSerializer.get( buffer, true );
            final LRUMap<Object, Object> result = new LRUMap<Object, Object>( maxCapacity );
            final int size = IntSerializer.get( buffer, true );
            for ( int i = 0; i < size; i++ ) {
                final Object key = _kryo.readClassAndObject( buffer );
                final Object value = _kryo.readClassAndObject( buffer );
                result.put( key, value );
            }
            return result;
        }

        @Override
        public void write( final ByteBuffer buffer, final LRUMap<?, ?> map ) {
            IntSerializer.put( buffer, getMaxCapacity( map ), true );
            IntSerializer.put( buffer, map.size(), true );
            for ( final Iterator<? extends Entry<?, ?>> iter = map.entrySet().iterator(); iter.hasNext(); ) {
                final Entry<?, ?> entry = iter.next();
                _kryo.writeClassAndObject( buffer, entry.getKey() );
                _kryo.writeClassAndObject( buffer, entry.getValue() );
            }
            if ( TRACE ) trace( "kryo", "Wrote map: " + map );
        }

        private int getMaxCapacity( final LRUMap<?, ?> map ) {
            try {
                return MAX_CAPACITY_FIELD.getInt( map );
            } catch ( final Exception e ) {
                throw new RuntimeException( "Could not access maxCapacity field.", e );
            }
        }

    }
    
}

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

import de.javakaffee.kryoserializers.cglib.CGLibProxySerializer;
import de.javakaffee.kryoserializers.cglib.CGLibProxySerializer.CGLibProxyMarker;

/**
 * A {@link SerializerFactory} that creates {@link CGLibProxySerializer} instances. Additionally
 * as {@link KryoCustomization} it registers a {@link CGLibProxySerializer} for the
 * {@link CGLibProxyMarker} class.
 * 
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class CGLibProxySerializerFactory implements UnregisteredClassHandler, KryoCustomization {
    
    private final Kryo _kryo;

    /**
     * Creates a new instances.
     * @param kryo the kryo instance that must be provided.
     */
    public CGLibProxySerializerFactory( final Kryo kryo ) {
        if ( kryo == null ) {
            throw new NullPointerException( "Kryo is not provided but null!" );
        }
        _kryo = kryo;
    }
    
    @Override
    public void customize( final Kryo kryo ) {
        kryo.register( CGLibProxySerializer.CGLibProxyMarker.class, new CGLibProxySerializer( kryo ) );
    }
    
    @Override
    public boolean handleUnregisteredClass( final Class<?> type ) {
        if ( CGLibProxySerializer.canSerialize( type ) ) {
            _kryo.register( type, _kryo.getRegisteredClass( CGLibProxySerializer.CGLibProxyMarker.class ) );
            return true;
        }
        return false;
    }
    
}

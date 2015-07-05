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
import org.objenesis.strategy.StdInstantiatorStrategy;

/**
 * A meta {@link SerializerFactory} and {@link KryoCustomization} that represents {@link WicketMiniMapRegistration}
 * and {@link WicketChildListSerializerFactory}. Therefore you can just register this {@link WicketSerializerFactory}
 * as custom converter.
 * 
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class WicketSerializerFactory implements KryoBuilderConfiguration, SerializerFactory, KryoCustomization {
    
    private final WicketChildListSerializerFactory _childListSerializerFactory;

    /**
     * Creates a new instances.
     * @param kryo the kryo instance that must be provided.
     */
    public WicketSerializerFactory( final Kryo kryo ) {
        if ( kryo == null ) {
            throw new NullPointerException( "Kryo is not provided but null!" );
        }
        _childListSerializerFactory = new WicketChildListSerializerFactory( kryo );
    }

    @Override
    public KryoBuilder configure(KryoBuilder kryoBuilder) {
        return kryoBuilder
                .withInstantiatorStrategy(
                        new Kryo.DefaultInstantiatorStrategy(new StdInstantiatorStrategy()))
                .withReferences(true);
    }

    @Override
    public void customize( final Kryo kryo ) {
        new WicketMiniMapRegistration().customize( kryo );
        new ComponentSerializerFactory().customize( kryo );
    }

    @Override
    public Serializer newSerializer( final Class<?> type ) {
        Serializer serializer;
        if ( ( serializer = _childListSerializerFactory.newSerializer( type ) ) != null ) {
            return serializer;
        }
        return null;
    }
    
}

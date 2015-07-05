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

import com.esotericsoftware.kryo.ClassResolver;
import com.esotericsoftware.kryo.Kryo;

import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.util.DefaultClassResolver;
import de.javakaffee.kryoserializers.cglib.CGLibProxySerializer;
import de.javakaffee.kryoserializers.cglib.CGLibProxySerializer.CGLibProxyMarker;
import org.objenesis.strategy.StdInstantiatorStrategy;

/**
 * A {@link SerializerFactory} that creates {@link CGLibProxySerializer} instances. Additionally
 * as {@link KryoCustomization} it registers a {@link CGLibProxySerializer} for the
 * {@link CGLibProxyMarker} class.
 * 
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class CGLibProxySerializerFactory implements KryoCustomization, SerializerFactory, KryoBuilderConfiguration {

    private final Kryo kryo;

    /**
     * Needed for instantiation as KryoBuilderConfiguration.
     */
    public CGLibProxySerializerFactory() {
        this(null);
    }

    public CGLibProxySerializerFactory(Kryo kryo) {
        this.kryo = kryo;
    }

    @Override
    public KryoBuilder configure(KryoBuilder kryoBuilder) {
        return kryoBuilder
                .withClassResolver(createClassResolver())
                .withInstantiatorStrategy(
                        new Kryo.DefaultInstantiatorStrategy(new StdInstantiatorStrategy()));
    }

    @Override
    public void customize( final Kryo kryo ) {
        kryo.register( CGLibProxySerializer.CGLibProxyMarker.class, new CGLibProxySerializer() );
    }

    @Override
    public Serializer newSerializer(Class<?> type) {
        if ( CGLibProxySerializer.canSerialize( type ) ) {
            return kryo.getSerializer(CGLibProxySerializer.CGLibProxyMarker.class);
        }
        return null;
    }

    protected ClassResolver createClassResolver() {
        return new CGLibProxyClassResolver();
    }

    static class CGLibProxyClassResolver extends DefaultClassResolver {
        @Override
        protected Class<?> getTypeByName(final String className) {
            if (className.indexOf(CGLibProxySerializer.DEFAULT_NAMING_MARKER) > 0) {
                return CGLibProxyMarker.class;
            }
            return super.getTypeByName(className);
        }
    }
}

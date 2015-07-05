/*
 * Copyright 2015 Martin Grotzke
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
 */
package de.javakaffee.web.msm.serializer.kryo;

import com.esotericsoftware.kryo.ClassResolver;
import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.ReferenceResolver;
import com.esotericsoftware.kryo.StreamFactory;
import com.esotericsoftware.kryo.util.DefaultClassResolver;
import com.esotericsoftware.kryo.util.DefaultStreamFactory;
import com.esotericsoftware.kryo.util.MapReferenceResolver;
import org.objenesis.strategy.InstantiatorStrategy;

/**
 * Builder for the {@link Kryo} instance, users can configure the builder via the
 * {@link KryoBuilderConfiguration}.
 * 
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class KryoBuilder {

    private ClassResolver classResolver;
    private ReferenceResolver referenceResolver;
    private StreamFactory streamFactory;

    /**
     * @return the configured {@link Kryo} instance.
     */
    public Kryo build() {
        Kryo kryo = createKryo(
                classResolver != null ? classResolver : new DefaultClassResolver(),
                referenceResolver != null ? referenceResolver : new MapReferenceResolver(),
                streamFactory != null ? streamFactory : new DefaultStreamFactory()
        );
        return kryo;
    }

    protected Kryo createKryo(ClassResolver classResolver, ReferenceResolver referenceResolver, StreamFactory streamFactory) {
        return new Kryo(classResolver, referenceResolver, streamFactory);
    }

    public KryoBuilder withClassResolver(final ClassResolver classResolver) {
        this.classResolver = classResolver;
        return this;
    }
    public KryoBuilder withReferenceResolver(final ReferenceResolver referenceResolver) {
        this.referenceResolver = referenceResolver;
        return this;
    }
    public KryoBuilder withStreamFactory(final StreamFactory streamFactory) {
        this.streamFactory = streamFactory;
        return this;
    }

    private Kryo buildFrom(KryoBuilder target) {
        // we must transfer local fields to the target which creates the Kryo instance.
        // yes, it's a bit hackish, but if s.o. calls the same method twice with different arguments it's kind of bullshit in...
        if(target.classResolver == null) target.classResolver = classResolver;
        if(target.referenceResolver == null) target.referenceResolver = referenceResolver;
        if(target.streamFactory == null) target.streamFactory = streamFactory;
        return target.build();
    }

    public KryoBuilder withRegistrationRequired(final boolean registrationRequired) {
        return new KryoBuilder() {
            @Override
            public Kryo build() {
                Kryo k = buildFrom(KryoBuilder.this);
                k.setRegistrationRequired(registrationRequired);
                return k;
            }
        };
    }

    public KryoBuilder withInstantiatorStrategy(final InstantiatorStrategy instantiatorStrategy) {
        return new KryoBuilder() {
            @Override
            public Kryo build() {
                Kryo k = buildFrom(KryoBuilder.this);
                k.setInstantiatorStrategy(instantiatorStrategy);
                return k;
            }
        };
    }

    public KryoBuilder withReferences(final boolean references) {
        return new KryoBuilder() {
            @Override
            public Kryo build() {
                Kryo k = buildFrom(KryoBuilder.this);
                k.setReferences(references);
                return k;
            }
        };
    }

    public KryoBuilder withKryoCustomization(final KryoCustomization kryoCustomization) {
        return new KryoBuilder() {
            @Override
            public Kryo build() {
                Kryo k = buildFrom(KryoBuilder.this);
                kryoCustomization.customize(k);
                return k;
            }
        };
    }
    
}
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
import com.esotericsoftware.kryo.serializers.FieldSerializer;
import org.apache.wicket.Component;
import org.objenesis.strategy.StdInstantiatorStrategy;

/**
 * A {@link KryoCustomization} that creates a {@link FieldSerializer} as
 * serializer for subclasses of {@link Component}. This is required, as the {@link Component}
 * constructor invokes {@link org.apache.wicket.Application#get()} to tell the application
 * to {@link org.apache.wicket.Application#notifyComponentInstantiationListeners()}. This will
 * lead to NullpointerExceptions if the application is not yet bound to the current thread
 * because the session is e.g. accessed from within a servlet filter. If the component is created
 * via the constructor for serialization, this problem does not occur.
 *
 * This also enables references and configures the StdInstantiatorStrategy as fallback for the default strategy.
 * 
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class ComponentSerializerFactory implements KryoBuilderConfiguration, KryoCustomization {

    @Override
    public KryoBuilder configure(KryoBuilder kryoBuilder) {
        return kryoBuilder
                .withInstantiatorStrategy(
                        new Kryo.DefaultInstantiatorStrategy(new StdInstantiatorStrategy()))
                .withReferences(true);
    }

    @Override
    public void customize(Kryo kryo) {
        kryo.addDefaultSerializer(Component.class, new com.esotericsoftware.kryo.factories.SerializerFactory() {
            @Override
            public Serializer makeSerializer(Kryo kryo, Class<?> type) {
                final FieldSerializer result = new FieldSerializer<Component>(kryo, type);
                result.setIgnoreSyntheticFields(false);
                return result;
            }
        });
    }

}

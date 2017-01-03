/*
 * Copyright 2016 Martin Grotzke
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

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.serializers.CollectionSerializer;
import com.esotericsoftware.kryo.util.DefaultClassResolver;
import com.esotericsoftware.kryo.util.DefaultStreamFactory;
import com.esotericsoftware.kryo.util.MapReferenceResolver;
import org.objenesis.strategy.InstantiatorStrategy;
import org.objenesis.strategy.StdInstantiatorStrategy;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.Collection;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

public class KryoBuilderTest {

	@DataProvider
	public static Object[][] buildKryoProvider() {
		return new Object[][] {
				{ new BuildKryo("customizationsFirst") {
					@Override
					Kryo build(DefaultClassResolver classResolver,
							   MapReferenceResolver referenceResolver,
							   DefaultStreamFactory streamFactory,
							   InstantiatorStrategy instantiatorStrategy,
							   KryoCustomization enableAsm,
							   KryoCustomization registerMyCollectionSerializer) {
						return new KryoBuilder()
								.withKryoCustomization(enableAsm)
								.withKryoCustomization(registerMyCollectionSerializer)
								.withClassResolver(classResolver)
								.withReferenceResolver(referenceResolver)
								.withStreamFactory(streamFactory)
								.withInstantiatorStrategy(instantiatorStrategy)
								.withRegistrationRequired(true) // Kryo default is false
								.withReferences(false) // Kryo default is true
								.withOptimizedGenerics(false)
								.build();
					}
				} },
				{ new BuildKryo("customizationsLast") {
					@Override
					Kryo build(DefaultClassResolver classResolver,
							   MapReferenceResolver referenceResolver,
							   DefaultStreamFactory streamFactory,
							   InstantiatorStrategy instantiatorStrategy,
							   KryoCustomization enableAsm,
							   KryoCustomization registerMyCollectionSerializer) {
						return new KryoBuilder()
								.withClassResolver(classResolver)
								.withReferenceResolver(referenceResolver)
								.withStreamFactory(streamFactory)
								.withInstantiatorStrategy(instantiatorStrategy)
								.withRegistrationRequired(true) // Kryo default is false
								.withReferences(false) // Kryo default is true
								.withOptimizedGenerics(false)
								.withKryoCustomization(enableAsm)
								.withKryoCustomization(registerMyCollectionSerializer)
								.build();
					}
				} }
		};
	}

	@Test(dataProvider = "buildKryoProvider")
	public void testKryoBuilder(BuildKryo buildKryo) {

		DefaultClassResolver classResolver = new DefaultClassResolver();
		MapReferenceResolver referenceResolver = new MapReferenceResolver();
		DefaultStreamFactory streamFactory = new DefaultStreamFactory();
		InstantiatorStrategy instantiatorStrategy = new Kryo.DefaultInstantiatorStrategy(new StdInstantiatorStrategy());

		KryoCustomization enableAsm = new KryoCustomization() {
			@Override public void customize(Kryo kryo) {
				kryo.getFieldSerializerConfig().setUseAsm(true); // Kryo default false
			}
		};

		final CollectionSerializer collectionSerializer = new CollectionSerializer();
		KryoCustomization registerMyCollectionSerializer = new KryoCustomization() {
			@Override public void customize(Kryo kryo) {
				kryo.addDefaultSerializer(Collection.class, collectionSerializer);
			}
		};

		Kryo kryo = buildKryo.build(classResolver, referenceResolver, streamFactory, instantiatorStrategy, enableAsm, registerMyCollectionSerializer);

		assertSame(kryo.getClassResolver(), classResolver);
		assertSame(kryo.getReferenceResolver(), referenceResolver);
		assertSame(kryo.getStreamFactory(), streamFactory);
		assertSame(kryo.getInstantiatorStrategy(), instantiatorStrategy);
		assertTrue(kryo.isRegistrationRequired());
		assertFalse(kryo.getReferences());
		assertTrue(kryo.getFieldSerializerConfig().isUseAsm());
		assertSame(kryo.getDefaultSerializer(Collection.class), collectionSerializer);
		assertFalse(kryo.getFieldSerializerConfig().isOptimizedGenerics());
	}

	static abstract class BuildKryo {
		private final String description;
		BuildKryo(String description) {
			this.description = description;
		}
		abstract Kryo build(DefaultClassResolver classResolver,
							MapReferenceResolver referenceResolver,
							DefaultStreamFactory streamFactory,
							InstantiatorStrategy instantiatorStrategy,
							KryoCustomization enableAsm,
							KryoCustomization registerMyCollectionSerializer);

		@Override
		public String toString() {
			return getClass().getSimpleName() + "(" + description + ")";
		}
	}
}

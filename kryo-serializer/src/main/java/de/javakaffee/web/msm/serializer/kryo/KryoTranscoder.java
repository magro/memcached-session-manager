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

import com.esotericsoftware.kryo.*;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.pool.KryoFactory;
import com.esotericsoftware.kryo.pool.KryoPool;
import de.javakaffee.kryoserializers.*;
import de.javakaffee.web.msm.MemcachedBackupSession;
import de.javakaffee.web.msm.SessionAttributesTranscoder;
import de.javakaffee.web.msm.TranscoderDeserializationException;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.objenesis.strategy.StdInstantiatorStrategy;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A {@link SessionAttributesTranscoder} that uses {@link Kryo} for serialization.
 * 
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class KryoTranscoder implements SessionAttributesTranscoder {

    private static final Log LOG = LogFactory.getLog( KryoTranscoder.class );
    
    public static final int DEFAULT_INITIAL_BUFFER_SIZE = 100 * 1024;
    public static final int DEFAULT_MAX_BUFFER_SIZE = 2000 * 1024;
    public static final String DEFAULT_SERIALIZER_FACTORY_CLASS = DefaultFieldSerializerFactory.class.getName();
    
    private final KryoPool _kryoPool;

    private final int _initialBufferSize;
    private final int _maxBufferSize;
    private final KryoDefaultSerializerFactory _defaultSerializerFactory;

    public KryoTranscoder() {
        this( null, null, false );
    }

    public KryoTranscoder( final ClassLoader classLoader, final String[] customConverterClassNames, final boolean copyCollectionsForSerialization ) {
        this( classLoader, customConverterClassNames, copyCollectionsForSerialization, DEFAULT_INITIAL_BUFFER_SIZE, DEFAULT_MAX_BUFFER_SIZE,
                DEFAULT_SERIALIZER_FACTORY_CLASS );
    }

    public KryoTranscoder( final ClassLoader classLoader, final String[] customConverterClassNames,
            final boolean copyCollectionsForSerialization, final int initialBufferSize, final int maxBufferSize,
            final String defaultSerializerFactoryClass ) {
        LOG.info( "Starting with initialBufferSize " + initialBufferSize + ", maxBufferSize " + maxBufferSize +
                " and defaultSerializerFactory " + defaultSerializerFactoryClass );
        final KryoFactory kryoFactory = createKryoFactory(classLoader, customConverterClassNames, copyCollectionsForSerialization);
        _kryoPool = new KryoPool.Builder(kryoFactory).softReferences().build();
        _initialBufferSize = initialBufferSize;
        _maxBufferSize = maxBufferSize;
        _defaultSerializerFactory = loadDefaultSerializerFactory( classLoader, defaultSerializerFactoryClass );
    }

    protected KryoDefaultSerializerFactory loadDefaultSerializerFactory( final ClassLoader classLoader, final String defaultSerializerFactoryClass ) {
         try {
             final ClassLoader loader = classLoader != null ? classLoader : Thread.currentThread().getContextClassLoader();
             final Class<?> clazz = Class.forName( defaultSerializerFactoryClass, true, loader );

             return (KryoDefaultSerializerFactory) clazz.newInstance();
        } catch ( final Exception e ) {
            throw new RuntimeException("Could not load default serializer factory: " + defaultSerializerFactoryClass, e );
        }
    }

    private KryoFactory createKryoFactory(final ClassLoader classLoader,
                                          final String[] customConverterClassNames,
                                          final boolean copyCollectionsForSerialization) {

        final KryoBuilder kryoBuilder = new KryoBuilder() {
            @Override
            protected Kryo createKryo(ClassResolver classResolver, ReferenceResolver referenceResolver, StreamFactory streamFactory) {
                return KryoTranscoder.this.createKryo(classResolver, referenceResolver, streamFactory,
                        classLoader, customConverterClassNames, copyCollectionsForSerialization);
            }
        }.withInstantiatorStrategy(new Kryo.DefaultInstantiatorStrategy(new StdInstantiatorStrategy()));

        final List<KryoBuilderConfiguration> builderConfigs = load(KryoBuilderConfiguration.class, customConverterClassNames, classLoader);
        for(KryoBuilderConfiguration config : builderConfigs) {
            config.configure(kryoBuilder);
        }

        return new KryoFactory() {
            @Override
            public Kryo create() {
                Kryo kryo = kryoBuilder.build();

                kryo.setDefaultSerializer(new KryoDefaultSerializerFactory.SerializerFactoryAdapter(_defaultSerializerFactory));

                if ( classLoader != null ) {
                    kryo.setClassLoader( classLoader );
                }

                // com.esotericsoftware.minlog.Log.TRACE = true;

                kryo.setRegistrationRequired(false);
                kryo.register( Arrays.asList( "" ).getClass(), new ArraysAsListSerializer() );
                kryo.register(InvocationHandler.class, new JdkProxySerializer());
                UnmodifiableCollectionsSerializer.registerSerializers(kryo);
                SynchronizedCollectionsSerializer.registerSerializers(kryo);

                kryo.addDefaultSerializer(EnumMap.class, EnumMapSerializer.class);
                SubListSerializers.addDefaultSerializers(kryo);

                final List<KryoCustomization> customizations = load(KryoCustomization.class, customConverterClassNames, classLoader, kryo);
                if ( customizations != null ) {
                    for( final KryoCustomization customization : customizations ) {
                        try {
                            LOG.info( "Executing KryoCustomization " + customization.getClass().getName() );
                            customization.customize( kryo );
                        } catch( final Throwable e ) {
                            LOG.error( "Could not execute customization " + customization, e );
                        }
                    }
                }

                return kryo;
            }
        };
    }

    protected Kryo createKryo(final ClassResolver classResolver, final ReferenceResolver referenceResolver, final StreamFactory streamFactory,
                              final ClassLoader classLoader, final String[] customConverterClassNames, final boolean copyCollectionsForSerialization) {
        return new Kryo(classResolver, referenceResolver, streamFactory) {

            private final List<SerializerFactory> serializerFactories = load(SerializerFactory.class, customConverterClassNames, classLoader, this);

            @Override
            @SuppressWarnings( { "rawtypes", "unchecked" } )
            public Serializer getDefaultSerializer(final Class clazz) {
                final Serializer customSerializer = loadCustomSerializer( clazz, serializerFactories );
                if ( customSerializer != null ) {
                    return customSerializer;
                }
                if ( copyCollectionsForSerialization ) {
                    // could also be installed via addDefaultSerializer
                    final Serializer copyCollectionSerializer = loadCopyCollectionSerializer( clazz );
                    if ( copyCollectionSerializer != null ) {
                        return copyCollectionSerializer;
                    }
                }
                return super.getDefaultSerializer( clazz );
            }

            private Serializer loadCustomSerializer(final Class<?> clazz, List<SerializerFactory> serializerFactories) {
                if ( serializerFactories != null ) {
                    for (SerializerFactory serializerFactory : serializerFactories) {
                        final Serializer serializer = serializerFactory.newSerializer(clazz);
                        if (serializer != null) {
                            if (LOG.isDebugEnabled()) {
                                LOG.debug("Loading custom serializer " + serializer.getClass().getName() + " for class " + clazz);
                            }
                            return serializer;
                        }
                    }
                }
                return null;
            }

        };
    }
    
    private Serializer loadCopyCollectionSerializer( final Class<?> clazz ) {
        if ( Collection.class.isAssignableFrom( clazz ) ) {
            if ( LOG.isDebugEnabled() ) {
                LOG.debug( "Loading CopyForIterateCollectionSerializer for class " + clazz );
            }
            return new CopyForIterateCollectionSerializer();
        }
        if ( Map.class.isAssignableFrom( clazz ) ) {
            if ( LOG.isDebugEnabled() ) {
                LOG.debug( "Loading CopyForIterateMapSerializer for class " + clazz );
            }
            return new CopyForIterateMapSerializer();
        }
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @SuppressWarnings( "unchecked" )
    @Override
    public Map<String, Object> deserializeAttributes( final byte[] data ) {
        final Kryo kryo = _kryoPool.borrow();
        try {
            return kryo.readObject(new Input(data), ConcurrentHashMap.class);
        } catch ( final RuntimeException e ) {
            throw new TranscoderDeserializationException( e );
        } finally {
            _kryoPool.release(kryo);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public byte[] serializeAttributes( final MemcachedBackupSession session, final Map<String, Object> attributes ) {
        final Kryo kryo = _kryoPool.borrow();
        try {
            /**
             * Creates an ObjectStream with an initial buffer size of 50KB and a maximum size of 1000KB.
             */
            Output out = new Output(_initialBufferSize, _maxBufferSize);
            kryo.writeObject(out, attributes);
            return out.toBytes();
        } catch ( final RuntimeException e ) {
            throw new TranscoderDeserializationException( e );
        } finally {
            _kryoPool.release(kryo);
        }
    }

    private <T> List<T> load( Class<T> type, final String[] customConverterClassNames, final ClassLoader classLoader) {
        return load(type, customConverterClassNames, classLoader, null);
    }

    private <T> List<T> load( Class<T> type, final String[] customConverterClassNames, final ClassLoader classLoader, final Kryo kryo) {
        if (customConverterClassNames == null || customConverterClassNames.length == 0 ) {
            return Collections.emptyList();
        }
        final List<T> result = new ArrayList<T>();
        final ClassLoader loader = classLoader != null ? classLoader : Thread.currentThread().getContextClassLoader();
        for (final String element : customConverterClassNames) {
            try {
                final Class<?> clazz = Class.forName( element, true, loader );
                if ( type.isAssignableFrom( clazz ) ) {
                    LOG.info("Loading " + type.getSimpleName() + " " + element);
                    final T item = createInstance(clazz.asSubclass(type), kryo);
                    result.add( item );
                }
            } catch (final Exception e) {
                LOG.error("Could not instantiate " + element + ", omitting this "+ type.getSimpleName() +".", e);
                throw new RuntimeException("Could not load "+ type.getSimpleName() +" " + element, e);
            }
        }
        return result;
    }

    private static <T> T createInstance( final Class<? extends T> clazz, final Kryo kryo ) throws SecurityException, NoSuchMethodException, IllegalArgumentException, InstantiationException, IllegalAccessException, InvocationTargetException {
        try {
            final Constructor<? extends T> constructor = clazz.getConstructor( Kryo.class );
            return constructor.newInstance( kryo );
        } catch ( final NoSuchMethodException nsme ) {
            final Constructor<? extends T> constructor = clazz.getConstructor();
            return constructor.newInstance();
        }
    }

}

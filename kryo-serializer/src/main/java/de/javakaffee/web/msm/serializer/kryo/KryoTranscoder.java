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

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Currency;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.ObjectBuffer;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.serialize.BigDecimalSerializer;
import com.esotericsoftware.kryo.serialize.BigIntegerSerializer;

import de.javakaffee.kryoserializers.ArraysAsListSerializer;
import de.javakaffee.kryoserializers.ClassSerializer;
import de.javakaffee.kryoserializers.CollectionsEmptyListSerializer;
import de.javakaffee.kryoserializers.CollectionsEmptyMapSerializer;
import de.javakaffee.kryoserializers.CollectionsEmptySetSerializer;
import de.javakaffee.kryoserializers.CollectionsSingletonListSerializer;
import de.javakaffee.kryoserializers.CollectionsSingletonMapSerializer;
import de.javakaffee.kryoserializers.CollectionsSingletonSetSerializer;
import de.javakaffee.kryoserializers.CopyForIterateCollectionSerializer;
import de.javakaffee.kryoserializers.CopyForIterateMapSerializer;
import de.javakaffee.kryoserializers.CurrencySerializer;
import de.javakaffee.kryoserializers.EnumMapSerializer;
import de.javakaffee.kryoserializers.EnumSetSerializer;
import de.javakaffee.kryoserializers.GregorianCalendarSerializer;
import de.javakaffee.kryoserializers.JdkProxySerializer;
import de.javakaffee.kryoserializers.KryoReflectionFactorySupport;
import de.javakaffee.kryoserializers.StringBufferSerializer;
import de.javakaffee.kryoserializers.StringBuilderSerializer;
import de.javakaffee.kryoserializers.SubListSerializer;
import de.javakaffee.kryoserializers.SynchronizedCollectionsSerializer;
import de.javakaffee.kryoserializers.UnmodifiableCollectionsSerializer;
import de.javakaffee.web.msm.MemcachedBackupSession;
import de.javakaffee.web.msm.SessionAttributesTranscoder;
import de.javakaffee.web.msm.SessionTranscoder;

/**
 * A {@link SessionAttributesTranscoder} that uses {@link Kryo} for serialization.
 * 
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class KryoTranscoder extends SessionTranscoder implements SessionAttributesTranscoder {

    private static final Log LOG = LogFactory.getLog( KryoTranscoder.class );
    
    public static final int DEFAULT_INITIAL_BUFFER_SIZE = 100 * 1024;
    public static final int DEFAULT_MAX_BUFFER_SIZE = 2000 * 1024;
    
    private final Kryo _kryo;
    private final SerializerFactory[] _serializerFactories;
    private final UnregisteredClassHandler[] _unregisteredClassHandlers;

    private final int _initialBufferSize;
    private final int _maxBufferSize;

    /**
     * 
     */
    public KryoTranscoder() {
        this( null, null, false );
    }
    
    /**
     * @param classLoader
     * @param copyCollectionsForSerialization 
     * @param customConverterClassNames 
     */
    public KryoTranscoder( final ClassLoader classLoader, final String[] customConverterClassNames, final boolean copyCollectionsForSerialization ) {
        this( classLoader, customConverterClassNames, copyCollectionsForSerialization, DEFAULT_INITIAL_BUFFER_SIZE, DEFAULT_MAX_BUFFER_SIZE );
    }
    
    /**
     * @param classLoader
     * @param copyCollectionsForSerialization 
     * @param customConverterClassNames 
     */
    public KryoTranscoder( final ClassLoader classLoader, final String[] customConverterClassNames,
            final boolean copyCollectionsForSerialization, final int initialBufferSize, final int maxBufferSize ) {
        LOG.info( "Starting with initialBufferSize " + initialBufferSize + " and maxBufferSize " + maxBufferSize );
        final Triple<Kryo, SerializerFactory[], UnregisteredClassHandler[]> triple = createKryo( classLoader, customConverterClassNames, copyCollectionsForSerialization );
        _kryo = triple.a;
        _serializerFactories = triple.b;
        _unregisteredClassHandlers = triple.c;
        _initialBufferSize = initialBufferSize;
        _maxBufferSize = maxBufferSize;
    }

    private Triple<Kryo, SerializerFactory[], UnregisteredClassHandler[]> createKryo( final ClassLoader classLoader,
            final String[] customConverterClassNames, final boolean copyCollectionsForSerialization ) {
        
        final Kryo kryo = new KryoReflectionFactorySupport() {
            
            @Override
            @SuppressWarnings( "unchecked" )
            public Serializer newSerializer(final Class clazz) {
                final Serializer customSerializer = loadCustomSerializer( clazz );
                if ( customSerializer != null ) {
                    return customSerializer;
                }
                if ( EnumSet.class.isAssignableFrom( clazz ) ) {
                    return new EnumSetSerializer( this );
                }
                if ( EnumMap.class.isAssignableFrom( clazz ) ) {
                    return new EnumMapSerializer( this );
                }
                if ( SubListSerializer.canSerialize( clazz ) ) {
                    return new SubListSerializer( this );
                }
                if ( copyCollectionsForSerialization ) {
                    final Serializer copyCollectionSerializer = loadCopyCollectionSerializer( clazz, this );
                    if ( copyCollectionSerializer != null ) {
                        return copyCollectionSerializer;
                    }
                }
                return super.newSerializer( clazz );
            }
            
            @SuppressWarnings( "unchecked" )
            @Override
            protected void handleUnregisteredClass( final Class clazz ) {
                if ( _unregisteredClassHandlers != null ) {
                    for( int i = 0; i < _unregisteredClassHandlers.length; i++ ) {
                        final boolean handled = _unregisteredClassHandlers[i].handleUnregisteredClass( clazz );
                        if ( handled ) {
                            if ( LOG.isDebugEnabled() ) {
                                LOG.debug( "UnregisteredClassHandler " + _unregisteredClassHandlers[i].getClass().getName() + " handled class " + clazz );
                            }
                            return;
                        }
                    }
                }
                super.handleUnregisteredClass( clazz );
            }
            
        };
        
        if ( classLoader != null ) {
            kryo.setClassLoader( classLoader );
        }
        
        // com.esotericsoftware.minlog.Log.TRACE = true;
        
        kryo.setRegistrationOptional( true );
        kryo.register( ArrayList.class );
        kryo.register( LinkedList.class );
        kryo.register( HashSet.class );
        kryo.register( HashMap.class );
        kryo.register( Arrays.asList( "" ).getClass(), new ArraysAsListSerializer( kryo ) );
        kryo.register( Currency.class, new CurrencySerializer( kryo ) );
        kryo.register( StringBuffer.class, new StringBufferSerializer( kryo ) );
        kryo.register( StringBuilder.class, new StringBuilderSerializer( kryo ) );
        kryo.register( Collections.EMPTY_LIST.getClass(), new CollectionsEmptyListSerializer() );
        kryo.register( Collections.EMPTY_MAP.getClass(), new CollectionsEmptyMapSerializer() );
        kryo.register( Collections.EMPTY_SET.getClass(), new CollectionsEmptySetSerializer() );
        kryo.register( Collections.singletonList( "" ).getClass(), new CollectionsSingletonListSerializer( kryo ) );
        kryo.register( Collections.singleton( "" ).getClass(), new CollectionsSingletonSetSerializer( kryo ) );
        kryo.register( Collections.singletonMap( "", "" ).getClass(), new CollectionsSingletonMapSerializer( kryo ) );
        kryo.register( Class.class, new ClassSerializer( kryo ) );
        kryo.register( BigDecimal.class, new BigDecimalSerializer() );
        kryo.register( BigInteger.class, new BigIntegerSerializer() );
        kryo.register( GregorianCalendar.class, new GregorianCalendarSerializer() );
        kryo.register( InvocationHandler.class, new JdkProxySerializer( kryo ) );
        UnmodifiableCollectionsSerializer.registerSerializers( kryo );
        SynchronizedCollectionsSerializer.registerSerializers( kryo );
        
        final Triple<KryoCustomization[], SerializerFactory[], UnregisteredClassHandler[]> pair = loadCustomConverter( customConverterClassNames,
                classLoader, kryo );
        
        final KryoCustomization[] customizations = pair.a;
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
        
        return Triple.create( kryo, pair.b, pair.c );
    }
    
    private Serializer loadCustomSerializer( final Class<?> clazz ) {
        if ( _serializerFactories != null ) {
            for( int i = 0; i < _serializerFactories.length; i++ ) {
                final Serializer serializer = _serializerFactories[i].newSerializer( clazz );
                if ( serializer != null ) {
                    if ( LOG.isDebugEnabled() ) {
                        LOG.debug( "Loading custom serializer " + serializer.getClass().getName() + " for class " + clazz );
                    }
                    return serializer;
                }
            }
        }
        return null;
    }
    
    private Serializer loadCopyCollectionSerializer( final Class<?> clazz, final Kryo kryo ) {
        if ( Collection.class.isAssignableFrom( clazz ) ) {
            if ( LOG.isDebugEnabled() ) {
                LOG.debug( "Loading CopyForIterateCollectionSerializer for class " + clazz );
            }
            return new CopyForIterateCollectionSerializer( kryo );
        }
        if ( Map.class.isAssignableFrom( clazz ) ) {
            if ( LOG.isDebugEnabled() ) {
                LOG.debug( "Loading CopyForIterateMapSerializer for class " + clazz );
            }
            return new CopyForIterateMapSerializer( kryo );
        }
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @SuppressWarnings( "unchecked" )
    @Override
    public Map<String, Object> deserializeAttributes( final byte[] data ) {
        return new ObjectBuffer( _kryo ).readObject( data, ConcurrentHashMap.class );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public byte[] serializeAttributes( final MemcachedBackupSession session, final Map<String, Object> attributes ) {
        /**
         * Creates an ObjectStream with an initial buffer size of 50KB and a maximum size of 1000KB.
         */
        return new ObjectBuffer( _kryo, _initialBufferSize, _maxBufferSize  ).writeObject( attributes );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected MemcachedBackupSession deserialize( final byte[] in ) {
        throw new UnsupportedOperationException( "Session deserialization not implemented." );
    }

    private Triple<KryoCustomization[], SerializerFactory[], UnregisteredClassHandler[]> loadCustomConverter( final String[] customConverterClassNames, final ClassLoader classLoader,
            final Kryo kryo ) {
        if ( customConverterClassNames == null || customConverterClassNames.length == 0 ) {
            return Triple.empty();
        }
        final List<KryoCustomization> customizations = new ArrayList<KryoCustomization>();
        final List<SerializerFactory> serializerFactories = new ArrayList<SerializerFactory>();
        final List<UnregisteredClassHandler> unregisteredClassHandlers = new ArrayList<UnregisteredClassHandler>();
        final ClassLoader loader = classLoader != null ? classLoader : Thread.currentThread().getContextClassLoader();
        for ( int i = 0; i < customConverterClassNames.length; i++ ) {
            final String element = customConverterClassNames[i];
            try {
                processElement( element, customizations, serializerFactories, unregisteredClassHandlers, kryo, loader );
            } catch ( final Exception e ) {
                LOG.error( "Could not instantiate " + element + ", omitting this KryoCustomization/SerializerFactory.", e );
                throw new RuntimeException( "Could not load serializer " + element, e );
            }
        }
        final KryoCustomization[] customizationsArray = customizations.toArray( new KryoCustomization[customizations.size()] );
        final SerializerFactory[] serializerFactoriesArray = serializerFactories.toArray( new SerializerFactory[serializerFactories.size()] );
        final UnregisteredClassHandler[] unregisteredClassHandlersArray = unregisteredClassHandlers.toArray( new UnregisteredClassHandler[unregisteredClassHandlers.size()] );
        return Triple.create( customizationsArray, serializerFactoriesArray, unregisteredClassHandlersArray );
    }

    private void processElement( final String element, final List<KryoCustomization> customizations,
            final List<SerializerFactory> serializerFactories, final List<UnregisteredClassHandler> unregisteredClassHandlers, final Kryo kryo, final ClassLoader loader )
        throws ClassNotFoundException, NoSuchMethodException, InstantiationException, IllegalAccessException,
        InvocationTargetException {
        final Class<?> clazz = Class.forName( element, true, loader );
        if ( KryoCustomization.class.isAssignableFrom( clazz ) ) {
            LOG.info( "Loading KryoCustomization " + element );
            final KryoCustomization customization = createInstance( clazz.asSubclass( KryoCustomization.class ), kryo );
            customizations.add( customization );
            if ( customization instanceof SerializerFactory ) {
                serializerFactories.add( (SerializerFactory) customization );
            }
        }
        if ( SerializerFactory.class.isAssignableFrom( clazz ) ) {
            LOG.info( "Loading SerializerFactory " + element );
            final SerializerFactory factory = createInstance( clazz.asSubclass( SerializerFactory.class ), kryo );
            serializerFactories.add( factory );
        }
        if ( UnregisteredClassHandler.class.isAssignableFrom( clazz ) ) {
            LOG.info( "Loading UnregisteredClassHandler " + element );
            final UnregisteredClassHandler handler = createInstance( clazz.asSubclass( UnregisteredClassHandler.class ), kryo );
            unregisteredClassHandlers.add( handler );
        }
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
    
    private static class Triple<A,B,C> {
        private static final Triple<?, ?, ?> EMPTY = Triple.create( null, null, null );
        private final A a;
        private final B b;
        private final C c;
        public Triple( final A a, final B b, final C c ) {
            this.a = a;
            this.b = b;
            this.c = c;
        }
        public static <A, B, C> Triple<A, B, C> create( final A a, final B b, final C c ) {
            return new Triple<A, B, C>( a, b, c );
        }
        @SuppressWarnings( "unchecked" )
        public static <A, B, C> Triple<A, B, C> empty() {
            return (Triple<A, B, C>) EMPTY;
        }
    }

}

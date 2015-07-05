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
package de.javakaffee.web.msm.serializer.kryo;

import de.javakaffee.web.msm.MemcachedSessionService.SessionManager;
import de.javakaffee.web.msm.SessionAttributesTranscoder;
import de.javakaffee.web.msm.TranscoderFactory;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

/**
 * Creates a {@link KryoTranscoder}.
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class KryoTranscoderFactory implements TranscoderFactory {
    
    private static final Log LOG = LogFactory.getLog( KryoTranscoderFactory.class );
    
    public static final String PROP_INIT_BUFFER_SIZE = "msm.kryo.bufferSize.initial";
    public static final String PROP_ENV_MAX_BUFFER_SIZE = "msm.kryo.bufferSize.max";
    public static final String PROP_ENV_DEFAULT_FACTORY = "msm.kryo.defaultSerializerFactory";

    private boolean _copyCollectionsForSerialization;
    private String[] _customConverterClassNames;
    private KryoTranscoder _transcoder;

    /**
     * {@inheritDoc}
     */
    @Override
    public SessionAttributesTranscoder createTranscoder( final SessionManager manager ) {
        return getTranscoder( manager.getContainerClassLoader() );
    }

    protected SessionAttributesTranscoder createTranscoder( final ClassLoader loader ) {
        return getTranscoder( loader );
    }

    /**
     * Gets/creates a single instance of {@link KryoTranscoder}.
     *
     * @param classLoader the class loader of the web app container/context.
     * @return for all invocations the same instance of {@link KryoTranscoder}.
     */
    private KryoTranscoder getTranscoder( final ClassLoader classLoader ) {
        if ( _transcoder == null ) {
            final int initialBufferSize = getSysPropValue( PROP_INIT_BUFFER_SIZE, KryoTranscoder.DEFAULT_INITIAL_BUFFER_SIZE );
            final int maxBufferSize = getSysPropValue( PROP_ENV_MAX_BUFFER_SIZE, KryoTranscoder.DEFAULT_MAX_BUFFER_SIZE );
            final String defaultSerializerFactory = getSysPropValue( PROP_ENV_DEFAULT_FACTORY, KryoTranscoder.DEFAULT_SERIALIZER_FACTORY_CLASS );
            _transcoder = new KryoTranscoder( classLoader, _customConverterClassNames, _copyCollectionsForSerialization,
                    initialBufferSize, maxBufferSize, defaultSerializerFactory );
        }
        return _transcoder;
    }

    private int getSysPropValue( final String propName, final int defaultValue ) {
        int value = defaultValue;
        final String propValue = System.getProperty( propName );
        if ( propValue != null ) {
            try {
                value = Integer.parseInt( propValue );
            } catch( final NumberFormatException e ) {
                LOG.warn( "Could not parse system property " + propName + ": " + e );
            }
        }
        return value;
    }

    private String getSysPropValue( final String propName, final String defaultValue ) {
        final String propValue = System.getProperty( propName );
        if ( propValue == null || propValue.trim().length() == 0 ) {
            return defaultValue;
        }
        return propValue;
    }

    /**
     * {@inheritDoc}
     */
    @Override
	public void setCopyCollectionsForSerialization( final boolean copyCollectionsForSerialization ) {
        _copyCollectionsForSerialization = copyCollectionsForSerialization;
    }

    /**
     * {@inheritDoc}
     */
    @Override
	@edu.umd.cs.findbugs.annotations.SuppressWarnings("EI_EXPOSE_REP2")
    public void setCustomConverterClassNames( final String[] customConverterClassNames ) {
        _customConverterClassNames = customConverterClassNames;
    }

}

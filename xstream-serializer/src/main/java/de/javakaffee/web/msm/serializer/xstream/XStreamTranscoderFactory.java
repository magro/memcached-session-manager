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
package de.javakaffee.web.msm.serializer.xstream;

import net.spy.memcached.transcoders.Transcoder;

import org.apache.catalina.Manager;

import de.javakaffee.web.msm.TranscoderFactory;

/**
 * Creates {@link XStreamTranscoder} instances.
 * 
 * @author Martin Grotzke (martin.grotzke@freiheit.com) (initial creation)
 */
public class XStreamTranscoderFactory implements TranscoderFactory {

    /**
     * {@inheritDoc}
     */
    @Override
    public Transcoder<Object> createTranscoder( final Manager manager ) {
        return new XStreamTranscoder( manager );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setCopyCollectionsForSerialization( final boolean val ) {
        if ( val ) {
            throw new UnsupportedOperationException( "XStream's CollectionConverter does not seem" +
                    " to be able to do this. See http://xstream.codehaus.org/converters.html" +
                    " for a list of xstream converters." );
        }
    }

    /**
     * Throws an {@link UnsupportedOperationException}, as java serialization
     * does not support custom xml format.
     * 
     * @param customConverterClassNames a list of class names or <code>null</code>.
     */
    public void setCustomConverterClassNames( final String[] customConverterClassNames ) {
        if ( customConverterClassNames != null && customConverterClassNames.length > 0 ) {
            throw new UnsupportedOperationException( "XStream based serialization does not support custom converter." );
        }
    }

}

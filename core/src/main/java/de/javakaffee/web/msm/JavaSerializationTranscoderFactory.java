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
package de.javakaffee.web.msm;

import de.javakaffee.web.msm.MemcachedSessionService.SessionManager;

/**
 * A {@link TranscoderFactory} that creates {@link JavaSerializationTranscoder} instances.
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 * @version $Id$
 */
public class JavaSerializationTranscoderFactory implements TranscoderFactory {

    /**
     * {@inheritDoc}
     */
    @Override
    public SessionAttributesTranscoder createTranscoder( final SessionManager manager ) {
        return new JavaSerializationTranscoder( manager );
    }

    /**
     * If <code>copyCollectionsForSerialization</code> is set to <code>true</code>,
     * an {@link UnsupportedOperationException} will be thrown, as java serialization
     * cannot be changed and it does not copy collections for serialization.
     *
     * @param copyCollectionsForSerialization the copyCollectionsForSerialization value
     */
    @Override
    public void setCopyCollectionsForSerialization( final boolean copyCollectionsForSerialization ) {
        if ( copyCollectionsForSerialization ) {
            throw new UnsupportedOperationException(
                    "Java serialization cannot be changed - it does not copy collections for serialization." );
        }
    }

    /**
     * Throws an {@link UnsupportedOperationException}, as java serialization
     * does not support custom xml format.
     *
     * @param customConverterClassNames a list of class names or <code>null</code>.
     */
    @Override
    public void setCustomConverterClassNames( final String[] customConverterClassNames ) {
        if ( customConverterClassNames != null && customConverterClassNames.length > 0 ) {
            throw new UnsupportedOperationException( "Java serialization does not support custom converter." );
        }
    }

}

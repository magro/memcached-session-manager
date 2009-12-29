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

import net.spy.memcached.transcoders.Transcoder;

import org.apache.catalina.Manager;
import org.apache.catalina.session.StandardSession;

/**
 * A {@link net.spy.memcached.transcoders.Transcoder} that serializes catalina
 * {@link StandardSession}s using the serialization of {@link StandardSession}.
 * 
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 * @version $Id$
 */
public class SessionSerializingTranscoderFactory implements TranscoderFactory {

    /**
     * {@inheritDoc}
     */
    @Override
    public Transcoder<Object> createTranscoder( final Manager manager ) {
        return new SessionSerializingTranscoder( manager );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setCopyCollectionsForSerialization( final boolean copyCollectionsForSerialization ) {
        if ( copyCollectionsForSerialization ) {
            throw new UnsupportedOperationException(
                    "Java serialization cannot be changed - it does not copy collections for serialization." );
        }
    }

}

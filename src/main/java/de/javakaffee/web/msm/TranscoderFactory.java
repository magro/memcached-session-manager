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
 * The factory to create a {@link net.spy.memcached.transcoders.Transcoder} that
 * serializes and deserializes catalina {@link StandardSession}s.
 * 
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 * @version $Id$
 */
public interface TranscoderFactory {

    /**
     * Creates a new {@link Transcoder} with the given manager.
     * 
     * @param manager
     *            the manager that needs to be set on deserialized sessions.
     * @return an implementation of {@link Transcoder}.
     */
    Transcoder<Object> createTranscoder( Manager manager );

    /**
     * Specifies, if iterating over collection elements shall be done on a copy
     * of the collection or on the collection itself.
     * <p>
     * This will be called before {@link #createTranscoder(Manager)}, so that
     * you can use this property in {@link #createTranscoder(Manager)}.
     * </p>
     * 
     * @param copyCollectionsForSerialization
     *            the boolean value.
     */
    void setCopyCollectionsForSerialization( boolean copyCollectionsForSerialization );

}

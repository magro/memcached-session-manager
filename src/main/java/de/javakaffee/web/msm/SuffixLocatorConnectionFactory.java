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

import java.util.List;

import net.spy.memcached.ConnectionFactory;
import net.spy.memcached.DefaultConnectionFactory;
import net.spy.memcached.MemcachedNode;
import net.spy.memcached.NodeLocator;
import net.spy.memcached.transcoders.Transcoder;

import org.apache.catalina.Manager;


/**
 * This {@link ConnectionFactory} uses the {@link SuffixBasedNodeLocator}
 * as {@link NodeLocator} and the {@link SessionSerializingTranscoder} as
 * {@link Transcoder}.
 * 
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 * @version $Id$
 */
public final class SuffixLocatorConnectionFactory extends
        DefaultConnectionFactory {
    
    private final Manager _manager;
    private final SessionIdFormat _sessionIdFormat;
    private final NodeIdResolver _resolver;
    
    public SuffixLocatorConnectionFactory( Manager manager, NodeIdResolver resolver, SessionIdFormat sessionIdFormat ) {
        _manager = manager;
        _resolver = resolver;
        _sessionIdFormat = sessionIdFormat;
    }

    /* (non-Javadoc)
     * @see net.spy.memcached.DefaultConnectionFactory#createLocator(java.util.List)
     */
    @Override
    public NodeLocator createLocator(
            List<MemcachedNode> nodes ) {
        return new SuffixBasedNodeLocator( nodes, _resolver, _sessionIdFormat );
    }
    
    @Override
    public Transcoder<Object> getDefaultTranscoder() {
        return new SessionSerializingTranscoder( _manager );
    }
    
}
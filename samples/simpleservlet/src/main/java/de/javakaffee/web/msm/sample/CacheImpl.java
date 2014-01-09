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
package de.javakaffee.web.msm.sample;

import java.io.Serializable;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple {@link CacheImpl} implementation.
 * 
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class CacheImpl implements Cache, Serializable {

    private static final long serialVersionUID = 1L;

    private final ConcurrentHashMap<String, Object> _map;

    public CacheImpl() {
        _map = new ConcurrentHashMap<String, Object>();
    }

    @Override
    public Object put( final String key, final Object value ) {
        return _map.put( key, value );
    }

    @Override
    public Object get( final String key ) {
        return _map.get( key );
    }

    @Override
    public Set<Entry<String, Object>> entrySet() {
        return _map.entrySet();
    }

}

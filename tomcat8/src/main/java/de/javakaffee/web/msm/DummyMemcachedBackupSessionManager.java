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
package de.javakaffee.web.msm;



/**
 * This {@link MemcachedBackupSessionManager} can be used for debugging session
 * <em>deserialization</em> - to see if serialized session data actually can be
 * deserialized. Session data is serialized at the end of the request as normal (stored
 * in a simple map), and deserialized when a following request is asking for the session.
 * The deserialization is done like this (instead of directly at the end of the request
 * when it is serialized) to perform deserialization at the same point in the lifecycle
 * as it would happen in the real failover case (there might be difference in respect
 * to initialized ThreadLocals or other stuff).
 * <p>
 * The memcached configuration (<code>memcachedNodes</code>, <code>failoverNode</code>) is
 * not used to create a memcached client, so serialized session data will <strong>not</strong>
 * be sent to memcached - and therefore no running memcacheds are required. Though, the
 * <code>memcachedNodes</code> attribute is still required (use some dummy values).
 * </p>
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 * @version $Id$
 */
public class DummyMemcachedBackupSessionManager extends MemcachedBackupSessionManager {
    
    public DummyMemcachedBackupSessionManager() {
        _msm = new DummyMemcachedSessionService<MemcachedBackupSessionManager>( this );
    }

}

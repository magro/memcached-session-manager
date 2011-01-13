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

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import javax.servlet.http.HttpSession;

import org.apache.catalina.Manager;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

import de.javakaffee.web.msm.MemcachedBackupSessionManager.LockStatus;

/**
 * This {@link Manager} stores session in configured memcached nodes after the
 * response is finished (committed).
 * <p>
 * Use this session manager in a Context element, like this <code><pre>
 * &lt;Context path="/foo"&gt;
 *     &lt;Manager className="de.javakaffee.web.msm.MemcachedBackupSessionManager"
 *         memcachedNodes="n1.localhost:11211 n2.localhost:11212" failoverNodes="n2"
 *         requestUriIgnorePattern=".*\.(png|gif|jpg|css|js)$" /&gt;
 * &lt;/Context&gt;
 * </pre></code>
 * </p>
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 * @version $Id$
 */
public class SessionLock {

    @SuppressWarnings( "unused" )
    private static final Log _log = LogFactory.getLog( SessionLock.class );

    private static MemcachedBackupSessionManager _manager;

    SessionLock( MemcachedBackupSessionManager manager ) {
        _manager = manager;
    }

    public static boolean lock( final String sessionId, final long timeout, final TimeUnit timeUnit ) {
        LockStatus lockStatus = _manager.lock( sessionId, timeout, timeUnit );
        return lockStatus == LockStatus.LOCKED;
    }

    public static boolean lock( final String sessionId ) {
        LockStatus lockStatus = _manager.lock( sessionId );
        return lockStatus == LockStatus.LOCKED;
    }
    
    public static void unlock( final String sessionId ) {
        _manager.releaseLock( sessionId );
    }

}

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

import org.apache.catalina.Manager;

import de.javakaffee.web.msm.MemcachedSessionService.SessionManager;

/**
 * The session class used by the {@link MemcachedSessionService}.
 * <p>
 * This class is needed to e.g.
 * <ul>
 * <li>be able to change the session id without the whole notification lifecycle (which includes the
 * application also).</li>
 * <li>provide access to internal fields of the session, for serialization/deserialization</li>
 * <li>be able to coordinate backup and expirationUpdate</li>
 * </ul>
 * </p>
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public final class MemcachedBackupSessionJBW3 extends MemcachedBackupSession {

    private static final long serialVersionUID = -8459531033748538547L;
    
    private int _thisAccessedTimeFromLastBackupCheck;

    /**
     * Creates a new instance without a given manager. This has to be
     * assigned via {@link #setManager(Manager)} before this session is
     * used.
     *
     */
    public MemcachedBackupSessionJBW3() {
        super( null );
    }

    /**
     * Creates a new instance with the given manager.
     *
     * @param manager
     *            the manager
     */
    public MemcachedBackupSessionJBW3( final SessionManager manager ) {
        super( manager );
    }

    /**
     * Calculates the expiration time that must be sent to memcached,
     * based on the sessions maxInactiveInterval and the time the session
     * is already idle (based on thisAccessedTime).
     * <p>
     * Calculating this time instead of just using maxInactiveInterval is
     * important for the update of the expiration time: if we would just use
     * maxInactiveInterval, the session would exist longer in memcached than it would
     * be valid in tomcat.
     * </p>
     *
     * @return the expiration time in seconds
     */
    @Override
    int getMemcachedExpirationTimeToSet() {

        /* SRV.13.4 ("Deployment Descriptor"): If the timeout is 0 or less, the container
         * ensures the default behaviour of sessions is never to time out.
         */
        if ( maxInactiveInterval <= 0 ) {
            return 0;
        }

        if ( !_sticky ) {
            return 2 * maxInactiveInterval;
        }

        /* rounding is just for tests, as they are using actually seconds for testing.
         * with a default setup 1 second difference wouldn't matter...
         */
        final int offset = (int) (System.currentTimeMillis() - creationTime);
        final int timeIdle = (offset - thisAccessedTime) / 1000; // Math.round( (float)timeIdleInMillis / 1000L );
        final int expirationTime = getMaxInactiveInterval() - timeIdle;
        return expirationTime;
    }

    /**
     * Determines, if the session was accessed since the last backup.
     * @return <code>true</code>, if <code>thisAccessedTime > lastBackupTimestamp</code>.
     */
    @Override
    boolean wasAccessedSinceLastBackup() {
        return super.creationTime + super.thisAccessedTime > _lastBackupTime;
    }

    @Override
    void storeThisAccessedTimeFromLastBackupCheck() {
        _thisAccessedTimeFromLastBackupCheck = super.thisAccessedTime;
    }

    /**
     * Determines, if the current request accessed the session. This is provided,
     * if the current value of {@link #getThisAccessedTimeInternal()}
     * differs from the value stored by {@link #storeThisAccessedTimeFromLastBackupCheck()}.
     * @return <code>true</code> if the session was accessed since the invocation
     * of {@link #storeThisAccessedTimeFromLastBackupCheck()}.
     */
    @Override
    boolean wasAccessedSinceLastBackupCheck() {
        return _thisAccessedTimeFromLastBackupCheck != super.thisAccessedTime;
    }

}

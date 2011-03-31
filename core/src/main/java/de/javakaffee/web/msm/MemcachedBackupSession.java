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

import java.security.Principal;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.Map;
import java.util.HashMap;
import java.util.regex.Pattern;

import org.apache.catalina.Manager;
import org.apache.catalina.SessionListener;
import org.apache.catalina.session.StandardSession;

import de.javakaffee.web.msm.MemcachedBackupSessionManager.LockStatus;
import de.javakaffee.web.msm.SessionTrackerValve.SessionBackupService.BackupResultStatus;

/**
 * The session class used by the {@link MemcachedBackupSessionManager}.
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
public final class MemcachedBackupSession extends StandardSession {

    private static final long serialVersionUID = 1L;

    /*
     * The hash code of the serialized byte[] of this session that is
     * used to determine, if the session was modified.
     */
    private transient int _dataHashCode;

    /*
     * Used to determine, if the session was #accessed since it was
     * last backup'ed (or checked if it needs to be backup'ed)
     */
    private transient long _thisAccessedTimeFromLastBackupCheck;

    /*
     * Stores the time in millis when this session was stored in memcached, is set
     * before session data is serialized.
     */
    private long _lastBackupTime;

    /*
     * The former value of _lastBackupTimestamp which is restored if the session could not be saved
     * in memcached.
     */
    private transient long _previousLastBackupTime;

    /*
     * The expiration time that was sent to memcached with the last backup/touch.
     */
    private transient int _lastMemcachedExpirationTime;

    /*
     * Stores, if the sessions expiration is just being updated in memcached
     */
    private volatile transient boolean _expirationUpdateRunning;

    /*
     * Stores, if the sessions is just being backuped
     */
    private volatile transient boolean _backupRunning;

    private transient boolean _authenticationChanged;

    private transient boolean _attributesAccessed;

    private transient boolean _sessionIdChanged;
    private transient boolean _sticky;
    private volatile transient LockStatus _lockStatus;

    /**
     * Creates a new instance without a given manager. This has to be
     * assigned via {@link #setManager(Manager)} before this session is
     * used.
     *
     */
    public MemcachedBackupSession() {
        super( null );
    }

    /**
     * Creates a new instance with the given manager.
     *
     * @param manager
     *            the manager
     */
    public MemcachedBackupSession( final Manager manager ) {
        super( manager );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Object getAttribute( final String name ) {
        if (filterAttribute(name)) {
            _attributesAccessed = true;
        }
        return super.getAttribute( name );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setAttribute( final String name, final Object value ) {
        if (filterAttribute(name)) {
            _attributesAccessed = true;
        }
        super.setAttribute( name, value );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setAttribute( final String name, final Object value, final boolean notify ) {
        if (filterAttribute(name)) {
            _attributesAccessed = true;
        }
        super.setAttribute( name, value, notify );
    }

    @Override
    public void recycle() {
        super.recycle();
        _dataHashCode = 0;
        _expirationUpdateRunning = false;
        _backupRunning = false;
        _lockStatus = null;
    }

    /**
     * Check whether the given attribute name matches our name pattern.
     *
     * @return true if the name matches
     */
    private boolean filterAttribute(String name) {
        if ( this.manager == null ) {
            throw new IllegalStateException( "There's no manager set." );
        }
        Pattern pattern = ((MemcachedBackupSessionManager)manager).getSessionAttributePattern();
        if ( pattern == null ) {
            return true;
        }
        return pattern.matcher(name).matches();
    }
    
    /**
     * Filter map of attributes using our name pattern.
     *
     * @return the filtered attribute map
     */
    private Map<String, Object> filterAttributes(Map<String, Object> map) {
        if ( this.manager == null ) {
            throw new IllegalStateException( "There's no manager set." );
        }
        Pattern pattern = ((MemcachedBackupSessionManager)manager).getSessionAttributePattern();
        if ( pattern == null ) {
            return map;
        }
        HashMap<String, Object> result = new HashMap<String, Object>(map.size());
        for (String name: map.keySet()) {
            if (pattern.matcher(name).matches()) {
                result.put(name, map.get(name));
            }
        }
        return result;
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

        final long timeIdleInMillis = System.currentTimeMillis() - getThisAccessedTimeInternal();
        /* rounding is just for tests, as they are using actually seconds for testing.
         * with a default setup 1 second difference wouldn't matter...
         */
        final int timeIdle = Math.round( (float)timeIdleInMillis / 1000L );
        final int expirationTime = getMaxInactiveInterval() - timeIdle;
        return expirationTime;
    }

    /**
     * Gets the time in seconds when this session will expire in memcached.
     * If the session was stored in memcached with expiration 0 this method will just
     * return 0.
     *
     * @return the time in seconds
     */
    int getMemcachedExpirationTime() {
        if ( !_sticky ) {
            throw new IllegalStateException( "The memcached expiration time cannot be determined in non-sticky mode." );
        }
        if ( _lastMemcachedExpirationTime == 0 ) {
            return 0;
        }

        final long timeIdleInMillis = _lastBackupTime == 0 ? 0 : System.currentTimeMillis() - _lastBackupTime;
        /* rounding is just for tests, as they are using actually seconds for testing.
         * with a default setup 1 second difference wouldn't matter...
         */
        final int timeIdle = Math.round( (float)timeIdleInMillis / 1000L );
        final int expirationTime = _lastMemcachedExpirationTime - timeIdle;
        return expirationTime;
    }

    /**
     * Store the time in millis when this session was successfully stored in memcached. This timestamp
     * is stored in memcached also to support non-sticky session mode. It's reset on backup failure
     * (see {@link #backupFailed()}), not on skipped backup, therefore it must be set after skip checks
     * before session serialization.
     * @param lastBackupTime the lastBackupTimestamp to set
     */
    void setLastBackupTime( final long lastBackupTime ) {
        _previousLastBackupTime = _lastBackupTime;
        _lastBackupTime = lastBackupTime;
    }

    /**
     * The time in millis when this session was the last time successfully stored in memcached. There's a short time when
     * this timestamp represents just the current time (right before backup) - it's updated just after
     * {@link #setBackupRunning(boolean)} was set to false and is reset in the case of backup failure, therefore when
     * {@link #isBackupRunning()} is <code>false</code>, it definitely represents the timestamp when this session was
     * stored in memcached the last time.
     */
    long getLastBackupTime() {
        return _lastBackupTime;
    }

    /**
     * The time in seconds that passed since this session was stored in memcached.
     */
    int getSecondsSinceLastBackup() {
        final long timeNotUpdatedInMemcachedInMillis = System.currentTimeMillis() - _lastBackupTime;
        return Math.round( (float)timeNotUpdatedInMemcachedInMillis / 1000L );
    }

    /**
     * The expiration time that was sent to memcached with the last backup/touch.
     *
     * @return the lastMemcachedExpirationTime
     */
    int getLastMemcachedExpirationTime() {
        return _lastMemcachedExpirationTime;
    }

    /**
     * Set the expiration time that was sent to memcached with this backup/touch.
     * @param lastMemcachedExpirationTime the lastMemcachedExpirationTime to set
     */
    void setLastMemcachedExpirationTime( final int lastMemcachedExpirationTime ) {
        _lastMemcachedExpirationTime = lastMemcachedExpirationTime;
    }

    /**
     * Determines, if the session was accessed since the last backup.
     * @return <code>true</code>, if <code>thisAccessedTime > lastBackupTimestamp</code>.
     */
    boolean wasAccessedSinceLastBackup() {
        return super.thisAccessedTime > _lastBackupTime;
    }

    /**
     * Stores the current value of {@link #getThisAccessedTimeInternal()} in a private,
     * transient field. You can check with {@link #wasAccessedSinceLastBackupCheck()}
     * if the current {@link #getThisAccessedTimeInternal()} value is different
     * from the previously stored value to see if the session was accessed in
     * the meantime.
     */
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
    boolean wasAccessedSinceLastBackupCheck() {
        return _thisAccessedTimeFromLastBackupCheck != super.thisAccessedTime;
    }

    /**
     * Determines, if attributes were accessed via {@link #getAttribute(String)},
     * {@link #setAttribute(String, Object)} or {@link #setAttribute(String, Object, boolean)}
     * since the last request.
     *
     * @return <code>true</code> if attributes were accessed.
     */
    boolean attributesAccessedSinceLastBackup() {
        return _attributesAccessed;
    }

    /**
     * Determines, if the sessions expiration is just being updated in memcached.
     *
     * @return the expirationUpdateRunning
     */
    boolean isExpirationUpdateRunning() {
        return _expirationUpdateRunning;
    }

    /**
     * Store, if the sessions expiration is just being updated in memcached.
     *
     * @param expirationUpdateRunning the expirationUpdateRunning to set
     */
    void setExpirationUpdateRunning( final boolean expirationUpdateRunning ) {
        _expirationUpdateRunning = expirationUpdateRunning;
    }

    /**
     * Determines, if the sessions is just being backuped.
     *
     * @return the backupRunning
     */
    boolean isBackupRunning() {
        return _backupRunning;
    }

    /**
     * Store, if the sessions is just being backuped.
     *
     * @param backupRunning the backupRunning to set
     */
    void setBackupRunning( final boolean backupRunning ) {
        _backupRunning = backupRunning;
    }

    /**
     * Set a new id for this session.<br/>
     * Before setting the new id, it removes itself from the associated
     * manager. After the new id is set, this session adds itself to the
     * session manager.
     *
     * @param id
     *            the new session id
     */
    protected void setIdForRelocate( final String id ) {

        if ( this.id == null ) {
            throw new IllegalStateException( "There's no session id set." );
        }
        if ( this.manager == null ) {
            throw new IllegalStateException( "There's no manager set." );
        }

        /*
         * and mark it as a node-failure-session, so that remove(session) does
         * not try to remove it from memcached... (the session is removed and
         * added when the session id is changed)
         */
        setNote( MemcachedBackupSessionManager.NODE_FAILURE, Boolean.TRUE );
        manager.remove( this );
        removeNote( MemcachedBackupSessionManager.NODE_FAILURE );
        this.id = id;
        manager.add( this );

    }

    /**
     * Performs some initialization of this session that is required after
     * deserialization. This must be invoked by custom serialization strategies
     * that do not rely on the {@link StandardSession} serialization.
     */
    public void doAfterDeserialization() {
        if ( listeners == null ) {
            listeners = new ArrayList<SessionListener>();
        }
        if ( notes == null ) {
            notes = new Hashtable<String, Object>();
        }
    }

    /**
     * The hash code of the serialized byte[] of this sessions attributes that is
     * used to determine, if the session was modified.
     * @return the hashCode
     */
    int getDataHashCode() {
        return _dataHashCode;
    }

    /**
     * Set the hash code of the serialized session attributes.
     *
     * @param attributesDataHashCode the hashCode of the serialized byte[].
     */
    void setDataHashCode( final int attributesDataHashCode ) {
        _dataHashCode = attributesDataHashCode;
    }

    void setCreationTimeInternal( final long creationTime ) {
        super.creationTime = creationTime;
    }

    boolean isNewInternal() {
        return super.isNew;
    }

    void setIsNewInternal( final boolean isNew ) {
        super.isNew = isNew;
    }

    @Override
    public boolean isValidInternal() {
        return super.isValidInternal();
    }

    void setIsValidInternal( final boolean isValid ) {
        super.isValid = isValid;
    }

    void setThisAccessedTimeInternal( final long thisAccessedTime ) {
        super.thisAccessedTime = thisAccessedTime;
    }

    void setLastAccessedTimeInternal( final long lastAccessedTime ) {
        super.lastAccessedTime = lastAccessedTime;
    }

    void setIdInternal( final String id ) {
        super.id = id;
    }

    public boolean isExpiring() {
        return super.expiring;
    }

    public Map<String, Object> getAttributesInternal() {
        return filterAttributes(super.attributes);
    }

    void setAttributesInternal( final Map<String, Object> attributes ) {
        super.attributes = attributes;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void removeAttributeInternal( final String name, final boolean notify ) {
        super.removeAttributeInternal( name, notify );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected boolean exclude( final String name ) {
        return super.exclude( name );
    }

    /**
     * Determines, if either the {@link #getAuthType()} or {@link #getPrincipal()}
     * properties have changed.
     * @return <code>true</code> if authentication details have changed.
     */
    boolean authenticationChanged() {
        return _authenticationChanged;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setAuthType( final String authType ) {
        if ( !equals( authType, super.authType ) ) {
            _authenticationChanged = true;
        }
        super.setAuthType( authType );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setPrincipal( final Principal principal ) {
        if ( !equals( principal, super.principal ) ) {
            _authenticationChanged = true;
        }
        super.setPrincipal( principal );
    }

    private static boolean equals( final Object one, final Object another ) {
        return one == null && another == null || one != null && one.equals( another );
    }

    /**
     * Is invoked after this session has been successfully stored with
     * {@link BackupResultStatus#SUCCESS}.
     */
    public void backupFinished() {
        _authenticationChanged = false;
        _attributesAccessed = false;
        _sessionIdChanged = false;
    }

    /**
     * Returns the value previously set by {@link #setSessionIdChanged(boolean)}.
     */
    public boolean isSessionIdChanged() {
        return _sessionIdChanged;
    }

    /**
     * Store that the session id was changed externally.
     * @param sessionIdChanged
     */
    public void setSessionIdChanged( final boolean sessionIdChanged ) {
        _sessionIdChanged = sessionIdChanged;
    }

    /**
     * Is invoked when session backup failed for this session (result was {@link BackupResultStatus#SUCCESS}).
     */
    public void backupFailed() {
        _lastBackupTime = _previousLastBackupTime;
    }

    /**
     * Sets the current operation mode of msm, which is important for determining
     * the {@link #getMemcachedExpirationTimeToSet()}.
     */
    public void setSticky( final boolean sticky ) {
        _sticky = sticky;
    }

    /**
     * Returns if there was a lock created in memcached.
     */
    public LockStatus getLockStatus() {
        return _lockStatus;
    }

    /**
     * Stores if there's a lock created in memcached.
     */
    public void setLockStatus( final LockStatus locked ) {
        _lockStatus = locked;
    }

    /**
     * Returns if there was a lock created in memcached.
     */
    public synchronized boolean isLocked() {
        return _lockStatus == LockStatus.LOCKED;
    }

    /**
     * Resets the lock status.
     */
    public void releaseLock() {
        _lockStatus = null;
    }

}

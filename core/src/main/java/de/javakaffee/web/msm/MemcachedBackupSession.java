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

import org.apache.catalina.Manager;
import org.apache.catalina.session.StandardSession;

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
     * Stores the time in millis when this session was stored in memcached
     */
    private transient long _lastBackupTimestamp;

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
        _attributesAccessed = true;
        return super.getAttribute( name );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setAttribute( final String name, final Object value ) {
        _attributesAccessed = true;
        super.setAttribute( name, value );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setAttribute( final String name, final Object value, final boolean notify ) {
        _attributesAccessed = true;
        super.setAttribute( name, value, notify );
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
     *
     * @return the time in seconds
     */
    int getMemcachedExpirationTime() {
        final long timeIdleInMillis = System.currentTimeMillis() - _lastBackupTimestamp;
        /* rounding is just for tests, as they are using actually seconds for testing.
         * with a default setup 1 second difference wouldn't matter...
         */
        final int timeIdle = Math.round( (float)timeIdleInMillis / 1000L );
        final int expirationTime = _lastMemcachedExpirationTime - timeIdle;
        return expirationTime;
    }

    /**
     * Store the time in millis when this session was successfully stored in memcached.
     * @param lastBackupTimestamp the lastBackupTimestamp to set
     */
    void setLastBackupTimestamp( final long lastBackupTimestamp ) {
        _lastBackupTimestamp = lastBackupTimestamp;
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
        return super.thisAccessedTime > _lastBackupTimestamp;
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
            listeners = new ArrayList<Object>();
        }
        if ( notes == null ) {
            notes = new Hashtable<Object, Object>();
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

    long getCreationTimeInternal() {
        return super.creationTime;
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

    protected boolean isValidInternal() {
        return super.isValid;
    }

    void setIsValidInternal( final boolean isValid ) {
        super.isValid = isValid;
    }

    /**
     * The timestamp (System.currentTimeMillis) of the last {@link #access()} invocation,
     * this is the timestamp when the application requested the session.
     *
     * @return the timestamp of the last {@link #access()} invocation.
     */
    long getThisAccessedTimeInternal() {
        return super.thisAccessedTime;
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

    boolean isExpiring() {
        return super.expiring;
    }

    @SuppressWarnings( "unchecked" )
    public Map<String, Object> getAttributesInternal() {
        return super.attributes;
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
    }

}
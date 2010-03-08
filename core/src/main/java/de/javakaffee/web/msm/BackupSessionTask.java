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

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import net.spy.memcached.MemcachedClient;

import org.apache.catalina.Manager;
import org.apache.catalina.Session;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

import de.javakaffee.web.msm.SessionTrackerValve.SessionBackupService.BackupResultStatus;

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
public class BackupSessionTask {

    private static final Log _log = LogFactory.getLog( BackupSessionTask.class );

    private final SessionIdFormat _sessionIdFormat = new SessionIdFormat();

    private final MemcachedBackupSession _session;
    private final TranscoderService _transcoderService;
    private final boolean _sessionBackupAsync;
    private final int _sessionBackupTimeout;
    private final MemcachedClient _memcached;
    private final NodeIdService _nodeIdService;

    /* the original session id is stored so that we can set this if no
     * memcached node is left for taking over
     */
    private String _origSessionId;

    /* The new session id for a session that needs to be relocated
     */
    private String _relocateSessionIdForBackup;

    /**
     * @param session
     * @param sessionBackupAsync
     * @param sessionBackupTimeout
     * @param memcached
     * @param nodeAvailabilityCache
     * @param nodeIds
     * @param failoverNodeIds
     */
    public BackupSessionTask( final MemcachedBackupSession session, final TranscoderService transcoderService, final boolean sessionBackupAsync, final int sessionBackupTimeout,
            final MemcachedClient memcached, final NodeIdService nodeIdService ) {
        _session = session;
        _transcoderService = transcoderService;
        _sessionBackupAsync = sessionBackupAsync;
        _sessionBackupTimeout = sessionBackupTimeout;
        _memcached = memcached;
        _nodeIdService = nodeIdService;
    }

    /**
     * Update the expiration for the session associated with this {@link BackupSessionTask}
     * in memcached, so that the session will expire in
     * <em>session.maxInactiveInterval - timeIdle</em>
     * seconds in memcached (whereas timeIdle is calculated as
     * <em>System.currentTimeMillis - session.thisAccessedTime</em>).
     * <p>
     * <strong>IMPLEMENTATION NOTE</strong>: right now this performs a new backup of the session
     * in memcached. Once the touch command is available in memcached
     * (see <a href="http://code.google.com/p/memcached/issues/detail?id=110">issue #110</a> in memcached),
     * we can consider to use this.
     * </p>
     *
     * @see Session#getMaxInactiveInterval()
     * @see MemcachedBackupSession#getThisAccessedTimeInternal()
     */
    public void updateExpiration() {
        if ( _log.isDebugEnabled() ) {
            _log.debug( "Updating expiration time for session " + _session.getId() );
        }

        if ( !hasMemcachedIdSet() ) {
            return;
        }

        _session.setExpirationUpdateRunning( true );
        try {
            final Map<String, Object> attributes = _session.getAttributesInternal();
            final byte[] attributesData = _transcoderService.serializeAttributes( _session, attributes );
            final byte[] data = _transcoderService.serialize( _session, attributesData );
            doBackupSession( data, attributesData );
        } finally {
            _session.setExpirationUpdateRunning( false );
        }
    }

    /**
     * Store the provided session in memcached if the session was modified
     * or if the session needs to be relocated.
     *
     * @param session
     *            the session to save
     * @return the {@link SessionTrackerValve.SessionBackupService.BackupResultStatus}
     */
    public BackupResultStatus backupSession() {
        if ( _log.isDebugEnabled() ) {
            _log.debug( "Starting for session id " + _session.getId() );
        }

        if ( !hasMemcachedIdSet() ) {
            return BackupResultStatus.FAILURE;
        }

        _session.setBackupRunning( true );
        try {

            /* Check if the session was accessed at all since the last backup/check.
             * If this is not the case, we even don't have to check if attributes
             * have changed (and can skip serialization and hash calucation)
             */
            if ( !_session.wasAccessedSinceLastBackupCheck()
                    && !sessionCookieWasRelocated( _session )
                    && !sessionWouldBeRelocated() ) {
                _log.debug( "Session was not accessed since last backup/check, therefore we can skip this" );
                return BackupResultStatus.SKIPPED;
            }

            final Map<String, Object> attributes = _session.getAttributesInternal();

            final byte[] attributesData = _transcoderService.serializeAttributes( _session, attributes );
            final int hashCode = Arrays.hashCode( attributesData );
            final BackupResultStatus result;
            if ( _session.getDataHashCode() != hashCode
                    || sessionCookieWasRelocated()
                    || sessionWouldBeRelocated()
                    || _session.authenticationChanged() ) {
                final byte[] data = _transcoderService.serialize( _session, attributesData );

                final BackupResult backupResult = doBackupSession( data, attributesData );
                if ( backupResult.isSuccess() || backupResult.isRelocated() ) {
                    /* we can use the already calculated hashcode if we have still the same
                     * attributes data, which is the case for the most common case SUCCESS
                     */
                    final int newHashCode = backupResult.getAttributesData() == attributesData
                        ? hashCode
                        : Arrays.hashCode( backupResult.getAttributesData() );
                    _session.setDataHashCode( newHashCode );
                }

                result = backupResult.getStatus();
            } else {
                result = BackupResultStatus.SKIPPED;
            }

            if ( result != BackupResultStatus.FAILURE ) {

                /* Store the current value of {@link #getThisAccessedTimeInternal()} in a private,
                 * transient field so that we can check above (before computing the hash of the
                 * session attributes) if the session was accessed since this backup/check.
                 */
                _session.storeThisAccessedTimeFromLastBackupCheck();

                /* Tell the session, that it has been stored, so that e.g. the
                 * authenticationChanged property can be reset.
                 */
                if ( result == BackupResultStatus.SUCCESS
                        || result == BackupResultStatus.RELOCATED ) {
                    _session.backupFinished();
                }

            }

            if ( _log.isDebugEnabled() ) {
                _log.debug( "Finished for session id " + _session.getId() +
                        ", returning status " + result );
            }

            return result;

        } finally {
            _session.setBackupRunning( false );
        }

    }

    private boolean hasMemcachedIdSet() {
        return _sessionIdFormat.isValid( _session.getId() );
    }

    private boolean sessionCookieWasRelocated( final MemcachedBackupSession backupSession ) {
        return backupSession.getBackupTask() != null
        && backupSession.getBackupTask().sessionCookieWasRelocated();
    }

    /**
     * Store the provided session in memcached.
     * @param data the serialized session data (session fields and session attributes).
     * @param attributesData just the serialized session attributes.
     *
     * @return the {@link SessionTrackerValve.SessionBackupService.BackupResultStatus}
     */
    private BackupResult doBackupSession( final byte[] data, final byte[] attributesData ) {
        if ( _log.isDebugEnabled() ) {
            _log.debug( "Trying to store session in memcached: " + _session.getId() );
        }

        try {

            /* the new session id might have been set by #sessionNeedsRelocate() which
             * was originally asked by the valve (before the response is committed).
             */
            if ( sessionCookieWasRelocated() ) {
                if ( _relocateSessionIdForBackup.equals( _session.getId() ) ) {
                    _log.warn( "Invalid state: the session has already set the new relocate session id." +
                    		" It must be checked how this can be possible and fixed." );
                }
                _log.debug( "Found relocate session id, setting new id on session..." );
                _session.setIdForRelocate( _relocateSessionIdForBackup );
                _relocateSessionIdForBackup = null;
            }

            storeSessionInMemcached( data );

            return new BackupResult( BackupResultStatus.SUCCESS, attributesData );
        } catch ( final NodeFailureException e ) {
            if ( _log.isInfoEnabled() ) {
                _log.info( "Could not store session " + _session.getId() +
                        " in memcached due to unavailable node " + e.getNodeId() );
            }

            /*
             * get the next memcached node to try
             */
            final String nodeId = _sessionIdFormat.extractMemcachedId( _session.getId() );
            final String targetNodeId = _nodeIdService.getAvailableNodeId( nodeId );

            if ( targetNodeId == null ) {

                if ( _log.isInfoEnabled() ) {
                    _log.info( "The node " + nodeId
                            + " is not available and there's no node for relocation left, omitting session backup." );
                }

                noFailoverNodeLeft();

                return new BackupResult( BackupResultStatus.FAILURE, null );

            } else {

                final BackupResult backupResult = failover( targetNodeId );
                final BackupResultStatus translatedStatus = handleAndTranslateFailoverBackupResult( backupResult.getStatus() );

                return new BackupResult( translatedStatus, backupResult.getAttributesData() );
            }
        }
    }

    private BackupResultStatus handleAndTranslateFailoverBackupResult( final BackupResultStatus backupResult ) {
        switch ( backupResult ) {
            case SUCCESS:

                //_relocatedSessions.put( session.getNote( ORIG_SESSION_ID ).toString(), session.getId() );

                /*
                 * cleanup
                 */
                _origSessionId = null;

                /*
                 * and tell our client to do his part as well
                 */
                return BackupResultStatus.RELOCATED;
            default:
                /*
                 * just pass it up
                 */
                return backupResult;

        }
    }

    /**
     * Returns the new session id if the provided session will be relocated
     * with the next {@link #backupSession(Session)}.
     * This is used to determine during the (directly before) response.commit,
     * if the session will be relocated so that a new session cookie can be
     * added to the response headers.
     *
     * @param session
     *            the session to check, never null.
     * @return the new session id, if this session has to be relocated.
     */
    public String determineSessionIdForBackup() {
        final String nodeId = _sessionIdFormat.extractMemcachedId( _session.getId() );
        if ( nodeId != null && !_nodeIdService.isNodeAvailable( nodeId ) ) {
            final String nextNodeId = _nodeIdService.getAvailableNodeId( nodeId );
            if ( nextNodeId != null ) {
                final String newSessionId = _sessionIdFormat.createNewSessionId( _session.getId(), nextNodeId );
                _relocateSessionIdForBackup = newSessionId;
                return newSessionId;
            } else {
                _log.warn( "The node " + nodeId + " is not available and there's no node for relocation left." );
            }
        }
        return null;
    }

    /**
     * Determines, if the session would be relocated by {@link #doBackupSession(byte[], byte[])}.
     * This is required, if {@link #determineSessionIdForBackup()} was not yet invoked, so
     * {@link #doBackupSession(byte[], byte[])} is expected to return {@link BackupResultStatus#RELOCATED}.
     *
     * @return <code>true</code> if this session will be relocated.
     */
    private boolean sessionWouldBeRelocated() {
        final String nodeId = _sessionIdFormat.extractMemcachedId( _session.getId() );
        return nodeId != null && !_nodeIdService.isNodeAvailable( nodeId );
    }

    /**
     * Specifies if previously {@link #determineSessionIdForBackup()} returned a new session id
     * to be sent to the client.
     * @return <code>true</code> if this session needs to be relocated.
     */
    private boolean sessionCookieWasRelocated() {
        return _relocateSessionIdForBackup != null;
    }

    private BackupResult failover( final String targetNodeId ) {
        /*
         * we must store the original session id so that we can set this if no
         * memcached node is left for taking over
         */
        if ( _origSessionId == null ) {
            _origSessionId = _session.getId();
        }

        /*
         * relocate session to our memcached node...
         */
        _session.setIdForRelocate( _sessionIdFormat.createNewSessionId( _session.getId(), targetNodeId ) );

        /* the serialized session data needs to be recreated as it changed.
         */
        final byte[] attributesData = _transcoderService.serializeAttributes( _session, _session.getAttributesInternal() );
        final byte[] data = _transcoderService.serialize( _session, attributesData );

        /*
         * invoke backup again, until we have a success or a failure
         */
        final BackupResult backupResult = doBackupSession( data, attributesData );

        return backupResult;
    }

    private void noFailoverNodeLeft() {

        /*
         * we must set the original session id in case we changed it already
         */
        if ( _origSessionId != null && !_origSessionId.equals( _session.getId() ) ) {
            _session.setIdForRelocate( _origSessionId );
        }

        /*
         * cleanup
         */
        _origSessionId = null;
    }

    private void storeSessionInMemcached( final byte[] data) throws NodeFailureException {

        /* calculate the expiration time (instead of using just maxInactiveInterval), as
         * this is relevant for the update of the expiration time: if we would just use
         * maxInactiveInterval, the session would exist longer in memcached than it would
         * be valid in tomcat
         */
        final int expirationTime = _session.getMemcachedExpirationTimeToSet();
        final Future<Boolean> future = _memcached.set( _session.getId(), expirationTime, data );
        if ( !_sessionBackupAsync ) {
            try {
                future.get( _sessionBackupTimeout, TimeUnit.MILLISECONDS );
                _session.setLastMemcachedExpirationTime( expirationTime );
                _session.setLastBackupTimestamp( System.currentTimeMillis() );
            } catch ( final Exception e ) {
                if ( _log.isInfoEnabled() ) {
                    _log.info( "Could not store session " + _session.getId() + " in memcached.", e );
                }
                final String nodeId = _sessionIdFormat.extractMemcachedId( _session.getId() );
                _nodeIdService.setNodeAvailable( nodeId, false );
                throw new NodeFailureException( "Could not store session in memcached.", nodeId );
            }
        }
        else {
            /* in async mode, we asume the session was stored successfully
             */
            _session.setLastMemcachedExpirationTime( expirationTime );
            _session.setLastBackupTimestamp( System.currentTimeMillis() );
        }
    }

    static final class BackupResult {
        private final BackupResultStatus _status;
        private final byte[] _attributesData;
        public BackupResult( final BackupResultStatus status, final byte[] attributesData ) {
            _status = status;
            _attributesData = attributesData;
        }
        /**
         * The status/result of the backup operation.
         * @return the status
         */
        BackupResultStatus getStatus() {
            return _status;
        }
        /**
         * The serialized attributes that were actually stored in memcached with the
         * full serialized session data. This can be <code>null</code>, e.g. if
         * {@link #getStatus()} is {@link BackupResultStatus#FAILURE}.
         *
         * @return the attributesData
         */
        byte[] getAttributesData() {
            return _attributesData;
        }
        /**
         * @return <code>true</code> if the status is {@link BackupResultStatus#SUCCESS},
         * otherwise <code>false</code>.
         */
        public boolean isSuccess() {
            return _status == BackupResultStatus.SUCCESS;
        }
        /**
         * @return <code>true</code> if the status is {@link BackupResultStatus#RELOCATED},
         * otherwise <code>false</code>.
         */
        public boolean isRelocated() {
            return _status == BackupResultStatus.RELOCATED;
        }
    }

}

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
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import net.spy.memcached.MemcachedClient;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

import de.javakaffee.web.msm.SessionTrackerValve.SessionBackupService.BackupResultStatus;

/**
 * Stores the provided session in memcached if the session was modified
 * or if the session needs to be relocated (set <code>force</code> to <code>true</code>).
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class BackupSessionTask implements Callable<BackupResultStatus> {

    private static final Log _log = LogFactory.getLog( BackupSessionTask.class );

    /* the original session id is stored so that we can set this if no
     * memcached node is left for taking over
     */
    private static final String ORIG_SESSION_ID_KEY = "orig.sessionid";

    private final SessionIdFormat _sessionIdFormat = new SessionIdFormat();

    private final MemcachedBackupSession _session;
    private final boolean _force;
    private final TranscoderService _transcoderService;
    private final boolean _sessionBackupAsync;
    private final int _sessionBackupTimeout;
    private final MemcachedClient _memcached;
    private final NodeIdService _nodeIdService;
    private final Statistics _statistics;

    /**
     * @param session
     *            the session to save
     * @param force
     *            specifies, if the session needs to be saved by all means, e.g.
     *            as it has to be relocated to another memcached
     *            node (the session id had been changed before in this case).
     * @param sessionBackupAsync
     * @param sessionBackupTimeout
     * @param memcached
     * @param nodeAvailabilityCache
     * @param nodeIds
     * @param failoverNodeIds
     */
    public BackupSessionTask( final MemcachedBackupSession session,
            final boolean force,
            final TranscoderService transcoderService,
            final boolean sessionBackupAsync,
            final int sessionBackupTimeout,
            final MemcachedClient memcached,
            final NodeIdService nodeIdService,
            final Statistics statistics ) {
        _session = session;
        _force = force;
        _transcoderService = transcoderService;
        _sessionBackupAsync = sessionBackupAsync;
        _sessionBackupTimeout = sessionBackupTimeout;
        _memcached = memcached;
        _nodeIdService = nodeIdService;
        _statistics = statistics;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public BackupResultStatus call() throws Exception {
        if ( _log.isDebugEnabled() ) {
            _log.debug( "Starting for session id " + _session.getId() );
        }

        _session.setBackupRunning( true );
        try {

            final long startBackup = System.currentTimeMillis();

            final Map<String, Object> attributes = _session.getAttributesInternal();

            final byte[] attributesData = serializeAttributes( _session, attributes );
            final int hashCode = Arrays.hashCode( attributesData );
            final BackupResultStatus result;
            if ( _session.getDataHashCode() != hashCode
                    || _force
                    || _session.authenticationChanged() ) {
                final byte[] data = _transcoderService.serialize( _session, attributesData );

                final BackupResult backupResult = doBackupSession( _session, data, attributesData );
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

            switch ( result ) {
                case FAILURE:
                    _statistics.requestWithBackupFailure();
                    break;
                case SKIPPED:
                    _statistics.requestWithoutSessionModification();
                    _session.storeThisAccessedTimeFromLastBackupCheck();
                    break;
                case SUCCESS:
                    _statistics.requestWithBackup();
                    _statistics.getBackupProbe().registerSince( startBackup );
                    _session.storeThisAccessedTimeFromLastBackupCheck();
                    _session.backupFinished();
                    break;
                case RELOCATED:
                    _statistics.requestWithBackupRelocation();
                    _statistics.getBackupRelocationProbe().registerSince( startBackup );
                    _session.storeThisAccessedTimeFromLastBackupCheck();
                    _session.backupFinished();
                    break;
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

    private byte[] serializeAttributes( final MemcachedBackupSession session, final Map<String, Object> attributes ) {
        final long start = System.currentTimeMillis();
        final byte[] attributesData = _transcoderService.serializeAttributes( session, attributes );
        _statistics.getAttributesSerializationProbe().registerSince( start );
        return attributesData;
    }

    /**
     * Store the provided session in memcached.
     * @param session the session to backup
     * @param data the serialized session data (session fields and session attributes).
     * @param attributesData just the serialized session attributes.
     *
     * @return the {@link SessionTrackerValve.SessionBackupService.BackupResultStatus}
     */
    BackupResult doBackupSession( final MemcachedBackupSession session, final byte[] data, final byte[] attributesData ) {
        if ( _log.isDebugEnabled() ) {
            _log.debug( "Trying to store session in memcached: " + session.getId() );
        }

        try {

            storeSessionInMemcached( session, data );

            return new BackupResult( BackupResultStatus.SUCCESS, attributesData );
        } catch ( final NodeFailureException e ) {
            if ( _log.isInfoEnabled() ) {
                _log.info( "Could not store session " + session.getId() +
                        " in memcached due to unavailable node " + e.getNodeId() );
            }

            /*
             * get the next memcached node to try
             */
            final String nodeId = _sessionIdFormat.extractMemcachedId( session.getId() );
            final String targetNodeId = _nodeIdService.getAvailableNodeId( nodeId );

            if ( targetNodeId == null ) {

                if ( _log.isInfoEnabled() ) {
                    _log.info( "The node " + nodeId
                            + " is not available and there's no node for relocation left, omitting session backup." );
                }

                noFailoverNodeLeft( session );

                return new BackupResult( BackupResultStatus.FAILURE, null );

            } else {

                final BackupResult backupResult = failover( session, targetNodeId );
                final BackupResultStatus translatedStatus = handleAndTranslateFailoverBackupResult( session, backupResult.getStatus() );

                return new BackupResult( translatedStatus, backupResult.getAttributesData() );
            }
        }
    }

    private BackupResultStatus handleAndTranslateFailoverBackupResult( final MemcachedBackupSession session,
            final BackupResultStatus backupResult ) {
        switch ( backupResult ) {
            case SUCCESS:

                //_relocatedSessions.put( session.getNote( ORIG_SESSION_ID ).toString(), session.getId() );

                /*
                 * cleanup
                 */
                session.removeNote( ORIG_SESSION_ID_KEY );

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

    private BackupResult failover( final MemcachedBackupSession session, final String targetNodeId ) {
        /*
         * we must store the original session id so that we can set this if no
         * memcached node is left for taking over
         */
        if ( session.getNote( ORIG_SESSION_ID_KEY ) == null ) {
            session.setNote( ORIG_SESSION_ID_KEY, session.getId() );
        }

        /*
         * relocate session to our memcached node...
         */
        session.setIdForRelocate( _sessionIdFormat.createNewSessionId( session.getId(), targetNodeId ) );

        /* the serialized session data needs to be recreated as it changed.
         */
        final byte[] attributesData = serializeAttributes( session, session.getAttributesInternal() );
        final byte[] data = _transcoderService.serialize( session, attributesData );

        /*
         * invoke backup again, until we have a success or a failure
         */
        final BackupResult backupResult = doBackupSession( session, data, attributesData );

        return backupResult;
    }

    private void noFailoverNodeLeft( final MemcachedBackupSession session ) {

        /*
         * we must set the original session id in case we changed it already
         */
        final String origSessionId = (String) session.getNote( ORIG_SESSION_ID_KEY );
        if ( origSessionId != null && !origSessionId.equals( session.getId() ) ) {
            session.setIdForRelocate( origSessionId );
        }

        /*
         * cleanup
         */
        session.removeNote( ORIG_SESSION_ID_KEY );
    }

    private void storeSessionInMemcached( final MemcachedBackupSession session, final byte[] data) throws NodeFailureException {

        /* calculate the expiration time (instead of using just maxInactiveInterval), as
         * this is relevant for the update of the expiration time: if we would just use
         * maxInactiveInterval, the session would exist longer in memcached than it would
         * be valid in tomcat
         */
        final int expirationTime = session.getMemcachedExpirationTimeToSet();
        final long start = System.currentTimeMillis();
        try {
            final Future<Boolean> future = _memcached.set( session.getId(), expirationTime, data );
            if ( !_sessionBackupAsync ) {
                try {
                    future.get( _sessionBackupTimeout, TimeUnit.MILLISECONDS );
                    session.setLastMemcachedExpirationTime( expirationTime );
                    session.setLastBackupTimestamp( System.currentTimeMillis() );
                } catch ( final Exception e ) {
                    if ( _log.isInfoEnabled() ) {
                        _log.info( "Could not store session " + session.getId() + " in memcached." );
                    }
                    final String nodeId = _sessionIdFormat.extractMemcachedId( session.getId() );
                    _nodeIdService.setNodeAvailable( nodeId, false );
                    throw new NodeFailureException( "Could not store session in memcached.", nodeId );
                }
            }
            else {
                /* in async mode, we asume the session was stored successfully
                 */
                session.setLastMemcachedExpirationTime( expirationTime );
                session.setLastBackupTimestamp( System.currentTimeMillis() );
            }
        } finally {
            _statistics.getMemcachedUpdateProbe().registerSince( start );
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

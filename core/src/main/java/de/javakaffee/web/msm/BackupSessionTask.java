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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import net.spy.memcached.MemcachedClient;

import org.apache.catalina.Manager;
import org.apache.catalina.Session;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

import de.javakaffee.web.msm.MemcachedBackupSessionManager.MemcachedBackupSession;
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
    private final NodeAvailabilityCache<String> _nodeAvailabilityCache;
    private final List<String> _nodeIds;
    private final List<String> _failoverNodeIds;

    /* the original session id is stored so that we can set this if no
     * memcached node is left for taking over
     */
    private String _origSessionId;

    /* The new session id for a session that needs to be relocated
     */
    private String _relocateSessionIdForBackup;
    private Set<String> _testedNodes;

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
            final MemcachedClient memcached, final NodeAvailabilityCache<String> nodeAvailabilityCache, final List<String> nodeIds,
            final List<String> failoverNodeIds ) {
        _session = session;
        _transcoderService = transcoderService;
        _sessionBackupAsync = sessionBackupAsync;
        _sessionBackupTimeout = sessionBackupTimeout;
        _memcached = memcached;
        _nodeAvailabilityCache = nodeAvailabilityCache;
        _nodeIds = nodeIds;
        _failoverNodeIds = failoverNodeIds;
    }

    /**
     * A special constructor used for testing of {@link #getNextNodeId(String, Set)}.
     *
     * @param nodeIds
     * @param failoverNodeIds
     */
    BackupSessionTask( final List<String> nodeIds,
            final List<String> failoverNodeIds ) {
        this( null, null, false, -1, null, null, nodeIds, failoverNodeIds );
    }

    /**
     * Store the provided session in memcached if the session was modified
     * or if the session needs to be relocated.
     *
     * @param session
     *            the session to save
     * @return the {@link SessionTrackerValve.SessionBackupService.BackupResultStatus}
     */
    public BackupResultStatus backupSession( final Session session ) {
        if ( _log.isDebugEnabled() ) {
            _log.debug( "Starting for session id " + session.getId() );
        }

        final MemcachedBackupSession backupSession = (MemcachedBackupSession) session;

        /* Check if the session was accessed at all since the last backup/check.
         * If this is not the case, we even don't have to check if attributes
         * have changed (and can skip serialization and hash calucation)
         */
        if ( !backupSession.wasAccessedSinceLastBackupCheck()
                && !sessionCookieWasRelocated( backupSession )
                && !sessionWouldBeRelocated() ) {
            _log.debug( "Session was not accessed since last backup/check, therefore we can skip this" );
            return BackupResultStatus.SKIPPED;
        }

        final Map<String, Object> attributes = backupSession.getAttributesInternal();

        final byte[] attributesData = _transcoderService.serializeAttributes( backupSession, attributes );
        final int hashCode = Arrays.hashCode( attributesData );
        final BackupResultStatus result;
        if ( backupSession.getDataHashCode() != hashCode
                || sessionCookieWasRelocated()
                || sessionWouldBeRelocated() ) {
            final byte[] data = _transcoderService.serialize( backupSession, attributesData );

            final BackupResult backupResult = doBackupSession( data, attributesData );
            if ( backupResult.isSuccess() || backupResult.isRelocated() ) {
                /* we can use the already calculated hashcode if we have still the same
                 * attributes data, which is the case for the most common case SUCCESS
                 */
                final int newHashCode = backupResult.getAttributesData() == attributesData
                    ? hashCode
                    : Arrays.hashCode( backupResult.getAttributesData() );
                backupSession.setDataHashCode( newHashCode );
            }

            result = backupResult.getStatus();
        } else {
            result = BackupResultStatus.SKIPPED;
        }

        /* Store the current value of {@link #getThisAccessedTimeInternal()} in a private,
         * transient field so that we can check above (before computing the hash of the
         * session attributes) if the session was accessed since this backup/check.
         */
        if ( result != BackupResultStatus.FAILURE ) {
            backupSession.storeThisAccessedTimeFromLastBackupCheck();
        }

        if ( _log.isDebugEnabled() ) {
            _log.debug( "Finished for session id " + session.getId() +
                    ", returning status " + result );
        }

        return result;

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
    public BackupResult doBackupSession( final byte[] data, final byte[] attributesData ) {
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
            final String targetNodeId = getNextNodeId( nodeId, _testedNodes );

            if ( targetNodeId == null ) {

                if ( _log.isInfoEnabled() ) {
                    _log.info( "The node " + nodeId
                            + " is not available and there's no node for relocation left, omitting session backup." );
                }

                noFailoverNodeLeft();

                return new BackupResult( BackupResultStatus.FAILURE, null );

            } else {

                if ( _testedNodes == null ) {
                    _testedNodes = new HashSet<String>();
                }

                final BackupResult backupResult = failover( _testedNodes, targetNodeId );
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
                _testedNodes = null;

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
        if ( nodeId != null && !_nodeAvailabilityCache.isNodeAvailable( nodeId ) ) {
            final String nextNodeId = getNextNodeId( nodeId, _nodeAvailabilityCache.getUnavailableNodes() );
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
        return nodeId != null && !_nodeAvailabilityCache.isNodeAvailable( nodeId );
    }

    /**
     * Specifies if previously {@link #determineSessionIdForBackup()} returned a new session id
     * to be sent to the client.
     * @return <code>true</code> if this session needs to be relocated.
     */
    private boolean sessionCookieWasRelocated() {
        return _relocateSessionIdForBackup != null;
    }

    private BackupResult failover( final Set<String> testedNodes, final String targetNodeId ) {

        final String nodeId = _sessionIdFormat.extractMemcachedId( _session.getId() );

        testedNodes.add( nodeId );

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
        _testedNodes = null;
    }

    /**
     * Get the next memcached node id for session backup. The active node ids
     * are preferred, if no active node id is left to try, a failover node id is
     * picked. If no failover node id is left, this method returns just null.
     *
     * @param nodeId
     *            the current node id
     * @param excludedNodeIds
     *            the node ids that were already tested and shall not be used
     *            again. Can be null.
     * @return the next node id or null, if no node is left.
     */
    protected String getNextNodeId( final String nodeId, final Set<String> excludedNodeIds ) {

        String result = null;

        /*
         * first check regular nodes
         */
        result = getNextNodeId( nodeId, _nodeIds, excludedNodeIds );

        /*
         * we got no node from the first nodes list, so we must check the
         * alternative node list
         */
        if ( result == null && _failoverNodeIds != null && !_failoverNodeIds.isEmpty() ) {
            result = getNextNodeId( nodeId, _failoverNodeIds, excludedNodeIds );
        }

        return result;
    }

    /**
     * Determines the next available node id from the provided node ids. The
     * returned node id will be different from the provided nodeId and will not
     * be contained in the excludedNodeIds.
     *
     * @param nodeId
     *            the original id
     * @param nodeIds
     *            the node ids to choose from
     * @param excludedNodeIds
     *            the set of invalid node ids
     * @return an available node or null
     */
    protected static String getNextNodeId( final String nodeId, final List<String> nodeIds, final Set<String> excludedNodeIds ) {

        String result = null;

        final int origIdx = nodeIds.indexOf( nodeId );
        final int nodeIdsSize = nodeIds.size();

        int idx = origIdx;
        while ( result == null && !loopFinished( origIdx, idx, nodeIdsSize ) ) {

            final int checkIdx = roll( idx, nodeIdsSize );
            final String checkNodeId = nodeIds.get( checkIdx );

            if ( excludedNodeIds != null && excludedNodeIds.contains( checkNodeId ) ) {
                idx = checkIdx;
            } else {
                result = checkNodeId;
            }

        }

        return result;
    }

    private static boolean loopFinished( final int origIdx, final int idx, final int nodeIdsSize ) {
        return origIdx == -1
            ? idx + 1 == nodeIdsSize
            : roll( idx, nodeIdsSize ) == origIdx;
    }

    protected static int roll( final int idx, final int size ) {
        return idx + 1 >= size
            ? 0
            : idx + 1;
    }

    private void storeSessionInMemcached( final byte[] data) throws NodeFailureException {
        final Future<Boolean> future = _memcached.set( _session.getId(), _session.getMaxInactiveInterval(), data );
        if ( !_sessionBackupAsync ) {
            try {
                future.get( _sessionBackupTimeout, TimeUnit.MILLISECONDS );
            } catch ( final Exception e ) {
                if ( _log.isInfoEnabled() ) {
                    _log.info( "Could not store session " + _session.getId() + " in memcached.", e );
                }
                final String nodeId = _sessionIdFormat.extractMemcachedId( _session.getId() );
                _nodeAvailabilityCache.setNodeAvailable( nodeId, false );
                throw new NodeFailureException( "Could not store session in memcached.", nodeId );
            }
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

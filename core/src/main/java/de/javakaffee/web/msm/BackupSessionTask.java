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

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import net.spy.memcached.MemcachedClient;

import org.apache.catalina.Manager;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

import de.javakaffee.web.msm.MemcachedBackupSessionManager.MemcachedBackupSession;
import de.javakaffee.web.msm.SessionTrackerValve.SessionBackupService.BackupResult;

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

    protected static final String NODE_FAILURE = "node.failure";

    private final SessionIdFormat _sessionIdFormat = new SessionIdFormat();

    private final MemcachedBackupSession _session;
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
    private String _relocateSessionId;
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
    public BackupSessionTask( final MemcachedBackupSession session, final boolean sessionBackupAsync, final int sessionBackupTimeout,
            final MemcachedClient memcached, final NodeAvailabilityCache<String> nodeAvailabilityCache, final List<String> nodeIds,
            final List<String> failoverNodeIds ) {
        _session = session;
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
        this( null, false, -1, null, null, nodeIds, failoverNodeIds );
    }

    /**
     * Store the provided session in memcached.
     *
     * @return the {@link SessionTrackerValve.SessionBackupService.BackupResult}
     */
    public BackupResult backupSession( ) {
        if ( _log.isInfoEnabled() ) {
            _log.debug( "Trying to store session in memcached: " + _session.getId() );
        }

        try {

            /* the new session id might have been set by #sessionNeedsRelocate() which
             * was originally asked by the valve (before the response is committed).
             */
            if ( _relocateSessionId != null ) {
                _log.debug( "Found relocate session id, setting new id on session..." );
                _session.setNote( NODE_FAILURE, Boolean.TRUE );
                _session.setIdForRelocate( _relocateSessionId );
                _relocateSessionId = null;
            }

            storeSessionInMemcached();
            return BackupResult.SUCCESS;
        } catch ( final NodeFailureException e ) {
            if ( _log.isInfoEnabled() ) {
                _log.info( "Could not store session in memcached (" + _session.getId() + ")" );
            }

            /*
             * get the next memcached node to try
             */
            final String nodeId = _sessionIdFormat.extractMemcachedId( _session.getId() );
            final String targetNodeId = getNextNodeId( nodeId, _testedNodes );

            if ( targetNodeId == null ) {

                _log.warn( "The node " + nodeId
                        + " is not available and there's no node for relocation left, omitting session backup." );

                noFailoverNodeLeft();

                return BackupResult.FAILURE;

            } else {

                if ( _testedNodes == null ) {
                    _testedNodes = new HashSet<String>();
                }

                final BackupResult backupResult = failover( _testedNodes, targetNodeId );

                return handleAndTranslateBackupResult( backupResult );
            }
        }
    }

    private BackupResult handleAndTranslateBackupResult( final BackupResult backupResult ) {
        switch ( backupResult ) {
            case SUCCESS:

                //_relocatedSessions.put( session.getNote( ORIG_SESSION_ID ).toString(), session.getId() );

                /*
                 * cleanup
                 */
                _origSessionId = null;
                _session.removeNote( NODE_FAILURE );
                _testedNodes = null;

                /*
                 * and tell our client to do his part as well
                 */
                return BackupResult.RELOCATED;
            default:
                /*
                 * just pass it up
                 */
                return backupResult;

        }
    }

    /**
     * Returns the new session id if the provided session has to be relocated.
     *
     * @param session
     *            the session to check, never null.
     * @return the new session id, if this session has to be relocated.
     */
    public String sessionNeedsRelocate() {
        final String nodeId = _sessionIdFormat.extractMemcachedId( _session.getId() );
        if ( nodeId != null && !_nodeAvailabilityCache.isNodeAvailable( nodeId ) ) {
            final String nextNodeId = getNextNodeId( nodeId, _nodeAvailabilityCache.getUnavailableNodes() );
            if ( nextNodeId != null ) {
                final String newSessionId = _sessionIdFormat.createNewSessionId( _session.getId(), nextNodeId );
                _relocateSessionId = newSessionId;
                return newSessionId;
            } else {
                _log.warn( "The node " + nodeId + " is not available and there's no node for relocation left." );
            }
        }
        return null;
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
         *
         * and mark it as a node-failure-session, so that remove(session) does
         * not try to remove it from memcached... (the session is removed and
         * added when the session id is changed)
         */
        _session.setNote( NODE_FAILURE, Boolean.TRUE );
        _session.setIdForRelocate( _sessionIdFormat.createNewSessionId( _session.getId(), targetNodeId ) );

        /*
         * invoke backup again, until we have a success or a failure
         */
        final BackupResult backupResult = backupSession();

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
        _session.removeNote( NODE_FAILURE );
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

    private void storeSessionInMemcached() throws NodeFailureException {
        final Future<Boolean> future = _memcached.set( _session.getId(), _session.getMaxInactiveInterval(), _session );
        if ( !_sessionBackupAsync ) {
            try {
                future.get( _sessionBackupTimeout, TimeUnit.MILLISECONDS );
            } catch ( final Exception e ) {
                if ( _log.isInfoEnabled() ) {
                    _log.info( "Could not store session " + _session.getId() + " in memcached: " + e );
                }
                final String nodeId = _sessionIdFormat.extractMemcachedId( _session.getId() );
                _nodeAvailabilityCache.setNodeAvailable( nodeId, false );
                throw new NodeFailureException( "Could not store session in memcached.", nodeId );
            }
        }
    }

//    // ===========================  for testing  ==============================
//
//    protected void setNodeIds( final List<String> nodeIds ) {
//        _nodeIds = nodeIds;
//    }
//
//    protected void setFailoverNodeIds( final List<String> failoverNodeIds ) {
//        _failoverNodeIds = failoverNodeIds;
//    }

}

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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.Set;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;


/**
 * Provides services related to node ids.
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class NodeIdService {

    private static final Log LOG = LogFactory.getLog( NodeIdService.class );

    private final Random _random = new Random();

    /*
     * Manager.remove(session) may be called with sessionIds that already failed before (probably
     * because the browser makes subsequent requests with the old sessionId -
     * the exact reason needs to be verified). These failed sessionIds should If
     * a session is requested that we don't have locally stored each findSession
     * invocation would trigger a memcached request - this would open the door
     * for DOS attacks...
     *
     * this solution: use a LRUCache with a timeout to store, which session had
     * been requested in the last <n> millis.
     *
     * Updated: the node status cache holds the status of each node for the
     * configured TTL.
     */
    private final NodeAvailabilityCache<String> _nodeAvailabilityCache;
    private final NodeIdList _nodeIds;
    private final List<String> _failoverNodeIds;

    /**
     * Constructs a new {@link NodeIdService}.
     *
     * @param nodeAvailabilityCache
     * @param nodeIds
     * @param failoverNodeIds
     */
    public NodeIdService( final NodeAvailabilityCache<String> nodeAvailabilityCache, final NodeIdList nodeIds, final List<String> failoverNodeIds ) {
        _nodeAvailabilityCache = nodeAvailabilityCache;
        _nodeIds = nodeIds;
        _failoverNodeIds = failoverNodeIds;
    }

    /**
     * A special constructor used for testing of {@link #getRandomNextNodeId(String, Set)}.
     *
     * @param nodeIds
     * @param failoverNodeIds
     */
    NodeIdService( final List<String> nodeIds,
            final List<String> failoverNodeIds ) {
        this( null, new NodeIdList( nodeIds ), failoverNodeIds );
    }

    /**
     * Determines, if the given nodeId is available.
     * @param nodeId the node to check, not <code>null</code>.
     * @return <code>true</code>, if the node is marked as available
     */
    public boolean isNodeAvailable( @Nonnull final String nodeId ) {
        return _nodeAvailabilityCache.isNodeAvailable( nodeId );
    }

    /**
     * Mark the given nodeId as available as specified.
     * @param nodeId the nodeId to update
     * @param available specifies if the node was abailable or not
     */
    public void setNodeAvailable( final String nodeId, final boolean available ) {
        _nodeAvailabilityCache.setNodeAvailable( nodeId, available );
    }

    /**
     * Get an available (randomly selected) memcached node id for session backup.
     * The active node ids are preferred, if no active node id is left to try,
     * a failover node id is picked.
     * If no failover node id is left, this method returns just null.
     *
     * @param nodeId the unavailable nodeId which is used to start checking other nodes.
     * @return a nodeId if any available node was found, otherwise <code>null</code>.
     */
    public String getAvailableNodeId( final String nodeId ) {

        String result = null;

        /*
         * first check regular nodes
         */
        result = getRandomNextNodeId( nodeId, _nodeIds );

        /*
         * we got no node from the first nodes list, so we must check the
         * alternative node list
         */
        if ( result == null && _failoverNodeIds != null && !_failoverNodeIds.isEmpty() ) {
            result = getRandomNextNodeId( nodeId, _failoverNodeIds );
        }

        return result;
    }

    /**
     * Gets the next node id for the given one from the list of all node ids.
     * If there's only a single node known, conceptionally there's no next node
     * and therefore <code>null</code> is returned.
     * @param nodeId the node id for that the next one is determined.
     * @return the next node id or <code>null</code>.
     *
     * @throws IllegalArgumentException thrown if the given nodeId is not part of this list.
     *
     * @see NodeIdList#getNextNodeId(String)
     */
    @CheckForNull
    public String getNextNodeId( @Nonnull final String nodeId ) throws IllegalArgumentException {
        return _nodeIds.getNextNodeId( nodeId );
    }

    /**
     * Determines (randomly) an available node id from the provided node ids. The
     * returned node id will be different from the provided nodeId and will
     * be available according to the local {@link NodeAvailabilityCache}.
     *
     * @param nodeId
     *            the original id
     * @param nodeIds
     *            the node ids to choose from
     * @return an available node or null
     */
    protected String getRandomNextNodeId( final String nodeId, final Collection<String> nodeIds ) {

        /* create a list of nodeIds to check randomly
         */
        final List<String> otherNodeIds = new ArrayList<String>( nodeIds );
        otherNodeIds.remove( nodeId );

        while ( !otherNodeIds.isEmpty() ) {
            final String nodeIdToCheck = otherNodeIds.get( _random.nextInt( otherNodeIds.size() ) );
            if ( isNodeAvailable( nodeIdToCheck ) ) {
                return nodeIdToCheck;
            }
            otherNodeIds.remove( nodeIdToCheck );
        }

        return null;
    }

    /**
     * Get the next random, available node id. If no node is available, <code>null</code>
     * is returned.
     * @return a nodeId or <code>null</code>.
     */
    public String getMemcachedNodeId() {
        final String nodeId = _nodeIds.get( _random.nextInt( _nodeIds.size() ) );
        return isNodeAvailable( nodeId ) ? nodeId : getAvailableNodeId( nodeId );
    }

    /* Just for testing
     */
    List<String> getNodeIds() {
        return new ArrayList<String>( _nodeIds );
    }
    /* Just for testing
     */
    List<String> getFailoverNodeIds() {
        return new ArrayList<String>( _failoverNodeIds );
    }

    /**
     * Returns a new node id if the given one is <code>null</code> or not available.
     * @param nodeId the node id that is checked for availability (if not <code>null</code>).
     * @return a new node id if the given one is <code>null</code> or not available, otherwise <code>null</code>.
     */
    public String getNewNodeIdIfUnavailable( final String nodeId ) {
        final String newNodeId;
        if ( nodeId == null ) {
            newNodeId = getMemcachedNodeId();
        }
        else {
            if ( !isNodeAvailable( nodeId ) ) {
                newNodeId = getAvailableNodeId( nodeId );
                if ( newNodeId == null ) {
                    LOG.warn( "The node " + nodeId + " is not available and there's no node for relocation left." );
                }
            }
            else {
                newNodeId = null;
            }
        }
        return newNodeId;
    }

}
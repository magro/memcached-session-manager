/*
 * Copyright 2011 Martin Grotzke
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
 */
package de.javakaffee.web.msm;

import static de.javakaffee.web.msm.Configurations.NODE_AVAILABILITY_CACHE_TTL_KEY;
import static de.javakaffee.web.msm.Configurations.getSystemProperty;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

import de.javakaffee.web.msm.NodeAvailabilityCache.CacheLoader;


/**
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class MemcachedNodesManager {

	/**
	 * Provides queries to memcached.
	 */
	public static interface MemcachedClientCallback {
		/**
		 * Must query the given key in memcached.
		 */
		@Nullable
		Object get(@Nonnull String key);
	}

	private static final Log LOG = LogFactory.getLog(MemcachedNodesManager.class);

    private static final String NODE_REGEX = "([\\w]+):([^:]+):([\\d]+)";
    private static final Pattern NODE_PATTERN = Pattern.compile( NODE_REGEX );

    private static final String NODES_REGEX = NODE_REGEX + "(?:(?:\\s+|,)" + NODE_REGEX + ")*";
    private static final Pattern NODES_PATTERN = Pattern.compile( NODES_REGEX );

    private static final String SINGLE_NODE_REGEX = "([^:]+):([\\d]+)";
    private static final Pattern SINGLE_NODE_PATTERN = Pattern.compile( SINGLE_NODE_REGEX );

    private static final String COUCHBASE_BUCKET_NODE_REGEX = "http://([^:]+):([\\d]+)/[\\w]+";
    private static final Pattern COUCHBASE_BUCKET_NODE_PATTERN = Pattern.compile( COUCHBASE_BUCKET_NODE_REGEX );

    private static final String COUCHBASE_BUCKET_NODES_REGEX = COUCHBASE_BUCKET_NODE_REGEX + "(?:(?:\\s+|,)" + COUCHBASE_BUCKET_NODE_REGEX + ")*";
    private static final Pattern COUCHBASE_BUCKET_NODES_PATTERN = Pattern.compile( COUCHBASE_BUCKET_NODES_REGEX );

    private static final int NODE_AVAILABILITY_CACHE_TTL = getSystemProperty(NODE_AVAILABILITY_CACHE_TTL_KEY, 1000);

	private final String _memcachedNodes;
    private final NodeIdList _primaryNodeIds;
    private final List<String> _failoverNodeIds;
    private final LinkedHashMap<InetSocketAddress, String> _address2Ids;
    private final boolean _encodeNodeIdInSessionId;
    private final StorageKeyFormat _storageKeyFormat;
    @Nullable
	private NodeIdService _nodeIdService;
	private SessionIdFormat _sessionIdFormat;

    /**
     *
     * @param memcachedNodes the original memcachedNodes configuration string
     * @param primaryNodeIds the list of primary node ids (memcachedNodes without failoverNodes).
     * @param failoverNodeIds the configured failover node ids.
     * @param address2Ids a mapping of inet addresses from the memcachedNodes configuration to their node ids.
     * @param storageKeyFormat the storage key format
     * @param memcachedClientCallback a callback to memcached, can only be null if the memcachedNodes config
     * 		contains a single node without node id.
     */
	public MemcachedNodesManager(final String memcachedNodes, @Nonnull final NodeIdList primaryNodeIds, @Nonnull final List<String> failoverNodeIds,
			@Nonnull final LinkedHashMap<InetSocketAddress, String> address2Ids,
			@Nullable final StorageKeyFormat storageKeyFormat, @Nullable final MemcachedClientCallback memcachedClientCallback) {
		_memcachedNodes = memcachedNodes;
		_primaryNodeIds = primaryNodeIds;
		_failoverNodeIds = failoverNodeIds;
		_address2Ids = address2Ids;
		_storageKeyFormat = storageKeyFormat;

        _encodeNodeIdInSessionId = !((getCountNodes() <= 1 || isCouchbaseConfig(memcachedNodes)) && _primaryNodeIds.isEmpty());

		if (_encodeNodeIdInSessionId) {
			if (memcachedClientCallback == null) {
				throw new IllegalArgumentException("The MemcachedClientCallback must not be null.");
			}
			_sessionIdFormat = new SessionIdFormat(storageKeyFormat);
	        _nodeIdService = new NodeIdService( createNodeAvailabilityCache( getCountNodes(), NODE_AVAILABILITY_CACHE_TTL, memcachedClientCallback ),
	        				primaryNodeIds, failoverNodeIds );
		}
		else {
			_sessionIdFormat = new SessionIdFormat(storageKeyFormat) {
				@Override
				public boolean isValid(final String sessionId) {
					return sessionId != null;
				}
				@Override
				public String createBackupKey(final String origKey) {
					throw new UnsupportedOperationException("Not supported for single node configuration without node id.");
				}
				@Override
				public String createSessionId(final String sessionId, final String memcachedId) {
					return sessionId;
				}
				@Override
				public String extractMemcachedId(final String sessionId) {
					throw new UnsupportedOperationException("Not supported for single node configuration without node id.");
				}
			};
	        _nodeIdService = null;
		}
	}

    private boolean isCouchbaseConfig(final String memcachedNodes) {
        return memcachedNodes.startsWith("http://");
    }

    protected NodeAvailabilityCache<String> createNodeAvailabilityCache( final int size, final long ttlInMillis,
            @Nonnull final MemcachedClientCallback memcachedClientCallback ) {
        return new NodeAvailabilityCache<String>( size, ttlInMillis, new CacheLoader<String>() {

            @Override
            public boolean isNodeAvailable( final String key ) {
                try {
                	memcachedClientCallback.get(_sessionIdFormat.createSessionId( "ping", key ) );
                    return true;
                } catch ( final Exception e ) {
                    return false;
                }
            }

        } );
    }

	/**
	 * Parses the given memcachedNodes definition and returns of {@link MemcachedNodesManager}.
	 * Supported memcachedNodes formats:
	 * <ul>
	 * <li><code>&lt;hostOrIPAddress&gt;:&lt;port&gt;</code> - e.g. <code>localhost:11211</code></li>
	 * <li><code>&lt;http://hostOrIPAddress&gt;:&lt;port&gt;/&lt;path&gt;</code> - e.g. <code>http://localhost:8091/pools</code></li>
	 * <li><code>&lt;nodeId&gt;:&lt;hostOrIPAddress&gt;:&lt;port&gt;</code> - e.g. <code>n1:localhost:11211</code></li>
	 * <li><code>&lt;nodeId&gt;:&lt;hostOrIPAddress&gt;:&lt;port&gt;([ ,]&lt;nodeId&gt;:&lt;hostOrIPAddress&gt;:&lt;port&gt;)+</code> - e.g.
	 * 	<ul>
	 * 		<li><code>n1:localhost:11211,n2:localhost:11212</code></li>
	 * 		<li><code>n1:localhost:11211 n2:localhost:11212</code></li>
	 *  </ul>
	 * </li>
	 * </ul>
	 * @param memcachedNodes
	 * @param failoverNodes TODO
	 * @param storageKeyPrefix TODO
	 * @param memcachedClientCallback TODO
	 * @return
	 */
	@Nonnull
    public static MemcachedNodesManager createFor(final String memcachedNodes, final String failoverNodes, final StorageKeyFormat storageKeyFormat,
            final MemcachedClientCallback memcachedClientCallback) {
		if ( memcachedNodes == null || memcachedNodes.trim().isEmpty() ) {
			throw new IllegalArgumentException("null or empty memcachedNodes not allowed.");
		}

        if ( !NODES_PATTERN.matcher( memcachedNodes ).matches() && !SINGLE_NODE_PATTERN.matcher(memcachedNodes).matches()
        		&& !COUCHBASE_BUCKET_NODES_PATTERN.matcher(memcachedNodes).matches()) {
            throw new IllegalArgumentException( "Configured memcachedNodes attribute has wrong format, must match " + NODES_REGEX );
        }

        final Matcher singleNodeMatcher = SINGLE_NODE_PATTERN.matcher(memcachedNodes);

        // we have a linked hashmap to have insertion order for addresses
        final LinkedHashMap<InetSocketAddress, String> address2Ids = new LinkedHashMap<InetSocketAddress, String>(1);

        /**
         * If mutliple nodes are configured
         */
        if (singleNodeMatcher.matches()) {    // for single
            address2Ids.put(getSingleShortNodeDefinition(singleNodeMatcher), null);
        }
        else if (COUCHBASE_BUCKET_NODES_PATTERN.matcher(memcachedNodes).matches()) {    // for couchbase
            final Matcher matcher = COUCHBASE_BUCKET_NODE_PATTERN.matcher(memcachedNodes);
            while (matcher.find()) {
                final String hostname = matcher.group( 1 );
                final int port = Integer.parseInt( matcher.group( 2 ) );
                address2Ids.put(new InetSocketAddress( hostname, port ), null);
            }
            if (address2Ids.isEmpty()) {
                throw new IllegalArgumentException("All nodes are also configured as failover nodes,"
                        + " this is a configuration failure. In this case, you probably want to leave out the failoverNodes.");
            }
        }
        else { // If mutliple nodes are configured
            final Matcher matcher = NODE_PATTERN.matcher( memcachedNodes);
            while (matcher.find()) {
                final Pair<String, InetSocketAddress> nodeInfo = getRegularNodeDefinition(matcher);
                address2Ids.put(nodeInfo.getSecond(), nodeInfo.getFirst());
            }
            if (address2Ids.isEmpty()) {
                throw new IllegalArgumentException("All nodes are also configured as failover nodes,"
                        + " this is a configuration failure. In this case, you probably want to leave out the failoverNodes.");
            }
        }

        final List<String> failoverNodeIds = initFailoverNodes(failoverNodes, address2Ids.values());

        // validate that for a single node there's no failover node specified as this does not make sense.
        if(address2Ids.size() == 1 && failoverNodeIds.size() >= 1) {
        	throw new IllegalArgumentException("For a single memcached node there should/must no failoverNodes be specified.");
        }

        final NodeIdList primaryNodeIds = new NodeIdList();
        for(final Map.Entry<InetSocketAddress, String> address2Id : address2Ids.entrySet()) {
	        final String nodeId = address2Id.getValue();
			if (nodeId != null && !failoverNodeIds.contains(nodeId) ) {
	        	primaryNodeIds.add(nodeId);
	        }
        }

        return new MemcachedNodesManager(memcachedNodes, primaryNodeIds, failoverNodeIds, address2Ids, storageKeyFormat, memcachedClientCallback);
	}

    private static InetSocketAddress getSingleShortNodeDefinition(final Matcher singleNodeMatcher) {
        final String hostname = singleNodeMatcher.group(1);
        final int port = Integer.parseInt(singleNodeMatcher.group(2));
        return new InetSocketAddress(hostname, port);
    }

    private static Pair<String, InetSocketAddress> getRegularNodeDefinition(final Matcher matcher) {
        final String nodeId = matcher.group( 1 );

        final String hostname = matcher.group( 2 );
        final int port = Integer.parseInt( matcher.group( 3 ) );
        final InetSocketAddress address = new InetSocketAddress( hostname, port );

        return Pair.of(nodeId, address);
    }

    private static List<String> initFailoverNodes(final String failoverNodes, final Collection<String> allNodeIds) {
        final List<String> failoverNodeIds = new ArrayList<String>();
        if ( failoverNodes != null && failoverNodes.trim().length() != 0 ) {
            final String[] failoverNodesArray = failoverNodes.split( " |," );
            for ( final String failoverNodeId : failoverNodesArray ) {
    	        final String failoverNodeIdTrimmed = failoverNodeId.trim();
				if ( !allNodeIds.contains( failoverNodeIdTrimmed ) ) {
    	            throw new IllegalArgumentException( "Invalid failover node id " + failoverNodeIdTrimmed + ": "
    	                    + "not existing in memcachedNodes '" + allNodeIds + "'." );
    	        }
                failoverNodeIds.add( failoverNodeIdTrimmed );
            }
        }
        return failoverNodeIds;
    }

    /**
     * Provides the original memcachedNodes configuration string.
     */
    public String getMemcachedNodes() {
		return _memcachedNodes;
	}

	/**
	 * Returns the number of memcached nodes.
	 */
	public int getCountNodes() {
		return _address2Ids.size();
	}

	/**
	 * Returns the primary node ids, which are the memcachedNodes that are not specified in failoverNodes.
	 */
	@Nonnull
	public NodeIdList getPrimaryNodeIds() {
		return _primaryNodeIds;
	}

	/**
	 * Returns the failover node ids as specified by failoverNodes in the config.
	 */
	@Nonnull
	public List<String> getFailoverNodeIds() {
		return _failoverNodeIds;
	}

	/**
	 * Specifies if the memcached node id shall be encoded in the sessionId. This is only false
	 * for a single memcachedNode definition without a nodeId (e.g. <code>localhost:11211</code>)
	 * or for couchbase REST URIs (one or more of e.g. http://10.10.0.1:8091/pools).
	 */
	public boolean isEncodeNodeIdInSessionId() {
		return _encodeNodeIdInSessionId;
	}

	/**
	 * Return the nodeId for the given socket address. Returns <code>null</code>
	 * if the socket address is not known.
	 * @throws IllegalArgumentException thrown when the socketAddress is <code>null</code> or not registered with this {@link MemcachedNodesManager}.
	 */
	@Nonnull
	public String getNodeId(final InetSocketAddress socketAddress) throws IllegalArgumentException {
		if ( socketAddress == null ) {
			throw new IllegalArgumentException("SocketAddress must not be null.");
		}
		final String result = _address2Ids.get( socketAddress );
		if ( result == null ) {
			throw new IllegalArgumentException("SocketAddress " + socketAddress + " not known (registered addresses: " + _address2Ids.keySet() + ").");
		}
		return result;
	}

	/**
     * Get the next node id for the given one, based on the primary node ids (memcachedNodes without failoverNodes).
     * For the last node id the first one is returned.
     * If this list contains only a single node, conceptionally there's no next node
     * so that <code>null</code> is returned.
     * @return the next node id or <code>null</code> if there's no next node id.
     * @throws IllegalArgumentException thrown if the given nodeId is not part of this list.
	 */
	@CheckForNull
	public String getNextPrimaryNodeId(final String nodeId) {
		return _primaryNodeIds.getNextNodeId(nodeId);
	}

	/**
     * Get the next available node id for the given one, based on the primary node ids
     * (memcachedNodes without failoverNodes). For the last node id the first one is returned.
     * If this list contains only a single node, conceptionally there's no next node
     * so that <code>null</code> is returned.
     * @return the next available node id or <code>null</code> if there's no next available node id.
     * @see #getNextPrimaryNodeId(String)
     * @see #isNodeAvailable(String)
	 */
	public String getNextAvailableNodeId(final String nodeId) {
	    String result = nodeId;
	    do {
	        result = _primaryNodeIds.getNextNodeId(result);
	        if(result != null && result.equals(nodeId)) {
	            result = null;
	        }
	    } while(result != null && !isNodeAvailable(result));
	    return result;
	}

	/**
	 * Provides access to the {@link SessionIdFormat} handling sessionIds for this memcached Nodes configuration.
	 */
	@Nonnull
	public SessionIdFormat getSessionIdFormat() {
		return _sessionIdFormat;
	}

    /**
     * Provides the {@link StorageKeyFormat} to create the storage key.
     */
    @Nonnull
    public StorageKeyFormat getStorageKeyFormat() {
        return _storageKeyFormat;
    }

	/**
	 * Must return all known memcached addresses.
	 */
	@Nonnull
	public List<InetSocketAddress> getAllMemcachedAddresses() {
		return new ArrayList<InetSocketAddress>( _address2Ids.keySet() );
	}

	/**
	 * Creates a new sessionId based on the given one, usually by appending a randomly selected memcached node id.
	 * If the memcachedNodes were configured using a single node without nodeId, the sessionId is returned unchanged.
	 */
	@Nonnull
	public String createSessionId( @Nonnull final String sessionId ) {
		return isEncodeNodeIdInSessionId() ? _sessionIdFormat.createSessionId(sessionId,  _nodeIdService.getMemcachedNodeId() ) : sessionId;
	}

    /**
     * Mark the given nodeId as available as specified.
     * @param nodeId the nodeId to update
     * @param available specifies if the node was abailable or not
     */
	public void setNodeAvailable(@Nullable final String nodeId, final boolean available) {
		if ( _nodeIdService != null ) {
			_nodeIdService.setNodeAvailable(nodeId, available);
		}
	}

    /**
     * Determines, if the given nodeId is available.
     * @param nodeId the node to check, not <code>null</code>.
     * @return <code>true</code>, if the node is marked as available
     */
	public boolean isNodeAvailable(final String nodeId) {
		return _nodeIdService.isNodeAvailable(nodeId);
	}

	/**
	 * Can be used to determine if the given sessionId can be used to interact with memcached.
	 * @see #canHitMemcached(String)
	 */
	public boolean isValidForMemcached(final String sessionId) {
		if ( isEncodeNodeIdInSessionId() ) {
	        final String nodeId = _sessionIdFormat.extractMemcachedId( sessionId );
	        if ( nodeId == null ) {
	            LOG.debug( "The sessionId does not contain a nodeId so that the memcached node could not be identified." );
	            return false;
	        }
		}
		return true;
	}

	/**
	 * Can be used to determine if the given sessionId can be used to interact with memcached.
	 * This also checks if the related memcached is available.
	 * @see #isValidForMemcached(String)
	 */
	public boolean canHitMemcached(final String sessionId) {
		if ( isEncodeNodeIdInSessionId() ) {
	        final String nodeId = _sessionIdFormat.extractMemcachedId( sessionId );
	        if ( nodeId == null ) {
	            LOG.debug( "The sessionId does not contain a nodeId so that the memcached node could not be identified." );
	            return false;
	        }
			if ( !_nodeIdService.isNodeAvailable( nodeId ) ) {
	            LOG.debug( "The node "+ nodeId +" is not available, therefore " + sessionId + " cannot be loaded from this memcached." );
	            return false;
	        }
		}
		return true;
	}

	public void onLoadFromMemcachedSuccess(final String sessionId) {
		setNodeAvailableForSessionId(sessionId, true);
	}

	public void onLoadFromMemcachedFailure(final String sessionId) {
		setNodeAvailableForSessionId(sessionId, false);
	}

	/**
	 * Mark the memcached node encoded in the given sessionId as available or not. If nodeIds shall
	 * not be encoded in the sessionId or if the given sessionId does not contain a nodeId no
	 * action will be taken.
	 *
	 * @param sessionId the sessionId that may contain a node id.
	 * @param available specifies if the possibly referenced node is available or not.
	 *
	 * @return the extracted nodeId or <code>null</code>.
	 *
	 * @see #isEncodeNodeIdInSessionId()
	 */
	public String setNodeAvailableForSessionId(final String sessionId, final boolean available) {
		if ( _nodeIdService != null && isEncodeNodeIdInSessionId() ) {
			final String nodeId = _sessionIdFormat.extractMemcachedId(sessionId);
			if ( nodeId != null ) {
				_nodeIdService.setNodeAvailable(nodeId, available);
				return nodeId;
			}
			else {
				LOG.warn("Got sessionId without nodeId: " + sessionId);
			}
		}
		return null;
	}

    /**
     * Returns a new session id if node information shall be encoded in the session id
     * and the encoded nodeId given one is <code>null</code> or not available.
     * @param sessionId the session id that is checked.
     * @return a new session id or <code>null</code>.
     */
	public String getNewSessionIdIfNodeFromSessionIdUnavailable( @Nonnull final String sessionId ) {
		if ( isEncodeNodeIdInSessionId() ) {
	        final String nodeId = _sessionIdFormat.extractMemcachedId( sessionId );
	        final String newNodeId = _nodeIdService.getNewNodeIdIfUnavailable( nodeId );
	        if ( newNodeId != null ) {
	            return _sessionIdFormat.createNewSessionId( sessionId, newNodeId);
	        }
		}
		return null;
	}

    /**
     * Changes the sessionId by setting the given jvmRoute and replacing the memcachedNodeId if it's currently
     * set to a failoverNodeId.
     * @param sessionId the current session id
     * @param jvmRoute the new jvmRoute to set.
     * @return the session id with maybe new jvmRoute and/or new memcachedId.
     */
    public String changeSessionIdForTomcatFailover( @Nonnull final String sessionId, final String jvmRoute ) {
        final String newSessionId = jvmRoute != null && !jvmRoute.trim().isEmpty()
                ? _sessionIdFormat.changeJvmRoute( sessionId, jvmRoute )
                : _sessionIdFormat.stripJvmRoute(sessionId);
        if ( isEncodeNodeIdInSessionId() ) {
            final String nodeId = _sessionIdFormat.extractMemcachedId( newSessionId );
            if(_failoverNodeIds != null && _failoverNodeIds.contains(nodeId)) {
                final String newNodeId = _nodeIdService.getAvailableNodeId( nodeId );
                if ( newNodeId != null ) {
                    return _sessionIdFormat.createNewSessionId( newSessionId, newNodeId);
                }
            }
        }
        return newSessionId;
    }

	/**
	 * Determines, if the current memcachedNodes configuration is a couchbase bucket configuration
	 * (like e.g. http://10.10.0.1:8091/pools).
	 */
    public boolean isCouchbaseBucketConfig() {
        return COUCHBASE_BUCKET_NODES_PATTERN.matcher(_memcachedNodes).matches();
    }

    /**
     * Returns a list of couchbase REST interface uris if the current configuration is
     * a couchbase bucket configuration.
     * @see #isCouchbaseBucketConfig()
     */
    public List<URI> getCouchbaseBucketURIs() {
        if(!isCouchbaseBucketConfig())
            throw new IllegalStateException("This is not a couchbase bucket configuration.");
        final List<URI> result = new ArrayList<URI>(_address2Ids.size());
        final Matcher matcher = COUCHBASE_BUCKET_NODE_PATTERN.matcher(_memcachedNodes);
        while (matcher.find()) {
            try {
                result.add(new URI(matcher.group()));
            } catch (final URISyntaxException e) {
                throw new RuntimeException(e);
            }
        }
        return result;
    }

}

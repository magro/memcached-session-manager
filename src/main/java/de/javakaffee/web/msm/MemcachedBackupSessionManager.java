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

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.spy.memcached.MemcachedClient;

import org.apache.catalina.Container;
import org.apache.catalina.Context;
import org.apache.catalina.Lifecycle;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleListener;
import org.apache.catalina.Manager;
import org.apache.catalina.Session;
import org.apache.catalina.session.ManagerBase;
import org.apache.catalina.session.StandardSession;
import org.apache.catalina.util.LifecycleSupport;

import de.javakaffee.web.msm.NodeAvailabilityCache.CacheLoader;
import de.javakaffee.web.msm.NodeIdResolver.MapBasedResolver;
import de.javakaffee.web.msm.SessionTrackerValve.SessionBackupService;

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
public class MemcachedBackupSessionManager extends ManagerBase implements Lifecycle, SessionBackupService, PropertyChangeListener {

    protected static final String NAME = MemcachedBackupSessionManager.class.getSimpleName();

    private static final String INFO = NAME + "/1.0";

    private static final String NODE_REGEX = "([\\w]+):([^:]+):([\\d]+)";
    private static final Pattern NODE_PATTERN = Pattern.compile( NODE_REGEX );

    private static final String NODES_REGEX = NODE_REGEX + "(?:\\s+" + NODE_REGEX + ")*";
    private static final Pattern NODES_PATTERN = Pattern.compile( NODES_REGEX );

    private static final String NODES_TESTED = "nodes.tested";

    private static final String NODE_FAILURE = "node.failure";

    private static final String ORIG_SESSION_ID = "orig.sid";

    private static final String RELOCATE_SESSION_ID = "relocate.sid";

    private final Random _random = new Random();

    private final Logger _logger = Logger.getLogger( MemcachedBackupSessionManager.class.getName() );

    private final LifecycleSupport _lifecycle = new LifecycleSupport( this );

    private final SessionIdFormat _sessionIdFormat = new SessionIdFormat();

    // -------------------- configuration properties --------------------

    /**
     * The memcached nodes space separated and with the id prefix, e.g.
     * n1:localhost:11211 n2:localhost:11212
     * 
     */
    private String _memcachedNodes;

    /**
     * The ids of memcached failover nodes separated by space, e.g.
     * <code>n1 n2</code>
     * 
     */
    private String _failoverNodes;

    /**
     * The pattern used for excluding requests from a session-backup, e.g.
     * <code>.*\.(png|gif|jpg|css|js)$</code>. Is matched against
     * request.getRequestURI.
     */
    private String _requestUriIgnorePattern;

    /**
     * Specifies if the session shall be stored asynchronously in memcached as
     * {@link MemcachedClient#set(String, int, Object)} supports it. If this is
     * false, the timeout set via {@link #setSessionBackupTimeout(int)} is
     * evaluated.
     * <p>
     * Notice: if the session backup is done asynchronously, it is possible that
     * a session cannot be stored in memcached and we don't notice that -
     * therefore the session would not get relocated to another memcached node.
     * </p>
     * <p>
     * By default this property is set to <code>false</code> - the session
     * backup is performed synchronously.
     * </p>
     */
    private boolean _sessionBackupAsync = false;

    /**
     * The timeout in milliseconds after that a session backup is considered as
     * beeing failed.
     * <p>
     * This property is only evaluated if sessions are stored synchronously (set
     * via {@link #setSessionBackupAsync(boolean)}).
     * </p>
     * <p>
     * The default value is <code>100</code> millis.
     * </p>
     */
    private int _sessionBackupTimeout = 100;

    /**
     * The class of the factory for
     * {@link net.spy.memcached.transcoders.Transcoder}s. Default class is
     * {@link SessionSerializingTranscoderFactory}.
     */
    private Class<? extends TranscoderFactory> _transcoderFactoryClass = SessionSerializingTranscoderFactory.class;

    /**
     * Specifies, if iterating over collection elements shall be done on a copy
     * of the collection or on the collection itself.
     * <p>
     * This option can be useful if you have multiple requests running in
     * parallel for the same session (e.g. AJAX) and you are using
     * non-thread-safe collections (e.g. {@link java.util.ArrayList} or
     * {@link java.util.HashMap}). In this case, your application might modify a
     * collection while it's being serialized for backup in memcached.
     * </p>
     * <p>
     * <strong>Note:</strong> This must be supported by the TranscoderFactory
     * specified via {@link #setTranscoderFactoryClass(String)}.
     * </p>
     */
    private boolean _copyCollectionsForSerialization = false;

    // -------------------- END configuration properties --------------------

    /*
     * the memcached client
     */
    private MemcachedClient _memcached;

    /*
     * findSession may be often called in one request. If a session is requested
     * that we don't have locally stored each findSession invocation would
     * trigger a memcached request - this would open the door for DOS attacks...
     * 
     * this solution: use a LRUCache with a timeout to store, which session had
     * been requested in the last <n> millis.
     */
    private LRUCache<String, Boolean> _missingSessionsCache;

    /*
     * remove may be called with sessionIds that already failed before (probably
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
    private NodeAvailabilityCache<String> _nodeAvailabilityCache;

    //private LRUCache<String, String> _relocatedSessions;

    /*
     * we have to implement rejectedSessions - not sure why
     */
    private int _rejectedSessions;

    private Set<String> _allNodeIds;
    private List<String> _nodeIds;

    private List<String> _failoverNodeIds;

    /**
     * Return descriptive information about this Manager implementation and the
     * corresponding version number, in the format
     * <code>&lt;description&gt;/&lt;version&gt;</code>.
     * 
     * @return the info string
     */
    @Override
    public String getInfo() {
        return INFO;
    }

    /**
     * Return the descriptive short name of this Manager implementation.
     * 
     * @return the short name
     */
    @Override
    public String getName() {
        return NAME;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void init() {
        _logger.info( getClass().getSimpleName() + " starts initialization..." );

        if ( initialized ) {
            return;
        }

        super.init();

        /*
         * add the valve for tracking requests for that the session must be sent
         * to memcached
         */
        getContainer().getPipeline().addValve( new SessionTrackerValve( _requestUriIgnorePattern, this ) );

        /*
         * init memcached
         */

        if ( !NODES_PATTERN.matcher( _memcachedNodes ).matches() ) {
            throw new IllegalArgumentException( "Configured memcachedNodes attribute has wrong format, must match " + NODES_REGEX );
        }

        _nodeIds = new ArrayList<String>();
        _allNodeIds = new HashSet<String>();
        final Matcher matcher = NODE_PATTERN.matcher( _memcachedNodes );
        final List<InetSocketAddress> addresses = new ArrayList<InetSocketAddress>();
        final Map<InetSocketAddress, String> address2Ids = new HashMap<InetSocketAddress, String>();
        while ( matcher.find() ) {
            initHandleNodeDefinitionMatch( matcher, addresses, address2Ids );
        }

        initFailoverNodes();

        if ( _nodeIds.isEmpty() ) {
            throw new IllegalArgumentException( "All nodes are also configured as failover nodes,"
                    + " this is a configuration failure. In this case, you probably want to leave out the failoverNodes." );
        }

        try {
            log.info( "Starting with transcoder factory " + _transcoderFactoryClass.getName() );
            final TranscoderFactory transcoderFactory = _transcoderFactoryClass.newInstance();
            _memcached =
                    new MemcachedClient( new SuffixLocatorConnectionFactory( this, new MapBasedResolver( address2Ids ),
                            _sessionIdFormat, transcoderFactory ), addresses );
        } catch ( final Exception e ) {
            throw new RuntimeException( "Could not create memcached client", e );
        }

        /*
         * create the missing sessions cache
         */
        _missingSessionsCache = new LRUCache<String, Boolean>( 200, 500 );
        _nodeAvailabilityCache = createNodeAvailabilityCache( 1000 );

        //_relocatedSessions = new LRUCache<String, String>( 100000, getMaxInactiveInterval() * 1000 );
    }

    private NodeAvailabilityCache<String> createNodeAvailabilityCache( final long ttlInMillis ) {
        return new NodeAvailabilityCache<String>( _allNodeIds.size(), ttlInMillis, new CacheLoader<String>() {

            @Override
            public boolean isNodeAvailable( final String key ) {
                try {
                    _memcached.get( _sessionIdFormat.createSessionId( "ping", key ) );
                    return true;
                } catch ( final Exception e ) {
                    return false;
                }
            }

        } );
    }

    private void initFailoverNodes() {
        _failoverNodeIds = new ArrayList<String>();
        if ( _failoverNodes != null && _failoverNodes.trim().length() != 0 ) {
            final String[] failoverNodes = _failoverNodes.split( " " );
            for ( final String failoverNode : failoverNodes ) {
                final String nodeId = failoverNode.trim();
                if ( !_nodeIds.remove( nodeId ) ) {
                    throw new IllegalArgumentException( "Invalid failover node id " + nodeId + ": "
                            + "not existing in memcachedNodes '" + _memcachedNodes + "'." );
                }
                _failoverNodeIds.add( nodeId );
            }
        }
    }

    private void initHandleNodeDefinitionMatch( final Matcher matcher, final List<InetSocketAddress> addresses,
            final Map<InetSocketAddress, String> address2Ids ) {
        final String nodeId = matcher.group( 1 );
        _nodeIds.add( nodeId );
        _allNodeIds.add( nodeId );

        final String hostname = matcher.group( 2 );
        final int port = Integer.parseInt( matcher.group( 3 ) );
        final InetSocketAddress address = new InetSocketAddress( hostname, port );
        addresses.add( address );

        address2Ids.put( address, nodeId );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setContainer( final Container container ) {

        // De-register from the old Container (if any)
        if ( this.container != null && this.container instanceof Context ) {
            ( (Context) this.container ).removePropertyChangeListener( this );
        }

        // Default processing provided by our superclass
        super.setContainer( container );

        // Register with the new Container (if any)
        if ( this.container != null && this.container instanceof Context ) {
            setMaxInactiveInterval( ( (Context) this.container ).getSessionTimeout() * 60 );
            ( (Context) this.container ).addPropertyChangeListener( this );
        }

    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected synchronized String generateSessionId() {
        return _sessionIdFormat.createSessionId( super.generateSessionId(), getMemcachedNodeId() );
    }

    private String getMemcachedNodeId() {
        return _nodeIds.get( _random.nextInt( _nodeIds.size() ) );
    }

    private boolean isValidSessionIdFormat( final String sessionId ) {
        return _sessionIdFormat.isValid( sessionId );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void expireSession( final String sessionId ) {
        _logger.fine( "expireSession invoked: " + sessionId );
        super.expireSession( sessionId );
        _memcached.delete( sessionId );
    }

    /**
     * Return the active Session, associated with this Manager, with the
     * specified session id (if any); otherwise return <code>null</code>.
     * 
     * @param id
     *            The session id for the session to be returned
     * @return the session or <code>null</code> if no session was found locally
     *         or in memcached.
     * 
     * @exception IllegalStateException
     *                if a new session cannot be instantiated for any reason
     * @exception IOException
     *                if an input/output error occurs while processing this
     *                request
     */
    @Override
    public Session findSession( final String id ) throws IOException {
        Session result = super.findSession( id );
        if ( result == null && _missingSessionsCache.get( id ) == null ) {
            result = loadFromMemcached( id );
            if ( result != null ) {
                add( result );
            } else {
                _missingSessionsCache.put( id, Boolean.TRUE );
            }
        }
        //        if ( result == null ) {
        //            final String relocatedSessionId = _relocatedSessions.get( id );
        //            if ( relocatedSessionId != null ) {
        //                result = findSession( relocatedSessionId );
        //            }
        //        }
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Session createSession( final String sessionId ) {
        _logger.fine( "createSession invoked: " + sessionId );

        Session session = null;

        if ( sessionId != null ) {
            session = loadFromMemcached( sessionId );
        }

        if ( session == null ) {

            session = createEmptySession();
            session.setNew( true );
            session.setValid( true );
            session.setCreationTime( System.currentTimeMillis() );
            session.setMaxInactiveInterval( this.maxInactiveInterval );
            session.setId( generateSessionId() );

            if ( _logger.isLoggable( Level.FINE ) ) {
                _logger.fine( "Created new session with id " + session.getId() );
            }

        }

        sessionCounter++;

        return ( session );

    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MemcachedBackupSession createEmptySession() {
        return new MemcachedBackupSession( this );
    }

    /**
     * Store the provided session in memcached.
     * 
     * @param session
     *            the session to save
     * @return the {@link SessionTrackerValve.SessionBackupService.BackupResult}
     */
    public BackupResult backupSession( final Session session ) {
        if ( _logger.isLoggable( Level.INFO ) ) {
            _logger.fine( "Trying to store session in memcached: " + session.getId() );
        }
        try {

            if ( session.getNote( RELOCATE_SESSION_ID ) != null ) {
                _logger.info( "Found relocate session id, setting new id on session..." );
                session.setNote( NODE_FAILURE, Boolean.TRUE );
                ( (MemcachedBackupSession) session ).setIdForRelocate( session.getNote( RELOCATE_SESSION_ID ).toString() );
                session.removeNote( RELOCATE_SESSION_ID );
            }

            storeSessionInMemcached( session );
            return BackupResult.SUCCESS;
        } catch ( final NodeFailureException e ) {
            if ( _logger.isLoggable( Level.INFO ) ) {
                _logger.info( "Could not store session in memcached (" + session.getId() + ")" );
            }

            /*
             * get the next memcached node to try
             */
            final String nodeId = _sessionIdFormat.extractMemcachedId( session.getId() );
            final String targetNodeId = getNextNodeId( nodeId, getTestedNodes( session ) );

            final MemcachedBackupSession backupSession = (MemcachedBackupSession) session;

            if ( targetNodeId == null ) {

                _logger.warning( "The node " + nodeId
                        + " is not available and there's no node for relocation left, omitting session backup." );

                noFailoverNodeLeft( backupSession );

                return BackupResult.FAILURE;

            } else {

                if ( getTestedNodes( session ) == null ) {
                    setTestedNodes( session, new HashSet<String>() );
                }

                final BackupResult backupResult = failover( backupSession, getTestedNodes( session ), targetNodeId );

                return handleAndTranslateBackupResult( backupResult, session );
            }
        }
    }

    private BackupResult handleAndTranslateBackupResult( final BackupResult backupResult, final Session session ) {
        switch ( backupResult ) {
            case SUCCESS:

                //_relocatedSessions.put( session.getNote( ORIG_SESSION_ID ).toString(), session.getId() );

                /*
                 * cleanup
                 */
                session.removeNote( ORIG_SESSION_ID );
                session.removeNote( NODE_FAILURE );
                session.removeNote( NODES_TESTED );

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

    private void setTestedNodes( final Session session, final Set<String> testedNodes ) {
        session.setNote( NODES_TESTED, testedNodes );
    }

    @SuppressWarnings( "unchecked" )
    private Set<String> getTestedNodes( final Session session ) {
        return (Set<String>) session.getNote( NODES_TESTED );
    }

    /**
     * Returns the new session id if the provided session has to be relocated.
     * 
     * @param session
     *            the session to check, never null.
     * @return the new session id, if this session has to be relocated.
     */
    public String sessionNeedsRelocate( final Session session ) {
        final String nodeId = _sessionIdFormat.extractMemcachedId( session.getId() );
        if ( nodeId != null && !_nodeAvailabilityCache.isNodeAvailable( nodeId ) ) {
            final String nextNodeId = getNextNodeId( nodeId, _nodeAvailabilityCache.getUnavailableNodes() );
            if ( nextNodeId != null ) {
                final String newSessionId = _sessionIdFormat.createNewSessionId( session.getId(), nextNodeId );
                session.setNote( RELOCATE_SESSION_ID, newSessionId );
                return newSessionId;
            } else {
                _logger.warning( "The node " + nodeId + " is not available and there's no node for relocation left." );
            }
        }
        return null;
    }

    private BackupResult failover( final MemcachedBackupSession session, final Set<String> testedNodes, final String targetNodeId ) {

        final String nodeId = _sessionIdFormat.extractMemcachedId( session.getId() );

        testedNodes.add( nodeId );

        /*
         * we must store the original session id so that we can set this if no
         * memcached node is left for taking over
         */
        if ( session.getNote( ORIG_SESSION_ID ) == null ) {
            session.setNote( ORIG_SESSION_ID, session.getId() );
        }

        /*
         * relocate session to our memcached node...
         * 
         * and mark it as a node-failure-session, so that remove(session) does
         * not try to remove it from memcached... (the session is removed and
         * added when the session id is changed)
         */
        session.setNote( NODE_FAILURE, Boolean.TRUE );
        session.setIdForRelocate( _sessionIdFormat.createNewSessionId( session.getId(), targetNodeId ) );

        /*
         * invoke backup again, until we have a success or a failure
         */
        final BackupResult backupResult = backupSession( session );

        return backupResult;
    }

    private void noFailoverNodeLeft( final MemcachedBackupSession session ) {

        /*
         * we must set the original session id in case we changed it already
         */
        final String origSessionId = (String) session.getNote( ORIG_SESSION_ID );
        if ( origSessionId != null && !origSessionId.equals( session.getId() ) ) {
            session.setIdForRelocate( origSessionId );
        }

        /*
         * cleanup
         */
        session.removeNote( ORIG_SESSION_ID );
        session.removeNote( NODE_FAILURE );
        session.removeNote( NODES_TESTED );
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

    private void storeSessionInMemcached( final Session session ) throws NodeFailureException {
        final Future<Boolean> future = _memcached.set( session.getId(), getMaxInactiveInterval(), session );
        if ( !_sessionBackupAsync ) {
            try {
                future.get( _sessionBackupTimeout, TimeUnit.MILLISECONDS );
            } catch ( final Exception e ) {
                if ( _logger.isLoggable( Level.INFO ) ) {
                    _logger.info( "Could not store session " + session.getId() + " in memcached: " + e );
                }
                final String nodeId = _sessionIdFormat.extractMemcachedId( session.getId() );
                _nodeAvailabilityCache.setNodeAvailable( nodeId, false );
                throw new NodeFailureException( "Could not store session in memcached.", nodeId );
            }
        }
    }

    private Session loadFromMemcached( final String sessionId ) {
        if ( !isValidSessionIdFormat( sessionId ) ) {
            return null;
        }
        final String nodeId = _sessionIdFormat.extractMemcachedId( sessionId );
        if ( !_nodeAvailabilityCache.isNodeAvailable( nodeId ) ) {
            _logger.fine( "Asked for session " + sessionId + ", but the related"
                    + " memcached node is still marked as unavailable (won't load from memcached)." );
        } else {
            _logger.fine( "Loading session from memcached: " + sessionId );
            try {
                final Session session = (Session) _memcached.get( sessionId );
                if ( _logger.isLoggable( Level.FINE ) ) {
                    if ( session == null ) {
                        _logger.fine( "Session " + sessionId + " not found in memcached." );
                    } else {
                        _logger.fine( "Found session with id " + sessionId );
                    }
                }
                _nodeAvailabilityCache.setNodeAvailable( nodeId, true );
                return session;
            } catch ( final NodeFailureException e ) {
                _logger.warning( "Could not load session with id " + sessionId + " from memcached." );
                _nodeAvailabilityCache.setNodeAvailable( nodeId, false );
            } catch ( final Exception e ) {
                _logger.warning( "Could not load session with id " + sessionId + " from memcached: " + e );
            }
        }
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void remove( final Session session ) {
        _logger.fine( "remove invoked," + " session.relocate:  " + session.getNote( SessionTrackerValve.RELOCATE )
                + ", node failure: " + session.getNote( NODE_FAILURE ) + ", node failure != TRUE: "
                + ( session.getNote( NODE_FAILURE ) != Boolean.TRUE ) + ", id: " + session.getId() );
        if ( session.getNote( NODE_FAILURE ) != Boolean.TRUE ) {
            try {
                _logger.fine( "Deleting session from memcached: " + session.getId() );
                _memcached.delete( session.getId() );
            } catch ( final NodeFailureException e ) {
                /* We can ignore this */
            }
        }
        super.remove( session );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getRejectedSessions() {
        return _rejectedSessions;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void load() throws ClassNotFoundException, IOException {
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setRejectedSessions( final int rejectedSessions ) {
        _rejectedSessions = rejectedSessions;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void unload() throws IOException {
    }

    /**
     * Set the memcached nodes.
     * <p>
     * E.g. <code>n1.localhost:11211 n2.localhost:11212</code>
     * </p>
     * 
     * @param memcachedNodes
     *            the memcached node definitions, whitespace separated
     */
    public void setMemcachedNodes( final String memcachedNodes ) {
        _memcachedNodes = memcachedNodes;
    }

    /**
     * The node ids of memcached nodes, that shall only be used for session
     * backup by this tomcat/manager, if there are no other memcached nodes
     * left. Node ids are separated by whitespace.
     * <p>
     * E.g. <code>n1 n2</code>
     * </p>
     * 
     * @param failoverNodes
     *            the failoverNodes to set, whitespace separated
     */
    public void setFailoverNodes( final String failoverNodes ) {
        _failoverNodes = failoverNodes;
    }

    /**
     * Set the regular expression for request uris to ignore for session backup.
     * This should include static resources like images, in the case they are
     * served by tomcat.
     * <p>
     * E.g. <code>.*\.(png|gif|jpg|css|js)$</code>
     * </p>
     * 
     * @param requestUriIgnorePattern
     *            the requestUriIgnorePattern to set
     * @author Martin Grotzke
     */
    public void setRequestUriIgnorePattern( final String requestUriIgnorePattern ) {
        _requestUriIgnorePattern = requestUriIgnorePattern;
    }

    public void setTranscoderFactoryClass( final String transcoderFactoryClassName ) {
        try {
            _transcoderFactoryClass = Class.forName( transcoderFactoryClassName ).asSubclass( TranscoderFactory.class );
        } catch ( final ClassNotFoundException e ) {
            _logger.severe( "The transcoderFactoryClass (" + transcoderFactoryClassName + ") could not be found" );
            throw new RuntimeException( e );
        }
    }

    /**
     * @param copyCollectionsForSerialization
     *            specifies, if iterating over collection elements shall be done
     *            on a copy of the collection or on the collection itself
     */
    public void setCopyCollectionsForSerialization( final boolean copyCollectionsForSerialization ) {
        _copyCollectionsForSerialization = copyCollectionsForSerialization;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addLifecycleListener( final LifecycleListener arg0 ) {
        _lifecycle.addLifecycleListener( arg0 );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public LifecycleListener[] findLifecycleListeners() {
        return _lifecycle.findLifecycleListeners();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void removeLifecycleListener( final LifecycleListener arg0 ) {
        _lifecycle.removeLifecycleListener( arg0 );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void start() throws LifecycleException {
        if ( !initialized ) {
            init();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void stop() throws LifecycleException {
        if ( initialized ) {
            _memcached.shutdown();
            destroy();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void propertyChange( final PropertyChangeEvent event ) {

        // Validate the source of this event
        if ( !( event.getSource() instanceof Context ) ) {
            return;
        }

        // Process a relevant property change
        if ( event.getPropertyName().equals( "sessionTimeout" ) ) {
            try {
                setMaxInactiveInterval( ( (Integer) event.getNewValue() ).intValue() * 60 );
            } catch ( final NumberFormatException e ) {
                _logger.warning( "standardManager.sessionTimeout: " + event.getNewValue().toString() );
            }
        }

    }

    // ===========================  for testing  ==============================

    protected void setNodeIds( final List<String> nodeIds ) {
        _nodeIds = nodeIds;
    }

    protected void setFailoverNodeIds( final List<String> failoverNodeIds ) {
        _failoverNodeIds = failoverNodeIds;
    }

    /**
     * Specifies if the session shall be stored asynchronously in memcached as
     * {@link MemcachedClient#set(String, int, Object)} supports it. If this is
     * false, the timeout set via {@link #setSessionBackupTimeout(int)} is
     * evaluated.
     * <p>
     * Notice: if the session backup is done asynchronously, it is possible that
     * a session cannot be stored in memcached and we don't notice that -
     * therefore the session would not get relocated to another memcached node.
     * </p>
     * <p>
     * By default this property is set to <code>false</code> - the session
     * backup is performed synchronously.
     * </p>
     * 
     * @param sessionBackupAsync
     *            the sessionBackupAsync to set
     */
    public void setSessionBackupAsync( final boolean sessionBackupAsync ) {
        _sessionBackupAsync = sessionBackupAsync;
    }

    /**
     * The timeout in milliseconds after that a session backup is considered as
     * beeing failed.
     * <p>
     * This property is only evaluated if sessions are stored synchronously (set
     * via {@link #setSessionBackupAsync(boolean)}).
     * </p>
     * <p>
     * The default value is <code>100</code> millis.
     * 
     * @param sessionBackupTimeout
     *            the sessionBackupTimeout to set (milliseconds)
     */
    public void setSessionBackupTimeout( final int sessionBackupTimeout ) {
        _sessionBackupTimeout = sessionBackupTimeout;
    }

    /**
     * The session class used by this manager, to be able to change the session
     * id without the whole notification lifecycle (which includes the
     * application also).
     */
    public static final class MemcachedBackupSession extends StandardSession {

        private static final long serialVersionUID = 1L;

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

            manager.remove( this );
            this.id = id;
            manager.add( this );

        }

        @Override
        public void setId( final String id ) {
            super.setId( id );
        }

        public void doAfterDeserialization() {
            if ( listeners == null ) {
                listeners = new ArrayList<Object>();
            }
            if ( notes == null ) {
                notes = new Hashtable<Object, Object>();
            }
        }

    }

}

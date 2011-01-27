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
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import net.spy.memcached.ConnectionFactory;
import net.spy.memcached.MemcachedClient;
import net.spy.memcached.transcoders.SerializingTranscoder;

import org.apache.catalina.Container;
import org.apache.catalina.Context;
import org.apache.catalina.Lifecycle;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleListener;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.Manager;
import org.apache.catalina.Session;
import org.apache.catalina.session.ManagerBase;
import org.apache.catalina.session.StandardSession;
import org.apache.catalina.util.LifecycleSupport;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

import de.javakaffee.web.msm.BackupSessionService.SimpleFuture;
import de.javakaffee.web.msm.BackupSessionTask.BackupResult;
import de.javakaffee.web.msm.LockingStrategy.LockingMode;
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

    private static final String NODES_REGEX = NODE_REGEX + "(?:(?:\\s+|,)" + NODE_REGEX + ")*";
    private static final Pattern NODES_PATTERN = Pattern.compile( NODES_REGEX );

    private static final int NODE_AVAILABILITY_CACHE_TTL = 50;

    private static final String PROTOCOL_TEXT = "text";
    private static final String PROTOCOL_BINARY = "binary";

    protected static final String NODE_FAILURE = "node.failure";

    protected final Log _log = LogFactory.getLog( getClass() );

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
     * evaluated. If this is <code>true</code>, the {@link #setBackupThreadCount(int)}
     * is evaluated.
     * <p>
     * By default this property is set to <code>true</code> - the session
     * backup is performed asynchronously.
     * </p>
     */
    private boolean _sessionBackupAsync = true;

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
     * The class name of the factory for
     * {@link net.spy.memcached.transcoders.Transcoder}s. Default class name is
     * {@link JavaSerializationTranscoderFactory}.
     */
    private String _transcoderFactoryClassName = JavaSerializationTranscoderFactory.class.getName();

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

    private String _customConverterClassNames;

    private boolean _enableStatistics = true;

    private int _backupThreadCount = Runtime.getRuntime().availableProcessors();

    private String _memcachedProtocol = PROTOCOL_TEXT;

    private final AtomicBoolean _enabled = new AtomicBoolean( true );

    // -------------------- END configuration properties --------------------

    protected Statistics _statistics;

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

    private NodeIdService _nodeIdService;

    //private LRUCache<String, String> _relocatedSessions;

    /**
     * The maximum number of active Sessions allowed, or -1 for no limit.
     */
    private int _maxActiveSessions = -1;

    private int _rejectedSessions;

    protected TranscoderService _transcoderService;

    private TranscoderFactory _transcoderFactory;

    private SerializingTranscoder _upgradeSupportTranscoder;

    private BackupSessionService _backupSessionService;

    private boolean _sticky = true;
    private String _lockingMode;
    private LockingStrategy _lockingStrategy;

    private SessionTrackerValve _sessionTrackerValve;


    static enum LockStatus {
        /**
         * For sticky sessions or readonly requests with non-sticky sessions there's no lock required.
         */
        LOCK_NOT_REQUIRED,
        LOCKED,
        COULD_NOT_AQUIRE_LOCK
    }

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
    public void initInternal() throws LifecycleException {
        initInternal( null );
    }

    /**
     * Initialize this manager. The memcachedClient parameter is there for testing
     * purposes. If the memcachedClient is provided it's used, otherwise a "real"/new
     * memcached client is created based on the configuration (like {@link #setMemcachedNodes(String)} etc.).
     *
     * @param memcachedClient the memcached client to use, for normal operations this should be <code>null</code>.
     */
    void initInternal( final MemcachedClient memcachedClient ) throws LifecycleException {
        super.initInternal();

        _log.info( getClass().getSimpleName() + " starts initialization... (configured" +
                " nodes definition " + _memcachedNodes + ", failover nodes " + _failoverNodes + ")" );

        _statistics = Statistics.create( _enableStatistics );

        /* init memcached
         */
        final MemcachedConfig config = createMemcachedConfig( _memcachedNodes, _failoverNodes );
        _memcached = memcachedClient != null ? memcachedClient : createMemcachedClient( config.getNodeIds(), config.getAddresses(),
                config.getAddress2Ids(), _statistics );
        _nodeIdService = new NodeIdService( createNodeAvailabilityCache( config.getCountNodes(), NODE_AVAILABILITY_CACHE_TTL, _memcached ),
                config.getNodeIds(), config.getFailoverNodeIds() );

        /* create the missing sessions cache
         */
        _missingSessionsCache = new LRUCache<String, Boolean>( 200, 500 );

        _sessionTrackerValve = new SessionTrackerValve( _requestUriIgnorePattern,
                (Context) getContainer(), this, _statistics, _enabled );
        getContainer().getPipeline().addValve( _sessionTrackerValve );

        initNonStickyLockingMode();

        _transcoderService = createTranscoderService( _statistics );

        _upgradeSupportTranscoder = getTranscoderFactory().createSessionTranscoder( this );

        _backupSessionService = new BackupSessionService( _transcoderService, _sessionBackupAsync, _sessionBackupTimeout,
                _backupThreadCount, _memcached, _nodeIdService, _statistics, _sticky );

        _log.info( getClass().getSimpleName() + " finished initialization, have node ids " + config.getNodeIds() + " and failover node ids " + config.getFailoverNodeIds() );

    }

    private MemcachedConfig createMemcachedConfig( final String memcachedNodes, final String failoverNodes ) {
        if ( !NODES_PATTERN.matcher( memcachedNodes ).matches() ) {
            throw new IllegalArgumentException( "Configured memcachedNodes attribute has wrong format, must match " + NODES_REGEX );
        }

        final List<String> nodeIds = new ArrayList<String>();
        final Matcher matcher = NODE_PATTERN.matcher( memcachedNodes  );
        final List<InetSocketAddress> addresses = new ArrayList<InetSocketAddress>();
        final Map<InetSocketAddress, String> address2Ids = new HashMap<InetSocketAddress, String>();
        while ( matcher.find() ) {
            initHandleNodeDefinitionMatch( matcher, addresses, address2Ids, nodeIds );
        }

        final List<String> failoverNodeIds = initFailoverNodes( failoverNodes, nodeIds );

        if ( nodeIds.isEmpty() ) {
            throw new IllegalArgumentException( "All nodes are also configured as failover nodes,"
                    + " this is a configuration failure. In this case, you probably want to leave out the failoverNodes." );
        }

        return new MemcachedConfig( memcachedNodes, failoverNodes, new NodeIdList( nodeIds ), failoverNodeIds, addresses, address2Ids );
    }

    private TranscoderService createTranscoderService( final Statistics statistics ) {
        return new TranscoderService( getTranscoderFactory().createTranscoder( this ) );
    }

    protected TranscoderFactory getTranscoderFactory() {
        if ( _transcoderFactory == null ) {
            try {
                _transcoderFactory = createTranscoderFactory();
            } catch ( final Exception e ) {
                throw new RuntimeException( "Could not create transcoder factory.", e );
            }
        }
        return _transcoderFactory;
    }

    protected MemcachedClient createMemcachedClient( final NodeIdList nodeIds, final List<InetSocketAddress> addresses,
            final Map<InetSocketAddress, String> address2Ids,
            final Statistics statistics ) {
        try {
            final ConnectionFactory connectionFactory = createConnectionFactory( nodeIds, address2Ids, statistics );
            return new MemcachedClient( connectionFactory, addresses );
        } catch ( final Exception e ) {
            throw new RuntimeException( "Could not create memcached client", e );
        }
    }

    private ConnectionFactory createConnectionFactory(
            final NodeIdList nodeIds, final Map<InetSocketAddress, String> address2Ids,
            final Statistics statistics ) {
        final MapBasedResolver resolver = new MapBasedResolver( address2Ids );
        if ( PROTOCOL_BINARY.equals( _memcachedProtocol ) ) {
            return new SuffixLocatorBinaryConnectionFactory( nodeIds, resolver, _sessionIdFormat, statistics );
        }
        return new SuffixLocatorConnectionFactory( nodeIds, resolver, _sessionIdFormat, statistics );
    }

    private TranscoderFactory createTranscoderFactory() throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        _log.info( "Creating transcoder factory " + _transcoderFactoryClassName );
        final Class<? extends TranscoderFactory> transcoderFactoryClass = loadTranscoderFactoryClass();
        final TranscoderFactory transcoderFactory = transcoderFactoryClass.newInstance();
        transcoderFactory.setCopyCollectionsForSerialization( _copyCollectionsForSerialization );
        if ( _customConverterClassNames != null ) {
            _log.info( "Found configured custom converter classes, setting on transcoder factory: " + _customConverterClassNames );
            transcoderFactory.setCustomConverterClassNames( _customConverterClassNames.split( ",\\s*" ) );
        }
        return transcoderFactory;
    }

    private Class<? extends TranscoderFactory> loadTranscoderFactoryClass() throws ClassNotFoundException {
        Class<? extends TranscoderFactory> transcoderFactoryClass;
        final ClassLoader classLoader = getContainer().getLoader().getClassLoader();
        try {
            _log.debug( "Loading transcoder factory class " + _transcoderFactoryClassName + " using classloader " + classLoader );
            transcoderFactoryClass = Class.forName( _transcoderFactoryClassName, false, classLoader ).asSubclass( TranscoderFactory.class );
        } catch ( final ClassNotFoundException e ) {
            _log.info( "Could not load transcoderfactory class with classloader "+ classLoader +", trying " + getClass().getClassLoader() );
            transcoderFactoryClass = Class.forName( _transcoderFactoryClassName, false, getClass().getClassLoader() ).asSubclass( TranscoderFactory.class );
        }
        return transcoderFactoryClass;
    }

    protected NodeAvailabilityCache<String> createNodeAvailabilityCache( final int size, final long ttlInMillis,
            final MemcachedClient memcachedClient ) {
        return new NodeAvailabilityCache<String>( size, ttlInMillis, new CacheLoader<String>() {

            public boolean isNodeAvailable( final String key ) {
                try {
                    memcachedClient.get( _sessionIdFormat.createSessionId( "ping", key ) );
                    return true;
                } catch ( final Exception e ) {
                    return false;
                }
            }

        } );
    }

    private List<String> initFailoverNodes( final String failoverNodes, final List<String> nodeIds ) {
        final List<String> failoverNodeIds = new ArrayList<String>();
        if ( failoverNodes != null && failoverNodes.trim().length() != 0 ) {
            final String[] failoverNodesArray = failoverNodes.split( " |," );
            for ( final String failoverNode : failoverNodesArray ) {
                final String nodeId = failoverNode.trim();
                if ( !nodeIds.remove( nodeId ) ) {
                    throw new IllegalArgumentException( "Invalid failover node id " + nodeId + ": "
                            + "not existing in memcachedNodes '" + nodeIds + "'." );
                }
                failoverNodeIds.add( nodeId );
            }
        }
        return failoverNodeIds;
    }

    private void initHandleNodeDefinitionMatch( final Matcher matcher, final List<InetSocketAddress> addresses,
            final Map<InetSocketAddress, String> address2Ids, final List<String> nodeIds ) {
        final String nodeId = matcher.group( 1 );
        nodeIds.add( nodeId );

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
        return _sessionIdFormat.createSessionId( super.generateSessionId(), _nodeIdService.getMemcachedNodeId() );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void expireSession( final String sessionId ) {
        if ( _log.isDebugEnabled() ) {
            _log.debug( "expireSession invoked: " + sessionId );
        }
        super.expireSession( sessionId );
        deleteFromMemcached( sessionId );
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
        MemcachedBackupSession result = (MemcachedBackupSession) super.findSession( id );
        if ( result == null && canHitMemcached( id ) ) {
            // when the request comes from the container, it's from CoyoteAdapter.postParseRequest
            if ( !_sticky && _lockingStrategy.isContainerSessionLookup() ) {
                // we can return just null as the requestedSessionId will still be set on
                // the request.
                return null;
            }

            // else load the session from memcached
            result = loadFromMemcached( id );
            // checking valid() would expire() the session if it's not valid!
            if ( result != null && result.isValid() ) {
                addValidLoadedSession( result );
            }
        }
        return result;
    }

    private void addValidLoadedSession( final StandardSession session ) {
        // make sure the listeners know about it. (as done by PersistentManagerBase)
        session.tellNew();
        add( session );
        session.activate();
        // endAccess() to ensure timeouts happen correctly.
        // access() to keep access count correct or it will end up
        // negative
        session.access();
        session.endAccess();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Session createSession( String sessionId ) {
        if ( _log.isDebugEnabled() ) {
            _log.debug( "createSession invoked: " + sessionId );
        }

        checkMaxActiveSessions();

        StandardSession session = null;

        if ( sessionId != null ) {
            session = loadFromMemcachedWithCheck( sessionId );
            // checking valid() would expire() the session if it's not valid!
            if ( session != null && session.isValid() ) {
                addValidLoadedSession( session );
            }
        }

        if ( session == null ) {

            session = createEmptySession();
            session.setNew( true );
            session.setValid( true );
            session.setCreationTime( System.currentTimeMillis() );
            session.setMaxInactiveInterval( this.maxInactiveInterval );

            if ( sessionId == null || !isNodeAvailableForSessionId( sessionId ) ) {
                sessionId = generateSessionId();
            }

            session.setId( sessionId );

            if ( _log.isDebugEnabled() ) {
                _log.debug( "Created new session with id " + session.getId() );
            }

        }

        sessionCounter++;

        return session;

    }

    private void checkMaxActiveSessions() {
        if ( _maxActiveSessions >= 0 && sessions.size() >= _maxActiveSessions ) {
            _rejectedSessions++;
            throw new IllegalStateException
                (sm.getString("standardManager.createSession.ise"));
        }
    }

    private boolean isNodeAvailableForSessionId( final String sessionId ) {
        final String nodeId = _sessionIdFormat.extractMemcachedId( sessionId );
        return nodeId != null && _nodeIdService.isNodeAvailable( nodeId );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MemcachedBackupSession createEmptySession() {
        final MemcachedBackupSession result = new MemcachedBackupSession( this );
        result.setSticky( _sticky );
        return result;
    }

    @Override
    public void changeSessionId( final Session session ) {
        // e.g. invoked by the AuthenticatorBase (for BASIC auth) on login to prevent session fixation
        // so that session backup won't be omitted we must store this event
        super.changeSessionId( session );
        ((MemcachedBackupSession)session).setSessionIdChanged( true );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String changeSessionIdOnTomcatFailover( final String requestedSessionId ) {
        if ( !_sticky ) {
            return null;
        }
        final String localJvmRoute = getJvmRoute();
        if ( localJvmRoute != null && !localJvmRoute.equals( _sessionIdFormat.extractJvmRoute( requestedSessionId ) ) ) {
            final MemcachedBackupSession session = loadFromMemcachedWithCheck( requestedSessionId );
            // checking valid() can expire() the session!
            if ( session != null && session.isValid() ) {
                return handleSessionTakeOver( session );
            }
        }
        return null;
    }

    private String handleSessionTakeOver( final MemcachedBackupSession session ) {

        checkMaxActiveSessions();

        final String origSessionId = session.getIdInternal();

        final String newSessionId = _sessionIdFormat.changeJvmRoute( session.getIdInternal(), getJvmRoute() );
        session.setIdInternal( newSessionId );

        addValidLoadedSession( session );

        deleteFromMemcached( origSessionId );

        _statistics.requestWithTomcatFailover();

        return newSessionId;

    }

    protected void deleteFromMemcached(final String sessionId) {
        if ( _sessionIdFormat.isValid( sessionId ) ) {
            if ( _log.isDebugEnabled() ) {
                _log.debug( "Deleting session from memcached: " + sessionId );
            }
            try {
                _memcached.delete( sessionId );
                if ( !_sticky ) {
                    _lockingStrategy.onAfterDeleteFromMemcached( sessionId );
                }
            } catch ( final Throwable e ) {
                _log.info( "Could not delete session from memcached.", e );
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String changeSessionIdOnMemcachedFailover( final String requestedSessionId ) {

        try {
            if ( _sticky ) {
                /* We can just lookup the session in the local session map, as we wouldn't get
                 * the session from memcached if the node was not available - or, the other way round,
                 * if we would get the session from memcached, the session would not have to be relocated.
                 */
                final MemcachedBackupSession session = (MemcachedBackupSession) super.findSession( requestedSessionId );

                if ( session != null && session.isValid() ) {
                    final String nodeId = _sessionIdFormat.extractMemcachedId( session.getId() );
                    final String newNodeId = getNewNodeIdIfUnavailable( nodeId );
                    if ( newNodeId != null ) {
                        final String newSessionId = _sessionIdFormat.createNewSessionId( session.getId(), newNodeId );
                        _log.debug( "Session needs to be relocated, setting new id on session..." );
                        session.setIdForRelocate( newSessionId );
                        _statistics.requestWithMemcachedFailover();
                        return newSessionId;
                    }
                }
            }
            else {

                /* for non-sticky sessions we check the validity info
                 */
                final String nodeId = _sessionIdFormat.extractMemcachedId( requestedSessionId );
                if ( nodeId == null || _nodeIdService.isNodeAvailable( nodeId ) ) {
                    return null;
                }

                final MemcachedBackupSession backupSession = loadBackupSession( requestedSessionId, nodeId );
                if ( backupSession != null ) {
                    addValidLoadedSession( backupSession );
                    _statistics.requestWithMemcachedFailover();
                    return backupSession.getId();
                }
            }

        } catch ( final IOException e ) {
            _log.warn( "Could not find session in local session map.", e );
        }
        return null;
    }

    @CheckForNull
    private MemcachedBackupSession loadBackupSession( @Nonnull final String requestedSessionId, @Nonnull final String nodeId ) {
        /* check the node that holds the backup of the session
         */
        final String nextNodeId = _nodeIdService.getNextNodeId( nodeId );
        if ( !_nodeIdService.isNodeAvailable( nextNodeId ) ) {
            _log.info( "Node "+ nodeId +" that stores the backup of the session "+ requestedSessionId +" is not available." );
            return null;
        }

        try {
            final SessionValidityInfo validityInfo = _lockingStrategy.loadBackupSessionValidityInfo( requestedSessionId );
            if ( validityInfo == null || !validityInfo.isValid() ) {
                _log.info( "No validity info (or no valid one) found for sessionId " + requestedSessionId );
                return null;
            }

            final Object obj = _memcached.get( _sessionIdFormat.createBackupKey( requestedSessionId ) );
            if ( obj == null ) {
                _log.info( "No backup found for sessionId " + requestedSessionId );
                return null;
            }

            final MemcachedBackupSession session = _transcoderService.deserialize( (byte[]) obj, this );
            session.setLastAccessedTimeInternal( validityInfo.getLastAccessedTime() );
            session.setThisAccessedTimeInternal( validityInfo.getThisAccessedTime() );

            _log.debug( "Session needs to be relocated, setting new id on session..." );
            final String newSessionId = _sessionIdFormat.createNewSessionId( requestedSessionId, nextNodeId );
            session.setIdInternal( newSessionId );
            return session;

        } catch( final Exception e ) {
            _log.error( "Could not get backup validityInfo or backup session for sessionId " + requestedSessionId, e );
        }
        return null;
    }

    /**
     * Returns a new node id if the given one is <code>null</code> or not available.
     * @param nodeId the node id that is checked for availability (if not <code>null</code>).
     * @return a new node id if the given one is <code>null</code> or not available, otherwise <code>null</code>.
     */
    private String getNewNodeIdIfUnavailable( final String nodeId ) {
        final String newNodeId;
        if ( nodeId == null ) {
            newNodeId = _nodeIdService.getMemcachedNodeId();
        }
        else {
            if ( !_nodeIdService.isNodeAvailable( nodeId ) ) {
                newNodeId = _nodeIdService.getAvailableNodeId( nodeId );
                if ( newNodeId == null ) {
                    _log.warn( "The node " + nodeId + " is not available and there's no node for relocation left." );
                }
            }
            else {
                newNodeId = null;
            }
        }
        return newNodeId;
    }

    /**
     * Store the provided session in memcached if the session was modified
     * or if the session needs to be relocated.
     *
     * @param session
     *            the session to save
     * @param sessionRelocationRequired
     *            specifies, if the session id was changed due to a memcached failover or tomcat failover.
     * @param requestId
     *            the uri/id of the request for that the session backup shall be performed, used for readonly tracking.
     * @return the {@link SessionTrackerValve.SessionBackupService.BackupResultStatus}
     */
    public Future<BackupResult> backupSession( final Session session, final boolean sessionIdChanged, final String requestId ) {
        if ( !_enabled.get() ) {
            return new SimpleFuture<BackupResult>( BackupResult.SKIPPED );
        }

        final MemcachedBackupSession msmSession = (MemcachedBackupSession) session;

        final boolean force = sessionIdChanged || msmSession.isSessionIdChanged() || !_sticky && (msmSession.getSecondsSinceLastBackup() >= session.getMaxInactiveInterval());
        final Future<BackupResult> result = _backupSessionService.backupSession( msmSession, force );
        if ( !_sticky ) {
            removeInternal( session, false, false );
            _lockingStrategy.onAfterBackupSession( msmSession, force, result, requestId, _backupSessionService );
        }
        return result;
    }

    protected MemcachedBackupSession loadFromMemcachedWithCheck( final String sessionId ) {
        if ( !canHitMemcached( sessionId ) ) {
            return null;
        }
        return loadFromMemcached( sessionId );
    }

    /**
     * Checks if this manager {@link #isEnabled()}, if the given sessionId is valid (contains a memcached id)
     * and if this sessionId is not in our missingSessionsCache.
     */
    private boolean canHitMemcached( @Nonnull final String sessionId ) {
        return _enabled.get() && _sessionIdFormat.isValid( sessionId ) && _missingSessionsCache.get( sessionId ) == null;
    }

    /**
     * Assumes that before you checked {@link #canHitMemcached(String)}.
     */
    private MemcachedBackupSession loadFromMemcached( final String sessionId ) {
        final String nodeId = _sessionIdFormat.extractMemcachedId( sessionId );
        if ( !_nodeIdService.isNodeAvailable( nodeId ) ) {
            _log.debug( "Asked for session " + sessionId + ", but the related"
                    + " memcached node is still marked as unavailable (won't load from memcached)." );
        } else {
            if ( _log.isDebugEnabled() ) {
                _log.debug( "Loading session from memcached: " + sessionId );
            }

            LockStatus lockStatus = null;
            try {

                if ( !_sticky ) {
                    lockStatus = _lockingStrategy.onBeforeLoadFromMemcached( sessionId );
                }

                final long start = System.currentTimeMillis();

                /* In the previous version (<1.2) the session was completely serialized by
                 * custom Transcoder implementations.
                 * Such sessions have set the SERIALIZED flag (from SerializingTranscoder) so that
                 * they get deserialized by BaseSerializingTranscoder.deserialize or the appropriate
                 * specializations.
                 */
                final Object object = _memcached.get( sessionId, _upgradeSupportTranscoder );
                _nodeIdService.setNodeAvailable( nodeId, true );

                if ( object != null ) {
                    final MemcachedBackupSession result;
                    if ( object instanceof MemcachedBackupSession ) {
                        result = (MemcachedBackupSession) object;
                    }
                    else {
                        result = _transcoderService.deserialize( (byte[]) object, this );
                    }
                    if ( !_sticky ) {
                        _lockingStrategy.onAfterLoadFromMemcached( result, lockStatus );
                    }

                    _statistics.getLoadFromMemcachedProbe().registerSince( start );
                    if ( _log.isDebugEnabled() ) {
                        _log.debug( "Found session with id " + sessionId );
                    }
                    return result;
                }
                else {
                    if ( lockStatus == LockStatus.LOCKED ) {
                        _lockingStrategy.releaseLock( sessionId );
                    }
                    _missingSessionsCache.put( sessionId, Boolean.TRUE );
                    if ( _log.isDebugEnabled() ) {
                        _log.debug( "Session " + sessionId + " not found in memcached." );
                    }
                    return null;
                }

            } catch ( final NodeFailureException e ) {
                _log.warn( "Could not load session with id " + sessionId + " from memcached." );
                _nodeIdService.setNodeAvailable( nodeId, false );
            } catch ( final Exception e ) {
                _log.warn( "Could not load session with id " + sessionId + " from memcached.", e );
                if ( lockStatus == LockStatus.LOCKED ) {
                    _lockingStrategy.releaseLock( sessionId );
                }
            }
        }
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void remove( final Session session, final boolean update ) {
        removeInternal( session, update, session.getNote( NODE_FAILURE ) != Boolean.TRUE );
    }

    private void removeInternal( final Session session, final boolean update, final boolean removeFromMemcached ) {
        if ( _log.isDebugEnabled() ) {
            _log.debug( "remove invoked, removeFromMemcached: " + removeFromMemcached +
                    ", id: " + session.getId() );
        }
        if ( removeFromMemcached ) {
            deleteFromMemcached( session.getId() );
        }
        super.remove( session, update );
    }

    /**
     * Set the maximum number of active Sessions allowed, or -1 for no limit.
     *
     * @param max
     *            The new maximum number of sessions
     */
    public void setMaxActiveSessions( final int max ) {
        final int oldMaxActiveSessions = _maxActiveSessions;
        _maxActiveSessions = max;
        support.firePropertyChange( "maxActiveSessions",
                Integer.valueOf( oldMaxActiveSessions ),
                Integer.valueOf( _maxActiveSessions ) );
    }

    /**
     * {@inheritDoc}
     */
    public int getRejectedSessions() {
        return _rejectedSessions;
    }

    /**
     * {@inheritDoc}
     */
    public void load() throws ClassNotFoundException, IOException {
    }

    /**
     * {@inheritDoc}
     */
    public void setRejectedSessions( final int rejectedSessions ) {
        _rejectedSessions = rejectedSessions;
    }

    /**
     * {@inheritDoc}
     */
    public void unload() throws IOException {
    }

    /**
     * Set the memcached nodes space or comma separated.
     * <p>
     * E.g. <code>n1.localhost:11211 n2.localhost:11212</code>
     * </p>
     * <p>
     * When the memcached nodes are set when this manager is already initialized,
     * the new configuration will be loaded.
     * </p>
     *
     * @param memcachedNodes
     *            the memcached node definitions, whitespace or comma separated
     */
    public void setMemcachedNodes( final String memcachedNodes ) {

            final MemcachedConfig config = reloadMemcachedConfig( memcachedNodes, _failoverNodes );
            _log.info( "Loaded new memcached node configuration." +
                    "\n- Former config: "+ _memcachedNodes +
                    "\n- New config: " + config.getMemcachedNodes() +
                    "\n- New node ids: " + config.getNodeIds() +
                    "\n- New failover node ids: " + config.getFailoverNodeIds() );

        _memcachedNodes = memcachedNodes;
    }

    /**
     * The memcached nodes configuration as provided in the server.xml/context.xml.
     * <p>
     * This getter is there to make this configuration accessible via jmx.
     * </p>
     * @return the configuration string for the memcached nodes.
     */
    public String getMemcachedNodes() {
        return _memcachedNodes;
    }

    private MemcachedConfig reloadMemcachedConfig( final String memcachedNodes, final String failoverNodes ) {

        /* first create all dependent services
         */
        final MemcachedConfig config = createMemcachedConfig( memcachedNodes, failoverNodes );
        final MemcachedClient memcachedClient = createMemcachedClient( config.getNodeIds(), config.getAddresses(),
                config.getAddress2Ids(), _statistics );
        final NodeIdService nodeIdService = new NodeIdService(
                createNodeAvailabilityCache( config.getCountNodes(), NODE_AVAILABILITY_CACHE_TTL, memcachedClient ),
                config.getNodeIds(), config.getFailoverNodeIds() );
        final BackupSessionService backupSessionService = new BackupSessionService( _transcoderService, _sessionBackupAsync,
                _sessionBackupTimeout, _backupThreadCount, memcachedClient, nodeIdService, _statistics, _sticky );

        /* then assign new services
         */
        if ( _memcached != null ) {
            _memcached.shutdown();
        }
        _memcached = memcachedClient;
        _nodeIdService = nodeIdService;
        _backupSessionService = backupSessionService;

        initNonStickyLockingMode();

        return config;
    }

    /**
     * The node ids of memcached nodes, that shall only be used for session
     * backup by this tomcat/manager, if there are no other memcached nodes
     * left. Node ids are separated by whitespace or comma.
     * <p>
     * E.g. <code>n1 n2</code>
     * </p>
     * <p>
     * When the failover nodes are set when this manager is already initialized,
     * the new configuration will be loaded.
     * </p>
     *
     * @param failoverNodes
     *            the failoverNodes to set, whitespace or comma separated
     */
    public void setFailoverNodes( final String failoverNodes ) {
        final MemcachedConfig config = reloadMemcachedConfig( _memcachedNodes, failoverNodes );
        _log.info( "Loaded new memcached failover node configuration." +
                "\n- Former failover config: "+ _failoverNodes +
                "\n- New failover config: " + config.getFailoverNodes() +
                "\n- New node ids: " + config.getNodeIds() +
                "\n- New failover node ids: " + config.getFailoverNodeIds() );

        _failoverNodes = failoverNodes;
    }

    /**
     * The memcached failover nodes configuration as provided in the server.xml/context.xml.
     * <p>
     * This getter is there to make this configuration accessible via jmx.
     * </p>
     * @return the configuration string for the failover nodes.
     */
    public String getFailoverNodes() {
        return _failoverNodes;
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

    /**
     * The class of the factory that creates the
     * {@link net.spy.memcached.transcoders.Transcoder} to use for serializing/deserializing
     * sessions to/from memcached (requires a default/no-args constructor).
     * The default value is the {@link JavaSerializationTranscoderFactory} class
     * (used if this configuration attribute is not specified).
     * <p>
     * After the {@link TranscoderFactory} instance was created from the specified class,
     * {@link TranscoderFactory#setCopyCollectionsForSerialization(boolean)}
     * will be invoked with the currently set <code>copyCollectionsForSerialization</code> propery, which
     * has either still the default value (<code>false</code>) or the value provided via
     * {@link #setCopyCollectionsForSerialization(boolean)}.
     * </p>
     *
     * @param transcoderFactoryClassName the {@link TranscoderFactory} class name.
     */
    public void setTranscoderFactoryClass( final String transcoderFactoryClassName ) {
        _transcoderFactoryClassName = transcoderFactoryClassName;
    }

    /**
     * Specifies, if iterating over collection elements shall be done on a copy
     * of the collection or on the collection itself. The default value is <code>false</code>
     * (used if this configuration attribute is not specified).
     * <p>
     * This option can be useful if you have multiple requests running in
     * parallel for the same session (e.g. AJAX) and you are using
     * non-thread-safe collections (e.g. {@link java.util.ArrayList} or
     * {@link java.util.HashMap}). In this case, your application might modify a
     * collection while it's being serialized for backup in memcached.
     * </p>
     * <p>
     * <strong>Note:</strong> This must be supported by the {@link TranscoderFactory}
     * specified via {@link #setTranscoderFactoryClass(String)}: after the {@link TranscoderFactory} instance
     * was created from the specified class, {@link TranscoderFactory#setCopyCollectionsForSerialization(boolean)}
     * will be invoked with the provided <code>copyCollectionsForSerialization</code> value.
     * </p>
     *
     * @param copyCollectionsForSerialization
     *            <code>true</code>, if iterating over collection elements shall be done
     *            on a copy of the collection, <code>false</code> if the collections own iterator
     *            shall be used.
     */
    public void setCopyCollectionsForSerialization( final boolean copyCollectionsForSerialization ) {
        _copyCollectionsForSerialization = copyCollectionsForSerialization;
    }

    /**
     * Custom converter allow you to provide custom serialization of application specific
     * types. Multiple converter classes are separated by comma (with optional space following the comma).
     * <p>
     * This option is useful if reflection based serialization is very verbose and you want
     * to provide a more efficient serialization for a specific type.
     * </p>
     * <p>
     * <strong>Note:</strong> This must be supported by the {@link TranscoderFactory}
     * specified via {@link #setTranscoderFactoryClass(String)}: after the {@link TranscoderFactory} instance
     * was created from the specified class, {@link TranscoderFactory#setCustomConverterClassNames(String[])}
     * is invoked with the provided custom converter class names.
     * </p>
     * <p>Requirements regarding the specific custom converter classes depend on the
     * actual serialization strategy, but a common requirement would be that they must
     * provide a default/no-args constructor.<br/>
     * For more details have a look at
     * <a href="http://code.google.com/p/memcached-session-manager/wiki/SerializationStrategies">SerializationStrategies</a>.
     * </p>
     *
     * @param customConverterClassNames a list of class names separated by comma
     */
    public void setCustomConverter( final String customConverterClassNames ) {
        _customConverterClassNames = customConverterClassNames;
    }

    /**
     * Specifies if statistics (like number of requests with/without session) shall be
     * gathered. Default value of this property is <code>true</code>.
     * <p>
     * Statistics will be available via jmx and the Manager mbean (
     * e.g. in the jconsole mbean tab open the attributes node of the
     * <em>Catalina/Manager/&lt;context-path&gt;/&lt;host name&gt;</em>
     * mbean and check for <em>msmStat*</em> values.
     * </p>
     *
     * @param enableStatistics <code>true</code> if statistics shall be gathered.
     */
    public void setEnableStatistics( final boolean enableStatistics ) {
        _enableStatistics = enableStatistics;
    }

    /**
     * Specifies the number of threads that are used if {@link #setSessionBackupAsync(boolean)}
     * is set to <code>true</code>.
     *
     * @param backupThreadCount the number of threads to use for session backup.
     */
    public void setBackupThreadCount( final int backupThreadCount ) {
        final int oldBackupThreadCount = _backupThreadCount;
        _backupThreadCount = backupThreadCount;

        _log.info( "Changed backupThreadCount from " + oldBackupThreadCount + " to " + _backupThreadCount + "." +
                " Reloading configuration..." );
        reloadMemcachedConfig( _memcachedNodes, _failoverNodes );
        _log.info( "Finished reloading configuration." );
    }

    /**
     * The number of threads to use for session backup if session backup shall be
     * done asynchronously.
     * @return the number of threads for session backup.
     */
    public int getBackupThreadCount() {
        return _backupThreadCount;
    }

    /**
     * Specifies the memcached protocol to use, either "text" (default) or "binary".
     *
     * @param memcachedProtocol one of "text" or "binary".
     */
    public void setMemcachedProtocol( final String memcachedProtocol ) {
        if ( !PROTOCOL_TEXT.equals( memcachedProtocol )
                && !PROTOCOL_BINARY.equals( memcachedProtocol ) ) {
            _log.warn( "Illegal memcachedProtocol " + memcachedProtocol + ", using default (" + _memcachedProtocol + ")." );
            return;
        }
        _memcachedProtocol = memcachedProtocol;
    }

    /**
     * Enable/disable memcached-session-manager (default <code>true</code> / enabled).
     * If disabled, sessions are neither looked up in memcached nor stored in memcached.
     *
     * @param enabled specifies if msm shall be disabled or not.
     * @throws IllegalStateException it's not allowed to disable this session manager when running in non-sticky mode.
     */
    public void setEnabled( final boolean enabled ) throws IllegalStateException {
        if ( !enabled && !_sticky ) {
            throw new IllegalStateException( "Disabling this session manager is not allowed in non-sticky mode. You must switch to sticky operation mode before." );
        }
        if ( _enabled.compareAndSet( !enabled, enabled ) ) {
            reloadMemcachedConfig( _memcachedNodes, _failoverNodes );
            _log.info( "Changed enabled status to " + enabled + "." );
        }
    }

    /**
     * Specifies, if msm is enabled or not.
     *
     * @return <code>true</code> if enabled, otherwise <code>false</code>.
     */
    public boolean isEnabled() {
        return _enabled.get();
    }

    public void setSticky( final boolean sticky ) {
        if ( sticky == _sticky ) {
            return;
        }
        if ( !sticky && getJvmRoute() != null ) {
            _log.warn( "Setting sticky to false while there's still a jvmRoute configured (" + getJvmRoute() + "), this might cause trouble." +
            		" You should remve the jvmRoute configuration for non-sticky mode." );
        }
        _sticky = sticky;
        if ( isInitialized() ) {
            _log.info( "Changed sticky to " + _sticky + ". Reloading configuration..." );
            reloadMemcachedConfig( _memcachedNodes, _failoverNodes );
            _log.info( "Finished reloading configuration." );
        }
    }

    private boolean isInitialized() {
        return getState() == LifecycleState.INITIALIZED || getState() == LifecycleState.STARTED;
    }

    protected void setStickyInternal( final boolean sticky ) {
        _sticky = sticky;
    }

    public boolean isSticky() {
        return _sticky;
    }

    /**
     * Sets the session locking mode. Possible values:
     * <ul>
     * <li><code>none</code> - does not lock the session at all (default for non-sticky sessions).</li>
     * <li><code>all</code> - the session is locked for each request accessing the session.</li>
     * <li><code>auto</code> - locks the session for each request except for those the were detected to access the session only readonly.</li>
     * <li><code>uriPattern:&lt;regexp&gt;</code> - locks the session for each request with a request uri (with appended querystring) matching
     * the provided regular expression.</li>
     * </ul>
     */
    public void setLockingMode( final String lockingMode ) {
        if ( lockingMode == null && _lockingMode == null
                || lockingMode.equals( _lockingMode ) ) {
            return;
        }
        _lockingMode = lockingMode;
        if ( isInitialized() ) {
            initNonStickyLockingMode();
        }
    }

    private void initNonStickyLockingMode() {
        if ( _sticky ) {
            setLockingMode( null, null );
            return;
        }

        Pattern uriPattern = null;
        LockingMode lockingMode = null;
        if ( _lockingMode != null ) {
            if ( _lockingMode.startsWith( "uriPattern:" ) ) {
                lockingMode = LockingMode.URI_PATTERN;
                uriPattern = Pattern.compile( _lockingMode.substring( "uriPattern:".length() ) );
            }
            else {
                lockingMode = LockingMode.valueOf( _lockingMode.toUpperCase() );
            }
        }
        if ( lockingMode == null ) {
            lockingMode = LockingMode.NONE;
        }
        setLockingMode( lockingMode, uriPattern );
    }

    public void setLockingMode( @Nonnull final LockingMode lockingMode, @Nullable final Pattern uriPattern ) {
        _log.info( "Setting lockingMode to " + lockingMode + ( uriPattern != null ? " with pattern " + uriPattern.pattern() : "" ) );
        _lockingStrategy = LockingStrategy.create( lockingMode, uriPattern, _memcached, this, _missingSessionsCache );
        if ( _sessionTrackerValve != null ) {
            _sessionTrackerValve.setLockingStrategy( _lockingStrategy );
        }
    }

    /**
     * {@inheritDoc}
     */
    public void addLifecycleListener( final LifecycleListener arg0 ) {
        _lifecycle.addLifecycleListener( arg0 );
    }

    /**
     * {@inheritDoc}
     */
    public LifecycleListener[] findLifecycleListeners() {
        return _lifecycle.findLifecycleListeners();
    }

    /**
     * {@inheritDoc}
     */
    public void removeLifecycleListener( final LifecycleListener arg0 ) {
        _lifecycle.removeLifecycleListener( arg0 );
    }

    /**
     * {@inheritDoc}
     */
    public void startInternal() throws LifecycleException {
        super.startInternal();
    	setState(LifecycleState.STARTING);
    }

    /**
     * {@inheritDoc}
     */
    public void stopInternal() throws LifecycleException {
    	setState(LifecycleState.STOPPING);

        try {
            _backupSessionService.shutdown();
        } catch ( final InterruptedException e ) {
            _log.info( "Got interrupted during backupSessionService shutdown," +
                    " continuing to shutdown memcached client and to destroy myself...", e );
        }
        if ( _memcached != null ) {
            _memcached.shutdown();
        }

        super.stopInternal();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void backgroundProcess() {
        updateExpirationInMemcached();
        super.backgroundProcess();
    }

    protected void updateExpirationInMemcached() {
        if ( _enabled.get() && _sticky ) {
            final Session[] sessions = findSessions();
            final int delay = getContainer().getBackgroundProcessorDelay();
            for ( final Session s : sessions ) {
                final MemcachedBackupSession session = (MemcachedBackupSession) s;
                if ( _log.isDebugEnabled() ) {
                    _log.debug( "Checking session " + session.getId() + ": " +
                            "\n- isValid: " + session.isValidInternal() +
                            "\n- isExpiring: " + session.isExpiring() +
                            "\n- isBackupRunning: " + session.isBackupRunning() +
                            "\n- isExpirationUpdateRunning: " + session.isExpirationUpdateRunning() +
                            "\n- wasAccessedSinceLastBackup: " + session.wasAccessedSinceLastBackup() +
                            "\n- memcachedExpirationTime: " + session.getMemcachedExpirationTime() );
                }
                if ( session.isValidInternal()
                        && !session.isExpiring()
                        && !session.isBackupRunning()
                        && !session.isExpirationUpdateRunning()
                        && session.wasAccessedSinceLastBackup()
                        && session.getMemcachedExpirationTime() <= 2 * delay ) {
                    try {
                        _backupSessionService.updateExpiration( session );
                    } catch ( final Throwable e ) {
                        _log.info( "Could not update expiration in memcached for session " + session.getId(), e );
                    }
                }
            }
        }
    }

    /**
     * {@inheritDoc}
     */
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
                _log.warn( "standardManager.sessionTimeout: " + event.getNewValue().toString() );
            }
        }

    }

    /**
     * Specifies if the session shall be stored asynchronously in memcached as
     * {@link MemcachedClient#set(String, int, Object)} supports it. If this is
     * false, the timeout set via {@link #setSessionBackupTimeout(int)} is
     * evaluated. If this is <code>true</code>, the {@link #setBackupThreadCount(int)}
     * is evaluated.
     * <p>
     * By default this property is set to <code>true</code> - the session
     * backup is performed asynchronously.
     * </p>
     *
     * @param sessionBackupAsync
     *            the sessionBackupAsync to set
     */
    public void setSessionBackupAsync( final boolean sessionBackupAsync ) {
        final boolean oldSessionBackupAsync = _sessionBackupAsync;
        _sessionBackupAsync = sessionBackupAsync;
        if ( oldSessionBackupAsync != sessionBackupAsync ) {
            _log.info( "SessionBackupAsync was changed to " + sessionBackupAsync + ", creating new BackupSessionService with new configuration." );
            _backupSessionService = new BackupSessionService( _transcoderService, _sessionBackupAsync, _sessionBackupTimeout,
                    _backupThreadCount, _memcached, _nodeIdService, _statistics, _sticky );
        }
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

    // ----------------------- protected getters/setters for testing ------------------

    /**
     * Set the {@link TranscoderService} that is used by this manager and the {@link BackupSessionService}.
     *
     * @param transcoderService the transcoder service to use.
     */
    void setTranscoderService( final TranscoderService transcoderService ) {
        _transcoderService = transcoderService;
        _backupSessionService = new BackupSessionService( transcoderService, _sessionBackupAsync, _sessionBackupTimeout,
                _backupThreadCount, _memcached, _nodeIdService, _statistics, _sticky );
    }

    /**
     * Return the currently configured node ids - just for testing.
     * @return the list of node ids.
     */
    List<String> getNodeIds() {
        return _nodeIdService.getNodeIds();
    }
    /**
     * Return the currently configured failover node ids - just for testing.
     * @return the list of failover node ids.
     */
    List<String> getFailoverNodeIds() {
        return _nodeIdService.getFailoverNodeIds();
    }

    /**
     * The memcached client.
     */
    MemcachedClient getMemcached() {
        return _memcached;
    }

    // -------------------------  statistics via jmx ----------------

    /**
     * @return
     * @see de.javakaffee.web.msm.Statistics#getRequestsWithBackup()
     */
    public long getMsmStatNumBackups() {
        return _statistics.getRequestsWithBackup();
    }

    /**
     * @return
     * @see de.javakaffee.web.msm.Statistics#getRequestsWithBackupFailure()
     */
    public long getMsmStatNumBackupFailures() {
        return _statistics.getRequestsWithBackupFailure();
    }

    /**
     * @return
     * @see de.javakaffee.web.msm.Statistics#getRequestsWithMemcachedFailover()
     */
    public long getMsmStatNumTomcatFailover() {
        return _statistics.getRequestsWithTomcatFailover();
    }

    /**
     * @return
     * @see de.javakaffee.web.msm.Statistics#getRequestsWithMemcachedFailover()
     */
    public long getMsmStatNumMemcachedFailover() {
        return _statistics.getRequestsWithMemcachedFailover();
    }

    /**
     * @return
     * @see de.javakaffee.web.msm.Statistics#getRequestsWithoutSession()
     */
    public long getMsmStatNumRequestsWithoutSession() {
        return _statistics.getRequestsWithoutSession();
    }

    /**
     * @return
     * @see de.javakaffee.web.msm.Statistics#getRequestsWithoutSessionAccess()
     */
    public long getMsmStatNumNoSessionAccess() {
        return _statistics.getRequestsWithoutSessionAccess();
    }

    /**
     * @return
     * @see de.javakaffee.web.msm.Statistics#getRequestsWithoutAttributesAccess()
     */
    public long getMsmStatNumNoAttributesAccess() {
        return _statistics.getRequestsWithoutAttributesAccess();
    }

    /**
     * @return
     * @see de.javakaffee.web.msm.Statistics#getRequestsWithoutSessionModification()
     */
    public long getMsmStatNumNoSessionModification() {
        return _statistics.getRequestsWithoutSessionModification();
    }

    /**
     * @return
     * @see de.javakaffee.web.msm.Statistics#getRequestsWithSession()
     */
    public long getMsmStatNumRequestsWithSession() {
        return _statistics.getRequestsWithSession();
    }

    /**
     * @return
     * @see de.javakaffee.web.msm.Statistics#getSessionsLoadedFromMemcached()
     */
    public long getMsmStatNumSessionsLoadedFromMemcached() {
        return _statistics.getSessionsLoadedFromMemcached();
    }

    /**
     * Returns a string array with labels and values of count, min, avg and max
     * of the time that took the attributes serialization.
     * @return a String array for statistics inspection via jmx.
     */
    public String[] getMsmStatAttributesSerializationInfo() {
        return _statistics.getAttributesSerializationProbe().getInfo();
    }

    /**
     * Returns a string array with labels and values of count, min, avg and max
     * of the time that session backups took in the request thread (including omitted
     * session backups e.g. because the session attributes were not accessed).
     * This time was spent in the request thread.
     *
     * @return a String array for statistics inspection via jmx.
     */
    public String[] getMsmStatEffectiveBackupInfo() {
        return _statistics.getEffectiveBackupProbe().getInfo();
    }

    /**
     * Returns a string array with labels and values of count, min, avg and max
     * of the time that session backups took (excluding backups where a session
     * was relocated). This time was spent in the request thread if session backup
     * is done synchronously, otherwise another thread used this time.
     *
     * @return a String array for statistics inspection via jmx.
     */
    public String[] getMsmStatBackupInfo() {
        return _statistics.getBackupProbe().getInfo();
    }

    /**
     * Returns a string array with labels and values of count, min, avg and max
     * of the time that loading sessions from memcached took (including deserialization).
     * @return a String array for statistics inspection via jmx.
     */
    public String[] getMsmStatSessionsLoadedFromMemcachedInfo() {
        return _statistics.getLoadFromMemcachedProbe().getInfo();
    }

    /**
     * Returns a string array with labels and values of count, min, avg and max
     * of the size of the data that was sent to memcached.
     * @return a String array for statistics inspection via jmx.
     */
    public String[] getMsmStatCachedDataSizeInfo() {
        return _statistics.getCachedDataSizeProbe().getInfo();
    }

    /**
     * Returns a string array with labels and values of count, min, avg and max
     * of the time that storing data in memcached took (excluding serialization,
     * including compression).
     * @return a String array for statistics inspection via jmx.
     */
    public String[] getMsmStatMemcachedUpdateInfo() {
        return _statistics.getMemcachedUpdateProbe().getInfo();
    }

    // ---------------------------------------------------------------------------

    private static class MemcachedConfig {
        private final String _memcachedNodes;
        private final String _failoverNodes;
        private final NodeIdList _nodeIds;
        private final List<String> _failoverNodeIds;
        private final List<InetSocketAddress> _addresses;
        private final Map<InetSocketAddress, String> _address2Ids;
        public MemcachedConfig( final String memcachedNodes, final String failoverNodes,
                final NodeIdList nodeIds, final List<String> failoverNodeIds, final List<InetSocketAddress> addresses,
                final Map<InetSocketAddress, String> address2Ids ) {
            _memcachedNodes = memcachedNodes;
            _failoverNodes = failoverNodes;
            _nodeIds = nodeIds;
            _failoverNodeIds = failoverNodeIds;
            _addresses = addresses;
            _address2Ids = address2Ids;
        }

        /**
         * @return the number of all known memcached nodes.
         */
        public int getCountNodes() {
            return _addresses.size();
        }

        public String getMemcachedNodes() {
            return _memcachedNodes;
        }
        public String getFailoverNodes() {
            return _failoverNodes;
        }
        public NodeIdList getNodeIds() {
            return _nodeIds;
        }
        public List<String> getFailoverNodeIds() {
            return _failoverNodeIds;
        }
        public List<InetSocketAddress> getAddresses() {
            return _addresses;
        }
        public Map<InetSocketAddress, String> getAddress2Ids() {
            return _address2Ids;
        }
    }

}

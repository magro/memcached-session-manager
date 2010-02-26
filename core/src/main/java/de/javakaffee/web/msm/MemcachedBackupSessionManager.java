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
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
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
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

import de.javakaffee.web.msm.NodeAvailabilityCache.CacheLoader;
import de.javakaffee.web.msm.NodeIdResolver.MapBasedResolver;
import de.javakaffee.web.msm.SessionTrackerValve.SessionBackupService;
import de.javakaffee.web.msm.TranscoderService.DeserializationResult;

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

    protected static final String NODE_FAILURE = "node.failure";

    private final Random _random = new Random();

    private final Log _log = LogFactory.getLog( MemcachedBackupSessionManager.class );

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
     * {@link JavaSerializationTranscoderFactory}.
     */
    private Class<? extends TranscoderFactory> _transcoderFactoryClass = JavaSerializationTranscoderFactory.class;

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

    private TranscoderService _transcoderService;

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
        init( null );
    }

    /**
     * Initialize this manager. The memcachedClient parameter is there for testing
     * purposes. If the memcachedClient is provided it's used, otherwise a "real"/new
     * memcached client is created based on the configuration (like {@link #setMemcachedNodes(String)} etc.).
     *
     * @param memcachedClient the memcached client to use, for normal operations this should be <code>null</code>.
     */
    void init( final MemcachedClient memcachedClient ) {
        _log.info( getClass().getSimpleName() + " starts initialization... (configured" +
        		" nodes definition " + _memcachedNodes + ", failover nodes " + _failoverNodes + ")" );

        if ( initialized ) {
            return;
        }

        super.init();

        /* add the valve for tracking requests for that the session must be sent
         * to memcached
         */
        getContainer().getPipeline().addValve( new SessionTrackerValve( _requestUriIgnorePattern, this ) );

        /* init memcached
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

        _memcached = memcachedClient != null ? memcachedClient : createMemcachedClient( addresses, address2Ids );

        /* create the missing sessions cache
         */
        _missingSessionsCache = new LRUCache<String, Boolean>( 200, 500 );
        _nodeAvailabilityCache = createNodeAvailabilityCache( 1000 );

        _transcoderService = createTranscoderService();

        _log.info( getClass().getSimpleName() + " finished initialization, have node ids " + _nodeIds + " and failover node ids " + _failoverNodeIds );

    }

    private TranscoderService createTranscoderService() {
        final TranscoderFactory transcoderFactory;
        try {
            transcoderFactory = createTranscoderFactory();
        } catch ( final Exception e ) {
            throw new RuntimeException( "Could not create transcoder factory.", e );
        }
        return new TranscoderService( transcoderFactory.createTranscoder( this ) );
    }

    private MemcachedClient createMemcachedClient( final List<InetSocketAddress> addresses, final Map<InetSocketAddress, String> address2Ids ) {
        try {
            return new MemcachedClient( new SuffixLocatorConnectionFactory( new MapBasedResolver( address2Ids ), _sessionIdFormat ), addresses );
        } catch ( final Exception e ) {
            throw new RuntimeException( "Could not create memcached client", e );
        }
    }

    private TranscoderFactory createTranscoderFactory() throws InstantiationException, IllegalAccessException {
        log.info( "Starting with transcoder factory " + _transcoderFactoryClass.getName() );
        final TranscoderFactory transcoderFactory = _transcoderFactoryClass.newInstance();
        transcoderFactory.setCopyCollectionsForSerialization( _copyCollectionsForSerialization );
        if ( _customConverterClassNames != null ) {
            _log.info( "Loading custom converter classes " + _customConverterClassNames );
            transcoderFactory.setCustomConverterClassNames( _customConverterClassNames.split( ", " ) );
        }
        return transcoderFactory;
    }

    private NodeAvailabilityCache<String> createNodeAvailabilityCache( final long ttlInMillis ) {
        return new NodeAvailabilityCache<String>( _allNodeIds.size(), ttlInMillis, new CacheLoader<String>() {

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
        _log.debug( "expireSession invoked: " + sessionId );
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
        _log.debug( "createSession invoked: " + sessionId );

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

            if ( _log.isDebugEnabled() ) {
                _log.debug( "Created new session with id " + session.getId() );
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
     * {@inheritDoc}
     */
    @Override
    public String determineSessionIdForBackup( final Session session ) {
        final BackupSessionTask task = getOrCreateBackupSessionTask( (MemcachedBackupSession) session );
        final String sessionNeedsRelocate = task.determineSessionIdForBackup();
        _log.info( "[" +Thread.currentThread().getName() +  "] Returning session id for relocate: " + sessionNeedsRelocate );
        return sessionNeedsRelocate;
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
        return getOrCreateBackupSessionTask( (MemcachedBackupSession) session ).backupSession( session );

    }

    private BackupSessionTask getOrCreateBackupSessionTask( final MemcachedBackupSession session ) {
        if ( session.getBackupTask() == null ) {
            session.setBackupTask( new BackupSessionTask( session, _transcoderService, _sessionBackupAsync, _sessionBackupTimeout,
                    _memcached, _nodeAvailabilityCache, _nodeIds, _failoverNodeIds ) );
        }
        return session.getBackupTask();
    }

    private Session loadFromMemcached( final String sessionId ) {
        if ( !isValidSessionIdFormat( sessionId ) ) {
            return null;
        }
        final String nodeId = _sessionIdFormat.extractMemcachedId( sessionId );
        if ( !_nodeAvailabilityCache.isNodeAvailable( nodeId ) ) {
            _log.debug( "Asked for session " + sessionId + ", but the related"
                    + " memcached node is still marked as unavailable (won't load from memcached)." );
        } else {
            _log.debug( "Loading session from memcached: " + sessionId );
            try {
                final byte[] data = (byte[]) _memcached.get( sessionId );
                if ( _log.isDebugEnabled() ) {
                    if ( data == null ) {
                        _log.debug( "Session " + sessionId + " not found in memcached." );
                    } else {
                        _log.debug( "Found session with id " + sessionId );
                    }
                }
                _nodeAvailabilityCache.setNodeAvailable( nodeId, true );

                final MemcachedBackupSession session;
                if ( data != null ) {
                    final DeserializationResult deserializationResult = TranscoderService.deserializeSessionFields( data );
                    final byte[] attributesData = deserializationResult.getAttributesData();
                    final Map<String, Object> attributes = _transcoderService.deserializeAttributes( attributesData );
                    session = deserializationResult.getSession();
                    session.setAttributesInternal( attributes );
                    session.setDataHashCode( Arrays.hashCode( attributesData ) );
                    session.setManager( this );
                    session.doAfterDeserialization();
                } else {
                    session = null;
                }
                return session;
            } catch ( final NodeFailureException e ) {
                _log.warn( "Could not load session with id " + sessionId + " from memcached." );
                _nodeAvailabilityCache.setNodeAvailable( nodeId, false );
            } catch ( final Exception e ) {
                _log.warn( "Could not load session with id " + sessionId + " from memcached.", e );
            }
        }
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void remove( final Session session ) {
        if ( _log.isDebugEnabled() ) {
            _log.debug( "remove invoked, session.relocate:  " + session.getNote( SessionTrackerValve.RELOCATE ) +
                    ", node failure: " + session.getNote( NODE_FAILURE ) +
                    ", id: " + session.getId() );
        }
        if ( session.getNote( NODE_FAILURE ) != Boolean.TRUE ) {
            try {
                _log.debug( "Deleting session from memcached: " + session.getId() );
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
        try {
            _transcoderFactoryClass = Class.forName( transcoderFactoryClassName ).asSubclass( TranscoderFactory.class );
        } catch ( final ClassNotFoundException e ) {
            _log.error( "The transcoderFactoryClass (" + transcoderFactoryClassName + ") could not be found" );
            throw new RuntimeException( e );
        }
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
    public void start() throws LifecycleException {
        if ( !initialized ) {
            init();
        }
    }

    /**
     * {@inheritDoc}
     */
    public void stop() throws LifecycleException {
        if ( initialized ) {
            _memcached.shutdown();
            destroy();
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

    // ----------------------- protected setters for testing ------------------

    /**
     * Set the {@link TranscoderService} that is used by this manager and the {@link BackupSessionTask}.
     *
     * @param transcoderService the transcoder service to use.
     */
    void setTranscoderService( final TranscoderService transcoderService ) {
        _transcoderService = transcoderService;
    }

    // ---------------------- END setters for testing

    /**
     * The session class used by this manager, to be able to change the session
     * id without the whole notification lifecycle (which includes the
     * application also).
     */
    public static final class MemcachedBackupSession extends StandardSession {

        private static final long serialVersionUID = 1L;

        private volatile transient BackupSessionTask _backupTask;

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
         * Stores the current value of {@link #getThisAccessedTimeInternal()} in a private,
         * transient field. You can check with {@link #wasAccessedSinceLastBackupCheck()}
         * if the current {@link #getThisAccessedTimeInternal()} value is different
         * from the previously stored value to see if the session was accessed in
         * the meantime.
         */
        public void storeThisAccessedTimeFromLastBackupCheck() {
            _thisAccessedTimeFromLastBackupCheck = super.thisAccessedTime;
        }

        /**
         * Determines, if the current value of {@link #getThisAccessedTimeInternal()}
         * differs from the value stored by {@link #storeThisAccessedTimeFromLastBackupCheck()}.
         * This indicates, if the session was accessed in the meantime.
         * @return <code>true</code> if the session was accessed since the invocation
         * of {@link #storeThisAccessedTimeFromLastBackupCheck()}.
         */
        public boolean wasAccessedSinceLastBackupCheck() {
            return _thisAccessedTimeFromLastBackupCheck != super.thisAccessedTime;
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

            /*
             * and mark it as a node-failure-session, so that remove(session) does
             * not try to remove it from memcached... (the session is removed and
             * added when the session id is changed)
             */
            setNote( NODE_FAILURE, Boolean.TRUE );
            manager.remove( this );
            removeNote( NODE_FAILURE );
            this.id = id;
            manager.add( this );

        }

        @Override
        public void setId( final String id ) {
            super.setId( id );
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
         * @return
         */
        public int getDataHashCode() {
            return _dataHashCode;
        }

        /**
         * Set the hash code of the serialized session attributes.
         */
        public void setDataHashCode( final int attributesDataHashCode ) {
            _dataHashCode = attributesDataHashCode;
        }

        protected long getCreationTimeInternal() {
            return super.creationTime;
        }

        protected void setCreationTimeInternal( final long creationTime ) {
            super.creationTime = creationTime;
        }

        protected boolean isNewInternal() {
            return super.isNew;
        }

        protected void setIsNewInternal( final boolean isNew ) {
            super.isNew = isNew;
        }

        protected boolean isValidInternal() {
            return super.isValid;
        }

        protected void setIsValidInternal( final boolean isValid ) {
            super.isValid = isValid;
        }

        /**
         * The timestamp (System.currentTimeMillis) of the last {@link #access()} invocation,
         * this is the timestamp when the application requested the session.
         *
         * @return the timestamp of the last {@link #access()} invocation.
         */
        protected long getThisAccessedTimeInternal() {
            return super.thisAccessedTime;
        }

        protected void setThisAccessedTimeInternal( final long thisAccessedTime ) {
            super.thisAccessedTime = thisAccessedTime;
        }

        protected void setLastAccessedTimeInternal( final long lastAccessedTime ) {
            super.lastAccessedTime = lastAccessedTime;
        }

        protected void setIdInternal( final String id ) {
            super.id = id;
        }

        /**
         * The backup task associated with this session if it was already set
         * via {@link #setBackupTask(BackupSessionTask)}.
         *
         * @return the {@link BackupSessionTask} or <code>null</code>.
         */
        BackupSessionTask getBackupTask() {
            return _backupTask;
        }

        /**
         * Removes the backup task from this session, so that {@link #getBackupTask()} will
         * return <code>null</code>.
         */
        void removeBackupTask() {
            _backupTask = null;
        }

        /**
         * Set the {@link BackupSessionTask} to use for this session.
         *
         * @param backupTask an instance of {@link BackupSessionTask}, never <code>null</code>.
         */
        void setBackupTask( final BackupSessionTask backupTask ) {
            _backupTask = backupTask;
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

    }

}

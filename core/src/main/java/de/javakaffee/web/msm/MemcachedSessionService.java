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


import static de.javakaffee.web.msm.Statistics.StatsType.DELETE_FROM_MEMCACHED;
import static de.javakaffee.web.msm.Statistics.StatsType.LOAD_FROM_MEMCACHED;
import static de.javakaffee.web.msm.Statistics.StatsType.SESSION_DESERIALIZATION;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import net.spy.memcached.BinaryConnectionFactory;
import net.spy.memcached.ConnectionFactory;
import net.spy.memcached.DefaultConnectionFactory;
import net.spy.memcached.MemcachedClient;

import org.apache.catalina.Container;
import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.Manager;
import org.apache.catalina.Session;
import org.apache.catalina.authenticator.Constants;
import org.apache.catalina.deploy.LoginConfig;
import org.apache.catalina.deploy.SecurityConstraint;
import org.apache.catalina.session.StandardSession;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

import de.javakaffee.web.msm.BackupSessionService.SimpleFuture;
import de.javakaffee.web.msm.BackupSessionTask.BackupResult;
import de.javakaffee.web.msm.LockingStrategy.LockingMode;
import de.javakaffee.web.msm.MemcachedNodesManager.MemcachedClientCallback;
import de.javakaffee.web.msm.SessionTrackerValve.SessionBackupService;

/**
 * This is the core of memcached session manager, managing sessions in memcached.
 * A {@link SessionManager} interface represents the dependency to tomcats session manager
 * (which normally keeps sessions in memory). This {@link SessionManager} has to be subclassed
 * for a concrete major tomcat version (e.g. for 7.x.x) and configured in the context.xml
 * as manager (see <a href="http://code.google.com/p/memcached-session-manager/wiki/SetupAndConfiguration">SetupAndConfiguration</a>)
 * for more. The {@link SessionManager} then has to pass configuration settings to this
 * {@link MemcachedSessionService}. Relevant lifecycle methods are {@link #startInternal(MemcachedClient)}
 * and {@link #shutdown()}.
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class MemcachedSessionService implements SessionBackupService {

    static enum LockStatus {
        /**
         * For sticky sessions or readonly requests with non-sticky sessions there's no lock required.
         */
        LOCK_NOT_REQUIRED,
        LOCKED,
        COULD_NOT_AQUIRE_LOCK
    }

    private static final String PROTOCOL_TEXT = "text";
    private static final String PROTOCOL_BINARY = "binary";

    protected static final String NODE_FAILURE = "node.failure";

    protected final Log _log = LogFactory.getLog( getClass() );

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
     * The pattern used for including session attributes to a session-backup,
     *  e.g. <code>^(userName|sessionHistory)$</code>. If not set, all session
     *  attributes will be part of the session-backup.
     */
    private String _sessionAttributeFilter = null;

    /**
     * The compiled pattern used for including session attributes to a session-backup,
     *  e.g. <code>^(userName|sessionHistory)$</code>. If not set, all session
     *  attributes will be part of the session-backup.
     */
    private Pattern _sessionAttributePattern = null;

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

	private MemcachedNodesManager _memcachedNodesManager;

    //private LRUCache<String, String> _relocatedSessions;

    protected TranscoderService _transcoderService;

    private TranscoderFactory _transcoderFactory;

    private BackupSessionService _backupSessionService;

    private boolean _sticky = true;
    private String _lockingMode;
    private LockingStrategy _lockingStrategy;
    private long _operationTimeout = 1000;

    private SessionTrackerValve _sessionTrackerValve;

    private final SessionManager _manager;
	private final MemcachedClientCallback _memcachedClientCallback = createMemcachedClientCallback();
    
    public MemcachedSessionService( final SessionManager manager ) {
        _manager = manager;
    }

    /**
     * Returns the tomcat session manager.
     * @return the session manager
     */
    @Nonnull
    public SessionManager getManager() {
        return _manager;
    }
    
    public static interface SessionManager extends Manager {
        SessionTrackerValve createSessionTrackerValve(
                @Nullable final String requestUriIgnorePattern,
                @Nonnull final Statistics statistics,
                @Nonnull final AtomicBoolean enabled );
        String generateSessionId();
        void expireSession( final String sessionId );
        MemcachedBackupSession getSessionInternal( String sessionId );
        Map<String, Session> getSessionsInternal();
        
        String getJvmRoute();

        /**
          * Get a string from the underlying resource bundle or return
          * null if the String is not found.
          * @param key to desired resource String
          * @return resource String matching <i>key</i> from underlying
          *         bundle or null if not found.
          * @throws IllegalArgumentException if <i>key</i> is null.
         */
        String getString(String key);

        /**
         * Get a string from the underlying resource bundle and format
         * it with the given set of arguments.
         *
         * @param key to desired resource String
         * @param args args for placeholders in the string
         * @return resource String matching <i>key</i> from underlying
         *         bundle or null if not found.
         * @throws IllegalArgumentException if <i>key</i> is null.
         */
        String getString(final String key, final Object... args);
        
        int getMaxActiveSessions();
        void incrementSessionCounter();
        void incrementRejectedSessions();

        /**
         * Remove this Session from the active Sessions for this Manager without
         * removing it from memcached.
         *
         * @param session   Session to be removed
         * @param update    Should the expiration statistics be updated (since tomcat7)
         */
        void removeInternal( final Session session, final boolean update );

        /**
         * Must return the initialized status. Must return <code>true</code> if this manager
         * has already been started.
         * @return the initialized status
         */
        boolean isInitialized();

        @Nonnull
        MemcachedSessionService getMemcachedSessionService();

        /**
         * Return the Container with which this Manager is associated.
         */
        @Override
        @Nonnull
        Container getContainer();
        
        /**
         * Reads the Principal from the given OIS.
         * @param ois the object input stream to read from. Will be closed by the caller.
         * @return the deserialized principal
         * @throws ClassNotFoundException expected to be declared by the implementation.
         * @throws IOException expected to be declared by the implementation.
         */
        @Nonnull
        Principal readPrincipal( @Nonnull ObjectInputStream ois ) throws ClassNotFoundException, IOException;
        
        // --------------------- setters for testing
        /**
         * Sets the sticky mode, must be provided for tests at least.
         * @param sticky the stickyness.
         */
        void setSticky( boolean sticky );
        void setEnabled( boolean b );
        void setOperationTimeout(long operationTimeout);
        
        /**
         * Set the manager checks frequency in seconds.
         * @param processExpiresFrequency the new manager checks frequency
         */
        void setProcessExpiresFrequency( int processExpiresFrequency );
        void setMemcachedNodes( @Nonnull String memcachedNodes );
        void setFailoverNodes( String failoverNodes );
        void setLockingMode( @Nullable final String lockingMode );
        void setLockingMode( @Nullable final LockingMode lockingMode, @Nullable final Pattern uriPattern, final boolean storeSecondaryBackup );
        
        /**
         * Creates a new instance of {@link MemcachedBackupSession} (needed so that it's possible to
         * create specialized {@link MemcachedBackupSession} instances).
         */
        @Nonnull
        MemcachedBackupSession newMemcachedBackupSession();
    }

    public void shutdown() {
        _log.info( "Stopping services." );
        _backupSessionService.shutdown();
        if ( _lockingStrategy != null ) {
            _lockingStrategy.shutdown();
        }
        if ( _memcached != null ) {
            _memcached.shutdown();
        }
    }

    /**
     * Initialize this manager. The memcachedClient parameter is there for testing
     * purposes. If the memcachedClient is provided it's used, otherwise a "real"/new
     * memcached client is created based on the configuration (like {@link #setMemcachedNodes(String)} etc.).
     *
     * @param memcachedClient the memcached client to use, for normal operations this should be <code>null</code>.
     */
    void startInternal( final MemcachedClient memcachedClient ) throws LifecycleException {
        _log.info( getClass().getSimpleName() + " starts initialization... (configured" +
                " nodes definition " + _memcachedNodes + ", failover nodes " + _failoverNodes + ")" );

        _statistics = Statistics.create( _enableStatistics );

        _memcachedNodesManager = createMemcachedNodesManager( _memcachedNodes, _failoverNodes);

        _memcached = memcachedClient != null ? memcachedClient : createMemcachedClient( _memcachedNodesManager, _statistics );

        /* create the missing sessions cache
         */
        _missingSessionsCache = new LRUCache<String, Boolean>( 200, 500 );

        _sessionTrackerValve = _manager.createSessionTrackerValve( _requestUriIgnorePattern,  _statistics, _enabled );
        _manager.getContainer().getPipeline().addValve( _sessionTrackerValve );

        initNonStickyLockingMode( _memcachedNodesManager );

        _transcoderService = createTranscoderService( _statistics );

        _backupSessionService = new BackupSessionService( _transcoderService, _sessionBackupAsync, _sessionBackupTimeout,
                _backupThreadCount, _memcached, _memcachedNodesManager, _statistics );

        _log.info( getClass().getSimpleName() + " finished initialization, sticky "+ _sticky + ", operation timeout " + _operationTimeout +", with node ids " +
        		_memcachedNodesManager.getPrimaryNodeIds() + " and failover node ids " + _memcachedNodesManager.getFailoverNodeIds() );

    }

	protected MemcachedClientCallback createMemcachedClientCallback() {
		return new MemcachedClientCallback() {
			@Override
			public Object get(final String key) {
				return _memcached.get(key);
			}
		};
	}

    protected MemcachedNodesManager createMemcachedNodesManager(final String memcachedNodes, final String failoverNodes) {
		return MemcachedNodesManager.createFor( memcachedNodes, failoverNodes, _memcachedClientCallback );
	}

    private TranscoderService createTranscoderService( final Statistics statistics ) {
        return new TranscoderService( getTranscoderFactory().createTranscoder( _manager ) );
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

    protected MemcachedClient createMemcachedClient( final MemcachedNodesManager memcachedNodesManager,
            final Statistics statistics ) {
        if ( ! _enabled.get() ) {
            return null;
        }
        try {
            final ConnectionFactory connectionFactory = createConnectionFactory( memcachedNodesManager, statistics );
            return new MemcachedClient( connectionFactory, memcachedNodesManager.getAllMemcachedAddresses() );
        } catch ( final Exception e ) {
            throw new RuntimeException( "Could not create memcached client", e );
        }
    }

    private ConnectionFactory createConnectionFactory(final MemcachedNodesManager memcachedNodesManager,
            final Statistics statistics ) {
        if ( PROTOCOL_BINARY.equals( _memcachedProtocol ) ) {
            return memcachedNodesManager.isEncodeNodeIdInSessionId() ? new SuffixLocatorBinaryConnectionFactory( memcachedNodesManager,
            		memcachedNodesManager.getSessionIdFormat(),
            		statistics, _operationTimeout ) : new BinaryConnectionFactory();
        }
        return memcachedNodesManager.isEncodeNodeIdInSessionId()
        		? new SuffixLocatorConnectionFactory( memcachedNodesManager, memcachedNodesManager.getSessionIdFormat(), statistics, _operationTimeout )
        		: new DefaultConnectionFactory();
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
        final ClassLoader classLoader = _manager.getContainer().getLoader().getClassLoader();
        try {
            _log.debug( "Loading transcoder factory class " + _transcoderFactoryClassName + " using classloader " + classLoader );
            transcoderFactoryClass = Class.forName( _transcoderFactoryClassName, false, classLoader ).asSubclass( TranscoderFactory.class );
        } catch ( final ClassNotFoundException e ) {
            _log.info( "Could not load transcoderfactory class with classloader "+ classLoader +", trying " + getClass().getClassLoader() );
            transcoderFactoryClass = Class.forName( _transcoderFactoryClassName, false, getClass().getClassLoader() ).asSubclass( TranscoderFactory.class );
        }
        return transcoderFactoryClass;
    }

    /**
     * {@inheritDoc}
     */
    public String newSessionId( @Nonnull final String sessionId ) {
        return _memcachedNodesManager.createSessionId( sessionId );
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
    public Session findSession( final String id ) throws IOException {
        MemcachedBackupSession result = null;
        
        // if session is locked, don't use internal session cache, instead load from memcached, which will wait until lock expires.
        if (_sticky || _lockingStrategy.equals(LockingStrategy.LockingMode.NONE) || !_memcachedNodesManager.isEncodeNodeIdInSessionId() || !_memcachedNodesManager.canHitMemcached(id) || !_lockingStrategy.isSessionLocked(id)) {        	
        	result = _manager.getSessionInternal( id );
        }
        
        if ( result == null && canHitMemcached( id ) && _missingSessionsCache.get( id ) == null ) {
            // when the request comes from the container, it's from CoyoteAdapter.postParseRequest
            // or AuthenticatorBase.invoke (for some kind of security-constraint, where a form-based
            // constraint needs the session to get the authenticated principal)
            if ( !_sticky && _lockingStrategy.isContainerSessionLookup()
                    && !contextHasFormBasedSecurityConstraint() ) {
                // we can return just null as the requestedSessionId will still be set on
                // the request.
                return null;
            }

            // else load the session from memcached
            result = loadFromMemcached( id );
            // checking valid() would expire() the session if it's not valid!
            if ( result != null && result.isValid() ) {

                // When the sessionId will be changed later in changeSessionIdOnTomcatFailover/handleSessionTakeOver
                // (due to a tomcat failover) we don't want to notify listeners via session.activate for the
                // old sessionId but do that later (in handleSessionTakeOver)
                // See also http://code.google.com/p/memcached-session-manager/issues/detail?id=92
                String jvmRoute;
                final boolean sessionIdWillBeChanged = _sticky && ( jvmRoute = _manager.getJvmRoute() ) != null
                    && !jvmRoute.equals( getSessionIdFormat().extractJvmRoute( id ) );

                final boolean activate = !sessionIdWillBeChanged;
                addValidLoadedSession( result, activate );
            }
        }
        return result;
    }
    
    private boolean contextHasFormBasedSecurityConstraint() {
        final Context context = (Context)_manager.getContainer();
        final SecurityConstraint[] constraints = context.findConstraints();
        final LoginConfig loginConfig = context.getLoginConfig();
        return constraints != null && constraints.length > 0
                && loginConfig != null && Constants.FORM_METHOD.equals( loginConfig.getAuthMethod() );
    }

    private void addValidLoadedSession( final StandardSession session, final boolean activate ) {
        // make sure the listeners know about it. (as done by PersistentManagerBase)
        if ( session.isNew() ) {
            session.tellNew();
        }
        _manager.add( session );
        if ( activate ) {
            session.activate();
        }
        // endAccess() to ensure timeouts happen correctly.
        // access() to keep access count correct or it will end up
        // negative
        session.access();
        session.endAccess();
    }

    /**
     * {@inheritDoc}
     */
    public MemcachedBackupSession createSession( String sessionId ) {
        if ( _log.isDebugEnabled() ) {
            _log.debug( "createSession invoked: " + sessionId );
        }

        checkMaxActiveSessions();

        MemcachedBackupSession session = null;

        if ( sessionId != null ) {
            session = loadFromMemcachedWithCheck( sessionId );
            // checking valid() would expire() the session if it's not valid!
            if ( session != null && session.isValid() ) {
                addValidLoadedSession( session, true );
            }
        }

        if ( session == null ) {

            session = createEmptySession();
            session.setNew( true );
            session.setValid( true );
            session.setCreationTime( System.currentTimeMillis() );
            session.setMaxInactiveInterval( _manager.getMaxInactiveInterval() );

            if ( sessionId == null || !_memcachedNodesManager.canHitMemcached( sessionId ) ) {
                sessionId = _manager.generateSessionId();
            }

            session.setId( sessionId );

            if ( _log.isDebugEnabled() ) {
                _log.debug( "Created new session with id " + session.getId() );
            }

        }

        _manager.incrementSessionCounter();

        return session;

    }

    private void checkMaxActiveSessions() {
        if ( _manager.getMaxActiveSessions() >= 0 && _manager.getSessionsInternal().size() >= _manager.getMaxActiveSessions() ) {
            _manager.incrementRejectedSessions();
            throw new IllegalStateException
                (_manager.getString("standardManager.createSession.ise"));
        }
    }

    /**
     * {@inheritDoc}
     */
    public MemcachedBackupSession createEmptySession() {
        final MemcachedBackupSession result = _manager.newMemcachedBackupSession();
        result.setSticky( _sticky );
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String changeSessionIdOnTomcatFailover( final String requestedSessionId ) {
        if ( !_sticky ) {
            return null;
        }
        final String localJvmRoute = _manager.getJvmRoute();
        if ( localJvmRoute != null && !localJvmRoute.equals( getSessionIdFormat().extractJvmRoute( requestedSessionId ) ) ) {

            // the session might have been loaded already (by some valve), so let's check our session map
            MemcachedBackupSession session = _manager.getSessionInternal( requestedSessionId );
            if ( session == null ) {
                session = loadFromMemcachedWithCheck( requestedSessionId );
            }

            // checking valid() can expire() the session!
            if ( session != null && session.isValid() ) {
                return handleSessionTakeOver( session );
            }
        }
        return null;
    }

    @Nonnull
	private SessionIdFormat getSessionIdFormat() {
		return _memcachedNodesManager.getSessionIdFormat();
	}

    private String handleSessionTakeOver( final MemcachedBackupSession session ) {

        checkMaxActiveSessions();

        final String origSessionId = session.getIdInternal();

        final String newSessionId = getSessionIdFormat().changeJvmRoute( session.getIdInternal(), _manager.getJvmRoute() );

        // If this session was already loaded we need to remove it from the session map
        // See http://code.google.com/p/memcached-session-manager/issues/detail?id=92
        if ( _manager.getSessionsInternal().containsKey( origSessionId ) ) {
            _manager.getSessionsInternal().remove( origSessionId );
        }

        session.setIdInternal( newSessionId );

        addValidLoadedSession( session, true );

        deleteFromMemcached( origSessionId );

        _statistics.requestWithTomcatFailover();

        return newSessionId;

    }

    protected void deleteFromMemcached(final String sessionId) {
        if ( _enabled.get() && _memcachedNodesManager.isValidForMemcached( sessionId ) ) {
            if ( _log.isDebugEnabled() ) {
                _log.debug( "Deleting session from memcached: " + sessionId );
            }
            try {
                final long start = System.currentTimeMillis();
                _memcached.delete( sessionId );
                _statistics.registerSince( DELETE_FROM_MEMCACHED, start );
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
    	
    	if ( !_memcachedNodesManager.isEncodeNodeIdInSessionId() ) {
    		return null;
    	}

        try {
            if ( _sticky ) {
                /* We can just lookup the session in the local session map, as we wouldn't get
                 * the session from memcached if the node was not available - or, the other way round,
                 * if we would get the session from memcached, the session would not have to be relocated.
                 */
                final MemcachedBackupSession session = _manager.getSessionInternal( requestedSessionId );

                if ( session != null && session.isValid() ) {
                	final String newSessionId = _memcachedNodesManager.getNewSessionIdIfNodeFromSessionIdUnavailable( session.getId() );
                    if ( newSessionId != null ) {
                        _log.debug( "Session needs to be relocated, setting new id on session..." );
                        session.setIdForRelocate( newSessionId );
                        _statistics.requestWithMemcachedFailover();
                        return newSessionId;
                    }
                }
            } else {

                /* for non-sticky sessions we check the validity info
                 */
                final String nodeId = getSessionIdFormat().extractMemcachedId( requestedSessionId );
                if ( nodeId == null || _memcachedNodesManager.isNodeAvailable( nodeId ) ) {
                    return null;
                }

                _log.info( "Session needs to be relocated as node "+ nodeId +" is not available, loading backup session for " + requestedSessionId );
                final MemcachedBackupSession backupSession = loadBackupSession( requestedSessionId );
                if ( backupSession != null ) {
                    _log.debug( "Loaded backup session for " + requestedSessionId + ", adding locally with "+ backupSession.getIdInternal() +"." );
                    addValidLoadedSession( backupSession, true );
                    _statistics.requestWithMemcachedFailover();
                    return backupSession.getId();
                }
            }

        } catch ( final RuntimeException e ) {
            _log.warn( "Could not find session in local session map.", e );
        }
        return null;
    }

    @CheckForNull
    private MemcachedBackupSession loadBackupSession( @Nonnull final String requestedSessionId ) {

        final String backupNodeId = getBackupNodeId( requestedSessionId );
        if ( backupNodeId == null ) {
            _log.info( "No backup node found for nodeId "+ getSessionIdFormat().extractMemcachedId( requestedSessionId ) );
            return null;
        }

        if ( !_memcachedNodesManager.isNodeAvailable( backupNodeId ) ) {
            _log.info( "Node "+ backupNodeId +" that stores the backup of the session "+ requestedSessionId +" is not available." );
            return null;
        }

        try {
            final SessionValidityInfo validityInfo = _lockingStrategy.loadBackupSessionValidityInfo( requestedSessionId );
            if ( validityInfo == null || !validityInfo.isValid() ) {
                _log.info( "No validity info (or no valid one) found for sessionId " + requestedSessionId );
                return null;
            }

            final Object obj = _memcached.get( getSessionIdFormat().createBackupKey( requestedSessionId ) );
            if ( obj == null ) {
                _log.info( "No backup found for sessionId " + requestedSessionId );
                return null;
            }

            final MemcachedBackupSession session = _transcoderService.deserialize( (byte[]) obj, _manager );
            session.setSticky( _sticky );
            session.setLastAccessedTimeInternal( validityInfo.getLastAccessedTime() );
            session.setThisAccessedTimeInternal( validityInfo.getThisAccessedTime() );
            final String newSessionId = getSessionIdFormat().createNewSessionId( requestedSessionId, backupNodeId );
            _log.info( "Session backup loaded from secondary memcached for "+ requestedSessionId +" (will be relocated)," +
            		" setting new id "+ newSessionId +" on session..." );
            session.setIdInternal( newSessionId );
            return session;

        } catch( final Exception e ) {
            _log.error( "Could not get backup validityInfo or backup session for sessionId " + requestedSessionId, e );
        }
        return null;
    }

    /**
     * Determines if the (secondary) memcached node used for failover backup of non-sticky sessions is available.
     * @param sessionId the id of the session that shall be stored in another, secondary memcached node.
     * @return <code>true</code> if the backup node is available. If there's no secondary memcached node
     *         (e.g. as there's only a single memcached), <code>false</code> is returned.
     * @see #getBackupNodeId(String)
     */
    boolean isBackupNodeAvailable( @Nonnull final String sessionId ) {
        final String backupNodeId = getBackupNodeId( sessionId );
        return backupNodeId == null ? false : _memcachedNodesManager.isNodeAvailable( backupNodeId );
    }

    /**
     * Determines the id of the (secondary) memcached node that's used for additional backup
     * of non-sticky sessions.
     * @param sessionId the id of the session
     * @return the nodeId, e.g. "n2", or <code>null</code>.
     * @see #isBackupNodeAvailable(String)
     * @see NodeIdService#getNextNodeId(String)
     */
    @CheckForNull
    String getBackupNodeId( @Nonnull final String sessionId ) {
        final String nodeId = getSessionIdFormat().extractMemcachedId( sessionId );
        // primary nodes are actually all nodes for non-sticky sessions, so this is ok.
        // Still, for non-sticky sessions it would make more sense to have just a
        // getNextNodeId()...
        return nodeId == null ? null : _memcachedNodesManager.getNextPrimaryNodeId(nodeId);
    }

    /**
     * Store the provided session in memcached if the session was modified
     * or if the session needs to be relocated.
     *
     * @param sessionId
     *            the id of the session to save
     * @param sessionRelocationRequired
     *            specifies, if the session id was changed due to a memcached failover or tomcat failover.
     * @param requestId
     *            the uri/id of the request for that the session backup shall be performed, used for readonly tracking.
     * @return the {@link SessionTrackerValve.SessionBackupService.BackupResultStatus}
     */
    @Override
    public Future<BackupResult> backupSession( final String sessionId, final boolean sessionIdChanged, final String requestId ) {
        if ( !_enabled.get() ) {
            return new SimpleFuture<BackupResult>( BackupResult.SKIPPED );
        }

        final MemcachedBackupSession msmSession = _manager.getSessionInternal( sessionId );
        if ( msmSession == null ) {
            _log.debug( "No session found in session map for " + sessionId );
            if ( !_sticky ) {
                _lockingStrategy.onBackupWithoutLoadedSession( sessionId, requestId, _backupSessionService );
            }
            return new SimpleFuture<BackupResult>( BackupResult.SKIPPED );
        }

        if ( !msmSession.isValidInternal() ) {
            _log.debug( "Non valid session found in session map for " + sessionId );
            return new SimpleFuture<BackupResult>( BackupResult.SKIPPED );
        }

        if ( !_sticky ) {
            msmSession.passivate();
        }

        final boolean force = sessionIdChanged || msmSession.isSessionIdChanged() || !_sticky && (msmSession.getSecondsSinceLastBackup() >= msmSession.getMaxInactiveInterval());
        final Future<BackupResult> result = _backupSessionService.backupSession( msmSession, force );

        if ( !_sticky ) {
            _manager.removeInternal( msmSession, false );
            _lockingStrategy.onAfterBackupSession( msmSession, force, result, requestId, _backupSessionService );
        }

        return result;
    }

    @Nonnull
    byte[] serialize( @Nonnull final MemcachedBackupSession session ) {
        return _transcoderService.serialize( session );
    }

    protected MemcachedBackupSession loadFromMemcachedWithCheck( final String sessionId ) {
        if ( !canHitMemcached( sessionId ) || _missingSessionsCache.get( sessionId ) != null ) {
            return null;
        }
        return loadFromMemcached( sessionId );
    }

    /**
     * Checks if this manager {@link #isEnabled()}, if the given sessionId is valid (contains a memcached id)
     * and if this sessionId can access memcached.
     */
    private boolean canHitMemcached( @Nonnull final String sessionId ) {
        return _enabled.get() && _memcachedNodesManager.canHitMemcached( sessionId );
    }

    /**
     * Assumes that before you checked {@link #canHitMemcached(String)}.
     */
    private MemcachedBackupSession loadFromMemcached( final String sessionId ) {
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
            final Object object = _memcached.get( sessionId );
            _memcachedNodesManager.onLoadFromMemcachedSuccess( sessionId );

            if ( object != null ) {
                if ( !(object instanceof byte[]) ) {
                    throw new RuntimeException( "The loaded object for sessionId " + sessionId + " is not of required type byte[], but " + object.getClass().getName() );
                }
                final long startDeserialization = System.currentTimeMillis();
                final MemcachedBackupSession result = _transcoderService.deserialize( (byte[]) object, _manager );
                _statistics.registerSince( SESSION_DESERIALIZATION, startDeserialization );
                _statistics.registerSince( LOAD_FROM_MEMCACHED, start );

                result.setSticky( _sticky );
                if ( !_sticky ) {
                    _lockingStrategy.onAfterLoadFromMemcached( result, lockStatus );
                }

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
            _memcachedNodesManager.onLoadFromMemcachedFailure( sessionId );
        } catch ( final Exception e ) {
            _log.warn( "Could not load session with id " + sessionId + " from memcached.", e );
            if ( lockStatus == LockStatus.LOCKED ) {
                _lockingStrategy.releaseLock( sessionId );
            }
        }
        return null;
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
        if ( _manager.isInitialized() ) {
            final MemcachedNodesManager config = reloadMemcachedConfig( memcachedNodes, _failoverNodes );
            _log.info( "Loaded new memcached node configuration." +
                    "\n- Former config: "+ _memcachedNodes +
                    "\n- New config: " + memcachedNodes +
                    "\n- New node ids: " + config.getPrimaryNodeIds() +
                    "\n- New failover node ids: " + config.getFailoverNodeIds() );
        }
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

    private MemcachedNodesManager reloadMemcachedConfig( final String memcachedNodes, final String failoverNodes ) {

        /* first create all dependent services
         */
        final MemcachedNodesManager memcachedNodesManager = createMemcachedNodesManager( memcachedNodes, failoverNodes );
        final MemcachedClient memcachedClient = createMemcachedClient( memcachedNodesManager, _statistics );
        final BackupSessionService backupSessionService = new BackupSessionService( _transcoderService, _sessionBackupAsync,
                _sessionBackupTimeout, _backupThreadCount, memcachedClient, memcachedNodesManager, _statistics );

        /* then assign new services
         */
        if ( _memcached != null ) {
            _memcached.shutdown();
        }
        _memcached = memcachedClient;
        _memcachedNodesManager = memcachedNodesManager;
        _backupSessionService = backupSessionService;

        initNonStickyLockingMode( memcachedNodesManager );

        return memcachedNodesManager;
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
        if ( _manager.isInitialized() ) {
            final MemcachedNodesManager config = reloadMemcachedConfig( _memcachedNodes, failoverNodes );
            _log.info( "Loaded new memcached failover node configuration." +
                    "\n- Former failover config: "+ _failoverNodes +
                    "\n- New failover config: " + failoverNodes +
                    "\n- New node ids: " + config.getPrimaryNodeIds() +
                    "\n- New failover node ids: " + config.getFailoverNodeIds() );
        }
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
     * Return the compiled pattern used for including session attributes to a session-backup.
     *
     * @return the sessionAttributePattern
     */
    @CheckForNull
    Pattern getSessionAttributePattern() {
        return _sessionAttributePattern;
    }

    /**
     * Return the string pattern used for including session attributes to a session-backup.
     *
     * @return the sessionAttributeFilter
     */
    @CheckForNull
    public String getSessionAttributeFilter() {
        return _sessionAttributeFilter;
    }

    /**
     * Set the pattern used for including session attributes to a session-backup.
     * If not set, all session attributes will be part of the session-backup.
     * <p>
     * E.g. <code>^(userName|sessionHistory)$</code>
     * </p>
     *
     * @param sessionAttributeFilter
     *            the sessionAttributeNames to set
     */
    public void setSessionAttributeFilter( @Nullable final String sessionAttributeFilter ) {
        if ( sessionAttributeFilter == null || sessionAttributeFilter.trim().equals("") ) {
            _sessionAttributeFilter = null;
            _sessionAttributePattern = null;
        }
        else {
            _sessionAttributeFilter = sessionAttributeFilter;
            _sessionAttributePattern = Pattern.compile( sessionAttributeFilter );
        }
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
        final boolean oldEnableStatistics = _enableStatistics;
        _enableStatistics = enableStatistics;
        if ( oldEnableStatistics != enableStatistics && _manager.isInitialized() ) {
            _log.info( "Changed enableStatistics from " + oldEnableStatistics + " to " + enableStatistics + "." +
            " Reloading configuration..." );
            reloadMemcachedConfig( _memcachedNodes, _failoverNodes );
        }
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
        if ( _manager.isInitialized() ) {
            _log.info( "Changed backupThreadCount from " + oldBackupThreadCount + " to " + _backupThreadCount + "." +
                    " Reloading configuration..." );
            reloadMemcachedConfig( _memcachedNodes, _failoverNodes );
            _log.info( "Finished reloading configuration." );
        }
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
        final boolean changed = _enabled.compareAndSet( !enabled, enabled );
        if ( changed && _manager.isInitialized() ) {
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
        if ( !sticky && _manager.getJvmRoute() != null ) {
            _log.warn( "Setting sticky to false while there's still a jvmRoute configured (" + _manager.getJvmRoute() + "), this might cause trouble." +
            		" You should remve the jvmRoute configuration for non-sticky mode." );
        }
        _sticky = sticky;
        if ( _manager.isInitialized() ) {
            _log.info( "Changed sticky to " + _sticky + ". Reloading configuration..." );
            reloadMemcachedConfig( _memcachedNodes, _failoverNodes );
            _log.info( "Finished reloading configuration." );
        }
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
    public void setLockingMode( @Nullable final String lockingMode ) {
        if ( lockingMode == null && _lockingMode == null
                || lockingMode != null && lockingMode.equals( _lockingMode ) ) {
            return;
        }
        _lockingMode = lockingMode;
        if ( _manager.isInitialized() ) {
            initNonStickyLockingMode( createMemcachedNodesManager( _memcachedNodes, _failoverNodes ) );
        }
    }

	private void initNonStickyLockingMode( @Nonnull final MemcachedNodesManager config ) {
        if ( _sticky ) {
            setLockingMode( null, null, false );
            return;
        }

        if ( _sessionAttributeFilter != null ) {
            _log.warn( "There's a sessionAttributesFilter configured ('" + _sessionAttributeFilter + "')," +
                    " all other session attributes will be lost after the request due to non-sticky configuration!" );
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
        final boolean storeSecondaryBackup = config.getCountNodes() > 1;
        setLockingMode( lockingMode, uriPattern, storeSecondaryBackup );
    }

    public void setLockingMode( @Nullable final LockingMode lockingMode, @Nullable final Pattern uriPattern, final boolean storeSecondaryBackup ) {
        _log.info( "Setting lockingMode to " + lockingMode + ( uriPattern != null ? " with pattern " + uriPattern.pattern() : "" ) );
        _lockingStrategy = LockingStrategy.create( lockingMode, uriPattern, _memcached, this, _memcachedNodesManager,
                _missingSessionsCache, storeSecondaryBackup, _statistics );
        if ( _sessionTrackerValve != null ) {
            _sessionTrackerValve.setLockingStrategy( _lockingStrategy );
        }
    }

    protected void updateExpirationInMemcached() {
        if ( _enabled.get() && _sticky ) {
            final Session[] sessions = _manager.findSessions();
            final int delay = _manager.getContainer().getBackgroundProcessorDelay();
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
                        && session.getMaxInactiveInterval() > 0 // for <= 0 the session was stored in memcached with expiration 0
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
        if ( ( oldSessionBackupAsync != sessionBackupAsync ) && _manager.isInitialized() ) {
            _log.info( "SessionBackupAsync was changed to " + sessionBackupAsync + ", creating new BackupSessionService with new configuration." );
            _backupSessionService = new BackupSessionService( _transcoderService, _sessionBackupAsync, _sessionBackupTimeout,
                    _backupThreadCount, _memcached, _memcachedNodesManager, _statistics );
        }
    }

    /**
     * Specifies if the session shall be stored asynchronously in memcached as
     * {@link MemcachedClient#set(String, int, Object)} supports it. If this is
     * false, the timeout from {@link #getSessionBackupTimeout()} is
     * evaluated.
     */
    public boolean isSessionBackupAsync() {
        return _sessionBackupAsync;
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
     * The timeout in milliseconds after that a session backup is considered as
     * beeing failed when {@link #getSessionBackupAsync()}) is <code>false</code>.
     */
    public long getSessionBackupTimeout() {
        return _sessionBackupTimeout;
    }
    
    public Statistics getStatistics() {
        return _statistics;
    }
    
	public long getOperationTimeout() {
		return _operationTimeout;
	}

	public void setOperationTimeout(long operationTimeout ) {
		_operationTimeout = operationTimeout;
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
                _backupThreadCount, _memcached, _memcachedNodesManager, _statistics );
    }

    /**
     * Return the memcached nodes manager.
     */
    @Nonnull
    MemcachedNodesManager getMemcachedNodesManager() {
        return _memcachedNodesManager;
    }

    /**
     * Return the currently configured node ids - just for testing.
     * @return the list of node ids.
     */
    List<String> getNodeIds() {
        return _memcachedNodesManager.getPrimaryNodeIds();
    }
    /**
     * Return the currently configured failover node ids - just for testing.
     * @return the list of failover node ids.
     */
    List<String> getFailoverNodeIds() {
        return _memcachedNodesManager.getFailoverNodeIds();
    }

    /**
     * The memcached client.
     */
    MemcachedClient getMemcached() {
        return _memcached;
    }

    /**
     * The currently set locking strategy.
     */
    @Nullable
    LockingStrategy getLockingStrategy() {
        return _lockingStrategy;
    }

}

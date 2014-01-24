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

import static de.javakaffee.web.msm.Configurations.MAX_RECONNECT_DELAY_KEY;
import static de.javakaffee.web.msm.Configurations.getSystemProperty;
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

import net.spy.memcached.DefaultConnectionFactory;
import net.spy.memcached.MemcachedClient;

import org.apache.catalina.Container;
import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.Manager;
import org.apache.catalina.Session;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.session.StandardSession;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

import de.javakaffee.web.msm.BackupSessionService.SimpleFuture;
import de.javakaffee.web.msm.BackupSessionTask.BackupResult;
import de.javakaffee.web.msm.LockingStrategy.LockingMode;
import de.javakaffee.web.msm.MemcachedNodesManager.MemcachedClientCallback;

/**
 * This is the core of memcached session manager, managing sessions in memcached.
 * A {@link SessionManager} interface represents the dependency to tomcats session manager
 * (which normally keeps sessions in memory). This {@link SessionManager} has to be subclassed
 * for a concrete major tomcat version (e.g. for 7.x.x) and configured in the context.xml
 * as manager (see <a href="http://code.google.com/p/memcached-session-manager/wiki/SetupAndConfiguration">SetupAndConfiguration</a>)
 * for more. The {@link SessionManager} then has to pass configuration settings to this
 * {@link MemcachedSessionService}. Relevant lifecycle methods are {@link #startInternal()}
 * and {@link #shutdown()}.
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class MemcachedSessionService {

    static enum LockStatus {
        /**
         * For sticky sessions or readonly requests with non-sticky sessions there's no lock required.
         */
        LOCK_NOT_REQUIRED,
        LOCKED,
        COULD_NOT_AQUIRE_LOCK
    }

    public static final String PROTOCOL_TEXT = "text";
    public static final String PROTOCOL_BINARY = "binary";

    protected static final String NODE_FAILURE = "node.failure";
    /**
     * Used to store the id for a new session in a request note. This is needed
     * for a context configured with cookie="false" as in this case there's no
     * set-cookie header with the session id. When the request came in with a
     * requestedSessionId this will be changed in the case of a tomcat/memcached
     * failover (via request.changeSessionId, called by the contextValve) so in
     * this case we don't need to note the new/changed session id.
     */
    protected static final String NEW_SESSION_ID = "msm.session.id";

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

    private String _username;
    private String _password;

    private final AtomicBoolean _enabled = new AtomicBoolean( true );

    private String _storageKeyPrefix = StorageKeyFormat.WEBAPP_VERSION;

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
     *
     * this cache is also used to track sessions that are not existing in memcached
     * or that got invalidated, to be able to handle backupSession (in non-sticky mode) correctly.
     */
    private final LRUCache<String, Boolean> _invalidSessionsCache = new LRUCache<String, Boolean>( 2000, 5000 );

	private MemcachedNodesManager _memcachedNodesManager;

    //private LRUCache<String, String> _relocatedSessions;

    protected TranscoderService _transcoderService;

    private TranscoderFactory _transcoderFactory;

    private BackupSessionService _backupSessionService;

    private boolean _sticky = true;
    private String _lockingMode;
    private LockingStrategy _lockingStrategy;
    private long _operationTimeout = 1000;

    private CurrentRequest _currentRequest;
    private RequestTrackingHostValve _trackingHostValve;
    private RequestTrackingContextValve _trackingContextValve;

    protected final SessionManager _manager;
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

        /**
         * Must return the configured session cookie name.
         * @return the session cookie name.
         */
        @Nonnull
        String getSessionCookieName();

        /**
         * Reads the Set-Cookie header(s) from the given response.
         */
        String[] getSetCookieHeaders(Response response);

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
         * Return the Context with which this Manager is associated.
         */
        @Nonnull
        ClassLoader getContainerClassLoader();

        /**
         * Reads the Principal from the given OIS.
         * @param ois the object input stream to read from. Will be closed by the caller.
         * @return the deserialized principal
         * @throws ClassNotFoundException expected to be declared by the implementation.
         * @throws IOException expected to be declared by the implementation.
         */
        @Nonnull
        Principal readPrincipal( @Nonnull ObjectInputStream ois ) throws ClassNotFoundException, IOException;

        /**
         * Determines if the context has a security contraint with form based login.
         */
        boolean contextHasFormBasedSecurityConstraint();

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
        void setUsername(String username);
        void setPassword(String password);

        /**
         * Creates a new instance of {@link MemcachedBackupSession} (needed so that it's possible to
         * create specialized {@link MemcachedBackupSession} instances).
         */
        @Nonnull
        MemcachedBackupSession newMemcachedBackupSession();

        /**
         * Frequency of the session expiration, and related manager operations.
         * Manager operations will be done once for the specified amount of
         * backgrondProcess calls (ie, the lower the amount, the most often the
         * checks will occur).
         */
        int getProcessExpiresFrequency();
    }

    public void shutdown() {
        _log.info( "Stopping services." );
        _manager.getContainer().getParent().getPipeline().removeValve(_trackingHostValve);
        _manager.getContainer().getPipeline().removeValve(_trackingContextValve);
        _backupSessionService.shutdown();
        if ( _lockingStrategy != null ) {
            _lockingStrategy.shutdown();
        }
        if ( _memcached != null ) {
            _memcached.shutdown();
            _memcached = null;
        }
        _transcoderFactory = null;
        _invalidSessionsCache.clear();
    }

    /**
     * Initialize this manager. The memcachedClient parameter is there for testing
     * purposes. If the memcachedClient is provided it's used, otherwise a "real"/new
     * memcached client is created based on the configuration (like {@link #setMemcachedNodes(String)} etc.).
     *
     * @param memcachedClient the memcached client to use, for normal operations this should be <code>null</code>.
     */
    void startInternal( final MemcachedClient memcachedClient ) throws LifecycleException {
        _memcached = memcachedClient;
        startInternal();
    }

    /**
     * Initialize this manager.
     */
    void startInternal() throws LifecycleException {
        _log.info( getClass().getSimpleName() + " starts initialization... (configured" +
                " nodes definition " + _memcachedNodes + ", failover nodes " + _failoverNodes + ")" );

        _statistics = Statistics.create( _enableStatistics );

        _memcachedNodesManager = createMemcachedNodesManager( _memcachedNodes, _failoverNodes);

        if(_memcached == null) {
            _memcached = createMemcachedClient( _memcachedNodesManager, _statistics );
        }

        final String sessionCookieName = _manager.getSessionCookieName();
        _currentRequest = new CurrentRequest();
        _trackingHostValve = createRequestTrackingHostValve(sessionCookieName, _currentRequest);
        final Context context = (Context) _manager.getContainer();
        context.getParent().getPipeline().addValve(_trackingHostValve);
        _trackingContextValve = createRequestTrackingContextValve(sessionCookieName);
        context.getPipeline().addValve( _trackingContextValve );

        initNonStickyLockingMode( _memcachedNodesManager );

        _transcoderService = createTranscoderService( _statistics );

        _backupSessionService = new BackupSessionService( _transcoderService, _sessionBackupAsync, _sessionBackupTimeout,
                _backupThreadCount, _memcached, _memcachedNodesManager, _statistics );

        _log.info( "--------\n- " + getClass().getSimpleName() + " finished initialization:" +
                "\n- sticky: "+ _sticky +
                "\n- operation timeout: " + _operationTimeout +
                "\n- node ids: " + _memcachedNodesManager.getPrimaryNodeIds() +
                "\n- failover node ids: " + _memcachedNodesManager.getFailoverNodeIds() +
                "\n- storage key prefix: " + _memcachedNodesManager.getStorageKeyFormat().prefix +
                "\n--------");

    }

    protected RequestTrackingContextValve createRequestTrackingContextValve(final String sessionCookieName) {
        return new RequestTrackingContextValve(sessionCookieName, this);
    }

    protected RequestTrackingHostValve createRequestTrackingHostValve(final String sessionCookieName, final CurrentRequest currentRequest) {
        return new RequestTrackingHostValve(_requestUriIgnorePattern, sessionCookieName, this, _statistics, _enabled, currentRequest) {
            @Override
            protected String[] getSetCookieHeaders(final Response response) {
                return _manager.getSetCookieHeaders(response);
            }
        };
    }

	protected MemcachedClientCallback createMemcachedClientCallback() {
		return new MemcachedClientCallback() {
			@Override
			public Object get(final String key) {
				return _memcached.get(_memcachedNodesManager.getStorageKeyFormat().format( key ));
			}
		};
	}

    protected MemcachedNodesManager createMemcachedNodesManager(final String memcachedNodes, final String failoverNodes) {
        final Context context = (Context) _manager.getContainer();
        final String webappVersion = Reflections.invoke(context, "getWebappVersion", null);
        final StorageKeyFormat storageKeyFormat = StorageKeyFormat.of(_storageKeyPrefix, context.getParent().getName(), context.getName(), webappVersion);
		return MemcachedNodesManager.createFor( memcachedNodes, failoverNodes, storageKeyFormat, _memcachedClientCallback );
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

        final long maxReconnectDelay = getSystemProperty(MAX_RECONNECT_DELAY_KEY, DefaultConnectionFactory.DEFAULT_MAX_RECONNECT_DELAY);
        return new MemcachedClientFactory().createMemcachedClient(memcachedNodesManager, _memcachedProtocol, _username, _password, _operationTimeout,
                maxReconnectDelay, statistics);
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
        final ClassLoader classLoader = _manager.getContainerClassLoader();
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
    public MemcachedBackupSession findSession( final String id ) throws IOException {
        MemcachedBackupSession result = _manager.getSessionInternal( id );
        if ( result != null ) {
            // TODO: document ignoring requests and container managed authentication
            // -> with container managed auth protected resources should not be ignored
            // TODO: check ignored resource also below
            if (!_sticky && !_trackingHostValve.isIgnoredRequest() && !isContainerSessionLookup()) {
                result.registerReference();
            }
        }
        else if ( canHitMemcached( id ) && _invalidSessionsCache.get( id ) == null ) {
            // when the request comes from the container, it's from CoyoteAdapter.postParseRequest
            // or AuthenticatorBase.invoke (for some kind of security-constraint, where a form-based
            // constraint needs the session to get the authenticated principal)
            if ( !_sticky && isContainerSessionLookup()
                    && !_manager.contextHasFormBasedSecurityConstraint() ) {
                // we can return just null as the requestedSessionId will still be set on
                // the request.
                return null;
            }

            // If no current request is set (RequestTrackerHostValve was not passed) we got invoked
            // by CoyoteAdapter.parseSessionCookiesId - here we can just return null, the requestedSessionId
            // will be accepted anyway
            if(!_sticky && _currentRequest.get() == null) {
                return null;
            }

            // else load the session from memcached
            result = loadFromMemcached( id );
            // checking valid() would expire() the session if it's not valid!
            if ( result != null && result.isValid() ) {
                if(!_sticky) {
                    // synchronized to have correct refcounts
                    synchronized (_manager.getSessionsInternal()) {
                        // in the meantime another request might have loaded and added the session,
                        // and we must ensure to have a single session instance per id to have
                        // correct refcounts (otherwise a session might be removed from the map at
                        // the end of #backupSession
                        if(_manager.getSessionInternal(id) != null) {
                            result = _manager.getSessionInternal(id);
                        }
                        else {
                            addValidLoadedSession(result);
                        }
                        result.registerReference();
                        // _log.info("Registering reference, isContainerSessionLookup(): " + isContainerSessionLookup(), new RuntimeException("foo"));
                    }
                }
                else {
                    addValidLoadedSession(result);
                }
            }
        }
        return result;
    }

    /**
     * Is used to determine if this thread / the current request already hit the application or if this method
     * invocation comes from the container.
     */
    private boolean isContainerSessionLookup() {
        return !_trackingContextValve.wasInvokedWith(_currentRequest.get());
    }

    private void addValidLoadedSession(final MemcachedBackupSession result) {
        // When the sessionId will be changed later in changeSessionIdOnTomcatFailover/handleSessionTakeOver
        // (due to a tomcat failover) we don't want to notify listeners via session.activate for the
        // old sessionId but do that later (in handleSessionTakeOver)
        // See also http://code.google.com/p/memcached-session-manager/issues/detail?id=92
        String jvmRoute;
        final boolean sessionIdWillBeChanged = _sticky && ( jvmRoute = _manager.getJvmRoute() ) != null
            && !jvmRoute.equals( getSessionIdFormat().extractJvmRoute( result.getId() ) );

        final boolean activate = !sessionIdWillBeChanged;
        addValidLoadedSession( result, activate );
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

        final MemcachedBackupSession session = createEmptySession();
        session.setNew( true );
        session.setValid( true );
        session.setCreationTime( System.currentTimeMillis() );
        session.setMaxInactiveInterval( _manager.getMaxInactiveInterval() );

        if ( sessionId == null || !_memcachedNodesManager.canHitMemcached( sessionId ) ) {
            sessionId = _manager.generateSessionId();
        }

        session.setId( sessionId );

        final Request request = _currentRequest.get();
        if(request != null) {
            request.setNote(NEW_SESSION_ID, sessionId);
        }

        if ( _log.isDebugEnabled() ) {
            _log.debug( "Created new session with id " + session.getId() );
        }

        _manager.incrementSessionCounter();

        return session;

    }

    /**
     * Is invoked when a session was removed from the manager, e.g. because the
     * session has been invalidated.
     *
     * Is used to release a lock if the non-stick session was locked
     *
     * It's also used to keep track of such sessions in non-sticky mode, so that
     * lockingStrategy.onBackupWithoutLoadedSession is not invoked (see issue 116).
     *
     * @param session the removed session.
     */
    public void sessionRemoved(final MemcachedBackupSession session) {
        if(!_sticky) {
            if(session.isLocked()) {
                _lockingStrategy.releaseLock(session.getIdInternal());
                session.releaseLock();
            }
            _invalidSessionsCache.put(session.getIdInternal(), Boolean.TRUE);
        }
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
     * Check if the given session id does not belong to this tomcat (according to the
     * local jvmRoute and the jvmRoute in the session id). If the session contains a
     * different jvmRoute load if from memcached. If the session was found in memcached and
     * if it's valid it must be associated with this tomcat and therefore the session id has to
     * be changed. The new session id must be returned if it was changed.
     * <p>
     * This is only useful for sticky sessions, in non-sticky operation mode <code>null</code> should
     * always be returned.
     * </p>
     *
     * @param requestedSessionId
     *            the sessionId that was requested.
     *
     * @return the new session id if the session is taken over and the id was changed.
     *          Otherwise <code>null</code>.
     *
     * @see Request#getRequestedSessionId()
     */
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

        final String newSessionId = _memcachedNodesManager.changeSessionIdForTomcatFailover(session.getIdInternal(), _manager.getJvmRoute());

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
                _memcached.delete( _memcachedNodesManager.getStorageKeyFormat().format(sessionId) ).get();
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
     * Check if the valid session associated with the provided
     * requested session Id will be relocated with the next {@link #backupSession(Session, boolean)}
     * and change the session id to the new one (containing the new memcached node). The
     * new session id must be returned if the session will be relocated and the id was changed.
     *
     * @param requestedSessionId
     *            the sessionId that was requested.
     *
     * @return the new session id if the session will be relocated and the id was changed.
     *          Otherwise <code>null</code>.
     *
     * @see Request#getRequestedSessionId()
     */
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

        final String nodeId = getSessionIdFormat().extractMemcachedId( requestedSessionId );
        if ( nodeId == null ) {
            _log.info( "Cannot load backupSession for sessionId without nodeId: "+ requestedSessionId );
            return null;
        }

        final String newNodeId = _memcachedNodesManager.getNextAvailableNodeId(nodeId);
        if ( newNodeId == null ) {
            _log.info( "No next available node found for nodeId "+ nodeId );
            return null;
        }

        MemcachedBackupSession result = loadBackupSession(requestedSessionId, newNodeId);
        String nextNodeId = nodeId;
        // if we didn't find the backup in the next node, let's go through other nodes
        // to see if the backup is there. For this we have to fake the session id so that
        // the SuffixBasedNodeLocator selects another backup node.
        while(result == null
                && (nextNodeId = _memcachedNodesManager.getNextAvailableNodeId(nextNodeId)) != null
                && !nextNodeId.equals(nodeId)) {
            final String newSessionId = getSessionIdFormat().createNewSessionId(requestedSessionId, nextNodeId);
            result = loadBackupSession(newSessionId, newNodeId);
        }

        if ( result == null ) {
            _log.info( "No backup found for sessionId " + requestedSessionId );
            return null;
        }

        return result;
    }

    private MemcachedBackupSession loadBackupSession(final String requestedSessionId, final String newNodeId) {
        try {
            final SessionValidityInfo validityInfo = _lockingStrategy.loadBackupSessionValidityInfo( requestedSessionId );
            if ( validityInfo == null || !validityInfo.isValid() ) {
                if(_log.isDebugEnabled())
                    _log.debug( "No validity info (or no valid one) found for sessionId " + requestedSessionId );
                return null;
            }

            final Object obj = _memcached.get( getSessionIdFormat().createBackupKey( requestedSessionId ) );
            if ( obj == null ) {
                if(_log.isDebugEnabled())
                    _log.debug( "No backup found for sessionId " + requestedSessionId );
                return null;
            }

            final MemcachedBackupSession session = _transcoderService.deserialize( (byte[]) obj, _manager );
            session.setSticky( _sticky );
            session.setLastAccessedTimeInternal( validityInfo.getLastAccessedTime() );
            session.setThisAccessedTimeInternal( validityInfo.getThisAccessedTime() );
            final String newSessionId = getSessionIdFormat().createNewSessionId( requestedSessionId, newNodeId );
            _log.info( "Session backup loaded from secondary memcached for "+ requestedSessionId +" (will be relocated)," +
            		" setting new id "+ newSessionId +" on session..." );
            session.setIdInternal( newSessionId );
            return session;

        } catch( final Exception e ) {
            _log.error( "Could not get backup validityInfo or backup session for sessionId " + requestedSessionId, e );
            return null;
        }
    }

    /**
     * Is invoked for requests matching {@link #setRequestUriIgnorePattern(String)} at the end
     * of the request. Any acquired resources should be freed.
     * @param sessionId the sessionId, must not be null.
     * @param requestId the uri/id of the request for that the session backup shall be performed, used for readonly tracking.
     */
    public void requestFinished(final String sessionId, final String requestId) {
        if(!_sticky) {
            final MemcachedBackupSession msmSession = _manager.getSessionInternal( sessionId );
            if ( msmSession == null ) {
                if(_log.isDebugEnabled())
                    _log.debug( "No session found in session map for " + sessionId );
                return;
            }

            if ( !msmSession.isValidInternal() ) {
                if(_log.isDebugEnabled())
                    _log.debug( "Non valid session found in session map for " + sessionId );
                return;
            }

            synchronized (_manager.getSessionsInternal()) {
                // if another thread in the meantime retrieved the session
                // we must not remove it as this would case session data loss
                // for the other request
                if ( msmSession.releaseReference() > 0 ) {
                    if(_log.isDebugEnabled())
                        _log.debug( "Session " + sessionId + " is still used by another request, skipping backup and (optional) lock handling/release." );
                    return;
                }
                msmSession.passivate();
                _manager.removeInternal( msmSession, false );
            }

            if(msmSession.isLocked()) {
                _lockingStrategy.releaseLock(sessionId);
                msmSession.releaseLock();
                _lockingStrategy.registerReadonlyRequest(requestId);
            }

        }
    }

    /**
     * Backup the session for the provided session id in memcached if the session was modified or
     * if the session needs to be relocated. In non-sticky session-mode the session should not be
     * loaded from memcached for just storing it again but only metadata should be updated.
     *
     * @param sessionId
     *            the if of the session to backup
     * @param sessionIdChanged
     *            specifies, if the session id was changed due to a memcached failover or tomcat failover.
     * @param requestId
     *            the uri of the request for that the session backup shall be performed.
     *
     * @return a {@link Future} providing the {@link BackupResultStatus}.
     */
    public Future<BackupResult> backupSession( final String sessionId, final boolean sessionIdChanged, final String requestId ) {
        if ( !_enabled.get() ) {
            return new SimpleFuture<BackupResult>( BackupResult.SKIPPED );
        }

        final MemcachedBackupSession msmSession = _manager.getSessionInternal( sessionId );
        if ( msmSession == null ) {
            if(_log.isDebugEnabled())
                _log.debug( "No session found in session map for " + sessionId );
            if ( !_sticky ) {
                // Issue 116/137: Only notify the lockingStrategy if the session was loaded and has not been removed/invalidated
                if(!_invalidSessionsCache.containsKey(sessionId)) {
                    _lockingStrategy.onBackupWithoutLoadedSession( sessionId, requestId, _backupSessionService );
                }
            }
            return new SimpleFuture<BackupResult>( BackupResult.SKIPPED );
        }

        if ( !msmSession.isValidInternal() ) {
            if(_log.isDebugEnabled())
                _log.debug( "Non valid session found in session map for " + sessionId );
            return new SimpleFuture<BackupResult>( BackupResult.SKIPPED );
        }

        if ( !_sticky ) {
            synchronized (_manager.getSessionsInternal()) {
                // if another thread in the meantime retrieved the session
                // we must not remove it as this would case session data loss
                // for the other request
                if ( msmSession.releaseReference() > 0 ) {
                    if(_log.isDebugEnabled())
                        _log.debug( "Session " + sessionId + " is still used by another request, skipping backup and (optional) lock handling/release." );
                    return new SimpleFuture<BackupResult>( BackupResult.SKIPPED );
                }
                msmSession.passivate();
                _manager.removeInternal( msmSession, false );
            }
        }

        final boolean force = sessionIdChanged || msmSession.isSessionIdChanged() || !_sticky && (msmSession.getSecondsSinceLastBackup() >= msmSession.getMaxInactiveInterval());
        final Future<BackupResult> result = _backupSessionService.backupSession( msmSession, force );

        if ( !_sticky ) {
            _lockingStrategy.onAfterBackupSession( msmSession, force, result, requestId, _backupSessionService );
        }

        return result;
    }

    @Nonnull
    byte[] serialize( @Nonnull final MemcachedBackupSession session ) {
        return _transcoderService.serialize( session );
    }

    protected MemcachedBackupSession loadFromMemcachedWithCheck( final String sessionId ) {
        if ( !canHitMemcached( sessionId ) || _invalidSessionsCache.get( sessionId ) != null ) {
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
            final Object object = _memcached.get( _memcachedNodesManager.getStorageKeyFormat().format( sessionId ) );
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
                releaseIfLocked( sessionId, lockStatus );
                _invalidSessionsCache.put( sessionId, Boolean.TRUE );
                if ( _log.isDebugEnabled() ) {
                    _log.debug( "Session " + sessionId + " not found in memcached." );
                }
                return null;
            }
        } catch ( final TranscoderDeserializationException e ) {
            _log.warn( "Could not deserialize session with id " + sessionId + " from memcached, session will be purged from storage.", e );
            releaseIfLocked( sessionId, lockStatus );
            _memcached.delete( _memcachedNodesManager.getStorageKeyFormat().format(sessionId) );
            _invalidSessionsCache.put( sessionId, Boolean.TRUE );
        } catch ( final Exception e ) {
            _log.warn( "Could not load session with id " + sessionId + " from memcached.", e );
            releaseIfLocked( sessionId, lockStatus );
        } finally {
        }
        return null;
    }

    protected void releaseIfLocked( final String sessionId, final LockStatus lockStatus ) {
        if ( lockStatus == LockStatus.LOCKED ) {
            _lockingStrategy.releaseLock( sessionId );
        }
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
        final boolean storeSecondaryBackup = config.getCountNodes() > 1 && !config.isCouchbaseBucketConfig();
        setLockingMode( lockingMode, uriPattern, storeSecondaryBackup );
    }

    public void setLockingMode( @Nullable final LockingMode lockingMode, @Nullable final Pattern uriPattern, final boolean storeSecondaryBackup ) {
        _log.info( "Setting lockingMode to " + lockingMode + ( uriPattern != null ? " with pattern " + uriPattern.pattern() : "" ) );
        _lockingStrategy = LockingStrategy.create( lockingMode, uriPattern, _memcached, this, _memcachedNodesManager,
                _invalidSessionsCache, storeSecondaryBackup, _statistics, _currentRequest );
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

	public void setOperationTimeout(final long operationTimeout ) {
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
    public MemcachedClient getMemcached() {
        return _memcached;
    }

    void setMemcachedClient(final MemcachedClient memcachedClient) {
        _memcached = memcachedClient;
    }

    RequestTrackingHostValve getTrackingHostValve() {
        return _trackingHostValve;
    }

    /**
     * The currently set locking strategy.
     */
    @Nullable
    LockingStrategy getLockingStrategy() {
        return _lockingStrategy;
    }

    public void setUsername(final String username) {
        _username = username;
    }

    /**
     * username required for SASL Connection types
     * @return
     */
    public String getUsername() {
        return _username;
    }

    public void setPassword(final String password) {
       _password = password;
    }

    /**
     * password required for SASL Connection types
     * @return
     */
    public String getPassword() {
        return _password;
    }

    public String getStorageKeyPrefix() {
        return _storageKeyPrefix;
    }

    /**
     * Configure the storage key prefix, this is prepended to the session id in e.g. memcached.
     *
     * The configuration has the form <code>$token,$token</code>
     *
     * Some examples which config would create which output for the key / session id "foo" with context path "ctxt",
     * host "hst" and webappVersion "001" (webappVersion as specified for parallel deployment):
     * <dl>
     * <dt>static:x</dt><dd>x_foo</dd>
     * <dt>host</dt><dd>hst_foo</dd>
     * <dt>host.hash</dt><dd>e93c085e_foo</dd>
     * <dt>context</dt><dd>ctxt_foo</dd>
     * <dt>context.hash</dt><dd>45e6345f_foo</dd>
     * <dt>host,context</dt><dd>hst:ctxt_foo</dd>
     * <dt>webappVersion</dt><dd>001_foo</dd>
     * <dt>host.hash,context.hash,webappVersion</dt><dd>e93c085e:45e6345f:001_foo</dd>
     * </dl>
     *
     * @param storageKeyPrefix
     */
    public void setStorageKeyPrefix(final String storageKeyPrefix) {
        _storageKeyPrefix = storageKeyPrefix;
    }

}

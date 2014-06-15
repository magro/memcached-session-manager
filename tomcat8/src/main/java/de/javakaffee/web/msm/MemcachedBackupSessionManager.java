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


import static de.javakaffee.web.msm.Statistics.StatsType.*;

import java.beans.PropertyChangeListener;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.security.Principal;
import java.util.Collection;
import java.util.Map;
import java.util.regex.Pattern;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.servlet.http.HttpServletRequest;

import net.spy.memcached.MemcachedClient;

import org.apache.catalina.Lifecycle;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.Manager;
import org.apache.catalina.Session;
import org.apache.catalina.connector.Response;
import org.apache.catalina.ha.session.SerializablePrincipal;
import org.apache.catalina.session.ManagerBase;
import org.apache.catalina.session.StandardSession;
import org.apache.catalina.util.SessionConfig;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.descriptor.web.LoginConfig;
import org.apache.tomcat.util.descriptor.web.SecurityConstraint;

import de.javakaffee.web.msm.LockingStrategy.LockingMode;

/**
 * This {@link Manager} stores session in configured memcached nodes after the
 * response is finished (committed).
 * <p>
 * Use this session manager in a Context element, like this <code><pre>
 * &lt;Context path="/foo"&gt;
 *     &lt;Manager className="de.javakaffee.web.msm.MemcachedBackupSessionManager"
 *         memcachedNodes="n1.localhost:11211 n2.localhost:11212" failoverNodes="n2"
 *         connectionType="SASL" non-required
 *         username="username" non-required
 *         password="password" non-required
 *         requestUriIgnorePattern=".*\.(png|gif|jpg|css|js)$" /&gt;
 * &lt;/Context&gt;
 * </pre></code>
 * </p>
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 * @version $Id$
 */
public class MemcachedBackupSessionManager extends ManagerBase implements Lifecycle, PropertyChangeListener, MemcachedSessionService.SessionManager {

    protected static final String NAME = MemcachedBackupSessionManager.class.getSimpleName();

    protected final Log _log = LogFactory.getLog( getClass() );

    protected MemcachedSessionService _msm;

    private Boolean _contextHasFormBasedSecurityConstraint;

    public MemcachedBackupSessionManager() {
        _msm = new MemcachedSessionService( this ) {
            @Override
            protected RequestTrackingContextValve createRequestTrackingContextValve(final String sessionCookieName) {
                final RequestTrackingContextValve result = super.createRequestTrackingContextValve(sessionCookieName);
                result.setAsyncSupported(true);
                return result;
            }
            @Override
            protected RequestTrackingHostValve createRequestTrackingHostValve(final String sessionCookieName, final CurrentRequest currentRequest) {
                final RequestTrackingHostValve result = super.createRequestTrackingHostValve(sessionCookieName, currentRequest);
                result.setAsyncSupported(true);
                return result;
            }
        };
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
    public void load() throws ClassNotFoundException, IOException {
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void unload() throws IOException {
    }

    @Override
    public MemcachedBackupSession createSession( final String sessionId ) {
        return _msm.createSession( sessionId );
    }

    @Override
    public MemcachedBackupSession createEmptySession() {
        return _msm.createEmptySession();
    }

    @Override
    public MemcachedBackupSession newMemcachedBackupSession() {
        return new MemcachedBackupSession( this );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized String generateSessionId() {
        return _msm.newSessionId( super.generateSessionId() );
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
        _msm.deleteFromMemcached( sessionId );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void remove( final Session session, final boolean update ) {
        removeInternal( session, update, session.getNote( MemcachedSessionService.NODE_FAILURE ) != Boolean.TRUE );
    }

    @Override
    public void removeInternal( final Session session, final boolean update ) {
        super.remove( session, update );
    }

    private void removeInternal( final Session session, final boolean update, final boolean removeFromMemcached ) {
        if ( _log.isDebugEnabled() ) {
            _log.debug( "remove invoked, removeFromMemcached: " + removeFromMemcached +
                    ", id: " + session.getId() );
        }
        if ( removeFromMemcached ) {
            _msm.deleteFromMemcached( session.getId() );
        }
        super.remove( session, update );
        _msm.sessionRemoved((MemcachedBackupSession) session);
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
        return _msm.findSession( id );
    }

    @Override
    public void changeSessionId( final Session session ) {
        // e.g. invoked by the AuthenticatorBase (for BASIC auth) on login to prevent session fixation
        // so that session backup won't be omitted we must store this event
        super.changeSessionId( session );
        ((MemcachedBackupSession)session).setSessionIdChanged( true );
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
    @Override
    public void setMemcachedNodes( final String memcachedNodes ) {
        _msm.setMemcachedNodes( memcachedNodes );
    }

    /**
     * The memcached nodes configuration as provided in the server.xml/context.xml.
     * <p>
     * This getter is there to make this configuration accessible via jmx.
     * </p>
     * @return the configuration string for the memcached nodes.
     */
    public String getMemcachedNodes() {
        return _msm.getMemcachedNodes();
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
    @Override
    public void setFailoverNodes( final String failoverNodes ) {
        _msm.setFailoverNodes( failoverNodes );
    }

    /**
     * The memcached failover nodes configuration as provided in the server.xml/context.xml.
     * <p>
     * This getter is there to make this configuration accessible via jmx.
     * </p>
     * @return the configuration string for the failover nodes.
     */
    public String getFailoverNodes() {
        return _msm.getFailoverNodes();
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
        _msm.setRequestUriIgnorePattern( requestUriIgnorePattern );
    }

    /**
     * Return the compiled pattern used for including session attributes to a session-backup.
     *
     * @return the sessionAttributePattern
     */
    @CheckForNull
    Pattern getSessionAttributePattern() {
        return _msm.getSessionAttributePattern();
    }

    /**
     * Return the string pattern used for including session attributes to a session-backup.
     *
     * @return the sessionAttributeFilter
     */
    @CheckForNull
    public String getSessionAttributeFilter() {
        return _msm.getSessionAttributeFilter();
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
        _msm.setSessionAttributeFilter( sessionAttributeFilter );
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
        _msm.setTranscoderFactoryClass( transcoderFactoryClassName );
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
        _msm.setCopyCollectionsForSerialization( copyCollectionsForSerialization );
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
        _msm.setCustomConverter( customConverterClassNames );
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
        _msm.setEnableStatistics( enableStatistics );
    }

    /**
     * Specifies the number of threads that are used if {@link #setSessionBackupAsync(boolean)}
     * is set to <code>true</code>.
     *
     * @param backupThreadCount the number of threads to use for session backup.
     */
    public void setBackupThreadCount( final int backupThreadCount ) {
        _msm.setBackupThreadCount( backupThreadCount );
    }

    /**
     * The number of threads to use for session backup if session backup shall be
     * done asynchronously.
     * @return the number of threads for session backup.
     */
    public int getBackupThreadCount() {
        return _msm.getBackupThreadCount();
    }

    /**
     * Specifies the memcached protocol to use, either "text" (default) or "binary".
     *
     * @param memcachedProtocol one of "text" or "binary".
     */
    public void setMemcachedProtocol( final String memcachedProtocol ) {
        _msm.setMemcachedProtocol( memcachedProtocol );
    }

    /**
     * Enable/disable memcached-session-manager (default <code>true</code> / enabled).
     * If disabled, sessions are neither looked up in memcached nor stored in memcached.
     *
     * @param enabled specifies if msm shall be disabled or not.
     * @throws IllegalStateException it's not allowed to disable this session manager when running in non-sticky mode.
     */
    @Override
    public void setEnabled( final boolean enabled ) throws IllegalStateException {
        _msm.setEnabled( enabled );
    }

    /**
     * Specifies, if msm is enabled or not.
     *
     * @return <code>true</code> if enabled, otherwise <code>false</code>.
     */
    public boolean isEnabled() {
        return _msm.isEnabled();
    }

    @Override
    public void setSticky( final boolean sticky ) {
        _msm.setSticky( sticky );
    }

    public boolean isSticky() {
        return _msm.isSticky();
    }

    @Override
	public void setOperationTimeout(final long operationTimeout ) {
		_msm.setOperationTimeout(operationTimeout);
	}

	public long getOperationTimeout() {
		return _msm.getOperationTimeout();
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
    @Override
    public void setLockingMode( @Nullable final String lockingMode ) {
        _msm.setLockingMode( lockingMode );
    }

    @Override
    public void setLockingMode( @Nullable final LockingMode lockingMode, @Nullable final Pattern uriPattern, final boolean storeSecondaryBackup ) {
        _msm.setLockingMode( lockingMode, uriPattern, storeSecondaryBackup );
    }

    @Override
    public void setUsername(final String username) {
        _msm.setUsername(username);
    }

    @Override
    public void setPassword(final String password) {
        _msm.setPassword(password);
    }

    public void setStorageKeyPrefix(final String storageKeyPrefix) {
        _msm.setStorageKeyPrefix(storageKeyPrefix);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void startInternal() throws LifecycleException {
        super.startInternal();
        _msm.startInternal();
        setState(LifecycleState.STARTING);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void stopInternal() throws LifecycleException {
    	setState(LifecycleState.STOPPING);

        if ( _msm.isSticky() ) {
            _log.info( "Removing sessions from local session map." );
            for( final Session session : sessions.values() ) {
                swapOut( (StandardSession) session );
            }
        }

        _msm.shutdown();

        super.stopInternal();
    }

    private void swapOut( @Nonnull final StandardSession session ) {
        // implementation like the one in PersistentManagerBase.swapOut
        if (!session.isValid()) {
            return;
        }
        session.passivate();
        removeInternal( session, true );
        session.recycle();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void backgroundProcess() {
        _msm.updateExpirationInMemcached();
        super.backgroundProcess();
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
        _msm.setSessionBackupAsync( sessionBackupAsync );
    }

    /**
     * Specifies if the session shall be stored asynchronously in memcached as
     * {@link MemcachedClient#set(String, int, Object)} supports it. If this is
     * false, the timeout from {@link #getSessionBackupTimeout()} is
     * evaluated.
     */
    public boolean isSessionBackupAsync() {
        return _msm.isSessionBackupAsync();
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
        _msm.setSessionBackupTimeout( sessionBackupTimeout );
    }

    /**
     * The timeout in milliseconds after that a session backup is considered as
     * beeing failed when {@link #getSessionBackupAsync()}) is <code>false</code>.
     */
    public long getSessionBackupTimeout() {
        return _msm.getSessionBackupTimeout();
    }

    // -------------------------  statistics via jmx ----------------

    /**
     * @return
     * @see de.javakaffee.web.msm.Statistics#getRequestsWithBackupFailure()
     */
    public long getMsmStatNumBackupFailures() {
        return _msm.getStatistics().getRequestsWithBackupFailure();
    }

    /**
     * @return
     * @see de.javakaffee.web.msm.Statistics#getRequestsWithMemcachedFailover()
     */
    public long getMsmStatNumTomcatFailover() {
        return _msm.getStatistics().getRequestsWithTomcatFailover();
    }

    /**
     * @return
     * @see de.javakaffee.web.msm.Statistics#getRequestsWithMemcachedFailover()
     */
    public long getMsmStatNumMemcachedFailover() {
        return _msm.getStatistics().getRequestsWithMemcachedFailover();
    }

    /**
     * @return
     * @see de.javakaffee.web.msm.Statistics#getRequestsWithoutSession()
     */
    public long getMsmStatNumRequestsWithoutSession() {
        return _msm.getStatistics().getRequestsWithoutSession();
    }

    /**
     * @return
     * @see de.javakaffee.web.msm.Statistics#getRequestsWithoutSessionAccess()
     */
    public long getMsmStatNumNoSessionAccess() {
        return _msm.getStatistics().getRequestsWithoutSessionAccess();
    }

    /**
     * @return
     * @see de.javakaffee.web.msm.Statistics#getRequestsWithoutAttributesAccess()
     */
    public long getMsmStatNumNoAttributesAccess() {
        return _msm.getStatistics().getRequestsWithoutAttributesAccess();
    }

    /**
     * @return
     * @see de.javakaffee.web.msm.Statistics#getRequestsWithoutSessionModification()
     */
    public long getMsmStatNumNoSessionModification() {
        return _msm.getStatistics().getRequestsWithoutSessionModification();
    }

    /**
     * @return
     * @see de.javakaffee.web.msm.Statistics#getRequestsWithSession()
     */
    public long getMsmStatNumRequestsWithSession() {
        return _msm.getStatistics().getRequestsWithSession();
    }

    public long getMsmStatNumNonStickySessionsPingFailed() {
        return _msm.getStatistics().getNonStickySessionsPingFailed();
    }
    public long getMsmStatNumNonStickySessionsReadOnlyRequest() {
        return _msm.getStatistics().getNonStickySessionsReadOnlyRequest();
    }

    /**
     * Returns a string array with labels and values of count, min, avg and max
     * of the time that took the attributes serialization.
     * @return a String array for statistics inspection via jmx.
     */
    public String[] getMsmStatAttributesSerializationInfo() {
        return _msm.getStatistics().getProbe( ATTRIBUTES_SERIALIZATION ).getInfo();
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
        return _msm.getStatistics().getProbe( EFFECTIVE_BACKUP ).getInfo();
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
        return _msm.getStatistics().getProbe( BACKUP ).getInfo();
    }

    /**
     * Returns a string array with labels and values of count, min, avg and max
     * of the time that loading sessions from memcached took (including deserialization).
     * @return a String array for statistics inspection via jmx.
     * @see #getMsmStatSessionDeserializationInfo()
     * @see #getMsmStatNonStickyAfterLoadFromMemcachedInfo()
     */
    public String[] getMsmStatSessionsLoadedFromMemcachedInfo() {
        return _msm.getStatistics().getProbe( LOAD_FROM_MEMCACHED ).getInfo();
    }

    /**
     * Returns a string array with labels and values of count, min, avg and max
     * of the time that deleting sessions from memcached took.
     * @return a String array for statistics inspection via jmx.
     * @see #getMsmStatNonStickyAfterDeleteFromMemcachedInfo()
     */
    public String[] getMsmStatSessionsDeletedFromMemcachedInfo() {
        return _msm.getStatistics().getProbe( DELETE_FROM_MEMCACHED ).getInfo();
    }

    /**
     * Returns a string array with labels and values of count, min, avg and max
     * of the time that deserialization of session data took.
     * @return a String array for statistics inspection via jmx.
     */
    public String[] getMsmStatSessionDeserializationInfo() {
        return _msm.getStatistics().getProbe( SESSION_DESERIALIZATION ).getInfo();
    }

    /**
     * Returns a string array with labels and values of count, min, avg and max
     * of the size of the data that was sent to memcached.
     * @return a String array for statistics inspection via jmx.
     */
    public String[] getMsmStatCachedDataSizeInfo() {
        return _msm.getStatistics().getProbe( CACHED_DATA_SIZE ).getInfo();
    }

    /**
     * Returns a string array with labels and values of count, min, avg and max
     * of the time that storing data in memcached took (excluding serialization,
     * including compression).
     * @return a String array for statistics inspection via jmx.
     */
    public String[] getMsmStatMemcachedUpdateInfo() {
        return _msm.getStatistics().getProbe( MEMCACHED_UPDATE ).getInfo();
    }

    /**
     * Info about locks acquired in non-sticky mode.
     */
    public String[] getMsmStatNonStickyAcquireLockInfo() {
        return _msm.getStatistics().getProbe( ACQUIRE_LOCK ).getInfo();
    }

    /**
     * Lock acquiration in non-sticky session mode.
     */
    public String[] getMsmStatNonStickyAcquireLockFailureInfo() {
        return _msm.getStatistics().getProbe( ACQUIRE_LOCK_FAILURE ).getInfo();
    }

    /**
     * Lock release in non-sticky session mode.
     */
    public String[] getMsmStatNonStickyReleaseLockInfo() {
        return _msm.getStatistics().getProbe( RELEASE_LOCK ).getInfo();
    }

    /**
     * Tasks executed (in the request thread) for non-sticky sessions at the end of requests that did not access
     * the session (validity load/update, ping session, ping 2nd session backup, update validity backup).
     */
    public String[] getMsmStatNonStickyOnBackupWithoutLoadedSessionInfo() {
        return _msm.getStatistics().getProbe( NON_STICKY_ON_BACKUP_WITHOUT_LOADED_SESSION ).getInfo();
    }

    /**
     * Tasks executed for non-sticky sessions after session backup (ping session, store validity info / meta data,
     * store additional backup in secondary memcached).
     */
    public String[] getMsmStatNonStickyAfterBackupInfo() {
        return _msm.getStatistics().getProbe( NON_STICKY_AFTER_BACKUP ).getInfo();
    }

    /**
     * Tasks executed for non-sticky sessions after a session was loaded from memcached (load validity info / meta data).
     */
    public String[] getMsmStatNonStickyAfterLoadFromMemcachedInfo() {
        return _msm.getStatistics().getProbe( NON_STICKY_AFTER_LOAD_FROM_MEMCACHED ).getInfo();
    }

    /**
     * Tasks executed for non-sticky sessions after a session was deleted from memcached (delete validity info and backup data).
     */
    public String[] getMsmStatNonStickyAfterDeleteFromMemcachedInfo() {
        return _msm.getStatistics().getProbe( NON_STICKY_AFTER_DELETE_FROM_MEMCACHED ).getInfo();
    }

    // ---------------------------------------------------------------------------

    @Override
    public String getSessionCookieName() {
        return SessionConfig.getSessionCookieName(getContext());
    }

    @Override
    public MemcachedBackupSession getSessionInternal( final String sessionId ) {
        return (MemcachedBackupSession) sessions.get( sessionId );
    }

    @Override
    public Map<String, Session> getSessionsInternal() {
        return sessions;
    }

    @Override
    public String getString( final String key ) {
        return sm.getString( key );
    }

    @Override
    public void incrementSessionCounter() {
        sessionCounter++;
    }

    @Override
    public void incrementRejectedSessions() {
        rejectedSessions++;
    }

    @Override
    public boolean isInitialized() {
        return getState() == LifecycleState.INITIALIZED || getState() == LifecycleState.STARTED;
    }

    @Override
    public String getString( final String key, final Object ... args ) {
        return sm.getString( key, args );
    }

    @Override
    public ClassLoader getContainerClassLoader() {
        return getContext().getLoader().getClassLoader();
    }

    @Override
    public Principal readPrincipal( final ObjectInputStream ois ) throws ClassNotFoundException, IOException {
        return SerializablePrincipal.readPrincipal( ois );
    }

    public boolean contextHasFormBasedSecurityConstraint(){
        if(_contextHasFormBasedSecurityConstraint != null) {
            return _contextHasFormBasedSecurityConstraint.booleanValue();
        }
        final SecurityConstraint[] constraints = getContext().findConstraints();
        final LoginConfig loginConfig = getContext().getLoginConfig();
        _contextHasFormBasedSecurityConstraint = constraints != null && constraints.length > 0
                && loginConfig != null && HttpServletRequest.FORM_AUTH.equals( loginConfig.getAuthMethod() );
        return _contextHasFormBasedSecurityConstraint;
    }

    @Override
    public MemcachedSessionService getMemcachedSessionService() {
        return _msm;
    }

    @Override
    public String[] getSetCookieHeaders(final Response response) {
        final Collection<String> result = response.getHeaders("Set-Cookie");
        return result.toArray(new String[result.size()]);
    }

}

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

import java.io.IOException;
import java.util.HashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.spy.memcached.AddrUtil;
import net.spy.memcached.MemcachedClient;

import org.apache.catalina.Lifecycle;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleListener;
import org.apache.catalina.Session;
import org.apache.catalina.session.ManagerBase;
import org.apache.catalina.util.LifecycleSupport;
import org.apache.commons.lang.builder.ToStringBuilder;

import de.javakaffee.web.msm.SessionTrackerValve.SessionBackupService;

/**
 * Use this session manager in a Context element, like this
 * <code><pre>
 * &lt;Context path="/foo"&gt;
 *     &lt;Manager className="de.javakaffee.web.msm.MemcachedBackupSessionManager"
 *         memcachedNodes="localhost:11211 localhost:11212" activeNodeIndex="1"
 *         requestUriIgnorePattern=".*\.png$" /&gt;
 * &lt;/Context&gt;
 * </pre></code>
 * 
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 * @version $Id$
 */
public class MemcachedBackupSessionManager extends ManagerBase implements
        Lifecycle, SessionBackupService {

    protected static String name = MemcachedBackupSessionManager.class.getSimpleName();
    private static final String info = name + "/1.0";

    private final Logger _logger = Logger
            .getLogger( MemcachedBackupSessionManager.class.getName() );
    
    private final LifecycleSupport lifecycle = new LifecycleSupport( this );

    // --------------------  configuration properties  --------------------

    /**
     * The memcached nodes space separated, e.g.
     * localhost:11211 localhost:11212
     * 
     */
    private String _memcachedNodes;

    /**
     * The index of the active node, referring to <code>memcachedNodes</code>
     * (of course starting with 0)
     */
    private int _activeNodeIndex;
    
    /**
     * The pattern used for excluding requests from a session-backup.
     * Is matched against request.getRequestURI.
     */
    private String _requestUriIgnorePattern;

    // --------------------  END configuration properties  --------------------

    /*
     * the memcached client
     */
    private MemcachedClient _memcached;
    
    /*
     * findSession may be often called in one request. If a session is requested
     * that we don't have locally stored each findSession invocation would trigger
     * a memcached request - this would open the door for DOS attacks... 
     * 
     * this solution: use a LRUCache with a timeout to store, which session had been
     * requested in the last <n> millis.
     */
    private LRUCache<String, Boolean> _missingSessionsCache;

    /*
     * we have to implement rejectedSessions - not sure why
     */
    private int _rejectedSessions;
    
    /* TODO: make this a parameter, with test
     * 
     */
    private boolean _sessionBackupAsync = false;
    private int _memcachedSessionTTL = 3600;
    private int _sessionBackupTimeout = 100;

    /**
     * Return descriptive information about this Manager implementation and the
     * corresponding version number, in the format
     * <code>&lt;description&gt;/&lt;version&gt;</code>.
     */
    public String getInfo() {
        return info;
    }

    /**
     * Return the descriptive short name of this Manager implementation.
     */
    public String getName() {
        return name;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.catalina.session.ManagerBase#init()
     */
    @Override
    public void init() {
        _logger.info( "init invoked" );
        
        if ( initialized )
            return;
        
        super.init();
        
        /* add the valve for tracking requests for that the session must be sent to memcached
         */
        final SessionTrackerValve sessionTrackerValve = new SessionTrackerValve(
                _requestUriIgnorePattern, this );
        getContainer().getPipeline().addValve( sessionTrackerValve );
        
        /* init memcached
         */
        try {
            _memcached = new MemcachedClient(
                    new SuffixLocatorConnectionFactory( this ),
                    // new BinaryConnectionFactory(),
                    AddrUtil.getAddresses( _memcachedNodes ) );
        } catch ( IOException e ) {
            throw new RuntimeException( "Could not create memcached client", e );
        }
        
        /* create the missing sessions cache
         */
        _logger.info( "Creating LRUCache with size 200 and TTL 100" );
        _missingSessionsCache = new LRUCache<String, Boolean>( 200, 500 );

    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.catalina.session.ManagerBase#generateSessionId()
     */
    @Override
    protected synchronized String generateSessionId() {
        return super.generateSessionId() + "." + _activeNodeIndex;
    }
    
    private boolean isValidSessionIdFormat( String sessionId ) {
        return sessionId != null && sessionId.indexOf( '.' ) > -1;
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * org.apache.catalina.session.ManagerBase#expireSession(java.lang.String)
     */
    @Override
    public void expireSession( String sessionId ) {
        _logger.info( "expireSession invoked: " + sessionId );
        super.expireSession( sessionId );
        _memcached.delete( sessionId );
    }

    /**
     * Return the active Session, associated with this Manager, with the
     * specified session id (if any); otherwise return <code>null</code>.
     * 
     * @param id
     *            The session id for the session to be returned
     * 
     * @exception IllegalStateException
     *                if a new session cannot be instantiated for any reason
     * @exception IOException
     *                if an input/output error occurs while processing this
     *                request
     */
    @Override
    public Session findSession( String id ) throws IOException {
        Session result = super.findSession( id );
        if ( result == null && _missingSessionsCache.get( id ) == null ) {
            result = loadFromMemcached( id );
            if ( result != null ) {
                add( result );
            }
            else {
                _missingSessionsCache.put( id, Boolean.TRUE );
            }
        }
        return result;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.catalina.session.ManagerBase#getSession(java.lang.String)
     */
    @Override
    public HashMap getSession( String id ) {
        _logger.info( "getSession invoked: " + id );
        return super.getSession( id );
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * org.apache.catalina.session.ManagerBase#createSession(java.lang.String)
     */
    @Override
    public Session createSession( String sessionId ) {
        _logger.info( "createSession invoked: " + sessionId );

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

            _logger.info( ToStringBuilder.reflectionToString( session ) );

        }

        sessionCounter++;

        return ( session );

    }

    public BackupResult backupSession( Session session ) {
        if ( _logger.isLoggable( Level.FINE ) ) {
            _logger.fine( "Trying to store session in memcached: " + session.getId() );
        }
        try {
            storeSessionInMemcached( session );
            return BackupResult.SUCCESS;
        } catch( RelocationException e ) {
            if ( _logger.isLoggable( Level.FINE ) ) {
                _logger.fine( "Could not store session in memcached (" + session.getId() + "), now moving to " + e.getTargetNodeId() );
            }
            /* let's do our part of the job
             */
            relocate( session, e.getTargetNodeId(), false );
            /* and tell our client to do his part as well
             */
            return BackupResult.RELOCATED;
        }
    }

    private void storeSessionInMemcached( Session session ) {
        final Future<Boolean> future = _memcached.set( session.getId(), _memcachedSessionTTL, session );
        if ( !_sessionBackupAsync ) {
            try {
                future.get( _sessionBackupTimeout, TimeUnit.MILLISECONDS );
            } catch ( Exception e ) {
                if ( _logger.isLoggable( Level.INFO ) ) {
                    _logger.info( "Could not store session in memcached: " + e +
                            "\nTrying another node now." );
                }
                _memcached.set( session.getId(), _memcachedSessionTTL, session );
            }
        }
    }

    private Session loadFromMemcached( String sessionId ) {
        if ( !isValidSessionIdFormat( sessionId ) ) {
            return null;
        }
        final Session session = (Session) _memcached.get( sessionId );
        if ( _logger.isLoggable( Level.FINE ) ) {
            if ( session == null ) {
                _logger.info( "Session " + sessionId + " not found in memcached." );
            } else {
                _logger.info( "Found session with id " + sessionId );
            }
        }
        return session;
    }

    private void relocate( final Session session, String newNodeId, boolean delete ) {
        final String sessionId = session.getId();
        
        /*
         * relocate session to our memcached node...
         */
        final int idx = sessionId.lastIndexOf( '.' );
        final String newSessionId = idx > -1
            ? sessionId.substring( 0, idx + 1 ) + newNodeId
            : sessionId + "." + newNodeId;
        session.setId( newSessionId );
        
        /* remove old session from memcached
         */
        if ( delete ) {
            _memcached.delete( sessionId );
        }

        /* store the session under the new id in memcached
         */
        storeSessionInMemcached( session );

        /* flag the session as relocated, so that the session tracker valve
         * knows it must send a cookie
         */
        session.setNote( SessionTrackerValve.RELOCATE, Boolean.TRUE );
        
    }

    private String getMemcachedId( String sessionId ) {
        final int idx = sessionId.lastIndexOf( '.' );
        return idx > -1 ? sessionId.substring( idx + 1 ) : null;
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * org.apache.catalina.session.ManagerBase#remove(org.apache.catalina.Session
     * )
     */
    @Override
    public void remove( Session session ) {
        _logger.info( "remove invoked, session.relocate " + session.getNote( SessionTrackerValve.RELOCATE ) +
                ", id " + session.getId() );
        try {
            _memcached.delete( session.getId() );
        } catch( RelocationException e ) {
            /* We can ignore this */
        }
        super.remove( session );
    }

    @Override
    public int getRejectedSessions() {
        return _rejectedSessions;
    }

    @Override
    public void load() throws ClassNotFoundException, IOException {
    }

    @Override
    public void setRejectedSessions( int rejectedSessions ) {
        _rejectedSessions = rejectedSessions;
    }

    @Override
    public void unload() throws IOException {
    }

    /**
     * @param memcachedNodes
     *            the memcachedNodes to set, whitespace separated
     */
    public void setMemcachedNodes( String memcachedNodes ) {
        _memcachedNodes = memcachedNodes;
    }

    /**
     * @param activeNodeIndex
     *            the activeNodeIndex to set
     * @author Martin Grotzke
     */
    public void setActiveNodeIndex( int activeNodeIndex ) {
        _activeNodeIndex = activeNodeIndex;
    }

    @Override
    public void addLifecycleListener( LifecycleListener arg0 ) {
        lifecycle.addLifecycleListener( arg0 );
    }

    @Override
    public LifecycleListener[] findLifecycleListeners() {
        return lifecycle.findLifecycleListeners();
    }

    @Override
    public void removeLifecycleListener( LifecycleListener arg0 ) {
        lifecycle.removeLifecycleListener( arg0 );
    }

    @Override
    public void start() throws LifecycleException {
        if ( !initialized )
            init();
    }

    @Override
    public void stop() throws LifecycleException {
        if ( initialized )
            destroy();
    }

    /**
     * @param requestUriIgnorePattern the requestUriIgnorePattern to set
     * @author Martin Grotzke
     */
    public void setRequestUriIgnorePattern( String requestUriIgnorePattern ) {
        _requestUriIgnorePattern = requestUriIgnorePattern;
    }

}

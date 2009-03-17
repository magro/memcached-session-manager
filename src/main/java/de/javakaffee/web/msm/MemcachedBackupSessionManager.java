/*
 * $Id: $ (c)
 * Copyright 2009 freiheit.com technologies GmbH
 *
 * Created on Mar 14, 2009
 *
 * This file contains unpublished, proprietary trade secret information of
 * freiheit.com technologies GmbH. Use, transcription, duplication and
 * modification are strictly prohibited without prior written consent of
 * freiheit.com technologies GmbH.
 */
package de.javakaffee.web.msm;

import java.io.IOException;
import java.util.HashMap;
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

/**
 * TODO: DESCRIBE ME<br>
 * Created on: Mar 14, 2009<br>
 * 
 * @author <a href="mailto:martin.grotzke@freiheit.com">Martin Grotzke</a>
 * @version $Id$
 */
public class MemcachedBackupSessionManager extends ManagerBase implements
        Lifecycle {

    private static final String info = "PersistentManager/1.0";

    protected static String name = "PersistentManager";

    protected LifecycleSupport lifecycle = new LifecycleSupport( this );

    private final Logger _logger = Logger
            .getLogger( MemcachedBackupSessionManager.class.getName() );

    private String _memcachedNodes;

    private int _activeNodeIndex;

    private String _memcachedId;

    private MemcachedClient _memcached;

    private int _rejectedSessions;

    private LRUCache<String, Boolean> _missingSessionsCache;

    /**
     * Return descriptive information about this Manager implementation and the
     * corresponding version number, in the format
     * <code>&lt;description&gt;/&lt;version&gt;</code>.
     */
    public String getInfo() {
        return ( info );
    }

    /**
     * Return the descriptive short name of this Manager implementation.
     */
    public String getName() {
        return ( name );
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
        
        /* init memcached
         */
        _memcachedId = String.valueOf( _activeNodeIndex );
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
        _missingSessionsCache = new de.javakaffee.web.msm.LRUCache<String, Boolean>( 200, 200 );

    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.catalina.session.ManagerBase#generateSessionId()
     */
    @Override
    protected synchronized String generateSessionId() {
        return super.generateSessionId() + "." + _memcachedId;
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
        _logger.info( "findSession invoked: " + id );
        Session result = super.findSession( id );
        if ( result == null && _missingSessionsCache.get( id ) == null ) {
            _logger.info( "No session found, loading from memcached." );
            result = loadFromMemcached( id );
            if ( result != null ) {
                _logger.info( "Found session in memcached, storing locally: " + result.getId() );
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

    protected void storeSession( Session session ) {
        _memcached.set( session.getId(), 3600, session );
    }

    private Session loadFromMemcached( String sessionId ) {
        final Session session = (Session) _memcached.get( sessionId );
        if ( session == null ) {
            _logger.warning( "Session " + sessionId
                    + " not found in memcached." );
        } else {
            _logger.info( "Found session with id " + sessionId );
            /* for now do not relocate, as simply changing the session id does not
             * trigger sending the cookie to the browser...
             */
//            final String requestedMemcachedId = getMemcachedId( sessionId );
//            if ( !_memcachedId.equals( requestedMemcachedId ) ) {
//                _logger.warning( "Session " + sessionId
//                        + " found in memcached," + " relocating from "
//                        + requestedMemcachedId + " to " + _memcachedId );
//                /*
//                 * relocate session to our memcached node...
//                 */
//                final int idx = sessionId.lastIndexOf( '.' );
//                final String newSessionId = idx > -1 ? sessionId.substring( 0,
//                        idx + 1 )
//                        + _memcachedId : sessionId + "." + _memcachedId;
//                session.setId( newSessionId );
//                _memcached.delete( sessionId );
//                storeSession( session );
//            }
        }
        return session;
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
        _logger.info( "remove invoked" );
        _memcached.delete( session.getId() );
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
     * @return the memcachedId
     * @author Martin Grotzke
     */
    public String getMemcachedId() {
        return _memcachedId;
    }

    /**
     * @param memcachedId
     *            the memcachedId to set
     * @author Martin Grotzke
     */
    public void setMemcachedId( String memcachedId ) {
        _memcachedId = memcachedId;
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

}

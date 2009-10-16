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
import org.apache.commons.lang.builder.ToStringBuilder;

import de.javakaffee.web.msm.NodeAvailabilityCache.CacheLoader;
import de.javakaffee.web.msm.NodeIdResolver.MapBasedResolver;
import de.javakaffee.web.msm.SessionTrackerValve.SessionBackupService;

/**
 * Use this session manager in a Context element, like this <code><pre>
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
        Lifecycle, SessionBackupService, PropertyChangeListener {

    protected static final String name = MemcachedBackupSessionManager.class
            .getSimpleName();

    private static final String info = name + "/1.0";
    
    private static final String NODE_REGEX = "([\\w]+):([^:]+):([\\d]+)";
    private static final Pattern NODE_PATTERN = Pattern.compile( NODE_REGEX );
    
    private static final String NODES_REGEX = NODE_REGEX + "(?:\\s+" + NODE_REGEX + ")*";
    private static final Pattern NODES_PATTERN = Pattern.compile( NODES_REGEX );

    private static final String NODES_TESTED = "nodes.tested";

    private static final String NODE_FAILURE = "node.failure";

    private static final String ORIG_SESSION_ID = "orig.sid";

    private static final String RELOCATE_SESSION_ID = "relocate.sid";

    private final Random _random = new Random();
    

    private final Logger _logger = Logger
            .getLogger( MemcachedBackupSessionManager.class.getName() );

    private final LifecycleSupport lifecycle = new LifecycleSupport( this );

    private final SessionIdFormat _sessionIdFormat = new SessionIdFormat();

    // -------------------- configuration properties --------------------

    /**
     * The memcached nodes space separated and with the id prefix, e.g. n1:localhost:11211 n2:localhost:11212
     * 
     */
    private String _memcachedNodes;

    /**
     * The ids of memcached failover nodes comma separated, e.g. <code>n1,n2</code>
     * 
     */
    private String _failoverNodes;

    /**
     * The pattern used for excluding requests from a session-backup. Is matched
     * against request.getRequestURI.
     */
    private String _requestUriIgnorePattern;

    /**
     * Specifies if the session shall be stored asynchronously in memcached
     * as {@link MemcachedClient#set(String, int, Object)} supports it. If this is
     * false, the timeout set via {@link #setSessionBackupTimeout(int)} is evaluated.
     * <p>
     * Notice: if the session backup is done asynchronously, it is possible that a session
     * cannot be stored in memcached and we don't notice that - therefore the session would
     * not get relocated to another memcached node.
     * </p>
     * <p>
     * By default this property is set to <code>false</code> - the session backup is performed synchronously.
     * </p>
     */
    private boolean _sessionBackupAsync = false;

    /**
     * The timeout in milliseconds after that a session backup is considered as beeing failed.
     * <p>
     * This property is only evaluated if sessions are stored synchronously
     * (set via {@link #setSessionBackupAsync(boolean)}).
     * </p>
     * <p>
     * The default value is <code>100</code> millis.
     * </p>
     */
    private int _sessionBackupTimeout = 100;

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
     * remove may be called with sessionIds that already failed before (probably because the
     * browser makes subsequent requests with the old sessionId - the exact reason needs to be verified).
     * These failed sessionIds should 
     * If a session is requested
     * that we don't have locally stored each findSession invocation would
     * trigger a memcached request - this would open the door for DOS attacks...
     * 
     * this solution: use a LRUCache with a timeout to store, which session had
     * been requested in the last <n> millis.
     * 
     * Updated: the node status cache holds the status of each node for the configured TTL.
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

    public static void main( String[] args ) {
        

        final List<String> nodeIds = new ArrayList<String>();
        final Random random = new Random();

        for( int i = 0; i < 3; i++ ) {
            nodeIds.add( String.valueOf( i ) );
            System.out.println( random.nextInt(3) );
            System.out.println( Math.floor( Math.random() * 3 ) );
        }
        
        
//        /*
//         * final String nodePattern = "[^:]+:[^:]+:[\\d]+"; Pattern pattern =
//         * Pattern.compile( nodePattern + "(:?\\s+" + nodePattern + ")*" ); //
//         * (\\.[\\w]+)?" );
//         */
//        /*
//         *  Special constructs (non-capturing)
//(?:X)   X, as a non-capturing group
//(?idmsux-idmsux)    Nothing, but turns match flags i d m s u x on - off
//(?idmsux-idmsux:X)      X, as a non-capturing group with the given flags i d m s u x on - off
//(?=X)   X, via zero-width positive lookahead
//(?!X)   X, via zero-width negative lookahead
//(?<=X)  X, via zero-width positive lookbehind
//(?<!X)  X, via zero-width negative lookbehind
//(?>X)   X, as an independent, non-capturing group
//         */
//        final String nodePattern = "([\\w]+):([^:]+):([\\d]+)";
//        Pattern pattern = Pattern.compile( nodePattern + "(?:\\s+"
//                + nodePattern + ")*" ); // (\\.[\\w]+)?" );
//
//        final Matcher matcher = pattern
//                .matcher( "n1:test:123 n2:tester:123 n3:localhost:1234" );
//        System.out.println( matcher.matches() );
//        //System.out.println( matcher.find() );
//        final Matcher matcher2 = Pattern.compile( nodePattern ).matcher( "n1:test:123 n2:tester:123 n3:localhost:1234" );
//        while( matcher2.find() ) {
//            System.out.println( matcher2.group(2) );
//        }
////        for ( int i = 0; i <= matcher.groupCount(); i++ ) {
////            System.out.println( matcher.group(  ) );
////
////        }

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

        /*
         * add the valve for tracking requests for that the session must be sent
         * to memcached
         */
        final SessionTrackerValve sessionTrackerValve = new SessionTrackerValve(
                _requestUriIgnorePattern, this );
        getContainer().getPipeline().addValve( sessionTrackerValve );

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
        final Map<InetSocketAddress,String> address2Ids = new HashMap<InetSocketAddress, String>();
        while( matcher.find() ) {
            
            final String nodeId = matcher.group( 1 );
            _nodeIds.add( nodeId );
            _allNodeIds.add( nodeId );
            
            final String hostname = matcher.group( 2 );
            final int port = Integer.parseInt( matcher.group( 3 ) );
            final InetSocketAddress address = new InetSocketAddress( hostname, port );
            addresses.add( address );
            
            address2Ids.put( address, nodeId );
            
        }
        
        _failoverNodeIds = new ArrayList<String>();
        if ( _failoverNodes != null && _failoverNodes.trim().length() != 0 ) {
            final String[] failoverNodes = _failoverNodes.split( ":" );
            for ( String nodeId : failoverNodes ) {
                nodeId = nodeId.trim();
                if ( !_nodeIds.remove( nodeId ) ) {
                    throw new IllegalArgumentException( "Invalid failover node id " + nodeId + ": " +
                    		"not existing in memcachedNodes '" + _memcachedNodes + "'." );
                }
                _failoverNodeIds.add( nodeId );
            }
        }
        
        try {
            _memcached = new MemcachedClient(
                    new SuffixLocatorConnectionFactory( this, new MapBasedResolver( address2Ids ), _sessionIdFormat ),
                    addresses );
        } catch ( IOException e ) {
            throw new RuntimeException( "Could not create memcached client", e );
        }

        /*
         * create the missing sessions cache
         */
        _missingSessionsCache = new LRUCache<String, Boolean>( 200, 500 );
        _nodeAvailabilityCache = new NodeAvailabilityCache<String>( _allNodeIds.size(), 1000, new CacheLoader<String>() {

            @Override
            public boolean isNodeAvailable( String key ) {
                try {
                    _memcached.get( _sessionIdFormat.createSessionId( "ping", key ) );
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }
            
        } );

        //_relocatedSessions = new LRUCache<String, String>( 100000, getMaxInactiveInterval() * 1000 );
    }

    /*
     * (non-Javadoc)
     * Copied from StandardManager.
     * @see org.apache.catalina.session.StandardManager#setContainer(org.apache.catalina.Container)
     */
    public void setContainer(Container container) {

        // De-register from the old Container (if any)
        if ( this.container != null && this.container instanceof Context ) {
            ((Context) this.container).removePropertyChangeListener( this );
        }

        // Default processing provided by our superclass
        super.setContainer( container );

        // Register with the new Container (if any)
        if ( this.container != null && this.container instanceof Context ) {
            setMaxInactiveInterval( ((Context) this.container).getSessionTimeout() * 60 );
            ((Context) this.container).addPropertyChangeListener( this );
        }

    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.catalina.session.ManagerBase#generateSessionId()
     */
    @Override
    protected synchronized String generateSessionId() {
        return _sessionIdFormat.createSessionId( super.generateSessionId(), getMemcachedNodeId() );
    }

    private String getMemcachedNodeId() {
        return _nodeIds.get( _random.nextInt( _nodeIds.size() ) );
    }

    private boolean isValidSessionIdFormat( String sessionId ) {
        return _sessionIdFormat.isValid( sessionId );
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * org.apache.catalina.session.ManagerBase#expireSession(java.lang.String)
     */
    @Override
    public void expireSession( String sessionId ) {
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

    /*
     * (non-Javadoc)
     * 
     * @see
     * org.apache.catalina.session.ManagerBase#createSession(java.lang.String)
     */
    @Override
    public Session createSession( String sessionId ) {
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

            _logger.fine( ToStringBuilder.reflectionToString( session ) );

        }

        sessionCounter++;

        return ( session );

    }
    
    /* (non-Javadoc)
     * @see org.apache.catalina.session.ManagerBase#createEmptySession()
     */
    @Override
    public MemcachedBackupSession createEmptySession() {
        return new MemcachedBackupSession( this );
    }

    public BackupResult backupSession( Session session ) {
        if ( _logger.isLoggable( Level.INFO ) ) {
            _logger.fine( "Trying to store session in memcached: "
                    + session.getId() );
        }
        try {
            
            if ( session.getNote( RELOCATE_SESSION_ID ) != null ) {
                _logger.info( "Found relocate session id, setting new id on session..." );
                session.setNote( NODE_FAILURE, Boolean.TRUE );
                ((MemcachedBackupSession) session).setIdForRelocate( session.getNote( RELOCATE_SESSION_ID ).toString() );
                session.removeNote( RELOCATE_SESSION_ID );
            }
            
            storeSessionInMemcached( session );
            return BackupResult.SUCCESS;
        } catch ( NodeFailureException e ) {
            if ( _logger.isLoggable( Level.INFO ) ) {
                _logger.info( "Could not store session in memcached ("
                        + session.getId() + ")" );
            }
            
            /* get the next memcached node to try
             */
            @SuppressWarnings("unchecked")
            Set<String> testedNodes = (Set<String>) session.getNote( NODES_TESTED );
            final String nodeId = _sessionIdFormat.extractMemcachedId( session.getId() );
            final String targetNodeId = getNextNodeId( nodeId, testedNodes );
            
            final MemcachedBackupSession backupSession = (MemcachedBackupSession) session;
            
            if ( targetNodeId == null ) {
                
                _logger.warning( "The node " + nodeId + " is not available and there's no node for relocation left, omitting session backup." );

                noFailoverNodeLeft( backupSession );
                
                return BackupResult.FAILURE;
                
            }
            else {
                
                if ( testedNodes == null ) {
                    testedNodes = new HashSet<String>();
                    session.setNote( NODES_TESTED, testedNodes );
                }
                
                final BackupResult backupResult = failover( backupSession, testedNodes, targetNodeId );
                
                switch( backupResult ) {
                    case SUCCESS:
                        
                        //_relocatedSessions.put( session.getNote( ORIG_SESSION_ID ).toString(), session.getId() );
                        
                        /* cleanup
                         */
                        session.removeNote( ORIG_SESSION_ID );
                        session.removeNote( NODE_FAILURE );
                        session.removeNote( NODES_TESTED );
                        
                        /*
                         * and tell our client to do his part as well
                         */
                        return BackupResult.RELOCATED;
                    default:
                        /* just pass it up
                         */
                        return backupResult;
                        
                }
            }
        }
    }
    
    /**
     * Returns the new session id if the provided session has to be relocated.
     * @param session the session to check, never null.
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
            }
            else {
                _logger.warning( "The node " + nodeId + " is not available and there's no node for relocation left." );
            }
        }
        return null;
    }

    private BackupResult failover( final MemcachedBackupSession session, Set<String> testedNodes, final String targetNodeId ) {

        final String nodeId = _sessionIdFormat.extractMemcachedId( session.getId() );
        
        testedNodes.add( nodeId );
        
        /* we must store the original session id so that we can
         * set this if no memcached node is left for taking over
         */
        if ( session.getNote( ORIG_SESSION_ID ) == null ) {
            session.setNote( ORIG_SESSION_ID, session.getId() );
        }
        
        /*
         * relocate session to our memcached node...
         * 
         * and mark it as a node-failure-session, so that remove(session) does
         * not try to remove it from memcached...
         * (the session is removed and added when the session id is changed)
         */
        session.setNote( NODE_FAILURE, Boolean.TRUE );
        session.setIdForRelocate( _sessionIdFormat.createNewSessionId(
                session.getId(), targetNodeId ) );
        
        /* invoke backup again, until we have a success or a failure
         */
        final BackupResult backupResult = backupSession( session );
        
        return backupResult;
    }

    private void noFailoverNodeLeft( MemcachedBackupSession session ) {
        
        /* we must set the original session id in case we changed it already
         */
        final String origSessionId = (String) session.getNote( ORIG_SESSION_ID );
        if ( origSessionId != null && !origSessionId.equals( session.getId() ) ) {
            session.setIdForRelocate( origSessionId );
        }
        
        /* cleanup
         */
        session.removeNote( ORIG_SESSION_ID );
        session.removeNote( NODE_FAILURE );
        session.removeNote( NODES_TESTED );
    }

    /**
     * Get the next memcached node id for session backup. The active node ids are preferred,
     * if no active node id is left to try, a failover node id is picked. If no failover node
     * id is left, this method returns just null. 
     * @param nodeId the current node id
     * @param excludedNodeIds the node ids that were already tested and shall not be used again. Can be null.
     * @return the next node id or null, if no node is left.
     */
    protected String getNextNodeId( String nodeId, Set<String> excludedNodeIds ) {

        String result = null;
        
        /* first check regular nodes
         */
        result = getNextNodeId( nodeId, _nodeIds, excludedNodeIds );
        
        /* we got no node from the first nodes list, so we must check the alternative node list
         */
        if ( result == null && _failoverNodeIds != null && !_failoverNodeIds.isEmpty() ) {
            result = getNextNodeId( nodeId, _failoverNodeIds, excludedNodeIds );
        }
        
        return result;
    }

    /**
     * Determines the next available node id from the provided node ids.
     * The returned node id will be different from the provided nodeId and will not be
     * contained in the excludedNodeIds.
     * @param nodeId the original id 
     * @param nodeIds the node ids to choose from
     * @param excludedNodeIds the set of invalid node ids
     * @return an available node or null
     */
    protected static String getNextNodeId( final String nodeId, final List<String> nodeIds,
            Set<String> excludedNodeIds ) {
        
        String result = null;
        
        final int origIdx = nodeIds.indexOf( nodeId );
        final int nodeIdsSize = nodeIds.size();
        
        int idx = origIdx;
        while( result == null && !loopFinished( origIdx, idx, nodeIdsSize ) ) {
            
            final int checkIdx = roll( idx, nodeIdsSize );
            final String checkNodeId = nodeIds.get( checkIdx );
            
            if ( excludedNodeIds != null && excludedNodeIds.contains( checkNodeId ) ) {
                idx = checkIdx;
            }
            else {
                result = checkNodeId;
            }
            
        }
        
        return result;
    }
    
    private static boolean loopFinished( final int origIdx, int idx,
            final int nodeIdsSize ) {
        return origIdx == -1 ? idx + 1 == nodeIdsSize : roll( idx, nodeIdsSize ) == origIdx;
    }

    protected static int roll( int idx, final int size ) {
        return idx + 1 >= size ? 0 : idx + 1;
    }

    private void storeSessionInMemcached( Session session ) throws NodeFailureException {
        final Future<Boolean> future = _memcached.set( session.getId(),
                getMaxInactiveInterval(), session );
        if ( !_sessionBackupAsync ) {
            try {
                future.get( _sessionBackupTimeout, TimeUnit.MILLISECONDS );
            } catch ( Exception e ) {
                if ( _logger.isLoggable( Level.INFO ) ) {
                    _logger.info( "Could not store session " + session.getId() + " in memcached: " + e );
                }
                final String nodeId = _sessionIdFormat.extractMemcachedId( session.getId() );
                _nodeAvailabilityCache.setNodeAvailable( nodeId, false );
                throw new NodeFailureException( "Could not store session in memcached.", nodeId );
            }
        }
    }

    private Session loadFromMemcached( String sessionId ) {
        if ( !isValidSessionIdFormat( sessionId ) ) {
            return null;
        }
        final String nodeId = _sessionIdFormat.extractMemcachedId( sessionId );
        if ( !_nodeAvailabilityCache.isNodeAvailable( nodeId ) ) {
            _logger.fine( "Asked for session " + sessionId + ", but the related" +
            		" memcached node is still marked as unavailable (won't load from memcached)." );
        }
        else {
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
            } catch (NodeFailureException e) {
                _logger.warning( "Could not load session with id " + sessionId + " from memcached." );
                _nodeAvailabilityCache.setNodeAvailable( nodeId, false );
            }
        }
        return null;
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
        _logger.fine( "remove invoked," +
        		" session.relocate:  " + session.getNote( SessionTrackerValve.RELOCATE ) +
                ", node failure: " + session.getNote( NODE_FAILURE ) +
                ", node failure != TRUE: " + (session.getNote( NODE_FAILURE ) != Boolean.TRUE) +
                ", id: " + session.getId() );
        if ( session.getNote( NODE_FAILURE ) != Boolean.TRUE ) {
            try {
                _logger.fine( "Deleting session from memcached: " + session.getId() );
                _memcached.delete( session.getId() );
            } catch ( NodeFailureException e ) {
                /* We can ignore this */
            }
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
     * @param failoverNodes the failoverNodes to set, comma separated
     */
    public void setFailoverNodes( String failoverNodes ) {
        _failoverNodes = failoverNodes;
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
        if ( initialized ) {
            _memcached.shutdown();
            destroy();
        }
    }

    /**
     * @param requestUriIgnorePattern
     *            the requestUriIgnorePattern to set
     * @author Martin Grotzke
     */
    public void setRequestUriIgnorePattern( String requestUriIgnorePattern ) {
        _requestUriIgnorePattern = requestUriIgnorePattern;
    }

    /*
     * (non-Javadoc)
     * Copied from StandardManager.
     * @see org.apache.catalina.session.StandardManager#propertyChange(java.beans.PropertyChangeEvent)
     */
    public void propertyChange(PropertyChangeEvent event) {

        // Validate the source of this event
        if ( !(event.getSource() instanceof Context) ) {
            return;
        }
        
        // Process a relevant property change
        if ( event.getPropertyName().equals( "sessionTimeout" ) ) {
            try {
                setMaxInactiveInterval( ((Integer) event.getNewValue()).intValue() * 60 );
            } catch (NumberFormatException e) {
                _logger.warning("standardManager.sessionTimeout: "
                                 + event.getNewValue().toString());
            }
        }

    }

    // ===========================  for testing  ==============================
    
    protected void setNodeIds( List<String> nodeIds ) {
        _nodeIds = nodeIds;
    }

    protected void setFailoverNodeIds( List<String> failoverNodeIds ) {
        _failoverNodeIds = failoverNodeIds;
    }

    /**
     * Specifies if the session shall be stored asynchronously in memcached
     * as {@link MemcachedClient#set(String, int, Object)} supports it. If this is
     * false, the timeout set via {@link #setSessionBackupTimeout(int)} is evaluated.
     * <p>
     * Notice: if the session backup is done asynchronously, it is possible that a session
     * cannot be stored in memcached and we don't notice that - therefore the session would
     * not get relocated to another memcached node.
     * </p>
     * <p>
     * By default this property is set to <code>false</code> - the session backup is performed synchronously.
     * </p>
     * 
     * @param sessionBackupAsync the sessionBackupAsync to set
     */
    public void setSessionBackupAsync( boolean sessionBackupAsync ) {
        _sessionBackupAsync = sessionBackupAsync;
    }

    /**
     * The timeout in milliseconds after that a session backup is considered as beeing failed.
     * <p>
     * This property is only evaluated if sessions are stored synchronously
     * (set via {@link #setSessionBackupAsync(boolean)}).
     * </p>
     * <p>
     * The default value is <code>100</code> millis.
     * 
     * @param sessionBackupTimeout the sessionBackupTimeout to set (milliseconds)
     */
    public void setSessionBackupTimeout( int sessionBackupTimeout ) {
        _sessionBackupTimeout = sessionBackupTimeout;
    }
    
    private static final class MemcachedBackupSession extends StandardSession {
        
        private static final long serialVersionUID = 1L;
        
        public MemcachedBackupSession(Manager manager) {
            super( manager );
        }

        /**
         * Set a new id for this session.<br/>
         * Before setting the new id, it removes itself from the associated manager.
         * After the new id is set, this session adds itself to the session manager.
         * @param id the new session id
         */
        protected void setIdForRelocate( final String id ) {
            
            if ( this.id == null ) {
                throw new IllegalStateException("There's no session id set.");
            }
            if ( this.manager == null ) {
                throw new IllegalStateException("There's no manager set.");
            }
            
            manager.remove( this );
            this.id = id;
            manager.add(this);
            
        }
        
        @Override
        public void setId( String id ) {
            super.setId( id );
        }
        
    }

}

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
import org.apache.catalina.Session;
import org.apache.catalina.session.ManagerBase;
import org.apache.catalina.util.LifecycleSupport;
import org.apache.commons.lang.builder.ToStringBuilder;

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
     * we have to implement rejectedSessions - not sure why
     */
    private int _rejectedSessions;

    /*
     * TODO: make this a parameter, with test
     */
    private boolean _sessionBackupAsync = false;

    private int _sessionBackupTimeout = 100;

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
        _logger.info( "Creating LRUCache with size 200 and TTL 100" );
        _missingSessionsCache = new LRUCache<String, Boolean>( 200, 500 );

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
        System.out.println( "############  findSession " + id + ", result: " + result);
        if ( result == null && _missingSessionsCache.get( id ) == null ) {
            result = loadFromMemcached( id );
            if ( result != null ) {
                add( result );
            } else {
                _missingSessionsCache.put( id, Boolean.TRUE );
            }
        }
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
            _logger.fine( "Trying to store session in memcached: "
                    + session.getId() );
        }
        try {
            storeSessionInMemcached( session );
            return BackupResult.SUCCESS;
        } catch ( NodeFailureException e ) {
            if ( _logger.isLoggable( Level.FINE ) ) {
                _logger.fine( "Could not store session in memcached ("
                        + session.getId() + ")" );
            }
            
            /* get the next memcached node to try
             */
            
            final String nodeId = _sessionIdFormat.extractMemcachedId( session.getId() );

            @SuppressWarnings("unchecked")
            Set<String> tested = (Set<String>) session.getNote( NODES_TESTED );
            final String targetNodeId = getNextNodeId( nodeId, tested );
            
            if ( targetNodeId == null ) {
                _logger.warning( "The node " + nodeId + " is not available and there's no node for relocation left, omitting session backup." );

                /* we must set the original session id in case we changed it already
                 */
                final String origSessionId = (String) session.getNote( ORIG_SESSION_ID );
                if ( origSessionId != null && !origSessionId.equals( session.getId() ) ) {
                    session.setId( origSessionId );
                }
                
                /* cleanup
                 */
                session.removeNote( ORIG_SESSION_ID );
                session.removeNote( NODE_FAILURE );
                session.removeNote( NODES_TESTED );
                
                return BackupResult.FAILURE;
            }
            else {
                
                if ( tested == null ) {
                    tested = new HashSet<String>();
                    session.setNote( NODES_TESTED, tested );
                }
                tested.add( nodeId );
                
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
                session.setId( _sessionIdFormat.createNewSessionId(
                        session.getId(), targetNodeId ) );
                
                /* invoke us again, until we have a success or a failure
                 */
                BackupResult backupResult = backupSession( session );
                switch( backupResult ) {
                    case SUCCESS:
                        
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
                throw new NodeFailureException( "Could not store session in memcached." );
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
                _logger.info( "Session " + sessionId
                        + " not found in memcached." );
            } else {
                _logger.info( "Found session with id " + sessionId );
            }
        }
        return session;
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
        _logger.info( "remove invoked," +
        		" session.relocate:  " + session.getNote( SessionTrackerValve.RELOCATE ) +
                ", node failure: " + session.getNote( NODE_FAILURE ) +
                ", id: " + session.getId() );
        if ( session.getNote( NODE_FAILURE ) != Boolean.TRUE ) {
            try {
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
        if ( initialized )
            destroy();
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

}

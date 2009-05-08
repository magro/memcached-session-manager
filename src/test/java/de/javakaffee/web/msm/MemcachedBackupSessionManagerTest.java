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

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.jmock.MockObjectTestCase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests the {@link MemcachedBackupSessionManager}.
 * 
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 * @version $Id$
 */
public class MemcachedBackupSessionManagerTest extends MockObjectTestCase {

    @Before
    public void setUp() throws Exception {
    }

    @After
    public void tearDown() throws Exception {
    }

    @Test
    public final void testRoll() {
        assertEquals( 0, MemcachedBackupSessionManager.roll( 0, 1 ) );
        assertEquals( 1, MemcachedBackupSessionManager.roll( 0, 2 ) );
        assertEquals( 0, MemcachedBackupSessionManager.roll( 1, 2 ) );
    }

    @Test
    public final void testGetNextNodeId_SingleNode() {
        String actual = MemcachedBackupSessionManager.getNextNodeId( "n1", Arrays.asList( "n1" ), null );
        assertNull( "For a sole existing node we cannot get a next node", actual );
    }

    /**
     * Test two memcached nodes:
     * - node n1 is the currently used node, which failed
     * - node n2 must be the next node
     * - node n2 must be recorded as beeing tested
     * 
     * Also test that if the current node is n2, then n1 must be chosen.
     */
    @Test
    public final void testGetNextNodeId_TwoNodes() {
        final String nodeId1 = "n1";
        final String nodeId2 = "n2";
        
        String actual = MemcachedBackupSessionManager.getNextNodeId( nodeId1, Arrays.asList( nodeId1, nodeId2 ), null );
        assertEquals( nodeId2, actual );
        
        /* let's switch nodes, so that the session is bound to node 2
         */
        actual = MemcachedBackupSessionManager.getNextNodeId( nodeId2, Arrays.asList( nodeId1, nodeId2 ), null );
        assertEquals( nodeId1, actual );
    }

    /**
     * Test two memcached nodes:
     * - node n2 is the currently used node, which failed
     * - node n1 was already tested and is excluded therefore
     * - the result must be null
     */
    @Test
    public final void testGetNextNodeId_TwoNodes_NoNodeLeft() {
        final String nodeId1 = "n1";
        final String nodeId2 = "n2";
        String actual = MemcachedBackupSessionManager.getNextNodeId( nodeId2, Arrays.asList( nodeId1, nodeId2 ), asSet( nodeId1 ) );
        assertNull( actual );
    }

    /**
     * Test two memcached nodes with no regular nodes left, so that a failover
     * node is chosen
     */
    @Test
    public final void testGetNextNodeId_RegularNode_NoRegularNodeLeft() {
        final MemcachedBackupSessionManager cut = new MemcachedBackupSessionManager();
        
        final String nodeId1 = "n1";
        cut.setNodeIds( Arrays.asList( nodeId1 ) );
        
        final String nodeId2 = "n2";
        cut.setFailoverNodeIds( Arrays.asList( nodeId2 ) );
        
        String actual = cut.getNextNodeId( nodeId1, null );
        assertEquals( "The failover node is not chosen", nodeId2, actual );
    }

    /**
     * Test two memcached nodes:
     * - with the current node beeing a failover node
     * - regular nodes present
     * 
     * A regular node shall be chosen
     */
    @Test
    public final void testGetNextNodeId_FailoverNode_RegularNodeLeft() {
        final MemcachedBackupSessionManager cut = new MemcachedBackupSessionManager();
        
        final String nodeId1 = "n1";
        cut.setNodeIds( Arrays.asList( nodeId1 ) );
        
        final String nodeId2 = "n2";
        cut.setFailoverNodeIds( Arrays.asList( nodeId2 ) );
        
        String actual = cut.getNextNodeId( nodeId2, null );
        assertEquals( "The regular node is not chosen", nodeId1, actual );
    }

    /**
     * Test two memcached nodes:
     * - with the current node beeing a failover node
     * - no regular nodes left
     * 
     *  no node can be chosen
     */
    @Test
    public final void testGetNextNodeId_FailoverNode_NoRegularNodeLeft() {
        final MemcachedBackupSessionManager cut = new MemcachedBackupSessionManager();
        
        final String nodeId1 = "n1";
        cut.setNodeIds( Arrays.asList( nodeId1 ) );
        
        final String nodeId2 = "n2";
        cut.setFailoverNodeIds( Arrays.asList( nodeId2 ) );
        
        String actual = cut.getNextNodeId( nodeId2, asSet( nodeId1 ) );
        assertNull( actual );
    }

    /**
     * Test three memcached nodes:
     * - with the current node beeing the first failover node
     * - no regular nodes left
     * - another failover node left
     * 
     *  the second failover node must be chosen
     */
    @Test
    public final void testGetNextNodeId_FailoverNode_NoRegularNodeButAnotherFailoverNodeLeft() {
        final MemcachedBackupSessionManager cut = new MemcachedBackupSessionManager();
        
        final String nodeId1 = "n1";
        cut.setNodeIds( Arrays.asList( nodeId1 ) );
        
        final String nodeId2 = "n2";
        final String nodeId3 = "n3";
        cut.setFailoverNodeIds( Arrays.asList( nodeId2, nodeId3 ) );
        
        String actual = cut.getNextNodeId( nodeId2, asSet( nodeId1 ) );
        assertEquals( "The second failover node is not chosen", nodeId3, actual );
    }

    private Set<String> asSet( String ... vals ) {
        final Set<String> result = new HashSet<String>( vals.length );
        for ( String val : vals ) {
            result.add( val );
        }
        return result;
    }

}

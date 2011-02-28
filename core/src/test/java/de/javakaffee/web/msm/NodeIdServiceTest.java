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


import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.testng.Assert;
import org.testng.annotations.Test;

import de.javakaffee.web.msm.NodeAvailabilityCache.CacheLoader;

/**
 * Tests the {@link NodeIdService}.
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class NodeIdServiceTest {

    @Test
    public void testSetNodeAvailability() {
        final String nodeId1 = "n1";
        final CacheLoader<String> cacheLoader = new CacheLoader<String>() {

            @Override
            public boolean isNodeAvailable( final String key ) {
                return true;
            }
        };
        final NodeIdService cut = new NodeIdService( new NodeAvailabilityCache<String>( 10, 100, cacheLoader ),
                NodeIdList.create( nodeId1 ), Collections.<String> emptyList() );
        Assert.assertTrue( cut.isNodeAvailable( nodeId1 ) );
        cut.setNodeAvailable( nodeId1, false );
        Assert.assertFalse( cut.isNodeAvailable( nodeId1 ) );
        cut.setNodeAvailable( nodeId1, true );
        Assert.assertTrue( cut.isNodeAvailable( nodeId1 ) );

    }

    @Test
    public final void testGetNextNodeId_SingleNode() {
        final CacheLoader<String> cacheLoader = new DummyCacheLoader( null );
        final NodeIdService cut = new NodeIdService( new NodeAvailabilityCache<String>( 10, 100, cacheLoader ),
                NodeIdList.create( "n1" ), null );
        final String actual = cut.getAvailableNodeId( "n1" );
        assertNull( actual, "For a sole existing node we cannot get a next node" );
    }

    /**
     * Test two memcached nodes:
     * - node n1 is the currently used node, which failed
     * - node n2 must be the next node
     *
     * Also test that if the current node is n2, then n1 must be chosen.
     */
    @Test
    public final void testGetNextNodeId_TwoNodes() {
        final String nodeId1 = "n1";
        final String nodeId2 = "n2";

        final CacheLoader<String> cacheLoader = new DummyCacheLoader( null );
        final NodeIdService cut = new NodeIdService( new NodeAvailabilityCache<String>( 10, 100, cacheLoader ),
                NodeIdList.create( nodeId1, nodeId2 ), null );

        String actual = cut.getAvailableNodeId( nodeId1 );
        assertEquals( nodeId2, actual );

        /* let's switch nodes, so that the session is bound to node 2
         */
        actual = cut.getAvailableNodeId( nodeId2 );
        assertEquals( nodeId1, actual );
    }

    /**
     * Test two memcached nodes:
     * - node n2 is the currently used node, which failed
     * - node n1 is also unavailable
     * - the result must be null
     */
    @Test
    public final void testGetNextNodeId_TwoNodes_NoNodeLeft() {
        final String nodeId1 = "n1";
        final String nodeId2 = "n2";

        final CacheLoader<String> cacheLoader = new DummyCacheLoader( Arrays.asList( nodeId1 ) );
        final NodeIdService cut = new NodeIdService( new NodeAvailabilityCache<String>( 10, 100, cacheLoader ),
                NodeIdList.create( nodeId1, nodeId2 ), null );

        final String actual = cut.getAvailableNodeId( nodeId2 );
        assertNull( actual );
    }

    /**
     * Test two memcached nodes with no regular nodes left, so that a failover
     * node is chosen
     */
    @Test
    public final void testGetNextNodeId_RegularNode_NoRegularNodeLeft() {

        final String nodeId1 = "n1";
        final String nodeId2 = "n2";

        final NodeIdService cut = new NodeIdService( createNodeAvailabilityCache(),
                NodeIdList.create( nodeId1 ), Arrays.asList( nodeId2 ) );

        final String actual = cut.getAvailableNodeId( nodeId1 );
        assertEquals( nodeId2, actual, "The failover node is not chosen" );
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

        final String nodeId1 = "n1";
        final String nodeId2 = "n2";
        final NodeIdService cut = new NodeIdService( createNodeAvailabilityCache(),
                NodeIdList.create( nodeId1 ), Arrays.asList( nodeId2 ) );

        final String actual = cut.getAvailableNodeId( nodeId2 );
        assertEquals( nodeId1, actual, "The regular node is not chosen" );
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

        final String nodeId1 = "n1";
        final String nodeId2 = "n2";
        final NodeIdService cut = new NodeIdService( createNodeAvailabilityCache( nodeId1 ),
                NodeIdList.create( nodeId1 ), Arrays.asList( nodeId2 ) );

        final String actual = cut.getAvailableNodeId( nodeId2 );
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

        final String nodeId1 = "n1";
        final String nodeId2 = "n2";
        final String nodeId3 = "n3";
        final NodeIdService cut = new NodeIdService( createNodeAvailabilityCache( nodeId1 ),
                NodeIdList.create( nodeId1 ), Arrays.asList( nodeId2, nodeId3 ) );

        final String actual = cut.getAvailableNodeId( nodeId2 );
        assertEquals( nodeId3, actual, "The second failover node is not chosen" );
    }

    private NodeAvailabilityCache<String> createNodeAvailabilityCache( final String ... unavailableNodes ) {
        final List<String> unavailable = unavailableNodes != null ? Arrays.asList( unavailableNodes ) : null;
        return new NodeAvailabilityCache<String>( 10, 100, new DummyCacheLoader( unavailable ) );
    }

    private static final class DummyCacheLoader implements CacheLoader<String> {

        private final List<String> _unavailable;

        private DummyCacheLoader( final List<String> unavailable ) {
            _unavailable = unavailable;
        }

        @Override
        public boolean isNodeAvailable( final String key ) {
            return _unavailable == null || !_unavailable.contains( key );
        }

    }

}

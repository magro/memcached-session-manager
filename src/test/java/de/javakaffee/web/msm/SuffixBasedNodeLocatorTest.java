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

import java.util.ArrayList;
import java.util.List;

import net.spy.memcached.MemcachedNode;

import org.jmock.MockObjectTestCase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests the {@link SuffixBasedNodeLocator}.
 * 
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 * @version $Id$
 */
public class SuffixBasedNodeLocatorTest extends MockObjectTestCase {

    @Before
    public void setUp() throws Exception {
    }

    @After
    public void tearDown() throws Exception {
    }

    @Test
    public final void testGetNextNodeId_NoNodeLeft() {
        final List<MemcachedNode> nodes = new ArrayList<MemcachedNode>();
        nodes.add( (MemcachedNode) mock( MemcachedNode.class ).proxy() );
        final SuffixBasedNodeLocator cut = new SuffixBasedNodeLocator( nodes );
        // we use try/catch here, as the expected attribute of the @Test
        // annotation does not do what it should
        try {
            cut.getNextNodeId( "foo.0" );
            fail( "We shouldn't reach this" );
        } catch( UnavailableNodeException e ) {
            /* we must reach this */
        }
    }

    @Test
    public final void testGetNextNodeId_NodesLeft() {
        final List<MemcachedNode> nodes = new ArrayList<MemcachedNode>();
        nodes.add( (MemcachedNode) mock( MemcachedNode.class ).proxy() );
        nodes.add( (MemcachedNode) mock( MemcachedNode.class ).proxy() );
        final SuffixBasedNodeLocator cut = new SuffixBasedNodeLocator( nodes );
        String nextNodeId = cut.getNextNodeId( "foo.0" );
        assertEquals( "1", nextNodeId );
    }

}

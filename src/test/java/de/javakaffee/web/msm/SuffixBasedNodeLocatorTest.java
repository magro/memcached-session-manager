/*
 * $Id: $ (c)
 * Copyright 2009 freiheit.com technologies GmbH
 *
 * Created on Mar 18, 2009
 *
 * This file contains unpublished, proprietary trade secret information of
 * freiheit.com technologies GmbH. Use, transcription, duplication and
 * modification are strictly prohibited without prior written consent of
 * freiheit.com technologies GmbH.
 */
package de.javakaffee.web.msm;

import java.util.ArrayList;
import java.util.List;

import net.spy.memcached.MemcachedNode;

import org.jmock.MockObjectTestCase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

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

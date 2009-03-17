/*
 * $Id: $ (c)
 * Copyright 2009 freiheit.com technologies GmbH
 *
 * Created on Mar 15, 2009
 *
 * This file contains unpublished, proprietary trade secret information of
 * freiheit.com technologies GmbH. Use, transcription, duplication and
 * modification are strictly prohibited without prior written consent of
 * freiheit.com technologies GmbH.
 */
package de.javakaffee.web.msm;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class LRUCacheTest {

    @Before
    public void setUp() throws Exception {
    }

    @After
    public void tearDown() throws Exception {
    }
    
    @Test
    public void testLRU() {
        final LRUCache<String,String> cut = new LRUCache<String, String>( 3 );
        final String f = "foo";
        final String br = "bar";
        
        cut.put(f, br);
        cut.put(br, "baz");
        
        Assert.assertArrayEquals( "invalid order of items, initially it should be insertion ordered",
                new String[]{ f, br }, cut.getKeys().toArray() );
        
        cut.get( f );
        Assert.assertArrayEquals( "invalid order of items, accessing foo should move it to the end", new String[]{ br, f }, cut.getKeys().toArray() );
        
        cut.get( br );
        Assert.assertArrayEquals( "invalid order of items", new String[]{ f, br }, cut.getKeys().toArray() );

        cut.get( f );
        Assert.assertArrayEquals( "invalid order of items, accessing foo should move it to the end", new String[]{ br, f }, cut.getKeys().toArray() );
        
        cut.put( "baz", "foo" );
        Assert.assertArrayEquals( "invalid order of items, last inserted item should be at last position",
                new String[]{ br, f, "baz" }, cut.getKeys().toArray() );
        
        
    }
    
    @Test
    public void testCacheSize() {
        final LRUCache<String,String> cut = new LRUCache<String, String>( 1 );
        cut.put("foo", "bar");
        Assert.assertEquals( "bar", cut.get("foo") );
        cut.put("bar", "baz");
        Assert.assertEquals( "baz", cut.get("bar") );
        Assert.assertNull( "old key still existing, unexpected cache size", cut.get("foo") );
    }
    
    @Test
    public void testCacheTTL() throws InterruptedException {
        final LRUCache<String,String> cut = new LRUCache<String, String>(1, 100);
        cut.put("foo", "bar");
        Assert.assertEquals( "bar", cut.get("foo") );
        Thread.sleep( 101 );
        Assert.assertNull( "expired key still existing, unexpected cache size", cut.get("foo") );
    }

}

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

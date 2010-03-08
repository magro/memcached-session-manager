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

import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Test the {@link LRUCache}.
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 * @version $Id$
 */
public class LRUCacheTest {

    @Test
    public void testLRU() {
        final LRUCache<String,String> cut = new LRUCache<String, String>( 3 );
        final String f = "foo";
        final String br = "bar";

        cut.put(f, br);
        cut.put(br, "baz");

        Assert.assertTrue( Arrays.equals( new String[]{ f, br }, cut.getKeys().toArray() ),
                "invalid order of items, initially it should be insertion ordered" );

        cut.get( f );
        Assert.assertTrue( Arrays.equals( new String[]{ br, f }, cut.getKeys().toArray() ),
                "invalid order of items, accessing foo should move it to the end" );

        cut.get( br );
        Assert.assertTrue( Arrays.equals( new String[]{ f, br }, cut.getKeys().toArray() ),
                "invalid order of items" );

        cut.get( f );
        Assert.assertTrue( Arrays.equals( new String[]{ br, f }, cut.getKeys().toArray() ),
                "invalid order of items, accessing foo should move it to the end" );

        cut.put( "baz", "foo" );
        Assert.assertTrue( Arrays.equals( new String[]{ br, f, "baz" }, cut.getKeys().toArray() ),
                "invalid order of items, last inserted item should be at last position" );


    }

    @Test
    public void testCacheSize() {
        final LRUCache<String,String> cut = new LRUCache<String, String>( 1 );
        cut.put("foo", "bar");
        Assert.assertEquals( "bar", cut.get("foo") );
        cut.put("bar", "baz");
        Assert.assertEquals( "baz", cut.get("bar") );
        Assert.assertNull( cut.get("foo"), "old key still existing, unexpected cache size" );
    }

    @Test
    public void testCacheTTL() throws InterruptedException {
        final LRUCache<String,String> cut = new LRUCache<String, String>(1, 100);
        cut.put("foo", "bar");
        Assert.assertEquals( "bar", cut.get("foo") );
        Thread.sleep( 101 );
        Assert.assertNull( cut.get("foo"), "expired key still existing, unexpected cache size" );
    }

}

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
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.util.Arrays;
import java.util.Comparator;

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
    public void testRemove() {
        final LRUCache<String,String> cut = new LRUCache<String, String>( 3 );
        cut.put("foo", "bar");
        assertTrue( cut.containsKey( "foo" ) );
        assertEquals( cut.remove( "foo" ), "bar" );
        assertFalse( cut.containsKey( "foo" ) );
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
        Thread.sleep( 120 );
        Assert.assertNull( cut.get("foo"), "expired key still existing, unexpected cache size" );
    }

    @Test
    public void testGetKeysSortedByValue() {
        final LRUCache<String,Integer> cut = new LRUCache<String, Integer>( 3 );
        final String f = "foo";
        final String br = "bar";

        cut.put(f, 1);
        cut.put(br, 2);

        final Comparator<Integer> c = new Comparator<Integer>() {

            @Override
            public int compare( final Integer o1, final Integer o2 ) {
                return o1.compareTo( o2 );
            }

        };

        Assert.assertTrue( Arrays.equals( new String[]{ f, br }, cut.getKeysSortedByValue( c ).toArray() ),
                "invalid order of items, the keys are not order by their values" );

        cut.put(f, 3);
        Assert.assertTrue( Arrays.equals( new String[]{ br, f }, cut.getKeysSortedByValue( c ).toArray() ),
                "invalid order of items, the keys are not order by their values" );
    }

}

/*
 * Copyright 2011 Martin Grotzke
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
import static org.testng.Assert.assertTrue;

import java.util.Arrays;

import org.testng.annotations.Test;

/**
 * Test the {@link ReadOnlyRequestsCache}.
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class ReadOnlyRequestsCacheTest {

    @Test
    public void testRegisterReadOnlyAndModifyingRequests() {
        final ReadOnlyRequestsCache cut = new ReadOnlyRequestsCache();

        // track a request as readonly and check that it's returned
        cut.readOnlyRequest( "foo" );
        readOnlyRequestsShouldContain( cut, "foo", true );
        // track the same request as modifying, it should not longer be there
        cut.modifyingRequest( "foo" );
        readOnlyRequestsShouldContain( cut, "foo", false );

        // track another request as modifying
        cut.modifyingRequest( "bar" );
        readOnlyRequestsShouldContain( cut, "bar", false );
        // after tracking the same request as readonly it should also not be returned as readonly
        cut.readOnlyRequest( "bar" );
        readOnlyRequestsShouldContain( cut, "bar", false );

    }

    @Test
    public void testGetReadOnlyRequestsByFrequency() {
        final ReadOnlyRequestsCache cut = new ReadOnlyRequestsCache();

        // track a request as readonly and check that it's returned
        cut.readOnlyRequest( "foo" );
        cut.readOnlyRequest( "bar" );
        cut.readOnlyRequest( "bar" );
        assertTrue( Arrays.equals( new String[] { "foo", "bar" }, cut.getReadOnlyRequestsByFrequency().toArray() ) );

        cut.readOnlyRequest( "foo" );
        cut.readOnlyRequest( "foo" );
        assertTrue( Arrays.equals( new String[] { "bar", "foo" }, cut.getReadOnlyRequestsByFrequency().toArray() ) );

    }

    private void readOnlyRequestsShouldContain( final ReadOnlyRequestsCache cut, final String key, final boolean shouldBeContained ) {
        assertEquals( cut.isReadOnlyRequest( key ), shouldBeContained );
        assertEquals( cut.getReadOnlyRequests().contains( key ), shouldBeContained );
        assertEquals( cut.getReadOnlyRequestsByFrequency().contains( key ), shouldBeContained );
    }

}

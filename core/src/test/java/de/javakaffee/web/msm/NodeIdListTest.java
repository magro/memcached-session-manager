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
import static org.testng.Assert.assertNull;
import static org.testng.Assert.fail;

import org.testng.annotations.Test;

/**
 * Tests the {@link NodeIdList}.
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class NodeIdListTest {

    @Test
    public void testGetNextNodeId() {
        assertEquals( NodeIdList.create( "n1", "n2" ).getNextNodeId( "n1" ), "n2" );
        assertEquals( NodeIdList.create( "n1", "n2" ).getNextNodeId( "n2" ), "n1" );
        assertNull( NodeIdList.create( "n1" ).getNextNodeId( "n1" ) );
        try {
            assertNull( NodeIdList.create( "n1" ).getNextNodeId( "n2" ) );
            fail( "An unknown node should lead to an illegal arg exception." );
        } catch( final IllegalArgumentException e ) {
            // expected
        }
    }

}

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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Test the {@link SessionIdFormat}.
 * 
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 * @version $Id$
 */
public class SessionIdFormatTest {

    @Before
    public void setUp() throws Exception {
    }

    @After
    public void tearDown() throws Exception {
    }
    
    @Test
    public void testCreateSessionId() {
        final SessionIdFormat cut = new SessionIdFormat();
        assertEquals( "foo-n", cut.createSessionId( "foo", "n" ) );
        assertEquals( "foo-n.jvm1", cut.createSessionId( "foo.jvm1", "n" ) );
    }
    
    @Test
    public void testCreateNewSessionId() {
        final SessionIdFormat cut = new SessionIdFormat();

        assertEquals( "foo-n", cut.createNewSessionId( "foo", "n" ) );
        assertEquals( "foo-m", cut.createNewSessionId( "foo-n", "m" ) );
        assertEquals( "foo-m.jvm1", cut.createNewSessionId( "foo-n.jvm1", "m" ) );
        
    }
    
    @Test
    public void testExtractMemcachedId() throws InterruptedException {
        final SessionIdFormat cut = new SessionIdFormat();
        
        assertEquals( "n", cut.extractMemcachedId( "foo-n" ) );
        assertEquals( "n", cut.extractMemcachedId( "foo-n.jvm1" ) );
    }
    
    @Test
    public void testIsValid() throws InterruptedException {
        final SessionIdFormat cut = new SessionIdFormat();
        
        assertFalse( cut.isValid( "foo" ) );
        assertFalse( cut.isValid( "foo.jvm1-n" ) );
        assertFalse( cut.isValid( "foo.n.jvm1" ) );
        
        assertTrue( cut.isValid( "foo-n" ) );
        assertTrue( cut.isValid( "foo-n.jvm1" ) );
    }

}

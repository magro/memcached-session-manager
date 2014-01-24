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

import org.testng.annotations.Test;

/**
 * Test the {@link SessionIdFormat}.
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 * @version $Id$
 */
public class SessionIdFormatTest {

    @Test
    public void testCreateSessionId() {
        final SessionIdFormat cut = new SessionIdFormat();
        assertEquals( "foo-n", cut.createSessionId( "foo", "n") );
        assertEquals( "foo-n.jvm1", cut.createSessionId( "foo.jvm1", "n") );
        assertEquals( "foo-n.j-v-m1", cut.createSessionId( "foo.j-v-m1", "n") );
    }

    @Test
    public void testCreateNewSessionId() {
        final SessionIdFormat cut = new SessionIdFormat();

        assertEquals( "foo-n", cut.createNewSessionId( "foo", "n") );
        assertEquals( "foo-m", cut.createNewSessionId( "foo-n", "m" ) );
        assertEquals( "foo-m.jvm1", cut.createNewSessionId( "foo-n.jvm1", "m" ) );
        assertEquals( "foo-m.jvm1", cut.createNewSessionId( "foo.jvm1", "m" ) );
        assertEquals( "foo-m.j-v-m1", cut.createNewSessionId( "foo.j-v-m1", "m" ) );

    }

    @Test
    public void testExtractMemcachedId() throws InterruptedException {
        final SessionIdFormat cut = new SessionIdFormat();

        assertEquals( "n", cut.extractMemcachedId( "foo-n" ) );
        assertEquals( "n", cut.extractMemcachedId( "foo-n.jvm1" ) );
        assertEquals( "n", cut.extractMemcachedId( "foo-n.j-v-m1" ) );
        assertEquals( null, cut.extractMemcachedId( "foo.j-v-m1" ) );
    }

    @Test
    public void testIsValid() throws InterruptedException {
        final SessionIdFormat cut = new SessionIdFormat();

        assertFalse( cut.isValid( "foo" ) );
        assertFalse( cut.isValid( "foo.jvm1-n" ) );
        assertFalse( cut.isValid( "foo.n.jvm1" ) );
        assertFalse( cut.isValid( "foo.n.j-v-m1" ) );

        assertTrue( cut.isValid( "foo-n" ) );
        assertTrue( cut.isValid( "foo-n.jvm1" ) );
        assertTrue( cut.isValid( "foo-n.j-v-m1" ) );
    }

    @Test
    public void testCreateBackupKey() {
        final SessionIdFormat cut = new SessionIdFormat(StorageKeyFormat.ofHost("localhost"));
        assertEquals(cut.createBackupKey("foo"), "bak:localhost_foo");
    }

    @Test
    public void testIsBackupKey() {
        final SessionIdFormat cut = new SessionIdFormat(StorageKeyFormat.ofHost("localhost"));
        assertTrue(cut.isBackupKey("bak:localhost_foo"));
    }

    @Test
    public void testCreateLockName() {
        final SessionIdFormat cut = new SessionIdFormat(StorageKeyFormat.ofHost("localhost"));
        assertEquals(cut.createLockName("foo"), "lock:localhost_foo");
    }

    @Test
    public void testCreateValidityInfoKeyName() {
        final SessionIdFormat cut = new SessionIdFormat(StorageKeyFormat.ofHost("localhost"));
        assertEquals(cut.createValidityInfoKeyName("foo"), "validity:localhost_foo");
    }

}

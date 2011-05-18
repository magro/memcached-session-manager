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

import static de.javakaffee.web.msm.integration.TestUtils.assertDeepEquals;
import static de.javakaffee.web.msm.integration.TestUtils.createSession;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.ObjectInputStream;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.apache.catalina.LifecycleException;
import org.apache.catalina.authenticator.Constants;
import org.apache.catalina.core.StandardContext;
import org.apache.catalina.realm.GenericPrincipal;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import de.javakaffee.web.msm.MemcachedSessionService.SessionManager;


/**
 * Test the {@link TranscoderService}.
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public abstract class TranscoderServiceTest {

    private static SessionManager _manager;

    @BeforeMethod
    public void setup() throws LifecycleException, ClassNotFoundException, IOException {
        
        _manager = mock( SessionManager.class );
        
        when( _manager.getContainer() ).thenReturn( new StandardContext() ); // needed for createSession
        when( _manager.newMemcachedBackupSession() ).thenReturn( newMemcachedBackupSession( _manager ) );
        
        final MemcachedSessionService service = new MemcachedSessionService( _manager );
        final MemcachedBackupSession session = createSession( service );
        when( _manager.createSession( anyString() ) ).thenReturn( session );
        
        when( _manager.readPrincipal( (ObjectInputStream)any() ) ).thenReturn( createPrincipal() );
        when( _manager.getMemcachedSessionService() ).thenReturn( service );

    }

    protected abstract MemcachedBackupSession newMemcachedBackupSession( @Nullable SessionManager manager );

    @Test
    public void testSerializeSessionFields() {
        final MemcachedBackupSession session = (MemcachedBackupSession) _manager.createSession( null );
        session.setLastBackupTime( System.currentTimeMillis() );
        final byte[] data = TranscoderService.serializeSessionFields( session );
        final MemcachedBackupSession deserialized = TranscoderService.deserializeSessionFields(data, _manager ).getSession();

        assertSessionFields( session, deserialized );
    }

    @Test
    public void testSerializeSessionFieldsWithAuthenticatedPrincipal() {
        final MemcachedBackupSession session = (MemcachedBackupSession) _manager.createSession( null );

        session.setAuthType( Constants.FORM_METHOD );
        session.setPrincipal( createPrincipal() );

        session.setLastBackupTime( System.currentTimeMillis() );

        final byte[] data = TranscoderService.serializeSessionFields( session );
        final MemcachedBackupSession deserialized = TranscoderService.deserializeSessionFields( data, _manager ).getSession();

        assertSessionFields( session, deserialized );
    }

    @Nonnull
    protected abstract GenericPrincipal createPrincipal();

    @Test
    public void testSerializeSessionWithoutAttributes() {
        final MemcachedBackupSession session = (MemcachedBackupSession) _manager.createSession( null );

        session.setLastBackupTime( System.currentTimeMillis() );

        final TranscoderService transcoderService = new TranscoderService( new JavaSerializationTranscoder( _manager ) );
        final byte[] data = transcoderService.serialize( session );
        final MemcachedBackupSession deserialized = transcoderService.deserialize( data, _manager );

        assertSessionFields( session, deserialized );
    }

    @Test
    public void testSerializeSessionWithAttributes() {
        final MemcachedBackupSession session = (MemcachedBackupSession) _manager.createSession( null );
        final TranscoderService transcoderService = new TranscoderService( new JavaSerializationTranscoder( _manager ) );

        final String value = "bar";
        session.setAttribute( "foo", value );

        session.setLastBackupTime( System.currentTimeMillis() );

        final byte[] data = transcoderService.serialize( session );
        final MemcachedBackupSession deserialized = transcoderService.deserialize( data, _manager );

        assertSessionFields( session, deserialized );
        Assert.assertEquals( value, deserialized.getAttribute( "foo" ) );
    }

    private void assertSessionFields( final MemcachedBackupSession session, final MemcachedBackupSession deserialized ) {
        Assert.assertEquals( session.getCreationTimeInternal(), deserialized.getCreationTimeInternal() );
        Assert.assertEquals( session.getLastAccessedTimeInternal(), deserialized.getLastAccessedTimeInternal() );
        Assert.assertEquals( session.getMaxInactiveInterval(), deserialized.getMaxInactiveInterval() );
        Assert.assertEquals( session.isNewInternal(), deserialized.isNewInternal() );
        Assert.assertEquals( session.isValidInternal(), deserialized.isValidInternal() );
        Assert.assertEquals( session.getThisAccessedTimeInternal(), deserialized.getThisAccessedTimeInternal() );
        Assert.assertEquals( session.getLastBackupTime(), deserialized.getLastBackupTime() );
        Assert.assertEquals( session.getIdInternal(), deserialized.getIdInternal() );
        Assert.assertEquals( session.getAuthType(), deserialized.getAuthType() );
        assertDeepEquals( session.getPrincipal(), deserialized.getPrincipal() );
    }

}

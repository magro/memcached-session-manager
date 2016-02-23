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
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.security.Principal;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.servlet.http.HttpServletRequest;

import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.authenticator.Constants;
import org.apache.catalina.authenticator.SavedRequest;
import org.apache.catalina.core.StandardContext;
import org.apache.catalina.realm.GenericPrincipal;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
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

    protected static SessionManager _manager;

    @BeforeMethod
    public void setup() throws LifecycleException, ClassNotFoundException, IOException {

        _manager = mock( SessionManager.class );

        final Context context = new StandardContext();
        when( _manager.getContext() ).thenReturn( context ); // needed for createSession
        when( _manager.getContainer() ).thenReturn( context ); // needed for createSession
        when( _manager.newMemcachedBackupSession() ).thenAnswer(new Answer<MemcachedBackupSession>() {
            @Override
            public MemcachedBackupSession answer(final InvocationOnMock invocation) throws Throwable {
                return  newMemcachedBackupSession( _manager );
            }
        });

        final MemcachedSessionService service = new DummyMemcachedSessionService<SessionManager>( _manager );
        when( _manager.createSession( anyString() ) ).thenAnswer(new Answer<MemcachedBackupSession>() {
            @Override
            public MemcachedBackupSession answer(final InvocationOnMock invocation) throws Throwable {
                return createSession(service);
            }
        });

        when( _manager.readPrincipal( (ObjectInputStream)any() ) ).thenReturn( createPrincipal() );
        when( _manager.getMemcachedSessionService() ).thenReturn( service );
        when( _manager.willAttributeDistribute(anyString(), any())).thenReturn(true);

    }

    @Nonnull
    protected MemcachedBackupSession newMemcachedBackupSession( @Nullable final SessionManager manager ) {
        return new MemcachedBackupSession( manager );
    }

    @Test
    public void testSerializeSessionFieldsIncludesFormPrincipalNote() {
        final MemcachedBackupSession session = (MemcachedBackupSession) _manager.createSession( null );

        final Principal saved = createPrincipal();
        session.setNote(Constants.FORM_PRINCIPAL_NOTE, saved);

        final byte[] data = TranscoderService.serializeSessionFields( session );
        final MemcachedBackupSession deserialized = TranscoderService.deserializeSessionFields(data, _manager ).getSession();

        final Principal actual = (Principal) deserialized.getNote(Constants.FORM_PRINCIPAL_NOTE);
        assertNotNull(actual);
        assertDeepEquals(actual, saved);
    }

    @Test
    public void testSerializeSessionFieldsIncludesFormRequestNote() {
        final MemcachedBackupSession session = (MemcachedBackupSession) _manager.createSession( null );

        final SavedRequest saved = new SavedRequest();
        saved.setQueryString("foo=bar");
        saved.setRequestURI("http://www.foo.org");
        session.setNote(Constants.FORM_REQUEST_NOTE, saved);

        final byte[] data = TranscoderService.serializeSessionFields( session );
        final MemcachedBackupSession deserialized = TranscoderService.deserializeSessionFields(data, _manager ).getSession();

        final SavedRequest actual = (SavedRequest) deserialized.getNote(Constants.FORM_REQUEST_NOTE);
        assertNotNull(actual);
        assertDeepEquals(actual, saved);
    }

    @Test
    public void testVersionUpgrade() {
        final MemcachedBackupSession session = (MemcachedBackupSession) _manager.createSession( null );

        final byte[] data = TranscoderService.serializeSessionFields( session, TranscoderService.VERSION_1 );
        final byte[] attributesData = TranscoderService.deserializeSessionFields(data, _manager ).getAttributesData();

        // we just check that data is read (w/o) bounds issues and no data
        // is left (we just passed data in, w/o added attributesData appended)
        assertEquals(attributesData.length, 0);
    }

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

        session.setAuthType( HttpServletRequest.FORM_AUTH );
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

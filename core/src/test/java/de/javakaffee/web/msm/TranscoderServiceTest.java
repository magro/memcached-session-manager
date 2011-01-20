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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.apache.catalina.LifecycleException;
import org.apache.catalina.authenticator.Constants;
import org.apache.catalina.core.StandardContext;
import org.apache.catalina.core.StandardEngine;
import org.apache.catalina.core.StandardHost;
import org.apache.catalina.core.StandardService;
import org.apache.catalina.loader.WebappLoader;
import org.apache.catalina.realm.GenericPrincipal;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;


/**
 * Test the {@link TranscoderService}.
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class TranscoderServiceTest {

    private static MemcachedBackupSessionManager _manager;

    @BeforeClass
    public static void setup() throws LifecycleException {

        _manager = new MemcachedBackupSessionManager();
        _manager.setMemcachedNodes( "n1:127.0.0.1:11211" );

        final StandardContext container = new StandardContext();
        container.setPath( "/" );
        final StandardEngine engine = new StandardEngine();
        engine.setService( new StandardService() );
        final StandardHost host = new StandardHost();
        host.setParent( engine );
        container.setParent( host );
        _manager.setContainer( container );

        final WebappLoader webappLoader = mock( WebappLoader.class );
        // webappLoaderControl.expects( once() ).method( "setContainer" ).withAnyArguments();
        when( webappLoader.getClassLoader() ).thenReturn( Thread.currentThread().getContextClassLoader() );
        Assert.assertNotNull( webappLoader.getClassLoader(), "Webapp Classloader is null." );

        _manager.getContainer().setLoader( webappLoader );
        _manager.initInternal();
        _manager.startInternal();

    }

    @Test
    public void testSerializeSessionFields() {
        final MemcachedBackupSession session = (MemcachedBackupSession) _manager.createSession( null );
        final byte[] data = TranscoderService.serializeSessionFields( session );
        final MemcachedBackupSession deserialized = TranscoderService.deserializeSessionFields( data ).getSession();

        assertSessionFields( session, deserialized );
    }

    @Test
    public void testSerializeSessionFieldsWithAuthenticatedPrincipal() {
        final MemcachedBackupSession session = (MemcachedBackupSession) _manager.createSession( null );

        session.setAuthType( Constants.FORM_METHOD );
        session.setPrincipal( new GenericPrincipal( "foo", "bar" ) );

        final byte[] data = TranscoderService.serializeSessionFields( session );
        final MemcachedBackupSession deserialized = TranscoderService.deserializeSessionFields( data ).getSession();

        assertSessionFields( session, deserialized );
    }

    @Test
    public void testSerializeSessionWithoutAttributes() {
        final MemcachedBackupSession session = (MemcachedBackupSession) _manager.createSession( null );
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
        Assert.assertEquals( session.getIdInternal(), deserialized.getIdInternal() );
        Assert.assertEquals( session.getAuthType(), deserialized.getAuthType() );
        assertDeepEquals( session.getPrincipal(), deserialized.getPrincipal() );
    }

}

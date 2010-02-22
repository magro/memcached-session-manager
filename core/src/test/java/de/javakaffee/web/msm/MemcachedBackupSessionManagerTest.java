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

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import net.spy.memcached.MemcachedClient;

import org.apache.catalina.core.StandardContext;
import org.apache.catalina.core.StandardEngine;
import org.apache.catalina.core.StandardHost;
import org.apache.catalina.loader.WebappLoader;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import de.javakaffee.web.msm.MemcachedBackupSessionManager.MemcachedBackupSession;

/**
 * Test the {@link MemcachedBackupSessionManager}.
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class MemcachedBackupSessionManagerTest {

    private MemcachedBackupSessionManager _manager;
    private MemcachedClient _memcachedMock;

    @Before
    public void setup() throws Exception {

        _manager = new MemcachedBackupSessionManager();
        _manager.setMemcachedNodes( "n1:127.0.0.1:11211" );

        final StandardContext container = new StandardContext();
        container.setPath( "/" );
        final StandardHost host = new StandardHost();
        host.setParent( new StandardEngine() );
        container.setParent( host );
        _manager.setContainer( container );

        final WebappLoader webappLoader = mock( WebappLoader.class );
        // webappLoaderControl.expects( once() ).method( "setContainer" ).withAnyArguments();
        when( webappLoader.getClassLoader() ).thenReturn( Thread.currentThread().getContextClassLoader() );
        Assert.assertNotNull( "Webapp Classloader is null.", webappLoader.getClassLoader() );

        _manager.getContainer().setLoader( webappLoader );

        _memcachedMock = mock( MemcachedClient.class );

        @SuppressWarnings( "unchecked" )
        final Future<Boolean> futureMock = mock( Future.class );
        when( futureMock.get( anyInt(), any( TimeUnit.class ) ) ).thenReturn( Boolean.TRUE );
        when( _memcachedMock.set(  any( String.class ), anyInt(), any() ) ).thenReturn( futureMock );

        _manager.init( _memcachedMock );

    }

    @Test
    public void testOnlySendModifiedSessions() {
        final MemcachedBackupSession session = (MemcachedBackupSession) _manager.createSession( null );

        session.setAttribute( "foo", "bar" );
        _manager.backupSession( session );
        verify( _memcachedMock, times( 1 ) ).set( eq( session.getId() ), anyInt(), any() );

        session.setAttribute( "foo", "bar" );
        session.setAttribute( "bar", "baz" );
        _manager.backupSession( session );
        verify( _memcachedMock, times( 2 ) ).set( eq( session.getId() ), anyInt(), any() );

        _manager.backupSession( session );
        verify( _memcachedMock, times( 2 ) ).set( eq( session.getId() ), anyInt(), any() );

    }

}

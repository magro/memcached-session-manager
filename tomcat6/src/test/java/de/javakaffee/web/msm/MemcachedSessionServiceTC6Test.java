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

import de.javakaffee.web.msm.storage.MemcachedStorageClient;
import net.spy.memcached.MemcachedClient;

import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.testng.annotations.Test;

import de.javakaffee.web.msm.MemcachedSessionService.SessionManager;


/**
 * Test the {@link MemcachedBackupSessionManager}.
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
@Test
public class MemcachedSessionServiceTC6Test extends MemcachedSessionServiceTest {

    @Override
    protected SessionManager createSessionManager(Context context) {
        MemcachedBackupSessionManager manager = new MemcachedBackupSessionManager();
        manager.setContainer(context);
        return manager;
    }

    @Override
    protected void startInternal( final SessionManager manager, final MemcachedClient memcachedMock ) throws LifecycleException {
        ((MemcachedBackupSessionManager)manager).startInternal( new MemcachedStorageClient(memcachedMock) );
    };

    @Override
    @Test
    public void testSessionsRefCountHandlingIssue111() throws Exception {
        super.testSessionsRefCountHandlingIssue111();
    }

}

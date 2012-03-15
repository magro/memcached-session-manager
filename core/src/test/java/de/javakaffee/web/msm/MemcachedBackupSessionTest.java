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
 */
package de.javakaffee.web.msm;

import static org.testng.Assert.assertEquals;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * Unit test for {@link MemcachedBackupSession}.
 *
 * @author @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class MemcachedBackupSessionTest {

    private MemcachedBackupSession cut;
    private ExecutorService executor;
    private ExecutorService alternateExecutor;

    @BeforeMethod
    public void beforeMethod() {
        cut = new MemcachedBackupSession();
        executor = Executors.newCachedThreadPool();
        alternateExecutor = Executors.newCachedThreadPool();
    }

    @AfterMethod
    public void afterMethod() {
        executor.shutdown();
    }

    @Test
    public void testRefCount() throws InterruptedException, ExecutionException {
        assertEquals(cut.getRefCount(), 0);
        cut.registerReference();
        assertEquals(cut.getRefCount(), 1);
        assertEquals(cut.getRefCount(), 1);
        cut.releaseReference();
        assertEquals(cut.getRefCount(), 0);

        // other threads must each increment the ref count
        final Runnable registerReference = new Runnable() {
            @Override public void run() { cut.registerReference(); }
        };
        executor.submit(registerReference).get();
        assertEquals(cut.getRefCount(), 1);
        alternateExecutor.submit(registerReference).get();
        assertEquals(cut.getRefCount(), 2);

        // we (no ref registered) must not be able to decrement the ref count
        cut.releaseReference();
        assertEquals(cut.getRefCount(), 2);
    }
}

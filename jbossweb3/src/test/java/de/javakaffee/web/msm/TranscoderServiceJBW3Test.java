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

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.apache.catalina.realm.GenericPrincipal;
import org.testng.annotations.Test;

import de.javakaffee.web.msm.MemcachedSessionService.SessionManager;


/**
 * Test the {@link TranscoderService}.
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
@Test
public class TranscoderServiceJBW3Test extends TranscoderServiceTest {

    @Override
    @Nonnull
    protected GenericPrincipal createPrincipal() {
        return new GenericPrincipal( null, "foo", "bar" );
    }

    @Override
    protected MemcachedBackupSession newMemcachedBackupSession( @Nullable final SessionManager manager ) {
        return new MemcachedBackupSessionJBW3( manager );
    }
    
}

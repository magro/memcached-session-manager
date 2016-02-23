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

import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.ArrayList;

import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.realm.GenericPrincipal;
import org.testng.annotations.Test;


/**
 * Test the {@link TranscoderService}.
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
@Test
public class TranscoderServiceTC8Test extends TranscoderServiceTest {
	
	@Override
	public void setup() throws LifecycleException, ClassNotFoundException,
			IOException {
		super.setup();
        final Context context = (Context)_manager.getContext();
		when( _manager.getContext() ).thenReturn( context ); // needed for createSession
	}

    @Override
    protected GenericPrincipal createPrincipal() {
        return new GenericPrincipal( "foo", "bar", new ArrayList<String>() );
    }

}

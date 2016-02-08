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
 *
 */
package de.javakaffee.web.msm.integration;

import org.testng.annotations.Test;

/**
 * Integration test testing non-sticky sessions.
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
@Test
public class NonStickySessionsIntegrationTC7Test extends NonStickySessionsIntegrationTest {

    @Override
    TestUtils<?> getTestUtils() {
        return new TestUtilsTC7();
    }

    @Override
    protected int getExpectedHitsForNoSessionAccess() {
        // for testSessionNotLoadedForNoSessionAccess, see the comment there.
        return 2;
    }

}

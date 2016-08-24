/*
 * Copyright 2016 Markus Ellinger
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

import org.testng.annotations.Test;

import de.javakaffee.web.msm.integration.TestUtils;
import de.javakaffee.web.msm.integration.TestUtilsTC6;

/**
 * Integration test testing session manager functionality with Redis.
 *
 * @author <a href="mailto:markus@ellinger.it">Markus Ellinger</a>
 */
@Test
public class RedisIntegrationTC6Test extends RedisIntegrationTest {

    @Override
    TestUtils<?> getTestUtils() {
        return new TestUtilsTC6();
    }

}

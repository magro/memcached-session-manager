/*
 * Copyright 2013 Martin Grotzke
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

/**
 * memcached specific utilities.
 *
 * @author @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
class MemcachedUtil {

    private static final int THIRTY_DAYS = 60*60*24*30;

    static int toMemcachedExpiration(final int expirationInSeconds) {
        return expirationInSeconds <= THIRTY_DAYS ? expirationInSeconds : (int)(System.currentTimeMillis() / 1000) + expirationInSeconds;
    }
}

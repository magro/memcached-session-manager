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
 *
 */
package de.javakaffee.web.msm;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

/**
 * Configuration keys and helper functions.
 *
 * @author @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public final class Configurations {

    /**
     * The TTL for {@link NodeAvailabilityCache} entries, in millis.
     */
    public static final String NODE_AVAILABILITY_CACHE_TTL_KEY = "msm.nodeAvailabilityCacheTTL";
    /**
     * The max reconnect delay for the MemcachedClient, in seconds.
     */
    public static final String MAX_RECONNECT_DELAY_KEY = "msm.maxReconnectDelay";

    private static final Log LOG = LogFactory.getLog(Configurations.class);

    public static int getSystemProperty(final String propName, final int defaultValue) {
        final String value = System.getProperty(propName);
        if(value != null) {
            try {
                return Integer.parseInt(value);
            } catch(final NumberFormatException e) {
                LOG.warn("Could not parse configured value for system property '" + propName + "': " + value);
            }
        }
        return defaultValue;
    }

    public static long getSystemProperty(final String propName, final long defaultValue) {
        final String value = System.getProperty(propName);
        if(value != null) {
            try {
                return Long.parseLong(value);
            } catch(final NumberFormatException e) {
                LOG.warn("Could not parse configured value for system property '" + propName + "': " + value);
            }
        }
        return defaultValue;
    }

}

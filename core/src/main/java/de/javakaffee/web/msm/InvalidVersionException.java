/*
 * Copyright 2010 Martin Grotzke
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

/**
 * This exception is thrown, if session data was loaded from memcached but the
 * version does not match the current version.
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class InvalidVersionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final short _version;

    /**
     * Creates a new {@link InvalidVersionException}.
     * @param msg the error message
     * @param version the version (the first 2 bytes) read from the session data that was loaded from memcached.
     */
    public InvalidVersionException( final String msg, final short version ) {
        super( msg );
        _version = version;
    }

    /**
     * The version (the first 2 bytes) read from the session data that was loaded from memcached.
     * @return the version
     */
    public short getVersion() {
        return _version;
    }

}

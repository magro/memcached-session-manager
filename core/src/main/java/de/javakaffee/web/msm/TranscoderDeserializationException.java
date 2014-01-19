/*
 * Copyright 2014 Marcus Thiesen
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
 * Thrown by transcoders/serializers when there's an error during session attributes deserialization.
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class TranscoderDeserializationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public TranscoderDeserializationException() {
    }

    public TranscoderDeserializationException( final String message ) {
        super( message );
    }

    public TranscoderDeserializationException( final Throwable cause ) {
        super( cause );
    }

    public TranscoderDeserializationException( final String message, final Throwable cause ) {
        super( message, cause );
    }

}

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

public class TranscoderDeserializationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public TranscoderDeserializationException() {
    }

    public TranscoderDeserializationException( String message ) {
        super( message );
    }

    public TranscoderDeserializationException( Throwable cause ) {
        super( cause );
    }

    public TranscoderDeserializationException( String message, Throwable cause ) {
        super( message, cause );
    }

}

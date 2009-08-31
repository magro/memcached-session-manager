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


import org.apache.catalina.connector.Response;
import org.apache.coyote.ActionCode;
import org.apache.coyote.ActionHook;

/**
 * This {@link ActionHook} adds a cookie if required to the response before it's committed.
 * 
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 * @version $Id$
 */
public abstract class CommitInterceptingActionHook implements ActionHook {
    
    private final Response _response;
    private final ActionHook _delegate;

    public CommitInterceptingActionHook( final Response response, final ActionHook delegate ) {
        _response = response;
        _delegate = delegate;
    }

    public void action( ActionCode actionCode, Object param ) {
        if (actionCode == ActionCode.ACTION_COMMIT && !_response.isCommitted()) {
            beforeCommit();
        }
        _delegate.action( actionCode, param );
    }

    abstract void beforeCommit();

}

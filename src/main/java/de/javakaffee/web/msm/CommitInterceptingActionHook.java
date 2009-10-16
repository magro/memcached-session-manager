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

import org.apache.coyote.ActionCode;
import org.apache.coyote.ActionHook;
import org.apache.coyote.Response;

/**
 * This {@link ActionHook} invokes {@link #beforeCommit()} if
 * {@link #action(ActionCode, Object)} is invoked with
 * {@link ActionCode#ACTION_COMMIT} and if the response is not yet committed.
 * 
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 * @version $Id$
 */
public abstract class CommitInterceptingActionHook implements ActionHook {

    private final Response _response;
    private final ActionHook _delegate;

    /**
     * Create a new <code>CommitInterceptingActionHook</code> for the provided
     * response and {@link ActionHook} delegate. Both must not be
     * <code>null</code>
     * 
     * @param response
     *            the response that is checked for its
     *            {@link Response#isCommitted()} property, must not be
     *            <code>null</code>.
     * @param delegate
     *            the action hook to delegate the
     *            {@link ActionHook#action(ActionCode, Object)} to, must not be
     *            <code>null</code>.
     */
    public CommitInterceptingActionHook( final Response response, final ActionHook delegate ) {
        if ( response == null || delegate == null ) {
            throw new IllegalArgumentException( "The provided response and action hook must not be null." + ( response == null
                ? " Response is null."
                : "" ) + ( delegate == null
                ? " ActionHook is null."
                : "" ) );
        }
        _response = response;
        _delegate = delegate;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void action( final ActionCode actionCode, final Object param ) {
        if ( actionCode == ActionCode.ACTION_COMMIT && !_response.isCommitted() ) {
            beforeCommit();
        }
        _delegate.action( actionCode, param );
    }

    /**
     * Is invoked before the reponse is committed.
     */
    abstract void beforeCommit();

}

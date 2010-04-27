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

import java.lang.reflect.Method;

import javax.servlet.http.Cookie;

import org.apache.catalina.Context;
import org.apache.catalina.connector.Response;

/**
 * This class abstracts the invocation of setting the session cookie on the
 * {@link Response}, which can include the HttpOnly flag configured at
 * the {@link Context} ({@link Context#setUseHttpOnly(boolean)}) for tomcat >= 6.0.19
 * (via {@link Response#addCookieInternal(Cookie, boolean)})
 * or just invokes {@link Response#addCookieInternal(Cookie)} if the HttpOnly flag
 * is not yet supported (for previous tomcat versions).
 * <p>
 * This was introduced for feature request
 * #54: "Support session cookie 'HttpOnly' flag when session id is rewritten due to memcached failover"
 * @since 1.3.0
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
abstract class AddCookieInteralStrategy {

    static AddCookieInteralStrategy createFor( final Context context ) {
        try {
            final Method getUseHttpOnly = Context.class.getMethod( "getUseHttpOnly" );
            return createUseHttpOnlyStrategy( getUseHttpOnly, context );
        } catch ( final SecurityException e ) {
            throw new RuntimeException( e );
        } catch ( final NoSuchMethodException e ) {
            return new LegacyAddCookieInteralStrategy();
        }
    }

    private static AddCookieInteralStrategy createUseHttpOnlyStrategy( final Method getUseHttpOnly, final Context context ) {
        try {
            final boolean useHttpOnly = ( (Boolean) getUseHttpOnly.invoke( context ) ).booleanValue();
            return new AddCookieInteralStrategyUseHttpOnly( useHttpOnly );
        } catch ( final Exception e ) {
            throw new RuntimeException( "Caught unexpected exception while invoking context.getUseHttpOnly.", e );
        }
    }

    /**
     * Add the provided session cookie to the response, using the HttpOnly flag
     * configured at the Context for tomcat >= 6.0.19.
     * @param newCookie the session cookie to add
     * @param response the response for setting the cookie
     */
    abstract void addCookieInternal( Cookie newCookie, Response response );

    /**
     * The AddCookieInteralStrategy for tomcat < 6.0.19.
     */
    private static final class LegacyAddCookieInteralStrategy extends AddCookieInteralStrategy {

        /**
         * {@inheritDoc}
         */
        @Override
        void addCookieInternal( final Cookie newCookie, final Response response ) {
            response.addCookieInternal( newCookie );
        }

    }

    /**
     * The AddCookieInteralStrategy for tomcat >= 6.0.19 with support for
     * {@link Response#addCookieInternal(Cookie, boolean)}.
     */
    private static final class AddCookieInteralStrategyUseHttpOnly extends AddCookieInteralStrategy {

        private final boolean _useHttpOnly;

        AddCookieInteralStrategyUseHttpOnly( final boolean useHttpOnly ) {
            _useHttpOnly = useHttpOnly;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        void addCookieInternal( final Cookie newCookie, final Response response ) {
            response.addCookieInternal( newCookie, _useHttpOnly );
        }

    }

}
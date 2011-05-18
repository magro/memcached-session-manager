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

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.apache.catalina.Context;
import org.apache.catalina.Globals;

/**
 * This valve is used for tracking requests for that the session must be sent to
 * memcached.
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 * @version $Id$
 */
class SessionTrackerValveJBW3 extends SessionTrackerValve {

    /**
     * Creates a new instance with the given ignore pattern and
     * {@link SessionBackupService}.
     *
     * @param ignorePattern
     *            the regular expression for request uris to ignore
     * @param context
     *            the catalina context of this valve
     * @param sessionBackupService
     *            the service that actually backups sessions
     * @param statistics
     *            used to store statistics
     * @param enabled
     *            specifies if memcached-session-manager is enabled or not.
     *            If <code>false</code>, each request is just processed without doing anything further.
     */
    public SessionTrackerValveJBW3( @Nullable final String ignorePattern, @Nonnull final Context context,
            @Nonnull final SessionBackupService sessionBackupService,
            @Nonnull final Statistics statistics,
            @Nonnull final AtomicBoolean enabled ) {
        super( ignorePattern, context, sessionBackupService, statistics, enabled );
    }

    @Override
    protected String getSessionCookieName( final Context context ) {
        String result = getSessionCookieNameFromContext( context );
        if ( result == null ) {
            result = Globals.SESSION_COOKIE_NAME;
            _log.debug( "Using session cookie name from context: " + result );
        }
        return result;
    }

    @CheckForNull
    private String getSessionCookieNameFromContext( final Context context ) {
        // since 6.0.27 the session cookie name, domain and path is configurable per context,
        // see issue http://issues.apache.org/bugzilla/show_bug.cgi?id=48379
        try {
            final Method getSessionCookieName = Context.class.getDeclaredMethod( "getSessionCookieName" );
            final String result = (String) getSessionCookieName.invoke( context );
            if ( result != null ) {
                _log.debug( "Using session cookie name from context: " + result );
            }
            return result;
        } catch( final NoSuchMethodException e ) {
            // the context does not provide the method getSessionCookieName
        } catch ( final Exception e ) {
            throw new RuntimeException( "Could not read session cookie name from context.", e );
        }
        return null;
    }

}

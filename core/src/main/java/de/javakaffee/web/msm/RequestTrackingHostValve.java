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

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import org.apache.catalina.Context;
import org.apache.catalina.Host;

import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

/**
 * This valve is used for tracking requests for that the session must be sent to
 * memcached, on host level. This encapsulates/surrounds als container request
 * processing like e.g. authentication and ServletRequestListener notification.
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 * @version $Id$
 */
public class RequestTrackingHostValve extends ValveBase {

    private static final String REQUEST_IGNORED = "de.javakaffee.msm.request.ignored";

    public static final String REQUEST_PROCESS = "de.javakaffee.msm.request.process";

    public static final String SESSION_ID_CHANGED = "de.javakaffee.msm.sessionIdChanged";

    public static final String REQUEST_PROCESSED = "de.javakaffee.msm.request.processed";

    static final String RELOCATE = "session.relocate";

    protected static final Log _log = LogFactory.getLog( RequestTrackingHostValve.class );

    private final Pattern _ignorePattern;
    private final MemcachedSessionService _sessionBackupService;
    private final Statistics _statistics;
    private final AtomicBoolean _enabled;
    protected final String _sessionCookieName;
    private @CheckForNull LockingStrategy _lockingStrategy;

    private static final String MSM_REQUEST_ID = "msm.requestId";

	private static final boolean IS_TOMCAT_6;
	static {
		Method getHeaderValues = null;
		try {
			getHeaderValues = Response.class.getMethod("getHeaderValues", String.class);
		} catch(final NoSuchMethodException e) {
			//Do nothing
		}

		IS_TOMCAT_6 = (getHeaderValues != null);
	}

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
    public RequestTrackingHostValve( @Nullable final String ignorePattern, @Nonnull final String sessionCookieName,
            @Nonnull final MemcachedSessionService sessionBackupService,
            @Nonnull final Statistics statistics,
            @Nonnull final AtomicBoolean enabled ) {
        if ( ignorePattern != null ) {
            _log.info( "Setting ignorePattern to " + ignorePattern );
            _ignorePattern = Pattern.compile( ignorePattern );
        } else {
            _ignorePattern = null;
        }
        _sessionCookieName = sessionCookieName;
        _sessionBackupService = sessionBackupService;
        _statistics = statistics;
        _enabled = enabled;
    }

    /**
     * Returns the actually used name for the session cookie.
     * @return the cookie name, never null.
     */
    protected String getSessionCookieName() {
        return _sessionCookieName;
    }

    public boolean isIgnoredRequest() {
        final Request request = _lockingStrategy == null ? null : _lockingStrategy.getCurrentRequest();
        return request != null && request.getNote(REQUEST_IGNORED) == Boolean.TRUE;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void invoke( final Request request, final Response response ) throws IOException, ServletException {

        final String requestId = getURIWithQueryString( request );
        Context context = (Context) _sessionBackupService.getManager().getContainer();
        Host host = (Host) _sessionBackupService.getManager().getContainer().getParent();
        if(!_enabled.get() || !container.equals(host) || !request.getRequestURI().startsWith(context.getPath())) {
            getNext().invoke( request, response );
        } else if ( _ignorePattern != null && _ignorePattern.matcher( requestId ).matches() ) {
            if(_log.isDebugEnabled()) {
                _log.debug( ">>>>>> Ignoring: " + requestId + " (requestedSessionId "+ request.getRequestedSessionId() +") ==================" );
            }

            try {
                storeRequestThreadLocal( request );
                request.setNote(REQUEST_IGNORED, Boolean.TRUE);
                getNext().invoke( request, response );
            } finally {
                if(request.getNote(REQUEST_PROCESSED) == Boolean.TRUE) {
                    final String sessionId = getSessionId(request, response);
                    if(sessionId != null) {
                        _sessionBackupService.requestFinished(sessionId, requestId);
                    }
                }
                resetRequestThreadLocal();
            }
            if(_log.isDebugEnabled()) {
                _log.debug( "<<<<<< Ignored: " + requestId + " ==================" );
            }
        } else {

            request.setNote(REQUEST_PROCESS, Boolean.TRUE);

            if ( _log.isDebugEnabled() ) {
                _log.debug( ">>>>>> Request starting: " + requestId + " (requestedSessionId "+ request.getRequestedSessionId() +") ==================" );
            }

            try {
                storeRequestThreadLocal( request );
                getNext().invoke( request, response );
            } finally {
                final Boolean sessionIdChanged = (Boolean) request.getNote(SESSION_ID_CHANGED);
                backupSession( request, response, sessionIdChanged == null ? false : sessionIdChanged.booleanValue() );
                resetRequestThreadLocal();
            }

            if ( _log.isDebugEnabled() ) {
                logDebugRequestSessionCookie( request );
                logDebugResponseCookie( response );
                _log.debug( "<<<<<< Request finished: " + requestId + " ==================" );
            }

        }
    }

    protected void logDebugRequestSessionCookie( final Request request ) {
        final Cookie[] cookies = request.getCookies();
        if ( cookies == null ) {
            return;
        }
        for( final javax.servlet.http.Cookie cookie : cookies ) {
            if ( cookie.getName().equals( _sessionCookieName ) ) {
                _log.debug( "Have request session cookie: domain=" + cookie.getDomain() + ", maxAge=" + cookie.getMaxAge() +
                        ", path=" + cookie.getPath() + ", value=" + cookie.getValue() +
                        ", version=" + cookie.getVersion() + ", secure=" + cookie.getSecure() );
            }
        }
    }

    @Nonnull
    protected static String getURIWithQueryString( @Nonnull final Request request ) {
        final Object note = request.getNote(MSM_REQUEST_ID);
        if(note != null) {
            // we have a string and want to save cast
            return note.toString();
        }
        final StringBuilder sb = new StringBuilder(30);
        sb.append(request.getMethod())
        .append(' ')
        .append(request.getRequestURI());
        if(!isPostMethod(request) && request.getQueryString() != null) {
            sb.append('?').append(request.getQueryString());
        }
        final String result = sb.toString();
        request.setNote(MSM_REQUEST_ID, result);
        return result;
    }

	protected static boolean isPostMethod(final Request request) {
		final String method = request.getMethod();
		if ( method == null && _log.isDebugEnabled() ) {
			_log.debug("No method set for request " + request.getRequestURI() +
					(request.getQueryString() != null ? "?" + request.getQueryString() : ""));
		}
		return method != null ? method.toLowerCase().equals( "post" ) : false;
	}

    private void resetRequestThreadLocal() {
        if ( _lockingStrategy != null ) {
            _lockingStrategy.onRequestFinished();
        }
    }

    private void storeRequestThreadLocal( @Nonnull final Request request ) {
        if ( _lockingStrategy != null ) {
            _lockingStrategy.onRequestStart( request );
        }
    }

    private void backupSession( final Request request, final Response response, final boolean sessionIdChanged ) {

        /*
         * Do we have a session?
         */
        final String sessionId = getSessionId(request, response);
        if ( sessionId != null ) {
            _statistics.requestWithSession();
            _sessionBackupService.backupSession( sessionId, sessionIdChanged, getURIWithQueryString( request ) );
        }
        else {
            _statistics.requestWithoutSession();
        }

    }

    private String getSessionId(final Request request, final Response response) {
        final String sessionId = getSessionIdFromResponseSessionCookie( response );
        return sessionId != null ? sessionId : request.getRequestedSessionId();
    }

    private String getSessionIdFromResponseSessionCookie(final Response response) {
        final String[] headers = getResponseSetCookieHeaders(response);
        if (headers == null) {
            return null;
        }
        for (final String header : headers) {
            if (header != null && header.contains(_sessionCookieName)) {
                final String sessionIdPrefix = _sessionCookieName + "=";
                final int idxNameStart = header.indexOf(sessionIdPrefix);
                final int idxValueStart = idxNameStart + sessionIdPrefix.length();
                int idxValueEnd = header.indexOf(';', idxNameStart);
                if (idxValueEnd == -1) {
                    idxValueEnd = header.indexOf(' ', idxValueStart);
                }
                if (idxValueEnd == -1) {
                    idxValueEnd = header.length();
                }
                return header.substring(idxValueStart, idxValueEnd);
            }
        }
        return null;
    }

    private String[] getResponseSetCookieHeaders(final Response response) {
        try {
            if (IS_TOMCAT_6) {
                final Method getHeaderValues = response.getClass().getMethod("getHeaderValues", String.class);
                final String[] result = (String[]) getHeaderValues.invoke(response, "Set-Cookie");
                return result;
            } else {
                final Method getHeaders = response.getClass().getMethod("getHeaders", String.class);
                @SuppressWarnings("unchecked")
                final
                Collection<String> result = (Collection<String>) getHeaders.invoke(response, "Set-Cookie");
                return result.toArray(new String[result.size()]);
            }
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void logDebugResponseCookie( final Response response ) {
        final String header = response.getHeader("Set-Cookie");
        if ( header != null && header.contains( _sessionCookieName ) ) {
            _log.debug( "Request finished, with Set-Cookie header: " + header );
        }
    }

    public void setLockingStrategy( @Nullable final LockingStrategy lockingStrategy ) {
        _lockingStrategy = lockingStrategy;
    }

}

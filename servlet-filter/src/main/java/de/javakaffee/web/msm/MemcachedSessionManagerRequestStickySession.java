/**
 * 
 */
package de.javakaffee.web.msm;

import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpSession;

/**
 * msm request for sticky sessions - loads session from memcached if not found locally.
 * 
 * @author Martin Grotzke
 */
public class MemcachedSessionManagerRequestStickySession extends HttpServletRequestWrapper {

    private final MsmSessionStore msmSessionStore;
    private HttpSession accessedSession;

    public MemcachedSessionManagerRequestStickySession(final HttpServletRequest request,
            final MsmSessionStore msmSessionStore) {
        super(request);
        this.msmSessionStore = msmSessionStore;
    }

    public HttpSession getAccessedSession() {
        return accessedSession;
    }

    @Override
    public HttpSession getSession() {
        return getSession(true);
    }

    @Override
    public HttpSession getSession(final boolean create) {
        // always get the session from the manager, as this affects
        // lastAccessedTimestamp.
        HttpSession session = super.getSession(false);
        if (session == null) {
            final Map<String, Object> sessionAttributes = msmSessionStore
                    .loadSessionAttributes(getRequestedSessionId());
            if (sessionAttributes != null) {
                session = swapIn(sessionAttributes);
            }
        }
        if (session == null && create) {
            session = super.getSession(true);
        }
        if (session != null && accessedSession == null) {
            accessedSession = session;
        }
        return session;
    }

    private HttpSession swapIn(final Map<String, Object> sessionAttributes) {
        final HttpSession session = super.getSession(true);
        final Set<Entry<String, Object>> entrySet = sessionAttributes.entrySet();
        for (final Entry<String, Object> entry : entrySet) {
            session.setAttribute(entry.getKey(), entry.getValue());
        }
        return session;
    }

    @Override
    public boolean isRequestedSessionIdValid() {
        // TODO: perhaps we should override this and check if we have a valid session
        return super.isRequestedSessionIdValid();
    }

}

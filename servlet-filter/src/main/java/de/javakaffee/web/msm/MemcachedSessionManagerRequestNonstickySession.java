/**
 * 
 */
package de.javakaffee.web.msm;

import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpSession;

/**
 * msm request for non-sticky sessions - loads session always from memcached, sipping the container session manager.
 * 
 * @author Martin Grotzke
 */
public class MemcachedSessionManagerRequestNonstickySession extends HttpServletRequestWrapper {

    private final MsmSessionStore msmSessionStore;
    private HttpSession accessedSession;
    private final ServletContext servletContext;

    public MemcachedSessionManagerRequestNonstickySession(final HttpServletRequest request,
            final MsmSessionStore msmSessionStore, final ServletContext servletContext) {
        super(request);
        this.msmSessionStore = msmSessionStore;
        this.servletContext = servletContext;
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
        if (accessedSession != null) {
            return accessedSession;
        }

        HttpSession session = null;
        final Map<String, Object> sessionAttributes = msmSessionStore.loadSessionAttributes(getRequestedSessionId());
        if (sessionAttributes != null) {
            session = new SimpleHttpSession(servletContext, getOrGenerateSessionId());
            addAttributesToSession(sessionAttributes, session);
        }

        if (session == null && create) {
            session = new SimpleHttpSession(servletContext, getOrGenerateSessionId());
        }
        if (session != null) {
            accessedSession = session;
        }

        return session;
    }

    private String getOrGenerateSessionId() {
        String result = getRequestedSessionId();
        if (result == null) {
            // TODO generate secure sessionId
            result = String.valueOf(System.currentTimeMillis()) + new Random().nextInt() + getRemoteAddr().hashCode();
        }
        return result;
    }

    private HttpSession addAttributesToSession(final Map<String, Object> sessionAttributes, final HttpSession session) {
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

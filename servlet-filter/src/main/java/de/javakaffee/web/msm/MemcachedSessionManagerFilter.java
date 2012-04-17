/**
 * 
 */
package de.javakaffee.web.msm;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import net.spy.memcached.MemcachedClient;

/**
 * The entry point for msm, wraps existing request by MemcachedSessionManagerRequest.
 * 
 * @author Martin Grotzke
 */
public class MemcachedSessionManagerFilter implements Filter {

    private MemcachedClient memcached;
    private MsmSessionStore msmSessionStore;
    private ServletContext servletContext;

    @Override
    public void init(final FilterConfig filterConfig) throws ServletException {
        try {
            memcached = new MemcachedClient(new InetSocketAddress("localhost", 11211));
        } catch (final IOException e) {
            throw new ServletException("Could not create MemcachedClient", e);
        }
        msmSessionStore = new MsmSessionStore(memcached);
        servletContext = filterConfig.getServletContext();
    }

    @Override
    public void doFilter(final ServletRequest request, final ServletResponse response, final FilterChain chain)
            throws IOException, ServletException {
        if (request instanceof HttpServletRequest) {
            final MemcachedSessionManagerRequestStickySession msmRequest = new MemcachedSessionManagerRequestStickySession(
                    (HttpServletRequest) request, msmSessionStore);
            try {
                chain.doFilter(msmRequest, response);
            } finally {
                final HttpSession accessedSession = msmRequest.getAccessedSession();
                if (accessedSession != null) {
                    msmSessionStore.saveSessionAttributes(accessedSession.getId(), getAttributes(accessedSession));
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getAttributes(final HttpSession session) {
        final Map<String, Object> result = new HashMap<String, Object>();
        final Enumeration<String> attributeNames = session.getAttributeNames();
        while (attributeNames.hasMoreElements()) {
            final String name = attributeNames.nextElement();
            result.put(name, session.getAttribute(name));
        }
        return result;
    }

    @Override
    public void destroy() {
        memcached.shutdown();
    }

}

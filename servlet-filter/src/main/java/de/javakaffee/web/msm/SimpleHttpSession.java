/**
 * 
 */
package de.javakaffee.web.msm;

import java.util.Enumeration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Vector;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionBindingEvent;
import javax.servlet.http.HttpSessionBindingListener;
import javax.servlet.http.HttpSessionContext;

/**
 * @author Martin Grotzke
 * 
 */
public class SimpleHttpSession implements HttpSession {

    private final String id;

    private final long creationTime = System.currentTimeMillis();

    private int maxInactiveInterval;

    private long lastAccessedTime = System.currentTimeMillis();

    private final ServletContext servletContext;

    private final Map<String, Object> attributes = new LinkedHashMap<String, Object>();

    private boolean invalid = false;

    private boolean isNew = true;

    /**
     * Create a new SimpleHttpSession.
     * 
     * @param servletContext
     *            the ServletContext that the session runs in
     * @param id
     *            a unique identifier for this session
     */
    public SimpleHttpSession(final ServletContext servletContext, final String id) {
        this.servletContext = servletContext;
        this.id = id;
    }

    @Override
    public long getCreationTime() {
        return this.creationTime;
    }

    @Override
    public String getId() {
        return this.id;
    }

    public void access() {
        this.lastAccessedTime = System.currentTimeMillis();
        this.isNew = false;
    }

    @Override
    public long getLastAccessedTime() {
        return this.lastAccessedTime;
    }

    @Override
    public ServletContext getServletContext() {
        return this.servletContext;
    }

    @Override
    public void setMaxInactiveInterval(final int interval) {
        this.maxInactiveInterval = interval;
    }

    @Override
    public int getMaxInactiveInterval() {
        return this.maxInactiveInterval;
    }

    @Override
    public HttpSessionContext getSessionContext() {
        throw new UnsupportedOperationException("getSessionContext");
    }

    @Override
    public Object getAttribute(final String name) {
        return this.attributes.get(name);
    }

    @Override
    public Object getValue(final String name) {
        return getAttribute(name);
    }

    @Override
    public Enumeration<String> getAttributeNames() {
        return new Vector<String>(this.attributes.keySet()).elements();
    }

    @Override
    public String[] getValueNames() {
        return this.attributes.keySet().toArray(new String[this.attributes.size()]);
    }

    @Override
    public void setAttribute(final String name, final Object value) {
        if (value != null) {
            this.attributes.put(name, value);
            if (value instanceof HttpSessionBindingListener) {
                ((HttpSessionBindingListener) value).valueBound(new HttpSessionBindingEvent(this, name, value));
            }
        } else {
            removeAttribute(name);
        }
    }

    @Override
    public void putValue(final String name, final Object value) {
        setAttribute(name, value);
    }

    @Override
    public void removeAttribute(final String name) {
        final Object value = this.attributes.remove(name);
        if (value instanceof HttpSessionBindingListener) {
            ((HttpSessionBindingListener) value).valueUnbound(new HttpSessionBindingEvent(this, name, value));
        }
    }

    @Override
    public void removeValue(final String name) {
        removeAttribute(name);
    }

    /**
     * Clear all of this session's attributes.
     */
    public void clearAttributes() {
        for (final Iterator<Map.Entry<String, Object>> it = this.attributes.entrySet().iterator(); it.hasNext();) {
            final Map.Entry<String, Object> entry = it.next();
            final String name = entry.getKey();
            final Object value = entry.getValue();
            it.remove();
            if (value instanceof HttpSessionBindingListener) {
                ((HttpSessionBindingListener) value).valueUnbound(new HttpSessionBindingEvent(this, name, value));
            }
        }
    }

    @Override
    public void invalidate() {
        this.invalid = true;
        clearAttributes();
    }

    public boolean isInvalid() {
        return this.invalid;
    }

    public void setNew(final boolean value) {
        this.isNew = value;
    }

    @Override
    public boolean isNew() {
        return this.isNew;
    }

}

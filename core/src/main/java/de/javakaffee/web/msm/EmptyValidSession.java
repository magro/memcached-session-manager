/*
 * Copyright 2011 Martin Grotzke
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
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.security.Principal;
import java.util.Enumeration;
import java.util.Iterator;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpSession;

import org.apache.catalina.Context;
import org.apache.catalina.Manager;
import org.apache.catalina.SessionListener;
import org.apache.catalina.connector.CoyoteAdapter;
import org.apache.catalina.session.StandardSession;

/**
 * The session is used in a non-sticky setup with lockingMode=auto when the container
 * ({@link CoyoteAdapter}) asks for a session to determine if the sessionId that came with the
 * session cookie represents a valid session. Therefore, the only method provided/implemented
 * by this session is {@link #isValid()}.
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public final class EmptyValidSession extends StandardSession {

    private static final long serialVersionUID = 1L;

    public EmptyValidSession(final int maxInactiveInterval, final long lastAccessedTime, final long thisAccessedTime) {
        super( null );
        this.maxInactiveInterval = maxInactiveInterval;
        // tomcat7 with STRICT_SERVLET_COMPLIANCE/LAST_ACCESS_AT_START compares the lastAccessedTime instead of
        // thisAccessedTime for idle time comparison
        this.lastAccessedTime = lastAccessedTime;
        this.thisAccessedTime = thisAccessedTime;
        // we assume that the session was valid the last time it was stored, otherwise we wouldn't be needed.
        this.isValid = true;
    }

    @Override
    public boolean isValid() {
        // explicitely override just that we definitely know that we have the "default" behavior and
        // it does not get overridden by accident.
        return super.isValid();
    }

    @Override
    public void access() {
        throw new UnsupportedOperationException( "Not supported, only isValid is allowed." );
    }

    @Override
    public void activate() {
        throw new UnsupportedOperationException( "Not supported, only isValid is allowed." );
    }

    @Override
    public void addSessionListener( final SessionListener listener ) {
        throw new UnsupportedOperationException( "Not supported, only isValid is allowed." );
    }

    @Override
    public void endAccess() {
        throw new UnsupportedOperationException( "Not supported, only isValid is allowed." );
    }

    @Override
    protected boolean exclude( final String arg0 ) {
        throw new UnsupportedOperationException( "Not supported, only isValid is allowed." );
    }

    @Override
    public void expire() {
        throw new UnsupportedOperationException( "Not supported, only isValid is allowed." );
    }

    @Override
    public void expire( final boolean arg0 ) {
        throw new UnsupportedOperationException( "Not supported, only isValid is allowed." );
    }

    @Override
    protected void fireContainerEvent( final Context context, final String type, final Object data ) throws Exception {
        throw new UnsupportedOperationException( "Not supported, only isValid is allowed." );
    }

    @Override
    public void fireSessionEvent( final String arg0, final Object arg1 ) {
        throw new UnsupportedOperationException( "Not supported, only isValid is allowed." );
    }

    @Override
    public Object getAttribute( final String name ) {
        throw new UnsupportedOperationException( "Not supported, only isValid is allowed." );
    }

    @Override
    @SuppressWarnings( "rawtypes" )
    public Enumeration getAttributeNames() {
        throw new UnsupportedOperationException( "Not supported, only isValid is allowed." );
    }

    @Override
    public String getAuthType() {
        throw new UnsupportedOperationException( "Not supported, only isValid is allowed." );
    }

    @Override
    public long getCreationTime() {
        throw new UnsupportedOperationException( "Not supported, only isValid is allowed." );
    }

    @Override
    public String getId() {
        throw new UnsupportedOperationException( "Not supported, only isValid is allowed." );
    }

    @Override
    public String getIdInternal() {
        throw new UnsupportedOperationException( "Not supported, only isValid is allowed." );
    }

    @Override
    public String getInfo() {
        throw new UnsupportedOperationException( "Not supported, only isValid is allowed." );
    }

    @Override
    public long getLastAccessedTime() {
        throw new UnsupportedOperationException( "Not supported, only isValid is allowed." );
    }

    @Override
    public long getLastAccessedTimeInternal() {
        throw new UnsupportedOperationException( "Not supported, only isValid is allowed." );
    }

    @Override
    public Manager getManager() {
        throw new UnsupportedOperationException( "Not supported, only isValid is allowed." );
    }

    @Override
    public int getMaxInactiveInterval() {
        throw new UnsupportedOperationException( "Not supported, only isValid is allowed." );
    }

    @Override
    public Object getNote( final String name ) {
        throw new UnsupportedOperationException( "Not supported, only isValid is allowed." );
    }

    @Override
    @SuppressWarnings( "rawtypes" )
    public Iterator getNoteNames() {
        throw new UnsupportedOperationException( "Not supported, only isValid is allowed." );
    }

    @Override
    public Principal getPrincipal() {
        throw new UnsupportedOperationException( "Not supported, only isValid is allowed." );
    }

    @Override
    public ServletContext getServletContext() {
        throw new UnsupportedOperationException( "Not supported, only isValid is allowed." );
    }

    @Override
    public HttpSession getSession() {
        throw new UnsupportedOperationException( "Not supported, only isValid is allowed." );
    }

    @Override
    public Object getValue( final String name ) {
        throw new UnsupportedOperationException( "Not supported, only isValid is allowed." );
    }

    @Override
    public String[] getValueNames() {
        throw new UnsupportedOperationException( "Not supported, only isValid is allowed." );
    }

    @Override
    public void invalidate() {
        throw new UnsupportedOperationException( "Not supported, only isValid is allowed." );
    }

    @Override
    public boolean isNew() {
        throw new UnsupportedOperationException( "Not supported, only isValid is allowed." );
    }

    @Override
    protected boolean isValidInternal() {
        throw new UnsupportedOperationException( "Not supported, only isValid is allowed." );
    }

    @Override
    protected String[] keys() {
        throw new UnsupportedOperationException( "Not supported, only isValid is allowed." );
    }

    @Override
    public void passivate() {
        throw new UnsupportedOperationException( "Not supported, only isValid is allowed." );
    }

    @Override
    public void putValue( final String name, final Object value ) {
        throw new UnsupportedOperationException( "Not supported, only isValid is allowed." );
    }

    @Override
    protected void readObject( final ObjectInputStream arg0 ) throws ClassNotFoundException, IOException {
        throw new UnsupportedOperationException( "Not supported, only isValid is allowed." );
    }

    @Override
    public void readObjectData( final ObjectInputStream stream ) throws ClassNotFoundException, IOException {
        throw new UnsupportedOperationException( "Not supported, only isValid is allowed." );
    }

    @Override
    public void recycle() {
        throw new UnsupportedOperationException( "Not supported, only isValid is allowed." );
    }

    @Override
    public void removeAttribute( final String name, final boolean notify ) {
        throw new UnsupportedOperationException( "Not supported, only isValid is allowed." );
    }

    @Override
    public void removeAttribute( final String name ) {
        throw new UnsupportedOperationException( "Not supported, only isValid is allowed." );
    }

    @Override
    protected void removeAttributeInternal( final String arg0, final boolean arg1 ) {
        throw new UnsupportedOperationException( "Not supported, only isValid is allowed." );
    }

    @Override
    public void removeNote( final String name ) {
        throw new UnsupportedOperationException( "Not supported, only isValid is allowed." );
    }

    @Override
    public void removeSessionListener( final SessionListener listener ) {
        throw new UnsupportedOperationException( "Not supported, only isValid is allowed." );
    }

    @Override
    public void removeValue( final String name ) {
        throw new UnsupportedOperationException( "Not supported, only isValid is allowed." );
    }

    @Override
    public void setAttribute( final String arg0, final Object arg1, final boolean arg2 ) {
        throw new UnsupportedOperationException( "Not supported, only isValid is allowed." );
    }

    @Override
    public void setAttribute( final String name, final Object value ) {
        throw new UnsupportedOperationException( "Not supported, only isValid is allowed." );
    }

    @Override
    public void setAuthType( final String authType ) {
        throw new UnsupportedOperationException( "Not supported, only isValid is allowed." );
    }

    @Override
    public void setCreationTime( final long time ) {
        throw new UnsupportedOperationException( "Not supported, only isValid is allowed." );
    }

    @Override
    public void setId( final String id ) {
        throw new UnsupportedOperationException( "Not supported, only isValid is allowed." );
    }

    @Override
    public void setManager( final Manager manager ) {
        throw new UnsupportedOperationException( "Not supported, only isValid is allowed." );
    }

    @Override
    public void setMaxInactiveInterval( final int interval ) {
        throw new UnsupportedOperationException( "Not supported, only isValid is allowed." );
    }

    @Override
    public void setNew( final boolean isNew ) {
        throw new UnsupportedOperationException( "Not supported, only isValid is allowed." );
    }

    @Override
    public void setNote( final String name, final Object value ) {
        throw new UnsupportedOperationException( "Not supported, only isValid is allowed." );
    }

    @Override
    public void setPrincipal( final Principal principal ) {
        throw new UnsupportedOperationException( "Not supported, only isValid is allowed." );
    }

    @Override
    public void setValid( final boolean isValid ) {
        throw new UnsupportedOperationException( "Not supported, only isValid is allowed." );
    }

    @Override
    public void tellNew() {
        throw new UnsupportedOperationException( "Not supported, only isValid is allowed." );
    }

    @Override
    public String toString() {
        throw new UnsupportedOperationException( "Not supported, only isValid is allowed." );
    }

    @Override
    protected void writeObject( final ObjectOutputStream arg0 ) throws IOException {
        throw new UnsupportedOperationException( "Not supported, only isValid is allowed." );
    }

    @Override
    public void writeObjectData( final ObjectOutputStream stream ) throws IOException {
        throw new UnsupportedOperationException( "Not supported, only isValid is allowed." );
    }

    @Override
    public int hashCode() {
        throw new UnsupportedOperationException( "Not supported, only isValid is allowed." );
    }

    @Override
    public boolean equals( final Object obj ) {
        throw new UnsupportedOperationException( "Not supported, only isValid is allowed." );
    }

    @Override
    protected Object clone() throws CloneNotSupportedException {
        throw new UnsupportedOperationException( "Not supported, only isValid is allowed." );
    }

    @Override
    protected void finalize() throws Throwable {
        throw new UnsupportedOperationException( "Not supported, only isValid is allowed." );
    }

}
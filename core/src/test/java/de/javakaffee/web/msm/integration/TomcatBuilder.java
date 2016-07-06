/*
 * Copyright 2013 Martin Grotzke
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
 */
package de.javakaffee.web.msm.integration;

import javax.annotation.Nonnull;

import org.apache.catalina.Context;
import org.apache.catalina.Engine;
import org.apache.catalina.Role;
import org.apache.catalina.User;
import org.apache.catalina.UserDatabase;
import org.apache.catalina.users.MemoryUserDatabase;

import de.javakaffee.web.msm.JavaSerializationTranscoderFactory;
import de.javakaffee.web.msm.LockingStrategy.LockingMode;
import de.javakaffee.web.msm.MemcachedSessionService;
import de.javakaffee.web.msm.MemcachedSessionService.SessionManager;
import de.javakaffee.web.msm.integration.TestUtils.LoginType;

/**
 * Builder and manager for {@link org.apache.catalina.startup.Embedded} tomcat or some other embedded tomcat implementation.
 *
 * @author @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public abstract class TomcatBuilder<T> {

    public static final String CONTEXT_PATH = "";
    protected static final String DEFAULT_HOST = "localhost";
    protected static final String DEFAULT_TRANSCODER_FACTORY = JavaSerializationTranscoderFactory.class.getName();

    protected static final String USER_DATABASE = "UserDatabase";
    protected static final String PASSWORD = "secret";
    protected static final String USER_NAME = "testuser";
    protected static final String ROLE_NAME = "test";

    public static final String STICKYNESS_PROVIDER = "stickynessProvider";
    public static final String BOOLEAN_PROVIDER = "booleanProvider";

    protected int port;
    protected int sessionTimeout = 1;
    protected boolean cookies = true;
    protected String jvmRoute = null;
    protected LoginType loginType = null;
    protected String memcachedNodes;
    protected String failoverNodes;
    protected boolean enabled = true;
    protected boolean sticky = true;
    protected LockingMode lockingMode;
    protected int lockExpire;
    protected String memcachedProtocol = MemcachedSessionService.PROTOCOL_TEXT;
    protected String username = null;
    protected String transcoderFactoryClassName = JavaSerializationTranscoderFactory.class.getName();
    protected String storageKeyPrefix;

    public TomcatBuilder<T> port(final int port) {
        this.port = port;
        return this;
    }

    public TomcatBuilder<T> sessionTimeout(final int sessionTimeout) {
        this.sessionTimeout = sessionTimeout;
        return this;
    }

    public TomcatBuilder<T> cookies(final boolean cookies) {
        this.cookies = cookies;
        return this;
    }

    public TomcatBuilder<T> memcachedNodes(final String memcachedNodes) {
        this.memcachedNodes = memcachedNodes;
        return this;
    }

    public TomcatBuilder<T> failoverNodes(final String failoverNodes) {
        this.failoverNodes = failoverNodes;
        return this;
    }

    public TomcatBuilder<T> storageKeyPrefix(final String storageKeyPrefix) {
        this.storageKeyPrefix = storageKeyPrefix;
        return this;
    }

    public TomcatBuilder<T> enabled(final boolean enabled) {
        this.enabled = enabled;
        return this;
    }

    public TomcatBuilder<T> sticky(final boolean sticky) {
        this.sticky = sticky;
        return this;
    }

    public TomcatBuilder<T> lockingMode(final LockingMode lockingMode) {
        this.lockingMode = lockingMode;
        return this;
    }

    public TomcatBuilder<T> lockExpire(final int lockExpire) {
        this.lockExpire = lockExpire;
        return this;
    }

    public TomcatBuilder<T> memcachedProtocol(final String memcachedProtocol) {
        this.memcachedProtocol = memcachedProtocol;
        return this;
    }

    public TomcatBuilder<T> username(final String memcachedUsername) {
        this.username = memcachedUsername;
        return this;
    }

    public TomcatBuilder<T> jvmRoute(final String jvmRoute) {
        this.jvmRoute = jvmRoute;
        return this;
    }

    public TomcatBuilder<T> loginType(final LoginType loginType) {
        this.loginType = loginType;
        return this;
    }

    public TomcatBuilder<T> transcoderFactoryClassName(final String transcoderFactoryClassName) {
        this.transcoderFactoryClassName = transcoderFactoryClassName;
        return this;
    }

    public abstract TomcatBuilder<T> buildAndStart() throws Exception;

    public abstract void stop() throws Exception;


    public abstract Context getContext();

    public abstract SessionManager getManager();

    public abstract MemcachedSessionService getService();

    public abstract Engine getEngine();

    public abstract void setChangeSessionIdOnAuth(final boolean changeSessionIdOnAuth);

    protected UserDatabase createUserDatabase() {
        final MemoryUserDatabase userDatabase = new MemoryUserDatabase();
        final Role role = userDatabase.createRole( ROLE_NAME, "the role for unit tests" );
        final User user = userDatabase.createUser( USER_NAME, PASSWORD, "the user for unit tests" );
        user.addRole( role );
        return userDatabase;
    }

    /**
     * Must create a {@link SessionManager} for the current tomcat version.
     */
    @Nonnull
    protected abstract SessionManager createSessionManager();

}

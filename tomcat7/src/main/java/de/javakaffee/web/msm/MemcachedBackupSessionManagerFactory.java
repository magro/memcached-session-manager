package de.javakaffee.web.msm;

import org.apache.catalina.Manager;

import de.javakaffee.web.msm.MemcachedSessionService.SessionManager;

public class MemcachedBackupSessionManagerFactory {

    private static final String DEFAULT_TRANSCODER_FACTORY = JavaSerializationTranscoderFactory.class.getName();
    
    public Manager createSessionManager() {
        SessionManager sessionManager = new MemcachedBackupSessionManager();
        
        String memcachedNodes = System.getProperty("msm.memcachedNodes");
        String failoverNodes = System.getProperty("msm.failoverNodes", "");
        boolean enabled = Boolean.parseBoolean(System.getProperty("msm.enabled", "true"));
        boolean sticky = Boolean.parseBoolean(System.getProperty("msm.sticky", "true"));;
        String lockingMode = System.getProperty("msm.lockingMode", null);
        String memcachedProtocol =  System.getProperty("msm.memcachedProtocol", MemcachedSessionService.PROTOCOL_TEXT);
        String username = System.getProperty("msm.username", "");
        
        // Default: One-Hour timeout
        int sessionTimeout = Integer.parseInt(System.getProperty("msm.sessionTimeout", "3600"));
        String transcoderFactoryClassName = System.getProperty("msm.transcoderFactoryClassName", DEFAULT_TRANSCODER_FACTORY);
        
        // Example: ".*\\.(png|gif|jpg|css|js|ico)$"
        String requestUriIgnorePattern = System.getProperty("msm.requestUriIgnorePattern", "");
        
        /* we must set the maxInactiveInterval after the context,
         * as setContainer(context) uses the session timeout set on the context
         */
        sessionManager.getMemcachedSessionService().setMemcachedNodes( memcachedNodes );
        sessionManager.getMemcachedSessionService().setFailoverNodes( failoverNodes );
        sessionManager.getMemcachedSessionService().setEnabled(enabled);
        sessionManager.getMemcachedSessionService().setSticky(sticky);
        if(lockingMode != null) {
            sessionManager.getMemcachedSessionService().setLockingMode(lockingMode);
        }
        sessionManager.getMemcachedSessionService().setMemcachedProtocol(memcachedProtocol);
        sessionManager.getMemcachedSessionService().setUsername(username);
        sessionManager.setMaxInactiveInterval( sessionTimeout ); // 1 second
        sessionManager.getMemcachedSessionService().setSessionBackupAsync( false );
        sessionManager.getMemcachedSessionService().setSessionBackupTimeout( 100 );
        sessionManager.setProcessExpiresFrequency( 1 ); // 1 second (factor for context.setBackgroundProcessorDelay)
        sessionManager.getMemcachedSessionService().setTranscoderFactoryClass( transcoderFactoryClassName);
        sessionManager.getMemcachedSessionService().setRequestUriIgnorePattern(requestUriIgnorePattern);

        System.out.println("MemcachedBackupSessionManager constructed");
        return sessionManager;
    }
}

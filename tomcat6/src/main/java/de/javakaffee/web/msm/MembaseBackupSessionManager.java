package de.javakaffee.web.msm;

import org.apache.catalina.LifecycleListener;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.Session;
import org.apache.catalina.Context;
import org.apache.catalina.session.StandardSession;
import org.apache.catalina.util.LifecycleSupport;

import java.beans.PropertyChangeEvent;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

/**
 *
 */
public class MembaseBackupSessionManager extends MemcachedBackupSessionManager {

    protected static final String NAME = MembaseBackupSessionManager.class.getSimpleName();

    private static final String INFO = NAME + "/1.0";

    protected final Log _log = LogFactory.getLog( getClass() );

    private final LifecycleSupport _lifecycle = new LifecycleSupport( this );

    private int _maxActiveSessions = -1;
    private int _rejectedSessions;

    /**
     * Has this component been _started yet?
     */
    protected boolean _started = false;

    protected MembaseSessionService _msm;

    public MembaseBackupSessionManager() {
        _msm = new MembaseSessionService( this );
    }
    
    public int getMaxActiveSessions() {
        return 0;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public MemcachedSessionService getMemcachedSessionService() {
        return _msm;
    }

    public int getRejectedSessions() {
        return _rejectedSessions;
    }

    public void setRejectedSessions(int i) {
        _rejectedSessions = i;
    }

    public void addLifecycleListener(LifecycleListener lifecycleListener) {
        _lifecycle.addLifecycleListener( lifecycleListener );
    }

    public LifecycleListener[] findLifecycleListeners() {
       return _lifecycle.findLifecycleListeners();
    }

    public void removeLifecycleListener(LifecycleListener lifecycleListener) {
        _lifecycle.removeLifecycleListener( lifecycleListener );
    }

    public void start() throws LifecycleException {
        if( ! initialized ) {
            init();
        }

        // Validate and update our current component state
        if (_started) {
            return;
        }
        _lifecycle.fireLifecycleEvent(START_EVENT, null);
        _started = true;

        // Force initialization of the random number generator
        if (log.isDebugEnabled()) {
            log.debug("Force random number initialization starting");
        }
        super.generateSessionId();
        if (log.isDebugEnabled()) {
            log.debug("Force random number initialization completed");
        }

        startInternal( null );
    }

    public void stop() throws LifecycleException {
            if (log.isDebugEnabled()) {
            log.debug("Stopping");
        }

        // Validate and update our current component state
        if (!_started) {
            throw new LifecycleException
                (sm.getString("standardManager.notStarted"));
        }
        _lifecycle.fireLifecycleEvent(STOP_EVENT, null);
        _started = false;

        // Require a new random number generator if we are restarted
        random = null;

        if ( initialized ) {

            if ( _msm.isSticky() ) {
                _log.info( "Removing sessions from local session map." );
                for( final Session session : sessions.values() ) {
                    swapOut( (StandardSession) session );
                }
            }

            _msm.shutdown();

            destroy();
        }
    }

    private void swapOut( final StandardSession session ) {
            // implementation like the one in PersistentManagerBase.swapOut
            if (!session.isValid()) {
                return;
            }
            session.passivate();
            removeInternal( session, true );
            session.recycle();
    }


    public void propertyChange(PropertyChangeEvent event) {
        // Validate the source of this event
        if ( !( event.getSource() instanceof Context) ) {
            return;
        }

        // Process a relevant property change
        if ( event.getPropertyName().equals( "sessionTimeout" ) ) {
            try {
                setMaxInactiveInterval( ( (Integer) event.getNewValue() ).intValue() * 60 );
            } catch ( final NumberFormatException e ) {
                _log.warn( "standardManager.sessionTimeout: " + event.getNewValue().toString() );
            }
        }
    }
}

/**
 * 
 */
package org.apache.juli.logging;

import org.jboss.logging.Logger;
import org.jboss.logging.LoggerPlugin;

/**
 * Adapter from jboss logging {@link Logger} to tomcat juli {@link Log} interface.
 * 
 * @author Martin Grotzke
 */
public class LogAdapter implements Log {

    private final Logger _log;

    public LogAdapter( final Logger log ) {
        _log = log;
    }

    @Override
    public void debug( final Object message, final Throwable t ) {
        _log.debug( message, t );
    }

    @Override
    public void debug( final Object message ) {
        _log.debug( message );
    }

    @Override
    public boolean equals( final Object obj ) {
        return _log.equals( obj );
    }

    @Override
    public void error( final Object message, final Throwable t ) {
        _log.error( message, t );
    }

    @Override
    public void error( final Object message ) {
        _log.error( message );
    }

    @Override
    public void fatal( final Object message, final Throwable t ) {
        _log.fatal( message, t );
    }

    @Override
    public void fatal( final Object message ) {
        _log.fatal( message );
    }

    public LoggerPlugin getLoggerPlugin() {
        return _log.getLoggerPlugin();
    }

    public String getName() {
        return _log.getName();
    }

    @Override
    public int hashCode() {
        return _log.hashCode();
    }

    @Override
    public void info( final Object message, final Throwable t ) {
        _log.info( message, t );
    }

    @Override
    public void info( final Object message ) {
        _log.info( message );
    }

    @Override
    public boolean isDebugEnabled() {
        return _log.isDebugEnabled();
    }

    @Override
    public boolean isInfoEnabled() {
        return _log.isInfoEnabled();
    }

    @Override
    public boolean isTraceEnabled() {
        return _log.isTraceEnabled();
    }

    @Override
    public String toString() {
        return _log.toString();
    }

    @Override
    public void trace( final Object message, final Throwable t ) {
        _log.trace( message, t );
    }

    @Override
    public void trace( final Object message ) {
        _log.trace( message );
    }

    @Override
    public void warn( final Object message, final Throwable t ) {
        _log.warn( message, t );
    }

    @Override
    public void warn( final Object message ) {
        _log.warn( message );
    }

    @Override
    public boolean isWarnEnabled() {
        return true;
    }

    @Override
    public boolean isErrorEnabled() {
        return true;
    }

    @Override
    public boolean isFatalEnabled() {
        return true;
    }

}

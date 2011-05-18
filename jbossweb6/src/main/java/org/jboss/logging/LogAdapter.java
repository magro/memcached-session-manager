/**
 * 
 */
package org.jboss.logging;

import org.apache.juli.logging.Log;
import org.jboss.logging.Logger.Level;

/**
 * Adapter from jboss logging {@link Logger} to tomcat juli {@link Log} interface.
 * 
 * @author Martin Grotzke
 */
public class LogAdapter implements Log {

    private static final String FQCN = Logger.class.getName();

    private final Logger _log;

    public LogAdapter( final Logger log ) {
        _log = log;
    }

    @Override
    public void debug( final Object message, final Throwable t ) {
        _log.doLog(Level.DEBUG, FQCN, message, null, t);
    }

    @Override
    public void debug( final Object message ) {
        _log.doLog(Level.DEBUG, FQCN, message, null, null);
    }

    @Override
    public boolean equals( final Object obj ) {
        return _log.equals( obj );
    }

    @Override
    public void error( final Object message, final Throwable t ) {
        _log.doLog(Level.ERROR, FQCN, message, null, t);
    }

    @Override
    public void error( final Object message ) {
        _log.doLog(Level.ERROR, FQCN, message, null, null);
    }

    @Override
    public void fatal( final Object message, final Throwable t ) {
        _log.doLog(Level.FATAL, FQCN, message, null, t);
    }

    @Override
    public void fatal( final Object message ) {
        _log.doLog(Level.FATAL, FQCN, message, null, null);
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
        _log.doLog(Level.INFO, FQCN, message, null, t);
    }

    @Override
    public void info( final Object message ) {
        _log.doLog(Level.INFO, FQCN, message, null, null);
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
        _log.doLog(Level.TRACE, FQCN, message, null, t);
    }

    @Override
    public void trace( final Object message ) {
        _log.doLog(Level.TRACE, FQCN, message, null, null);
    }

    @Override
    public void warn( final Object message, final Throwable t ) {
        _log.doLog(Level.WARN, FQCN, message, null, t);
    }

    @Override
    public void warn( final Object message ) {
        _log.doLog(Level.WARN, FQCN, message, null, null);
    }

    @Override
    public boolean isWarnEnabled() {
        return _log.isEnabled(Level.WARN);
    }

    @Override
    public boolean isErrorEnabled() {
        return _log.isEnabled(Level.ERROR);
    }

    @Override
    public boolean isFatalEnabled() {
        return _log.isEnabled(Level.FATAL);
    }

}

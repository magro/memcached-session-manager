package de.javakaffee.web.msm;

import org.apache.juli.logging.Log;

import java.util.concurrent.*;

import static de.javakaffee.web.msm.Configurations.getSystemProperty;

/**
 * The spymemcached library can block for a quite long time (currently 10 seconds),
 * if its operations queue is full. Since a call to an external system should not
 * block the page delivery for that long, calls can now be timeout with this class.
 *
 * @author Marco Kortkamp (marco.kortkamp@freiheit.com)
 */
final class TimeoutExecutor {

    private TimeoutExecutor() {
    }

    static final String CALLABLE_TIMEOUT_MS_KEY = "msm.timeoutExecutor.timeoutMs";
    static final String POOL_SIZE_KEY = "msm.timeoutExecutor.poolSize";

    static final int DEFAULT_CALLABLE_TIMEOUT_MS = getSystemProperty(CALLABLE_TIMEOUT_MS_KEY, 1500);
    static final int DEFAULT_TIMEOUT_EXECUTOR_POOL_SIZE = getSystemProperty(POOL_SIZE_KEY, 10);

    private static ExecutorService TIMEOUT_EXECUTOR = Executors.newFixedThreadPool(DEFAULT_TIMEOUT_EXECUTOR_POOL_SIZE,
            new NamedThreadFactory("TimeoutExecutor"));

    static String callWithTimeout(final Callable<String> callable, final int timeoutMs, final Log log) {
        try {
            return TIMEOUT_EXECUTOR.submit(callable).get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (final InterruptedException e) {
            logException(log, e, timeoutMs);
        } catch (final ExecutionException e) {
            logException(log, e, timeoutMs);
        } catch (final TimeoutException e) {
            logException(log, e, timeoutMs);
        }
        return null;
    }

    private static void logException(final Log log, final Exception e, final int timeoutMs) {
        if (log.isDebugEnabled()) {
            log.debug("Exception on callable with timeoue (timeoutMs: " + timeoutMs + ").", e);
        }
    }
}

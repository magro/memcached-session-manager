package de.javakaffee.web.msm;

import org.apache.juli.logging.Log;
import org.testng.annotations.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.ThreadPoolExecutor;

import static org.mockito.Mockito.mock;
import static org.testng.Assert.assertEquals;

/**
 * Unit Tests for the TimeoutExecutor.
 *
 * @author Marco Kortkamp (marco.kortkamp@freiheit.com)
 */
public class TimeoutExecutorTest {
    @Test
    public final void shouldNotBeTimedOut() {
        // given
        final Log log = mock(Log.class);
        final String expectedResult = "foo";
        final Callable<String> callable = new Callable<String>() {
            @Override
            public String call() {
                return expectedResult;
            }
        };
        final int timeoutMs = 200;
        // when
        final String actualResult = TimeoutExecutor.callWithTimeout(callable, timeoutMs, log);
        // then
        assertEquals(actualResult, expectedResult);
    }

    @Test
    public final void shouldBeTimedOut() {
        // given
        final Log log = mock(Log.class);
        final String expectedResult = null;
        final Callable<String> callable = new Callable<String>() {
            @Override
            public String call() throws InterruptedException {
                Thread.sleep(400);
                return "";
            }
        };
        final int timeoutMs = 200;
        // when
        final String actualResult = TimeoutExecutor.callWithTimeout(callable, timeoutMs, log);
        // then
        assertEquals(actualResult, expectedResult);
    }
}

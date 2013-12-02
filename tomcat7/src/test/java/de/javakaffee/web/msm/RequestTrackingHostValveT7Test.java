package de.javakaffee.web.msm;

import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collection;

import org.apache.catalina.connector.Response;
import org.testng.annotations.Test;

@Test
public class RequestTrackingHostValveT7Test extends RequestTrackingHostValveTest {

    @Override
    protected void setupGetResponseSetCookieHeadersExpectations(final Response response, final String[] result) {
        when(response.getHeaders(eq("Set-Cookie"))).thenReturn(Arrays.asList(result));
    }

    @Override
    protected String[] getSetCookieHeaders(final Response response) {
        final Collection<String> result = response.getHeaders("Set-Cookie");
        return result.toArray(new String[result.size()]);
    }
}

package de.javakaffee.web.msm;

import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.when;

import org.apache.catalina.connector.Response;
import org.testng.annotations.Test;

@Test
public class RequestTrackingHostValveT6Test extends RequestTrackingHostValveTest {

    @Override
    protected void setupGetResponseSetCookieHeadersExpectations(final Response response, final String[] result) {
        when(response.getHeaderValues(eq("Set-Cookie"))).thenReturn(result);
    }

    @Override
    protected String[] getSetCookieHeaders(final Response response) {
        return response.getHeaderValues("Set-Cookie");
    }
}

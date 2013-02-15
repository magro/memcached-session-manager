package de.javakaffee.web.msm;

import org.apache.catalina.connector.Response;
import org.testng.annotations.Test;

import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.when;

@Test
public class RequestTrackingHostValveT6Test extends RequestTrackingHostValveTest {

    @Override
    protected void setupGetResponseSetCookieHeadersExpectations(Response response, String[] result) {
        when(response.getHeaderValues(eq("Set-Cookie"))).thenReturn(result);
    }
}

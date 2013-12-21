package de.javakaffee.web.msm;

import org.apache.catalina.connector.Response;
import org.testng.annotations.Test;

import java.util.Arrays;

import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.when;

@Test
public class RequestTrackingHostValveT8Test extends RequestTrackingHostValveTest {

    @Override
    protected void setupGetResponseSetCookieHeadersExpectations(Response response, String[] result) {
        when(response.getHeaders(eq("Set-Cookie"))).thenReturn(Arrays.asList(result));
    }
}

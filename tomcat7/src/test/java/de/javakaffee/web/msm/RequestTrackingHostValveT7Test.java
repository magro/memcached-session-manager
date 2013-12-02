package de.javakaffee.web.msm;

import org.apache.catalina.connector.Response;
import org.testng.annotations.Test;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.when;

@Test
public class RequestTrackingHostValveT7Test extends RequestTrackingHostValveTest {

    @Override
    protected void setupGetResponseSetCookieHeadersExpectations(Response response, String[] result) {
        when(response.getHeaders(eq("Set-Cookie"))).thenReturn(Arrays.asList(result));
    }

    @Override
    protected AbstractRequestTrackingHostValve createSessionTrackerValve() {
        return new RequestTrackingHostValve(".*\\.(png|gif|jpg|css|js|ico)$", "somesessionid", _service, Statistics.create(),
                new AtomicBoolean( true ), new CurrentRequest());
    }
}

package de.javakaffee.web.msm;

import java.util.concurrent.atomic.AtomicBoolean;
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

    @Override
    protected AbstractRequestTrackingHostValve createSessionTrackerValve() {
        return new RequestTrackingHostValve(".*\\.(png|gif|jpg|css|js|ico)$", "somesessionid", _service, Statistics.create(),
                new AtomicBoolean( true ), new CurrentRequest());
    }
}

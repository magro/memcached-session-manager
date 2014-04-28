package de.javakaffee.web.msm;

import org.apache.catalina.connector.Response;
import org.apache.tomcat.util.http.SetCookieSupport;
import org.testng.annotations.Test;

import java.util.Arrays;
import java.util.Collection;
import javax.annotation.Nonnull;
import javax.servlet.http.Cookie;

import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.when;

@Test
public class RequestTrackingHostValveT8Test extends RequestTrackingHostValveTest {

    @Override
    protected void setupGetResponseSetCookieHeadersExpectations(Response response, String[] result) {
        when(response.getHeaders(eq("Set-Cookie"))).thenReturn(Arrays.asList(result));
    }

    @Override
    protected String[] getSetCookieHeaders(final Response response) {
        final Collection<String> result = response.getHeaders("Set-Cookie");
        return result.toArray(new String[result.size()]);
    }

    @Nonnull
    protected String generateCookieString(final Cookie cookie) {
        return SetCookieSupport.generateHeader(cookie);
    }
}

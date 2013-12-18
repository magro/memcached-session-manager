package de.javakaffee.web.msm;

import static de.javakaffee.web.msm.MemcachedUtil.toMemcachedExpiration;
import static org.testng.Assert.assertEquals;

import java.util.concurrent.TimeUnit;

import org.testng.annotations.Test;

public class MemcachedUtilTest {

    public static void main(final String[] args) {
        System.out.println(System.currentTimeMillis()/1000 + TimeUnit.DAYS.toSeconds(1000));
        System.out.println(Integer.MAX_VALUE);
    }

    @Test
    public void testToMemcachedExpiration() throws Exception {
        assertEquals(toMemcachedExpiration(60*60*24*30), 60*60*24*30);
        assertEquals(toMemcachedExpiration(60*60*24*30 + 1), System.currentTimeMillis()/1000 + 60*60*24*30 + 1);
    }
}

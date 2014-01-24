package de.javakaffee.web.msm;

import java.lang.reflect.Method;

final class Reflections {

    private Reflections() {
    }

    @SuppressWarnings("unchecked")
    static <T> T invoke(final Object obj, final String methodName, final T defaultValue) {
        try {
            final Method method = obj.getClass().getMethod(methodName);
            return (T) method.invoke(obj);
        } catch (final Exception e) {
            return defaultValue;
        }
    }


}

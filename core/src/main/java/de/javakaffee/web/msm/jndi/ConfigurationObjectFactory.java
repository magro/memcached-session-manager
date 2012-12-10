/*
 * Copyright 2012 Hans-Joachim Kliemeck
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an &quot;AS IS&quot; BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package de.javakaffee.web.msm.jndi;

import java.lang.reflect.Field;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;
import javax.naming.*;
import javax.naming.spi.ObjectFactory;

public class ConfigurationObjectFactory implements ObjectFactory {
    private static Map<Class, Class> mapper = new HashMap<Class, Class>();
    static {
        mapper.put(boolean.class, Boolean.class);
        mapper.put(int.class, Integer.class);
        mapper.put(long.class, Long.class);
    }

    @Override
    public Object getObjectInstance(Object obj, Name name, Context nameCtx, Hashtable<?, ?> environment) throws Exception {
        if (obj == null) {
            throw new NamingException("Invalid JNDI object reference");
        }

        Class<ConfigurationObject> clazz = ConfigurationObject.class;
        ConfigurationObject config = clazz.newInstance();

        Reference ref = (Reference) obj;
        Enumeration<RefAddr> props = ref.getAll();
        while (props.hasMoreElements()) {
            RefAddr addr = props.nextElement();
            String propName = addr.getType();
            String propValue = addr.getContent().toString();

            Field[] fields = ConfigurationObject.class.getFields();
            for (Field field : fields) {
                if (propName.equals(field.getName())) {
                    Class<?> type = field.getType();
                    if (mapper.containsKey(type)) {
                        type = mapper.get(type);
                    }

                    Object value = null;
                    if (type.isAssignableFrom(Long.class)) {
                        try {
                            value = Long.valueOf(propValue);
                        } catch (NumberFormatException e) {
                            throw new NamingException(field.getName() + " is not an long value");
                        }
                    } else if (type.isAssignableFrom(Integer.class)) {
                        try {
                            value = Integer.parseInt(propValue);
                        } catch (NumberFormatException e) {
                            throw new NamingException(field.getName() + " is not an integer value");
                        }
                    } else if (type.isAssignableFrom(Boolean.class)) {
                        if ("true".equalsIgnoreCase(propValue)) {
                            value = true;
                        } else if ("false".equalsIgnoreCase(propValue)) {
                            value = false;
                        } else {
                            throw new NamingException(field.getName() + " is not a boolean value");
                        }
                    } else if (type.isAssignableFrom(String.class)) {
                        value = propValue;
                    }

                    if (value != null) {
                        field.set(config, value);
                    }
                }
            }
        }

        return config;
    }
}

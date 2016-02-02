/*
 * Copyright 2010 Martin Grotzke
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
package de.javakaffee.web.msm.serializer.kryo;

import static org.testng.Assert.*;
import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import org.testng.annotations.Test;

/**
 * @author <a href="mailto:cleclerc@cloudbees.com">Cyrille Le Clerc</a>
 */
public class HibernateCollectionsSerializerFactoryTest {

    private static Class<?> PERSISTENT_LIST_CLASS;

    static {
        try {
            PERSISTENT_LIST_CLASS = Class.forName("org.hibernate.collection.PersistentList");
        } catch (ClassNotFoundException e) {
            try {
                PERSISTENT_LIST_CLASS = Class.forName("org.hibernate.collection.internal.PersistentList");
            } catch (ClassNotFoundException e2) {
                PERSISTENT_LIST_CLASS = null;
            }
        }
    }

    @Test
    public void test() {
        HibernateCollectionsSerializerFactory factory = new HibernateCollectionsSerializerFactory(new Kryo());

        Serializer serializer = factory.newSerializer(PERSISTENT_LIST_CLASS);
        assertNotNull(serializer);
    }
}

/*
 * Copyright 2014 Marcus Thiesen
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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.testng.annotations.Test;

import static org.testng.Assert.assertNotNull;

import de.javakaffee.web.msm.MemcachedBackupSession;
import de.javakaffee.web.msm.SessionAttributesTranscoder;
import de.javakaffee.web.msm.TranscoderDeserializationException;
import static de.javakaffee.web.msm.integration.TestUtils.assertDeepEquals;

/**
 * A test to verify that the correct exception is thrown when a class 
 * show incompatible changes.
 * 
 * @author Marcus Thiesen (marcus.thiesen@freiheit.com) (initial creation)
 */
public class SerializationChangeTests {

    private static final String TEST_TYPE_CLASS_NAME = "test.Type";

    @Test( expectedExceptions = TranscoderDeserializationException.class )
    public void testDeserializationError() {
        // Create a class with one simple field:
        final ClassLoader loaderForCustomClassInVersion1 = ClassGenerationUtil.makeClassLoaderForCustomClass( this.getClass().getClassLoader(), TEST_TYPE_CLASS_NAME, "field1" );
        final Object value = makeValueInstance( loaderForCustomClassInVersion1 );

        final SessionAttributesTranscoder transcoder = new KryoTranscoderFactory().createTranscoder( loaderForCustomClassInVersion1 );

        // serialize one instance
        final MemcachedBackupSession memcachedBackupSession = new MemcachedBackupSession();
        final ConcurrentMap<String, Object> attributes = new ConcurrentHashMap<String, Object>();
        attributes.put( "test", value );

        byte[] data = transcoder.serializeAttributes( memcachedBackupSession, attributes );
        final Map<String, Object> deserializeAttributes = transcoder.deserializeAttributes( data );

        final Object actual = deserializeAttributes.get( "test" );
        assertNotNull(actual);
        assertDeepEquals(actual, value);

        // create same class with second field
        final ClassLoader loaderForCustomClassInVersion2 = ClassGenerationUtil.makeClassLoaderForCustomClass( this.getClass().getClassLoader(), TEST_TYPE_CLASS_NAME, "field1", "field2" );
        final SessionAttributesTranscoder secondTranscoder = new KryoTranscoderFactory().createTranscoder( loaderForCustomClassInVersion2 );

        // this should lead to an exception
        secondTranscoder.deserializeAttributes( data );
    }

    private static Object makeValueInstance( ClassLoader loaderForCustomClass ) {
        try {
            Class<?> forName = Class.forName( TEST_TYPE_CLASS_NAME, true, loaderForCustomClass );

            return forName.newInstance();

        } catch ( Exception e ) {
            throw new RuntimeException( e );
        }
    }

}

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

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.serializers.FieldSerializer;
import org.grails.web.servlet.GrailsFlashScope;

/**
 * {@link SerializerFactory} that supports grails flash scope.
 * It creates a {@link FieldSerializer} for {@link GrailsFlashScope} and enables references.
 * 
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class GrailsFlashScopeRegistration implements KryoCustomization {
    
	@Override
	public void customize(Kryo kryo) {
		kryo.setReferences(true);
        kryo.register( GrailsFlashScope.class, new FieldSerializer( kryo, GrailsFlashScope.class ) );
	}
    
}
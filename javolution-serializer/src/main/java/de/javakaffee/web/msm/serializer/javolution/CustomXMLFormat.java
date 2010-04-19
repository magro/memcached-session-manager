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
package de.javakaffee.web.msm.serializer.javolution;

import javolution.xml.XMLFormat;

/**
 * The superclass for custom {@link XMLFormat}s that are used by the
 * javolution serializer.
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public abstract class CustomXMLFormat<T> extends XMLFormat<T> {

    /**
     * Creates a new instance, super {@link XMLFormat} constructor is
     * invoked with <code>null</code>.
     */
    public CustomXMLFormat() {
        super( null );
    }

    /**
     * Used to determine if this {@link XMLFormat} can handle the given class, both
     * during serialization and deserialization.
     *
     * @param cls the class to check
     * @return <code>true</code> if this {@link XMLFormat} serializes/deserializes instances of the provided class.
     */
    public abstract boolean canConvert( Class<?> cls );

    /**
     * Used to determine the class that is used for writing the <code>class</code>
     * attribute to the serialized xml. This is usefull if the given class
     * is a proxy.
     * <p>
     * This implementation just returns the provided class, subclasses may
     * override this.
     * </p>
     *
     * @param cls the class to check
     * @return the translated class
     */
    public Class<?> getTargetClass( final Class<?> cls ) {
        return cls;
    }

}

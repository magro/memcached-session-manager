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

/**
 * This interface allows to intercept <code>handleUnregisteredClass(Class)</code> in {@link Kryo}.
 * 
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public interface UnregisteredClassHandler {

    /**
     * Allows to handle the given unregistered class as a replacement of
     * {@link Kryo#handleUnregisteredClass(Class)}. <code>true</code> must be returned
     * if the class was handled and {@link Kryo#handleUnregisteredClass(Class)} shall
     * not be invoked by the caller. 
     * 
     * @param type the type to handle.
     * @return <code>true</code> if the class was handled.
     */
    boolean handleUnregisteredClass( final Class<?> type );
    
}

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

import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtField;

/**
 * @author Marcus Thiesen (marcus.thiesen@freiheit.com) (initial creation)
 */
public class ClassGenerationUtil {

    private static byte[] makeClass( final String name, final String... fields ) {
        final ClassPool pool = ClassPool.getDefault();

        final CtClass newClass = pool.makeClass( name );

        try {
            for ( final String field : fields ) {
                newClass.addField( new CtField( pool.get( "java.lang.String"), field, newClass ) );
            }

            return newClass.toBytecode();
        } catch ( Exception e ) {
            throw new RuntimeException( e );
        }
    }

    public static class ByteClassLoader extends ClassLoader {

        private final String _className;
        private final byte[] _classBytes;

        public ByteClassLoader( final ClassLoader parent, final String className, byte[] classBytes ) {
            super( parent);

            _className = className;
            _classBytes = classBytes;
        }

        @Override
        protected Class<?> findClass(final String name) throws ClassNotFoundException {
            if ( _className.equals( name ) ) {
                return defineClass( name, _classBytes, 0, _classBytes.length ); 
            }
            return super.findClass(name);
        }
    }

    public static ClassLoader makeClassLoaderForCustomClass( final ClassLoader parent, final String className, final String... fields ) {
        return new ByteClassLoader( parent, className, makeClass( className, fields ) );
    }

}

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

import static de.javakaffee.web.msm.serializer.javolution.ReflectionBinding.XMLJdkProxyFormat.getInterfaceNames;
import static de.javakaffee.web.msm.serializer.javolution.ReflectionBinding.XMLJdkProxyFormat.getInterfaces;
import javolution.xml.stream.XMLStreamException;
import net.sf.cglib.proxy.Callback;
import net.sf.cglib.proxy.Enhancer;
import net.sf.cglib.proxy.Factory;

/**
 * A format that serializes/deserializes cglib proxies.
 * 
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class CGLibProxyFormat extends CustomXMLFormat<Object> {

    private static String DEFAULT_NAMING_MARKER = "$$EnhancerByCGLIB$$";
    
    private static final String CALLBACKS = "callbacks";
    private static final String INTERFACES = "interfaces";
    private static final String SUPERCLASS = "superclass";
   
    /**
     * {@inheritDoc}
     */
    @Override
    public boolean canConvert( final Class<?> cls ) {
        return canSerialize( cls ) || canDeserialize( cls );
    }

    private boolean canDeserialize( final Class<?> cls ) {
        return cls == CGLibProxyMarker.class;
    }

    private boolean canSerialize( final Class<?> cls ) {
        return Enhancer.isEnhanced( cls ) && cls.getName().indexOf( DEFAULT_NAMING_MARKER ) > 0;
    }
    
    /**
     * This class is used as a marker class - written to the class attribute
     * on serialization and checked on deserialization (via {@link CGLibProxyFormat#canConvert(Class)}.
     */
    public static interface CGLibProxyMarker {}
    
    /**
     * Used to determine the class that is used for writing the class
     * attribute to the serialized xml.
     * <p>
     * This implementation returns a marker class so that we know on deserialization
     * that we should handle/deserialize the serialized xml.
     * </p>
     * 
     * @param the proxy class
     * @return the marker class {@link CGLibProxyMarker}.
     * 
     * @see #canConvert(Class)
     */
    @Override
    public Class<?> getTargetClass( final Class<?> cls ) {
        return CGLibProxyMarker.class;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isReferenceable() {
        return false;
    }
    
    /**
     * {@inheritDoc}
     */
    @Override
    public Object newInstance( final Class<Object> cls, final InputElement xml ) throws XMLStreamException {
        final Class<?> superclass;
        try {
            superclass = Class.forName( xml.getAttribute( SUPERCLASS ).toString() );
        } catch ( final ClassNotFoundException e ) {
            throw new XMLStreamException( e );
        }
        /* this class should be loaded from the webapp, therefore we have access to the correct class loader
         */
        final ClassLoader classLoader = getClass().getClassLoader();
        final Class<?>[] interfaces = getInterfaces( xml, INTERFACES, classLoader );
        final Callback[] callbacks = xml.get( CALLBACKS );

        return createProxy( superclass, interfaces, callbacks );
    }

    private Object createProxy( final Class<?> targetClass, final Class<?>[] interfaces, final Callback[] callbacks ) {
        final Enhancer e = new Enhancer();
        e.setInterfaces( interfaces );
        e.setSuperclass( targetClass );
        e.setCallbacks( callbacks );
        return e.create();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void read( final InputElement arg0, final Object arg1 ) throws XMLStreamException {
        // nothing to do...
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void write( final Object obj, final OutputElement xml ) throws XMLStreamException {
        
        final Class<?> superclass = obj.getClass().getSuperclass();
        xml.setAttribute( SUPERCLASS, superclass.getName() );
        
        final String[] interfaceNames = getInterfaceNames( obj );
        xml.add( interfaceNames, INTERFACES );
        
        final Callback[] callbacks = ((Factory)obj).getCallbacks();
        xml.add( callbacks, CALLBACKS );
    }

}

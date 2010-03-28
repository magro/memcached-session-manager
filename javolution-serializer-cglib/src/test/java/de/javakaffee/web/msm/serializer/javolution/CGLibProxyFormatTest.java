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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import javolution.xml.XMLBinding;
import javolution.xml.XMLObjectReader;
import javolution.xml.XMLObjectWriter;
import javolution.xml.XMLReferenceResolver;
import javolution.xml.stream.XMLStreamException;
import net.sf.cglib.core.DefaultNamingPolicy;
import net.sf.cglib.core.Predicate;
import net.sf.cglib.proxy.Enhancer;
import net.sf.cglib.proxy.InvocationHandler;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.testng.Assert;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.Test;

/**
 * Test for {@link CGLibProxyFormat}.
 * 
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class CGLibProxyFormatTest {

    private static final Log LOG = LogFactory.getLog( CGLibProxyFormatTest.class );
    
    private ReflectionBinding _binding;

    @BeforeTest
    protected void beforeTest() {
        _binding = new ReflectionBinding( getClass().getClassLoader(), false, new CGLibProxyFormat() );
    }

    @Test( enabled = true )
    public void testCGLibProxy() throws XMLStreamException {
        final ClassToProxy proxy = createProxy( new ClassToProxy() );
        proxy.setValue( "foo" );
        
        final byte[] serialized = serialize( proxy, _binding );
        System.out.println( new String( serialized ) );
        final ClassToProxy deserialized = deserialize( serialized, _binding );
        Assert.assertEquals( deserialized.getValue(), proxy.getValue() );
    }

    /**
     * Test that a proxy for another existing xmlformat is handled correctly.
     * 
     * @throws XMLStreamException
     */
    @Test( enabled = true )
    public void testCGLibProxyForExistingFormat() throws XMLStreamException {
        final Map<String, String> proxy = createProxy( new HashMap<String, String>() );
        proxy.put( "foo", "bar" );
        Assert.assertEquals( proxy.get( "foo" ), "bar" );
        
        final byte[] serialized = serialize( proxy, _binding );
        System.out.println( new String( serialized ) );
        final Map<String, String> deserialized = deserialize( serialized, _binding );
        Assert.assertEquals( deserialized.get( "foo" ), proxy.get( "foo" ) );
    }

    @SuppressWarnings( "unchecked" )
    private <T> T createProxy( final T obj ) {
        
        final Enhancer e = new Enhancer();
        e.setInterfaces( new Class[] { Serializable.class } );
        final Class<? extends Object> class1 = obj.getClass();
        e.setSuperclass( class1 );
        e.setCallback( new DelegatingHandler( obj ) );
        e.setNamingPolicy( new DefaultNamingPolicy() {
            @Override
            public String getClassName(final String prefix, final String source,
                final Object key, final Predicate names) {
                return super.getClassName( "MSM_" + prefix, source, key, names );
            }
        } );

        return (T) e.create();
    }

    protected byte[] serialize( final Object o, final XMLBinding binding ) {
        if ( o == null ) {
            throw new NullPointerException( "Can't serialize null" );
        }

        XMLObjectWriter writer = null;
        try {
            final ByteArrayOutputStream bos = new ByteArrayOutputStream();
            writer = XMLObjectWriter.newInstance( bos );
            final XMLReferenceResolver xmlReferenceResolver = new XMLReferenceResolver();
            xmlReferenceResolver.setIdentifierAttribute( JavolutionTranscoder.REFERENCE_ATTRIBUTE_ID );
            xmlReferenceResolver.setReferenceAttribute( JavolutionTranscoder.REFERENCE_ATTRIBUTE_REF_ID );
            writer.setReferenceResolver( xmlReferenceResolver );
            writer.setBinding( binding );
            writer.write( o, "root" );
            writer.flush();
            return bos.toByteArray();
        } catch ( final Exception e ) {
            throw new IllegalArgumentException( "Non-serializable object", e );
        } finally {
            try {
                writer.close();
            } catch ( final XMLStreamException e ) {
                // fail silently
            }
        }

    }

    protected <T> T deserialize( final byte[] in, final XMLBinding binding ) {
        XMLObjectReader reader = null;
        try {
            final ByteArrayInputStream bis = new ByteArrayInputStream( in );
            reader = XMLObjectReader.newInstance( bis );
            final XMLReferenceResolver xmlReferenceResolver = new XMLReferenceResolver();
            xmlReferenceResolver.setIdentifierAttribute( JavolutionTranscoder.REFERENCE_ATTRIBUTE_ID );
            xmlReferenceResolver.setReferenceAttribute( JavolutionTranscoder.REFERENCE_ATTRIBUTE_REF_ID );
            reader.setReferenceResolver( xmlReferenceResolver );
            reader.setBinding( binding );
            if ( !reader.hasNext() ) {
                throw new IllegalStateException( "reader has no input" );
            }
            return reader.read( "root" );
        } catch ( final RuntimeException e ) {
            LOG.error( "Could not deserialize.", e );
            throw e;
        } catch ( final javolution.xml.stream.XMLStreamException e ) {
            LOG.error( "Could not deserialize.", e );
            throw new RuntimeException( e );
        } finally {
            try {
                reader.close();
            } catch ( final XMLStreamException e ) {
                // fail silently
            }
        }
    }

    public static class DelegatingHandler implements InvocationHandler, Serializable {
        
        private static final long serialVersionUID = 1L;
        
        private final Object _delegate;

        public DelegatingHandler( final Object delegate ) {
            _delegate = delegate;
        }

        public Object invoke( final Object obj, final Method method, final Object[] args ) throws Throwable {
            return method.invoke( _delegate, args );
        }
    }
    
    public static class ClassToProxy {
        private String _value;
        
        /**
         * @param value the value to set
         */
        public void setValue( final String value ) {
            _value = value;
        }
        
        /**
         * @return the value
         */
        public String getValue() {
            return _value;
        }
    }

}

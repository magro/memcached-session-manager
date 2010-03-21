/*
 * Copyright 2009 Martin Grotzke
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

import static de.javakaffee.web.msm.serializer.javolution.TestClasses.createPerson;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Currency;
import java.util.Date;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javolution.xml.XMLObjectReader;
import javolution.xml.XMLObjectWriter;
import javolution.xml.XMLReferenceResolver;
import javolution.xml.stream.XMLStreamException;

import org.apache.catalina.core.StandardContext;
import org.apache.catalina.loader.WebappLoader;
import org.apache.catalina.session.StandardSession;
import org.apache.commons.lang.mutable.MutableInt;
import org.jmock.Mock;
import org.jmock.cglib.MockObjectTestCase;
import org.testng.Assert;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import de.javakaffee.web.msm.MemcachedBackupSession;
import de.javakaffee.web.msm.MemcachedBackupSessionManager;
import de.javakaffee.web.msm.serializer.javolution.TestClasses.Container;
import de.javakaffee.web.msm.serializer.javolution.TestClasses.CounterHolder;
import de.javakaffee.web.msm.serializer.javolution.TestClasses.CounterHolderArray;
import de.javakaffee.web.msm.serializer.javolution.TestClasses.Email;
import de.javakaffee.web.msm.serializer.javolution.TestClasses.Holder;
import de.javakaffee.web.msm.serializer.javolution.TestClasses.HolderArray;
import de.javakaffee.web.msm.serializer.javolution.TestClasses.HolderList;
import de.javakaffee.web.msm.serializer.javolution.TestClasses.MyContainer;
import de.javakaffee.web.msm.serializer.javolution.TestClasses.MyXMLSerializable;
import de.javakaffee.web.msm.serializer.javolution.TestClasses.Person;
import de.javakaffee.web.msm.serializer.javolution.TestClasses.SomeInterface;
import de.javakaffee.web.msm.serializer.javolution.TestClasses.Person.Gender;

/**
 * Test for {@link JavolutionTranscoder}
 * 
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class JavolutionTranscoderTest extends MockObjectTestCase {

    private MemcachedBackupSessionManager _manager;
    private JavolutionTranscoder _transcoder;

    @BeforeTest
    protected void beforeTest() {
        _manager = new MemcachedBackupSessionManager();

        final StandardContext container = new StandardContext();
        _manager.setContainer( container );

        final Mock webappLoaderControl = mock( WebappLoader.class );
        final WebappLoader webappLoader = (WebappLoader) webappLoaderControl.proxy();
        webappLoaderControl.expects( once() ).method( "setContainer" ).withAnyArguments();
        webappLoaderControl.expects( atLeastOnce() ).method( "getClassLoader" ).will(
                returnValue( Thread.currentThread().getContextClassLoader() ) );
        Assert.assertNotNull( webappLoader.getClassLoader(), "Webapp Classloader is null." );
        _manager.getContainer().setLoader( webappLoader );

        Assert.assertNotNull( _manager.getContainer().getLoader().getClassLoader(), "Classloader is null." );

        _transcoder = new JavolutionTranscoder( _manager, true );
    }

    /**
     * This is test for issue #34:
     * msm-javolution-serializer: java.util.Currency gets deserialized with ReflectionFormat
     * 
     * See http://code.google.com/p/memcached-session-manager/issues/detail?id=34
     * 
     * @throws Exception
     */
    @Test( enabled = true )
    public void testCurrency() throws Exception {
        final MemcachedBackupSession session = _manager.createEmptySession();
        session.setValid( true );
        
        final Currency orig = Currency.getInstance( "EUR" );
        session.setAttribute( "currency1", orig );
        session.setAttribute( "currency2", orig );
        
        final Map<String, Object> deserialized =
                _transcoder.deserializeAttributes( _transcoder.serializeAttributes( session, session.getAttributesInternal() ) );
        
        assertDeepEquals( deserialized, session.getAttributesInternal() );
        
        // Check that the transient field defaultFractionDigits is initialized correctly (that was the bug)
        final Currency currency1 = (Currency) deserialized.get( "currency1" );
        Assert.assertEquals( currency1.getCurrencyCode(), orig.getCurrencyCode() );
        Assert.assertEquals( currency1.getDefaultFractionDigits(), orig.getDefaultFractionDigits() );
        
    }

    /**
     * This is test for issue #33:
     * msm-javolution-serializer: ReflectionBinding does not honor XMLSerializable interface
     * 
     * See http://code.google.com/p/memcached-session-manager/issues/detail?id=33
     * 
     * @throws Exception
     */
    @Test( enabled = true )
    public void testXMLSerializableSupport() throws Exception {
        final MemcachedBackupSession session = _manager.createEmptySession();
        session.setValid( true );
        
        final String attributeName = "myxmlserializable";
        session.setAttribute( attributeName, new MyXMLSerializable( Runtime.getRuntime() ) );
        
        final Map<String, Object> deserialized =
                _transcoder.deserializeAttributes( _transcoder.serializeAttributes( session, session.getAttributesInternal() ) );
        
        assertDeepEquals( deserialized, session.getAttributesInternal() );
        final MyXMLSerializable myXMLSerializable = (MyXMLSerializable) deserialized.get( attributeName );
        Assert.assertNotNull( myXMLSerializable.getRuntime(), "Transient field runtime should be initialized by XMLFormat" +
        		" used due to implementation of XMLSerializable." );
    }

    /**
     * This is test for issue #30:
     * msm-javolution-serializer should support serialization of java.util.Collections$UnmodifiableMap  
     * 
     * See http://code.google.com/p/memcached-session-manager/issues/detail?id=30
     * 
     * @throws Exception
     */
    @Test( enabled = true )
    public void testJavaUtilCollectionsUnmodifiable() throws Exception {
        final MemcachedBackupSession session = _manager.createEmptySession();
        session.setValid( true );
        
        session.setAttribute( "unmodifiableList", Collections.unmodifiableList( new ArrayList<String>( Arrays.asList( "foo", "bar" ) ) ) );
        final HashMap<String, String> m = new HashMap<String, String>();
        m.put( "foo", "bar" );
        session.setAttribute( "unmodifiableList", Collections.unmodifiableMap( m ) );

        final Map<String, Object> deserialized =
                _transcoder.deserializeAttributes( _transcoder.serializeAttributes( session, session.getAttributesInternal() ) );
        
        assertDeepEquals( deserialized, session.getAttributesInternal() );
    }

    /**
     * This is the test for issue #28:
     * msm-javolution-serializer should support serialization of java.util.Collections$EmptyList
     * 
     * See http://code.google.com/p/memcached-session-manager/issues/detail?id=28
     * 
     * @throws Exception
     */
    @Test( enabled = true )
    public void testJavaUtilLists() throws Exception {
        final MemcachedBackupSession session = _manager.createEmptySession();
        session.setValid( true );
        
        session.setAttribute( "emptyList", Collections.<String>emptyList() );
        session.setAttribute( "arrayList", new ArrayList<String>() );
        session.setAttribute( "arraysAsList", Arrays.asList( "foo", "bar" ) );

        final Map<String, Object> deserialized =
                _transcoder.deserializeAttributes( _transcoder.serializeAttributes( session, session.getAttributesInternal() ) );
        
        assertDeepEquals( deserialized, session.getAttributesInternal() );
    }

    /**
     * This is another test for issue #28, just for maps:
     * msm-javolution-serializer should support serialization of java.util.Collections$EmptyList
     * 
     * See http://code.google.com/p/memcached-session-manager/issues/detail?id=28
     * 
     * @throws Exception
     */
    @Test( enabled = true )
    public void testJavaUtilCollectionsEmptyMap() throws Exception {
        final MemcachedBackupSession session = _manager.createEmptySession();
        session.setValid( true );
        session.setAttribute( "emptyMap", Collections.<String, String>emptyMap() );
        session.setAttribute( "hashMap", new HashMap<String, String>() );

        final Map<String, Object> deserialized =
                _transcoder.deserializeAttributes( _transcoder.serializeAttributes( session, session.getAttributesInternal() ) );
        
        assertDeepEquals( deserialized, session.getAttributesInternal() );
    }

    @Test( enabled = true )
    public void testProxy() throws Exception {
        final SomeInterface bean = TestClasses.createProxy();
        final byte[] bytes = serialize( bean );
        assertDeepEquals( deserialize( bytes ), bean );
    }

    @Test( enabled = true )
    public void testInnerClass() throws Exception {
        final Container container = TestClasses.createContainer( "some content" );
        assertDeepEquals( deserialize( serialize( container ) ), container );
    }

    @DataProvider( name = "sharedObjectIdentityProvider" )
    protected Object[][] createSharedObjectIdentityProviderData() {
        return new Object[][] { { AtomicInteger.class.getSimpleName(), new AtomicInteger( 42 ) },
                { Email.class.getSimpleName(), new Email( "foo bar", "foo.bar@example.com" ) } };
    }

    @Test( enabled = true )
    public <T> void testSharedObjectIdentity_CounterHolder() throws Exception {

        final AtomicInteger sharedObject = new AtomicInteger( 42 );
        final CounterHolder holder1 = new CounterHolder( sharedObject );
        final CounterHolder holder2 = new CounterHolder( sharedObject );
        final CounterHolderArray holderHolder = new CounterHolderArray( holder1, holder2 );

        final MemcachedBackupSession session = _manager.createEmptySession();
        session.setValid( true );
        session.setAttribute( "hh", holderHolder );
        
        final Map<String, Object> deserialized =
                _transcoder.deserializeAttributes( _transcoder.serializeAttributes( session, session.getAttributesInternal() ) );
        assertDeepEquals( deserialized, session.getAttributesInternal() );

        final CounterHolderArray hhd = (CounterHolderArray) deserialized.get( "hh" );

        Assert.assertTrue( hhd.holders[0].item == hhd.holders[1].item );

    }

    @Test( enabled = true, dataProvider = "sharedObjectIdentityProvider" )
    public <T> void testSharedObjectIdentityWithArray( final String name, final T sharedObject ) throws Exception {

        final Holder<T> holder1 = new Holder<T>( sharedObject );
        final Holder<T> holder2 = new Holder<T>( sharedObject );
        @SuppressWarnings( "unchecked" )
        final HolderArray<T> holderHolder = new HolderArray<T>( holder1, holder2 );

        final MemcachedBackupSession session = _manager.createEmptySession();
        session.setValid( true );
        session.setAttribute( name, holderHolder );

        final Map<String, Object> deserialized =
                _transcoder.deserializeAttributes( _transcoder.serializeAttributes( session, session.getAttributesInternal() ) );
        assertDeepEquals( deserialized, session.getAttributesInternal() );

        @SuppressWarnings( "unchecked" )
        final HolderArray<T> hhd = (HolderArray<T>) deserialized.get( name );

        Assert.assertTrue( hhd.holders[0].item == hhd.holders[1].item );

    }

    @Test( enabled = true, dataProvider = "sharedObjectIdentityProvider" )
    public <T> void testSharedObjectIdentity( final String name, final T sharedObject ) throws Exception {

        final Holder<T> holder1 = new Holder<T>( sharedObject );
        final Holder<T> holder2 = new Holder<T>( sharedObject );
        @SuppressWarnings( "unchecked" )
        final HolderList<T> holderHolder = new HolderList<T>( new ArrayList<Holder<T>>( Arrays.asList( holder1, holder2 ) ) );

        final MemcachedBackupSession session = _manager.createEmptySession();
        session.setValid( true );
        session.setAttribute( name, holderHolder );

        final Map<String, Object> deserialized =
                _transcoder.deserializeAttributes( _transcoder.serializeAttributes( session, session.getAttributesInternal() ) );
        assertDeepEquals( deserialized, session.getAttributesInternal() );

        @SuppressWarnings( "unchecked" )
        final HolderList<T> hhd = (HolderList<T>) deserialized.get( name );

        Assert.assertTrue( hhd.holders.get( 0 ).item == hhd.holders.get( 1 ).item );

    }

    @DataProvider( name = "typesAsSessionAttributesProvider" )
    protected Object[][] createTypesAsSessionAttributesData() {
        return new Object[][] { { int.class, 42 },
                { long.class, 42 },
                { Boolean.class, Boolean.TRUE },
                { String.class, "42" },
                { Class.class, String.class },
                { Long.class, new Long( 42 ) },
                { Integer.class, new Integer( 42 ) },
                { Character.class, new Character( 'c' ) },
                { Byte.class, new Byte( "b".getBytes()[0] ) },
                { Double.class, new Double( 42d ) },
                { Float.class, new Float( 42f ) },
                { Short.class, new Short( (short) 42 ) },
                { BigDecimal.class, new BigDecimal( 42 ) },
                { AtomicInteger.class, new AtomicInteger( 42 ) },
                { AtomicLong.class, new AtomicLong( 42 ) },
                { MutableInt.class, new MutableInt( 42 ) },
                { Integer[].class, new Integer[] { 42 } },
                { Date.class, new Date( System.currentTimeMillis() - 10000 ) },
                { Calendar.class, Calendar.getInstance() },
                { Currency.class, Currency.getInstance( "EUR" ) },
                { ArrayList.class, new ArrayList<String>( Arrays.asList( "foo" ) ) },
                { int[].class, new int[] { 1, 2 } },
                { long[].class, new long[] { 1, 2 } },
                { short[].class, new short[] { 1, 2 } },
                { float[].class, new float[] { 1, 2 } },
                { double[].class, new double[] { 1, 2 } },
                { int[].class, new int[] { 1, 2 } },
                { byte[].class, "42".getBytes() },
                { char[].class, "42".toCharArray() },
                { String[].class, new String[] { "23", "42" } },
                { Person[].class, new Person[] { createPerson( "foo bar", Gender.MALE, 42 ) } } };
    }

    @Test( enabled = true, dataProvider = "typesAsSessionAttributesProvider" )
    public <T> void testTypesAsSessionAttributes( final Class<T> type, final T instance ) throws Exception {

        final MemcachedBackupSession session = _manager.createEmptySession();
        session.setValid( true );
        session.setAttribute( type.getSimpleName(), instance );

        final byte[] bytes = _transcoder.serializeAttributes( session, session.getAttributesInternal() );
        assertDeepEquals( _transcoder.deserializeAttributes( bytes ), session.getAttributesInternal());
    }

    @Test( enabled = true )
    public void testTypesInContainerClass() throws Exception {

        final MemcachedBackupSession session = _manager.createEmptySession();
        session.setValid( true );
        session.setAttribute( MyContainer.class.getSimpleName(), new MyContainer() );

        final Map<String, Object> deserialized = _transcoder.deserializeAttributes( _transcoder.serializeAttributes( session, session.getAttributesInternal() ) );
        assertDeepEquals( deserialized, session.getAttributesInternal() );
    }

    @Test( enabled = true )
    public void testClassWithoutDefaultConstructor() throws Exception {

        final MemcachedBackupSession session = _manager.createEmptySession();
        session.setValid( true );
        session.setAttribute( "no-default constructor", TestClasses.createClassWithoutDefaultConstructor( "foo" ) );

        final Map<String, Object> deserialized = _transcoder.deserializeAttributes( _transcoder.serializeAttributes( session, session.getAttributesInternal() ) );
        assertDeepEquals( deserialized, session.getAttributesInternal() );
    }

    @Test( enabled = true )
    public void testPrivateClass() throws Exception {

        final MemcachedBackupSession session = _manager.createEmptySession();
        session.setValid( true );
        session.setAttribute( "pc", TestClasses.createPrivateClass( "foo" ) );

        final Map<String, Object> deserialized = _transcoder.deserializeAttributes( _transcoder.serializeAttributes( session, session.getAttributesInternal() ) );
        assertDeepEquals( deserialized, session.getAttributesInternal() );
    }

    @Test( enabled = true )
    public void testCollections() throws Exception {
        final MemcachedBackupSession session = _manager.createEmptySession();
        session.setValid( true );
        session.setAttribute( "foo", new EntityWithCollections() );

        final Map<String, Object> deserialized = _transcoder.deserializeAttributes( _transcoder.serializeAttributes( session, session.getAttributesInternal() ) );
        assertDeepEquals( deserialized, session.getAttributesInternal() );
    }

    @Test( enabled = true )
    public void testCyclicDependencies() throws Exception {
        final MemcachedBackupSession session = _manager.createEmptySession();
        session.setValid( true );
        session.setCreationTime( System.currentTimeMillis() );
        getField( StandardSession.class, "lastAccessedTime" ).set( session, System.currentTimeMillis() + 100 );
        session.setMaxInactiveInterval( 600 );

        final Person p1 = createPerson( "foo bar", Gender.MALE, 42, "foo.bar@example.org", "foo.bar@example.com" );
        final Person p2 = createPerson( "bar baz", Gender.FEMALE, 42, "bar.baz@example.org", "bar.baz@example.com" );
        p1.addFriend( p2 );
        p2.addFriend( p1 );

        session.setAttribute( "person1", p1 );
        session.setAttribute( "person2", p2 );

        final byte[] bytes = _transcoder.serializeAttributes( session, session.getAttributesInternal() );
        assertDeepEquals( session.getAttributesInternal(), _transcoder.deserializeAttributes( bytes ) );

    }

    public static class EntityWithCollections {
        private final String[] _bars;
        private final List<String> _foos;
        private final Map<String, Integer> _bazens;

        public EntityWithCollections() {
            _bars = new String[] { "foo", "bar" };
            _foos = new ArrayList<String>( Arrays.asList( "foo", "bar" ) );
            _bazens = new HashMap<String, Integer>();
            _bazens.put( "foo", 1 );
            _bazens.put( "bar", 2 );
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + Arrays.hashCode( _bars );
            result = prime * result + ( ( _bazens == null )
                ? 0
                : _bazens.hashCode() );
            result = prime * result + ( ( _foos == null )
                ? 0
                : _foos.hashCode() );
            return result;
        }

        @Override
        public boolean equals( final Object obj ) {
            if ( this == obj )
                return true;
            if ( obj == null )
                return false;
            if ( getClass() != obj.getClass() )
                return false;
            final EntityWithCollections other = (EntityWithCollections) obj;
            if ( !Arrays.equals( _bars, other._bars ) )
                return false;
            if ( _bazens == null ) {
                if ( other._bazens != null )
                    return false;
            } else if ( !_bazens.equals( other._bazens ) )
                return false;
            if ( _foos == null ) {
                if ( other._foos != null )
                    return false;
            } else if ( !_foos.equals( other._foos ) )
                return false;
            return true;
        }
    }

    private Field getField( final Class<?> clazz, final String name ) throws NoSuchFieldException {
        final Field field = clazz.getDeclaredField( name );
        field.setAccessible( true );
        return field;
    }

    /*
     * person2=Person [_gender=FEMALE, _name=bar baz, _props={email0=Email
     * [_email=bar.baz@example.org, _name=bar baz], email1=Email
     * [_email=bar.baz@example.com, _name=bar baz]}], person1=Person
     * [_gender=MALE, _name=foo bar, _props={email0=Email
     * [_email=foo.bar@example.org, _name=foo bar], email1=Email
     * [_email=foo.bar@example.com, _name=foo bar]}]}
     * 
     * but was: person2={name=bar baz, props={email0={name=bar baz,
     * email=bar.baz@example.org}, email1={name=bar baz,
     * email=bar.baz@example.com}}, gender=FEMALE} person1={name=foo bar,
     * props={email0={name=foo bar, email=foo.bar@example.org}, email1={name=foo
     * bar, email=foo.bar@example.com}}, gender=MALE}}
     */

    private void assertDeepEquals( final Object one, final Object another ) throws Exception {
        assertDeepEquals( one, another, new IdentityHashMap<Object, Object>() );
    }

    private void assertDeepEquals( final Object one, final Object another, final Map<Object, Object> alreadyChecked )
        throws Exception {
        if ( one == another ) {
            return;
        }
        if ( one == null && another != null || one != null && another == null ) {
            Assert.fail( "One of both is null: " + one + ", " + another );
        }
        if ( alreadyChecked.containsKey( one ) ) {
            return;
        }
        alreadyChecked.put( one, another );

        Assert.assertEquals( one.getClass(), another.getClass() );
        if ( one.getClass().isPrimitive() || one instanceof String || one instanceof Character || one instanceof Boolean ) {
            Assert.assertEquals( one, another );
            return;
        }

        if ( Map.class.isAssignableFrom( one.getClass() ) ) {
            final Map<?, ?> m1 = (Map<?, ?>) one;
            final Map<?, ?> m2 = (Map<?, ?>) another;
            Assert.assertEquals( m1.size(), m2.size() );
            for ( final Map.Entry<?, ?> entry : m1.entrySet() ) {
                assertDeepEquals( entry.getValue(), m2.get( entry.getKey() ) );
            }
            return;
        }

        if ( Number.class.isAssignableFrom( one.getClass() ) ) {
            Assert.assertEquals( ( (Number) one ).longValue(), ( (Number) another ).longValue() );
            return;
        }
        
        if ( one instanceof Currency ) {
            // Check that the transient field defaultFractionDigits is initialized correctly (that was issue #34)
            final Currency currency1 = ( Currency) one;
            final Currency currency2 = ( Currency) another;
            Assert.assertEquals( currency1.getCurrencyCode(), currency2.getCurrencyCode() );
            Assert.assertEquals( currency1.getDefaultFractionDigits(), currency2.getDefaultFractionDigits() );
        }

        Class<? extends Object> clazz = one.getClass();
        while ( clazz != null ) {
            assertEqualDeclaredFields( clazz, one, another, alreadyChecked );
            clazz = clazz.getSuperclass();
        }

    }

    private void assertEqualDeclaredFields( final Class<? extends Object> clazz, final Object one, final Object another,
            final Map<Object, Object> alreadyChecked ) throws Exception, IllegalAccessException {
        for ( final Field field : clazz.getDeclaredFields() ) {
            field.setAccessible( true );
            if ( !Modifier.isTransient( field.getModifiers() ) ) {
                assertDeepEquals( field.get( one ), field.get( another ), alreadyChecked );
            }
        }
    }

    private StandardSession javaRoundtrip( final StandardSession session, final MemcachedBackupSessionManager manager )
        throws IOException, ClassNotFoundException {

        final long start1 = System.nanoTime();
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        final ObjectOutputStream oos = new ObjectOutputStream( bos );
        session.writeObjectData( oos );
        oos.close();
        bos.close();
        System.out.println( "java-ser took " + ( System.nanoTime() - start1 ) / 1000 );

        final ByteArrayInputStream bis = new ByteArrayInputStream( bos.toByteArray() );
        final ObjectInputStream ois = new ObjectInputStream( bis );
        final StandardSession readSession = manager.createEmptySession();
        readSession.readObjectData( ois );
        ois.close();
        bis.close();

        return readSession;
    }

    protected byte[] serialize( final Object o ) {
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
            writer.setBinding( new ReflectionBinding( getClass().getClassLoader() ) );
            writer.write( o, "session" );
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

    protected Object deserialize( final byte[] in ) {
        XMLObjectReader reader = null;
        try {
            final ByteArrayInputStream bis = new ByteArrayInputStream( in );
            reader = XMLObjectReader.newInstance( bis );
            final XMLReferenceResolver xmlReferenceResolver = new XMLReferenceResolver();
            xmlReferenceResolver.setIdentifierAttribute( JavolutionTranscoder.REFERENCE_ATTRIBUTE_ID );
            xmlReferenceResolver.setReferenceAttribute( JavolutionTranscoder.REFERENCE_ATTRIBUTE_REF_ID );
            reader.setReferenceResolver( xmlReferenceResolver );
            reader.setBinding( new ReflectionBinding( getClass().getClassLoader() ) );
            if ( !reader.hasNext() ) {
                throw new IllegalStateException( "reader has no input" );
            }
            return reader.read( "session" );
        } catch ( final RuntimeException e ) {
            throw e;
        } catch ( final javolution.xml.stream.XMLStreamException e ) {
            throw new RuntimeException( e );
        } finally {
            try {
                reader.close();
            } catch ( final XMLStreamException e ) {
                // fail silently
            }
        }
    }

}

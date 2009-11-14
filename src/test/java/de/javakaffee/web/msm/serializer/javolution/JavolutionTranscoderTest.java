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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.catalina.core.StandardContext;
import org.apache.catalina.loader.WebappLoader;
import org.apache.catalina.session.StandardSession;
import org.apache.commons.lang.builder.ToStringBuilder;
import org.apache.commons.lang.mutable.MutableInt;
import org.jmock.Mock;
import org.jmock.cglib.MockObjectTestCase;
import org.testng.Assert;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import de.javakaffee.web.msm.MemcachedBackupSessionManager;
import de.javakaffee.web.msm.MemcachedBackupSessionManager.MemcachedBackupSession;
import de.javakaffee.web.msm.serializer.javolution.JavolutionTranscoderTest.Person.Gender;

/**
 * Test for {@link JavolutionTranscoder}
 * 
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class JavolutionTranscoderTest extends MockObjectTestCase {
    
    private MemcachedBackupSessionManager _manager;
    private JavolutionTranscoder _transcoder;

    public static void main( final String[] args ) {
        try {
            final Constructor<?> constructor = BigDecimal.class.getConstructor( int.class );
            final Object instance = constructor.newInstance( 42 );
            System.out.println( "have instance: " + instance );
        } catch ( final Exception e ) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }
    
    @BeforeTest
    protected void beforeTest() {
        _manager = new MemcachedBackupSessionManager();
        
        final StandardContext container = new StandardContext();
        _manager.setContainer( container );

        final Mock webappLoaderControl = mock( WebappLoader.class );
        final WebappLoader webappLoader = (WebappLoader) webappLoaderControl.proxy();
        webappLoaderControl.expects( once() ).method( "setContainer" ).withAnyArguments();
        webappLoaderControl.expects( atLeastOnce() ).method( "getClassLoader" ).will( returnValue( Thread.currentThread().getContextClassLoader() ) );
        Assert.assertNotNull( webappLoader.getClassLoader(), "Webapp Classloader is null." );
        _manager.getContainer().setLoader( webappLoader );
        
        Assert.assertNotNull( _manager.getContainer().getLoader().getClassLoader(), "Classloader is null." );
        
        _transcoder = new JavolutionTranscoder( _manager );
    }

    @DataProvider( name="typesProvider")
    protected Object[][] createTypesData() {
        return new Object[][] {
                { int.class, 42 },
                { long.class, 42 },
                { String.class, "42" },
                { Long.class, new Long( 42 ) },
                { Integer.class, new Integer( 42 ) },
                { Character.class, new Character( 'c' ) },
                { Byte.class, new Byte( "b".getBytes()[0] ) },
                { Double.class, new Double( 42d ) },
                { Float.class, new Float( 42f ) },
                { Short.class, new Short( (short)42 ) },
                { BigDecimal.class, new BigDecimal( 42 ) },
                { AtomicInteger.class, new AtomicInteger( 42 ) },
                { AtomicLong.class, new AtomicLong( 42 ) },
                { MutableInt.class, new MutableInt( 42 ) },
                { Integer[].class, new Integer[]{ 42 } },
                { Date.class, new Date( System.currentTimeMillis() - 10000 ) },
                { Calendar.class, Calendar.getInstance() },
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
                { Person[].class, new Person[] { createPerson( "foo bar", Gender.MALE, 42 ) } }
        };
    }
    
    @Test(enabled=true, dataProvider="typesProvider")
    public <T> void testTypes( final Class<T> type, final T instance ) throws Exception {
        
        final MemcachedBackupSession session = _manager.createEmptySession();
        session.setValid( true );
        System.out.println( "HAve instance: " + instance );
        session.setAttribute( type.getSimpleName(), instance );
        
        final byte[] serialized = _transcoder.serialize( session );
        System.out.println(new String(serialized));
        assertDeepEquals( _transcoder.deserialize( serialized ), session );
    }
    
    @Test(enabled=false)
    public void testClassWithoutDefaultConstructor() throws Exception {
        
        final MemcachedBackupSession session = _manager.createEmptySession();
        session.setValid( true );
        session.setAttribute( "no-default constructor", new ClassWithoutDefaultConstructor( "foo" ) );
        
        assertDeepEquals( _transcoder.deserialize( _transcoder.serialize( session ) ), session );
    }
    
    @Test(enabled=false)
    public void testPrivateClass() throws Exception {
        
        final MemcachedBackupSession session = _manager.createEmptySession();
        session.setValid( true );
        final PrivateClass privateClass = new PrivateClass();
        privateClass.foo = "foo";
        session.setAttribute( "pc", privateClass );
        
        System.out.println( new String( _transcoder.serialize( session ) ) );
        assertDeepEquals( _transcoder.deserialize( _transcoder.serialize( session ) ), session );
    }
    
    @Test(enabled=false)
    public void testCollections() throws Exception {
        final MemcachedBackupSession session = _manager.createEmptySession();
        session.setValid( true );
        session.setAttribute( "foo", new EntityWithCollections() );
        
        assertDeepEquals( _transcoder.deserialize( _transcoder.serialize( session ) ), session );
    }

    @Test(enabled=false)
    public void testCyclicDependencies() throws Exception {
        final StandardSession session = _manager.createEmptySession();
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
        
        final byte[] bytes = _transcoder.serialize( session );
        // System.out.println( "xml: " + new String( bytes ) );
        assertDeepEquals( session, _transcoder.deserialize( bytes ) );
        
        
    }

    @Test(enabled=false)
    public void testReadValueIntoObject() throws Exception {
        final StandardSession session = _manager.createEmptySession();
        session.setValid( true );
        session.setCreationTime( System.currentTimeMillis() );
        getField( StandardSession.class, "lastAccessedTime" ).set( session, System.currentTimeMillis() + 100 );
        session.setMaxInactiveInterval( 600 );

        session.setId( "foo" );

        session.setAttribute( "person1", createPerson( "foo bar", Gender.MALE, 42, "foo.bar@example.org", "foo.bar@example.com" ) );
        session.setAttribute( "person2", createPerson( "bar baz", Gender.FEMALE, 42, "bar.baz@example.org", "bar.baz@example.com" ) );

        final long start1 = System.nanoTime();
        _transcoder.serialize( session );
        System.out.println("javolution ser took " + (System.nanoTime() - start1)/1000);

        final long start2 = System.nanoTime();
        _transcoder.serialize( session );
        System.out.println("javolution ser took " + (System.nanoTime() - start2)/1000);
        
        final long start3 = System.nanoTime();
        final byte[] json = _transcoder.serialize( session );
        final StandardSession readJSONValue = (StandardSession) _transcoder.deserialize( json );
        System.out.println("javolution-round took " + (System.nanoTime() - start3)/1000);

        System.out.println( "Have xml: " + readJSONValue.getId() );
        assertDeepEquals( readJSONValue, session );

        final long start4 = System.nanoTime();
        final StandardSession readJavaValue = javaRoundtrip( session, _manager );
        System.out.println("java-round took " + (System.nanoTime() - start4)/1000);
        assertDeepEquals( readJavaValue, session );

        assertDeepEquals( readJSONValue, readJavaValue );

        System.out.println( ToStringBuilder.reflectionToString( session ) );
        System.out.println( ToStringBuilder.reflectionToString( readJSONValue ) );
        System.out.println( ToStringBuilder.reflectionToString( readJavaValue ) );

    }
    
    public static class EntityWithCollections {
        private final String[] _bars;
        private final List<String> _foos;
        private final Map<String,Integer> _bazens;
        public EntityWithCollections() {
            _bars = new String[] { "foo", "bar" };
            _foos = new ArrayList<String>(Arrays.asList( "foo", "bar" ));
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

    private Person createPerson( final String name, final Gender gender, final Integer age, final String... emailAddresses ) {
        final Person person = new Person();
        person.setName( name );
        person.setGender( gender );
        person.setAge( age );
        final HashMap<String, Object> props = new HashMap<String, Object>();
        for ( int i = 0; i < emailAddresses.length; i++ ) {
            final String emailAddress = emailAddresses[i];
            props.put( "email" + i, new Email( name, emailAddress ) );
        }
        person.setProps( props );
        return person;
    }

    private Field getField( final Class<?> clazz, final String name ) throws NoSuchFieldException {
        final Field field = clazz.getDeclaredField( name );
        field.setAccessible( true );
        return field;
    }
    
    /*
     * person2=Person
     * [_gender=FEMALE, _name=bar baz,
     *      _props={email0=Email [_email=bar.baz@example.org, _name=bar baz],
     *          email1=Email [_email=bar.baz@example.com, _name=bar baz]}],
     * person1=Person [_gender=MALE, _name=foo bar,
     *      _props={email0=Email [_email=foo.bar@example.org, _name=foo bar],
     *          email1=Email [_email=foo.bar@example.com, _name=foo bar]}]}
     *          
     * but was:
     * person2={name=bar baz,
     *      props={email0={name=bar baz, email=bar.baz@example.org},
     *          email1={name=bar baz, email=bar.baz@example.com}}, gender=FEMALE}
     * person1={name=foo bar,
     *      props={email0={name=foo bar, email=foo.bar@example.org},
     *          email1={name=foo bar, email=foo.bar@example.com}}, gender=MALE}}
     */

    private void assertDeepEquals( final Object one, final Object another ) throws Exception {
        if ( one == another ) {
            return;
        }
        if ( one == null && another != null || one != null && another == null ) {
            Assert.fail( "One of both is null: " + one + ", " + another );
        }
        Assert.assertEquals( one.getClass(), another.getClass() );
        if ( one.getClass().isPrimitive() || one instanceof String || one instanceof Character
                || one instanceof Boolean ) {
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
            Assert.assertEquals( ((Number)one).longValue(), ((Number)another).longValue() );
            return;
        }
        
        Class<? extends Object> clazz = one.getClass();
        while ( clazz != null ) {
            assertEqualDeclaredFields( clazz, one, another );
            clazz = clazz.getSuperclass();
        }

    }

    private void assertEqualDeclaredFields( final Class<? extends Object> clazz, final Object one, final Object another )
        throws Exception, IllegalAccessException {
        for ( final Field field : clazz.getDeclaredFields() ) {
            field.setAccessible( true );
            if ( !Modifier.isTransient( field.getModifiers() ) ) {
                assertDeepEquals( field.get( one ), field.get( another ) );
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
        System.out.println("java-ser took " + (System.nanoTime() - start1)/1000);

        final ByteArrayInputStream bis = new ByteArrayInputStream( bos.toByteArray() );
        final ObjectInputStream ois = new ObjectInputStream( bis );
        final StandardSession readSession = manager.createEmptySession();
        readSession.readObjectData( ois );
        ois.close();
        bis.close();

        return readSession;
    }

    public static class Person implements Serializable {

        private static final long serialVersionUID = 1L;

        static enum Gender {
                MALE,
                FEMALE
        }

        private String _name;
        private Gender _gender;
        private Integer _age;
        private Map<String, Object> _props;
        private final Collection<Person> _friends = new ArrayList<Person>();

        public String getName() {
            return _name;
        }
        
        public void addFriend( final Person p ) {
            _friends.add( p );
        }

        public void setName( final String name ) {
            _name = name;
        }

        public Map<String, Object> getProps() {
            return _props;
        }

        public void setProps( final Map<String, Object> props ) {
            _props = props;
        }

        public Gender getGender() {
            return _gender;
        }

        public void setGender( final Gender gender ) {
            _gender = gender;
        }

        public Integer getAge() {
            return _age;
        }

        public void setAge( final Integer age ) {
            _age = age;
        }

        public Collection<Person> getFriends() {
            return _friends;
        }

        /**
         * @param friends
         * @param friends2
         * @return
         */
        private boolean flatEquals( final Collection<?> c1, final Collection<?> c2 ) {
            return c1 == c2 || c1 != null && c2 != null && c1.size() == c2.size();
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ( ( _age == null )
                ? 0
                : _age.hashCode() );
            result = prime * result + ( ( _friends == null )
                ? 0
                : _friends.size() );
            result = prime * result + ( ( _gender == null )
                ? 0
                : _gender.hashCode() );
            result = prime * result + ( ( _name == null )
                ? 0
                : _name.hashCode() );
            result = prime * result + ( ( _props == null )
                ? 0
                : _props.hashCode() );
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
            final Person other = (Person) obj;
            if ( _age == null ) {
                if ( other._age != null )
                    return false;
            } else if ( !_age.equals( other._age ) )
                return false;
            if ( _friends == null ) {
                if ( other._friends != null )
                    return false;
            } else if ( !flatEquals( _friends, other._friends ) )
                return false;
            if ( _gender == null ) {
                if ( other._gender != null )
                    return false;
            } else if ( !_gender.equals( other._gender ) )
                return false;
            if ( _name == null ) {
                if ( other._name != null )
                    return false;
            } else if ( !_name.equals( other._name ) )
                return false;
            if ( _props == null ) {
                if ( other._props != null )
                    return false;
            } else if ( !_props.equals( other._props ) )
                return false;
            return true;
        }

        @Override
        public String toString() {
            return "Person [_age=" + _age + ", _friends.size=" + _friends.size() + ", _gender=" + _gender + ", _name=" + _name + ", _props="
                    + _props + "]";
        }

    }

    public static class Email implements Serializable {

        private static final long serialVersionUID = 1L;

        private String _name;
        private String _email;
        
        public Email() {
        }

        public Email( final String name, final String email ) {
            super();
            _name = name;
            _email = email;
        }

        public String getName() {
            return _name;
        }

        public void setName( final String name ) {
            _name = name;
        }

        public String getEmail() {
            return _email;
        }

        public void setEmail( final String email ) {
            _email = email;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ( ( _email == null )
                ? 0
                : _email.hashCode() );
            result = prime * result + ( ( _name == null )
                ? 0
                : _name.hashCode() );
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
            final Email other = (Email) obj;
            if ( _email == null ) {
                if ( other._email != null )
                    return false;
            } else if ( !_email.equals( other._email ) )
                return false;
            if ( _name == null ) {
                if ( other._name != null )
                    return false;
            } else if ( !_name.equals( other._name ) )
                return false;
            return true;
        }

        @Override
        public String toString() {
            return "Email [_email=" + _email + ", _name=" + _name + "]";
        }

    }
    
    public static class PublicClass {
        PrivateClass privateClass;
        public PublicClass() {
        }
        public PublicClass( final PrivateClass protectedClass ) {
            this.privateClass = protectedClass;
        }
        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ( ( privateClass == null )
                ? 0
                : privateClass.hashCode() );
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
            final PublicClass other = (PublicClass) obj;
            if ( privateClass == null ) {
                if ( other.privateClass != null )
                    return false;
            } else if ( !privateClass.equals( other.privateClass ) )
                return false;
            return true;
        }
    }
    
    private static class PrivateClass {
        String foo;
        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ( ( foo == null )
                ? 0
                : foo.hashCode() );
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
            final PrivateClass other = (PrivateClass) obj;
            if ( foo == null ) {
                if ( other.foo != null )
                    return false;
            } else if ( !foo.equals( other.foo ) )
                return false;
            return true;
        }
    }
    
    public class ClassWithoutDefaultConstructor {
        final String value;
        public ClassWithoutDefaultConstructor( final String value ) {
            this.value = value;
        }
        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ( ( value == null )
                ? 0
                : value.hashCode() );
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
            final ClassWithoutDefaultConstructor other = (ClassWithoutDefaultConstructor) obj;
            if ( value == null ) {
                if ( other.value != null )
                    return false;
            } else if ( !value.equals( other.value ) )
                return false;
            return true;
        }
        @Override
        public String toString() {
            return "ClassWithoutDefaultConstructor [value=" + value + "]";
        }
    }

}

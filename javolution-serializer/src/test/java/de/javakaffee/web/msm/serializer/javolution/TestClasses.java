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

import java.io.Serializable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Currency;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javolution.xml.XMLFormat;
import javolution.xml.XMLSerializable;
import javolution.xml.stream.XMLStreamException;

import org.apache.commons.lang.mutable.MutableInt;

import de.javakaffee.web.msm.serializer.javolution.TestClasses.Person.Gender;

/**
 * Test for {@link JavolutionTranscoder}
 * 
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class TestClasses {

    static Person createPerson( final String name, final Gender gender, final String... emailAddresses ) {
        final Person person = new Person();
        person.setName( name );
        person.setGender( gender );
        if ( emailAddresses != null ) {
            final HashMap<String, Object> props = new HashMap<String, Object>();
            for ( int i = 0; i < emailAddresses.length; i++ ) {
                final String emailAddress = emailAddresses[i];
                props.put( "email" + i, new Email( name, emailAddress ) );
            }
            person.setProps( props );
        }
        return person;
    }

    static Person createPerson( final String name, final Gender gender, final Integer age, final String... emailAddresses ) {
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

    static ClassWithoutDefaultConstructor createClassWithoutDefaultConstructor( final String string ) {
        return new ClassWithoutDefaultConstructor( string );
    }

    static PrivateClass createPrivateClass( final String string ) {
        final PrivateClass result = new PrivateClass();
        result.foo = string;
        return result;
    }

    static Container createContainer( final String bodyContent ) {
        return new Container( bodyContent );
    }
    
    static SomeInterface createProxy() {
        return (SomeInterface) Proxy.newProxyInstance( Thread.currentThread().getContextClassLoader(),
                new Class<?>[] { SomeInterface.class, Serializable.class },
                new MyInvocationHandler( SomeInterfaceImpl.class ) );
    }
    
    static class MyInvocationHandler implements InvocationHandler {
        
        private final Class<?> _targetClazz;
        private transient Object _target;

        public MyInvocationHandler( final Class<?> targetClazz ) {
            _targetClazz = targetClazz;
        }

        public Object invoke( final Object proxy, final Method method, final Object[] args ) throws Throwable {
            if ( _target == null ) {
                _target = _targetClazz.newInstance();
            }
            return method.invoke( _target, args );
        }
    }

    static interface SomeInterface {
        String hello();
    }
    
    static class SomeInterfaceImpl implements SomeInterface {

        /**
         * {@inheritDoc}
         */
        public String hello() {
            return "hi";
        }
        
    }
    
    /**
     * A class with a transient field that must be initialized after deserialization,
     * this is a way to test if the XMLFormat defined in this XMLSerializable implementation
     * is used and if XMLSerializable is honored at all.
     */
    public static class MyXMLSerializable implements XMLSerializable {
        
        private static final long serialVersionUID = -3392119483974151376L;
        
        protected static final XMLFormat<MyXMLSerializable> XML = new XMLFormat<MyXMLSerializable>(MyXMLSerializable.class) {
            public MyXMLSerializable newInstance( final Class<MyXMLSerializable> cls, final InputElement xml ) throws XMLStreamException {
                return new MyXMLSerializable( Runtime.getRuntime() );
            }
            public void write( final MyXMLSerializable obj, final OutputElement xml ) throws XMLStreamException {
                // nothing to do
            }
            public void read( final InputElement xml, final MyXMLSerializable obj ) {
                // Immutable, deserialization occurs at creation, ref. newIntance(...) 
             }
        };
        
        // Just some field that should not be serialized,
        // but which must be available after deserialization
        private transient final Runtime _runtime;
        
        public MyXMLSerializable( final Runtime runtime ) {
            _runtime = runtime;
        }

        public Runtime getRuntime() {
            return _runtime;
        }
        
    }

    public static class Container {

        private final Body _body;

        public Container( final String bodyContent ) {
            _body = new Body();
            _body.someContent = bodyContent;
        }

        class Body {
            String someContent;
        }

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
            return "Person [_age=" + _age + ", _friends.size=" + _friends.size() + ", _gender=" + _gender + ", _name=" + _name
                    + ", _props=" + _props + "]";
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

    public static class ClassWithoutDefaultConstructor {
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

    @SuppressWarnings( "unused" )
    public static class MyContainer {

        private int _int;
        private long _long;
        private final boolean _boolean;
        private final Boolean _Boolean;
        private final Class<?> _Class;
        private String _String;
        private Long _Long;
        private Integer _Integer;
        private Character _Character;
        private Byte _Byte;
        private Double _Double;
        private Float _Float;
        private Short _Short;
        private BigDecimal _BigDecimal;
        private AtomicInteger _AtomicInteger;
        private AtomicLong _AtomicLong;
        private MutableInt _MutableInt;
        private Integer[] _IntegerArray;
        private Date _Date;
        private Calendar _Calendar;
        private Currency _Currency;
        private List<String> _ArrayList;
        private final Set<String> _HashSet;
        private final Map<String, Integer> _HashMap;
        private int[] _intArray;
        private long[] _longArray;
        private short[] _shortArray;
        private float[] _floatArray;
        private double[] _doubleArray;
        private byte[] _byteArray;
        private char[] _charArray;
        private String[] _StringArray;
        private Person[] _PersonArray;

        public MyContainer() {

            _int = 42;
            _long = 42;
            _boolean = true;
            _Boolean = Boolean.TRUE;
            _Class = String.class;
            _String = "42";
            _Long = new Long( 42 );
            _Integer = new Integer( 42 );
            _Character = new Character( 'c' );
            _Byte = new Byte( "b".getBytes()[0] );
            _Double = new Double( 42d );
            _Float = new Float( 42f );
            _Short = new Short( (short) 42 );
            _BigDecimal = new BigDecimal( 42 );
            _AtomicInteger = new AtomicInteger( 42 );
            _AtomicLong = new AtomicLong( 42 );
            _MutableInt = new MutableInt( 42 );
            _IntegerArray = new Integer[] { 42 };
            _Date = new Date( System.currentTimeMillis() - 10000 );
            _Calendar = Calendar.getInstance();
            _Currency = Currency.getInstance( "EUR" );
            _ArrayList = new ArrayList<String>( Arrays.asList( "foo" ) );
            _HashSet = new HashSet<String>();
            _HashSet.add( "42" );

            _HashMap = new HashMap<String, Integer>();
            _HashMap.put( "foo", 23 );
            _HashMap.put( "bar", 42 );

            _intArray = new int[] { 1, 2 };
            _longArray = new long[] { 1, 2 };
            _shortArray = new short[] { 1, 2 };
            _floatArray = new float[] { 1, 2 };
            _doubleArray = new double[] { 1, 2 };
            _byteArray = "42".getBytes();
            _charArray = "42".toCharArray();
            _StringArray = new String[] { "23", "42" };
            _PersonArray = new Person[] { createPerson( "foo bar", Gender.MALE, 42 ) };

        }

        public int getInt() {
            return _int;
        }

        public void setInt( final int i ) {
            _int = i;
        }

        public long getLong() {
            return _long;
        }

        public void setLong( final long l ) {
            _long = l;
        }

        public String getString() {
            return _String;
        }

        public void setString( final String string ) {
            _String = string;
        }

        public Long getLongWrapper() {
            return _Long;
        }

        public void setLongWrapper( final Long l ) {
            _Long = l;
        }

        public Integer getInteger() {
            return _Integer;
        }

        public void setInteger( final Integer integer ) {
            _Integer = integer;
        }

        public Character getCharacter() {
            return _Character;
        }

        public void setCharacter( final Character character ) {
            _Character = character;
        }

        public Byte getByte() {
            return _Byte;
        }

        public void setByte( final Byte b ) {
            _Byte = b;
        }

        public Double getDouble() {
            return _Double;
        }

        public void setDouble( final Double d ) {
            _Double = d;
        }

        public Float getFloat() {
            return _Float;
        }

        public void setFloat( final Float f ) {
            _Float = f;
        }

        public Short getShort() {
            return _Short;
        }

        public void setShort( final Short s ) {
            _Short = s;
        }

        public BigDecimal getBigDecimal() {
            return _BigDecimal;
        }

        public void setBigDecimal( final BigDecimal bigDecimal ) {
            _BigDecimal = bigDecimal;
        }

        public AtomicInteger getAtomicInteger() {
            return _AtomicInteger;
        }

        public void setAtomicInteger( final AtomicInteger atomicInteger ) {
            _AtomicInteger = atomicInteger;
        }

        public AtomicLong getAtomicLong() {
            return _AtomicLong;
        }

        public void setAtomicLong( final AtomicLong atomicLong ) {
            _AtomicLong = atomicLong;
        }

        public MutableInt getMutableInt() {
            return _MutableInt;
        }

        public void setMutableInt( final MutableInt mutableInt ) {
            _MutableInt = mutableInt;
        }

        public Integer[] getIntegerArray() {
            return _IntegerArray;
        }

        public void setIntegerArray( final Integer[] integerArray ) {
            _IntegerArray = integerArray;
        }

        public Date getDate() {
            return _Date;
        }

        public void setDate( final Date date ) {
            _Date = date;
        }

        public Calendar getCalendar() {
            return _Calendar;
        }

        public void setCalendar( final Calendar calendar ) {
            _Calendar = calendar;
        }

        public List<String> getArrayList() {
            return _ArrayList;
        }

        public void setArrayList( final List<String> arrayList ) {
            _ArrayList = arrayList;
        }

        public int[] getIntArray() {
            return _intArray;
        }

        public void setIntArray( final int[] intArray ) {
            _intArray = intArray;
        }

        public long[] getLongArray() {
            return _longArray;
        }

        public void setLongArray( final long[] longArray ) {
            _longArray = longArray;
        }

        public short[] getShortArray() {
            return _shortArray;
        }

        public void setShortArray( final short[] shortArray ) {
            _shortArray = shortArray;
        }

        public float[] getFloatArray() {
            return _floatArray;
        }

        public void setFloatArray( final float[] floatArray ) {
            _floatArray = floatArray;
        }

        public double[] getDoubleArray() {
            return _doubleArray;
        }

        public void setDoubleArray( final double[] doubleArray ) {
            _doubleArray = doubleArray;
        }

        public byte[] getByteArray() {
            return _byteArray;
        }

        public void setByteArray( final byte[] byteArray ) {
            _byteArray = byteArray;
        }

        public char[] getCharArray() {
            return _charArray;
        }

        public void setCharArray( final char[] charArray ) {
            _charArray = charArray;
        }

        public String[] getStringArray() {
            return _StringArray;
        }

        public void setStringArray( final String[] stringArray ) {
            _StringArray = stringArray;
        }

        public Person[] getPersonArray() {
            return _PersonArray;
        }

        public void setPersonArray( final Person[] personArray ) {
            _PersonArray = personArray;
        }

        public Set<String> getHashSet() {
            return _HashSet;
        }

        public Map<String, Integer> getHashMap() {
            return _HashMap;
        }

    }

    static class Holder<T> {
        T item;

        public Holder( final T item ) {
            this.item = item;
        }
    }

    static class HolderList<T> {
        List<Holder<T>> holders;

        public HolderList( final List<Holder<T>> holders ) {
            this.holders = holders;
        }
    }

    static class CounterHolder {
        AtomicInteger item;

        public CounterHolder( final AtomicInteger item ) {
            this.item = item;
        }
    }

    static class CounterHolderArray {
        CounterHolder[] holders;

        public CounterHolderArray( final CounterHolder... holders ) {
            this.holders = holders;
        }
    }

    static class HolderArray<T> {
        Holder<T>[] holders;

        public HolderArray( final Holder<T>... holders ) {
            this.holders = holders;
        }
    }
    
    public static class HashMapWithIntConstructorOnly extends HashMap<Object, Object> {
        
        private static final long serialVersionUID = 1L;

        @SuppressWarnings( "unused" )
        private HashMapWithIntConstructorOnly() {
        }

        public HashMapWithIntConstructorOnly( int size ) {
            super( size );
        }
        
    }

}

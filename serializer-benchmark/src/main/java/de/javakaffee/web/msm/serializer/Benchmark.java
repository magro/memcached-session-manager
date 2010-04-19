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
package de.javakaffee.web.msm.serializer;

import java.util.Calendar;

import org.apache.catalina.core.StandardContext;
import org.apache.catalina.loader.WebappLoader;

import de.javakaffee.web.msm.JavaSerializationTranscoder;
import de.javakaffee.web.msm.MemcachedBackupSession;
import de.javakaffee.web.msm.MemcachedBackupSessionManager;
import de.javakaffee.web.msm.SessionAttributesTranscoder;
import de.javakaffee.web.msm.TranscoderService;
import de.javakaffee.web.msm.serializer.TestClasses.Address;
import de.javakaffee.web.msm.serializer.TestClasses.Component;
import de.javakaffee.web.msm.serializer.TestClasses.Person;
import de.javakaffee.web.msm.serializer.TestClasses.Person.Gender;
import de.javakaffee.web.msm.serializer.javolution.JavolutionTranscoder;
import de.javakaffee.web.msm.serializer.kryo.KryoTranscoder;

/**
 * A simple benchmark for existing serialization strategies.
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class Benchmark {
    
    /*
     * 50000:
     * -- JavaSerializationTranscoder --
Serializing 1000 sessions took 156863 msec.
serialized size is 59016 bytes.
-- JavolutionTranscoder --
Serializing 1000 sessions took 251870 msec.
serialized size is 138374 bytes.
-- KryoTranscoder --
Serializing 1000 sessions took 154816 msec.
serialized size is 70122 bytes.

     */
    
    public static void main( String[] args ) throws InterruptedException {
        
        //Thread.sleep( 1000 );
        
        MemcachedBackupSessionManager manager = createManager();
        
        benchmark( manager, new JavaSerializationTranscoder() );
        
        recover();
        
        benchmark( manager, new JavolutionTranscoder( Thread.currentThread().getContextClassLoader(), false ) );

        recover();
        
        benchmark( manager, new KryoTranscoder() );
        
        Thread.sleep( Integer.MAX_VALUE );
    }

    private static void recover() throws InterruptedException {
        Thread.sleep( 200 );
        System.gc();
        Thread.sleep( 200 );
    }

    private static void benchmark( MemcachedBackupSessionManager manager, SessionAttributesTranscoder transcoder ) {
        TranscoderService transcoderService = new TranscoderService( transcoder );
        
        final MemcachedBackupSession session = createSession( manager, "123456789abcdefghijk987654321" );

        long start = System.currentTimeMillis();
        for( int i = 0; i < 1000; i++ ) {
            transcoderService.serialize( createSession( manager, "123456789abcdefghijk987654321" ) );
        }
        System.out.println( "-- " + transcoder.getClass().getSimpleName() + " --");
        System.out.println( "Serializing " + 1000 + " sessions took " + (System.currentTimeMillis() - start) + " msec." +
        		"\nserialized size is " + transcoderService.serialize( session ).length + " bytes." );
    }

    private static MemcachedBackupSession createSession( MemcachedBackupSessionManager manager, String id ) {
        final MemcachedBackupSession session = manager.createEmptySession();
        session.setId( id );
        session.setValid( true );

        session.setAttribute( "stringbuffer", new StringBuffer( "<string\n&buffer/>" ) );
        session.setAttribute( "stringbuilder", new StringBuilder( "<string\n&buffer/>" ) );
        
        session.setAttribute( "persons", createPersons( 100 ) );
        session.setAttribute( "mycontainer", new TestClasses.MyContainer() );
        
        session.setAttribute( "component", new Component( "root" )
            .addChild( new Component( "child1" )
                .addChild( new Component( "child1-1" ) )
                .addChild( new Component( "child1-2" ) ) )
            .addChild( new Component( "child2" )
                .addChild( new Component( "child2-1" ) )
                .addChild( new Component( "child2-2" ) ) ) );
        return session;
    }

    private static Person[] createPersons( final int countPersons ) {
        Person[] persons = new Person[countPersons];
        for( int i = 0; i < countPersons; i++ ) {
            final Calendar dateOfBirth = Calendar.getInstance();
            dateOfBirth.set( Calendar.YEAR, dateOfBirth.get( Calendar.YEAR ) - 42 );
            Person person = TestClasses.createPerson( "Firstname" + i + " Lastname" + i,
                    i % 2 == 0 ? Gender.FEMALE : Gender.MALE,
                        dateOfBirth,
                        "email" + i + "-1@example.org", "email" + i + "-2@example.org", "email" + i + "-3@example.org" );
            person.addAddress( new Address( "route66", "123456", "sincity", "sincountry" ) );
            
            if ( i > 0 ) {
                person.addFriend( persons[i - 1] );
            }
            
            persons[i] = person;
        }
        return persons;
    }
    
    private static MemcachedBackupSessionManager createManager() {
        MemcachedBackupSessionManager manager = new MemcachedBackupSessionManager();

        final StandardContext container = new StandardContext();
        manager.setContainer( container );

        final WebappLoader webappLoader = new WebappLoader() {
            /**
             * {@inheritDoc}
             */
            @Override
            public ClassLoader getClassLoader() {
                return Thread.currentThread().getContextClassLoader();
            }
        };
        manager.getContainer().setLoader( webappLoader );
        
        return manager;
    }

}

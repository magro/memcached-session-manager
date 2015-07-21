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
import java.util.concurrent.atomic.AtomicInteger;

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
    
    public static void main( final String[] args ) throws InterruptedException {
        
        //Thread.sleep( 1000 );
        
        final MemcachedBackupSessionManager manager = createManager();
        
        // some warmup
        // final int warmupCycles = 100000;
        final int warmupCycles = 100;
        warmup( manager, new KryoTranscoder(), warmupCycles, 100, 3 );
        warmup( manager, new JavaSerializationTranscoder(), warmupCycles, 100, 3 );
        warmup( manager, new JavolutionTranscoder( Thread.currentThread().getContextClassLoader(), false ), warmupCycles, 100, 3 );
        recover();

        benchmark( manager, 10, 500, 4 /* 4^4 = 256 */ );
        benchmark( manager, 10, 100, 3 /* 3^3 = 27 */ );
        benchmark( manager, 10, 10, 2 /* 2^2 = 4 */ );
        
        // Thread.sleep( Integer.MAX_VALUE );
    }

    private static void benchmark( final MemcachedBackupSessionManager manager, final int rounds, final int countPersons,
            final int nodesPerEdge ) throws InterruptedException {

        final Stats kryoSerStats = new Stats();
        final Stats kryoDeSerStats = new Stats();
        benchmark( manager, new KryoTranscoder(), kryoSerStats, kryoDeSerStats, rounds, countPersons, nodesPerEdge );

        final Stats javaSerStats = new Stats();
        final Stats javaDeSerStats = new Stats();
        benchmark( manager, new JavaSerializationTranscoder(), javaSerStats, javaDeSerStats, rounds, countPersons, nodesPerEdge );
        
        recover();

        final Stats javolutionSerStats = new Stats();
        final Stats javolutionDeSerStats = new Stats();
        benchmark( manager, new JavolutionTranscoder( Thread.currentThread().getContextClassLoader(), false ), javolutionSerStats,
                javolutionDeSerStats, rounds, countPersons, nodesPerEdge );

        recover();
        
        System.out.println( "Serialization,Size,Ser-Min,Ser-Avg,Ser-Max,Deser-Min,Deser-Avg,Deser-Max");
        System.out.println( toCSV( "Java", javaSerStats, javaDeSerStats ) );
        System.out.println( toCSV( "Javolution", javolutionSerStats, javolutionDeSerStats ) );
        System.out.println( toCSV( "Kryo", kryoSerStats, kryoDeSerStats ) );
    }

    private static String toCSV( final String name, final Stats serStats, final Stats deSerStats ) {
        return  name + "," + serStats.size +","+ minAvgMax( serStats ) + "," + minAvgMax( deSerStats );
    }

    private static String minAvgMax( final Stats stats ) {
        return stats.min +","+ stats.avg +","+ stats.max;
    }

    private static void recover() throws InterruptedException {
        Thread.sleep( 200 );
        System.gc();
        Thread.sleep( 200 );
    }

    private static void benchmark( final MemcachedBackupSessionManager manager, final SessionAttributesTranscoder transcoder,
            final Stats serializationStats,
            final Stats deserializationStats,
            final int rounds, final int countPersons, final int nodesPerEdge ) throws InterruptedException {

        System.out.println( "Running benchmark for " + transcoder.getClass().getSimpleName() + "..." +
        		" (rounds: "+ rounds +", persons: "+ countPersons +", nodes: "+ ((int)Math.pow( nodesPerEdge, nodesPerEdge ) + nodesPerEdge + 1 ) +")" );
        
        final TranscoderService transcoderService = new TranscoderService( transcoder );
        
        final MemcachedBackupSession session = createSession( manager, "123456789abcdefghijk987654321", countPersons, nodesPerEdge );
        final byte[] data = transcoderService.serialize( session );
        final int size = data.length;
        
        for( int r = 0; r < rounds; r++ ) {
            final long start = System.currentTimeMillis();
            for( int i = 0; i < 500; i++ ) {
                transcoderService.serialize( session );
            }
            serializationStats.registerSince( start );
            serializationStats.setSize( size );
        }
        
        System.gc();
        Thread.sleep( 100 );
        
        // deserialization
        for( int r = 0; r < rounds; r++ ) {
            final long start = System.currentTimeMillis();
            for( int i = 0; i < 500; i++ ) {
                transcoderService.deserialize( data, manager );
            }
            deserializationStats.registerSince( start );
            deserializationStats.setSize( size );
        }
        
    }

    private static void warmup( final MemcachedBackupSessionManager manager, final SessionAttributesTranscoder transcoder,
            final int loops, final int countPersons, final int nodesPerEdge )
        throws InterruptedException {
        
        final TranscoderService transcoderService = new TranscoderService( transcoder );
        final MemcachedBackupSession session = createSession( manager, "123456789abcdefghijk987654321", countPersons, nodesPerEdge );
        
        System.out.print("Performing warmup for serialization using "+ transcoder.getClass().getSimpleName() +"...");
        final long serWarmupStart = System.currentTimeMillis();
        for( int i = 0; i < loops; i++ ) transcoderService.serialize( session );
        System.out.println(" (" + (System.currentTimeMillis() - serWarmupStart) + " ms)");
        
        System.out.print("Performing warmup for deserialization...");
        final byte[] data = transcoderService.serialize( session );
        final long deserWarmupStart = System.currentTimeMillis();
        for( int i = 0; i < loops; i++ ) transcoderService.deserialize( data, manager );
        System.out.println(" (" + (System.currentTimeMillis() - deserWarmupStart) + " ms)");

    }

    private static MemcachedBackupSession createSession( final MemcachedBackupSessionManager manager, final String id,
            final int countPersons, final int countNodesPerEdge ) {
        final MemcachedBackupSession session = manager.createEmptySession();
        session.setId( id );
        session.setValid( true );

        session.setAttribute( "stringbuffer", new StringBuffer( "<string\n&buffer/>" ) );
        session.setAttribute( "stringbuilder", new StringBuilder( "<string\n&buffer/>" ) );
        
        session.setAttribute( "persons", createPersons( countPersons ) );
        session.setAttribute( "mycontainer", new TestClasses.MyContainer() );
        
        session.setAttribute( "component", createComponents( countNodesPerEdge ) );
        
        return session;
    }

    private static Component createComponents( final int countNodesPerEdge ) {
        final Component root = new Component( "root" );
        for ( int i = 0; i < countNodesPerEdge; i++ ) {
            final Component node = new Component( "child" + i );
            addChildren( node, countNodesPerEdge );
            root.addChild( node );
        }
        return root;
    }

    private static void addChildren( final Component node, final int count ) {
        for ( int i = 0; i < count; i++ ) {
            node.addChild( new Component( node.getName() + "-" + i ) );
        }
    }

    private static Person[] createPersons( final int countPersons ) {
        final Person[] persons = new Person[countPersons];
        for( int i = 0; i < countPersons; i++ ) {
            final Calendar dateOfBirth = Calendar.getInstance();
            dateOfBirth.set( Calendar.YEAR, dateOfBirth.get( Calendar.YEAR ) - 42 );
            final Person person = TestClasses.createPerson( "Firstname" + i + " Lastname" + i,
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
        final MemcachedBackupSessionManager manager = new MemcachedBackupSessionManager();

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
    
    static class Stats {

        long min;
        long max;
        double avg;
        int size;
        
        private boolean _first = true;
        private final AtomicInteger _count = new AtomicInteger();

        /**
         * A utility method that calculates the difference of the time
         * between the given <code>startInMillis</code> and {@link System#currentTimeMillis()}
         * and registers the difference via {@link #register(long)}.
         * @param startInMillis the time in millis that shall be subtracted from {@link System#currentTimeMillis()}.
         */
        public void registerSince( final long startInMillis ) {
            register( System.currentTimeMillis() - startInMillis );
        }

        public void setSize( final int size ) {
            this.size = size;
        }

        /**
         * Register the given value.
         * @param value the value to register.
         */
        public void register( final long value ) {
            if ( value < min || _first ) {
                min = value;
            }
            if ( value > max || _first ) {
                max = value;
            }
            avg = ( avg * _count.get() + value ) / _count.incrementAndGet();
            _first = false;
        }

        /**
         * Returns a string array with labels and values of count, min, avg and max.
         * @return a String array.
         */
        public String[] getInfo() {
            return new String[] {
                    "Count = " + _count.get(),
                    "Min = "+ min,
                    "Avg = "+ avg,
                    "Max = "+ max
            };
        }
        
    }

}

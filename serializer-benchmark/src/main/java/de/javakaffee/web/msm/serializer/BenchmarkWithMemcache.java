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
import de.javakaffee.web.msm.serializer.javalgzip.JavaGzipTranscoder;
import de.javakaffee.web.msm.serializer.javalz4.JavaLZ4Transcoder;
import de.javakaffee.web.msm.serializer.kryo.KryoTranscoder;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import net.spy.memcached.MemcachedClient;

/**
 * A simple benchmark for existing serialization strategies.
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class BenchmarkWithMemcache {

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
    public static void main(final String[] args) throws InterruptedException, IOException {

        //Thread.sleep( 1000 );
        final MemcachedBackupSessionManager manager = createManager();

        // some warmup
        // final int warmupCycles = 100000;
        final int warmupCycles = 100;
        warmup(manager, new KryoTranscoder(), warmupCycles, 100, 3);
        warmup(manager, new JavaSerializationTranscoder(), warmupCycles, 100, 3);
        warmup(manager, new JavaLZ4Transcoder(null, true, 1, 0), warmupCycles, 100, 3);
        warmup(manager, new JavaGzipTranscoder(null, true, 1, 0), warmupCycles, 100, 3);

        recover();

        benchmark(manager, 10, 500, 4 /* 4^4 = 256 */);
        benchmark(manager, 10, 100, 3 /* 3^3 = 27 */);
        benchmark(manager, 10, 10, 2 /* 2^2 = 4 */);

        // Thread.sleep( Integer.MAX_VALUE );
    }

    private static void benchmark(final MemcachedBackupSessionManager manager, final int rounds, final int countPersons,
            final int nodesPerEdge) throws InterruptedException, IOException {

        FullStats benchmarkKryo = benchmark(manager, new KryoTranscoder(), rounds, countPersons, nodesPerEdge);

        recover();

        FullStats benchmarkJavaLZ4 = benchmark(manager, new JavaLZ4Transcoder(null, true, 1, 0), rounds, countPersons, nodesPerEdge);
        recover();

        FullStats benchmarkJavaGzip = benchmark(manager, new JavaGzipTranscoder(null, true, 1, 0), rounds, countPersons, nodesPerEdge);

        recover();

        FullStats benchmarkJava = benchmark(manager, new JavaSerializationTranscoder(), rounds, countPersons, nodesPerEdge);

        System.out.println("Serialization,Size,Total-Min,Total-Avg,Total-Max,Ser-Min,Ser-Avg,Ser-Max,Deser-Min,Deser-Avg,Deser-Max,Write-Min,Write-Avg,Write-Max,Read-Min,Read-Avg,Read-Max");
        System.out.println(toCSV("Kryo", benchmarkKryo));
        System.out.println(toCSV("Java", benchmarkJava));
        System.out.println(toCSV("JavaLZ4", benchmarkJavaLZ4));
        System.out.println(toCSV("JavaGzip", benchmarkJavaGzip));

    }

    private static String toCSV(final String name, FullStats fullstats) {
        return name + "," + fullstats.size + ","
                + minAvgMax(fullstats.totalTimeStats) + ","
                + minAvgMax(fullstats.serializationStats) + ","
                + minAvgMax(fullstats.deserializationStats) + ","
                + minAvgMax(fullstats.writeMemcacheStats) + ","
                + minAvgMax(fullstats.readMemcacheStats);
    }

    private static String minAvgMax(final NanoStats stats) {
        return stats.min + "," + stats.getAvg2() + "," + stats.max;
    }

    private static void recover() throws InterruptedException {
        Thread.sleep(100);
        System.gc();
        Thread.sleep(1000);
    }

    private static FullStats benchmark(final MemcachedBackupSessionManager manager, final SessionAttributesTranscoder transcoder,
            final int rounds, final int countPersons, final int nodesPerEdge) throws InterruptedException, IOException {

        System.out.println("Running benchmark for " + transcoder.getClass().getSimpleName() + "..."
                + " (rounds: " + rounds + ", persons: " + countPersons + ", nodes: " + ((int) Math.pow(nodesPerEdge, nodesPerEdge) + nodesPerEdge + 1) + ")");
        final NanoStats serializationStats = new NanoStats();
        final NanoStats deserializationStats = new NanoStats();
        final NanoStats writeMemcacheStats = new NanoStats();
        final NanoStats readMemcacheStats = new NanoStats();
        final NanoStats totalTimeStats = new NanoStats();

        final TranscoderService transcoderService = new TranscoderService(transcoder);

        final MemcachedBackupSession session = createSession(manager, "123456789abcdefghijk987654321", countPersons, nodesPerEdge);
        final byte[] data = transcoderService.serialize(session);
        final int size = data.length;
        MemcachedClient memcachedClient = new MemcachedClient(new InetSocketAddress("127.0.0.1", 11211));
        for (int r = 0; r < rounds; r++) {

            for (int i = 0; i < 500; i++) {
                //TODO Add a timming or memcache server to take in account operations that are afected by the size of the session
                long start = System.nanoTime();
                byte[] sessionData = transcoderService.serialize(session);
                serializationStats.registerSince(start);

                long startMemcachedSet = System.nanoTime();
                memcachedClient.set("key" + i, 600, sessionData);
                writeMemcacheStats.registerSince(startMemcachedSet);

                long startMemcachedGet = System.nanoTime();
                byte[] sessionDataRetrived = (byte[]) memcachedClient.get("key" + i);
                readMemcacheStats.registerSince(startMemcachedGet);

                long startDeserialize = System.nanoTime();
                transcoderService.deserialize(sessionDataRetrived, manager);
                deserializationStats.registerSince(startDeserialize);
                totalTimeStats.registerSince(start);
            }
        }
        memcachedClient.shutdown(10, TimeUnit.SECONDS);
        return new FullStats(size, serializationStats, deserializationStats, writeMemcacheStats, readMemcacheStats, totalTimeStats);
    }

    private static void warmup(final MemcachedBackupSessionManager manager, final SessionAttributesTranscoder transcoder,
            final int loops, final int countPersons, final int nodesPerEdge)
            throws InterruptedException {

        final TranscoderService transcoderService = new TranscoderService(transcoder);
        final MemcachedBackupSession session = createSession(manager, "123456789abcdefghijk987654321", countPersons, nodesPerEdge);

        System.out.print("Performing warmup for serialization using " + transcoder.getClass().getSimpleName() + "...");
        final long serWarmupStart = System.currentTimeMillis();
        for (int i = 0; i < loops; i++) {
            transcoderService.serialize(session);
        }
        System.out.println(" (" + (System.currentTimeMillis() - serWarmupStart) + " ms)");

        System.out.print("Performing warmup for deserialization...");
        final byte[] data = transcoderService.serialize(session);
        final long deserWarmupStart = System.currentTimeMillis();
        for (int i = 0; i < loops; i++) {
            transcoderService.deserialize(data, manager);
        }
        System.out.println(" (" + (System.currentTimeMillis() - deserWarmupStart) + " ms)");

    }

    private static MemcachedBackupSession createSession(final MemcachedBackupSessionManager manager, final String id,
            final int countPersons, final int countNodesPerEdge) {
        final MemcachedBackupSession session = manager.createEmptySession();
        session.setId(id);
        session.setValid(true);

        session.setAttribute("stringbuffer", new StringBuffer("<string\n&buffer/>"));
        session.setAttribute("stringbuilder", new StringBuilder("<string\n&buffer/>"));

        session.setAttribute("persons", createPersons(countPersons));
        session.setAttribute("mycontainer", new TestClasses.MyContainer());

        session.setAttribute("component", createComponents(countNodesPerEdge));

        return session;
    }

    private static Component createComponents(final int countNodesPerEdge) {
        final Component root = new Component("root");
        for (int i = 0; i < countNodesPerEdge; i++) {
            final Component node = new Component("child" + i);
            addChildren(node, countNodesPerEdge);
            root.addChild(node);
        }
        return root;
    }

    private static void addChildren(final Component node, final int count) {
        for (int i = 0; i < count; i++) {
            node.addChild(new Component(node.getName() + "-" + i));
        }
    }

    private static Person[] createPersons(final int countPersons) {
        final Person[] persons = new Person[countPersons];
        for (int i = 0; i < countPersons; i++) {
            final Calendar dateOfBirth = Calendar.getInstance();
            dateOfBirth.set(Calendar.YEAR, dateOfBirth.get(Calendar.YEAR) - 42);
            final Person person = TestClasses.createPerson("Firstname" + i + " Lastname" + i,
                    i % 2 == 0 ? Gender.FEMALE : Gender.MALE,
                    dateOfBirth,
                    "email" + i + "-1@example.org", "email" + i + "-2@example.org", "email" + i + "-3@example.org");
            person.addAddress(new Address("route66", "123456", "sincity", "sincountry"));

            if (i > 0) {
                person.addFriend(persons[i - 1]);
            }

            persons[i] = person;
        }
        return persons;
    }

    private static MemcachedBackupSessionManager createManager() {
        final MemcachedBackupSessionManager manager = new MemcachedBackupSessionManager();

        final StandardContext container = new StandardContext();
        manager.setContainer(container);

        final WebappLoader webappLoader = new WebappLoader() {
            /**
             * {@inheritDoc}
             */
            @Override
            public ClassLoader getClassLoader() {
                return Thread.currentThread().getContextClassLoader();
            }
        };
        manager.getContainer().setLoader(webappLoader);

        return manager;
    }

    static class NanoStats {

        long min;
        long max;
        long total = 0;
        int samples;

        private boolean _first = true;
        private final AtomicInteger _count = new AtomicInteger();

        /**
         * A utility method that calculates the difference of the time between
         * the given <code>startInNamo</code> and {@link System#nanoTime()} and
         * registers the difference via {@link #register(long)}.
         *
         * @param startInMillis the time in millis that shall be subtracted from
         * {@link System#currentTimeMillis()}.
         */
        public void registerSince(final long startInMillis) {
            register(System.nanoTime() - startInMillis);
        }

        /**
         * Register the given value.
         *
         * @param value the value to register.
         */
        public void register(final long value) {
            if (value < min || _first) {
                min = value;
            }
            if (value > max || _first) {
                max = value;
            }
            total += value;
            samples++;
            _first = false;
        }

        public double getAvg() {
            return (total) / (samples);
        }

        /**
         * Avg discarding the best and the worst time
         * @return avg discarding the best and the worst time
         */
        public double getAvg2() {
            return (total - min - max) / (samples - 2);
        }

        /**
         * Returns a string array with labels and values of count, min, avg and
         * max.
         *
         * @return a String array.
         */
        public String[] getInfo() {
            return new String[]{
                "Count = " + _count.get(),
                "Min = " + min,
                "Avg = " + getAvg(),
                "Avg2 = " + getAvg2(),
                "Max = " + max
            };
        }

    }

    static class FullStats {

        final NanoStats serializationStats;
        final NanoStats deserializationStats;
        final NanoStats writeMemcacheStats;
        final NanoStats readMemcacheStats;
        final NanoStats totalTimeStats;
        final int size;

        public FullStats(int size, NanoStats serializationStats, NanoStats deserializationStats, NanoStats writeMemcacheStats, NanoStats readMemcacheStats, NanoStats totalTimeStats) {
            this.serializationStats = serializationStats;
            this.deserializationStats = deserializationStats;
            this.writeMemcacheStats = writeMemcacheStats;
            this.readMemcacheStats = readMemcacheStats;
            this.totalTimeStats = totalTimeStats;
            this.size = size;
        }

    }

}

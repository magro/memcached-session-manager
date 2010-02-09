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

import javolution.xml.XMLBinding;
import javolution.xml.XMLObjectReader;
import javolution.xml.XMLObjectWriter;
import javolution.xml.XMLReferenceResolver;
import javolution.xml.stream.XMLStreamException;

import org.joda.time.Chronology;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.chrono.BuddhistChronology;
import org.joda.time.chrono.CopticChronology;
import org.joda.time.chrono.EthiopicChronology;
import org.joda.time.chrono.GJChronology;
import org.joda.time.chrono.GregorianChronology;
import org.joda.time.chrono.IslamicChronology;
import org.joda.time.chrono.JulianChronology;
import org.testng.Assert;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Test for {@link JodaDateTimeFormat}.
 * 
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class JodaDateTimeFormatTest {

    private ReflectionBinding _binding;

    @BeforeTest
    protected void beforeTest() {
        _binding = new ReflectionBinding( getClass().getClassLoader(), false, new JodaDateTimeFormat() );
    }

    @DataProvider( name = "timeZoneProvider" )
    protected Object[][] createTimeZoneProviderData() {
        return new Object[][] {
                { null },
                { DateTimeZone.UTC },
                { DateTimeZone.forOffsetHours( 1 ) },
                { DateTimeZone.forID( "Europe/Berlin" ) }
                };
    }

    @Test( enabled = true, dataProvider = "timeZoneProvider" )
    public void testWriteDateTimeWithTimeZone( final DateTimeZone timeZone ) throws XMLStreamException {
        final DateTime dateTime = new DateTime( 0, timeZone );
        final byte[] serialized = serialize( dateTime, _binding );
        final DateTime deserialized = deserialize( serialized, _binding );
        Assert.assertEquals( deserialized, dateTime );
    }

    @DataProvider( name = "chronologyProvider" )
    protected Object[][] createChronologyProviderData() {
        return new Object[][] {
                { null },
                { BuddhistChronology.getInstance() },
                { BuddhistChronology.getInstance( DateTimeZone.forOffsetHoursMinutes( 5, 30 ) ) },
                { CopticChronology.getInstance() },
                { CopticChronology.getInstance( DateTimeZone.forOffsetHours( 1 ) ) },
                { EthiopicChronology.getInstance() },
                { EthiopicChronology.getInstance( DateTimeZone.forOffsetHours( 1 ) ) },
                { GregorianChronology.getInstance() },
                { GregorianChronology.getInstance( DateTimeZone.forOffsetHours( 1 ) ) },
                { JulianChronology.getInstance() },
                { JulianChronology.getInstance( DateTimeZone.forOffsetHours( 1 ) ) },
                { IslamicChronology.getInstance() },
                { IslamicChronology.getInstance( DateTimeZone.forOffsetHours( 1 ) ) },
                { BuddhistChronology.getInstance() },
                { BuddhistChronology.getInstance( DateTimeZone.forOffsetHours( 1 ) ) },
                { GJChronology.getInstance() },
                { GJChronology.getInstance( DateTimeZone.forOffsetHours( 1 ) ) }
                };
    }

    @Test( enabled = true, dataProvider = "chronologyProvider" )
    public void testWriteDateTimeWithChronology( final Chronology chronology ) throws XMLStreamException {
        final DateTime dateTime = new DateTime( 0, chronology );
        final byte[] serialized = serialize( dateTime, _binding );
        final DateTime deserialized = deserialize( serialized, _binding );
        Assert.assertEquals( deserialized, dateTime );
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

//    @BeforeTest
//    protected void beforeTest() {
//        //_binding = new ReflectionBinding( getClass().getClassLoader(), false, new JodaDateTimeFormat() );
//        
//        _manager = new MemcachedBackupSessionManager();
//
//        final StandardContext container = new StandardContext();
//        _manager.setContainer( container );
//
//        final WebappLoader webappLoader = mock( WebappLoader.class );
//        //webappLoaderControl.expects( once() ).method( "setContainer" ).withAnyArguments();
//        when( webappLoader.getClassLoader() ).thenReturn( Thread.currentThread().getContextClassLoader() );
//        Assert.assertNotNull( webappLoader.getClassLoader(), "Webapp Classloader is null." );
//        _manager.getContainer().setLoader( webappLoader );
//
//        Assert.assertNotNull( _manager.getContainer().getLoader().getClassLoader(), "Classloader is null." );
//
//        _transcoder = new JavolutionTranscoder( _manager, true, new JodaDateTimeFormat() );
//
//    }
//
//    @Test( enabled = true )
//    public void testJodaTime() throws Exception {
//        final MemcachedBackupSession session = _manager.createEmptySession();
//        session.setValid( true );
//
//        for( int i = 0; i < 100; i++ ) {
//            session.setAttribute( "jodaTime" + i, new DateTime() );
//        }
//
//        _transcoder.serialize( session );
//        _transcoder.serialize( session );
//
//        final long start = System.currentTimeMillis();
//        for( int i = 0; i < 1000; i++ ) {
//            _transcoder.serialize( session );
//        }
//        final long time = System.currentTimeMillis() - start;
//        System.out.println( "It took " + time + " msec" +
//                        " to serialize " + _transcoder.serialize( session ).length + " bytes");
//        System.out.println( "1000x   0xjoda :  100       msec,   332         bytes");
//        System.out.println( "1000x   1xjoda :  900 (180) msec, 10801   (467) bytes");
//        System.out.println( "1000x   2xjoda :  900 (200) msec, 10997   (590) bytes");
//        System.out.println( "1000x   5xjoda :  900 (340) msec, 11585   (961) bytes");
//        System.out.println( "1000x  10xjoda :  960 (520) msec, 12566  (1586) bytes");
//        System.out.println( "1000x 100xjoda : 1391 (860) msec, 30417 (13028) bytes");
//
//        System.out.println( new String(_transcoder.serialize( session )));
//
//    }

}

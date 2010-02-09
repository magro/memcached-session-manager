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

import javolution.text.CharArray;
import javolution.xml.XMLFormat;
import javolution.xml.stream.XMLStreamException;

import org.joda.time.Chronology;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.chrono.BuddhistChronology;
import org.joda.time.chrono.CopticChronology;
import org.joda.time.chrono.EthiopicChronology;
import org.joda.time.chrono.GJChronology;
import org.joda.time.chrono.GregorianChronology;
import org.joda.time.chrono.ISOChronology;
import org.joda.time.chrono.IslamicChronology;
import org.joda.time.chrono.JulianChronology;

/**
 * A format for joda {@link DateTime}, that stores the millis, chronology and
 * time zone as separate attributes. If the chronlogy is {@link ISOChronology},
 * the attribute is omitted, thus {@link ISOChronology} is seen as default. If
 * the time zone is the default time zone ({@link DateTimeZone#getDefault()}),
 * the time zone attribute is omitted. This requires different machines to
 * have the same time zone settings.
 * <p>
 * The following chronologies are supported:
 * <ul>
 * <li>{@link ISOChronology}</li>
 * <li>{@link CopticChronology}</li>
 * <li>{@link EthiopicChronology}</li>
 * <li>{@link GregorianChronology}</li>
 * <li>{@link JulianChronology}</li>
 * <li>{@link IslamicChronology}</li>
 * <li>{@link BuddhistChronology}</li>
 * <li>{@link GJChronology}</li>
 * </ul>
 * </p>
 * 
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class JodaDateTimeFormat extends XMLFormat<DateTime> {

    static final String MILLIS = "millis";
    static final String DATE_TIME = "dt";
    static final String CHRONOLOGY = "ch";
    static final String TIME_ZONE = "tz";

    /**
     * @param cls
     */
    public JodaDateTimeFormat() {
        super( DateTime.class );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public DateTime newInstance( final Class<DateTime> cls, final javolution.xml.XMLFormat.InputElement input )
        throws XMLStreamException {
        final long millis = input.getAttribute( MILLIS ).toLong();
        final Chronology chronology = readChronology( input );
        final DateTimeZone tz = readTimeZone( input );
        return new DateTime( millis, chronology.withZone( tz ) );
    }

    private Chronology readChronology( final javolution.xml.XMLFormat.InputElement input ) throws XMLStreamException {
        final CharArray chronologyId = input.getAttribute( CHRONOLOGY );
        return IdentifiableChronology.valueOfId( chronologyId != null ? chronologyId.toString() : null );
    }

    private DateTimeZone readTimeZone( final javolution.xml.XMLFormat.InputElement input ) throws XMLStreamException {
        final CharArray tz = input.getAttribute( TIME_ZONE );
        return tz != null ? DateTimeZone.forID( tz.toString() ) : DateTimeZone.getDefault();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void read( final javolution.xml.XMLFormat.InputElement input, final DateTime obj ) throws XMLStreamException {
        // nothing to do
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void write( final DateTime obj, final javolution.xml.XMLFormat.OutputElement output ) throws XMLStreamException {
        output.setAttribute( MILLIS, obj.getMillis() );
        final String chronologyId = getChronologyId( obj.getChronology() );
        if ( chronologyId != null ) {
            output.setAttribute( CHRONOLOGY, chronologyId );
        }
        if ( obj.getZone() != null && obj.getZone() != DateTimeZone.getDefault() ) {
            output.setAttribute( TIME_ZONE, obj.getZone().getID() );
        }
    }

    private String getChronologyId( final Chronology chronology ) {
        return IdentifiableChronology.getIdByChronology( chronology.getClass() );
    }
    
    /**
     * An enumeration that provides a String id for subclasses of {@link Chronology}.
     * For {@link ISOChronology}, <code>null</code> is used as id, as {@link ISOChronology}
     * is used as default and the id does not have to be serialized.
     * 
     * @author Martin Grotzke (martin.grotzke@freiheit.com) (initial creation)
     */
    static enum IdentifiableChronology {
        
        ISO( null, ISOChronology.getInstance() ),
        COPTIC( "COPTIC", CopticChronology.getInstance() ),
        ETHIOPIC( "ETHIOPIC", EthiopicChronology.getInstance()),
        GREGORIAN("GREGORIAN", GregorianChronology.getInstance()),
        JULIAN("JULIAN", JulianChronology.getInstance()),
        ISLAMIC("ISLAMIC",IslamicChronology.getInstance()),
        BUDDHIST( "BUDDHIST", BuddhistChronology.getInstance()),
        GJ( "GJ", GJChronology.getInstance());
        
        private final String _id;
        private final Chronology _chronology;
        
        private IdentifiableChronology( String id, Chronology chronology ) {
            _id = id;
            _chronology = chronology;
        }
        
        public String getId() {
            return _id;
        }
        
        /**
         * Determines the id for the given {@link Chronology} subclass that later
         * can be used to resolve the {@link Chronology} with {@link #valueOfId(String)}.
         * For {@link ISOChronology} class <code>null</code> is returned.
         * 
         * @param clazz a subclass of {@link Chronology}.
         * @return an id, or <code>null</code> for {@link ISOChronology}.
         * @throws IllegalArgumentException if the {@link Chronology} is not supported.
         */
        public static String getIdByChronology( Class<? extends Chronology> clazz ) throws IllegalArgumentException {
            for( final IdentifiableChronology item : values() ) {
                if ( clazz.equals( item._chronology.getClass() ) ) {
                    return item._id;
                }
            }
            throw new IllegalArgumentException( "Chronology not supported: " + clazz.getSimpleName() );
        }
        
        /**
         * Returns the chronology of the {@link IdentifiableChronology} matching the
         * provided <code>id</code>. If the provided <code>id</code> is <code>null</code>,
         * {@link ISOChronology} is returned.
         * @param id the id from {@link #getIdByChronology(Class)}.
         * @return a matching {@link Chronology} if any was found.
         * @throws IllegalArgumentException if no match was found.
         */
        public static Chronology valueOfId(String id) throws IllegalArgumentException {
            if ( id == null ) {
                return ISO._chronology;
            }
            for( final IdentifiableChronology item : values() ) {
                if ( id.equals( item._id ) ) {
                    return item._chronology;
                }
            }
            throw new IllegalArgumentException( "No chronology found for id " + id );
        }
        
    }

}

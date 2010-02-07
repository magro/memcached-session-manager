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

import javolution.xml.XMLFormat;
import javolution.xml.stream.XMLStreamException;

import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;

/**
 * A format for joda {@link DateTime}, that formats given {@link DateTime} instances
 * using a {@link DateTimeFormatter}, and parsed the formatted date time to recreate
 * a {@link DateTime} instance.
 * 
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class JodaDateTimeFormat extends XMLFormat<DateTime> {

    private static final DateTimeFormatter FORMAT = ISODateTimeFormat.basicDateTime();

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
    public DateTime newInstance( final Class<DateTime> cls, final javolution.xml.XMLFormat.InputElement input ) throws XMLStreamException {
        final String string = input.getAttribute( "datetime" ).toString();
        return FORMAT.parseDateTime( string );
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
        output.setAttribute( "datetime", obj.toString( FORMAT ) );
    }


}

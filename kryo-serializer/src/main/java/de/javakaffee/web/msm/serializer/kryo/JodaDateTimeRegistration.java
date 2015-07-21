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
package de.javakaffee.web.msm.serializer.kryo;

import de.javakaffee.kryoserializers.jodatime.JodaIntervalSerializer;
import de.javakaffee.kryoserializers.jodatime.JodaLocalDateSerializer;
import de.javakaffee.kryoserializers.jodatime.JodaLocalDateTimeSerializer;
import org.joda.time.DateTime;

import com.esotericsoftware.kryo.Kryo;

import de.javakaffee.kryoserializers.jodatime.JodaDateTimeSerializer;
import org.joda.time.Interval;
import org.joda.time.LocalDate;
import org.joda.time.LocalDateTime;

/**
 * A {@link KryoCustomization} that registers serializers for
 * joda's {@link Interval}, {@link DateTime}, {@link LocalDateTime}, {@link LocalDate} classes.
 * 
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class JodaDateTimeRegistration implements KryoCustomization {
    
    @Override
    public void customize( final Kryo kryo ) {
        kryo.register( Interval.class, new JodaIntervalSerializer());
        kryo.register( DateTime.class, new JodaDateTimeSerializer());
        kryo.register( LocalDateTime.class, new JodaLocalDateTimeSerializer());
        kryo.register( LocalDate.class, new JodaLocalDateSerializer());
    }
    
}

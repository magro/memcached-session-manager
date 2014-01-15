/*
 * Copyright 2014 Marcus Thiesen
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

/**
 * Container for any configuration flags for the Kryo Transcoder.
 *
 * @author Marcus Thiesen (marcus.thiesen@freiheit.com) (initial creation)
 */
public class KryoTranscoderConfiguration {

    private static final KryoTranscoderConfiguration DEFAULT = new KryoTranscoderConfiguration( false, false );

    private final boolean _copyCollectionsForSerialization;
    private final boolean _useCompatibleFieldSerializer;

    private KryoTranscoderConfiguration( boolean copyCollectionsForSerialization, boolean useCompatibleFieldSerializer ) {
        _copyCollectionsForSerialization = copyCollectionsForSerialization;
        _useCompatibleFieldSerializer = useCompatibleFieldSerializer;
    }

    public boolean isCopyCollectionsForSerialization() {
        return _copyCollectionsForSerialization;
    }

    public boolean isUseCompatibleFieldSerializer() {
        return _useCompatibleFieldSerializer;
    }

    public static KryoTranscoderConfiguration getDefault() {
        return DEFAULT;
    }

    public static KryoTranscoderConfiguration create( boolean copyCollectionsForSerialization, boolean useCompatibleFieldSerializer ) {
        return new KryoTranscoderConfiguration( copyCollectionsForSerialization, useCompatibleFieldSerializer );
    }

}

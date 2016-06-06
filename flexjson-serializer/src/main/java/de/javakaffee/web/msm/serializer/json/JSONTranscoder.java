/*
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
 */
package de.javakaffee.web.msm.serializer.json;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.apache.catalina.Manager;
import org.apache.catalina.session.StandardSession;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

import de.javakaffee.web.msm.MemcachedBackupSession;
import de.javakaffee.web.msm.SessionAttributesTranscoder;
import de.javakaffee.web.msm.TranscoderDeserializationException;
import flexjson.JSONDeserializer;
import flexjson.JSONSerializer;


/**
 * A {@link net.spy.memcached.transcoders.Transcoder} that serializes catalina
 * {@link StandardSession}s in json, using <a href="http://flexjson.sourceforge.net/">FLEXJSON</a>
 *
 * @author <a href="mailto:moresandeep@gmail.com">Sandeep More</a>
 */
public class JSONTranscoder implements SessionAttributesTranscoder {

    private static final Log LOG = LogFactory.getLog(JSONTranscoder.class);

	private final JSONSerializer serializer;
	private final JSONDeserializer<ConcurrentMap<String, Object>> deserializer;

	/**
	 * Constructor
	 * @param manager
	 */
	public JSONTranscoder(final Manager manager) {
		serializer = new JSONSerializer();
		deserializer = new JSONDeserializer<ConcurrentMap<String, Object>>();
		if (LOG.isDebugEnabled()) {
		    LOG.debug("Initialized json serializer");
		}
	}

	/**
	 * Return the deserialized map
	 *
	 *  @param in bytes to deserialize
	 *  @return map of deserialized objects
	 */
	@Override
	public ConcurrentMap<String, Object> deserializeAttributes(final byte[] in) {
		final InputStreamReader inputStream = new InputStreamReader( new ByteArrayInputStream( in ) );
		if (LOG.isDebugEnabled()) {
		    LOG.debug("deserialize the stream");
		}
		try {
			return deserializer.deserializeInto(inputStream, new ConcurrentHashMap<String, Object>());
		} catch( final RuntimeException e) {
			LOG.warn("Caught Exception deserializing JSON "+e);
			throw new TranscoderDeserializationException(e);
		}
	}

	/* (non-Javadoc)
	 * @see de.javakaffee.web.msm.SessionAttributesTranscoder#serializeAttributes(de.javakaffee.web.msm.MemcachedBackupSession, java.util.Map)
	 */
	@Override
	public byte[] serializeAttributes(final MemcachedBackupSession sessions, final ConcurrentMap<String, Object> attributes) {
		if (attributes == null) {
        	throw new NullPointerException();
        }

        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
        	// This performs a deep serialization of the target instance.
            // It's serialized to a string as flexjson doesn't like writing to
            // an OutputStreamWriter: it throws the exception "Stepping back two steps is not supported".
            // See https://github.com/moresandeep/memcached-session-manager/commit/db2faaa0a846e16d65ac0b14819689c67bf92c68#commitcomment-512505
        	final String serResult = serializer.deepSerialize(attributes);
        	if (LOG.isDebugEnabled()) {
        	    LOG.debug("JSON Serialised object: " + serResult);
        	}
        	return serResult.getBytes(); // converts to bytes
        } catch (final Exception e) {
        	LOG.warn("Caught Exception deserializing JSON " + e);
        	throw new IllegalArgumentException();
        } finally {
        	close(bos);
        }
	}

	private void close(final Closeable stream) {
		if(stream != null) {
			try {
				stream.close();
			} catch( final IOException e) {
				LOG.warn("JSON Transcoder Failed to close the stream: " + e);
			}
		}
	}

}
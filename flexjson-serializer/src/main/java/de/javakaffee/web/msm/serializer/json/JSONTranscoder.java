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

import org.apache.catalina.Manager;
import de.javakaffee.web.msm.MemcachedBackupSession;
import de.javakaffee.web.msm.SessionAttributesTranscoder;
import flexjson.JSONDeserializer;
import flexjson.JSONSerializer;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;


/**
 * <p>
 * @author <a href="mailto:moresandeep@gmail.com">Sandeep More</a>
 * </p>
 * <p>
 * A {@link net.spy.memcached.transcoders.Transcoder} that serializes catalina
 * {@link StandardSession}s in json, using <a href="http://flexjson.sourceforge.net/">FLEXJSON</a>
 * </p>
 */
public class JSONTranscoder implements SessionAttributesTranscoder {
	private final Manager manager;
	private final JSONSerializer serializer;
	private final JSONDeserializer<MemcachedBackupSession> deserializer;
	private static final Log log = LogFactory.getLog(JSONTranscoder.class);
	
	
	/**
	 * Constructor
	 * @param manager
	 */
	public JSONTranscoder(final Manager manager) {
		this.manager = manager;
		serializer = new JSONSerializer();
		deserializer = new JSONDeserializer<MemcachedBackupSession>();
		if (log.isDebugEnabled()) log.debug("Initialized json serializer and deserializer");
	}
	
	/**
	 * Return the deserialized map
	 * 
	 *  @param in bytes to deserialize
	 *  @return map of deserialized objects
	 */
	@Override
	public Map<String, Object> deserializeAttributes(byte[] in) {		
		final InputStreamReader inputStream = new InputStreamReader( new ByteArrayInputStream( in ) );
		if (log.isDebugEnabled()) log.debug("deserializer the stream");
		try {			
			//@SuppressWarnings("unchecked")
			final Map<String, Object> result = new JSONDeserializer<Map<String, Object>>().deserialize(inputStream);
			return result;
		} catch( RuntimeException e) {
			log.warn("Caught Exception deserializing JSON "+e);
			throw e;
		}		
	}

	/* (non-Javadoc)
	 * @see de.javakaffee.web.msm.SessionAttributesTranscoder#serializeAttributes(de.javakaffee.web.msm.MemcachedBackupSession, java.util.Map)
	 */
	@Override
	public byte[] serializeAttributes(MemcachedBackupSession sessions, Map<String, Object> attributes) {
		
		return doSerialize( attributes );
	}
	
	
	private byte[] doSerialize(final Object object) {
		if (object == null){
			throw new NullPointerException();
		}
		
		final ByteArrayOutputStream bos = new ByteArrayOutputStream();
	
		try {
			// This performs a deep serialization of the target instance.		
			String serResult = serializer.deepSerialize(object);			
			if (log.isDebugEnabled()) log.debug("JSON Serialised object: "+serResult);
			byte buffer[] = serResult.getBytes(); // converts to bytes
			return buffer;
		} catch (Exception e) {
			log.warn("Caught Exception deserializing JSON "+e);
			throw new IllegalArgumentException();
		} finally {
			close(bos);
		}
		
	}
	
	protected byte[] serialize(final Object o) {
		return doSerialize(o);
	}

	/* (non-Javadoc)
	 * @see de.javakaffee.web.msm.SessionTranscoder#deserialize(byte[])
	 */	
	protected MemcachedBackupSession deserialize(byte[] in) {
		final ByteArrayInputStream bis = new ByteArrayInputStream( in );
		try {
			final MemcachedBackupSession session = (MemcachedBackupSession) manager.createEmptySession();
			deserializer.deserializeInto(bis.toString(), session);
			return session;
		}catch (RuntimeException e){
			log.warn("Caught Exception deserializing JSON "+e);
			throw e;
		}finally {
			close(bis);
		}
		
	}
	
	private void close( final Closeable stream) {
		if(stream != null) {
			try {
				stream.close();
			}catch( IOException e) {
				log.warn("JSON Transcoder Failed to close the stream: "+e);
			}
		}
	}

}
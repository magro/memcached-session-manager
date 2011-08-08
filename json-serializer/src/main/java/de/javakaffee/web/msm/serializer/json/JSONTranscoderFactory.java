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

import org.apache.catalina.Manager;

import de.javakaffee.web.msm.SessionAttributesTranscoder;
import de.javakaffee.web.msm.TranscoderFactory;
import de.javakaffee.web.msm.MemcachedSessionService.SessionManager;

/**
 * <p>
 * @author <a href="mailto:moresandeep@gmail.com">Sandeep More</a>
 * </p>
 * Creates {@link JSONTranscoder} instances.
 *
 */
public class JSONTranscoderFactory implements TranscoderFactory{
	
	private JSONTranscoder transcoder;
	
	@Override
	public SessionAttributesTranscoder createTranscoder(SessionManager manager) {
		return getTranscoder(manager);
	}
	
	private JSONTranscoder getTranscoder (final Manager manager) {
		if(transcoder == null){
			transcoder = new JSONTranscoder(manager);
		}
		return transcoder;
	}

	@Override
	public void setCopyCollectionsForSerialization(
			boolean copyCollectionsForSerialization) {
		
	}

	@Override
	public void setCustomConverterClassNames(String[] customConverterClassNames) {	
	}

}

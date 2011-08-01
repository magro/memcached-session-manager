package de.javakaffee.web.msm.serializer.json;

import org.apache.catalina.Manager;

import de.javakaffee.web.msm.SessionAttributesTranscoder;
import de.javakaffee.web.msm.TranscoderFactory;
import de.javakaffee.web.msm.MemcachedSessionService.SessionManager;

/**
 * Creates {@link JSONTranscoder} instances.
 * @author sandeep_more
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
		// TODO Auto-generated method stub
		
	}

	@Override
	public void setCustomConverterClassNames(String[] customConverterClassNames) {
		// TODO Auto-generated method stub		
	}

}

package de.javakaffee.web.msm.serializer.javalz4;

import de.javakaffee.web.msm.MemcachedSessionService;
import de.javakaffee.web.msm.SessionAttributesTranscoder;
import de.javakaffee.web.msm.TranscoderFactory;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

/**
* A {@link TranscoderFactory} that creates {@link JavaLZ4Transcoder} instances.
 * @author ilucas
 */
public class JavaLZ4TranscoderFactory implements TranscoderFactory {

    private static final Log LOG = LogFactory.getLog(JavaLZ4TranscoderFactory.class );
    
    /**
     * Default number of tries to serialize attributes if a
     * ConcurrentModificationException is thrown
     */
    private static final int COCURRENT_MODIFICATION_EXCEPTION_RETRY_NUMBER = 3;
    
    /**
     * Default interval in retries (ms)
     */
    private static final int COCURRENT_MODIFICATION_EXCEPTION_RETRY_TIME = 0;
    
   
    
    /**
     * Property name of retryActive
     */
    private static final String COCURRENT_MODIFICATION_EXCEPTION_RETRY_ACTIVE_PROPERTY = "msm.JavaLZ4.retryActive";
    private static final String COCURRENT_MODIFICATION_EXCEPTION_RETRY_NUMBER_PROPERTY = "msm.JavaLZ4.retryNumber";
    private static final String COCURRENT_MODIFICATION_EXCEPTION_RETRY_INTERVAL_PROPERTY = "msm.JavaLZ4.retryInterval";
    
    
    private final boolean retryActive;
    private final int retryNumber;
    private final int retryInterval;

    public JavaLZ4TranscoderFactory() {
        this.retryActive = getSysPropValue(COCURRENT_MODIFICATION_EXCEPTION_RETRY_ACTIVE_PROPERTY, false);        
        this.retryNumber = getSysPropValue(COCURRENT_MODIFICATION_EXCEPTION_RETRY_NUMBER_PROPERTY, COCURRENT_MODIFICATION_EXCEPTION_RETRY_NUMBER);
        this.retryInterval = getSysPropValue(COCURRENT_MODIFICATION_EXCEPTION_RETRY_INTERVAL_PROPERTY, COCURRENT_MODIFICATION_EXCEPTION_RETRY_TIME);
    }
    
    
    /**
     * {@inheritDoc}
     */
    @Override
    public SessionAttributesTranscoder createTranscoder( final MemcachedSessionService.SessionManager manager ) {
        return new JavaLZ4Transcoder( manager.getContainerClassLoader(),retryActive,retryNumber,retryInterval );
    }

    /**
     * If <code>copyCollectionsForSerialization</code> is set to <code>true</code>,
     * an {@link UnsupportedOperationException} will be thrown, as java serialization
     * cannot be changed and it does not copy collections for serialization.
     *
     * @param copyCollectionsForSerialization the copyCollectionsForSerialization value
     */
    @Override
    public void setCopyCollectionsForSerialization( final boolean copyCollectionsForSerialization ) {
        if ( copyCollectionsForSerialization ) {
            throw new UnsupportedOperationException(
                    "Java serialization cannot be changed - it does not copy collections for serialization." );
        }
    }

    /**
     * Throws an {@link UnsupportedOperationException}, as java serialization
     * does not support custom xml format.
     *
     * @param customConverterClassNames a list of class names or <code>null</code>.
     */
    @Override
    public void setCustomConverterClassNames( final String[] customConverterClassNames ) {
        if ( customConverterClassNames != null && customConverterClassNames.length > 0 ) {
            throw new UnsupportedOperationException( "Java serialization does not support custom converter." );
        }
    }
    
    private int getSysPropValue( final String propName, final int defaultValue ) {
        int value = defaultValue;
        final String propValue = System.getProperty( propName );
        if ( propValue != null ) {
            try {
                value = Integer.parseInt( propValue );
            } catch( final NumberFormatException e ) {
                LOG.warn( "Could not parse system property " + propName + " using default value " +defaultValue + " : " + e );
            }
        }
        return value;
    }
    
    private boolean getSysPropValue( final String propName, final boolean defaultValue ) {
        boolean value = defaultValue;
        final String propValue = System.getProperty( propName );
        if ( propValue != null ) {
            try {
                value = Boolean.parseBoolean( propValue );
            } catch( final NumberFormatException e ) {
                LOG.warn( "Could not parse system property " + propName + " using default value " +defaultValue + " : " + e );
            }
        }
        return value;
    }

}

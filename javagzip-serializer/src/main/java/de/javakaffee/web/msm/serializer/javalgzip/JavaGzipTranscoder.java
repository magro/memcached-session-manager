package de.javakaffee.web.msm.serializer.javalgzip;

import de.javakaffee.web.msm.JavaSerializationTranscoder;
import de.javakaffee.web.msm.MemcachedBackupSession;
import de.javakaffee.web.msm.SessionAttributesTranscoder;
import de.javakaffee.web.msm.TranscoderDeserializationException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.NotSerializableException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.apache.catalina.session.StandardSession;
import org.apache.catalina.util.CustomObjectInputStream;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

/**
 * A {@link SessionAttributesTranscoder} that serializes catalina
 * {@link StandardSession}s using java serialization (and the serialization
 * logic of {@link StandardSession} as found in
 * {@link StandardSession#writeObjectData(ObjectOutputStream)} and
 * {@link StandardSession#readObjectData(ObjectInputStream)}). It is custom
 * implementation of {@link JavaSerializationTranscoder} that uses
 * {@link GZIPInputStream} and {@link GZIPOutputStream} to compress and
 * decompress the session.
 *
 * @author ilucas
 */
public class JavaGzipTranscoder implements SessionAttributesTranscoder {

    private static final Log LOG = LogFactory.getLog(JavaGzipTranscoder.class);

    private static final String EMPTY_ARRAY[] = new String[0];

    private final boolean retryActive;
    private final int retryNumber;
    private final int retryInterval;
    /**
     * The dummy attribute value serialized when a NotSerializableException is
     * encountered in <code>writeObject()</code>.
     */
    protected static final String NOT_SERIALIZED = "___NOT_SERIALIZABLE_EXCEPTION___";

    private final ClassLoader classLoader;

    /**
     * Constructor.
     *
     * @param manager the manager
     */
    public JavaGzipTranscoder() {
        this(null, false, 1, 0);
    }

    /**
     * Constructor.
     *
     * @param classLoader
     * @param compresser
     * @param retryActive
     * @param retryNumber
     * @param retryInterval
     */
    public JavaGzipTranscoder(ClassLoader classLoader, boolean retryActive, int retryNumber, int retryInterval) {
        this.retryActive = retryActive;
        this.retryNumber = retryNumber;
        this.retryInterval = retryInterval;
        this.classLoader = classLoader;

    }

    /**
     * {@inheritDoc}
     */
    @Override
    public byte[] serializeAttributes(final MemcachedBackupSession session, final ConcurrentMap<String, Object> attributes) {
        if (attributes == null) {
            throw new NullPointerException("Can't serialize null");
        }
        if (retryActive) {
            int i = 0;
            ConcurrentModificationException concurrentModificationException = null;
            do {
                try {
                    return writeAttributes(session, attributes);
                } catch (ConcurrentModificationException ex) {
                    concurrentModificationException = ex;
                    LOG.warn("ConcurrentModificationException on write attributes of session " + session.getIdInternal() + " retry " + i);
                    if (retryInterval > 0) {
                        try {
                            Thread.sleep(retryInterval);
                        } catch (InterruptedException interruptedException) {
                            LOG.debug("Interrupted on wating for try to writeAttributes: " + interruptedException);
                        }
                    }
                }
            } while (i++ < retryNumber);
            throw concurrentModificationException;
        } else {

            return writeAttributes(session, attributes);

        }
    }

    private byte[] writeAttributes(final MemcachedBackupSession session, final ConcurrentMap<String, Object> attributes) throws IllegalArgumentException {
        ByteArrayOutputStream bos = null;
        OutputStream gzs = null;
        ObjectOutputStream oos = null;
        try {
            bos = new ByteArrayOutputStream();
            gzs = new GZIPOutputStream(bos);
            oos = new ObjectOutputStream(gzs);
            writeAttributes(session, attributes, oos);
            closeSilently(oos);
            closeSilently(gzs);
            return bos.toByteArray();
        } catch (final IOException e) {
            throw new IllegalArgumentException("Non-serializable object", e);
        } finally {
            closeSilently(oos);
            closeSilently(gzs);
            closeSilently(bos);
        }
    }

    private void writeAttributes(final MemcachedBackupSession session, final Map<String, Object> attributes,
            final ObjectOutputStream oos) throws IOException {

        // Accumulate the names of serializable and non-serializable attributes
        final String keys[] = attributes.keySet().toArray(EMPTY_ARRAY);
        final List<String> saveNames = new ArrayList<String>();
        final List<Object> saveValues = new ArrayList<Object>();
        for (int i = 0; i < keys.length; i++) {
            final Object value = attributes.get(keys[i]);
            if (value == null) {
                continue;
            } else if (value instanceof Serializable) {
                saveNames.add(keys[i]);
                saveValues.add(value);
            } else {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Ignoring attribute '" + keys[i] + "' as it does not implement Serializable");
                }
            }
        }

        // Serialize the attribute count and the Serializable attributes
        final int n = saveNames.size();
        oos.writeObject(Integer.valueOf(n));
        for (int i = 0; i < n; i++) {
            oos.writeObject(saveNames.get(i));
            if (LOG.isDebugEnabled()) {
                LOG.debug(" start storing attribute '" + saveNames.get(i) + "'");
            }
            try {
                oos.writeObject(saveValues.get(i));
                if (LOG.isDebugEnabled()) {
                    LOG.debug("  stored attribute '" + saveNames.get(i) + "' with value '" + saveValues.get(i) + "'");
                }
            } catch (final NotSerializableException e) {
                LOG.warn("Attribute " + saveNames.get(i) + " of session " + session.getIdInternal() + " is not serializable", e);
                oos.writeObject(NOT_SERIALIZED);
                if (LOG.isDebugEnabled()) {
                    LOG.debug("  storing attribute '" + saveNames.get(i) + "' with value NOT_SERIALIZED");
                }
            } catch (final ConcurrentModificationException concurrentModificationException) {
                // throw to catch and retry
                throw concurrentModificationException;
            } catch (final Exception e) {
                LOG.warn("Attribute " + saveNames.get(i) + " of session " + session.getIdInternal() + " was not serialized correctly", e);
                throw new IOException("Attribute " + saveNames.get(i) + " of session " + session.getIdInternal() + " was not serialized correctly, stream invalidated", e);
            }

        }

    }

    /**
     * Get the object represented by the given serialized bytes.
     *
     * @param in the bytes to deserialize
     * @return the resulting object
     */
    @Override
    public ConcurrentMap<String, Object> deserializeAttributes(final byte[] in) {
        ByteArrayInputStream bis = null;
        InputStream gzs = null;
        ObjectInputStream ois = null;
        try {
            bis = new ByteArrayInputStream(in);
            gzs = new GZIPInputStream(bis);
            ois = createObjectInputStream(gzs);

            final ConcurrentMap<String, Object> attributes = new ConcurrentHashMap<String, Object>();
            final int n = ((Integer) ois.readObject()).intValue();
            for (int i = 0; i < n; i++) {
                final String name = (String) ois.readObject();
                if (LOG.isDebugEnabled()) {
                    LOG.debug("  start reading attribute '" + name + "'");
                }
                final Object value = ois.readObject();
                if ((value instanceof String) && (value.equals(NOT_SERIALIZED))) {
                    continue;
                }
                if (LOG.isDebugEnabled()) {
                    LOG.debug("  loading attribute '" + name + "' with value '" + value + "' to map");
                }
                attributes.put(name, value);
            }

            return attributes;
        } catch (final ClassNotFoundException e) {
            LOG.warn("Caught CNFE decoding " + in.length + " bytes of data", e);
            throw new TranscoderDeserializationException("Caught CNFE decoding data", e);
        } catch (final IOException e) {
            LOG.warn("Caught IOException decoding " + in.length + " bytes of data", e);
            throw new TranscoderDeserializationException("Caught IOException decoding data", e);
        } finally {
            closeSilently(bis);
            closeSilently(gzs);
            closeSilently(ois);
        }
    }

    private ObjectInputStream createObjectInputStream(final InputStream bis) throws IOException {
        final ObjectInputStream ois;
        if (classLoader != null) {
            ois = new CustomObjectInputStream(bis, classLoader);
        } else {
            ois = new ObjectInputStream(bis);
        }
        return ois;
    }

    private void closeSilently(final OutputStream os) {
        if (os != null) {
            try {
                os.close();
            } catch (final IOException f) {
                LOG.debug("Error on closeSilently OutputStream " + os, f);
            }
        }
    }

    private void closeSilently(final InputStream is) {
        if (is != null) {
            try {
                is.close();
            } catch (final IOException f) {
                LOG.debug("Error on closeSilently InputStream:" + is, f);
            }
        }
    }

}

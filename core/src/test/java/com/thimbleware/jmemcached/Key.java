package com.thimbleware.jmemcached;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;

/**
 * Adds toString to the original class
 */
public class Key {

    public ChannelBuffer bytes;
    private int hashCode;

    public Key(ChannelBuffer bytes) {
        this.bytes = bytes.copy();
        this.hashCode = this.bytes.hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Key key1 = (Key) o;

        bytes.readerIndex(0);
        key1.bytes.readerIndex(0);
        if (!bytes.equals(key1.bytes)) return false;

        return true;
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    public String toString() {
        return new String(bytes.toByteBuffer().array());
    }

}

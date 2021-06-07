package de.javakaffee.web.msm;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInput;
import java.io.ObjectInputStream;
import java.io.ObjectOutput;
import java.io.ObjectOutputStream;
import java.io.OutputStream;

public class DefaultObjectIOStrategy implements ObjectIOStrategy {
	@Override
	public ObjectInput createObjectInput(final InputStream is) throws IOException {
		return new ObjectInputStream(is);
	}

	@Override
	public ObjectOutput createObjectOutput(final OutputStream os) throws IOException {
		return new ObjectOutputStream(os);
	}
}

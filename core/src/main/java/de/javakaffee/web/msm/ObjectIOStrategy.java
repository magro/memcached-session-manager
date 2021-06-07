package de.javakaffee.web.msm;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.io.OutputStream;

public interface ObjectIOStrategy {
    ObjectInput createObjectInput( InputStream is ) throws IOException;

    ObjectOutput createObjectOutput( OutputStream os ) throws IOException;
}

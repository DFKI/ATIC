package de.dfki.sds.atic.agent;

import java.io.InputStream;
import java.util.Objects;
import java.util.function.Supplier;

public record BinaryAttachment(
        String filename,
        String contentType,
        Supplier<InputStream> inputStreamSupplier
) implements Attachment {

    public BinaryAttachment {
        Objects.requireNonNull(filename, "filename");
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(inputStreamSupplier, "inputStreamSupplier");
    }

    public InputStream openStream() {
        return inputStreamSupplier.get();
    }
}

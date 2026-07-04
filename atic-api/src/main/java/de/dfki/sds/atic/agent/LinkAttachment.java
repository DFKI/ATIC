

package de.dfki.sds.atic.agent;

import java.net.URI;
import java.util.Objects;

public record LinkAttachment(
        URI uri,
        String title,
        String abstractText
) implements Attachment {

    public LinkAttachment {
        Objects.requireNonNull(uri, "uri");
    }
}
package de.dfki.sds.atic.agent;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record LinkAttachment(
        URI uri,
        String title,
        String abstractText
        ) implements Attachment {

    public LinkAttachment {
        Objects.requireNonNull(uri, "uri");
    }

    @Override
    public Map<String, Object> toMap() {

        Map<String, Object> map = new LinkedHashMap<>();

        map.put("type", "link");
        map.put("uri", uri.toString());

        if (title != null) {
            map.put("title", title);
        }

        if (abstractText != null) {
            map.put("abstractText", abstractText);
        }

        return Map.copyOf(map);
    }
}

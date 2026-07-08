package de.dfki.sds.atic.agent;


import java.util.Objects;

public record ToolCallAttachment(
        String name,
        String arguments,
        String result,
        Throwable throwable
) implements Attachment {

    public ToolCallAttachment {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(arguments, "arguments");
    }
}

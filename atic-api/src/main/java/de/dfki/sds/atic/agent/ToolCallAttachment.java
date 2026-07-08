package de.dfki.sds.atic.agent;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.Map;
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

    @Override
    public Map<String, Object> toMap() {

        Map<String, Object> map = new LinkedHashMap<>();

        map.put("type", "toolCall");
        map.put("name", name);
        map.put("arguments", arguments);

        if (result != null) {
            map.put("result", result);
        }

        if (throwable != null) {
            map.put("throwable", stackTraceToString(throwable));
        }

        return Map.copyOf(map);
    }

    private static String stackTraceToString(Throwable throwable) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        throwable.printStackTrace(pw);
        pw.flush();
        return sw.toString();
    }
}

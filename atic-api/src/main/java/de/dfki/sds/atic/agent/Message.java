package de.dfki.sds.atic.agent;

import de.dfki.sds.atic.ac.User;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record Message(
        User sender,
        Instant timestamp,
        String content,
        String contentType,
        List<Attachment> attachments
        ) {

    public static final String TEXT_PLAIN = "text/plain";
    public static final String TEXT_MARKDOWN = "text/markdown";
    public static final String TEXT_HTML = "text/html";

    public Message {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(contentType, "contentType");

        attachments = attachments == null
                ? List.of()
                : List.copyOf(attachments);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Message{");
        sb.append("sender=").append(sender.getUsername());
        sb.append(", timestamp=").append(timestamp);
        sb.append(", content=").append(content);
        sb.append(", contentType=").append(contentType);
        sb.append(", attachments=\n");
        
        for(Attachment attachment : attachments) {
            sb.append(attachment).append("\n");
        }
        
        sb.append('}');
        return sb.toString();
    }

    
    
    public static Message plainText(User sender, String content) {
        return new Message(
                sender,
                Instant.now(),
                content,
                TEXT_PLAIN,
                List.of()
        );
    }

    public static Message markdown(User sender, String content) {
        return new Message(
                sender,
                Instant.now(),
                content,
                TEXT_MARKDOWN,
                List.of()
        );
    }

    public static Builder builder(
            User sender,
            String content,
            String contentType) {

        return new Builder(sender, content, contentType);
    }

    // ---------------- attachments API ----------------
    public boolean hasAttachment(Class<? extends Attachment> type) {
        Objects.requireNonNull(type, "type");

        return attachments.stream()
                .anyMatch(type::isInstance);
    }

    public <T extends Attachment> T getAttachment(Class<T> type) {
        Objects.requireNonNull(type, "type");

        return attachments.stream()
                .filter(type::isInstance)
                .map(type::cast)
                .findFirst()
                .orElse(null);
    }

    public <T extends Attachment> List<T> getAttachments(Class<T> type) {
        Objects.requireNonNull(type, "type");

        List<T> result = new ArrayList<>();

        for (Attachment attachment : attachments) {
            if (type.isInstance(attachment)) {
                result.add(type.cast(attachment));
            }
        }

        return List.copyOf(result);
    }

    // ---------------- builder ----------------
    public static final class Builder {

        private final User sender;
        private final String content;
        private final String contentType;

        private Instant timestamp = Instant.now();

        private final List<Attachment> attachments = new ArrayList<>();

        private Builder(
                User sender,
                String content,
                String contentType) {

            this.sender = Objects.requireNonNull(sender);
            this.content = Objects.requireNonNull(content);
            this.contentType = Objects.requireNonNull(contentType);
        }

        public Builder timestamp(Instant timestamp) {
            this.timestamp = Objects.requireNonNull(timestamp);
            return this;
        }

        public Builder attachment(Attachment attachment) {
            attachments.add(Objects.requireNonNull(attachment));
            return this;
        }

        public Builder attachments(
                java.util.Collection<? extends Attachment> attachments) {

            this.attachments.addAll(attachments);
            return this;
        }

        public Message build() {
            return new Message(
                    sender,
                    timestamp,
                    content,
                    contentType,
                    attachments
            );
        }
    }
}

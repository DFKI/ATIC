

package de.dfki.sds.atic.jenatic;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class AticVirtualGraphResponse {

    private final InputStream inputStream;
    private final int status;
    private final String contentType;

    private AticVirtualGraphResponse(Builder builder) {
        this.inputStream = builder.inputStream;
        this.status = builder.status;
        this.contentType = builder.contentType;
    }

    public InputStream getInputStream() {
        return inputStream;
    }

    public int getStatus() {
        return status;
    }

    public String getContentType() {
        return contentType;
    }

    // ---------- Builder ----------
    public static class Builder {

        private InputStream inputStream = InputStream.nullInputStream();
        private int status = 200;
        private String contentType = "application/octet-stream";

        public Builder inputStream(InputStream inputStream) {
            this.inputStream = inputStream;
            return this;
        }

        public Builder body(String content) {
            if (content != null) {
                byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
                this.inputStream = new ByteArrayInputStream(bytes);
                this.contentType = "text/plain; charset=utf-8";
            }
            return this;
        }

        public Builder body(byte[] data) {
            if (data != null) {
                this.inputStream = new ByteArrayInputStream(data);
            }
            return this;
        }

        public Builder status(int status) {
            this.status = status;
            return this;
        }

        public Builder contentType(String contentType) {
            this.contentType = contentType;
            return this;
        }

        public AticVirtualGraphResponse build() {
            return new AticVirtualGraphResponse(this);
        }
    }

    // optional convenience
    public static Builder builder() {
        return new Builder();
    }
}

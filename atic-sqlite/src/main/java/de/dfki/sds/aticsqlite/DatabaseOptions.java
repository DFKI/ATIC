

package de.dfki.sds.aticsqlite;

/**
 *
 */
public class DatabaseOptions {

    private final String dbFilePath;
    private final int busyTimeoutMs;
    private final boolean enableWal;
    private final boolean enableForeignKeys;

    private DatabaseOptions(Builder builder) {
        this.dbFilePath = builder.dbFilePath;
        this.busyTimeoutMs = builder.busyTimeoutMs;
        this.enableWal = builder.enableWal;
        this.enableForeignKeys = builder.enableForeignKeys;
    }

    public String getDbFilePath() {
        return dbFilePath;
    }

    public int getBusyTimeoutMs() {
        return busyTimeoutMs;
    }

    public boolean isEnableWal() {
        return enableWal;
    }

    public boolean isEnableForeignKeys() {
        return enableForeignKeys;
    }

    public static class Builder {
        private final String dbFilePath; // required

        private int busyTimeoutMs = 5000; // default
        private boolean enableWal = true; // default
        private boolean enableForeignKeys = true; // default

        public Builder(String dbFilePath) {
            if (dbFilePath == null || dbFilePath.isEmpty()) {
                throw new IllegalArgumentException("dbFilePath cannot be null or empty");
            }
            this.dbFilePath = dbFilePath;
        }

        public Builder busyTimeoutMs(int busyTimeoutMs) {
            this.busyTimeoutMs = busyTimeoutMs;
            return this;
        }

        public Builder enableWal(boolean enableWal) {
            this.enableWal = enableWal;
            return this;
        }

        public Builder enableForeignKeys(boolean enableForeignKeys) {
            this.enableForeignKeys = enableForeignKeys;
            return this;
        }

        public DatabaseOptions build() {
            return new DatabaseOptions(this);
        }
    }
}

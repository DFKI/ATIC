

package de.dfki.sds.aticserver;

import de.dfki.sds.aticsqlite.RDFPatchListener;
import de.dfki.sds.rdfpatchsqlite.Converter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import org.apache.jena.rdfpatch.RDFPatch;

public class RDFPatchWriter implements RDFPatchListener {

    private final File folder;
    private final int batchSize = 500;
    private Duration rotationInterval;
    private final Converter converter;

    private File currentDbFile;
    private Instant currentStartTime;
    private String jdbcLink;

    public RDFPatchWriter(File folder, Duration rotationInterval) {
        this.folder = folder;
        this.rotationInterval = rotationInterval;
        this.converter = new Converter();

        initFromFolder();
    }

    @Override
    public synchronized void handlePatch(RDFPatch patch) {
        rotateIfNeeded();
        converter.toSqliteUnwrap(patch, jdbcLink, batchSize);
    }

    // -------------------------
    // Initialization
    // -------------------------

    private void initFromFolder() {
        File[] rollingFiles = folder.listFiles((dir, name) -> name.startsWith("rolling-") && name.endsWith(".db"));

        if (rollingFiles != null && rollingFiles.length > 0) {
            // pick latest rolling file
            File latest = Arrays.stream(rollingFiles)
                    .max(Comparator.comparing(File::getName))
                    .orElseThrow();

            this.currentDbFile = latest;
            this.currentStartTime = extractTimestamp(latest.getName());
        } else {
            // create new one
            this.currentStartTime = Instant.now();
            this.currentDbFile = new File(folder, rollingName(currentStartTime));
        }

        updateJdbcLink();
    }

    // -------------------------
    // Rotation
    // -------------------------

    private void rotateIfNeeded() {
        Instant now = Instant.now();

        if (Duration.between(currentStartTime, now).compareTo(rotationInterval) >= 0) {
            rotate(now);
        }
    }

    private void rotate(Instant now) {
        try {
            // rename current → done
            File doneFile = new File(folder, doneName(currentStartTime));

            Files.move(
                    currentDbFile.toPath(),
                    doneFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
            );

            // create new rolling file
            currentStartTime = now;
            currentDbFile = new File(folder, rollingName(now));

            updateJdbcLink();

        } catch (IOException e) {
            throw new RuntimeException("Failed to rotate sqlite file", e);
        }
    }

    // -------------------------
    // Naming helpers
    // -------------------------

    private String rollingName(Instant time) {
        return "rolling-" + format(time) + ".db";
    }

    private String doneName(Instant time) {
        return "done-" + format(time) + ".db";
    }

    private String format(Instant time) {
        return DateTimeFormatter.ISO_INSTANT.format(time)
                .replace(":", "-"); // filesystem safe
    }

    private Instant extractTimestamp(String name) {
        String raw = name
                .replace("rolling-", "")
                .replace("done-", "")
                .replace(".db", "");

        int tIndex = raw.indexOf('T');

        String datePart = raw.substring(0, tIndex);          // 2026-04-18
        String timePart = raw.substring(tIndex + 1) // 10-00-00Z
                .replace("-", ":");                          // 10:00:00Z

        String iso = datePart + "T" + timePart;

        return Instant.parse(iso);
    }

    private void updateJdbcLink() {
        this.jdbcLink = "jdbc:sqlite:" + currentDbFile.getAbsolutePath();
    }

    public Duration getRotationInterval() {
        return rotationInterval;
    }

    public void setRotationInterval(Duration rotationInterval) {
        this.rotationInterval = rotationInterval;
    }
    
}

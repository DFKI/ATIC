package de.dfki.sds.aticsqlitejmh;

import java.io.*;
import java.net.URL;
import java.nio.file.*;
import java.util.zip.GZIPInputStream;

public class Downloader {

    private static final String BASE_URL = "https://zenodo.org/records/5714035/files/BSBM-%d.nt.gz?download=1";
    private static final String TEMP_DIR_NAME = "BSBM";

    public static Path downloadBSBM(int size) throws IOException {
        return downloadBSBM(size, false);
    }
    
    public static Path downloadBSBM(int size, boolean unzip) throws IOException {
        String gzFileName = String.format("BSBM-%d.nt.gz", size);
        String url = String.format(BASE_URL, size);

        Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"), TEMP_DIR_NAME);
        Files.createDirectories(tempDir);

        Path gzPath = tempDir.resolve(gzFileName);

        // download if needed
        downloadIfNeeded(url, gzPath);

        if (!unzip) {
            return gzPath;
        }

        // unzip if needed
        Path ntPath = tempDir.resolve(gzFileName.replace(".gz", ""));
        return unzipIfNeeded(gzPath, ntPath);
    }

    private static Path downloadIfNeeded(String urlString, Path targetFile) throws IOException {
        if (Files.exists(targetFile)) {
            return targetFile;
        }

        try (InputStream in = new URL(urlString).openStream()) {
            Files.copy(in, targetFile);
        }

        return targetFile;
    }

    private static Path unzipIfNeeded(Path gzFile, Path targetFile) throws IOException {
        if (Files.exists(targetFile)) {
            return targetFile;
        }

        try (GZIPInputStream gis = new GZIPInputStream(Files.newInputStream(gzFile));
             OutputStream out = Files.newOutputStream(targetFile)) {

            byte[] buffer = new byte[8192];
            int len;
            while ((len = gis.read(buffer)) > 0) {
                out.write(buffer, 0, len);
            }
        }

        return targetFile;
    }
    
}

package de.dfki.sds.aticsqlite;

import de.dfki.sds.atic.jenatic.AticGraph;
import de.dfki.sds.atic.jenatic.InvocationContext;
import de.dfki.sds.atic.vocab.NFO;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.GraphEventManager;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.TransactionHandler;
import org.apache.jena.graph.Triple;
import org.apache.jena.graph.impl.GraphMatcher;
import org.apache.jena.graph.impl.SimpleEventManager;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.shared.PrefixMapping;
import org.apache.jena.sparql.graph.PrefixMappingAdapter;
import org.apache.jena.sparql.graph.TransactionHandlerNull;
import org.apache.jena.util.iterator.ExtendedIterator;
import org.apache.jena.util.iterator.NiceIterator;
import org.apache.jena.sparql.graph.GraphFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Content-addressed blob store backed by a SQLite pointer table and the filesystem.
 *
 * <p>Architecture overview:
 * <ul>
 *   <li><b>Identity</b>: Each binary is identified by its SHA-256 hash (content-addressed).</li>
 *   <li><b>Storage</b>: Bytes are stored on disk under {@code blob/{sha256[0:2]}/{sha256[2:4]}/{sha256[4:6]}/{sha256}}.</li>
 *   <li><b>Pointer table</b>: {@code blobgraph} maps {@code filepath → uri} (with optional {@code sha256}).</li>
 *   <li><b>Metadata</b>: RDF metadata lives in {@code .ttl} files alongside the blob file.</li>
 *   <li><b>Transformations</b>: Derived representations (e.g. resized images) are
 *       deterministically keyed by source hash + transformation parameters.</li>
 * </ul>
 *
 * <p>URI convention: every blob gets a stable URI of the form
 * {@code urn:atic:blob:{sha256}}.
 * The URI is content-addressed — two blobs with the same content always
 * share the same URI, regardless of where the file lives on disk.
 */
public class BlobGraph implements AticGraph {

    private static final Logger log = LoggerFactory.getLogger(BlobGraph.class);

    public static final Node node = NodeFactory.createURI("urn:atic:blob");

    /**
     * The URI namespace for blob identities.
     * Every blob gets a stable URI of the form {@code urn:atic:blob:{sha256}}.
     */
    public static final String NS_BLOB = "urn:atic:blob:";

    private static final String NS_ATIC = "http://www.atic.org/ns#";
    public static final String PREFIX_ATIC = "atic";

    private static final String NS_PROV = "http://www.w3.org/ns/prov#";
    public static final String PREFIX_PROV = "prov";

    /** Standard transformation parameters with defaults */
    public static final String DEFAULT_FORMAT = "webp";
    public static final int DEFAULT_QUALITY = 85;
    public static final String DEFAULT_MODE = "fit";

    private final SqliteAticDatasetGraph datasetGraph;
    private final TransactionHandler transactionHandler;
    private final GraphEventManager graphEventManager;
    private final Database db;
    /*package*/ final File rootFolder;

    public BlobGraph(SqliteAticDatasetGraph datasetGraph) {
        this.datasetGraph = datasetGraph;
        this.db = datasetGraph.getDatabase();

        File dbFolder = this.db.getFolder();
        this.rootFolder = new File(dbFolder, "blob");
        this.rootFolder.mkdirs();

        transactionHandler = new TransactionHandlerNull();
        graphEventManager = new SimpleEventManager();
    }

    /**
     * Bootstrap the blob table in the database.
     */
    /*package*/ void bootstrap() throws SQLException {
        db.writeQuery("create_table_blobgraph.sql");
    }

    // =========================================================================
    //  Content-addressed identity helpers
    // =========================================================================

    /**
     * Compute the SHA-256 hex digest of the given bytes.
     */
    public static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data);
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Compute the SHA-256 hex digest by streaming through the input.
     */
    public static String sha256(InputStream is) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
            return bytesToHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * Compute a deterministic transformation key.
     *
     * <p>The key is SHA-256 of a canonical JSON object containing source hash
     * and transformation parameters. This is <em>not</em> the content hash of
     * the resulting image — it is a lookup key that lets the system detect
     * when the same transformation has already been generated.
     *
     * @param sourceHash  SHA-256 of the original blob
     * @param width       target width (0 = auto)
     * @param height      target height (0 = auto)
     * @param format      output format (e.g. "webp", "jpeg", "png")
     * @param quality     quality factor (1-100)
     * @param mode        resize mode ("fit", "cover", "crop", "pad")
     * @return hex SHA-256 of the canonical transformation description
     */
    public static String computeTransformationKey(String sourceHash,
                                                    Integer width,
                                                    Integer height,
                                                    String format,
                                                    Integer quality,
                                                    String mode) {
        // Build canonical JSON: sorted keys, compact separators
        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"format\":\"").append(escapeJson(format)).append("\",");
        json.append("\"height\":").append(height == null ? 0 : height).append(",");
        json.append("\"mode\":\"").append(escapeJson(mode)).append("\",");
        json.append("\"quality\":").append(quality == null ? DEFAULT_QUALITY : quality).append(",");
        json.append("\"source\":\"").append(escapeJson(sourceHash)).append("\",");
        json.append("\"width\":").append(width == null ? 0 : width);
        json.append("}");

        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return bytesToHex(md.digest(json.toString().getBytes("UTF-8")));
        } catch (NoSuchAlgorithmException | java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException("Hashing failure", e);
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // =========================================================================
    //  Filesystem helpers
    // =========================================================================

    /**
     * Return the on-disk path for a blob identified by its SHA-256 hash.
     * Path layout: {@code {root}/{sha256[0:2]}/{sha256[2:4]}/{sha256[4:6]}/{sha256}}
     *
     * <p>Three folder levels distribute files evenly across directories, avoiding
     * filesystem performance degradation from too many files in a single directory.
     */
    /*package*/ File blobPath(String sha256) {
        File d1 = new File(rootFolder, sha256.substring(0, 2));
        File d2 = new File(d1, sha256.substring(2, 4));
        File d3 = new File(d2, sha256.substring(4, 6));
        return new File(d3, sha256);
    }

    /**
     * Ensure the directory for a given hash exists.
     */
    private void ensureDir(String sha256) {
        File dir = blobPath(sha256).getParentFile();
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    /**
     * Build the blob URI: {@code urn:atic:blob:{sha256}}.
     *
     * <p>This is the stable, content-addressed identity for a blob.
     * Two blobs with identical content always produce the same URI,
     * regardless of their filepath on disk.
     *
     * @param sha256 the 64-character hex SHA-256 hash
     * @return URI string, e.g. {@code urn:atic:blob:abc123...}
     */
    public String blobUri(String sha256) {
        return NS_BLOB + sha256;
    }

    /**
     * Read a blob's bytes from disk by its SHA-256 hash.
     *
     * @return bytes, or {@code null} if the blob does not exist
     */
    public byte[] readBlob(String sha256) {
        File f = blobPath(sha256);
        if (!f.isFile()) {
            return null;
        }
        try {
            return Files.readAllBytes(f.toPath());
        } catch (IOException e) {
            log.warn("Failed to read blob {}", sha256, e);
            return null;
        }
    }

    /**
     * Write bytes to disk under the content-addressed path.
     * Overwrites if the file already exists.
     */
    private void writeBlob(String sha256, byte[] data) {
        ensureDir(sha256);
        File f = blobPath(sha256);
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(data);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write blob " + sha256, e);
        }
    }

    /**
     * Delete a blob file from disk.
     */
    private void deleteBlobFile(String sha256) {
        File f = blobPath(sha256);
        if (f.isFile()) {
            f.delete();
        }
    }

    // =========================================================================
    //  Pointer-table operations (blobgraph)
    // =========================================================================

    /**
     * Insert or update the pointer: filepath → uri (+ sha256).
     *
     * <p>Uses a single SQL statement with {@code INSERT OR REPLACE} so the
     * upsert is atomic — no race condition between SELECT and INSERT/UPDATE.
     * The {@code blobgraph} table has {@code UNIQUE(filepath)}, so SQLite
     * automatically replaces the old row when the insert conflicts.
     */
    private void upsertPointer(String filepath, String uri, String sha256) {
        try {
            // INSERT OR REPLACE exploits the UNIQUE(filepath) constraint:
            // if a row with this filepath already exists it is deleted first
            // and the new row is inserted.
            db.write(
                "INSERT OR REPLACE INTO blobgraph (filepath, uri, sha256, creator) "
                + "VALUES (?, ?, ?, COALESCE("
                +   "(SELECT creator FROM blobgraph WHERE filepath = ?), 0))",
                filepath, uri, sha256, filepath
            );
        } catch (SQLException e) {
            throw new RuntimeException("Failed to upsert pointer for " + filepath, e);
        }
    }

    /**
     * Look up the URI for a filepath from the pointer table.
     *
     * @return URI string, or {@code null} if not found
     */
    private String lookupUriByPath(String filepath) {
        try {
            return db.read(
                "SELECT uri FROM blobgraph WHERE filepath = ?",
                rs -> rs.next() ? rs.getString("uri") : null,
                filepath
            );
        } catch (SQLException e) {
            throw new RuntimeException("DB error looking up uri for " + filepath, e);
        }
    }

    /**
     * Look up the SHA-256 hash for a filepath.
     *
     * @return hash string, or {@code null} if not found
     */
    private String lookupSha256ByPath(String filepath) {
        try {
            return db.read(
                "SELECT sha256 FROM blobgraph WHERE filepath = ?",
                rs -> rs.next() ? rs.getString("sha256") : null,
                filepath
            );
        } catch (SQLException e) {
            throw new RuntimeException("DB error looking up sha256 for " + filepath, e);
        }
    }

    /**
     * Look up the filepath for a SHA-256 hash.
     *
     * @return filepath string, or {@code null} if not found
     */
    private String lookupPathBySha256(String sha256) {
        try {
            return db.read(
                "SELECT filepath FROM blobgraph WHERE sha256 = ?",
                rs -> rs.next() ? rs.getString("filepath") : null,
                sha256
            );
        } catch (SQLException e) {
            throw new RuntimeException("DB error looking up filepath for sha256 " + sha256, e);
        }
    }

    /**
     * Look up the SHA-256 hash for a URI.
     *
     * @return hash string, or {@code null} if not found
     */
    private String lookupSha256ByUri(String uri) {
        try {
            return db.read(
                "SELECT sha256 FROM blobgraph WHERE uri = ?",
                rs -> rs.next() ? rs.getString("sha256") : null,
                uri
            );
        } catch (SQLException e) {
            throw new RuntimeException("DB error looking up sha256 for uri " + uri, e);
        }
    }

    /**
     * Delete a pointer entry.
     */
    private void deletePointer(String filepath) {
        try {
            db.write("DELETE FROM blobgraph WHERE filepath = ?", filepath);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete pointer for " + filepath, e);
        }
    }

    // =========================================================================
    //  Metadata (.ttl) file operations
    // =========================================================================

    /**
     * Return the path to the .ttl metadata file for a given SHA-256 hash.
     * Lives alongside the blob file: {@code {sha[0:2]}/{sha[2:4]}/{sha[4:6]}/{sha}.ttl}.
     */
    /*package*/ File metadataFile(String sha256) {
        File blob = blobPath(sha256);
        return new File(blob.getParentFile(), blob.getName() + ".ttl");
    }

    /**
     * Load RDF metadata from the .ttl file alongside the blob.
     */
    public Model loadMetadata(String blobFilepath, InvocationContext ctx) {
        String sha256 = lookupSha256ByPath(blobFilepath);
        if (sha256 == null) {
            return ModelFactory.createDefaultModel();
        }
        File metaFile = metadataFile(sha256);
        if (!metaFile.isFile()) {
            return ModelFactory.createDefaultModel();
        }
        try {
            Model model = ModelFactory.createDefaultModel();
            model.read(metaFile.getAbsolutePath(), null, "TTL");
            return model;
        } catch (Exception e) {
            log.warn("Failed to load metadata from {}", metaFile, e);
            return ModelFactory.createDefaultModel();
        }
    }

    /**
     * Save RDF metadata to a .ttl file alongside the blob.
     *
     * @param sha256    SHA-256 hash of the blob
     * @param model     RDF model to persist
     */
    public void saveMetadata(String sha256, Model model) {
        File metaFile = metadataFile(sha256);
        // Ensure parent directory exists
        File parent = metaFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try (FileWriter fw = new FileWriter(metaFile)) {
            model.write(fw, "TTL");
            fw.flush();
        } catch (IOException e) {
            throw new RuntimeException("Failed to write metadata to " + metaFile, e);
        }
    }

    /**
     * Build and save default RDF metadata for a newly uploaded blob.
     */
    public void saveDefaultMetadata(String blobFilepath, String sha256, String mimeType) {
        Model model = ModelFactory.createDefaultModel();
        String uri = blobUri(sha256);

        // Namespace declarations
        model.setNsPrefix(PREFIX_ATIC, NS_ATIC);
        model.setNsPrefix(PREFIX_PROV, NS_PROV);
        model.setNsPrefix("nfo", NFO.NS);

        // The blob resource
        Resource blobRes = model.getResource(uri);

        // Always declare as FileDataObject
        blobRes.addProperty(
            model.createProperty(NFO.FileDataObject.getURI()),
            NFO.FileDataObject
        );
        if (mimeType != null && mimeType.startsWith("image/")) {
            blobRes.addProperty(
                model.createProperty(NFO.FileDataObject.getURI()),
                NFO.Image
            );
        }
        // Use NFO vocab property for the filename
        blobRes.addProperty(NFO.fileName, new File(blobFilepath).getName());

        // sha256 and mimeType stored as raw properties (not in NFO vocabulary)
        blobRes.addProperty(
            model.createProperty(NS_ATIC + "sha256"),
            sha256
        );
        if (mimeType != null) {
            blobRes.addProperty(
                model.createProperty(NS_ATIC + "mimeType"),
                mimeType
            );
        }

        saveMetadata(sha256, model);
    }
    // =========================================================================

    /**
     * Save a binary blob to content-addressed storage.
     *
     * <p>Steps:
     * <ol>
     *   <li>Read content and compute SHA-256.</li>
     *   <li>Store bytes under content-addressed path.</li>
     *   <li>Write default RDF metadata (.ttl) alongside the original filepath.</li>
     *   <li>Insert/update pointer in {@code blobgraph} table.</li>
     * </ol>
     *
     * @param filepath        logical filepath (used as the pointer key)
     * @param originalFilename human-readable filename
     * @param mimeType         MIME type hint
     * @param content          input stream of the binary data
     * @param ctx              invocation context
     */
    public void save(String filepath, String originalFilename, String mimeType, InputStream content, InvocationContext ctx) {
        try {
            byte[] data = content.readAllBytes();
            String sha256 = sha256(data);
            String uri = blobUri(sha256);

            // Store bytes on disk
            writeBlob(sha256, data);

            // Save default RDF metadata as .ttl beside the logical filepath
            saveDefaultMetadata(filepath, sha256, mimeType);

            // Register in pointer table
            upsertPointer(filepath, uri, sha256);

            log.debug("Saved blob {} -> {} ({})", sha256, filepath, data.length);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save blob for " + filepath, e);
        }
    }

    /**
     * Delete a blob by its logical filepath.
     * Removes the pointer entry, the .ttl metadata, and the on-disk file.
     */
    public void delete(String filepath, InvocationContext ctx) {
        String sha256 = lookupSha256ByPath(filepath);
        if (sha256 == null) {
            return; // nothing to delete
        }
        deleteBlobFile(sha256);
        File metaFile = metadataFile(sha256);
        if (metaFile.isFile()) {
            metaFile.delete();
        }
        deletePointer(filepath);
        log.debug("Deleted blob {} -> {}", sha256, filepath);
    }

    /**
     * Load the binary bytes for a given filepath from content-addressed storage.
     *
     * @throws IllegalArgumentException if the filepath is not registered
     */
    public InputStream loadContent(String filepath, InvocationContext ctx) {
        String sha256 = lookupSha256ByPath(filepath);
        if (sha256 == null) {
            throw new IllegalStateException("File not found: " + filepath);
        }
        byte[] data = readBlob(sha256);
        if (data == null) {
            throw new IllegalStateException("Blob data missing for sha256: " + sha256);
        }
        return new java.io.ByteArrayInputStream(data);
    }

    /**
     * Get the URI for a given filepath from the pointer table.
     *
     * @throws IllegalStateException if filepath is not registered
     */
    public String getURI(String filepath, InvocationContext ctx) {
        String uri = lookupUriByPath(filepath);
        if (uri == null) {
            throw new IllegalStateException("File not found: " + filepath);
        }
        return uri;
    }

    /**
     * Get the SHA-256 for a given filepath.
     *
     * @throws IllegalStateException if filepath is not registered
     */
    public String getSHA256(String filepath, InvocationContext ctx) {
        String hash = lookupSha256ByPath(filepath);
        if (hash == null) {
            throw new IllegalStateException("File not found: " + filepath);
        }
        return hash;
    }

    /**
     * Get the filepath for a given SHA-256 hash.
     *
     * @throws IllegalStateException if sha256 is not registered
     */
    public String getPath(String sha256, InvocationContext ctx) {
        String path = lookupPathBySha256(sha256);
        if (path == null) {
            throw new IllegalStateException("Hash not found: " + sha256);
        }
        return path;
    }

    // =========================================================================
    //  Transformation / derived representations
    // =========================================================================

    /**
     * Check whether a derived representation already exists in the blob store.
     *
     * @param sourceSha256 the source blob's hash
     * @param width        target width (nullable)
     * @param height       target height (nullable)
     * @param format       output format
     * @param quality      quality factor
     * @param mode         resize mode
     * @return the derived blob's SHA-256 if it exists, or {@code null}
     */
    public String getDerivedBlobExists(String sourceSha256, Integer width, Integer height,
                                        String format, Integer quality, String mode) {
        // Derived blobs are stored with a synthetic URI that encodes the
        // transformation key.  We use the same urn:atic:blob: scheme.
        String transformKey = computeTransformationKey(sourceSha256, width, height, format, quality, mode);
        String derivedUri = NS_BLOB + transformKey;
        return lookupSha256ByUri(derivedUri);
    }

    /**
     * Register a derived representation in the pointer table.
     *
     * <p>The pointer maps a synthetic filepath (derived from the transformation key)
     * to the derived blob's URI. The RDF metadata (.ttl) records the derivation
     * relationship using PROV-O.
     *
     * @param sourceSha256  the source blob's SHA-256
     * @param derivedSha256 the derived blob's SHA-256 (actual content hash)
     * @param width         target width
     * @param height        target height
     * @param format        output format
     * @param quality       quality factor
     * @param mode          resize mode
     * @param filepath      logical filepath for the derived representation
     * @param ctx           invocation context
     */
    public void registerDerived(String sourceSha256, String derivedSha256,
                                 Integer width, Integer height,
                                 String format, Integer quality, String mode,
                                 String filepath, InvocationContext ctx) {
        String transformKey = computeTransformationKey(sourceSha256, width, height, format, quality, mode);
        String derivedUri = blobUri(derivedSha256);

        // Store the transform relationship in the URI column so we can look it up
        // We encode the source in the filepath so we can reverse-lookup
        String syntheticPath = filepath + "?src=" + sourceSha256 + "&w=" + width + "&h=" + height
                + "&fmt=" + format + "&q=" + quality + "&mode=" + mode;
        upsertPointer(syntheticPath, derivedUri, derivedSha256);

        // Write PROV-O metadata to the .ttl file
        saveDerivedMetadata(filepath, sourceSha256, derivedSha256, width, height, format, quality, mode);
    }

    /**
     * Write PROV-O derivation metadata to the .ttl file for a derived blob.
     */
    private void saveDerivedMetadata(String blobFilepath, String sourceSha256, String derivedSha256,
                                      Integer width, Integer height, String format,
                                      Integer quality, String mode) {
        Model model = ModelFactory.createDefaultModel();

        // Namespace declarations
        model.setNsPrefix(PREFIX_ATIC, NS_ATIC);
        model.setNsPrefix(PREFIX_PROV, NS_PROV);
        model.setNsPrefix("nfo", NFO.NS);

        String derivedUri = blobUri(derivedSha256);
        String sourceUri = blobUri(sourceSha256);

        Resource derivedRes = model.getResource(derivedUri);
        // Use NFO vocab resource for Image type
        derivedRes.addProperty(
            model.createProperty(NFO.FileDataObject.getURI()),
            NFO.Image
        );
        if (width != null) {
            derivedRes.addProperty(
                model.createProperty(NS_ATIC + "width"),
                model.createLiteral(String.valueOf(width))
            );
        }
        if (height != null) {
            derivedRes.addProperty(
                model.createProperty(NS_ATIC + "height"),
                model.createLiteral(String.valueOf(height))
            );
        }
        derivedRes.addProperty(
            model.createProperty(NS_ATIC + "format"),
            format
        );
        if (quality != null) {
            derivedRes.addProperty(
                model.createProperty(NS_ATIC + "quality"),
                model.createLiteral(String.valueOf(quality))
            );
        }
        derivedRes.addProperty(
            model.createProperty(NS_ATIC + "resizeMode"),
            mode
        );
        // PROV-O: wasDerivedFrom
        derivedRes.addProperty(
            model.createProperty(NS_PROV + "wasDerivedFrom"),
            model.getResource(sourceUri)
        );

        saveMetadata(derivedSha256, model);
    }

    /**
     * Request a derived (resized) representation of a blob.
     *
     * <p>Lazy generation strategy:
     * <ol>
     *   <li>Compute the transformation key.</li>
     *   <li>Check if the derived blob already exists (by transform key → actual hash lookup).</li>
     *   <li>If it exists, return its bytes immediately.</li>
     *   <li>If not, call {@link #generateDerived} to produce it, then return the bytes.</li>
     * </ol>
     *
     * <p><b>NOTE:</b> {@code generateDerived} is a no-op placeholder. In a real
     * implementation, this is where image processing (e.g. {@code Thumbnailator},
     * {@code Java2D}, or an external tool) would happen.
     *
     * @param sourceSha256 source blob hash
     * @param width        target width (0 = preserve aspect ratio)
     * @param height       target height (0 = preserve aspect ratio)
     * @param format       output format (e.g. "webp")
     * @param quality      quality (1-100, default 85)
     * @param mode         mode ("fit", "cover", "crop", "pad")
     * @param filepath     logical filepath for the derived representation
     * @param ctx          invocation context
     * @return input stream of the derived blob, or {@code null} if source not found
     */
    public InputStream getDerived(String sourceSha256, Integer width, Integer height,
                                   String format, Integer quality, String mode,
                                   String filepath, InvocationContext ctx) {
        // Check if derivation already exists
        String existingDerived = getDerivedBlobExists(sourceSha256, width, height, format, quality, mode);
        if (existingDerived != null) {
            byte[] data = readBlob(existingDerived);
            return data != null ? new java.io.ByteArrayInputStream(data) : null;
        }

        // Lazy generation: produce the derived representation
        String derivedSha256 = generateDerived(sourceSha256, width, height, format, quality, mode, filepath, ctx);
        if (derivedSha256 == null) {
            return null;
        }
        byte[] data = readBlob(derivedSha256);
        return data != null ? new java.io.ByteArrayInputStream(data) : null;
    }

    /**
     * Placeholder for the actual image derivation/generation step.
     *
     * <p>In production, this would read the source blob, apply the image
     * transformation (resize, crop, format conversion), write the result
     * to disk, compute its SHA-256, and register it.
     *
     * @return the derived blob's SHA-256, or {@code null} on failure
     */
    private String generateDerived(String sourceSha256, Integer width, Integer height,
                                    String format, Integer quality, String mode,
                                    String filepath, InvocationContext ctx) {
        // Read source
        byte[] sourceData = readBlob(sourceSha256);
        if (sourceData == null) {
            log.warn("Source blob {} not found for derivation", sourceSha256);
            return null;
        }

        // Compute transformation key
        String transformKey = computeTransformationKey(sourceSha256, width, height, format, quality, mode);

        // In a real implementation, image processing would happen here:
        //   BufferedImage original = ImageIO.read(new ByteArrayInputStream(sourceData));
        //   BufferedImage resized = transform(original, width, height, mode);
        //   ByteArrayOutputStream out = new ByteArrayOutputStream();
        //   ImageIO.write(resized, format, out);
        //   byte[] derivedData = out.toByteArray();
        // For the test suite we store a synthetic "derived" blob whose content
        // is the concatenation of source bytes + a transformation marker.
        // This allows us to test the full lifecycle without needing image libs.
        byte[] derivedData = synthesizeDerived(sourceData, transformKey);

        // Compute the real content hash of the derived blob
        String derivedSha256 = sha256(derivedData);

        // Store on disk
        writeBlob(derivedSha256, derivedData);

        // Register in pointer table and write PROV-O metadata
        registerDerived(sourceSha256, derivedSha256, width, height, format, quality, mode, filepath, ctx);

        log.debug("Generated derived blob {} from {} [{}x{}, {}]",
            derivedSha256, sourceSha256, width, height, format);
        return derivedSha256;
    }

    /**
     * Synthesize derived blob data for testing purposes.
     * In production, replace with actual image processing.
     */
    private byte[] synthesizeDerived(byte[] sourceData, String transformKey) {
        // Create a deterministic "derived" blob by prepending the transform key
        // and appending the source. This ensures content-addressing works.
        try {
            byte[] keyBytes = transformKey.getBytes("UTF-8");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(keyBytes);
            out.write('\n');
            out.write(sourceData);
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to synthesize derived data", e);
        }
    }

    /**
     * Compute the derived blob's filepath from a source filepath + transform params.
     */
    public String derivedFilePath(String sourceFilepath, Integer width, Integer height,
                                   String format, Integer quality, String mode) {
        // Derive a synthetic filepath for the pointer table
        String extension = new File(sourceFilepath).getName();
        int dotIdx = extension.lastIndexOf('.');
        String base = dotIdx > 0 ? extension.substring(0, dotIdx) : extension;
        String ext = dotIdx > 0 ? extension.substring(dotIdx) : "";
        String fmtExt = formatToExtension(format);
        return sourceFilepath.substring(0, sourceFilepath.lastIndexOf('/'))
                + "/" + base + "_" + width + "x" + height + fmtExt;
    }

    private String formatToExtension(String format) {
        return switch (format.toLowerCase()) {
            case "jpeg" -> ".jpg";
            case "png" -> ".png";
            case "webp" -> ".webp";
            default -> "." + format.toLowerCase();
        };
    }

    // =========================================================================
    //  Directory listing
    // =========================================================================

    /**
     * List blob files in a given folder path.
     * Searches the pointer table for entries whose filepath starts with the given prefix.
     */
    public List<String> list(String folderpath, InvocationContext ctx) {
        try {
            return db.read(
                "SELECT filepath FROM blobgraph WHERE filepath LIKE ? ORDER BY filepath",
                rs -> {
                    List<String> result = new ArrayList<>();
                    while (rs.next()) {
                        result.add(rs.getString("filepath"));
                    }
                    return result;
                },
                folderpath + "%"
            );
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list blobs under " + folderpath, e);
        }
    }

    // =========================================================================
    //  File operations: move, copy, link
    // =========================================================================

    /**
     * Move a blob from one filepath to another.
     * Updates the pointer table; the underlying blob bytes stay in place.
     */
    public void move(String srcFilepath, String trgFilepath, InvocationContext ctx) {
        String uri = lookupUriByPath(srcFilepath);
        String sha256 = lookupSha256ByPath(srcFilepath);
        if (uri == null || sha256 == null) {
            throw new IllegalArgumentException("Source filepath not found: " + srcFilepath);
        }

        // Update pointer table only; metadata is content-addressed (shared by all pointers)
        deletePointer(srcFilepath);
        upsertPointer(trgFilepath, uri, sha256);
    }

    /**
     * Copy a blob to a new filepath (shallow copy: same blob bytes, new pointer).
     */
    public void copy(String srcFilepath, String trgFilepath, InvocationContext ctx) {
        String uri = lookupUriByPath(srcFilepath);
        String sha256 = lookupSha256ByPath(srcFilepath);
        if (uri == null || sha256 == null) {
            throw new IllegalArgumentException("Source filepath not found: " + srcFilepath);
        }

        // Copy pointer only; metadata is content-addressed (shared by all pointers)
        upsertPointer(trgFilepath, uri, sha256);
    }

    /**
     * Create a symbolic link from one filepath to another.
     * Since blobgraph is content-addressed, this copies the pointer entry
     * (the underlying bytes are the same anyway).
     */
    public void link(String srcFilepath, String trgFilepath, InvocationContext ctx) {
        // For content-addressed storage, "linking" is essentially a copy
        // since there are no separate source/destination files on disk.
        copy(srcFilepath, trgFilepath, ctx);
    }

    // =========================================================================
    //  Triple-graph interface (AticGraph / Graph)
    // =========================================================================
    //
    //  BlobGraph also serves as a Jena Graph for querying blob metadata
    //  stored in the .ttl files.  Triple subjects are blob URIs; predicates
    //  and objects come from the associated RDF metadata.
    //
    //  Subject matching:
    //  - urn:atic:blob:{hash}  → load metadata for that exact hash
    //  - urn:atic:blob:path:{glob}  → glob for matching .ttl files
    //  - Node.ANY             → load all blob metadata
    // =========================================================================

    /**
     * Find triples for blob metadata by combining all matching .ttl graphs
     * into a single graph, then applying the BGP (s, p, o) on it.
     *
     * <p>Subject patterns:
     * <ul>
     *   <li>{@code Node.ANY} — iterate all registered blobs</li>
     *   <li>{@code urn:atic:blob:{sha256}} — exact blob identity, load its .ttl file</li>
     *   <li>{@code urn:atic:blob:path:{glob}} — glob pattern on filepath, load matching .ttl files</li>
     *   <li>{@code urn:atic:blob} (the {@code node} constant) — same as ANY</li>
     * </ul>
     */
    @Override
    public ExtendedIterator<Triple> find(Node s, Node p, Node o, InvocationContext ctx) {
        ctx = InvocationContext.fromContextIfEmpty(ctx, datasetGraph.getContext());

        // Combine all matching .ttl graphs into a single graph
        Graph combined = GraphFactory.createDefaultGraph();
        List<Graph> graphs = findMatchingGraphs(s);
        for (Graph g : graphs) {
            ExtendedIterator<Triple> iter = g.find(Node.ANY, Node.ANY, Node.ANY);
            try {
                while (iter.hasNext()) {
                    combined.add(iter.next());
                }
            } finally {
                iter.close();
            }
        }

        if (combined.isEmpty()) {
            return NiceIterator.emptyIterator();
        }
        return combined.find(s, p, o);
    }

    /**
     * Find graphs (from .ttl files) matching the given subject node.
     */
    private List<Graph> findMatchingGraphs(Node s) {
        List<Graph> graphs = new ArrayList<>();

        // All blobs: null, ANY, or the root node urn:atic:blob
        if (s == null || s.equals(Node.ANY) || s.equals(node)) {
            List<String> uris;
            try {
                uris = db.read(
                    "SELECT uri FROM blobgraph WHERE uri IS NOT NULL AND sha256 IS NOT NULL",
                    rs -> {
                        List<String> list = new ArrayList<>();
                        while (rs.next()) {
                            list.add(rs.getString("uri"));
                        }
                        return list;
                    }
                );
            } catch (SQLException e) {
                throw new RuntimeException("Failed to list blobs for graph iteration", e);
            }
            for (String uri : uris) {
                String hash = uri.substring(NS_BLOB.length());
                String filepath = lookupPathBySha256(hash);
                if (filepath != null) {
                    Model model = loadMetadata(filepath, InvocationContext.EMPTY);
                    graphs.add(model.getGraph());
                }
            }
        } else {
            String subjectUri = s.getURI();

            // Exact hash match: urn:atic:blob:{sha256}
            if (subjectUri.startsWith(NS_BLOB) && subjectUri.length() > NS_BLOB.length()) {
                String hash = subjectUri.substring(NS_BLOB.length());
                if (hash.length() == 64) {
                    String filepath = lookupPathBySha256(hash);
                    if (filepath != null) {
                        Model model = loadMetadata(filepath, InvocationContext.EMPTY);
                        graphs.add(model.getGraph());
                    }
                }
            }

            // Path glob: urn:atic:blob:path:{glob}
            if (subjectUri.startsWith(NS_BLOB + "path:")) {
                String globPattern = subjectUri.substring(NS_BLOB.length() + "path:".length());
                List<String> allUris;
                try {
                    allUris = db.read(
                        "SELECT uri FROM blobgraph WHERE sha256 IS NOT NULL",
                        rs -> {
                            List<String> list = new ArrayList<>();
                            while (rs.next()) {
                                list.add(rs.getString("uri"));
                            }
                            return list;
                        }
                    );
                } catch (SQLException e) {
                    throw new RuntimeException("Failed to glob-match filepaths: " + globPattern, e);
                }
                for (String uri : allUris) {
                    String hash = uri.substring(NS_BLOB.length());
                    String filepath = lookupPathBySha256(hash);
                    if (filepath != null && filepathMatchesGlob(filepath, globPattern)) {
                        Model model = loadMetadata(filepath, InvocationContext.EMPTY);
                        graphs.add(model.getGraph());
                    }
                }
            }
        }

        return graphs;
    }

    /**
     * Check if a filepath matches a simple glob pattern.
     * Supports {@code *} (matches anything except /) and {@code ?} (matches single char).
     */
    private boolean filepathMatchesGlob(String filepath, String glob) {
        String regex = glob
            .replace(".", "\\.")
            .replace("*", "[^/]*")
            .replace("?", "[^/]");
        return filepath.matches(regex);
    }

    @Override
    public boolean contains(Node s, Node p, Node o, InvocationContext ctx) {
        ctx = InvocationContext.fromContextIfEmpty(ctx, datasetGraph.getContext());
        ExtendedIterator<Triple> iter = find(s, p, o, ctx);
        try {
            return iter.hasNext();
        } finally {
            iter.close();
        }
    }

    @Override
    public int size(InvocationContext ctx) {
        ctx = InvocationContext.fromContextIfEmpty(ctx, datasetGraph.getContext());
        List<Map.Entry<String, String>> entries;
        try {
            entries = db.read(
                "SELECT uri FROM blobgraph WHERE uri IS NOT NULL",
                rs -> {
                    List<Map.Entry<String, String>> list = new ArrayList<>();
                    while (rs.next()) {
                        list.add(Map.entry(rs.getString("uri"), ""));
                    }
                    return list;
                }
            );
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count blobs", e);
        }
        int total = 0;
        for (Map.Entry<String, String> entry : entries) {
            String filepath = lookupPathBySha256(entry.getValue());
            if (filepath != null) {
                Model model = loadMetadata(filepath, InvocationContext.EMPTY);
                total += model.size();
            }
        }
        return total;
    }

    @Override
    public void add(Triple t, InvocationContext ctx) {
        throw new UnsupportedOperationException("BlobGraph is read-only for triple operations");
    }

    @Override
    public void remove(Node s, Node p, Node o, InvocationContext ctx) {
        throw new UnsupportedOperationException("BlobGraph is read-only for triple operations");
    }

    @Override
    public void delete(Triple t, InvocationContext ctx) {
        throw new UnsupportedOperationException("BlobGraph is read-only for triple operations");
    }

    @Override
    public void clear(InvocationContext ctx) {
        throw new UnsupportedOperationException("BlobGraph is read-only for triple operations");
    }

    @Override
    public boolean contains(Triple t, InvocationContext ctx) {
        return contains(t.getSubject(), t.getPredicate(), t.getObject(), ctx);
    }

    @Override
    public ExtendedIterator<Triple> find(Triple t, InvocationContext ctx) {
        return find(t.getSubject(), t.getPredicate(), t.getObject(), ctx);
    }

    @Override
    public boolean isEmpty(InvocationContext ctx) {
        return size(ctx) == 0;
    }

    @Override
    public void close(InvocationContext ctx) {
        // No persistent connection to close
    }

    @Override
    public boolean isClosed(InvocationContext ctx) {
        return false;
    }

    @Override
    public PrefixMapping getPrefixMapping(InvocationContext ctx) {
        return new PrefixMappingAdapter(datasetGraph.prefixes(ctx));
    }

    @Override
    public TransactionHandler getTransactionHandler() {
        return transactionHandler;
    }

    @Override
    public GraphEventManager getEventManager() {
        return graphEventManager;
    }

    @Override
    public boolean isIsomorphicWith(Graph g, InvocationContext ctx) {
        return GraphMatcher.equals(this, g);
    }

    @Override
    public boolean dependsOn(Graph other, InvocationContext ctx) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

}

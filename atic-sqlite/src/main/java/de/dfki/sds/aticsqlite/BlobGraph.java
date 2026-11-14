package de.dfki.sds.aticsqlite;

import de.dfki.sds.atic.jenatic.AticGraph;
import de.dfki.sds.atic.jenatic.InvocationContext;
import de.dfki.sds.atic.vocab.NFO;
import de.dfki.sds.atic.vocab.PROV;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
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
import org.apache.jena.sparql.graph.GraphFactory;
import org.apache.jena.sparql.graph.PrefixMappingAdapter;
import org.apache.jena.sparql.graph.TransactionHandlerNull;
import org.apache.jena.util.iterator.ExtendedIterator;
import org.apache.jena.util.iterator.NiceIterator;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.json.JSONObject;
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
 *   <li><b>Transformations</b>: Derived representations (e.g. resized or reformatted images) are
 *       stored with their own content-addressed SHA-256 identity.</li>
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

    //TODO later better way
    private static final String NS_ATIC_ONTO = "urn:atic:ontology:";
    public static final String PREFIX_ATIC = "atic";



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
    /*package*/ static String sha256(byte[] data) {
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
    /*package*/ static String sha256(InputStream is) throws IOException {
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
    /*package*/ String blobUri(String sha256) {
        return NS_BLOB + sha256;
    }

    /**
     * Read a blob from disk by its SHA-256 hash.
     * Streams the file — does not load the full content into memory.
     *
     * @return an InputStream over the blob file, or {@code null} if the blob does not exist
     */
    private InputStream readBlob(String sha256) {
        File f = blobPath(sha256);
        if (!f.isFile()) {
            return null;
        }
        try {
            return new FileInputStream(f);
        } catch (IOException e) {
            log.warn("Failed to read blob {}", sha256, e);
            return null;
        }
    }

    /**
     * Write data to disk under the content-addressed path.
     * Reads from the input stream — does not load the full content into memory.
     * Overwrites if the file already exists.
     */
    private void writeBlob(String sha256, InputStream in) throws IOException {
        ensureDir(sha256);
        File f = blobPath(sha256);
        try (FileOutputStream fos = new FileOutputStream(f)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                fos.write(buf, 0, n);
            }
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
     * Insert or update the pointer with mimeType.
     */
    private void upsertPointer(String filepath, String uri, String sha256, String mimeType, InvocationContext ctx) {
        long userId = ctx.getUserId();
        try {
            db.write(
                "INSERT INTO blobgraph (filepath, uri, sha256, mimeType, creator) "
                + "VALUES (?, ?, ?, ?, ?) "
                + "ON CONFLICT(filepath) DO UPDATE SET "
                + "uri = excluded.uri, "
                + "sha256 = excluded.sha256, "
                + "mimeType = excluded.mimeType, "
                + "creator = excluded.creator, "
                + "created_at = unixepoch()",
                filepath, uri, sha256, mimeType, userId
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
     * Look up the filepaths for a SHA-256 hash.
     * Multiple logical filepaths may point to the same content-addressed blob.
     *
     * @return list of filepath strings, empty if not found
     */
    private List<String> lookupPathsBySha256(String sha256) {
        try {
            return db.read(
                "SELECT filepath FROM blobgraph WHERE sha256 = ?",
                rs -> {
                    List<String> paths = new ArrayList<>();
                    while (rs.next()) {
                        paths.add(rs.getString("filepath"));
                    }
                    return paths;
                },
                sha256
            );
        } catch (SQLException e) {
            throw new RuntimeException("DB error looking up filepaths for sha256 " + sha256, e);
        }
    }

    /**
     * Extract the SHA-256 hash from a blob URI.
     * URIs are always in the form {@code urn:atic:blob:{sha256}}.
     *
     * @return hash string (64 hex chars), or {@code null} if the URI is invalid
     */
    private String lookupSha256ByUri(String uri) {
        if (uri == null || !uri.startsWith(NS_BLOB) || uri.length() <= NS_BLOB.length()) {
            return null;
        }
        return uri.substring(NS_BLOB.length());
    }

    /**
     * Look up the MIME type for a filepath.
     *
     * @return MIME type, or {@code null} if not set
     */
    private String lookupMimeTypeByPath(String filepath) {
        try {
            return db.read(
                "SELECT mimeType FROM blobgraph WHERE filepath = ?",
                rs -> rs.next() ? rs.getString("mimeType") : null,
                filepath
            );
        } catch (SQLException e) {
            throw new RuntimeException("DB error looking up mimeType for " + filepath, e);
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
     *
     * @param uri blob URI (e.g. {@code urn:atic:blob:{sha256}})
     */
    public Model loadMetadata(String uri, InvocationContext ctx) {
        String sha256 = lookupSha256ByUri(uri);
        if (sha256 == null) {
            throw new IllegalStateException("Blob not found: " + uri);
        }
        return loadMetadata(sha256);
    }

    /**
     * Load RDF metadata by SHA-256 hash (internal).
     */
    private Model loadMetadata(String sha256) {
        File metaFile = metadataFile(sha256);
        if (!metaFile.isFile()) {
            throw new IllegalStateException("Metadata not found for sha256: " + sha256);
        }
        try {
            Model model = ModelFactory.createDefaultModel();
            try (FileInputStream fis = new FileInputStream(metaFile)) {
                model.read(fis, null, "TTL");
            }
            return model;
        } catch (IOException e) {
            log.warn("Failed to load metadata from {}", metaFile, e);
            throw new RuntimeException("Failed to load metadata from " + metaFile, e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load metadata from " + metaFile, e);
        }
    }

    /**
     * Save RDF metadata to a .ttl file alongside the blob.
     *
     * @param uri   blob URI (e.g. {@code urn:atic:blob:{sha256}})
     * @param model RDF model to persist
     */
    public void saveMetadata(String uri, Model model) {
        String sha256 = lookupSha256ByUri(uri);
        if (sha256 == null) {
            throw new IllegalArgumentException("Invalid blob URI: " + uri);
        }
        saveMetadataByHash(sha256, model);
    }

    /**
     * Save RDF metadata by SHA-256 hash (internal).
     */
    private void saveMetadataByHash(String sha256, Model model) {
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
     * Set common namespace prefixes on a model (ATIC, PROV, NFO, RDFS, RDF).
     */
    private void setupPrefixes(Model model) {
        model.setNsPrefix(PREFIX_ATIC, NS_ATIC_ONTO);
        model.setNsPrefix("prov", PROV.uri);
        model.setNsPrefix("nfo", NFO.NS);
        model.setNsPrefix("rdfs", RDFS.uri);
        model.setNsPrefix("rdf", RDF.uri);
    }

    /**
     * Build and save default RDF metadata for a newly uploaded blob.
     */
    public void saveDefaultMetadata(String uri, String filename, String mimeType) {
        String sha256 = lookupSha256ByUri(uri);
        if (sha256 == null) {
            throw new IllegalArgumentException("Invalid blob URI: " + uri);
        }

        Model model = ModelFactory.createDefaultModel();
        setupPrefixes(model);

        // The blob resource
        Resource blobRes = model.getResource(uri);

        // Always declare as FileDataObject
        blobRes.addProperty(
            RDF.type,
            NFO.FileDataObject
        );
        if (mimeType != null && mimeType.startsWith("image/")) {
            blobRes.addProperty(
                RDF.type,
                NFO.Image
            );
        }
        blobRes.addProperty(NFO.fileName, filename);

        // sha256 and mimeType stored as raw properties (not in NFO vocabulary)
        blobRes.addProperty(
            model.createProperty(NS_ATIC_ONTO + "sha256"),
            sha256
        );
        if (mimeType != null) {
            blobRes.addProperty(
                model.createProperty(NS_ATIC_ONTO + "mimeType"),
                mimeType
            );
        }

        saveMetadataByHash(sha256, model);
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
    public void saveByPath(String filepath, String originalFilename, String mimeType, InputStream content, InvocationContext ctx) {
        try {
            byte[] data = content.readAllBytes();
            String sha256 = sha256(data);
            String uri = blobUri(sha256);

            // Store bytes on disk (stream via ByteArrayInputStream)
            writeBlob(sha256, new java.io.ByteArrayInputStream(data));

            // Save default RDF metadata as .ttl beside the logical filepath
            saveDefaultMetadata(uri, originalFilename, mimeType);

            // Register in pointer table
            upsertPointer(filepath, uri, sha256, mimeType, ctx);

            log.debug("Saved blob {} -> {} ({})", sha256, filepath, data.length);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save blob for " + filepath, e);
        }
    }

    /**
     * Save a binary blob using its content-addressed URI.
     * Skips pointer table registration since the blob is addressed by its URI.
     *
     * @param uri      blob URI (e.g. {@code urn:atic:blob:{sha256}})
     * @param mimeType MIME type hint
     * @param content  input stream of the binary data
     * @param ctx      invocation context
     */
    public void save(String uri, String mimeType, InputStream content, InvocationContext ctx) {
        String sha256 = lookupSha256ByUri(uri);
        if (sha256 == null) {
            throw new IllegalArgumentException("Invalid blob URI: " + uri);
        }
        try {
            byte[] data = content.readAllBytes();
            String computedHash = sha256(data);
            if (!computedHash.equals(sha256)) {
                throw new IllegalArgumentException(
                    "Computed SHA-256 (" + computedHash + ") does not match URI (" + uri + ")"
                );
            }

            // Store bytes on disk (stream via ByteArrayInputStream)
            writeBlob(sha256, new java.io.ByteArrayInputStream(data));

            log.debug("Saved blob {} ({})", sha256, data.length);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save blob " + uri, e);
        }
    }

    /**
     * Delete a blob by its content-addressed URI.
     *
     * @param uri blob URI (e.g. {@code urn:atic:blob:{sha256}})
     * @param ctx invocation context
     */
    public void delete(String uri, InvocationContext ctx) {
        String sha256 = lookupSha256ByUri(uri);
        if (sha256 == null) {
            return; // nothing to delete
        }
        doDelete(sha256, null);
        log.debug("Deleted blob {}", sha256);
    }

    /**
     * Delete a blob by its logical filepath.
     * Removes the pointer entry.  The underlying blob file and .ttl metadata
     * are only deleted when no other pointer references the same SHA-256.
     */
    public void deleteByPath(String filepath, InvocationContext ctx) {
        String sha256 = lookupSha256ByPath(filepath);
        if (sha256 == null) {
            return; // nothing to delete
        }
        delete(blobUri(sha256), filepath, ctx);
        log.debug("Deleted blob {} -> {}", sha256, filepath);
    }

    /**
     * Delete a blob by URI and optional filepath.
     * If filepath is provided, only removes that specific pointer and the underlying
     * blob file/metadata are deleted only when no other pointer references the same SHA-256.
     *
     * @param uri      blob URI (e.g. {@code urn:atic:blob:{sha256}})
     * @param filepath logical filepath of the pointer to remove, or {@code null} for unconditional deletion
     * @param ctx      invocation context
     */
    private void delete(String uri, String filepath, InvocationContext ctx) {
        String sha256 = lookupSha256ByUri(uri);
        if (sha256 == null) {
            return;
        }
        doDelete(sha256, filepath);
    }

    /**
     * Core deletion logic for blob files and metadata.
     *
     * @param sha256   SHA-256 hash of the blob
     * @param filepath logical filepath of the pointer to remove, or {@code null} for unconditional deletion
     */
    private void doDelete(String sha256, String filepath) {
        if (filepath != null) {
            // Count remaining pointers to this SHA-256 (excluding the one we're about to remove)
            int refCount;
            try {
                refCount = db.read(
                    "SELECT COUNT(*) AS cnt FROM blobgraph WHERE sha256 = ? AND filepath != ?",
                    rs -> rs.next() ? rs.getInt("cnt") : 0,
                    sha256, filepath
                );
            } catch (SQLException e) {
                throw new RuntimeException("Failed to count pointers for " + sha256, e);
            }
            // Only delete underlying file/metadata when last pointer
            if (refCount == 0) {
                deleteBlobFile(sha256);
                File metaFile = metadataFile(sha256);
                if (metaFile.isFile()) {
                    metaFile.delete();
                }
            }
            deletePointer(filepath);
        } else {
            // Unconditional deletion (called via delete(uri, ctx))
            deleteBlobFile(sha256);
            File metaFile = metadataFile(sha256);
            if (metaFile.isFile()) {
                metaFile.delete();
            }
        }
    }

    /**
     * Load the binary bytes for a content-addressed blob URI.
     *
     * @throws IllegalStateException if the blob data is missing
     */
    public InputStream loadContent(String uri, InvocationContext ctx) {
        String sha256 = lookupSha256ByUri(uri);
        if (sha256 == null) {
            throw new IllegalStateException("Blob not found: " + uri);
        }
        return loadBlob(sha256, "blob data");
    }

    /**
     * Load the binary bytes for a given filepath from content-addressed storage.
     *
     * @throws IllegalStateException if the filepath is not registered
     */
    public InputStream loadContentByPath(String filepath, InvocationContext ctx) {
        String sha256 = lookupSha256ByPath(filepath);
        if (sha256 == null) {
            throw new IllegalStateException("File not found: " + filepath);
        }
        String uri = blobUri(sha256);
        return loadContent(uri, ctx);
    }

    /**
     * Read the blob bytes from disk, validating the file exists.
     *
     * @param sha256 SHA-256 hash of the blob
     * @param context human-readable context for the error message ("blob data", etc.)
     * @return InputStream over the blob file
     * @throws IllegalStateException if the blob file is missing on disk
     */
    private InputStream loadBlob(String sha256, String context) {
        InputStream is = readBlob(sha256);
        if (is == null) {
            throw new IllegalStateException(context + " missing for sha256: " + sha256);
        }
        return is;
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
     * Get all filepaths for a given SHA-256 hash.
     *
     * @return list of filepaths (may be empty if the hash is not registered)
     */
    public List<String> getPaths(String sha256, InvocationContext ctx) {
        return lookupPathsBySha256(sha256);
    }

    /**
     * Get the MIME type for a given filepath from the pointer table.
     *
     * @return MIME type, or {@code null} if not set
     */
    public String getMimeType(String filepath, InvocationContext ctx) {
        return lookupMimeTypeByPath(filepath);
    }

    // =========================================================================
    //  Image operations: resize, reformat
    // =========================================================================

    /**
     * Resize an image to the given dimensions.
     * If only width is set, height is derived to maintain aspect ratio.
     * If only height is set, width is derived to maintain aspect ratio.
     * If neither is set, a copy is returned with no resizing.
     *
     * @param srcFilepath   logical filepath of the source image
     * @param targetWidth   target width (nullable)
     * @param targetHeight  target height (nullable)
     * @param trgFilepath   logical filepath for the resized representation
     * @param ctx           invocation context
     * @return SHA-256 of the resized blob
     * @throws IllegalStateException if the source blob is not found or is not a valid image
     */
    public String resize(String srcFilepath, Integer targetWidth, Integer targetHeight,
                         String trgFilepath, InvocationContext ctx) {
        String sourceSha256 = lookupSha256ByPath(srcFilepath);
        if (sourceSha256 == null) {
            throw new IllegalStateException("Source filepath not found: " + srcFilepath);
        }

        BufferedImage original = readImage(sourceSha256);
        if (original == null) {
            throw new IllegalStateException("Source blob is not a valid image: " + srcFilepath);
        }

        // Determine target dimensions
        int tw = targetWidth != null ? targetWidth : original.getWidth();
        int th = targetHeight != null ? targetHeight : original.getHeight();

        // Aspect ratio: if one dimension is null, derive it
        if (targetWidth == null && targetHeight != null) {
            tw = (targetHeight * original.getWidth()) / original.getHeight();
        } else if (targetHeight == null && targetWidth != null) {
            th = (targetWidth * original.getHeight()) / original.getWidth();
        }

        BufferedImage resized = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = resized.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.drawImage(original, 0, 0, tw, th, null);
        g2.dispose();

        // Encode to output format
        String extension = getFileExtension(trgFilepath);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(resized, extension, out)) {
                throw new IllegalStateException("Unsupported output format: " + extension);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to encode image to " + extension, e);
        }

        byte[] outputBytes = out.toByteArray();
        String derivedSha256 = sha256(outputBytes);
        try {
            writeBlob(derivedSha256, new java.io.ByteArrayInputStream(outputBytes));
        } catch (IOException e) {
            throw new RuntimeException("Failed to write resized blob", e);
        }

        // Register with PROV-O metadata
        saveDerivedMetadata(trgFilepath, sourceSha256, derivedSha256, tw, th);

        log.debug("Resized {} -> {}x{} (sha256={})", srcFilepath, tw, th, derivedSha256);
        return derivedSha256;
    }

    /**
     * Resize an image to the given dimensions, auto-generating the target filepath
     * by appending {@code -WxH.png} before the extension.
     *
     * <p>Example: {@code photos/vacation.jpg} → {@code photos/vacation-800x600.png}
     *
     * @param srcFilepath  logical filepath of the source image
     * @param targetWidth  target width in pixels
     * @param targetHeight target height in pixels
     * @param ctx          invocation context
     * @return SHA-256 of the resized blob
     * @throws IllegalStateException if the source blob is not found or is not a valid image
     */
    public String resize(String srcFilepath, Integer targetWidth, Integer targetHeight, InvocationContext ctx) {
        int tw = targetWidth != null ? targetWidth : 0;
        int th = targetHeight != null ? targetHeight : 0;
        String trgFilepath = deriveResizePath(srcFilepath, tw, th);
        return resize(srcFilepath, targetWidth, targetHeight, trgFilepath, ctx);
    }

    /**
     * Reformat an image to a different format (JPEG or PNG).
     * The image dimensions are preserved.
     *
     * @param srcFilepath  logical filepath of the source image
     * @param format       target format ("jpg" or "png")
     * @param trgFilepath  logical filepath for the reformatted representation
     * @param ctx          invocation context
     * @return SHA-256 of the reformatted blob
     * @throws IllegalStateException if the source blob is not found or is not a valid image
     */
    public String reformat(String srcFilepath, String format, String trgFilepath, InvocationContext ctx) {
        String sourceSha256 = lookupSha256ByPath(srcFilepath);
        if (sourceSha256 == null) {
            throw new IllegalStateException("Source filepath not found: " + srcFilepath);
        }

        BufferedImage original = readImage(sourceSha256);
        if (original == null) {
            throw new IllegalStateException("Source blob is not a valid image: " + srcFilepath);
        }

        // Convert to RGB if needed (e.g. for JPEG which doesn't support alpha)
        BufferedImage image = original;
        if (format.equals("jpg") && original.getType() != BufferedImage.TYPE_INT_RGB) {
            image = new BufferedImage(original.getWidth(), original.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g2 = image.createGraphics();
            g2.drawImage(original, 0, 0, null);
            g2.dispose();
        }

        // Encode to output format
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(image, format, out)) {
                throw new IllegalStateException("Unsupported output format: " + format);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to encode image to " + format, e);
        }

        byte[] outputBytes = out.toByteArray();
        String derivedSha256 = sha256(outputBytes);
        try {
            writeBlob(derivedSha256, new java.io.ByteArrayInputStream(outputBytes));
        } catch (IOException e) {
            throw new RuntimeException("Failed to write reformatted blob", e);
        }

        // Register with PROV-O metadata
        saveDerivedMetadata(trgFilepath, sourceSha256, derivedSha256, original.getWidth(), original.getHeight());

        log.debug("Reformatted {} -> {} (sha256={})", srcFilepath, format, derivedSha256);
        return derivedSha256;
    }

    /**
     * Reformat an image to a different format, auto-swapping the file extension.
     *
     * <p>Example: {@code photo.png} → {@code photo.jpg}
     *
     * @param srcFilepath logical filepath of the source image
     * @param format      target format ("jpg" or "png")
     * @param ctx         invocation context
     * @return SHA-256 of the reformatted blob
     * @throws IllegalStateException if the source blob is not found or is not a valid image
     */
    public String reformat(String srcFilepath, String format, InvocationContext ctx) {
        String trgFilepath = swapExtension(srcFilepath, format);
        return reformat(srcFilepath, format, trgFilepath, ctx);
    }

    /**
     * Derive a target filepath for a resized image: {@code base-WxH.ext}.
     */
    private String deriveResizePath(String srcFilepath, int width, int height) {
        int dotIdx = srcFilepath.lastIndexOf('.');
        String base = dotIdx > 0 ? srcFilepath.substring(0, dotIdx) : srcFilepath;
        String ext = dotIdx > 0 ? srcFilepath.substring(dotIdx) : ".png";
        return base + "-" + width + "x" + height + ext;
    }

    /**
     * Swap the file extension of a filepath.
     * Example: {@code photo.png} → {@code photo.jpg}
     */
    private String swapExtension(String srcFilepath, String newExt) {
        int dotIdx = srcFilepath.lastIndexOf('.');
        return dotIdx > 0
                ? srcFilepath.substring(0, dotIdx) + "." + newExt
                : srcFilepath + "." + newExt;
    }

    /**
     * Read a blob as a BufferedImage. Returns null if not found or not a valid image.
     */
    private BufferedImage readImage(String sha256) {
        InputStream is = readBlob(sha256);
        if (is == null) {
            return null;
        }
        try {
            return ImageIO.read(is);
        } catch (IOException e) {
            log.warn("Failed to read image for sha256 {}", sha256, e);
            return null;
        }
    }

    /**
     * Write derived representation metadata to the .ttl file with PROV-O derivation info.
     */
    private void saveDerivedMetadata(String blobFilepath, String sourceSha256, String derivedSha256,
                                     int width, int height) {
        Model model = ModelFactory.createDefaultModel();
        setupPrefixes(model);

        String derivedUri = blobUri(derivedSha256);
        String sourceUri = blobUri(sourceSha256);

        Resource derivedRes = model.getResource(derivedUri);
        derivedRes.addProperty(
            model.createProperty(NFO.FileDataObject.getURI()),
            NFO.Image
        );
        derivedRes.addProperty(
            model.createProperty(NS_ATIC_ONTO + "width"),
            model.createLiteral(String.valueOf(width))
        );
        derivedRes.addProperty(
            model.createProperty(NS_ATIC_ONTO + "height"),
            model.createLiteral(String.valueOf(height))
        );
        derivedRes.addProperty(NFO.fileName, new File(blobFilepath).getName());
        // PROV-O: wasDerivedFrom
        derivedRes.addProperty(
            PROV.wasDerivedFrom,
            model.getResource(sourceUri)
        );

        saveMetadata(derivedUri, model);
    }

    /**
     * Get the file extension from a filepath (without leading dot).
     */
    private String getFileExtension(String filepath) {
        int dotIdx = filepath.lastIndexOf('.');
        return dotIdx > 0 ? filepath.substring(dotIdx + 1).toLowerCase() : "png";
    }

    // =========================================================================
    //  Directory listing
    // =========================================================================

    /**
     * List direct children (files and subfolders) of a given folder path.
     * Searches the pointer table for entries whose filepath falls directly under
     * the given folder — does NOT recurse into subdirectories.
     *
     * @param folderpath folder to list; empty string or "/" means root
     * @return sorted list of {@link JSONObject}, each with keys:
     *         {@code path}, {@code filename}, {@code isFolder}
     */
    public List<JSONObject> list(String folderpath, InvocationContext ctx) {
        // Normalize: strip trailing slashes, treat "/" as root (same as "")
        String fp = folderpath;
        while (fp.length() > 1 && fp.endsWith("/")) {
            fp = fp.substring(0, fp.length() - 1);
        }
        if (fp.equals("/")) {
            fp = "";
        }

        // Gather all matching paths first
        List<String> allPaths;
        try {
            allPaths = db.read(
                "SELECT filepath FROM blobgraph WHERE filepath LIKE ? ORDER BY filepath",
                rs -> {
                    List<String> result = new ArrayList<>();
                    while (rs.next()) {
                        result.add(rs.getString("filepath"));
                    }
                    return result;
                },
                fp.isEmpty() ? "%" : fp + "/%"
            );
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list blobs under " + folderpath, e);
        }

        // Collect unique direct child names and determine if they're folders or files.
        // Paths are stored like "/photos/a.jpg" or "/readme.md", so strip leading "/".
        java.util.LinkedHashMap<String, JSONObject> childMap = new java.util.LinkedHashMap<>();
        String prefix = fp.isEmpty() ? "/" : fp + "/";

        for (String p : allPaths) {
            // p is like "/photos/a.jpg" or "/readme.md"
            String remainder = p.startsWith(prefix) ? p.substring(prefix.length()) : p.substring(prefix.length() - 1);
            // Remove leading "/" from remainder if it survived (shouldn't, but be safe)
            if (remainder.startsWith("/")) {
                remainder = remainder.substring(1);
            }
            int slash = remainder.indexOf('/');
            if (slash < 0) {
                // Direct file child — remainder is the filename
                String filename = remainder;
                String childPath = fp.isEmpty() ? "/" + filename : fp + "/" + filename;
                JSONObject entry = new JSONObject();
                entry.put("path", childPath);
                entry.put("filename", filename);
                entry.put("isFolder", false);
                childMap.put(filename, entry);
            } else {
                // First segment is an implicit folder
                String folderName = remainder.substring(0, slash);
                String childPath = fp.isEmpty() ? "/" + folderName : fp + "/" + folderName;
                JSONObject entry = new JSONObject();
                entry.put("path", childPath);
                entry.put("filename", folderName);
                entry.put("isFolder", true);
                childMap.merge(folderName, entry, (old, nw) -> nw); // folders always win
            }
        }

        // Build sorted list: folders first, then files, both alphabetically by filename
        List<JSONObject> children = new ArrayList<>(childMap.values());
        children.sort((a, b) -> {
            boolean aFolder = a.optBoolean("isFolder");
            boolean bFolder = b.optBoolean("isFolder");
            if (aFolder != bFolder) {
                return aFolder ? -1 : 1;
            }
            return a.getString("filename").compareTo(b.getString("filename"));
        });
        return children;
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
        String mimeType = getMimeType(srcFilepath, ctx);
        if (uri == null || sha256 == null) {
            throw new IllegalArgumentException("Source filepath not found: " + srcFilepath);
        }

        // Update pointer table only; metadata is content-addressed (shared by all pointers)
        deletePointer(srcFilepath);
        upsertPointer(trgFilepath, uri, sha256, mimeType, ctx);
    }

    /**
     * Copy a blob to a new filepath (shallow copy: same blob bytes, new pointer).
     */
    public void copy(String srcFilepath, String trgFilepath, InvocationContext ctx) {
        String uri = lookupUriByPath(srcFilepath);
        String sha256 = lookupSha256ByPath(srcFilepath);
        String mimeType = getMimeType(srcFilepath, ctx);
        if (uri == null || sha256 == null) {
            throw new IllegalArgumentException("Source filepath not found: " + srcFilepath);
        }

        // Copy pointer and mimeType; metadata is content-addressed (shared by all pointers)
        upsertPointer(trgFilepath, uri, sha256, mimeType, ctx);
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

        // Resolve path-based subject URI to SHA256-based URI for filtering
        Node filterSubject = s;
        if (s != null && !s.equals(Node.ANY)) {
            String uri = s.getURI();
            if (uri != null && uri.startsWith(NS_BLOB) && uri.length() > NS_BLOB.length()) {
                String path = uri.substring(NS_BLOB.length());
                // Not a hash (64 hex chars) and not a glob pattern — treat as path
                if (path.length() != 64 && !uri.startsWith(NS_BLOB + "path:")) {
                    String filepath = decodePathUri(uri);
                    if (filepath != null) {
                        String sha256 = lookupSha256ByPath(filepath);
                        if (sha256 != null) {
                            filterSubject = NodeFactory.createURI(blobUri(sha256));
                        }
                    }
                }
            }
        }

        // Combine all matching .ttl graphs into a single graph
        Graph combined = GraphFactory.createDefaultGraph();
        List<Graph> graphs = findMatchingGraphs(filterSubject);
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
        return combined.find(filterSubject, p, o);
    }

    /**
     * Load metadata for a given SHA-256 hash and add its triples to the graph list.
     * Skips silently if no metadata file exists (graceful degradation).
     */
    private void addMetadataForHash(List<Graph> graphs, String sha256) {
        File metaFile = metadataFile(sha256);
        if (!metaFile.isFile()) {
            return;
        }
        try (FileInputStream fis = new FileInputStream(metaFile)) {
            Model model = ModelFactory.createDefaultModel();
            model.read(fis, null, "TTL");
            graphs.add(model.getGraph());
        } catch (IOException e) {
            log.warn("Failed to read metadata for {}", sha256, e);
        }
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
                addMetadataForHash(graphs, hash);
            }
        } else {
            String subjectUri = s.getURI();

            // Exact hash match: urn:atic:blob:{sha256}
            if (subjectUri.startsWith(NS_BLOB) && subjectUri.length() > NS_BLOB.length()) {
                String hash = subjectUri.substring(NS_BLOB.length());
                if (hash.length() == 64) {
                    addMetadataForHash(graphs, hash);
                } else {
                    // Path-based URI: urn:atic:blob:{filepath}
                    // Look up the filepath in the pointer table
                    String filepath = decodePathUri(subjectUri);
                    if (filepath != null) {
                        String sha256 = lookupSha256ByPath(filepath);
                        if (sha256 != null) {
                            addMetadataForHash(graphs, sha256);
                        }
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
                    List<String> filepaths = lookupPathsBySha256(hash);
                    for (String filepath : filepaths) {
                        if (filepathMatchesGlob(filepath, globPattern)) {
                            addMetadataForHash(graphs, hash);
                            break; // match on any one filepath is enough
                        }
                    }
                }
            }
        }

        return graphs;
    }

    /**
     * Decode a path-based URI to a filepath.
     *
     * <p>URIs like {@code urn:atic:blob:graph/f.jpg} are decoded by taking everything
     * after the {@code NS_BLOB} prefix and URL-decoding the path component.
     */
    private String decodePathUri(String uri) {
        if (!uri.startsWith(NS_BLOB) || uri.length() <= NS_BLOB.length()) {
            return null;
        }
        // Extract path part and URL-decode it
        String encodedPath = uri.substring(NS_BLOB.length());
        try {
            return java.net.URLDecoder.decode(encodedPath, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Failed to decode path URI: {}", uri, e);
            return null;
        }
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
        List<String> uris;
        try {
            uris = db.read(
                "SELECT uri FROM blobgraph WHERE uri IS NOT NULL",
                rs -> {
                    List<String> list = new ArrayList<>();
                    while (rs.next()) {
                        list.add(rs.getString("uri"));
                    }
                    return list;
                }
            );
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count blobs", e);
        }
        int total = 0;
        for (String uri : uris) {
            // URI is urn:atic:blob:{sha256}, extract the hash
            String sha256 = uri.startsWith(NS_BLOB) ? uri.substring(NS_BLOB.length()) : null;
            if (sha256 != null) {
                // TTL file is content-addressed: {sha[0:2]}/{sha[2:4]}/{sha[4:6]}/{sha}.ttl
                File metaFile = metadataFile(sha256);
                if (metaFile.isFile()) {
                    try (FileInputStream fis = new FileInputStream(metaFile)) {
                        Model m = ModelFactory.createDefaultModel();
                        m.read(fis, null, "TTL");
                        total += m.size();
                    } catch (IOException e) {
                        log.warn("Failed to read metadata for {} from {}", sha256, metaFile, e);
                    }
                }
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

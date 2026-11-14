package de.dfki.sds.aticsqlite;

import de.dfki.sds.atic.ac.User;
import de.dfki.sds.atic.ac.UserGroupManagement;
import de.dfki.sds.atic.jenatic.InvocationContext;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.util.iterator.ExtendedIterator;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Simple unit tests for BlobGraph: save, load, move, copy, link, delete.
 */
class BlobGraphUnitTest {

    private SqliteAticDatasetGraph dataset;
    private BlobGraph blobGraph;
    private InvocationContext adminCtx;
    private Path tempDir;

    @BeforeEach
    void setup(@TempDir Path tempDir) throws Exception {
        this.tempDir = tempDir;
        dataset = TL.createDatasetGraph(tempDir);
        blobGraph = dataset.getBlobGraph();

        dataset.executeWrite(() -> {
            dataset.addUser("Alice", "Doe", "alice.doe@example.com", "alice", InvocationContext.EMPTY);
        });

        User adminUser = dataset.calculateRead(() ->
            dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY)
        );
        adminCtx = new InvocationContext.Builder().fromUser(adminUser).build();
    }

    // =========================================================================
    //  SHA-256
    // =========================================================================

    @Test
    @DisplayName("sha256: same content produces same hash")
    void sha256Deterministic() {
        byte[] data = "hello".getBytes();
        assertEquals(BlobGraph.sha256(data), BlobGraph.sha256(data));
    }

    @Test
    @DisplayName("sha256: different content produces different hash")
    void sha256DifferentContent() {
        assertNotEquals(BlobGraph.sha256("a".getBytes()), BlobGraph.sha256("b".getBytes()));
    }

    @Test
    @DisplayName("sha256: stream hash matches byte array hash")
    void sha256StreamVsBytes() throws Exception {
        byte[] data = "test".getBytes();
        assertEquals(BlobGraph.sha256(data), BlobGraph.sha256(new ByteArrayInputStream(data)));
    }

    // =========================================================================
    //  Blob Graph constants
    // =========================================================================

    @Test
    @DisplayName("NS_BLOB has correct value")
    void nsBlobConstant() {
        assertEquals("urn:atic:blob:", BlobGraph.NS_BLOB);
    }

    @Test
    @DisplayName("blobUri produces urn:atic:blob:{sha256}")
    void blobUriFormat() {
        String uri = blobGraph.blobUri("abc123");
        assertEquals("urn:atic:blob:abc123", uri);
    }

    @Test
    @DisplayName("blobPath creates 3-folder-level hierarchical path")
    void blobPathLayout() {
        String sha = "aabbccddeeff0123456789aabbccddeeff0123456789aabbccddeeff0123456789";
        File f = blobGraph.blobPath(sha);
        String rel = f.getPath().substring(blobGraph.rootFolder.getPath().length() + 1);
        String[] parts = rel.split("/");
        assertEquals(4, parts.length);
        assertEquals(sha.substring(0, 2), parts[0]);
        assertEquals(sha.substring(2, 4), parts[1]);
        assertEquals(sha.substring(4, 6), parts[2]);
        assertEquals(sha, parts[3]);
    }

    // =========================================================================
    //  Save & Load
    // =========================================================================

    @Test
    @DisplayName("save + getURI returns correct URI")
    void saveAndGetUri() {
        dataset.executeWrite(() -> {
            blobGraph.saveByPath("img/a.jpg", "a.jpg", "image/jpeg",
                new ByteArrayInputStream("image data".getBytes()), adminCtx);
            String uri = blobGraph.getURI("img/a.jpg", adminCtx);
            assertNotNull(uri);
            assertTrue(uri.startsWith("urn:atic:blob:"));
        });
    }

    @Test
    @DisplayName("save + getSHA256 returns correct hash")
    void saveAndGetSha256() {
        dataset.executeWrite(() -> {
            blobGraph.saveByPath("img/b.jpg", "b.jpg", "image/jpeg",
                new ByteArrayInputStream("image data".getBytes()), adminCtx);
            String hash = blobGraph.getSHA256("img/b.jpg", adminCtx);
            assertEquals(BlobGraph.sha256("image data".getBytes()), hash);
        });
    }

    @Test
    @DisplayName("save + loadContent returns original bytes")
    void saveAndLoad() {
        dataset.executeWrite(() -> {
            byte[] content = "original bytes".getBytes();
            blobGraph.saveByPath("img/c.jpg", "c.jpg", "image/jpeg",
                new ByteArrayInputStream(content), adminCtx);
            InputStream is = blobGraph.loadContentByPath("img/c.jpg", adminCtx);
            assertNotNull(is);
            try {
                assertArrayEquals(content, is.readAllBytes());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    @DisplayName("loadContent throws for unregistered file")
    void loadNonExistent() {
        assertThrows(IllegalStateException.class, () ->
            dataset.calculateRead(() -> blobGraph.loadContentByPath("/no/such/file.jpg", adminCtx)));
    }

    @Test
    @DisplayName("getURI throws for unregistered file")
    void getUriNotRegistered() {
        assertThrows(IllegalStateException.class, () ->
            dataset.calculateRead(() -> blobGraph.getURI("/no/such/file.jpg", adminCtx)));
    }

    @Test
    @DisplayName("save stores blob at content-addressed filesystem path")
    void storageLayout() {
        dataset.executeWrite(() -> {
            byte[] content = "layout test".getBytes();
            blobGraph.saveByPath("img/layout.jpg", "layout.jpg", "image/jpeg",
                new ByteArrayInputStream(content), adminCtx);
            String hash = BlobGraph.sha256(content);
            File stored = blobGraph.blobPath(hash);
            assertTrue(stored.isFile());
            try {
                assertArrayEquals(content, Files.readAllBytes(stored.toPath()));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    @DisplayName("same content saved twice shares same hash")
    void deduplication() {
        dataset.executeWrite(() -> {
            byte[] content = "dedup".getBytes();
            blobGraph.saveByPath("a.txt", "a.txt", "text/plain",
                new ByteArrayInputStream(content), adminCtx);
            blobGraph.saveByPath("b.txt", "b.txt", "text/plain",
                new ByteArrayInputStream(content), adminCtx);
            assertEquals(blobGraph.getSHA256("a.txt", adminCtx),
                         blobGraph.getSHA256("b.txt", adminCtx));
        });
    }

    @Test
    @DisplayName("re-save same content is idempotent")
    void idempotentSave() {
        dataset.executeWrite(() -> {
            byte[] content = "idem".getBytes();
            String expected = BlobGraph.sha256(content);
            blobGraph.saveByPath("img/idem.jpg", "idem.jpg", "image/jpeg",
                new ByteArrayInputStream(content), adminCtx);
            blobGraph.saveByPath("img/idem.jpg", "idem.jpg", "image/jpeg",
                new ByteArrayInputStream(content), adminCtx);
            assertEquals(expected, blobGraph.getSHA256("img/idem.jpg", adminCtx));
        });
    }

    @Test
    @DisplayName("re-save different content overwrites")
    void overwriteSave() {
        dataset.executeWrite(() -> {
            blobGraph.saveByPath("img/ov.jpg", "ov.jpg", "image/jpeg",
                new ByteArrayInputStream("old".getBytes()), adminCtx);
            blobGraph.saveByPath("img/ov.jpg", "ov.jpg", "image/jpeg",
                new ByteArrayInputStream("new".getBytes()), adminCtx);
            InputStream is = blobGraph.loadContentByPath("img/ov.jpg", adminCtx);
            try {
                assertArrayEquals("new".getBytes(), is.readAllBytes());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    // =========================================================================
    //  Pointer table
    // =========================================================================

    @Test
    @DisplayName("getPaths resolves SHA-256 to filepaths")
    void lookupBySha256() {
        dataset.executeWrite(() -> {
            byte[] content = "lookup".getBytes();
            blobGraph.saveByPath("ptr/file.txt", "file.txt", "text/plain",
                new ByteArrayInputStream(content), adminCtx);
            String hash = BlobGraph.sha256(content);
            List<String> paths = blobGraph.getPaths(hash, adminCtx);
            assertEquals(1, paths.size());
            assertEquals("ptr/file.txt", paths.get(0));
        });
    }

    @Test
    @DisplayName("getPaths returns empty list for unknown hash")
    void lookupUnknownSha256() {
        List<String> paths = dataset.calculateRead(() ->
            blobGraph.getPaths(
                "nonexistent123456789012345678901234567890123456789012345678901234567890",
                adminCtx));
        assertTrue(paths.isEmpty());
    }

    // =========================================================================
    //  Metadata
    // =========================================================================

    @Test
    @DisplayName("save writes .ttl metadata file")
    void savesMetadata() {
        dataset.executeWrite(() -> {
            blobGraph.saveByPath("meta/m.jpg", "m.jpg", "image/jpeg",
                new ByteArrayInputStream("meta".getBytes()), adminCtx);
            String uri = blobGraph.getURI("meta/m.jpg", adminCtx);
            Model model = blobGraph.loadMetadata(uri, adminCtx);
            assertNotNull(model);
            assertTrue(model.size() > 0);
        });
    }

    @Test
    @DisplayName("loadMetadata throws for unregistered file")
    void loadMetadataThrows() {
        assertThrows(IllegalStateException.class, () ->
            blobGraph.loadMetadata("urn:atic:blob:nonexistent", adminCtx));
    }

    // =========================================================================
    //  Move, Copy, Link
    // =========================================================================

    @Test
    @DisplayName("move updates pointer to new path")
    void moveFile() throws Exception {
        dataset.executeWrite(() -> {
            byte[] content = "move".getBytes();
            blobGraph.saveByPath("src/a.txt", "a.txt", "text/plain",
                new ByteArrayInputStream(content), adminCtx);

            blobGraph.move("src/a.txt", "dst/a.txt", adminCtx);

            // src no longer exists
            assertThrows(IllegalStateException.class, () -> blobGraph.getURI("src/a.txt", adminCtx));
            // dst exists
            assertNotNull(blobGraph.getURI("dst/a.txt", adminCtx));
            InputStream is = blobGraph.loadContentByPath("dst/a.txt", adminCtx);
            try {
                assertArrayEquals(content, is.readAllBytes());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    @DisplayName("copy creates new pointer to same blob")
    void copyFile() {
        dataset.executeWrite(() -> {
            byte[] content = "copy".getBytes();
            blobGraph.saveByPath("src/c.txt", "c.txt", "text/plain",
                new ByteArrayInputStream(content), adminCtx);

            blobGraph.copy("src/c.txt", "dst/c.txt", adminCtx);

            assertEquals(blobGraph.getURI("src/c.txt", adminCtx),
                         blobGraph.getURI("dst/c.txt", adminCtx));
        });
    }

    @Test
    @DisplayName("link is equivalent to copy")
    void linkFile() {
        dataset.executeWrite(() -> {
            byte[] content = "link".getBytes();
            blobGraph.saveByPath("src/l.txt", "l.txt", "text/plain",
                new ByteArrayInputStream(content), adminCtx);

            blobGraph.link("src/l.txt", "dst/l.txt", adminCtx);

            assertEquals(blobGraph.getURI("src/l.txt", adminCtx),
                         blobGraph.getURI("dst/l.txt", adminCtx));
        });
    }

    @Test
    @DisplayName("move non-existent throws")
    void moveNonExistent() {
        assertThrows(IllegalArgumentException.class, () ->
            dataset.executeWrite(() -> blobGraph.move("/no/such", "/yes", adminCtx)));
    }

    @Test
    @DisplayName("copy non-existent throws")
    void copyNonExistent() {
        assertThrows(IllegalArgumentException.class, () ->
            dataset.executeWrite(() -> blobGraph.copy("/no/such", "/yes", adminCtx)));
    }

    // =========================================================================
    //  List
    // =========================================================================

    @Test
    @DisplayName("list returns files matching folder prefix")
    void listFiles() {
        dataset.executeWrite(() -> {
            blobGraph.saveByPath("/photos/a.jpg", "a.jpg", "image/jpeg",
                new ByteArrayInputStream("a".getBytes()), adminCtx);
            blobGraph.saveByPath("/photos/b.png", "b.png", "image/png",
                new ByteArrayInputStream("b".getBytes()), adminCtx);
            blobGraph.saveByPath("/docs/d.txt", "d.txt", "text/plain",
                new ByteArrayInputStream("d".getBytes()), adminCtx);

            List<JSONObject> photos = blobGraph.list("/photos/", adminCtx);
            assertEquals(2, photos.size());
            assertTrue(photos.stream().anyMatch(e -> e.getString("path").equals("/photos/a.jpg")));
            assertTrue(photos.stream().anyMatch(e -> e.getString("path").equals("/photos/b.png")));

            // Verify filenames are extracted correctly
            List<String> filenames = photos.stream().map(e -> e.getString("filename")).toList();
            assertTrue(filenames.contains("a.jpg"));
            assertTrue(filenames.contains("b.png"));

            List<JSONObject> docs = blobGraph.list("/docs/", adminCtx);
            assertEquals(1, docs.size());
            assertEquals("d.txt", docs.get(0).getString("filename"));
        });
    }

    @Test
    @DisplayName("list returns empty for non-existent folder")
    void listEmpty() {
        List<JSONObject> result = dataset.calculateRead(() -> blobGraph.list("/no/such/", adminCtx));
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("list with '/' returns root-level files and subfolders")
    void listRoot() {
        dataset.executeWrite(() -> {
            blobGraph.saveByPath("/photos/a.jpg", "a.jpg", "image/jpeg",
                new ByteArrayInputStream("a".getBytes()), adminCtx);
            blobGraph.saveByPath("/photos/b.png", "b.png", "image/png",
                new ByteArrayInputStream("b".getBytes()), adminCtx);
            blobGraph.saveByPath("/docs/d.txt", "d.txt", "text/plain",
                new ByteArrayInputStream("d".getBytes()), adminCtx);
            blobGraph.saveByPath("/readme.md", "readme.md", "text/markdown",
                new ByteArrayInputStream("x".getBytes()), adminCtx);

            List<JSONObject> root = blobGraph.list("/", adminCtx);
            // Should return 2 folders (docs, photos) + 1 file (readme.md) = 3 entries
            assertEquals(3, root.size());

            // Folders come first
            assertTrue(root.get(0).optBoolean("isFolder"));
            assertTrue(root.get(1).optBoolean("isFolder"));
            assertFalse(root.get(2).optBoolean("isFolder"));

            // Verify filenames are correct
            String[] folderNames = root.stream()
                .filter(e -> e.optBoolean("isFolder"))
                .map(e -> e.getString("filename"))
                .sorted()
                .toArray(String[]::new);
            assertArrayEquals(new String[]{"docs", "photos"}, folderNames);

            String[] fileNames = root.stream()
                .filter(e -> !e.optBoolean("isFolder"))
                .map(e -> e.getString("filename"))
                .toArray(String[]::new);
            assertArrayEquals(new String[]{"readme.md"}, fileNames);
        });
    }

    @Test
    @DisplayName("list with empty string '' also returns root")
    void listEmptyStringReturnsRoot() {
        dataset.executeWrite(() -> {
            blobGraph.saveByPath("root-file.txt", "root-file.txt", "text/plain",
                new ByteArrayInputStream("data".getBytes()), adminCtx);

            List<JSONObject> result1 = blobGraph.list("", adminCtx);
            List<JSONObject> result2 = blobGraph.list("/", adminCtx);

            assertEquals(result1.size(), result2.size());
            assertTrue(result1.stream().anyMatch(e -> e.getString("path").equals("/root-file.txt")));
        });
    }

    // =========================================================================
    //  Delete
    // =========================================================================

    @Test
    @DisplayName("delete removes pointer and metadata")
    void deleteFile() {
        dataset.executeWrite(() -> {
            byte[] content = "del".getBytes();
            blobGraph.saveByPath("del/d.jpg", "d.jpg", "image/jpeg",
                new ByteArrayInputStream(content), adminCtx);

            assertNotNull(blobGraph.getURI("del/d.jpg", adminCtx));

            blobGraph.deleteByPath("del/d.jpg", adminCtx);

            assertThrows(IllegalStateException.class, () -> blobGraph.getURI("del/d.jpg", adminCtx));
            assertThrows(IllegalStateException.class, () -> blobGraph.loadContentByPath("del/d.jpg", adminCtx));
        });
    }

    @Test
    @DisplayName("delete non-existent is no-op")
    void deleteNonExistent() {
        dataset.executeWrite(() -> blobGraph.deleteByPath("/no/such/file.jpg", adminCtx));
    }

    @Test
    @DisplayName("blob persists on disk after last pointer deleted")
    void blobPersistedAfterDelete() {
        dataset.executeWrite(() -> {
            byte[] content = "shared".getBytes();
            blobGraph.saveByPath("s1.txt", "s1.txt", "text/plain",
                new ByteArrayInputStream(content), adminCtx);
            blobGraph.saveByPath("s2.txt", "s2.txt", "text/plain",
                new ByteArrayInputStream(content), adminCtx);

            // Same hash
            assertEquals(blobGraph.getSHA256("s1.txt", adminCtx),
                         blobGraph.getSHA256("s2.txt", adminCtx));

            blobGraph.deleteByPath("s1.txt", adminCtx);

            // s2 still works
            assertNotNull(blobGraph.getURI("s2.txt", adminCtx));

            blobGraph.deleteByPath("s2.txt", adminCtx);
            assertThrows(IllegalStateException.class, () -> blobGraph.getURI("s2.txt", adminCtx));
        });
    }

    // =========================================================================
    //  Graph interface
    // =========================================================================

    @Test
    @DisplayName("find with ANY returns triples after save")
    void findReturnsTriples() {
        dataset.executeWrite(() -> {
            blobGraph.saveByPath("graph/g.jpg", "g.jpg", "image/jpeg",
                new ByteArrayInputStream("graph".getBytes()), adminCtx);

            ExtendedIterator<Triple> iter = blobGraph.find(Node.ANY, Node.ANY, Node.ANY, adminCtx);
            try {
                assertTrue(iter.hasNext());
            } finally {
                iter.close();
            }
        });
    }

    @Test
    @DisplayName("find with subject filter narrows results")
    void findWithSubject() {
        dataset.executeWrite(() -> {
            blobGraph.saveByPath("graph/f.jpg", "f.jpg", "image/jpeg",
                new ByteArrayInputStream("filter".getBytes()), adminCtx);

            String uri = blobGraph.getURI("graph/f.jpg", adminCtx);
            Node subj = NodeFactory.createURI(uri);

            ExtendedIterator<Triple> iter = blobGraph.find(subj, Node.ANY, Node.ANY, adminCtx);
            try {
                assertTrue(iter.hasNext());
            } finally {
                iter.close();
            }

            // Non-matching subject
            Node other = NodeFactory.createURI("http://no.such.blob/");
            ExtendedIterator<Triple> iter2 = blobGraph.find(other, Node.ANY, Node.ANY, adminCtx);
            try {
                assertFalse(iter2.hasNext());
            } finally {
                iter2.close();
            }
        });
    }

    @Test
    @DisplayName("find with path-based subject URI")
    void findWithSubjectPathUri() {
        dataset.executeWrite(() -> {
            blobGraph.saveByPath("graph/g.jpg", "g.jpg", "image/jpeg",
                new ByteArrayInputStream("pathsubj".getBytes()), adminCtx);

            // Use the filepath as the subject URI directly (path version)
            Node subj = NodeFactory.createURI("urn:atic:blob:graph/g.jpg");

            ExtendedIterator<Triple> iter = blobGraph.find(subj, Node.ANY, Node.ANY, adminCtx);
            try {
                assertTrue(iter.hasNext());
            } finally {
                iter.close();
            }

            // Non-matching path
            Node other = NodeFactory.createURI("urn:atic:blob:no/such.jpg");
            ExtendedIterator<Triple> iter2 = blobGraph.find(other, Node.ANY, Node.ANY, adminCtx);
            try {
                assertFalse(iter2.hasNext());
            } finally {
                iter2.close();
            }
        });
    }

    @Test
    @DisplayName("contains returns true for existing blob triples")
    void containsTrue() {
        dataset.executeWrite(() -> {
            blobGraph.saveByPath("graph/c.jpg", "c.jpg", "image/jpeg",
                new ByteArrayInputStream("contains".getBytes()), adminCtx);
            assertFalse(blobGraph.size(adminCtx) == 0);
        });
    }

    @Test
    @DisplayName("contains returns false for fake triple")
    void containsFalse() {
        Node s = NodeFactory.createURI("http://fake/s");
        Node p = NodeFactory.createURI("http://fake/p");
        Node o = NodeFactory.createLiteralString("x");
        assertFalse(blobGraph.contains(s, p, o, adminCtx));
    }

    @Test
    @DisplayName("size increases after save")
    void sizeIncreases() {
        dataset.executeWrite(() -> {
            blobGraph.saveByPath("graph/s1.jpg", "s1.jpg", "image/jpeg",
                new ByteArrayInputStream("s1".getBytes()), adminCtx);
            int s1 = blobGraph.size(adminCtx);
            assertTrue(s1 > 0);
            blobGraph.saveByPath("graph/s2.jpg", "s2.jpg", "image/jpeg",
                new ByteArrayInputStream("s2".getBytes()), adminCtx);
            assertTrue(blobGraph.size(adminCtx) >= s1);
        });
    }

    @Test
    @DisplayName("isEmpty is true initially")
    void isEmptyInitially() {
        assertTrue(dataset.calculateRead(() -> blobGraph.isEmpty(adminCtx)));
    }

    @Test
    @DisplayName("isEmpty is false after save")
    void isEmptyFalseAfterSave() {
        dataset.executeWrite(() -> {
            blobGraph.saveByPath("graph/e.jpg", "e.jpg", "image/jpeg",
                new ByteArrayInputStream("data".getBytes()), adminCtx);
            assertFalse(blobGraph.isEmpty(adminCtx));
        });
    }

    // =========================================================================
    //  Path conventions
    // =========================================================================

    @Test
    @DisplayName("metadataFile appends .ttl to SHA256")
    void metadataFileExtension() {
        String sha = "aabbccddeeff0123456789aabbccddeeff0123456789aabbccddeeff0123456789";
        File meta = blobGraph.metadataFile(sha);
        assertEquals(sha + ".ttl", meta.getName());
    }
}

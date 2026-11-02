package de.dfki.sds.aticsqlite;

import de.dfki.sds.atic.ac.User;
import de.dfki.sds.atic.ac.UserGroupManagement;
import de.dfki.sds.atic.jenatic.FragmentSettings;
import de.dfki.sds.atic.jenatic.InvocationContext;
import de.dfki.sds.aticsqlite.FragmentStorage.FragmentSettingsEntry;
import io.json.compare.CompareMode;
import io.json.compare.JSONCompare;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 *
 */
public class FragmentSettingsUnitTest {

    private static final String EX = "http://example.org/";

    private SqliteAticDatasetGraph dataset;
    private FragmentStorage fragmentStorage;

    @BeforeEach
    void setup(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        dataset = TL.createDatasetGraph(tempDir);
        fragmentStorage = dataset.getFragmentStorage();
    }

    @Test
    public void testSaveLoadDelete() {
        User adminUser = dataset.calculateRead(() -> {
            return dataset.getUser(
                    UserGroupManagement.ADMIN_USERNAME,
                    InvocationContext.EMPTY
            );
        });
        InvocationContext ctx = new InvocationContext.Builder()
                .fromUser(adminUser)
                .build();

        FragmentSettings settings = FragmentSettings.defaultSettings();

        dataset.executeWrite(() -> {
            fragmentStorage.save(null, null, settings, ctx);
        });

        dataset.executeRead(() -> {
            FragmentSettings loaded = fragmentStorage.load(null, null, ctx);

            assertNotNull(loaded);

            JSONCompare.assertMatches(
                    settings.toJson().toString(),
                    loaded.toJson().toString(),
                    Set.of(
                            CompareMode.JSON_ARRAY_NON_EXTENSIBLE,
                            CompareMode.JSON_OBJECT_NON_EXTENSIBLE
                    )
            );
        });

        dataset.executeWrite(() -> {
            fragmentStorage.delete(null, null, ctx);
        });

        dataset.executeRead(() -> {
            assertNull(fragmentStorage.load(null, null, ctx));
        });
    }

    @Test
    public void testSaveOverwritesExistingSettings() {
        User adminUser = dataset.calculateRead(()
                -> dataset.getUser(
                        UserGroupManagement.ADMIN_USERNAME,
                        InvocationContext.EMPTY
                )
        );
        InvocationContext ctx = new InvocationContext.Builder()
                .fromUser(adminUser)
                .build();

        FragmentSettings settings1 = FragmentSettings.builder()
                .addProperty("label", NodeFactory.createURI(EX + "label"), true)
                .addProperty("comment", NodeFactory.createURI(EX + "comment"), true)
                .addProperty("icon", NodeFactory.createURI(EX + "icon"), false)
                .build();

        FragmentSettings settings2 = FragmentSettings.builder()
                .addProperty("name", NodeFactory.createURI(EX + "name"), true)
                .addProperty("image", NodeFactory.createURI(EX + "image"), false)
                .build();

        dataset.executeWrite(() -> {
            fragmentStorage.save(null, null, settings1, ctx);
        });

        dataset.executeWrite(() -> {
            fragmentStorage.save(null, null, settings2, ctx);
        });

        dataset.executeRead(() -> {
            FragmentSettings loaded = fragmentStorage.load(null, null, ctx);

            assertNotNull(loaded);

            //has to be settings2 then
            JSONCompare.assertMatches(
                    settings2.toJson().toString(),
                    loaded.toJson().toString(),
                    Set.of(
                            CompareMode.JSON_ARRAY_NON_EXTENSIBLE,
                            CompareMode.JSON_OBJECT_NON_EXTENSIBLE
                    )
            );
        });
    }

    @Test
    public void testLoadAllWithDifferentContexts() {
        User adminUser = dataset.calculateRead(()
                -> dataset.getUser(
                        UserGroupManagement.ADMIN_USERNAME,
                        InvocationContext.EMPTY
                )
        );
        InvocationContext ctx = new InvocationContext.Builder()
                .fromUser(adminUser)
                .build();

        Node context1 = NodeFactory.createURI(EX + "context1");
        Node context2 = NodeFactory.createURI(EX + "context2");

        FragmentSettings settings1 = FragmentSettings.builder()
                .addProperty("label", NodeFactory.createURI(EX + "label"), true)
                .addProperty("comment", NodeFactory.createURI(EX + "comment"), true)
                .build();

        FragmentSettings settings2 = FragmentSettings.builder()
                .addProperty("name", NodeFactory.createURI(EX + "name"), true)
                .addProperty("image", NodeFactory.createURI(EX + "image"), false)
                .build();

        dataset.executeWrite(() -> {
            fragmentStorage.save(context1, null, settings1, ctx);
            fragmentStorage.save(context2, null, settings2, ctx);
        });

        dataset.executeRead(() -> {
            List<FragmentSettingsEntry> entries = fragmentStorage.loadAll(ctx);

            assertEquals(2, entries.size());

            Map<String, FragmentSettingsEntry> entriesByContext = entries.stream()
                    .collect(Collectors.toMap(
                            FragmentSettingsEntry::contextUri,
                            Function.identity()
                    ));

            FragmentSettingsEntry entry1 = entriesByContext.get(context1.getURI());
            FragmentSettingsEntry entry2 = entriesByContext.get(context2.getURI());

            assertNotNull(entry1);
            assertNotNull(entry2);

            JSONCompare.assertMatches(
                    settings1.toJson().toString(),
                    entry1.settings().toJson().toString(),
                    Set.of(
                            CompareMode.JSON_ARRAY_NON_EXTENSIBLE,
                            CompareMode.JSON_OBJECT_NON_EXTENSIBLE
                    )
            );

            JSONCompare.assertMatches(
                    settings2.toJson().toString(),
                    entry2.settings().toJson().toString(),
                    Set.of(
                            CompareMode.JSON_ARRAY_NON_EXTENSIBLE,
                            CompareMode.JSON_OBJECT_NON_EXTENSIBLE
                    )
            );
        });
    }
    
}

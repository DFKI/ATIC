

package de.dfki.sds.aticsqlite;

import de.dfki.sds.atic.ac.User;
import de.dfki.sds.atic.ac.UserGroupManagement;
import de.dfki.sds.atic.jenatic.InvocationContext;
import java.nio.file.Path;
import java.util.List;
import org.json.JSONArray;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 *
 */
public class VueComponentStorageUnitTest {

    private SqliteAticDatasetGraph dataset;
    private VueComponentStorage vueComponentStorage;

    @BeforeEach
    void setup(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        dataset = TL.createDatasetGraph(tempDir);
        vueComponentStorage = dataset.getVueComponentStorage();
    }
    
    @Test
    public void roundtripTest() {
        User adminUser = dataset.calculateRead(()
                -> dataset.getUser(
                        UserGroupManagement.ADMIN_USERNAME,
                        InvocationContext.EMPTY
                )
        );

        InvocationContext ctx = new InvocationContext.Builder()
                .fromUser(adminUser)
                .build();
        
        // Initially, no components should exist.
        assertTrue(dataset.calculateRead(() -> vueComponentStorage.loadAll()).isEmpty());

        // Create a component.
        VueComponent component = new VueComponent("/components", "MyComponent");
        component.setBridges(new JSONArray().put("bridge1").put("bridge2"));
        component.setScript("export default {};");
        component.setTemplate("<div>Hello</div>");
        component.setStyle(".hello { color: red; }");

        // Save the component.
        dataset.executeWrite(() -> vueComponentStorage.save(component, ctx));

        // Load by path and name.
        VueComponent loaded = dataset.calculateRead(() -> vueComponentStorage.load("/components", "MyComponent"));

        assertNotNull(loaded);
        assertEquals(component.getPath(), loaded.getPath());
        assertEquals(component.getName(), loaded.getName());
        assertEquals(component.getBridges().toString(), loaded.getBridges().toString());
        assertEquals(component.getScript(), loaded.getScript());
        assertEquals(component.getTemplate(), loaded.getTemplate());
        assertEquals(component.getStyle(), loaded.getStyle());

        // Load by path.
        List<VueComponent> componentsByPath = dataset.calculateRead(() -> vueComponentStorage.loadByPath("/components"));

        assertEquals(1, componentsByPath.size());
        assertEquals("MyComponent", componentsByPath.get(0).getName());

        // Load all.
        assertEquals(1, dataset.calculateRead(() -> vueComponentStorage.loadAll()).size());

        // Delete the component.
        dataset.executeWrite(() -> vueComponentStorage.delete("/components", "MyComponent"));

        // Verify that it no longer exists.
        assertNull(dataset.calculateRead(() -> vueComponentStorage.load("/components", "MyComponent")));
        assertTrue(dataset.calculateRead(() -> vueComponentStorage.loadAll()).isEmpty());
    }
    
}

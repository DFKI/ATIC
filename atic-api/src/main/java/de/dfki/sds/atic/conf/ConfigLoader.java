package de.dfki.sds.atic.conf;

import com.moandjiezana.toml.Toml;
import java.io.File;
import java.lang.reflect.*;
import java.time.*;
import java.util.*;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.json.JSONObject;

public class ConfigLoader {

    public static <T> T load(Class<T> clazz, String[] args) throws Exception {

        T instance = clazz.getDeclaredConstructor().newInstance();

        // -------------------------
        // 1. TOML
        // -------------------------
        Map<String, Object> toml = new HashMap<>();
        File file = new File("atic.toml");
        if (file.exists()) {
            toml = new Toml().read(file).toMap();
        }

        // -------------------------
        // 2. CLI setup
        // -------------------------
        Options options = new Options();

        // ALWAYS add help
        options.addOption("h", "help", false, "Show help");

        for (Field field : clazz.getDeclaredFields()) {
            Config cfg = field.getAnnotation(Config.class);
            if (cfg != null) {
                boolean hasArg = field.getType() != boolean.class;
                options.addOption(null, cfg.value(), hasArg, cfg.description());
            }
        }

        CommandLine cmd = new DefaultParser().parse(options, args);

        // -------------------------
        // 🔥 HANDLE HELP EARLY
        // -------------------------
        if (cmd.hasOption("help")) {
            HelpFormatter formatter = new HelpFormatter();
            formatter.printHelp(clazz.getSimpleName(), options);
            System.exit(0);
        }

        // -------------------------
        // 3. Resolve fields
        // -------------------------
        for (Field field : clazz.getDeclaredFields()) {
            Config cfg = field.getAnnotation(Config.class);
            if (cfg == null) {
                continue;
            }

            field.setAccessible(true);

            String key = cfg.value();
            Object value = field.get(instance); // default

            // --- TOML ---
            if (toml.containsKey(key)) {
                value = toml.get(key);
            }

            // --- ENV ---
            String envKey = "ATIC_" + key.toUpperCase().replace('.', '_');
            String envVal = System.getenv(envKey);
            if (envVal != null) {
                value = cast(envVal, field.getType());
            }

            // --- CLI ---
            if (cmd.hasOption(key)) {
                if (field.getType() == boolean.class) {
                    value = true;
                } else {
                    value = cast(cmd.getOptionValue(key), field.getType());
                }
            }

            field.set(instance, convert(value, field.getType()));
        }

        return instance;
    }

    // -------------------------
    // CAST (String → type)
    // -------------------------
    private static Object cast(String val, Class<?> type) {

        if (type == int.class) {
            return Integer.parseInt(val);
        }
        if (type == long.class) {
            return Long.parseLong(val);
        }
        if (type == double.class) {
            return Double.parseDouble(val);
        }
        if (type == boolean.class) {
            return Boolean.parseBoolean(val);
        }
        if (type == String.class) {
            return val;
        }

        // FILE SUPPORT
        if (type == File.class) {
            return new File(val);
        }

        // time types
        if (type == LocalDate.class) {
            return LocalDate.parse(val);
        }
        if (type == LocalDateTime.class) {
            return LocalDateTime.parse(val);
        }
        if (type == OffsetDateTime.class) {
            return OffsetDateTime.parse(val);
        }

        throw new RuntimeException("Unsupported type: " + type);
    }

    // -------------------------
    // CONVERT (TOML → Java)
    // -------------------------
    private static Object convert(Object value, Class<?> type) {
        if (value == null) {
            return null;
        }

        if (type.isAssignableFrom(value.getClass())) {
            return value;
        }

        // FILE SUPPORT (from TOML string)
        if (type == File.class && value instanceof String) {
            return new File((String) value);
        }

        // Lists (arrays)
        if (List.class.isAssignableFrom(type) && value instanceof List) {
            return value;
        }

        // Maps (tables)
        if (Map.class.isAssignableFrom(type) && value instanceof Map) {
            return value;
        }

        return value;
    }

    public static <T> JSONObject toJson(T configInstance) throws Exception {
        JSONObject json = new JSONObject();

        Class<?> clazz = configInstance.getClass();

        for (Field field : clazz.getDeclaredFields()) {
            Config cfg = field.getAnnotation(Config.class);
            if (cfg == null) {
                continue;
            }

            field.setAccessible(true);

            String key = cfg.value();
            Object value = field.get(configInstance);

            if (value == null) {
                continue;
            }

            value = normalize(value);

            json.put(key, value);
        }

        return json;
    }

    private static Object normalize(Object value) {

        // File → path
        if (value instanceof File) {
            return ((File) value).getPath();
        }

        // Time types → ISO string
        if (value instanceof LocalDate
                || value instanceof LocalDateTime
                || value instanceof OffsetDateTime) {
            return value.toString();
        }

        // Lists → recursively normalize
        if (value instanceof List<?>) {
            List<Object> out = new ArrayList<>();
            for (Object v : (List<?>) value) {
                out.add(normalize(v));
            }
            return out;
        }

        // Maps → recursively normalize
        if (value instanceof Map<?, ?>) {
            Map<String, Object> out = new HashMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                out.put(String.valueOf(e.getKey()), normalize(e.getValue()));
            }
            return out;
        }

        return value;
    }

    public static <T> Object getConfig(String name, T configInstance) throws Exception {
        Class<?> clazz = configInstance.getClass();

        for (Field field : clazz.getDeclaredFields()) {
            Config cfg = field.getAnnotation(Config.class);
            if (cfg == null) {
                continue;
            }

            if (cfg.value().equals(name)) {
                field.setAccessible(true);
                Object value = field.get(configInstance);

                return normalize(value); // reuse same logic as toJson
            }
        }

        throw new IllegalArgumentException("Unknown config key: " + name);
    }
}

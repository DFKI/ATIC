

package de.dfki.sds.aticserver;

import java.io.InputStream;
import java.util.Properties;

/**
 *
 */
public class VersionUtil {
    public static String getVersion() {
        try (InputStream is = VersionUtil.class.getClassLoader()
                .getResourceAsStream(
                    "META-INF/maven/de.dfki.sds/atic-server/pom.properties")) {

            Properties props = new Properties();
            props.load(is);
            return props.getProperty("version");

        } catch (Exception e) {
            return "";
        }
    }
}

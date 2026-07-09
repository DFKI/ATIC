

package de.dfki.sds.aticserver;

import de.dfki.sds.atic.conf.ConfigLoader;

/**
 *
 */
public class Main {

    public static void main(String[] args) throws Exception {
        System.out.println(ASCII_LOGO);
        System.out.println(Utils.getVersion());
        
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "error");
        
        AticConfig appConfig = ConfigLoader.load(AticConfig.class, args);
        
        AticServer server = new AticServer(appConfig);
        server.init();
    }
    
        
    public static final String ASCII_LOGO = 
"""
 ▗▄▖▗▄▄▄▖▗▄▄▄▖ ▗▄▄▖
▐▌ ▐▌ █    █  ▐▌   
▐▛▀▜▌ █    █  ▐▌   
▐▌ ▐▌ █  ▗▄█▄▖▝▚▄▄▖
""";
}

package de.dfki.sds.atic.invex;

import java.nio.file.Path;

public interface InvexSystemApi {

    void bootstrap(Path dumpFile) throws Exception;
    
    void update(Path patchFile) throws Exception;
    
    void shutdown() throws Exception;
}

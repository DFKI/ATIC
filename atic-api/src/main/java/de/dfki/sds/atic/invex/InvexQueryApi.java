package de.dfki.sds.atic.invex;

public interface InvexQueryApi {
    
    QueryResults initializeQuery(QueryOptions options) throws Exception;
    
    QueryResults proceedWithQuery(String queryProcessID) throws Exception;
}

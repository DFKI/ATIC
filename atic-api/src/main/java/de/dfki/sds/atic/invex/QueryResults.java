package de.dfki.sds.atic.invex;

import java.io.Serializable;

public class QueryResults implements Serializable {
    
    private static final long serialVersionUID = 8586175856931624204L;

    //__________________________________________________________________________
    // FIELDS: GENERAL METADATA
    //__________________________________________________________________________
    
    private boolean success;
    private String error;
    
    private long responseTime;

    //__________________________________________________________________________
    // FIELDS: SPECIFIC METADATA (INIT PHASE)
    //__________________________________________________________________________

    private String queryProcessID;

    private boolean queryAccepted;

    private long luceneUpperBoundGuestimation;
    private long luceneUpperBoundGuestimationTime;
    
    //__________________________________________________________________________
    // FIELDS: SPECIFIC METADATA (MAIN PHASE)
    //__________________________________________________________________________

    private long luceneLowerBound;
    private long returnedResourceIDsCount;

    private long limit;
    private long offset;

    //__________________________________________________________________________
    // FIELDS: DATA (ONLY IN MAIN PHASE)
    //__________________________________________________________________________
    
    private long[] foundResourceIDs;
    
    //__________________________________________________________________________
    // CONSTRUCTOR
    //__________________________________________________________________________
    
    public QueryResults() {
    }

    //__________________________________________________________________________
    // METHODS
    //__________________________________________________________________________
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(128);
        sb.append("QueryResults (");
        sb.append("success=").append(success ? "T" : "F");
        sb.append(", error=").append(error == null ? "F" : "T");
        sb.append(", responseTime=").append(responseTime).append("ms");
        sb.append(", queryProcessID=").append(queryProcessID);
        sb.append(", accepted=").append(queryAccepted ? "T" : "F");
        sb.append(", lucUBG=").append(luceneUpperBoundGuestimation);
        sb.append(", lucUBGT=").append(luceneUpperBoundGuestimationTime).append("ms");
        sb.append(", lucLB=").append(luceneLowerBound);
        sb.append(", #returnedResults=").append(returnedResourceIDsCount);
        sb.append(" ... )");
        return sb.toString();
    }
    
    //__________________________________________________________________________
    // GETTERS & SETTERS
    //__________________________________________________________________________
    
    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public long getResponseTime() {
        return responseTime;
    }

    public void setResponseTime(long responseTime) {
        this.responseTime = responseTime;
    }

    public String getQueryProcessID() {
        return queryProcessID;
    }

    public void setQueryProcessID(String queryProcessID) {
        this.queryProcessID = queryProcessID;
    }

    public boolean isQueryAccepted() {
        return queryAccepted;
    }

    public void setQueryAccepted(boolean queryAccepted) {
        this.queryAccepted = queryAccepted;
    }

    public long getLuceneUpperBoundGuestimation() {
        return luceneUpperBoundGuestimation;
    }

    public void setLuceneUpperBoundGuestimation(long luceneUpperBoundGuestimation) {
        this.luceneUpperBoundGuestimation = luceneUpperBoundGuestimation;
    }

    public long getLuceneUpperBoundGuestimationTime() {
        return luceneUpperBoundGuestimationTime;
    }

    public void setLuceneUpperBoundGuestimationTime(
            long luceneUpperBoundGuestimationTime) {
        this.luceneUpperBoundGuestimationTime = luceneUpperBoundGuestimationTime;
    }

    public long getLuceneLowerBound() {
        return luceneLowerBound;
    }

    public void setLuceneLowerBound(long luceneLowerBound) {
        this.luceneLowerBound = luceneLowerBound;
    }

    public long getReturnedResourceIDsCount() {
        return returnedResourceIDsCount;
    }

    public void setReturnedResourceIDsCount(long returnedResourceIDsCount) {
        this.returnedResourceIDsCount = returnedResourceIDsCount;
    }

    public long getLimit() {
        return limit;
    }

    public void setLimit(long limit) {
        this.limit = limit;
    }

    public long getOffset() {
        return offset;
    }

    public void setOffset(long offset) {
        this.offset = offset;
    }

    public long[] getFoundResourceIDs() {
        return foundResourceIDs;
    }

    public void setFoundResourceIDs(long[] foundResourceIDs) {
        this.foundResourceIDs = foundResourceIDs;
    }
}

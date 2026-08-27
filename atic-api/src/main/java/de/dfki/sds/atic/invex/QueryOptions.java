package de.dfki.sds.atic.invex;

import java.io.Serializable;

public class QueryOptions implements Serializable {

    private static final long serialVersionUID = -4985539998748702144L;

    //__________________________________________________________________________
    // FIELDS
    //__________________________________________________________________________
    
    private String queryProcessID;
    
    private String searchString;
    
    private int userID;
    
    private int limit = 10;
    private int offset = 0;
    
    //__________________________________________________________________________
    // CONSTRUCTOR
    //__________________________________________________________________________
    
    public QueryOptions() {
    }

    //__________________________________________________________________________
    // GETTERS & SETTERS
    //__________________________________________________________________________

    public String getQueryProcessID() {
        return queryProcessID;
    }

    public void setQueryProcessID(String queryProcessID) {
        this.queryProcessID = queryProcessID;
    }

    public String getSearchString() {
        return searchString;
    }

    public void setSearchString(String searchString) {
        this.searchString = searchString;
    }

    public int getUserID() {
        return userID;
    }

    public void setUserID(int userID) {
        this.userID = userID;
    }

    public int getLimit() {
        return limit;
    }

    public void setLimit(int limit) {
        this.limit = limit;
    }

    public int getOffset() {
        return offset;
    }

    public void setOffset(int offset) {
        this.offset = offset;
    }
}

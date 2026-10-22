package de.dfki.sds.invex.temp;

import java.io.Serializable;

public class CategorizedResults implements Serializable {

    private static final long serialVersionUID = 57782141529391559L;

    private String category;
    
    private long[] foundResourceIDs;
    
    public CategorizedResults() {
    }

    public CategorizedResults(String category, long[] foundResourceIDs) {
        this.category = category;
        this.foundResourceIDs = foundResourceIDs;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public long[] getFoundResourceIDs() {
        return foundResourceIDs;
    }

    public void setFoundResourceIDs(long[] foundResourceIDs) {
        this.foundResourceIDs = foundResourceIDs;
    }
}

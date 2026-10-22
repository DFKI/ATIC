

package de.dfki.sds.atic.invex;

import de.dfki.sds.atic.jenatic.Fragment;
import java.util.List;

/**
 *
 */
public class AticCategorizedResults {
    
    private String categoryName;
    private List<Fragment> fragments;

    public AticCategorizedResults(String categoryName, List<Fragment> fragments) {
        this.categoryName = categoryName;
        this.fragments = fragments;
    }

    public String getCategoryName() {
        return categoryName;
    }

    public void setCategoryName(String categoryName) {
        this.categoryName = categoryName;
    }

    public List<Fragment> getFragments() {
        return fragments;
    }

    public void setFragments(List<Fragment> fragments) {
        this.fragments = fragments;
    }
    
}

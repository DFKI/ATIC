

package de.dfki.sds.aticserver;

import java.util.Comparator;

/**
 *
 */
public class NaturalComparator implements Comparator<String> {

    @Override
    public int compare(String a, String b) {
        int i = 0, j = 0;
        int lenA = a.length(), lenB = b.length();

        while (i < lenA && j < lenB) {
            char ca = a.charAt(i);
            char cb = b.charAt(j);

            // If both are digits → compare full number
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int startI = i;
                int startJ = j;

                // extract full number from a
                while (i < lenA && Character.isDigit(a.charAt(i))) i++;
                while (j < lenB && Character.isDigit(b.charAt(j))) j++;

                String numA = a.substring(startI, i);
                String numB = b.substring(startJ, j);

                // remove leading zeros for fair comparison
                numA = numA.replaceFirst("^0+", "");
                numB = numB.replaceFirst("^0+", "");

                int cmp = Integer.compare(numA.length(), numB.length());
                if (cmp != 0) return cmp;

                cmp = numA.compareTo(numB);
                if (cmp != 0) return cmp;

            } else {
                // normal char compare
                int cmp = Character.compare(ca, cb);
                if (cmp != 0) return cmp;
                i++;
                j++;
            }
        }

        return Integer.compare(lenA, lenB);
    }
}

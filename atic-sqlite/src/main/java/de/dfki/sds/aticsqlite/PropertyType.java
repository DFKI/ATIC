

package de.dfki.sds.aticsqlite;

/**
 *
 */
public enum PropertyType {
    
    UNDEFINED(0),
    URI(1),
    LITERAL(2);

    private final int value;

    PropertyType(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    public static PropertyType fromValue(int value) {
        for (PropertyType type : values()) {
            if (type.value == value) {
                return type;
            }
        }

        throw new IllegalArgumentException("Unknown PropertyType: " + value);
    }
}
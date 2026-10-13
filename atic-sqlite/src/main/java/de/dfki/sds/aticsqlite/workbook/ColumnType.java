
package de.dfki.sds.aticsqlite.workbook;

/**
 * The type of the column.
 * We distinguish between {@link #Resource} columns and {@link #Literal} columns.
 * This is useful when we interpret the cell input of a user.
 */
public enum ColumnType {
    Resource,
    Literal
}



package de.dfki.sds.aticsqlite.s16n;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;

public class Window extends ArrayList<List<Cell>> {

    public Window() {
        super();
    }

    public Window(List<List<Cell>> rows) {
        super(rows);
    }

    public JSONArray toJson() {
        JSONArray rows = new JSONArray();

        for (List<Cell> row : this) {
            JSONArray cells = new JSONArray();

            for (Cell cell : row) {
                cells.put(cell.toJson());
            }

            rows.put(cells);
        }

        return rows;
    }
    
}
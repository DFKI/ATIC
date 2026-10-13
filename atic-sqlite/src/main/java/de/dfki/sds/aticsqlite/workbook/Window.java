

package de.dfki.sds.aticsqlite.workbook;

import de.dfki.sds.atic.helper.JSONUtils;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public class Window extends ArrayList<List<Cell>> {

    private List<ColumnConfig> colHeader;
    private List<Cell> rowHeader;
    
    public Window() {
        super();
        init();
    }

    public Window(List<List<Cell>> rows) {
        super(rows);
        init();
    }
    
    private void init() {
        colHeader = new ArrayList<>();
        rowHeader = new ArrayList<>();
    }

    public JSONObject toJson() {
        JSONObject json = JSONUtils.createJSONObject();
        
        JSONArray colArray = new JSONArray();
        json.put("columns", colArray);
        for (ColumnConfig c : colHeader) {
            if(c == null) {
                colArray.put(JSONObject.NULL);
            } else {
                colArray.put(c.toJson());
            }
        }
        
        JSONArray rowArray = new JSONArray();
        json.put("rows", rowArray);
        for (Cell c : rowHeader) {
            if(c == null) {
                rowArray.put(JSONObject.NULL);
            } else {
                rowArray.put(c.toJson());
            }
        }
        
        JSONArray rows = new JSONArray();
        json.put("cells", rows);
        for (List<Cell> row : this) {
            JSONArray cells = new JSONArray();
            for (Cell cell : row) {
                cells.put(cell.toJson());
            }
            rows.put(cells);
        }

        return json;
    }

    public List<ColumnConfig> getColHeader() {
        return colHeader;
    }

    public List<Cell> getRowHeader() {
        return rowHeader;
    }
    
}
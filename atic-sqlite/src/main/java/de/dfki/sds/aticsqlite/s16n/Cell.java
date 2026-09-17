package de.dfki.sds.aticsqlite.s16n;

import java.util.ArrayList;
import java.util.List;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.json.JSONArray;
import org.json.JSONObject;

public class Cell {

    private Node workbook;
    private Node sheet;
    private Node column;
    private Node rowEntity;
    private int rowIndex;
    private int columnIndex;

    private final List<Node> nodes;

    private Cell(Builder builder) {
        this.workbook = builder.workbook;
        this.sheet = builder.sheet;
        this.column = builder.column;
        this.rowEntity = builder.rowEntity;
        this.rowIndex = builder.rowIndex;
        this.columnIndex = builder.columnIndex;
        this.nodes = builder.nodes;
    }

    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {

        private Node workbook;
        private Node sheet;
        private Node column;
        private Node rowEntity;
        private int rowIndex;
        private int columnIndex;
        private List<Node> nodes = new ArrayList<>();

        public Builder workbook(Node workbook) {
            this.workbook = workbook;
            return this;
        }

        public Builder sheet(Node sheet) {
            this.sheet = sheet;
            return this;
        }

        public Builder column(Node column) {
            this.column = column;
            return this;
        }
        
        public Builder rowEntity(Node rowEntity) {
            this.rowEntity = rowEntity;
            return this;
        }

        public Builder rowIndex(int rowIndex) {
            this.rowIndex = rowIndex;
            return this;
        }

        public Builder columnIndex(int columnIndex) {
            this.columnIndex = columnIndex;
            return this;
        }

        public Builder location(int rowIndex, int columnIndex) {
            this.rowIndex = rowIndex;
            this.columnIndex = columnIndex;
            return this;
        }

        public Builder nodes(List<Node> nodes) {
            this.nodes = nodes;
            return this;
        }

        public Builder addNode(Node node) {
            this.nodes.add(node);
            return this;
        }

        public Builder fromJson(JSONObject json) {
            JSONArray content = json.getJSONArray("content");
            this.nodes = new ArrayList<>();

            for (int i = 0; i < content.length(); i++) {
                this.nodes.add(fromJsonLd(content.getJSONObject(i)));
            }

            return this;
        }

        public Cell build() {
            return new Cell(this);
        }
    }
    
    public Node getWorkbook() {
        return workbook;
    }

    public Node getSheet() {
        return sheet;
    }

    public Node getColumn() {
        return column;
    }

    public int getRowIndex() {
        return rowIndex;
    }

    public int getColumnIndex() {
        return columnIndex;
    }

    public List<Node> getNodes() {
        return nodes;
    }


    public static Cell fromJson(JSONObject json) {
        return builder()
                .workbook(NodeFactory.createURI(json.getJSONObject("workbook").getString("@id")))
                .sheet(NodeFactory.createURI(json.getJSONObject("sheet").getString("@id")))
                .column(NodeFactory.createURI(json.getJSONObject("column").getString("@id")))
                .rowIndex(json.getInt("rowIndex"))
                .columnIndex(json.getInt("columnIndex"))
                .fromJson(json)
                .build();
    }

    public JSONObject toJson() {
        JSONArray content = new JSONArray();

        for (Node node : nodes) {
            content.put(toJsonLd(node));
        }

        JSONObject json = new JSONObject()
                .put("workbook", new JSONObject().put("@id", workbook.getURI()))
                .put("sheet", new JSONObject().put("@id", sheet.getURI()))
                .put("rowIndex", rowIndex)
                .put("columnIndex", columnIndex)
                .put("content", content);

        if (column != null) {
            json.put("column", new JSONObject().put("@id", column.getURI()));
        }
        if (rowEntity != null) {
            json.put("rowEntity", new JSONObject().put("@id", rowEntity.getURI()));
        }

        return json;
    }

    private static JSONObject toJsonLd(Node node) {
        if (node.isURI()) {
            return new JSONObject().put("@id", node.getURI());
        }

        if (node.isLiteral()) {
            JSONObject json = new JSONObject().put("@value", node.getLiteralLexicalForm());

            if (node.getLiteralLanguage() != null && !node.getLiteralLanguage().isEmpty()) {
                json.put("@language", node.getLiteralLanguage());
            } else if (node.getLiteralDatatypeURI() != null) {
                json.put("@type", node.getLiteralDatatypeURI());
            }

            return json;
        }

        throw new IllegalArgumentException("Unsupported Node type: " + node);
    }

    private static Node fromJsonLd(JSONObject json) {
        if (json.has("@id")) {
            return NodeFactory.createURI(json.getString("@id"));
        }

        if (json.has("@value")) {
            String value = json.getString("@value");

            if (json.has("@language")) {
                return NodeFactory.createLiteralLang(value, json.getString("@language"));
            }

            if (json.has("@type")) {
                return NodeFactory.createLiteralDT(value, NodeFactory.getType(json.getString("@type")));
            }

            return NodeFactory.createLiteralString(value);
        }

        throw new IllegalArgumentException("Invalid JSON-LD node: " + json);
    }

    public boolean isEmpty() {
        return nodes.isEmpty();
    }

}

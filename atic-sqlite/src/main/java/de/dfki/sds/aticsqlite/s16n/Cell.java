package de.dfki.sds.aticsqlite.s16n;

import java.util.ArrayList;
import java.util.List;
import static org.apache.jena.datatypes.xsd.XSDDatatype.*;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.json.JSONArray;
import org.json.JSONObject;

public class Cell {

    private Node workbook;
    private Node sheet;
    private Node column;
    private Node rowEntity;
    private Integer rowIndex;
    private Integer columnIndex;

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

            if (json.has("workbook")) {
                this.workbook = NodeFactory.createURI(json.getJSONObject("workbook").getString("@id"));
            }
            if (json.has("sheet")) {
                this.sheet = NodeFactory.createURI(json.getJSONObject("sheet").getString("@id"));
            }
            if (json.has("column")) {
                this.column = NodeFactory.createURI(json.getJSONObject("column").getString("@id"));
            }
            if (json.has("rowIndex")) {
                this.rowIndex = json.getInt("rowIndex");
            }
            if (json.has("columnIndex")) {
                this.columnIndex = json.getInt("columnIndex");
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
        return builder().fromJson(json).build();
    }

    public JSONObject toJson() {
        JSONArray content = new JSONArray();

        for (Node node : nodes) {
            content.put(toJsonLd(node));
        }

        JSONObject json = new JSONObject().put("content", content);

        if (workbook != null) {
            json.put("workbook", new JSONObject().put("@id", workbook.getURI()));
        }
        if (sheet != null) {
            json.put("sheet", new JSONObject().put("@id", sheet.getURI()));
        }
        if (column != null) {
            json.put("column", new JSONObject().put("@id", column.getURI()));
        }
        if (rowEntity != null) {
            json.put("rowEntity", new JSONObject().put("@id", rowEntity.getURI()));
        }
        if (rowIndex != null) {
            json.put("rowIndex", rowIndex);
        }
        if (columnIndex != null) {
            json.put("columnIndex", columnIndex);
        }

        return json;
    }

    public static JSONObject toJsonLd(Node node) {
        if (node.isURI()) {
            return new JSONObject()
                    .put("@id", node.getURI());
        }

        if (node.isLiteral()) {
            String lexicalForm = node.getLiteralLexicalForm();
            String language = node.getLiteralLanguage();
            String datatype = node.getLiteralDatatypeURI();

            JSONObject json = new JSONObject();

            // Language-tagged string
            if (language != null && !language.isEmpty()) {
                json.put("@value", lexicalForm);
                json.put("@language", language);
                return json;
            }

            // Native JSON types
            if (XSDboolean.getURI().equals(datatype)) {
                json.put("@value", Boolean.parseBoolean(lexicalForm));
                json.put("@type", datatype);
                return json;
            }

            if (XSDinteger.getURI().equals(datatype)
                    || XSDint.getURI().equals(datatype)
                    || XSDlong.getURI().equals(datatype)
                    || XSDshort.getURI().equals(datatype)
                    || XSDbyte.getURI().equals(datatype)
                    || XSDnonNegativeInteger.getURI().equals(datatype)
                    || XSDpositiveInteger.getURI().equals(datatype)
                    || XSDnonPositiveInteger.getURI().equals(datatype)
                    || XSDnegativeInteger.getURI().equals(datatype)
                    || XSDunsignedLong.getURI().equals(datatype)
                    || XSDunsignedInt.getURI().equals(datatype)
                    || XSDunsignedShort.getURI().equals(datatype)
                    || XSDunsignedByte.getURI().equals(datatype)) {
                json.put("@value", Long.parseLong(lexicalForm));
                json.put("@type", datatype);
                return json;
            }

            if (XSDdecimal.getURI().equals(datatype)
                    || XSDdouble.getURI().equals(datatype)
                    || XSDfloat.getURI().equals(datatype)) {
                json.put("@value", Double.parseDouble(lexicalForm));
                json.put("@type", datatype);
                return json;
            }

            // Plain/string literal
            if (datatype == null
                    || XSDstring.getURI().equals(datatype)) {
                json.put("@value", lexicalForm);
                if (datatype != null) {
                    json.put("@type", datatype);
                }
                return json;
            }

            // Other explicitly typed literal
            json.put("@value", lexicalForm);
            json.put("@type", datatype);
            return json;
        }

        throw new IllegalArgumentException("Unsupported Node type: " + node);
    }

    public static Node fromJsonLd(JSONObject json) {
        if (json.has("@id")) {
            return NodeFactory.createURI(json.getString("@id"));
        }

        if (!json.has("@value")) {
            throw new IllegalArgumentException(
                    "Invalid JSON-LD node: " + json);
        }

        Object value = json.get("@value");

        // JSON-LD language-tagged literal
        if (json.has("@language")) {
            if (!(value instanceof String)) {
                throw new IllegalArgumentException(
                        "@language requires a string @value: " + json);
            }

            return NodeFactory.createLiteralLang(
                    (String) value,
                    json.getString("@language"));
        }

        // Explicit datatype
        if (json.has("@type")) {
            String datatype = json.getString("@type");

            if (!(value instanceof String)) {
                throw new IllegalArgumentException(
                        "Explicit @type requires a string @value: " + json);
            }

            return NodeFactory.createLiteralDT(
                    (String) value,
                    NodeFactory.getType(datatype));
        }

        // Native JSON boolean
        if (value instanceof Boolean) {
            return NodeFactory.createLiteralDT(
                    value.toString(),
                    NodeFactory.getType(
                            "http://www.w3.org/2001/XMLSchema#boolean"));
        }

        // Native JSON number
        if (value instanceof Number) {
            String lexicalForm = value.toString();

            String datatype;

            if (value instanceof Byte
                    || value instanceof Short
                    || value instanceof Integer
                    || value instanceof Long) {
                datatype
                        = "http://www.w3.org/2001/XMLSchema#integer";
            } else {
                datatype
                        = "http://www.w3.org/2001/XMLSchema#double";
                // TODO: Native JSON numbers are mapped to xsd:integer/xsd:double,
                //so xsd:decimal and other numeric datatypes can be lost; 
                //preserve the datatype via @type (or disable native numeric conversion) for lossless round-tripping.
            }

            return NodeFactory.createLiteralDT(
                    lexicalForm,
                    NodeFactory.getType(datatype));
        }

        // Native JSON string
        if (value instanceof String) {
            return NodeFactory.createLiteralString((String) value);
        }

        throw new IllegalArgumentException(
                "Unsupported @value type: "
                + value.getClass().getName());
    }

    public boolean isEmpty() {
        return nodes.isEmpty();
    }

}

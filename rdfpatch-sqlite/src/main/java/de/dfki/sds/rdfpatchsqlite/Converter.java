package de.dfki.sds.rdfpatchsqlite;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.rdfpatch.RDFPatch;
import org.apache.jena.rdfpatch.changes.RDFChangesBase;
import org.apache.jena.rdfpatch.changes.RDFChangesCollector;

/**
 *
 */
public class Converter {

    /**
     * @deprecated better use batch version
     */
    @Deprecated
    public void toSqlite(RDFPatch patch, String jdbcLink) {
        try (Connection conn = DriverManager.getConnection(jdbcLink); Statement stmt = conn.createStatement()) {

            stmt.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS rdfpatch ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "op TEXT,"
                    + "g TEXT,"
                    + "s TEXT,"
                    + "p TEXT,"
                    + "o TEXT,"
                    + // URI of object (if not a literal)
                    "l_lex TEXT,"
                    + // lexical form of literal
                    "l_lang TEXT,"
                    + // language tag of literal
                    "l_dt TEXT,"
                    + // datatype URI of literal
                    "prefix TEXT,"
                    + "uri TEXT,"
                    + "field TEXT,"
                    + "created_at INTEGER NOT NULL DEFAULT (unixepoch())"
                    + ")"
            );

            String sql = "INSERT INTO rdfpatch (op,g,s,p,o,l_lex,l_lang,l_dt,prefix,uri,field) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?,?)";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {

                RDFChangesBase handler = new RDFChangesBase() {
                    private void addRow(String op,
                            Node g,
                            Node s,
                            Node p,
                            Node o,
                            String prefix,
                            String uri,
                            String field) throws SQLException {

                        ps.setString(1, op);
                        ps.setString(2, g == null ? null : g.toString());
                        ps.setString(3, s == null ? null : s.toString());
                        ps.setString(4, p == null ? null : p.toString());

                        // ----- object handling (URI or literal) -----
                        if (o == null) {
                            ps.setNull(5, java.sql.Types.VARCHAR);   // o (URI)
                            ps.setNull(6, java.sql.Types.VARCHAR);   // l_lex
                            ps.setNull(7, java.sql.Types.VARCHAR);   // l_lang
                            ps.setNull(8, java.sql.Types.VARCHAR);   // l_dt
                        } else if (o.isLiteral()) {
                            ps.setNull(5, java.sql.Types.VARCHAR);                       // no URI
                            ps.setString(6, o.getLiteralLexicalForm());         // lexical
                            ps.setString(7, o.getLiteralLanguage());            // lang (may be empty)
                            ps.setString(8, o.getLiteralDatatypeURI());         // datatype (may be null)
                        } else { // URI node
                            ps.setString(5, o.toString());
                            ps.setNull(6, java.sql.Types.VARCHAR);
                            ps.setNull(7, java.sql.Types.VARCHAR);
                            ps.setNull(8, java.sql.Types.VARCHAR);
                        }

                        ps.setString(9, prefix);
                        ps.setString(10, uri);
                        ps.setString(11, field);
                        ps.executeUpdate();
                    }

                    @Override
                    public void start() {
                    }

                    @Override
                    public void finish() {
                    }

                    @Override
                    public void header(String field, Node value) {
                        try {
                            addRow("H", null, null, null, value, null, null, field);
                        } catch (Exception ex) {
                        }
                    }

                    @Override
                    public void add(Node g, Node s, Node p, Node o) {
                        try {
                            addRow("A", g, s, p, o, null, null, null);
                        } catch (Exception ex) {
                        }
                    }

                    @Override
                    public void delete(Node g, Node s, Node p, Node o) {
                        try {
                            addRow("D", g, s, p, o, null, null, null);
                        } catch (Exception ex) {
                        }
                    }

                    @Override
                    public void addPrefix(Node graph, String prefix, String uriStr) {
                        try {
                            addRow("PA", graph, null, null, null, prefix, uriStr, null);
                        } catch (Exception ex) {
                        }
                    }

                    @Override
                    public void deletePrefix(Node graph, String prefix) {
                        try {
                            addRow("PD", graph, null, null, null, prefix, null, null);
                        } catch (Exception ex) {
                        }
                    }

                    @Override
                    public void txnBegin() {
                        try {
                            addRow("TX", null, null, null, null, null, null, null);
                        } catch (Exception ex) {
                        }
                    }

                    @Override
                    public void txnCommit() {
                        try {
                            addRow("TC", null, null, null, null, null, null, null);
                        } catch (Exception ex) {
                        }
                    }

                    @Override
                    public void txnAbort() {
                        try {
                            addRow("TA", null, null, null, null, null, null, null);
                        } catch (Exception ex) {
                        }
                    }
                };

                patch.apply(handler);
            }
        } catch (Exception e) {
            // handle as appropriate
        }
    }

    public void toSqlite(RDFPatch patch, String jdbcLink, int batchSize) {
        try (Connection conn = DriverManager.getConnection(jdbcLink); Statement stmt = conn.createStatement()) {

            conn.setAutoCommit(false); // important for batching

            stmt.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS rdfpatch ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "op TEXT,"
                    + "g TEXT,"
                    + "s TEXT,"
                    + "p TEXT,"
                    + "o TEXT,"
                    + "l_lex TEXT,"
                    + "l_lang TEXT,"
                    + "l_dt TEXT,"
                    + "prefix TEXT,"
                    + "uri TEXT,"
                    + "field TEXT,"
                    + "created_at INTEGER NOT NULL DEFAULT (unixepoch())"
                    + ")"
            );

            String sql = "INSERT INTO rdfpatch (op,g,s,p,o,l_lex,l_lang,l_dt,prefix,uri,field) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?,?)";

            try (PreparedStatement ps = conn.prepareStatement(sql)) {

                final int[] counter = {0};

                RDFChangesBase handler = new RDFChangesBase() {

                    private void flushIfNeeded() throws SQLException {
                        if (counter[0] % batchSize == 0) {
                            ps.executeBatch();
                            conn.commit();
                        }
                    }

                    private void addRow(String op,
                            Node g,
                            Node s,
                            Node p,
                            Node o,
                            String prefix,
                            String uri,
                            String field) throws SQLException {

                        ps.setString(1, op);
                        ps.setString(2, g == null ? null : g.toString());
                        ps.setString(3, s == null ? null : s.toString());
                        ps.setString(4, p == null ? null : p.toString());

                        if (o == null) {
                            ps.setNull(5, java.sql.Types.VARCHAR);
                            ps.setNull(6, java.sql.Types.VARCHAR);
                            ps.setNull(7, java.sql.Types.VARCHAR);
                            ps.setNull(8, java.sql.Types.VARCHAR);
                        } else if (o.isLiteral()) {
                            ps.setNull(5, java.sql.Types.VARCHAR);
                            ps.setString(6, o.getLiteralLexicalForm());
                            ps.setString(7, o.getLiteralLanguage());
                            ps.setString(8, o.getLiteralDatatypeURI());
                        } else {
                            ps.setString(5, o.toString());
                            ps.setNull(6, java.sql.Types.VARCHAR);
                            ps.setNull(7, java.sql.Types.VARCHAR);
                            ps.setNull(8, java.sql.Types.VARCHAR);
                        }

                        ps.setString(9, prefix);
                        ps.setString(10, uri);
                        ps.setString(11, field);

                        ps.addBatch();
                        counter[0]++;

                        flushIfNeeded();
                    }

                    @Override
                    public void start() {
                    }

                    @Override
                    public void finish() {
                        try {
                            ps.executeBatch(); // flush remaining
                            conn.commit();
                        } catch (Exception ex) {
                        }
                    }

                    @Override
                    public void header(String field, Node value) {
                        try {
                            addRow("H", null, null, null, value, null, null, field);
                        } catch (Exception ex) {
                        }
                    }

                    @Override
                    public void add(Node g, Node s, Node p, Node o) {
                        try {
                            addRow("A", g, s, p, o, null, null, null);
                        } catch (Exception ex) {
                        }
                    }

                    @Override
                    public void delete(Node g, Node s, Node p, Node o) {
                        try {
                            addRow("D", g, s, p, o, null, null, null);
                        } catch (Exception ex) {
                        }
                    }

                    @Override
                    public void addPrefix(Node graph, String prefix, String uriStr) {
                        try {
                            addRow("PA", graph, null, null, null, prefix, uriStr, null);
                        } catch (Exception ex) {
                        }
                    }

                    @Override
                    public void deletePrefix(Node graph, String prefix) {
                        try {
                            addRow("PD", graph, null, null, null, prefix, null, null);
                        } catch (Exception ex) {
                        }
                    }

                    @Override
                    public void txnBegin() {
                        try {
                            addRow("TX", null, null, null, null, null, null, null);
                        } catch (Exception ex) {
                        }
                    }

                    @Override
                    public void txnCommit() {
                        try {
                            addRow("TC", null, null, null, null, null, null, null);
                        } catch (Exception ex) {
                        }
                    }

                    @Override
                    public void txnAbort() {
                        try {
                            addRow("TA", null, null, null, null, null, null, null);
                        } catch (Exception ex) {
                        }
                    }
                };

                patch.apply(handler);
                
                //just in case, because finish() might not be called
                try {
                    ps.executeBatch(); // flush remaining
                    conn.commit();
                } catch (Exception ex) {
                }
            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    //special case: patch contains urn:atic:{id}:{enc-uri} that contains also the id
    public void toSqliteUnwrap(RDFPatch patch, String jdbcLink, int batchSize) {
        try (Connection conn = DriverManager.getConnection(jdbcLink); Statement stmt = conn.createStatement()) {

            conn.setAutoCommit(false); // important for batching

            stmt.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS rdfpatch ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "op TEXT,"
                    + "g TEXT,"
                    + "g_id INTEGER,"
                    + "s TEXT,"
                    + "s_id INTEGER,"
                    + "p TEXT,"
                    + "p_id INTEGER,"
                    + "o TEXT,"
                    + "o_id INTEGER,"
                    + "l_lex TEXT,"
                    + "l_lang TEXT,"
                    + "l_dt TEXT,"
                    + "prefix TEXT,"
                    + "uri TEXT,"
                    + "field TEXT,"
                    + "created_at INTEGER NOT NULL DEFAULT (unixepoch())"
                    + ")"
            );

            String sql = "INSERT INTO rdfpatch (op,g,g_id,s,s_id,p,p_id,o,o_id,l_lex,l_lang,l_dt,prefix,uri,field) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

            try (PreparedStatement ps = conn.prepareStatement(sql)) {

                final int[] counter = {0};

                RDFChangesBase handler = new RDFChangesBase() {

                    private void flushIfNeeded() throws SQLException {
                        if (counter[0] % batchSize == 0) {
                            ps.executeBatch();
                            conn.commit();
                        }
                    }

                    private void addRow(String op,
                            Node g,
                            Node s,
                            Node p,
                            Node o,
                            String prefix,
                            String uri,
                            String field) throws SQLException {

                        Object[] gUnwrap = unwrapIdAndUri(g);
                        Object[] sUnwrap = unwrapIdAndUri(s);
                        Object[] pUnwrap = unwrapIdAndUri(p);
                        
                        Object[] oUnwrap;
                        if(op.equals("H")) {
                            oUnwrap = new Object[] { -1L, o };
                        } else {
                            oUnwrap = unwrapIdAndUri(o);
                        }

                        ps.setString(1, op);
                        ps.setString(2, g == null ? null : ((Node) gUnwrap[1]).getURI());
                        if(g == null)
                            ps.setNull(3, java.sql.Types.INTEGER);
                        else
                            ps.setLong(3, (Long) gUnwrap[0]);

                        ps.setString(4, s == null ? null : ((Node) sUnwrap[1]).getURI());
                        if(g == null)
                            ps.setNull(5, java.sql.Types.INTEGER);
                        else
                            ps.setLong(5, (Long) sUnwrap[0]);

                        ps.setString(6, p == null ? null : ((Node) pUnwrap[1]).getURI());
                        if(g == null)
                            ps.setNull(7, java.sql.Types.INTEGER);
                        else
                            ps.setLong(7, (Long) pUnwrap[0]);

                        if (o == null) {
                            ps.setNull(8, java.sql.Types.VARCHAR);
                            ps.setNull(9, java.sql.Types.INTEGER);

                            ps.setNull(10, java.sql.Types.VARCHAR);
                            ps.setNull(11, java.sql.Types.VARCHAR);
                            ps.setNull(12, java.sql.Types.VARCHAR);
                        } else if (o.isLiteral()) {
                            ps.setNull(8, java.sql.Types.VARCHAR);
                            ps.setNull(9, java.sql.Types.INTEGER);

                            ps.setString(10, o.getLiteralLexicalForm());
                            ps.setString(11, o.getLiteralLanguage());
                            ps.setString(12, o.getLiteralDatatypeURI());
                        } else {
                            ps.setString(8, ((Node) oUnwrap[1]).getURI());
                            ps.setLong(9, (Long) oUnwrap[0]);

                            ps.setNull(10, java.sql.Types.VARCHAR);
                            ps.setNull(11, java.sql.Types.VARCHAR);
                            ps.setNull(12, java.sql.Types.VARCHAR);
                        }

                        ps.setString(13, prefix);
                        ps.setString(14, uri);
                        ps.setString(15, field);

                        ps.addBatch();
                        counter[0]++;

                        flushIfNeeded();
                    }

                    @Override
                    public void start() {
                    }

                    @Override
                    public void finish() {
                        try {
                            ps.executeBatch(); // flush remaining
                            conn.commit();
                        } catch (Exception ex) {
                            throw new RuntimeException(ex);
                        }
                    }

                    @Override
                    public void header(String field, Node value) {
                        try {
                            addRow("H", null, null, null, value, null, null, field);
                        } catch (Exception ex) {
                            throw new RuntimeException(ex);
                        }
                    }

                    @Override
                    public void add(Node g, Node s, Node p, Node o) {
                        try {
                            addRow("A", g, s, p, o, null, null, null);
                        } catch (Exception ex) {
                            throw new RuntimeException(ex);
                        }
                    }

                    @Override
                    public void delete(Node g, Node s, Node p, Node o) {
                        try {
                            addRow("D", g, s, p, o, null, null, null);
                        } catch (Exception ex) {
                            throw new RuntimeException(ex);
                        }
                    }

                    @Override
                    public void addPrefix(Node graph, String prefix, String uriStr) {
                        try {
                            addRow("PA", graph, null, null, null, prefix, uriStr, null);
                        } catch (Exception ex) {
                            throw new RuntimeException(ex);
                        }
                    }

                    @Override
                    public void deletePrefix(Node graph, String prefix) {
                        try {
                            addRow("PD", graph, null, null, null, prefix, null, null);
                        } catch (Exception ex) {
                            throw new RuntimeException(ex);
                        }
                    }

                    @Override
                    public void txnBegin() {
                        try {
                            addRow("TX", null, null, null, null, null, null, null);
                        } catch (Exception ex) {
                            throw new RuntimeException(ex);
                        }
                    }

                    @Override
                    public void txnCommit() {
                        try {
                            addRow("TC", null, null, null, null, null, null, null);
                        } catch (Exception ex) {
                            throw new RuntimeException(ex);
                        }
                    }

                    @Override
                    public void txnAbort() {
                        try {
                            addRow("TA", null, null, null, null, null, null, null);
                        } catch (Exception ex) {
                            throw new RuntimeException(ex);
                        }
                    }
                };

                patch.apply(handler);

                //just in case, because finish() might not be called
                try {
                    ps.executeBatch(); // flush remaining
                    conn.commit();
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void toPatch(String jdbcLink, RDFChangesCollector collector) {
        try (Connection conn = DriverManager.getConnection(jdbcLink); Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("SELECT * FROM rdfpatch ORDER BY id")) {

            while (rs.next()) {
                String op = rs.getString("op");
                String gStr = rs.getString("g");
                String sStr = rs.getString("s");
                String pStr = rs.getString("p");
                String oStr = rs.getString("o");
                String lex = rs.getString("l_lex");
                String lang = rs.getString("l_lang");
                String dt = rs.getString("l_dt");
                String prefix = rs.getString("prefix");
                String uri = rs.getString("uri");
                String field = rs.getString("field");

                Node g = gStr == null ? null : NodeFactory.createURI(gStr);
                Node s = sStr == null ? null : NodeFactory.createURI(sStr);
                Node p = pStr == null ? null : NodeFactory.createURI(pStr);
                Node o = null;

                if (lex != null) {                     // literal
                    if (dt != null && !dt.isEmpty()) {
                        o = NodeFactory.createLiteralDT(lex, NodeFactory.getType(dt));
                    } else if (lang != null && !lang.isEmpty()) {
                        o = NodeFactory.createLiteralLang(lex, lang);
                    } else {
                        o = NodeFactory.createLiteralString(lex);
                    }
                } else if (oStr != null) {             // URI
                    o = NodeFactory.createURI(oStr);
                }

                switch (op) {
                    case "H":
                        collector.header(field, o);
                        break;
                    case "TX":
                        collector.txnBegin();
                        break;
                    case "TC":
                        collector.txnCommit();
                        break;
                    case "TA":
                        collector.txnAbort();
                        break;
                    case "PA":
                        collector.addPrefix(g, prefix, uri);
                        break;
                    case "PD":
                        collector.deletePrefix(g, prefix);
                        break;
                    case "A":
                        collector.add(g, s, p, o);
                        break;
                    case "D":
                        collector.delete(g, s, p, o);
                        break;
                    default:
                    // unknown op – ignore
                }
            }
        } catch (Exception e) {
            // handle as appropriate
        }
    }

    public RDFPatch toPatch(String jdbcLink) {
        RDFChangesCollector collector = new RDFChangesCollector();
        toPatch(jdbcLink, collector);
        return collector.getRDFPatch();
    }

    //----------------------------------------
    //helper
    //code from RDFPatchEmitterTransactional
    //but because of dependency direction here in simpler form wtih Object[]
    public static Object[] unwrapIdAndUri(Node n) {
        if (n == null) {
            return null;
        }

        if (n.isLiteral()) {
            return new Object[]{ -1L, n };
        }

        //urn:atic:{id}:{enc-uri}
        String[] segments = n.getURI().split(":");

        long id = Long.parseLong(segments[segments.length - 2]);
        String encUri = segments[segments.length - 1];
        Node node = NodeFactory.createURI(URLDecoder.decode(encUri, StandardCharsets.UTF_8));

        return new Object[]{id, node};
    }
    
    public static Node unwrapNode(Node n) {
        Node node = (Node) unwrapIdAndUri(n)[1];
        return node;
    }
    
    public static String unwrapUri(Node n) {
        Node node = unwrapNode(n);
        if(node.isURI()) {
            return node.getURI();
        }
        return null;
    }
}

package de.dfki.sds.aticsqlite;

import de.dfki.sds.atic.jenatic.AticGraph;
import de.dfki.sds.atic.jenatic.InvocationContext;
import java.io.File;
import java.io.InputStream;
import java.sql.SQLException;
import java.util.List;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.GraphEventManager;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.TransactionHandler;
import org.apache.jena.graph.Triple;
import org.apache.jena.graph.impl.GraphMatcher;
import org.apache.jena.graph.impl.SimpleEventManager;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.shared.PrefixMapping;
import org.apache.jena.sparql.graph.PrefixMappingAdapter;
import org.apache.jena.sparql.graph.TransactionHandlerNull;
import org.apache.jena.util.iterator.ExtendedIterator;

/**
 *
 */
public class BlobGraph implements AticGraph {

    public static final Node node = NodeFactory.createURI("urn:atic:blob");

    private SqliteAticDatasetGraph datasetGraph;

    private final TransactionHandler transactionHandler;
    private final GraphEventManager graphEventManager;

    private Database db;
    
    private File rootFolder;

    public BlobGraph(SqliteAticDatasetGraph datasetGraph) {
        this.datasetGraph = datasetGraph;
        this.db = datasetGraph.getDatabase();

        File dbFolder = this.db.getFolder();
        this.rootFolder = new File(dbFolder, "blob");

        transactionHandler = new TransactionHandlerNull();
        graphEventManager = new SimpleEventManager();
    }

    /*package*/ void bootstrap() throws SQLException {
        db.writeQuery("create_table_blobgraph.sql");
    }
    
    //---------------------------------
    //file management
    
    public void save(String filepath, String originalFilename, String mimeType, InputStream content, InvocationContext ctx) {
        
    }
    
    public InputStream loadContent(String filepath, InvocationContext ctx) {
        return null;
    }
    
    public Model loadMetadata(String filepath, InvocationContext ctx) {
        return null;
    }
    
    //lists files of a folder
    public List<String> list(String folderpath, InvocationContext ctx) {
        
        return null;
    }
    
    public void move(String srcFilepath, String trgFilepath, InvocationContext ctx) {
    }
    
    public void copy(String srcFilepath, String trgFilepath, InvocationContext ctx) {
    }
    
    public void link(String srcFilepath, String trgFilepath, InvocationContext ctx) {
    }
    
    public String getURI(String filepath, InvocationContext ctx) {
        return null;
    }
    
    
    //----------------------------------
    //read
    //TODO
    @Override
    public ExtendedIterator<Triple> find(Node s, Node p, Node o, InvocationContext ctx) {
        ctx = InvocationContext.fromContextIfEmpty(ctx, datasetGraph.getContext());

        return null;
    }

    //TODO
    @Override
    public boolean contains(Node s, Node p, Node o, InvocationContext ctx) {
        ctx = InvocationContext.fromContextIfEmpty(ctx, datasetGraph.getContext());

        return false;
    }

    //TODO
    @Override
    public int size(InvocationContext ctx) {
        ctx = InvocationContext.fromContextIfEmpty(ctx, datasetGraph.getContext());

        return 0;
    }

    //-----------------------------
    //write
    @Override
    public void add(Triple t, InvocationContext ctx) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void remove(Node s, Node p, Node o, InvocationContext ctx) {
        throw new UnsupportedOperationException();
    }

    //delete reuses remove
    @Override
    public void delete(Triple t, InvocationContext ctx) {
        throw new UnsupportedOperationException();
    }

    //clear reuses remove with Node.ANY, Node.ANY, Node.ANY
    @Override
    public void clear(InvocationContext ctx) {
        throw new UnsupportedOperationException();
    }

    //------------------------------------------------------
    //the delegates (e.g. opens Triple and delegates to s,p,o method)
    @Override
    public boolean contains(Triple t, InvocationContext ctx) {
        return contains(t.getSubject(), t.getPredicate(), t.getObject(), ctx);
    }

    @Override
    public ExtendedIterator<Triple> find(Triple t, InvocationContext ctx) {
        return find(t.getSubject(), t.getPredicate(), t.getObject(), ctx);
    }

    @Override
    public boolean isEmpty(InvocationContext ctx) {
        return size(ctx) == 0;
    }

    //============================
    //management and other
    @Override
    public void close(InvocationContext ctx) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public boolean isClosed(InvocationContext ctx) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public PrefixMapping getPrefixMapping(InvocationContext ctx) {
        return new PrefixMappingAdapter(datasetGraph.prefixes(ctx));
    }

    @Override
    public TransactionHandler getTransactionHandler() {
        return transactionHandler;
    }

    @Override
    public GraphEventManager getEventManager() {
        return graphEventManager;
    }

    @Override
    public boolean isIsomorphicWith(Graph g, InvocationContext ctx) {
        //TODO isIsomorphicWith currently does not check permissions
        return GraphMatcher.equals(this, g);
    }

    @Override
    public boolean dependsOn(Graph other, InvocationContext ctx) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    //============================================
    //helper
    private void valid(Triple t) {
        Node s = t.getSubject();
        Node p = t.getPredicate();
        Node o = t.getObject();
        valid(s, p, o);
    }

    private void valid(Node s, Node p, Node o) {

        // -------------------------
        // SUBJECT (allow ANY)
        // -------------------------
        if (!s.equals(Node.ANY) && !s.isURI()) {
            throw new IllegalArgumentException("Invalid subject: " + s);
        }

        // -------------------------
        // PREDICATE (allow ANY)
        // -------------------------
        if (!p.equals(Node.ANY) && !p.isURI()) {
            throw new IllegalArgumentException("Predicate must be URI: " + p);
        }

        // -------------------------
        // OBJECT (allow ANY)
        // -------------------------
        if (!o.equals(Node.ANY) && !o.isLiteral()) {
            throw new IllegalArgumentException("Invalid object: " + o);
        }
    }

}

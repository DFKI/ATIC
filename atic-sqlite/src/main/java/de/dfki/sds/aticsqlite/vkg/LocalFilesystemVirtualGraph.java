package de.dfki.sds.aticsqlite.vkg;

import de.dfki.sds.atic.api.UriMapper;
import de.dfki.sds.atic.jenatic.AticGraph;
import de.dfki.sds.atic.jenatic.AticVirtualGraph;
import de.dfki.sds.atic.jenatic.AticVirtualGraphResponse;
import de.dfki.sds.atic.jenatic.InvocationContext;
import de.dfki.sds.atic.vocab.NFO;
import de.dfki.sds.aticsqlite.SqliteAticDatasetGraph;
import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.GraphEventManager;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.TransactionHandler;
import org.apache.jena.graph.Triple;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.shared.PrefixMapping;
import org.apache.jena.shared.impl.PrefixMappingImpl;
import org.apache.jena.sparql.graph.TransactionHandlerNull;
import org.apache.jena.util.iterator.ExtendedIterator;
import org.apache.jena.util.iterator.NiceIterator;
import org.apache.jena.util.iterator.WrappedIterator;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.SKOS;
import org.json.JSONObject;

/**
 *
 */
public class LocalFilesystemVirtualGraph implements AticVirtualGraph {

    private UriMapper<File> fileUriMapper;

    private Resource classFile;
    private Resource classFolder;
    private Property propertyLabel;
    private Property propertyPrefLabel;
    private Property propertyFileName;
    private Property propertyHidden;
    private Property propertyBelongsToContainer;
    private Property propertyContains;

    private UriMapper<File> fileMapper;
    private String host;
    
    private PrefixMapping prefixMapping;

    public LocalFilesystemVirtualGraph(String host) {
        this.host = host;
        this.prefixMapping = new PrefixMappingImpl();
        
        defaultProperties();
        
        this.fileUriMapper = new UriMapper<File>() {
            @Override
            public File toObject(String uri) {

                //normal file:/ uris are also allowed
                //but they will not work with the VKG
                if (uri.startsWith("file:/")) {
                    uri = uri.replace("+", "%20");
                    File f;
                    try {
                        f = new File(new URI(uri));
                    } catch (URISyntaxException ex) {
                        throw new RuntimeException(ex);
                    }

                    return f;
                }

                String prefix = host; //+ "/file";

                if (!uri.startsWith(prefix)) {
                    return null;
                }

                //cut away prefix
                String fileUri = uri.substring(prefix.length());

                //add file schema
                fileUri = "file:" + fileUri;

                //the "new File(new URI" does not understand + so we have to turn it into %20
                fileUri = fileUri.replace("+", "%20");

                File f;
                try {
                    f = new File(new URI(fileUri));
                } catch (URISyntaxException ex) {
                    throw new RuntimeException(ex);
                }

                return f;
            }

            @Override
            public String toURI(File obj) {

                URI fileURI = obj.toURI();
                //cut away the file schema
                String fileURIStr = fileURI.toString().substring("file:".length());

                StringBuilder uri = new StringBuilder();
                uri.append(host);
                //uri.append("/file");
                //append path
                uri.append(fileURIStr);

                return uri.toString();
            }
        };
    }

    public LocalFilesystemVirtualGraph(UriMapper<File> fileUriMapper) {
        this.fileUriMapper = fileUriMapper;
        this.prefixMapping = new PrefixMappingImpl();
        defaultProperties();
    }

    public final void defaultProperties() {
        classFile = NFO.FileDataObject;
        classFolder = NFO.Folder;

        propertyLabel = RDFS.label;
        propertyPrefLabel = SKOS.prefLabel;
        propertyFileName = NFO.fileName;
        propertyHidden = NFO.hidden;
        propertyBelongsToContainer = NFO.belongsToContainer;
        propertyContains = NFO.contains;
    }
    
    @Override
    public ExtendedIterator<Triple> find(Triple triple, InvocationContext ctx) {
        //TODO we could map atic user id to OS user id to check access
        //ctx.getUserId()
        
        //we do not answer queries with unknown subject
        if (triple.getSubject() == Node.ANY) {
            return NiceIterator.emptyIterator();
        }

        //interpret the URI to local file
        File subject = fileUriMapper.toObject(triple.getSubject().getURI());

        //not a local file
        if (subject == null) {
            return NiceIterator.emptyIterator();
        }

        //file does not exist
        if (!subject.exists()) {
            return NiceIterator.emptyIterator();
        }

        //file to triples
        Set<Triple> triples = triplify(subject, triple);

        //only matching triples are returned
        Triple pattern = triple;
        return WrappedIterator.create(triples.iterator()).filterKeep(t -> {
            boolean match = pattern.matches(t);

            return match;
        });
    }
    
    @Override
    public ExtendedIterator<Triple> find(Node s, Node p, Node o, InvocationContext ctx) {
        return find(Triple.create(s, p, o), ctx);
    }
    
    @Override
    public boolean contains(Node s, Node p, Node o, InvocationContext ctx) {
        return contains(Triple.create(s, p, o), ctx);
    }

    @Override
    public boolean contains(Triple t, InvocationContext ctx) {
        ExtendedIterator<Triple> iter = find(t, ctx);
        boolean hasNext = iter.hasNext();
        iter.close();
        return hasNext;
    }
    
    @Override
    public boolean isEmpty(InvocationContext ctx) {
        return false;
    }

    @Override
    public int size(InvocationContext ctx) {
        return -1;
    }

    
    @Override
    public TransactionHandler getTransactionHandler() {
        return new TransactionHandlerNull();
    }
    
    /**
     * Turns file to set of triples.
     *
     * @param f
     * @return
     */
    private Set<Triple> triplify(File f, Triple triple) {
        Set<Triple> triples = new HashSet<>();

        //to avoid filter it because toURI will create a version with ending '/'
        Node s = triple.getSubject(); //NodeFactory.createURI(fileUriMapper.toURI(f));

        if (f.isDirectory()) {
            triples.add(Triple.create(s, RDF.type.asNode(), classFolder.asNode()));
        } else {
            triples.add(Triple.create(s, RDF.type.asNode(), classFile.asNode()));
        }

        if (propertyLabel != null) {
            triples.add(Triple.create(s, propertyLabel.asNode(), NodeFactory.createLiteralString(f.getName())));
        }
        if (propertyPrefLabel != null) {
            triples.add(Triple.create(s, propertyPrefLabel.asNode(), NodeFactory.createLiteralString(f.getName())));
        }
        if (propertyFileName != null) {
            triples.add(Triple.create(s, propertyFileName.asNode(), NodeFactory.createLiteralString(f.getName())));
        }

        if (propertyHidden != null) {
            triples.add(Triple.create(s, propertyHidden.asNode(), NodeFactory.createLiteralDT("" + f.isHidden(), XSDDatatype.XSDboolean)));
        }

        /*
        if(propertyBelongsToContainer != null && f.getParentFile() != null) {
            triples.add(Triple.create(s, propertyBelongsToContainer.asNode(), NodeFactory.createURI(fileUriMapper.toURI(f.getParentFile()))));
        }
         */
        if (propertyContains != null) {
            //better use propertyContains as incoming for the belongsToContainer case
            if (f.getParentFile() != null) {
                triples.add(Triple.create(NodeFactory.createURI(fileUriMapper.toURI(f.getParentFile())), propertyContains.asNode(), s));
            }

            File[] array = f.listFiles();
            if (array != null) {
                for (File child : array) {
                    if (child.isHidden()) {
                        continue;
                    }
                    triples.add(Triple.create(s, propertyContains.asNode(), NodeFactory.createURI(fileUriMapper.toURI(child))));
                }
            }
        }
        return triples;
    }

    //---------------------------------------------------
    //properties
    public Property getPropertyLabel() {
        return propertyLabel;
    }

    public void setPropertyLabel(Property propertyLabel) {
        this.propertyLabel = propertyLabel;
    }

    public Property getPropertyPrefLabel() {
        return propertyPrefLabel;
    }

    public void setPropertyPrefLabel(Property propertyPrefLabel) {
        this.propertyPrefLabel = propertyPrefLabel;
    }

    public Property getPropertyFileName() {
        return propertyFileName;
    }

    public void setPropertyFileName(Property propertyFileName) {
        this.propertyFileName = propertyFileName;
    }

    public Property getPropertyHidden() {
        return propertyHidden;
    }

    public void setPropertyHidden(Property propertyHidden) {
        this.propertyHidden = propertyHidden;
    }

    public Property getPropertyBelongsToContainer() {
        return propertyBelongsToContainer;
    }

    public void setPropertyBelongsToContainer(Property propertyBelongsToContainer) {
        this.propertyBelongsToContainer = propertyBelongsToContainer;
    }

    public Property getPropertyContains() {
        return propertyContains;
    }

    public void setPropertyContains(Property propertyContains) {
        this.propertyContains = propertyContains;
    }

    public Resource getClassFile() {
        return classFile;
    }

    public void setClassFile(Resource classFile) {
        this.classFile = classFile;
    }

    public Resource getClassFolder() {
        return classFolder;
    }

    public void setClassFolder(Resource classFolder) {
        this.classFolder = classFolder;
    }

    public UriMapper<File> getFileUriMapper() {
        return fileUriMapper;
    }

    //=================================================================================
    
    @Override
    public boolean dependsOn(Graph other, InvocationContext ctx) {
        throw new UnsupportedOperationException("Not supported."); 
    }

    @Override
    public GraphEventManager getEventManager() {
        throw new UnsupportedOperationException("Not supported."); 
    }

    @Override
    public PrefixMapping getPrefixMapping(InvocationContext ctx) {
        return prefixMapping;
    }

    @Override
    public void add(Triple t, InvocationContext ctx) {
        throw new UnsupportedOperationException("Not supported."); 
    }

    @Override
    public void delete(Triple t, InvocationContext ctx) {
        throw new UnsupportedOperationException("Not supported."); 
    }

    @Override
    public boolean isIsomorphicWith(Graph g, InvocationContext ctx) {
        throw new UnsupportedOperationException("Not supported."); 
    }

    @Override
    public void clear(InvocationContext ctx) {
        throw new UnsupportedOperationException("Not supported."); 
    }

    @Override
    public void remove(Node s, Node p, Node o, InvocationContext ctx) {
        throw new UnsupportedOperationException("Not supported."); 
    }

    @Override
    public void close(InvocationContext ctx) {
        throw new UnsupportedOperationException("Not supported."); 
    }

    @Override
    public boolean isClosed(InvocationContext ctx) {
        throw new UnsupportedOperationException("Not supported."); 
    }

    //=============================================================================
    
    public static AticGraph create(String uri, String config, SqliteAticDatasetGraph parent) {
        JSONObject configObj = new JSONObject(config);
        return new LocalFilesystemVirtualGraph(configObj.getString("host"));
    }

    @Override
    public AticVirtualGraphResponse handleRequest(String method, String path, Map<String, List<String>> queryParamMap, InvocationContext ctx) {
        throw new UnsupportedOperationException("Not supported.");
    }
    
}

package de.dfki.sds.aticsqlite.vkg;

import com.github.sardine.DavResource;
import com.github.sardine.Sardine;
import com.github.sardine.SardineFactory;
import de.dfki.sds.atic.ac.User;
import de.dfki.sds.atic.api.UriMapper;
import de.dfki.sds.atic.jenatic.AticGraph;
import de.dfki.sds.atic.jenatic.AticVirtualGraph;
import de.dfki.sds.atic.jenatic.AticVirtualGraphResponse;
import de.dfki.sds.atic.jenatic.InvocationContext;
import de.dfki.sds.atic.vocab.NFO;
import de.dfki.sds.aticsqlite.SqliteAticDatasetGraph;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.xml.namespace.QName;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.GraphEventManager;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.TransactionHandler;
import org.apache.jena.graph.Triple;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.shared.PrefixMapping;
import org.apache.jena.shared.impl.PrefixMappingImpl;
import org.apache.jena.sparql.graph.TransactionHandlerNull;
import org.apache.jena.util.iterator.ExtendedIterator;
import org.apache.jena.util.iterator.NiceIterator;
import org.apache.jena.util.iterator.WrappedIterator;
import org.apache.jena.vocabulary.DCAT;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.SKOS;
import org.json.JSONObject;

/**
 *
 */
public class WebdavVirtualGraph implements AticVirtualGraph {

    private Resource classFile;
    private Resource classFolder;
    private Property propertyLabel;
    private Property propertyPrefLabel;
    private Property propertyFileName;
    private Property propertyHidden;
    private Property propertyBelongsToContainer;
    private Property propertyContains;
    private Property propertyDownloadURL;
    private Property propertyNewFileName;
    private Property propertyNewFolderName;

    private PrefixMapping prefixMapping;

    private Map<String, Sardine> user2sardine;

    private URI webdavHost;
    private String instanceEndpoint;

    private JSONObject configObj;

    private SqliteAticDatasetGraph parent;

    //TODO it creates its own links and handles them: implement interface in AticVirtualGraph
    //TODO an add triple can be used to invoke something: rename file
    //TODO an remove triple can be used to invoke something: remove file
    public WebdavVirtualGraph(String uri, JSONObject configObj, SqliteAticDatasetGraph parent) {

        this.parent = parent;
        this.configObj = configObj;
        this.user2sardine = new HashMap<>();

        //endpoint
        String vkgEndpoint = this.configObj.getString("vkgEndpoint");
        instanceEndpoint = vkgEndpoint + "/" + URLEncoder.encode(uri, StandardCharsets.UTF_8);

        //extract webdav host
        String baseUrl = configObj.getString("baseUrl");
        URI baseUri = URI.create(baseUrl);
        webdavHost = URI.create(baseUri.getScheme() + "://" + baseUri.getHost() + (baseUri.getPort() != -1 ? ":" + baseUri.getPort() : ""));

        this.prefixMapping = new PrefixMappingImpl();

        defaultProperties();
    }

    //lazy user login
    private Sardine ensureSardine(int userId) {

        User user = parent.getUser(userId, InvocationContext.EMPTY);

        if (!user2sardine.containsKey(user.getUsername())) {

            //TODO later this should be loaded from SqliteAticDatasetGraph parent, maybe from table where user can config their credentials for other services
            JSONObject credentials = configObj.getJSONObject("credentials");
            JSONObject userCredentials = credentials.getJSONObject(user.getUsername());

            Sardine sardine = SardineFactory.begin(
                    userCredentials.getString("username"),
                    userCredentials.getString("password")
            );

            user2sardine.put(user.getUsername(), sardine);

            return sardine;
        }
        return user2sardine.get(user.getUsername());
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
        propertyDownloadURL = DCAT.downloadURL;

        propertyNewFileName = ResourceFactory.createProperty("urn:atic:newFileName");
        propertyNewFolderName = ResourceFactory.createProperty("urn:atic:newFolderName");
    }

    //idea: requests at http://127.0.0.1:6583/vkg/{uri} are forwarded here
    //vkg can decide how to respond
    @Override
    public AticVirtualGraphResponse handleRequest(String method, String path, Map<String, List<String>> queryParamMap, InvocationContext ictx) {
        
        Sardine sardine = ensureSardine(ictx.getUserId());
        
        AticVirtualGraphResponse.Builder b = new AticVirtualGraphResponse.Builder();
        
        if(method.equals("GET") && path.startsWith("/download/")) {
            
            String encUrl = path.split("/")[2];
            String url = URLDecoder.decode(encUrl, StandardCharsets.UTF_8);
            String[] segments = url.split("/");
            String filename = segments[segments.length - 1];
            
            try {
                InputStream is = sardine.get(url);
                b.inputStream(is);
                
                String type = URLConnection.guessContentTypeFromName(filename);
                b.contentType(type != null ? type : "application/octet-stream");
                
            } catch (IOException ex) {
                b.status(500);
                b.body("URL download error: " + url);
            }
            
            return b.build();
        }
        
        //not found
        b.status(404);
        return b.build();
    }
    
    @Override
    public ExtendedIterator<Triple> find(Triple triple, InvocationContext ctx) {
        ctx = InvocationContext.fromContextIfEmpty(ctx, parent.getContext());
        
        if (ctx.isEmpty()) {
            throw new IllegalArgumentException("Empty Invocation Context");
        }
        
        

        //a sardine per user
        Sardine sardine = ensureSardine(ctx.getUserId());

        //sardine dependent mapper (webdav link only)
        UriMapper<DavResource> mapper = new UriMapper<DavResource>() {
            @Override
            public DavResource toObject(String uri) {
                QName fileIdProp = new QName("http://owncloud.org/ns", "fileid", "oc");
                try {
                    List<DavResource> resources = sardine.list(uri, 0, Set.of(fileIdProp));
                    if (!resources.isEmpty()) {
                        DavResource singleResource = resources.get(0);
                        return singleResource;
                    }
                } catch (IOException ex) {
                    throw new RuntimeException(ex);
                }
                return null;
            }

            @Override
            public String toURI(DavResource obj) {
                URI uri = obj.getHref();
                uri = webdavHost.resolve(uri);
                return uri.toString();
            }
        };

        //we do not answer queries with unknown subject
        if (triple.getSubject() == Node.ANY) {
            return NiceIterator.emptyIterator();
        }
        
        //needs to be a nextcloud link with correct host
        String subjectUri = triple.getSubject().getURI();
        if(!subjectUri.startsWith(webdavHost.toString())) {
            return NiceIterator.emptyIterator();
        }

        String webdavLink = browserToWebdavLink(triple.getSubject().getURI(), sardine);

        //interpret the URI to local file
        DavResource davResource = mapper.toObject(webdavLink);

        //not a local file
        if (davResource == null) {
            return NiceIterator.emptyIterator();
        }

        //file to triples
        Set<Triple> triples = triplify(davResource, triple, sardine, mapper);

        //only matching triples are returned
        Triple pattern = triple;
        return WrappedIterator.create(triples.iterator()).filterKeep(t -> {
            return pattern.matches(t);
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
     * @param davResource
     * @return
     */
    private Set<Triple> triplify(DavResource davResource, Triple triple, Sardine sardine, UriMapper<DavResource> mapper) {
        Set<Triple> triples = new HashSet<>();

        Node s = triple.getSubject();
        String webdavLink = mapper.toURI(davResource);

        if (davResource.isDirectory()) {
            triples.add(Triple.create(s, RDF.type.asNode(), classFolder.asNode()));
        } else {
            triples.add(Triple.create(s, RDF.type.asNode(), classFile.asNode()));
        }

        if (propertyLabel != null) {
            triples.add(Triple.create(s, propertyLabel.asNode(), NodeFactory.createLiteralString(davResource.getName())));
        }
        if (propertyPrefLabel != null) {
            triples.add(Triple.create(s, propertyPrefLabel.asNode(), NodeFactory.createLiteralString(davResource.getName())));
        }
        if (propertyFileName != null) {
            triples.add(Triple.create(s, propertyFileName.asNode(), NodeFactory.createLiteralString(davResource.getName())));
        }

        /*
        if(propertyBelongsToContainer != null && f.getParentFile() != null) {
            triples.add(Triple.create(s, propertyBelongsToContainer.asNode(), NodeFactory.createURI(fileUriMapper.toURI(f.getParentFile()))));
        }
         */
        if (propertyContains != null) {

            //TODO parent
            /*
            //better use propertyContains as incoming for the belongsToContainer case
            if (davResource.getParentFile() != null) {
                triples.add(Triple.create(NodeFactory.createURI(mapper.toURI(davResource.getParentFile())), propertyContains.asNode(), s));
            }
             */
            List<DavResource> list;
            try {
                list = sardine.list(mapper.toURI(davResource));
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }

            //we use sublist because first one is always self
            for (DavResource child : list.subList(1, list.size())) {

                String webdavLinkChild = mapper.toURI(child);

                String browserLink = webdavToBrowserLink(webdavLinkChild, sardine);

                triples.add(Triple.create(s, propertyContains.asNode(), NodeFactory.createURI(browserLink)));
            }
        }

        //idea: the virtual graph creates a link that leads to itself to handel the request
        //in this case, we create a download link that will directly download the file
        if (!davResource.isDirectory() && propertyDownloadURL != null) {
            String downloadLink = instanceEndpoint + "/download/" + URLEncoder.encode(webdavLink, StandardCharsets.UTF_8);
            triples.add(Triple.create(s, propertyDownloadURL.asNode(), NodeFactory.createURI(downloadLink)));
        }

        return triples;
    }

    private String browserToWebdavLink(String browserLink, Sardine sardine) {

        try {
            URI uri = URI.create(browserLink);

            String base = uri.getScheme() + "://" + uri.getHost();

            // extract fileid from path
            String[] pathParts = uri.getPath().split("/");
            String fileId = pathParts[pathParts.length - 1];

            if (!fileId.matches("\\d+")) {
                throw new IllegalArgumentException("no fileid in url found: " + browserLink);
            }

            // parse query parameters
            Map<String, String> params = Arrays.stream(uri.getQuery().split("&"))
                    .map(p -> p.split("="))
                    .collect(Collectors.toMap(
                            a -> a[0],
                            a -> URLDecoder.decode(a[1], StandardCharsets.UTF_8)));

            String dir = params.get("dir");

            String folderUrl = configObj.getString("baseUrl") + dir;

            QName fileIdProp = new QName("http://owncloud.org/ns", "fileid", "oc");

            List<DavResource> resources
                    = sardine.list(folderUrl, 1, Set.of(fileIdProp));

            for (DavResource r : resources) {
                String id = r.getCustomProps().get("fileid");
                if (fileId.equals(id)) {
                    return base + r.getHref().getPath().replace(" ", "%20"); //TODO later do this encoding better
                }
            }

            throw new RuntimeException("File not found for fileid=" + fileId);

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private String webdavToBrowserLink(String webdavLink, Sardine sardine) {

        try {
            URI uri = URI.create(webdavLink);

            //query fileid
            QName fileIdProp = new QName("http://owncloud.org/ns", "fileid", "oc");
            List<DavResource> res = sardine.list(webdavLink, 0, Set.of(fileIdProp));
            if (res.isEmpty()) {
                throw new RuntimeException("Cannot resolve file");
            }

            //resource
            DavResource r = res.get(0);
            String fileId = r.getCustomProps().get("fileid");

            //dir
            String path = uri.getPath();
            String[] segments = path.split("/");
            List<String> dirSegments = new ArrayList<>();
            for (int i = 6; i < segments.length - 1; i++) {
                dirSegments.add(segments[i]);
            }

            String base = uri.getScheme() + "://" + uri.getHost();
            String browserLink = base + "/owncloud/index.php/apps/files/files/" + fileId
                    + "?dir=/" + String.join("/", dirSegments);

            if (!r.isDirectory()) {
                browserLink += "&openfile=true";
            }

            return browserLink;

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    //-----------------------------------------------------------------------------
    //add & delete
    //TODO later we can wait for transaction to add more triple in one transaction and act accordingly
    @Override
    public void add(Triple t, InvocationContext ctx) {
        ctx = InvocationContext.fromContextIfEmpty(ctx, parent.getContext());
        
        Node s = t.getSubject();
        Node p = t.getPredicate();
        Node o = t.getObject();

        Sardine sardine = ensureSardine(ctx.getUserId());

        //TODO check that s is a valid browser link
        if ((p.equals(propertyLabel.asNode())
                || p.equals(propertyPrefLabel.asNode())
                || p.equals(propertyFileName.asNode()))
                && o.isLiteral()) {

            String webdavLink = browserToWebdavLink(s.getURI(), sardine);

            renameFile(webdavLink, o.getLiteralLexicalForm(), sardine);
            return;
        }

        if (p.equals(propertyNewFileName.asNode())
                && o.isLiteral()) {

            String webdavLink = browserToWebdavLink(s.getURI(), sardine);

            createFile(webdavLink, o.getLiteralLexicalForm(), sardine);
            return;
        }

        if (p.equals(propertyNewFolderName.asNode())
                && o.isLiteral()) {

            String webdavLink = browserToWebdavLink(s.getURI(), sardine);

            createFolder(webdavLink, o.getLiteralLexicalForm(), sardine);
            return;
        }

        throw new IllegalArgumentException("Triple cannot be added: " + t);
    }

    @Override
    public void delete(Triple t, InvocationContext ctx) {
        ctx = InvocationContext.fromContextIfEmpty(ctx, parent.getContext());
        
        Node s = t.getSubject();
        Node p = t.getPredicate();
        Node o = t.getObject();

        Sardine sardine = ensureSardine(ctx.getUserId());

        //TODO check that s is a valid browser link
        if (p.equals(RDF.type.asNode()) && o.equals(classFile.asNode())) {
            String webdavLink = browserToWebdavLink(s.getURI(), sardine);

            deleteFile(webdavLink, sardine);
            return;
        }
        
        if (p.equals(RDF.type.asNode()) && o.equals(classFolder.asNode())) {
            String webdavLink = browserToWebdavLink(s.getURI(), sardine);

            deleteFolder(webdavLink, sardine);
            return;
        }

        throw new IllegalArgumentException("Triple cannot be deleted: " + t);
    }

    //-----------------------------------------
    //helper
    private void renameFile(String fileUrl, String newName, Sardine sardine) {
        try {
            String base = fileUrl.substring(0, fileUrl.lastIndexOf("/") + 1);
            String newUrl = base + encodeSegment(newName);

            sardine.move(fileUrl, newUrl);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void createFile(String parentUrl, String name, Sardine sardine) {
        try {
            String url = parentUrl.endsWith("/") ? parentUrl : parentUrl + "/";
            String newFileUrl = url + encodeSegment(name);

            byte[] emptyContent = new byte[0]; // empty file
            sardine.put(newFileUrl, emptyContent);

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void createFolder(String parentUrl, String name, Sardine sardine) {
        try {
            String url = parentUrl.endsWith("/") ? parentUrl : parentUrl + "/";
            String newFolderUrl = url + encodeSegment(name);

            sardine.createDirectory(newFolderUrl);

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void deleteFile(String webdavLink, Sardine sardine) {
        try {
            sardine.delete(webdavLink);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void deleteFolder(String webdavLink, Sardine sardine) {
        try {
            sardine.delete(webdavLink);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private String encodeSegment(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8)
                .replace("+", "%20"); // keep spaces as %20 instead of +
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
        try {
            for (Sardine sardine : user2sardine.values()) {
                sardine.shutdown();
            }
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    @Override
    public boolean isClosed(InvocationContext ctx) {
        throw new UnsupportedOperationException("Not supported.");
    }

    //=============================================================================
    public static AticGraph create(String uri, String config, SqliteAticDatasetGraph parent) {
        JSONObject configObj = new JSONObject(config);
        return new WebdavVirtualGraph(uri, configObj, parent);
    }

    //getter
    public Property getPropertyLabel() {
        return propertyLabel;
    }

    public Property getPropertyPrefLabel() {
        return propertyPrefLabel;
    }

    public Property getPropertyFileName() {
        return propertyFileName;
    }

    public Property getPropertyHidden() {
        return propertyHidden;
    }

    public Property getPropertyBelongsToContainer() {
        return propertyBelongsToContainer;
    }

    public Property getPropertyContains() {
        return propertyContains;
    }

    public Property getPropertyDownloadURL() {
        return propertyDownloadURL;
    }

    public Property getPropertyNewFileName() {
        return propertyNewFileName;
    }

    public Property getPropertyNewFolderName() {
        return propertyNewFolderName;
    }

    public Resource getClassFile() {
        return classFile;
    }

    public Resource getClassFolder() {
        return classFolder;
    }

    
    
}

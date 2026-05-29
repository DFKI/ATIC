
package edu.lehigh.swat.bench.uba;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.riot.system.StreamRDF;
import org.apache.jena.vocabulary.RDF;

/**
 * 
 */
public class StreamRDFWriter implements Writer {

    private static final String T_ONTO_URI = "http://www.lehigh.edu/~zhp2/2004/0401/univ-bench.owl#";
    private Generator generator;
    private StreamRDF stream;
    
    public StreamRDFWriter(StreamRDF stream, Generator generator) {
        this.stream = stream;
        this.generator = generator;
    }
    
    @Override
    public void start() {
        stream.start();
    }

    @Override
    public void end() {
        stream.finish();
    }

    @Override
    public void startFile(String fileName) {
        //ignore
    }

    @Override
    public void endFile() {
        //ignore
    }

    private String lastSectionId;
    
    @Override
    public void startSection(int classType, String id) {
        generator.startSectionCB(classType);
        writeInstanceWithType(classType, id);
        lastSectionId = id;
    }

    @Override
    public void startAboutSection(int classType, String id) {
        generator.startAboutSectionCB(classType);
        writeInstanceWithType(classType, id);
        lastSectionId = id;
    }
    
    @Override
    public void endSection(int classType) {
        //ignore
    }
    
    //=====================================================
    //writing
    
    private void writeInstanceWithType(int classType, String id) {
        
        Node s = NodeFactory.createURI(id);
        Node p = RDF.type.asNode();
        Node o = NodeFactory.createURI(T_ONTO_URI + Generator.CLASS_TOKEN[classType]);
        Triple t = Triple.create(s, p, o);
        stream.triple(t);
    }

    @Override
    public void addProperty(int property, String value, boolean isResource) {
        generator.addPropertyCB(property);
        
        Node s = NodeFactory.createURI(lastSectionId);
        Node p = NodeFactory.createURI(T_ONTO_URI + Generator.PROP_TOKEN[property]);
        
        Node o;
        if(isResource) {
            o = NodeFactory.createURI(value);
        } else {
            o = NodeFactory.createLiteralString(value);
        }
        
        Triple t = Triple.create(s, p, o);
        stream.triple(t);
    }

    @Override
    public void addProperty(int property, int valueClass, String valueId) {
        generator.addPropertyCB(property);
        generator.addValueClassCB(valueClass);
        
        Node s = NodeFactory.createURI(lastSectionId);
        Node p = NodeFactory.createURI(T_ONTO_URI + Generator.PROP_TOKEN[property]);
        Node o = NodeFactory.createURI(valueId);
        Triple t = Triple.create(s, p, o);
        stream.triple(t);
    }

}

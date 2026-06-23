package de.dfki.sds.aticserver;

import de.dfki.sds.atic.conf.Config;
import java.io.File;

public class AticConfig {

    @Config(value = "port", description = "Port to bind the server")
    int port = 'A' * 100 + 'S'; //Atic Server: 6583

    @Config(value = "host", description = "Host address")
    String host = "127.0.0.1";

    @Config(value = "debug", description = "Enable debug mode")
    boolean debug = false;

    @Config(value = "home", description = "Application home directory")
    File home = new File(".");
    
    @Config(value = "landingpage", description = "Landing page after login")
    String landingpage = "/app/home.html";
    
    @Config(value = "instance.name", description = "Name of this ATIC instance")
    String instanceName = "ATIC";
    
    @Config(value = "cdce.endpoint.path", description = "Path where Configuration-Driven CRUD Endpoints are registered")
    String cdceEndpointPath = "/api";
    
    @Config(value = "print.log", description = "Prints what is logged")
    boolean printLog = true;
    
    @Config(value = "locale", description = "Locale (e.g. en, de)")
    String locale = "en";
    
    @Config(value = "db.busy.timeout.ms", description = "Busy timeout in milliseconds")
    int databaseBusyTimeoutMs = 5000;
    
    @Config(value = "db.wal", description = "Enable WAL mode")
    boolean databaseWalEnabled = true;
    
    @Config(value = "db.foreignkeys", description = "Enable Foreign Keys")
    boolean databaseForeignKeysEnabled = true;
    
    @Config(value = "sparql.timeout", description = "Timeout in ms when SPARQL query should be canceled")
    int sparqlTimeout = 30000;
    
    @Config(value = "sparql.defaultquery", description = "The default query in SPARQL UI")
    String sparqlDefaultquery = """
SELECT * {
  ?s ?p ?o
}
LIMIT 10
""";
    
    @Config(value = "sparql.defaultprefixes", description = "The default prefixes in SPARQL UI")
    String sparqlDefaultprefixes = """
PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>
PREFIX foaf: <http://xmlns.com/foaf/0.1/>
PREFIX skos: <http://www.w3.org/2004/02/skos/core#>
PREFIX dcat: <http://www.w3.org/ns/dcat#>
PREFIX ex: <http://example.org/>
""";

    @Config(value = "rdfpatch.rotationinterval", description = "RDFPatch rotation interval in seconds")
    long rdfpatchRotationinterval = 60 * 60 * 24;
    
    public int getPort() {
        return port;
    }

    public String getHost() {
        return host;
    }

    public boolean isDebug() {
        return debug;
    }

    public File getHome() {
        return home;
    }

    public int getDatabaseBusyTimeoutMs() {
        return databaseBusyTimeoutMs;
    }

    public boolean isDatabaseWalEnabled() {
        return databaseWalEnabled;
    }

    public boolean isDatabaseForeignKeysEnabled() {
        return databaseForeignKeysEnabled;
    }

    public boolean isPrintLog() {
        return printLog;
    }

    public String getLocale() {
        return locale;
    }

    public String getLandingpage() {
        return landingpage;
    }

    public String getInstanceName() {
        return instanceName;
    }

    public String getCdceEndpointPath() {
        return cdceEndpointPath;
    }

    public int getSparqlTimeout() {
        return sparqlTimeout;
    }

    public String getSparqlDefaultquery() {
        return sparqlDefaultquery;
    }

    public String getSparqlDefaultprefixes() {
        return sparqlDefaultprefixes;
    }
    
    public long getRdfpatchRotationinterval() {
        return rdfpatchRotationinterval;
    }
    
}

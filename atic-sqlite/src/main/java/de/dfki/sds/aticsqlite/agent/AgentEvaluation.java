package de.dfki.sds.aticsqlite.agent;

import de.dfki.sds.atic.ac.Agent;
import de.dfki.sds.atic.ac.Permission;
import de.dfki.sds.atic.ac.User;
import de.dfki.sds.atic.ac.UserGroupManagement;
import de.dfki.sds.atic.agent.AgentProgram;
import de.dfki.sds.atic.agent.Message;
import de.dfki.sds.atic.agent.Session;
import de.dfki.sds.atic.jenatic.InvocationContext;
import de.dfki.sds.aticsqlite.AticFactory;
import de.dfki.sds.aticsqlite.SqliteAticDatasetGraph;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.apache.jena.graph.Node;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.json.JSONObject;
import org.yaml.snakeyaml.Yaml;

public class AgentEvaluation {

    public void runAll(String packagePath, String indexFile) {

        try {
            List<String> yamlFiles;
            try (InputStream in
                    = getClass().getResourceAsStream(
                            packagePath + "/" + indexFile)) {

                yamlFiles
                        = new ArrayList<>(new BufferedReader(
                                new InputStreamReader(in))
                                .lines()
                                .filter(s -> !s.isBlank())
                                .sorted()
                                .toList());
            }

            yamlFiles.sort(
                    Comparator.comparing(
                            p -> p,
                            String.CASE_INSENSITIVE_ORDER
                    )
            );

            System.out.println("Found " + yamlFiles.size() + " evaluation files");

            for (String yaml : yamlFiles) {

                System.out.println();
                System.out.println("=================================================");
                System.out.println("RUNNING: " + yaml);
                System.out.println("=================================================");

                try {
                    runSingle(
                            packagePath,
                            yaml
                    );
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void runSingle(String packagePath, String yamlFilename) {
        try {
            JSONObject evalSetting = loadEvaluationSetting(packagePath, yamlFilename);

            SessionAndData sessionAndData = setupSessionAndData(evalSetting);
            Session session = sessionAndData.session();
            SqliteAticDatasetGraph ds = sessionAndData.dataset();

            //settings
            String agentClass = evalSetting.getString("agentClass");
            String messageContent = evalSetting.getString("messageContent");
            JSONObject config = evalSetting.optJSONObject("config");

            //load program 
            Class<?> clazz = Class.forName("de.dfki.sds.aticsqlite.agent." + agentClass);
            Constructor<?> ctor
                    = clazz.getConstructor(
                            Agent.class,
                            JSONObject.class,
                            Session.class,
                            SqliteAticDatasetGraph.class
                    );
            AgentProgram agentProgram
                    = (AgentProgram) ctor.newInstance(
                            session.getAgent(),
                            config,
                            session,
                            ds
                    );

            long start = System.currentTimeMillis();

            agentProgram.process(
                    Message.plainText(
                            session.getPrincipal(),
                            messageContent
                    )
            );

            long duration = System.currentTimeMillis() - start;

            System.out.println(toString(evalSetting, session, duration));

        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to execute evaluation "
                    + yamlFilename,
                    e
            );
        }
    }

    private JSONObject loadEvaluationSetting(String packagePath, String yamlFilename) {
        Path yamlPath = Path.of(packagePath, yamlFilename);
        Yaml yaml = new Yaml();
        JSONObject root;
        try (InputStream in = getClass().getResourceAsStream(yamlPath.toString())) {
            Object loaded = yaml.load(in);
            root = new JSONObject((java.util.Map<String, Object>) loaded);
            root.put("packagePath", packagePath);
            root.put("yamlFilename", yamlFilename);
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
        return root;
    }

    private SessionAndData setupSessionAndData(JSONObject evalSetting) {
        try {

            String sessionId = "session-" + UUID.randomUUID();

            String principalUsername = evalSetting.optString("principalUsername", "principal");
            String agentUsername = evalSetting.optString("agentUsername", "agent");

            SqliteAticDatasetGraph ds = (SqliteAticDatasetGraph) AticFactory.createTxn();

            ds.executeWrite(() -> {
                try {
                    ds.addUser("", "", "", principalUsername, InvocationContext.EMPTY);
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }

                try {
                    ds.addUser("", "", "", agentUsername, InvocationContext.EMPTY);
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            });

            User principal = ds.calculateRead(()
                    -> ds.getUser(
                            principalUsername,
                            InvocationContext.EMPTY
                    )
            );

            User agentAsUser = ds.calculateRead(()
                    -> ds.getUser(
                            agentUsername,
                            InvocationContext.EMPTY
                    )
            );

            //prepare and share data
            String datasetTrig = evalSetting.optString("dataset", null);
            if (datasetTrig != null && !datasetTrig.isBlank()) {
                loadDatasetTrig(evalSetting.getString("packagePath"), datasetTrig, ds, principal, agentAsUser);
            }

            //enableAgent to get as Agent
            ds.executeWrite(() -> {
                try {
                    ds.enableAgent(
                            agentUsername,
                            "", //no need to fill it because we manage session and AgentProgram
                            new JSONObject(),
                            InvocationContext.EMPTY
                    );

                } catch (Exception ignore) {
                }
            });

            //we need this for session
            Agent agent = ds.calculateRead(()
                    -> (Agent) ds.getUser(
                            agentUsername,
                            InvocationContext.EMPTY
                    )
            );

            Session session = new Session(
                    principal,
                    sessionId,
                    agent,
                    Instant.EPOCH
            );

            return new SessionAndData(session, ds);

        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to load session "
                    + evalSetting,
                    e
            );
        }
    }

    private void loadDatasetTrig(String packagePath, String filenameTrig, SqliteAticDatasetGraph ds, User user, User agent) {
        User adminUser = ds.calculateRead(() -> {
            return ds.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });
        InvocationContext ictx = new InvocationContext.Builder().fromUser(adminUser).build();

        Path trig = Path.of(packagePath, filenameTrig);

        try (InputStream in = getClass().getResourceAsStream(trig.toString())) {

            ictx.transferContext(ds.getContext());
            ds.executeWrite(() -> {
                RDFDataMgr.read(
                        ds,
                        in,
                        Lang.TRIG
                );
            });

        } catch (IOException e) {
            throw new RuntimeException(
                    "Could not load dataset: "
                    + trig,
                    e
            );
        }

        ds.executeWrite(() -> {

            Set<String> graphUris = new HashSet<>();
            ds.listGraphNodes().forEachRemaining(graph -> {
                graphUris.add(graph.getURI());
            });

            Set<String> resourceUris = new HashSet<>();
            ds.find(Node.ANY, Node.ANY, Node.ANY, Node.ANY, ictx).forEachRemaining(quad -> {
                resourceUris.add(quad.getSubject().getURI());
                if (!quad.getObject().isLiteral()) {
                    resourceUris.add(quad.getObject().getURI());
                }
            });

            ds.shareGraphs(graphUris, Set.of(user.getShareUri(), agent.getShareUri()), Permission.EDIT, ictx);
            ds.shareResources(resourceUris, Set.of(user.getShareUri(), agent.getShareUri()), Permission.EDIT, ictx);
        });
    }

    private String toString(JSONObject evalSetting, Session session, long durationMillis) {

        StringBuilder sb = new StringBuilder();

        sb.append("\n");
        sb.append("Result\n");
        sb.append("----------------------------------------\n");
        sb.append("File      : ")
                .append(evalSetting.get("yamlFilename"))
                .append("\n");
        sb.append("Name      : ")
                .append(evalSetting.get("name"))
                .append("\n");
        sb.append("Dataset   : ")
                .append(evalSetting.get("dataset"))
                .append("\n");
        sb.append("AgentClass: ")
                .append(evalSetting.get("agentClass"))
                .append("\n");
        
        //result
        sb.append("Duration  : ")
                .append(durationMillis)
                .append(" ms\n");
        sb.append("Messages  : ")
                .append(session.getMessages().size())
                .append("\n");
        sb.append("Logs      : ")
                .append(session.getLogRecords().size())
                .append("\n");
        
        sb.append("Messages  :\n");
        for(Message message : session.getMessages()) {
            sb.append(message.toString()).append("\n");
        }

        return sb.toString();
    }

    public record SessionAndData(Session session, SqliteAticDatasetGraph dataset) {

    }

    public static void main(String[] args) {
        AgentEvaluation agentEvaluation = new AgentEvaluation();
        agentEvaluation.runAll("/de/dfki/sds/aticsqlite/agenteval", "evaluations.txt");
    }
}

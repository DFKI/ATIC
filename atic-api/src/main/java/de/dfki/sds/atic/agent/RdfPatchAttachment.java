package de.dfki.sds.atic.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.jena.rdfpatch.RDFPatch;
import org.apache.jena.rdfpatch.RDFPatchOps;
import org.apache.jena.rdfpatch.changes.RDFChangesCollector;
import org.apache.jena.rdfpatch.items.AddPrefix;
import org.apache.jena.rdfpatch.items.AddQuad;
import org.apache.jena.rdfpatch.items.ChangeItem;
import org.apache.jena.rdfpatch.items.DeletePrefix;
import org.apache.jena.rdfpatch.items.DeleteQuad;
import org.apache.jena.rdfpatch.items.Segment;
import org.apache.jena.rdfpatch.items.TxnAbort;
import org.apache.jena.rdfpatch.items.TxnBegin;
import org.apache.jena.rdfpatch.items.TxnCommit;

public record RdfPatchAttachment(
        RDFPatch patch
        ) implements Attachment {

    public RdfPatchAttachment {
        Objects.requireNonNull(patch, "patch");
    }

    @Override
    public Map<String, Object> toMap() {

        Map<String, Object> map = new LinkedHashMap<>();

        map.put("type", "rdfPatch");
        map.put("content", RDFPatchOps.str(patch));
        
        map.put("additions", 0);
        map.put("removals", 0);

        //detailed actions
        List<Map<String, Object>> actions = new ArrayList<>();
        RDFChangesCollector.RDFPatchStored stored
                = (RDFChangesCollector.RDFPatchStored) patch;

        for (ChangeItem item : stored.getActions()) {

            Map<String, Object> action = new LinkedHashMap<>();

            if (item instanceof AddQuad q) {

                action.put("type", "addQuad");
                action.put("graph", q.g.toString());
                action.put("subject", q.s.toString());
                action.put("predicate", q.p.toString());
                action.put("object", q.o.toString());
                
                map.put("additions", (int) map.get("additions") + 1);

            } else if (item instanceof DeleteQuad q) {

                action.put("type", "deleteQuad");
                action.put("graph", q.g.toString());
                action.put("subject", q.s.toString());
                action.put("predicate", q.p.toString());
                action.put("object", q.o.toString());
                
                map.put("removals", (int) map.get("removals") + 1);

            } else if (item instanceof AddPrefix p) {

                action.put("type", "addPrefix");
                action.put("graph", p.gn == null ? null : p.gn.toString());
                action.put("prefix", p.prefix);
                action.put("uri", p.uriStr);

            } else if (item instanceof DeletePrefix p) {

                action.put("type", "deletePrefix");
                action.put("graph", p.gn == null ? null : p.gn.toString());
                action.put("prefix", p.prefix);

            } else if (item instanceof TxnBegin) {

                action.put("type", "txnBegin");

            } else if (item instanceof TxnCommit) {

                action.put("type", "txnCommit");

            } else if (item instanceof TxnAbort) {

                action.put("type", "txnAbort");

            } else if (item instanceof Segment) {

                action.put("type", "segment");

            } else {

                action.put("type", item.getClass().getSimpleName());
                action.put("value", item.toString());
            }

            actions.add(Map.copyOf(action));
        }

        map.put("actions", actions);

        return Map.copyOf(map);
    }

    @Override
    public String toString() {
        return "RdfPatchAttachment[patch=\n"
                + RDFPatchOps.str(patch)
                + "]";
    }
}

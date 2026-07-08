

package de.dfki.sds.atic.agent;

import java.util.Map;

/**
 *
 */
public sealed interface Attachment
        permits LinkAttachment,
                BinaryAttachment,
                RdfNodesAttachment,
                RdfGraphAttachment, 
                ToolCallAttachment {
    
    Map<String, Object> toMap();
    
}

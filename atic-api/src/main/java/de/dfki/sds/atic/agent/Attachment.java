

package de.dfki.sds.atic.agent;

/**
 *
 */
public sealed interface Attachment
        permits LinkAttachment,
                BinaryAttachment,
                RdfNodesAttachment,
                RdfGraphAttachment, 
                ToolCallAttachment {
}

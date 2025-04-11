package ddvote.shared;

import java.io.Serializable;

/**
 * A wrapper class to hold the actual data being replicated along with its
 * associated Lamport timestamp. This ensures that the timestamp is always
 * sent with the operation data.
 */
public class ReplicationData implements Serializable {
    private static final long serialVersionUID = 12L; // Unique ID for serialization

    // The actual data payload for the replicated operation.
    // Examples: Credentials object, String[] {voterId, candidateId}, ElectionState enum.
    public final Object data;

    // The Lamport timestamp assigned by the Primary server when the operation occurred.
    public final int timestamp;

    /**
     * Constructs ReplicationData.
     *
     * @param data      The data payload of the operation.
     * @param timestamp The Lamport timestamp of the operation.
     */
    public ReplicationData(Object data, int timestamp) {
        this.data = data;
        this.timestamp = timestamp;
    }
}
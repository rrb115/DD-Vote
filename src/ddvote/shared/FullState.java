package ddvote.shared;

import java.io.Serializable;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Bundles the entire essential server state for state transfer between peers.
 * Contains copies of the data to ensure serializability and avoid issues
 * with concurrent modification during transfer.
 */
public class FullState implements Serializable {
    private static final long serialVersionUID = 11L; // Unique ID for serialization

    public final Map<String, String> registeredVoters;
    public final Map<String, Candidate> candidates;
    public final Map<String, Integer> voteCounts;
    public final Set<String> votersWhoVoted;
    public final ElectionState electionState;
    public final int lamportTime; // Lamport timestamp of the primary when the state snapshot was taken

    /**
     * Constructs a FullState snapshot.
     *
     * @param registeredVoters Map of registered voters.
     * @param candidates       Map of candidates.
     * @param voteCounts       Map of vote counts per candidate.
     * @param votersWhoVoted   Set of voters who have voted.
     * @param electionState    The current election state.
     * @param lamportTime      The primary's Lamport time when the snapshot was created.
     */
    public FullState(
            Map<String, String> registeredVoters,
            Map<String, Candidate> candidates,
            Map<String, Integer> voteCounts,
            Set<String> votersWhoVoted,
            ElectionState electionState,
            int lamportTime)
    {
        // Create defensive copies to ensure serializability and isolate state
        this.registeredVoters = new ConcurrentHashMap<>(registeredVoters);
        this.candidates = new ConcurrentHashMap<>(candidates);
        this.voteCounts = new ConcurrentHashMap<>(voteCounts);

        // ConcurrentHashMap.newKeySet() often returns a non-serializable view.
        // We must explicitly copy the elements into a new serializable Set.
        this.votersWhoVoted = ConcurrentHashMap.newKeySet();
        if (votersWhoVoted != null) {
            this.votersWhoVoted.addAll(votersWhoVoted);
        }

        this.electionState = electionState;
        this.lamportTime = lamportTime;
    }
}
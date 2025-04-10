package ddvote.shared; // CHANGED

import java.io.Serializable;

// Class definition remains the same
public class VoteResult implements Serializable {
    private static final long serialVersionUID = 4L;
    private final String candidateId;
    private final String candidateName;
    private final int voteCount;

    public VoteResult(String candidateId, String candidateName, int voteCount) {
        this.candidateId = candidateId; this.candidateName = candidateName; this.voteCount = voteCount;
    }
    public String getCandidateId() { return candidateId; }
    public String getCandidateName() { return candidateName; }
    public int getVoteCount() { return voteCount; }
    @Override public String toString() { return candidateName + ": " + voteCount + " votes"; }
}
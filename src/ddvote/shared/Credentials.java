package ddvote.shared; // CHANGED

import java.io.Serializable;

// Class definition remains the same (still INSECURE)
public class Credentials implements Serializable {
    private static final long serialVersionUID = 3L;
    private final String voterId;
    private final String password; // INSECURE plain text

    public Credentials(String voterId, String password) { this.voterId = voterId; this.password = password; }
    public String getVoterId() { return voterId; }
    public String getPassword() { return password; } // INSECURE
    @Override public String toString() { return "Credentials{voterId='" + voterId + "'}"; } // Hide password
}
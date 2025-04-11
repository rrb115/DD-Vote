package ddvote.shared;

import java.io.Serializable;

/**
 * Defines the types of messages exchanged in the DD-Vote system,
 * including client-server interactions and server-peer communication.
 */
public enum MessageType implements Serializable {
    // --- Client <-> Server Communication ---
    REGISTER,               // Client request to register a new voter (Payload: Credentials)
    LOGIN,                  // Client request to log in (Payload: Credentials)
    GET_CANDIDATES,         // Client request to get the list of candidates (Payload: null)
    SUBMIT_VOTE,            // Client request to submit a vote (Payload: String candidateId)
    GET_RESULTS,            // Client request to get election results (Payload: null)
    PING,                   // Client request to check server connectivity (Payload: null)
    DISCONNECT,             // Client notification that it is disconnecting (Payload: null)

    REGISTRATION_SUCCESS,   // Server response: Registration successful (Payload: String message)
    REGISTRATION_FAILED,    // Server response: Registration failed (Payload: String reason)
    LOGIN_SUCCESS,          // Server response: Login successful (Payload: String welcome message)
    LOGIN_FAILED,           // Server response: Login failed (Payload: String reason)
    ALREADY_LOGGED_IN,      // Server response: User already logged in elsewhere (Payload: String message)
    CANDIDATE_LIST,         // Server response: List of candidates (Payload: List<Candidate>)
    VOTE_ACCEPTED,          // Server response: Vote successfully recorded (Payload: String message)
    ALREADY_VOTED,          // Server response: User has already voted (Payload: String message)
    VOTE_REJECTED,          // Server response: Vote rejected (Payload: String reason, e.g., invalid candidate)
    RESULTS_DATA,           // Server response: Election results (Payload: List<VoteResult>)
    ELECTION_NOT_STARTED,   // Server notification/response: Election hasn't started (Payload: String message)
    ELECTION_RUNNING,       // Server notification/response: Election is currently running (Payload: String message)
    ELECTION_FINISHED,      // Server notification/response: Election has finished (Payload: String message)
    PONG,                   // Server response to a PING (Payload: null)
    SERVER_MESSAGE,         // General broadcast message from the server (e.g., admin message) (Payload: String message)
    ERROR,                  // General error message from the server (Payload: String error details)
    AUTHENTICATION_REQUIRED,// Server response: Action requires login (Payload: String message)

    // --- Server <-> Server (Peer Communication) ---
    HEARTBEAT,              // Basic liveness check (Payload: String senderServerId)
    REPLICATE_REGISTER,     // Replicate voter registration (Payload: ReplicationData wrapping Credentials)
    REPLICATE_VOTE,         // Replicate a cast vote (Payload: ReplicationData wrapping String[]{voterId, candidateId})
    REPLICATE_CANDIDATE,    // Replicate added candidate (Payload: ReplicationData wrapping Candidate)
    REPLICATE_STATE_CHANGE, // Replicate election state change (Payload: ReplicationData wrapping ElectionState)
    REPLICATE_RESET,        // Replicate vote data reset (Payload: ReplicationData wrapping null)
    REQUEST_STATE,          // Request full state from Primary (Payload: String requestingServerId)
    FULL_STATE_DATA         // Response containing the full state (Payload: FullState)
}
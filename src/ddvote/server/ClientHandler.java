package ddvote.server;

import ddvote.shared.*;
import java.io.*;
import java.net.Socket;
import java.net.SocketException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles communication with a single connected client or peer server.
 * Runs in its own thread, reading messages and dispatching actions to the VotingServer.
 */
public class ClientHandler implements Runnable {
    private static final Logger LOGGER = Logger.getLogger(ClientHandler.class.getName());
    private final Socket clientSocket;
    private final VotingServer server;
    private ObjectOutputStream out;
    private ObjectInputStream in;
    private volatile boolean running = true;
    private String voterId = null; // Tracks voter ID if logged in as a voter
    private String peerId = null;  // Tracks peer ID if connection is identified as a peer
    private final String clientAddress; // Remote address for logging

    /**
     * Constructor for ClientHandler. Initializes I/O streams.
     * @param socket The socket connected to the client/peer.
     * @param server The main VotingServer instance.
     */
    public ClientHandler(Socket socket, VotingServer server) {
        this.clientSocket = socket;
        this.server = server;
        this.clientAddress = socket.getRemoteSocketAddress().toString();
        try {
            // Output stream MUST be created first for Object Stream header negotiation
            this.out = new ObjectOutputStream(socket.getOutputStream());
            this.out.flush(); // Ensure header is sent
            this.in = new ObjectInputStream(socket.getInputStream());
        } catch (IOException e) {
            // Log stream initialization failures, potentially less severe for aborted connections
            if (e instanceof SocketException && e.getMessage() != null && (e.getMessage().contains("aborted") || e.getMessage().contains("reset"))) {
                LOGGER.fine("Stream init likely aborted by remote closing connection early for " + clientAddress + ": " + e.getMessage());
            } else {
                LOGGER.log(Level.WARNING, "Stream init failed for " + clientAddress, e);
            }
            running = false; // Cannot proceed if streams fail
            closeConnectionResources(); // Clean up socket
        }
    }

    /**
     * The main loop for the handler thread. Reads messages from the input stream
     * and processes them based on their type (client request or peer communication).
     */
    @Override
    public void run() {
        if (!running) return; // Don't run if initialization failed
        String logId = clientAddress; // Initial log identifier

        try {
            // Loop while running and socket is connected
            while (running && !clientSocket.isClosed()) {
                // Read the next message object from the stream
                Message msg = (Message) in.readObject();
                logId = clientAddress; // Reset log identifier for each message

                // --- Message Processing Logic ---
                // Use a switch to handle different message types efficiently
                switch(msg.getType()) {
                    // --- Peer Communication ---
                    case HEARTBEAT:
                        if (msg.getPayload() instanceof String) {
                            this.peerId = (String) msg.getPayload(); // Identify sender as peer
                            logId = "Peer:" + peerId;
                            LOGGER.finest("Rcvd from " + logId + ": " + msg.getType());
                            server.processHeartbeat(this.peerId);
                            // Keep connection open? No, heartbeat is one-off. But don't exit thread yet.
                        } else { LOGGER.warning("Invalid HEARTBEAT payload from " + logId); running = false; }
                        // Heartbeat processing is quick, continue reading on same connection? Unlikely needed.
                        // Let's assume heartbeat connections close after sending. The readObject will likely fail next.
                        break; // Continue loop (will likely hit EOF/SocketException)

                    // Replication messages from Primary
                    case REPLICATE_REGISTER:
                    case REPLICATE_VOTE:
                    case REPLICATE_CANDIDATE:
                    case REPLICATE_STATE_CHANGE:
                    case REPLICATE_RESET:
                        if (msg.getPayload() instanceof ReplicationData) {
                            logId = "Peer(Repl)"; // Identify as replication source for logs
                            LOGGER.finest("Rcvd from " + logId + ": " + msg.getType());
                            server.handleReplicationMessage(msg.getType(), (ReplicationData) msg.getPayload());
                        } else { LOGGER.warning("Invalid Replication payload for " + msg.getType() + " from " + logId); running = false; }
                        // Assume replication messages are also one-off per connection for simplicity
                        break; // Continue loop

                    // State Transfer messages
                    case REQUEST_STATE: // Received by Primary from a Backup
                        if (msg.getPayload() instanceof String) {
                            this.peerId = (String) msg.getPayload(); // ID of requesting peer
                            logId = "Peer(StateReq:" + peerId + ")";
                            LOGGER.fine("Rcvd from " + logId + ": " + msg.getType());
                            server.handleStateRequest(this.peerId);
                        } else { LOGGER.warning("Invalid REQUEST_STATE payload from " + logId); running = false; }
                        // Primary handles request then this connection can close.
                        running = false; // Exit loop after handling state request
                        break;
                    case FULL_STATE_DATA: // Received by Backup from Primary
                        if (msg.getPayload() instanceof FullState) {
                            logId = "Peer(StateRcv)";
                            LOGGER.fine("Rcvd from " + logId + ": " + msg.getType());
                            server.handleFullState((FullState) msg.getPayload());
                        } else { LOGGER.warning("Invalid FULL_STATE_DATA payload from " + logId); running = false; }
                        // Backup received state, this connection can close.
                        running = false; // Exit loop after handling state response
                        break;

                    // --- Regular Client Messages ---
                    default:
                        // If already identified as peer, receiving non-peer message is an error
                        if (this.peerId != null) {
                            LOGGER.warning("Peer " + this.peerId + " sent unexpected client message type: " + msg.getType());
                            running = false; // Disconnect invalid peer behavior
                        } else {
                            // Process as a regular client message
                            logId = (this.voterId != null) ? this.voterId : clientAddress; // Use voterId if logged in
                            LOGGER.fine("Rcvd from " + logId + ": " + msg.getType());
                            handleClientMessage(msg); // Delegate to client message handler
                        }
                        break; // Break switch, continue loop to read next message
                } // End switch block
            } // End while loop
        } catch (EOFException | SocketException e) {
            // Handle expected disconnections gracefully
            if (running) { // Avoid logging errors during planned shutdown
                String reason = e.getMessage();
                if (e instanceof EOFException) reason = "EOF"; // Clearer reason
                if (reason != null && (reason.contains("aborted") || reason.contains("reset") || reason.equals("EOF"))) {
                    // Log expected closures at a finer level
                    LOGGER.fine("Client/Peer " + logId + " disconnected (" + reason + ")");
                } else {
                    // Log other unexpected network errors as warnings
                    LOGGER.warning("Network error for " + logId + ": " + e);
                }
            }
        } catch (IOException | ClassNotFoundException e) {
            // Handle issues with reading/deserializing objects
            if (running) LOGGER.log(Level.WARNING, "Network/Serialization error for " + logId, e);
        } catch (Exception e) {
            // Catch any other unexpected exceptions during message processing
            if (running) LOGGER.log(Level.SEVERE, "Unexpected error in handler for " + logId, e);
        } finally {
            // Ensure resources are cleaned up and logout is handled when thread exits
            shutdown();
        }
        LOGGER.info("Handler thread stopped for: " + logId);
    }

    /** Processes messages received from a regular (non-peer) client. */
    private void handleClientMessage(Message message) {
        MessageType type = message.getType();
        Object payload = message.getPayload();
        try {
            // Ensure server is ready (state transfer complete) before processing most client requests
            if (!server.isStateTransferComplete() &&
                    // Allow PING and DISCONNECT even if server is syncing
                    !(type == MessageType.PING || type == MessageType.DISCONNECT))
            {
                LOGGER.warning("Server not ready (syncing), rejecting client request: " + type);
                sendMessage(new Message(MessageType.ERROR, "Server is initializing/syncing. Please try again shortly."));
                return;
            }


            switch (type) {
                case REGISTER:
                    if (payload instanceof Credentials) server.registerVoter((Credentials) payload, this);
                    else throw new ClassCastException("Expected Credentials for REGISTER");
                    break;
                case LOGIN:
                    if (payload instanceof Credentials) {
                        if (this.voterId == null) server.loginVoter((Credentials) payload, this);
                        else sendMessage(new Message(MessageType.ALREADY_LOGGED_IN, "Already logged in on this connection."));
                    } else throw new ClassCastException("Expected Credentials for LOGIN");
                    break;
                case GET_CANDIDATES:
                    sendMessage(new Message(MessageType.CANDIDATE_LIST, server.getCandidates()));
                    break;
                case SUBMIT_VOTE:
                    if (payload instanceof String) {
                        if (this.voterId != null) server.recordVote(this.voterId, (String) payload, this);
                        else sendMessage(new Message(MessageType.AUTHENTICATION_REQUIRED, "Login required to vote."));
                    } else throw new ClassCastException("Expected String (CandidateId) for SUBMIT_VOTE");
                    break;
                case GET_RESULTS:
                    // Allow getting results even if not started, server logic handles empty results
                    sendMessage(new Message(MessageType.RESULTS_DATA, server.getResults()));
                    break;
                case PING:
                    sendMessage(new Message(MessageType.PONG, null));
                    break;
                case DISCONNECT:
                    running = false; // Signal loop to stop
                    break;
                // Default case handles unknown client message types
                default:
                    LOGGER.warning("Unhandled client message type from " + clientAddress + ": " + type);
                    sendMessage(new Message(MessageType.ERROR, "Unknown request type received."));
                    break;
            }
        } catch (ClassCastException e) {
            // Handle invalid data types sent by client
            LOGGER.warning("Invalid payload type for " + type + " from " + clientAddress + ": " + e.getMessage());
            sendMessage(new Message(MessageType.ERROR, "Invalid data format for request " + type));
        } catch (Exception e) {
            // Catch-all for errors during client message processing
            LOGGER.log(Level.SEVERE, "Error processing client message " + type + " for " + clientAddress, e);
            try {
                sendMessage(new Message(MessageType.ERROR, "Server error processing your request (" + type + ")."));
            } catch (Exception sendEx) { /* Ignore if sending error fails */ }
        }
    }

    /** Sends a message object to the connected client/peer. Synchronized for thread safety on the output stream. */
    public synchronized void sendMessage(Message message) {
        if (out == null || clientSocket.isClosed()) {
            LOGGER.fine("Cannot send " + message.getType() + " to " + clientAddress + ", stream/socket closed.");
            return;
        }
        try {
            // Write the object, flush ensure it's sent, reset helps with object caching issues if sending same object multiple times
            out.writeObject(message);
            out.flush();
            out.reset();
            LOGGER.finest("Sent message " + message.getType() + " to " + clientAddress);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Send failed to " + clientAddress + " (" + message.getType() + "): " + e.getMessage());
            // If send fails, assume connection is dead and signal loop to stop
            running = false;
        }
    }

    /** Signals the handler to stop and cleans up resources. */
    public void shutdown() {
        if (!running) return; // Prevent multiple shutdowns
        running = false; // Signal the run loop to exit

        // If this handler represented a logged-in voter, notify the server
        if (this.voterId != null) {
            server.handleLogout(this.voterId, this);
        }
        // Close streams and socket
        closeConnectionResources();
    }

    /** Closes the input stream, output stream, and socket, ignoring errors. */
    private void closeConnectionResources() {
        try { if (in != null) in.close(); } catch (IOException e) { /* ignore */ }
        try { if (out != null) out.close(); } catch (IOException e) { /* ignore */ }
        try { if (clientSocket != null && !clientSocket.isClosed()) clientSocket.close(); } catch (IOException e) { /* ignore */ }
        LOGGER.finest("Closed resources for " + clientAddress);
    }

    // --- Getters/Setters ---
    /** Sets the voter ID for this handler (called upon successful login). */
    protected void setVoterId(String voterId) { this.voterId = voterId; }
    /** Gets the remote address of the connected client/peer. */
    public String getClientAddress() { return clientAddress; }
    /** Gets the voter ID if logged in, otherwise null. */
    public String getVoterId() { return voterId; }
}
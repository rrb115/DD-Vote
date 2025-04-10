package ddvote.server;

import ddvote.shared.*;
import java.io.*;
import java.net.Socket;
import java.net.SocketException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ClientHandler implements Runnable {
    private static final Logger LOGGER = Logger.getLogger(ClientHandler.class.getName());
    private final Socket clientSocket;
    private final VotingServer server; // Correct type
    private ObjectOutputStream out;
    private ObjectInputStream in;
    private volatile boolean running = true;
    private String voterId = null; // If logged in as a voter
    private String peerId = null; // If connection is from a peer server
    private final String clientAddress;

    public ClientHandler(Socket socket, VotingServer server) { // Correct type
        this.clientSocket = socket;
        this.server = server;
        this.clientAddress = socket.getRemoteSocketAddress().toString();
        try {
            // Important: Create output stream *first* for Object Stream header exchange
            this.out = new ObjectOutputStream(socket.getOutputStream());
            this.out.flush(); // Flush header
            this.in = new ObjectInputStream(socket.getInputStream());
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Stream init failed for " + clientAddress, e);
            running = false;
            closeConnectionResources();
        }
    }

    @Override
    public void run() {
        if (!running) return;
        String logId = clientAddress; // Default log identifier

        try {
            while (running && !clientSocket.isClosed()) {
                Message msg = (Message) in.readObject();

                // --- Distinguish Peer Heartbeat ---
                if (msg.getType() == MessageType.HEARTBEAT && msg.getPayload() instanceof String) {
                    this.peerId = (String) msg.getPayload(); // Identify connection as peer
                    logId = "Peer:" + peerId; // Update log identifier
                    LOGGER.finest("Rcvd from " + logId + ": " + msg.getType());
                    server.processHeartbeat(this.peerId);
                    // Keep connection open briefly for potential future peer comms, or close?
                    // For simplicity now, just process heartbeat and let loop continue/end.
                    // In a real system, might keep peer connections more persistent.
                    // Let's close after heartbeat for now to avoid many idle peer connections.
                    // running = false; // Uncomment if you want handler thread to exit after one heartbeat
                    continue; // Process next message or exit if running becomes false
                }
                // --- End Peer Heartbeat Handling ---

                // Regular client message processing
                logId = (this.voterId != null) ? this.voterId : clientAddress; // Use voter ID if logged in
                if (this.peerId != null) { // If it was a peer but sent non-heartbeat?
                    LOGGER.warning("Peer " + this.peerId + " sent unexpected message type: " + msg.getType());
                    running = false; // Disconnect unexpected peer messages
                    continue;
                }

                LOGGER.fine("Rcvd from " + logId + ": " + msg.getType());
                handleClientMessage(msg); // Renamed for clarity
            }
        } catch (EOFException | SocketException e) {
            if (running) LOGGER.info("Client/Peer " + logId + " disconnected: " + e.getMessage());
        } catch (IOException | ClassNotFoundException e) {
            if (running) LOGGER.log(Level.WARNING, "Network/Serialization error for client/peer " + logId, e);
        } catch (Exception e) {
            if (running) LOGGER.log(Level.SEVERE, "Unexpected error in handler for client/peer " + logId, e);
        } finally {
            shutdown(); // Will call handleLogout if it was a voter
        }
        LOGGER.info("Handler thread stopped for: " + logId);
    }

    // Renamed from handleMessage
    private void handleClientMessage(Message message) {
        MessageType type = message.getType();
        Object payload = message.getPayload();
        try {
            switch (type) {
                case REGISTER:
                    // ... (rest of switch cases unchanged - make sure they use correct MessageType from ddvote.shared) ...
                    if (payload instanceof Credentials) server.registerVoter((Credentials) payload, this);
                    else throw new ClassCastException("Expected Credentials for REGISTER");
                    break;
                case LOGIN:
                    if (payload instanceof Credentials) {
                        if (this.voterId == null) server.loginVoter((Credentials) payload, this);
                        else sendMessage(new Message(MessageType.ALREADY_LOGGED_IN, "Already logged in."));
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
                    if (server.getElectionState() == ElectionState.NOT_STARTED) sendMessage(new Message(MessageType.ELECTION_NOT_STARTED, "Not started."));
                    else sendMessage(new Message(MessageType.RESULTS_DATA, server.getResults()));
                    break;
                case PING:
                    sendMessage(new Message(MessageType.PONG, null));
                    break;
                case DISCONNECT:
                    running = false;
                    break;
                // Default case handles unknown types
                default:
                    LOGGER.warning("Unhandled message type from " + clientAddress + ": " + type);
                    sendMessage(new Message(MessageType.ERROR, "Unknown request type."));
                    break;
            }
        } catch (ClassCastException e) {
            LOGGER.warning("Invalid payload type for " + type + " from " + clientAddress + ": " + e.getMessage());
            sendMessage(new Message(MessageType.ERROR, "Invalid data for request " + type));
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error processing " + type + " for " + clientAddress, e);
            sendMessage(new Message(MessageType.ERROR, "Server error processing " + type));
        }
    }

    // sendMessage unchanged
    public synchronized void sendMessage(Message message) {
        if (out == null || clientSocket.isClosed()) {
            // Avoid logging errors for heartbeats sent *by* this server, as the handler isn't expecting a reply here
            if (message.getType() != MessageType.HEARTBEAT) {
                LOGGER.warning("Cannot send " + message.getType() + " to " + clientAddress + ", output stream closed/null.");
            }
            return;
        }
        try {
            out.writeObject(message);
            out.flush();
            out.reset(); // Reset object stream cache if sending multiple objects/updates
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Send failed to " + clientAddress + " (" + message.getType() + "): " + e.getMessage());
            // Don't necessarily disconnect immediately, maybe retry later?
            // For simplicity, we still let the read loop detect disconnection.
            running = false; // Trigger shutdown on send failure
        }
    }

    public void shutdown() {
        if (!running) return;
        running = false;
        // Only call logout if it was a logged-in voter, not a peer connection
        if (this.voterId != null) {
            server.handleLogout(this.voterId, this);
        }
        closeConnectionResources();
    }

    private void closeConnectionResources() {
        // ... (no changes) ...
        try { if (in != null) in.close(); } catch (IOException e) { /* ignore */ }
        try { if (out != null) out.close(); } catch (IOException e) { /* ignore */ }
        try { if (clientSocket != null && !clientSocket.isClosed()) clientSocket.close(); } catch (IOException e) { /* ignore */ }
    }

    // --- Getters/Setters unchanged ---
    protected void setVoterId(String voterId) { this.voterId = voterId; }
    public String getClientAddress() { return clientAddress; }
    public String getVoterId() { return voterId; }
}
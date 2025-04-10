package ddvote.server; // CHANGED

// CHANGED Imports
import ddvote.shared.*;
// Standard imports
import java.io.*;
import java.net.Socket;
import java.net.SocketException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ClientHandler implements Runnable {
    // CHANGED Logger name
    private static final Logger LOGGER = Logger.getLogger(ddvote.server.ClientHandler.class.getName());
    private final Socket clientSocket;
    // CHANGED Type
    private final ddvote.server.VotingServer server;
    private ObjectOutputStream out;
    private ObjectInputStream in;
    private volatile boolean running = true;
    private String voterId = null;
    private final String clientAddress;

    // Constructor uses updated VotingServer type
    public ClientHandler(Socket socket, ddvote.server.VotingServer server) {
        this.clientSocket = socket;
        this.server = server;
        this.clientAddress = socket.getRemoteSocketAddress().toString();
        try {
            this.out = new ObjectOutputStream(socket.getOutputStream());
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
        String logId = clientAddress;
        try {
            while (running && !clientSocket.isClosed()) {
                // Use updated Message type
                ddvote.shared.Message msg = (ddvote.shared.Message) in.readObject();
                logId = (this.voterId != null) ? this.voterId : clientAddress;
                LOGGER.fine("Rcvd from " + logId + ": " + msg.getType());
                handleMessage(msg);
            }
        } catch (EOFException | SocketException e) {
            if (running) LOGGER.info("Client " + logId + " disconnected: " + e.getMessage());
        } catch (IOException | ClassNotFoundException e) {
            if (running) LOGGER.log(Level.WARNING, "Network/Serialization error for client " + logId, e);
        } catch (Exception e) {
            if (running) LOGGER.log(Level.SEVERE, "Unexpected error in handler for client " + logId, e);
        } finally {
            shutdown();
        }
        LOGGER.info("Handler thread stopped for: " + logId);
    }

    // handleMessage uses updated types
    private void handleMessage(ddvote.shared.Message message) {
        ddvote.shared.MessageType type = message.getType();
        Object payload = message.getPayload();
        try {
            switch (type) {
                case REGISTER:
                    if (payload instanceof ddvote.shared.Credentials) server.registerVoter((ddvote.shared.Credentials) payload, this);
                    else throw new ClassCastException("Expected Credentials for REGISTER");
                    break;
                case LOGIN:
                    if (payload instanceof ddvote.shared.Credentials) {
                        if (this.voterId == null) server.loginVoter((ddvote.shared.Credentials) payload, this);
                        else sendMessage(new ddvote.shared.Message(ddvote.shared.MessageType.ALREADY_LOGGED_IN, "Already logged in."));
                    } else throw new ClassCastException("Expected Credentials for LOGIN");
                    break;
                case GET_CANDIDATES:
                    sendMessage(new ddvote.shared.Message(ddvote.shared.MessageType.CANDIDATE_LIST, server.getCandidates()));
                    break;
                case SUBMIT_VOTE:
                    if (payload instanceof String) {
                        if (this.voterId != null) server.recordVote(this.voterId, (String) payload, this);
                        else sendMessage(new ddvote.shared.Message(ddvote.shared.MessageType.AUTHENTICATION_REQUIRED, "Login required to vote."));
                    } else throw new ClassCastException("Expected String (CandidateId) for SUBMIT_VOTE");
                    break;
                case GET_RESULTS:
                    if (server.getElectionState() == ddvote.shared.ElectionState.NOT_STARTED) sendMessage(new ddvote.shared.Message(ddvote.shared.MessageType.ELECTION_NOT_STARTED, "Not started."));
                    else sendMessage(new ddvote.shared.Message(ddvote.shared.MessageType.RESULTS_DATA, server.getResults()));
                    break;
                case PING:
                    sendMessage(new ddvote.shared.Message(ddvote.shared.MessageType.PONG, null));
                    break;
                case DISCONNECT:
                    running = false;
                    break;
                default:
                    LOGGER.warning("Unhandled message type from " + clientAddress + ": " + type);
                    sendMessage(new ddvote.shared.Message(ddvote.shared.MessageType.ERROR, "Unknown request type."));
                    break;
            }
        } catch (ClassCastException e) {
            LOGGER.warning("Invalid payload type for " + type + " from " + clientAddress + ": " + e.getMessage());
            sendMessage(new ddvote.shared.Message(ddvote.shared.MessageType.ERROR, "Invalid data for request " + type));
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error processing " + type + " for " + clientAddress, e);
            sendMessage(new ddvote.shared.Message(ddvote.shared.MessageType.ERROR, "Server error processing " + type));
        }
    }

    // sendMessage uses updated Message type
    public synchronized void sendMessage(ddvote.shared.Message message) {
        if (out == null || clientSocket.isClosed()) return;
        try {
            out.writeObject(message);
            out.flush();
            out.reset();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Send failed to " + clientAddress + ": " + e.getMessage());
            running = false;
        }
    }

    public void shutdown() {
        if (!running) return;
        running = false;
        server.handleLogout(this.voterId, this);
        closeConnectionResources();
    }

    private void closeConnectionResources() {
        try { if (in != null) in.close(); } catch (IOException e) { /* ignore */ }
        try { if (out != null) out.close(); } catch (IOException e) { /* ignore */ }
        try { if (clientSocket != null && !clientSocket.isClosed()) clientSocket.close(); } catch (IOException e) { /* ignore */ }
    }

    protected void setVoterId(String voterId) { this.voterId = voterId; }
    public String getClientAddress() { return clientAddress; }
    public String getVoterId() { return voterId; }
}
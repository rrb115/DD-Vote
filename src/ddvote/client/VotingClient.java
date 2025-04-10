package ddvote.client;

import javax.swing.*;
import java.io.*;
import java.net.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.logging.*;

public class VotingClient {
    // CHANGED Logger name
    private static final Logger LOGGER = Logger.getLogger(ddvote.client.VotingClient.class.getName());
    private String serverAddress;
    private int serverPort;
    private Socket socket;
    private ObjectOutputStream out;
    private ObjectInputStream in;
    private volatile boolean connected = false;
    // CHANGED Type
    private ddvote.client.ClientGUI gui;
    private String loggedInVoterId = null;
    private ExecutorService listenerExecutor;
    private final ExecutorService taskExecutor = Executors.newCachedThreadPool();

    // Constructor uses updated ClientGUI type
    public VotingClient(ddvote.client.ClientGUI gui) { this.gui = gui; setupBasicLogger(); }

    // connect uses updated types
    public void connect(String address, int port) {
        if (connected) { gui.appendToLog("Already connected."); return; }
        SwingWorker<Boolean, String> connector = new SwingWorker<>() {
            @Override protected Boolean doInBackground() throws Exception {
                publish("Connecting to " + address + ":" + port + "...");
                serverAddress = address; serverPort = port;
                try {
                    socket = new Socket();
                    socket.connect(new InetSocketAddress(serverAddress, serverPort), 5000);
                    out = new ObjectOutputStream(socket.getOutputStream());
                    in = new ObjectInputStream(socket.getInputStream());
                    connected = true; loggedInVoterId = null;
                    publish("Connected.");
                    startServerListener();
                    return true;
                } catch (IOException e) {
                    publish("ERROR: Connection failed: " + e.getMessage());
                    LOGGER.log(Level.WARNING, "Connection failed", e);
                    connected = false; disconnect(false); throw e;
                }
            }
            @Override protected void process(List<String> chunks) { for (String msg : chunks) gui.appendToLog(msg); }
            @Override protected void done() {
                try {
                    boolean success = get();
                    SwingUtilities.invokeLater(() -> gui.updateConnectionStatus(success, success ? null : "Failed"));
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    SwingUtilities.invokeLater(() -> gui.updateConnectionStatus(false, cause.getClass().getSimpleName()));
                } catch (CancellationException | InterruptedException e) {
                    SwingUtilities.invokeLater(() -> gui.updateConnectionStatus(false, "Cancelled"));
                    Thread.currentThread().interrupt();
                } finally { SwingUtilities.invokeLater(() -> gui.setConnectButtonEnabled(true)); }
            }
        };
        gui.setConnectButtonEnabled(false);
        taskExecutor.submit(connector);
    }

    private void startServerListener() {
        shutdownListenerExecutor();
        listenerExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "ClientListener"));
        listenerExecutor.submit(() -> {
            LOGGER.info("Listener started.");
            try {
                while (connected && socket != null && !socket.isClosed() && !Thread.currentThread().isInterrupted()) {
                    // Use updated Message type
                    ddvote.shared.Message serverMsg = (ddvote.shared.Message) in.readObject();
                    LOGGER.fine("Rcvd from server: " + serverMsg.getType());
                    SwingUtilities.invokeLater(() -> processServerMessage(serverMsg));
                }
            } catch (EOFException | SocketException e) { if (connected) handleDisconnectError("Connection lost: " + e.getMessage()); }
            catch (IOException | ClassNotFoundException e) { if (connected) handleDisconnectError("Network/Data error: " + e.getMessage()); }
            catch (Exception e) { if (connected) handleDisconnectError("Unexpected listener error: " + e.getMessage()); }
            finally { LOGGER.info("Listener stopped."); if (connected) SwingUtilities.invokeLater(() -> disconnect(false)); }
        });
    }

    private void handleDisconnectError(String errorMessage) {
        LOGGER.warning(errorMessage);
        SwingUtilities.invokeLater(() -> { gui.appendToLog("ERROR: " + errorMessage); disconnect(false); });
    }

    // processServerMessage uses updated types
    private void processServerMessage(ddvote.shared.Message message) {
        if (!SwingUtilities.isEventDispatchThread()) { SwingUtilities.invokeLater(() -> processServerMessage(message)); return; }
        ddvote.shared.MessageType type = message.getType(); Object payload = message.getPayload();
        String text = (payload instanceof String) ? (String) payload : "";
        try {
            switch (type) {
                case REGISTRATION_SUCCESS: gui.showInfoDialog("Register", text); break;
                case REGISTRATION_FAILED: gui.showErrorDialog("Register Failed", text); break;
                case LOGIN_SUCCESS:
                    loggedInVoterId = text.substring(text.indexOf("Welcome, ") + 9, text.indexOf("!"));
                    gui.updateLoginStatus(true, loggedInVoterId);
                    gui.showInfoDialog("Login", text);
                    requestCandidates(); break;
                case LOGIN_FAILED: case ALREADY_LOGGED_IN:
                    gui.updateLoginStatus(false, null); loggedInVoterId = null;
                    gui.showErrorDialog("Login Failed", text); break;
                case AUTHENTICATION_REQUIRED:
                    gui.updateLoginStatus(false, null); loggedInVoterId = null;
                    gui.showWarningDialog("Auth Required", text); break;
                case CANDIDATE_LIST: gui.displayCandidates((List<ddvote.shared.Candidate>) payload); break;
                case VOTE_ACCEPTED: gui.showInfoDialog("Vote Cast", text); gui.updateVotingStatus(true); break;
                case ALREADY_VOTED: gui.showWarningDialog("Already Voted", text); gui.updateVotingStatus(true); break;
                case VOTE_REJECTED: gui.showErrorDialog("Vote Rejected", text); gui.updateVotingStatus(false); break;
                case RESULTS_DATA: gui.displayResults((List<ddvote.shared.VoteResult>) payload); break;
                case ELECTION_NOT_STARTED: gui.updateElectionStatus(ddvote.shared.ElectionState.NOT_STARTED); break;
                case ELECTION_RUNNING: gui.updateElectionStatus(ddvote.shared.ElectionState.RUNNING); if (loggedInVoterId != null) requestCandidates(); break;
                case ELECTION_FINISHED: gui.updateElectionStatus(ddvote.shared.ElectionState.FINISHED); requestResults(); break;
                case PONG: gui.appendToLog("Server Pong received (Ping OK)."); break;
                case SERVER_MESSAGE: gui.showInfoDialog("Server Message", text); gui.appendToLog(text); break;
                case ERROR: gui.showErrorDialog("Server Error", text); break;
                default: gui.appendToLog("WARN: Unhandled msg type: " + type); break;
            }
            if (type != ddvote.shared.MessageType.PONG) gui.appendToLog("Server: " + type + (text.isEmpty() ? "" : " - " + text.split("\n")[0]));
        } catch (Exception e) { gui.appendToLog("ERROR processing msg " + type + ": " + e.getMessage()); LOGGER.log(Level.SEVERE, "Error processing message: " + message, e); }
    }

    // sendMessage uses updated Message type
    public void sendMessage(ddvote.shared.Message message) {
        if (!connected || out == null) { gui.appendToLog("ERROR: Not connected, cannot send " + message.getType()); return; }
        taskExecutor.submit(() -> {
            try {
                synchronized (out) {
                    LOGGER.fine("Sending: " + message.getType());
                    out.writeObject(message); out.flush(); out.reset();
                }
            } catch (IOException e) {
                SwingUtilities.invokeLater(() -> { gui.appendToLog("ERROR sending " + message.getType() + ": " + e.getMessage()); disconnect(false); });
                LOGGER.log(Level.WARNING, "Send failed", e);
            }
        });
    }

    // disconnect uses updated Message type
    public void disconnect(boolean sendDisconnectMsg) {
        if (!connected) return; connected = false;
        gui.appendToLog("Disconnecting..."); LOGGER.info("Disconnecting...");
        if (sendDisconnectMsg && out != null && socket != null && !socket.isClosed()) {
            try { sendMessage(new ddvote.shared.Message(ddvote.shared.MessageType.DISCONNECT, null)); } catch (Exception e) {}
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
        }
        shutdownListenerExecutor();
        try { if (in != null) in.close(); } catch (IOException e) {}
        try { if (out != null) out.close(); } catch (IOException e) {}
        try { if (socket != null) socket.close(); } catch (IOException e) {}
        in = null; out = null; socket = null; loggedInVoterId = null;
        SwingUtilities.invokeLater(() -> {
            gui.updateConnectionStatus(false, "Disconnected"); gui.updateLoginStatus(false, null);
            gui.clearCandidates(); gui.clearResults(); gui.updateElectionStatus(null); gui.updateVotingStatus(false);
            gui.appendToLog("Disconnected.");
        });
        LOGGER.info("Client disconnected.");
    }

    private void shutdownListenerExecutor() { /* ... (same as before) ... */
        if (listenerExecutor != null && !listenerExecutor.isShutdown()) {
            listenerExecutor.shutdownNow(); try { listenerExecutor.awaitTermination(1, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        } listenerExecutor = null;
    }

    // Request methods use updated Credentials/Message types
    public void requestRegister(String id, String pass) { sendMessage(new ddvote.shared.Message(ddvote.shared.MessageType.REGISTER, new ddvote.shared.Credentials(id, pass))); }
    public void requestLogin(String id, String pass) { sendMessage(new ddvote.shared.Message(ddvote.shared.MessageType.LOGIN, new ddvote.shared.Credentials(id, pass))); }
    public void requestCandidates() { sendMessage(new ddvote.shared.Message(ddvote.shared.MessageType.GET_CANDIDATES, null)); }
    public void submitVote(String candidateId) { sendMessage(new ddvote.shared.Message(ddvote.shared.MessageType.SUBMIT_VOTE, candidateId)); }
    public void requestResults() { sendMessage(new ddvote.shared.Message(ddvote.shared.MessageType.GET_RESULTS, null)); }
    public void pingServer() { sendMessage(new ddvote.shared.Message(ddvote.shared.MessageType.PING, null)); }

    public boolean isConnected() { return connected; }
    public String getLoggedInVoterId() { return loggedInVoterId; }
    private void setupBasicLogger() { /* ... (same as before) ... */
        Logger rootLogger = Logger.getLogger(""); rootLogger.setLevel(Level.INFO);
        java.util.logging.ConsoleHandler handler = new java.util.logging.ConsoleHandler(); handler.setLevel(Level.ALL);
        handler.setFormatter(new java.util.logging.SimpleFormatter() {
            private static final String format = "[CLIENT %1$tF %1$tT] [%2$-7s] %3$s %n";
            @Override public synchronized String format(java.util.logging.LogRecord lr) {
                return String.format(format, new java.util.Date(lr.getMillis()), lr.getLevel().getLocalizedName(), lr.getMessage());
            }
        });
        if (rootLogger.getHandlers().length == 0) rootLogger.addHandler(handler); rootLogger.setUseParentHandlers(false);
    }

    // Main method uses updated types
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception e) {}
            // CHANGED Types
            ddvote.client.ClientGUI gui = new ddvote.client.ClientGUI();
            ddvote.client.VotingClient client = new ddvote.client.VotingClient(gui);
            gui.setClient(client);
            gui.setVisible(true);
        });
    }
}
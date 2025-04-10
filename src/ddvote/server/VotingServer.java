package ddvote.server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

public class VotingServer {
    private static final Logger LOGGER = Logger.getLogger(ddvote.server.VotingServer.class.getName());
    private static final int DEFAULT_PORT = 12345;
    private static final boolean SIMULATE_VOTE_DELAY = false;
    private static final int VOTE_DELAY_MS = 100;

    private final int port;
    private volatile boolean running = true;
    private ServerSocket serverSocket;
    private final ExecutorService clientExecutorService;
    private final ddvote.server.ServerAdminConsole adminConsole;

    // State maps (types use ddvote.shared implicitly or explicitly)
    private final ConcurrentMap<String, String> registeredVoters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ddvote.server.ClientHandler> activeClients = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ddvote.shared.Candidate> candidates = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Integer> voteCounts = new ConcurrentHashMap<>();
    private final Set<String> votersWhoVoted = ConcurrentHashMap.newKeySet();
    private final AtomicReference<ddvote.shared.ElectionState> electionState = new AtomicReference<>(ddvote.shared.ElectionState.NOT_STARTED);

    public VotingServer(int port) {
        this.port = port;
        this.clientExecutorService = Executors.newCachedThreadPool();
        initializeCandidates();
        this.adminConsole = new ddvote.server.ServerAdminConsole(this);
        LOGGER.info("DD-Vote Server initialized on port " + port); // CHANGED Name
    }

    private void initializeCandidates() {
        addCandidateInternal(new ddvote.shared.Candidate("C1", "Alice", "Infra"));
        addCandidateInternal(new ddvote.shared.Candidate("C2", "Bob", "Services"));
        addCandidateInternal(new ddvote.shared.Candidate("C3", "Charlie", "Arts"));
        addCandidateInternal(new ddvote.shared.Candidate("C4", "Diana", "Env"));
    }

    public void startServer() {
        setupBasicLogger();
        try {
            serverSocket = new ServerSocket(port);
            LOGGER.info("DD-Vote Server listening on port: " + port + " | State: " + electionState.get()); // CHANGED Name
            new Thread(adminConsole, "AdminConsoleThread").start();

            while (running) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    LOGGER.info("[Concurrency] Connection from: " + clientSocket.getRemoteSocketAddress() + " - assigning to handler thread.");
                    // CHANGED Instantiation to use new package
                    clientExecutorService.submit(new ddvote.server.ClientHandler(clientSocket, this));
                } catch (IOException e) {
                    if (running) LOGGER.log(Level.SEVERE, "Error accepting connection", e);
                    else LOGGER.info("Server socket closed.");
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Could not start server on port " + port, e);
        } finally {
            shutdownServer(false);
        }
    }

    public synchronized void shutdownServer(boolean waitAdmin) {
        if (!running) return;
        LOGGER.warning("--- DD-Vote Server Shutdown Initiated ---"); // CHANGED Name
        running = false;
        adminConsole.stop();
        try { if (serverSocket != null && !serverSocket.isClosed()) serverSocket.close(); }
        catch (IOException e) { LOGGER.log(Level.WARNING, "Error closing server socket", e); }

        broadcastMessage(new ddvote.shared.Message(ddvote.shared.MessageType.SERVER_MESSAGE, "SERVER_SHUTDOWN"), true);
        try { Thread.sleep(200); } catch (InterruptedException ignored) {}

        LOGGER.info("[Concurrency] Shutting down " + activeClients.size() + " active client handlers...");
        activeClients.values().forEach(ddvote.server.ClientHandler::shutdown); // Use correct type
        activeClients.clear();

        clientExecutorService.shutdown();
        try {
            if (!clientExecutorService.awaitTermination(5, TimeUnit.SECONDS)) {
                clientExecutorService.shutdownNow();
            }
        } catch (InterruptedException ie) {
            clientExecutorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
        LOGGER.warning("--- DD-Vote Server Shutdown Complete ---"); // CHANGED Name
    }

    // --- Core Logic ---
    // Method signatures use updated package types where necessary
    public synchronized boolean registerVoter(ddvote.shared.Credentials creds, ddvote.server.ClientHandler handler) {
        String voterId = creds.getVoterId();
        String password = creds.getPassword();
        if (voterId == null || voterId.trim().isEmpty() || password == null || password.isEmpty()) {
            handler.sendMessage(new ddvote.shared.Message(ddvote.shared.MessageType.REGISTRATION_FAILED, "ID/Password required."));
            return false;
        }
        voterId = voterId.trim();
        if (registeredVoters.containsKey(voterId)) {
            handler.sendMessage(new ddvote.shared.Message(ddvote.shared.MessageType.REGISTRATION_FAILED, "Voter ID exists."));
            return false;
        }
        registeredVoters.put(voterId, password);
        LOGGER.info("[Concurrency] Registered: " + voterId + " (Thread: " + Thread.currentThread().getName() + ")");
        handler.sendMessage(new ddvote.shared.Message(ddvote.shared.MessageType.REGISTRATION_SUCCESS, "Registered: " + voterId));
        return true;
    }

    public synchronized boolean loginVoter(ddvote.shared.Credentials creds, ddvote.server.ClientHandler handler) {
        String voterId = creds.getVoterId();
        String passwordAttempt = creds.getPassword();
        if (voterId == null || passwordAttempt == null) return false;
        voterId = voterId.trim();

        String storedPassword = registeredVoters.get(voterId);
        if (storedPassword == null || !storedPassword.equals(passwordAttempt)) {
            handler.sendMessage(new ddvote.shared.Message(ddvote.shared.MessageType.LOGIN_FAILED, "Invalid ID or Password."));
            return false;
        }
        if (activeClients.containsKey(voterId)) {
            handler.sendMessage(new ddvote.shared.Message(ddvote.shared.MessageType.ALREADY_LOGGED_IN, "Already logged in elsewhere."));
            return false;
        }

        handler.setVoterId(voterId);
        activeClients.put(voterId, handler);
        LOGGER.info("[Concurrency] Login: " + voterId + " from " + handler.getClientAddress() + " (Thread: " + Thread.currentThread().getName() + ")");
        handler.sendMessage(new ddvote.shared.Message(ddvote.shared.MessageType.LOGIN_SUCCESS, "Welcome, " + voterId + "!"));
        handler.sendMessage(new ddvote.shared.Message(getElectionStateMessageType(), "State: " + getElectionState()));
        if (votersWhoVoted.contains(voterId)) {
            handler.sendMessage(new ddvote.shared.Message(ddvote.shared.MessageType.ALREADY_VOTED, "You have already voted."));
        }
        return true;
    }

    public void handleLogout(String voterId, ddvote.server.ClientHandler handler) {
        if (voterId != null) {
            if (activeClients.remove(voterId) != null) {
                LOGGER.info("[Concurrency] Logout: " + voterId);
            }
        }
    }

    public synchronized void recordVote(String voterId, String candidateId, ddvote.server.ClientHandler handler) {
        if (voterId == null || !activeClients.containsKey(voterId)) {
            handler.sendMessage(new ddvote.shared.Message(ddvote.shared.MessageType.AUTHENTICATION_REQUIRED, "Not logged in.")); return;
        }
        if (electionState.get() != ddvote.shared.ElectionState.RUNNING) {
            handler.sendMessage(new ddvote.shared.Message(getElectionStateMessageType(), "Voting not active.")); return;
        }
        if (candidateId == null || !candidates.containsKey(candidateId)) {
            handler.sendMessage(new ddvote.shared.Message(ddvote.shared.MessageType.VOTE_REJECTED, "Invalid candidate.")); return;
        }
        if (votersWhoVoted.contains(voterId)) {
            handler.sendMessage(new ddvote.shared.Message(ddvote.shared.MessageType.ALREADY_VOTED, "Already voted.")); return;
        }

        votersWhoVoted.add(voterId);
        LOGGER.fine("[Vote Atomicity] Step 1/2: Marked " + voterId + " as voted.");

        if (SIMULATE_VOTE_DELAY) { /* ... (delay logic same) ... */
            try {
                LOGGER.warning("[Vote Atomicity] SIMULATING DELAY before incrementing count for " + voterId);
                Thread.sleep(VOTE_DELAY_MS);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        voteCounts.compute(candidateId, (id, count) -> (count == null) ? 1 : count + 1);
        LOGGER.fine("[Vote Atomicity] Step 2/2: Incremented count for " + candidateId);

        LOGGER.info("[Concurrency] Vote Recorded: " + voterId + " -> " + candidateId + " (Thread: " + Thread.currentThread().getName() + ")");
        LOGGER.fine("[Replication] Vote for " + candidateId + " needs replication/consensus.");
        handler.sendMessage(new ddvote.shared.Message(ddvote.shared.MessageType.VOTE_ACCEPTED, "Vote for " + candidates.get(candidateId).getName() + " accepted."));
    }

    public List<ddvote.shared.Candidate> getCandidates() {
        return new ArrayList<>(candidates.values());
    }

    public synchronized List<ddvote.shared.VoteResult> getResults() {
        List<ddvote.shared.VoteResult> results = new ArrayList<>();
        candidates.forEach((id, candidate) ->
                results.add(new ddvote.shared.VoteResult(id, candidate.getName(), voteCounts.getOrDefault(id, 0)))
        );
        results.sort((r1, r2) -> Integer.compare(r2.getVoteCount(), r1.getVoteCount()));
        LOGGER.fine("[Consistency] Providing consistent result snapshot.");
        return results;
    }

    public void broadcastMessage(ddvote.shared.Message message, boolean includeUnauthenticated) {
        LOGGER.fine("[Broadcast] Sending: " + message.getType());
        new ArrayList<>(activeClients.values()).forEach(handler -> handler.sendMessage(message));
    }

    // --- Admin Methods ---
    public synchronized void setElectionState(ddvote.shared.ElectionState newState) {
        ddvote.shared.ElectionState oldState = electionState.getAndSet(newState);
        if (oldState != newState) {
            LOGGER.warning("!!! State changed: " + oldState + " -> " + newState + " (Admin) !!!");
            broadcastMessage(new ddvote.shared.Message(getElectionStateMessageType(newState), "ADMIN: State is now " + newState), true);
            if (oldState == ddvote.shared.ElectionState.FINISHED && newState != ddvote.shared.ElectionState.FINISHED) {
                resetElectionDataInternal();
                LOGGER.warning("!!! Election data RESET due to state change from FINISHED !!!");
                broadcastMessage(new ddvote.shared.Message(ddvote.shared.MessageType.SERVER_MESSAGE, "ADMIN: Election data reset."), true);
            }
            if (newState == ddvote.shared.ElectionState.FINISHED) broadcastResults();
        }
    }

    private synchronized void resetElectionDataInternal() {
        voteCounts.clear(); votersWhoVoted.clear();
        candidates.keySet().forEach(id -> voteCounts.put(id, 0));
        LOGGER.info("[State] Internal election data reset.");
    }

    public synchronized boolean resetElectionDataAdmin(boolean force) {
        if (electionState.get() == ddvote.shared.ElectionState.RUNNING && !force) {
            System.err.println("WARN: Election running. Use 'reset_votes force'."); return false;
        }
        resetElectionDataInternal();
        LOGGER.warning("!!! Election data RESET by admin command !!!");
        broadcastMessage(new ddvote.shared.Message(ddvote.shared.MessageType.SERVER_MESSAGE, "ADMIN: Election data reset."), true);
        return true;
    }

    private synchronized boolean addCandidateInternal(ddvote.shared.Candidate candidate) {
        if (candidates.containsKey(candidate.getId())) return false;
        candidates.put(candidate.getId(), candidate);
        voteCounts.putIfAbsent(candidate.getId(), 0);
        return true;
    }

    public synchronized boolean addCandidateAdmin(ddvote.shared.Candidate candidate) {
        if (electionState.get() != ddvote.shared.ElectionState.NOT_STARTED) {
            LOGGER.warning("[State] Admin adding candidate while election state is " + electionState.get());
        }
        boolean added = addCandidateInternal(candidate);
        if(added) LOGGER.info("Admin added candidate: " + candidate);
        else LOGGER.severe("Admin failed to add candidate: " + candidate);
        return added;
    }

    // --- Getters & Utilities ---
    public ddvote.shared.ElectionState getElectionState() { return electionState.get(); }
    public Map<String, String> getRegisteredVotersMap() { return registeredVoters; }
    // CHANGED Return type to use new package
    public Map<String, ddvote.server.ClientHandler> getActiveClientsMap() { return activeClients; }
    public Set<String> getVotersWhoVotedSet() { return votersWhoVoted; }

    public ddvote.shared.MessageType getElectionStateMessageType() { return getElectionStateMessageType(getElectionState()); }
    public ddvote.shared.MessageType getElectionStateMessageType(ddvote.shared.ElectionState state) {
        switch (state) {
            case NOT_STARTED: return ddvote.shared.MessageType.ELECTION_NOT_STARTED;
            case RUNNING:     return ddvote.shared.MessageType.ELECTION_RUNNING;
            case FINISHED:    return ddvote.shared.MessageType.ELECTION_FINISHED;
            default:          return ddvote.shared.MessageType.ERROR;
        }
    }
    private void setupBasicLogger() { /* ... (same as before) ... */
        Logger rootLogger = Logger.getLogger(""); rootLogger.setLevel(Level.INFO);
        java.util.logging.ConsoleHandler handler = new java.util.logging.ConsoleHandler(); handler.setLevel(Level.ALL);
        handler.setFormatter(new java.util.logging.SimpleFormatter() {
            private static final String format = "[%1$tF %1$tT] [%2$-7s] [%3$s] %4$s %n";
            @Override public synchronized String format(java.util.logging.LogRecord lr) {
                return String.format(format, new java.util.Date(lr.getMillis()), lr.getLevel().getLocalizedName(), Thread.currentThread().getName(), lr.getMessage());
            }
        });
        if (rootLogger.getHandlers().length == 0) rootLogger.addHandler(handler); rootLogger.setUseParentHandlers(false);
    }

    public void broadcastResults() {
        List<ddvote.shared.VoteResult> results = getResults();
        broadcastMessage(new ddvote.shared.Message(ddvote.shared.MessageType.RESULTS_DATA, results), true);
        LOGGER.info("Broadcasted results.");
    }

    // --- Main ---
    public static void main(String[] args) {
        int port = DEFAULT_PORT;
        if (args.length > 0) try { port = Integer.parseInt(args[0]); } catch (NumberFormatException e) { /* Use default */ }

        // CHANGED Instantiation to use new package name
        ddvote.server.VotingServer server = new ddvote.server.VotingServer(port);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.shutdownServer(true), "ServerShutdownHook"));
        server.startServer();
    }
}
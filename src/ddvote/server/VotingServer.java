package ddvote.server;

import ddvote.shared.*;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class VotingServer {
    private static final Logger LOGGER = Logger.getLogger(VotingServer.class.getName());
    private static final int DEFAULT_PORT = 12345;
    private static final boolean SIMULATE_VOTE_DELAY = false;
    private static final int VOTE_DELAY_MS = 100;
    // --- Distributed Computing Enhancements ---
    private static final long HEARTBEAT_INTERVAL_MS = 2000;
    private static final long PEER_TIMEOUT_MS = HEARTBEAT_INTERVAL_MS * 3 + 500;
    private final String ownServerId;
    private final Map<String, PeerInfo> peers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService peerExecutorService;
    private final AtomicReference<String> currentPrimaryId = new AtomicReference<>(null);
    private final AtomicBoolean isSelfPrimary = new AtomicBoolean(false);
    // --- End Distributed Computing Enhancements ---

    private final int port;
    private volatile boolean running = true;
    private ServerSocket serverSocket;
    private final ExecutorService clientExecutorService;
    private final ServerAdminConsole adminConsole;

    // State maps
    private final ConcurrentMap<String, String> registeredVoters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ClientHandler> activeClients = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Candidate> candidates = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Integer> voteCounts = new ConcurrentHashMap<>();
    private final Set<String> votersWhoVoted = ConcurrentHashMap.newKeySet();
    private final AtomicReference<ElectionState> electionState = new AtomicReference<>(ElectionState.NOT_STARTED);

    // Inner class to hold peer status - MADE PUBLIC and FIELDS PUBLIC
    public static class PeerInfo {
        public final String serverId; // Made public
        public final String host;     // Made public
        public final int port;        // Made public
        public final AtomicLong lastHeartbeatReceived = new AtomicLong(0); // Made public (AtomicLong itself is thread-safe)
        public final AtomicBoolean isUp = new AtomicBoolean(false);         // Made public (AtomicBoolean itself is thread-safe)

        // Constructor remains package-private or public, doesn't matter much here
        PeerInfo(String serverId, String host, int port) {
            this.serverId = serverId;
            this.host = host;
            this.port = port;
        }
    }

    // Constructor
    public VotingServer(int port, String ownServerId, List<String> peerAddresses) {
        this.port = port;
        this.ownServerId = ownServerId;
        this.clientExecutorService = Executors.newCachedThreadPool();
        this.peerExecutorService = Executors.newScheduledThreadPool(2);
        initializeCandidates();
        initializePeers(peerAddresses);
        this.adminConsole = new ServerAdminConsole(this);
        LOGGER.info("DD-Vote Server (" + ownServerId + ") initialized on port " + port);
    }

    private void initializePeers(List<String> peerAddresses) {
        for (String address : peerAddresses) {
            try {
                String[] parts = address.split(":");
                if (parts.length != 2) throw new IllegalArgumentException("Invalid peer format");
                String host = parts[0];
                int peerPort = Integer.parseInt(parts[1]);
                String serverId = host + ":" + peerPort;
                if (!serverId.equals(this.ownServerId)) {
                    // Use the PeerInfo constructor (access is fine from within VotingServer)
                    peers.put(serverId, new PeerInfo(serverId, host, peerPort));
                    LOGGER.info("[Peers] Added potential peer: " + serverId);
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Could not parse peer address: " + address, e);
            }
        }
    }

    private void initializeCandidates() {
        addCandidateInternal(new Candidate("C1", "Jash", "Actor"));
        addCandidateInternal(new Candidate("C2", "Myana", "Politician"));
        addCandidateInternal(new Candidate("C3", "Abhishek", "Carrom Player"));
        addCandidateInternal(new Candidate("C4", "Divyansh", "Film Director"));
    }

    public void startServer() {
        setupBasicLogger();
        try {
            serverSocket = new ServerSocket(port);
            LOGGER.info("DD-Vote Server (" + ownServerId + ") listening on port: " + port + " | State: " + electionState.get());
            new Thread(adminConsole, "AdminConsoleThread").start();
            startPeerInteractions();

            while (running) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    LOGGER.fine("[Network] Connection from: " + clientSocket.getRemoteSocketAddress());
                    clientExecutorService.submit(new ClientHandler(clientSocket, this));
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

    private void startPeerInteractions() {
        if (peers.isEmpty()) {
            LOGGER.warning("[Peers] No peers configured. Running in standalone mode.");
            this.currentPrimaryId.set(this.ownServerId);
            this.isSelfPrimary.set(true);
            return;
        }
        peerExecutorService.scheduleAtFixedRate(this::sendHeartbeatsToPeers,
                HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
        peerExecutorService.scheduleAtFixedRate(this::checkPeerStatusesAndUpdateRole,
                PEER_TIMEOUT_MS, PEER_TIMEOUT_MS / 2, TimeUnit.MILLISECONDS);
        LOGGER.info("[Peers] Started heartbeat and status checking tasks.");
    }

    private void sendHeartbeatsToPeers() {
        if (!running) return;
        LOGGER.finest("[Heartbeat] Sending heartbeats..."); // Changed level to finest
        for (PeerInfo peer : peers.values()) {
            peerExecutorService.submit(() -> sendSingleHeartbeat(peer));
        }
    }

    private void sendSingleHeartbeat(PeerInfo peer) {
        if (!running) return;
        try (Socket peerSocket = new Socket()) {
            peerSocket.connect(new InetSocketAddress(peer.host, peer.port), (int) HEARTBEAT_INTERVAL_MS / 2);
            peerSocket.setSoTimeout((int) HEARTBEAT_INTERVAL_MS / 2);
            try (ObjectOutputStream peerOut = new ObjectOutputStream(peerSocket.getOutputStream())) {
                Message heartbeatMsg = new Message(MessageType.HEARTBEAT, this.ownServerId);
                peerOut.writeObject(heartbeatMsg);
                peerOut.flush();
                LOGGER.finest("[Heartbeat] Sent HEARTBEAT to " + peer.serverId);
            }
        } catch (IOException e) {
            if (peer.isUp.compareAndSet(true, false)) {
                LOGGER.warning("[Heartbeat] Failed to send HEARTBEAT to " + peer.serverId + ". Marking as potentially DOWN. Reason: " + e.getMessage());
            } else {
                LOGGER.finest("[Heartbeat] Still unable to reach " + peer.serverId); // Changed level
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "[Heartbeat] Unexpected error sending heartbeat to " + peer.serverId, e);
        }
    }

    public void processHeartbeat(String peerId) {
        PeerInfo peer = peers.get(peerId);
        if (peer != null) {
            peer.lastHeartbeatReceived.set(System.currentTimeMillis());
            if (peer.isUp.compareAndSet(false, true)) {
                LOGGER.info("[Heartbeat] Received first HEARTBEAT from " + peerId + ". Marking as UP.");
                peerExecutorService.submit(this::checkPeerStatusesAndUpdateRole); // Trigger immediate check
            } else {
                LOGGER.finest("[Heartbeat] Received HEARTBEAT from " + peerId); // Changed level
            }
        } else {
            LOGGER.warning("[Heartbeat] Received HEARTBEAT from unknown peer: " + peerId);
        }
    }

    private void checkPeerStatusesAndUpdateRole() {
        if (!running || peers.isEmpty()) return;
        long now = System.currentTimeMillis();
        boolean roleOrStatusChanged = false;

        // Check status
        for (PeerInfo peer : peers.values()) {
            long lastSeen = peer.lastHeartbeatReceived.get();
            boolean currentlyUp = peer.isUp.get();
            if (currentlyUp && (now - lastSeen > PEER_TIMEOUT_MS)) {
                if (peer.isUp.compareAndSet(true, false)) {
                    LOGGER.warning("[Peers] Peer " + peer.serverId + " TIMED OUT. Marked as DOWN.");
                    roleOrStatusChanged = true;
                }
            }
        }

        // Determine Primary
        List<String> upServerIds = new ArrayList<>();
        upServerIds.add(this.ownServerId); // Always include self
        peers.values().stream()
                .filter(p -> p.isUp.get())
                .map(p -> p.serverId) // Get the serverId field (now public)
                .forEach(upServerIds::add);

        Collections.sort(upServerIds);
        String newPrimaryId = upServerIds.isEmpty() ? this.ownServerId : upServerIds.get(0); // Default to self if list empty? Or null? Let's stick to lowest up.

        String oldPrimary = this.currentPrimaryId.getAndSet(newPrimaryId);
        boolean selfIsNowPrimary = this.ownServerId.equals(newPrimaryId);
        boolean selfWasPrimary = this.isSelfPrimary.getAndSet(selfIsNowPrimary);

        if (!Objects.equals(oldPrimary, newPrimaryId)) {
            LOGGER.info("[Role] Primary changed from " + oldPrimary + " -> " + newPrimaryId);
            roleOrStatusChanged = true;
        }
        if (selfWasPrimary != selfIsNowPrimary) {
            LOGGER.info("[Role] This server (" + ownServerId + ") is now " + (selfIsNowPrimary ? "PRIMARY" : "BACKUP"));
            roleOrStatusChanged = true;
        }

        if (roleOrStatusChanged) {
            // Log current UP peers only if something changed
            List<String> finalUpPeers = peers.values().stream().filter(p -> p.isUp.get()).map(p -> p.serverId).sorted().collect(Collectors.toList());
            LOGGER.info("[Peers] Current UP peers (excluding self): " + finalUpPeers);
        }
        LOGGER.finest("[Peers] Status check complete. Primary=" + newPrimaryId + ", SelfIsPrimary=" + selfIsNowPrimary); // Changed level
    }

    public synchronized void shutdownServer(boolean waitAdmin) {
        if (!running) return;
        LOGGER.warning("--- DD-Vote Server (" + ownServerId + ") Shutdown Initiated ---");
        running = false;
        adminConsole.stop();
        peerExecutorService.shutdownNow();
        try { peerExecutorService.awaitTermination(1, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        LOGGER.info("[Peers] Stopped heartbeat and status checking.");
        try { if (serverSocket != null && !serverSocket.isClosed()) serverSocket.close(); } catch (IOException e) {}
        broadcastMessage(new Message(MessageType.SERVER_MESSAGE, "SERVER_SHUTDOWN"), true);
        try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        LOGGER.info("[Concurrency] Shutting down " + activeClients.size() + " active client handlers...");
        activeClients.values().forEach(ClientHandler::shutdown);
        activeClients.clear();
        clientExecutorService.shutdown();
        try { if (!clientExecutorService.awaitTermination(5, TimeUnit.SECONDS)) clientExecutorService.shutdownNow(); }
        catch (InterruptedException ie) { clientExecutorService.shutdownNow(); Thread.currentThread().interrupt(); }
        LOGGER.warning("--- DD-Vote Server (" + ownServerId + ") Shutdown Complete ---");
    }

    // --- Core Logic (Register, Login, Vote with Role Checks) ---
    public synchronized boolean registerVoter(Credentials creds, ClientHandler handler) {
        String voterId = creds.getVoterId(); String password = creds.getPassword();
        if (voterId == null || voterId.trim().isEmpty() || password == null || password.isEmpty()) {
            handler.sendMessage(new Message(MessageType.REGISTRATION_FAILED, "ID/Password required.")); return false;
        }
        voterId = voterId.trim();
        if (!this.isSelfPrimary.get()) {
            LOGGER.warning("[Role] Received REGISTER on BACKUP node. Rejecting.");
            handler.sendMessage(new Message(MessageType.ERROR, "Not primary server (" + this.currentPrimaryId.get() + "), cannot register."));
            return false; // Enforce primary writes
        }
        if (registeredVoters.containsKey(voterId)) {
            handler.sendMessage(new Message(MessageType.REGISTRATION_FAILED, "Voter ID exists.")); return false;
        }
        registeredVoters.put(voterId, password);
        LOGGER.info("[State] Registered: " + voterId);
        // TODO: Replicate registration
        handler.sendMessage(new Message(MessageType.REGISTRATION_SUCCESS, "Registered: " + voterId));
        return true;
    }

    public synchronized boolean loginVoter(Credentials creds, ClientHandler handler) {
        String voterId = creds.getVoterId(); String passwordAttempt = creds.getPassword();
        if (voterId == null || passwordAttempt == null) return false;
        voterId = voterId.trim();
        String storedPassword = registeredVoters.get(voterId); // Reads OK on backups
        if (storedPassword == null || !storedPassword.equals(passwordAttempt)) {
            handler.sendMessage(new Message(MessageType.LOGIN_FAILED, "Invalid ID or Password.")); return false;
        }
        if (activeClients.containsKey(voterId)) {
            handler.sendMessage(new Message(MessageType.ALREADY_LOGGED_IN, "Already logged in elsewhere.")); return false;
        }
        handler.setVoterId(voterId); activeClients.put(voterId, handler);
        LOGGER.info("[State] Login: " + voterId + " from " + handler.getClientAddress());
        handler.sendMessage(new Message(MessageType.LOGIN_SUCCESS, "Welcome, " + voterId + "!"));
        handler.sendMessage(new Message(getElectionStateMessageType(), "State: " + getElectionState()));
        if (votersWhoVoted.contains(voterId)) {
            handler.sendMessage(new Message(MessageType.ALREADY_VOTED, "You have already voted."));
        }
        return true;
    }

    public void handleLogout(String voterId, ClientHandler handler) {
        if (voterId != null && activeClients.remove(voterId) != null) {
            LOGGER.info("[State] Logout: " + voterId);
        }
    }

    public synchronized void recordVote(String voterId, String candidateId, ClientHandler handler) {
        if (voterId == null || !activeClients.containsKey(voterId)) {
            handler.sendMessage(new Message(MessageType.AUTHENTICATION_REQUIRED, "Not logged in.")); return;
        }
        if (electionState.get() != ElectionState.RUNNING) {
            handler.sendMessage(new Message(getElectionStateMessageType(), "Voting not active.")); return;
        }
        if (!this.isSelfPrimary.get()) {
            LOGGER.warning("[Role] Received SUBMIT_VOTE on BACKUP node. Rejecting.");
            handler.sendMessage(new Message(MessageType.ERROR, "Not primary server (" + this.currentPrimaryId.get() + "), cannot vote."));
            return; // Enforce primary writes
        }
        if (candidateId == null || !candidates.containsKey(candidateId)) {
            handler.sendMessage(new Message(MessageType.VOTE_REJECTED, "Invalid candidate.")); return;
        }
        if (votersWhoVoted.contains(voterId)) {
            handler.sendMessage(new Message(MessageType.ALREADY_VOTED, "Already voted.")); return;
        }

        // Local vote recording (NOT REPLICATED)
        votersWhoVoted.add(voterId);
        voteCounts.compute(candidateId, (id, count) -> (count == null) ? 1 : count + 1);
        LOGGER.info("[State] Vote Recorded (Locally): " + voterId + " -> " + candidateId);
        LOGGER.warning("[Replication] Vote for " + candidateId + " needs replication/consensus in a real system.");
        handler.sendMessage(new Message(MessageType.VOTE_ACCEPTED, "Vote for " + candidates.get(candidateId).getName() + " accepted."));
    }

    // --- Read/Admin Operations with Role Checks ---
    public List<Candidate> getCandidates() { return new ArrayList<>(candidates.values()); } // Reads OK

    public synchronized List<VoteResult> getResults() { // Reads OK
        List<VoteResult> results = new ArrayList<>();
        candidates.forEach((id, candidate) ->
                results.add(new VoteResult(id, candidate.getName(), voteCounts.getOrDefault(id, 0))) );
        results.sort((r1, r2) -> Integer.compare(r2.getVoteCount(), r1.getVoteCount()));
        LOGGER.fine("[Consistency] Providing local result snapshot.");
        return results;
    }

    public void broadcastMessage(Message message, boolean includeUnauthenticated) {
        LOGGER.fine("[Broadcast] Sending: " + message.getType() + " to " + activeClients.size() + " clients.");
        new ArrayList<>(activeClients.values()).forEach(handler -> handler.sendMessage(message));
    }

    public synchronized void setElectionState(ElectionState newState) {
        if (!this.isSelfPrimary.get()) {
            LOGGER.warning("[Role] Received SET_STATE on BACKUP node. Ignoring.");
            System.err.println("Error: Cannot change state on a backup node. Try primary: " + currentPrimaryId.get());
            return; // Enforce primary control
        }
        ElectionState oldState = electionState.getAndSet(newState);
        if (oldState != newState) {
            LOGGER.warning("!!! State changed: " + oldState + " -> " + newState + " (Admin Command on " + ownServerId + ") !!!");
            // TODO: Replicate state change
            broadcastMessage(new Message(getElectionStateMessageType(newState), "ADMIN: State is now " + newState), true);
            if (oldState == ElectionState.FINISHED && newState != ElectionState.FINISHED) {
                resetElectionDataInternal();
                LOGGER.warning("!!! Election data RESET due to state change from FINISHED !!!");
                broadcastMessage(new Message(MessageType.SERVER_MESSAGE, "ADMIN: Election data reset."), true);
            }
            if (newState == ElectionState.FINISHED) broadcastResults();
        }
    }

    private synchronized void resetElectionDataInternal() {
        voteCounts.clear(); votersWhoVoted.clear();
        candidates.keySet().forEach(id -> voteCounts.put(id, 0));
        LOGGER.info("[State] Internal election data reset (Locally).");
        // TODO: Replicate reset
    }

    public synchronized boolean resetElectionDataAdmin(boolean force) {
        if (!this.isSelfPrimary.get()) {
            LOGGER.warning("[Role] Received RESET_VOTES on BACKUP node. Ignoring.");
            System.err.println("Error: Cannot reset votes on a backup node. Try primary: " + currentPrimaryId.get());
            return false; // Enforce primary control
        }
        if (electionState.get() == ElectionState.RUNNING && !force) {
            System.err.println("WARN: Election running. Use 'reset_votes force'."); return false;
        }
        resetElectionDataInternal();
        LOGGER.warning("!!! Election data RESET by admin command (Locally) !!!");
        broadcastMessage(new Message(MessageType.SERVER_MESSAGE, "ADMIN: Election data reset."), true);
        return true;
    }

    private synchronized boolean addCandidateInternal(Candidate candidate) {
        if (candidates.containsKey(candidate.getId())) return false;
        candidates.put(candidate.getId(), candidate); voteCounts.putIfAbsent(candidate.getId(), 0);
        // TODO: Replicate candidate addition
        return true;
    }

    public synchronized boolean addCandidateAdmin(Candidate candidate) {
        if (!this.isSelfPrimary.get()) {
            LOGGER.warning("[Role] Received ADD_CANDIDATE on BACKUP node. Ignoring.");
            System.err.println("Error: Cannot add candidate on backup. Try primary: " + currentPrimaryId.get());
            return false; // Enforce primary control
        }
        if (electionState.get() != ElectionState.NOT_STARTED) {
            LOGGER.warning("[State] Admin adding candidate while election state is " + electionState.get());
        }
        boolean added = addCandidateInternal(candidate);
        if(added) LOGGER.info("Admin added candidate: " + candidate + " (Locally)");
        else LOGGER.warning("Admin failed to add candidate (already exists?): " + candidate);
        return added;
    }

    // --- Getters & Utilities ---
    public ElectionState getElectionState() { return electionState.get(); }
    public Map<String, String> getRegisteredVotersMap() { return registeredVoters; }
    public Map<String, ClientHandler> getActiveClientsMap() { return activeClients; }
    public Set<String> getVotersWhoVotedSet() { return votersWhoVoted; }
    public MessageType getElectionStateMessageType() { return getElectionStateMessageType(getElectionState()); }
    public MessageType getElectionStateMessageType(ElectionState state) { /* ... (no changes) ... */ return null;} // Placeholder return
    private void setupBasicLogger() { /* ... (no changes) ... */ }
    public void broadcastResults() { /* ... (no changes) ... */ }

    // --- Distributed Info Getters ---
    public String getOwnServerId() { return ownServerId; }
    public String getCurrentPrimaryId() { return currentPrimaryId.get(); }
    public boolean isSelfPrimary() { return isSelfPrimary.get(); }
    public Map<String, PeerInfo> getPeerInfoMap() { return Collections.unmodifiableMap(peers); }

    // --- Main Method (Unchanged from previous fix) ---
    public static void main(String[] args) {
        int port = DEFAULT_PORT; String myId = null; List<String> peerAddresses = new ArrayList<>();
        if (args.length < 2) {
            System.err.println("Usage: java ddvote.server.VotingServer <port> <serverId> [peer1_host:port] ..."); System.exit(1);
        }
        try {
            port = Integer.parseInt(args[0]); myId = args[1];
            if (args.length > 2) { peerAddresses.addAll(Arrays.asList(args).subList(2, args.length)); }
        } catch (Exception e) { System.err.println("Error parsing arguments: " + e.getMessage()); System.exit(1); }
        VotingServer server = new VotingServer(port, myId, peerAddresses);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.shutdownServer(true), "ServerShutdownHook"));
        server.startServer();
    }
}
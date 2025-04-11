package ddvote.server;

import ddvote.shared.*; // Import new shared classes

// IO Imports
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.StringWriter; // <<< ADDED IMPORT
import java.io.PrintWriter; // <<< ADDED IMPORT

// Net Imports
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

// Util Imports
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

// Logging Imports <<< ADDED IMPORTS
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;


/**
 * The main server class for the Distributed Voting System (DD-Vote).
 * Manages election state, voter registration, vote counting, client connections,
 * and peer-to-peer communication for heartbeating, replication, and state transfer.
 */
public class VotingServer {
    private static final Logger LOGGER = Logger.getLogger(VotingServer.class.getName());
    private static final int DEFAULT_PORT = 12345;
    private static final boolean SIMULATE_VOTE_DELAY = false; // For testing concurrency issues
    private static final int VOTE_DELAY_MS = 100;

    // --- Distributed System Configuration ---
    private static final long HEARTBEAT_INTERVAL_MS = 2000;     // How often to send heartbeats
    private static final long PEER_TIMEOUT_MS = HEARTBEAT_INTERVAL_MS * 3 + 500; // Timeout for considering a peer down
    private static final long HEARTBEAT_CLOSE_DELAY_MS = 50;    // Small delay before closing heartbeat socket to prevent receiver errors
    private static final long STATE_REQUEST_DELAY_MS = 5000;    // Initial delay before backup requests state
    private static final long STATE_REQUEST_RETRY_MS = 10000;   // Delay before retrying state request if primary unavailable

    // --- Distributed State Management ---
    private final String ownServerId;                             // Unique identifier for this server instance (e.g., "host:port")
    private final Map<String, PeerInfo> peers = new ConcurrentHashMap<>(); // Info about known peer servers
    private final ScheduledExecutorService peerExecutorService;     // Executes heartbeat sending, status checks, state requests
    private final AtomicReference<String> currentPrimaryId = new AtomicReference<>(null); // Currently perceived primary server ID
    private final AtomicBoolean isSelfPrimary = new AtomicBoolean(false);  // Is this instance the primary?
    private final LamportClock lamportClock = new LamportClock();     // Logical clock for event ordering
    private final AtomicBoolean stateTransferComplete = new AtomicBoolean(false); // Has this server received initial state?

    // --- Core Application State (Requires Careful Synchronization) ---
    private final int port;                                       // Port this server listens on
    private volatile boolean running = true;                      // Flag to control the main server loop
    private ServerSocket serverSocket;                            // Listens for incoming connections
    private final ExecutorService clientExecutorService;          // Handles client/peer connections threads
    private final ServerAdminConsole adminConsole;                // Handles administrative commands

    // Data Maps - Use Concurrent versions for basic thread safety within this instance
    private final ConcurrentMap<String, String> registeredVoters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ClientHandler> activeClients = new ConcurrentHashMap<>(); // Tracks logged-in clients
    private final ConcurrentMap<String, Candidate> candidates = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Integer> voteCounts = new ConcurrentHashMap<>();
    private final Set<String> votersWhoVoted = ConcurrentHashMap.newKeySet(); // Set view backed by a ConcurrentHashMap

    // Election State
    private final AtomicReference<ElectionState> electionState = new AtomicReference<>(ElectionState.NOT_STARTED);

    /**
     * Holds status information about a peer server.
     */
    public static class PeerInfo {
        public final String serverId;
        public final String host;
        public final int port;
        public final AtomicLong lastHeartbeatReceived = new AtomicLong(0); // Timestamp of last received heartbeat
        public final AtomicBoolean isUp = new AtomicBoolean(false);         // Current perceived status of the peer

        PeerInfo(String serverId, String host, int port) {
            this.serverId = serverId;
            this.host = host;
            this.port = port;
        }
    }

    /**
     * Constructor for VotingServer.
     * @param port Port to listen on.
     * @param ownServerId Unique ID for this server.
     * @param peerAddresses List of peer server addresses ("host:port").
     */
    public VotingServer(int port, String ownServerId, List<String> peerAddresses) {
        this.port = port;
        this.ownServerId = ownServerId;
        this.clientExecutorService = Executors.newCachedThreadPool();
        // Need threads for heartbeats, status checks, and potentially state requests/sends
        this.peerExecutorService = Executors.newScheduledThreadPool(3, r -> new Thread(r, "PeerExecutor"));
        initializeCandidates(); // Initialize default candidates locally first
        initializePeers(peerAddresses);
        this.adminConsole = new ServerAdminConsole(this);
        LOGGER.info("DD-Vote Server (" + ownServerId + ") initialized on port " + port);
    }

    /** Populates the initial list of known peers based on provided addresses. */
    private void initializePeers(List<String> peerAddresses) {
        for (String address : peerAddresses) {
            try {
                String[] parts = address.split(":", 2); // Split only on the first colon
                if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
                    throw new IllegalArgumentException("Invalid peer format: " + address);
                }
                String host = parts[0];
                int peerPort = Integer.parseInt(parts[1]);
                String serverId = host + ":" + peerPort;
                if (!serverId.equals(this.ownServerId)) { // Don't add self as a peer
                    peers.put(serverId, new PeerInfo(serverId, host, peerPort));
                    LOGGER.info("[Peers] Added potential peer: " + serverId);
                }
            } catch (NumberFormatException e){
                LOGGER.warning("Invalid port number in peer address: " + address);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Could not parse peer address: " + address, e);
            }
        }
    }

    /** Initializes the default set of candidates. */
    private void initializeCandidates() {
        // Add candidates using the internal method, timestamp doesn't matter much here initially
        addCandidateInternal(new Candidate("C1", "Jash", "Actor"), 0);
        addCandidateInternal(new Candidate("C2", "Abhishek", "Carrom Player"), 0);
        addCandidateInternal(new Candidate("C3", "Divyansh", "Director"), 0);
        addCandidateInternal(new Candidate("C4", "Myana", "Politician"), 0);
        LOGGER.info("[State] Initialized default candidates.");
    }

    /** Starts the main server loop and peer interaction tasks. */
    public void startServer() {
        setupBasicLogger(); // Configure logger format
        try {
            serverSocket = new ServerSocket(port);
            LOGGER.info("DD-Vote Server (" + ownServerId + ") listening on port: " + port + " | State: " + electionState.get());
            // Start admin console in a separate thread
            new Thread(adminConsole, "AdminConsoleThread").start();
            // Start peer communication (heartbeats, status checks, etc.)
            startPeerInteractions();

            // Main loop to accept incoming connections
            while (running) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    LOGGER.fine("[Network] Connection accepted from: " + clientSocket.getRemoteSocketAddress());
                    // Submit connection to a handler thread
                    clientExecutorService.submit(new ClientHandler(clientSocket, this));
                } catch (IOException e) {
                    if (running) { // Don't log error if shutdown was initiated
                        LOGGER.log(Level.SEVERE, "Error accepting connection", e);
                    } else {
                        LOGGER.info("Server socket closed during shutdown.");
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Could not start server on port " + port, e);
            // Ensure shutdown happens if server socket fails
            running = false;
        } finally {
            // Ensure resources are cleaned up regardless of how loop exits
            shutdownServer(false);
        }
    }

    /** Starts the scheduled tasks for peer heartbeating and status checking. */
    private void startPeerInteractions() {
        if (peers.isEmpty()) {
            LOGGER.warning("[Peers] No peers configured. Running in standalone mode.");
            // If standalone, assume self is primary and state is complete
            this.currentPrimaryId.set(this.ownServerId);
            this.isSelfPrimary.set(true);
            this.stateTransferComplete.set(true);
            return;
        }

        // Task 1: Periodically send heartbeats to all known peers
        peerExecutorService.scheduleAtFixedRate(this::sendHeartbeatsToPeers,
                HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);

        // Task 2: Periodically check peer statuses (based on received heartbeats) and update roles
        peerExecutorService.scheduleAtFixedRate(this::checkPeerStatusesAndUpdateRole,
                PEER_TIMEOUT_MS, PEER_TIMEOUT_MS / 2, TimeUnit.MILLISECONDS); // Check more often than timeout

        // Task 3: If not primary, schedule an initial attempt to request state from the primary
        peerExecutorService.schedule(this::requestStateFromPrimaryIfNeeded,
                STATE_REQUEST_DELAY_MS, TimeUnit.MILLISECONDS);

        LOGGER.info("[Peers] Started peer interaction tasks (Heartbeat Send/Check, State Request).");
    }

    /** Submits tasks to send a heartbeat to each known peer. */
    private void sendHeartbeatsToPeers() {
        if (!running) return;
        LOGGER.finest("[Heartbeat] Scheduling heartbeat sends...");
        for (PeerInfo peer : peers.values()) {
            peerExecutorService.submit(() -> sendSingleHeartbeat(peer));
        }
    }

    /** Connects to a single peer, sends a heartbeat message, and closes. */
    private void sendSingleHeartbeat(PeerInfo peer) {
        if (!running) return;
        // Use try-with-resources for automatic socket/stream closure
        try (Socket peerSocket = new Socket()) {
            // Connect with a shorter timeout than the interval
            peerSocket.connect(new InetSocketAddress(peer.host, peer.port), (int) HEARTBEAT_INTERVAL_MS / 2);
            // Set a read timeout (though we don't expect a reply for heartbeat)
            peerSocket.setSoTimeout((int) HEARTBEAT_INTERVAL_MS / 2);

            try (ObjectOutputStream peerOut = new ObjectOutputStream(peerSocket.getOutputStream())) {
                // Send own ID so receiver knows who sent the heartbeat
                Message heartbeatMsg = new Message(MessageType.HEARTBEAT, this.ownServerId);
                peerOut.writeObject(heartbeatMsg);
                peerOut.flush(); // Ensure data is sent
                LOGGER.finest("[Heartbeat] Sent HEARTBEAT to " + peer.serverId);

                // Wait briefly before closing to reduce receiver errors
                try { Thread.sleep(HEARTBEAT_CLOSE_DELAY_MS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        } catch (IOException e) {
            // If connection fails, peer might be down. Update status if it was previously UP.
            if (peer.isUp.compareAndSet(true, false)) {
                LOGGER.warning("[Heartbeat] Failed to send HEARTBEAT to " + peer.serverId + ". Marking as potentially DOWN. Reason: " + e.getMessage());
                // Trigger role check immediately upon detecting potential failure
                peerExecutorService.submit(this::checkPeerStatusesAndUpdateRole);
            } else {
                // Peer was already considered down or connection failed again
                LOGGER.finest("[Heartbeat] Still unable to reach " + peer.serverId + ": " + e.getMessage());
            }
        } catch (Exception e) {
            // Catch unexpected errors during heartbeat sending
            LOGGER.log(Level.SEVERE, "[Heartbeat] Unexpected error sending heartbeat to " + peer.serverId, e);
        }
    }

    /** Processes a received heartbeat message from a peer. */
    public void processHeartbeat(String peerId) {
        PeerInfo peer = peers.get(peerId);
        if (peer != null) {
            // Record the time the heartbeat was received
            peer.lastHeartbeatReceived.set(System.currentTimeMillis());
            // If peer was marked DOWN, mark it UP now and log the change
            if (peer.isUp.compareAndSet(false, true)) {
                LOGGER.info("[Heartbeat] Received first/resumed HEARTBEAT from " + peerId + ". Marking as UP.");
                // Trigger an immediate role check since cluster membership changed
                peerExecutorService.submit(this::checkPeerStatusesAndUpdateRole);
            } else {
                // Regular heartbeat received from a peer already considered UP
                LOGGER.finest("[Heartbeat] Received HEARTBEAT from " + peerId);
            }
        } else {
            // Received heartbeat from an address not in the configured peer list
            LOGGER.warning("[Heartbeat] Received HEARTBEAT from unknown/unconfigured peer: " + peerId);
        }
    }

    /** Periodically checks timeouts for peers and updates the primary role. */
    private void checkPeerStatusesAndUpdateRole() {
        if (!running || peers.isEmpty()) return; // No checks needed if stopped or standalone

        long now = System.currentTimeMillis();
        boolean membershipChanged = false; // Flag if any peer status changes

        // Step 1: Check for timed-out peers
        for (PeerInfo peer : peers.values()) {
            long lastSeen = peer.lastHeartbeatReceived.get();
            boolean currentlyUp = peer.isUp.get();
            // If peer is marked UP but timeout has expired
            // Make sure lastSeen is > 0 to avoid timing out peers never seen
            if (currentlyUp && (lastSeen > 0) && (now - lastSeen > PEER_TIMEOUT_MS)) {
                // Atomically set status to DOWN if it was UP
                if (peer.isUp.compareAndSet(true, false)) {
                    LOGGER.warning("[Peers] Peer " + peer.serverId + " TIMED OUT (last seen " + (now - lastSeen) + "ms ago). Marked as DOWN.");
                    membershipChanged = true;
                }
            }
        }

        // Step 2: Determine the current Primary based on UP peers
        // Simple Strategy: Lowest sortable server ID among all UP servers (including self) is Primary.
        List<String> upServerIds = new ArrayList<>();
        upServerIds.add(this.ownServerId); // Always include self
        peers.values().stream()
                .filter(p -> p.isUp.get())  // Filter for peers marked as UP
                .map(p -> p.serverId)       // Get their server IDs
                .forEach(upServerIds::add); // Add them to the list

        Collections.sort(upServerIds); // Sort IDs lexicographically
        // The first element after sorting is the one with the "lowest" ID
        String newPrimaryId = upServerIds.isEmpty() ? null : upServerIds.get(0); // Should not be empty if self is included

        // Step 3: Update local role status if Primary changed
        String oldPrimary = this.currentPrimaryId.getAndSet(newPrimaryId);
        boolean selfIsNowPrimary = this.ownServerId.equals(newPrimaryId);
        boolean selfWasPrimary = this.isSelfPrimary.getAndSet(selfIsNowPrimary);

        // Log changes if they occurred
        if (!Objects.equals(oldPrimary, newPrimaryId)) {
            LOGGER.info("[Role] Primary changed from " + oldPrimary + " -> " + newPrimaryId);
            membershipChanged = true; // Primary change implies membership/role change
            // If *this* server just became primary, it needs to ensure its state is marked complete
            if (selfIsNowPrimary && !stateTransferComplete.get()) {
                LOGGER.info("[State Transfer] Became primary. Marking state as complete.");
                stateTransferComplete.set(true); // Primary has the definitive state by definition here
            }
            // If *this* server just became a backup, reset state transfer flag to potentially re-sync if needed
            else if (!selfIsNowPrimary) {
                // Only reset if it wasn't already a backup (prevents constant resets if primary flaps)
                if (selfWasPrimary) {
                    LOGGER.info("[State Transfer] Became backup. Resetting state transfer flag.");
                    stateTransferComplete.set(false);
                    // Schedule a state request check
                    peerExecutorService.schedule(this::requestStateFromPrimaryIfNeeded,
                            STATE_REQUEST_DELAY_MS, TimeUnit.MILLISECONDS);
                }
            }
        }
        // Log role change specifically
        if (selfWasPrimary != selfIsNowPrimary) {
            LOGGER.info("[Role] This server (" + ownServerId + ") is now " + (selfIsNowPrimary ? "PRIMARY" : "BACKUP"));
        }

        // If peer status or primary role changed, log the current view of UP peers
        if (membershipChanged) {
            List<String> finalUpPeers = peers.values().stream().filter(p -> p.isUp.get()).map(p -> p.serverId).sorted().collect(Collectors.toList());
            LOGGER.info("[Peers] Current UP peers (excluding self): " + finalUpPeers);
        }
        LOGGER.finest("[Peers] Status check complete. Primary=" + newPrimaryId + ", SelfIsPrimary=" + selfIsNowPrimary);
    }

    /** Gracefully shuts down the server and its associated services. */
    public synchronized void shutdownServer(boolean waitAdmin) {
        if (!running) return; // Prevent multiple shutdown attempts
        LOGGER.warning("--- DD-Vote Server (" + ownServerId + ") Shutdown Initiated ---");
        running = false; // Signal loops to stop

        // Stop admin console first
        if (adminConsole != null) adminConsole.stop();

        // Shutdown peer communication executor
        peerExecutorService.shutdownNow(); // Interrupt running tasks
        try {
            if (!peerExecutorService.awaitTermination(1500, TimeUnit.MILLISECONDS)) {
                LOGGER.warning("[Shutdown] Peer executor did not terminate gracefully.");
            }
        } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        LOGGER.info("[Peers] Stopped peer interaction tasks.");

        // Close the main server socket to stop accepting new connections
        try { if (serverSocket != null && !serverSocket.isClosed()) serverSocket.close(); }
        catch (IOException e) { LOGGER.log(Level.WARNING, "Error closing server socket", e); }

        // Notify connected clients about shutdown (best effort)
        broadcastMessage(new Message(MessageType.SERVER_MESSAGE, "SERVER_SHUTDOWN"), true);
        try { Thread.sleep(200); } catch (InterruptedException ignored) {} // Brief pause for messages to send

        // Shutdown client handler threads
        LOGGER.info("[Concurrency] Shutting down " + activeClients.size() + " active client handlers...");
        // Shutdown handlers gracefully (signals them to stop)
        new ArrayList<>(activeClients.values()).forEach(ClientHandler::shutdown);
        activeClients.clear(); // Clear the map

        // Shutdown the client executor service
        clientExecutorService.shutdown(); // Disable new tasks
        try {
            // Wait a little for existing tasks to terminate
            if (!clientExecutorService.awaitTermination(5, TimeUnit.SECONDS)) {
                clientExecutorService.shutdownNow(); // Forcefully interrupt if they don't finish
                LOGGER.warning("[Shutdown] Client executor did not terminate gracefully.");
            }
        } catch (InterruptedException ie) {
            clientExecutorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
        LOGGER.warning("--- DD-Vote Server (" + ownServerId + ") Shutdown Complete ---");
    }


    // ========================================================================
    // --- Replication Logic ---
    // ========================================================================

    /** Broadcasts a state change operation to all known UP backup servers. */
    private void broadcastReplication(MessageType type, Object data, int timestamp) {
        // Only the Primary server should broadcast replications
        if (!isSelfPrimary.get()) return;
        // Avoid broadcasting if primary itself isn't fully initialized (relevant if state transfer was needed for it)
        if (!stateTransferComplete.get()) {
            LOGGER.warning("[Replication] Primary not ready, cannot broadcast " + type);
            return;
        }

        ReplicationData replicationPayload = new ReplicationData(data, timestamp);
        Message replicationMsg = new Message(type, replicationPayload);

        LOGGER.fine("[Replication] Broadcasting " + type + " @ " + timestamp);
        peers.values().stream()
                .filter(peer -> peer.isUp.get()) // Only send to peers marked as UP
                .forEach(peer -> sendPeerMessage(peer, replicationMsg)); // Use helper to send
    }

    /** Sends a generic message to a specific peer server. */
    private void sendPeerMessage(PeerInfo peer, Message message) {
        // Submit sending logic to the peer executor to avoid blocking caller
        peerExecutorService.submit(() -> {
            if (!running) return; // Don't send if server is shutting down
            // Use try-with-resources for socket and output stream
            try (Socket peerSocket = new Socket()) {
                // Connect with a reasonable timeout
                peerSocket.connect(new InetSocketAddress(peer.host, peer.port), (int) HEARTBEAT_INTERVAL_MS); // Increased timeout slightly for non-HB
                // Set a timeout for potential reads (though mostly writes here)
                peerSocket.setSoTimeout((int) HEARTBEAT_INTERVAL_MS * 2); // Longer timeout for potentially larger messages

                try (ObjectOutputStream peerOut = new ObjectOutputStream(peerSocket.getOutputStream())) {
                    peerOut.writeObject(message);
                    peerOut.flush(); // Ensure message is sent
                    LOGGER.finest("[Peers] Sent " + message.getType() + " to " + peer.serverId);
                    // Add small delay before closing, similar to heartbeat
                    try { Thread.sleep(HEARTBEAT_CLOSE_DELAY_MS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
            } catch (IOException e) {
                // Log failure to send message
                LOGGER.warning("[Peers] Failed to send " + message.getType() + " to " + peer.serverId + ": " + e.getMessage());
                // Let the heartbeat mechanism handle marking the peer down if connection fails consistently
            } catch (Exception e) {
                // Catch any other unexpected errors
                LOGGER.log(Level.SEVERE, "[Peers] Unexpected error sending " + message.getType() + " to " + peer.serverId, e);
            }
        });
    }

    /** Handles incoming replication messages received from the Primary. */
    public synchronized void handleReplicationMessage(MessageType type, ReplicationData payload) {
        // Backups should process replication, Primary should ignore them in this model
        if (isSelfPrimary.get()) {
            LOGGER.warning("[Replication] Primary received unexpected replication message: " + type + ". Ignoring.");
            return;
        }
        // If state transfer isn't complete, queueing would be needed in a robust system.
        // For simplicity, we just ignore if not ready.
        if (!stateTransferComplete.get()) {
            LOGGER.warning("[Replication] Backup not ready, ignoring replication: " + type + " @ " + payload.timestamp);
            return;
        }

        // 1. Update local Lamport clock based on the received timestamp
        int newTime = lamportClock.update(payload.timestamp);
        LOGGER.fine("[Replication] Received " + type + " @ " + payload.timestamp + " -> Apply. New Clock: " + newTime);

        // 2. Apply the state change based on the message type
        //    IMPORTANT: For simplicity, we apply immediately. A real system might need
        //    to buffer messages to ensure Lamport's total ordering guarantees if messages
        //    can arrive out of order, which requires more complex logic (queues, acknowledgements).
        try {
            switch (type) {
                case REPLICATE_REGISTER:
                    if (payload.data instanceof Credentials) applyReplicatedRegistration((Credentials) payload.data);
                    else LOGGER.warning("[Replication] Invalid data type for REPLICATE_REGISTER: " + (payload.data == null ? "null" : payload.data.getClass().getName()));
                    break;
                case REPLICATE_VOTE:
                    // Expecting String[] {voterId, candidateId}
                    if (payload.data instanceof String[]) {
                        String[] voteData = (String[]) payload.data;
                        if (voteData.length == 2) applyReplicatedVote(voteData[0], voteData[1]);
                        else LOGGER.warning("[Replication] Invalid array length for REPLICATE_VOTE: " + voteData.length);
                    } else LOGGER.warning("[Replication] Invalid data type for REPLICATE_VOTE: " + (payload.data == null ? "null" : payload.data.getClass().getName()));
                    break;
                case REPLICATE_CANDIDATE:
                    if (payload.data instanceof Candidate) applyReplicatedCandidate((Candidate) payload.data);
                    else LOGGER.warning("[Replication] Invalid data type for REPLICATE_CANDIDATE: " + (payload.data == null ? "null" : payload.data.getClass().getName()));
                    break;
                case REPLICATE_STATE_CHANGE:
                    if (payload.data instanceof ElectionState) applyReplicatedStateChange((ElectionState) payload.data);
                    else LOGGER.warning("[Replication] Invalid data type for REPLICATE_STATE_CHANGE: " + (payload.data == null ? "null" : payload.data.getClass().getName()));
                    break;
                case REPLICATE_RESET:
                    applyReplicatedReset(); // Payload data is null for reset
                    break;
                default:
                    LOGGER.warning("[Replication] Unhandled replication message type: " + type);
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "[Replication] CRITICAL: Error applying replicated state for " + type + ". State may be inconsistent.", e);
            // In a real system, this might trigger a request for full state resynchronization.
            stateTransferComplete.set(false); // Mark state as potentially inconsistent
            peerExecutorService.schedule(this::requestStateFromPrimaryIfNeeded, 100, TimeUnit.MILLISECONDS); // Request resync quickly
        }
    }

    // --- Methods to Apply Replicated State Changes ---
    // These methods modify the server's state based on received replication messages.
    // They MUST be synchronized or use thread-safe operations if modifying shared state.

    private synchronized void applyReplicatedRegistration(Credentials creds) {
        // Use putIfAbsent to avoid overwriting if already exists (makes operation idempotent)
        if (registeredVoters.putIfAbsent(creds.getVoterId(), creds.getPassword()) == null) {
            LOGGER.info("[Replication] Applied registration for: " + creds.getVoterId());
        } else {
            LOGGER.fine("[Replication] Registration already existed for: " + creds.getVoterId() + ". No change.");
        }
    }

    private synchronized void applyReplicatedVote(String voterId, String candidateId) {
        // Perform checks based on local (replicated) state
        if (!registeredVoters.containsKey(voterId)) { LOGGER.warning("[Replication] Ignoring vote for unknown voter: " + voterId); return; }
        if (!candidates.containsKey(candidateId)) { LOGGER.warning("[Replication] Ignoring vote for unknown candidate: " + candidateId); return; }
        // votersWhoVoted uses ConcurrentHashMap.newKeySet(), which is thread-safe for add/contains
        if (votersWhoVoted.contains(voterId)) { LOGGER.fine("[Replication] Voter already voted: " + voterId + ". No change."); return; }

        // Apply the vote if checks pass
        if (votersWhoVoted.add(voterId)) { // add returns true if element was not already present
            // voteCounts is ConcurrentHashMap, compute is atomic per key
            voteCounts.compute(candidateId, (id, count) -> (count == null) ? 1 : count + 1);
            LOGGER.info("[Replication] Applied vote: " + voterId + " -> " + candidateId);
        }
    }

    private synchronized void applyReplicatedCandidate(Candidate candidate) {
        // Use putIfAbsent for idempotency
        if (candidates.putIfAbsent(candidate.getId(), candidate) == null) {
            voteCounts.putIfAbsent(candidate.getId(), 0); // Ensure vote count entry exists
            LOGGER.info("[Replication] Applied added candidate: " + candidate.getName());
        } else {
            LOGGER.fine("[Replication] Candidate already existed: " + candidate.getName() + ". No change.");
        }
    }

    private void applyReplicatedStateChange(ElectionState newState) {
        // electionState is AtomicReference, set is thread-safe
        ElectionState oldState = electionState.getAndSet(newState);
        if (oldState != newState) {
            LOGGER.info("[Replication] Applied state change: " + oldState + " -> " + newState);
            // Client GUI updates are triggered by server responses/broadcasts, not directly here
        } else {
            LOGGER.fine("[Replication] State was already " + newState + ". No change.");
        }
    }

    private synchronized void applyReplicatedReset() {
        // Apply reset logic locally
        voteCounts.clear();
        votersWhoVoted.clear();
        // Need to ensure vote count entries exist for all candidates after clearing
        candidates.keySet().forEach(id -> voteCounts.put(id, 0));
        LOGGER.info("[Replication] Applied vote data reset.");
    }


    // ========================================================================
    // --- State Transfer Logic ---
    // ========================================================================

    /** Schedules a task to request the full state from the primary if needed. */
    private void requestStateFromPrimaryIfNeeded() {
        // Don't request if this node is primary, or if state is already marked complete
        if (isSelfPrimary.get() || stateTransferComplete.get()) {
            return;
        }
        String primaryId = currentPrimaryId.get();
        PeerInfo primaryPeer = (primaryId != null) ? peers.get(primaryId) : null;

        if (primaryPeer != null && primaryPeer.isUp.get()) {
            // Primary is known and seems UP, send the request
            LOGGER.info("[State Transfer] Requesting full state from Primary: " + primaryId);
            // Tick clock before sending request? Generally not needed for read requests.
            Message requestMsg = new Message(MessageType.REQUEST_STATE, this.ownServerId); // Send own ID
            sendPeerMessage(primaryPeer, requestMsg);
        } else {
            // Cannot request state now, schedule a retry
            LOGGER.warning("[State Transfer] Cannot request state: Primary (" + primaryId + ") unknown or down. Retrying in " + STATE_REQUEST_RETRY_MS + "ms.");
            // Check running flag before rescheduling
            if (running) {
                peerExecutorService.schedule(this::requestStateFromPrimaryIfNeeded,
                        STATE_REQUEST_RETRY_MS, TimeUnit.MILLISECONDS);
            }
        }
    }

    /** Handles a state request from a peer (should only be executed by the Primary). */
    public void handleStateRequest(String requestingPeerId) {
        // Only Primary should fulfill state requests
        if (!isSelfPrimary.get()) {
            LOGGER.warning("[State Transfer] Received state request on BACKUP node from " + requestingPeerId + ". Ignoring.");
            return;
        }
        // Primary should also be initialized before sending state
        if (!stateTransferComplete.get()){
            LOGGER.warning("[State Transfer] Primary not ready, cannot fulfill state request from " + requestingPeerId);
            // Optionally send an error message back? No, keep it simple.
            return;
        }

        PeerInfo requestingPeer = peers.get(requestingPeerId);
        if (requestingPeer == null) {
            LOGGER.warning("[State Transfer] Received state request from unknown peer: " + requestingPeerId);
            return;
        }

        LOGGER.info("[State Transfer] Primary preparing full state for peer: " + requestingPeerId);
        FullState currentState;
        int currentTime;
        // Synchronize on 'this' to get a consistent snapshot across all state maps
        synchronized (this) {
            // Tick clock *before* taking the snapshot to timestamp the state itself
            currentTime = lamportClock.tick();
            currentState = new FullState(
                    registeredVoters,
                    candidates,
                    voteCounts,
                    votersWhoVoted, // Pass the Set directly, constructor handles copy
                    electionState.get(),
                    currentTime
            );
        }

        // Create and send the message containing the full state
        Message stateMsg = new Message(MessageType.FULL_STATE_DATA, currentState);
        LOGGER.info("[State Transfer] Sending full state snapshot @ " + currentTime + " to " + requestingPeerId);
        sendPeerMessage(requestingPeer, stateMsg);
    }

    /** Handles receiving the full state from the Primary (should only be executed by Backups). */
    public synchronized void handleFullState(FullState receivedState) {
        // Primary should ignore state updates from others
        if (isSelfPrimary.get()) {
            LOGGER.warning("[State Transfer] Primary received unexpected full state data. Ignoring.");
            return;
        }
        // Avoid applying state multiple times if received redundantly
        if (stateTransferComplete.get()) {
            LOGGER.info("[State Transfer] Received full state data, but already initialized/synced. Ignoring.");
            return;
        }

        LOGGER.info("[State Transfer] Received full state snapshot @ " + receivedState.lamportTime + ". Applying...");

        // 1. Update Local Lamport Clock using the timestamp from the state snapshot
        lamportClock.update(receivedState.lamportTime);

        // 2. Apply the received state (BLUNT REPLACEMENT - Clears existing state first)
        registeredVoters.clear();
        registeredVoters.putAll(receivedState.registeredVoters);

        candidates.clear();
        candidates.putAll(receivedState.candidates);

        voteCounts.clear();
        voteCounts.putAll(receivedState.voteCounts);

        votersWhoVoted.clear();
        votersWhoVoted.addAll(receivedState.votersWhoVoted); // AddAll handles null source gracefully

        electionState.set(receivedState.electionState);

        // 3. Mark state transfer as complete for this node
        stateTransferComplete.set(true);
        LOGGER.info("[State Transfer] State synchronized successfully. Current Clock: " + lamportClock.getTime());
        // Log summary of synced state
        LOGGER.info(String.format("[State Transfer] Synced State: Voters=%d, Candidates=%d, Voted=%d, State=%s",
                registeredVoters.size(), candidates.size(), votersWhoVoted.size(), electionState.get()));
    }


    // ========================================================================
    // --- Core Logic Methods (Modified for Replication) ---
    // ========================================================================

    /** Handles voter registration request. Only Primary performs the action and replicates. */
    public synchronized boolean registerVoter(Credentials creds, ClientHandler handler) {
        String voterId = creds.getVoterId(); String password = creds.getPassword();
        if (voterId == null || voterId.trim().isEmpty() || password == null || password.isEmpty()) {
            handler.sendMessage(new Message(MessageType.REGISTRATION_FAILED, "ID/Password required.")); return false;
        }
        voterId = voterId.trim();

        // --- Primary Check & Readiness Check ---
        if (!this.isSelfPrimary.get()) {
            LOGGER.warning("[Role] Received REGISTER on BACKUP node. Rejecting.");
            handler.sendMessage(new Message(MessageType.ERROR, "Not primary server (" + (currentPrimaryId.get()==null?"unknown":currentPrimaryId.get()) + "), cannot register."));
            return false;
        }
        if (!this.stateTransferComplete.get()){ // Primary must also be ready
            LOGGER.warning("[State] Primary not ready, rejecting REGISTER.");
            handler.sendMessage(new Message(MessageType.ERROR, "Server not ready. Please try again later."));
            return false;
        }
        // --- End Checks ---

        // Increment clock *before* the operation
        int timestamp = lamportClock.tick();

        // Check if voter already exists locally
        if (registeredVoters.containsKey(voterId)) {
            handler.sendMessage(new Message(MessageType.REGISTRATION_FAILED, "Voter ID exists.")); return false;
        }

        // Apply change locally
        registeredVoters.put(voterId, password);
        LOGGER.info("[State] Registered: " + voterId + " @ " + timestamp);

        // Broadcast replication message to backups
        broadcastReplication(MessageType.REPLICATE_REGISTER, creds, timestamp);

        // Respond success to client
        handler.sendMessage(new Message(MessageType.REGISTRATION_SUCCESS, "Registered: " + voterId));
        return true;
    }

    /** Handles voter login. Reads state, no changes, no replication needed. */
    public synchronized boolean loginVoter(Credentials creds, ClientHandler handler) {
        String voterId = creds.getVoterId(); String passwordAttempt = creds.getPassword();
        if (voterId == null || passwordAttempt == null) return false;
        voterId = voterId.trim();

        // --- Readiness Check (Client should not login if server still syncing) ---
        if (!this.stateTransferComplete.get()){
            LOGGER.warning("[State] Server not ready, rejecting LOGIN for " + voterId);
            handler.sendMessage(new Message(MessageType.ERROR, "Server not ready. Please try again later."));
            return false;
        }
        // --- End Check ---

        // Reads are safe on primary or backup (assuming eventual consistency from replication)
        String storedPassword = registeredVoters.get(voterId);
        if (storedPassword == null || !storedPassword.equals(passwordAttempt)) {
            handler.sendMessage(new Message(MessageType.LOGIN_FAILED, "Invalid ID or Password.")); return false;
        }
        // Check local active client map (not replicated state)
        if (activeClients.containsKey(voterId)) {
            // Maybe allow login again on same node? Or reject? Let's reject for now.
            handler.sendMessage(new Message(MessageType.ALREADY_LOGGED_IN, "Already logged in to this server node.")); return false;
        }

        // Update local active client map
        handler.setVoterId(voterId); activeClients.put(voterId, handler);
        LOGGER.info("[State] Login: " + voterId + " from " + handler.getClientAddress());

        // Send success and current election state
        handler.sendMessage(new Message(MessageType.LOGIN_SUCCESS, "Welcome, " + voterId + "!"));
        handler.sendMessage(new Message(getElectionStateMessageType(), "State: " + getElectionState()));
        // Check replicated voted status
        if (votersWhoVoted.contains(voterId)) {
            handler.sendMessage(new Message(MessageType.ALREADY_VOTED, "You have already voted."));
        }
        return true;
    }

    /** Handles client logout. Only affects local active client map. */
    public void handleLogout(String voterId, ClientHandler handler) {
        if (voterId != null) {
            // Remove from local map, log if successful
            if (activeClients.remove(voterId) != null) {
                LOGGER.info("[State] Logout: " + voterId);
            }
        }
    }

    /** Handles vote submission. Only Primary performs the action and replicates. */
    public synchronized void recordVote(String voterId, String candidateId, ClientHandler handler) {
        // --- Primary Check & Readiness Check ---
        if (!this.isSelfPrimary.get()) {
            LOGGER.warning("[Role] Received SUBMIT_VOTE on BACKUP node. Rejecting.");
            handler.sendMessage(new Message(MessageType.ERROR, "Not primary server (" + (currentPrimaryId.get()==null?"unknown":currentPrimaryId.get()) + "), cannot vote."));
            return;
        }
        if (!this.stateTransferComplete.get()){
            LOGGER.warning("[State] Primary not ready, rejecting SUBMIT_VOTE.");
            handler.sendMessage(new Message(MessageType.ERROR, "Server not ready. Please try again later."));
            return;
        }
        // --- End Checks ---

        // --- Basic Validation ---
        if (voterId == null || !activeClients.containsKey(voterId)) { // Check if client is known to this handler
            handler.sendMessage(new Message(MessageType.AUTHENTICATION_REQUIRED, "Not logged in on this connection.")); return;
        }
        if (electionState.get() != ElectionState.RUNNING) {
            handler.sendMessage(new Message(getElectionStateMessageType(), "Voting is not active.")); return;
        }
        if (candidateId == null || !candidates.containsKey(candidateId)) {
            handler.sendMessage(new Message(MessageType.VOTE_REJECTED, "Invalid candidate selected.")); return;
        }
        // --- End Validation ---

        // Increment clock *before* the operation
        int timestamp = lamportClock.tick();

        // --- Idempotency Check ---
        // Check if voter has already voted (using the replicated set)
        if (votersWhoVoted.contains(voterId)) {
            handler.sendMessage(new Message(MessageType.ALREADY_VOTED, "Already voted."));
            LOGGER.fine("[State] Rejected duplicate vote attempt by " + voterId + " @ " + timestamp);
            return;
        }
        // --- End Check ---

        // --- Apply State Change Locally ---
        votersWhoVoted.add(voterId); // Add voter to the voted set
        // Increment vote count for the candidate
        voteCounts.compute(candidateId, (id, count) -> (count == null) ? 1 : count + 1);
        LOGGER.info("[State] Vote Recorded: " + voterId + " -> " + candidateId + " @ " + timestamp);
        // --- End Local Apply ---

        // --- Replicate the Vote ---
        String[] voteData = {voterId, candidateId}; // Data payload for replication
        broadcastReplication(MessageType.REPLICATE_VOTE, voteData, timestamp);
        // --- End Replication ---

        // --- Respond Success to Client ---
        handler.sendMessage(new Message(MessageType.VOTE_ACCEPTED, "Vote for " + candidates.get(candidateId).getName() + " accepted."));
    }

    // --- Read Operations (No state change, no replication needed) ---

    /** Gets the list of candidates. Safe to call on Primary or Backup. */
    public List<Candidate> getCandidates() {
        // Return a copy to avoid external modification
        return new ArrayList<>(candidates.values());
    }

    /** Gets the current vote results. Safe to call on Primary or Backup. */
    public synchronized List<VoteResult> getResults() {
        List<VoteResult> results = new ArrayList<>();
        // Iterate over known candidates and get their counts
        // Ensure thread safety if candidates map could change during iteration?
        // Copying keyset first is safer.
        Set<String> currentCandidateIds = new HashSet<>(candidates.keySet());
        for(String id : currentCandidateIds) {
            Candidate candidate = candidates.get(id);
            if (candidate != null) { // Check if candidate still exists
                results.add(new VoteResult(id, candidate.getName(), voteCounts.getOrDefault(id, 0)));
            }
        }
        // Sort results by vote count descending
        results.sort((r1, r2) -> Integer.compare(r2.getVoteCount(), r1.getVoteCount()));
        LOGGER.fine("[Read] Providing local result snapshot.");
        return results;
    }

    /** Broadcasts a message to all currently connected clients on this node. */
    public void broadcastMessage(Message message, boolean includeUnauthenticated) {
        // includeUnauthenticated flag is currently ignored, always sends to all
        LOGGER.fine("[Broadcast] Sending '" + message.getType() + "' to " + activeClients.size() + " clients on node " + ownServerId);
        // Copy values to avoid ConcurrentModificationException if client disconnects during iteration
        new ArrayList<>(activeClients.values()).forEach(handler -> handler.sendMessage(message));
    }

    // --- Admin Operations (Modified for Replication) ---

    /** Sets the election state. Only Primary performs the action and replicates. */
    public synchronized void setElectionState(ElectionState newState) {
        // --- Primary Check & Readiness Check ---
        if (!this.isSelfPrimary.get()) {
            LOGGER.warning("[Role] Received SET_STATE on BACKUP node. Ignoring.");
            System.err.println("Error: Cannot change state on a backup node. Try primary: " + (currentPrimaryId.get()==null?"unknown":currentPrimaryId.get()));
            return;
        }
        if (!this.stateTransferComplete.get()){
            LOGGER.warning("[State] Primary not ready, rejecting SET_STATE.");
            System.err.println("Error: Server not ready. Please try again later.");
            return;
        }
        // --- End Checks ---

        // Tick clock before operation
        int timestamp = lamportClock.tick();
        // Atomically set the new state
        ElectionState oldState = electionState.getAndSet(newState);

        // Only proceed if state actually changed
        if (oldState != newState) {
            LOGGER.warning("!!! State changed by Admin: " + oldState + " -> " + newState + " @ " + timestamp + " !!!");

            // Replicate the state change to backups
            broadcastReplication(MessageType.REPLICATE_STATE_CHANGE, newState, timestamp);

            // If moving *out* of FINISHED state, reset vote data locally and trigger replication of reset
            if (oldState == ElectionState.FINISHED && newState != ElectionState.FINISHED) {
                // Pass timestamp to internal reset method
                // Tick clock again for the reset operation itself? Let's say reset inherits the state change timestamp for simplicity.
                resetElectionDataInternal(timestamp); // This method will handle its own replication broadcast
            }

            // Broadcast state change to connected clients (all nodes do this based on replicated state later?)
            // For now, only primary broadcasts admin changes immediately. Backups rely on replication.
            broadcastMessage(new Message(getElectionStateMessageType(newState), "ADMIN: State is now " + newState), true);

            // If election just finished, broadcast final results to clients
            if (newState == ElectionState.FINISHED) {
                broadcastResults(); // Broadcast results to clients connected to this primary
            }
        } else {
            LOGGER.info("[Admin] State is already " + newState + ". No change.");
        }
    }

    /** Internal method to reset vote counts and voted list. Replicates if called by Primary. */
    private synchronized void resetElectionDataInternal(int timestamp) { // Accepts timestamp
        // Apply locally
        voteCounts.clear();
        votersWhoVoted.clear();
        // Ensure all candidates have a 0 count entry after clearing
        candidates.keySet().forEach(id -> voteCounts.put(id, 0));
        LOGGER.warning("!!! Election data RESET locally @ " + timestamp + " !!!");

        // Only Primary broadcasts the reset replication
        if(isSelfPrimary.get() && stateTransferComplete.get()) { // Also check if ready
            // Use the provided timestamp for the replication message
            broadcastReplication(MessageType.REPLICATE_RESET, null, timestamp); // No data needed for reset message
        }
    }

    /** Admin command to reset vote data. Checks role and triggers internal reset. */
    public synchronized boolean resetElectionDataAdmin(boolean force) {
        // --- Primary Check & Readiness Check ---
        if (!this.isSelfPrimary.get()) {
            LOGGER.warning("[Role] Received RESET_VOTES on BACKUP node. Ignoring.");
            System.err.println("Error: Cannot reset votes on a backup node. Try primary: " + (currentPrimaryId.get()==null?"unknown":currentPrimaryId.get()));
            return false;
        }
        if (!this.stateTransferComplete.get()){
            LOGGER.warning("[State] Primary not ready, rejecting RESET_VOTES.");
            System.err.println("Error: Server not ready. Please try again later.");
            return false;
        }
        // --- End Checks ---

        // Prevent reset while running unless forced
        if (electionState.get() == ElectionState.RUNNING && !force) {
            System.err.println("WARN: Election running. Use 'reset_votes force' to proceed.");
            return false;
        }

        // Tick clock *before* operation that potentially calls internal reset
        int timestamp = lamportClock.tick();
        // Call internal method to perform reset and replication, passing the new timestamp
        resetElectionDataInternal(timestamp);
        // Notify clients connected to this primary
        broadcastMessage(new Message(MessageType.SERVER_MESSAGE, "ADMIN: Election data has been reset."), true);
        return true;
    }

    /** Internal method to add a candidate. Replicates if called by Primary. */
    private synchronized boolean addCandidateInternal(Candidate candidate, int timestamp) { // Accepts timestamp
        // Use putIfAbsent for idempotency
        if (candidates.putIfAbsent(candidate.getId(), candidate) == null) {
            voteCounts.putIfAbsent(candidate.getId(), 0); // Ensure vote count entry exists
            LOGGER.info("[State] Candidate added locally: " + candidate.getName() + " @ " + timestamp);

            // Only Primary broadcasts the candidate addition, if ready
            if(isSelfPrimary.get() && stateTransferComplete.get()) {
                // Use the provided timestamp for the replication message
                broadcastReplication(MessageType.REPLICATE_CANDIDATE, candidate, timestamp);
            }
            return true; // Candidate was added
        }
        return false; // Candidate already existed
    }

    /** Admin command to add a candidate. Checks role and triggers internal add. */
    public synchronized boolean addCandidateAdmin(Candidate candidate) {
        // --- Primary Check & Readiness Check ---
        if (!this.isSelfPrimary.get()) {
            LOGGER.warning("[Role] Received ADD_CANDIDATE on BACKUP node. Ignoring.");
            System.err.println("Error: Cannot add candidate on a backup node. Try primary: " + (currentPrimaryId.get()==null?"unknown":currentPrimaryId.get()));
            return false;
        }
        if (!this.stateTransferComplete.get()){
            LOGGER.warning("[State] Primary not ready, rejecting ADD_CANDIDATE.");
            System.err.println("Error: Server not ready. Please try again later.");
            return false;
        }
        // --- End Checks ---

        // Log if adding while election isn't in NOT_STARTED state
        if (electionState.get() != ElectionState.NOT_STARTED) {
            LOGGER.warning("[Admin] Adding candidate while election state is " + electionState.get() + ". This might require client refresh.");
            // Allow adding for simplicity, though ideally restricted
        }

        // Tick clock before operation
        int timestamp = lamportClock.tick();
        // Call internal method to perform add and replication, passing the new timestamp
        boolean added = addCandidateInternal(candidate, timestamp);

        if(added) LOGGER.info("[Admin] Initiated add candidate: " + candidate.getName());
        else LOGGER.warning("[Admin] Failed to add candidate (already exists?): " + candidate.getName());
        return added;
    }

    // --- Utility Methods & Getters ---

    /** Gets the current election state (thread-safe). */
    public ElectionState getElectionState() { return electionState.get(); }

    /** Gets a read-only view of registered voters (thread-safe). */
    public Map<String, String> getRegisteredVotersMap() { return Collections.unmodifiableMap(registeredVoters); }

    /** Gets a read-only view of active client handlers on this node (thread-safe). */
    public Map<String, ClientHandler> getActiveClientsMap() { return Collections.unmodifiableMap(activeClients); }

    /** Gets a read-only view of voters who have voted (thread-safe). */
    public Set<String> getVotersWhoVotedSet() { return Collections.unmodifiableSet(votersWhoVoted); }

    /** Gets the appropriate MessageType based on the current election state. */
    public MessageType getElectionStateMessageType() { return getElectionStateMessageType(getElectionState()); }

    /** Gets the MessageType corresponding to a given ElectionState. */
    public MessageType getElectionStateMessageType(ElectionState state) {
        if (state == null) return MessageType.ERROR; // Handle null case
        switch (state) {
            case NOT_STARTED: return MessageType.ELECTION_NOT_STARTED;
            case RUNNING:     return MessageType.ELECTION_RUNNING;
            case FINISHED:    return MessageType.ELECTION_FINISHED;
            default:          return MessageType.ERROR; // Should not happen
        }
    }

    /** Sets up basic console logging format. */
    private void setupBasicLogger() {
        Logger rootLogger = Logger.getLogger("");
        // Remove existing handlers to avoid duplicates if called multiple times
        for (Handler handler : rootLogger.getHandlers()) {
            rootLogger.removeHandler(handler);
        }
        // Set level for root logger (can be overridden by specific loggers)
        rootLogger.setLevel(Level.OFF); // Log fine/finest for debugging network/replication

        // Configure console handler
        ConsoleHandler handler = new ConsoleHandler();
        handler.setLevel(Level.ALL); // Handler processes all levels passed by logger
        handler.setFormatter(new SimpleFormatter() {
            // Updated format string for clarity and milliseconds
            private static final String format = "[%1$tF %1$tT.%1$tL] [%4$-7s] [%3$s] [%2$s] %5$s%n";

            @Override
            public synchronized String format(LogRecord lr) {
                // Extract source method name if available
                String method = lr.getSourceMethodName() != null ? lr.getSourceMethodName() : "";
                String source = lr.getLoggerName().substring(lr.getLoggerName().lastIndexOf('.')+1); // Get simple logger name

                // Format with date, level, thread, logger name, method, and message
                String message = String.format(format,
                        new Date(lr.getMillis()),
                        source + "." + method, // Combine logger and method
                        Thread.currentThread().getName(),
                        lr.getLevel().getLocalizedName(),
                        lr.getMessage()
                );

                // Append stack trace if present
                if (lr.getThrown() != null) {
                    StringWriter sw = new StringWriter();
                    PrintWriter pw = new PrintWriter(sw);
                    lr.getThrown().printStackTrace(pw);
                    pw.close();
                    message += sw.toString(); // Append stack trace on new lines
                }
                return message;
            }
        });
//        rootLogger.addHandler(handler);
        rootLogger.setUseParentHandlers(false); // Prevent duplicate logging to parent/root console
    }

    /** Broadcasts the current election results to connected clients. */
    public void broadcastResults() {
        List<VoteResult> results = getResults(); // Get sorted results
        broadcastMessage(new Message(MessageType.RESULTS_DATA, results), true);
        LOGGER.info("[Broadcast] Broadcasted final results to clients.");
    }

    // --- Getters for Distributed State (for Admin Console, etc.) ---
    public String getOwnServerId() { return ownServerId; }
    public String getCurrentPrimaryId() { return currentPrimaryId.get(); }
    public boolean isSelfPrimary() { return isSelfPrimary.get(); }
    public Map<String, PeerInfo> getPeerInfoMap() { return Collections.unmodifiableMap(peers); }
    public int getCurrentLamportTime() { return lamportClock.getTime(); }
    public boolean isStateTransferComplete() { return stateTransferComplete.get(); }


    // --- Main Entry Point ---
    public static void main(String[] args) {
        int port = DEFAULT_PORT;
        String myId = null;
        List<String> peerAddresses = new ArrayList<>();

        // Args format: <port> <serverId> [peer1_host:port] [peer2_host:port] ...
        if (args.length < 2) {
            System.err.println("Usage: java ddvote.server.VotingServer <port> <serverId> [peer1_host:port] ...");
            System.exit(1);
        }

        try {
            port = Integer.parseInt(args[0]);
            myId = args[1]; // e.g., "localhost:10001"
            if (myId == null || myId.trim().isEmpty() || !myId.contains(":")) {
                System.err.println("Invalid serverId format. Should be host:port (e.g., localhost:" + port + ")");
                System.exit(1);
            }
            // Parse peer addresses if provided
            if (args.length > 2) {
                peerAddresses.addAll(Arrays.asList(args).subList(2, args.length));
            }
        } catch (NumberFormatException e) {
            System.err.println("Invalid port number: " + args[0]); System.exit(1);
        } catch (Exception e) {
            System.err.println("Error parsing arguments: " + e.getMessage()); System.exit(1);
        }

        // Create and start the server instance
        VotingServer server = new VotingServer(port, myId, peerAddresses);
        // Add shutdown hook for graceful termination on Ctrl+C
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.shutdownServer(true), "ServerShutdownHook"));
        // Start the server's main loop
        server.startServer();
    }
}
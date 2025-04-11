package ddvote.server;

import ddvote.shared.*;
import java.io.*;
import java.util.Comparator; // Keep this if used (sorting)
import java.util.Date; // Needed for formatTimeAgo
import java.util.List;
import java.util.Map;
import java.util.Set; // Keep this
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Provides a command-line interface for administering the VotingServer.
 */
public class ServerAdminConsole implements Runnable {
    private static final Logger LOGGER = Logger.getLogger(ServerAdminConsole.class.getName());
    private final VotingServer server;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final BufferedReader consoleReader = new BufferedReader(new InputStreamReader(System.in));

    // Pre-compile the regex pattern for adding candidates for efficiency
    private static final Pattern ADD_CANDIDATE_PATTERN = Pattern.compile(
            "^add_candidate\\s+(\\S+)\\s+\"([^\"]+)\"(?:\\s+\"([^\"]+)\")?$", Pattern.CASE_INSENSITIVE);

    public ServerAdminConsole(VotingServer server) {
        this.server = server;
    }

    /** Main loop for reading and processing admin commands. */
    @Override
    public void run() {
        LOGGER.info("Admin Console started. Type 'help' for commands.");
        try {
            while (running.get()) {
                System.out.print("Admin> "); // Prompt
                String line = consoleReader.readLine();
                // Handle EOF (e.g., Ctrl+D in Unix) or stop signal
                if (line == null || !running.get()) {
                    running.set(false); // Ensure flag is set if EOF caused exit
                    break;
                }
                line = line.trim();
                if (!line.isEmpty()) {
                    processCommand(line); // Process the entered command
                }
            }
        } catch (IOException e) {
            // Log error only if console was supposed to be running
            if(running.get()) LOGGER.log(Level.SEVERE, "Admin console read error", e);
        } finally {
            LOGGER.info("Admin Console stopped.");
        }
    }

    /** Parses and executes administrative commands. */
    private void processCommand(String line) {
        String[] parts = line.split("\\s+", 2); // Split into command and arguments
        String cmd = parts[0].toLowerCase();
        String args = (parts.length > 1) ? parts[1].trim() : "";
        try {
            switch (cmd) {
                case "help": case "?": printHelp(); break;
                case "status": printStatus(); break;
                case "peers": printPeerStatus(); break;
                case "clock": printClock(); break;
                case "start": server.setElectionState(ElectionState.RUNNING); break; // Response implicit via logs/status
                case "stop": case "finish": server.setElectionState(ElectionState.FINISHED); printResults(); break; // Show results on stop
                case "reset_state": server.setElectionState(ElectionState.NOT_STARTED); break;
                case "reset_votes": if (server.resetElectionDataAdmin(args.equalsIgnoreCase("force"))) System.out.println("-> Vote data reset."); break;
                case "results": printResults(); break;
                case "candidates": printCandidates(); break;
                case "add_candidate": addCandidate(line); break;
                case "list_voters": listRegisteredVoters(); break;
                case "list_active": listActiveClients(); break;
                case "list_voted": listVotersWhoVoted(); break;
                case "broadcast": broadcastMessage(args); break;
                case "shutdown":
                    System.out.println("-> Initiating server shutdown...");
                    running.set(false); // Stop console loop
                    // Trigger shutdown in a new thread to avoid blocking console reader potentially
                    new Thread(() -> server.shutdownServer(false), "AdminShutdownTrigger").start();
                    break;
                case "exit": case "quit":
                    running.set(false); // Signal console loop to stop
                    System.out.println("-> Exiting admin console. Server continues running.");
                    // Attempt to close reader to break potential blocking readLine
                    closeReader();
                    break;
                default: System.out.println("Error: Unknown command '" + cmd + "'. Type 'help'."); break;
            }
        } catch (Exception e) {
            // Catch unexpected errors during command execution
            System.err.println("Error executing '" + cmd + "': " + e.getMessage());
            LOGGER.log(Level.WARNING, "Admin command execution error for: " + line, e);
        }
    }

    /** Prints the list of available admin commands. */
    private void printHelp() {
        System.out.println("DD-Vote Server Commands:");
        System.out.println("  help          - Show this help message");
        System.out.println("  status        - Show current server status, role, and clock");
        System.out.println("  peers         - Show status of known peer servers");
        System.out.println("  clock         - Show the server's current Lamport clock time");
        System.out.println("  start         - Set election state to RUNNING (Primary only)");
        System.out.println("  stop/finish   - Set election state to FINISHED (Primary only)");
        System.out.println("  reset_state   - Set election state to NOT_STARTED (Primary only)");
        System.out.println("  reset_votes [force] - Reset votes and voted list (Primary only)");
        System.out.println("  results       - Display current voting results (local view)");
        System.out.println("  candidates    - List configured candidates (local view)");
        System.out.println("  add_candidate <id> \"<name>\" [\"<desc>\"] - Add candidate (Primary only)");
        System.out.println("  list_voters   - List all registered voter IDs (local view)");
        System.out.println("  list_active   - List currently logged-in clients on this node");
        System.out.println("  list_voted    - List IDs of voters who have voted (local view)");
        System.out.println("  broadcast <msg> - Send a message to clients connected to this node");
        System.out.println("  shutdown      - Stop this server instance");
        System.out.println("  exit/quit     - Exit this admin console (server keeps running)");
    }

    /** Prints the current status of the server node. */
    private void printStatus() {
        System.out.println("--- DD-Vote Status (" + server.getOwnServerId() + ") ---");
        // Indicate if syncing state
        String role = server.isSelfPrimary() ? "PRIMARY" : "BACKUP";
        String syncStatus = server.isStateTransferComplete() ? "" : " (Syncing)";
        System.out.println(" Role: " + role + syncStatus);
        System.out.println(" Current Primary: " + (server.getCurrentPrimaryId() == null ? "Unknown" : server.getCurrentPrimaryId()));
        System.out.println(" Lamport Clock: " + server.getCurrentLamportTime());
        System.out.println(" Election State: " + server.getElectionState());
        System.out.println(" Registered Voters: " + server.getRegisteredVotersMap().size());
        System.out.println(" Active Clients (This Node): " + server.getActiveClientsMap().size());
        System.out.println(" Voters Voted: " + server.getVotersWhoVotedSet().size());
        System.out.println("------------------------------");
    }

    /** Prints the status of known peer servers. */
    private void printPeerStatus() {
        System.out.println("--- Peer Status (" + server.getOwnServerId() + ") ---");
        Map<String, VotingServer.PeerInfo> peers = server.getPeerInfoMap();
        if (peers.isEmpty()) {
            System.out.println(" No peers configured.");
        } else {
            System.out.printf(" %-25s | %-5s | %s\n", "Peer Server ID", "Status", "Last Heartbeat");
            System.out.println("------------------------------------------------------");
            // Sort peers by ID for consistent display
            List<Map.Entry<String, VotingServer.PeerInfo>> sortedPeers = peers.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .collect(Collectors.toList());

            for (Map.Entry<String, VotingServer.PeerInfo> entry : sortedPeers) {
                VotingServer.PeerInfo info = entry.getValue();
                String status = info.isUp.get() ? "UP" : "DOWN";
                long lastSeen = info.lastHeartbeatReceived.get();
                // Format time since last heartbeat received
                String lastSeenStr = (lastSeen == 0) ? "Never" : formatTimeAgo(System.currentTimeMillis() - lastSeen);
                System.out.printf(" %-25s | %-5s | %s\n", info.serverId, status, lastSeenStr);
            }
        }
        System.out.println("------------------------------");
        System.out.println(" Current Primary (This Node's View): " + (server.getCurrentPrimaryId() == null ? "Unknown" : server.getCurrentPrimaryId()));
        System.out.println("------------------------------");
    }

    /** Prints the server's current Lamport clock value. */
    private void printClock() {
        System.out.println("Lamport Clock (" + server.getOwnServerId() + "): " + server.getCurrentLamportTime());
    }

    /** Formats a duration in milliseconds into a human-readable "time ago" string. */
    private String formatTimeAgo(long durationMs) {
        if (durationMs < 0) return "N/A";
        if (durationMs < 1000) return durationMs + " ms ago";
        long seconds = durationMs / 1000;
        if (seconds < 60) return seconds + " s ago";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + " min " + (seconds % 60) + " s ago";
        long hours = minutes / 60;
        // Add more levels (days, etc.) if needed
        return hours + " hr " + (minutes % 60) + " min ago";
    }

    /** Prints the current election results based on this server's local view. */
    private void printResults() {
        System.out.println("--- Results (Local View) ---");
        List<VoteResult> results = server.getResults(); // Get results from server
        if (results == null || results.isEmpty()) {
            System.out.println(" No results available or election not finished.");
        } else {
            results.forEach(r -> System.out.printf(" %-20s : %d\n", r.getCandidateName(), r.getVoteCount()));
        }
        System.out.println("----------------------------");
    }

    /** Prints the list of candidates currently configured on this server. */
    private void printCandidates() {
        System.out.println("--- Candidates (Local View) ---");
        List<Candidate> candidates = server.getCandidates(); // Get candidates from server
        if (candidates == null || candidates.isEmpty()) {
            System.out.println(" No candidates configured.");
        } else {
            candidates.forEach(c -> System.out.printf(" %-5s : %-20s (%s)\n", c.getId(), c.getName(), c.getDescription()));
        }
        System.out.println("-----------------------------");
    }

    /** Handles the 'add_candidate' command, parsing arguments and calling the server method. */
    private void addCandidate(String line) {
        Matcher m = ADD_CANDIDATE_PATTERN.matcher(line);
        if (!m.matches()) { // Check if input matches the expected pattern
            System.out.println("Error: Invalid format. Use: add_candidate <id> \"<Name>\" [\"<Description>\"]");
            return;
        }
        try {
            // Extract groups captured by the regex
            String id = m.group(1);
            String name = m.group(2);
            String desc = (m.group(3) != null) ? m.group(3) : ""; // Description is optional
            // Create Candidate object
            Candidate newCandidate = new Candidate(id, name, desc);
            // Call the server method to add the candidate (handles primary check and replication)
            if (server.addCandidateAdmin(newCandidate)) {
                System.out.println("-> Candidate '" + name + "' add initiated (if primary).");
            } else {
                System.out.println("Error: Failed to add candidate (check logs - ID exists or not primary?).");
            }
        } catch (IllegalArgumentException e) { // Catch errors from Candidate constructor
            System.out.println("Error: Invalid candidate data - " + e.getMessage());
        } catch (Exception e) { // Catch other potential errors
            System.out.println("Error processing add_candidate command: " + e.getMessage());
            LOGGER.log(Level.WARNING, "Error during add_candidate admin command", e);
        }
    }

    /** Prints the list of all registered voter IDs from this server's local view. */
    private void listRegisteredVoters() {
        System.out.println("--- Registered Voters (Local View) ---");
        Map<String, String> voters = server.getRegisteredVotersMap(); // Get map from server
        if (voters.isEmpty()) {
            System.out.println(" No voters registered.");
        } else {
            // Sort IDs for consistent display
            voters.keySet().stream().sorted().forEach(id -> System.out.println(" " + id));
        }
        System.out.println(" Total: " + voters.size());
        System.out.println("------------------------------------");
    }

    /** Prints the list of clients currently connected and logged in to *this* server node. */
    private void listActiveClients() {
        System.out.println("--- Active Clients (Connected to This Node) ---");
        Map<String, ClientHandler> active = server.getActiveClientsMap(); // Get map from server
        if (active.isEmpty()) {
            System.out.println("  No clients currently logged in to this node.");
        } else {
            // Sort by Voter ID for consistent display
            active.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> System.out.printf("  Voter: %-20s @ Address: %s\n", entry.getKey(), entry.getValue().getClientAddress()));
            System.out.println("  Total Active (This Node): " + active.size());
        }
        System.out.println("---------------------------------------------");
    }

    /** Prints the list of voter IDs who have voted, according to this server's local view. */
    private void listVotersWhoVoted() {
        System.out.println("--- Voters Who Voted (Local View) ---");
        Set<String> voted = server.getVotersWhoVotedSet(); // Get set from server
        if (voted.isEmpty()) {
            System.out.println(" No votes recorded yet.");
        } else {
            // Sort IDs for consistent display
            voted.stream().sorted().forEach(id -> System.out.println(" " + id));
        }
        System.out.println(" Total Voted: " + voted.size());
        System.out.println("-----------------------------------");
    }

    /** Handles the 'broadcast' command, sending a message to clients connected to this node. */
    private void broadcastMessage(String msg) {
        if (msg == null || msg.isEmpty()) {
            System.out.println("Error: Cannot broadcast an empty message.");
            return;
        }
        // Call server method to broadcast
        server.broadcastMessage(new Message(MessageType.SERVER_MESSAGE, "[Admin] " + msg), true);
        System.out.println("-> Broadcast sent to clients connected to this node.");
    }

    /** Signals the console thread to stop and attempts to close the reader. */
    public void stop() {
        running.set(false);
        closeReader(); // Attempt to break blocking readLine
    }

    /** Closes the console reader to potentially unblock the reading thread. */
    private void closeReader() {
        try {
            if (consoleReader != null) {
                consoleReader.close();
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Error closing console reader during stop/exit", e);
        }
    }
}
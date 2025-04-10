package ddvote.server;

import ddvote.shared.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern; // <<< FIXED: Added import
import java.util.stream.Collectors;

public class ServerAdminConsole implements Runnable {
    private static final Logger LOGGER = Logger.getLogger(ServerAdminConsole.class.getName());
    private final VotingServer server;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final BufferedReader consoleReader = new BufferedReader(new InputStreamReader(System.in));
    // Pattern definition moved here, outside method, and uses the imported class
    private static final Pattern ADD_CANDIDATE_PATTERN = Pattern.compile(
            "^add_candidate\\s+(\\S+)\\s+\"([^\"]+)\"(?:\\s+\"([^\"]+)\")?$", Pattern.CASE_INSENSITIVE);

    public ServerAdminConsole(VotingServer server) { this.server = server; }

    @Override
    public void run() {
        LOGGER.info("Admin Console started. Type 'help'.");
        try {
            while (running.get()) {
                System.out.print("Admin> ");
                String line = consoleReader.readLine();
                if (line == null) { running.set(false); break; } // Handle Ctrl+D
                line = line.trim();
                if (!line.isEmpty()) processCommand(line);
            }
        } catch (IOException e) {
            if(running.get()) LOGGER.log(Level.SEVERE, "Admin console read error", e);
        } finally {
            LOGGER.info("Admin Console stopped.");
        }
    }


    private void processCommand(String line) {
        String[] parts = line.split("\\s+", 2);
        String cmd = parts[0].toLowerCase();
        String args = (parts.length > 1) ? parts[1].trim() : "";
        try {
            switch (cmd) {
                case "help": case "?": printHelp(); break;
                case "status": printStatus(); break;
                case "start": server.setElectionState(ElectionState.RUNNING); break; // Simpler output
                case "stop": case "finish": server.setElectionState(ElectionState.FINISHED); printResults(); break;
                case "reset_state": server.setElectionState(ElectionState.NOT_STARTED); break;
                case "reset_votes": if (server.resetElectionDataAdmin(args.equalsIgnoreCase("force"))) System.out.println("-> Votes RESET."); break;
                case "results": printResults(); break;
                case "candidates": printCandidates(); break;
                case "add_candidate": addCandidate(line); break;
                case "list_voters": listRegisteredVoters(); break;
                case "list_active": listActiveClients(); break;
                case "list_voted": listVotersWhoVoted(); break;
                case "broadcast": broadcastMessage(args); break;
                case "peers": printPeerStatus(); break;
                case "shutdown":
                    System.out.println("-> Initiating server shutdown...");
                    running.set(false); // Stop console loop
                    new Thread(() -> server.shutdownServer(false), "AdminShutdownTrigger").start();
                    break;
                case "exit": case "quit": running.set(false); System.out.println("-> Exiting console."); break;
                default: System.out.println("Error: Unknown command '" + cmd + "'. Type 'help'."); break;
            }
        } catch (Exception e) {
            System.err.println("Error executing '" + cmd + "': " + e.getMessage());
            LOGGER.log(Level.WARNING, "Admin command error: " + line, e);
        }
    }

    private void printHelp() {
        System.out.println("DD-Vote Server Commands:");
        System.out.println("  help          - Show this help message");
        System.out.println("  status        - Show current server status and role");
        System.out.println("  peers         - Show status of known peer servers");
        System.out.println("  start         - Set election state to RUNNING (Primary only)");
        System.out.println("  stop/finish   - Set election state to FINISHED (Primary only)");
        System.out.println("  reset_state   - Set election state to NOT_STARTED (Primary only)");
        System.out.println("  reset_votes [force] - Reset votes and voted list (Primary only)");
        System.out.println("  results       - Display current voting results");
        System.out.println("  candidates    - List configured candidates");
        System.out.println("  add_candidate <id> \"<name>\" [\"<desc>\"] - Add candidate (Primary only, before start)");
        System.out.println("  list_voters   - List all registered voter IDs");
        System.out.println("  list_active   - List currently logged-in clients");
        System.out.println("  list_voted    - List IDs of voters who have voted");
        System.out.println("  broadcast <msg> - Send a message to all connected clients");
        System.out.println("  shutdown      - Stop the server instance");
        System.out.println("  exit/quit     - Exit this admin console");
    }

    private void printStatus() {
        System.out.println("--- DD-Vote Status (" + server.getOwnServerId() + ") ---");
        System.out.println(" Role: " + (server.isSelfPrimary() ? "PRIMARY" : "BACKUP"));
        System.out.println(" Current Primary: " + server.getCurrentPrimaryId());
        System.out.println(" State: " + server.getElectionState());
        System.out.println(" Registered: " + server.getRegisteredVotersMap().size());
        System.out.println(" Active Clients: " + server.getActiveClientsMap().size());
        System.out.println(" Voted: " + server.getVotersWhoVotedSet().size());
        System.out.println("------------------------------");
    }

    private void printPeerStatus() {
        System.out.println("--- Peer Status (" + server.getOwnServerId() + ") ---");
        // Access PeerInfo using the getter which returns the map
        Map<String, VotingServer.PeerInfo> peers = server.getPeerInfoMap();
        if (peers.isEmpty()) {
            System.out.println(" No peers configured.");
        } else {
            System.out.printf(" %-25s | %-5s | %s\n", "Peer Server ID", "Status", "Last Heartbeat");
            System.out.println("------------------------------------------------------");
            List<Map.Entry<String, VotingServer.PeerInfo>> sortedPeers = peers.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .collect(Collectors.toList());

            for (Map.Entry<String, VotingServer.PeerInfo> entry : sortedPeers) {
                // Access fields directly via the PeerInfo object (now public)
                VotingServer.PeerInfo info = entry.getValue();
                String status = info.isUp.get() ? "UP" : "DOWN"; // Access public field
                long lastSeen = info.lastHeartbeatReceived.get(); // Access public field
                String lastSeenStr = (lastSeen == 0) ? "Never" : formatTimeAgo(System.currentTimeMillis() - lastSeen);
                System.out.printf(" %-25s | %-5s | %s\n", info.serverId, status, lastSeenStr); // Access public field
            }
        }
        System.out.println("------------------------------");
        System.out.println(" Current Primary (View): " + server.getCurrentPrimaryId());
        System.out.println("------------------------------");
    }

    // Helper to format time difference
    private String formatTimeAgo(long durationMs) {
        if (durationMs < 0) return "N/A";
        if (durationMs < 1000) return durationMs + " ms ago";
        long seconds = durationMs / 1000;
        if (seconds < 60) return seconds + " s ago";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + " min " + (seconds % 60) + " s ago";
        long hours = minutes / 60;
        return hours + " hr " + (minutes % 60) + " min ago"; // Added hours
    }

    private void printResults() {
        System.out.println("--- Results (Local View) ---"); // Clarify it's local view
        List<VoteResult> results = server.getResults();
        if (results.isEmpty()) System.out.println(" No results yet.");
        else results.forEach(r -> System.out.printf(" %-20s : %d\n", r.getCandidateName(), r.getVoteCount()));
        System.out.println("---------------");
    }

    private void printCandidates() {
        System.out.println("--- Candidates ---");
        List<Candidate> candidates = server.getCandidates();
        if (candidates.isEmpty()) System.out.println(" No candidates configured.");
        else candidates.forEach(c -> System.out.printf(" %-5s : %-20s (%s)\n", c.getId(), c.getName(), c.getDescription()));
        System.out.println("------------------");
    }

    private void addCandidate(String line) {
        Matcher m = ADD_CANDIDATE_PATTERN.matcher(line); // Use the static Pattern
        if (!m.matches()) { System.out.println("Error: Use add_candidate <id> \"<name>\" [\"<desc>\"]"); return; }
        try {
            String id = m.group(1); String name = m.group(2); String desc = (m.group(3) != null) ? m.group(3) : "";
            // Create candidate object using shared class constructor
            Candidate newCandidate = new Candidate(id, name, desc);
            if (server.addCandidateAdmin(newCandidate)) System.out.println("-> Candidate added (if primary).");
            else System.out.println("Error: Failed to add (ID exists or not primary?).");
        } catch (IllegalArgumentException e) { System.out.println("Error: Invalid candidate data - " + e.getMessage()); }
        catch (Exception e) { System.out.println("Error processing add_candidate: " + e.getMessage()); }
    }

    private void listRegisteredVoters() {
        System.out.println("--- Registered Voters (Local View) ---");
        Map<String, String> voters = server.getRegisteredVotersMap();
        if (voters.isEmpty()) System.out.println(" No voters registered.");
        else voters.keySet().stream().sorted().forEach(id -> System.out.println(" " + id));
        System.out.println(" Total: " + voters.size());
        System.out.println("-------------------------");
    }

    private void listActiveClients() {
        System.out.println("--- Active Client Connections ---");
        Map<String, ClientHandler> active = server.getActiveClientsMap();
        if (active.isEmpty()) System.out.println("  No clients currently logged in.");
        else {
            active.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey()) // Sort by voterId for consistent output
                    .forEach(entry -> System.out.printf("  Voter: %-20s @ Address: %s\n", entry.getKey(), entry.getValue().getClientAddress()));
            System.out.println("  Total Active: " + active.size());
        }
        System.out.println("---------------------------------");
    }

    private void listVotersWhoVoted() {
        System.out.println("--- Voted List (Local View) ---");
        Set<String> voted = server.getVotersWhoVotedSet();
        if (voted.isEmpty()) System.out.println(" No votes cast yet.");
        else voted.stream().sorted().forEach(id -> System.out.println(" " + id));
        System.out.println(" Total: " + voted.size());
        System.out.println("------------------");
    }

    private void broadcastMessage(String msg) {
        if (msg == null || msg.isEmpty()) { System.out.println("Error: Empty broadcast message."); return; }
        // Use shared Message class
        server.broadcastMessage(new Message(MessageType.SERVER_MESSAGE, "[Admin] " + msg), true);
        System.out.println("-> Broadcast sent.");
    }

    public void stop() {
        running.set(false);
        // Attempt to close the reader to interrupt the blocking readLine call
        try {
            consoleReader.close();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Error closing console reader during stop", e);
        }
    }
}
package ddvote.server;

import java.io.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ServerAdminConsole implements Runnable {
    private static final Logger LOGGER = Logger.getLogger(ddvote.server.ServerAdminConsole.class.getName());
    // CHANGED Type
    private final ddvote.server.VotingServer server;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final BufferedReader consoleReader = new BufferedReader(new InputStreamReader(System.in));
    private static final Pattern ADD_CANDIDATE_PATTERN = Pattern.compile(
            "^add_candidate\\s+(\\S+)\\s+\"([^\"]+)\"(?:\\s+\"([^\"]+)\")?$", Pattern.CASE_INSENSITIVE);

    // Constructor uses updated VotingServer type
    public ServerAdminConsole(ddvote.server.VotingServer server) { this.server = server; }

    @Override
    public void run() {
        LOGGER.info("Admin Console started. Type 'help'.");
        try {
            while (running.get()) {
                System.out.print("Admin> ");
                String line = consoleReader.readLine();
                if (line == null) { running.set(false); break; }
                line = line.trim();
                if (!line.isEmpty()) processCommand(line);
            }
        } catch (IOException e) {
            if(running.get()) LOGGER.log(Level.SEVERE, "Admin console read error", e);
        } finally {
            LOGGER.info("Admin Console stopped.");
        }
    }

    // processCommand uses updated ElectionState type
    private void processCommand(String line) {
        String[] parts = line.split("\\s+", 2);
        String cmd = parts[0].toLowerCase();
        String args = (parts.length > 1) ? parts[1].trim() : "";
        try {
            switch (cmd) {
                case "help": case "?": printHelp(); break;
                case "status": printStatus(); break;
                case "start": server.setElectionState(ddvote.shared.ElectionState.RUNNING); System.out.println("-> State: RUNNING"); break;
                case "stop": case "finish": server.setElectionState(ddvote.shared.ElectionState.FINISHED); System.out.println("-> State: FINISHED"); printResults(); break;
                case "reset_state": server.setElectionState(ddvote.shared.ElectionState.NOT_STARTED); System.out.println("-> State: NOT_STARTED"); break;
                case "reset_votes": if (server.resetElectionDataAdmin(args.equalsIgnoreCase("force"))) System.out.println("-> Votes RESET."); break;
                case "results": printResults(); break;
                case "candidates": printCandidates(); break;
                case "add_candidate": addCandidate(line); break;
                case "list_voters": listRegisteredVoters(); break;
                case "list_active": listActiveClients(); break;
                case "list_voted": listVotersWhoVoted(); break;
                case "broadcast": broadcastMessage(args); break;
                case "shutdown":
                    System.out.println("-> Initiating server shutdown...");
                    running.set(false);
                    new Thread(() -> server.shutdownServer(false), "AdminShutdownTrigger").start();
                    break;
                case "exit": case "quit": running.set(false); System.out.println("-> Exiting console."); break;
                default: System.out.println("Error: Unknown command '" + cmd + "'."); break;
            }
        } catch (Exception e) {
            System.err.println("Error executing '" + cmd + "': " + e.getMessage());
            LOGGER.log(Level.WARNING, "Admin command error: " + line, e);
        }
    }

    private void printHelp() {
        System.out.println("DD-Vote Server Commands:"); // CHANGED Name
        System.out.println("  help, status, start, stop, reset_state, reset_votes [force], results,");
        System.out.println("  candidates, add_candidate <id> \"<name>\" [\"<desc>\"], list_voters, list_active,");
        System.out.println("  list_voted, broadcast <msg>, shutdown, exit");
    }

    private void printStatus() {
        System.out.println("--- DD-Vote Status ---"); // CHANGED Name
        System.out.println(" State: " + server.getElectionState());
        System.out.println(" Registered: " + server.getRegisteredVotersMap().size());
        System.out.println(" Active Clients: " + server.getActiveClientsMap().size());
        System.out.println(" Voted: " + server.getVotersWhoVotedSet().size());
        System.out.println("----------------------");
    }

    // printResults uses updated VoteResult type
    private void printResults() {
        System.out.println("--- Results ---");
        List<ddvote.shared.VoteResult> results = server.getResults();
        if (results.isEmpty()) System.out.println(" No results.");
        else results.forEach(r -> System.out.printf(" %-20s : %d\n", r.getCandidateName(), r.getVoteCount()));
        System.out.println("---------------");
    }

    // printCandidates uses updated Candidate type
    private void printCandidates() {
        System.out.println("--- Candidates ---");
        List<ddvote.shared.Candidate> candidates = server.getCandidates();
        if (candidates.isEmpty()) System.out.println(" No candidates.");
        else candidates.forEach(c -> System.out.printf(" %-5s : %-20s (%s)\n", c.getId(), c.getName(), c.getDescription()));
        System.out.println("------------------");
    }

    // addCandidate uses updated Candidate type
    private void addCandidate(String line) {
        Matcher m = ADD_CANDIDATE_PATTERN.matcher(line);
        if (!m.matches()) { System.out.println("Error: Use add_candidate <id> \"<name>\" [\"<desc>\"]"); return; }
        try {
            String id = m.group(1); String name = m.group(2); String desc = (m.group(3) != null) ? m.group(3) : "";
            if (server.addCandidateAdmin(new ddvote.shared.Candidate(id, name, desc))) System.out.println("-> Candidate added.");
            else System.out.println("Error: Failed to add (ID exists?).");
        } catch (Exception e) { System.out.println("Error: " + e.getMessage()); }
    }

    private void listRegisteredVoters() { /* ... (Same logic) ... */
        System.out.println("--- Registered Voters ---");
        server.getRegisteredVotersMap().keySet().forEach(id -> System.out.println(" " + id));
        System.out.println(" Total: " + server.getRegisteredVotersMap().size());
        System.out.println("-------------------------");
    }

    // listActiveClients uses updated ClientHandler type
    private void listActiveClients() {
        System.out.println("--- Active Client Connections (Concurrent Handlers) ---");
        // CHANGED Map type
        Map<String, ddvote.server.ClientHandler> active = server.getActiveClientsMap();
        if (active.isEmpty()) {
            System.out.println("  No clients currently logged in.");
        } else {
            active.forEach((id, handler) -> System.out.printf("  Voter: %-20s @ Address: %s\n", id, handler.getClientAddress()));
            System.out.println("  Total Active: " + active.size());
        }
        System.out.println("-------------------------------------------------------");
    }

    private void listVotersWhoVoted() { /* ... (Same logic) ... */
        System.out.println("--- Voted List ---");
        server.getVotersWhoVotedSet().forEach(id -> System.out.println(" " + id));
        System.out.println(" Total: " + server.getVotersWhoVotedSet().size());
        System.out.println("------------------");
    }

    // broadcastMessage uses updated Message type
    private void broadcastMessage(String msg) {
        if (msg == null || msg.isEmpty()) { System.out.println("Error: Empty broadcast."); return; }
        server.broadcastMessage(new ddvote.shared.Message(ddvote.shared.MessageType.SERVER_MESSAGE, "[Admin] " + msg), true);
        System.out.println("-> Broadcast sent.");
    }

    public void stop() { running.set(false); }
}
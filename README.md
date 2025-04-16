# Distributed Democracy Vote
## Overview

DD-Vote is a simulated voting system designed to demonstrate fundamental concepts in distributed computing. It features multiple server instances communicating with each other for fault tolerance, and a client application for users to register, log in, and vote.

**Key Distributed Computing Concepts Illustrated:**

*   **Multi-Node Execution:** The system can run across multiple machines (or multiple JVMs on the same machine), forming a cluster.
*   **Primary-Backup Replication:** Implements a simple Primary-Backup data replication model for increased availability. The Primary handles write requests (registering, voting) and replicates the changes to Backup servers.
*   **Heartbeating & Failure Detection:** Servers monitor each other's liveness using periodic heartbeats and timeouts.
*   **Logical Time (Lamport Timestamps):** Uses Lamport clocks to establish a partial ordering of events across the servers.
*   **State Transfer:** New or recovering Backup servers synchronize their state with the Primary.
*   **Eventual Consistency:** The system is eventually consistent, meaning that backups might be temporarily out of sync with the primary.

**Important Limitations:**

*   **Simplified Replication:** The replication mechanism is asynchronous, so there's a potential for data loss if the Primary server fails before propagating updates.
*   **No Strong Consensus:** The system does *not* implement a true consensus algorithm (like Paxos or Raft) to guarantee agreement across all servers before committing a write operation.
*   **Rudimentary Leader Determination:** The Primary is determined simply as the server with the lowest lexicographical ID among the UP nodes. There's no explicit leader election protocol.
*   **No Automatic Client Failover:** If a client's connected server fails, the client does *not* automatically redirect to another server.
*   **Potential Split-Brain:** Without a consensus protocol, network partitions can lead to a "split-brain" scenario where multiple servers incorrectly believe they are the Primary, resulting in data inconsistencies.

## Getting Started

### Prerequisites

*   **Java Development Kit (JDK):** You need a JDK installed (version 8 or later). Ensure that the `javac` and `java` commands are in your system's PATH environment variable. You can download a JDK from [https://www.oracle.com/java/technologies/javase-downloads.html](https://www.oracle.com/java/technologies/javase-downloads.html) or use an open-source distribution like [OpenJDK](https://openjdk.java.net/).

### Building the Application

1.  **Download/Clone the Project:** Download the source code as a ZIP file or clone it from a Git repository to a directory (e.g., `DD-Vote`).

2.  **Open a Terminal/Command Prompt:** Navigate to the root directory of the project (the `DD-Vote` folder containing the `src` folder).

3.  **Compile the Code:** Use the `javac` command to compile all `.java` files and create the output directory.
    ```bash
    mkdir bin  # Create the output directory (if it doesn't exist)
    javac -d bin src/ddvote/shared/*.java src/ddvote/server/*.java src/ddvote/client/*.java
    ```
    *   **Windows Note:** If you're using the Windows Command Prompt and the `/` character causes problems, use backslashes `\` instead.

### Running the Application

1.  **Start the Servers:** You need to open a **separate terminal window** for *each* server instance. Use the following command format:

    ```bash
    java -cp bin ddvote.server.VotingServer <port> <uniqueServerId> [peer1_host:port] [peer2_host:port] ...
    ```

    *   `<port>`: The port number this server will listen on (e.g., `10001`).
    *   `<uniqueServerId>`: A unique identifier for this server (e.g., `localhost:10001`). It's common (and recommended) to use the `host:port` combination as the ID. This ID must be unique among all servers.
    *   `[peer1_host:port] [peer2_host:port] ...`: A space-separated list of the addresses (host:port) of the *other* server instances in the cluster. This allows the servers to discover and communicate with each other for heartbeating and data replication.

    **Example (Three Servers):**

    *   **Terminal 1 (Server A):**
        ```bash
        java -cp bin ddvote.server.VotingServer 10001 localhost:10001 localhost:10002 localhost:10003
        ```

    *   **Terminal 2 (Server B):**
        ```bash
        java -cp bin ddvote.server.VotingServer 10002 localhost:10002 localhost:10001 localhost:10003
        ```

    *   **Terminal 3 (Server C):**
        ```bash
        java -cp bin ddvote.server.VotingServer 10003 localhost:10003 localhost:10001 localhost:10002
        ```

2.  **Start the Client:**
    *   Open yet another terminal window.
    *   Run the client application:
        ```bash
        java -cp bin ddvote.client.VotingClient
        ```
    *   A GUI window should appear.

### Using the Application

1.  **Client Connection:** In the `VotingClient` GUI, enter the `Host` (e.g., `localhost`) and `Port` of one of the running servers (it's best to connect to the *Primary* server to ensure writes are processed) and click "Connect."
2.  **Register/Login:** Use the `Register` and `Login` buttons to create a new voter account or log in to an existing one.
3.  **Vote:** Select a candidate from the list and click the "Submit Vote" button.
4.  **Administrative Commands:** Use the terminal window running the *Primary* server to enter administrative commands:
    *   `help`: Shows a list of available commands.
    *   `status`: Shows the current status of the server, including its role (Primary/Backup), the current Lamport clock, and the election state.
    *   `peers`: Shows the list of known peers and their status (UP/DOWN).
    *   `start`: Starts the election.
    *   `stop` or `finish`: Stops the election and displays the final results.
    *   `reset_votes`: Resets the vote count.

### Demonstration Scenarios

Here are some scenarios you can use to demonstrate the distributed computing features of the application:

*   **Normal Voting:** Start all servers, connect the client to the Primary, register, log in, vote, and verify the vote is counted on the Primary.
*   **Replication:** Register a user on the Primary server, then check the `list_voters` command on one of the Backup servers to confirm that the voter has been replicated.
*   **Simulate Primary Failure:** During voting, close the terminal running the Primary server. Observe that the remaining servers detect the failure and a new Primary is established. Client action is temporarily disrupted until the new primary processes.
*   **State Transfer (New Server):** Run one server, then later add another by starting the second server, observing the process where it takes over and becomes the new primary.
*   **Adding a peer while an election is active:** Add a peer while an election is active to see the automatic new state being transferred.
*   **Adding Candidates:** Add new candidates to the candidate listing in between election periods (using the force or in the not started phase).

### Important Notes

*   **Logging Level:** The servers are configured to log a lot of information for debugging. To reduce clutter, you can change the logging level by editing `src/ddvote/server/VotingServer.java` and modifying the `rootLogger.setLevel()` line in the `setupBasicLogger()` method (e.g., set it to `Level.INFO` or `Level.WARNING`).
*   **Firewall/Network Configuration:** Ensure that your firewall or network configuration allows the servers to communicate with each other on the specified ports.


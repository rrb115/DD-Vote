package ddvote.client;

import ddvote.shared.*;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.text.DefaultCaret;
import java.awt.*;
import java.awt.event.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ClientGUI extends JFrame {
    private static final String DEFAULT_HOST = "localhost";
    private static final String DEFAULT_PORT = "12345";

    private ddvote.client.VotingClient client;

    // GUI Components
    private JTextField hostField, portField, voterIdField;
    private JPasswordField passwordField;
    private JButton connectButton, disconnectButton, registerButton, loginButton, voteButton, refreshCandidatesButton, refreshResultsButton, pingButton;
    private JLabel connectionStatusLabel, loginStatusLabel, electionStatusLabel, votedStatusLabel;
    private JPanel candidatesPanel;
    private ButtonGroup candidateGroup;
    private JTextArea resultsArea, logArea;
    private Map<String, ddvote.shared.Candidate> candidateMap = new HashMap<>();

    private boolean hasVoted = false;
    private ddvote.shared.ElectionState currentElectionState = null;

    public ClientGUI() {
        super("DD-Vote Client");
        initializeGUI();
    }

    public void setClient(ddvote.client.VotingClient client) { this.client = client; }

    private void initializeGUI() {
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setPreferredSize(new Dimension(800, 700));
        setLayout(new BorderLayout(5, 5));
        getRootPane().setBorder(new EmptyBorder(5, 5, 5, 5));
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { handleWindowClose(); }
        });
        JPanel topPanel = new JPanel(); topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.X_AXIS));
        topPanel.add(createConnectionPanel()); topPanel.add(Box.createHorizontalStrut(5)); topPanel.add(createAuthenticationPanel());
        JPanel centerPanel = new JPanel(new GridLayout(1, 2, 5, 0));
        centerPanel.add(createVotingPanel()); centerPanel.add(createResultsPanel());
        add(topPanel, BorderLayout.NORTH); add(centerPanel, BorderLayout.CENTER); add(createLogPanel(), BorderLayout.SOUTH);
        pack(); setLocationRelativeTo(null); updateGUIStateForConnection(false);
    }

    private void handleWindowClose() {
        if (client != null && client.isConnected()) {
            if (JOptionPane.showConfirmDialog(this, "Disconnect and close?", "Confirm Exit", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                client.disconnect(true); dispose(); System.exit(0);
            }
        } else { dispose(); System.exit(0); }
    }

    // --- Panel Creation (No changes needed here) ---
    private JPanel createConnectionPanel() {
        JPanel p = new JPanel(new GridBagLayout()); p.setBorder(BorderFactory.createTitledBorder("Connection"));
        GridBagConstraints gbc = new GridBagConstraints(); gbc.insets = new Insets(2,3,2,3); gbc.anchor=GridBagConstraints.WEST;
        gbc.gridx=0; gbc.gridy=0; p.add(new JLabel("Host:"), gbc); gbc.gridx=1; gbc.gridy=0; gbc.fill=GridBagConstraints.HORIZONTAL; gbc.weightx=1.0; p.add(hostField = new JTextField(DEFAULT_HOST, 12), gbc);
        gbc.gridx=0; gbc.gridy=1; gbc.fill=GridBagConstraints.NONE; gbc.weightx=0.0; p.add(new JLabel("Port:"), gbc); gbc.gridx=1; gbc.gridy=1; gbc.fill=GridBagConstraints.HORIZONTAL; gbc.weightx=1.0; p.add(portField = new JTextField(DEFAULT_PORT, 5), gbc);
        JPanel bp = new JPanel(new FlowLayout(FlowLayout.LEFT,0,0)); bp.add(connectButton = new JButton("Connect")); bp.add(disconnectButton = new JButton("Disconnect")); bp.add(pingButton = new JButton("Ping"));
        gbc.gridx=1; gbc.gridy=2; gbc.anchor=GridBagConstraints.WEST; p.add(bp, gbc);
        gbc.gridx=0; gbc.gridy=3; gbc.gridwidth=2; gbc.fill=GridBagConstraints.HORIZONTAL; gbc.insets = new Insets(5,3,2,3); p.add(connectionStatusLabel = new JLabel("Status: Disconnected"), gbc);
        connectButton.addActionListener(this::handleConnectAction); disconnectButton.addActionListener(e -> { if(client!=null) client.disconnect(true); }); pingButton.addActionListener(e -> { if(client != null && client.isConnected()) client.pingServer(); }); pingButton.setToolTipText("Check connection to server");
        return p;
    }
    private JPanel createAuthenticationPanel() {
        JPanel p = new JPanel(new GridBagLayout()); p.setBorder(BorderFactory.createTitledBorder("Authentication"));
        GridBagConstraints gbc = new GridBagConstraints(); gbc.insets = new Insets(2,3,2,3); gbc.anchor=GridBagConstraints.WEST;
        gbc.gridx=0; gbc.gridy=0; p.add(new JLabel("Voter ID:"), gbc); gbc.gridx=1; gbc.gridy=0; gbc.gridwidth=2; gbc.fill=GridBagConstraints.HORIZONTAL; gbc.weightx=1.0; p.add(voterIdField = new JTextField(12), gbc);
        gbc.gridx=0; gbc.gridy=1; gbc.gridwidth=1; gbc.fill=GridBagConstraints.NONE; gbc.weightx=0.0; p.add(new JLabel("Password:"), gbc); gbc.gridx=1; gbc.gridy=1; gbc.gridwidth=2; gbc.fill=GridBagConstraints.HORIZONTAL; gbc.weightx=1.0; p.add(passwordField = new JPasswordField(12), gbc);
        JPanel bp = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0)); bp.add(registerButton = new JButton("Register")); bp.add(loginButton = new JButton("Login"));
        gbc.gridx=1; gbc.gridy=2; gbc.gridwidth=2; gbc.anchor=GridBagConstraints.EAST; p.add(bp, gbc);
        gbc.gridx=0; gbc.gridy=3; gbc.gridwidth=3; gbc.fill=GridBagConstraints.HORIZONTAL; gbc.anchor=GridBagConstraints.WEST; gbc.insets = new Insets(5,3,2,3); p.add(loginStatusLabel = new JLabel("Status: Not logged in"), gbc);
        registerButton.addActionListener(this::handleRegisterAction); loginButton.addActionListener(this::handleLoginAction);
        return p;
    }
    private JPanel createVotingPanel() {
        JPanel p = new JPanel(new BorderLayout(5, 5)); p.setBorder(BorderFactory.createTitledBorder("Cast Vote"));
        JPanel topStatus = new JPanel(new BorderLayout()); topStatus.add(electionStatusLabel = new JLabel("Election: Unknown"), BorderLayout.WEST); topStatus.add(votedStatusLabel = new JLabel(""), BorderLayout.EAST); p.add(topStatus, BorderLayout.NORTH);
        candidatesPanel = new JPanel(); candidatesPanel.setLayout(new BoxLayout(candidatesPanel, BoxLayout.Y_AXIS)); candidatesPanel.add(new JLabel(" (Candidates appear here)")); candidateGroup = new ButtonGroup();
        JScrollPane scrollPane = new JScrollPane(candidatesPanel); scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED); p.add(scrollPane, BorderLayout.CENTER);
        JPanel bp = new JPanel(new FlowLayout(FlowLayout.CENTER)); bp.add(refreshCandidatesButton = new JButton("Refresh Candidates")); bp.add(voteButton = new JButton("Submit Vote")); p.add(bp, BorderLayout.SOUTH);
        refreshCandidatesButton.addActionListener(e -> { if (client != null) client.requestCandidates(); }); voteButton.addActionListener(this::handleVoteAction);
        return p;
    }
    private JPanel createResultsPanel() {
        JPanel p = new JPanel(new BorderLayout(5, 5)); p.setBorder(BorderFactory.createTitledBorder("Results"));
        resultsArea = new JTextArea(10, 25); resultsArea.setEditable(false); resultsArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12)); resultsArea.setText(" (Results appear here)");
        p.add(new JScrollPane(resultsArea), BorderLayout.CENTER);
        JPanel bp = new JPanel(new FlowLayout(FlowLayout.CENTER)); bp.add(refreshResultsButton = new JButton("Refresh Results")); p.add(bp, BorderLayout.SOUTH);
        refreshResultsButton.addActionListener(e -> { if (client != null) client.requestResults(); });
        return p;
    }
    private JPanel createLogPanel() {
        JPanel p = new JPanel(new BorderLayout()); p.setBorder(BorderFactory.createTitledBorder("Log"));
        logArea = new JTextArea(6, 50); logArea.setEditable(false); logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11)); logArea.setLineWrap(true); logArea.setWrapStyleWord(true);
        ((DefaultCaret)logArea.getCaret()).setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE);
        p.add(new JScrollPane(logArea), BorderLayout.CENTER); p.setPreferredSize(new Dimension(p.getPreferredSize().width, 120));
        return p;
    }

    // --- Action Handlers (No changes needed here) ---
    private void handleConnectAction(ActionEvent e) {
        if (client == null) return; String host = hostField.getText().trim(); String portStr = portField.getText().trim();
        if (host.isEmpty() || portStr.isEmpty()) { showErrorDialog("Input Error", "Host/Port required."); return; }
        try { int port = Integer.parseInt(portStr); if (port <= 0 || port > 65535) throw new NumberFormatException("Invalid port range");
            setConnectButtonEnabled(false); client.connect(host, port);
        } catch (NumberFormatException ex) { showErrorDialog("Invalid Port", "Port must be a number (1-65535)."); setConnectButtonEnabled(true); }
    }
    private void handleRegisterAction(ActionEvent e) {
        if (client == null) return; String id = voterIdField.getText().trim(); String pass = new String(passwordField.getPassword());
        if (id.isEmpty() || pass.isEmpty()) { showErrorDialog("Input Error", "ID/Password required."); return; } client.requestRegister(id, pass);
    }
    private void handleLoginAction(ActionEvent e) {
        if (client == null) return; String id = voterIdField.getText().trim(); String pass = new String(passwordField.getPassword());
        if (id.isEmpty() || pass.isEmpty()) { showErrorDialog("Input Error", "ID/Password required."); return; } client.requestLogin(id, pass);
    }
    private void handleVoteAction(ActionEvent e) {
        if (client == null) return; ButtonModel selected = candidateGroup.getSelection();
        if (selected == null) { showWarningDialog("Vote Error", "Please select a candidate."); return; }
        setVoteButtonEnabled(false); client.submitVote(selected.getActionCommand());
    }

    // --- GUI Update Methods ---

    /** Updates component enabled states based on connection */
    public void updateGUIStateForConnection(boolean connected) {
        hostField.setEnabled(!connected); portField.setEnabled(!connected);
        // connectButton state managed by its action/worker
        disconnectButton.setEnabled(connected);
        pingButton.setEnabled(connected);

        // Determine if auth components should be enabled based ONLY on connection state here
        boolean authPossible = connected; // Simplified: enable if connected, loginStatus handles specifics
        voterIdField.setEnabled(authPossible);
        passwordField.setEnabled(authPossible);
        registerButton.setEnabled(authPossible);
        loginButton.setEnabled(authPossible);

        refreshCandidatesButton.setEnabled(connected);
        refreshResultsButton.setEnabled(connected);

        if (!connected) {
            // **** FIX: Only call updateLoginStatus when disconnected ****
            updateLoginStatus(false, null); // Reset login state display
            updateElectionStatus(null);
            clearCandidates(); clearResults();
            updateVotingStatus(false);
        }
        // Always update the vote button state as it depends on multiple factors
        updateVoteButtonState();
    }

    public void setConnectButtonEnabled(boolean enabled) {
        if (!SwingUtilities.isEventDispatchThread()) { SwingUtilities.invokeLater(() -> connectButton.setEnabled(enabled)); }
        else { connectButton.setEnabled(enabled); }
    }
    public void setVoteButtonEnabled(boolean enabled) {
        if (!SwingUtilities.isEventDispatchThread()) { SwingUtilities.invokeLater(() -> voteButton.setEnabled(enabled)); }
        else { voteButton.setEnabled(enabled); }
    }

    public void updateConnectionStatus(boolean connected, String statusMsg) {
        connectionStatusLabel.setText("Status: " + (connected ? "Connected" : "Disconnected" + (statusMsg != null ? " ("+statusMsg+")" : "")));
        connectionStatusLabel.setForeground(connected ? Color.GREEN.darker() : Color.RED);
        updateGUIStateForConnection(connected); // Update enable states based on new connection status
    }

    /** Updates login status label and enables/disables ONLY login-specific components */
    public void updateLoginStatus(boolean loggedIn, String voterId) {
        loginStatusLabel.setText("Status: " + (loggedIn ? "Logged in as " + voterId : "Not logged in"));
        loginStatusLabel.setForeground(loggedIn ? Color.BLUE.darker() : Color.DARK_GRAY);

        // **** FIX: Manage only login-related components here ****
        // Enable auth fields/buttons ONLY if connected AND not logged in
        boolean enableAuthFields = (client != null && client.isConnected() && !loggedIn);
        voterIdField.setEnabled(enableAuthFields);
        passwordField.setEnabled(enableAuthFields);
        registerButton.setEnabled(enableAuthFields);
        loginButton.setEnabled(enableAuthFields);
        // **** END FIX ****

        if (!loggedIn) {
            clearCandidates(); // Clear candidates on logout
            updateVotingStatus(false); // Reset voted status
        }
        // Always update vote button state as it depends on login status
        updateVoteButtonState();
    }

    // displayCandidates uses updated Candidate type
    public void displayCandidates(List<ddvote.shared.Candidate> candidates) { /* ... (Same as before) ... */
        candidatesPanel.removeAll(); candidateGroup = new ButtonGroup(); candidateMap.clear();
        if (candidates == null || candidates.isEmpty()) { candidatesPanel.add(new JLabel(" No candidates available.")); }
        else {
            for (ddvote.shared.Candidate c : candidates) {
                String text = "<html><b>" + c.getName() + "</b>" + (c.getDescription().isEmpty() ? "" : "<br>  <i>"+c.getDescription()+"</i>") + "</html>";
                JRadioButton rb = new JRadioButton(text); rb.setActionCommand(c.getId());
                candidateGroup.add(rb); candidatesPanel.add(rb); candidateMap.put(c.getId(), c);
            }
        }
        candidatesPanel.revalidate(); candidatesPanel.repaint(); updateVoteButtonState();
    }
    public void clearCandidates() { /* ... (Same as before) ... */
        candidatesPanel.removeAll(); candidatesPanel.add(new JLabel(" (Candidates appear here)"));
        candidateGroup = new ButtonGroup(); candidateMap.clear(); candidatesPanel.revalidate(); candidatesPanel.repaint();
    }
    // displayResults uses updated VoteResult type
    public void displayResults(List<ddvote.shared.VoteResult> results) { /* ... (Same as before) ... */
        resultsArea.setText("");
        if (results == null || results.isEmpty()) { resultsArea.append("No results available."); }
        else {
            resultsArea.append("--- Results ---\n");
            results.forEach(r -> resultsArea.append(String.format(" %-20s: %d\n", r.getCandidateName(), r.getVoteCount())));
            resultsArea.append("---------------\n");
            resultsArea.append("State: " + (currentElectionState != null ? currentElectionState : "Unknown") + "\n");
        }
        resultsArea.setCaretPosition(0);
    }
    public void clearResults() { /* ... (Same as before) ... */ resultsArea.setText(" (Results appear here)"); }
    // updateElectionStatus uses updated ElectionState type
    public void updateElectionStatus(ddvote.shared.ElectionState state) { /* ... (Same as before) ... */
        this.currentElectionState = state; String statusText = "Election: Unknown"; Color statusColor = Color.GRAY;
        if (state != null) {
            statusText = "Election: " + state.getDisplayName();
            switch (state) { case RUNNING: statusColor = Color.GREEN.darker().darker(); break; case FINISHED: statusColor = Color.RED; break; case NOT_STARTED: statusColor = Color.BLUE; break; }
        }
        electionStatusLabel.setText(statusText); electionStatusLabel.setForeground(statusColor); updateVoteButtonState();
    }
    public void updateVotingStatus(boolean voted) { /* ... (Same as before) ... */
        this.hasVoted = voted; votedStatusLabel.setText(voted ? "(Voted)" : ""); votedStatusLabel.setForeground(Color.MAGENTA.darker());
        updateVoteButtonState();
    }
    /** Centralized logic for enabling/disabling the vote button */
    private void updateVoteButtonState() { /* ... (Same logic, uses updated ElectionState type) ... */
        boolean enable = client != null && client.isConnected() && client.getLoggedInVoterId() != null
                && !hasVoted && currentElectionState == ddvote.shared.ElectionState.RUNNING && !candidateMap.isEmpty();
        setVoteButtonEnabled(enable);
    }

    // --- Logging & Dialogs (Remain the same) ---
    public void appendToLog(String text) { if (!SwingUtilities.isEventDispatchThread()) { SwingUtilities.invokeLater(() -> appendToLog(text)); return; } logArea.append(text + "\n"); }
    public void showInfoDialog(String title, String msg) { JOptionPane.showMessageDialog(this, msg, title, JOptionPane.INFORMATION_MESSAGE); }
    public void showErrorDialog(String title, String msg) { JOptionPane.showMessageDialog(this, msg, title, JOptionPane.ERROR_MESSAGE); }
    public void showWarningDialog(String title, String msg) { JOptionPane.showMessageDialog(this, msg, title, JOptionPane.WARNING_MESSAGE); }
}
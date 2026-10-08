package mt.su.nrm.ui;

import mt.su.nrm.nginx.AccessPolicy;
import mt.su.nrm.nginx.Htpasswd;
import mt.su.nrm.nginx.LayoutDetector;
import mt.su.nrm.nginx.LocationSettings;
import mt.su.nrm.ssh.AuthService;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Sets up who may open a location without writing any nginx: pick who is allowed (anyone, people with
 * a password, people on certain networks, or a combination), then for passwords name the login box,
 * choose or create the password file and add users, and for networks list the addresses. Passwords
 * are hashed on this computer; the password file is created on the server when the wizard finishes,
 * and the access rules become part of the (pending) configuration change.
 */
final class AccessWizard {

    /** What to do with the password file. */
    private enum FileMode {
        ADD("Add these users to the file (keep the users already in it)"),
        NEW("Start a new file with these users (replaces any file at this path)"),
        KEEP("Use the file as it is (don't add or change users)");

        private final String label;

        FileMode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** One user row: name and password. */
    private static final class UserRow {
        final TextField user = new TextField();
        final PasswordField password = new PasswordField();
        final Button remove = new Button("Remove");
        final HBox box;

        UserRow() {
            user.setPromptText("username");
            password.setPromptText("password");
            HBox.setHgrow(user, Priority.ALWAYS);
            HBox.setHgrow(password, Priority.ALWAYS);
            box = new HBox(8, user, password, remove);
            box.setAlignment(Pos.CENTER_LEFT);
        }

        boolean isBlank() {
            return user.getText().isBlank() && password.getText().isEmpty();
        }
    }

    private AccessWizard() {
    }

    /**
     * Shows the wizard and, if the user finishes it, writes the password file (when one is needed) and reports
     * the chosen policy.
     *
     * @param owner      the window to show the wizard over
     * @param location   the location whose current access setup is the starting point
     * @param siteName   the site's name, used for the default password file name; may be empty
     * @param access     the server to write the password file to
     * @param onFinished called on the FX thread with the chosen policy once it may be applied to the
     *                   location (after the password file, if one was needed, is on the server)
     */
    static void run(Window owner, LocationSettings location, String siteName, ServerAccess access,
                    Consumer<AccessPolicy> onFinished) {
        AccessPolicy current = AccessPolicy.from(location);
        String slug = LayoutDetector.safeFileName(siteName.isBlank() ? "site" : siteName);
        String confDir = access.confDir().replaceAll("/+$", "");

        // ---- page 1: who
        ToggleGroup modeGroup = new ToggleGroup();
        VBox page1 = new VBox(8);
        page1.setPadding(new Insets(6));
        for (AccessPolicy.Mode m : AccessPolicy.Mode.values()) {
            RadioButton b = new RadioButton(m.toString());
            b.setUserData(m);
            b.setToggleGroup(modeGroup);
            b.setSelected(m == current.mode);
            page1.getChildren().addAll(b, WizardParts.hint("   " + m.description()));
        }
        java.util.function.Supplier<AccessPolicy.Mode> mode = () ->
                modeGroup.getSelectedToggle() == null ? AccessPolicy.Mode.EVERYONE
                        : (AccessPolicy.Mode) modeGroup.getSelectedToggle().getUserData();

        // ---- page 2: login
        TextField realm = new TextField(current.mode.usesPassword() ? current.realm : "Restricted area");
        realm.setPromptText("Restricted area");
        ToggleGroup fileGroup = new ToggleGroup();
        VBox fileChoices = new VBox(6);
        FileMode initialFile = current.passwordFile.isBlank() ? FileMode.NEW : FileMode.KEEP;
        for (FileMode f : FileMode.values()) {
            RadioButton b = new RadioButton(f.toString());
            b.setUserData(f);
            b.setToggleGroup(fileGroup);
            b.setSelected(f == (access.connected() ? (current.passwordFile.isBlank() ? FileMode.NEW : FileMode.ADD) : FileMode.KEEP));
            b.setDisable(!access.connected() && f != FileMode.KEEP);
            fileChoices.getChildren().add(b);
        }
        java.util.function.Supplier<FileMode> fileMode = () ->
                fileGroup.getSelectedToggle() == null ? initialFile : (FileMode) fileGroup.getSelectedToggle().getUserData();
        TextField file = new TextField(current.passwordFile.isBlank() ? confDir + "/htpasswd/" + slug : current.passwordFile);
        Label existing = WizardParts.hint("");
        VBox rows = new VBox(6);
        List<UserRow> userRows = new ArrayList<>();
        Runnable addRow = () -> {
            UserRow row = new UserRow();
            userRows.add(row);
            rows.getChildren().add(row.box);
            row.remove.setOnAction(e -> {
                userRows.remove(row);
                rows.getChildren().remove(row.box);
            });
        };
        addRow.run();
        Button addUser = new Button("Add another user");
        addUser.setOnAction(e -> addRow.run());
        Label weak = new Label();
        weak.setWrapText(true);
        weak.setStyle("-fx-text-fill: #8a6d00;");

        Runnable showUsers = () -> {
            boolean editing = fileMode.get() != FileMode.KEEP;
            rows.setDisable(!editing);
            addUser.setDisable(!editing);
            boolean anyWeak = userRows.stream().anyMatch(r -> !r.password.getText().isEmpty() && r.password.getText().length() < 8);
            weak.setText(editing && anyWeak ? "Short passwords are easy to guess. Use at least 8 characters." : "");
        };
        fileGroup.selectedToggleProperty().addListener((obs, o, n) -> showUsers.run());
        Runnable refreshExisting = () -> {
            String path = file.getText().strip();
            if (!access.connected() || !path.startsWith("/")) {
                existing.setText(access.connected() ? "" : "Not connected: you can only point at a password file that already exists.");
                return;
            }
            existing.setText("Checking the file on the server...");
            access.listUsers(owner, path, users -> existing.setText(users.isEmpty()
                    ? "No file at this path yet (or it has no users)."
                    : "Users already in this file: " + String.join(", ", users)));
        };

        GridPane loginForm = WizardParts.form();
        int r = WizardParts.row(loginForm, 0, "Text in the login box", realm);
        r = WizardParts.row(loginForm, r, "Password file", file);
        r = WizardParts.note(loginForm, r, existing);
        r = WizardParts.row(loginForm, r, "What to do", fileChoices);
        r = WizardParts.note(loginForm, r, WizardParts.hint("Users and passwords"));
        VBox page2 = new VBox(8, loginForm, rows, addUser, weak, WizardParts.hint(
                "Passwords are scrambled (hashed) on this computer, so only the hashes reach the server. The file is "
                        + "created on the server when you finish this wizard, readable only by root and nginx."));
        page2.setPadding(new Insets(6));

        // ---- page 3: networks
        TextArea networks = new TextArea(String.join("\n", current.networks));
        networks.setPrefRowCount(6);
        networks.setPromptText("One per line, e.g.\n203.0.113.7\n192.168.0.0/16");
        networks.setStyle("-fx-font-family: 'Consolas', 'Menlo', monospace;");
        Button privateNets = new Button("Add private networks");
        privateNets.setOnAction(e -> {
            Set<String> all = new LinkedHashSet<>(parseNetworks(networks.getText()));
            all.addAll(AccessPolicy.PRIVATE_NETWORKS);
            networks.setText(String.join("\n", all));
        });
        Button localhost = new Button("Add this server itself");
        localhost.setOnAction(e -> {
            Set<String> all = new LinkedHashSet<>(parseNetworks(networks.getText()));
            all.add("127.0.0.1");
            all.add("::1");
            networks.setText(String.join("\n", all));
        });
        VBox page3 = new VBox(8, WizardParts.hint("List the IP addresses or networks that may come in. Everyone else is "
                + "turned away (\"deny all\" is added for you). A network is written like 192.168.0.0/16."),
                networks, new HBox(8, privateNets, localhost),
                WizardParts.hint("Private networks are the ranges used inside offices and homes: 10.x.x.x, 172.16-31.x.x "
                        + "and 192.168.x.x. If nginx sits behind a proxy or load balancer, the visitor's address may be "
                        + "the proxy's, so check before relying on this."));
        page3.setPadding(new Insets(6));

        // ---- policy and validation
        java.util.function.Supplier<AccessPolicy> policy = () -> {
            AccessPolicy p = new AccessPolicy();
            p.mode = mode.get();
            p.realm = realm.getText().strip();
            p.passwordFile = file.getText().strip();
            p.networks = parseNetworks(networks.getText());
            return p;
        };

        List<WizardDialog.Page> pages = List.of(
                new WizardDialog.Page("Who can open it", "Choose who is allowed to open this location.", page1, List::of),
                new WizardDialog.Page("Login", "Set up the login box and its users.", page2, () -> {
                    List<String> problems = new ArrayList<>();
                    AccessPolicy p = policy.get();
                    p.mode = AccessPolicy.Mode.PASSWORD;
                    p.networks = List.of();
                    problems.addAll(p.problems());
                    if (fileMode.get() != FileMode.KEEP) {
                        problems.addAll(userProblems(userRows, access, confDir, p.passwordFile));
                    }
                    return problems;
                }, () -> {
                    showUsers.run();
                    refreshExisting.run();
                }, () -> mode.get().usesPassword()),
                new WizardDialog.Page("Trusted networks", "Say which addresses may come in.", page3, () -> {
                    AccessPolicy p = policy.get();
                    p.mode = AccessPolicy.Mode.NETWORK;
                    return p.problems();
                }, () -> { }, () -> mode.get().usesNetworks()));

        boolean finished = WizardDialog.show(owner, "Access control", "Apply", pages, () -> policy.get().preview(), () -> {
            StringBuilder sb = new StringBuilder();
            AccessPolicy p = policy.get();
            if (p.mode.usesPassword() && fileMode.get() != FileMode.KEEP) {
                sb.append("When you click Apply, the password file ").append(p.passwordFile).append(" is ")
                        .append(fileMode.get() == FileMode.NEW ? "created" : "updated").append(" on the server with ")
                        .append(filled(userRows).size()).append(" user(s). ");
            }
            sb.append("These settings then become part of the pending configuration change: review and apply it "
                    + "from Pending Changes.");
            return sb.toString();
        });
        if (!finished) {
            return;
        }
        AccessPolicy result = policy.get();
        if (result.mode.usesPassword() && fileMode.get() != FileMode.KEEP) {
            List<String> entries = new ArrayList<>();
            for (UserRow row : filled(userRows)) {
                entries.add(Htpasswd.entry(row.user.getText().strip(), row.password.getText()));
            }
            AuthService.PasswordFileRequest request = new AuthService.PasswordFileRequest(result.passwordFile,
                    fileMode.get() == FileMode.NEW, entries, access.workerUser());
            access.writePasswordFile(owner, request, ok -> {
                if (ok) {
                    onFinished.accept(result);
                }
            });
        } else {
            onFinished.accept(result);
        }
    }

    private static List<UserRow> filled(List<UserRow> rows) {
        List<UserRow> filled = new ArrayList<>();
        for (UserRow row : rows) {
            if (!row.isBlank()) {
                filled.add(row);
            }
        }
        return filled;
    }

    private static List<String> userProblems(List<UserRow> rows, ServerAccess access, String confDir, String path) {
        List<String> problems = new ArrayList<>();
        if (!access.connected()) {
            problems.add("Connect to the server to create a password file, or choose to use an existing one.");
            return problems;
        }
        List<UserRow> filled = filled(rows);
        if (filled.isEmpty()) {
            problems.add("Add at least one user.");
        }
        Set<String> seen = new HashSet<>();
        for (UserRow row : filled) {
            String name = row.user.getText().strip();
            if (!Htpasswd.isValidUser(name)) {
                problems.add("\"" + name + "\" is not a valid username (letters, digits and . _ @ - only).");
            } else if (!seen.add(name)) {
                problems.add("The user \"" + name + "\" is listed twice.");
            }
            if (row.password.getText().isEmpty()) {
                problems.add("Give \"" + name + "\" a password.");
            }
        }
        // Check the path with a stand-in entry, so hashing doesn't run on every keystroke.
        problems.addAll(AuthService.problems(confDir, new AuthService.PasswordFileRequest(path, false,
                List.of("u:$apr1$AAAAAAAA$AAAAAAAAAAAAAAAAAAAAAA"), "")).stream()
                .filter(p -> !p.startsWith("Add at least")).toList());
        return problems;
    }

    /**
     * Splits a list of addresses typed by the user.
     *
     * @param text addresses separated by whitespace, commas or semicolons
     * @return the entries in order, without duplicates or empties
     */
    static List<String> parseNetworks(String text) {
        Set<String> out = new LinkedHashSet<>();
        for (String part : text.split("[\\s,;]+")) {
            if (!part.isBlank()) {
                out.add(part.strip());
            }
        }
        return new ArrayList<>(out);
    }
}

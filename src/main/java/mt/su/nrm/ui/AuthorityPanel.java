package mt.su.nrm.ui;

import mt.su.nrm.model.ServerProfile;
import mt.su.nrm.ssh.CaService;
import mt.su.nrm.ssl.CertificateInfo;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;

/**
 * The Authority tab: the server's private certificate authorities. A server can have any number of
 * them (each in its own folder), for example one per team or environment. A CA's key is created on
 * the server and never leaves it; only the public CA certificate can be downloaded, to install on
 * the machines that should trust it.
 */
final class AuthorityPanel extends CertificateViewBase {

    private final TableView<CertificateInfo> list = new TableView<>();

    AuthorityPanel(ServerProfile profile, ServerConnection connection) {
        super(profile, connection);
        list.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        list.setPlaceholder(new Label("No certificate authorities yet."));
        list.getColumns().add(text("Name", 220, c -> "    ".repeat(depthOf(c, authorities()))
                + (depthOf(c, authorities()) > 0 ? "\u2514 " : "") + c.commonName()));
        list.getColumns().add(text("Type", 100, c -> c.selfSigned() ? "Root" : "Intermediate"));
        list.getColumns().add(text("Signed by", 140, c -> {
            CertificateInfo parent = parentOf(c, authorities());
            return c.selfSigned() ? "itself" : parent != null ? parent.commonName() : c.issuerField("CN");
        }));
        list.getColumns().add(text("Organisation", 150, c -> c.subjectField("O")));
        list.getColumns().add(text("Expires", 95, c -> c.notAfter() == null ? "" : c.notAfter().toString().substring(0, 10)));
        list.getColumns().add(text("Status", 110, CertificateViewBase::statusText));
        list.getColumns().add(text("Key", 90, CertificateInfo::keyInfo));
        list.getColumns().add(text("Issued", 60, c -> String.valueOf(issuedBy(c, connection.certificates() == null
                ? List.of() : connection.certificates()).size())));
        list.getColumns().add(text("Folder", 160, c -> authorityLabel(c).contains("(") ? c.path() : "(main folder)"));
        list.getSelectionModel().selectedItemProperty().addListener((obs, o, n) ->
                details.setText(n == null ? "" : n.details()));
        render();
    }

    private static TableColumn<CertificateInfo, String> text(String title, double width,
                                                            java.util.function.Function<CertificateInfo, String> v) {
        TableColumn<CertificateInfo, String> c = new TableColumn<>(title);
        c.setPrefWidth(width);
        c.setCellValueFactory(cell -> new javafx.beans.property.SimpleStringProperty(v.apply(cell.getValue())));
        return c;
    }

    @Override
    Node content(List<CertificateInfo> all) {
        List<CertificateInfo> cas = authorityTree(authorities());
        String selected = list.getSelectionModel().getSelectedItem() == null ? null
                : list.getSelectionModel().getSelectedItem().path();
        list.setItems(FXCollections.observableArrayList(cas));
        cas.stream().filter(c -> c.path().equals(selected)).findFirst()
                .ifPresentOrElse(c -> list.getSelectionModel().select(c),
                        () -> {
                            if (!cas.isEmpty()) {
                                list.getSelectionModel().select(0);
                            }
                        });

        Label heading = new Label(cas.isEmpty() ? "This server has no certificate authority yet."
                : cas.size() + " certificate " + (cas.size() == 1 ? "authority" : "authorities"));
        heading.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");
        Label explain = new Label("A private CA lets you issue your own certificates for internal names and addresses. You can "
                + "have as many as you need, for example one per team or environment. A CA can also be signed by another one "
                + "(an intermediate CA), so you keep the root safe and issue from the intermediate; clients then trust the root. "
                + "Each is kept in its own folder under "
                + profile.getPaths().getCaStorageDir() + " with its key readable only by root and never downloaded; "
                + "machines trust a CA once you install its (public) certificate.");
        explain.setWrapText(true);
        explain.setOpacity(0.85);

        details.setPrefRowCount(11);
        Button create = new Button("Create CA");
        create.setOnAction(e -> createCa());
        Button download = new Button("Download CA certificate");
        download.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());
        download.setOnAction(e -> downloadCa());
        Button refresh = new Button("Refresh");
        refresh.setOnAction(e -> reload());
        Button delete = new Button("Delete CA");
        delete.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());
        delete.setOnAction(e -> deleteCa());
        Button chain = new Button("Download chain");
        chain.disableProperty().bind(javafx.beans.binding.Bindings.createBooleanBinding(() -> {
            CertificateInfo c = list.getSelectionModel().getSelectedItem();
            return c == null || c.selfSigned();
        }, list.getSelectionModel().selectedItemProperty()));
        chain.setOnAction(e -> downloadChain());
        FlowPane buttons = new FlowPane(8, 8, create, download, chain, delete, refresh);

        VBox box = new VBox(10, heading, explain, list, details, buttons);
        VBox.setVgrow(list, Priority.ALWAYS);
        box.setPadding(new Insets(12));
        return box;
    }

    private void createCa() {
        List<CaDialogs.Signer> signers = new java.util.ArrayList<>();
        for (CertificateInfo c : authorityTree(authorities())) {
            try {
                signers.add(new CaDialogs.Signer(authorityLabel(c), CaService.authority(profile.getPaths(), c.path()),
                        c.daysLeft(CLOCK)));
            } catch (IOException e) {
                // Not a CA this app manages: it can't sign new ones.
            }
        }
        CaDialogs.createCa(window(), authorityFolders(), signers).ifPresent(request -> work("Creating CA",
                "Creating the certificate authority on the server (a 4096-bit key can take a moment)...",
                () -> CaService.createCa(connection.session(), profile.getPaths(), request), result -> {
                    if (result.ok()) {
                        Dialogs.info(window(), "Certificate authority created",
                                "The CA key stays on the server. Download the CA certificate and install it on the clients "
                                        + "that should trust your certificates.");
                        reload();
                    } else {
                        Dialogs.showOutput(window(), Alert.AlertType.ERROR, "The CA was not created", "", result.output());
                    }
                }));
    }

    private void deleteCa() {
        CertificateInfo ca = list.getSelectionModel().getSelectedItem();
        if (ca == null || connection.certificates() == null) {
            return;
        }
        List<CertificateInfo> children = childrenOf(ca, authorities());
        if (!children.isEmpty()) {
            Dialogs.info(window(), "This CA has signed other CAs", "It signed: "
                    + children.stream().map(CertificateInfo::commonName).collect(java.util.stream.Collectors.joining(", "))
                    + ".\n\nDelete those first, since their certificates would no longer be trusted.");
            return;
        }
        boolean intermediate = !ca.selfSigned();
        List<CertificateInfo> issued = issuedBy(ca, connection.certificates());
        java.util.Map<String, Set<String>> used = usedByPath();
        List<String> inUse = new java.util.ArrayList<>();
        for (CertificateInfo c : issued) {
            if (used.containsKey(c.path())) {
                inUse.add(c.commonName() + " (used by " + String.join(", ", used.get(c.path())) + ")");
            }
        }
        if (!inUse.isEmpty()) {
            Dialogs.info(window(), "Certificates from this CA are in use", "These certificates are used by virtual hosts:\n"
                    + String.join("\n", inUse) + "\n\nChange those sites to use other certificates and apply the change, then "
                    + "delete the CA.");
            return;
        }
        List<String> deletable = issued.stream().map(CertificateInfo::path)
                .filter(p -> CaService.isDeletableCertificate(profile.getPaths(), p)).toList();

        Label warning = new Label("This permanently deletes the CA's certificate and private key from the server. Machines "
                + "that trust this CA will no longer be able to get new certificates from it, and its key can't be recovered.");
        warning.setWrapText(true);
        javafx.scene.control.CheckBox alsoCerts = new javafx.scene.control.CheckBox(deletable.isEmpty()
                ? "It has issued no certificates that can be deleted here"
                : "Also delete the " + deletable.size() + " certificate(s) it issued (and their keys)");
        alsoCerts.setSelected(!deletable.isEmpty());
        alsoCerts.setDisable(deletable.isEmpty());
        javafx.scene.control.TextField confirm = new javafx.scene.control.TextField();
        confirm.setPromptText(ca.commonName());
        if (!FormDialog.show(window(), "Delete certificate authority", "Delete CA",
                () -> confirm.getText().strip().equals(ca.commonName()) ? List.of()
                        : List.of("Type the CA's name exactly to confirm: " + ca.commonName()),
                "", warning, "", alsoCerts, "Type the CA's name", confirm)) {
            return;
        }
        boolean withCerts = alsoCerts.isSelected();
        work("Deleting CA", "Deleting the certificate authority on the server...", () -> CaService.deleteCa(
                connection.session(), profile.getPaths(), CaService.authority(profile.getPaths(), ca.path()),
                withCerts ? deletable : List.of(), intermediate), result -> finishDelete(result.ok(), "CA deleted", result.output()));
    }

    private void downloadChain() {
        CertificateInfo ca = list.getSelectionModel().getSelectedItem();
        if (ca == null) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save the certificate chain");
        FileChooser.ExtensionFilter p7b = new FileChooser.ExtensionFilter("Windows chain, PKCS#7 (*.p7b)", "*.p7b");
        FileChooser.ExtensionFilter pemFilter = new FileChooser.ExtensionFilter("PEM bundle for servers and Linux (*.crt)", "*.crt");
        chooser.getExtensionFilters().addAll(p7b, pemFilter);
        chooser.setSelectedExtensionFilter(p7b);
        chooser.setInitialFileName(mt.su.nrm.nginx.LayoutDetector.safeFileName(ca.commonName()) + "-chain");
        File target = chooser.showSaveDialog(window());
        if (target == null) {
            return;
        }
        boolean asP7b = target.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".p7b")
                || (!target.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".crt")
                && chooser.getSelectedExtensionFilter() == p7b);
        File out = asP7b && !target.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".p7b")
                ? new File(target.getPath() + ".p7b") : target;
        work("Downloading", "Fetching the certificate chain (public part only)...", () -> CaService.fetchChain(
                connection.session(), profile.getPaths(), CaService.authority(profile.getPaths(), ca.path())), pem -> {
                    try {
                        if (asP7b) {
                            Files.write(out.toPath(), mt.su.nrm.ssl.CertificateChains.toPkcs7(pem));
                            Dialogs.info(window(), "Saved", "Open " + out.getName() + " in Windows to see every certificate in the "
                                    + "chain, or right-click it and choose Install Certificate to add the root and intermediates "
                                    + "to the right stores. No private key was downloaded.");
                            return;
                        }
                        Files.write(out.toPath(), pem.getBytes(StandardCharsets.UTF_8));
                        Dialogs.info(window(), "Saved", "This PEM file holds " + ca.commonName() + " and the authorities above it, "
                                + "ending with the root. It suits servers and Linux, but Windows shows only the first certificate in "
                                + "such a file; use the .p7b format for Windows. No private key was downloaded.");
                    } catch (IOException e) {
                        Dialogs.error(window(), "Could not save the file", e.getMessage());
                    }
                });
    }

    private void downloadCa() {
        CertificateInfo ca = list.getSelectionModel().getSelectedItem();
        if (ca == null) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save the CA certificate");
        chooser.setInitialFileName(mt.su.nrm.nginx.LayoutDetector.safeFileName(ca.commonName()) + ".crt");
        File target = chooser.showSaveDialog(window());
        if (target == null) {
            return;
        }
        work("Downloading", "Fetching the CA certificate (public part only)...", () -> CaService.fetchCaCertificate(
                connection.session(), profile.getPaths(), CaService.authority(profile.getPaths(), ca.path())), pem -> {
                    try {
                        Files.write(target.toPath(), pem.getBytes(StandardCharsets.UTF_8));
                        Dialogs.info(window(), "Saved", "Install " + target.getName() + " as a trusted root certificate on the "
                                + "machines that should trust certificates from this CA. The CA's private key was not downloaded.");
                    } catch (IOException e) {
                        Dialogs.error(window(), "Could not save the file", e.getMessage());
                    }
                });
    }

    /** Unused folder names, for callers that need to pick one. */
    static String uniqueFolder(String wanted, Set<String> taken) {
        String candidate = wanted;
        for (int i = 2; taken.contains(candidate); i++) {
            candidate = wanted + "-" + i;
        }
        return candidate;
    }
}

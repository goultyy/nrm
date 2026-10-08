package mt.su.nrm.ui;

import mt.su.nrm.ssh.ImportService;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * "Import a certificate you already have": choose the certificate, its private key and optionally the
 * intermediate chain from this computer, give it a name, and it is copied to the server. The dialog
 * reads and checks the files as you choose them and shows what the certificate is for and when it
 * expires, so a wrong file is noticed before anything is sent.
 */
final class CertificateImportDialog {

    private static final long MAX_BYTES = 200_000;

    private CertificateImportDialog() {
    }

    /** A file field with a Browse button. */
    private static final class FileField extends HBox {
        final TextField path = new TextField();

        FileField(Window owner, String prompt, String... extensions) {
            super(6);
            path.setPromptText(prompt);
            HBox.setHgrow(path, Priority.ALWAYS);
            Button browse = new Button("Browse");
            browse.setOnAction(e -> {
                FileChooser chooser = new FileChooser();
                chooser.setTitle(prompt);
                chooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("Certificate files", extensions),
                        new FileChooser.ExtensionFilter("All files", "*.*"));
                File file = chooser.showOpenDialog(owner);
                if (file != null) {
                    path.setText(file.getAbsolutePath());
                }
            });
            getChildren().addAll(path, browse);
        }

        /** The file's text, "" if none is chosen. */
        String read() throws IOException {
            String p = path.getText().strip();
            if (p.isEmpty()) {
                return "";
            }
            File f = new File(p);
            if (!f.isFile()) {
                throw new IOException("Can't find " + p);
            }
            if (f.length() > MAX_BYTES) {
                throw new IOException(f.getName() + " is too large to be a certificate or key.");
            }
            return Files.readString(f.toPath(), StandardCharsets.ISO_8859_1);
        }
    }

    /**
     * Asks for the files, sends them and reports the result.
     *
     * @param onImported called on the FX thread with the result once the certificate is installed
     */
    static void run(Window owner, ServerAccess access, Consumer<ImportService.Result> onImported) {
        if (!access.connected()) {
            Dialogs.error(owner, "Not connected", "Connect to the server to import a certificate.");
            return;
        }
        FileField cert = new FileField(owner, "Certificate (.crt, .pem, .cer)", "*.crt", "*.pem", "*.cer");
        FileField key = new FileField(owner, "Private key (.key, .pem)", "*.key", "*.pem");
        FileField chain = new FileField(owner, "Intermediate chain (optional)", "*.crt", "*.pem", "*.cer");
        TextField name = new TextField();
        name.setPromptText("e.g. example.com");
        CheckBox replace = new CheckBox("Replace an imported certificate with the same name");
        Label summary = new Label();
        summary.setWrapText(true);
        summary.setMaxWidth(460);
        boolean[] nameTouched = {false};
        name.textProperty().addListener((obs, o, n) -> nameTouched[0] = true);
        cert.path.textProperty().addListener((obs, o, n) -> {
            if (!nameTouched[0] || name.getText().isBlank()) {
                String file = new File(n.strip()).getName();
                int dot = file.lastIndexOf('.');
                String base = dot > 0 ? file.substring(0, dot) : file;
                name.setText(base.replaceAll("[^A-Za-z0-9._-]", "_"));
                nameTouched[0] = false;
            }
        });

        // What the dialog read; refreshed by problems() on every change.
        String[] texts = {"", "", ""};
        java.util.function.Supplier<List<String>> problems = () -> {
            List<String> issues = new ArrayList<>();
            try {
                texts[0] = cert.read();
                texts[1] = chain.read();
                texts[2] = key.read();
            } catch (IOException e) {
                issues.add(e.getMessage());
                return issues;
            }
            if (texts[0].isEmpty()) {
                issues.add("Choose the certificate file.");
            }
            if (texts[2].isEmpty()) {
                issues.add("Choose the private key file.");
            }
            summary.setText(describe(texts[0]));
            if (issues.isEmpty()) {
                issues.addAll(ImportService.problems(access.certificateDir(), request(name, texts, replace)));
            }
            return issues;
        };

        Label note = new Label("The certificate and its key are copied to the server over SSH (the key is never shown in "
                + "the command log), checked to belong together, and stored in " + access.certificateDir()
                + " readable by root only. Protect the key file on this computer as well.");
        note.setWrapText(true);
        note.setMaxWidth(460);
        note.setOpacity(0.8);

        if (!FormDialog.show(owner, "Import Certificate", "Import", problems,
                "Certificate", cert, "Private key", key, "Chain", chain, "Name", name, "", replace, "", summary,
                "", note)) {
            return;
        }
        ImportService.ImportRequest request = request(name, texts, replace);
        access.importCertificate(owner, request, result -> {
            if (result != null) {
                onImported.accept(result);
            }
        });
    }

    private static ImportService.ImportRequest request(TextField name, String[] texts, CheckBox replace) {
        return new ImportService.ImportRequest(name.getText().strip(), texts[0], texts[1], texts[2], replace.isSelected());
    }

    /** "example.com, expires 2027-01-01" for the first certificate in the text, or a note if unreadable. */
    static String describe(String pem) {
        if (pem.isBlank()) {
            return "";
        }
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            X509Certificate c = (X509Certificate) factory.generateCertificate(
                    new ByteArrayInputStream(pem.getBytes(StandardCharsets.ISO_8859_1)));
            StringBuilder sb = new StringBuilder("For ").append(c.getSubjectX500Principal().getName());
            try {
                if (c.getSubjectAlternativeNames() != null) {
                    List<String> names = new ArrayList<>();
                    c.getSubjectAlternativeNames().forEach(n -> names.add(String.valueOf(n.get(1))));
                    sb.append(" (").append(String.join(", ", names)).append(")");
                }
            } catch (CertificateException ignored) {
                // Names are a courtesy; the server reads the certificate itself.
            }
            sb.append(". Valid until ").append(c.getNotAfter().toInstant().toString(), 0, 10).append('.');
            return sb.toString();
        } catch (CertificateException | RuntimeException e) {
            return "This doesn't look like a PEM certificate.";
        }
    }
}

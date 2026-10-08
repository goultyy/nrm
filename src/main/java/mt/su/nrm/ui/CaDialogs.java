package mt.su.nrm.ui;

import mt.su.nrm.ssh.CaService;
import mt.su.nrm.ssl.AltNames;
import mt.su.nrm.ssl.SubjectInfo;
import javafx.collections.FXCollections;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** The forms for creating a certificate authority and for issuing a certificate from it. */
final class CaDialogs {

    private CaDialogs() {
    }

    /** The identity fields shared by both forms. */
    private static final class SubjectFields {
        final TextField commonName = new TextField();
        final TextField organisation = new TextField();
        final TextField unit = new TextField();
        final TextField country = new TextField();
        final TextField state = new TextField();
        final TextField locality = new TextField();
        final TextField email = new TextField();

        SubjectFields() {
            organisation.setPromptText("company or team name");
            unit.setPromptText("department (optional)");
            country.setPromptText("2 letters, e.g. AU");
            state.setPromptText("state or province");
            locality.setPromptText("city");
            email.setPromptText("contact address, e.g. pki@example.com");
        }

        SubjectInfo read() {
            return new SubjectInfo(commonName.getText(), organisation.getText(), unit.getText(),
                    country.getText().strip().toUpperCase(java.util.Locale.ROOT), state.getText(), locality.getText(),
                    email.getText());
        }
    }

    /** An authority that could sign a new one: how to show it, where it is, and how long it stays valid. */
    record Signer(String label, CaService.CaRef ref, long daysLeft) {
        @Override
        public String toString() {
            return label;
        }
    }

    /** Asks for a root CA only; kept for callers with no other authorities to offer. */
    static Optional<CaService.CaRequest> createCa(Window owner, java.util.Set<String> takenFolders) {
        return createCa(owner, takenFolders, List.of());
    }

    /**
     * Asks for everything a certificate authority needs; empty if cancelled. The CA is a self-signed root
     * unless one of {@code signers} is chosen, which makes it an intermediate signed by that authority.
     */
    static Optional<CaService.CaRequest> createCa(Window owner, java.util.Set<String> takenFolders, List<Signer> signers) {
        SubjectFields subject = new SubjectFields();
        subject.commonName.setText("Internal CA");
        subject.commonName.setPromptText("the CA's name, shown to people who trust it");
        ComboBox<CaService.KeyType> keyType = new ComboBox<>(FXCollections.observableArrayList(CaService.KeyType.values()));
        keyType.setValue(CaService.KeyType.RSA_4096);
        TextField days = new TextField("3650");
        ComboBox<Signer> signedBy = new ComboBox<>();
        signedBy.getItems().add(null);
        signedBy.getItems().addAll(signers);
        signedBy.setValue(null);
        signedBy.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(Signer signer) {
                return signer == null ? "Nobody: a self-signed root CA" : signer.label();
            }

            @Override
            public Signer fromString(String text) {
                return null;
            }
        });

        boolean ok = FormDialog.show(owner, "Create certificate authority", "Create", () -> {
            List<String> problems = new ArrayList<>();
            Integer d = CertificateViewBase.parseInt(days.getText());
            if (d == null) {
                problems.add("Validity must be a number of days.");
            } else {
                problems.addAll(CaService.problems(request(subject, d, keyType, takenFolders, signedBy.getValue())));
                Signer signer = signedBy.getValue();
                if (signer != null && d > signer.daysLeft()) {
                    problems.add("It can't stay valid longer than " + signer.label() + ", which has " + signer.daysLeft()
                            + " days left.");
                }
            }
            return problems;
        }, "Signed by", signedBy, "Common name", subject.commonName, "Organisation", subject.organisation,
                "Organisational unit", subject.unit, "Country", subject.country, "State / province", subject.state,
                "City", subject.locality, "E-mail", subject.email, "Key type", keyType, "Valid for (days)", days);
        if (!ok) {
            return Optional.empty();
        }
        return Optional.of(request(subject, CertificateViewBase.parseInt(days.getText()), keyType, takenFolders, signedBy.getValue()));
    }

    /** A CA request whose folder is made from the name and made unique among the existing authorities. */
    private static CaService.CaRequest request(SubjectFields subject, int days, ComboBox<CaService.KeyType> keyType,
                                               java.util.Set<String> takenFolders, Signer signer) {
        SubjectInfo info = subject.read();
        String folder = AuthorityPanel.uniqueFolder(CaService.defaultFolder(info.commonName()), takenFolders);
        return new CaService.CaRequest(info, days, keyType.getValue(), folder, signer == null ? null : signer.ref());
    }

    /** Asks for everything a certificate needs; empty if cancelled. */
    static Optional<CaService.CertRequest> issueCertificate(Window owner, String authorityName) {
        SubjectFields subject = new SubjectFields();
        subject.commonName.setPromptText("optional: the first name below is used if left empty");
        TextField names = new TextField();
        names.setPromptText("app.internal *.app.internal 10.0.0.5 email:me@example.com uri:https://app.internal");
        ComboBox<CaService.Usage> usage = new ComboBox<>(FXCollections.observableArrayList(CaService.Usage.values()));
        usage.setValue(CaService.Usage.SERVER);
        ComboBox<CaService.KeyType> keyType = new ComboBox<>(FXCollections.observableArrayList(CaService.KeyType.values()));
        keyType.setValue(CaService.KeyType.RSA_2048);
        TextField days = new TextField("825");
        TextField fileName = new TextField();
        fileName.setPromptText("optional; made from the common name if left empty");

        java.util.function.Supplier<CaService.CertRequest> read = () -> new CaService.CertRequest(subject.read(),
                AltNames.parse(names.getText(), true, true).names(),
                CertificateViewBase.parseInt(days.getText()) == null ? 0 : CertificateViewBase.parseInt(days.getText()),
                keyType.getValue(), usage.getValue(), fileName.getText().strip());

        boolean ok = FormDialog.show(owner, "Issue certificate from " + authorityName, "Issue", () -> {
            List<String> problems = new ArrayList<>();
            String text = names.getText().strip();
            if (!text.isEmpty()) {
                problems.addAll(AltNames.parse(text, true, true).problems());
            }
            if (CertificateViewBase.parseInt(days.getText()) == null) {
                problems.add("Validity must be a number of days.");
            } else if (problems.isEmpty()) {
                problems.addAll(CaService.problems(read.get()));
            }
            return problems;
        }, "Used for", usage, "Common name", subject.commonName, "Names (SAN)", names,
                "Organisation", subject.organisation, "Organisational unit", subject.unit, "Country", subject.country,
                "State / province", subject.state, "City", subject.locality, "E-mail", subject.email,
                "Key type", keyType, "Valid for (days)", days, "File name", fileName);
        return ok ? Optional.of(read.get()) : Optional.empty();
    }
}

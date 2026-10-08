package mt.su.nrm.ui;

import mt.su.nrm.nginx.LayoutDetector;

/**
 * The files a server certificate can be saved as. Each is asked for by name in a list before the file dialog opens, so
 * the choice never depends on a "Save as type" drop-down. Only public certificate blocks are ever fetched.
 */
enum CertificateExportKind {

    WINDOWS(".p7b", "", "Windows install file (.p7b)",
            "The certificate and its whole chain in one file that Windows can open and install."),
    PEM_FULL(".crt", "-fullchain", "Certificate and chain in one file (PEM, .crt)",
            "The usual \"full chain\" file for nginx and other servers: the certificate first, then the issuing certificates."),
    PEM_CERT(".crt", "", "Certificate only (PEM, .crt)",
            "Just the server's own certificate, without any issuing certificates."),
    PEM_CHAIN(".crt", "-chain", "Chain only (PEM, .crt)",
            "Only the issuing certificates, nearest first, without the server's own certificate.");

    private final String extension;
    private final String nameSuffix;
    private final String title;
    private final String description;

    CertificateExportKind(String extension, String nameSuffix, String title, String description) {
        this.extension = extension;
        this.nameSuffix = nameSuffix;
        this.title = title;
        this.description = description;
    }

    String extension() {
        return extension;
    }

    String title() {
        return title;
    }

    String description() {
        return description;
    }

    /** The file name offered in the save dialog: the certificate's name, a suffix that tells the kinds apart, the extension. */
    String suggestedFileName(String commonName) {
        return LayoutDetector.safeFileName(commonName) + nameSuffix + extension;
    }

    /** The name of the file type shown in the save dialog. */
    String filterName() {
        return this == WINDOWS ? "Windows certificate chain (*.p7b)" : "PEM certificate (*.crt)";
    }

    /** What to write to the file, and what to tell the user about it. */
    record Output(byte[] bytes, String note) {
    }

    /** The chosen kind has nothing to save (a chain-only file for a certificate that has no issuers). */
    static final class NothingToSave extends Exception {
        NothingToSave(String message) {
            super(message);
        }
    }

    /**
     * The file content for this kind. {@code certificate} is the server's own certificate block, {@code chain} the
     * issuing certificates, nearest first. Only certificate blocks are ever passed in.
     */
    Output render(String certificate, java.util.List<String> chain) throws NothingToSave, java.io.IOException {
        String all = certificate + String.join("", chain);
        switch (this) {
            case WINDOWS:
                return new Output(mt.su.nrm.ssl.CertificateChains.toPkcs7(all),
                        "Open it in Windows to see every certificate, or right-click it and choose Install Certificate to "
                                + "add the root and intermediates to the right stores.");
            case PEM_CERT:
                return new Output(certificate.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        "It holds the certificate only.");
            case PEM_CHAIN:
                if (chain.isEmpty()) {
                    throw new NothingToSave("This certificate has no issuing certificates to add: it is self-signed, or its "
                            + "issuer isn't on this server and its file holds nothing but the certificate. Nothing was saved.");
                }
                return new Output(String.join("", chain).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        "It holds the " + chain.size() + " issuing certificate(s), nearest first.");
            default:
                return new Output(all.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        "It holds the certificate followed by " + chain.size() + " issuing certificate(s). Windows shows "
                                + "only the first certificate in a PEM file; use the .p7b format for Windows.");
        }
    }

    /** Adds this kind's extension if the user typed a name without one. */
    String withExtension(String chosenName) {
        return chosenName.contains(".") ? chosenName : chosenName + extension;
    }
}

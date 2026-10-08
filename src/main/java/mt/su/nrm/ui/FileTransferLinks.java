package mt.su.nrm.ui;

import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

import java.util.function.Supplier;

/** The "Open in File Explorer" button that sits beside any field holding a folder on the server. */
final class FileTransferLinks {

    private FileTransferLinks() {
    }

    /**
     * The field with an "Open in File Explorer" button after it.
     *
     * @param access supplied lazily because some editors learn about their server after they are built
     * @param isFile true if the field holds a file (a log, a certificate), so its folder is opened
     */
    static HBox beside(TextField field, Supplier<ServerAccess> access, boolean isFile) {
        Button open = new Button("Open in File Explorer");
        open.disableProperty().bind(field.textProperty().isEmpty());
        open.setOnAction(e -> {
            ServerAccess server = access.get();
            javafx.stage.Window owner = open.getScene() == null ? null : open.getScene().getWindow();
            String dir = directoryOf(field.getText(), isFile);
            if (!server.connected()) {
                Dialogs.info(owner, "Not connected", "Connect to the server to browse its files.");
            } else if (dir == null) {
                Dialogs.info(owner, "Can't open this folder", "\"" + field.getText().strip()
                        + "\" isn't a full path on the server (it is relative or built from nginx variables), "
                        + "so there is no single folder to open.");
            } else {
                server.openInFileTransfer(dir);
            }
        });
        HBox row = new HBox(6, field, open);
        HBox.setHgrow(field, Priority.ALWAYS);
        return row;
    }

    /**
     * The folder to open for a value typed into a field, or null if it isn't a full path. Parts after
     * the first nginx variable ({@code /var/www/$host/html}) are dropped, since they have no fixed
     * location; for a file the folder it is in is used.
     */
    static String directoryOf(String raw, boolean isFile) {
        if (raw == null) {
            return null;
        }
        String value = raw.strip();
        if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
                || value.startsWith("'") && value.endsWith("'"))) {
            value = value.substring(1, value.length() - 1).strip();
        }
        if (!value.startsWith("/")) {
            return null;
        }
        boolean cut = false;
        StringBuilder out = new StringBuilder();
        for (String part : value.split("/")) {
            if (part.isEmpty()) {
                continue;
            }
            if (part.indexOf('$') >= 0) {
                cut = true;
                break;
            }
            out.append('/').append(part);
        }
        String path = out.length() == 0 ? "/" : out.toString();
        if (isFile && !cut) {
            int slash = path.lastIndexOf('/');
            path = slash <= 0 ? "/" : path.substring(0, slash);
        }
        return path;
    }
}

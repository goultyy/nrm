package mt.su.nrm.ui;

import mt.su.nrm.model.ServerProfile;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * A File Explorer screen in its own window, opened from a folder field in an editor. A window of
 * its own (rather than the File Explorer entry in the tree) means a half-edited virtual host is not
 * thrown away by leaving its page. One window is kept per server and reused.
 */
final class FileTransferWindow {

    private final Stage stage = new Stage();
    private final SftpPanel panel;
    private boolean fresh = true;

    FileTransferWindow(Window owner, ServerProfile profile, ServerConnection connection, String startFolder) {
        panel = new SftpPanel(profile, connection, startFolder);
        stage.setTitle("File Explorer - " + profile.getName());
        stage.setScene(new Scene(panel, 1000, 640));
        if (owner != null) {
            stage.initOwner(owner);
            stage.setX(owner.getX() + 40);
            stage.setY(owner.getY() + 40);
        }
    }

    /** Shows the window and moves its server pane to {@code folder}. */
    void show(String folder) {
        if (fresh) {
            fresh = false; // the panel was built already pointing at this folder
        } else {
            panel.goTo(folder);
        }
        stage.show();
        stage.setIconified(false);
        stage.toFront();
    }
}

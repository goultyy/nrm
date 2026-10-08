package mt.su.nrm.app;

/**
 * Plain entry point so the app can start from a classpath jar (and jpackage) without
 * JavaFX complaining that its runtime components are missing.
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        NrmApp.main(args);
    }
}

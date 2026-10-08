package mt.su.nrm.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import mt.su.nrm.ssh.RemotePaths;
import mt.su.nrm.ssh.SftpEntry;
import mt.su.nrm.ui.RemotePickerModel.Kind;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The server folder picker: its path rules, and its behaviour against a fake server. */
class RemotePickerTest {

    private static boolean toolkitAvailable;

    @BeforeAll
    static void startToolkit() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyRunning) {
            started.countDown();
        } catch (Throwable t) {
            return;
        }
        toolkitAvailable = started.await(15, TimeUnit.SECONDS);
        if (toolkitAvailable) {
            Platform.setImplicitExit(false);
        }
    }

    private static <T> T onFx(Callable<T> work) throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available");
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                result.set(work.call());
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });
        if (!done.await(20, TimeUnit.SECONDS)) {
            throw new AssertionError("FX thread did not finish");
        }
        if (failure.get() != null) {
            throw new AssertionError("failed on the FX thread: " + failure.get(), failure.get());
        }
        return result.get();
    }

    private static SftpEntry dir(String name) {
        return new SftpEntry(name, true, 0, 1_700_000_000L, "drwxr-xr-x");
    }

    private static SftpEntry file(String name) {
        return new SftpEntry(name, false, 1234, 1_700_000_000L, "-rw-r--r--");
    }

    // ---------------------------------------------------------------- paths

    @Test
    void pathsAreTidiedWithoutEverEscapingTheRoot() {
        assertEquals("/", RemotePaths.normalize(""));
        assertEquals("/", RemotePaths.normalize(null));
        assertEquals("/", RemotePaths.normalize("/"));
        assertEquals("/a/c", RemotePaths.normalize("//a/./b/../c/"));
        assertEquals("/a/b", RemotePaths.normalize("a/b"));
        assertEquals("/", RemotePaths.normalize("/../.."));
        assertEquals("/x", RemotePaths.normalize("  /x  "));
        assertEquals("/etc/passwd", RemotePaths.normalize("/var/www/../../etc/passwd"));
    }

    @Test
    void crumbsParentAndNamesFollowTheTidiedPath() {
        assertEquals(List.of("/", "var", "www"), RemotePaths.crumbs("/var/www/").stream().map(RemotePaths.Crumb::name).toList());
        assertEquals("/var/www", RemotePaths.crumbs("/var/www").get(2).path());
        assertEquals(1, RemotePaths.crumbs("/").size());
        assertEquals("/var", RemotePaths.parent("/var/www"));
        assertEquals("/", RemotePaths.parent("/var"));
        assertEquals("/", RemotePaths.parent("/"));
        assertEquals("www", RemotePaths.lastName("/var/www/"));
        assertEquals("", RemotePaths.lastName("/"));
    }

    @Test
    void onlyAbsolutePathsWithoutControlCharactersAreListed() {
        assertTrue(RemotePaths.usable("/var/www"));
        assertFalse(RemotePaths.usable("var/www"));
        assertFalse(RemotePaths.usable("/var/\nwww"));
        assertFalse(RemotePaths.usable(null));
    }

    // ---------------------------------------------------------------- the rules

    @Test
    void foldersComeFirstAndFilesAreShownOnlyWhenAsked() {
        List<SftpEntry> all = List.of(file("b.txt"), dir("Zeta"), dir("alpha"), file("A.txt"), dir("."), dir(".."));
        assertEquals(List.of("alpha", "Zeta"), RemotePickerModel.visible(all, false).stream().map(SftpEntry::name).toList());
        assertEquals(List.of("alpha", "Zeta", "A.txt", "b.txt"),
                RemotePickerModel.visible(all, true).stream().map(SftpEntry::name).toList());
    }

    @Test
    void folderModeChoosesFoldersAndFileModeChoosesFiles() {
        assertTrue(RemotePickerModel.selectable(Kind.FOLDER, dir("a")));
        assertFalse(RemotePickerModel.selectable(Kind.FOLDER, file("a")));
        assertTrue(RemotePickerModel.selectable(Kind.FILE, file("a")));
        assertFalse(RemotePickerModel.selectable(Kind.FILE, dir("a")));

        assertEquals("/var/www", RemotePickerModel.chosen(Kind.FOLDER, "/var/www", null), "the folder being viewed");
        assertEquals("/var/www/site", RemotePickerModel.chosen(Kind.FOLDER, "/var/www", dir("site")));
        assertNull(RemotePickerModel.chosen(Kind.FOLDER, "/var/www", file("index.html")), "a file can't be a folder");
        assertNull(RemotePickerModel.chosen(Kind.FILE, "/etc", null), "a file field needs a file");
        assertNull(RemotePickerModel.chosen(Kind.FILE, "/etc", dir("nginx")));
        assertEquals("/etc/a.pem", RemotePickerModel.chosen(Kind.FILE, "/etc", file("a.pem")));
        assertEquals("/a.pem", RemotePickerModel.chosen(Kind.FILE, "/", file("a.pem")));
    }

    @Test
    void thePickerOpensNearWhatTheFieldAlreadyHolds() {
        assertEquals("/var/www", RemotePickerModel.startFolder("/var/www", Kind.FOLDER));
        assertEquals("/var/www", RemotePickerModel.startFolder("/var/www/$host/html", Kind.FOLDER),
                "parts built from variables have no fixed place");
        assertEquals("/", RemotePickerModel.startFolder("", Kind.FOLDER));
        assertEquals("/", RemotePickerModel.startFolder("relative/dir", Kind.FOLDER));
        assertEquals("/etc/ssl", RemotePickerModel.startFolder("/etc/ssl/a.pem", Kind.FILE), "a file field opens its folder");
        assertEquals("a.pem", RemotePickerModel.preselect("/etc/ssl/a.pem", Kind.FILE));
        assertNull(RemotePickerModel.preselect("/etc/ssl/$host.pem", Kind.FILE));
        assertNull(RemotePickerModel.preselect("/etc/ssl", Kind.FOLDER));
        assertEquals(List.of("/a/b/c", "/a/b", "/a", "/"), RemotePickerModel.fallbacks("/a/b/c"));
        assertEquals(List.of("/"), RemotePickerModel.fallbacks("/"));
        assertTrue(RemotePickerModel.showFilesByDefault(Kind.FILE));
        assertFalse(RemotePickerModel.showFilesByDefault(Kind.FOLDER));
        assertEquals("Choose folder", RemotePickerModel.buttonLabel(Kind.FOLDER));
        assertEquals("Choose file", RemotePickerModel.buttonLabel(Kind.FILE));
    }

    // ---------------------------------------------------------------- the dialog against a fake server

    /** A tiny server: folders by path, each with its entries; a path with no entry doesn't exist. */
    private static final class FakeServer implements RemotePickerDialog.FileSystem {
        final Map<String, List<SftpEntry>> tree = new HashMap<>();
        final List<String> listed = new ArrayList<>();
        final List<String> made = new ArrayList<>();
        final List<String> denied = new ArrayList<>();

        @Override
        public List<SftpEntry> list(String dir) throws IOException {
            listed.add(dir);
            if (denied.contains(dir)) {
                throw new IOException("Permission denied");
            }
            List<SftpEntry> entries = tree.get(dir);
            if (entries == null) {
                throw new IOException("No such file");
            }
            return entries;
        }

        @Override
        public void mkdir(String dir) {
            made.add(dir);
            String parent = RemotePaths.parent(dir);
            List<SftpEntry> siblings = new ArrayList<>(tree.getOrDefault(parent, List.of()));
            siblings.add(dir(RemotePaths.lastName(dir)));
            tree.put(parent, siblings);
            tree.put(dir, List.of());
        }
    }

    private static FakeServer server() {
        FakeServer s = new FakeServer();
        s.tree.put("/", List.of(dir("var"), dir("etc"), file("swapfile")));
        s.tree.put("/var", List.of(dir("www"), dir("log")));
        s.tree.put("/var/www", List.of(dir("site"), file("index.html")));
        s.tree.put("/var/www/site", List.of());
        s.tree.put("/etc", List.of(dir("nginx")));
        s.tree.put("/etc/nginx", List.of(file("nginx.conf"), file("a.pem"), dir("ssl")));
        return s;
    }

    private static void waitIdle(RemotePickerDialog d) throws Exception {
        for (int i = 0; i < 200; i++) {
            if (!onFx(d::isBusy)) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("the picker stayed busy");
    }

    private static RemotePickerDialog open(FakeServer s, String field, Kind kind) throws Exception {
        RemotePickerDialog d = onFx(() -> new RemotePickerDialog(s, field, kind));
        onFx(() -> {
            d.goTo(RemotePickerModel.startFolder(field, kind), true);
            return null;
        });
        waitIdle(d);
        return d;
    }

    @Test
    void aFolderThatDoesNotExistFallsBackToTheNearestOneAboveIt() throws Exception {
        FakeServer s = server();
        RemotePickerDialog d = open(s, "/var/www/new-site/html", Kind.FOLDER);
        assertEquals("/var/www", onFx(d::currentFolder));
        assertTrue(onFx(d::statusText).contains("nearest folder above it"), onFx(d::statusText));
        assertEquals(List.of("/var/www/new-site/html", "/var/www/new-site", "/var/www"), s.listed);
    }

    @Test
    void folderModeShowsOnlyFoldersUntilFilesAreTurnedOnThenGreysThemOut() throws Exception {
        FakeServer s = server();
        RemotePickerDialog d = open(s, "/var/www", Kind.FOLDER);
        assertEquals(List.of("site/"), onFx(d::rowNames));
        onFx(() -> {
            d.setShowFiles(true);
            return null;
        });
        assertEquals(List.of("site/", "index.html"), onFx(d::rowNames));
        assertTrue(onFx(() -> d.rowDisabled("index.html")), "a file can't be picked as a folder");
        assertFalse(onFx(() -> d.rowDisabled("site")));
    }

    @Test
    void selectGivesTheFolderYouAreInOrTheOneHighlighted() throws Exception {
        FakeServer s = server();
        RemotePickerDialog d = open(s, "/var/www", Kind.FOLDER);
        assertEquals("/var/www", onFx(d::chosen));
        onFx(() -> {
            d.select("site");
            return null;
        });
        assertEquals("/var/www/site", onFx(d::chosen));
    }

    @Test
    void fileModeStartsWithFilesShownPreselectsTheCurrentFileAndNeedsAFile() throws Exception {
        FakeServer s = server();
        RemotePickerDialog d = open(s, "/etc/nginx/a.pem", Kind.FILE);
        assertEquals("/etc/nginx", onFx(d::currentFolder));
        assertEquals(List.of("ssl/", "a.pem", "nginx.conf"), onFx(d::rowNames));
        assertEquals("/etc/nginx/a.pem", onFx(d::chosen), "the file already in the field is highlighted");
        onFx(() -> {
            d.select("ssl");
            return null;
        });
        assertNull(onFx(d::chosen), "a folder can't be chosen for a file field");
        onFx(() -> {
            d.select("nginx.conf");
            return null;
        });
        assertEquals("/etc/nginx/nginx.conf", onFx(d::chosen));
        onFx(() -> {
            d.setShowFiles(false);
            return null;
        });
        assertEquals(List.of("ssl/"), onFx(d::rowNames), "the files box is a toggle in file mode too");
    }

    @Test
    void walkingDownAndUpWorksAndUpIsOffAtTheRoot() throws Exception {
        FakeServer s = server();
        RemotePickerDialog d = open(s, "", Kind.FOLDER);
        assertEquals("/", onFx(d::currentFolder));
        assertFalse(onFx(d::upEnabled));
        assertEquals(List.of("etc/", "var/"), onFx(d::rowNames));
        onFx(() -> {
            d.goTo("/var", false);
            return null;
        });
        waitIdle(d);
        assertEquals("/var", onFx(d::currentFolder));
        assertTrue(onFx(d::upEnabled));
        onFx(() -> {
            d.goTo("/var/../etc/./nginx", false);
            return null;
        });
        waitIdle(d);
        assertEquals("/etc/nginx", onFx(d::currentFolder), "typed paths are tidied");
    }

    @Test
    void aFolderThatCantBeReadStaysWhereYouWereAndSaysWhy() throws Exception {
        FakeServer s = server();
        s.denied.add("/etc");
        RemotePickerDialog d = open(s, "/var/www", Kind.FOLDER);
        onFx(() -> {
            d.goTo("/etc", false);
            return null;
        });
        waitIdle(d);
        assertEquals("/var/www", onFx(d::currentFolder));
        assertTrue(onFx(d::statusText).contains("Could not read /etc: Permission denied"), onFx(d::statusText));
        assertEquals("/var/www", onFx(d::chosen), "the choice is still the folder you are in");
    }

    @Test
    void aPathThatIsNotUsableIsRefusedBeforeAnythingIsSent() throws Exception {
        FakeServer s = server();
        RemotePickerDialog d = open(s, "/var/www", Kind.FOLDER);
        int before = s.listed.size();
        onFx(() -> {
            d.goTo("/var/\nwww", false);
            return null;
        });
        assertEquals(before, s.listed.size(), "nothing may be sent for a path with control characters");
        assertTrue(onFx(d::statusText).contains("isn't a usable path"));
    }
}

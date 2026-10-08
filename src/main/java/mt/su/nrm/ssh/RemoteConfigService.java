package mt.su.nrm.ssh;

import mt.su.nrm.model.ServerPaths;
import mt.su.nrm.nginx.ConfigFile;
import mt.su.nrm.nginx.LayoutDetector;
import mt.su.nrm.nginx.NginxParseException;
import mt.su.nrm.nginx.RemoteConfig;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads a server's nginx configuration: the main file, then every file it includes (following
 * includes a few levels deep). Each round trip fetches all files matched by a set of include
 * patterns in one command, since every round trip is slow on a distant server.
 */
public final class RemoteConfigService {

    /** A real (glob-free) path the app is willing to place inside a remote shell command. */
    static final Pattern SAFE_PATH = Pattern.compile("/[A-Za-z0-9_./+@%=,:~-]+");
    /** The same, allowing glob characters, for include patterns. */
    private static final Pattern SAFE_GLOB = Pattern.compile("/[A-Za-z0-9_./*?\\[\\]+@%=,:~-]+");

    private static final int MAX_ROUNDS = 4;
    private static final SecureRandom RANDOM = new SecureRandom();

    private RemoteConfigService() {
    }

    /** Loads the configuration. Blocking: run it through {@link SshExecutor}. */
    public static RemoteConfig load(SshSession session, ServerPaths paths) throws IOException {
        String mainPath = paths.mainConfigFile();
        CommandResult main = session.execPrivileged("cat " + Shell.quote(mainPath));
        if (!main.ok()) {
            throw new IOException("Could not read " + mainPath + ": " + firstLine(main.stderr()));
        }
        if (main.stdout().indexOf('�') >= 0) {
            throw new IOException(mainPath + " is not valid UTF-8 text.");
        }
        ConfigFile mainFile;
        try {
            mainFile = ConfigFile.parse(mainPath, main.stdout());
        } catch (NginxParseException e) {
            throw new IOException(mainPath + " has a syntax error: " + e.getMessage(), e);
        }

        RemoteConfig config = new RemoteConfig(paths.getNginxConfDir(), LayoutDetector.detect(mainFile), mainFile);
        Set<String> seenPatterns = new HashSet<>();
        Set<String> loaded = new HashSet<>();
        List<String> patterns = mainFile.includePatterns();

        for (int round = 0; round < MAX_ROUNDS && !patterns.isEmpty(); round++) {
            List<String> globs = new ArrayList<>();
            for (String pattern : patterns) {
                String absolute = pattern.startsWith("/") ? pattern : join(config.confDir(), pattern);
                if (!seenPatterns.add(absolute)) {
                    continue;
                }
                if (SAFE_GLOB.matcher(absolute).matches()) {
                    globs.add(absolute);
                } else {
                    config.addProblem("Skipped include \"" + pattern + "\": the path has unusual characters.");
                }
            }
            if (globs.isEmpty()) {
                break;
            }
            String nonce = newNonce();
            CommandResult result = session.execPrivileged(loadScript(nonce, globs), SshSession.DEFAULT_TIMEOUT.multipliedBy(3));
            List<ConfigFile> newFiles = readFiles(result.stdout(), nonce, config, loaded);
            patterns = new ArrayList<>();
            for (ConfigFile f : newFiles) {
                patterns.addAll(f.includePatterns());
            }
        }
        return config;
    }

    /** The shell script that prints every file matching the globs, framed with markers containing the nonce. */
    static String loadScript(String nonce, List<String> globs) {
        StringBuilder sb = new StringBuilder();
        sb.append("for p in ").append(String.join(" ", globs)).append("; do\n");
        sb.append("  for f in $p; do\n");
        sb.append("    [ -f \"$f\" ] || continue\n");
        sb.append("    printf '@@NRM-").append(nonce).append(" FILE\\t%s\\t%s\\t%s\\n' \"$f\" \"$(readlink -f \"$f\")\" \"$(readlink \"$f\")\"\n");
        sb.append("    cat \"$f\"\n");
        sb.append("    printf '\\n@@NRM-").append(nonce).append(" END\\n'\n");
        sb.append("  done\n");
        sb.append("done\n");
        return sb.toString();
    }

    private record Found(String path, String real, String linkTarget, String text) {
    }

    private static List<ConfigFile> readFiles(String output, String nonce, RemoteConfig config, Set<String> loaded) {
        String head = "@@NRM-" + nonce + " FILE\t";
        String end = "\n@@NRM-" + nonce + " END\n";
        Map<String, List<Found>> byReal = new LinkedHashMap<>();
        int pos = 0;
        while (true) {
            int start = output.indexOf(head, pos);
            if (start < 0) {
                break;
            }
            int headerEnd = output.indexOf('\n', start);
            if (headerEnd < 0) {
                break;
            }
            String[] fields = output.substring(start + head.length(), headerEnd).split("\t", -1);
            int contentEnd = output.indexOf(end, headerEnd + 1);
            if (fields.length < 3 || contentEnd < 0) {
                break;
            }
            String text = output.substring(headerEnd + 1, contentEnd);
            String real = fields[1].isEmpty() ? fields[0] : fields[1];
            byReal.computeIfAbsent(real, k -> new ArrayList<>()).add(new Found(fields[0], real, fields[2], text));
            pos = contentEnd + end.length();
        }

        List<ConfigFile> parsed = new ArrayList<>();
        Map<String, List<RemoteConfig.Link>> links = new HashMap<>();
        for (Map.Entry<String, List<Found>> e : byReal.entrySet()) {
            for (Found f : e.getValue()) {
                if (!f.path().equals(f.real()) && !f.linkTarget().isEmpty()) {
                    links.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(new RemoteConfig.Link(f.path(), f.linkTarget()));
                }
            }
        }
        for (Map.Entry<String, List<Found>> e : byReal.entrySet()) {
            String real = e.getKey();
            if (!loaded.add(real)) {
                continue;
            }
            Found first = e.getValue().get(0);
            if (first.text().indexOf('�') >= 0) {
                config.addProblem(real + " is not valid UTF-8 text and was skipped.");
                continue;
            }
            try {
                ConfigFile file = ConfigFile.parse(real, first.text());
                config.addFile(file, links.getOrDefault(real, List.of()), readOnlyReason(real, config.confDir()));
                parsed.add(file);
            } catch (NginxParseException ex) {
                config.addProblem(real + " could not be parsed and is left untouched: " + ex.getMessage());
            }
        }
        return parsed;
    }

    private static String readOnlyReason(String realPath, String confDir) {
        if (!SAFE_PATH.matcher(realPath).matches()) {
            return "The path has unusual characters.";
        }
        if (!realPath.startsWith(confDir + "/")) {
            return "Outside the nginx config folder (" + confDir + ").";
        }
        return null;
    }

    private static String newNonce() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static String join(String dir, String name) {
        return dir.endsWith("/") ? dir + name : dir + "/" + name;
    }

    private static String firstLine(String text) {
        String t = text.strip();
        int nl = t.indexOf('\n');
        return nl < 0 ? t : t.substring(0, nl);
    }
}

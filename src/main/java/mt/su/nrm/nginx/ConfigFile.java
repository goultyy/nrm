package mt.su.nrm.nginx;

import java.util.ArrayList;
import java.util.List;

/** One parsed nginx configuration file: its path on the server, the tree, and the text it was read from. */
public final class ConfigFile {

    private final String path;
    private final Block root;
    private final String originalText;

    ConfigFile(String path, Block root, String originalText) {
        this.path = path;
        this.root = root;
        this.originalText = originalText;
    }

    /** Parses text that is expected to be the content of {@code path}. */
    public static ConfigFile parse(String path, String text) throws NginxParseException {
        return new ConfigFile(path, NginxParser.parseRoot(text), text);
    }

    /** An empty file that has not been written to the server yet. */
    public static ConfigFile empty(String path) {
        return new ConfigFile(path, new Block("", true), "");
    }

    public String path() {
        return path;
    }

    public Block root() {
        return root;
    }

    /** The text as it was read; empty for a new file. */
    public String originalText() {
        return originalText;
    }

    /** The current text: identical to {@link #originalText()} unless something was changed. */
    public String generate() {
        StringBuilder out = new StringBuilder(originalText.length() + 256);
        root.emit(out);
        return out.toString();
    }

    public boolean isModified() {
        return !generate().equals(originalText);
    }

    /** The {@code server} blocks directly inside {@code http { }} (or at top level, for included files). */
    public List<Block> serverBlocks() {
        List<Block> result = new ArrayList<>();
        collectServers(root, result);
        return result;
    }

    private static void collectServers(Block block, List<Block> out) {
        for (Node n : block.children) {
            if (n instanceof Block) {
                Block b = (Block) n;
                if (b.name.equals("server")) {
                    out.add(b);
                } else if (b.name.equals("http") && !b.isOpaque()) {
                    collectServers(b, out);
                }
            }
        }
    }

    /** Every directive with one of these names, at any depth (Lua and other opaque bodies are skipped). */
    public List<Directive> findDirectives(java.util.Collection<String> names) {
        List<Directive> result = new ArrayList<>();
        collect(root, names, result);
        return result;
    }

    private static void collect(Block block, java.util.Collection<String> names, List<Directive> out) {
        for (Node n : block.children) {
            if (n instanceof Directive && names.contains(n.name)) {
                out.add((Directive) n);
            } else if (n instanceof Block && !((Block) n).isOpaque()) {
                collect((Block) n, names, out);
            }
        }
    }

    /** All {@code include} patterns in the file, at any depth, in order. */
    public List<String> includePatterns() {
        List<String> result = new ArrayList<>();
        collectIncludes(root, result);
        return result;
    }

    private static void collectIncludes(Block block, List<String> out) {
        for (Node n : block.children) {
            if (n instanceof Directive && n.name.equals("include") && n.arg(0) != null) {
                out.add(n.arg(0));
            } else if (n instanceof Block && !((Block) n).isOpaque()) {
                collectIncludes((Block) n, out);
            }
        }
    }

    @Override
    public String toString() {
        return "ConfigFile[" + path + "]";
    }
}

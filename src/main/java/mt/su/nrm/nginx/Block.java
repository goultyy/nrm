package mt.su.nrm.nginx;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A statement with a body: {@code name args { ... }}. The file itself is a root block with no
 * braces. Blocks whose body is not nginx syntax (for example {@code content_by_lua_block}) keep the
 * body as opaque text and are never looked into or changed.
 */
public final class Block extends Node {

    private static final String INDENT_UNIT = "    ";

    final List<Node> children = new ArrayList<>();
    /** Whitespace and comments between the last child and the closing brace. */
    String closeLeading = "";
    /** Body text for blocks that are not nginx syntax; null for normal blocks. */
    String rawBody;
    final boolean root;

    Block(String name, boolean root) {
        super(name);
        this.root = root;
    }

    /** A new, empty block, not yet attached to a parent. */
    public static Block create(String name, List<String> values) {
        Block b = new Block(name, false);
        for (String v : values) {
            b.args.add(Arg.of(v));
        }
        return b;
    }

    public boolean isRoot() {
        return root;
    }

    /** True if the body is kept as opaque text (not parsed). */
    public boolean isOpaque() {
        return rawBody != null;
    }

    public List<Node> children() {
        return Collections.unmodifiableList(children);
    }

    /** Direct child directives with this name, in order. */
    public List<Directive> directives(String directiveName) {
        List<Directive> result = new ArrayList<>();
        for (Node n : children) {
            if (n instanceof Directive && n.name.equals(directiveName)) {
                result.add((Directive) n);
            }
        }
        return result;
    }

    /** The first direct child directive with this name, or null. */
    public Directive first(String directiveName) {
        for (Node n : children) {
            if (n instanceof Directive && n.name.equals(directiveName)) {
                return (Directive) n;
            }
        }
        return null;
    }

    /** Direct child blocks with this name, in order. */
    public List<Block> blocks(String blockName) {
        List<Block> result = new ArrayList<>();
        for (Node n : children) {
            if (n instanceof Block && n.name.equals(blockName)) {
                result.add((Block) n);
            }
        }
        return result;
    }

    /** Adds a child at the end of the body. */
    public void add(Node node) {
        insert(children.size(), node);
    }

    /**
     * Inserts a child, giving it the body's indentation. A comment sitting on the same line as the
     * previous statement stays with that statement.
     */
    public void insert(int index, Node node) {
        String holder = index < children.size() ? children.get(index).leading : closeLeading;
        int nl = holder.indexOf('\n');
        String sameLine = nl < 0 ? holder : holder.substring(0, nl);
        String rest = nl < 0 ? "" : holder.substring(nl);
        if (sameLine.isBlank()) {
            sameLine = "";
        }
        String indent = childIndent();
        node.leading = sameLine + "\n" + indent;
        node.raw = null;
        node.parent = this;
        if (node instanceof Block && ((Block) node).closeLeading.isEmpty() && ((Block) node).children.isEmpty()) {
            ((Block) node).closeLeading = "\n" + indent;
        }
        if (index < children.size()) {
            children.get(index).leading = rest.isEmpty() ? "\n" + indent : rest;
        } else {
            closeLeading = rest.isEmpty() ? "\n" + ownIndent() : rest;
        }
        children.add(index, node);
    }

    /** Removes a child together with the comments written above it. */
    public void remove(Node node) {
        int i = children.indexOf(node);
        if (i < 0) {
            return;
        }
        String own = node.leading;
        int ownNl = own.indexOf('\n');
        String keep = ownNl < 0 ? own : own.substring(0, ownNl);
        if (keep.isBlank()) {
            keep = "";
        }
        String holder = i + 1 < children.size() ? children.get(i + 1).leading : closeLeading;
        int nl = holder.indexOf('\n');
        String rest = nl < 0 ? "" : holder.substring(nl);
        String replacement = keep + rest;
        if (i + 1 < children.size()) {
            children.get(i + 1).leading = replacement;
        } else {
            closeLeading = replacement;
        }
        children.remove(i);
        node.parent = null;
    }

    @Override
    String terminator() {
        return " {";
    }

    @Override
    void emit(StringBuilder out) {
        if (root) {
            for (Node n : children) {
                n.emit(out);
            }
            out.append(closeLeading);
            return;
        }
        out.append(leading).append(head());
        if (rawBody != null) {
            out.append(rawBody);
        } else {
            for (Node n : children) {
                n.emit(out);
            }
            out.append(closeLeading);
        }
        out.append('}');
    }

    /** Indentation of this block's own line. */
    private String ownIndent() {
        return root ? "" : indent();
    }

    /** Indentation for statements inside the body: what existing children use, else one level in. */
    private String childIndent() {
        for (Node n : children) {
            if (n.leading.indexOf('\n') >= 0) {
                return n.indent();
            }
        }
        if (root) {
            return "";
        }
        String own = ownIndent();
        return own.contains("\t") ? own + "\t" : own + INDENT_UNIT;
    }

    @Override
    public String toString() {
        return root ? "(file)" : head();
    }
}

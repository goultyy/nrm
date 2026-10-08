package mt.su.nrm.nginx;

import java.util.ArrayList;
import java.util.List;

/**
 * A statement in an nginx config: a {@link Directive} ({@code name args;}) or a {@link Block}
 * ({@code name args { ... }}).
 * <p>
 * The tree is lossless. {@link #leading()} holds the whitespace and comments before the statement
 * and {@link #raw} the statement's own original text, so anything the app doesn't change is
 * written back exactly as it was read. Changing a statement's arguments discards its raw text and
 * regenerates just that statement.
 */
public abstract class Node {

    String leading = "";
    /** Original source of the statement head (name, args and terminator); null once modified. */
    String raw;
    final String name;
    final List<Arg> args = new ArrayList<>();
    Block parent;

    Node(String name) {
        this.name = name;
    }

    public final String name() {
        return name;
    }

    public final String leading() {
        return leading;
    }

    public final Block parent() {
        return parent;
    }

    /** The arguments' values, quotes removed. */
    public final List<String> values() {
        List<String> values = new ArrayList<>(args.size());
        for (Arg a : args) {
            values.add(a.value());
        }
        return values;
    }

    /** The i-th argument's value, or null if there is none. */
    public final String arg(int i) {
        return i < args.size() ? args.get(i).value() : null;
    }

    /** Replaces the arguments; does nothing (keeping the original text) if the values are unchanged. */
    public final void setArgs(List<String> newValues) {
        if (values().equals(newValues)) {
            return;
        }
        args.clear();
        for (String v : newValues) {
            args.add(Arg.of(v));
        }
        raw = null;
    }

    /** True if this statement has been changed since it was read (or was created by the app). */
    public final boolean isModified() {
        return raw == null;
    }

    final String head() {
        if (raw != null) {
            return raw;
        }
        StringBuilder sb = new StringBuilder(name);
        for (Arg a : args) {
            sb.append(' ').append(a.raw());
        }
        sb.append(terminator());
        return sb.toString();
    }

    abstract String terminator();

    abstract void emit(StringBuilder out);

    /** Whitespace before this statement on its own line ("" if it isn't at the start of a line). */
    final String indent() {
        int nl = leading.lastIndexOf('\n');
        if (nl < 0) {
            return "";
        }
        String tail = leading.substring(nl + 1);
        return tail.isBlank() ? tail : "";
    }
}

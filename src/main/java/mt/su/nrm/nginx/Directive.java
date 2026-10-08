package mt.su.nrm.nginx;

import java.util.List;

/** A simple statement: {@code name arg1 arg2;}. */
public final class Directive extends Node {

    Directive(String name) {
        super(name);
    }

    /** A new directive, not yet attached to a block. */
    public static Directive create(String name, List<String> values) {
        Directive d = new Directive(name);
        for (String v : values) {
            d.args.add(Arg.of(v));
        }
        return d;
    }

    @Override
    String terminator() {
        return ";";
    }

    @Override
    void emit(StringBuilder out) {
        out.append(leading).append(head());
    }

    @Override
    public String toString() {
        return head();
    }
}

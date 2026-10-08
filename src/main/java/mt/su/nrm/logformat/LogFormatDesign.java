package mt.su.nrm.logformat;

import mt.su.nrm.nginx.LogFormatSettings;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the log format builder edits: an ordered list of rows that becomes one {@code log_format}. A row is either a
 * <i>field</i> (an nginx variable) or a piece of <i>text</i> placed between fields (a space, a bracket, a quote).
 * <p>
 * Two styles: {@link Style#TEXT}, where the rows are written one after another, and {@link Style#JSON}, where each
 * field becomes a key of a JSON object (and values are escaped for JSON). Any existing format can be read back into
 * rows ({@link #from}), so the builder can edit formats it didn't create.
 * <p>
 * Free of JavaFX so everything here can be tested.
 */
public final class LogFormatDesign {

    public enum Style { TEXT, JSON }

    /**
     * One row. {@code value} is a variable for a field or the literal for text; {@code key} and {@code quoted} only
     * matter in the JSON style (the key in the object, and whether the value is written inside quotes).
     */
    public record Element(Kind kind, String value, String key, boolean quoted) {

        public enum Kind { FIELD, TEXT }

        public static Element field(String variable) {
            return new Element(Kind.FIELD, variable, defaultKey(variable), !numeric(variable));
        }

        public static Element text(String literal) {
            return new Element(Kind.TEXT, literal, "", false);
        }

        public Element withValue(String newValue) {
            return new Element(kind, newValue, key, quoted);
        }

        public Element withKey(String newKey) {
            return new Element(kind, value, newKey, quoted);
        }

        public Element withQuoted(boolean isQuoted) {
            return new Element(kind, value, key, isQuoted);
        }

        public boolean isField() {
            return kind == Kind.FIELD;
        }
    }

    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_-]{0,63}");
    private static final Pattern KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]*");
    private static final Pattern DOLLAR_VARIABLE = Pattern.compile("\\$[A-Za-z_{]");
    private static final int LONG = 1000;

    private static final String JSON_PAIR_KEY = "\"([A-Za-z_][A-Za-z0-9_.-]*)\"\\s*:\\s*";
    private static final Pattern JSON_PAIR = Pattern.compile(
            JSON_PAIR_KEY + "(?:\"(\\$[A-Za-z_][A-Za-z0-9_]*)\"|(\\$[A-Za-z_][A-Za-z0-9_]*))");

    private Style style = Style.TEXT;
    private LogFormatSettings.Escape escape = LogFormatSettings.Escape.DEFAULT;
    private final List<Element> elements = new ArrayList<>();

    // ---------------------------------------------------------------- creating

    public static LogFormatDesign blank() {
        return new LogFormatDesign();
    }

    /** Reads an existing format into rows. A JSON object with plain key-value pairs becomes the JSON style. */
    public static LogFormatDesign from(LogFormatSettings settings) {
        LogFormatDesign design = new LogFormatDesign();
        design.escape = settings.escape;
        if (settings.escape == LogFormatSettings.Escape.JSON) {
            Optional<List<Element>> json = parseJson(settings.text);
            if (json.isPresent()) {
                design.style = Style.JSON;
                design.elements.addAll(json.get());
                return design;
            }
        }
        for (String[] token : LogFields.tokens(settings.text)) {
            design.elements.add(token[0].equals("variable") ? Element.field(token[1]) : Element.text(token[1]));
        }
        return design;
    }

    public LogFormatDesign copy() {
        LogFormatDesign c = new LogFormatDesign();
        c.style = style;
        c.escape = escape;
        c.elements.addAll(elements);
        return c;
    }

    // ---------------------------------------------------------------- the rows and options

    public Style style() {
        return style;
    }

    /** Switches style, keeping the fields: to JSON each becomes a key; to text they are separated by spaces. */
    public void style(Style newStyle) {
        if (newStyle == style) {
            return;
        }
        List<Element> fields = elements.stream().filter(Element::isField).toList();
        elements.clear();
        if (newStyle == Style.JSON) {
            for (Element f : fields) {
                elements.add(Element.field(f.value()));
            }
        } else {
            for (int i = 0; i < fields.size(); i++) {
                if (i > 0) {
                    elements.add(Element.text(" "));
                }
                elements.add(Element.field(fields.get(i).value()));
            }
        }
        style = newStyle;
    }

    /** How values are escaped. A JSON-style format always escapes for JSON. */
    public LogFormatSettings.Escape escape() {
        return style == Style.JSON ? LogFormatSettings.Escape.JSON : escape;
    }

    public void escape(LogFormatSettings.Escape newEscape) {
        this.escape = newEscape;
    }

    /** The rows, in order. The builder changes this list directly. */
    public List<Element> elements() {
        return elements;
    }

    // ---------------------------------------------------------------- the result

    /** The format string nginx gets. */
    public String text() {
        StringBuilder out = new StringBuilder();
        if (style == Style.JSON) {
            out.append('{');
            boolean first = true;
            for (Element e : elements) {
                if (!e.isField()) {
                    continue;
                }
                if (!first) {
                    out.append(',');
                }
                first = false;
                out.append('"').append(e.key()).append("\":");
                out.append(e.quoted() ? "\"" + e.value() + "\"" : e.value());
            }
            return out.append('}').toString();
        }
        for (Element e : elements) {
            out.append(e.value());
        }
        return out.toString();
    }

    public LogFormatSettings toSettings(String name) {
        LogFormatSettings s = new LogFormatSettings();
        s.name = name;
        s.escape = escape();
        s.text = text();
        return s;
    }

    /** A line as it would look in the log, using realistic sample values. */
    public String preview() {
        String text = text();
        Matcher m = LogFields.IN_TEXT.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String sample = LogFields.sample(m.group());
            if (style == Style.JSON && isQuotedInJson(text, m.start())) {
                sample = jsonEscape(sample);
            }
            m.appendReplacement(out, Matcher.quoteReplacement(sample));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static boolean isQuotedInJson(String text, int variableStart) {
        return variableStart > 0 && text.charAt(variableStart - 1) == '"';
    }

    private static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ---------------------------------------------------------------- checking

    /**
     * What stops this from being saved, worded for the user.
     *
     * @param name       the name chosen for the format
     * @param takenNames the names of the other log formats, which can't be reused
     */
    public List<String> problems(String name, Collection<String> takenNames) {
        List<String> problems = new ArrayList<>(nameProblems(name, takenNames));
        problems.addAll(contentProblems());
        return problems;
    }

    /** What is wrong with the name alone. */
    public static List<String> nameProblems(String name, Collection<String> takenNames) {
        List<String> problems = new ArrayList<>();
        if (name.isBlank()) {
            problems.add("Give the format a name.");
        } else if (!NAME.matcher(name).matches()) {
            problems.add("The name may use letters, digits, underscores and hyphens, and must start with a letter or "
                    + "underscore.");
        } else if (takenNames.contains(name)) {
            problems.add("A log format named \"" + name + "\" already exists.");
        }
        return problems;
    }

    /** What is wrong with the rows, whatever the format is called. */
    public List<String> contentProblems() {
        List<String> problems = new ArrayList<>();
        if (elements.stream().noneMatch(Element::isField)) {
            problems.add("Add at least one field, or the log lines would say nothing.");
        }
        Set<String> keys = new HashSet<>();
        for (Element e : elements) {
            if (e.isField()) {
                if (!LogFields.isWellFormed(e.value())) {
                    problems.add("\"" + e.value() + "\" is not a variable name. A variable is a dollar sign and a "
                            + "name, such as $status.");
                }
                if (style == Style.JSON) {
                    if (!KEY.matcher(e.key()).matches()) {
                        problems.add("\"" + e.key() + "\" can't be used as a JSON key. Use letters, digits, "
                                + "underscores, dots and hyphens.");
                    } else if (!keys.add(e.key())) {
                        problems.add("The key \"" + e.key() + "\" is used twice.");
                    }
                    if (e.value().startsWith("${")) {
                        problems.add(e.value() + " must be written as " + LogFields.plain(e.value()) + " in a JSON log.");
                    }
                }
            } else if (DOLLAR_VARIABLE.matcher(e.value()).find()) {
                problems.add("Text can't contain a dollar sign followed by a letter: nginx would read it as a variable. "
                        + "Add it as a field instead.");
            }
            if (e.value().chars().anyMatch(c -> c == '\n' || c == '\r' || c == 0)) {
                problems.add("A log line is one line: remove the line break.");
            }
        }
        return problems;
    }

    /** Things worth knowing that don't stop it from being saved. */
    public List<String> warnings(String name) {
        List<String> warnings = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Element e : elements) {
            if (!e.isField() || !seen.add(LogFields.plain(e.value()))) {
                continue;
            }
            Optional<LogField> known = LogFields.find(e.value());
            if (known.isEmpty() && LogFields.isWellFormed(e.value()) && !isFamily(e.value())) {
                warnings.add(LogFields.plain(e.value()) + " is not one of the common variables. That is fine if a "
                        + "module or a map block defines it; otherwise nginx will refuse the configuration.");
            } else if (known.isPresent() && !known.get().note().isEmpty()) {
                warnings.add(known.get().variable() + ": " + known.get().note());
            }
        }
        if (name.equals("combined")) {
            warnings.add("\"combined\" is nginx's built-in format. A format with this name replaces it for everything "
                    + "that uses \"combined\".");
        }
        if (escape() == LogFormatSettings.Escape.NONE) {
            warnings.add("With no escaping, a visitor can put quotes or line breaks into the log and confuse tools that "
                    + "read it.");
        }
        if (text().length() > LONG) {
            warnings.add("This format is very long (" + text().length() + " characters).");
        }
        return warnings;
    }

    /** Variables that nginx builds from a name: request headers, response headers, cookies and query arguments. */
    private static boolean isFamily(String variable) {
        String v = LogFields.plain(variable);
        return v.startsWith("$http_") || v.startsWith("$sent_http_") || v.startsWith("$upstream_http_")
                || v.startsWith("$cookie_") || v.startsWith("$arg_") || v.startsWith("$upstream_cookie_");
    }

    // ---------------------------------------------------------------- helpers

    private static boolean numeric(String variable) {
        return LogFields.find(variable).map(LogField::numeric).orElse(false);
    }

    private static String defaultKey(String variable) {
        return LogFields.find(variable).map(LogField::defaultKey)
                .orElseGet(() -> LogFields.plain(variable).substring(1).toLowerCase(Locale.ROOT));
    }

    /** {"key":"$var","key2":$num} as rows, or empty if the text is anything else. */
    private static Optional<List<Element>> parseJson(String text) {
        String t = text.strip();
        if (t.length() < 2 || t.charAt(0) != '{' || t.charAt(t.length() - 1) != '}') {
            return Optional.empty();
        }
        String inner = t.substring(1, t.length() - 1);
        List<Element> rows = new ArrayList<>();
        int pos = skip(inner, 0);
        while (pos < inner.length()) {
            Matcher m = JSON_PAIR.matcher(inner);
            m.region(pos, inner.length());
            if (!m.lookingAt()) {
                return Optional.empty();
            }
            boolean quoted = m.group(2) != null;
            String variable = quoted ? m.group(2) : m.group(3);
            rows.add(new Element(Element.Kind.FIELD, variable, m.group(1), quoted));
            pos = skip(inner, m.end());
            if (pos < inner.length()) {
                if (inner.charAt(pos) != ',') {
                    return Optional.empty();
                }
                pos = skip(inner, pos + 1);
                if (pos >= inner.length()) {
                    return Optional.empty();
                }
            }
        }
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows);
    }

    private static int skip(String s, int from) {
        int i = from;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }
}

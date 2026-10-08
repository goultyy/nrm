package mt.su.nrm.nginx;

/**
 * Lossless nginx config parser. It builds a tree in which every statement keeps its original text
 * and every gap keeps its whitespace and comments, so regenerating an unchanged tree reproduces
 * the input exactly.
 * <p>
 * The parser never interprets directives: anything it does not specifically know is still just a
 * directive or block with arguments, so unrecognised configuration survives untouched. The only
 * bodies that are not parsed are those of blocks in a different language (Lua), which are kept as
 * opaque text.
 */
final class NginxParser {

    private final String s;
    private int pos;

    private NginxParser(String text) {
        this.s = text;
    }

    static Block parseRoot(String text) throws NginxParseException {
        NginxParser parser = new NginxParser(text);
        Block root = new Block("", true);
        parser.parseBody(root);
        return root;
    }

    /** Parses statements until the closing brace (or end of input for the root). */
    private void parseBody(Block block) throws NginxParseException {
        while (true) {
            int triviaStart = pos;
            skipTrivia();
            String leading = s.substring(triviaStart, pos);
            if (pos >= s.length()) {
                if (!block.root) {
                    throw error("unexpected end of file, expecting \"}\"");
                }
                block.closeLeading = leading;
                return;
            }
            char c = s.charAt(pos);
            if (c == '}') {
                if (block.root) {
                    throw error("unexpected \"}\"");
                }
                block.closeLeading = leading;
                pos++;
                return;
            }
            Node node = parseStatement(leading);
            node.parent = block;
            block.children.add(node);
        }
    }

    private Node parseStatement(String leading) throws NginxParseException {
        int start = pos;
        java.util.List<String> words = new java.util.ArrayList<>();
        while (true) {
            skipTrivia();
            if (pos >= s.length()) {
                throw error("unexpected end of file, expecting \";\" or \"}\"");
            }
            char c = s.charAt(pos);
            if (c == ';' || c == '{') {
                if (words.isEmpty()) {
                    throw error("unexpected \"" + c + "\"");
                }
                pos++;
                return finishStatement(leading, start, words, c == '{');
            }
            if (c == '}' && words.isEmpty()) {
                throw error("unexpected \"}\"");
            }
            words.add(readWord());
        }
    }

    private Node finishStatement(String leading, int start, java.util.List<String> words, boolean isBlock)
            throws NginxParseException {
        String name = words.get(0);
        String headRaw = s.substring(start, pos);
        Node node;
        if (isBlock) {
            Block block = new Block(name, false);
            block.leading = leading;
            block.raw = headRaw;
            addArgs(block, words);
            if (isOpaqueBlock(name)) {
                int end = findMatchingBrace(pos);
                block.rawBody = s.substring(pos, end);
                pos = end + 1;
            } else {
                parseBody(block);
            }
            node = block;
        } else {
            Directive directive = new Directive(name);
            directive.leading = leading;
            directive.raw = headRaw;
            addArgs(directive, words);
            node = directive;
        }
        return node;
    }

    private static void addArgs(Node node, java.util.List<String> words) {
        for (int i = 1; i < words.size(); i++) {
            node.args.add(new Arg(words.get(i)));
        }
    }

    /** Blocks whose body is another language, e.g. content_by_lua_block. */
    private static boolean isOpaqueBlock(String name) {
        return name.endsWith("_by_lua_block") || name.equals("lua_block");
    }

    private String readWord() throws NginxParseException {
        int start = pos;
        while (pos < s.length()) {
            char c = s.charAt(pos);
            if (c == '"' || c == '\'') {
                skipQuoted(c);
                continue;
            }
            if (c == '\\' && pos + 1 < s.length()) {
                pos += 2;
                continue;
            }
            if (c == '$' && pos + 1 < s.length() && s.charAt(pos + 1) == '{') {
                int close = s.indexOf('}', pos);
                if (close < 0) {
                    throw error("unterminated variable \"${\"");
                }
                pos = close + 1;
                continue;
            }
            if (Character.isWhitespace(c) || c == ';' || c == '{') {
                break;
            }
            pos++;
        }
        return s.substring(start, pos);
    }

    private void skipQuoted(char quote) throws NginxParseException {
        int startLine = pos;
        pos++;
        while (pos < s.length()) {
            char c = s.charAt(pos);
            if (c == '\\') {
                pos += 2;
                continue;
            }
            if (c == quote) {
                pos++;
                return;
            }
            pos++;
        }
        pos = startLine;
        throw error("unterminated string");
    }

    /** Skips whitespace and {@code #} comments (up to, not including, the line break). */
    private void skipTrivia() {
        while (pos < s.length()) {
            char c = s.charAt(pos);
            if (Character.isWhitespace(c)) {
                pos++;
            } else if (c == '#') {
                while (pos < s.length() && s.charAt(pos) != '\n') {
                    pos++;
                }
            } else {
                return;
            }
        }
    }

    /** Finds the brace that closes a body starting at {@code from}, skipping strings and Lua comments. */
    private int findMatchingBrace(int from) throws NginxParseException {
        int depth = 1;
        int i = from;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '"' || c == '\'') {
                i = skipLuaString(i, c);
                continue;
            }
            if (c == '-' && s.startsWith("--", i)) {
                if (s.startsWith("--[[", i)) {
                    int end = s.indexOf("]]", i + 4);
                    i = end < 0 ? s.length() : end + 2;
                } else {
                    while (i < s.length() && s.charAt(i) != '\n') {
                        i++;
                    }
                }
                continue;
            }
            if (c == '[' && s.startsWith("[[", i)) {
                int end = s.indexOf("]]", i + 2);
                i = end < 0 ? s.length() : end + 2;
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
            i++;
        }
        pos = from;
        throw error("unexpected end of file, expecting \"}\"");
    }

    private int skipLuaString(int start, char quote) {
        int i = start + 1;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\') {
                i += 2;
            } else if (c == quote) {
                return i + 1;
            } else if (c == '\n') {
                return i; // unterminated on this line; let the caller carry on
            } else {
                i++;
            }
        }
        return i;
    }

    private NginxParseException error(String message) {
        int line = 1;
        for (int i = 0; i < Math.min(pos, s.length()); i++) {
            if (s.charAt(i) == '\n') {
                line++;
            }
        }
        return new NginxParseException(message, line);
    }
}

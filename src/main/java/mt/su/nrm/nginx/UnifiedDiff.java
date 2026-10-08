package mt.su.nrm.nginx;

import java.util.ArrayList;
import java.util.List;

/** A plain unified diff of two texts, for showing what an apply will change before it happens. */
public final class UnifiedDiff {

    private static final int CONTEXT = 3;
    /** Above this many lines in the differing middle, fall back to showing the whole middle as replaced. */
    private static final int MAX_MIDDLE = 4000;

    private UnifiedDiff() {
    }

    /** Returns the diff, or an empty string if the texts are equal. */
    public static String diff(String oldPath, String newPath, String oldText, String newText) {
        if (oldText.equals(newText)) {
            return "";
        }
        List<String> a = lines(oldText);
        List<String> b = lines(newText);

        int prefix = 0;
        while (prefix < a.size() && prefix < b.size() && a.get(prefix).equals(b.get(prefix))) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < a.size() - prefix && suffix < b.size() - prefix
                && a.get(a.size() - 1 - suffix).equals(b.get(b.size() - 1 - suffix))) {
            suffix++;
        }
        List<String> midA = a.subList(prefix, a.size() - suffix);
        List<String> midB = b.subList(prefix, b.size() - suffix);

        // Each entry: ' ' context, '-' removed, '+' added.
        List<String> ops = new ArrayList<>();
        for (int i = 0; i < prefix; i++) {
            ops.add(" " + a.get(i));
        }
        ops.addAll(middle(midA, midB));
        for (int i = a.size() - suffix; i < a.size(); i++) {
            ops.add(" " + a.get(i));
        }
        return render(oldPath, newPath, ops);
    }

    private static List<String> lines(String text) {
        if (text.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> result = new ArrayList<>(List.of(text.split("\r?\n", -1)));
        if (result.get(result.size() - 1).isEmpty()) {
            result.remove(result.size() - 1);
        }
        return result;
    }

    /** Longest-common-subsequence diff of the differing middle section. */
    private static List<String> middle(List<String> a, List<String> b) {
        List<String> ops = new ArrayList<>();
        if ((long) a.size() * b.size() > (long) MAX_MIDDLE * MAX_MIDDLE || a.size() > MAX_MIDDLE
                || b.size() > MAX_MIDDLE) {
            a.forEach(l -> ops.add("-" + l));
            b.forEach(l -> ops.add("+" + l));
            return ops;
        }
        int[][] lcs = new int[a.size() + 1][b.size() + 1];
        for (int i = a.size() - 1; i >= 0; i--) {
            for (int j = b.size() - 1; j >= 0; j--) {
                lcs[i][j] = a.get(i).equals(b.get(j)) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        int i = 0;
        int j = 0;
        while (i < a.size() && j < b.size()) {
            if (a.get(i).equals(b.get(j))) {
                ops.add(" " + a.get(i));
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                ops.add("-" + a.get(i++));
            } else {
                ops.add("+" + b.get(j++));
            }
        }
        while (i < a.size()) {
            ops.add("-" + a.get(i++));
        }
        while (j < b.size()) {
            ops.add("+" + b.get(j++));
        }
        return ops;
    }

    private static String render(String oldPath, String newPath, List<String> ops) {
        StringBuilder out = new StringBuilder();
        out.append("--- ").append(oldPath).append('\n').append("+++ ").append(newPath).append('\n');

        int n = ops.size();
        int idx = 0;
        while (idx < n) {
            // Find the next change.
            while (idx < n && ops.get(idx).charAt(0) == ' ') {
                idx++;
            }
            if (idx >= n) {
                break;
            }
            int start = Math.max(0, idx - CONTEXT);
            // Changes closer than 2*CONTEXT lines share a hunk.
            int lastChange = idx;
            int scan = idx + 1;
            while (scan < n && scan - lastChange <= 2 * CONTEXT + 1) {
                if (ops.get(scan).charAt(0) != ' ') {
                    lastChange = scan;
                }
                scan++;
            }
            int end = Math.min(n - 1, lastChange + CONTEXT);

            int oldStart = 1;
            int newStart = 1;
            for (int k = 0; k < start; k++) {
                char c = ops.get(k).charAt(0);
                if (c != '+') {
                    oldStart++;
                }
                if (c != '-') {
                    newStart++;
                }
            }
            int oldCount = 0;
            int newCount = 0;
            StringBuilder body = new StringBuilder();
            for (int k = start; k <= end; k++) {
                String op = ops.get(k);
                char c = op.charAt(0);
                if (c != '+') {
                    oldCount++;
                }
                if (c != '-') {
                    newCount++;
                }
                body.append(op).append('\n');
            }
            out.append("@@ -").append(oldCount == 0 ? oldStart - 1 : oldStart).append(',').append(oldCount)
                    .append(" +").append(newCount == 0 ? newStart - 1 : newStart).append(',').append(newCount)
                    .append(" @@\n").append(body);
            idx = end + 1;
        }
        return out.toString();
    }
}

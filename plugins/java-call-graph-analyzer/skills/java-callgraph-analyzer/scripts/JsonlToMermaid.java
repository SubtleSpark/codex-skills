import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JsonlToMermaid {
    public static void main(String[] args) throws Exception {
        Map<String, String> cli = parseArgs(args);
        Path input = Paths.get(cli.getOrDefault("input", ".tmp/callgraph-java.jsonl"));
        Path output = Paths.get(cli.getOrDefault("output", ".tmp/callgraph-java.mmd"));
        Mode mode = Mode.parse(cli.getOrDefault("mode", "down"));
        List<String> requestedFuncs = splitList(cli.getOrDefault("func", ""));
        List<String> includePrefixes = splitList(cli.getOrDefault("include-prefix", ""));
        int maxDepth = parseMaxDepth(cli.getOrDefault("max-depth", "20"));
        List<String> cutPatterns = splitList(cli.getOrDefault("cut", ""));
        List<String> markPatterns = splitList(cli.getOrDefault("mark", ""));

        List<Edge> edges = filterEdges(readEdges(input), includePrefixes);
        Selection selection = requestedFuncs.isEmpty()
                ? selectAll(edges, cutPatterns, markPatterns)
                : selectFocused(edges, mode, requestedFuncs, maxDepth, cutPatterns, markPatterns);

        String mmd = render(selection);
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        Files.write(output, mmd.getBytes(StandardCharsets.UTF_8));
        System.out.println("Generated Mermaid file: " + output);
    }

    private static String render(Selection selection) {
        StringBuilder mmd = new StringBuilder();
        mmd.append("flowchart LR\n");

        Map<String, String> alias = new LinkedHashMap<>();
        int idx = 0;
        for (String node : selection.nodes) {
            String n = "N" + idx++;
            alias.put(node, n);
            mmd.append("  ").append(n)
                    .append("[\"").append(mermaidLabel(node)).append("\"]")
                    .append(styleClass(node, selection))
                    .append("\n");
        }

        if (!selection.edges.isEmpty()) {
            mmd.append("\n");
        }
        for (Edge edge : selection.edges) {
            String from = alias.get(edge.from);
            String to = alias.get(edge.to);
            if (from != null && to != null) {
                mmd.append("  ").append(from).append(" --> ").append(to).append("\n");
            }
        }

        appendStyleDefinitions(mmd, selection);
        return mmd.toString();
    }

    private static List<Edge> readEdges(Path input) throws Exception {
        List<Edge> edges = new ArrayList<>();

        int lineNumber = 0;
        for (String line : Files.readAllLines(input, StandardCharsets.UTF_8)) {
            lineNumber++;
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String from = jsonStringField(trimmed, "from");
            String to = jsonStringField(trimmed, "to");
            if (from == null || to == null) {
                throw new IllegalArgumentException("Invalid JSONL edge at line " + lineNumber + ": " + line);
            }
            edges.add(new Edge(from, to));
        }
        return edges;
    }

    private static String jsonStringField(String json, String key) {
        Pattern p = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\\\\\"])*)\\\"");
        Matcher m = p.matcher(json);
        return m.find() ? unescape(m.group(1)) : null;
    }

    private static List<Edge> filterEdges(List<Edge> edges, List<String> includePrefixes) {
        if (includePrefixes.isEmpty()) {
            return edges;
        }
        List<Edge> filtered = new ArrayList<>();
        for (Edge edge : edges) {
            if (matchPrefix(edge.from, includePrefixes) && matchPrefix(edge.to, includePrefixes)) {
                filtered.add(edge);
            }
        }
        return filtered;
    }

    private static Selection selectAll(List<Edge> edges, List<String> cutPatterns, List<String> markPatterns) {
        Selection selection = new Selection();
        for (Edge edge : edges) {
            selection.addEdge(edge);
        }
        for (String node : selection.nodes) {
            if (matchPattern(node, cutPatterns)) {
                selection.cutNodes.add(node);
            }
            if (matchPattern(node, markPatterns)) {
                selection.markedNodes.add(node);
            }
        }
        return selection;
    }

    private static Selection selectFocused(
            List<Edge> edges,
            Mode mode,
            List<String> requestedFuncs,
            int maxDepth,
            List<String> cutPatterns,
            List<String> markPatterns) {
        GraphIndexes indexes = GraphIndexes.from(edges);
        List<String> startFuncs = resolveRequestedFuncs(requestedFuncs, indexes.allNodes);
        return mode == Mode.UP
                ? selectUp(indexes.reverse, startFuncs, maxDepth)
                : selectDown(indexes.forward, startFuncs, maxDepth, cutPatterns, markPatterns);
    }

    private static Selection selectDown(
            Map<String, List<Edge>> graph,
            List<String> startFuncs,
            int maxDepth,
            List<String> cutPatterns,
            List<String> markPatterns) {
        Selection selection = new Selection();
        Set<String> visited = new LinkedHashSet<>();
        Deque<QueueItem> queue = new ArrayDeque<>();

        for (String fn : startFuncs) {
            selection.nodes.add(fn);
            selection.entryNodes.add(fn);
            boolean marked = matchPattern(fn, markPatterns);
            if (marked) {
                selection.markedNodes.add(fn);
            }
            queue.add(new QueueItem(fn, 0, marked));
        }

        while (!queue.isEmpty()) {
            QueueItem current = queue.removeFirst();
            boolean isMarked = current.fromMark || matchPattern(current.name, markPatterns);
            if (isMarked) {
                selection.markedNodes.add(current.name);
            }
            if (matchPattern(current.name, cutPatterns)) {
                selection.cutNodes.add(current.name);
                continue;
            }
            if (visited.contains(current.name)) {
                continue;
            }
            visited.add(current.name);
            if (current.depth >= maxDepth) {
                continue;
            }

            List<Edge> outgoing = graph.get(current.name);
            if (outgoing == null) {
                continue;
            }
            for (Edge edge : outgoing) {
                selection.addEdge(edge);
                if (isMarked) {
                    selection.markedNodes.add(edge.to);
                }
                if (!visited.contains(edge.to)) {
                    queue.add(new QueueItem(edge.to, current.depth + 1, isMarked));
                }
            }
        }
        return selection;
    }

    private static Selection selectUp(Map<String, List<Edge>> reverseGraph, List<String> startFuncs, int maxDepth) {
        Selection selection = new Selection();
        Set<String> visited = new LinkedHashSet<>();
        Deque<QueueItem> queue = new ArrayDeque<>();

        for (String fn : startFuncs) {
            selection.nodes.add(fn);
            selection.entryNodes.add(fn);
            queue.add(new QueueItem(fn, 0, false));
        }

        while (!queue.isEmpty()) {
            QueueItem current = queue.removeFirst();
            if (visited.contains(current.name)) {
                continue;
            }
            visited.add(current.name);
            if (current.depth >= maxDepth) {
                continue;
            }

            List<Edge> incoming = reverseGraph.get(current.name);
            if (incoming == null || incoming.isEmpty()) {
                selection.topNodes.add(current.name);
                continue;
            }
            for (Edge edge : incoming) {
                selection.addEdge(edge);
                if (!reverseGraph.containsKey(edge.from)) {
                    selection.topNodes.add(edge.from);
                }
                if (!visited.contains(edge.from)) {
                    queue.add(new QueueItem(edge.from, current.depth + 1, false));
                }
            }
        }
        return selection;
    }

    private static List<String> resolveRequestedFuncs(List<String> requestedFuncs, Set<String> allNodes) {
        List<String> resolved = new ArrayList<>();
        for (String requested : requestedFuncs) {
            if (allNodes.contains(requested)) {
                addIfAbsent(resolved, requested);
                continue;
            }

            List<String> startsWithMatches = new ArrayList<>();
            List<String> containsMatches = new ArrayList<>();
            for (String node : allNodes) {
                if (node.startsWith(requested)) {
                    startsWithMatches.add(node);
                } else if (node.contains(requested)) {
                    containsMatches.add(node);
                }
            }

            List<String> matches = startsWithMatches.isEmpty() ? containsMatches : startsWithMatches;
            if (matches.isEmpty()) {
                System.err.println("Warning: function not found in callgraph: " + requested);
                addIfAbsent(resolved, requested);
            } else {
                for (String match : matches) {
                    addIfAbsent(resolved, match);
                }
            }
        }
        return resolved;
    }

    private static void addIfAbsent(List<String> list, String value) {
        if (!list.contains(value)) {
            list.add(value);
        }
    }

    private static boolean matchPrefix(String methodId, List<String> prefixes) {
        if (prefixes.isEmpty()) {
            return true;
        }
        for (String prefix : prefixes) {
            if (methodId.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchPattern(String methodId, List<String> patterns) {
        for (String pattern : patterns) {
            if (!pattern.isEmpty() && methodId.contains(pattern)) {
                return true;
            }
        }
        return false;
    }

    private static String styleClass(String node, Selection selection) {
        if (selection.entryNodes.contains(node)) {
            return ":::entry";
        }
        if (selection.cutNodes.contains(node)) {
            return ":::cut";
        }
        if (selection.markedNodes.contains(node)) {
            return ":::marked";
        }
        if (selection.topNodes.contains(node)) {
            return ":::top";
        }
        return "";
    }

    private static void appendStyleDefinitions(StringBuilder mmd, Selection selection) {
        if (selection.entryNodes.isEmpty()
                && selection.cutNodes.isEmpty()
                && selection.markedNodes.isEmpty()
                && selection.topNodes.isEmpty()) {
            return;
        }

        mmd.append("\n");
        if (!selection.entryNodes.isEmpty()) {
            mmd.append("  classDef entry fill:#e1bee7,stroke:#7b1fa2,stroke-width:3px,color:#4a148c\n");
        }
        if (!selection.cutNodes.isEmpty()) {
            mmd.append("  classDef cut fill:#ffcccc,stroke:#ff0000,color:#cc0000\n");
        }
        if (!selection.markedNodes.isEmpty()) {
            mmd.append("  classDef marked fill:#fff3cd,stroke:#ff9800,color:#e65100\n");
        }
        if (!selection.topNodes.isEmpty()) {
            mmd.append("  classDef top fill:#e1bee7,stroke:#7b1fa2,stroke-width:3px,color:#4a148c\n");
        }
    }

    private static String mermaidLabel(String methodId) {
        int hash = methodId.indexOf('#');
        int openParen = methodId.indexOf('(', hash + 1);
        int closeParen = methodId.lastIndexOf(')');
        if (hash < 0 || openParen < hash || closeParen < openParen) {
            return escapeLabelText(methodId);
        }

        String ownerAndMethod = methodId.substring(0, openParen);
        String paramsRaw = methodId.substring(openParen + 1, closeParen).trim();
        if (paramsRaw.isEmpty()) {
            return escapeLabelText(ownerAndMethod) + "()";
        }

        List<String> params = splitTopLevelParams(paramsRaw);
        StringBuilder label = new StringBuilder();
        label.append(escapeLabelText(ownerAndMethod)).append("(<br/>");
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) {
                label.append("<br/>");
            }
            label.append(escapeLabelText(params.get(i)));
            if (i < params.size() - 1) {
                label.append(",");
            }
        }
        label.append("<br/>)");
        return label.toString();
    }

    private static List<String> splitTopLevelParams(String paramsRaw) {
        List<String> params = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int angleDepth = 0;
        for (int i = 0; i < paramsRaw.length(); i++) {
            char ch = paramsRaw.charAt(i);
            if (ch == '<') {
                angleDepth++;
            } else if (ch == '>' && angleDepth > 0) {
                angleDepth--;
            }

            if (ch == ',' && angleDepth == 0) {
                addTrimmed(params, current);
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        addTrimmed(params, current);
        return params;
    }

    private static String escapeLabelText(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private static String unescape(String s) {
        StringBuilder out = new StringBuilder();
        boolean escaping = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (escaping) {
                switch (ch) {
                    case '"':
                    case '\\':
                    case '/':
                        out.append(ch);
                        break;
                    case 'n':
                        out.append('\n');
                        break;
                    case 'r':
                        out.append('\r');
                        break;
                    case 't':
                        out.append('\t');
                        break;
                    default:
                        out.append(ch);
                        break;
                }
                escaping = false;
            } else if (ch == '\\') {
                escaping = true;
            } else {
                out.append(ch);
            }
        }
        if (escaping) {
            out.append('\\');
        }
        return out.toString();
    }

    private static List<String> splitList(String s) {
        if (s == null || s.trim().isEmpty()) {
            return Collections.emptyList();
        }

        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int parenDepth = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '(') {
                parenDepth++;
            } else if (ch == ')' && parenDepth > 0) {
                parenDepth--;
            }

            if (ch == ',' && parenDepth == 0) {
                addTrimmed(values, current);
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        addTrimmed(values, current);
        return values;
    }

    private static void addTrimmed(List<String> values, StringBuilder current) {
        String value = current.toString().trim();
        if (!value.isEmpty()) {
            values.add(value);
        }
    }

    private static int parseMaxDepth(String raw) {
        try {
            int value = Integer.parseInt(raw);
            if (value < 0) {
                throw new IllegalArgumentException("max-depth must be >= 0");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid --max-depth: " + raw, e);
        }
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                continue;
            }

            String keyValue = arg.substring(2);
            String key;
            String value = "true";
            int equals = keyValue.indexOf('=');
            if (equals >= 0) {
                key = keyValue.substring(0, equals);
                value = keyValue.substring(equals + 1);
            } else {
                key = keyValue;
            }
            if (equals < 0 && i + 1 < args.length && !args[i + 1].startsWith("--")) {
                value = args[++i];
            }
            out.put(key, value);
        }
        return out;
    }

    private enum Mode {
        DOWN,
        UP;

        static Mode parse(String raw) {
            String normalized = raw == null ? "down" : raw.trim().toLowerCase();
            if ("down".equals(normalized)) {
                return DOWN;
            }
            if ("up".equals(normalized)) {
                return UP;
            }
            throw new IllegalArgumentException("Invalid --mode: " + raw + " (expected down or up)");
        }
    }

    private static final class Edge {
        final String from;
        final String to;

        Edge(String from, String to) {
            this.from = from;
            this.to = to;
        }

        String key() {
            return from + "\u0000" + to;
        }
    }

    private static final class QueueItem {
        final String name;
        final int depth;
        final boolean fromMark;

        QueueItem(String name, int depth, boolean fromMark) {
            this.name = name;
            this.depth = depth;
            this.fromMark = fromMark;
        }
    }

    private static final class Selection {
        final Set<String> nodes = new LinkedHashSet<>();
        final List<Edge> edges = new ArrayList<>();
        final Set<String> edgeKeys = new LinkedHashSet<>();
        final Set<String> entryNodes = new LinkedHashSet<>();
        final Set<String> cutNodes = new LinkedHashSet<>();
        final Set<String> markedNodes = new LinkedHashSet<>();
        final Set<String> topNodes = new LinkedHashSet<>();

        void addEdge(Edge edge) {
            nodes.add(edge.from);
            nodes.add(edge.to);
            if (edgeKeys.add(edge.key())) {
                edges.add(edge);
            }
        }
    }

    private static final class GraphIndexes {
        final Map<String, List<Edge>> forward = new LinkedHashMap<>();
        final Map<String, List<Edge>> reverse = new LinkedHashMap<>();
        final Set<String> allNodes = new LinkedHashSet<>();

        static GraphIndexes from(List<Edge> edges) {
            GraphIndexes indexes = new GraphIndexes();
            for (Edge edge : edges) {
                indexes.allNodes.add(edge.from);
                indexes.allNodes.add(edge.to);
                append(indexes.forward, edge.from, edge);
                append(indexes.reverse, edge.to, edge);
            }
            return indexes;
        }

        private static void append(Map<String, List<Edge>> graph, String key, Edge edge) {
            List<Edge> list = graph.get(key);
            if (list == null) {
                list = new ArrayList<>();
                graph.put(key, list);
            }
            list.add(edge);
        }
    }
}

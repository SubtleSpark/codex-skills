import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JsonlToMermaid {
    public static void main(String[] args) throws Exception {
        Map<String, String> cli = parseArgs(args);
        Path input = Paths.get(cli.getOrDefault("input", ".tmp/callgraph-java.jsonl"));
        Path output = Paths.get(cli.getOrDefault("output", ".tmp/callgraph-java.mmd"));

        List<Edge> edges = readEdges(input);
        Set<String> nodes = extractNodes(edges);

        StringBuilder mmd = new StringBuilder();
        mmd.append("flowchart LR\n");

        Map<String, String> alias = new LinkedHashMap<>();
        int idx = 0;
        for (String node : nodes) {
            String n = "N" + idx++;
            alias.put(node, n);
            mmd.append("  ").append(n).append("[\"").append(escape(node)).append("\"]\n");
        }

        for (Edge edge : edges) {
            String from = alias.get(edge.from);
            String to = alias.get(edge.to);
            if (from != null && to != null) {
                mmd.append("  ").append(from).append(" --> ").append(to).append("\n");
            }
        }

        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        Files.writeString(output, mmd.toString(), StandardCharsets.UTF_8);
        System.out.println("Generated Mermaid file: " + output);
    }

    private static List<Edge> readEdges(Path input) throws Exception {
        List<Edge> edges = new ArrayList<>();
        Pattern p = Pattern.compile("\\{\\s*\\\"from\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\\\\\"])*)\\\"\\s*,\\s*\\\"to\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\\\\\"])*)\\\"\\s*\\}");

        int lineNumber = 0;
        for (String line : Files.readAllLines(input, StandardCharsets.UTF_8)) {
            lineNumber++;
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Matcher m = p.matcher(trimmed);
            if (!m.matches()) {
                throw new IllegalArgumentException("Invalid JSONL edge at line " + lineNumber + ": " + line);
            }
            edges.add(new Edge(unescape(m.group(1)), unescape(m.group(2))));
        }
        return edges;
    }

    private static Set<String> extractNodes(List<Edge> edges) {
        Set<String> nodes = new LinkedHashSet<>();
        for (Edge edge : edges) {
            nodes.add(edge.from);
            nodes.add(edge.to);
        }
        return nodes;
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
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

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) continue;
            String key = arg.substring(2);
            String value = "true";
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                value = args[++i];
            }
            out.put(key, value);
        }
        return out;
    }

    private static final class Edge {
        final String from;
        final String to;

        Edge(String from, String to) {
            this.from = from;
            this.to = to;
        }
    }
}

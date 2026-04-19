import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JsonToMermaid {
    public static void main(String[] args) throws Exception {
        Map<String, String> cli = parseArgs(args);
        Path input = Paths.get(cli.getOrDefault("input", ".tmp/callgraph-java.json"));
        Path output = Paths.get(cli.getOrDefault("output", ".tmp/callgraph-java.mmd"));

        String json = Files.readString(input, StandardCharsets.UTF_8);
        Set<String> nodes = extractNodes(json);
        Set<String[]> edges = extractEdges(json);

        StringBuilder mmd = new StringBuilder();
        mmd.append("flowchart LR\n");

        Map<String, String> alias = new LinkedHashMap<>();
        int idx = 0;
        for (String node : nodes) {
            String n = "N" + idx++;
            alias.put(node, n);
            mmd.append("  ").append(n).append("[\"").append(escape(node)).append("\"]\n");
        }

        for (String[] edge : edges) {
            String from = alias.get(edge[0]);
            String to = alias.get(edge[1]);
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

    private static Set<String> extractNodes(String json) {
        Set<String> nodes = new LinkedHashSet<>();
        Pattern p = Pattern.compile("\\\"id\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
        Matcher m = p.matcher(json);
        while (m.find()) {
            nodes.add(unescape(m.group(1)));
        }
        return nodes;
    }

    private static Set<String[]> extractEdges(String json) {
        Set<String[]> edges = new LinkedHashSet<>();
        Pattern p = Pattern.compile("\\{\\s*\\\"from\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"\\s*,\\s*\\\"to\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"\\s*\\}");
        Matcher m = p.matcher(json);
        while (m.find()) {
            edges.add(new String[]{unescape(m.group(1)), unescape(m.group(2))});
        }
        return edges;
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String unescape(String s) {
        return s.replace("\\\"", "\"").replace("\\\\", "\\");
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
}

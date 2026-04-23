import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PackageDependenciesToMermaid {
    private static final LayerInfo UNKNOWN = new LayerInfo("unknown", "Unknown", "#A0AEC0");
    private static final LayerInfo MIXED = new LayerInfo("mixed", "Mixed", "#CBD5E0");

    public static void main(String[] args) throws Exception {
        Map<String, String> cli = parseArgs(args);
        Path dependencies = Paths.get(cli.getOrDefault("dependencies", ".tmp/class-dependencies-java.jsonl"));
        Path layers = Paths.get(cli.getOrDefault("layers", ".tmp/class-layers-java.jsonl"));
        Path output = Paths.get(cli.getOrDefault("output", ".tmp/package-dependencies-java.mmd"));

        List<ClassEdge> classEdges = readClassEdges(dependencies);
        Map<String, ClassLayer> classLayers = readClassLayers(layers);
        Set<String> knownClasses = new LinkedHashSet<>(classLayers.keySet());

        Graph graph = aggregate(classEdges, classLayers, knownClasses);
        writeMermaid(output, graph);

        System.out.println("Generated package dependency Mermaid: " + output);
        System.out.println("Packages: " + graph.nodes.size());
        System.out.println("Package edges: " + graph.edges.size());
    }

    private static List<ClassEdge> readClassEdges(Path input) throws IOException {
        List<ClassEdge> edges = new ArrayList<>();
        int lineNumber = 0;
        for (String line : Files.readAllLines(input, StandardCharsets.UTF_8)) {
            lineNumber++;
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String from = field(trimmed, "from");
            String to = field(trimmed, "to");
            String kind = field(trimmed, "kind");
            if (from == null || to == null || kind == null) {
                throw new IllegalArgumentException("Invalid class dependency JSONL at line " + lineNumber + ": " + line);
            }
            edges.add(new ClassEdge(from, to, kind));
        }
        return edges;
    }

    private static Map<String, ClassLayer> readClassLayers(Path input) throws IOException {
        Map<String, ClassLayer> layers = new LinkedHashMap<>();
        int lineNumber = 0;
        for (String line : Files.readAllLines(input, StandardCharsets.UTF_8)) {
            lineNumber++;
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String className = field(trimmed, "class");
            String layer = field(trimmed, "layer");
            String label = field(trimmed, "label");
            String color = field(trimmed, "color");
            if (className == null || layer == null || label == null || color == null) {
                throw new IllegalArgumentException("Invalid class layer JSONL at line " + lineNumber + ": " + line);
            }
            layers.put(className, new ClassLayer(className, new LayerInfo(layer, label, color)));
        }
        return layers;
    }

    private static Graph aggregate(
            List<ClassEdge> classEdges,
            Map<String, ClassLayer> classLayers,
            Set<String> knownClasses
    ) {
        Map<String, PackageLayerAggregate> packageLayers = buildPackageLayers(classLayers, knownClasses);
        Set<String> packageNames = new TreeSet<>();
        Map<String, PackageEdge> edges = new TreeMap<>();

        for (ClassEdge classEdge : classEdges) {
            String fromPackage = packageName(classEdge.from, knownClasses);
            String toPackage = packageName(classEdge.to, knownClasses);
            if (fromPackage.equals(toPackage)) {
                continue;
            }

            addClassToPackage(packageLayers, fromPackage, classEdge.from, classLayers.get(classEdge.from));
            addClassToPackage(packageLayers, toPackage, classEdge.to, classLayers.get(classEdge.to));

            packageNames.add(fromPackage);
            packageNames.add(toPackage);

            String key = fromPackage + "\u0000" + toPackage;
            PackageEdge edge = edges.get(key);
            if (edge == null) {
                edge = new PackageEdge(fromPackage, toPackage);
                edges.put(key, edge);
            }
            edge.add(classEdge.kind);
        }

        Set<String> nodePackageNames = new TreeSet<>(packageLayers.keySet());
        nodePackageNames.addAll(packageNames);

        Map<String, PackageNode> nodes = new TreeMap<>();
        for (String packageName : nodePackageNames) {
            nodes.put(packageName, packageNode(packageName, packageLayers.get(packageName)));
        }

        return new Graph(nodes, edges);
    }

    private static Map<String, PackageLayerAggregate> buildPackageLayers(
            Map<String, ClassLayer> classLayers,
            Set<String> knownClasses
    ) {
        Map<String, PackageLayerAggregate> packageLayers = new TreeMap<>();
        for (ClassLayer classLayer : classLayers.values()) {
            String packageName = packageName(classLayer.className, knownClasses);
            addClassToPackage(packageLayers, packageName, classLayer.className, classLayer);
        }
        return packageLayers;
    }

    private static void addClassToPackage(
            Map<String, PackageLayerAggregate> packageLayers,
            String packageName,
            String className,
            ClassLayer classLayer
    ) {
        PackageLayerAggregate aggregate = packageLayers.get(packageName);
        if (aggregate == null) {
            aggregate = new PackageLayerAggregate(packageName);
            packageLayers.put(packageName, aggregate);
        }
        aggregate.add(className, classLayer == null ? UNKNOWN : classLayer.layer);
    }

    private static PackageNode packageNode(String packageName, PackageLayerAggregate aggregate) {
        LayerInfo layer = aggregate == null ? UNKNOWN : aggregate.resolvedLayer();
        return new PackageNode(packageName, layer);
    }

    private static String packageName(String className, Set<String> knownClasses) {
        if (className == null || className.isBlank()) {
            return "(default)";
        }

        String enclosing = enclosingKnownClass(className, knownClasses);
        if (enclosing != null) {
            return packagePrefix(enclosing);
        }
        return packagePrefix(className);
    }

    private static String enclosingKnownClass(String className, Set<String> knownClasses) {
        int dot = className.indexOf('.');
        while (dot >= 0) {
            String candidate = className.substring(0, dot);
            if (knownClasses.contains(candidate)) {
                return candidate;
            }
            dot = className.indexOf('.', dot + 1);
        }
        return knownClasses.contains(className) ? className : null;
    }

    private static String packagePrefix(String className) {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? "(default)" : className.substring(0, dot);
    }

    private static void writeMermaid(Path output, Graph graph) throws IOException {
        StringBuilder mmd = new StringBuilder();
        mmd.append("flowchart LR\n");

        Map<String, String> aliases = new LinkedHashMap<>();
        int idx = 0;
        for (PackageNode node : graph.nodes.values()) {
            String alias = "P" + idx++;
            aliases.put(node.packageName, alias);
            mmd.append("  ").append(alias)
                    .append("[\"")
                    .append(nodeLabel(node))
                    .append("\"]\n");
        }

        if (!graph.edges.isEmpty()) {
            mmd.append("\n");
        }
        for (PackageEdge edge : graph.edges.values()) {
            String from = aliases.get(edge.fromPackage);
            String to = aliases.get(edge.toPackage);
            if (from == null || to == null) {
                continue;
            }
            mmd.append("  ").append(from)
                    .append(" -->|\"")
                    .append(escapeMermaid(edge.label()))
                    .append("\"| ")
                    .append(to)
                    .append("\n");
        }

        if (!graph.nodes.isEmpty()) {
            mmd.append("\n");
            writeClassDefs(mmd, graph);
            writeClassAssignments(mmd, graph, aliases);
        }

        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        Files.writeString(output, mmd.toString(), StandardCharsets.UTF_8);
    }

    private static void writeClassDefs(StringBuilder mmd, Graph graph) {
        Map<String, LayerInfo> styles = new TreeMap<>();
        for (PackageNode node : graph.nodes.values()) {
            styles.putIfAbsent(styleClass(node.layer.id), node.layer);
        }
        for (Map.Entry<String, LayerInfo> entry : styles.entrySet()) {
            LayerInfo layer = entry.getValue();
            mmd.append("  classDef ").append(entry.getKey())
                    .append(" fill:").append(layer.color)
                    .append(",color:").append(textColor(layer.color))
                    .append(",stroke:#1A202C,stroke-width:1px;\n");
        }
    }

    private static void writeClassAssignments(StringBuilder mmd, Graph graph, Map<String, String> aliases) {
        Map<String, List<String>> byStyle = new TreeMap<>();
        for (PackageNode node : graph.nodes.values()) {
            String style = styleClass(node.layer.id);
            List<String> ids = byStyle.get(style);
            if (ids == null) {
                ids = new ArrayList<>();
                byStyle.put(style, ids);
            }
            ids.add(aliases.get(node.packageName));
        }
        for (Map.Entry<String, List<String>> entry : byStyle.entrySet()) {
            mmd.append("  class ")
                    .append(String.join(",", entry.getValue()))
                    .append(" ")
                    .append(entry.getKey())
                    .append(";\n");
        }
    }

    private static String nodeLabel(PackageNode node) {
        return escapeHtml(node.packageName) + "<br/>" + escapeHtml(node.layer.label);
    }

    private static String styleClass(String layerId) {
        String sanitized = layerId.replaceAll("[^A-Za-z0-9_]", "_");
        if (sanitized.isEmpty()) {
            sanitized = "unknown";
        }
        if (Character.isDigit(sanitized.charAt(0))) {
            sanitized = "_" + sanitized;
        }
        return "layer_" + sanitized;
    }

    private static String textColor(String hex) {
        int[] rgb = parseHexColor(hex);
        if (rgb == null) {
            return "#1A202C";
        }
        double luminance = 0.299 * rgb[0] + 0.587 * rgb[1] + 0.114 * rgb[2];
        return luminance > 150 ? "#1A202C" : "#FFFFFF";
    }

    private static int[] parseHexColor(String hex) {
        if (hex == null || !hex.matches("#[0-9A-Fa-f]{6}")) {
            return null;
        }
        return new int[] {
                Integer.parseInt(hex.substring(1, 3), 16),
                Integer.parseInt(hex.substring(3, 5), 16),
                Integer.parseInt(hex.substring(5, 7), 16)
        };
    }

    private static String field(String json, String key) {
        Pattern pattern = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\\\\\"])*)\\\"");
        Matcher matcher = pattern.matcher(json);
        return matcher.find() ? unescape(matcher.group(1)) : null;
    }

    private static String escapeMermaid(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String escapeHtml(String value) {
        return escapeMermaid(value)
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

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                continue;
            }
            String key = arg.substring(2);
            String value = "true";
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                value = args[++i];
            }
            out.put(key, value);
        }
        return out;
    }

    private static final class Graph {
        final Map<String, PackageNode> nodes;
        final Map<String, PackageEdge> edges;

        Graph(Map<String, PackageNode> nodes, Map<String, PackageEdge> edges) {
            this.nodes = nodes;
            this.edges = edges;
        }
    }

    private static final class ClassEdge {
        final String from;
        final String to;
        final String kind;

        ClassEdge(String from, String to, String kind) {
            this.from = from;
            this.to = to;
            this.kind = kind;
        }
    }

    private static final class ClassLayer {
        final String className;
        final LayerInfo layer;

        ClassLayer(String className, LayerInfo layer) {
            this.className = className;
            this.layer = layer;
        }
    }

    private static final class LayerInfo {
        final String id;
        final String label;
        final String color;

        LayerInfo(String id, String label, String color) {
            this.id = id;
            this.label = label;
            this.color = color;
        }
    }

    private static final class PackageLayerAggregate {
        final String packageName;
        final Set<String> classNames = new LinkedHashSet<>();
        final Map<String, LayerInfo> layers = new LinkedHashMap<>();

        PackageLayerAggregate(String packageName) {
            this.packageName = packageName;
        }

        void add(String className, LayerInfo layer) {
            if (classNames.add(className)) {
                layers.putIfAbsent(layer.id, layer);
            }
        }

        LayerInfo resolvedLayer() {
            if (layers.isEmpty()) {
                return UNKNOWN;
            }
            if (layers.size() == 1) {
                return layers.values().iterator().next();
            }
            return MIXED;
        }
    }

    private static final class PackageNode {
        final String packageName;
        final LayerInfo layer;

        PackageNode(String packageName, LayerInfo layer) {
            this.packageName = packageName;
            this.layer = layer;
        }
    }

    private static final class PackageEdge {
        final String fromPackage;
        final String toPackage;
        int count;
        final Set<String> kinds = new TreeSet<>();

        PackageEdge(String fromPackage, String toPackage) {
            this.fromPackage = fromPackage;
            this.toPackage = toPackage;
        }

        void add(String kind) {
            count++;
            kinds.add(kind);
        }

        String label() {
            String countLabel = count == 1 ? "1 dep" : count + " deps";
            if (kinds.isEmpty()) {
                return countLabel;
            }
            return countLabel + " \u00B7 " + String.join(", ", kinds);
        }
    }
}

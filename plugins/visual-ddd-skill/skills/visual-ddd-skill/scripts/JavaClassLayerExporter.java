import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class JavaClassLayerExporter {
    public static void main(String[] args) throws Exception {
        Map<String, String> cli = parseArgs(args);
        Path projectDir = Paths.get(required(cli, "project")).toAbsolutePath().normalize();
        Path output = Paths.get(cli.getOrDefault("output", projectDir.resolve(".tmp/class-layers-java.jsonl").toString()));
        Path layerConfigPath = Paths.get(required(cli, "layer-config")).toAbsolutePath().normalize();
        String classpath = cli.getOrDefault("classpath", "");
        List<String> includePrefixes = splitCsv(cli.getOrDefault("include-prefix", ""));

        List<Path> javaFiles = findJavaFiles(projectDir);
        if (javaFiles.isEmpty()) {
            throw new IllegalArgumentException("No .java files found under: " + projectDir);
        }

        LayerConfig layerConfig = LayerConfig.load(layerConfigPath);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("JDK compiler not available. Please run with a full JDK.");
        }

        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjectsFromPaths(javaFiles);
            List<String> options = new ArrayList<>();
            options.add("-Xlint:none");
            if (!classpath.isBlank()) {
                options.add("-classpath");
                options.add(classpath);
            }

            JavacTask task = (JavacTask) compiler.getTask(null, fileManager, null, options, null, units);
            List<CompilationUnitTree> parsedUnits = new ArrayList<>();
            for (CompilationUnitTree unit : task.parse()) {
                parsedUnits.add(unit);
            }
            task.analyze();

            Trees trees = Trees.instance(task);
            Set<String> sourceTypes = collectSourceTypes(trees, parsedUnits);

            List<String> lines = new ArrayList<>();
            for (String className : sourceTypes) {
                if (!include(className, includePrefixes)) {
                    continue;
                }
                Layer layer = layerConfig.match(className);
                lines.add(classJson(className, layer));
            }

            writeJsonl(output, lines);
            System.out.println("Generated class layer JSONL: " + output);
            System.out.println("Classes: " + lines.size());
        }
    }

    private static Set<String> collectSourceTypes(Trees trees, List<CompilationUnitTree> units) {
        Set<String> sourceTypes = new LinkedHashSet<>();
        TreePathScanner<Void, Void> scanner = new TreePathScanner<Void, Void>() {
            @Override
            public Void visitClass(ClassTree node, Void unused) {
                Element el = trees.getElement(getCurrentPath());
                String typeName = typeName(el);
                if (typeName != null) {
                    sourceTypes.add(typeName);
                }
                return super.visitClass(node, unused);
            }
        };
        for (CompilationUnitTree unit : units) {
            scanner.scan(unit, null);
        }
        return sourceTypes;
    }

    private static List<Path> findJavaFiles(Path root) throws IOException {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream
                    .filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".java"))
                    .sorted(Comparator.naturalOrder())
                    .collect(Collectors.toList());
        }
    }

    private static void writeJsonl(Path output, List<String> lines) throws IOException {
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        String content = lines.isEmpty() ? "" : String.join(System.lineSeparator(), lines) + System.lineSeparator();
        Files.writeString(output, content, StandardCharsets.UTF_8);
    }

    private static String required(Map<String, String> args, String key) {
        String v = args.get(key);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("Missing required argument --" + key);
        }
        return v;
    }

    private static List<String> splitCsv(String s) {
        if (s == null || s.isBlank()) {
            return List.of();
        }
        return Stream.of(s.split(","))
                .map(String::trim)
                .filter(x -> !x.isEmpty())
                .collect(Collectors.toList());
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

    private static String typeName(Element el) {
        if (!(el instanceof TypeElement)) {
            return null;
        }
        TypeElement typeElement = (TypeElement) el;
        String name = typeElement.getQualifiedName().toString();
        return name.isBlank() ? null : name;
    }

    private static boolean include(String typeName, List<String> includePrefixes) {
        if (typeName == null) {
            return false;
        }
        if (includePrefixes.isEmpty()) {
            return true;
        }
        for (String prefix : includePrefixes) {
            if (typeName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static String classJson(String className, Layer layer) {
        return "{\"class\":\"" + escape(className)
                + "\",\"layer\":\"" + escape(layer.id)
                + "\",\"label\":\"" + escape(layer.label)
                + "\",\"color\":\"" + escape(layer.color)
                + "\"}";
    }

    private static String escape(String s) {
        return s
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    static final class LayerConfig {
        private final List<LayerRule> rules;
        private final Layer defaultLayer;

        LayerConfig(List<LayerRule> rules, Layer defaultLayer) {
            this.rules = rules;
            this.defaultLayer = defaultLayer;
        }

        static LayerConfig load(Path path) throws IOException {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            Object parsed = new JsonParser(json).parse();
            Map<String, Object> root = asObject(parsed, "root");

            List<LayerRule> rules = new ArrayList<>();
            Object rawLayers = root.get("layers");
            if (rawLayers != null) {
                for (Object rawLayer : asArray(rawLayers, "layers")) {
                    Map<String, Object> layerObject = asObject(rawLayer, "layer");
                    Layer layer = new Layer(
                            stringValue(layerObject, "id", "unknown"),
                            stringValue(layerObject, "label", stringValue(layerObject, "id", "Unknown")),
                            stringValue(layerObject, "color", "#A0AEC0")
                    );
                    List<Pattern> patterns = new ArrayList<>();
                    Object rawPatterns = layerObject.get("patterns");
                    if (rawPatterns != null) {
                        for (Object rawPattern : asArray(rawPatterns, "patterns")) {
                            String pattern = asString(rawPattern, "pattern");
                            try {
                                patterns.add(Pattern.compile(pattern));
                            } catch (PatternSyntaxException e) {
                                throw new IllegalArgumentException("Invalid layer regex pattern: " + pattern, e);
                            }
                        }
                    }
                    rules.add(new LayerRule(layer, patterns));
                }
            }

            Layer defaultLayer = new Layer("unknown", "Unknown", "#A0AEC0");
            Object rawDefault = root.get("defaultLayer");
            if (rawDefault != null) {
                Map<String, Object> defaultObject = asObject(rawDefault, "defaultLayer");
                defaultLayer = new Layer(
                        stringValue(defaultObject, "id", "unknown"),
                        stringValue(defaultObject, "label", "Unknown"),
                        stringValue(defaultObject, "color", "#A0AEC0")
                );
            }
            return new LayerConfig(rules, defaultLayer);
        }

        Layer match(String className) {
            for (LayerRule rule : rules) {
                if (rule.matches(className)) {
                    return rule.layer;
                }
            }
            return defaultLayer;
        }

        private static Map<String, Object> asObject(Object value, String name) {
            if (value instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> object = (Map<String, Object>) value;
                return object;
            }
            throw new IllegalArgumentException("Expected JSON object for " + name);
        }

        private static List<Object> asArray(Object value, String name) {
            if (value instanceof List) {
                @SuppressWarnings("unchecked")
                List<Object> array = (List<Object>) value;
                return array;
            }
            throw new IllegalArgumentException("Expected JSON array for " + name);
        }

        private static String asString(Object value, String name) {
            if (value instanceof String) {
                return (String) value;
            }
            throw new IllegalArgumentException("Expected JSON string for " + name);
        }

        private static String stringValue(Map<String, Object> object, String key, String fallback) {
            Object value = object.get(key);
            return value == null ? fallback : asString(value, key);
        }
    }

    static final class LayerRule {
        private final Layer layer;
        private final List<Pattern> patterns;

        LayerRule(Layer layer, List<Pattern> patterns) {
            this.layer = layer;
            this.patterns = patterns;
        }

        boolean matches(String className) {
            for (Pattern pattern : patterns) {
                if (pattern.matcher(className).matches()) {
                    return true;
                }
            }
            return false;
        }
    }

    static final class Layer {
        private final String id;
        private final String label;
        private final String color;

        Layer(String id, String label, String color) {
            this.id = id;
            this.label = label;
            this.color = color;
        }
    }

    static final class JsonParser {
        private final String text;
        private int pos;

        JsonParser(String text) {
            this.text = text;
        }

        Object parse() {
            Object value = parseValue();
            skipWhitespace();
            if (pos != text.length()) {
                throw error("Unexpected trailing content");
            }
            return value;
        }

        private Object parseValue() {
            skipWhitespace();
            if (pos >= text.length()) {
                throw error("Unexpected end of JSON");
            }
            char c = text.charAt(pos);
            if (c == '{') {
                return parseObject();
            }
            if (c == '[') {
                return parseArray();
            }
            if (c == '"') {
                return parseString();
            }
            if (text.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (text.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            if (text.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            return parseNumber();
        }

        private Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> object = new LinkedHashMap<>();
            skipWhitespace();
            if (peek('}')) {
                expect('}');
                return object;
            }
            while (true) {
                String key = parseString();
                skipWhitespace();
                expect(':');
                Object value = parseValue();
                object.put(key, value);
                skipWhitespace();
                if (peek('}')) {
                    expect('}');
                    return object;
                }
                expect(',');
            }
        }

        private List<Object> parseArray() {
            expect('[');
            List<Object> array = new ArrayList<>();
            skipWhitespace();
            if (peek(']')) {
                expect(']');
                return array;
            }
            while (true) {
                array.add(parseValue());
                skipWhitespace();
                if (peek(']')) {
                    expect(']');
                    return array;
                }
                expect(',');
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (pos < text.length()) {
                char c = text.charAt(pos++);
                if (c == '"') {
                    return out.toString();
                }
                if (c == '\\') {
                    if (pos >= text.length()) {
                        throw error("Unexpected end in string escape");
                    }
                    char escaped = text.charAt(pos++);
                    switch (escaped) {
                        case '"':
                        case '\\':
                        case '/':
                            out.append(escaped);
                            break;
                        case 'b':
                            out.append('\b');
                            break;
                        case 'f':
                            out.append('\f');
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
                        case 'u':
                            out.append(parseUnicodeEscape());
                            break;
                        default:
                            throw error("Invalid string escape: \\" + escaped);
                    }
                } else {
                    out.append(c);
                }
            }
            throw error("Unterminated string");
        }

        private char parseUnicodeEscape() {
            if (pos + 4 > text.length()) {
                throw error("Incomplete unicode escape");
            }
            String hex = text.substring(pos, pos + 4);
            pos += 4;
            try {
                return (char) Integer.parseInt(hex, 16);
            } catch (NumberFormatException e) {
                throw error("Invalid unicode escape: " + hex);
            }
        }

        private Number parseNumber() {
            int start = pos;
            if (peek('-')) {
                pos++;
            }
            while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
                pos++;
            }
            if (peek('.')) {
                pos++;
                while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
                    pos++;
                }
            }
            if (peek('e') || peek('E')) {
                pos++;
                if (peek('+') || peek('-')) {
                    pos++;
                }
                while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
                    pos++;
                }
            }
            if (start == pos) {
                throw error("Expected JSON value");
            }
            String raw = text.substring(start, pos);
            try {
                if (raw.contains(".") || raw.contains("e") || raw.contains("E")) {
                    return Double.parseDouble(raw);
                }
                return Long.parseLong(raw);
            } catch (NumberFormatException e) {
                throw error("Invalid number: " + raw);
            }
        }

        private void skipWhitespace() {
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                    pos++;
                } else {
                    return;
                }
            }
        }

        private boolean peek(char expected) {
            return pos < text.length() && text.charAt(pos) == expected;
        }

        private void expect(char expected) {
            skipWhitespace();
            if (pos >= text.length() || text.charAt(pos) != expected) {
                throw error("Expected '" + expected + "'");
            }
            pos++;
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at offset " + pos);
        }
    }
}

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class JavaSourceCallGraphExporter {
    public static void main(String[] args) throws Exception {
        Map<String, String> cli = parseArgs(args);
        Path projectDir = Paths.get(required(cli, "project")).toAbsolutePath().normalize();
        Path output = Paths.get(cli.getOrDefault("output", projectDir.resolve(".tmp/callgraph-java.json").toString()));
        String classpath = cli.getOrDefault("classpath", "");
        List<String> includePrefixes = splitCsv(cli.getOrDefault("include-prefix", ""));

        List<Path> javaFiles = findJavaFiles(projectDir);
        if (javaFiles.isEmpty()) {
            throw new IllegalArgumentException("No .java files found under: " + projectDir);
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("JDK compiler not available. Please run with a full JDK.");
        }

        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjectsFromPaths(javaFiles);
            List<String> options = new ArrayList<>();
            options.add("-proc:none");
            options.add("-Xlint:none");
            if (!classpath.isBlank()) {
                options.add("-classpath");
                options.add(classpath);
            }

            JavacTask task = (JavacTask) compiler.getTask(null, fileManager, null, options, null, units);
            Iterable<? extends CompilationUnitTree> parsed = task.parse();
            task.analyze();

            Trees trees = Trees.instance(task);
            CallGraphCollector collector = new CallGraphCollector(trees, includePrefixes);
            for (CompilationUnitTree unit : parsed) {
                collector.scan(unit, null);
            }

            writeJson(output, collector.toJsonMap(projectDir.toString()));
            System.out.println("Generated callgraph JSON: " + output);
            System.out.println("Nodes: " + collector.nodeCount() + ", Edges: " + collector.edgeCount());
        }
    }

    private static List<Path> findJavaFiles(Path root) throws IOException {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream
                    .filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".java"))
                    .sorted(Comparator.naturalOrder())
                    .collect(Collectors.toList());
        }
    }

    private static void writeJson(Path output, Map<String, Object> json) throws IOException {
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        Files.writeString(output, toJson(json), StandardCharsets.UTF_8);
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

    private static String toJson(Object obj) {
        if (obj == null) return "null";
        if (obj instanceof String s) return '"' + escape(s) + '"';
        if (obj instanceof Number || obj instanceof Boolean) return obj.toString();
        if (obj instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                    .map(e -> '"' + escape(String.valueOf(e.getKey())) + '"' + ":" + toJson(e.getValue()))
                    .collect(Collectors.joining(",", "{", "}"));
        }
        if (obj instanceof Iterable<?> it) {
            List<String> items = new ArrayList<>();
            for (Object x : it) items.add(toJson(x));
            return items.stream().collect(Collectors.joining(",", "[", "]"));
        }
        return '"' + escape(String.valueOf(obj)) + '"';
    }

    private static String escape(String s) {
        return s
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    static final class CallGraphCollector extends TreePathScanner<Void, Void> {
        private final Trees trees;
        private final List<String> includePrefixes;
        private final Deque<String> currentMethod = new ArrayDeque<>();
        private final Map<String, Map<String, Object>> nodes = new LinkedHashMap<>();
        private final Set<String> edges = new LinkedHashSet<>();

        CallGraphCollector(Trees trees, List<String> includePrefixes) {
            this.trees = trees;
            this.includePrefixes = includePrefixes;
        }

        @Override
        public Void visitMethod(MethodTree node, Void unused) {
            Element el = trees.getElement(getCurrentPath());
            String methodId = methodId(el);
            if (methodId != null) {
                currentMethod.push(methodId);
                addNode(methodId, el);
            }
            try {
                return super.visitMethod(node, unused);
            } finally {
                if (methodId != null) currentMethod.pop();
            }
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
            if (currentMethod.isEmpty()) {
                return super.visitMethodInvocation(node, unused);
            }
            String caller = currentMethod.peek();
            Element calleeElement = trees.getElement(new TreePath(getCurrentPath(), node.getMethodSelect()));
            String callee = methodId(calleeElement);
            if (callee != null && include(caller) && include(callee)) {
                addNode(callee, calleeElement);
                edges.add(caller + "\u0000" + callee);
            }
            return super.visitMethodInvocation(node, unused);
        }

        int nodeCount() { return nodes.size(); }
        int edgeCount() { return edges.size(); }

        Map<String, Object> toJsonMap(String projectDir) {
            List<Map<String, Object>> edgeList = edges.stream().map(e -> {
                String[] parts = e.split("\u0000", 2);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("from", parts[0]);
                m.put("to", parts[1]);
                return m;
            }).collect(Collectors.toList());

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("tool", "jdk-source-analyzer");
            meta.put("mode", "static-source");
            meta.put("projectDir", projectDir);

            Map<String, Object> root = new LinkedHashMap<>();
            root.put("meta", meta);
            root.put("nodes", new ArrayList<>(nodes.values()));
            root.put("edges", edgeList);
            return root;
        }

        private void addNode(String id, Element el) {
            if (nodes.containsKey(id)) return;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            String owner = ownerName(el);
            m.put("class", owner);
            m.put("method", methodName(el));
            nodes.put(id, m);
        }

        private boolean include(String methodId) {
            if (includePrefixes.isEmpty()) return true;
            for (String prefix : includePrefixes) {
                if (methodId.startsWith(prefix)) return true;
            }
            return false;
        }

        private static String ownerName(Element el) {
            Element encl = el == null ? null : el.getEnclosingElement();
            if (encl instanceof TypeElement t) {
                return t.getQualifiedName().toString();
            }
            return "<unknown>";
        }

        private static String methodName(Element el) {
            if (el instanceof ExecutableElement exe) {
                String params = exe.getParameters().stream()
                        .map(v -> v.asType().toString())
                        .collect(Collectors.joining(","));
                return exe.getSimpleName() + "(" + params + ")";
            }
            return el == null ? "<unknown>" : el.getSimpleName().toString();
        }

        private static String methodId(Element el) {
            if (!(el instanceof ExecutableElement)) {
                return null;
            }
            String owner = ownerName(el);
            if (Objects.equals(owner, "<unknown>")) {
                return null;
            }
            return owner + "#" + methodName(el);
        }
    }
}

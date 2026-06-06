import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class JavaSourceCallGraphExporter {
    public static void main(String[] args) throws Exception {
        Map<String, String> cli = parseArgs(args);
        Path projectDir = Paths.get(required(cli, "project")).toAbsolutePath().normalize();
        Path output = Paths.get(cli.getOrDefault("output", projectDir.resolve(".tmp/callgraph-java.jsonl").toString()));
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
            Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjectsFromFiles(toFiles(javaFiles));
            List<String> options = new ArrayList<>();
            options.add("-Xlint:none");
            if (!isBlank(classpath)) {
                options.add("-classpath");
                options.add(classpath);
            }

            JavacTask task = (JavacTask) compiler.getTask(null, fileManager, null, options, null, units);
            Iterable<? extends CompilationUnitTree> parsed = task.parse();
            task.analyze();

            Trees trees = Trees.instance(task);
            Elements elements = task.getElements();
            CallGraphCollector collector = new CallGraphCollector(trees, elements, includePrefixes);
            for (CompilationUnitTree unit : parsed) {
                collector.scan(unit, null);
            }

            writeJsonl(output, collector.toJsonlLines());
            System.out.println("Generated callgraph JSONL: " + output);
            System.out.println("Edges: " + collector.edgeCount());
            System.out.println("Direct edges: " + collector.directEdgeCount());
            System.out.println("Hierarchy edges: " + collector.hierarchyEdgeCount());
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

    private static List<File> toFiles(List<Path> paths) {
        List<File> files = new ArrayList<>();
        for (Path path : paths) {
            files.add(path.toFile());
        }
        return files;
    }

    private static void writeJsonl(Path output, List<String> lines) throws IOException {
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        String content = lines.isEmpty() ? "" : String.join(System.lineSeparator(), lines) + System.lineSeparator();
        Files.write(output, content.getBytes(StandardCharsets.UTF_8));
    }

    private static String required(Map<String, String> args, String key) {
        String v = args.get(key);
        if (isBlank(v)) {
            throw new IllegalArgumentException("Missing required argument --" + key);
        }
        return v;
    }

    private static List<String> splitCsv(String s) {
        if (isBlank(s)) {
            return Collections.emptyList();
        }
        return Stream.of(s.split(","))
                .map(String::trim)
                .filter(x -> !x.isEmpty())
                .collect(Collectors.toList());
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
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

    private static String edgeJson(GraphEdge edge) {
        return "{\"from\":\"" + escape(edge.from) + "\",\"to\":\"" + escape(edge.to)
                + "\",\"kind\":\"" + escape(edge.kind) + "\"}";
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
        private final Elements elements;
        private final List<String> includePrefixes;
        private final Deque<String> currentMethod = new ArrayDeque<>();
        private final Set<GraphEdge> directEdges = new LinkedHashSet<>();
        private final Map<String, ExecutableElement> directCalleesById = new LinkedHashMap<>();
        private final Map<String, List<MethodRef>> sourceMethodsByName = new LinkedHashMap<>();
        private Set<GraphEdge> cachedHierarchyEdges;

        CallGraphCollector(Trees trees, Elements elements, List<String> includePrefixes) {
            this.trees = trees;
            this.elements = elements;
            this.includePrefixes = includePrefixes;
        }

        @Override
        public Void visitMethod(MethodTree node, Void unused) {
            Element el = trees.getElement(getCurrentPath());
            String methodId = methodId(el);
            if (methodId != null && el instanceof ExecutableElement) {
                TypeElement owner = ownerType(el);
                if (owner != null) {
                    MethodRef method = new MethodRef(methodId, (ExecutableElement) el, owner);
                    addSourceMethodByName(method);
                }
            }
            if (methodId != null) {
                currentMethod.push(methodId);
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
            ExecutableElement executable = executable(calleeElement);
            String callee = methodId(executable);
            if (callee != null && include(caller) && include(callee)) {
                directEdges.add(new GraphEdge(caller, callee, "direct"));
                if (!directCalleesById.containsKey(callee)) {
                    directCalleesById.put(callee, executable);
                }
            }
            return super.visitMethodInvocation(node, unused);
        }

        int edgeCount() { return allEdges().size(); }

        int directEdgeCount() { return directEdges.size(); }

        int hierarchyEdgeCount() { return hierarchyEdges().size(); }

        List<String> toJsonlLines() {
            List<String> lines = new ArrayList<>();
            for (GraphEdge edge : allEdges()) {
                lines.add(edgeJson(edge));
            }
            return lines;
        }

        private Set<GraphEdge> allEdges() {
            Set<GraphEdge> all = new LinkedHashSet<>(directEdges);
            all.addAll(hierarchyEdges());
            return all;
        }

        private Set<GraphEdge> hierarchyEdges() {
            if (cachedHierarchyEdges != null) {
                return cachedHierarchyEdges;
            }

            Set<GraphEdge> edges = new LinkedHashSet<>();
            Map<String, List<MethodRef>> overridesCache = new LinkedHashMap<>();
            for (GraphEdge directEdge : directEdges) {
                ExecutableElement baseMethod = directCalleesById.get(directEdge.to);
                if (baseMethod == null || baseMethod.getKind() == ElementKind.CONSTRUCTOR) {
                    continue;
                }

                String baseId = methodId(baseMethod);
                if (baseId == null || !include(baseId)) {
                    continue;
                }

                List<MethodRef> overrides = overridesCache.get(baseId);
                if (overrides == null) {
                    overrides = findOverrides(baseMethod);
                    overridesCache.put(baseId, overrides);
                }

                for (MethodRef override : overrides) {
                    if (!baseId.equals(override.id) && include(override.id)) {
                        edges.add(new GraphEdge(baseId, override.id, "hierarchy"));
                    }
                }
            }
            cachedHierarchyEdges = edges;
            return edges;
        }

        private List<MethodRef> findOverrides(ExecutableElement baseMethod) {
            List<MethodRef> overrides = new ArrayList<>();
            List<MethodRef> candidates = sourceMethodsByName.get(baseMethod.getSimpleName().toString());
            if (candidates == null) {
                return overrides;
            }

            for (MethodRef candidate : candidates) {
                if (candidate.element.equals(baseMethod)
                        || candidate.element.getKind() == ElementKind.CONSTRUCTOR
                        || !candidate.element.getSimpleName().contentEquals(baseMethod.getSimpleName())) {
                    continue;
                }
                if (elements.overrides(candidate.element, baseMethod, candidate.owner)) {
                    overrides.add(candidate);
                }
            }
            return overrides;
        }

        private void addSourceMethodByName(MethodRef method) {
            String name = method.element.getSimpleName().toString();
            List<MethodRef> methods = sourceMethodsByName.get(name);
            if (methods == null) {
                methods = new ArrayList<>();
                sourceMethodsByName.put(name, methods);
            }
            methods.add(method);
        }

        private boolean include(String methodId) {
            if (includePrefixes.isEmpty()) return true;
            for (String prefix : includePrefixes) {
                if (methodId.startsWith(prefix)) return true;
            }
            return false;
        }

        private static ExecutableElement executable(Element el) {
            return el instanceof ExecutableElement ? (ExecutableElement) el : null;
        }

        private static TypeElement ownerType(Element el) {
            Element encl = el == null ? null : el.getEnclosingElement();
            if (encl instanceof TypeElement) {
                return (TypeElement) encl;
            }
            return null;
        }

        private static String ownerName(Element el) {
            TypeElement owner = ownerType(el);
            if (owner != null) {
                return owner.getQualifiedName().toString();
            }
            return "<unknown>";
        }

        private static String methodName(Element el) {
            if (el instanceof ExecutableElement) {
                ExecutableElement exe = (ExecutableElement) el;
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
            if ("<unknown>".equals(owner)) {
                return null;
            }
            return owner + "#" + methodName(el);
        }
    }

    static final class GraphEdge {
        final String from;
        final String to;
        final String kind;

        GraphEdge(String from, String to, String kind) {
            this.from = from;
            this.to = to;
            this.kind = kind;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof GraphEdge)) return false;
            GraphEdge other = (GraphEdge) o;
            return from.equals(other.from) && to.equals(other.to) && kind.equals(other.kind);
        }

        @Override
        public int hashCode() {
            int result = from.hashCode();
            result = 31 * result + to.hashCode();
            result = 31 * result + kind.hashCode();
            return result;
        }
    }

    static final class MethodRef {
        final String id;
        final ExecutableElement element;
        final TypeElement owner;

        MethodRef(String id, ExecutableElement element, TypeElement owner) {
            this.id = id;
            this.element = element;
            this.owner = owner;
        }
    }
}

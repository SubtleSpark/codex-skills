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
            Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjectsFromPaths(javaFiles);
            List<String> options = new ArrayList<>();
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

            writeJsonl(output, collector.toJsonlLines());
            System.out.println("Generated callgraph JSONL: " + output);
            System.out.println("Edges: " + collector.edgeCount());
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

    private static String edgeJson(String from, String to) {
        return "{\"from\":\"" + escape(from) + "\",\"to\":\"" + escape(to) + "\"}";
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
                edges.add(caller + "\u0000" + callee);
            }
            return super.visitMethodInvocation(node, unused);
        }

        int edgeCount() { return edges.size(); }

        List<String> toJsonlLines() {
            List<String> lines = new ArrayList<>();
            for (String edge : edges) {
                int separator = edge.indexOf('\u0000');
                if (separator < 0) {
                    continue;
                }
                lines.add(edgeJson(edge.substring(0, separator), edge.substring(separator + 1)));
            }
            return lines;
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
            if (encl instanceof TypeElement) {
                TypeElement t = (TypeElement) encl;
                return t.getQualifiedName().toString();
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
}

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.InstanceOfTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;
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
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class JavaClassDependencyExporter {
    public static void main(String[] args) throws Exception {
        Map<String, String> cli = parseArgs(args);
        Path projectDir = Paths.get(required(cli, "project")).toAbsolutePath().normalize();
        Path output = Paths.get(cli.getOrDefault("output", projectDir.resolve(".tmp/class-dependencies-java.jsonl").toString()));
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
            List<CompilationUnitTree> parsedUnits = new ArrayList<>();
            for (CompilationUnitTree unit : task.parse()) {
                parsedUnits.add(unit);
            }
            task.analyze();

            Trees trees = Trees.instance(task);
            Set<String> sourceTypes = collectSourceTypes(trees, parsedUnits);
            Map<CompilationUnitTree, Set<String>> staticImportOwners =
                    collectStaticImportOwners(parsedUnits, sourceTypes, includePrefixes);

            DependencyCollector collector = new DependencyCollector(
                    trees,
                    sourceTypes,
                    staticImportOwners,
                    includePrefixes
            );
            for (CompilationUnitTree unit : parsedUnits) {
                collector.scan(unit, null);
            }

            writeJsonl(output, collector.toJsonlLines());
            System.out.println("Generated class dependency JSONL: " + output);
            System.out.println("Edges: " + collector.edgeCount());
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

    private static Map<CompilationUnitTree, Set<String>> collectStaticImportOwners(
            List<CompilationUnitTree> units,
            Set<String> sourceTypes,
            List<String> includePrefixes
    ) {
        Map<CompilationUnitTree, Set<String>> result = new IdentityHashMap<>();
        for (CompilationUnitTree unit : units) {
            Set<String> owners = new LinkedHashSet<>();
            for (ImportTree importTree : unit.getImports()) {
                if (!importTree.isStatic()) {
                    continue;
                }
                String imported = importTree.getQualifiedIdentifier().toString();
                String owner = staticImportOwner(imported, sourceTypes);
                if (owner != null && include(owner, includePrefixes)) {
                    owners.add(owner);
                }
            }
            result.put(unit, owners);
        }
        return result;
    }

    private static String staticImportOwner(String imported, Set<String> sourceTypes) {
        if (imported.endsWith(".*")) {
            String owner = imported.substring(0, imported.length() - 2);
            return sourceTypes.contains(owner) ? owner : null;
        }

        String candidate = imported;
        while (candidate.contains(".")) {
            candidate = candidate.substring(0, candidate.lastIndexOf('.'));
            if (sourceTypes.contains(candidate)) {
                return candidate;
            }
        }
        return null;
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

    private static String edgeJson(String from, String to, String kind) {
        return "{\"from\":\"" + escape(from) + "\",\"to\":\"" + escape(to) + "\",\"kind\":\"" + escape(kind) + "\"}";
    }

    private static String escape(String s) {
        return s
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
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

    static final class DependencyCollector extends TreePathScanner<Void, Void> {
        private final Trees trees;
        private final Set<String> sourceTypes;
        private final Map<CompilationUnitTree, Set<String>> staticImportOwnersByUnit;
        private final List<String> includePrefixes;
        private final Deque<String> currentClass = new ArrayDeque<>();
        private final Set<String> currentStaticImportOwners = new LinkedHashSet<>();
        private final Set<String> edges = new LinkedHashSet<>();

        DependencyCollector(
                Trees trees,
                Set<String> sourceTypes,
                Map<CompilationUnitTree, Set<String>> staticImportOwnersByUnit,
                List<String> includePrefixes
        ) {
            this.trees = trees;
            this.sourceTypes = sourceTypes;
            this.staticImportOwnersByUnit = staticImportOwnersByUnit;
            this.includePrefixes = includePrefixes;
        }

        @Override
        public Void visitCompilationUnit(CompilationUnitTree node, Void unused) {
            currentStaticImportOwners.clear();
            currentStaticImportOwners.addAll(staticImportOwnersByUnit.getOrDefault(node, Set.of()));
            return super.visitCompilationUnit(node, unused);
        }

        @Override
        public Void visitClass(ClassTree node, Void unused) {
            Element el = trees.getElement(getCurrentPath());
            String from = typeName(el);
            if (from == null) {
                return super.visitClass(node, unused);
            }

            currentClass.push(from);
            try {
                addTypeTree(node.getExtendsClause(), "extends");
                for (Tree implemented : node.getImplementsClause()) {
                    addTypeTree(implemented, "implements");
                }
                return super.visitClass(node, unused);
            } finally {
                currentClass.pop();
            }
        }

        @Override
        public Void visitVariable(VariableTree node, Void unused) {
            Element el = trees.getElement(getCurrentPath());
            ElementKind kind = el == null ? null : el.getKind();

            if (kind == ElementKind.FIELD || kind == ElementKind.ENUM_CONSTANT) {
                addTypeTree(node.getType(), "field");
            } else if (kind == ElementKind.PARAMETER) {
                addTypeTree(node.getType(), "method-param");
            } else if (kind == ElementKind.LOCAL_VARIABLE
                    || kind == ElementKind.RESOURCE_VARIABLE
                    || kind == ElementKind.EXCEPTION_PARAMETER) {
                addTypeTree(node.getType(), "local-var");
            }
            return super.visitVariable(node, unused);
        }

        @Override
        public Void visitMethod(MethodTree node, Void unused) {
            addTypeTree(node.getReturnType(), "method-return");
            for (ExpressionTree thrown : node.getThrows()) {
                addTypeTree(thrown, "throws");
            }
            return super.visitMethod(node, unused);
        }

        @Override
        public Void visitNewClass(NewClassTree node, Void unused) {
            Element el = trees.getElement(getCurrentPath());
            addOwningType(el, "new");
            return super.visitNewClass(node, unused);
        }

        @Override
        public Void visitAnnotation(AnnotationTree node, Void unused) {
            addTypeTree(node.getAnnotationType(), "annotation");
            return super.visitAnnotation(node, unused);
        }

        @Override
        public Void visitTypeCast(TypeCastTree node, Void unused) {
            addTypeTree(node.getType(), "cast");
            return super.visitTypeCast(node, unused);
        }

        @Override
        public Void visitInstanceOf(InstanceOfTree node, Void unused) {
            addTypeTree(node.getType(), "instanceof");
            return super.visitInstanceOf(node, unused);
        }

        @Override
        public Void visitMemberSelect(MemberSelectTree node, Void unused) {
            if ("class".contentEquals(node.getIdentifier())) {
                addTypeTree(node.getExpression(), "class-literal");
            }
            return super.visitMemberSelect(node, unused);
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
            if (node.getMethodSelect().getKind() == Tree.Kind.IDENTIFIER) {
                Element el = trees.getElement(new TreePath(getCurrentPath(), node.getMethodSelect()));
                addStaticImportIfUsed(el);
            }
            return super.visitMethodInvocation(node, unused);
        }

        @Override
        public Void visitIdentifier(IdentifierTree node, Void unused) {
            Element el = trees.getElement(getCurrentPath());
            if (el != null && (el.getKind() == ElementKind.FIELD || el.getKind() == ElementKind.ENUM_CONSTANT)) {
                addStaticImportIfUsed(el);
            }
            return super.visitIdentifier(node, unused);
        }

        int edgeCount() {
            return edges.size();
        }

        List<String> toJsonlLines() {
            List<String> lines = new ArrayList<>();
            for (String edge : edges) {
                String[] parts = edge.split("\u0000", -1);
                if (parts.length != 3) {
                    continue;
                }
                lines.add(edgeJson(parts[0], parts[1], parts[2]));
            }
            return lines;
        }

        private void addTypeTree(Tree tree, String kind) {
            if (tree == null) {
                return;
            }
            TreePath path = new TreePath(getCurrentPath(), tree);
            TypeMirror type = trees.getTypeMirror(path);
            addTypeMirror(type, kind);
        }

        private void addTypeMirror(TypeMirror type, String kind) {
            if (type == null || type.getKind() == TypeKind.NONE || type.getKind() == TypeKind.NULL) {
                return;
            }
            if (type instanceof ArrayType) {
                ArrayType arrayType = (ArrayType) type;
                addTypeMirror(arrayType.getComponentType(), kind);
                return;
            }
            if (type instanceof DeclaredType) {
                DeclaredType declared = (DeclaredType) type;
                Element el = declared.asElement();
                addTypeElement(el, kind);
                for (TypeMirror arg : declared.getTypeArguments()) {
                    addTypeMirror(arg, kind);
                }
                TypeMirror enclosing = declared.getEnclosingType();
                if (enclosing != null && enclosing.getKind() != TypeKind.NONE) {
                    addTypeMirror(enclosing, kind);
                }
                return;
            }
            if (type instanceof TypeVariable) {
                TypeVariable typeVariable = (TypeVariable) type;
                addTypeMirror(typeVariable.getUpperBound(), kind);
                addTypeMirror(typeVariable.getLowerBound(), kind);
                return;
            }
            if (type instanceof WildcardType) {
                WildcardType wildcard = (WildcardType) type;
                addTypeMirror(wildcard.getExtendsBound(), kind);
                addTypeMirror(wildcard.getSuperBound(), kind);
            }
        }

        private void addOwningType(Element el, String kind) {
            Element owner = el == null ? null : el.getEnclosingElement();
            while (owner != null && !(owner instanceof TypeElement)) {
                owner = owner.getEnclosingElement();
            }
            addTypeElement(owner, kind);
        }

        private void addTypeElement(Element el, String kind) {
            String to = typeName(el);
            if (to == null) {
                return;
            }
            addEdge(to, kind);
        }

        private void addStaticImportIfUsed(Element el) {
            if (el == null || !el.getModifiers().contains(Modifier.STATIC)) {
                return;
            }
            Element owner = el.getEnclosingElement();
            String ownerName = typeName(owner);
            if (ownerName != null && currentStaticImportOwners.contains(ownerName)) {
                addEdge(ownerName, "static-import");
            }
        }

        private void addEdge(String to, String kind) {
            if (currentClass.isEmpty()) {
                return;
            }
            String from = currentClass.peek();
            if (from.equals(to)) {
                return;
            }
            if (!sourceTypes.contains(from) || !sourceTypes.contains(to)) {
                return;
            }
            if (!include(from, includePrefixes) || !include(to, includePrefixes)) {
                return;
            }
            edges.add(from + "\u0000" + to + "\u0000" + kind);
        }
    }
}

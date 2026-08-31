package com.umlanalyze.javasidecar;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 解析 Java 源码，抽取声明（类/接口/方法/组件/端点）与关系（CALL/EXTENDS/IMPLEMENTS/CONTAINS）。
 */
final class JavaAnalyzer {

    private static final Map<String, String> HTTP_METHOD_BY_ANNOTATION = Map.of(
            "GetMapping", "GET",
            "PostMapping", "POST",
            "PutMapping", "PUT",
            "DeleteMapping", "DELETE",
            "PatchMapping", "PATCH");

    private final Uam uam = new Uam();
    private final Map<String, String> methodIdByKey = new HashMap<>();
    private final Map<String, String> classIdByKey = new HashMap<>();

    String analyze(Path root) throws IOException {
        List<Module> modules = GradleModuleDiscovery.discover(root);
        configureSymbolResolver(modules);

        List<CompilationUnit> units = new ArrayList<>();
        for (Module module : modules) {
            String moduleId = uam.addNode("module", module.name(), "java",
                    "kind", "gradle-module", "path", module.path().toString());
            for (Path srcDir : module.sourceDirs()) {
                for (Path file : javaFiles(srcDir)) {
                    CompilationUnit cu = parse(file);
                    units.add(cu);
                    extractDeclarations(cu, moduleId);
                }
            }
        }

        for (CompilationUnit cu : units) {
            extractRelations(cu);
        }

        return uam.toJson();
    }

    private void configureSymbolResolver(List<Module> modules) {
        CombinedTypeSolver solver = new CombinedTypeSolver();
        solver.add(new ReflectionTypeSolver());
        for (Module m : modules) {
            for (Path srcDir : m.sourceDirs()) {
                if (Files.isDirectory(srcDir)) {
                    solver.add(new JavaParserTypeSolver(srcDir));
                }
            }
        }
        StaticJavaParser.setConfiguration(
                new ParserConfiguration().setSymbolResolver(new JavaSymbolSolver(solver)));
    }

    private CompilationUnit parse(Path file) {
        try {
            return StaticJavaParser.parse(file);
        } catch (IOException e) {
            throw new RuntimeException("解析失败: " + file, e);
        }
    }

    private List<Path> javaFiles(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    private void extractDeclarations(CompilationUnit cu, String moduleId) {
        String packageName = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString()).orElse("");
        for (ClassOrInterfaceDeclaration decl : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            String fqn = packageName.isEmpty()
                    ? decl.getNameAsString()
                    : packageName + "." + decl.getNameAsString();

            List<MethodDeclaration> methods = decl.getMethods();
            List<Map<String, String>> members = new ArrayList<>();
            for (MethodDeclaration m : methods) {
                members.add(Map.of("name", m.getNameAsString(), "signature", m.getSignature().asString()));
            }

            String classId = uam.addNode("class", decl.getNameAsString(), "java",
                    "kind", decl.isInterface() ? "interface" : "class",
                    "moduleId", moduleId,
                    "members", members);
            classIdByKey.put(fqn, classId);

            String componentType = componentType(decl);
            if (componentType != null) {
                String componentId = uam.addNode("component", decl.getNameAsString(), "java",
                        "componentType", componentType,
                        "classId", classId,
                        "moduleId", moduleId);
                uam.addEdge("CONTAINS", moduleId, componentId);
            }

            String classPath = classMappingPath(decl);
            for (MethodDeclaration m : methods) {
                String methodId = uam.addNode("method", m.getNameAsString(), "java",
                        "ownerClassId", classId,
                        "visibility", m.getAccessSpecifier().asString(),
                        "signature", m.getSignature().asString());
                uam.addEdge("CONTAINS", classId, methodId);

                String key = resolveKey(m);
                if (key != null) {
                    methodIdByKey.put(key, methodId);
                }

                String httpMethod = httpMethod(m);
                if (httpMethod != null) {
                    String path = joinPath(classPath, methodMappingPath(m));
                    uam.addNode("endpoint", httpMethod + " " + path, "java",
                            "httpMethod", httpMethod,
                            "path", path,
                            "methodId", methodId);
                }
            }
        }
    }

    private void extractRelations(CompilationUnit cu) {
        String packageName = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString()).orElse("");

        for (ClassOrInterfaceDeclaration decl : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            String fqn = packageName.isEmpty()
                    ? decl.getNameAsString()
                    : packageName + "." + decl.getNameAsString();
            String srcClassId = classIdByKey.get(fqn);
            if (srcClassId == null) {
                continue;
            }
            for (ClassOrInterfaceType t : decl.getExtendedTypes()) {
                String parentId = resolveTypeId(t);
                if (parentId != null) {
                    uam.addEdge("EXTENDS", srcClassId, parentId);
                }
            }
            for (ClassOrInterfaceType t : decl.getImplementedTypes()) {
                String parentId = resolveTypeId(t);
                if (parentId != null) {
                    uam.addEdge("IMPLEMENTS", srcClassId, parentId);
                }
            }
        }

        for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
            ResolvedMethodDeclaration target = resolveCall(call);
            if (target == null) {
                continue;
            }
            String targetId = methodIdByKey.get(target.getQualifiedSignature());
            if (targetId == null) {
                continue; // 外部/库调用，无本地方法节点
            }
            Optional<MethodDeclaration> owner = call.findAncestor(MethodDeclaration.class);
            if (owner.isEmpty()) {
                continue;
            }
            String srcKey = resolveKey(owner.get());
            String srcId = srcKey == null ? null : methodIdByKey.get(srcKey);
            if (srcId != null) {
                uam.addEdge("CALL", srcId, targetId, "kind", "direct");
            }
        }
    }

    private String resolveTypeId(ClassOrInterfaceType t) {
        try {
            return classIdByKey.get(t.resolve().asReferenceType().getQualifiedName());
        } catch (Exception e) {
            return null;
        }
    }

    private String resolveKey(MethodDeclaration m) {
        try {
            return m.resolve().getQualifiedSignature();
        } catch (Exception e) {
            return null;
        }
    }

    private ResolvedMethodDeclaration resolveCall(MethodCallExpr call) {
        try {
            return call.resolve();
        } catch (Exception e) {
            return null;
        }
    }

    private String componentType(ClassOrInterfaceDeclaration decl) {
        if (decl.getAnnotationByName("RestController").isPresent()
                || decl.getAnnotationByName("Controller").isPresent()) {
            return "controller";
        }
        if (decl.getAnnotationByName("Service").isPresent()) {
            return "service";
        }
        if (decl.getAnnotationByName("Repository").isPresent()) {
            return "repository";
        }
        if (decl.getAnnotationByName("Component").isPresent()) {
            return "component";
        }
        return null;
    }

    private String httpMethod(MethodDeclaration m) {
        for (Map.Entry<String, String> e : HTTP_METHOD_BY_ANNOTATION.entrySet()) {
            if (m.getAnnotationByName(e.getKey()).isPresent()) {
                return e.getValue();
            }
        }
        Optional<AnnotationExpr> requestMapping = m.getAnnotationByName("RequestMapping");
        if (requestMapping.isPresent()) {
            String method = memberValue(requestMapping.get(), "method");
            if (method != null) {
                String upper = method.toUpperCase();
                int idx = upper.lastIndexOf('.');
                return idx >= 0 ? upper.substring(idx + 1) : upper;
            }
        }
        return null;
    }

    private String classMappingPath(ClassOrInterfaceDeclaration decl) {
        return decl.getAnnotationByName("RequestMapping")
                .map(this::annotationPath).orElse("");
    }

    private String methodMappingPath(MethodDeclaration m) {
        for (String name : HTTP_METHOD_BY_ANNOTATION.keySet()) {
            Optional<AnnotationExpr> a = m.getAnnotationByName(name);
            if (a.isPresent()) {
                return annotationPath(a.get());
            }
        }
        return m.getAnnotationByName("RequestMapping")
                .map(this::annotationPath).orElse("");
    }

    private String annotationPath(AnnotationExpr a) {
        String path = memberValue(a, "value");
        if (path != null) {
            return path;
        }
        path = memberValue(a, "path");
        return path != null ? path : "";
    }

    private String memberValue(AnnotationExpr a, String key) {
        if (a instanceof SingleMemberAnnotationExpr sm) {
            return exprString(sm.getMemberValue());
        }
        if (a instanceof NormalAnnotationExpr n) {
            for (MemberValuePair p : n.getPairs()) {
                if (p.getNameAsString().equals(key)) {
                    return exprString(p.getValue());
                }
            }
        }
        return null;
    }

    private String exprString(Expression e) {
        if (e instanceof StringLiteralExpr s) {
            return s.asString();
        }
        return e.toString();
    }

    private String joinPath(String base, String sub) {
        if (base.isEmpty()) {
            return sub.isEmpty() ? "/" : sub;
        }
        if (sub.isEmpty()) {
            return base;
        }
        if (base.endsWith("/") && sub.startsWith("/")) {
            return base + sub.substring(1);
        }
        if (!base.endsWith("/") && !sub.startsWith("/")) {
            return base + "/" + sub;
        }
        return base + sub;
    }
}

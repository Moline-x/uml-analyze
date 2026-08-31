package com.umlanalyze.javasidecar;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("用法: <项目根路径>");
            System.exit(1);
        }
        Path root = Paths.get(args[0]).toAbsolutePath().normalize();

        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();

        int[] seq = {0};
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> javaFiles = walk
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().contains("build/") && !p.toString().contains("build\\"))
                    .toList();
            for (Path file : javaFiles) {
                CompilationUnit cu = StaticJavaParser.parse(file);
                for (ClassOrInterfaceDeclaration decl : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                    String classId = "c" + (++seq[0]);

                    Map<String, Object> cls = new LinkedHashMap<>();
                    cls.put("id", classId);
                    cls.put("type", "class");
                    cls.put("name", decl.getNameAsString());
                    cls.put("language", "java");
                    cls.put("kind", decl.isInterface() ? "interface" : "class");
                    List<Map<String, String>> members = new ArrayList<>();
                    for (MethodDeclaration m : decl.getMethods()) {
                        members.add(Map.of(
                                "name", m.getNameAsString(),
                                "signature", m.getSignature().asString()));
                    }
                    cls.put("members", members);
                    nodes.add(cls);

                    for (MethodDeclaration m : decl.getMethods()) {
                        String methodId = "m" + (++seq[0]);

                        Map<String, Object> method = new LinkedHashMap<>();
                        method.put("id", methodId);
                        method.put("type", "method");
                        method.put("name", m.getNameAsString());
                        method.put("language", "java");
                        method.put("ownerClassId", classId);
                        method.put("signature", m.getSignature().asString());
                        nodes.add(method);

                        Map<String, Object> edge = new LinkedHashMap<>();
                        edge.put("id", "e" + (++seq[0]));
                        edge.put("type", "CONTAINS");
                        edge.put("source", classId);
                        edge.put("target", methodId);
                        edges.add(edge);
                    }
                }
            }
        }

        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("schemaVersion", 1);
        graph.put("nodes", nodes);
        graph.put("edges", edges);

        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        System.out.println(gson.toJson(graph));
    }
}

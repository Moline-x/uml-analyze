package com.umlanalyze.javasidecar;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * UAM（统一分析模型）图构建器。生成节点/边并输出 JSON，见 docs/spec.md §3。
 */
final class Uam {

    private static final Map<String, String> PREFIX = Map.of(
            "module", "mod",
            "component", "comp",
            "class", "cls",
            "method", "mth",
            "endpoint", "ep");

    private final List<Map<String, Object>> nodes = new ArrayList<>();
    private final List<Map<String, Object>> edges = new ArrayList<>();
    private int seq = 0;

    /**
     * 添加节点，返回其 id。kv 为交替的 key/value 对。
     */
    String addNode(String type, String name, String language, Object... kv) {
        String id = PREFIX.getOrDefault(type, "n") + (++seq);
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("type", type);
        node.put("name", name);
        node.put("language", language);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            node.put((String) kv[i], kv[i + 1]);
        }
        nodes.add(node);
        return id;
    }

    void addEdge(String type, String source, String target, Object... kv) {
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("id", "e" + (++seq));
        edge.put("type", type);
        edge.put("source", source);
        edge.put("target", target);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            edge.put((String) kv[i], kv[i + 1]);
        }
        edges.add(edge);
    }

    String toJson() {
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("schemaVersion", 1);
        graph.put("nodes", nodes);
        graph.put("edges", edges);
        return new GsonBuilder().setPrettyPrinting().create().toJson(graph);
    }
}

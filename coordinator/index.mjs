#!/usr/bin/env node
// 协调器：合并 Java/TS 两个 sidecar 的 UAM 输出，做跨边界 HTTP_CALL 关联。
// 用法: node index.mjs <java.json> <ts.json>
import fs from "node:fs";

const [javaPath, tsPath] = process.argv.slice(2);
if (!javaPath || !tsPath) {
  console.error("用法: node index.mjs <java.json> <ts.json>");
  process.exit(1);
}

const java = JSON.parse(fs.readFileSync(javaPath, "utf8").replace(/^\uFEFF/, ""));
const ts = JSON.parse(fs.readFileSync(tsPath, "utf8").replace(/^\uFEFF/, ""));

/** 路径归一化：剥离 query/base、统一路径变量为 {}，供跨边界匹配。 */
function normalizePath(p) {
  let s = String(p);
  try {
    s = new URL(s).pathname;
  } catch {
    // 非完整 URL，原样处理
  }
  s = s.split("?")[0];
  s = s.replace(/:\w+/g, "{}"); // :param
  s = s.replace(/\$\{[^}]*\}/g, "{}"); // ${var}
  s = s.replace(/\{[^}]*\}/g, "{}"); // {var}
  if (!s.startsWith("/")) {
    s = "/" + s;
  }
  s = s.replace(/\/+$/, "");
  return (s || "/").toLowerCase();
}

const buildIdMap = (list, lang) => {
  const m = new Map();
  for (const n of list) {
    m.set(n.id, `${lang}:${n.id}`);
  }
  return m;
};
const javaIdMap = buildIdMap(java.nodes, "java");
const tsIdMap = buildIdMap(ts.nodes, "ts");

const nodes = [];
const edges = [];
let seq = 0;

const remapRef = (idMap, v) => idMap.get(v) ?? v;

const remapNode = (n, idMap) => {
  const out = {};
  for (const [k, v] of Object.entries(n)) {
    if (k === "id") {
      out[k] = idMap.get(v);
    } else if (k.endsWith("Id") && typeof v === "string") {
      out[k] = remapRef(idMap, v);
    } else {
      out[k] = v;
    }
  }
  nodes.push(out);
};
for (const n of java.nodes) {
  remapNode(n, javaIdMap);
}
for (const n of ts.nodes) {
  remapNode(n, tsIdMap);
}

const remapEdge = (e, idMap) => {
  edges.push({
    ...e,
    source: remapRef(idMap, e.source),
    target: remapRef(idMap, e.target),
  });
};
for (const e of java.edges) {
  remapEdge(e, javaIdMap);
}
for (const e of ts.edges) {
  remapEdge(e, tsIdMap);
}

// 跨边界关联：TS http_call → Java endpoint
const endpoints = java.nodes.filter((n) => n.type === "endpoint");
for (const hc of ts.nodes.filter((n) => n.type === "http_call")) {
  const target = endpoints.find(
    (ep) =>
      ep.httpMethod === hc.httpMethod &&
      normalizePath(ep.path) === normalizePath(hc.url),
  );
  if (target) {
    edges.push({
      id: "x" + ++seq,
      type: "HTTP_CALL",
      source: tsIdMap.get(hc.sourceMethodId),
      target: javaIdMap.get(target.id),
      httpMethod: hc.httpMethod,
      url: hc.url,
    });
  }
}

process.stdout.write(JSON.stringify({ schemaVersion: 1, nodes, edges }, null, 2));

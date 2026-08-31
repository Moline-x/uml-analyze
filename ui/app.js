// UML 分析器 前端逻辑：加载 UAM JSON，用 Mermaid 渲染类图/组件图/序列图。
/* global mermaid */

const SAMPLE = {
  schemaVersion: 1,
  nodes: [
    { id: "mod1", type: "module", name: "backend", language: "java" },
    { id: "cls1", type: "class", name: "UserController", language: "java", kind: "class", members: [{ name: "getUser", signature: "getUser(Long)" }] },
    { id: "comp1", type: "component", name: "UserController", language: "java", componentType: "controller", classId: "cls1" },
    { id: "mth1", type: "method", name: "getUser", language: "java", ownerClassId: "cls1", signature: "getUser(Long)" },
    { id: "ep1", type: "endpoint", name: "GET /api/users/{id}", httpMethod: "GET", path: "/api/users/{id}", methodId: "mth1" },
    { id: "cls2", type: "class", name: "UserService", language: "java", kind: "class", members: [{ name: "findById", signature: "findById(Long)" }] },
    { id: "comp2", type: "component", name: "UserService", language: "java", componentType: "service", classId: "cls2" },
    { id: "mth2", type: "method", name: "findById", language: "java", ownerClassId: "cls2", signature: "findById(Long)" },
    { id: "cls3", type: "class", name: "AppService", language: "ts", kind: "class", members: [{ name: "getUser", signature: "getUser()" }] },
    { id: "comp3", type: "component", name: "AppService", language: "ts", componentType: "service", classId: "cls3" },
    { id: "mth3", type: "method", name: "getUser", language: "ts", ownerClassId: "cls3", signature: "getUser()" },
    { id: "hc1", type: "http_call", name: "GET /api/users/1", language: "ts", httpMethod: "GET", url: "/api/users/1", sourceMethodId: "mth3" },
  ],
  edges: [
    { id: "e1", type: "CALL", source: "mth1", target: "mth2" },
    { id: "e2", type: "HTTP_CALL", source: "mth3", target: "ep1", httpMethod: "GET", url: "/api/users/1" },
  ],
};

let uam = SAMPLE;
let currentType = "class";

const diagramEl = document.getElementById("diagram");
const searchEl = document.getElementById("search");
const endpointSelect = document.getElementById("endpointSelect");
const seqPick = document.getElementById("seqPick");
const statusEl = document.getElementById("status");

function nameById(nodes) {
  return new Map(nodes.map((n) => [n.id, n.name]));
}

function matchNames(nodes, term) {
  const t = term.trim().toLowerCase();
  if (!t) return new Set();
  return new Set(
    nodes.filter((n) => n.name && n.name.toLowerCase().includes(t)).map((n) => n.name),
  );
}

function toClassDiagram(nodes, edges, highlight) {
  const classes = nodes.filter((n) => n.type === "class");
  const nm = nameById(nodes);
  const lines = ["classDiagram"];
  if (highlight.size) lines.push("  classDef highlight fill:#ffe08a,stroke:#d69e2e");
  for (const c of classes) {
    lines.push(`  class ${c.name} {`);
    if (c.kind === "interface") lines.push("    <<interface>>");
    for (const m of c.members || []) lines.push(`    +${m.signature}`);
    lines.push("  }");
  }
  for (const e of edges) {
    if (e.type === "EXTENDS" || e.type === "IMPLEMENTS") {
      const arrow = e.type === "EXTENDS" ? "--|>" : "..|>";
      lines.push(`  ${nm.get(e.source)} ${arrow} ${nm.get(e.target)}`);
    }
  }
  for (const n of highlight) {
    if (classes.some((c) => c.name === n)) lines.push(`  class ${n} highlight`);
  }
  return lines.join("\n");
}

function toComponentDiagram(nodes, edges, highlight) {
  const comps = nodes.filter((n) => n.type === "component");
  const methodOwner = new Map(
    nodes.filter((n) => n.type === "method").map((n) => [n.id, n.ownerClassId]),
  );
  const compByClass = new Map(comps.map((c) => [c.classId, c.id]));
  const endpointById = new Map(
    nodes.filter((n) => n.type === "endpoint").map((n) => [n.id, n]),
  );

  const safeId = {};
  comps.forEach((c, i) => (safeId[c.id] = "N" + i));

  const lines = ["flowchart TD"];
  for (const c of comps) {
    lines.push(`  ${safeId[c.id]}[${c.name}]`);
    if (highlight.has(c.name)) lines.push(`  style ${safeId[c.id]} fill:#ffe08a,stroke:#d69e2e`);
  }

  const deps = new Set();
  for (const e of edges) {
    if (e.type === "CALL") {
      const a = compByClass.get(methodOwner.get(e.source));
      const b = compByClass.get(methodOwner.get(e.target));
      if (a && b && a !== b) deps.add(`${a}>${b}`);
    } else if (e.type === "HTTP_CALL") {
      const a = compByClass.get(methodOwner.get(e.source));
      const ep = endpointById.get(e.target);
      const b = ep ? compByClass.get(methodOwner.get(ep.methodId)) : null;
      if (a && b && a !== b) deps.add(`${a}>${b}`);
    }
  }
  for (const d of deps) {
    const [a, b] = d.split(">");
    lines.push(`  ${safeId[a]} --> ${safeId[b]}`);
  }
  return lines.join("\n");
}

function toSequenceDiagram(nodes, edges, endpointId) {
  const methodById = new Map(
    nodes.filter((n) => n.type === "method").map((n) => [n.id, n]),
  );
  const classById = new Map(
    nodes.filter((n) => n.type === "class").map((n) => [n.id, n]),
  );
  const endpoint = nodes.find((n) => n.id === endpointId);
  if (!endpoint) return "sequenceDiagram\n  participant None";
  const entryMethodId = endpoint.methodId;

  const classIds = [];
  const seenClasses = new Set();
  const addClass = (cid) => {
    if (cid && !seenClasses.has(cid)) {
      seenClasses.add(cid);
      classIds.push(cid);
    }
  };
  const messages = [];

  // 入站 HTTP_CALL：FE 调用方 → BE 入口（跨边界消息）
  for (const e of edges) {
    if (e.type !== "HTTP_CALL" || e.target !== endpointId) continue;
    const feClass = methodById.get(e.source)?.ownerClassId;
    const beClass = methodById.get(entryMethodId)?.ownerClassId;
    if (feClass && beClass) {
      addClass(feClass);
      addClass(beClass);
      messages.push({ from: feClass, to: beClass, label: `${e.httpMethod} ${e.url}`, dashed: true });
    }
  }

  // 入口方法所在类确保参与
  addClass(methodById.get(entryMethodId)?.ownerClassId);

  // 沿 CALL 边 BFS（后端调用路径）
  const adj = new Map();
  for (const e of edges) {
    if (e.type !== "CALL") continue;
    if (!adj.has(e.source)) adj.set(e.source, []);
    adj.get(e.source).push(e.target);
  }
  const visited = new Set([entryMethodId]);
  const queue = [entryMethodId];
  while (queue.length) {
    const src = queue.shift();
    const srcClass = methodById.get(src)?.ownerClassId;
    for (const tgt of adj.get(src) || []) {
      const tgtClass = methodById.get(tgt)?.ownerClassId;
      if (!srcClass || !tgtClass) continue;
      addClass(srcClass);
      addClass(tgtClass);
      messages.push({ from: srcClass, to: tgtClass, label: `${methodById.get(tgt).name}()`, dashed: false });
      if (!visited.has(tgt)) {
        visited.add(tgt);
        queue.push(tgt);
      }
    }
  }

  // 参与者按语言分组（FE/BE box）
  const pid = {};
  classIds.forEach((c, i) => (pid[c] = "P" + (i + 1)));
  const feClasses = classIds.filter((c) => classById.get(c)?.language === "ts");
  const beClasses = classIds.filter((c) => classById.get(c)?.language !== "ts");

  const lines = ["sequenceDiagram"];
  if (feClasses.length) {
    lines.push('  box "FE"');
    for (const c of feClasses) lines.push(`    participant ${pid[c]} as ${classById.get(c)?.name ?? c}`);
    lines.push("  end");
  }
  if (beClasses.length) {
    lines.push('  box "BE"');
    for (const c of beClasses) lines.push(`    participant ${pid[c]} as ${classById.get(c)?.name ?? c}`);
    lines.push("  end");
  }
  for (const m of messages) {
    const arrow = m.dashed ? "-->>" : "->>";
    lines.push(`  ${pid[m.from]}${arrow}${pid[m.to]}: ${m.label}`);
  }
  return lines.join("\n");
}

function currentCode() {
  const highlight = matchNames(uam.nodes, searchEl.value);
  if (currentType === "class") return toClassDiagram(uam.nodes, uam.edges, highlight);
  if (currentType === "component") return toComponentDiagram(uam.nodes, uam.edges, highlight);
  if (currentType === "sequence") {
    const ep = endpointSelect.value;
    if (!ep) return "sequenceDiagram\n  participant None";
    return toSequenceDiagram(uam.nodes, uam.edges, ep);
  }
  return "";
}

async function render() {
  const code = currentCode();
  try {
    const { svg } = await mermaid.render("mmd", code);
    diagramEl.innerHTML = svg;
    statusEl.textContent = "";
  } catch (err) {
    diagramEl.textContent = "渲染失败: " + err.message;
    statusEl.textContent = "渲染错误";
  }
}

function populateEndpoints() {
  const endpoints = uam.nodes.filter((n) => n.type === "endpoint");
  endpointSelect.innerHTML = "";
  for (const ep of endpoints) {
    const opt = document.createElement("option");
    opt.value = ep.id;
    opt.textContent = ep.name;
    endpointSelect.appendChild(opt);
  }
  seqPick.style.display = currentType === "sequence" ? "" : "none";
}

function setType(type) {
  currentType = type;
  document.querySelectorAll(".tab").forEach((b) => {
    b.classList.toggle("active", b.dataset.type === type);
  });
  seqPick.style.display = type === "sequence" ? "" : "none";
  render();
}

document.querySelectorAll(".tab").forEach((b) => {
  b.addEventListener("click", () => setType(b.dataset.type));
});

searchEl.addEventListener("input", render);
endpointSelect.addEventListener("change", render);

document.getElementById("loadBtn").addEventListener("click", () => {
  document.getElementById("fileInput").click();
});
document.getElementById("fileInput").addEventListener("change", (ev) => {
  const file = ev.target.files[0];
  if (!file) return;
  const reader = new FileReader();
  reader.onload = () => {
    try {
      uam = JSON.parse(reader.result);
      populateEndpoints();
      render();
      statusEl.textContent = "已加载 " + file.name;
    } catch (err) {
      statusEl.textContent = "JSON 解析失败: " + err.message;
    }
  };
  reader.readAsText(file);
});

function download(filename, content, mime) {
  const a = document.createElement("a");
  a.href = URL.createObjectURL(new Blob([content], { type: mime }));
  a.download = filename;
  a.click();
  URL.revokeObjectURL(a.href);
}

function exportSvg() {
  const svg = diagramEl.querySelector("svg");
  if (!svg) return statusEl.textContent = "无 SVG 可导出";
  const clone = svg.cloneNode(true);
  clone.setAttribute("xmlns", "http://www.w3.org/2000/svg");
  download("diagram.svg", new XMLSerializer().serializeToString(clone), "image/svg+xml");
}

async function exportPng() {
  const svg = diagramEl.querySelector("svg");
  if (!svg) return statusEl.textContent = "无 SVG 可导出";
  const rect = svg.getBoundingClientRect();
  const width = Math.max(1, Math.round(rect.width));
  const height = Math.max(1, Math.round(rect.height));
  const clone = svg.cloneNode(true);
  clone.setAttribute("xmlns", "http://www.w3.org/2000/svg");
  clone.setAttribute("width", width);
  clone.setAttribute("height", height);
  const svgText = new XMLSerializer().serializeToString(clone);
  const img = new Image();
  const url = "data:image/svg+xml;base64," + btoa(unescape(encodeURIComponent(svgText)));
  img.onload = () => {
    const canvas = document.createElement("canvas");
    canvas.width = width;
    canvas.height = height;
    const ctx = canvas.getContext("2d");
    ctx.fillStyle = "#ffffff";
    ctx.fillRect(0, 0, width, height);
    ctx.drawImage(img, 0, 0, width, height);
    download("diagram.png", canvas.toDataURL("image/png"), "image/png");
  };
  img.src = url;
}

document.getElementById("exportSvg").addEventListener("click", exportSvg);
document.getElementById("exportPng").addEventListener("click", exportPng);

mermaid.initialize({ startOnLoad: false, theme: "default" });
populateEndpoints();
render();

import { Project, MethodDeclaration } from "ts-morph";
import fs from "node:fs";
import path from "node:path";

// 精简的 UAM 结构（对齐 docs/spec.md §3；与 java-sidecar 输出同构）
interface UamNode {
  id: string;
  type: string;
  name: string;
  language: "ts";
  [key: string]: unknown;
}
interface UamEdge {
  id: string;
  type: string;
  source: string;
  target: string;
}

const target = process.argv[2];
if (!target) {
  console.error("用法: node dist/index.js <项目根路径>");
  process.exit(1);
}

const abs = path.resolve(target);
const tsconfigPath = path.join(abs, "tsconfig.json");
const project = new Project({
  tsConfigFilePath: fs.existsSync(tsconfigPath) ? tsconfigPath : undefined,
});

// 无 tsconfig 时兜底：手动纳入 src/**/*.ts
if (!fs.existsSync(tsconfigPath)) {
  project.addSourceFilesAtPaths(
    path.join(abs, "src", "**", "*.ts").replaceAll("\\", "/"),
  );
}

const nodes: UamNode[] = [];
const edges: UamEdge[] = [];
let seq = 0;
const nextId = (prefix: string) => `${prefix}${++seq}`;

const signatureText = (m: MethodDeclaration): string =>
  `${m.getName() ?? ""}(${m
    .getParameters()
    .map((p) => p.getText())
    .join(", ")})`;

const scopeText = (m: MethodDeclaration): string =>
  String(m.getScope() ?? "public");

for (const sf of project.getSourceFiles()) {
  for (const cls of sf.getClasses()) {
    const classId = nextId("c");
    nodes.push({
      id: classId,
      type: "class",
      name: cls.getName() ?? "<anonymous>",
      language: "ts",
      kind: "class",
      members: cls.getInstanceMethods().map((m) => ({
        name: m.getName() ?? "<anonymous>",
        signature: signatureText(m),
      })),
    });

    for (const m of cls.getInstanceMethods()) {
      const methodId = nextId("m");
      nodes.push({
        id: methodId,
        type: "method",
        name: m.getName() ?? "<anonymous>",
        language: "ts",
        ownerClassId: classId,
        visibility: scopeText(m),
        signature: signatureText(m),
      });
      edges.push({
        id: nextId("e"),
        type: "CONTAINS",
        source: classId,
        target: methodId,
      });
    }
  }

  for (const iface of sf.getInterfaces()) {
    nodes.push({
      id: nextId("i"),
      type: "class",
      name: iface.getName(),
      language: "ts",
      kind: "interface",
      members: iface.getMethods().map((m) => ({
        name: m.getName(),
        signature: m.getText(),
      })),
    });
  }
}

process.stdout.write(JSON.stringify({ schemaVersion: 1, nodes, edges }, null, 2));

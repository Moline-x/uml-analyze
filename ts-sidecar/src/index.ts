import {
  Project,
  Node,
  MethodDeclaration,
  CallExpression,
} from "ts-morph";
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
  [key: string]: unknown;
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
if (!fs.existsSync(tsconfigPath)) {
  project.addSourceFilesAtPaths(
    path.join(abs, "src", "**", "*.ts").replaceAll("\\", "/"),
  );
}

const nodes: UamNode[] = [];
const edges: UamEdge[] = [];
let seq = 0;
const nextId = (prefix: string) => `${prefix}${++seq}`;

const methodIdByDecl = new Map<MethodDeclaration, string>();

const signatureText = (m: MethodDeclaration): string =>
  `${m.getName() ?? ""}(${m
    .getParameters()
    .map((p) => p.getText())
    .join(", ")})`;

const HTTP_VERBS = ["get", "post", "put", "delete", "patch"];

/** 识别 HttpClient 调用：动词 + URL（类型名或接收者变量名判定）。 */
function httpCallInfo(
  call: CallExpression,
): { method: string; url: string } | null {
  const expr = call.getExpression();
  if (!Node.isPropertyAccessExpression(expr)) {
    return null;
  }
  const verb = expr.getName().toLowerCase();
  if (!HTTP_VERBS.includes(verb)) {
    return null;
  }
  const receiver = expr.getExpression();
  const typeName = receiver.getType().getSymbol()?.getName();
  const receiverText = receiver.getText();
  const isHttp =
    typeName === "HttpClient" || /(^|\.)(http|httpClient)$/.test(receiverText);
  if (!isHttp) {
    return null;
  }
  const arg0 = call.getArguments()[0];
  const url =
    arg0 && Node.isStringLiteral(arg0) ? arg0.getLiteralValue() : (arg0?.getText() ?? "");
  return { method: verb.toUpperCase(), url };
}

/** 把调用点解析回本工程内的方法声明（外部/库调用返回 null）。 */
function resolveCallTarget(call: CallExpression): MethodDeclaration | null {
  const expr = call.getExpression();
  if (Node.isPropertyAccessExpression(expr)) {
    return expr.getNameNode().getDefinitionNodes().find(Node.isMethodDeclaration) ?? null;
  }
  if (Node.isIdentifier(expr)) {
    return expr.getDefinitionNodes().find(Node.isMethodDeclaration) ?? null;
  }
  return null;
}

const moduleId = nextId("mod");
nodes.push({
  id: moduleId,
  type: "module",
  name: path.basename(abs),
  language: "ts",
  kind: "ts-package",
  path: abs,
});

const methodsWithIds: Array<{ m: MethodDeclaration; methodId: string }> = [];

for (const sf of project.getSourceFiles()) {
  for (const cls of sf.getClasses()) {
    const className = cls.getName() ?? "<anonymous>";
    const methods = cls.getInstanceMethods();

    const classId = nextId("cls");
    nodes.push({
      id: classId,
      type: "class",
      name: className,
      language: "ts",
      kind: "class",
      moduleId,
      members: methods.map((m) => ({
        name: m.getName() ?? "<anonymous>",
        signature: signatureText(m),
      })),
    });
    edges.push({ id: nextId("e"), type: "CONTAINS", source: moduleId, target: classId });

    // Angular 组件 / 服务识别
    const decorators = cls.getDecorators().map((d) => d.getName());
    let componentType: string | null = null;
    if (decorators.includes("Component")) {
      componentType = "component";
    } else if (decorators.includes("Injectable")) {
      componentType = "service";
    }
    if (componentType) {
      const componentId = nextId("comp");
      nodes.push({
        id: componentId,
        type: "component",
        name: className,
        language: "ts",
        componentType,
        classId,
        moduleId,
      });
      edges.push({ id: nextId("e"), type: "CONTAINS", source: moduleId, target: componentId });
    }

    for (const m of methods) {
      const methodId = nextId("mth");
      methodIdByDecl.set(m, methodId);
      methodsWithIds.push({ m, methodId });
      nodes.push({
        id: methodId,
        type: "method",
        name: m.getName() ?? "<anonymous>",
        language: "ts",
        ownerClassId: classId,
        visibility: String(m.getScope() ?? "public"),
        signature: signatureText(m),
      });
      edges.push({ id: nextId("e"), type: "CONTAINS", source: classId, target: methodId });
    }
  }

  for (const iface of sf.getInterfaces()) {
    nodes.push({
      id: nextId("cls"),
      type: "class",
      name: iface.getName(),
      language: "ts",
      kind: "interface",
      moduleId,
      members: iface.getMethods().map((m) => ({
        name: m.getName(),
        signature: m.getText(),
      })),
    });
  }
}

// 第二遍：调用图 + HttpClient 调用（此时 methodIdByDecl 已完整）
for (const { m, methodId } of methodsWithIds) {
  m.forEachDescendant((node) => {
    if (!Node.isCallExpression(node)) {
      return;
    }
    const http = httpCallInfo(node);
    if (http) {
      nodes.push({
        id: nextId("hc"),
        type: "http_call",
        name: `${http.method} ${http.url}`,
        language: "ts",
        httpMethod: http.method,
        url: http.url,
        sourceMethodId: methodId,
      });
    }
    const target = resolveCallTarget(node);
    if (target) {
      const targetId = methodIdByDecl.get(target);
      if (targetId) {
        edges.push({
          id: nextId("e"),
          type: "CALL",
          source: methodId,
          target: targetId,
          kind: "direct",
        });
      }
    }
  });
}

process.stdout.write(JSON.stringify({ schemaVersion: 1, nodes, edges }, null, 2));

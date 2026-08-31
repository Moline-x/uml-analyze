# UML 分析器 设计规格

> 本文档是「UML 分析器设计规格 — 地图」的目的地：把各决策票的结论组装为一份实现就绪的规格。术语见根目录 `CONTEXT.md`。

## 1. 概述与目标

一个**本地运行**的工具：给定本地 **Java（Spring Boot / Gradle，含多模块）+ Angular/TypeScript** 仓库，静态分析源码，生成三类图：

- **类图**：类、接口、继承/实现关系、成员。
- **组件图**：框架级组件（Controller / Service / Component 等）及其依赖。
- **序列图**：以某个端点或方法为入口展开的调用路径（静态推断，无运行时追踪）。

同时展示 **REST API 面**（后端端点清单）与 FE↔BE 的跨边界调用关系。

**非目标（Out of scope）**：多用户/鉴权、托管部署、运行时追踪、Java+TS 之外的语言、包图/部署图。

## 2. 系统架构总览

```
┌─────────────── 协调器（Coordinator）───────────────┐
│  · 调度两个 sidecar                                    │
│  · 合并输出为统一分析模型（UAM）                        │
│  · 做跨边界关联（HTTP_CALL）                           │
└──────────┬──────────────────────────┬──────────────┘
           │                          │
   ┌───────▼───────┐          ┌───────▼───────┐
   │ Java sidecar  │          │ TS sidecar    │
   │ (JVM)         │          │ (Node)        │
   │ · JavaParser  │          │ · ts-morph    │
   │ · SymbolSolver│          │ · TS Compiler │
   │ · Gradle TAPI │          │   API         │
   └───────────────┘          └───────────────┘
           └──────────┬───────────────────────┘
                ┌─────▼─────┐
                │ UAM（内存图）│── JSON 快照（存储）
                └─────┬─────┘
                ┌─────▼─────┐
                │ Web UI     │ 类图/组件图/序列图 + 搜索 + 导出
                └───────────┘
```

- **Java sidecar**：JVM 进程。JavaParser 解析 AST；JavaSymbolSolver 做符号/调用解析；Gradle Tooling API 发现多模块结构。
- **TS sidecar**：Node 进程。ts-morph（封装 TypeScript Compiler API）解析 AST 与类型/符号。
- **协调器**：合并两个 sidecar 的抽取结果，统一为 UAM，并执行端点提取与跨边界关联。
- **UI**：交互式 Web，渲染三类图，提供点击导航、搜索、PNG/SVG 导出。

## 3. 统一分析模型（UAM）

统一 FE+BE 图，节点-边结构。存储为 JSON 快照，载入内存图后三类图各取投影。

### 3.1 节点类型

每节点 `{ id, type, name, language, moduleId, ... }`，`language ∈ {java, ts}`。

| type | 说明 | 关键字段 |
|---|---|---|
| `module` | 模块 | `kind`（gradle-module / angular-module / ts-package）、`path` |
| `component` | 组件图单元 | `componentType`（controller / service / component / repository / other）、`classId` |
| `class` | 类 / 接口 / 枚举 / 注解 | `kind`（class / interface / enum / annotation）、`componentId?`、`members[]` |
| `method` | 方法 | `ownerClassId`、`visibility`、`isStatic`、`signature`、`annotations[]` |
| `endpoint` | REST 端点 | `httpMethod`、`path`、`methodId`、`componentId` |

`class.members[]` 为成员签名摘要（供类图渲染），与 `method` 节点并存：类图读 `members`，调用图/序列图读 `method` 节点 + `CALL` 边。

### 3.2 边类型

每边 `{ id, type, source, target, ... }`。

| type | 含义 |
|---|---|
| `CALL` | 方法调用（调用图 / 序列图基础） |
| `HTTP_CALL` | 跨边界调用：TS 方法 → Java 端点 |
| `EXTENDS` | 继承（类图） |
| `IMPLEMENTS` | 实现（类图） |
| `DEPENDS_ON` | 组件间依赖（组件图） |
| `CONTAINS` | 归属：module→class/component、class→method |

### 3.3 JSON 快照

```json
{
  "schemaVersion": 1,
  "nodes": [
    { "id": "m1", "type": "module", "name": "user-service", "language": "java", "kind": "gradle-module", "path": "services/user" },
    { "id": "c1", "type": "class", "name": "UserController", "language": "java", "moduleId": "m1", "kind": "class", "componentId": "comp1",
      "members": [{ "name": "getUser", "signature": "getUser(Long)", "visibility": "public" }] },
    { "id": "comp1", "type": "component", "name": "UserController", "language": "java", "moduleId": "m1", "componentType": "controller", "classId": "c1" },
    { "id": "meth1", "type": "method", "name": "getUser", "language": "java", "ownerClassId": "c1", "visibility": "public", "signature": "getUser(Long)",
      "annotations": ["GetMapping"] },
    { "id": "ep1", "type": "endpoint", "name": "GET /api/users/{id}", "httpMethod": "GET", "path": "/api/users/{id}", "methodId": "meth1", "componentId": "comp1" }
  ],
  "edges": [
    { "id": "e1", "type": "CONTAINS", "source": "m1", "target": "c1" },
    { "id": "e2", "type": "CALL", "source": "meth1", "target": "meth2" },
    { "id": "e3", "type": "HTTP_CALL", "source": "methTs1", "target": "ep1", "httpMethod": "GET", "url": "/api/users/1" }
  ]
}
```

## 4. 解析与提取

### 4.1 Java 侧（JavaParser + JavaSymbolSolver）

- **声明提取**：`CompilationUnit.findAll(...)` 或 `VoidVisitorAdapter` 收集 `ClassOrInterfaceDeclaration` / `MethodDeclaration` / `FieldDeclaration` / `ConstructorDeclaration` / `EnumDeclaration` / `AnnotationDeclaration`，读修饰符、名称、继承（`getExtendedTypes()`）、实现（`getImplementedTypes()`）、注解。
- **调用图**：`MethodCallExpr.resolve()`（依赖 JavaSymbolSolver）解析调用点 → `ResolvedMethodDeclaration`，生成 `CALL` 边。
- **TypeSolver 配置**：每个源码目录一个 `JavaParserTypeSolver`，每个依赖一个 `JarTypeSolver`，加一个 `ReflectionTypeSolver`，合并进 `CombinedTypeSolver`，注入各 `CompilationUnit`。
- **已知局限**：重载/泛型/lambda 歧义可能抛 `MethodAmbiguityException` / `UnsolvedSymbolException`；解析不到时跳过并记录，不中断整体抽取。

### 4.2 Gradle 多模块发现（独立机制）

JavaParser 无法解析 Groovy/Kotlin DSL，故用 **Gradle Tooling API**：`GradleConnector → ProjectConnection → getModel(GradleProject.class)`，读取 `getRootProject()` / `getChildren()` / `getSourceDirectories()`，得到项目树与源码目录。这是唯一能正确还原 `projectDir`/`buildFileName` 覆盖、条件 `include`、`buildSrc` 等复杂场景的机制。

### 4.3 TS 侧（ts-morph）

- **声明提取**：`sourceFile.getClasses()` / `getInterfaces()` / `getEnums()` 等，读继承（`getExtends()` / `getImplements()`）与成员。
- **调用图**：遍历 `CallExpression` / `PropertyAccessExpression`，用 `getType()` / `getSymbol()` / `findReferences()` / `getDefinitions()` 解析调用目标，生成 `CALL` 边。
- **模块/导入图**：`getImportDeclarations()`、`getReferencingSourceFiles()` / `getReferencedSourceFiles()` 构建导入/引用关系。
- **Angular 识别（按约定）**：`@Component` → 组件；`@Injectable` → 服务；依赖注入边取构造器参数 / `inject(...)` 调用。

### 4.4 端点提取

**Java 侧**：
1. 类级 `@RestController` / `@Controller` → 标为 controller 组件。
2. 方法级 `@GetMapping` / `@PostMapping` / `@PutMapping` / `@DeleteMapping` / `@PatchMapping` / `@RequestMapping` → 记为端点。
3. `path = 类级 @RequestMapping path + 方法级 path`（无类级则仅方法级）。
4. HTTP 方法：注解名映射（GetMapping→GET 等），或 `@RequestMapping(method=...)` 的 `method` 成员。

**TS 侧**：
1. 遍历 `CallExpression` / `PropertyAccessExpression`。
2. 用类型系统把调用目标解析回 `HttpClient`（`@angular/common/http`）。
3. 提取动词（`get`/`post`/`put`/`delete`/`patch`）与 URL（首个字符串参数）。

### 4.5 跨边界关联（FE↔BE）

- 关联键 = 规范化后的 `method + path`。
- 规范化：路径变量统一归一为 `{param}`（Java `{id}` 与 TS `${id}` / 字符串拼接统一）；剥离 query string；剥离 base URL 前缀（如 `environment.apiUrl`）。
- 匹配成功 → 生成 `HTTP_CALL` 边（TS 方法 → Java 端点）。
- 匹配失败 → 端点标 `unresolved`（BE 仍可作为序列图入口，只是无 FE 入边）。

## 5. 序列图生成策略

- **入口**：用户选定一个端点或公共方法。
- **展开**：沿 `CALL` 边 BFS/DFS，生成参与者（类/组件）+ 消息（调用边）。
- **深度**：默认展开到叶子（无更多 `CALL`）；同一方法去重——只展开一次，再次命中以回指/折叠标记。
- **分支**：静态分析无运行时，条件 / 循环 / switch **全取**（所有可达调用都纳入，不区分运行时分支）；循环以 `loop` 片段表达。
- **跨模块 / 跨边界**：Java 模块间调用自然展开；`HTTP_CALL` 表示为跨生命线分组（FE→BE）的消息。
- **多态 / 接口**：`resolve()` 能解析到具体实现则展开；解析不到（接口/抽象方法）则停止并标 `<<abstract>>`。

## 6. 图形渲染与布局

- **选型**：渲染统一用 **Mermaid**（纯前端）：
  - 类图 → `classDiagram`
  - 序列图 → `sequenceDiagram`
  - 组件图 → `flowchart`（节点=组件，边=依赖）
- **布局**：默认 Mermaid 自带（dagre）；类图/组件图布局不满时切 Mermaid 的 ELK renderer（`flowchart.defaultRenderer: elk`）。序列图布局 Mermaid 自带，不自建。
- **导出**：SVG 直接序列化（Mermaid 原生）；PNG 走 `mermaid-cli`（本地 Node 调用，工具本身即本地运行）。
- **交互**：Mermaid click 事件做点击导航（元素 → 对应代码/实体）；全局搜索框做过滤/高亮。深交互（拖拽编辑）不在范围。
- **回退**：若后续需「深度可编辑」，再评估 JointJS（当前不引入）。

## 7. 存储与数据流

- **数据流**：`parse（两个 sidecar）→ merge（协调器）→ UAM（内存图）→ JSON 快照 → 渲染`。
- **JSON 快照**：`{ schemaVersion, nodes[], edges[] }`，落盘为分析产物，可重复载入内存图而不必重跑解析。
- **内存图**：三类图在内存图上各取投影（类图取 `class`+`EXTENDS/IMPLEMENTS`，组件图取 `component`+`DEPENDS_ON`，序列图取 `method`+`CALL/HTTP_CALL`）。

## 8. 交互式 UI 与导出

- **三类图视图**：切换类图 / 组件图 / 序列图。
- **点击导航**：点击图中元素跳转到对应源码位置/实体详情。
- **搜索**：全局搜索类 / 方法 / 端点，命中后高亮并定位。
- **导出**：PNG / SVG。

## 9. 非功能需求

- **性能**：解析为离线批处理；Gradle Tooling API 会启动 Gradle daemon，需容忍首次解析较慢。
- **错误处理**：符号解析失败、`resolve()` 歧义等按「跳过并记录」处理，不中断整体抽取。
- **增量重新解析**：变更检测尚未指定（留待后续，见地图 Not yet specified）。
- **多项目对比**：尚未指定（留待后续）。
- **范围外**：多用户/鉴权、托管部署、运行时追踪、Java+TS 之外语言、包图/部署图。

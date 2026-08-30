# JavaParser + JavaSymbolSolver 能力调研，以及 Gradle 多模块结构发现机制

> 调研目标：回答两个问题——(1) JavaParser + JavaSymbolSolver 能从 Java 源码中提取什么（Spring REST 端点、接口、类、方法调用图）；(2) 如何发现 Gradle 多模块项目结构（注意 settings.gradle / build.gradle 是 Groovy/Kotlin DSL，JavaParser 无法解析，需要单独机制）。
>
> 所有结论均来自官方文档与源码仓库等一手来源，文末附来源列表。

---

## 1. JavaParser 是什么

JavaParser 是一个把 Java 源代码解析为抽象语法树（AST）的开源库，官网定位为「分析（Analyse）、转换（Transform）、生成（Generate）」三件事。[^jp-site]

关键事实（来自官方 README）：[^jp-readme]

- 它是一个 **Java 1.0 – Java 25 的解析器**，即只解析 Java 语言，不能解析 Groovy、Kotlin、XML 等。
- 解析入口得到根节点 `CompilationUnit`。
- 分三个 artifact：
  - `javaparser-core`：AST 与遍历/改写能力；
  - `javaparser-symbol-solver-core`：符号解析（JavaSymbolSolver 已并入 JavaParser），依赖它等于同时引入 core；
  - `javaparser-core-serialization`：自 3.6.17 起可将 AST 序列化为 JSON。
- 最新版本 3.28.2（截至调研时）。

> 结论：AST 提取用 JavaParser，语义解析（调用点→声明、类型求解）用 JavaSymbolSolver（`javaparser-symbol-solver-core`）。

---

## 2. 能从 Java 源码提取什么

### 2.1 类、接口、方法、字段等声明

JavaParser 为 Java 语言的各种声明提供了对应 AST 节点，例如 `ClassOrInterfaceDeclaration`、`MethodDeclaration`、`FieldDeclaration`、`ConstructorDeclaration`、`EnumDeclaration`、`AnnotationDeclaration` 等。[^jp-readme][^jp-book]

- 可通过 `CompilationUnit.findAll(ClassOrInterfaceDeclaration.class)` 等按类型批量收集，或用 `VoidVisitorAdapter`（覆写 `visit(MethodCallExpr, …)` 等）做访问者遍历。[^jp-site][^jp-issue-4762]
- 每个节点可读取修饰符、名称、`getRange()` 行号、注解、继承（`getExtendedTypes()`）/实现（`getImplementedTypes()`）关系等。[^jp-site]

对 UML 分析而言，这些足够提取「类 / 接口 / 方法 / 字段 / 继承实现关系 / 方法签名」。

### 2.2 注解与 Spring REST 端点

JavaParser 把注解统一表示为 `AnnotationExpr`，有三个具体子类（源码见 javaparser-core 的 `ast/expr` 包）：[^ann-single][^ann-normal]

| 子类 | 对应 Java 语法 | 例 | 读取方式 |
|---|---|---|---|
| `MarkerAnnotationExpr` | 无成员注解 | `@RestController` | 只有名称，无值 |
| `SingleMemberAnnotationExpr` | 单值注解 | `@GetMapping("/users")` | `getMemberValue()` |
| `NormalAnnotationExpr` | 键值对注解 | `@RequestMapping(value="/x", method=RequestMethod.GET)` | `getPairs()`（`MemberValuePair`） |

因此 Spring 端点提取路径为：

1. 用 `ClassOrInterfaceDeclaration.getAnnotations()` / `MethodDeclaration.getAnnotations()` 拿到注解列表；
2. 按注解名匹配：类级 `@RestController`/`@Controller`/`@RequestMapping`，方法级 `@GetMapping`/`@PostMapping`/`@PutMapping`/`@DeleteMapping`/`@PatchMapping`/`@RequestMapping` 等；
3. 读值：路径来自 `SingleMemberAnnotationExpr` 的 `memberValue` 或 `NormalAnnotationExpr` 中 key 为 `value`/`path` 的 `MemberValuePair`；HTTP 方法来自 `method=…` 成员。

> 注意：**JavaParser 不内置任何 Spring 语义**，它只提供注解的 AST 表示；「识别 REST 端点」这一步需要我们按注解名与属性值自行匹配。类级 `@RequestMapping` 路径与方法的相对路径需要拼接。

### 2.3 方法调用图（call graph）

- 方法调用在 AST 中是 `MethodCallExpr` 节点，它实现了 `Resolvable<ResolvedMethodDeclaration>`。[^methodcall]
- 配合 JavaSymbolSolver，调用 `MethodCallExpr.resolve()` 可把调用点解析到 `ResolvedMethodDeclaration`（含声明位置、所属类型、参数类型等），从而构建「调用点 → 被调用方法声明」的边，形成调用图。[^jss-readme][^methodcall]
- 官方推荐用法示例：[^jss-readme]

  ```java
  Node node = <解析源码得到的节点>;
  Type typeOfTheNode = JavaParserFacade.get(typeSolver).getType(node);
  ```

- 可据此求表达式类型、验证类型、追踪符号用法（字段/局部变量引用）等。[^jss-readme]

---

## 3. JavaSymbolSolver 的配置（符号解析必需）

JavaSymbolSolver 依赖一组 `TypeSolver` 来定位代码引用的类型，官方 README 列出四种：[^jss-readme]

- `JavaParserTypeSolver`：在某个**源码目录**中查找类型；
- `JarTypeSolver`：在 **JAR 文件**中查找类型；
- `ReflectionTypeSolver`：用反射查找类型（用于 `java`/`javax` 包，如 `Object`）；
- `CombinedTypeSolver`：组合多个 TypeSolver。

典型做法：为每个源码目录建一个 `JavaParserTypeSolver`、每个依赖建一个 `JarTypeSolver`、再加一个 `ReflectionTypeSolver`，全部放进 `CombinedTypeSolver`，然后注入 `JavaSymbolSolver` 到各个 `CompilationUnit`。[^jss-readme][^jss-javadoc]

局限（已知问题）：重载方法、泛型与 lambda 推断存在歧义时可能抛 `MethodAmbiguityException` / `UnsolvedSymbolException`；符号解析需要依赖（classpath）尽量齐全。[^jss-readme][^jp-issue-4814]

---

## 4. Gradle 多模块结构发现（独立机制）

### 4.1 为什么 JavaParser 不行

- Gradle 的多模块结构定义在 `settings.gradle(.kts)` 中，构建逻辑在 `build.gradle(.kts)` 中；这些是 **Groovy DSL / Kotlin DSL**，不是 Java 源码。[^gradle-multiproject]
- JavaParser 只是「Java 1.0 – Java 25 的解析器」，无法解析 Groovy/Kotlin。[^jp-readme]

**结论：Gradle 项目结构发现必须使用独立于 JavaParser 的机制。**

### 4.2 结构定义在哪里（知识背景）

- 一个多项目构建 = 根项目（root project）+ 若干子项目（subproject），全部在单个 `settings.gradle(.kts)` 中声明，通过 `include()` 加入。[^gradle-multiproject]
- `include('app', 'core', 'util')`；project path 默认映射到相对物理目录（如 `services:api` → `./services/api`）。[^gradle-multiproject]
- 可用 `ProjectDescriptor` 修改子项目的 `name`、`projectDir`、`buildFileName`（即子项目目录/构建文件名可被覆盖）。[^gradle-multiproject]
- 命令 `./gradlew -q projects` 可打印层次结构。[^gradle-organizing]

### 4.3 可选机制对比

| 机制 | 做法 | 优点 | 缺点 |
|---|---|---|---|
| **Gradle Tooling API（推荐）** | `GradleConnector` → `ProjectConnection` → `getModel(GradleProject.class)`，读取 `getRootProject()`/`getChildren()`/`getSourceDirectories()`/依赖等 | 版本无关、自动采用 wrapper 指定的 Gradle 版本、由 Gradle 自己求值 settings 脚本，最健壮 | 需要引入 `org.gradle:gradle-tooling-api`，运行时会起 Gradle daemon |
| 解析 `settings.gradle` 的 Groovy AST / `.kts` 的 Kotlin AST | 用 Groovy 解析器 / Kotlin 编译器解析 DSL 文本，提取 `include()` 调用 | 不依赖 Gradle 运行时 | 需自己复制 Gradle DSL 语义（include 参数、projectDir/buildFileName 覆盖、条件 include、buildSrc 等），脆弱 |
| 执行 `./gradlew -q projects` 并解析输出 | 调 Gradle 命令解析文本输出 | 简单 | 依赖 wrapper/安装、会执行配置阶段、输出格式非结构化 |

官方 Tooling API 文档明确支持「查询构建详情，包括**项目层次结构**、项目依赖、源码目录以及每个项目的任务」，且「以版本无关的方式运行」「识别 Gradle wrapper 并默认使用与 wrapper 相同的 Gradle 版本」。[^gradle-tooling]

`GradleProject` 模型支持作为 `getModel` 目标获取，可拿到项目树（root + children）。[^gradle-getmodel]

### 4.4 注意

- 复杂场景（`projectDir`/`buildFileName` 被覆盖、条件/动态 `include`、`includeBuild` 复合构建、`buildSrc`）只有 **Tooling API**（让 Gradle 自己求值脚本）才能正确、完整地还原结构；自研 Groovy/Kotlin 文本解析只适合简单、规范的项目。[^gradle-multiproject][^gradle-organizing]

---

## 5. 来源

[^jp-site]: JavaParser 官网，https://javaparser.org/
[^jp-readme]: JavaParser 官方仓库 README（含「Java 1.0 - Java 25 Parser」、模块划分、JavaSymbolSolver 并入说明、3.28.2 版本），https://github.com/javaparser/javaparser
[^jp-book]: 《JavaParser: Visited》（官方书籍预览版）：What is JavaParser / is not a symbol solver 等说明，https://files.gitter.im/javaparser/javaparserbook/ce0K/javaparservisited-preview.pdf
[^jp-issue-4762]: JavaParser 仓库 Issue #4762（官方协作人说明用 `VoidVisitorAdapter.visit(MethodCallExpr,…)` 检测方法调用、需配置 symbol resolver），https://github.com/javaparser/javaparser/issues/4762
[^jp-issue-4814]: JavaParser 仓库 Issue #4814（符号解析的重载歧义 `MethodAmbiguityException` 案例），https://github.com/javaparser/javaparser/issues/4814
[^ann-single]: `SingleMemberAnnotationExpr.java` 源码（`getMemberValue()`），https://github.com/javaparser/javaparser/blob/master/javaparser-core/src/main/java/com/github/javaparser/ast/expr/SingleMemberAnnotationExpr.java
[^ann-normal]: `NormalAnnotationExpr.java` 源码（`getPairs()` / `MemberValuePair`），https://github.com/javaparser/javaparser/blob/master/javaparser-core/src/main/java/com/github/javaparser/ast/expr/NormalAnnotationExpr.java
[^methodcall]: `MethodCallExpr.java` 源码（`implements Resolvable<ResolvedMethodDeclaration>`），https://github.com/javaparser/javaparser/blob/master/javaparser-core/src/main/java/com/github/javaparser/ast/expr/MethodCallExpr.java
[^jss-readme]: JavaSymbolSolver 官方 README（已并入 JavaParser；说明「resolve method calls」、四种 TypeSolver、`JavaParserFacade.getType` 用法），https://github.com/javaparser/javasymbolsolver
[^jss-javadoc]: `JavaSymbolSolver` Javadoc（`inject` 将 resolver 注入 CompilationUnit），https://www.javadoc.io/doc/com.github.javaparser/javaparser-symbol-solver-core/3.6.10/com/github/javaparser/symbolsolver/JavaSymbolSolver.html
[^gradle-tooling]: Gradle 官方文档「Tooling API」（查询项目层次结构/依赖/源码目录；版本无关；识别 wrapper），https://docs.gradle.org/current/userguide/tooling_api.html
[^gradle-getmodel]: Gradle 官方文档 `BuildController.getModel`（支持 `GradleProject` 模型），https://docs.gradle.org/current/kotlin-dsl/gradle/org.gradle.tooling/-build-controller/get-model.html
[^gradle-multiproject]: Gradle 官方文档「Multi-Project Builds」（`settings.gradle(.kts)`、`include()`、`ProjectDescriptor`、project path 映射），https://docs.gradle.org/current/userguide/multi_project_builds.html
[^gradle-organizing]: Gradle 官方文档「Structuring and Organizing Gradle Projects」（root project / subprojects / settings file / `gradlew -q projects`），https://docs.gradle.org/current/userguide/organizing_gradle_projects.html

# ts-morph / TypeScript Compiler API 能力调研

> 调研目标：明确 ts-morph 与底层 TypeScript Compiler API 能从 Angular/TypeScript 源码中提取哪些信息（Angular 组件/服务、HttpClient 调用、TS 接口、方法调用图、模块/导入图），作为后续 UML 分析工具选型与实现的依据。
>
> 调研原则：只采用一手来源（官方文档、官方源码仓库），逐条标注出处。

## 1. 概述

- **ts-morph** 是 TypeScript Compiler API 的一层封装。官方定位是“用于静态分析和程序化代码修改的 TypeScript Compiler API 封装（TypeScript Compiler API wrapper for static analysis and programmatic code changes）”，提供“更简单的方式去程序化地导航和修改 TypeScript/JavaScript 代码”。[来源：ts-morph GitHub README](https://github.com/dsherret/ts-morph)
- 官方文档首页直接说明其目的：**“Setup, navigation, and manipulation of the TypeScript AST can be a challenge. This library wraps the TypeScript compiler API so it's simple.”**（安装、导航与操纵 TypeScript AST 可能很困难，本库封装了 TypeScript 编译器 API，使其变得简单）。[来源：ts-morph.com](https://ts-morph.com/)
- 因此，ts-morph 的能力本质上**继承自 TypeScript Compiler API**——凡是 TS 编译器能解析出的信息（语法 AST + 类型/符号），ts-morph 都能以更友好的 API 访问到。

> ⚠️ 版本注意：TypeScript 官方 Wiki 明确提示，当前 Compiler API 文档描述的是 **TypeScript 6.0 及更早版本**，并预告 **TypeScript 7.1 将拥有完全不同的 API**。[来源：Using the Compiler API](https://github.com/microsoft/TypeScript/wiki/Using-the-Compiler-API)

## 2. TypeScript Compiler API 基础组件

官方 Wiki 指出 Compiler API 主要有以下核心组件：[来源：Using the Compiler API](https://github.com/microsoft/TypeScript/wiki/Using-the-Compiler-API)

| 组件 | 含义 |
| --- | --- |
| `Program` | TypeScript 术语里指代“整个应用/工程”，由 `createProgram` 创建 |
| `CompilerHost` | 表示用户系统，提供读文件、检查目录、大小写敏感性等接口 |
| `SourceFile` | 表示应用中的每个源文件，同时承载“源文本”与“TypeScript AST” |

此外还有两个对静态分析至关重要的组件：

- **`TypeChecker`**：由 `program.getTypeChecker()` 获得，用于跨文件解析类型与符号。常用 API 包括 `getSymbolAtLocation(node)`（取 AST 节点关联的 `Symbol`）、`getTypeAtLocation(node)`（取 AST 节点关联的 `Type`）、`getTypeOfSymbolAtLocation(symbol, node)`、`typeToString(type)`。[来源：Using-the-Compiler-API.md（TypeScript-wiki 仓库）](https://github.com/microsoft/TypeScript-wiki/blob/main/Using-the-Compiler-API.md)
- **`LanguageService`**：面向编辑器场景的“按需处理”服务，设计目标之一是只做回答问题所需的最小工作量；配合 `LanguageServiceHost` 使用。[来源：Using the Language Service API](https://github.com/microsoft/TypeScript/wiki/Using-the-Language-Service-API)

## 3. AST 遍历能力

- TypeScript Compiler API 提供 `ts.forEachChild(node, cb)` 递归遍历整棵 AST；所有节点类型由 `ts.SyntaxKind` 枚举定义（如 `ClassDeclaration`、`InterfaceDeclaration`、`CallExpression`、`PropertyAccessExpression`、`Decorator`、`ImportDeclaration` 等），并提供 `ts.isXxx(node)` 类型守卫（如 `ts.isClassDeclaration`、`ts.isInterfaceDeclaration`、`ts.isCallExpression`、`ts.isPropertyAccessExpression`）。[来源：Using the Compiler API（Traversing the AST with a little linter 一节）](https://github.com/microsoft/TypeScript/wiki/Using-the-Compiler-API)
- ts-morph 在同样的概念上提供了更顺手的导航方法：
  - `.getChildren()`（返回含 token 的全部子节点）、`.forEachChild(cb)`、`.forEachDescendant(cb)`（遍历所有后代，适合实现 visitor 模式），并支持 `traversal.skip()` / `traversal.up()` / `traversal.stop()` 控制遍历。[来源：ts-morph Navigation](https://ts-morph.com/navigation)
  - 高层导航方法如 `.getClasses()`、`.getClass('MyClass')`、`.getModules()` 等。[来源：ts-morph Navigation](https://ts-morph.com/navigation)

## 4. ts-morph 封装的节点类型

ts-morph 仓库的 `wrapped-nodes.md`（自动生成）列出**已封装的 AST 节点共 224 种**，未封装的节点仍可作为通用 `Node` 访问，只是缺少专用辅助方法。[来源：wrapped-nodes.md](https://github.com/dsherret/ts-morph/blob/latest/packages/ts-morph/wrapped-nodes.md)

与本任务直接相关的封装节点（节选）：

| 节点 | 关键成员（官方标注已实现） |
| --- | --- |
| `ClassDeclaration` | `modifiers`、`name` |
| `InterfaceDeclaration` | `modifiers`、`name`、`typeParameters`、`heritageClauses`、`members` |
| `Decorator` | `expression` |
| `ImportDeclaration` | `importClause`、`moduleSpecifier`、`attributes` |
| `ExportDeclaration` | `exportClause`、`moduleSpecifier`、`isTypeOnly`、`attributes` |
| `ExportSpecifier` | `isTypeOnly`、`propertyName`、`name` |
| `ImportSpecifier` | `propertyName`、`name`、`isTypeOnly` |
| `CallExpression` | `expression`、`typeArguments`、`arguments` |
| `PropertyAccessExpression` | （继承自表达式节点，用于 `a.b()` 形式调用） |
| `EnumDeclaration` / `EnumMember` | `modifiers`、`name`、`members` 等 |
| `HeritageClause` | `token`、`types`（用于 `extends` / `implements`） |

[来源：wrapped-nodes.md](https://github.com/dsherret/ts-morph/blob/latest/packages/ts-morph/wrapped-nodes.md)

官方文档“Details”目录列出当前具备导航支持的语言结构：Source Files、Classes、Decorators、Enums、Functions、Imports、Interfaces、Namespaces、Parameters、Type Parameters、Type Aliases、Variables、Types、Signatures、Expressions 等；同时明确 **Symbols 仍标记为 todo、Manipulation 支持尚不完整**。[来源：ts-morph Details](https://ts-morph.com/details/index)

## 5. 各类源码元素的具体提取能力

### 5.1 类（ClassDeclaration）

- 获取类：`sourceFile.getClasses()`、`sourceFile.getClass("Class1")` 或按条件匹配。[来源：ts-morph Classes](https://ts-morph.com/details/classes)
- 继承/实现：`getExtends()`、`getImplements()`、`getBaseClass()`（返回基类 `ClassDeclaration`）、`getBaseTypes()`（返回 `Type[]`，适合 mixin 场景）、`getDerivedClasses()`（反向找所有派生类）。[来源：ts-morph Classes](https://ts-morph.com/details/classes)
- 成员：`getInstanceMethods()` / `getStaticMethods()`、`getInstanceProperties()` / `getStaticProperties()`（含 parameter properties）、`getConstructors()`、`getMembers()`。[来源：ts-morph Classes](https://ts-morph.com/details/classes)
- 附加能力：`extractInterface()` 可从类结构提取出接口结构。[来源：ts-morph Classes](https://ts-morph.com/details/classes)

### 5.2 接口（InterfaceDeclaration）

- 官方 Details 明确 InterfaceDeclaration 具备 `modifiers`、`name`、`typeParameters`、`heritageClauses`、`members`，可用于提取接口名称、泛型参数、继承关系与成员签名。[来源：wrapped-nodes.md](https://github.com/dsherret/ts-morph/blob/latest/packages/ts-morph/wrapped-nodes.md)
- 可通过 `sourceFile.getInterfaces()` 获取源文件内全部接口（与 `getClasses()` 同类的导航方法族，见 [ts-morph Navigation](https://ts-morph.com/navigation) 与 [ts-morph Details](https://ts-morph.com/details/index)）。

### 5.3 装饰器（Decorator）—— Angular 识别的基础

- 从类等节点取装饰器：`classDeclaration.getDecorators()`。[来源：ts-morph Decorators](https://ts-morph.com/details/decorators)
- 名称：`getName()`（如 `Component`）、`getFullName()`（如 `obj.decorator` 形式的全限定名）。[来源：ts-morph Decorators](https://ts-morph.com/details/decorators)
- 工厂判断：`isDecoratorFactory()` 判断是否带括号（`@decorator(3)` 是工厂，`@decorator` 不是）。[来源：ts-morph Decorators](https://ts-morph.com/details/decorators)
- 参数：`getArguments()` 返回装饰器实参（`Expression[]`）——`@Component({...})` 的元数据对象就在此；`getCallExpression()` 返回装饰器工厂对应的 `CallExpression`。[来源：ts-morph Decorators](https://ts-morph.com/details/decorators)

### 5.4 模块 / 导入导出图

- 导入：`sourceFile.getImportDeclarations()`、按条件或模块说明符筛选；`getModuleSpecifierValue()`、`getModuleSpecifierSourceFile()`（解析出被引用文件，`SourceFile | undefined`）、`isModuleSpecifierRelative()`；`getDefaultImport()`、`getNamespaceImport()`、`getNamedImports()`。[来源：ts-morph Imports](https://ts-morph.com/details/imports)
- 引用/被引用关系（构建模块图的核心）：
  - `sourceFile.getReferencingSourceFiles()`、`getReferencingNodesInOtherSourceFiles()`、`getReferencingLiteralsInOtherSourceFiles()`——**谁引用了本文件**；
  - `sourceFile.getReferencedSourceFiles()`、`getNodesReferencingOtherSourceFiles()`、`getImportStringLiterals()`——**本文件引用了谁**；
  - `getPathReferenceDirectives()` / `getTypeReferenceDirectives()` / `getLibReferenceDirectives()`——三斜线 `/// <reference .../>` 注释引用。[来源：ts-morph Source Files](https://ts-morph.com/details/source-files)
- 导出：`ExportDeclaration` 节点提供 `exportClause`、`moduleSpecifier`、`isTypeOnly` 等成员，可用于构建 re-export 关系。[来源：wrapped-nodes.md](https://github.com/dsherret/ts-morph/blob/latest/packages/ts-morph/wrapped-nodes.md)

### 5.5 方法调用图（call graph）

- 调用表达式：`CallExpression` 封装了 `expression`（被调用方）、`typeArguments`、`arguments`；`PropertyAccessExpression` 支持 `a.b()` 形式的成员调用。[来源：wrapped-nodes.md](https://github.com/dsherret/ts-morph/blob/latest/packages/ts-morph/wrapped-nodes.md)
- 引用查找（构建跨文件调用/引用图的基石）：
  - `declaration.findReferences()`——返回该声明/标识符的全部引用（含文件路径、`TextSpan` 起止位置、父节点类型等）；
  - `declaration.findReferencesAsNodes()`——只返回引用它的节点；
  - `identifier.getDefinitions()` / `identifier.getDefinitionNodes()`——“跳转到定义”，把调用点解析回其声明。[来源：ts-morph Finding References](https://github.com/dsherret/ts-morph/blob/master/docs/navigation/finding-references.md)
- 类型信息（把 `http.get(...)` 这类调用解析为“HttpClient 上的方法”所必需）：
  - `node.getType()`、`functionDeclaration.getReturnType()`、`type.getProperties()` / `type.getProperty()`、`type.getSymbol()`、`type.isClass()` / `type.isInterface()`、`type.getBaseTypes()`、`type.getCallSignatures()` / `getConstructSignatures()`、`type.isAssignableTo(...)`（ts-morph 22+）。[来源：ts-morph Types](https://ts-morph.com/details/types)
  - 底层对应 TypeScript `TypeChecker` 的 `getSymbolAtLocation` / `getTypeAtLocation` 等 API。[来源：Using-the-Compiler-API.md（TypeScript-wiki 仓库）](https://github.com/microsoft/TypeScript-wiki/blob/main/Using-the-Compiler-API.md)

> 结论：方法调用图可拆成“语法层”（`CallExpression` / `PropertyAccessExpression` 的遍历）+“语义层”（`getType()` / `getSymbol()` / `findReferences()` 解析调用目标与跨文件引用）两步完成；ts-morph 对两者都提供支持。

## 6. Angular 特定信息的提取

ts-morph 本身**不内置 Angular 概念**，Angular 元素的识别需要基于其“类 + 装饰器”的约定，通过第 5.3 节的装饰器能力实现。以下为 Angular 官方文档所定义的、可被静态识别的结构：

### 6.1 组件（Component）

- Angular 官方定义：组件就是“在 TypeScript 类上方加一个 `@Component` 装饰器”，装饰器对象即组件的**元数据（metadata）**，包含 `selector`、`template`/`templateUrl`、`styles`/`styleUrl`、`imports` 等字段。[来源：angular.dev — Anatomy of a component](https://angular.dev/guide/components)
- `templateUrl` / `styleUrl` 指向与组件同目录的相对文件（模板 HTML、样式 CSS），即模板内容在**独立文件**中，不在 TS 源码里。[来源：angular.dev — Anatomy of a component](https://angular.dev/guide/components)
- 组件默认是 **standalone**，直接放入其它组件的 `imports` 数组即可使用；`standalone: false` 的旧组件则挂靠 `NgModule`。[来源：angular.dev — Anatomy of a component](https://angular.dev/guide/components)
- 由此可静态提取：类名、`@Component` 元数据（selector、模板路径、样式路径、imports 数组中的依赖组件/指令/管道、providers 等）。

### 6.2 服务（Service / Injectable）

- 服务类通过 **`@Injectable` 装饰器**标记为“可注入”；常用 `@Injectable({ providedIn: 'root' })` 提供根级单例。[来源：angular.dev — Understanding dependency injection](https://angular.dev/guide/di/dependency-injection)
- 依赖注入消费方式有两种：构造函数参数注入，以及 Angular 的 **`inject()` 函数**（可用于属性初始化器或构造函数内）。[来源：angular.dev — Understanding dependency injection](https://angular.dev/guide/di/dependency-injection)
- 由此可静态提取：哪些类被 `@Injectable` 标记、其 `providedIn` 级别、以及构造器参数 / `inject(...)` 调用所声明的依赖（即组件→服务的依赖边）。

### 6.3 HttpClient API 调用

- `HttpClient` 提供与 HTTP 动词对应的方法（`get` / `post` 等），每个方法返回 RxJS `Observable`；`get(url, options?)` 返回数据，`post(url, body, options?)` 提交变更。[来源：angular.dev — Making HTTP requests](https://angular.dev/guide/http/making-requests)
- 识别方式（基于第 5.5 节的调用图能力）：在类内遍历 `CallExpression` / `PropertyAccessExpression`，通过类型系统把调用目标解析回 `@angular/common/http` 的 `HttpClient` 类型（即属性/参数/`inject()` 的声明类型为 `HttpClient`），即可判定某次调用是否为 HTTP 请求、属于哪个动词、URL 与参数是什么。

## 7. 能力边界与注意事项

以下为基于上述一手来源的结构性推断，用于指导选型，非官方“限制声明”：

1. **模板不在 TS AST 内**：`@Component` 的 `templateUrl` 指向独立 `.html` 文件，`template` 内联字符串也只是装饰器元数据里的一个普通字符串——模板内的元素、数据绑定、组件引用关系无法由 ts-morph 直接解析，需要额外的 HTML/Angular 模板解析工具。[依据：angular.dev 将模板描述为独立文件/元数据](https://angular.dev/guide/components)
2. **识别 Angular 元素需自定义规则**：ts-morph 只给出装饰器/类型/调用的通用能力，具体“这是组件”“这是服务”“这是 HTTP 调用”的判定逻辑需要自行实现。
3. **Manipulation 能力不完整、Symbols 待完善**：官方 Details 页明确标注 Symbols 为 todo、Manipulation 支持尚不完整——对本任务（以“提取/分析”为主）影响较小，但若要基于它做大规模改写需留意。[来源：ts-morph Details](https://ts-morph.com/details/index)
4. **TS 版本迁移风险**：TypeScript 官方预告 7.1 将更换 Compiler API，长期维护需关注上游变化。[来源：Using the Compiler API](https://github.com/microsoft/TypeScript/wiki/Using-the-Compiler-API)
5. **跨文件解析依赖完整 Program**：类型/符号解析（如把 `http.get` 解析到 `HttpClient`）需要把相关文件都纳入同一个 `Program`/`Project`，否则 `getType()` / `getSymbol()` 可能无法解析。

## 8. 结论（对 UML 分析工具的启示）

- 一个 `ts-morph` `Project`（或原生 `ts.createProgram`）即可获得**整个工程的 TS AST + 类型/符号信息**，足以支撑：
  - **类图**：类、接口、继承（`extends`）、实现（`implements`）、成员（方法/属性/构造器）。
  - **模块/导入图**：import/export 及 `getReferencingSourceFiles()` 等双向引用查询。
  - **方法调用图**：`CallExpression` 遍历 + `findReferences()`/`getDefinitions()` + 类型解析。
  - **Angular 图**：通过装饰器（`@Component`/`@Injectable`/`@NgModule` 等）识别组件与服务，通过类型解析识别 `HttpClient` 调用。
- 模板层面的关系（组件树、模板内的组件引用）无法由 ts-morph 单独完成，需另行处理 `.html` 模板。

## 引用来源

1. [ts-morph 官网（Overview）](https://ts-morph.com/)
2. [ts-morph GitHub README](https://github.com/dsherret/ts-morph)
3. [ts-morph Navigation](https://ts-morph.com/navigation)
4. [ts-morph Details（index）](https://ts-morph.com/details/index)
5. [ts-morph Classes](https://ts-morph.com/details/classes)
6. [ts-morph Decorators](https://ts-morph.com/details/decorators)
7. [ts-morph Imports](https://ts-morph.com/details/imports)
8. [ts-morph Source Files](https://ts-morph.com/details/source-files)
9. [ts-morph Types](https://ts-morph.com/details/types)
10. [ts-morph Finding References（文档）](https://github.com/dsherret/ts-morph/blob/master/docs/navigation/finding-references.md)
11. [ts-morph wrapped-nodes.md（封装的节点清单）](https://github.com/dsherret/ts-morph/blob/latest/packages/ts-morph/wrapped-nodes.md)
12. [TypeScript Wiki — Using the Compiler API](https://github.com/microsoft/TypeScript/wiki/Using-the-Compiler-API)
13. [TypeScript-wiki 仓库 — Using-the-Compiler-API.md（含 Type Checker APIs 一节）](https://github.com/microsoft/TypeScript-wiki/blob/main/Using-the-Compiler-API.md)
14. [TypeScript Wiki — Using the Language Service API](https://github.com/microsoft/TypeScript/wiki/Using-the-Language-Service-API)
15. [angular.dev — Anatomy of a component](https://angular.dev/guide/components)
16. [angular.dev — Understanding dependency injection](https://angular.dev/guide/di/dependency-injection)
17. [angular.dev — Making HTTP requests](https://angular.dev/guide/http/making-requests)

# UML 分析器

一个本地工具，静态分析 Java（Spring Boot / Gradle，含多模块）与 Angular/TypeScript 仓库，生成类图、组件图与序列图。本文件定义其统一分析模型（UAM）中的领域术语。

## Language

**模块 (Module)**:
统一分析模型中的顶层分组单元；Java 侧为 Gradle 子项目，TS 侧为 Angular 模块或包。
_Avoid_: 项目、工程、包、module（泛指代码模块）

**组件 (Component)**:
组件图的基本单元，由带框架标注的类扮演（如 `@RestController`/`@Service`/`@Component`/`@Injectable`）。
_Avoid_: 节点、服务、Bean、Service

**类 / 接口 (Class / Interface)**:
类图的基本单元。接口并入同类节点，用 `kind` 字段区分，不单列节点类型。
_Avoid_: 类型、结构体、type

**方法 (Method)**:
类内可调用单元；在 UAM 中作为独立节点，承载调用图与序列图的展开。
_Avoid_: 函数、操作、procedure

**端点 (Endpoint)**:
一个 HTTP 面入口，由「HTTP 方法 + 路径」标识，锚定到某个后端处理方法。
_Avoid_: 路由、API、接口、handler

**调用边 (Call edge)**:
方法到方法的一次静态调用关系（`CALL` 边），调用图与序列图展开的基础。
_Avoid_: 连线、依赖、依赖边

**跨边界调用 (Cross-boundary call)**:
前端 TS 方法经 `HttpClient` 调用后端 Java 端点的 HTTP 调用关系（`HTTP_CALL` 边）。
_Avoid_: 远程调用、RPC、HTTP 请求

**统一分析模型 (Unified Analysis Model, UAM)**:
把 Java 与 TS 抽取结果合并成一张节点-边图的中间表示；类图、组件图、序列图各取所需投影。
_Avoid_: 图模型、中间层、IR

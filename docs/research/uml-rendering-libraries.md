# UML/图表渲染库调研：复用 vs 自研

> 目标：为「交互式 Web UI 中渲染类图、组件图、时序图，支持 PNG/SVG 导出与自动布局」选型，判断各库应**复用**还是**自研**。
> 所有结论均基于一手来源（官方文档、官方仓库、官方许可文件），并以链接标注。

## 评估维度

- 图类型覆盖：类图、组件图、时序图
- 运行形态：纯前端浏览器内运行，还是需要服务端
- 交互性：拖拽、缩放、选择、编辑、事件
- 导出：SVG / PNG
- 自动布局：是否内置、算法质量
- 许可证：对复用与分发的约束

## 结论摘要（TL;DR）

- 若需求是「文本定义 → 快速出图 + 纯前端 + SVG 导出」，**复用 Mermaid**（`classDiagram` / `sequenceDiagram`），PNG 交给服务端（mermaid-cli 或 Kroki）。
- 若需求是「真正可交互、可编辑的 UML 编辑器」，以 **JointJS 核心（MPL-2.0）为渲染/交互框架**，自建 UML 类/组件/时序形状；导出（尤其 PNG/JPEG）是其商业版 JointJS+ 的能力，核心版需自行序列化 SVG 再光栅化。
- **自动布局统一复用 ELK.js**（类图/组件图的分层布局）；时序图布局 ELK 不覆盖，需自建或复用专用引擎。
- **PlantUML 不作为前端库复用**（Java、需服务端），可作为服务端出图后端（自建 plantuml-server 或 Kroki）。
- nomnoml、Cytoscape.js 等仅在特定窄场景（轻量类图 / 通用图可视化）考虑，一般不作主选。

---

## 逐库分析

### 1. PlantUML

**定位**：Java 实现的「文本 → 图」工具，语法丰富、UML 覆盖最全。

- 图类型：官方首页明确列出类图、组件图（Component）、时序图（Sequence）等十余种 UML 图（[plantuml.com](https://plantuml.com/)）。
- 布局引擎：默认 Graphviz（依赖外部程序），另有 Smetana（Java 内嵌的 Graphviz 移植）、VizJs（JavaScript）、ELK（仅正交布局、功能不全）（[plantuml.com 布局引擎一节](https://plantuml.com/)）。
- 输出：PNG、SVG、LaTeX、EPS、ASCII（ASCII 仅时序图）（[plantuml.com](https://plantuml.com/)）。
- 交互：支持超链接与 tooltip（[plantuml.com 附加特性](https://plantuml.com/)）。
- Web 集成形态：**不是浏览器端 JS 库**。官方提供 PlantUML Server（部署到 JEE/Tomcat 10，或本地 PicoWeb），通过 URL 端点返回 PNG/SVG/ASCII/image-map（[plantuml.com/server](https://plantuml.com/server)）。
- 许可：多许可证模式，源码仓库可见 `plantuml-asl`、`plantuml-bsd`、`plantuml-epl`、`plantuml-gplv2` 等分模块目录（[github.com/plantuml/plantuml](https://github.com/plantuml/plantuml)）。

**结论**：功能与排版质量最高、组件图/时序图支持最完整，但**需服务端**，不适合直接嵌入纯前端交互 UI。可作为「服务端出图后端」复用（Kroki 或自建 server）。

### 2. Mermaid

**定位**：JavaScript 库，Markdown 风格文本 → 图，浏览器内直接运行（[mermaid.js.org/intro](https://mermaid.js.org/intro/)）。

- 类图：`classDiagram`，支持属性/方法分栏、继承/聚合等关系（[mermaid.js.org/syntax/classDiagram](https://mermaid.js.org/syntax/classDiagram.html)）。
- 时序图：`sequenceDiagram`，支持参与者、消息、激活框、组合片段等（[mermaid.js.org/syntax/sequenceDiagram](https://mermaid.js.org/syntax/sequenceDiagram.html)）。
- 组件图：**无专用「组件图」类型**；可用 `flowchart`（节点形状）或 `block`（块图，用于方框/嵌套结构）近似表达（[flowchart](https://mermaid.js.org/syntax/flowchart.html)、[block](https://mermaid.js.org/syntax/block.html)）。
- 布局：默认 dagre；可选 ELK（`flowchart.defaultRenderer: "elk"` 或顶层 `layout`/`elk` 配置项），对复杂图更佳（[flowchart 配置 schema](https://mermaid.js.org/config/schema-docs/config-defs-flowchart-diagram-config.html)、[config.schema.json](https://mermaid.js.org/schemas/config.schema.json)）。
- 导出：**SVG 原生**；PNG/PDF 需 mermaid-cli（基于 puppeteer，Node 环境，非纯浏览器）（[github.com/mermaid-js/mermaid-cli](https://github.com/mermaid-js/mermaid-cli)）。
- 交互：支持 click 事件、tooltip 等（[mermaid.js.org/config/usage](https://mermaid.js.org/config/usage.html)）。
- 许可：MIT（[LICENSE](https://raw.githubusercontent.com/mermaid-js/mermaid/develop/LICENSE)）。

**结论**：类图/时序图「文本优先」场景的首选复用对象，纯前端、生态大。短板是组件图需模拟、PNG 导出需服务端/CLI。

### 3. Cytoscape.js

**定位**：通用「图论/网络」可视化与分析库，**非 UML 专用**（[js.cytoscape.org](https://js.cytoscape.org/)）。

- 图类型：通用节点-边图，无内置类图/组件图/时序图语义形状。
- 交互：极强——拖拽、缩放、框选、事件、样式选择器（[js.cytoscape.org](https://js.cytoscape.org/)）。
- 布局：内置 grid/circle/concentric/cose/breadthfirst 等；扩展 dagre、cola、klay、cose-bilkent、fcose、elk（`cytoscape.js-elk`）等（[js.cytoscape.org 示例列表](https://js.cytoscape.org/)、[cytoscape.js-dagre](https://github.com/cytoscape/cytoscape.js-dagre)、[elkjs README](https://github.com/kieler/elkjs)）。
- 导出：核心提供图像导出（`cy.png()` / `cy.jpg()`，导出当前渲染结果）；**SVG 需扩展 `cytoscape-svg`**（GPL-3.0）（[js.cytoscape.org](https://js.cytoscape.org/)、[cytoscape-svg](https://github.com/kinimesi/cytoscape-svg)）。
- 许可：核心 MIT（[LICENSE](https://raw.githubusercontent.com/cytoscape/cytoscape.js/unstable/LICENSE)）；`cytoscape-svg` GPL-3.0。

**结论**：适合「通用图数据可视化」，若做 UML 需自建全部形状与语义，成本高。一般不作为 UML 主选。

### 4. JointJS

**定位**：基于 SVG 的交互式图表/编辑器框架；核心开源（`@joint/core`，MPL-2.0），JointJS+（`@joint/plus`）为商业版（[jointjs.com/opensource](https://www.jointjs.com/opensource)、[github.com/clientIO/joint](https://github.com/clientIO/joint)）。

- 形状：内置 `shapes.standard`（Rectangle/Circle/Record 等）；`shapes` 命名空间含 standard/bpmn2/chart/measurement/vsm，**无开箱即用的 UML 类图形状集**（[docs.jointjs.com shapes](https://docs.jointjs.com/4.1/api/shapes/)）。
- 类图：官方做法是用 `standard.Record`（或 HeaderedRecord）拼出 UML 类，属 JointJS+ demo「UML Class Shape Inspector」（[demo](https://www.jointjs.com/demos/uml-class-shape-inspector)）。
- 时序图：有官方「Sequence」demo（自定义形状实现）（[jointjs.com/opensource](https://www.jointjs.com/opensource)）。
- 交互：完整编辑器能力——拖拽、连线、路由/连接器/锚点、工具、undo/redo 等（[jointjs.com/opensource](https://www.jointjs.com/opensource)）。
- 导出：SVG 导出由 `format.toSVG` / `openAsSVG` 提供，属 **JointJS+ 的 format 插件**（[docs.jointjs.com SVG API](https://docs.jointjs.com/4.1/api/format/SVG/)）；PNG/JPEG/WebP 光栅导出亦由 JointJS+ 提供（[Image Export](https://docs.jointjs.com/react/features/export-and-import/image-export/)）。开源核心以 SVG 渲染，可自行从 DOM 序列化 SVG 再做光栅化。
- 许可：核心 MPL-2.0（[LICENSE](https://raw.githubusercontent.com/clientIO/joint/master/LICENSE)）；JointJS+ 商业。

**结论**：若要「深度交互、可编辑」的 UML 编辑器，JointJS 是最接近的现成框架，但 UML 语义形状需自建，且 PNG/JPEG 导出在商业版。

### 5. ELK.js

**定位**：**纯自动布局引擎，只计算坐标，不渲染、不样式化**（[eclipse.dev/elk](https://eclipse.dev/elk/)、[github.com/kieler/elkjs](https://github.com/kieler/elkjs)）。

- 算法：默认 `layered`（基于 Sugiyama 的分层布局，适合有方向的节点-连线图），另有 stress、mrtree、radial、force、disco（[elkjs README](https://github.com/kieler/elkjs)）。
- 生态：被 Cytoscape（`cytoscape.js-elk`）、React Flow、sprotty、Mermaid（elk renderer）等采用（[elkjs README](https://github.com/kieler/elkjs)）。
- 许可：EPL-2.0（[LICENSE.md](https://raw.githubusercontent.com/kieler/elkjs/master/LICENSE.md)）。

**结论**：**布局层的最佳复用点**，与自研渲染层解耦。注意其面向节点-连线/分层布局，**不覆盖时序图**（时序图布局需自建）。

### 6. nomnoml

**定位**：轻量 JS 库，文本 → SVG/Canvas，**面向类图**，语法贴近所生成的图（[nomnoml.com](https://nomnoml.com/)、[github.com/skanaar/nomnoml](https://github.com/skanaar/nomnoml)）。

- 关系与分类器：内置 association/dependency/generalization/composition/aggregation 等关系，及 class/abstract/package/table 等分类器（[nomnoml README](https://github.com/skanaar/nomnoml)）。
- 布局：内置（唯一依赖 graphre），提供 `#ranker`、`#acyclicer`、`#direction` 等指令（[nomnoml README](https://github.com/skanaar/nomnoml)）。
- 导出：`renderSvg()` 返回 SVG 字符串；`draw(canvas)` 画到 Canvas；PNG 需自行从 Canvas/SVG 转换（[nomnoml README](https://github.com/skanaar/nomnoml)）。
- 许可：MIT（[LICENSE](https://raw.githubusercontent.com/skanaar/nomnoml/master/LICENSE)）。

**结论**：轻量、类图专用，但组件图/时序图支持弱、生态小、交互有限。仅轻量类图场景可考虑。

---

## 其他相关

- **Kroki**：统一「文本 → 图」渲染服务，聚合 PlantUML、Mermaid、nomnoml、GraphViz、D2、Structurizr 等，统一 API 输出 PNG/SVG（[kroki.io](https://kroki.io/)）。适合做「服务端统一出图」兜底。
- **draw.io（diagrams.net / mxGraph）**：Apache-2.0 的全功能图编辑器，支持嵌入与 SVG/PNG/PDF 导出；是「应用」而非可深度定制的库，重量级（[drawio.com](https://www.drawio.com/)）。
- **React Flow**：React 节点编辑器，采用 dagre/elk 布局（[elkjs README「Example Users」](https://github.com/kieler/elkjs)）；非 UML 专用。
- **sprotty**：与 elkjs 配合的图框架（[elkjs README](https://github.com/kieler/elkjs)）。

---

## 对比表

| 库 | 类图 | 组件图 | 时序图 | 纯前端 | 交互性 | SVG | PNG | 自动布局 | 许可 |
|---|---|---|---|---|---|---|---|---|---|
| PlantUML | ✅ 完整 | ✅ 完整 | ✅ 完整 | ❌（需服务端） | 弱（链接/tooltip） | ✅ | ✅ | Graphviz/Smetana/ELK | 多许可（GPL v3 为主） |
| Mermaid | ✅ | ⚠️（flowchart/block 模拟） | ✅ | ✅ | 中（事件/tooltip） | ✅ 原生 | ⚠️（需 mermaid-cli） | dagre/ELK | MIT |
| Cytoscape.js | ❌（自建） | ❌（自建） | ❌（自建） | ✅ | 强 | ⚠️（扩展，GPL-3.0） | ✅ 核心 | 内置+dagre/elk 扩展 | 核心 MIT |
| JointJS | ⚠️（Record 拼装） | ⚠️（自建形状） | ⚠️（demo 形状） | ✅ | 强（编辑器级） | ✅（JointJS+） | ⚠️（JointJS+） | 外部（dagre/elk） | 核心 MPL-2.0 / Plus 商业 |
| ELK.js | —（仅布局） | —（仅布局） | ❌ | ✅ | — | — | — | ✅（本库即引擎） | EPL-2.0 |
| nomnoml | ✅（轻量） | ❌ | ❌ | ✅ | 弱 | ✅ | ⚠️（自行转换） | 内置（graphre） | MIT |
| Kroki | ✅（后端） | ✅（后端） | ✅（后端） | ❌（服务） | — | ✅ | ✅ | 各引擎 | 服务端 MIT |

---

## 复用 vs 自研 建议

1. **类图 + 时序图（文本驱动、快速出图）**：复用 **Mermaid**；SVG 直接导出，PNG 走 mermaid-cli 或 Kroki。
2. **组件图**：PlantUML 原生最完整（走 Kroki/plantuml-server 后端）；纯前端则用 Mermaid flowchart/block 或自建轻量组件图。
3. **深度交互/可编辑编辑器**：以 **JointJS 核心**为框架，自建 UML 类（`standard.Record`）、组件、时序形状；SVG 自序列化 + Canvas 光栅化出 PNG，或引入 JointJS+ 获得现成导出。
4. **自动布局**：统一复用 **ELK.js**（类图/组件图分层布局）；时序图布局**自建**（ELK 不覆盖）。
5. **PlantUML** 作为服务端出图后端复用（Kroki 或自建 server），不进入前端运行时。
6. nomnoml、Cytoscape.js 一般**不选作主方案**；Cytoscape.js 仅在「通用图数据」而非「UML 语义」需求下考虑。

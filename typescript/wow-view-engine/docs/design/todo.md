# TODO

规则，先读再加：

- 只记**已经决定、但还没做**的事。尚无结论的产品问题不在这里，在 [decisions.md#搁置待议](decisions.md#搁置待议)。
- 每条都要写清 **为什么** / **做完的判据** / **落点**（design 里的哪一页，或哪个文件）。三者缺一就不是一条 TODO，是一句抱怨。
- 做完就**删掉**这一条，不打勾、不留归档。历史在 git 里。
- 改行为之前先看这里有没有对应项；有就接着做，别另起一条。

## 首发前的门（npm 首发与阶段 6 开工之前）

- **全面审查过门，由用户明确点头**（用户 2026-09-24）：npm 首发与阶段 6（Wow 存储后端）开工之前，先做第二轮全面审查——架构质量（职责清晰、高内聚、低耦合、扩展性、可维护性）、企业级产品体验（UI 视觉、UX 交互）、功能的可用性、可访问性与易用性——处置完后交审查报告，**用户明确审查通过**才开始这两件事。
  - 为什么：这是一次重大发布；公开面一旦上 npm 就要背兼容性。
  - 判据：审查报告（含 Storybook 场景审查与就绪审计 P1 的处置）交给用户并得到明确通过；此前不发布、不开阶段 6。
  - 落点：[progress.md](progress.md)「这个暂停点」；Wow 仓的 `typescript/RELEASING.md`。
- **先重构到生产就绪，不留兼容债**（用户 2026-09-24：「尽早重构解决问题，避免以后再考虑兼容性债务」）：审查里「现在做还是以后做」默认现在做；公开面（[D29](decisions.md) 快照）上的破坏性改动趁首发前一次改到位，不为发布前的形态留兼容层（面向已部署 Wow 服务端的兼容除外）。
  - 判据：首发时 `docs/compat-debt.md` 里没有本包因发布前形态而欠下的条目。落点：本页各节、[decisions.md](decisions.md)。
- **就绪审计里本包的 P1**（2026-09-24 只读审计；本包这次不发 npm，所以不挡 9.2.0，但挡本包首发）——并入第二轮审查的清单，逐条变成带判据的 TODO 或拍板：
  - 严格 CSP：提示框色块的 `style=` 改 class，写 CSP 指南，加一个严格 CSP 下的故事。
  - 分析表 10k 行的渲染实测，必要时虚拟化。
  - 手工读屏（VoiceOver／NVDA）与纯键盘走查的成文记录，WCAG 2.2 AA 符合性声明。
  - 视觉回归基线，Firefox／WebKit 跑一次。（连真服务端的端到端已落地，见下一节。）
  - 判据：每条要么合并、要么由用户拍板推迟到首发后并写进 [decisions.md](decisions.md)。落点：本页。

## 连真 Wow 服务端的端到端（2026-09-25 落地后的余项）

端到端在 Wow 仓 `typescript/integration-test/test/view-engine/`，由 `typescript-contract.yml` 的同源契约作业对着同一提交构建的示例服务端（MongoDB）运行；覆盖面与本地跑法见那里的 README「View engine against the server」。落地时发现、没在那个 PR 里修的：

- **守卫数的过滤节点与值，引擎没有在同一处数**（[D42](decisions.md#d42-引擎的缺省预算不超过缺省配置的-wow-服务端2026-09-25) 里没一起改的两条）：Wow 的 `HttpQueryGuard` 缺省收至多 128 个过滤节点（`max-filter-nodes`，数的是编译后的整条查询：条件、注入的作用域、指标与元素的条件、只保留合在一起）和一个 `IN` 至多 1,000 个值（`max-filter-values`）；引擎的 `maxFilterNodes` 缺省 256、数的是配置里的一棵树（一个日期区间编译后是三个节点），值的个数没有预算。超出时是服务端的 400，经 `runtime.query.failed` 如实报出，不会静默，但准入放行了一条必被拒的查询。
  - 为什么：同一类错配——准入的口径不是服务端的口径；只把 256 改成 128 仍数不准，还会连带收紧 Wow 自己按 256 收的表达式预算。
  - 判据：在编译后的查询上按守卫的口径数节点与值（`RuntimeLimits` 各一条、缺省对齐守卫），超出时在发出之前报一条带路径的 error；端到端里加一条 129 个节点的用例看它在本地就被拦下。
  - 落点：`src/analysis/compile.ts`、`src/record/compile.ts`、`src/model/limits.ts`，[kernels.md](kernels.md)。
- **分析视图的时间轴也只补首尾之间的洞**：走势卡已经补到自己的窗口（`cardWindow`，D39），柱、线、面积与热力图的时间轴仍只补回来的首尾两桶之间——「近 30 天」而前五天没有记录时，轴从第六天开始。
  - 为什么：与走势卡同一个缺口；拆分与热力图的洞要按组合补，与卡的一维补法不同，没在修卡的 PR 里一起做。
  - 判据：条件在时间轴字段上钉住窗口、且结果完整（`absenceReader` 能担保）时，这几种图的时间轴补到窗口两端，可加的指标补 0 并标 `filled`。
  - 落点：`src/analysis/cartesian.ts`、`src/analysis/chart.ts`（热力图），[kernels.md](kernels.md)。
- **搜索在 MongoDB 后端不可用**：示例服务端的快照模型没有全文能力，`SEARCH` 被拒（`Model search is unsupported.`／`FULL_TEXT_TERMS`），端到端只验证了拒绝如实报出。要验证搜索真的命中，需要一台带 Elasticsearch 快照的服务端。
  - 判据：契约作业有了 ES 快照（或另起一个作业）后，搜索用例改为断言命中。落点：`recordView.test.ts`。

## Storybook：真实交易订单场景

- **七批按方案做**（Wow 仓 [typescript/storybook/docs/scenarios.md](../../../storybook/docs/scenarios.md)，Q1～Q4 与首页验收 6.3 已定）：零售数据集与生成器（第 1 批，在做）→ 数据源加速与补语义 → 记录、分析、事件流的业务场景 → 仪表盘与嵌入页 → 目录与导览 → 跟着 ECharts B、C、E 补展示 → 首页换成只读的运营日报（必做）。
  - 为什么：用户 2026-09-24——夹具要面向真实交易订单场景，尽量体现本包的能力，分析视图面向真实的数据分析。
  - 判据：以方案为准；七批做完后按第一性原理做一次完整 review（领域专家、架构、前端、数据分析、UI/UX 五个视角，真浏览器逐场景走查），处置后报告给用户，并入上面的第二轮审查。
  - 落点：`typescript/storybook/stories/view-engine/retail/`；方案页。

## 用本包重构补偿控制台

- **按方案分八批做**（Wow 仓 [compensation/dashboard/docs/design/view-engine-rebuild.md](../../../../compensation/dashboard/docs/design/view-engine-rebuild.md)，第 7 节 Q1～Q5 待用户拍板）：前置（超时边界、CI、依赖）→ 定义与预览页 →「此刻」与三个时刻队列 → 行动作与成批动作 → 详情 → 接管队列路由 → 首页换成系统板 → 收尾与验证报告。
  - 为什么：用户 TODO——增强补偿控制台（成批处置、任意条件与个人视图、从分析追到处置、导出），并在真实产品里验证本包：真 CI、真部署、真鉴权、宿主的写、依赖时刻的口径、宿主外壳。
  - 本包要先修的两处（方案第 3 节）：G1 条件里说「此刻」（日期时刻的「此刻」值与严格的 `LT`／`GT`）；G2 详情抽屉的宿主节与受控打开（按键打开、回报给宿主）。G3（板上记录面板的分页、总数与宿主行动作）随引擎缺口 PR 3（D39）。
  - 判据：方案第 6 节十条逐条达成——新旧七个队列的 ID 集合在钉住的时钟下相等、首页数字与旧版对齐、旧 e2e 场景都有对应断言且 Dashboard Test 每批绿、成批处置 ≤ 6 次点击、axe 0 违规、控制台只用公开入口、删约 3.6k 行而宿主新增 ≤ 1.2k 行、发现的缺口都进了本页或已合并、真服务走查报告并入第二轮全面审查。
  - 落点：本包的 G1、G2 进 [model.md](model.md)、[kernels.md](kernels.md)、[ui/record.md](ui/record.md)；控制台在 `compensation/dashboard/src/views/`；做完一批在方案页记一行，全部做完删掉这一条。

## D22 标了「以后」的几项（线索）

- 联动筛选、卡片内筛选（「全局筛选」）；按列的点击行为（「点击」）；整板 PDF（「运维」）；订阅、版本历史、验证、缓存要服务端，归阶段 6。
  - 为什么：它们只在 decisions 里有半句话，排下一个阶段时看不到（审查 X-11）。
  - 判据：排进某个阶段时各自成为一条带判据的 TODO，或进 [decisions.md#搁置待议](decisions.md#搁置待议)；那时删掉这一条。
  - 落点：本页。
- 改变窗口尺寸或从板子「返回」重新挂载的第一帧，网格会短暂比容器宽（随即恢复）——迁移前看到、未处理；第二轮审查时复现，确认后修或删掉这一条。

## 阶段 5：内置多主题

- **四批（5A～5D）都已合并，剩阶段审查与收尾**（[phase5-themes.md](phase5-themes.md) 第 4 节，裁定 [D30](decisions.md#d30-阶段-5-内置多主题的十条裁定2026-09-24)）：
  - 为什么：批次的判据各自由测试守住了（对比度矩阵、`test/presetContrast.test.ts`、`verify-package` 的预设与桥接检查），但方案页还单独立着，阶段的五维审查也还没做。
  - 判据：按惯例先把架构、代码质量、UI、视觉、UX 五个维度的审查清单给用户看，处置完后把 [phase5-themes.md](phase5-themes.md) 并入 [ui/README.md#主题弹层与明暗](ui/README.md#主题弹层与明暗)，删掉方案页与这一条，并重写 [progress.md](progress.md)。
  - 落点：[phase5-themes.md](phase5-themes.md)、[ui/README.md](ui/README.md)、[progress.md](progress.md)。

## 内置主题目录

- **六批按方案做**（[themes.md](themes.md) 第 7 节，裁定 [D35](decisions.md#d35-内置主题目录与三条轴的四条裁定2026-09-24)）；每批的完整判据以方案为准，这里只列线索：
  - 为什么：用户 2026-09-24 要内置常用、经典风格的主题；宿主研发选，引擎只暴露属性、prop 与 CSS 入口。
  - T5 主题一览三视图矩阵、截图基线、强制颜色、打印（等交易订单夹具改造合并）。T6 阶段审查与收尾。
  - 判据：每套 × 每种明暗过对比度矩阵与色板门；neutral 在默认密度、默认约定下像素不变；每批 PR 写 CSS gzip 实测数。
  - 落点：[themes.md](themes.md)；做完一批删一行，全部做完后把方案页并入 [ui/README.md](ui/README.md)，删掉方案页与这一条。

## 分析视图：释放 ECharts

- **五批按方案做**，A（#3334）、B（#3341）、C（#3365）、D（#3331）、E 已做，剩阶段审查（[analysis-echarts.md](analysis-echarts.md) 第 3 节，裁定 [D33](decisions.md#d33-分析视图释放-echarts-能力的九条裁定2026-09-24)）；每批的完整判据以方案为准，这里只列线索：
  - 为什么：用户 2026-09-24 的方向，首个大版本前分析视图要到企业 BI（Metabase、Superset、Grafana、Tableau）的水准；审计见方案第 1 节——缩放、框选、图例点选、花纹、采样、导出图片都还没有。
  - 首发后的线索（Q59 整段对比、箱线图、地图口子、注释等）见方案第 3 节末，另加日历热力图（批 D 按 Q55 没做，理由见 [ui/analysis.md](ui/analysis.md#一个家族一个文件)）；每批 PR 写图表块 gzip 实测数。
  - 落点：[analysis-echarts.md](analysis-echarts.md)；做完一批删一行，五批与阶段审查做完后把方案页并入 [ui/analysis.md](ui/analysis.md)、[model-shapes.md](model-shapes.md)、[kernels.md](kernels.md)，删掉方案页与这一条。

## 阶段 2 留下的线索（不做，或待产品口径）

- 准入发现里的字段用的是 `field.name`（「给 status 一个值」）而不是显示名——整个包的惯例，要改是包级的决定。
- 「更多图型」折叠宿主扩展的图型：今天没有宿主扩展图型的入口，等有了再做。
- 透视表（Q8）；精确的 M（Q7）；分析表冻结列；STDDEV／VARIANCE 与去重计数在 ES 上的近似提示按后端能力声明。
- 按日期部件分组（星期几、几点）与两个时刻之差：为什么——「星期 × 时段」「付款到发货几小时」是零售分析的常见问题，Wow 聚合今天只有 `DATE_HISTOGRAM`，Storybook 场景先用读模型的派生字段回答（[storybook/docs/scenarios.md](../../../storybook/docs/scenarios.md) Q4，2026-09-24 按推荐）；判据——Wow 查询（`wow-query`、`wow-client` 的 `AggregationGroup`）先有这两种分组与表达式，本包再在定义准入、托盘与编译里各加一条；落点：Wow 查询模块，随后 [model.md](model.md) 与 [kernels.md](kernels.md)。

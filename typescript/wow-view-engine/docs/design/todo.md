# TODO

规则，先读再加：

- 只记**已经决定、但还没做**的事。尚无结论的产品问题不在这里，在 [decisions.md#搁置待议](decisions.md#搁置待议)。
- 每条都要写清 **为什么** / **做完的判据** / **落点**（design 里的哪一页，或哪个文件）。三者缺一就不是一条 TODO，是一句抱怨。
- 做完就**删掉**这一条，不打勾、不留归档。历史在 git 里。
- 改行为之前先看这里有没有对应项；有就接着做，别另起一条。

## 检查点：迁往 Wow 仓（阶段 3、4 收口之后）

- **迁移窗口已开（2026-09-24）。** 阶段 3、4 与它们的联合审查处置全部合并（最后一批 R3b #1902），用户确认了迁移步骤：这里不再开新工作，本包之后在 Wow 仓的 `typescript/wow-view-engine` 里做（Wow 那份方案是 `typescript/MIGRATION.md`）。下面几条都到 Wow 做。
  - 为什么：迁移方案定的时机是阶段边界；阶段 6 的存储后端也要和引擎放在同一个仓库。
  - 判据：远端一出现 tag `wow-migration-base`（用 `git ls-remote --tags origin wow-migration-base` 查），本包就冻结，这里一律不再改；迁移第 3′ 步把本包从 fetcher 删掉时，这一页随之删除。
  - 落点：[迁移方案](../../../../docs/superpowers/specs/2026-09-23-wow-packages-migration-design.md)，根目录 `AGENTS.md` 的「Migration Checkpoint」一节。

- **R3 的界面重构——到 Wow 仓做**（[D28](decisions.md#d28-r3-的界面重构到-wow-做迁移前提放宽2026-09-24)）：阶段 3＋4 联合审查里不改行为的界面重构，迁移后在 Wow 做。
  - 判据：行为不变的重构由现有测试守住；`max-lines` 豁免表仍为空。落点：`src/ui/`。

- **D22 标了「以后」的几项**——线索，到 Wow 仓排阶段时再定：联动筛选、卡片内筛选（「全局筛选」）；按列的点击行为（「点击」）；整板 PDF（「运维」）；订阅、版本历史、验证、缓存要服务端，归阶段 6。
  - 为什么：迁走之后它们只剩 decisions 里的半句话，排下一个阶段时看不到（审查 X-11）。
  - 判据：排进某个阶段时各自成为一条带判据的 TODO，或进 [decisions.md#搁置待议](decisions.md#搁置待议)；那时删掉这一条。
  - 落点：本页。

## 阶段 5：内置多主题

- **四批（5A～5D）都已合并，剩阶段审查与收尾**（[phase5-themes.md](phase5-themes.md) 第 4 节，裁定 [D30](decisions.md#d30-阶段-5-内置多主题的十条裁定2026-09-24)）：
  - 为什么：批次的判据各自由测试守住了（对比度矩阵、`test/presetContrast.test.ts`、`verify-package` 的预设与桥接检查），但方案页还单独立着，阶段的五维审查也还没做。
  - 判据：按惯例先把架构、代码质量、UI、视觉、UX 五个维度的审查清单给用户看，处置完后把 [phase5-themes.md](phase5-themes.md) 并入 [ui/README.md#主题弹层与明暗](ui/README.md#主题弹层与明暗)，删掉方案页与这一条，并重写 [progress.md](progress.md)。
  - 落点：[phase5-themes.md](phase5-themes.md)、[ui/README.md](ui/README.md)、[progress.md](progress.md)。

## 分析视图：释放 ECharts

- **五批按方案做**（[analysis-echarts.md](analysis-echarts.md) 第 3 节，裁定 [D33](decisions.md#d33-分析视图释放-echarts-能力的九条裁定2026-09-24)）；每批的完整判据以方案为准，这里只列线索：
  - 为什么：用户 2026-09-24 的方向，首个大版本前分析视图要到企业 BI（Metabase、Superset、Grafana、Tableau）的水准；审计见方案第 1 节——缩放、框选、图例点选、花纹、采样、导出图片都还没有。
  - B 参考与算出的系列——判据：平均、中位参考线、目标区间、最高／最低点、趋势、移动平均、累计都在内核算，补出的 0 与被截断的行不进统计，行不完整时置灰并说原因（Q53）；`src/analysis/chart.ts` 先把派生系列拆出去。
  - C 框选与追问——判据：横轴框选弹出与点一组同一个追问菜单（Q52），一段桶读回一个 [首桶起, 末桶终) 条件、跨夏令时正确；仪表盘「设为〈时间筛选〉」；按维度分段的漏斗一段可按；触屏先提示后追问。
  - D 新图型——判据：瀑布图、矩形树图（日历热力图可选，Q55）各在 `CHART_FAMILIES` 一行并过「可选即画得出」的性质测试；图型网格分「适合这个结果」与「其他图型」两组（Q54）。
  - E 显示收口——判据：数值轴与散点的对数刻度（遇 0 或负数置灰）、散点轴规格与十字准星、超过八条的口径（Q56，结清 Q9）、读屏摘要句、导出 PNG 与 SVG（带标题、图例、范围说明，Q58），入口与「导出数据…」同一菜单。
  - 首发后的线索（Q59 整段对比、箱线图、地图口子、注释等）见方案第 3 节末；每批 PR 写图表块 gzip 实测数。
  - 落点：[analysis-echarts.md](analysis-echarts.md)；做完一批删一行，五批与阶段审查做完后把方案页并入 [ui/analysis.md](ui/analysis.md)、[model-shapes.md](model-shapes.md)、[kernels.md](kernels.md)，删掉方案页与这一条。

## 阶段 2 留下的线索（不做，或待产品口径）

- 准入发现里的字段用的是 `field.name`（「给 status 一个值」）而不是显示名——整个包的惯例，要改是包级的决定。
- 「更多图型」折叠宿主扩展的图型：今天没有宿主扩展图型的入口，等有了再做；散点的坐标轴范围并入上面「分析视图：释放 ECharts」的批 E。
- 透视表（Q8）；精确的 M（Q7）；分析表冻结列；STDDEV／VARIANCE 与去重计数在 ES 上的近似提示按后端能力声明。

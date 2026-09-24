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

## 阶段 2 留下的线索（不做，或待产品口径）

- 准入发现里的字段用的是 `field.name`（「给 status 一个值」）而不是显示名——整个包的惯例，要改是包级的决定。
- 「更多图型」折叠宿主扩展的图型：今天没有宿主扩展图型的入口，等有了再做；散点的坐标轴范围要先进模型（`ScatterSpec` 没有轴规格，所以没有坐标轴页签）。
- 透视表（Q8）；精确的 M（Q7）；分析表冻结列；STDDEV／VARIANCE 与去重计数在 ES 上的近似提示按后端能力声明。

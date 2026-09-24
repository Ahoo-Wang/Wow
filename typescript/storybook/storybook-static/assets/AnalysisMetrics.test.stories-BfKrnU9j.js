import{n as e}from"./rolldown-runtime-DkW27tQK.js";import{Es as t,R as n,Ts as r,z as i}from"./styles-Dpj2y9Rj.js";import{A as a,T as o,_ as s,j as c}from"./AnalysisWorkbench.stories-DyF9m040.js";import{a as l,i as u,o as d,r as f}from"./readTable-DOunjEkP.js";function p(e){return S(e.querySelector(`[data-slot="editor-toggle"]`)).getByRole(`button`)}async function m(e){return await b.click(p(e)),await x(()=>v(w(e)).not.toBeNull()),w(e)}function h(e){return S(e.querySelector(`[data-slot="analysis-tray-actions"]`)).getByRole(`button`,{name:i[`label.filter.apply`]})}function g(e){return S(e.querySelector(`[data-slot="auto-run"]`)).getByRole(`checkbox`)}function _(e){let t={marked:!1,opacity:1,painted:!1},n=!0,r=()=>{let i=e.querySelector(`[data-slot="analysis-result"][data-stale]`);i&&(t.marked=!0,t.opacity=Math.min(t.opacity,Number(getComputedStyle(i).opacity)),i.getBoundingClientRect().height>0&&(t.painted=!0)),n&&requestAnimationFrame(r)};return r(),{read:()=>({...t}),stop:()=>{n=!1}}}var v,y,b,x,S,C,w,T,E,D,O,k,A,j,M,N,P,F,I,L,R,z;function B(){return(B=e((()=>{t(),n(),a(),u(),{expect:v,screen:y,userEvent:b,waitFor:x,within:S}=__STORYBOOK_MODULE_TEST__,C={...c,title:`View Engine/分析视图/指标与保留/回归`,tags:[`!dev`,`!autodocs`,`test`],parameters:{...c.parameters}},w=e=>e.querySelector(`[data-slot="analysis-tray"]`),T=e=>e.tBodies[0]?.rows.length??0,E=r(i,`label.summary.of`,{field:`金额`,fn:i[`label.summary.fn.SUM`]}),D=r(i,`label.summary.of`,{field:`(金额 − 成本)`,fn:i[`label.summary.fn.SUM`]}),O=e=>e.querySelector(`[data-slot="analysis-reading"]`),k=r(i,`label.analysis.reading-kept`,{reading:r(i,`label.analysis.reading`,{dimensions:`仓库`,metrics:`${i[`label.analysis.row-count`]}${i[`label.filter.join`]}${E}`}),conditions:r(i,`label.analysis.reading-kept-row`,{metric:E,operator:i[`label.having.op.GT`],value:new Intl.NumberFormat(`zh-CN`,{style:`currency`,currency:`CNY`}).format(2e3)})}),A={...o,play:async({canvasElement:e})=>{let t=await f(e);await x(()=>v(T(t)).toBe(4));let n=await m(e);await b.click(n.querySelector(`[data-slot="add-having"]`));let r=await x(()=>{let e=n.querySelector(`[data-slot="having-row"]`);if(!e)throw Error(`「只保留」那一行没有出来`);return e});await b.click(S(r).getByLabelText(i[`label.analysis.having-metric`])),await b.click(await y.findByRole(`option`,{name:E})),await b.type(S(r).getByLabelText(i[`label.analysis.having-value`]),`2000`),await b.click(h(e));let a=await f(e);await x(()=>v(T(a)).toBe(2)),await v(l(a,`仓库`)).toEqual([`华北`,`华南`]),await b.click(p(e)),await x(()=>v(w(e)).toBeNull()),await v(O(e)).toBeVisible(),await v(O(e)?.textContent).toBe(k)}},j={...o,args:{...o.args,kept:2e3},play:async({canvasElement:e})=>{let t=await f(e);await x(()=>v(T(t)).toBe(2)),await v(l(t,`仓库`)).toEqual([`华北`,`华南`]),await v(w(e)).toBeNull(),await v(O(e)).toBeVisible(),await v(O(e)?.textContent).toBe(k)}},M={...o,play:async({canvasElement:e})=>{let t=S(e),n=await f(e);await x(()=>v(T(n)).toBe(4)),await v(d(n)).not.toContain(D),await m(e),await b.click(t.getByRole(`button`,{name:i[`label.analysis.add-metric`]})),await b.click(await y.findByRole(`menuitem`,{name:i[`label.analysis.add-formula`]})),await x(()=>v([...e.querySelectorAll(`[data-slot="card-name"]`)].map(e=>e.textContent)).toContain(`金额 − 成本`)),await b.click(h(e));let r=await f(e);await x(()=>v(d(r)).toContain(D)),await v(l(r,D)[0]).toContain(`520`),await v(l(r,D)[0]).toBe(`¥520.00`)}},N={...o,play:async({canvasElement:e})=>{let t=await f(e);await x(()=>v(T(t)).toBe(4));let n=await m(e);await b.click(n.querySelector(`[data-slot="analysis-sort"] [data-control="sort"]`));let r=await y.findByRole(`dialog`);for(let e of[i[`label.analysis.row-count`],E])await b.click(S(r).getByRole(`button`,{name:i[`label.sort.groups.add`]})),await b.click(await y.findByRole(`menuitem`,{name:e}));await v([...r.querySelectorAll(`[data-slot="sort-entry"]`)].map(e=>e.getAttribute(`data-field`))).toEqual([`orders`,`amount`]),await b.keyboard(`{Escape}`),await b.click(h(e));let a=await f(e);await x(()=>v(l(a,`仓库`)).toEqual([`西南`,`华北`,`华东`,`华南`]))}},P={...o,play:async({canvasElement:e})=>{let t=await f(e);await x(()=>v(T(t)).toBe(4));let n=await m(e),a=()=>S(n).getByLabelText(i[`label.analysis.row-limit`]),o=r(i,`label.analysis.row-limit-invalid`,{max:1e4});for(let e of[`-3`,`2.5`]){await b.clear(a()),await b.type(a(),e),await v(a()).toHaveValue(e),await v(a()).toHaveAttribute(`aria-invalid`,`true`);let t=await S(n).findByText(o);await v(t).toBeVisible(),await v(a()).toHaveAccessibleDescription(v.stringContaining(o));let r=t.getBoundingClientRect();await v(r.top).toBeGreaterThanOrEqual(a().getBoundingClientRect().bottom),await v(r.right).toBeLessThanOrEqual(n.getBoundingClientRect().right)}await b.clear(a()),await b.tab(),await v(a()).toHaveValue(``),await v(a()).toHaveAttribute(`placeholder`,`100`),await v(a()).not.toHaveAttribute(`aria-invalid`,`true`),await v(S(n).queryByText(o)).toBeNull(),await b.type(a(),`-3`),await S(n).findByText(o),await b.click(S(n).getByRole(`button`,{name:r(i,`label.analysis.remove-group`,{name:`仓库`})})),await x(()=>v(n.querySelector(`[data-slot="analysis-limit"]`)).toBeNull()),await v(S(e).queryByText(o)).toBeNull(),await v(h(e)).toBeEnabled(),await b.click(h(e)),await x(async()=>v(T(await f(e))).toBe(1)),await v(e.textContent??``).not.toContain(r(i,`analysis.limit.out-of-range`,{max:1e4}))}},F=r(i,`label.summary.of`,{field:`成本`,fn:i[`label.summary.fn.SUM`]}),I={...o,play:async({canvasElement:e})=>{let t=S(e),n=await f(e);await x(()=>v(T(n)).toBe(4)),await v(d(n)).not.toContain(`状态`);let r=await m(e);await v(g(e)).toBeChecked();let a=_(e);await b.click(S(r).getByRole(`button`,{name:i[`label.analysis.add-group`]})),await b.click(await y.findByRole(`menuitem`,{name:`状态`})),await x(async()=>v(d(await f(e))).toContain(`状态`)),await v(T(await f(e))).toBeGreaterThan(4),a.stop(),await v(a.read().marked).toBe(!0),await v(a.read().painted).toBe(!0),await v(a.read().opacity).toBeLessThan(1),await x(()=>v(e.querySelector(`[data-slot="analysis-result"][data-stale]`)).toBeNull()),await v(h(e).querySelector(`[data-slot="pending-dot"]`)).toBeNull(),await b.click(g(e)),await x(()=>v(g(e)).not.toBeChecked());let o=_(e);await b.click(t.getByRole(`button`,{name:i[`label.analysis.add-metric`]})),await b.click(await y.findByRole(`menuitem`,{name:`成本`})),await x(()=>v([...e.querySelectorAll(`[data-slot="card-name"]`)].map(e=>e.textContent)).toContain(`成本`)),o.stop(),await v(o.read().marked).toBe(!1),await v(d(await f(e))).not.toContain(F),await v(h(e).querySelector(`[data-slot="pending-dot"]`)).not.toBeNull(),await b.click(h(e)),await x(async()=>v(d(await f(e))).toContain(F))}},L=r(i,`label.summary.of`,{field:`创建时间`,fn:i[`label.summary.fn.date.MAX`]}),R={...s,play:async({canvasElement:e})=>{let t=await f(e);await x(()=>v(T(t)).toBe(4)),await v(d(t)).toContain(L);let n=e=>new Intl.DateTimeFormat(`zh-CN`,{dateStyle:`medium`,timeStyle:`medium`}).format(new Date(e));await v(l(t,`仓库`)).toEqual([`西南`,`华南`,`华北`,`华东`]),await v(l(t,L)).toEqual([n(`2026-09-17T08:45:00.000Z`),n(`2026-09-17T02:20:00.000Z`),n(`2026-09-16T01:05:00.000Z`),n(`2026-09-15T06:40:00.000Z`)]),await v(t.textContent).not.toContain(`2026-09-17T08:45`),await v(t.textContent).not.toMatch(/[0-9]{13}/),await b.click(S(e).getByRole(`button`,{name:i[`label.analysis.visualize`]}));let r=await x(()=>{let t=e.querySelector(`[data-slot="chart-picker"] [data-chart-type="scatter"]`);if(!t)throw Error(`可视化面板没有出来`);return t});await v(r.getAttribute(`aria-disabled`)).toBe(`true`),await v(r.textContent).toContain(i[`chart.fit.needs-quantity`])}},z=[`KeepOnly`,`KeepOnlySaved`,`Formula`,`SortedByTwo`,`TopNField`,`RunsAsEdited`,`LatestPerWarehouse`],A.parameters={...A.parameters,docs:{...A.parameters?.docs,source:{originalSource:`{
  ...DisplayTableWithTotals,
  play: async ({
    canvasElement
  }) => {
    const table = await findDataTable(canvasElement);
    await waitFor(() => expect(groupRows(table)).toBe(4));
    const opened = await openTray(canvasElement);
    await userEvent.click(opened.querySelector<HTMLElement>('[data-slot="add-having"]')!);
    const row = await waitFor(() => {
      const found = opened.querySelector<HTMLElement>('[data-slot="having-row"]');
      if (!found) throw new Error('「只保留」那一行没有出来');
      return found;
    });

    // Which metric keeps a group: the sample values are not offered, because
    // Wow refuses a having over one.
    await userEvent.click(within(row).getByLabelText(zhCN['label.analysis.having-metric']));
    await userEvent.click(await screen.findByRole('option', {
      name: AMOUNT_METRIC
    }));
    await userEvent.type(within(row).getByLabelText(zhCN['label.analysis.having-value']), '2000');
    await userEvent.click(applyButton(canvasElement));
    const after = await findDataTable(canvasElement);
    await waitFor(() => expect(groupRows(after)).toBe(2));
    await expect(readColumn(after, '仓库')).toEqual(['华北', '华南']);

    // 托盘收起之后，被筛掉的两组仍由结果第一行说出来（2026-09-23 审查 P0-2）。
    await userEvent.click(trayToggle(canvasElement));
    await waitFor(() => expect(tray(canvasElement)).toBeNull());
    await expect(reading(canvasElement)).toBeVisible();
    await expect(reading(canvasElement)?.textContent).toBe(KEPT_READING);
  }
}`,...A.parameters?.docs?.source},description:{story:`「只保留」：一行一条比较，跑完之后表上真的少了两组。

四个仓库的金额总和是 1920／2450／4880／980，「金额的总和 大于 2000」
之后只剩华北与华南。它是聚合之后、排序与截断之前的一道筛选，所以它减少
的是**组**，不是记录——一条画在条件面板里的筛选做不到这件事，这也是它
为什么不在范围里。`,...A.parameters?.docs?.description}}},j.parameters={...j.parameters,docs:{...j.parameters?.docs,source:{originalSource:`{
  ...DisplayTableWithTotals,
  args: {
    ...DisplayTableWithTotals.args,
    kept: 2000
  },
  play: async ({
    canvasElement
  }) => {
    const table = await findDataTable(canvasElement);
    await waitFor(() => expect(groupRows(table)).toBe(2));
    await expect(readColumn(table, '仓库')).toEqual(['华北', '华南']);

    // A saved view opens folded: the tray is not there to say it.
    await expect(tray(canvasElement)).toBeNull();
    await expect(reading(canvasElement)).toBeVisible();
    await expect(reading(canvasElement)?.textContent).toBe(KEPT_READING);
  }
}`,...j.parameters?.docs?.source},description:{story:`存着「只保留」的视图，打开时托盘收着（P0-2）。

表上只有华北与华南，合计行却数着全部记录；从前屏幕上没有一个字说华东与
西南去了哪里，读的人只能当它们没有数据。现在结果第一行在指标后面说出
「只保留 金额的总和 大于 ¥2,000.00」——不打开托盘也看得见。`,...j.parameters?.docs?.description}}},M.parameters={...M.parameters,docs:{...M.parameters?.docs,source:{originalSource:`{
  ...DisplayTableWithTotals,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    const table = await findDataTable(canvasElement);
    await waitFor(() => expect(groupRows(table)).toBe(4));
    await expect(readHeaders(table)).not.toContain(MARGIN_HEADER);
    await openTray(canvasElement);
    await userEvent.click(canvas.getByRole('button', {
      name: zhCN['label.analysis.add-metric']
    }));
    await userEvent.click(await screen.findByRole('menuitem', {
      name: zhCN['label.analysis.add-formula']
    }));

    // The card is named by what it says, because no field stands behind it.
    await waitFor(() => expect([...canvasElement.querySelectorAll('[data-slot="card-name"]')].map(name => name.textContent)).toContain('金额 − 成本'));
    await userEvent.click(applyButton(canvasElement));
    const after = await findDataTable(canvasElement);
    await waitFor(() => expect(readHeaders(after)).toContain(MARGIN_HEADER));
    // 华东 1920 − 1400 = 520；这一列的数由数据源逐条算出来。
    await expect(readColumn(after, MARGIN_HEADER)[0]).toContain('520');
    // Money minus money is money: the column reads in the ¥ its operands
    // are in, as the amount column beside it does (2026-09-23 audit).
    await expect(readColumn(after, MARGIN_HEADER)[0]).toBe('¥520.00');
  }
}`,...M.parameters?.docs?.source},description:{story:`公式：两个字段一次运算，逐条算完再汇总，屏幕上多出一列。

「金额 − 成本」在**每一条记录上**算一次、再在组里合计，这与「金额总和
减 成本合计」在合计上碰巧相等、在平均上并不相等——数据源真的按表达式
算，所以这一列的数是查询答的，不是故事写死的。`,...M.parameters?.docs?.description}}},N.parameters={...N.parameters,docs:{...N.parameters?.docs,source:{originalSource:`{
  ...DisplayTableWithTotals,
  play: async ({
    canvasElement
  }) => {
    const table = await findDataTable(canvasElement);
    await waitFor(() => expect(groupRows(table)).toBe(4));
    const opened = await openTray(canvasElement);
    await userEvent.click(opened.querySelector<HTMLElement>('[data-slot="analysis-sort"] [data-control="sort"]')!);
    const editor = await screen.findByRole('dialog');
    for (const name of [zhCN['label.analysis.row-count'], AMOUNT_METRIC]) {
      await userEvent.click(within(editor).getByRole('button', {
        name: zhCN['label.sort.groups.add']
      }));
      await userEvent.click(await screen.findByRole('menuitem', {
        name
      }));
    }
    await expect([...editor.querySelectorAll('[data-slot="sort-entry"]')].map(entry => entry.getAttribute('data-field'))).toEqual(['orders', 'amount']);
    await userEvent.keyboard('{Escape}');
    await userEvent.click(applyButton(canvasElement));
    const after = await findDataTable(canvasElement);
    await waitFor(() => expect(readColumn(after, '仓库')).toEqual(['西南', '华北', '华东', '华南']));
  }
}`,...N.parameters?.docs?.source},description:{story:`排序：与记录视图同一个控件，所以「先按哪个、再按哪个」说得出来。

先按记录数、再按金额：记录数 1 的两组（华北 2450、西南 980）排在前面，
组内按金额升序，于是西南在华北之前。一个只装得下一条排序的控件说不出
这句话——它只能在两组并列时听天由命。`,...N.parameters?.docs?.description}}},P.parameters={...P.parameters,docs:{...P.parameters?.docs,source:{originalSource:`{
  ...DisplayTableWithTotals,
  play: async ({
    canvasElement
  }) => {
    const table = await findDataTable(canvasElement);
    await waitFor(() => expect(groupRows(table)).toBe(4));
    const opened = await openTray(canvasElement);
    const box = () => within(opened).getByLabelText<HTMLInputElement>(zhCN['label.analysis.row-limit']);
    const refusal = formatMessage(zhCN, 'label.analysis.row-limit-invalid', {
      max: 10_000
    });
    for (const typed of ['-3', '2.5']) {
      await userEvent.clear(box());
      await userEvent.type(box(), typed);
      await expect(box()).toHaveValue(typed);
      await expect(box()).toHaveAttribute('aria-invalid', 'true');
      // A fraction is told the range, not that it must be positive.
      const said = await within(opened).findByText(refusal);
      await expect(said).toBeVisible();
      // Beside the note that the source picks the groups (nothing sorts
      // them here), so the description holds both.
      await expect(box()).toHaveAccessibleDescription(expect.stringContaining(refusal));
      // Under its box, and inside the tray rather than cut off at its edge.
      const where = said.getBoundingClientRect();
      await expect(where.top).toBeGreaterThanOrEqual(box().getBoundingClientRect().bottom);
      await expect(where.right).toBeLessThanOrEqual(opened.getBoundingClientRect().right);
    }

    // Emptied, it stays empty: the N a view starts at, said as the placeholder.
    await userEvent.clear(box());
    await userEvent.tab();
    await expect(box()).toHaveValue('');
    await expect(box()).toHaveAttribute('placeholder', '100');
    await expect(box()).not.toHaveAttribute('aria-invalid', 'true');
    await expect(within(opened).queryByText(refusal)).toBeNull();

    // A refused N left in the box goes with the box when the last dimension
    // takes the row away — and nothing is left behind to refuse Apply.
    await userEvent.type(box(), '-3');
    await within(opened).findByText(refusal);
    await userEvent.click(within(opened).getByRole('button', {
      name: formatMessage(zhCN, 'label.analysis.remove-group', {
        name: '仓库'
      })
    }));
    await waitFor(() => expect(opened.querySelector('[data-slot="analysis-limit"]')).toBeNull());
    await expect(within(canvasElement).queryByText(refusal)).toBeNull();
    await expect(applyButton(canvasElement)).toBeEnabled();
    await userEvent.click(applyButton(canvasElement));
    await waitFor(async () => expect(groupRows(await findDataTable(canvasElement))).toBe(1));
    // Nor does the status line say anything about the N.
    await expect(canvasElement.textContent ?? '').not.toContain(formatMessage(zhCN, 'analysis.limit.out-of-range', {
      max: 10_000
    }));
  }
}`,...P.parameters?.docs?.source},description:{story:`「前 N 组」的框（2026-09-23 审查）：草稿里只放 Wow 收得下的 N。

从前清空之后弹回刚才的 -3；2.5 被说成「必须是正数」；删掉最后一个维度把这一
行带走之后，那个 -3 还留在草稿里拦着应用。这里在真浏览器里走一遍：出界的字
照原样留在框里，框下面一句「须为 1～10,000 的整数」，读得见、在框下面；清空
就是空着（占位字写着起步的 100）；带着一个出界的字删掉维度，框与那句话一起
走，应用照跑，表上只剩一行。`,...P.parameters?.docs?.description}}},I.parameters={...I.parameters,docs:{...I.parameters?.docs,source:{originalSource:`{
  ...DisplayTableWithTotals,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    const table = await findDataTable(canvasElement);
    await waitFor(() => expect(groupRows(table)).toBe(4));
    await expect(readHeaders(table)).not.toContain('状态');
    const opened = await openTray(canvasElement);
    await expect(autoRunSwitch(canvasElement)).toBeChecked();

    // 加一个维度，然后什么也不按。
    const faded = watchFading(canvasElement);
    await userEvent.click(within(opened).getByRole('button', {
      name: zhCN['label.analysis.add-group']
    }));
    await userEvent.click(await screen.findByRole('menuitem', {
      name: '状态'
    }));
    await waitFor(async () => expect(readHeaders(await findDataTable(canvasElement))).toContain('状态'));
    // 四个仓库按状态再切一刀，组比原来多。
    await expect(groupRows(await findDataTable(canvasElement))).toBeGreaterThan(4);
    // 等的那一下，上一份答案留在屏幕上、**真的**淡着——不只是带了个属性，
    // 而是浏览器为它画了一个盒子、并把那个盒子画淡了；跑完就不淡了，点也没了。
    faded.stop();
    await expect(faded.read().marked).toBe(true);
    await expect(faded.read().painted).toBe(true);
    await expect(faded.read().opacity).toBeLessThan(1);
    await waitFor(() => expect(canvasElement.querySelector('[data-slot="analysis-result"][data-stale]')).toBeNull());
    await expect(applyButton(canvasElement).querySelector('[data-slot="pending-dot"]')).toBeNull();

    // 关掉开关：这是这个用户对这个定义的偏好，写完列表重读，勾自然落下。
    await userEvent.click(autoRunSwitch(canvasElement));
    await waitFor(() => expect(autoRunSwitch(canvasElement)).not.toBeChecked());
    const stillFading = watchFading(canvasElement);
    await userEvent.click(canvas.getByRole('button', {
      name: zhCN['label.analysis.add-metric']
    }));
    await userEvent.click(await screen.findByRole('menuitem', {
      name: '成本'
    }));

    // 编辑落进了托盘——卡片在那儿——而结果一次也没淡过：没有哪一次自动运行
    // 在路上，那一列因此也还没有。
    await waitFor(() => expect([...canvasElement.querySelectorAll('[data-slot="card-name"]')].map(name => name.textContent)).toContain('成本'));
    stillFading.stop();
    await expect(stillFading.read().marked).toBe(false);
    await expect(readHeaders(await findDataTable(canvasElement))).not.toContain(COST_HEADER);
    await expect(applyButton(canvasElement).querySelector('[data-slot="pending-dot"]')).not.toBeNull();

    // 按下去才跑。
    await userEvent.click(applyButton(canvasElement));
    await waitFor(async () => expect(readHeaders(await findDataTable(canvasElement))).toContain(COST_HEADER));
  }
}`,...I.parameters?.docs?.source}}},R.parameters={...R.parameters,docs:{...R.parameters?.docs,source:{originalSource:`{
  ...DisplayLatestPerWarehouse,
  play: async ({
    canvasElement
  }) => {
    const table = await findDataTable(canvasElement);
    await waitFor(() => expect(groupRows(table)).toBe(4));
    await expect(readHeaders(table)).toContain(LATEST_HEADER);
    const shown = (iso: string) => new Intl.DateTimeFormat('zh-CN', {
      dateStyle: 'medium',
      timeStyle: 'medium'
    }).format(new Date(iso));
    await expect(readColumn(table, '仓库')).toEqual(['西南', '华南', '华北', '华东']);
    await expect(readColumn(table, LATEST_HEADER)).toEqual([shown('2026-09-17T08:45:00.000Z'), shown('2026-09-17T02:20:00.000Z'), shown('2026-09-16T01:05:00.000Z'), shown('2026-09-15T06:40:00.000Z')]);
    // Neither the stored value nor the number it was compared as.
    await expect(table.textContent).not.toContain('2026-09-17T08:45');
    await expect(table.textContent).not.toMatch(/[0-9]{13}/);
    await userEvent.click(within(canvasElement).getByRole('button', {
      name: zhCN['label.analysis.visualize']
    }));
    const scatter = await waitFor(() => {
      const tile = canvasElement.querySelector<HTMLElement>('[data-slot="chart-picker"] [data-chart-type="scatter"]');
      if (!tile) throw new Error('可视化面板没有出来');
      return tile;
    });
    await expect(scatter.getAttribute('aria-disabled')).toBe('true');
    await expect(scatter.textContent).toContain(zhCN['chart.fit.needs-quantity']);
  }
}`,...R.parameters?.docs?.source},description:{story:`每个仓库最晚的一单（生产审查：真实 Wow 服务上 \`MAX(eventTime)\` 在表里、
图上与读屏表里都是一串十三位毫秒）。

数据源真的按组取创建时间的最大值、再按它降序排（\`rowSource.ts\`），所以
这一列的时刻与行序都是查询答的：西南最近，华东最早（它最晚的那一单已被
软删除，不在答复里）。每一格按界面语言与引擎时区读，与记录视图的单元格
同一套读法；表头说「最晚」。切到可视化，散点灰着——它要两个数量，而
最晚是一个时刻，不是数量。`,...R.parameters?.docs?.description}}}})))()}B();export{M as Formula,A as KeepOnly,j as KeepOnlySaved,R as LatestPerWarehouse,I as RunsAsEdited,N as SortedByTwo,P as TopNField,z as __namedExportsOrder,C as default};
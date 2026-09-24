import{n as e}from"./rolldown-runtime-DkW27tQK.js";import{Af as t,Es as n,R as r,Ts as i,vd as a,z as o}from"./styles-Dpj2y9Rj.js";import{t as s}from"./src-DsG2EjN4.js";import{A as c,D as l,h as u,j as d,n as f,o as p,r as m,s as h}from"./AnalysisWorkbench.stories-DyF9m040.js";import{a as g,h as _,l as v,n as y,o as b,r as x,t as S}from"./chartDom-C6NvYOT8.js";import{a as C,i as w,o as ee,r as T}from"./readTable-DOunjEkP.js";async function E(e){return await A.click(M(e).getByRole(`button`,{name:o[`label.analysis.visualize`]})),e.querySelector(`[data-slot="view-panel"]`)}function te(e,t){return e.left>=t.left-1&&e.right<=t.right+1&&e.top>=t.top-1&&e.bottom<=t.bottom+1}async function D(e,t){await A.click(await j(()=>{let t=e.querySelector(`[data-slot="chart-options-open"]`);if(!t)throw Error(`没有选项按钮`);return t}));let n=await j(()=>{let t=e.querySelector(`[data-slot="chart-options"]`);if(!t)throw Error(`选项没有打开`);return t});return await A.click(M(n).getByRole(`tab`,{name:t})),n}var O,k,A,j,M,N,P,F,I,L,R,z,B,V,H,U,W,G,K,q,J,Y,X,Z,Q;function $(){return($=e((()=>{n(),r(),c(),v(),w(),s(),{expect:O,screen:k,userEvent:A,waitFor:j,within:M}=__STORYBOOK_MODULE_TEST__,N={...d,title:`View Engine/分析视图/图型/回归`,tags:[`!dev`,`!autodocs`,`test`],parameters:{...d.parameters}},P=i(o,`label.summary.of`,{field:`金额`,fn:o[`label.summary.fn.SUM`]}),F=o[`label.analysis.row-count`],I=[`华东`,`华南`,`华北`,`西南`],L=e=>b(e),R=(e,t)=>e.querySelector(`[data-slot="chart-tile"][data-chart-type="${t}"]`),z=(e,t)=>e.map(e=>({element:e,box:e.getBoundingClientRect()})).filter(({box:e})=>!te(e,t)).map(({element:e,box:n})=>({text:e.textContent,box:[n.left,n.top,n.right,n.bottom].map(Math.round),outer:[t.left,t.top,t.right,t.bottom].map(Math.round)})),B=(e,t)=>y(e,t===`x`?`bottom`:`left`).map(e=>(e.textContent??``).trim()),V={...f,play:async({canvasElement:e})=>{await j(()=>O(L(e)).toHaveLength(4));let t=await E(e);await O(R(t,`scatter`)).not.toHaveAttribute(`aria-disabled`),await A.click(R(t,`scatter`)),await g(e);let n=await j(()=>{let t=b(e);return O(t).toHaveLength(4),t});for(let t of[`x`,`y`]){let n=await j(()=>{let n=B(e,t);return O(n.length).toBeGreaterThan(1),n});await O(new Set(n).size).toBe(n.length)}let r=e.querySelector(`[data-slot="chart-plot"] svg`).getBoundingClientRect();await O(z(n,r)).toEqual([]);let i=x(e);await O(i.map(e=>e.textContent).sort()).toEqual([P,F].sort()),await O(z(i,r)).toEqual([]);let a=S(e).filter(e=>!i.includes(e)).map(e=>e.getBoundingClientRect());for(let e of i){let t=e.getBoundingClientRect();await O(a.filter(e=>e.left<t.right&&t.left<e.right&&e.top<t.bottom&&t.top<e.bottom)).toEqual([])}let o=await j(()=>{let t=_(e);return O(t.map(e=>e.textContent).sort()).toEqual([...I].sort()),t});await O(z(o,r)).toEqual([])}},H=e=>[...e.querySelector(`[data-slot="chart-reading"] table`)?.tBodies[0]?.rows??[]].map(e=>[e.cells[0]?.textContent,e.cells[1]?.textContent]),U={...f,play:async({canvasElement:e})=>{await j(()=>O(L(e)).toHaveLength(4));let n=await E(e);await O(R(n,`funnel`)).not.toHaveAttribute(`aria-disabled`),await A.click(R(n,`funnel`));let r=await j(()=>{let t=e.querySelector(`[data-slot="chart"][data-chart="funnel"]`);return O(t).not.toBeNull(),t});await g(e);let i=b(r);await O(i).toHaveLength(4),await O(e.querySelector(`[data-slot="status-line"] [role="alert"]`)).toBeNull();let s=e.querySelector(`[data-slot="result-block"]`).getBoundingClientRect(),c=r.getBoundingClientRect();await O(c.left-s.left).toBeGreaterThanOrEqual(15),await O(s.right-c.right).toBeGreaterThanOrEqual(15),await O(z(i,c)).toEqual([]);let l=a(t(getComputedStyle(r).getPropertyValue(`--chart-1`).trim()));for(let e of i)await O(getComputedStyle(e).fill).toBe(l);let u=i.map(e=>e.getBoundingClientRect()).sort((e,t)=>e.top-t.top),d=u.map(e=>(e.left+e.right)/2);await O(d.every(e=>Math.abs(e-d[0])<1)).toBe(!0);for(let[e,t]of u.entries())e>0&&await O(t.top).toBeGreaterThanOrEqual(u[e-1].bottom);await O(r.querySelector(`[data-slot="funnel-conversion-heading"]`)).toHaveTextContent(o[`label.chart.column.conversion.previous`]),await O(r).toHaveAttribute(`data-cumulative`,`off`),await O(r.querySelector(`[data-slot="funnel-cumulative-note"]`)).toBeNull();let f=H(e);await O(f).toHaveLength(4);let p=f.map(([,e])=>Number(e)),m=u.map(e=>e.width),h=Math.max(...p);for(let[e,t]of p.entries())await O(Math.abs(m[e]/Math.max(...m)-t/h)).toBeLessThan(.02);let _=new Set(f.map(([e])=>e)),v=[...r.querySelectorAll(`[data-slot="chart-plot"] svg text`)].filter(e=>_.has(e.textContent)).map(e=>Math.round(e.getBoundingClientRect().left));await O(v).toHaveLength(4),await O(new Set(v).size).toBe(1),await O(v[0]).toBeGreaterThan(Math.max(...u.map(e=>e.right))),await A.click(M(e).getByRole(`button`,{name:o[`label.layout.table`]}));let y=await T(e),x=C(y,F);await O(f).toEqual(C(y,`仓库`).map((e,t)=>[e,x[t]]))}},W={...f,args:{...f.args,limit:1},play:async({canvasElement:e})=>{await j(()=>O(L(e)).toHaveLength(1));let t=await E(e),n=R(t,`funnel`);await O(n).toHaveAttribute(`aria-disabled`,`true`),await O(n.querySelector(`[data-slot="chart-reason"]`)).toHaveTextContent(o[`chart.fit.needs-two-stages`]),await A.click(n),await O(n).toHaveAttribute(`aria-checked`,`false`),await O(L(e)).toHaveLength(1),await O(k.queryByRole(`button`,{name:o[`label.analysis.open-editor`]})).toBeNull()}},G={...p,play:async({canvasElement:e})=>{await j(()=>O(L(e).length).toBeGreaterThan(10));let t=await E(e),n=R(t,`funnel`);await O(n).toHaveAttribute(`aria-disabled`,`true`),await O(n.querySelector(`[data-slot="chart-reason"]`)).toHaveTextContent(o[`chart.fit.needs-category`])}},K={...f,args:{...f.args,savedFunnel:{value:`amount`,order:[`CN-EAST`]}},play:async({canvasElement:e})=>{let t=M(e),n=await j(()=>{let t=e.querySelector(`[data-slot="status-line"] [role="alert"]`);return O(t).not.toBeNull(),t});await O(n).toHaveTextContent(o[`chart.funnel.too-few-stages`]),await O(L(e)).toHaveLength(0),await A.click(t.getByRole(`button`,{name:o[`label.analysis.open-chart-options`]}));let r=await j(()=>{let t=e.querySelector(`[data-slot="view-panel"]`);return O(t).not.toBeNull(),t}),i=R(r,`funnel`);await O(i).toHaveAttribute(`aria-disabled`,`true`),await O(i.querySelector(`[data-slot="chart-reason"]`)).toHaveTextContent(o[`chart.fit.needs-two-stages`]),await O(R(r,`bar`)).not.toHaveAttribute(`aria-disabled`),await A.click(R(r,`bar`)),await g(e),await j(()=>O(y(e,`bottom`)).toHaveLength(4)),await O(L(e).length).toBeGreaterThan(0),await O(e.querySelector(`[data-slot="status-line"] [role="alert"]`)).toBeNull();let a=R(e.querySelector(`[data-slot="view-panel"]`),`funnel`);await j(()=>O(a).not.toHaveAttribute(`aria-disabled`)),await A.click(a);let s=await j(()=>{let t=e.querySelector(`[data-slot="chart"][data-chart="funnel"]`);return O(t).not.toBeNull(),t});await g(e),await O(b(s)).toHaveLength(4),await O(e.querySelector(`[data-slot="status-line"] [role="alert"]`)).toBeNull()}},q=e=>e.map(e=>{let t=e.getBoundingClientRect();return(t.top+t.bottom)/2}).sort((e,t)=>e-t),J={...m,play:async({canvasElement:e})=>{await j(()=>O(L(e)).toHaveLength(4));let t=await E(e);await A.click(R(t,`combo`)),await g(e);let[n,r]=await j(()=>{let t=[y(e,`left`),y(e,`right`)];return O(t[1].length).toBeGreaterThan(1),t}),i=x(e),a=e=>i.find(t=>t.textContent===e);await O(a(P)).toBeDefined(),await O(a(F)).toBeDefined();let o=Math.max(...b(e).map(e=>e.getBoundingClientRect().right));await O(a(P).getBoundingClientRect().left).toBeGreaterThan(o),await O(r.length).toBe(n.length);let s=q(n),c=q(r);await O(s.every((e,t)=>Math.abs(e-c[t])<1)).toBe(!0)}},Y={...u,play:async({canvasElement:e})=>{await g(e);let t=await E(e);await A.click(R(t,`bar`)),await g(e);let n=await D(e,o[`label.chart.tab.display`]);await A.click(M(n).getByRole(`checkbox`,{name:o[`label.chart.percent-stack`]})),await g(e);let r=await j(()=>{let t=new Map;for(let n of b(e)){let e=n.getBoundingClientRect(),r=Math.round((e.left+e.right)/2);t.set(r,(t.get(r)??0)+e.height)}let n=[...t.values()];return O(n).toHaveLength(I.length),O(Math.max(...n)-Math.min(...n)).toBeLessThan(1.5),n});await O(r[0]).toBeGreaterThan(0);let i=B(e,`y`);await O(i).toContain(`100%`),await O(i).toContain(`0%`);let a=H(e).flatMap(e=>e.slice(1));await O(a.filter(e=>/%$/.test(e??``)).length).toBeGreaterThan(0)}},X={...h,play:async({canvasElement:e})=>{await g(e);let t=await j(()=>{let t=H(e);return O(t.length).toBeGreaterThan(10),t.map(([,e])=>Number((e??``).replace(/[^\d]/g,``)))}),n=t.filter(e=>e>0).length;await O(n).toBeLessThan(t.length),await j(()=>O(b(e)).toHaveLength(t.length)),await E(e);let r=await D(e,o[`label.chart.tab.display`]);await A.click(M(r).getByRole(`button`,{name:o[`label.chart.missing.gap`]})),await j(()=>O(b(e)).toHaveLength(n))}},Z={...l,play:async({canvasElement:e})=>{let t=M(e),n=await T(e);await j(()=>O(C(n,`目的城市`).length).toBeGreaterThan(3)),await O(ee(n).slice(0,3)).toEqual([`目的城市`,`承运商`,`运输方式`]),await O(e.querySelector(`[data-slot="status-line"] [data-tone="error"]`)).toBeNull();let r=await E(e);for(let[e,t]of Object.entries({bar:`chart.fit.too-many-dimensions`,line:`chart.fit.too-many-dimensions`,pie:`chart.fit.needs-one-dimension`,heatmap:`chart.fit.needs-two-dimensions`,metric:`chart.fit.needs-no-dimension`})){let n=R(r,e);await O(n).toHaveAttribute(`aria-disabled`,`true`),await O(n.querySelector(`[data-slot="chart-reason"]`)).toHaveTextContent(o[t])}await O(R(r,`table`)).toHaveAttribute(`aria-checked`,`true`),await A.click(t.getByRole(`button`,{name:o[`label.layout.chart`]})),await O(await t.findByText(i(o,`label.analysis.as-table`,{type:o[`label.chart.type.bar`],reason:o[`chart.fit.too-many-dimensions`]}))).toBeVisible(),await O(await T(e)).toBeVisible(),await O(b(e)).toHaveLength(0),await O(e.querySelector(`[data-slot="status-line"] [data-tone="error"]`)).toBeNull()}},Q=[`ScatterReadsItsPoints`,`FunnelFromTheRows`,`FunnelNeedsStages`,`FunnelNeedsCategory`,`SavedFunnelRepaired`,`ComboPutsTheAmountRight`,`PercentStackReachesTheTop`,`MissingLeftAsGaps`,`ThreeDimensionsRunAsTable`],V.parameters={...V.parameters,docs:{...V.parameters?.docs,source:{originalSource:`{
  ...DisplayBarChart,
  play: async ({
    canvasElement
  }) => {
    await waitFor(() => expect(bars(canvasElement)).toHaveLength(4));
    const panel = await visualize(canvasElement);
    await expect(chartTile(panel, 'scatter')).not.toHaveAttribute('aria-disabled');
    await userEvent.click(chartTile(panel, 'scatter'));
    await chartsDrawn(canvasElement);
    const symbols = await waitFor(() => {
      const found = drawnMarks(canvasElement);
      expect(found).toHaveLength(4);
      return found;
    });

    // Every tick once, on both axes.
    for (const axis of ['x', 'y'] as const) {
      const ticks = await waitFor(() => {
        const found = ticksOf(canvasElement, axis);
        expect(found.length).toBeGreaterThan(1);
        return found;
      });
      await expect(new Set(ticks).size).toBe(ticks.length);
    }

    // Every point inside the drawing, whole; that each sits a radius in from
    // the plot's edges is measured by coordinate in the package
    // (test/analysisChart.test.tsx「a scatter」).
    const surface = canvasElement.querySelector('[data-slot="chart-plot"] svg')!.getBoundingClientRect();
    await expect(outside(symbols, surface)).toEqual([]);

    // Each axis titled as the table heads its column, inside the drawing.
    const titles = axisTitles(canvasElement);
    await expect(titles.map(title => title.textContent).sort()).toEqual([AMOUNT_HEADER, COUNT_HEADER].sort());
    await expect(outside(titles, surface)).toEqual([]);
    // And clear of the numbers on its own axis.
    const tickBoxes = axisTexts(canvasElement).filter(text => !titles.includes(text)).map(tick => tick.getBoundingClientRect());
    for (const title of titles) {
      const box = title.getBoundingClientRect();
      await expect(tickBoxes.filter(tick => tick.left < box.right && box.left < tick.right && tick.top < box.bottom && box.top < tick.bottom)).toEqual([]);
    }

    // Four points are four named things, each name inside the drawing.
    // They land once the points have finished moving in.
    const names = await waitFor(() => {
      const found = valueLabels(canvasElement);
      expect(found.map(name => name.textContent).sort()).toEqual([...WAREHOUSES].sort());
      return found;
    });
    await expect(outside(names, surface)).toEqual([]);

    // The tooltip is headed by the group the point is: pinned in the
    // package (test/scatterOption.test.ts).
  }
}`,...V.parameters?.docs?.source},description:{story:`散点：刻度不重复，点不出界，两根轴各有标题，点说得出自己是哪一组。

审查（2026-09-23）在散点上看到四件事：横轴读成「0 1 1 2 2」——订单数是整数，
比例尺却在中间放了 0.5、1.5，订单数的格式把它们写成 1、2；落在最大值上的点半个
在绘图区外、被图自己的框切掉；两根轴都没有标题，看不出哪根是订单数、哪根是金额；
点上没有名字，也说不出是哪个仓库。这里从柱状图经图型网格换成散点——用户走的就是
这条路——然后量：两根轴的刻度都各不相同，每个点的框都在绘图区里，两个标题是表头
那两句、都在图里且不压刻度，四个点各带仓库名。四个仓库的极值不正好落在最末
一个刻度上；落在上面的那一种（点心压在绘图区边上）由包里的测试按坐标量着。`,...V.parameters?.docs?.description}}},U.parameters={...U.parameters,docs:{...U.parameters?.docs,source:{originalSource:`{
  ...DisplayBarChart,
  play: async ({
    canvasElement
  }) => {
    await waitFor(() => expect(bars(canvasElement)).toHaveLength(4));
    const panel = await visualize(canvasElement);
    await expect(chartTile(panel, 'funnel')).not.toHaveAttribute('aria-disabled');
    await userEvent.click(chartTile(panel, 'funnel'));
    const funnel = await waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>('[data-slot="chart"][data-chart="funnel"]');
      expect(found).not.toBeNull();
      return found!;
    });
    await chartsDrawn(canvasElement);
    const stageBars = drawnMarks(funnel);
    await expect(stageBars).toHaveLength(4);
    await expect(canvasElement.querySelector('[data-slot="status-line"] [role="alert"]')).toBeNull();

    // The result's 16px gutter, on both sides.
    const block = canvasElement.querySelector('[data-slot="result-block"]')!.getBoundingClientRect();
    const box = funnel.getBoundingClientRect();
    await expect(box.left - block.left).toBeGreaterThanOrEqual(15);
    await expect(block.right - box.right).toBeGreaterThanOrEqual(15);
    await expect(outside(stageBars, box)).toEqual([]);
    // The palette's first slot, as a lone series wears it — read back off
    // the stylesheet, as the drawing was handed it.
    const first = formatRgb(parse(getComputedStyle(funnel).getPropertyValue('--chart-1').trim()));
    for (const bar of stageBars) await expect(getComputedStyle(bar).fill).toBe(first);
    // One bar a stage, all centred on one line, each as long as its number
    // against the longest — one length for one number. The library's
    // trapezoids drew each stage from its own width to the next one's, two
    // numbers in one area (2026-09-23 audit).
    const boxes = stageBars.map(bar => bar.getBoundingClientRect()).sort((a, b) => a.top - b.top);
    const centres = boxes.map(one => (one.left + one.right) / 2);
    await expect(centres.every(centre => Math.abs(centre - centres[0]!) < 1)).toBe(true);
    // Rectangles: a path's box is the bar itself, stage under stage.
    for (const [index, box] of boxes.entries()) if (index > 0) await expect(box.top).toBeGreaterThanOrEqual(boxes[index - 1]!.bottom);
    await expect(funnel.querySelector('[data-slot="funnel-conversion-heading"]')).toHaveTextContent(zhCN['label.chart.column.conversion.previous']);

    // Nothing added up, so nothing to say about it — and each stage is the
    // number the table shows for its warehouse.
    await expect(funnel).toHaveAttribute('data-cumulative', 'off');
    await expect(funnel.querySelector('[data-slot="funnel-cumulative-note"]')).toBeNull();
    const stages = readingOf(canvasElement);
    await expect(stages).toHaveLength(4);
    // Each bar's length is its stage's number against the longest.
    const measured = stages.map(([, count]) => Number(count));
    const widths = boxes.map(box => box.width);
    const longest = Math.max(...measured);
    for (const [index, count] of measured.entries()) await expect(Math.abs(widths[index]! / Math.max(...widths) - count / longest)).toBeLessThan(0.02);
    // The words stand in one column, past the longest bar: every stage's
    // name starts at one left edge.
    const names = new Set(stages.map(([name]) => name));
    const lefts = funnel.querySelectorAll('[data-slot="chart-plot"] svg text');
    const starts = [...lefts].filter(text => names.has(text.textContent)).map(text => Math.round(text.getBoundingClientRect().left));
    await expect(starts).toHaveLength(4);
    await expect(new Set(starts).size).toBe(1);
    await expect(starts[0]).toBeGreaterThan(Math.max(...boxes.map(box => box.right)));
    await userEvent.click(within(canvasElement).getByRole('button', {
      name: zhCN['label.layout.table']
    }));
    const table = await findDataTable(canvasElement);
    const counts = readColumn(table, COUNT_HEADER);
    await expect(stages).toEqual(readColumn(table, '仓库').map((name, index) => [name, counts[index]]));
  }
}`,...U.parameters?.docs?.source},description:{story:`漏斗：从结果行起头，当场画出；每一段就是表格里那个仓库的数；条用色板的第一档，
留着结果区的边，百分比说明是相对上一段的转化率。

审查（2026-09-23）：漏斗贴着边、颜色是按钮的 primary 而不是图表色板、百分比
没说是什么的百分比。四个仓库是四个阶段，从图型网格选中即按行来的顺序画出，
状态行什么也不说；图离结果区左右各留 16px，每根条都在漏斗自己的框里，百分比
那一列上面写着「转化率（相对上一段）」。图表审查（同日 P0-1）：漏斗缺省把后面
各段累加进前面，「华东 6」而华东只有 2 条记录——与表格对不上。现在缺省不累计：
漏斗读屏表里每一段的数就是切到表格后那个仓库的记录数，图上也不写「累计」。`,...U.parameters?.docs?.description}}},W.parameters={...W.parameters,docs:{...W.parameters?.docs,source:{originalSource:`{
  ...DisplayBarChart,
  args: {
    ...DisplayBarChart.args,
    limit: 1
  },
  play: async ({
    canvasElement
  }) => {
    await waitFor(() => expect(bars(canvasElement)).toHaveLength(1));
    const panel = await visualize(canvasElement);
    const funnel = chartTile(panel, 'funnel');
    await expect(funnel).toHaveAttribute('aria-disabled', 'true');
    await expect(funnel.querySelector('[data-slot="chart-reason"]')).toHaveTextContent(zhCN['chart.fit.needs-two-stages']);
    // And pressing it changes nothing: the bar stays, nothing is refused.
    await userEvent.click(funnel);
    await expect(funnel).toHaveAttribute('aria-checked', 'false');
    await expect(bars(canvasElement)).toHaveLength(1);
    await expect(screen.queryByRole('button', {
      name: zhCN['label.analysis.open-editor']
    })).toBeNull();
  }
}`,...W.parameters?.docs?.source},description:{story:`一组画不成漏斗：卡片灰着，底下写明理由。

只要前 1 组时结果只有一个仓库，一个阶段没有可以转化的上一段。审查时它可选，
选中之后只有一句「漏斗至少要有两个阶段」和一张空图，而那句话的按钮「打开分析」
打开的是托盘——那里改不了图。现在这张卡片与热力图一样灰着，写的是缺什么。`,...W.parameters?.docs?.description}}},G.parameters={...G.parameters,docs:{...G.parameters?.docs,source:{originalSource:`{
  ...DisplayDailyNewestFirst,
  play: async ({
    canvasElement
  }) => {
    await waitFor(() => expect(bars(canvasElement).length).toBeGreaterThan(10));
    const panel = await visualize(canvasElement);
    const funnel = chartTile(panel, 'funnel');
    await expect(funnel).toHaveAttribute('aria-disabled', 'true');
    await expect(funnel.querySelector('[data-slot="chart-reason"]')).toHaveTextContent(zhCN['chart.fit.needs-category']);
  }
}`,...G.parameters?.docs?.source},description:{story:`按日的结果画不成漏斗：一天不是流程里的一步，从昨天到今天也谈不上转化。`,...G.parameters?.docs?.description}}},K.parameters={...K.parameters,docs:{...K.parameters?.docs,source:{originalSource:`{
  ...DisplayBarChart,
  args: {
    ...DisplayBarChart.args,
    savedFunnel: {
      value: 'amount',
      order: ['CN-EAST']
    }
  },
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    // Refused, so nothing ran: the status line says why, and no bar is drawn.
    const alert = await waitFor(() => {
      const found = canvasElement.querySelector('[data-slot="status-line"] [role="alert"]');
      expect(found).not.toBeNull();
      return found!;
    });
    await expect(alert).toHaveTextContent(zhCN['chart.funnel.too-few-stages']);
    await expect(bars(canvasElement)).toHaveLength(0);
    await userEvent.click(canvas.getByRole('button', {
      name: zhCN['label.analysis.open-chart-options']
    }));
    const panel = await waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>('[data-slot="view-panel"]');
      expect(found).not.toBeNull();
      return found!;
    });
    // The funnel is judged by the one stage it names; bars are offered.
    const funnel = chartTile(panel, 'funnel');
    await expect(funnel).toHaveAttribute('aria-disabled', 'true');
    await expect(funnel.querySelector('[data-slot="chart-reason"]')).toHaveTextContent(zhCN['chart.fit.needs-two-stages']);
    await expect(chartTile(panel, 'bar')).not.toHaveAttribute('aria-disabled');

    // A pick with no rows fits the draft and runs: a bar chart over the four
    // warehouses, a series for each metric the family had never measured.
    await userEvent.click(chartTile(panel, 'bar'));
    await chartsDrawn(canvasElement);
    await waitFor(() => expect(axisTicks(canvasElement, 'bottom')).toHaveLength(4));
    await expect(bars(canvasElement).length).toBeGreaterThan(0);
    await expect(canvasElement.querySelector('[data-slot="status-line"] [role="alert"]')).toBeNull();

    // With rows on screen the funnel draws: the stage it was saved with
    // first, the rest from the rows.
    const offered = chartTile(canvasElement.querySelector<HTMLElement>('[data-slot="view-panel"]')!, 'funnel');
    await waitFor(() => expect(offered).not.toHaveAttribute('aria-disabled'));
    await userEvent.click(offered);
    const drawn = await waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>('[data-slot="chart"][data-chart="funnel"]');
      expect(found).not.toBeNull();
      return found!;
    });
    await chartsDrawn(canvasElement);
    await expect(drawnMarks(drawn)).toHaveLength(4);
    await expect(canvasElement.querySelector('[data-slot="status-line"] [role="alert"]')).toBeNull();
  }
}`,...K.parameters?.docs?.source},description:{story:`存下来画不出的漏斗，从图型网格修好：换一个画得出的图型就跑，跑出行来漏斗又可选。

漏斗只在画得出的地方给选（#1803）之前，一个阶段的漏斗是存得下来的。打开它
什么也不跑——配置被拒——状态行说「漏斗至少要有两个阶段」，按钮「打开图表选项」
打开的是图型网格；可网格上按什么都不起作用，因为没有行可重画。现在：漏斗那张
卡片照它自己点名的阶段判，灰着说缺什么；按柱状图，图按草稿的形态装槽、视图
变合法、当场跑，四根柱子出来，状态行不再报错；有了行，漏斗可选，选中它时存
下的那一个阶段排第一，其余从行里补齐，四段都画出来。`,...K.parameters?.docs?.description}}},J.parameters={...J.parameters,docs:{...J.parameters?.docs,source:{originalSource:`{
  ...DisplayCountBars,
  play: async ({
    canvasElement
  }) => {
    await waitFor(() => expect(bars(canvasElement)).toHaveLength(4));
    const panel = await visualize(canvasElement);
    await userEvent.click(chartTile(panel, 'combo'));
    await chartsDrawn(canvasElement);
    const [left, right] = await waitFor(() => {
      const found = [axisTicks(canvasElement, 'left'), axisTicks(canvasElement, 'right')];
      expect(found[1].length).toBeGreaterThan(1);
      return found;
    });
    // The right axis is titled by the amount's column, and stands right of
    // the marks; the left by the count's.
    const titles = axisTitles(canvasElement);
    const titleOf = (text: string) => titles.find(title => title.textContent === text);
    await expect(titleOf(AMOUNT_HEADER)).toBeDefined();
    await expect(titleOf(COUNT_HEADER)).toBeDefined();
    const east = Math.max(...drawnMarks(canvasElement).map(mark => mark.getBoundingClientRect().right));
    await expect(titleOf(AMOUNT_HEADER)!.getBoundingClientRect().left).toBeGreaterThan(east);
    // One set of gridlines: as many ticks each side, pairwise level.
    await expect(right.length).toBe(left.length);
    const leftAt = middles(left);
    const rightAt = middles(right);
    await expect(leftAt.every((at, index) => Math.abs(at - rightAt[index]!) < 1)).toBe(true);
  }
}`,...J.parameters?.docs?.source},description:{story:`组合图的右轴：订单数的柱上加金额的线，金额坐右轴、右轴写「金额的总和」，两根
轴的刻度落在同一组网格线上。

从前槽位层看不到数，组合图的第二个指标永远在左轴：个位数的订单数与上千的金额
同一把尺子，线贴着零。现在按两者量的是什么判（计数对金额）——不看值。这里从
订单数的柱状图在图型网格里选「组合图」，量：右边有刻度，右轴的标题是金额那一
列的表头、在绘图区右侧；左右两列刻度一样多，每一对的垂直中线相差不到 1px
（审查 P2-4：两根轴各取各的刻度，右轴的数落在两条网格线之间）。`,...J.parameters?.docs?.description}}},Y.parameters={...Y.parameters,docs:{...Y.parameters?.docs,source:{originalSource:`{
  ...DisplayHeatmapChart,
  play: async ({
    canvasElement
  }) => {
    await chartsDrawn(canvasElement);
    const panel = await visualize(canvasElement);
    await userEvent.click(chartTile(panel, 'bar'));
    await chartsDrawn(canvasElement);
    const options = await optionsPage(canvasElement, zhCN['label.chart.tab.display']);
    await userEvent.click(within(options).getByRole('checkbox', {
      name: zhCN['label.chart.percent-stack']
    }));
    await chartsDrawn(canvasElement);
    const stacks = await waitFor(() => {
      const byColumn = new Map<number, number>();
      for (const mark of drawnMarks(canvasElement)) {
        const box = mark.getBoundingClientRect();
        const column = Math.round((box.left + box.right) / 2);
        byColumn.set(column, (byColumn.get(column) ?? 0) + box.height);
      }
      const heights = [...byColumn.values()];
      expect(heights).toHaveLength(WAREHOUSES.length);
      expect(Math.max(...heights) - Math.min(...heights)).toBeLessThan(1.5);
      return heights;
    });
    await expect(stacks[0]).toBeGreaterThan(0);
    const ticks = ticksOf(canvasElement, 'y');
    await expect(ticks).toContain('100%');
    await expect(ticks).toContain('0%');
    // The reading table says each part's share beside its value, as the
    // tooltip does (audit): a screen reader heard values of stacks the
    // picture drew as equal heights.
    const cells = readingOf(canvasElement).flatMap(row => row.slice(1));
    await expect(cells.filter(cell => /%$/.test(cell ?? '')).length).toBeGreaterThan(0);
  }
}`,...Y.parameters?.docs?.source},description:{story:`百分比堆叠：仓库 × 状态的金额，从热力图换成柱状图（按状态拆分），在显示页勾
「百分比堆叠」——每个仓库的那一摞都顶到绘图区的顶，纵轴写到 100%。

构成类的问题，饼图画不了几个仓库并排；堆叠柱按金额画，高低不同的摞读不出
比例（审查 P1-10）。量：每个仓库各段高度之和相同（差不到 1.5px），纵轴从
「0%」写到「100%」。`,...Y.parameters?.docs?.description}}},X.parameters={...X.parameters,docs:{...X.parameters?.docs,source:{originalSource:`{
  ...DisplayDailyQuietDays,
  play: async ({
    canvasElement
  }) => {
    await chartsDrawn(canvasElement);
    const days = await waitFor(() => {
      const rows = readingOf(canvasElement);
      expect(rows.length).toBeGreaterThan(10);
      return rows.map(([, count]) => Number((count ?? '').replace(/[^\\d]/g, '')));
    });
    const busy = days.filter(count => count > 0).length;
    await expect(busy).toBeLessThan(days.length);
    // Filled: a dot a day, the quiet ones on zero.
    await waitFor(() => expect(drawnMarks(canvasElement)).toHaveLength(days.length));
    await visualize(canvasElement);
    const options = await optionsPage(canvasElement, zhCN['label.chart.tab.display']);
    await userEvent.click(within(options).getByRole('button', {
      name: zhCN['label.chart.missing.gap']
    }));
    // Gapped: a dot for each day that had a waybill, and none for the rest.
    await waitFor(() => expect(drawnMarks(canvasElement)).toHaveLength(busy));
  }
}`,...X.parameters?.docs?.source},description:{story:`缺值：留空（断开）。只看发往杭州、上海的运单，没单的日子默认按 0 画（确知
没有记录、记录数可加）；在显示页选「留空（断开）」，那些日子不再有点，折线
在那里断开——有单的日子一天一个点，其余没有。`,...X.parameters?.docs?.description}}},Z.parameters={...Z.parameters,docs:{...Z.parameters?.docs,source:{originalSource:`{
  ...DisplayThreeDimensions,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    const table = await findDataTable(canvasElement);
    await waitFor(() => expect(readColumn(table, '目的城市').length).toBeGreaterThan(3));
    await expect(readHeaders(table).slice(0, 3)).toEqual(['目的城市', '承运商', '运输方式']);
    await expect(canvasElement.querySelector('[data-slot="status-line"] [data-tone="error"]')).toBeNull();

    // Every chart greyed, each with its own reason; the table chosen.
    const panel = await visualize(canvasElement);
    const reasons = {
      bar: 'chart.fit.too-many-dimensions',
      line: 'chart.fit.too-many-dimensions',
      pie: 'chart.fit.needs-one-dimension',
      heatmap: 'chart.fit.needs-two-dimensions',
      metric: 'chart.fit.needs-no-dimension'
    } as const;
    for (const [type, reason] of Object.entries(reasons)) {
      const tile = chartTile(panel, type);
      await expect(tile).toHaveAttribute('aria-disabled', 'true');
      await expect(tile.querySelector('[data-slot="chart-reason"]')).toHaveTextContent(zhCN[reason]);
    }
    await expect(chartTile(panel, 'table')).toHaveAttribute('aria-checked', 'true');

    // The chart layout draws what it can — the table — and says why.
    await userEvent.click(canvas.getByRole('button', {
      name: zhCN['label.layout.chart']
    }));
    await expect(await canvas.findByText(formatMessage(zhCN, 'label.analysis.as-table', {
      type: zhCN['label.chart.type.bar'],
      reason: zhCN['chart.fit.too-many-dimensions']
    }))).toBeVisible();
    await expect(await findDataTable(canvasElement)).toBeVisible();
    await expect(drawnMarks(canvasElement)).toHaveLength(0);
    await expect(canvasElement.querySelector('[data-slot="status-line"] [data-tone="error"]')).toBeNull();
  }
}`,...Z.parameters?.docs?.source},description:{story:`三个维度的分析以表格跑出来，图表不拦它（D20：怎么看是展示，不是问题）。

直角坐标图最多消化两个维度；从前图表在表格布局下也参与校验，第三个维度报
「图表没有用上每一个维度」，连表格也跑不起来。现在：表格照跑、状态行不报错；
「可视化」里每种图都灰着、写着缺什么，表格是选中的那一张；切到「图表」仍是这
张表，状态行说一句为什么——不是一个被拦住的状态。`,...Z.parameters?.docs?.description}}}})))()}$();export{J as ComboPutsTheAmountRight,U as FunnelFromTheRows,G as FunnelNeedsCategory,W as FunnelNeedsStages,X as MissingLeftAsGaps,Y as PercentStackReachesTheTop,K as SavedFunnelRepaired,V as ScatterReadsItsPoints,Z as ThreeDimensionsRunAsTable,Q as __namedExportsOrder,N as default};
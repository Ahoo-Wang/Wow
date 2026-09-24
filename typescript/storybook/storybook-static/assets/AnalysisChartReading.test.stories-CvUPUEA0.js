import{n as e}from"./rolldown-runtime-DkW27tQK.js";import{t}from"./jsx-runtime-DeHZSEgm.js";import{Af as n,R as r,z as i}from"./styles-Dpj2y9Rj.js";import{t as a}from"./src-DsG2EjN4.js";import{A as o,O as s,S as c,c as l,d as u,h as d,j as f,k as p,n as m,s as h}from"./AnalysisWorkbench.stories-DyF9m040.js";import{a as g,d as _,f as v,h as y,l as b,n as x,o as S,r as C}from"./chartDom-C6NvYOT8.js";import{n as w,t as T}from"./contrast-BGE4rlQ_.js";function E(e){for(let t of[`mouseover`,`mousemove`])e.dispatchEvent(new MouseEvent(t,{bubbles:!0,...I(e)}))}async function D(e,t){await A.click(await j(()=>{let t=e.querySelector(`[data-slot="chart-options-open"]`);if(!t)throw Error(`没有选项按钮`);return t}));let n=await j(()=>{let t=e.querySelector(`[data-slot="chart-options"]`);if(!t)throw Error(`选项没有打开`);return t});return await A.click(M(n).getByRole(`tab`,{name:t})),n}async function ee(e){return await A.click(M(e).getByRole(`button`,{name:i[`label.analysis.visualize`]})),e.querySelector(`[data-slot="view-panel"]`)}var O,k,A,j,M,N,P,F,I,L,R,z,B,V,H,U,W,G,K,q,J,Y,X,Z,Q;function $(){return($=e((()=>{r(),a(),o(),b(),w(),O=t(),{expect:k,userEvent:A,waitFor:j,within:M}=__STORYBOOK_MODULE_TEST__,N={...f,title:`View Engine/分析视图/图表读法/回归`,tags:[`!dev`,`!autodocs`,`test`],parameters:{...f.parameters,docs:{description:{component:`图表怎么读（2026-09-23 图表审查的 P2 打磨）：悬停的柱子更显眼而不是更淡，
堆叠段内的数不糊、不重复，两根轴的刻度落在同一组网格线上，饼的图例贴着饼，
中文纵轴标题不侧躺，数值标签要么都写要么都不写，长名字的排行横着放，热力图
格子上的数用与格子对比的墨色，指标卡站在结果中间，补出的 0 不写数。每一条都
量画出来的样子：等图画完（\`chartsDrawn\`）再量。`}}}},P=e=>{let t=n(e);if(!t||!(`r`in t))throw Error(`not a colour: ${e}`);return{r:t.r,g:t.g,b:t.b}},F={r:1,g:1,b:1},I=e=>{let t=e.getBoundingClientRect();return{clientX:t.left+t.width/2,clientY:t.top+t.height/2}},L={...m,play:async({canvasElement:e})=>{await g(e);let t=(await j(()=>{let t=S(e);return k(t).toHaveLength(4),t}))[1],n=t.getAttribute(`fill`),r=P(n);E(t),await j(()=>k(T(P(t.getAttribute(`fill`)),F)).toBeGreaterThan(T(r,F)+.5));let i=I(t);await k(document.elementsFromPoint(i.clientX,i.clientY)[0]).toBe(t)}},R=(e,t)=>e.querySelector(`[data-slot="chart-tile"][data-chart-type="${t}"]`),z={...d,play:async({canvasElement:e})=>{await g(e);let t=await ee(e);await A.click(R(t,`bar`)),await g(e);let n=await D(e,i[`label.chart.tab.display`]);await A.click(M(n).getByRole(`checkbox`,{name:i[`label.chart.stacked`]})),await g(e);let r=await j(()=>{let t=y(e).map(e=>e.textContent??``);return k(t.length).toBeGreaterThan(4),t});await k(r).not.toContain(`¥0`),await k(r.filter(e=>e===`¥2,450`)).toHaveLength(1);let a=_(e);await k(a.length).toBeGreaterThan(0);for(let e of a){await k(e.getAttribute(`stroke`)).toBeNull();let t=I(e),n=document.elementsFromPoint(t.clientX,t.clientY).find(e=>e.tagName===`path`),r=P(n.getAttribute(`fill`)),i=P(e.getAttribute(`fill`)),a=T(i,F)>2?F:{r:.04,g:.04,b:.04};await k(T(i,r)).toBeGreaterThanOrEqual(T(a,r))}}},B=e=>e.map(e=>I(e).clientY).sort((e,t)=>e-t),V={...s,play:async({canvasElement:e})=>{await g(e);let[t,n]=await j(()=>{let t=[x(e,`left`),x(e,`right`)];return k(t[1].length).toBeGreaterThan(1),t});await k(n.length).toBe(t.length);let r=B(t),i=B(n);await k(r.every((e,t)=>Math.abs(e-i[t])<1)).toBe(!0),await k(n.every(e=>/^\d+$/.test((e.textContent??``).trim()))).toBe(!0)}},H={...c,play:async({canvasElement:e})=>{await g(e);let t=e.querySelector(`[data-slot="chart"]`);await k(t).toHaveAttribute(`data-legend`,`right`);let n=t.querySelector(`[data-slot="chart-legend"]`).getBoundingClientRect(),r=[...S(t),...y(t)].map(e=>e.getBoundingClientRect()),i=Math.max(...r.map(e=>e.right)),a=Math.min(...r.map(e=>e.left));await k(n.left-i).toBeLessThanOrEqual(64),await k(n.left).toBeGreaterThan(i);let o=t.getBoundingClientRect();await k(Math.abs(a-o.left-(o.right-n.right))).toBeLessThan(o.width*.2)}},U={...m,play:async({canvasElement:e})=>{await g(e);let t=(await j(()=>{let t=C(e).find(e=>e.textContent===`金额的总和`);return k(t).toBeDefined(),t})).getBoundingClientRect();await k(t.width).toBeGreaterThan(t.height);let n=x(e,`left`),r=Math.min(...n.map(e=>e.getBoundingClientRect().top));await k(t.bottom).toBeLessThanOrEqual(r);for(let e of n)await k(v(t,e.getBoundingClientRect())).toBe(!1)}},W=e=>{let t=/matrix\(([^)]*)\)/.exec(e.getAttribute(`transform`)??``);return Math.abs(Number(t?.[1]?.split(`,`)[1]??0))>.01},G={...p,play:async({canvasElement:e})=>{await g(e);let t=await j(()=>{let t=S(e);return k(t.length).toBeGreaterThan(10),t}),n=await j(()=>{let n=y(e).filter(e=>(e.textContent??``)!==``);return k(n).toHaveLength(t.length),n});await k(n.some(W)).toBe(!1)}},K={...p,decorators:[e=>(0,O.jsx)(`div`,{style:{width:360},children:(0,O.jsx)(e,{})})],play:async({canvasElement:e})=>{await g(e);let t=await j(()=>{let t=S(e);return k(t.length).toBeGreaterThan(10),t}),n=y(e).filter(e=>(e.textContent??``)!==``);await k([0,t.length]).toContain(n.length),await k(new Set(n.map(W)).size).toBeLessThanOrEqual(1);for(let[e,t]of n.entries())for(let r of n.slice(e+1))await k(v(t.getBoundingClientRect(),r.getBoundingClientRect())).toBe(!1)}},q={...u,play:async({canvasElement:e})=>{await A.click(await M(e).findByRole(`button`,{name:i[`label.layout.chart`]})),await g(e);let t=e.querySelector(`[data-slot="chart"]`);await k(t).toHaveAttribute(`data-orientation`,`horizontal`);let n=S(e).map(e=>e.getBoundingClientRect());await k(n).toHaveLength(9),await k(n.every(e=>e.width>=e.height)).toBe(!0);let r=x(e,`left`);await k(r.length).toBeGreaterThan(0),await k(r.every(e=>e.getBoundingClientRect().height<20)).toBe(!0)}},J={...d,play:async({canvasElement:e})=>{await g(e);let t=await j(()=>{let t=_(e);return k(t.length).toBeGreaterThan(0),t}),n=new Set;for(let e of t){let t=I(e),r=document.elementsFromPoint(t.clientX,t.clientY).find(e=>e.tagName===`path`),i=P(r.getAttribute(`fill`)),a=e.getAttribute(`fill`);n.add(a);let o=Math.max(T({r:.04,g:.04,b:.04},i),T(F,i));await k(T(P(a),i)).toBeGreaterThanOrEqual(o-.01),await k(e.getAttribute(`stroke`)).toBeNull()}}},Y={...l,play:async({canvasElement:e})=>{let t=await j(()=>{let t=e.querySelector(`[data-slot="metric-card"]`);return k(t).not.toBeNull(),t});await g(e);let n=e.querySelector(`[data-slot="analysis-result"]`).getBoundingClientRect(),r=t.getBoundingClientRect();await k(Math.abs(r.left-n.left-(n.right-r.right))).toBeLessThan(2),await k(Math.abs(r.top-n.top-(n.bottom-r.bottom))).toBeLessThan(28);let i=t.querySelector(`[data-slot="metric-value"]`).getBoundingClientRect();await k(i.height).toBeGreaterThanOrEqual(44)}},X=e=>[...e.querySelector(`[data-slot="chart-reading"] table`)?.tBodies[0]?.rows??[]].map(e=>[...e.cells].map(e=>e.textContent??``)),Z={...h,args:{...h.args,labels:!0},play:async({canvasElement:e})=>{await g(e);let t=await j(()=>{let t=X(e);return k(t.length).toBeGreaterThan(10),t}),n=t.filter(e=>e[1]?.includes(i[`label.chart.filled.DAY`].replace(`{value}`,`0`))),r=t.length-n.length;await k(n.length).toBeGreaterThan(0),await k(r).toBeGreaterThan(0),await j(()=>k(S(e)).toHaveLength(t.length)),await j(()=>k(y(e).filter(e=>(e.textContent??``)!==``)).toHaveLength(r)),await k(y(e).some(e=>e.textContent===`0`)).toBe(!1)}},Q=[`HoverReadsAsEmphasis`,`StackedPartsReadable`,`TwoAxesShareTheGrid`,`PieLegendBesideThePie`,`ChineseTitleUpright`,`ValueLabelsAllOrNone`,`ValueLabelsAllOrNoneNarrow`,`LongNamesLieDown`,`HeatmapInkStandsOff`,`TrendCardStandsInTheMiddle`,`FilledZerosWriteNothing`],L.parameters={...L.parameters,docs:{...L.parameters?.docs,source:{originalSource:`{
  ...DisplayBarChart,
  play: async ({
    canvasElement
  }) => {
    await chartsDrawn(canvasElement);
    const marks = await waitFor(() => {
      const found = drawnMarks(canvasElement);
      expect(found).toHaveLength(4);
      return found;
    });
    const read = marks[1]!;
    const before = read.getAttribute('fill')!;
    const rest = rgb(before);
    hover(read);
    // Darker on this light page: more contrast against the ground, never
    // less (the colour is written anew on hover, so it is read as a colour).
    await waitFor(() => expect(contrastRatio(rgb(read.getAttribute('fill')!), WHITE)).toBeGreaterThan(contrastRatio(rest, WHITE) + 0.5));
    // Nothing is laid over it: the band behind the category is behind.
    const at = middle(read);
    await expect(document.elementsFromPoint(at.clientX, at.clientY)[0]).toBe(read);
  }
}`,...L.parameters?.docs?.source},description:{story:`悬停的柱子往墨色走一步：浅色主题下更深，对底色的对比更高；它的数也照旧是
字的颜色。从前库把一层半透明的灰带盖在柱子上，读的那一根反而最淡、像被禁用
（审查）。`,...L.parameters?.docs?.description}}},z.parameters={...z.parameters,docs:{...z.parameters?.docs,source:{originalSource:`{
  ...DisplayHeatmapChart,
  play: async ({
    canvasElement
  }) => {
    await chartsDrawn(canvasElement);
    const panel = await visualize(canvasElement);
    await userEvent.click(tile(panel, 'bar'));
    await chartsDrawn(canvasElement);
    const options = await optionsPage(canvasElement, zhCN['label.chart.tab.display']);
    await userEvent.click(within(options).getByRole('checkbox', {
      name: zhCN['label.chart.stacked']
    }));
    await chartsDrawn(canvasElement);
    const texts = await waitFor(() => {
      const found = valueLabels(canvasElement).map(label => label.textContent ?? '');
      expect(found.length).toBeGreaterThan(4);
      return found;
    });
    await expect(texts).not.toContain('¥0');
    // 华北 is one part: its number once, over the stack.
    await expect(texts.filter(text => text === '¥2,450')).toHaveLength(1);

    // Every part inside its segment: no halo, and the better of the two
    // inks against the segment's own colour.
    const parts = markLabels(canvasElement);
    await expect(parts.length).toBeGreaterThan(0);
    for (const part of parts) {
      await expect(part.getAttribute('stroke')).toBeNull();
      const at = middle(part);
      const segment = document.elementsFromPoint(at.clientX, at.clientY).find(hit => hit.tagName === 'path')!;
      const fill = rgb(segment.getAttribute('fill')!);
      const ink = rgb(part.getAttribute('fill')!);
      const other = contrastRatio(ink, WHITE) > 2 ? WHITE : {
        r: 0.04,
        g: 0.04,
        b: 0.04
      };
      await expect(contrastRatio(ink, fill)).toBeGreaterThanOrEqual(contrastRatio(other, fill));
    }
  }
}`,...z.parameters?.docs?.source},description:{story:`堆叠柱段内的数：不写 ¥0，一段的栈只写一次（栈顶的合计），段内的数没有白边、
墨色是对着这一段的颜色取的（审查：浅色下黑字套白边发糊，华北只有「待出库」
一段，段内与栈顶各写一遍 ¥2,450）。`,...z.parameters?.docs?.description}}},V.parameters={...V.parameters,docs:{...V.parameters?.docs,source:{originalSource:`{
  ...DisplayTwoMetrics,
  play: async ({
    canvasElement
  }) => {
    await chartsDrawn(canvasElement);
    const [left, right] = await waitFor(() => {
      const found = [axisTicks(canvasElement, 'left'), axisTicks(canvasElement, 'right')];
      expect(found[1].length).toBeGreaterThan(1);
      return found;
    });
    await expect(right.length).toBe(left.length);
    const leftAt = middles(left);
    const rightAt = middles(right);
    await expect(leftAt.every((at, index) => Math.abs(at - rightAt[index]!) < 1)).toBe(true);
    await expect(right.every(tick => /^\\d+$/.test((tick.textContent ?? '').trim()))).toBe(true);
  }
}`,...V.parameters?.docs?.source},description:{story:`两根轴一套网格线：金额在左、记录数在右，两边的刻度一样多、逐对齐平，记录数
的刻度都是整数（从前右轴跟着左轴的线走，写出 0.5、1.5 条记录）。`,...V.parameters?.docs?.description}}},H.parameters={...H.parameters,docs:{...H.parameters?.docs,source:{originalSource:`{
  ...DisplayPieChart,
  play: async ({
    canvasElement
  }) => {
    await chartsDrawn(canvasElement);
    const frame = canvasElement.querySelector<HTMLElement>('[data-slot="chart"]')!;
    await expect(frame).toHaveAttribute('data-legend', 'right');
    const legend = frame.querySelector('[data-slot="chart-legend"]')!.getBoundingClientRect();
    const pie = [...drawnMarks(frame), ...valueLabels(frame)].map(each => each.getBoundingClientRect());
    const east = Math.max(...pie.map(box => box.right));
    const west = Math.min(...pie.map(box => box.left));
    await expect(legend.left - east).toBeLessThanOrEqual(64);
    await expect(legend.left).toBeGreaterThan(east);
    // The pie and its legend stand together in the frame's middle.
    const box = frame.getBoundingClientRect();
    await expect(Math.abs(west - box.left - (box.right - legend.right))).toBeLessThan(box.width * 0.2);
  }
}`,...H.parameters?.docs?.source},description:{story:`饼图的图例贴着饼：宽屏上从前饼站在绘图区正中、图例在最右，隔着一掌宽
（审查）。量：图例的左边离饼（连同片外的占比）不超过 64px，而饼与图例一起
站在图框中间。`,...H.parameters?.docs?.description}}},U.parameters={...U.parameters,docs:{...U.parameters?.docs,source:{originalSource:`{
  ...DisplayBarChart,
  play: async ({
    canvasElement
  }) => {
    await chartsDrawn(canvasElement);
    const title = await waitFor(() => {
      const found = axisTitles(canvasElement).find(text => text.textContent === '金额的总和');
      expect(found).toBeDefined();
      return found!;
    });
    const box = title.getBoundingClientRect();
    await expect(box.width).toBeGreaterThan(box.height);
    const ticks = axisTicks(canvasElement, 'left');
    const top = Math.min(...ticks.map(tick => tick.getBoundingClientRect().top));
    await expect(box.bottom).toBeLessThanOrEqual(top);
    for (const tick of ticks) await expect(overlaps(box, tick.getBoundingClientRect())).toBe(false);
  }
}`,...U.parameters?.docs?.source},description:{story:`中文的纵轴标题不侧躺：「金额的总和」平放在纵轴顶端、刻度字的上方，宽大于高，
不压任何刻度（审查：转了 90° 的中文每个字都躺着）。`,...U.parameters?.docs?.description}}},G.parameters={...G.parameters,docs:{...G.parameters?.docs,source:{originalSource:`{
  ...DisplayValueLabels,
  play: async ({
    canvasElement
  }) => {
    await chartsDrawn(canvasElement);
    const bars = await waitFor(() => {
      const found = drawnMarks(canvasElement);
      expect(found.length).toBeGreaterThan(10);
      return found;
    });
    const labels = await waitFor(() => {
      const found = valueLabels(canvasElement).filter(label => (label.textContent ?? '') !== '');
      expect(found).toHaveLength(bars.length);
      return found;
    });
    // Flat, where each fits over its bar: none of them turned.
    await expect(labels.some(turned)).toBe(false);
  }
}`,...G.parameters?.docs?.source},description:{story:`数值标签要么都写、要么都不写：三十天的柱在宽处每根都写平排的数，在窄处每根
都写竖排的数（或一个都不写），从不像库的 \`hideOverlap\` 那样隔一个藏一个——
一根没写数的柱读起来就是没有值（审查）。`,...G.parameters?.docs?.description}}},K.parameters={...K.parameters,docs:{...K.parameters?.docs,source:{originalSource:`{
  ...DisplayValueLabels,
  decorators: [Story => <div style={{
    width: 360
  }}>
        <Story />
      </div>],
  play: async ({
    canvasElement
  }) => {
    await chartsDrawn(canvasElement);
    const bars = await waitFor(() => {
      const found = drawnMarks(canvasElement);
      expect(found.length).toBeGreaterThan(10);
      return found;
    });
    const labels = valueLabels(canvasElement).filter(label => (label.textContent ?? '') !== '');
    await expect([0, bars.length]).toContain(labels.length);
    // Written, they are all turned alike, or all flat.
    await expect(new Set(labels.map(turned)).size).toBeLessThanOrEqual(1);
    // Written, they run up from the bars: no two on each other.
    for (const [index, label] of labels.entries()) for (const other of labels.slice(index + 1)) await expect(overlaps(label.getBoundingClientRect(), other.getBoundingClientRect())).toBe(false);
  }
}`,...K.parameters?.docs?.source},description:{story:`The same thirty days in a column a phone wide.`,...K.parameters?.docs?.description}}},q.parameters={...q.parameters,docs:{...q.parameters?.docs,source:{originalSource:`{
  ...DisplayFailingAggregates,
  play: async ({
    canvasElement
  }) => {
    await userEvent.click(await within(canvasElement).findByRole('button', {
      name: zhCN['label.layout.chart']
    }));
    await chartsDrawn(canvasElement);
    const frame = canvasElement.querySelector('[data-slot="chart"]')!;
    await expect(frame).toHaveAttribute('data-orientation', 'horizontal');
    const bars = drawnMarks(canvasElement).map(bar => bar.getBoundingClientRect());
    await expect(bars).toHaveLength(9);
    // Bars that run across: each wider than it is tall, one under the next.
    await expect(bars.every(bar => bar.width >= bar.height)).toBe(true);
    // The names stand flat, one line each.
    const names = axisTicks(canvasElement, 'left');
    await expect(names.length).toBeGreaterThan(0);
    await expect(names.every(name => name.getBoundingClientRect().height < 20)).toBe(true);
  }
}`,...q.parameters?.docs?.source},description:{story:`长名字的排行横着放：九个聚合 ID 竖着站时名字斜 45°、要歪着头读；没人说过
方向时它们缺省横放，名字整行写在左边（审查）。`,...q.parameters?.docs?.description}}},J.parameters={...J.parameters,docs:{...J.parameters?.docs,source:{originalSource:`{
  ...DisplayHeatmapChart,
  play: async ({
    canvasElement
  }) => {
    await chartsDrawn(canvasElement);
    const labels = await waitFor(() => {
      const found = markLabels(canvasElement);
      expect(found.length).toBeGreaterThan(0);
      return found;
    });
    const inks = new Set<string>();
    for (const label of labels) {
      const at = middle(label);
      const cell = document.elementsFromPoint(at.clientX, at.clientY).find(hit => hit.tagName === 'path')!;
      const fill = rgb(cell.getAttribute('fill')!);
      const ink = label.getAttribute('fill')!;
      inks.add(ink);
      const dark = {
        r: 0.04,
        g: 0.04,
        b: 0.04
      };
      const best = Math.max(contrastRatio(dark, fill), contrastRatio(WHITE, fill));
      await expect(contrastRatio(rgb(ink), fill)).toBeGreaterThanOrEqual(best - 0.01);
      await expect(label.getAttribute('stroke')).toBeNull();
    }
  }
}`,...J.parameters?.docs?.source},description:{story:`热力图格子上的数用与这一格对比的墨色：深格子上是底色的字、浅格子上是字的
颜色，没有白边（审查：深色格子上的深色字看不清）。`,...J.parameters?.docs?.description}}},Y.parameters={...Y.parameters,docs:{...Y.parameters?.docs,source:{originalSource:`{
  ...DisplayDailyTrendCard,
  play: async ({
    canvasElement
  }) => {
    const card = await waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>('[data-slot="metric-card"]');
      expect(found).not.toBeNull();
      return found!;
    });
    await chartsDrawn(canvasElement);
    const area = canvasElement.querySelector('[data-slot="analysis-result"]')!.getBoundingClientRect();
    const box = card.getBoundingClientRect();
    await expect(Math.abs(box.left - area.left - (area.right - box.right))).toBeLessThan(2);
    await expect(Math.abs(box.top - area.top - (area.bottom - box.bottom))).toBeLessThan(28);
    const value = card.querySelector('[data-slot="metric-value"]')!.getBoundingClientRect();
    await expect(value.height).toBeGreaterThanOrEqual(44);
  }
}`,...Y.parameters?.docs?.source},description:{story:`工作台里的指标卡站在结果中间：左右留白相等、上下留白相差不到一行，数字比
仪表盘上的大一号（审查：挤在左上角、下面一大片空）。`,...Y.parameters?.docs?.description}}},Z.parameters={...Z.parameters,docs:{...Z.parameters?.docs,source:{originalSource:`{
  ...DisplayDailyQuietDays,
  args: {
    ...DisplayDailyQuietDays.args,
    labels: true
  },
  play: async ({
    canvasElement
  }) => {
    await chartsDrawn(canvasElement);
    const rows = await waitFor(() => {
      const found = readingOf(canvasElement);
      expect(found.length).toBeGreaterThan(10);
      return found;
    });
    const quiet = rows.filter(row => row[1]?.includes(zhCN['label.chart.filled.DAY'].replace('{value}', '0')));
    const busy = rows.length - quiet.length;
    await expect(quiet.length).toBeGreaterThan(0);
    await expect(busy).toBeGreaterThan(0);
    // A dot on every day, the quiet ones on zero; a number on the busy ones.
    await waitFor(() => expect(drawnMarks(canvasElement)).toHaveLength(rows.length));
    await waitFor(() => expect(valueLabels(canvasElement).filter(label => (label.textContent ?? '') !== '')).toHaveLength(busy));
    await expect(valueLabels(canvasElement).some(label => label.textContent === '0')).toBe(false);
  }
}`,...Z.parameters?.docs?.source},description:{story:`补出的 0 不写数（D23，Q14）：只发往杭州、上海的运单，没单的日子画在 0 上，
线照样落到那里，但开着数值标签也不在那一点上写「0」——那不是量出来的数，满屏
「0」会盖住真正有值的点；读屏表与提示框说「0（这一天没有记录）」。`,...Z.parameters?.docs?.description}}}})))()}$();export{U as ChineseTitleUpright,Z as FilledZerosWriteNothing,J as HeatmapInkStandsOff,L as HoverReadsAsEmphasis,q as LongNamesLieDown,H as PieLegendBesideThePie,z as StackedPartsReadable,Y as TrendCardStandsInTheMiddle,V as TwoAxesShareTheGrid,G as ValueLabelsAllOrNone,K as ValueLabelsAllOrNoneNarrow,Q as __namedExportsOrder,N as default};
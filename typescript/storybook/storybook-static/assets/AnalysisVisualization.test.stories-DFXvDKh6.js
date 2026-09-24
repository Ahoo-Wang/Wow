import{n as e}from"./rolldown-runtime-DkW27tQK.js";import{t}from"./jsx-runtime-DeHZSEgm.js";import{Es as n,R as r,Ts as i,z as a}from"./styles-Dpj2y9Rj.js";import{A as o,O as s,j as c,n as l}from"./AnalysisWorkbench.stories-DyF9m040.js";import{a as u,f as d,h as f,l as p,m,o as h,t as g}from"./chartDom-C6NvYOT8.js";import{n as _,r as v}from"./pointerDrag-CZRuWcId.js";var y,b,x,S,C,w,T,E,D,O,k,A,j,M,N,P,F,I,L;function R(){return(R=e((()=>{n(),r(),o(),v(),p(),y=t(),{expect:b,userEvent:x,waitFor:S,within:C}=__STORYBOOK_MODULE_TEST__,w={...c,title:`View Engine/分析视图/可视化面板/回归`,tags:[`!dev`,`!autodocs`,`test`],parameters:{...c.parameters}},T=e=>i(a,`label.chart.options`,{name:a[`label.chart.type.${e}`]}),E=()=>document.querySelector(`[data-slot="chart-options"]`),D=i(a,`label.summary.of`,{field:`金额`,fn:a[`label.summary.fn.SUM`]}),O=()=>m(document).map(e=>(e.getAttribute(`d`)?.match(/A/g)??[]).length),k=()=>[...E().querySelectorAll(`[data-slot="series-card"]`)].map(e=>e.getAttribute(`data-metric`)),A=()=>[...document.querySelectorAll(`[data-slot="chart-legend-item"]`)].map(e=>e.textContent),j=e=>{let t=f(e).map(e=>({text:e.textContent,box:e.getBoundingClientRect()}));return t.flatMap((e,n)=>t.slice(n+1).filter(t=>d(e.box,t.box)).map(t=>({pair:[e.text,t.text],boxes:[e.box,t.box].map(e=>[e.left,e.top,e.right,e.bottom].map(Math.round))})))},M={...s,play:async({canvasElement:e})=>{let t=C(e),n=C(document.body);await S(()=>b(h(e)).toHaveLength(8));let r=A();await b(r).toHaveLength(2),await x.click(t.getByRole(`button`,{name:a[`label.analysis.visualize`]})),await x.click(n.getByRole(`button`,{name:T(`bar`)})),await S(()=>b(E()).not.toBeNull());let i=k();await b(i).toEqual([`amount`,`orders`]);let o=[...E().querySelectorAll(`[data-slot="series-card"]`)];await _(o[0].querySelector(`button`),o[1]),await S(()=>b(k()).toEqual([`orders`,`amount`])),await S(()=>b(A()).toEqual([...r].reverse())),await b(h(e)).toHaveLength(8)}},N={...s,play:async({canvasElement:e})=>{let t=C(e),n=C(document.body);await S(()=>b(h(e)).toHaveLength(8)),await x.click(t.getByRole(`button`,{name:a[`label.analysis.visualize`]})),await S(()=>b(document.querySelector(`[data-slot="chart-picker"]`)).not.toBeNull()),b(document.querySelector(`[data-slot="view-list"]`)).toBeNull(),await x.click(n.getByRole(`button`,{name:T(`bar`)})),await S(()=>b(E()).not.toBeNull()),await b(C(E()).getAllByRole(`tab`).map(e=>e.textContent)).toEqual([a[`label.chart.tab.data`],a[`label.chart.tab.display`],a[`label.chart.tab.axes`]]),await b(C(E()).getByLabelText(a[`label.chart.slot.x`])).toBeVisible(),await b(E().querySelectorAll(`[data-slot="series-card"]`)).toHaveLength(2),await x.click(C(E()).getByRole(`tab`,{name:a[`label.chart.tab.display`]}));let r=()=>C(E()).getByRole(`checkbox`,{name:a[`label.chart.labels`]});await b(r()).toHaveAttribute(`aria-checked`,`true`),await S(()=>b(f(e).length).toBeGreaterThan(0)),await x.click(r()),await S(()=>b(f(e)).toHaveLength(0)),await x.click(r()),await S(()=>b(f(e).length).toBeGreaterThan(0)),await u(e),await b(j(e)).toEqual([]),await x.click(C(E()).getByRole(`checkbox`,{name:a[`label.chart.stacked`]})),await S(()=>b(C(E()).getByRole(`checkbox`,{name:a[`label.chart.stacked`]}).getAttribute(`aria-checked`)).toBe(`true`)),await x.click(C(E()).getByRole(`button`,{name:a[`label.chart.add-reference-line`]})),await S(()=>b(e.querySelectorAll(`[data-slot="chart-plot"] path[stroke-dasharray]`)).toHaveLength(1)),await x.click(C(E()).getByRole(`tab`,{name:a[`label.chart.tab.axes`]})),await x.type(C(E()).getAllByLabelText(a[`label.chart.axis-title`])[0],`金额`),await S(()=>b(g(e).map(e=>e.textContent)).toContain(`金额`)),await x.click(C(E()).getByRole(`button`,{name:a[`label.chart.options-back`]})),await x.click(await n.findByRole(`radio`,{name:a[`label.chart.type.pie`]})),await u(e),await S(()=>b(O()).toHaveLength(4)),await b(O().every(e=>e===1)).toBe(!0),await x.click(n.getByRole(`button`,{name:T(`pie`)})),await S(()=>b(E()).not.toBeNull()),await b(C(E()).getAllByRole(`tab`).map(e=>e.textContent)).toEqual([a[`label.chart.tab.data`],a[`label.chart.tab.display`]]),await x.click(C(E()).getByRole(`tab`,{name:a[`label.chart.tab.display`]})),await x.click(C(E()).getByRole(`checkbox`,{name:a[`label.chart.donut`]})),await u(e),await S(()=>b(O().every(e=>e===2)).toBe(!0)),await S(()=>b(g(e).map(e=>e.textContent)).toContain(a[`label.chart.total`])),await x.click(C(E()).getByRole(`button`,{name:a[`label.chart.options-back`]})),await x.click(await n.findByRole(`button`,{name:a[`label.chart.picker-back`]})),await S(()=>b(document.querySelector(`[data-slot="view-list"]`)).not.toBeNull()),await b(document.querySelector(`[data-slot="chart-picker"]`)).toBeNull()}},P={...l,play:async({canvasElement:e})=>{let t=C(e),n=C(document.body);await S(()=>b(h(e)).toHaveLength(4)),await x.click(t.getByRole(`button`,{name:a[`label.analysis.visualize`]})),await x.click(n.getByRole(`button`,{name:T(`bar`)})),await S(()=>b(E()).not.toBeNull()),await x.click(C(E()).getByRole(`tab`,{name:a[`label.chart.tab.axes`]}));let r=C(E()).getAllByLabelText(a[`label.chart.axis-title`])[0];await b(r.value).toBe(``);let i=g(e).map(e=>e.textContent);await b(i).toContain(r.placeholder),await b(r.placeholder).toBe(D)}},F={...l,play:async({canvasElement:e})=>{let t=C(e);await S(()=>b(h(e)).toHaveLength(4)),t.getByRole(`button`,{name:a[`label.analysis.visualize`]}).focus(),await x.keyboard(`{Enter}`);let n=await S(()=>{let e=document.querySelector(`[data-slot="chart-picker"] h2`);return b(e).toHaveFocus(),e});await b(getComputedStyle(n).outlineStyle).toBe(`none`),document.querySelector(`[data-slot="chart-options-open"]`).focus(),await x.keyboard(`{Enter}`);let r=await S(()=>{let e=document.querySelector(`[data-slot="chart-options"] h2`);return b(e).toHaveFocus(),e});await b(r.matches(`:focus-visible`)).toBe(!0),await b(getComputedStyle(r).outlineStyle).toBe(`none`)}},I={...l,decorators:[e=>(0,y.jsx)(`div`,{style:{width:414},children:(0,y.jsx)(e,{})})],play:async({canvasElement:e})=>{let t=C(e);await S(()=>b(h(e)).toHaveLength(4));let n=t.getByRole(`button`,{name:a[`label.analysis.visualize`]});await x.click(n);let r=await C(document.body).findByRole(`dialog`,{name:a[`label.chart.picker`]});await b(r).toHaveAttribute(`data-side`,`bottom`),await b(e.querySelector(`aside[data-slot="view-panel"]`)).toBeNull(),await S(()=>{let e=r.getBoundingClientRect();b(Math.abs(e.bottom-window.innerHeight)).toBeLessThan(2),b(e.height).toBeLessThanOrEqual(window.innerHeight*.8+1)}),await S(()=>b(r.querySelector(`[data-slot="chart-picker"] h2`)).toHaveFocus()),await x.click(r.querySelector(`[data-slot="chart-tile"][data-chart-type="line"]`)),await S(()=>b(e.querySelector(`[data-slot="chart"]`)).toHaveAttribute(`data-chart`,`line`)),await x.keyboard(`{Escape}`),await S(()=>b(document.querySelector(`[data-slot="view-panel"]`)).toBeNull()),await S(()=>b(n).toHaveFocus())}},L=[`SeriesOrder`,`ChartOptionsPages`,`AxisTitleShowsItsDefault`,`OptionsHeadingRingless`,`VisualizeOnAPhone`],M.parameters={...M.parameters,docs:{...M.parameters?.docs,source:{originalSource:`{
  ...DisplayTwoMetrics,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    const body = within(document.body);
    await waitFor(() => expect(drawnMarks(canvasElement)).toHaveLength(8));
    const drawn = legendOrder();
    await expect(drawn).toHaveLength(2);
    await userEvent.click(canvas.getByRole('button', {
      name: zhCN['label.analysis.visualize']
    }));
    await userEvent.click(body.getByRole('button', {
      name: optionsOf('bar')
    }));
    await waitFor(() => expect(panel()).not.toBeNull());
    const order = seriesOrder();
    await expect(order).toEqual(['amount', 'orders']);

    // The handle leads its row, and the drop is onto the row below it.
    const rows = [...panel()!.querySelectorAll('[data-slot="series-card"]')];
    await dragHandleOnto(rows[0]!.querySelector('button')!, rows[1]! as HTMLElement);
    await waitFor(() => expect(seriesOrder()).toEqual(['orders', 'amount']));
    // Only the order moved: each series kept the axis it is measured on,
    // and the chart still draws both.
    await waitFor(() => expect(legendOrder()).toEqual([...drawn].reverse()));
    await expect(drawnMarks(canvasElement)).toHaveLength(8);
  }
}`,...M.parameters?.docs?.source},description:{story:`A series carried into another place with the pointer (D20 屏 J).

Which series comes first is a setting — a stack is read from the bottom up
and a legend from its first entry — and the whole of what it changes is
the order of \`cartesian.series\`. jsdom can pin the move the arrow keys
make (\`typescript/wow-view-engine/test/chartOptionsUi.test.tsx\`), but not the
gesture: \`@dnd-kit/dom\` picks its drop target by *measuring*, and in jsdom
every box is 0×0 at the origin. Here the boxes are real, so this is the
one place the pointer path is exercised at all — and the proof is the
drawing, not the config: the legend comes back in the new order.`,...M.parameters?.docs?.description}}},N.parameters={...N.parameters,docs:{...N.parameters?.docs,source:{originalSource:`{
  ...DisplayTwoMetrics,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    const body = within(document.body);
    // Two metrics, so there are two series to stack and a legend to read.
    await waitFor(() => expect(drawnMarks(canvasElement)).toHaveLength(8));

    // Level one: the panel takes the sidebar column, where the view list was.
    await userEvent.click(canvas.getByRole('button', {
      name: zhCN['label.analysis.visualize']
    }));
    await waitFor(() => expect(document.querySelector('[data-slot="chart-picker"]')).not.toBeNull());
    expect(document.querySelector('[data-slot="view-list"]')).toBeNull();

    // Level two: the gear beside the chosen tile, and the three pages a
    // cartesian chart has.
    await userEvent.click(body.getByRole('button', {
      name: optionsOf('bar')
    }));
    await waitFor(() => expect(panel()).not.toBeNull());
    await expect(within(panel()!).getAllByRole('tab').map(tab => tab.textContent)).toEqual([zhCN['label.chart.tab.data'], zhCN['label.chart.tab.display'], zhCN['label.chart.tab.axes']]);

    // The data page: a slot per position, each named by its column.
    await expect(within(panel()!).getByLabelText(zhCN['label.chart.slot.x'])).toBeVisible();
    await expect(panel()!.querySelectorAll('[data-slot="series-card"]')).toHaveLength(2);

    // The display page: value labels over every mark, and one stack.
    await userEvent.click(within(panel()!).getByRole('tab', {
      name: zhCN['label.chart.tab.display']
    }));
    // A bar chart writes its values unasked, as Metabase's does where they
    // fit: turned off they go, and back on they return.
    const labelsBox = () => within(panel()!).getByRole('checkbox', {
      name: zhCN['label.chart.labels']
    });
    await expect(labelsBox()).toHaveAttribute('aria-checked', 'true');
    await waitFor(() => expect(valueLabels(canvasElement).length).toBeGreaterThan(0));
    await userEvent.click(labelsBox());
    await waitFor(() => expect(valueLabels(canvasElement)).toHaveLength(0));
    await userEvent.click(labelsBox());
    // A label over every bar there is room for, and none over another.
    await waitFor(() => expect(valueLabels(canvasElement).length).toBeGreaterThan(0));
    // Measured once the redraw the labels came with has landed.
    await chartsDrawn(canvasElement);
    await expect(labelsOver(canvasElement)).toEqual([]);
    await userEvent.click(within(panel()!).getByRole('checkbox', {
      name: zhCN['label.chart.stacked']
    }));
    // Stacking is one choice for the whole chart, so the box reads back on.
    await waitFor(() => expect(within(panel()!).getByRole('checkbox', {
      name: zhCN['label.chart.stacked']
    }).getAttribute('aria-checked')).toBe('true'));

    // A reference line, drawn across the marks rather than merely stored.
    await userEvent.click(within(panel()!).getByRole('button', {
      name: zhCN['label.chart.add-reference-line']
    }));
    await waitFor(() => expect(canvasElement.querySelectorAll('[data-slot="chart-plot"] path[stroke-dasharray]')).toHaveLength(1));

    // The axes page: a title written along the numeric axis.
    await userEvent.click(within(panel()!).getByRole('tab', {
      name: zhCN['label.chart.tab.axes']
    }));
    await userEvent.type(within(panel()!).getAllByLabelText(zhCN['label.chart.axis-title'])[0]!, '金额');
    await waitFor(() => expect(axisTexts(canvasElement).map(text => text.textContent)).toContain('金额'));

    // Back to the types, and on to another family: a pie of the same rows,
    // with no query in between.
    await userEvent.click(within(panel()!).getByRole('button', {
      name: zhCN['label.chart.options-back']
    }));
    await userEvent.click(await body.findByRole('radio', {
      name: zhCN['label.chart.type.pie']
    }));
    // One slice per warehouse: a type picked here is fitted to the rows on
    // screen, and nothing has asked for a tail to be merged.
    await chartsDrawn(canvasElement);
    await waitFor(() => expect(arcsPerSlice()).toHaveLength(4));
    await expect(arcsPerSlice().every(arcs => arcs === 1)).toBe(true);

    // The pie's own display page, and the one setting that changes its shape.
    await userEvent.click(body.getByRole('button', {
      name: optionsOf('pie')
    }));
    await waitFor(() => expect(panel()).not.toBeNull());
    await expect(within(panel()!).getAllByRole('tab').map(tab => tab.textContent)).toEqual([zhCN['label.chart.tab.data'], zhCN['label.chart.tab.display']]);
    await userEvent.click(within(panel()!).getByRole('tab', {
      name: zhCN['label.chart.tab.display']
    }));
    await userEvent.click(within(panel()!).getByRole('checkbox', {
      name: zhCN['label.chart.donut']
    }));
    // A hole in the middle: every sector now has an inner arc as well,
    // and the hole says the whole — the amount adds up.
    await chartsDrawn(canvasElement);
    await waitFor(() => expect(arcsPerSlice().every(arcs => arcs === 2)).toBe(true));
    await waitFor(() => expect(axisTexts(canvasElement).map(text => text.textContent)).toContain(zhCN['label.chart.total']));

    // Out the way it came in: the types, then the view list back in the
    // column the panel borrowed.
    await userEvent.click(within(panel()!).getByRole('button', {
      name: zhCN['label.chart.options-back']
    }));
    await userEvent.click(await body.findByRole('button', {
      name: zhCN['label.chart.picker-back']
    }));
    await waitFor(() => expect(document.querySelector('[data-slot="view-list"]')).not.toBeNull());
    await expect(document.querySelector('[data-slot="chart-picker"]')).toBeNull();
  }
}`,...N.parameters?.docs?.source},description:{story:`The visualization panel's two levels, walked (D20 屏 I／J).

Every step here is a redraw of the rows already on screen: the panel is
opened from the result toolbar, the chosen type's options are walked page
by page, and each setting reaches the drawing — the labels over the marks,
the reference line across them, the axis title along the axis, and the
hole in the middle of a pie. jsdom can pin the spec each control writes
(\`typescript/wow-view-engine/test/chartOptionsUi.test.tsx\`); only a browser can
say the marks changed, which is what this walk is for.`,...N.parameters?.docs?.description}}},P.parameters={...P.parameters,docs:{...P.parameters?.docs,source:{originalSource:`{
  ...DisplayBarChart,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    const body = within(document.body);
    await waitFor(() => expect(drawnMarks(canvasElement)).toHaveLength(4));
    await userEvent.click(canvas.getByRole('button', {
      name: zhCN['label.analysis.visualize']
    }));
    await userEvent.click(body.getByRole('button', {
      name: optionsOf('bar')
    }));
    await waitFor(() => expect(panel()).not.toBeNull());
    await userEvent.click(within(panel()!).getByRole('tab', {
      name: zhCN['label.chart.tab.axes']
    }));
    const box = within(panel()!).getAllByLabelText(zhCN['label.chart.axis-title'])[0] as HTMLInputElement;
    await expect(box.value).toBe('');
    // The same words the axis is drawn with.
    const drawn = axisTexts(canvasElement).map(text => text.textContent);
    await expect(drawn).toContain(box.placeholder);
    await expect(box.placeholder).toBe(AMOUNT_HEADER);
  }
}`,...P.parameters?.docs?.source},description:{story:`坐标轴页的「轴标题」框空着时写出图上画的那个标题：一个量的轴是它的列标题
（审查：空框看不出缺省）。`,...P.parameters?.docs?.description}}},F.parameters={...F.parameters,docs:{...F.parameters?.docs,source:{originalSource:`{
  ...DisplayBarChart,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await waitFor(() => expect(drawnMarks(canvasElement)).toHaveLength(4));
    canvas.getByRole('button', {
      name: zhCN['label.analysis.visualize']
    }).focus();
    await userEvent.keyboard('{Enter}');
    const heading = await waitFor(() => {
      const found = document.querySelector<HTMLElement>('[data-slot="chart-picker"] h2');
      expect(found).toHaveFocus();
      return found!;
    });
    await expect(getComputedStyle(heading).outlineStyle).toBe('none');
    document.querySelector<HTMLElement>('[data-slot="chart-options-open"]')!.focus();
    await userEvent.keyboard('{Enter}');
    const title = await waitFor(() => {
      const found = document.querySelector<HTMLElement>('[data-slot="chart-options"] h2');
      expect(found).toHaveFocus();
      return found!;
    });
    // The browser would ring it — it is focused from the keyboard — and it
    // wears none: a landing, not a control.
    await expect(title.matches(':focus-visible')).toBe(true);
    await expect(getComputedStyle(title).outlineStyle).toBe('none');
  }
}`,...F.parameters?.docs?.source},description:{story:`选项页的标题聚焦时没有焦点环：键盘从「可视化」进第一层、从「…选项」按钮进
第二层，都落在那一层的标题上（\`tabIndex={-1}\`，只接落点），标题不画一圈框
（审查）。`,...F.parameters?.docs?.description}}},I.parameters={...I.parameters,docs:{...I.parameters?.docs,source:{originalSource:`{
  ...DisplayBarChart,
  decorators: [Story => <div style={{
    width: 414
  }}>
        <Story />
      </div>],
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await waitFor(() => expect(drawnMarks(canvasElement)).toHaveLength(4));
    const visualizeButton = canvas.getByRole('button', {
      name: zhCN['label.analysis.visualize']
    });
    await userEvent.click(visualizeButton);
    const drawer = await within(document.body).findByRole('dialog', {
      name: zhCN['label.chart.picker']
    });
    await expect(drawer).toHaveAttribute('data-side', 'bottom');
    // Not a block in the page: no column in the workbench holds the panel.
    await expect(canvasElement.querySelector('aside[data-slot="view-panel"]')).toBeNull();
    await waitFor(() => {
      const box = drawer.getBoundingClientRect();
      expect(Math.abs(box.bottom - window.innerHeight)).toBeLessThan(2);
      expect(box.height).toBeLessThanOrEqual(window.innerHeight * 0.8 + 1);
    });
    // The keyboard is on the level's heading, as it is beside the view.
    await waitFor(() => expect(drawer.querySelector('[data-slot="chart-picker"] h2')).toHaveFocus());
    // A pick redraws the result under it.
    await userEvent.click(drawer.querySelector<HTMLElement>('[data-slot="chart-tile"][data-chart-type="line"]')!);
    await waitFor(() => expect(canvasElement.querySelector('[data-slot="chart"]')).toHaveAttribute('data-chart', 'line'));
    await userEvent.keyboard('{Escape}');
    await waitFor(() => expect(document.querySelector('[data-slot="view-panel"]')).toBeNull());
    await waitFor(() => expect(visualizeButton).toHaveFocus());
  }
}`,...I.parameters?.docs?.source},description:{story:`手机宽度（414）上的可视化面板是从底边升起的抽屉，不再是压在结果上方的一块：
抽屉贴着视口底边、不高过视口的八成；选一个图型，底下的结果就地重画；按 Escape
收起，键盘回到「可视化」（审查）。`,...I.parameters?.docs?.description}}}})))()}R();export{P as AxisTitleShowsItsDefault,N as ChartOptionsPages,F as OptionsHeadingRingless,M as SeriesOrder,I as VisualizeOnAPhone,L as __namedExportsOrder,w as default};
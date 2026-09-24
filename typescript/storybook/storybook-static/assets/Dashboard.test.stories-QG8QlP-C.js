import{n as e}from"./rolldown-runtime-DkW27tQK.js";import{t}from"./jsx-runtime-DeHZSEgm.js";import{R as n,g as r,h as i,y as a,z as o}from"./styles-Dpj2y9Rj.js";import{a as s,f as c,l as ee,n as te,o as ne}from"./chartDom-C6NvYOT8.js";import{c as l,n as u,r as re,s as ie}from"./contrast-BGE4rlQ_.js";import{a as d,c as f,i as p,r as m,t as h}from"./readTable-DOunjEkP.js";import{C as g,S as ae,c as oe,g as se,h as ce,i as _,l as le,m as ue,n as v,o as y,p as de,t as b,u as fe}from"./Dashboard.stories-Dhw1Xda_.js";async function x(e){await D(()=>w(e.querySelector(`[data-slot="dashboard-grid"]`)).not.toHaveAttribute(`data-narrow`))}async function S(e){await E.click(await O(e).findByRole(`button`,{name:o[`label.dashboard.edit`]}))}async function pe(e,t){e.focus();for(let e=0;e<20;e+=1){if(document.activeElement===t)return;await E.tab()}if(document.activeElement!==t)throw Error(`Tab never reached the target.`)}var C,w,T,E,D,O,k,A,j,M,N,P,F,I,L,R,z,B,V,H,U,W,G,K,q,J,Y,X,Z,Q;function $(){return($=e((()=>{n(),ae(),p(),ee(),u(),i(),C=t(),{expect:w,screen:T,userEvent:E,waitFor:D,within:O}=__STORYBOOK_MODULE_TEST__,k={...g,title:`View Engine/仪表盘视图/Dashboard/回归`,tags:[`!dev`,`!autodocs`,`test`],parameters:{...g.parameters}},A=e=>ne(e),j=e=>(0,C.jsx)(`div`,{style:{width:1280},children:(0,C.jsx)(e,{})}),M={...b,play:async({canvasElement:e})=>{let t=O(e),n=await m(e);await D(()=>w(d(n,`订单号`)).toEqual([`SO-1003`,`SO-1005`,`SO-1001`,`SO-1006`])),await w(h(f(n,`金额`))).toBe(6470),await D(()=>w(A(e)).toHaveLength(4)),await w(t.getByRole(`link`,{name:/^出库异常处理/})).toBeVisible()}},N={...le,decorators:[j],play:async({canvasElement:e})=>{let t=await m(e);await D(()=>w(d(t,`订单号`)).toEqual([`SO-1005`])),await w(h(f(t,`金额`))).toBe(1760),await D(()=>w(A(e)).toHaveLength(1));let n=O(e),r=n.getByRole(`region`,{name:o[`label.filters.bar`]});await w(O(r).getByRole(`combobox`,{name:`仓库`})).toHaveTextContent(`华南`),await w(n.queryByRole(`region`,{name:o[`label.applied.title`]})).toBeNull()}},P={...de,play:async({canvasElement:e})=>{let t=O(e);await w(await t.findByText(o[`label.panel.out.missing`])).toBeVisible();let n=e.querySelector(`[data-slot="panel-unavailable"]`);await w(n).toHaveTextContent(o[`label.panel.way-out.share`]),await w(n.textContent).not.toContain(`不可用`),await w(n.textContent).not.toContain(`orders-pending`),await w(O(n).queryByRole(`button`)).toBeNull(),await w(t.getByRole(`heading`,{level:3,name:`待出库明细`})).toBeVisible(),await D(()=>w(A(e)).toHaveLength(4))}},F={...b,decorators:[e=>(0,C.jsx)(`div`,{style:{width:414},children:(0,C.jsx)(e,{})})],play:async({canvasElement:e})=>{let t=await D(()=>{let t=e.querySelector(`[data-slot="dashboard-grid"]`);return w(t).toHaveAttribute(`data-narrow`),t});await s(e),await D(()=>w(A(e)).toHaveLength(4));let n=t.getBoundingClientRect();await D(()=>{let e=[...t.querySelectorAll(`.react-grid-item`)].map(e=>({title:e.querySelector(`[data-slot="panel-title"]`)?.textContent,box:e.getBoundingClientRect()}));w([...e].sort((e,t)=>e.box.top-t.box.top).map(e=>e.title)).toEqual([`待出库明细`,`按仓库汇总`,`值班手册`]);for(let t of e)w(t.box.width).toBeGreaterThan(n.width*.9),w(t.box.left).toBeGreaterThanOrEqual(n.left-1),w(t.box.right).toBeLessThanOrEqual(n.right+1)}),await w(t.scrollWidth).toBeLessThanOrEqual(t.clientWidth+1),await D(()=>{let t=e.querySelector(`[data-slot="chart-plot"]`).getBoundingClientRect();w(t.width).toBeGreaterThan(300);let n=[...te(e,`bottom`)].map(e=>e.getBoundingClientRect());w(n.length).toBeGreaterThan(0);for(let[e,r]of n.entries()){w(r.left).toBeGreaterThanOrEqual(t.left-1),w(r.right).toBeLessThanOrEqual(t.right+1);for(let t of n.slice(e+1))w(c(r,t)).toBe(!1)}}),await w(e.querySelector(`[data-slot="panel-grip"]`)).toBeNull()}},I={...se,play:async({canvasElement:e})=>{let t=O(e);await D(()=>w(t.getAllByText(o[`label.query.failed`])).toHaveLength(2)),await w(t.getByRole(`link`,{name:/^出库异常处理/})).toBeVisible()}},L={...v,decorators:[j],play:async({canvasElement:e})=>{let t=O(e);await x(e),await S(e);let n=t.getByLabelText(o[`label.panel.handle`].replace(`{title}`,`待出库明细`)),r=n.closest(`.react-grid-item`),i=e.querySelector(`[data-slot="dashboard-grid"]`),a=()=>(r.getBoundingClientRect().left-i.getBoundingClientRect().left)/i.getBoundingClientRect().width;await w(a()).toBeLessThan(.03),n.focus(),await E.keyboard(`{Enter}`),await w(n).toHaveAttribute(`aria-pressed`,`true`),await E.keyboard(`{ArrowRight}`),await D(()=>w(a()).toBeGreaterThan(.04)),await w(n).toHaveFocus(),await E.keyboard(`{Enter}`),await w(n).toHaveAttribute(`aria-pressed`,`false`);let s=O(r).getByLabelText(o[`label.panel.resize`].replace(`{title}`,`待出库明细`));s.focus(),await w(getComputedStyle(s).opacity).toBe(`1`)}},R={...y,play:async({canvasElement:e})=>{await w(await O(e).findByText(o[`label.dashboard.empty`])).toBeVisible();let t=e.querySelector(`[data-slot="dashboard-empty"]`);await w(t).toHaveTextContent(o[`label.dashboard.empty-hint`]),await w(O(t).getAllByRole(`button`).map(e=>e.textContent)).toEqual([o[`label.dashboard.empty.add-view`],o[`label.dashboard.add.new-analysis`],o[`label.dashboard.empty.add-heading`]])}},z={...b,args:{...b.args,behaviour:`outage`},play:async({canvasElement:e})=>{let t=O(e),n=RegExp(`${o[`label.query.stale`].replace(`{error} · `,``)}$`);a.down=!1;try{let r=await m(e);await D(()=>w(d(r,`订单号`)).toHaveLength(4)),await D(()=>w(A(e)).toHaveLength(4)),a.down=!0,await E.click(t.getByRole(`button`,{name:o[`label.toolbar.refresh`]})),await D(()=>w(t.getAllByText(n)).toHaveLength(2)),await w(d(r,`订单号`)).toHaveLength(4),await D(()=>w(A(e)).toHaveLength(4)),await w(t.queryByText(o[`label.query.failed`])).toBeNull(),a.down=!1;let[i]=t.getAllByRole(`button`,{name:o[`label.query.retry`]});await E.click(i),await D(()=>w(t.getAllByText(n)).toHaveLength(1)),await w(d(r,`订单号`)).toHaveLength(4)}finally{a.down=!1}}},B={...v,decorators:[j],play:async({canvasElement:e})=>{let t=O(e);await x(e),await S(e);let n=e=>t.getByLabelText(o[`label.panel.handle`].replace(`{title}`,e)),r=e=>n(e).closest(`.react-grid-item`),i=r(`值班手册`),a=r(`待出库明细`),s=()=>{let e=i.getBoundingClientRect(),t=a.getBoundingClientRect();return e.left<t.right&&t.left<e.right&&e.top<t.bottom&&t.top<e.bottom};await w(s()).toBe(!1),n(`值班手册`).focus(),await E.keyboard(`{Enter}`),await E.keyboard(`{ArrowUp}`),await D(()=>w(i.getBoundingClientRect().top).toBeLessThan(a.getBoundingClientRect().top)),await D(()=>w(s()).toBe(!1))}},V={...fe,decorators:[j],play:async({canvasElement:e})=>{await x(e),await m(e);let t=e.querySelector(`.react-grid-layout`);for(let n of r().panels){let r=e.querySelector(`.react-grid-item[data-panel-id="${n.id}"]`);await D(()=>{let e=(t.getBoundingClientRect().width-110-20)/12,i=r.getBoundingClientRect(),a=i.left-t.getBoundingClientRect().left,{x:o,w:s}=n.layout;w(Math.abs(a-((e+10)*o+10))).toBeLessThan(1.5),w(Math.abs(i.width-(e*s+(s-1)*10))).toBeLessThan(1.5)})}await w(O(e).queryByText(o[`label.header.unsaved`])).toBeNull()}},H={...ce,decorators:[j],play:async({canvasElement:e})=>{let t=O(e),n=await m(e),r=await t.findByRole(`region`,{name:o[`label.filters.bar`]});await w(O(r).getByRole(`combobox`,{name:`仓库`})).toHaveTextContent(`华南`);let i=O(r).getByRole(`group`,{name:o[`label.filters.fixed`]});await w(i).toHaveTextContent(o[`label.filters.fixed`]),await w(i).toHaveTextContent(`仓库`),await w(i).toHaveTextContent(`西南`),await w(O(i).getAllByRole(`button`)).toHaveLength(1),await w(O(i).getByRole(`button`,{name:o[`label.filters.fixed-note`]})).toBeVisible(),await w(t.queryByRole(`region`,{name:o[`label.applied.title`]})).toBeNull(),await D(()=>w(d(n,`订单号`)).toEqual([`SO-1005`])),await w(t.queryByText(o[`label.header.unsaved`])).toBeNull()}},U=3,W=e=>({...ue,globals:{theme:e},decorators:[j],play:async({canvasElement:t})=>{let n=(await O(t).findByRole(`heading`,{level:3,name:`我盯的大额单`})).closest(`[data-slot="dashboard-panel"]`);await D(()=>w(n).toHaveAttribute(`data-warning`));let r=[...t.querySelectorAll(`[data-slot="dashboard-panel"]`)].find(e=>!e.hasAttribute(`data-warning`)),i=l(n);await w(i.ratio,`${e} warning edge ${JSON.stringify(i.colors)}`).toBeGreaterThanOrEqual(U),await w(l(r).ratio).toBeLessThan(i.ratio);let a=O(n).getByRole(`group`,{name:`我盯的大额单`});await pe(O(n).getByRole(`button`,{name:o[`label.panel.menu`].replace(`{title}`,`我盯的大额单`)}),a);let s=ie(a);await w(s.ratio,`${e} panel body focus ${JSON.stringify(s.colors)}`).toBeGreaterThanOrEqual(U);let c=getComputedStyle(a);await w(parseFloat(c.outlineOffset)).toBeLessThanOrEqual(-parseFloat(c.outlineWidth))}}),G=W(`light`),K=W(`dark`),q=e=>({...oe,decorators:[j],globals:{theme:e},play:async({canvasElement:t})=>{await D(()=>w(t.querySelectorAll(`[data-slot="dashboard-filter"]`).length).toBeGreaterThan(1));let n=[...t.querySelectorAll(`[data-slot="dashboard-filter"]`)].map(e=>({name:e.dataset.filter,idle:e.hasAttribute(`data-idle`),...re(e)})),r=n.map(({name:e,idle:t,ratio:n,colors:r})=>`${e}${t?` (idle)`:``} ${n.toFixed(2)}:1 ${JSON.stringify(r)}`).join(`; `);await w(Math.min(...n.map(({ratio:e})=>e)),`${e} — ${r}`).toBeGreaterThanOrEqual(U)}}),J=q(`light`),Y=q(`dark`),X={..._,play:async({canvasElement:e})=>{await D(()=>w(e.querySelector(`[data-slot="panel-click-filter"]`)).not.toBeNull());let t=e.querySelector(`[data-slot="panel-click-filter"]`).closest(`[data-slot="dashboard-panel"]`),n=t.querySelector(`[data-slot="panel-title"]`),r=t.querySelector(`[data-slot="panel-badges"]`);await w(n.scrollWidth).toBeLessThanOrEqual(n.clientWidth+1),await w(r.getBoundingClientRect().top).toBeGreaterThanOrEqual(n.getBoundingClientRect().bottom-1)}},Z={...y,parameters:{...y.parameters,viewport:{options:{phone:{name:`414×896`,styles:{width:`414px`,height:`896px`}}}}},globals:{viewport:{value:`phone`}},play:async({canvasElement:e})=>{await w(window.innerWidth).toBe(414),await E.click(await O(e).findByRole(`button`,{name:o[`label.dashboard.add.new-analysis`]}));let t=await T.findByRole(`dialog`),n=O(t).getByRole(`textbox`,{name:o[`label.panel.new-analysis.title`]}),r=O(t).getByRole(`button`,{name:o[`label.panel.new-analysis.add`]});await w(n.getBoundingClientRect().bottom).toBeLessThanOrEqual(r.getBoundingClientRect().top),await E.keyboard(`{Escape}`),await D(()=>w(T.queryByRole(`dialog`)).toBeNull())}},Q=[`AllPanels`,`GlobalFilter`,`PanelUnavailable`,`OnAPhone`,`QueryFailed`,`KeyboardLayout`,`EmptyDashboard`,`RefreshFailedKeepsData`,`KeyboardStepPushes`,`LegacyLayoutDrawsTheSame`,`PreBatchCFixedScope`,`PanelChromeInLightTheme`,`PanelChromeInDarkTheme`,`FilterChipEdgesInLightTheme`,`FilterChipEdgesInDarkTheme`,`TitleOverItsBadges`,`NewAnalysisTitleFirstOnAPhone`],M.parameters={...M.parameters,docs:{...M.parameters?.docs,source:{originalSource:`{
  ...DisplayAllPanels,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    const table = await findDataTable(canvasElement);
    // The record panel runs its own view: pending orders, largest first.
    await waitFor(() => expect(readColumn(table, '订单号')).toEqual(['SO-1003', 'SO-1005', 'SO-1001', 'SO-1006']));
    await expect(amountOf(readTotal(table, '金额'))).toBe(6470);
    await waitFor(() => expect(bars(canvasElement)).toHaveLength(4));
    await expect(canvas.getByRole('link', {
      name: /^出库异常处理/
    })).toBeVisible();
  }
}`,...M.parameters?.docs?.source}}},N.parameters={...N.parameters,docs:{...N.parameters?.docs,source:{originalSource:`{
  ...DisplayGlobalFilter,
  // A desk: below \`md\` the bar is one button and a sheet (D26 Q38).
  decorators: [DESK],
  play: async ({
    canvasElement
  }) => {
    const table = await findDataTable(canvasElement);
    // 华南 reaches both panels through their own warehouse field.
    await waitFor(() => expect(readColumn(table, '订单号')).toEqual(['SO-1005']));
    await expect(amountOf(readTotal(table, '金额'))).toBe(1760);
    await waitFor(() => expect(bars(canvasElement)).toHaveLength(1));
    // The value is the filter bar's, and the bar says it once: no
    // 「正在显示」 band over the panels says it again (D27).
    const canvas = within(canvasElement);
    const bar = canvas.getByRole('region', {
      name: zhCN['label.filters.bar']
    });
    await expect(within(bar).getByRole('combobox', {
      name: '仓库'
    })).toHaveTextContent('华南');
    await expect(canvas.queryByRole('region', {
      name: zhCN['label.applied.title']
    })).toBeNull();
  }
}`,...N.parameters?.docs?.source}}},P.parameters={...P.parameters,docs:{...P.parameters?.docs,source:{originalSource:`{
  ...DisplayPanelUnavailable,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await expect(await canvas.findByText(zhCN['label.panel.out.missing'])).toBeVisible();
    const out = canvasElement.querySelector('[data-slot="panel-unavailable"]') as HTMLElement;
    await expect(out).toHaveTextContent(zhCN['label.panel.way-out.share']);
    await expect(out.textContent).not.toContain('不可用');
    await expect(out.textContent).not.toContain('orders-pending');
    await expect(within(out).queryByRole('button')).toBeNull();
    // The panel keeps its heading, one level under the view's own.
    await expect(canvas.getByRole('heading', {
      level: 3,
      name: '待出库明细'
    })).toBeVisible();
    // The other data panel is not taken down with it.
    await waitFor(() => expect(bars(canvasElement)).toHaveLength(4));
  }
}`,...P.parameters?.docs?.source},description:{story:`A panel whose view was deleted says why once, in the reader's words, and
who can bring it back — never 「不可用」 twice, never the id it points at,
and no button offering what nothing on the board can do yet (U5).`,...P.parameters?.docs?.description}}},F.parameters={...F.parameters,docs:{...F.parameters?.docs,source:{originalSource:`{
  ...DisplayAllPanels,
  decorators: [Story => <div style={{
    width: 414
  }}>
        <Story />
      </div>],
  play: async ({
    canvasElement
  }) => {
    const grid = await waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>('[data-slot="dashboard-grid"]');
      expect(found).toHaveAttribute('data-narrow');
      return found!;
    });
    await chartsDrawn(canvasElement);
    await waitFor(() => expect(bars(canvasElement)).toHaveLength(4));

    // No sideways scroll: the grid holds its panels inside its own width.
    // The library slides an item to a new place over 200ms, so the panels
    // are measured once they have landed.
    const column = grid.getBoundingClientRect();
    await waitFor(() => {
      const panels = [...grid.querySelectorAll<HTMLElement>('.react-grid-item')].map(item => ({
        title: item.querySelector('[data-slot="panel-title"]')?.textContent,
        box: item.getBoundingClientRect()
      }));
      // Reading order, top to bottom: the stored layout puts the two data
      // panels side by side on the first row and the runbook under them.
      expect([...panels].sort((a, b) => a.box.top - b.box.top).map(panel => panel.title)).toEqual(['待出库明细', '按仓库汇总', '值班手册']);
      // Every panel full width, each on a row of its own.
      for (const panel of panels) {
        expect(panel.box.width).toBeGreaterThan(column.width * 0.9);
        expect(panel.box.left).toBeGreaterThanOrEqual(column.left - 1);
        expect(panel.box.right).toBeLessThanOrEqual(column.right + 1);
      }
    });
    await expect(grid.scrollWidth).toBeLessThanOrEqual(grid.clientWidth + 1);

    // The chart's labels stay inside its drawing, and do not sit on each
    // other: the ~130px panel wrote them over one another.
    // Measured once the drawing has followed its panel to the new width.
    await waitFor(() => {
      const plot = canvasElement.querySelector('[data-slot="chart-plot"]')!.getBoundingClientRect();
      expect(plot.width).toBeGreaterThan(300);
      const labels = [...axisTicks(canvasElement, 'bottom')].map(text => text.getBoundingClientRect());
      expect(labels.length).toBeGreaterThan(0);
      for (const [index, box] of labels.entries()) {
        expect(box.left).toBeGreaterThanOrEqual(plot.left - 1);
        expect(box.right).toBeLessThanOrEqual(plot.right + 1);
        for (const other of labels.slice(index + 1)) expect(overlaps(box, other)).toBe(false);
      }
    });

    // Nothing to drag in a derived column.
    await expect(canvasElement.querySelector('[data-slot="panel-grip"]')).toBeNull();
  }
}`,...F.parameters?.docs?.source},description:{story:`A phone-width column (2026-09-23 analysis audit): at 414px the twelve
columns squeezed the analysis panel to ~130px, its bar labels over each
other and cut. Below \`md\` the grid is one column — the panels in reading
order, each full width and as tall as it was saved — so nothing scrolls
sideways and every label stays inside its chart. The saved layout is not
touched: the one column is derived, and nothing can be dragged in it.`,...F.parameters?.docs?.description}}},I.parameters={...I.parameters,docs:{...I.parameters?.docs,source:{originalSource:`{
  ...DisplayQueryFailed,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await waitFor(() => expect(canvas.getAllByText(zhCN['label.query.failed'])).toHaveLength(2));
    await expect(canvas.getByRole('link', {
      name: /^出库异常处理/
    })).toBeVisible();
  }
}`,...I.parameters?.docs?.source}}},L.parameters={...L.parameters,docs:{...L.parameters?.docs,source:{originalSource:`{
  ...DisplayBuilding,
  // The test browser is a phone's width, and below \`md\` the board is one
  // derived column with nothing to place — so this one is given a desk.
  decorators: [DESK],
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await onTheGrid(canvasElement);
    await startBuilding(canvasElement);
    const grip = canvas.getByLabelText(zhCN['label.panel.handle'].replace('{title}', '待出库明细'));
    const panel = grip.closest('.react-grid-item') as HTMLElement;
    const grid = canvasElement.querySelector('[data-slot="dashboard-grid"]') as HTMLElement;
    /** How far into the grid the panel starts, as a fraction of its width. */
    const from = () => (panel.getBoundingClientRect().left - grid.getBoundingClientRect().left) / grid.getBoundingClientRect().width;

    // The first column, give or take the grid's own padding.
    await expect(from()).toBeLessThan(0.03);
    // One handle: Enter starts arranging with it, the arrows move (V-02).
    grip.focus();
    await userEvent.keyboard('{Enter}');
    await expect(grip).toHaveAttribute('aria-pressed', 'true');
    await userEvent.keyboard('{ArrowRight}');
    // The second of 24, once the grid has finished sliding it there.
    await waitFor(() => expect(from()).toBeGreaterThan(0.04));
    // Still on the handle, still arranging; Enter ends it where it is.
    await expect(grip).toHaveFocus();
    await userEvent.keyboard('{Enter}');
    await expect(grip).toHaveAttribute('aria-pressed', 'false');

    // Named after its own panel, like the grip: each corner on the board
    // used to share one name (U8).
    const corner = within(panel).getByLabelText(zhCN['label.panel.resize'].replace('{title}', '待出库明细'));
    corner.focus();
    // Upstream keeps the corner at \`opacity: 0\` until a pointer is over the
    // panel; a keyboard that can reach it must be able to see it.
    await expect(getComputedStyle(corner).opacity).toBe('1');
  }
}`,...L.parameters?.docs?.source},description:{story:`The keyboard path, in a real browser.

jsdom already holds the contract — \`test/dashboardUi.test.tsx\` presses the
arrows on both handles and reads \`controller().panels[0].layout\` back. The
two things it cannot hold are the two this story is for. jsdom lays nothing
out, so every box is 0×0 at the origin and a panel that moved is
indistinguishable from one that did not; and it applies no stylesheet, so
the corner upstream paints only while a pointer is over the panel would
look reachable whether or not focus shows it.

The panel is measured against the grid rather than in pixels: applying a
placement re-runs the panels, and the container is re-measured as their
contents settle, so two pixel widths taken either side of a keypress are
not comparable. Where the panel starts within the grid is.`,...L.parameters?.docs?.description}}},R.parameters={...R.parameters,docs:{...R.parameters?.docs,source:{originalSource:`{
  ...DisplayEmptyDashboard,
  play: async ({
    canvasElement
  }) => {
    await expect(await within(canvasElement).findByText(zhCN['label.dashboard.empty'])).toBeVisible();
    const empty = canvasElement.querySelector('[data-slot="dashboard-empty"]') as HTMLElement;
    await expect(empty).toHaveTextContent(zhCN['label.dashboard.empty-hint']);
    await expect(within(empty).getAllByRole('button').map(button => button.textContent)).toEqual([zhCN['label.dashboard.empty.add-view'], zhCN['label.dashboard.add.new-analysis'], zhCN['label.dashboard.empty.add-heading']]);
  }
}`,...R.parameters?.docs?.source},description:{story:`What an empty dashboard is, and — for whoever may build it — the first
steps, under the words (D22 A): a view, a new analysis (the workbench
provides the dialog it opens, D22 C), a heading.`,...R.parameters?.docs?.description}}},z.parameters={...z.parameters,docs:{...z.parameters?.docs,source:{originalSource:`{
  ...DisplayAllPanels,
  args: {
    ...DisplayAllPanels.args,
    behaviour: 'outage'
  },
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    const stale = new RegExp(\`\${zhCN['label.query.stale'].replace('{error} · ', '')}$\`);
    outage.down = false;
    try {
      const table = await findDataTable(canvasElement);
      await waitFor(() => expect(readColumn(table, '订单号')).toHaveLength(4));
      await waitFor(() => expect(bars(canvasElement)).toHaveLength(4));
      outage.down = true;
      await userEvent.click(canvas.getByRole('button', {
        name: zhCN['label.toolbar.refresh']
      }));
      // Both panels say the answer is the last one, and still show it.
      await waitFor(() => expect(canvas.getAllByText(stale)).toHaveLength(2));
      await expect(readColumn(table, '订单号')).toHaveLength(4);
      // The strip above takes height from the chart, which redraws into
      // what is left — its bars grow back in rather than being there at
      // once, in the full-width panel of the phone-width test browser.
      await waitFor(() => expect(bars(canvasElement)).toHaveLength(4));
      await expect(canvas.queryByText(zhCN['label.query.failed'])).toBeNull();

      // Back up: one panel's retry re-runs that panel, and only its line goes.
      outage.down = false;
      const [first] = canvas.getAllByRole('button', {
        name: zhCN['label.query.retry']
      });
      await userEvent.click(first);
      await waitFor(() => expect(canvas.getAllByText(stale)).toHaveLength(1));
      await expect(readColumn(table, '订单号')).toHaveLength(4);
    } finally {
      outage.down = false;
    }
  }
}`,...z.parameters?.docs?.source},description:{story:`A board whose backend drops out after it has answered, and comes back.

jsdom holds the rules (\`test/dashboardPlacement.test.tsx\`); this is the
same in a real browser, with both data panels and the real title bar. The
refresh that fails keeps each panel's last answer on screen under a line
saying it is the last one — the words the workbenches and the embed use —
and that line's 「重试」 re-runs that panel alone.`,...z.parameters?.docs?.description}}},B.parameters={...B.parameters,docs:{...B.parameters?.docs,source:{originalSource:`{
  ...DisplayBuilding,
  // A desk, as for \`KeyboardLayout\`: nothing is placed in the phone-width
  // column the test browser would otherwise give it.
  decorators: [DESK],
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await onTheGrid(canvasElement);
    await startBuilding(canvasElement);
    const grip = (title: string) => canvas.getByLabelText(zhCN['label.panel.handle'].replace('{title}', title));
    const item = (title: string) => grip(title).closest('.react-grid-item') as HTMLElement;
    const runbook = item('值班手册');
    const pending = item('待出库明细');
    const overlap = () => {
      const a = runbook.getBoundingClientRect();
      const b = pending.getBoundingClientRect();
      return a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom;
    };
    await expect(overlap()).toBe(false);
    grip('值班手册').focus();
    await userEvent.keyboard('{Enter}');
    await userEvent.keyboard('{ArrowUp}');

    // Once the grid has slid them there: the runbook above, nothing overlaid.
    await waitFor(() => expect(runbook.getBoundingClientRect().top).toBeLessThan(pending.getBoundingClientRect().top));
    await waitFor(() => expect(overlap()).toBe(false));
  }
}`,...B.parameters?.docs?.source},description:{story:`A keyboard step into a neighbour moves the neighbour out of the way, in a
real layout: stepping 值班手册 up one row puts it over 待出库明细's last
row, and 待出库明细 goes below it rather than being drawn underneath — the
two trade places, and the column closes up (batch-A walk).`,...B.parameters?.docs?.description}}},V.parameters={...V.parameters,docs:{...V.parameters?.docs,source:{originalSource:`{
  ...DisplayLegacyLayout,
  decorators: [DESK],
  play: async ({
    canvasElement
  }) => {
    await onTheGrid(canvasElement);
    await findDataTable(canvasElement);
    const grid = canvasElement.querySelector('.react-grid-layout') as HTMLElement;
    const gap = 10;
    for (const panel of legacyDashboardConfig().panels) {
      const item = canvasElement.querySelector(\`.react-grid-item[data-panel-id="\${panel.id}"]\`) as HTMLElement;
      await waitFor(() => {
        const width = grid.getBoundingClientRect().width;
        const column = (width - gap * 11 - gap * 2) / 12;
        const box = item.getBoundingClientRect();
        const left = box.left - grid.getBoundingClientRect().left;
        const {
          x,
          w
        } = panel.layout;
        expect(Math.abs(left - ((column + gap) * x + gap))).toBeLessThan(1.5);
        expect(Math.abs(box.width - (column * w + (w - 1) * gap))).toBeLessThan(1.5);
      });
    }
    // Read into the new form, not moved: opening it changes nothing to save.
    await expect(within(canvasElement).queryByText(zhCN['label.header.unsaved'])).toBeNull();
  }
}`,...V.parameters?.docs?.source},description:{story:`A board stored in the 12-column grid, drawn in 24 (D22 E), measured in a
real layout: every panel sits exactly where its twelve-column numbers put
it — the grid library's arithmetic for 12 columns (a column and the gap
after it, 10px gaps and padding), against the boxes on screen, to a pixel.
The kernel test measures every panel a 12-column board can hold the same
way; this one holds that the grid on screen agrees.`,...V.parameters?.docs?.description}}},H.parameters={...H.parameters,docs:{...H.parameters?.docs,source:{originalSource:`{
  ...DisplayPreBatchCCondition,
  decorators: [DESK],
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    const table = await findDataTable(canvasElement);
    const bar = await canvas.findByRole('region', {
      name: zhCN['label.filters.bar']
    });
    await expect(within(bar).getByRole('combobox', {
      name: '仓库'
    })).toHaveTextContent('华南');
    const fixed = within(bar).getByRole('group', {
      name: zhCN['label.filters.fixed']
    });
    await expect(fixed).toHaveTextContent(zhCN['label.filters.fixed']);
    await expect(fixed).toHaveTextContent('仓库');
    await expect(fixed).toHaveTextContent('西南');
    // Read-only: its one button says why, and takes nothing out.
    await expect(within(fixed).getAllByRole('button')).toHaveLength(1);
    await expect(within(fixed).getByRole('button', {
      name: zhCN['label.filters.fixed-note']
    })).toBeVisible();
    await expect(canvas.queryByRole('region', {
      name: zhCN['label.applied.title']
    })).toBeNull();

    // 华南 and not 西南: the list answers for 华南 alone.
    await waitFor(() => expect(readColumn(table, '订单号')).toEqual(['SO-1005']));
    await expect(canvas.queryByText(zhCN['label.header.unsaved'])).toBeNull();
  }
}`,...H.parameters?.docs?.source},description:{story:`A board stored before batch C (D26 Q31): the leaf a filter could hold is
that filter's default on the filter bar, the reader's to change; the rest
is the board's fixed scope, read on the bar's row as 「固定范围」 with
nothing to remove it by — and no 「正在显示」 band says it again (D27).
Both reach the panels, and opening it changes nothing to save.`,...H.parameters?.docs?.description}}},G.parameters={...G.parameters,docs:{...G.parameters?.docs,source:{originalSource:`panelChrome('light')`,...G.parameters?.docs?.source}}},K.parameters={...K.parameters,docs:{...K.parameters?.docs,source:{originalSource:`panelChrome('dark')`,...K.parameters?.docs?.source}}},J.parameters={...J.parameters,docs:{...J.parameters?.docs,source:{originalSource:`chipEdges('light')`,...J.parameters?.docs?.source}}},Y.parameters={...Y.parameters,docs:{...Y.parameters?.docs,source:{originalSource:`chipEdges('dark')`,...Y.parameters?.docs?.source}}},X.parameters={...X.parameters,docs:{...X.parameters?.docs,source:{originalSource:`{
  ...DisplayCrossFilter,
  play: async ({
    canvasElement
  }) => {
    await waitFor(() => expect(canvasElement.querySelector('[data-slot="panel-click-filter"]')).not.toBeNull());
    const panel = canvasElement.querySelector('[data-slot="panel-click-filter"]')!.closest<HTMLElement>('[data-slot="dashboard-panel"]')!;
    const title = panel.querySelector<HTMLElement>('[data-slot="panel-title"]')!;
    const badges = panel.querySelector<HTMLElement>('[data-slot="panel-badges"]')!;
    // The whole name, not an ellipsis of it…
    await expect(title.scrollWidth).toBeLessThanOrEqual(title.clientWidth + 1);
    // …with the badges on a line of their own under it.
    await expect(badges.getBoundingClientRect().top).toBeGreaterThanOrEqual(title.getBoundingClientRect().bottom - 1);
  }
}`,...X.parameters?.docs?.source},description:{story:`On a phone's width a panel's badges take a line under its title rather
than squeezing it (U-10): the name is how a reader tells the panels
apart, and it used to be the first thing to give way.`,...X.parameters?.docs?.description}}},Z.parameters={...Z.parameters,docs:{...Z.parameters?.docs,source:{originalSource:`{
  ...DisplayEmptyDashboard,
  // The footer's order follows the page's width (\`sm\`), not the column's:
  // the runner sizes the page to a phone (\`parameters.viewport\`).
  parameters: {
    ...DisplayEmptyDashboard.parameters,
    viewport: {
      options: {
        phone: {
          name: '414×896',
          styles: {
            width: '414px',
            height: '896px'
          }
        }
      }
    }
  },
  globals: {
    viewport: {
      value: 'phone'
    }
  },
  play: async ({
    canvasElement
  }) => {
    await expect(window.innerWidth).toBe(414);
    await userEvent.click(await within(canvasElement).findByRole('button', {
      name: zhCN['label.dashboard.add.new-analysis']
    }));
    const dialog = await screen.findByRole('dialog');
    const title = within(dialog).getByRole('textbox', {
      name: zhCN['label.panel.new-analysis.title']
    });
    const add = within(dialog).getByRole('button', {
      name: zhCN['label.panel.new-analysis.add']
    });
    await expect(title.getBoundingClientRect().bottom).toBeLessThanOrEqual(add.getBoundingClientRect().top);
    await userEvent.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  }
}`,...Z.parameters?.docs?.source},description:{story:`「新建分析…」 on a phone: its title comes before 「放进仪表盘」, on screen
as in the Tab order (U-14). The registry's footer stacks its children
bottom-up on a narrow screen, which put the button over the box it adds
by.`,...Z.parameters?.docs?.description}}}})))()}$();export{M as AllPanels,R as EmptyDashboard,Y as FilterChipEdgesInDarkTheme,J as FilterChipEdgesInLightTheme,N as GlobalFilter,L as KeyboardLayout,B as KeyboardStepPushes,V as LegacyLayoutDrawsTheSame,Z as NewAnalysisTitleFirstOnAPhone,F as OnAPhone,K as PanelChromeInDarkTheme,G as PanelChromeInLightTheme,P as PanelUnavailable,H as PreBatchCFixedScope,I as QueryFailed,z as RefreshFailedKeepsData,X as TitleOverItsBadges,Q as __namedExportsOrder,k as default};
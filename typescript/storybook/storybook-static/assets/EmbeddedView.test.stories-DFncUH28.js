import{n as e}from"./rolldown-runtime-DkW27tQK.js";import{R as t,z as n}from"./styles-Dpj2y9Rj.js";import{l as r,o as i}from"./chartDom-C6NvYOT8.js";import{a,c as o,i as s,s as c,t as l}from"./readTable-DOunjEkP.js";import{a as u,c as d,d as f,f as p,h as m,l as h,m as g,n as _,o as v,p as y,r as b,s as x,t as S,u as C}from"./EmbeddedView.stories-D5wXjdaw.js";function w(e){let t=(e.scrollingElement??e.documentElement).style;return[`overflow-x`,`overflow-y`].map(e=>{let n=t.getPropertyPriority(e);return t.getPropertyValue(e)+(n?` !${n}`:``)})}function T(e){let t=e.ownerDocument.defaultView,n=e.getBoundingClientRect();return Math.abs(n.left)<1&&Math.abs(n.top)<1&&Math.abs(n.width-t.innerWidth)<1&&Math.abs(n.height-t.innerHeight)<1}var E,D,O,k,A,j,M,N,P,F,I,L,R,z,B,V,H,U,W,G,K,q,J;function Y(){return(Y=e((()=>{t(),g(),s(),r(),{expect:E,screen:D,userEvent:O,waitFor:k,within:A}=__STORYBOOK_MODULE_TEST__,j={...m,title:`View Engine/数据视图/EmbeddedView/回归`,tags:[`!dev`,`!autodocs`,`test`],parameters:{...m.parameters}},M=[`SO-1003`,`SO-1005`,`SO-1001`,`SO-1006`],N=e=>A(e).getByRole(`button`,{name:/全屏查看|退出全屏/}),P=e=>e.querySelector(`.host-embed`),F={...b,play:async({canvasElement:e})=>{let t=A(e),r=await t.findByRole(`table`);await k(()=>E(a(r,`订单号`)).toEqual(M)),await E(l(o(r,`金额`))).toBe(6470),await E(e.querySelector(`[data-slot="applied-bar"]`)).not.toBeNull();for(let t of[`view-header`,`view-sidebar`,`view-list`,`result-toolbar`,`save-actions`,`editor-band`,`record-pagination`])await E(e.querySelector(`[data-slot="${t}"]`)).toBeNull();let i=e.querySelector(`[data-slot="applied-bar"]`);await E(A(i).queryAllByRole(`button`)).toHaveLength(0);for(let e of A(r).getAllByRole(`columnheader`))await E(A(e).queryByRole(`button`)).toBeNull();await E(e.querySelector(`[data-slot="view-expand"]`)).toBeNull(),await E(t.queryByRole(`button`,{name:n[`label.workbench.expand-view`]})).toBeNull(),await E(t.queryByRole(`button`,{name:n[`label.workbench.collapse-view`]})).toBeNull()}},I={...b,globals:{theme:`dark`},play:async({canvasElement:e})=>{let t=await A(e).findByRole(`table`);await k(()=>E(a(t,`订单号`)).toEqual(M));let n=P(e),r=n.closest(`[data-slot="card"]`),i=e=>getComputedStyle(e).backgroundColor;await E(document.documentElement).toHaveClass(`dark`),await E(i(n)).toBe(i(r)),await k(()=>E(i(t.querySelector(`tbody tr`))).toBe(i(r)));for(let e of[t.querySelector(`thead tr`),t.querySelector(`tfoot tr`)])await E(i(e)).not.toBe(i(r)),await E(i(e)).not.toMatch(/transparent|rgba\(0, 0, 0, 0\)/)}},L={...p,play:async({canvasElement:e})=>{let t=await A(e).findByRole(`table`);await k(()=>E(a(t,`订单号`)).toEqual([`SO-1001`])),await E(l(o(t,`金额`))).toBe(1280),await E(A(e).queryByText(n[`label.scope.refused`])).toBeNull()}},R={...C,play:async({canvasElement:e})=>{let t=A(e),r=await t.findByRole(`table`);await k(()=>E(a(r,`订单号`)).toEqual([`SO-1001`])),await O.click(t.getByRole(`button`,{name:`按客户收窄`})),await E(await t.findByText(n[`label.scope.refused`])).toBeVisible(),await E(a(await t.findByRole(`table`),`订单号`)).toEqual([`SO-1001`]),await O.click(t.getByRole(`button`,{name:`不收窄`})),await k(()=>E(t.queryByText(n[`label.scope.refused`])).toBeNull()),await k(()=>E(a(t.getByRole(`table`),`订单号`)).toEqual(M))}},z={...f,play:async({canvasElement:e})=>{let t=A(e);await E(await t.findByText(n[`label.scope.refused`])).toBeVisible(),await E(t.queryByText(n[`label.view.needs-fixing`])).toBeNull();let r=await t.findByRole(`table`);await k(()=>E(a(r,`订单号`)).toEqual(M))}},B={...u,play:async({canvasElement:e})=>{let t=A(e);await E(await t.findByText(n[`label.record.empty`])).toBeVisible(),await E(t.queryByRole(`table`)).toBeNull()}},V={...d,play:async({canvasElement:e})=>{let t=A(e),r=await k(()=>{let t=e.querySelector(`[data-slot="status-strip"][data-tone="error"]`);if(!t)throw Error(`no error strip yet`);return t});await E(r).toHaveTextContent(`仓储服务暂时不可用`),await E(t.queryByRole(`table`)).toBeNull(),await E(t.queryByRole(`button`,{name:n[`label.query.retry`]})).toBeNull(),await E(t.getByText(`明远商贸 · 客户详情`)).toBeVisible()}},H={...S,play:async({canvasElement:e})=>{await k(()=>E(i(e)).toHaveLength(4)),await E(e.querySelector(`[data-slot="applied-bar"]`)).not.toBeNull()}},U={...y,play:async({canvasElement:e})=>{let t=await A(e).findByRole(`table`);await k(()=>E(l(c(t,`金额`))).toBe(6470)),await E(A(e).getByText(n[`label.summary.scope.page`])).toBeVisible()}},W={...v,play:async({canvasElement:e})=>{let t=A(e);await t.findByRole(`table`);let r=P(e),i=N(e),a=e.ownerDocument,o=w(a);await E(r).not.toHaveAttribute(`data-view-expanded`),await O.click(i),await k(()=>E(r).toHaveAttribute(`data-view-expanded`,`true`)),await k(()=>E(T(r)).toBe(!0)),await E(w(a)).toEqual([`hidden !important`,`hidden !important`]),await E(r.contains(i)).toBe(!1);let s=t.getByRole(`button`,{name:n[`label.workbench.collapse-view`]});await E(r.contains(s)).toBe(!0),await O.click(s),await k(()=>E(r).not.toHaveAttribute(`data-view-expanded`)),await E(w(a)).toEqual(o),await E(a.activeElement).toBe(i),await E(t.queryByRole(`button`,{name:n[`label.workbench.collapse-view`]})).toBeNull(),await O.click(i),await k(()=>E(r).toHaveAttribute(`data-view-expanded`,`true`)),await O.keyboard(`{Escape}`),await k(()=>E(r).not.toHaveAttribute(`data-view-expanded`)),await E(w(a)).toEqual(o),await E(a.activeElement).toBe(i),await E(i).toHaveAttribute(`aria-expanded`,`false`),await O.click(i),await k(()=>E(i).toHaveAttribute(`aria-expanded`,`true`)),await O.click(N(e)),await k(()=>E(r).not.toHaveAttribute(`data-view-expanded`)),await E(w(a)).toEqual(o)}},G={...x,play:async({canvasElement:e})=>{let t=A(e),r=await t.findByRole(`table`);await k(()=>E(a(r,`订单号`)).toEqual([`SO-1001`])),await O.click(A(r).getByRole(`button`,{name:/按订单号升序排序/})),await k(()=>E(A(r).getByRole(`columnheader`,{name:/订单号/})).toHaveAttribute(`aria-sort`,`ascending`)),await E(e.querySelector(`[data-slot="record-pagination"]`)).not.toBeNull();let i=t.getByRole(`searchbox`,{name:`搜索订单`});await O.type(i,`1003{Enter}`),await k(()=>E(t.getByText(n[`label.record.empty`])).toBeVisible()),await O.clear(i),await O.type(i,`1001{Enter}`),await k(async()=>E(a(await t.findByRole(`table`),`订单号`)).toEqual([`SO-1001`])),await E(t.getByRole(`button`,{name:n[`label.export.title`]})).toBeVisible(),await O.click(t.getByRole(`button`,{name:n[`label.panel.open`]}));let o=e.querySelector(`[data-host-route]`);await E(o).toHaveTextContent(`orders-pending`),await E(o).toHaveTextContent(`CN-EAST`)}},K={...h,play:async({canvasElement:e})=>{let t=A(e),r=await t.findByRole(`table`);await k(()=>E(a(r,`订单号`)).toEqual(M)),await E(A(r).queryAllByRole(`checkbox`)).toHaveLength(0),await O.click(t.getByRole(`button`,{name:n[`label.export.title`]}));let i=await D.findByRole(`dialog`,{name:n[`label.export.title`]});await E(A(i).queryByRole(`radio`)).toBeNull(),await E(i).not.toHaveTextContent(n[`label.export.scope`]),await O.keyboard(`{Escape}`),await k(()=>E(D.queryByRole(`dialog`)).toBeNull())}},q={..._,play:async({canvasElement:e})=>{let t=A(e);await k(()=>E(i(e)).toHaveLength(4));let r=t.getByRole(`group`,{name:n[`label.analysis.layout`]});await O.click(A(r).getByRole(`button`,{name:n[`label.layout.table`]}));let a=await k(()=>{let t=e.querySelector(`[data-slot="analysis-table"] table`);return E(t).not.toBeNull(),t}),o=await A(a).findByRole(`row`,{name:/华东/});await E(o).toHaveAttribute(`aria-haspopup`,`menu`),await O.click(o),await O.click(await D.findByRole(`menuitem`,{name:new RegExp(n[`label.drill.records`])}));let s=e.querySelector(`[data-host-route]`);await k(()=>E(s).toHaveTextContent(`CN-EAST`))}},J=[`Default`,`OnTheCardInTheDark`,`ScopedByHost`,`ScopeRefused`,`ScopeRefusedOnOpen`,`EmptyResult`,`QueryFailed`,`AnalysisEmbed`,`TotalCoversThisPageOnly`,`FillTheScreen`,`Interactive`,`ReadOnlyExport`,`AnalysisInteractive`],F.parameters={...F.parameters,docs:{...F.parameters?.docs,source:{originalSource:`{
  ...DisplayDefault,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    await waitFor(() => expect(readColumn(table, '订单号')).toEqual(PENDING_BY_AMOUNT));
    await expect(amountOf(readTotal(table, '金额'))).toBe(6470);

    // The result, and what it was fetched under — and nothing else. Every
    // piece of chrome a workbench has is absent here, which is the whole
    // definition of the component.
    await expect(canvasElement.querySelector('[data-slot="applied-bar"]')).not.toBeNull();
    for (const slot of ['view-header', 'view-sidebar', 'view-list', 'result-toolbar', 'save-actions', 'editor-band', 'record-pagination']) await expect(canvasElement.querySelector(\`[data-slot="\${slot}"]\`)).toBeNull();

    // Read-only: a ✕ here would let a reader drop a saved condition, which on
    // a page that embedded this view to show one customer's orders is the
    // page quietly listing everyone's.
    const applied = canvasElement.querySelector<HTMLElement>('[data-slot="applied-bar"]')!;
    await expect(within(applied).queryAllByRole('button')).toHaveLength(0);

    // The read-only tier (D22): the headers are read, not pressed.
    for (const head of within(table).getAllByRole('columnheader')) await expect(within(head).queryByRole('button')).toBeNull();

    // No expand control of its own: the only one on screen is the host's.
    await expect(canvasElement.querySelector('[data-slot="view-expand"]')).toBeNull();
    await expect(canvas.queryByRole('button', {
      name: zhCN['label.workbench.expand-view']
    })).toBeNull();
    // And the way out stays out of the page — and out of the a11y tree —
    // while nothing is filling the screen.
    await expect(canvas.queryByRole('button', {
      name: zhCN['label.workbench.collapse-view']
    })).toBeNull();
  }
}`,...F.parameters?.docs?.source}}},I.parameters={...I.parameters,docs:{...I.parameters?.docs,source:{originalSource:`{
  ...DisplayDefault,
  globals: {
    theme: 'dark'
  },
  play: async ({
    canvasElement
  }) => {
    const table = await within(canvasElement).findByRole('table');
    await waitFor(() => expect(readColumn(table, '订单号')).toEqual(PENDING_BY_AMOUNT));
    const surface = surfaceOf(canvasElement);
    const card = surface.closest<HTMLElement>('[data-slot="card"]')!;
    const paint = (element: Element) => getComputedStyle(element).backgroundColor;
    await expect(document.documentElement).toHaveClass('dark');
    await expect(paint(surface)).toBe(paint(card));
    // A row is the card's colour too — opaque, so a pinned cell still hides
    // the column scrolling under it. Waited for: a row fades between
    // colours (\`transition-colors\`), and the dark class can land after it
    // was first painted light.
    await waitFor(() => expect(paint(table.querySelector('tbody tr')!)).toBe(paint(card)));
    // The two sticky bands — the header and the totals — are their own
    // colour, and opaque.
    for (const band of [table.querySelector('thead tr')!, table.querySelector('tfoot tr')!]) {
      await expect(paint(band)).not.toBe(paint(card));
      await expect(paint(band)).not.toMatch(/transparent|rgba\\(0, 0, 0, 0\\)/);
    }
  }
}`,...I.parameters?.docs?.source},description:{story:"暗色下嵌入块与所在的卡片同底。\n\n根涂的是 `--background`，暗色下它比 `--card` 深一档，嵌入块在卡片里读成一块\n更深的区域（明色两者都是白，所以看不出）。宿主在卡片上把 `--fve-background`\n与 `--fve-dark-background` 设为卡片色，嵌入视图、它的行都涂卡片色；表头与合计\n那两条吸附带仍然不透明。",...I.parameters?.docs?.description}}},L.parameters={...L.parameters,docs:{...L.parameters?.docs,source:{originalSource:`{
  ...DisplayScopedByHost,
  play: async ({
    canvasElement
  }) => {
    const table = await within(canvasElement).findByRole('table');
    // The host's condition went in *with* the config, so the opening query
    // was already narrowed: one 华东 order out of the four pending ones.
    await waitFor(() => expect(readColumn(table, '订单号')).toEqual(['SO-1001']));
    await expect(amountOf(readTotal(table, '金额'))).toBe(1280);
    // Nothing was refused, so nothing says anything was.
    await expect(within(canvasElement).queryByText(zhCN['label.scope.refused'])).toBeNull();
  }
}`,...L.parameters?.docs?.source}}},R.parameters={...R.parameters,docs:{...R.parameters?.docs,source:{originalSource:`{
  ...DisplayScopeRefused,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    // It opens on a narrowing that *was* accepted.
    await waitFor(() => expect(readColumn(table, '订单号')).toEqual(['SO-1001']));

    // The host swaps it for one this definition cannot take.
    await userEvent.click(canvas.getByRole('button', {
      name: '按客户收窄'
    }));
    await expect(await canvas.findByText(zhCN['label.scope.refused'])).toBeVisible();
    // The refusal left the previous narrowing running, which is exactly why
    // it has to be said out loud: the page asked for another customer and is
    // still being shown this one's.
    await expect(readColumn(await canvas.findByRole('table'), '订单号')).toEqual(['SO-1001']);

    // Back to something admissible and the refusal goes with it.
    await userEvent.click(canvas.getByRole('button', {
      name: '不收窄'
    }));
    await waitFor(() => expect(canvas.queryByText(zhCN['label.scope.refused'])).toBeNull());
    await waitFor(() => expect(readColumn(canvas.getByRole('table'), '订单号')).toEqual(PENDING_BY_AMOUNT));
  }
}`,...R.parameters?.docs?.source}}},z.parameters={...z.parameters,docs:{...z.parameters?.docs,source:{originalSource:`{
  ...DisplayScopeRefusedOnOpen,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await expect(await canvas.findByText(zhCN['label.scope.refused'])).toBeVisible();
    await expect(canvas.queryByText(zhCN['label.view.needs-fixing'])).toBeNull();
    // And the un-narrowed result is on screen under the alert, as it is for
    // a narrowing refused later: the page not getting the range it asked for
    // is no reason to withhold what the view does say.
    const table = await canvas.findByRole('table');
    await waitFor(() => expect(readColumn(table, '订单号')).toEqual(PENDING_BY_AMOUNT));
  }
}`,...z.parameters?.docs?.source},description:{story:`The same refusal on the first open, in the same words (D17-5).

A scope the definition cannot take no longer rides into the first
admission as part of the config: what was refused is the *page's* own
condition, and the page is the only one who could change it — the view is
fine, and a host cannot fix somebody else's saved config anyway.`,...z.parameters?.docs?.description}}},B.parameters={...B.parameters,docs:{...B.parameters?.docs,source:{originalSource:`{
  ...DisplayEmptyResult,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await expect(await canvas.findByText(zhCN['label.record.empty'])).toBeVisible();
    await expect(canvas.queryByRole('table')).toBeNull();
  }
}`,...B.parameters?.docs?.source}}},V.parameters={...V.parameters,docs:{...V.parameters?.docs,source:{originalSource:`{
  ...DisplayQueryFailed,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    // The strip says what the source answered, in the source's own words:
    // the embed has nothing else on screen to explain an empty card with.
    const strip = await waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>('[data-slot="status-strip"][data-tone="error"]');
      if (!found) throw new Error('no error strip yet');
      return found;
    });
    await expect(strip).toHaveTextContent('仓储服务暂时不可用');
    await expect(canvas.queryByRole('table')).toBeNull();
    // No toolbar, so no retry: a button that was the block's only control
    // would make the result look like one.
    await expect(canvas.queryByRole('button', {
      name: zhCN['label.query.retry']
    })).toBeNull();
    // The host's page is untouched by a failed query inside its card.
    await expect(canvas.getByText('明远商贸 · 客户详情')).toBeVisible();
  }
}`,...V.parameters?.docs?.source}}},H.parameters={...H.parameters,docs:{...H.parameters?.docs,source:{originalSource:`{
  ...DisplayAnalysisEmbed,
  play: async ({
    canvasElement
  }) => {
    // Dispatch by kind is the embed's own: the host handed over an id.
    await waitFor(() => expect(drawnMarks(canvasElement)).toHaveLength(4));
    await expect(canvasElement.querySelector('[data-slot="applied-bar"]')).not.toBeNull();
  }
}`,...H.parameters?.docs?.source}}},U.parameters={...U.parameters,docs:{...U.parameters?.docs,source:{originalSource:`{
  ...DisplayTotalCoversThisPageOnly,
  play: async ({
    canvasElement
  }) => {
    const table = await within(canvasElement).findByRole('table');
    // The aggregation was refused, so the summary falls back to this page —
    // and says so, which matters more here than in a workbench: there is no
    // editor, no toolbar and no scope bar to correct a number that lied.
    await waitFor(() => expect(amountOf(readPage(table, '金额'))).toBe(6470));
    await expect(within(canvasElement).getByText(zhCN['label.summary.scope.page'])).toBeVisible();
  }
}`,...U.parameters?.docs?.source}}},W.parameters={...W.parameters,docs:{...W.parameters?.docs,source:{originalSource:`{
  ...DisplayFillTheScreen,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    const surface = surfaceOf(canvasElement);
    const toggle = hostToggle(canvasElement);
    const doc = canvasElement.ownerDocument;
    const before = held(doc);
    await expect(surface).not.toHaveAttribute('data-view-expanded');
    await userEvent.click(toggle);
    await waitFor(() => expect(surface).toHaveAttribute('data-view-expanded', 'true'));
    // On the viewport to the pixel, whatever the host's page did around it.
    await waitFor(() => expect(onViewport(surface)).toBe(true));
    // And the page underneath stops scrolling, in a way a host stylesheet
    // cannot outrank.
    await expect(held(doc)).toEqual(['hidden !important', 'hidden !important']);
    // The host's own chrome is underneath it now — which is the whole reason
    // the surface has to grow a way out.
    await expect(surface.contains(toggle)).toBe(false);
    const exit = canvas.getByRole('button', {
      name: zhCN['label.workbench.collapse-view']
    });
    await expect(surface.contains(exit)).toBe(true);

    // 1. The surface's own exit — the only one a touch device has.
    await userEvent.click(exit);
    await waitFor(() => expect(surface).not.toHaveAttribute('data-view-expanded'));
    await expect(held(doc)).toEqual(before);
    await expect(doc.activeElement).toBe(toggle);
    await expect(canvas.queryByRole('button', {
      name: zhCN['label.workbench.collapse-view']
    })).toBeNull();

    // 2. Escape gives the page back, from the host's control again.
    await userEvent.click(toggle);
    await waitFor(() => expect(surface).toHaveAttribute('data-view-expanded', 'true'));
    await userEvent.keyboard('{Escape}');
    await waitFor(() => expect(surface).not.toHaveAttribute('data-view-expanded'));
    await expect(held(doc)).toEqual(before);
    await expect(doc.activeElement).toBe(toggle);

    // 3. And the host's control is still a toggle: it says so, both ways.
    await expect(toggle).toHaveAttribute('aria-expanded', 'false');
    await userEvent.click(toggle);
    await waitFor(() => expect(toggle).toHaveAttribute('aria-expanded', 'true'));
    await userEvent.click(hostToggle(canvasElement));
    await waitFor(() => expect(surface).not.toHaveAttribute('data-view-expanded'));
    await expect(held(doc)).toEqual(before);
  }
}`,...W.parameters?.docs?.source},description:{story:`The one thing an embed cannot do for itself, done by the host.

The control is the host's, so a surface filling the screen covers it — and
the surface grows its own way out for exactly that case. Three exits are
checked here because each is the only one some user has: Escape for a
keyboard, the surface's own button for a touch device, and the host's
control again once the page is back.`,...W.parameters?.docs?.description}}},G.parameters={...G.parameters,docs:{...G.parameters?.docs,source:{originalSource:`{
  ...DisplayInteractive,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    // The page narrows to the east warehouse: one pending order.
    await waitFor(() => expect(readColumn(table, '订单号')).toEqual(['SO-1001']));
    // Sorted by a header, and paged.
    await userEvent.click(within(table).getByRole('button', {
      name: /按订单号升序排序/
    }));
    await waitFor(() => expect(within(table).getByRole('columnheader', {
      name: /订单号/
    })).toHaveAttribute('aria-sort', 'ascending'));
    await expect(canvasElement.querySelector('[data-slot="record-pagination"]')).not.toBeNull();

    // The search sits at the applied band's end, and narrows within the
    // page's scope: nothing in the east warehouse matches 1003.
    const box = canvas.getByRole('searchbox', {
      name: '搜索订单'
    });
    await userEvent.type(box, '1003{Enter}');
    await waitFor(() => expect(canvas.getByText(zhCN['label.record.empty'])).toBeVisible());
    await userEvent.clear(box);
    await userEvent.type(box, '1001{Enter}');
    await waitFor(async () => expect(readColumn(await canvas.findByRole('table'), '订单号')).toEqual(['SO-1001']));

    // The export, in the first row.
    await expect(canvas.getByRole('button', {
      name: zhCN['label.export.title']
    })).toBeVisible();

    // 在工作台中打开: the saved view under the page's narrowing.
    await userEvent.click(canvas.getByRole('button', {
      name: zhCN['label.panel.open']
    }));
    const route = canvasElement.querySelector('[data-host-route]')!;
    await expect(route).toHaveTextContent('orders-pending');
    await expect(route).toHaveTextContent('CN-EAST');
  }
}`,...G.parameters?.docs?.source},description:{story:`The interactive tier end to end (D22): a header orders the rows, the
search narrows them within the page's own narrowing, the export is in the
first row, and 在工作台中打开 hands the host's route the saved view under
the page's condition.`,...G.parameters?.docs?.description}}},K.parameters={...K.parameters,docs:{...K.parameters?.docs,source:{originalSource:`{
  ...DisplayReadOnlyExport,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    await waitFor(() => expect(readColumn(table, '订单号')).toEqual(PENDING_BY_AMOUNT));
    // No row's box and no 「全选」 in the header.
    await expect(within(table).queryAllByRole('checkbox')).toHaveLength(0);
    await userEvent.click(canvas.getByRole('button', {
      name: zhCN['label.export.title']
    }));
    const dialog = await screen.findByRole('dialog', {
      name: zhCN['label.export.title']
    });
    // One scope is not a choice: no 「导出哪些记录」 group, no radio.
    await expect(within(dialog).queryByRole('radio')).toBeNull();
    await expect(dialog).not.toHaveTextContent(zhCN['label.export.scope']);
    await userEvent.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  }
}`,...K.parameters?.docs?.source},description:{story:`The read-only tier with the export switched on (D26 Q36): the export is a
switch and does not change the tier, so the rows carry no checkboxes, and
the window has one scope to offer — every row the conditions match, as a
dashboard panel's 「导出数据…」 does.`,...K.parameters?.docs?.description}}},q.parameters={...q.parameters,docs:{...q.parameters?.docs,source:{originalSource:`{
  ...DisplayAnalysisInteractive,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await waitFor(() => expect(drawnMarks(canvasElement)).toHaveLength(4));
    const layout = canvas.getByRole('group', {
      name: zhCN['label.analysis.layout']
    });
    await userEvent.click(within(layout).getByRole('button', {
      name: zhCN['label.layout.table']
    }));
    const table = await waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>('[data-slot="analysis-table"] table');
      expect(found).not.toBeNull();
      return found!;
    });
    const row = await within(table).findByRole('row', {
      name: /华东/
    });
    await expect(row).toHaveAttribute('aria-haspopup', 'menu');
    await userEvent.click(row);
    await userEvent.click(await screen.findByRole('menuitem', {
      name: new RegExp(zhCN['label.drill.records'])
    }));
    const route = canvasElement.querySelector('[data-host-route]')!;
    await waitFor(() => expect(route).toHaveTextContent('CN-EAST'));
  }
}`,...q.parameters?.docs?.source},description:{story:`An analysis embed, interactive: the table｜chart switch is the reader's,
and a group's follow-up opens through the host's route.`,...q.parameters?.docs?.description}}}})))()}Y();export{H as AnalysisEmbed,q as AnalysisInteractive,F as Default,B as EmptyResult,W as FillTheScreen,G as Interactive,I as OnTheCardInTheDark,V as QueryFailed,K as ReadOnlyExport,R as ScopeRefused,z as ScopeRefusedOnOpen,L as ScopedByHost,U as TotalCoversThisPageOnly,J as __namedExportsOrder,j as default};
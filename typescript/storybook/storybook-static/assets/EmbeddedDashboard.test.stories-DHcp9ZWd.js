import{n as e}from"./rolldown-runtime-DkW27tQK.js";import{t}from"./jsx-runtime-DeHZSEgm.js";import{R as n,z as r}from"./styles-Dpj2y9Rj.js";import{l as i,o as a}from"./chartDom-C6NvYOT8.js";import{a as o,i as s,r as c}from"./readTable-DOunjEkP.js";import{a as l,n as u,o as d,r as f,s as p,t as m}from"./EmbeddedDashboard.stories-BwcWY-o5.js";async function h(e){return T(e).findByRole(`region`,{name:r[`label.filters.bar`]})}function g(e,t){return T(e).findByRole(`group`,{name:t})}async function _(e){let t=await g(e,`这个客户的订单`),n=await c(t);return o(n,`订单号`)}function v(e){let t=e.getBoundingClientRect();if(t.top<0||t.left<0||t.bottom>window.innerHeight||t.right>window.innerWidth)return!1;let n=document.elementFromPoint(t.left+t.width/2,t.top+t.height/2);return n!==null&&e.contains(n)}function y(e){return[...e.querySelectorAll(`.react-grid-item [data-slot="panel-title"]`)].map(e=>e.textContent??``)}var b,x,S,C,w,T,E,D,O,k,A,j,M,N,P;function F(){return(F=e((()=>{n(),d(),s(),i(),b=t(),{expect:x,screen:S,userEvent:C,waitFor:w,within:T}=__STORYBOOK_MODULE_TEST__,E={...p,title:`View Engine/仪表盘视图/EmbeddedDashboard/回归`,tags:[`!dev`,`!autodocs`,`test`],parameters:{...p.parameters}},D=(e,t={})=>Object.entries(t).reduce((e,[t,n])=>e.replace(`{${t}}`,n),r[e]),O={...m,play:async({canvasElement:e})=>{let t=T(e),n=await h(e),i=T(n).getByRole(`group`,{name:D(`label.embed.locked-name`,{filter:`客户`})});await x(i).toHaveTextContent(`晨光食品`),await x(T(i).queryByRole(`combobox`)).toBeNull(),await x(T(i).getByRole(`button`,{name:r[`label.embed.locked`]})).toBeVisible(),await w(async()=>x(await _(e)).toEqual([`SO-1003`,`SO-1001`]));let a=async()=>(await C.click(T(n).getByRole(`button`,{name:/^筛选/})),S.findByRole(`dialog`,{name:r[`label.filters.bar`]})),o=async()=>{await C.keyboard(`{Escape}`),await w(()=>x(S.queryByRole(`dialog`)).toBeNull())};await C.click(T(await a()).getByRole(`combobox`,{name:D(`label.date.period-of`,{field:`下单时间`})})),await C.click(await S.findByRole(`option`,{name:r[`label.relative.preset.lastMonth`]})),await o();let s=await g(e,`这个客户的订单`);await w(()=>x(s).toHaveTextContent(r[`label.record.empty`]));let c=e.querySelector(`[data-host-address]`);await w(()=>x(decodeURIComponent(c.textContent??``)).toContain(`lastMonth`)),await x(decodeURIComponent(c.textContent??``)).not.toContain(`c-03`);let l=e.querySelector(`.story-app-page`);await x(l.scrollWidth).toBeLessThanOrEqual(l.clientWidth),await x(document.documentElement.scrollWidth).toBeLessThanOrEqual(document.documentElement.clientWidth),await C.click(T(await a()).getByRole(`button`,{name:r[`label.filters.clear`]})),await o(),await w(async()=>x(await _(e)).toEqual([`SO-1003`,`SO-1001`])),await x(i).toHaveTextContent(`晨光食品`);let u=await g(e,`按仓库金额`),d=await T(u).findByRole(`row`,{name:/华北/});await x(d).toHaveAttribute(`aria-haspopup`,`menu`),await C.click(d),await C.click(await S.findByRole(`menuitem`,{name:new RegExp(r[`label.drill.records`])}));let f=e.querySelector(`[data-host-route]`);await w(()=>x(f).toHaveTextContent(`c-03`)),await x(f).toHaveTextContent(`CN-NORTH`),await C.click(t.getByRole(`button`,{name:D(`label.panel.menu`,{title:`这个客户的订单`})})),await C.click(await S.findByRole(`menuitem`,{name:r[`label.panel.open`]})),await w(()=>x(f).toHaveTextContent(`customer-orders`)),await x(f).toHaveTextContent(`c-03`),await x(l.scrollWidth).toBeLessThanOrEqual(l.clientWidth)}},k={...m,play:async({canvasElement:e})=>{let t=T(e);await c(await g(e,`这个客户的订单`));let n=t.getByRole(`button`,{name:D(`label.panel.menu`,{title:`这个客户的订单`})});await C.click(n),await C.click(await S.findByRole(`menuitem`,{name:r[`label.panel.export`]}));let i=await S.findByRole(`dialog`,{name:r[`label.export.title`]});await x(i.textContent).toContain(`晨光食品`),await x(i.textContent).toMatch(/文件：这个客户的订单-\d{4}-\d{2}-\d{2}\.csv/),await C.keyboard(`{Escape}`),await w(()=>x(S.queryByRole(`dialog`)).toBeNull()),await w(()=>x(n).toHaveFocus()),await C.click(t.getByRole(`button`,{name:D(`label.panel.menu`,{title:`按仓库金额`})})),await x(T(await S.findByRole(`menu`)).queryByRole(`menuitem`,{name:r[`label.panel.export`]})).toBeNull(),await C.keyboard(`{Escape}`)}},A={...l,play:async({canvasElement:e})=>{let t=T(e);await x(await t.findByRole(`heading`,{level:2,name:`出库概览`})).toBeVisible();let n=await h(e);await x(T(n).getByRole(`group`,{name:D(`label.embed.locked-name`,{filter:`仓库`})})).toHaveTextContent(`华东`),await x(T(n).queryByRole(`button`,{name:r[`label.filters.clear`]})).toBeNull();let i=await g(e,`待出库明细`),s=await c(i);await w(()=>x(o(s,`订单号`)).toEqual([`SO-1001`]));let l=await g(e,`按仓库汇总`);await w(()=>x(a(l)).toHaveLength(1)),await x(e.querySelector(`[data-slot="panel-menu"]`)).toBeNull(),await x(e.querySelector(`[data-pickable], [aria-haspopup="menu"]`)).toBeNull(),await x(t.queryByRole(`button`,{name:r[`label.dashboard.edit`]})).toBeNull();let u=e.querySelector(`[data-wall]`),d=u.querySelector(`.host-embed`);await x(d).toHaveAttribute(`data-embed-size`,`fill`),await x(Math.abs(d.getBoundingClientRect().height-u.getBoundingClientRect().height)).toBeLessThan(1)}},j={...u,play:async({canvasElement:e})=>{let t=T(e);await c(await g(e,`待出库明细`)),await w(()=>x(a(e)).toHaveLength(4)),await x(t.getByRole(`link`,{name:/出库异常处理/})).toBeVisible();let n=await w(()=>{let t=e.querySelector(`[data-slot="panel-unavailable"]`);return x(t).not.toBeNull(),t});await x(n).toHaveTextContent(r[`label.panel.out.missing`]),await x(n).toHaveTextContent(r[`label.panel.way-out.share`]),await x(e.querySelector(`[data-slot="status-strip"][data-tone="error"]`)).toBeNull()}},M=e=>(0,b.jsx)(`div`,{"data-host-scroller":!0,style:{width:880,height:560,overflowY:`auto`},children:(0,b.jsx)(e,{})}),N={...f,decorators:[M],play:async({canvasElement:e})=>{let t=T(e),n=e.querySelector(`[data-host-stored]`);await x(await t.findByRole(`heading`,{level:2,name:`班组看板`})).toBeVisible(),await c(await g(e,`待出库明细`)),await x(n).toHaveTextContent(`还没保存过`);let i=t.getByRole(`button`,{name:r[`label.dashboard.edit`]});await C.click(i),await x(await t.findByRole(`region`,{name:new RegExp(r[`label.dashboard.editing`])})).toBeVisible(),await C.click(t.getByRole(`button`,{name:r[`label.dashboard.add`]})),await C.click(await S.findByRole(`menuitem`,{name:r[`label.dashboard.add.markdown`]}));let a=await S.findByRole(`dialog`,{name:r[`label.content.markdown.add`]});await C.type(T(a).getByRole(`textbox`,{name:r[`label.content.markdown.field`]}),`夜班交接前清点**华东仓**的待出库单。`),await C.type(T(a).getByRole(`textbox`,{name:r[`label.content.title`]}),`交接说明`),await C.click(T(a).getByRole(`button`,{name:r[`label.content.submit-add`]})),await w(()=>x(S.queryByRole(`dialog`)).toBeNull()),await w(()=>x(y(e)).toContain(`交接说明`));let o=await g(e,`交接说明`);await x(o).toHaveTextContent(`夜班交接前清点华东仓的待出库单。`),await x(n).toHaveTextContent(`还没保存过`);let s=e.querySelector(`[data-host-scroller]`),l=e.querySelector(`[data-slot="dashboard-edit-bar"]`),u=l.getBoundingClientRect().top;o.scrollIntoView({block:`end`}),await w(()=>x(s.scrollTop).toBeGreaterThan(u)),await x(l.getBoundingClientRect().top).toBe(s.getBoundingClientRect().top);for(let e of[`label.dialog.cancel`,`label.dashboard.save`])await x(v(T(l).getByRole(`button`,{name:r[e]}))).toBe(!0);s.scrollTop=0,await C.click(t.getByRole(`button`,{name:r[`label.dashboard.save`]}));let d=await S.findByRole(`alertdialog`);await C.click(T(d).getByRole(`button`,{name:r[`label.save.shared-confirm`]})),await w(()=>x(n).toHaveTextContent(`修订 2 · 4 个面板`)),await w(()=>x(e.querySelector(`[data-slot="dashboard-edit-bar"]`)).toBeNull()),await x(t.getByRole(`button`,{name:r[`label.dashboard.edit`]})).toHaveFocus(),await x(y(e)).toContain(`交接说明`),await C.click(t.getByRole(`button`,{name:r[`label.dashboard.edit`]})),await C.click(await t.findByRole(`button`,{name:r[`label.dashboard.add`]})),await C.click(await S.findByRole(`menuitem`,{name:r[`label.dashboard.add.heading`]})),await C.keyboard(`{Enter}`),await w(()=>x(y(e)).toContain(r[`label.dashboard.new-heading`])),await C.click(t.getByRole(`button`,{name:r[`label.dialog.cancel`]}));let f=await S.findByRole(`alertdialog`);await C.click(T(f).getByRole(`button`,{name:r[`label.save.revert`]})),await w(()=>x(y(e)).not.toContain(r[`label.dashboard.new-heading`])),await x(y(e)).toContain(`交接说明`),await x(n).toHaveTextContent(`修订 2 · 4 个面板`),await w(()=>x(t.getByRole(`button`,{name:r[`label.dashboard.edit`]})).toHaveFocus())}},P=[`CustomerDetail`,`CustomerOrdersExport`,`WallScreenReadOnly`,`DashboardWithAPanelOut`,`EditableBoard`],O.parameters={...O.parameters,docs:{...O.parameters?.docs,source:{originalSource:`{
  ...DisplayCustomerDetail,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    const bar = await filterBar(canvasElement);
    const customer = within(bar).getByRole('group', {
      name: label('label.embed.locked-name', {
        filter: '客户'
      })
    });
    await expect(customer).toHaveTextContent('晨光食品');
    await expect(within(customer).queryByRole('combobox')).toBeNull();
    await expect(within(customer).getByRole('button', {
      name: zhCN['label.embed.locked']
    })).toBeVisible();

    // This month, this customer: two orders, the larger first.
    await waitFor(async () => expect(await orderNumbers(canvasElement)).toEqual(['SO-1003', 'SO-1001']));

    // On a phone the bar is one button (D26 Q38): the customer is read
    // beside it, the reader's own filters are in the sheet it opens.
    const sheet = async () => {
      await userEvent.click(within(bar).getByRole('button', {
        name: /^筛选/
      }));
      return screen.findByRole('dialog', {
        name: zhCN['label.filters.bar']
      });
    };
    // Closed again, the panels behind it are the page's once more.
    const closeSheet = async () => {
      await userEvent.keyboard('{Escape}');
      await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    };
    // The placing time is the reader's: last month holds none of theirs.
    await userEvent.click(within(await sheet()).getByRole('combobox', {
      name: label('label.date.period-of', {
        field: '下单时间'
      })
    }));
    await userEvent.click(await screen.findByRole('option', {
      name: zhCN['label.relative.preset.lastMonth']
    }));
    await closeSheet();
    const orders = await panelBody(canvasElement, '这个客户的订单');
    await waitFor(() => expect(orders).toHaveTextContent(zhCN['label.record.empty']));
    // The host's address follows the reader's filter — and never holds the
    // locked customer, which would come back from it as the reader's.
    const address = canvasElement.querySelector('[data-host-address]')!;
    await waitFor(() => expect(decodeURIComponent(address.textContent ?? '')).toContain('lastMonth'));
    await expect(decodeURIComponent(address.textContent ?? '')).not.toContain('c-03');
    // However long the address, the page does not scroll sideways.
    const area = canvasElement.querySelector<HTMLElement>('.story-app-page')!;
    await expect(area.scrollWidth).toBeLessThanOrEqual(area.clientWidth);
    await expect(document.documentElement.scrollWidth).toBeLessThanOrEqual(document.documentElement.clientWidth);

    // 「清空」 clears what the reader holds and leaves the customer: every
    // order of theirs, of any time.
    await userEvent.click(within(await sheet()).getByRole('button', {
      name: zhCN['label.filters.clear']
    }));
    await closeSheet();
    await waitFor(async () => expect(await orderNumbers(canvasElement)).toEqual(['SO-1003', 'SO-1001']));
    await expect(customer).toHaveTextContent('晨光食品');

    // A group's follow-up opens in the workbench, through the host's route,
    // with the customer among its conditions.
    const byWarehouse = await panelBody(canvasElement, '按仓库金额');
    const row = await within(byWarehouse).findByRole('row', {
      name: /华北/
    });
    await expect(row).toHaveAttribute('aria-haspopup', 'menu');
    await userEvent.click(row);
    await userEvent.click(await screen.findByRole('menuitem', {
      name: new RegExp(zhCN['label.drill.records'])
    }));
    const route = canvasElement.querySelector('[data-host-route]')!;
    await waitFor(() => expect(route).toHaveTextContent('c-03'));
    await expect(route).toHaveTextContent('CN-NORTH');

    // And the view behind a panel, under the board's filters in its names.
    await userEvent.click(canvas.getByRole('button', {
      name: label('label.panel.menu', {
        title: '这个客户的订单'
      })
    }));
    await userEvent.click(await screen.findByRole('menuitem', {
      name: zhCN['label.panel.open']
    }));
    await waitFor(() => expect(route).toHaveTextContent('customer-orders'));
    await expect(route).toHaveTextContent('c-03');
    // A long route wraps too.
    await expect(area.scrollWidth).toBeLessThanOrEqual(area.clientWidth);
  }
}`,...O.parameters?.docs?.source},description:{story:`The customer page, the interactive tier end to end (D22): the customer is
locked — read on the bar as who it is, with no control and no way to
clear it, beside the one button a phone's bar is (D26 Q38) — the placing
time is the reader's, set in the sheet that button opens, and moves the
panels, both
reach the host's address, and a group's follow-up and 在工作台中打开 go
through the host's route carrying the customer.`,...O.parameters?.docs?.description}}},k.parameters={...k.parameters,docs:{...k.parameters?.docs,source:{originalSource:`{
  ...DisplayCustomerDetail,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await findDataTable(await panelBody(canvasElement, '这个客户的订单'));
    const trigger = canvas.getByRole('button', {
      name: label('label.panel.menu', {
        title: '这个客户的订单'
      })
    });
    await userEvent.click(trigger);
    await userEvent.click(await screen.findByRole('menuitem', {
      name: zhCN['label.panel.export']
    }));
    const dialog = await screen.findByRole('dialog', {
      name: zhCN['label.export.title']
    });
    await expect(dialog.textContent).toContain('晨光食品');
    await expect(dialog.textContent).toMatch(/文件：这个客户的订单-\\d{4}-\\d{2}-\\d{2}\\.csv/);
    await userEvent.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    await waitFor(() => expect(trigger).toHaveFocus());
    await userEvent.click(canvas.getByRole('button', {
      name: label('label.panel.menu', {
        title: '按仓库金额'
      })
    }));
    await expect(within(await screen.findByRole('menu')).queryByRole('menuitem', {
      name: zhCN['label.panel.export']
    })).toBeNull();
    await userEvent.keyboard('{Escape}');
  }
}`,...k.parameters?.docs?.source},description:{story:`The customer page switched exports on (\`withExport\`): the order list's
「⋯」 offers 「导出数据…」, whose window is the workbench's — under the
customer the page locks and the time the reader picked, named after the
panel — and the keyboard is back on the 「⋯」 as it closes. The chart
panel has no export. Nothing is exported (test/embeddedDashboard.test.tsx
and test/dashboardPanelMenu.test.tsx hold the file).`,...k.parameters?.docs?.description}}},A.parameters={...A.parameters,docs:{...A.parameters?.docs,source:{originalSource:`{
  ...DisplayWallScreen,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await expect(await canvas.findByRole('heading', {
      level: 2,
      name: '出库概览'
    })).toBeVisible();
    const bar = await filterBar(canvasElement);
    await expect(within(bar).getByRole('group', {
      name: label('label.embed.locked-name', {
        filter: '仓库'
      })
    })).toHaveTextContent('华东');
    await expect(within(bar).queryByRole('button', {
      name: zhCN['label.filters.clear']
    })).toBeNull();

    // Only the east warehouse's pending order, and one bar.
    const pending = await panelBody(canvasElement, '待出库明细');
    const table = await findDataTable(pending);
    await waitFor(() => expect(readColumn(table, '订单号')).toEqual(['SO-1001']));
    const chart = await panelBody(canvasElement, '按仓库汇总');
    await waitFor(() => expect(drawnMarks(chart)).toHaveLength(1));

    // Read, and nothing else.
    await expect(canvasElement.querySelector('[data-slot="panel-menu"]')).toBeNull();
    await expect(canvasElement.querySelector('[data-pickable], [aria-haspopup="menu"]')).toBeNull();
    await expect(canvas.queryByRole('button', {
      name: zhCN['label.dashboard.edit']
    })).toBeNull();

    // It fills the wall it was given, to the pixel.
    const wall = canvasElement.querySelector<HTMLElement>('[data-wall]')!;
    const surface = wall.querySelector<HTMLElement>('.host-embed')!;
    await expect(surface).toHaveAttribute('data-embed-size', 'fill');
    await expect(Math.abs(surface.getBoundingClientRect().height - wall.getBoundingClientRect().height)).toBeLessThan(1);
  }
}`,...A.parameters?.docs?.source},description:{story:`The wall screen, the read-only tier end to end (D22): the board fills the
page it is on, titled, under the warehouse the page locks it to; nothing
on it answers a press, offers a menu, builds or clears.`,...A.parameters?.docs?.description}}},j.parameters={...j.parameters,docs:{...j.parameters?.docs,source:{originalSource:`{
  ...DisplayDashboardWithAPanelOut,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await findDataTable(await panelBody(canvasElement, '待出库明细'));
    await waitFor(() => expect(drawnMarks(canvasElement)).toHaveLength(4));
    await expect(canvas.getByRole('link', {
      name: /出库异常处理/
    })).toBeVisible();
    const out = await waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>('[data-slot="panel-unavailable"]');
      expect(found).not.toBeNull();
      return found!;
    });
    await expect(out).toHaveTextContent(zhCN['label.panel.out.missing']);
    await expect(out).toHaveTextContent(zhCN['label.panel.way-out.share']);
    await expect(canvasElement.querySelector('[data-slot="status-strip"][data-tone="error"]')).toBeNull();
  }
}`,...j.parameters?.docs?.source},description:{story:`One panel of an embedded board is out: the grid still draws, the other
panels run, and the one that is out says why in its own frame (R3).`,...j.parameters?.docs?.description}}},N.parameters={...N.parameters,docs:{...N.parameters?.docs,source:{originalSource:`{
  ...DisplayEditableBoard,
  decorators: [DESK],
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    const stored = canvasElement.querySelector('[data-host-stored]')!;
    await expect(await canvas.findByRole('heading', {
      level: 2,
      name: '班组看板'
    })).toBeVisible();
    await findDataTable(await panelBody(canvasElement, '待出库明细'));
    await expect(stored).toHaveTextContent('还没保存过');
    const edit = canvas.getByRole('button', {
      name: zhCN['label.dashboard.edit']
    });
    await userEvent.click(edit);
    await expect(await canvas.findByRole('region', {
      name: new RegExp(zhCN['label.dashboard.editing'])
    })).toBeVisible();

    // 「添加」 → 「文字…」: the window, the words and a title of its own.
    await userEvent.click(canvas.getByRole('button', {
      name: zhCN['label.dashboard.add']
    }));
    await userEvent.click(await screen.findByRole('menuitem', {
      name: zhCN['label.dashboard.add.markdown']
    }));
    const dialog = await screen.findByRole('dialog', {
      name: zhCN['label.content.markdown.add']
    });
    await userEvent.type(within(dialog).getByRole('textbox', {
      name: zhCN['label.content.markdown.field']
    }), '夜班交接前清点**华东仓**的待出库单。');
    await userEvent.type(within(dialog).getByRole('textbox', {
      name: zhCN['label.content.title']
    }), '交接说明');
    await userEvent.click(within(dialog).getByRole('button', {
      name: zhCN['label.content.submit-add']
    }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    await waitFor(() => expect(panelTitles(canvasElement)).toContain('交接说明'));
    const note = await panelBody(canvasElement, '交接说明');
    await expect(note).toHaveTextContent('夜班交接前清点华东仓的待出库单。');
    // On the board, not yet in the store.
    await expect(stored).toHaveTextContent('还没保存过');
    // Scrolled down the page past where it sat, the edit bar stays in view
    // (R3b): it sticks to the top of what scrolls the board, over the
    // panels, so the ways out never scroll away with the building.
    const scroller = canvasElement.querySelector<HTMLElement>('[data-host-scroller]')!;
    const bar = canvasElement.querySelector<HTMLElement>('[data-slot="dashboard-edit-bar"]')!;
    const sat = bar.getBoundingClientRect().top;
    note.scrollIntoView({
      block: 'end'
    });
    await waitFor(() => expect(scroller.scrollTop).toBeGreaterThan(sat));
    // At the top of the box, both ways out drawn over the panels beneath.
    await expect(bar.getBoundingClientRect().top).toBe(scroller.getBoundingClientRect().top);
    for (const way of ['label.dialog.cancel', 'label.dashboard.save'] as const) await expect(onTop(within(bar).getByRole('button', {
      name: zhCN[way]
    }))).toBe(true);
    scroller.scrollTop = 0;

    // 「保存」 over a shared board asks, as Save does, and saves.
    await userEvent.click(canvas.getByRole('button', {
      name: zhCN['label.dashboard.save']
    }));
    const confirm = await screen.findByRole('alertdialog');
    await userEvent.click(within(confirm).getByRole('button', {
      name: zhCN['label.save.shared-confirm']
    }));
    await waitFor(() => expect(stored).toHaveTextContent('修订 2 · 4 个面板'));
    await waitFor(() => expect(canvasElement.querySelector('[data-slot="dashboard-edit-bar"]')).toBeNull());
    await expect(canvas.getByRole('button', {
      name: zhCN['label.dashboard.edit']
    })).toHaveFocus();
    await expect(panelTitles(canvasElement)).toContain('交接说明');

    // 「取消」 asks, puts back the board as saved, and writes nothing.
    await userEvent.click(canvas.getByRole('button', {
      name: zhCN['label.dashboard.edit']
    }));
    await userEvent.click(await canvas.findByRole('button', {
      name: zhCN['label.dashboard.add']
    }));
    await userEvent.click(await screen.findByRole('menuitem', {
      name: zhCN['label.dashboard.add.heading']
    }));
    await userEvent.keyboard('{Enter}');
    await waitFor(() => expect(panelTitles(canvasElement)).toContain(zhCN['label.dashboard.new-heading']));
    await userEvent.click(canvas.getByRole('button', {
      name: zhCN['label.dialog.cancel']
    }));
    const question = await screen.findByRole('alertdialog');
    await userEvent.click(within(question).getByRole('button', {
      name: zhCN['label.save.revert']
    }));
    await waitFor(() => expect(panelTitles(canvasElement)).not.toContain(zhCN['label.dashboard.new-heading']));
    await expect(panelTitles(canvasElement)).toContain('交接说明');
    await expect(stored).toHaveTextContent('修订 2 · 4 个面板');
    await waitFor(() => expect(canvas.getByRole('button', {
      name: zhCN['label.dashboard.edit']
    })).toHaveFocus());
  }
}`,...N.parameters?.docs?.source},description:{story:`The team page, the editable tier end to end (D22 A): 「编辑」 builds the
board where it sits; a text added from 「添加」 is on the board at once
and in the store only once 「保存」 has asked and saved — the host's
footer reads the store, not the screen. 「取消」 then puts back what was
saved and writes nothing, and each time the keyboard is back on 「编辑」.`,...N.parameters?.docs?.description}}}})))()}F();export{O as CustomerDetail,k as CustomerOrdersExport,j as DashboardWithAPanelOut,N as EditableBoard,A as WallScreenReadOnly,P as __namedExportsOrder,E as default};
import{n as e}from"./rolldown-runtime-DkW27tQK.js";import{t}from"./jsx-runtime-DeHZSEgm.js";import{R as n,a as r,h as i,z as a}from"./styles-Dpj2y9Rj.js";import{C as o,S as s,c,t as l}from"./Dashboard.stories-Dhw1Xda_.js";async function u(e){return y(e).findByRole(`region`,{name:a[`label.filters.bar`]})}function d(e,t){return[...e.querySelectorAll(`[data-slot="panel-title"]`)].find(e=>e.textContent===t)?.closest(`[data-slot="dashboard-panel"]`)?.querySelector(`[data-slot="panel-not-reached"]`)?.textContent??null}async function f(e){await _.click(y(e).getByRole(`combobox`,{name:`仓库`})),await _.click(await g.findByRole(`option`,{name:`华南`}))}var p,m,h,g,_,v,y,b,x,S,C,w,T,E,D,O,k,A,j,M,N;function P(){return(P=e((()=>{n(),s(),i(),p=t(),{expect:m,fn:h,screen:g,userEvent:_,waitFor:v,within:y}=__STORYBOOK_MODULE_TEST__,b={...o,title:`View Engine/仪表盘视图/Dashboard/筛选`,tags:[`!dev`,`!autodocs`,`test`],parameters:{...o.parameters}},x=e=>(0,p.jsx)(`div`,{style:{width:1280},children:(0,p.jsx)(e,{})}),S=(e,t={})=>Object.entries(t).reduce((e,[t,n])=>e.replace(`{${t}}`,n),a[e]),C={...l,decorators:[x],play:async({canvasElement:e})=>{let t=y(e);await t.findByRole(`heading`,{level:3,name:`按仓库汇总`}),await _.click(t.getByRole(`button`,{name:a[`label.dashboard.edit`]})),await _.click(await t.findByRole(`button`,{name:a[`label.filters.add`]})),await _.click(await g.findByRole(`menuitem`,{name:a[`label.filters.type.date`]}));let n=await g.findByRole(`dialog`,{name:S(`label.filters.settings-of`,{filter:a[`label.filters.type.date`]})});await _.click(y(n).getByRole(`button`,{name:a[`label.filters.wire`]})),await t.findByRole(`region`,{name:S(`label.filters.wiring`,{filter:a[`label.filters.type.date`]})}),await v(()=>m(e.querySelectorAll(`[data-slot="panel-wiring"]`)).toHaveLength(2)),await _.click(t.getByRole(`combobox`,{name:S(`label.filters.wire-field-of`,{panel:`待出库明细`,filter:a[`label.filters.type.date`]})})),await _.click(await g.findByRole(`option`,{name:`创建时间`})),await m(await t.findByText(S(`label.filters.auto-wired-one`,{field:`创建时间`}))).toBeVisible();let r=()=>t.getByRole(`combobox`,{name:S(`label.filters.wire-field-of`,{panel:`按仓库汇总`,filter:a[`label.filters.type.date`]})});await v(()=>m(r()).toHaveTextContent(`创建时间`)),await m(e.querySelectorAll(`[data-slot="panel-wiring-manual"]`)).toHaveLength(1),await _.click(t.getByRole(`button`,{name:a[`label.filters.only-picked`]})),await v(()=>m(r()).toHaveTextContent(a[`label.filters.unwired`]))}},w={...c,decorators:[x],play:async({canvasElement:e})=>{let t=await u(e),n=y(t).getByRole(`group`,{name:`创建时间 ${a[`label.filters.required`]}`});await m(n).toHaveTextContent(`创建时间*`),await m(y(n).queryByRole(`button`,{name:S(`label.filters.back-to-default`,{filter:`创建时间`})})).toBeNull(),await f(t);let r=y(t).getByRole(`button`,{name:a[`label.filters.clear`]});await v(()=>m(r).toBeEnabled()),await _.click(r),await v(()=>m(r).toBeDisabled()),await m(y(t).getByRole(`combobox`,{name:`仓库`})).toHaveTextContent(a[`label.filter.not-set`]),await m(n.querySelector(`[data-slot="filter-value"]`)?.textContent).toContain(`2026`)}},T={...c,decorators:[x],play:async({canvasElement:e})=>{let t=await u(e),n=y(t).getByRole(`group`,{name:a[`label.filters.grouping`]});await y(e).findByRole(`heading`,{level:3,name:`每日订单`}),await v(()=>m(y(n).getByRole(`button`,{name:`按日`})).toHaveAttribute(`aria-pressed`,`true`));let i=r.current;await _.click(y(n).getByRole(`button`,{name:`按月`})),await v(()=>m(r.current).toBeGreaterThan(i)),await m(y(n).getByRole(`button`,{name:`按月`})).toHaveAttribute(`aria-pressed`,`true`)}},E={...c,decorators:[x],play:async({canvasElement:e})=>{let t=await u(e);await y(e).findByRole(`heading`,{level:3,name:`每日订单`}),await m(d(e,`每日订单`)).toBeNull(),await f(t),await v(()=>m(d(e,`每日订单`)).toBe(S(`label.filters.not-reached`,{filters:S(`label.filters.name-quoted`,{name:`仓库`})}))),await m(d(e,`按仓库汇总`)).toBeNull()}},D={...c,decorators:[x],play:async({canvasElement:e})=>{let t=await u(e),n=y(t).getByRole(`group`,{name:`状态`}),r=await y(n).findByRole(`combobox`,{name:`状态`});await m(y(n).queryByRole(`textbox`)).toBeNull(),await _.click(r),await m(await g.findByRole(`option`,{name:`已发运`})).toBeVisible(),await _.click(await g.findByRole(`option`,{name:`待出库`})),await v(()=>m(r).toHaveTextContent(`待出库`));let[i]=await y(e).findAllByRole(`table`);await v(()=>m(y(i).getAllByRole(`row`).length).toBeGreaterThan(1))}},O={...c,decorators:[x],play:async({canvasElement:e})=>{let t=await u(e),n=y(t).getByRole(`group`,{name:`订单号`});await _.click(y(n).getByRole(`combobox`,{name:`订单号`})),await v(()=>m(document.querySelectorAll(`[data-slot="candidate-count"]`).length).toBeGreaterThan(0)),await m(await g.findByRole(`option`,{name:`SO-1001（1 条记录）`})).toBeInTheDocument(),await _.keyboard(`{Escape}`),await v(()=>m(g.queryByRole(`listbox`)).toBeNull())}},k={...c,decorators:[x],play:async({canvasElement:e})=>{let t=y(e),n=await u(e),r=()=>[...n.querySelectorAll(`[data-filter]`)].map(e=>e.dataset.filter);await m(r()).toEqual([`created`,`region`,`phase`,`order`]),await _.click(t.getByRole(`button`,{name:a[`label.dashboard.edit`]}));let i=await y(n).findByRole(`button`,{name:S(`label.filters.reorder`,{filter:`订单号`})});i.focus(),await _.keyboard(`{ArrowLeft}`),await v(()=>m(r()).toEqual([`created`,`region`,`order`,`phase`])),await v(()=>m(i).toHaveFocus()),await _.keyboard(`{ArrowLeft}`),await v(()=>m(r()).toEqual([`created`,`order`,`region`,`phase`])),await m(e.querySelector(`[data-slot="dashboard-announcement"]`)).toHaveTextContent(`「订单号」现在是第 2 个筛选，共 4 个`),await v(()=>m(i).toHaveFocus()),await _.keyboard(`{ArrowRight}`),await v(()=>m(r()).toEqual([`created`,`region`,`order`,`phase`])),await v(()=>m(i).toHaveFocus()),await m(e.querySelector(`[data-slot="dashboard-announcement"]`)).toHaveTextContent(`「订单号」现在是第 3 个筛选，共 4 个`);let o=y(n).getByRole(`group`,{name:a[`label.filters.grouping`]}),s=n.querySelector(`[data-filter="phase"]`);await m((s?.compareDocumentPosition(o)??0)&Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()}},A={...c,decorators:[x],args:{...c.args,onFiltersChange:h()},play:async({canvasElement:e,args:t})=>{let n=t.onFiltersChange;await v(()=>m(n).toHaveBeenCalledWith({values:{created:{type:`absolute`,from:`2026-09-01`,to:`2026-09-30`}},unit:`DAY`})),await f(await u(e)),await v(()=>m(n).toHaveBeenLastCalledWith(m.objectContaining({values:m.objectContaining({region:[`CN-SOUTH`]})})))}},j=e=>(0,p.jsx)(`div`,{style:{width:390},children:(0,p.jsx)(e,{})}),M={...c,decorators:[j],play:async({canvasElement:e})=>{let t=await u(e);await v(()=>m(t).toHaveAttribute(`data-narrow`)),await m(y(t).queryByRole(`combobox`)).toBeNull();let n=y(t).getByRole(`button`,{name:S(`label.filters.sheet-set`,{count:`1`})});await _.click(n);let r=await g.findByRole(`dialog`,{name:a[`label.filters.bar`]});await m([...r.querySelectorAll(`[data-slot="dashboard-filter"]`)].map(e=>e.dataset.filter)).toEqual([`created`,`region`,`phase`,`order`]),await v(()=>m(y(r).getByRole(`group`,{name:a[`label.filters.grouping`]})).toBeVisible()),await v(()=>{let e=r.getBoundingClientRect();m(Math.round(e.bottom)).toBe(window.innerHeight),m(e.top).toBeGreaterThan(0)}),await f(r),await v(()=>m(n).toHaveTextContent(S(`label.filters.sheet-set`,{count:`2`}))),await m(y(r).getByRole(`combobox`,{name:`仓库`})).toHaveTextContent(`华南`)}},N=[`AddTimeFilterAutoConnects`,`RequiredNeverEmpty`,`TimeGroupingSwitches`,`UnwiredPanelSaysSo`,`CategoryPicksFromLabels`,`TextOffersCountedValues`,`FiltersReordered`,`ValuesReachTheHost`,`FiltersInASheetOnAPhone`],C.parameters={...C.parameters,docs:{...C.parameters?.docs,source:{originalSource:`{
  ...DisplayAllPanels,
  decorators: [DESK],
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('heading', {
      level: 3,
      name: '按仓库汇总'
    });
    await userEvent.click(canvas.getByRole('button', {
      name: zhCN['label.dashboard.edit']
    }));
    await userEvent.click(await canvas.findByRole('button', {
      name: zhCN['label.filters.add']
    }));
    await userEvent.click(await screen.findByRole('menuitem', {
      name: zhCN['label.filters.type.date']
    }));
    const settings = await screen.findByRole('dialog', {
      name: label('label.filters.settings-of', {
        filter: zhCN['label.filters.type.date']
      })
    });
    await userEvent.click(within(settings).getByRole('button', {
      name: zhCN['label.filters.wire']
    }));
    await canvas.findByRole('region', {
      name: label('label.filters.wiring', {
        filter: zhCN['label.filters.type.date']
      })
    });
    await waitFor(() => expect(canvasElement.querySelectorAll('[data-slot="panel-wiring"]')).toHaveLength(2));
    await userEvent.click(canvas.getByRole('combobox', {
      name: label('label.filters.wire-field-of', {
        panel: '待出库明细',
        filter: zhCN['label.filters.type.date']
      })
    }));
    await userEvent.click(await screen.findByRole('option', {
      name: '创建时间'
    }));
    await expect(await canvas.findByText(label('label.filters.auto-wired-one', {
      field: '创建时间'
    }))).toBeVisible();
    // The other panel follows on its own; the one chosen by hand is 「手动」.
    const summary = () => canvas.getByRole('combobox', {
      name: label('label.filters.wire-field-of', {
        panel: '按仓库汇总',
        filter: zhCN['label.filters.type.date']
      })
    });
    await waitFor(() => expect(summary()).toHaveTextContent('创建时间'));
    await expect(canvasElement.querySelectorAll('[data-slot="panel-wiring-manual"]')).toHaveLength(1);
    await userEvent.click(canvas.getByRole('button', {
      name: zhCN['label.filters.only-picked']
    }));
    await waitFor(() => expect(summary()).toHaveTextContent(zhCN['label.filters.unwired']));
  }
}`,...C.parameters?.docs?.source},description:{story:`Screen G: 「添加筛选」 → 日期 opens its settings; 「接线」 puts a strip on
every panel; picking 创建时间 on one wires the other panel with that field
on its own, and the toast says so with 「只接刚选的面板」 — which unwires it again.`,...C.parameters?.docs?.description}}},w.parameters={...w.parameters,docs:{...w.parameters?.docs,source:{originalSource:`{
  ...DisplayFilters,
  decorators: [DESK],
  play: async ({
    canvasElement
  }) => {
    const bar = await filterBar(canvasElement);
    const created = within(bar).getByRole('group', {
      name: \`创建时间 \${zhCN['label.filters.required']}\`
    });
    await expect(created).toHaveTextContent('创建时间*');
    await expect(within(created).queryByRole('button', {
      name: label('label.filters.back-to-default', {
        filter: '创建时间'
      })
    })).toBeNull();
    await pickSouth(bar);
    const clear = within(bar).getByRole('button', {
      name: zhCN['label.filters.clear']
    });
    await waitFor(() => expect(clear).toBeEnabled());
    await userEvent.click(clear);
    await waitFor(() => expect(clear).toBeDisabled());
    await expect(within(bar).getByRole('combobox', {
      name: '仓库'
    })).toHaveTextContent(zhCN['label.filter.not-set']);
    // Still a window: the required filter went back to its default.
    await expect(created.querySelector('[data-slot="filter-value"]')?.textContent).toContain('2026');
  }
}`,...w.parameters?.docs?.source},description:{story:`Screen F: 创建时间 is required — starred, and never empty: at its default
it offers no way back, and 「清空」 clears 仓库 and leaves it holding.`,...w.parameters?.docs?.description}}},T.parameters={...T.parameters,docs:{...T.parameters?.docs,source:{originalSource:`{
  ...DisplayFilters,
  decorators: [DESK],
  play: async ({
    canvasElement
  }) => {
    const bar = await filterBar(canvasElement);
    const grouping = within(bar).getByRole('group', {
      name: zhCN['label.filters.grouping']
    });
    await within(canvasElement).findByRole('heading', {
      level: 3,
      name: '每日订单'
    });
    await waitFor(() => expect(within(grouping).getByRole('button', {
      name: '按日'
    })).toHaveAttribute('aria-pressed', 'true'));
    const asked = aggregateCalls.current;
    await userEvent.click(within(grouping).getByRole('button', {
      name: '按月'
    }));
    await waitFor(() => expect(aggregateCalls.current).toBeGreaterThan(asked));
    await expect(within(grouping).getByRole('button', {
      name: '按月'
    })).toHaveAttribute('aria-pressed', 'true');
  }
}`,...T.parameters?.docs?.source},description:{story:`Screen F: 按日｜按月 regroups every panel whose time dimension can take
it — the trend asks again, and the bar says which is in force.`,...T.parameters?.docs?.description}}},E.parameters={...E.parameters,docs:{...E.parameters?.docs,source:{originalSource:`{
  ...DisplayFilters,
  decorators: [DESK],
  play: async ({
    canvasElement
  }) => {
    const bar = await filterBar(canvasElement);
    await within(canvasElement).findByRole('heading', {
      level: 3,
      name: '每日订单'
    });
    await expect(badgeOn(canvasElement, '每日订单')).toBeNull();
    await pickSouth(bar);
    await waitFor(() => expect(badgeOn(canvasElement, '每日订单')).toBe(label('label.filters.not-reached', {
      filters: label('label.filters.name-quoted', {
        name: '仓库'
      })
    })));
    await expect(badgeOn(canvasElement, '按仓库汇总')).toBeNull();
  }
}`,...E.parameters?.docs?.source},description:{story:`Screen F: a panel a filter does not reach says so once the filter holds a
value — the trend is not wired to 仓库.`,...E.parameters?.docs?.description}}},D.parameters={...D.parameters,docs:{...D.parameters?.docs,source:{originalSource:`{
  ...DisplayFilters,
  decorators: [DESK],
  play: async ({
    canvasElement
  }) => {
    const bar = await filterBar(canvasElement);
    const chip = within(bar).getByRole('group', {
      name: '状态'
    });
    const select = await within(chip).findByRole('combobox', {
      name: '状态'
    });
    await expect(within(chip).queryByRole('textbox')).toBeNull();
    await userEvent.click(select);
    await expect(await screen.findByRole('option', {
      name: '已发运'
    })).toBeVisible();
    await userEvent.click(await screen.findByRole('option', {
      name: '待出库'
    }));
    await waitFor(() => expect(select).toHaveTextContent('待出库'));
    // Still the pending orders on the list: the code went out, not the label.
    const [table] = await within(canvasElement).findAllByRole('table');
    await waitFor(() => expect(within(table).getAllByRole('row').length).toBeGreaterThan(1));
  }
}`,...D.parameters?.docs?.source},description:{story:`Screen G, a category: 状态 is a text filter wired to the orders' status,
an enum — so it picks from the enum's labels and holds its codes, rather
than asking the reader to type 「PENDING」.`,...D.parameters?.docs?.description}}},O.parameters={...O.parameters,docs:{...O.parameters?.docs,source:{originalSource:`{
  ...DisplayFilters,
  decorators: [DESK],
  play: async ({
    canvasElement
  }) => {
    const bar = await filterBar(canvasElement);
    const chip = within(bar).getByRole('group', {
      name: '订单号'
    });
    await userEvent.click(within(chip).getByRole('combobox', {
      name: '订单号'
    }));
    await waitFor(() => expect(document.querySelectorAll('[data-slot="candidate-count"]').length).toBeGreaterThan(0));
    // Each with its count, as a text condition's values are listed.
    await expect(await screen.findByRole('option', {
      name: 'SO-1001（1 条记录）'
    })).toBeInTheDocument();
    // Closed again, so the page is read as a whole once more.
    await userEvent.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('listbox')).toBeNull());
  }
}`,...O.parameters?.docs?.source},description:{story:`Screen G, values from the data: 订单号 is wired to a plain text field
the data can be counted by, so it offers the order numbers there are,
each with how many records hold it.`,...O.parameters?.docs?.description}}},k.parameters={...k.parameters,docs:{...k.parameters?.docs,source:{originalSource:`{
  ...DisplayFilters,
  decorators: [DESK],
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    const bar = await filterBar(canvasElement);
    const order = () => [...bar.querySelectorAll<HTMLElement>('[data-filter]')].map(chip => chip.dataset.filter);
    await expect(order()).toEqual(['created', 'region', 'phase', 'order']);
    await userEvent.click(canvas.getByRole('button', {
      name: zhCN['label.dashboard.edit']
    }));
    const handle = await within(bar).findByRole('button', {
      name: label('label.filters.reorder', {
        filter: '订单号'
      })
    });
    handle.focus();
    await userEvent.keyboard('{ArrowLeft}');
    await waitFor(() => expect(order()).toEqual(['created', 'region', 'order', 'phase']));
    // Put back in the DOM at its place, the chip keeps the keyboard.
    await waitFor(() => expect(handle).toHaveFocus());
    await userEvent.keyboard('{ArrowLeft}');
    await waitFor(() => expect(order()).toEqual(['created', 'order', 'region', 'phase']));
    await expect(canvasElement.querySelector('[data-slot="dashboard-announcement"]')).toHaveTextContent('「订单号」现在是第 2 个筛选，共 4 个');
    // And back a place: going right is the chip itself put back in the DOM.
    await waitFor(() => expect(handle).toHaveFocus());
    await userEvent.keyboard('{ArrowRight}');
    await waitFor(() => expect(order()).toEqual(['created', 'region', 'order', 'phase']));
    await waitFor(() => expect(handle).toHaveFocus());
    await expect(canvasElement.querySelector('[data-slot="dashboard-announcement"]')).toHaveTextContent('「订单号」现在是第 3 个筛选，共 4 个');
    // The time grouping is not in this order: it stays after the filters.
    const grouping = within(bar).getByRole('group', {
      name: zhCN['label.filters.grouping']
    });
    const last = bar.querySelector('[data-filter="phase"]');
    await expect((last?.compareDocumentPosition(grouping) ?? 0) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  }
}`,...k.parameters?.docs?.source},description:{story:`Screen G, the order: while the board is built each chip wears a handle,
and ← on 订单号's moves it a place along the bar — twice, past 状态 and
仓库, then → once back — the keyboard staying on the handle, each landing
said, and the time grouping still after the filters.`,...k.parameters?.docs?.description}}},A.parameters={...A.parameters,docs:{...A.parameters?.docs,source:{originalSource:`{
  ...DisplayFilters,
  decorators: [DESK],
  args: {
    ...DisplayFilters.args,
    onFiltersChange: fn()
  },
  play: async ({
    canvasElement,
    args
  }) => {
    const told = args.onFiltersChange as ReturnType<typeof fn>;
    await waitFor(() => expect(told).toHaveBeenCalledWith({
      values: {
        created: {
          type: 'absolute',
          from: '2026-09-01',
          to: '2026-09-30'
        }
      },
      unit: 'DAY'
    }));
    await pickSouth(await filterBar(canvasElement));
    await waitFor(() => expect(told).toHaveBeenLastCalledWith(expect.objectContaining({
      values: expect.objectContaining({
        region: ['CN-SOUTH']
      })
    })));
  }
}`,...A.parameters?.docs?.source},description:{story:"What the filters hold is the host's to keep in its address\n(`onFiltersChange`): told as the board opens, and again on every pick.",...A.parameters?.docs?.description}}},M.parameters={...M.parameters,docs:{...M.parameters?.docs,source:{originalSource:`{
  ...DisplayFilters,
  decorators: [PHONE],
  play: async ({
    canvasElement
  }) => {
    const bar = await filterBar(canvasElement);
    await waitFor(() => expect(bar).toHaveAttribute('data-narrow'));
    // Nothing is changed on the bar itself: its controls are in the sheet.
    await expect(within(bar).queryByRole('combobox')).toBeNull();
    const open = within(bar).getByRole('button', {
      name: label('label.filters.sheet-set', {
        count: '1'
      })
    });
    await userEvent.click(open);
    const sheet = await screen.findByRole('dialog', {
      name: zhCN['label.filters.bar']
    });
    // Every filter the bar holds, and the time grouping after them.
    await expect([...sheet.querySelectorAll<HTMLElement>('[data-slot="dashboard-filter"]')].map(chip => chip.dataset.filter)).toEqual(['created', 'region', 'phase', 'order']);
    // Once it has slid in: it starts transparent.
    await waitFor(() => expect(within(sheet).getByRole('group', {
      name: zhCN['label.filters.grouping']
    })).toBeVisible());
    // The sheet stands on the bottom edge, the board's top still in view.
    await waitFor(() => {
      const box = sheet.getBoundingClientRect();
      expect(Math.round(box.bottom)).toBe(window.innerHeight);
      expect(box.top).toBeGreaterThan(0);
    });
    await pickSouth(sheet);
    await waitFor(() => expect(open).toHaveTextContent(label('label.filters.sheet-set', {
      count: '2'
    })));
    await expect(within(sheet).getByRole('combobox', {
      name: '仓库'
    })).toHaveTextContent('华南');
  }
}`,...M.parameters?.docs?.source},description:{story:`On a phone (D26 Q38): the bar is one button, 「筛选（已设 1 个）」 —
创建时间 is required and holds its default — and pressing it opens every
filter in a sheet from the bottom edge. 华南 picked there runs the panels
behind it and the count follows; the sheet is left open, so axe judges it.`,...M.parameters?.docs?.description}}}})))()}P();export{C as AddTimeFilterAutoConnects,D as CategoryPicksFromLabels,M as FiltersInASheetOnAPhone,k as FiltersReordered,w as RequiredNeverEmpty,O as TextOffersCountedValues,T as TimeGroupingSwitches,E as UnwiredPanelSaysSo,A as ValuesReachTheHost,N as __namedExportsOrder,b as default};
import{n as e}from"./rolldown-runtime-DkW27tQK.js";import{t}from"./jsx-runtime-DeHZSEgm.js";import{R as n,z as r}from"./styles-Dpj2y9Rj.js";import{a as i,c as a,i as o,l as s,o as c,p as l,s as u}from"./chartDom-C6NvYOT8.js";import{n as d,o as f}from"./contrast-BGE4rlQ_.js";import{a as p,i as m}from"./readTable-DOunjEkP.js";import{C as h,S as g,b as _,d as v,i as y,r as b,x,y as S}from"./Dashboard.stories-Dhw1Xda_.js";async function C(e,t){let n=(await M(e).findByRole(`heading`,{level:3,name:t})).closest(`[data-slot="dashboard-panel"]`);if(!n)throw Error(`no panel ${t}`);return n}async function w(e){let t=await C(e,`按仓库汇总`);return await i(t),j(()=>{let e=c(t);return D(e.length).toBeGreaterThan(1),e},{timeout:4e3})}async function T(e){let t=M(e);await t.findByRole(`button`,{name:/回到出库概览/});let n=await t.findByRole(`region`,{name:r[`label.filters.bar`]}),i=M(n).getByRole(`group`,{name:`仓库`}),a=M(i).getByRole(`combobox`,{name:`仓库`});await j(()=>D(H(a)).toBeTruthy());let o=H(a)??``;await D(M(n).queryByText(F(`label.click.from`,{panel:`按仓库汇总`}))).toBeNull();let s=await C(e,`待出库明细`);await j(()=>{let e=s.querySelector(`table`);D(e).not.toBeNull();let t=p(e,`仓库`);D(t.length).toBeGreaterThan(0),D(t.every(e=>e===o)).toBe(!0)})}var E,D,O,k,A,j,M,N,P,F,I,L,R,z,B,V,H,U,W,G,K,q,J;function Y(){return(Y=e((()=>{n(),g(),s(),d(),m(),E=t(),{expect:D,fn:O,screen:k,userEvent:A,waitFor:j,within:M}=__STORYBOOK_MODULE_TEST__,N={...h,title:`View Engine/仪表盘视图/Dashboard/点击`,tags:[`!dev`,`!autodocs`,`test`],parameters:{...h.parameters}},P=e=>(0,E.jsx)(`div`,{style:{width:1280},children:(0,E.jsx)(e,{})}),F=(e,t={})=>Object.entries(t).reduce((e,[t,n])=>e.replace(`{${t}}`,n),r[e]),I=1.8,L={...b,decorators:[P],args:{...b.args,onNavigate:O()},play:async({canvasElement:e,args:t})=>{let[n]=await w(e);l(n);let i=await k.findByRole(`menu`);await D(i.querySelector(`[data-slot="drill-group"]`)?.textContent).toMatch(/^仓库 是 /),await D(i.querySelectorAll(`[data-slot="drill-away"]`).length).toBeGreaterThan(0),await A.click(M(i).getByRole(`menuitem`,{name:new RegExp(r[`label.drill.records`])})),await j(()=>D(t.onNavigate).toHaveBeenCalledTimes(1)),await D(t.onNavigate).toHaveBeenCalledWith(D.objectContaining({kind:`unsaved`,definitionId:`orders`})),await M(e).findByRole(`heading`,{level:2,name:/^订单 · 仓库 是 /});let a=await j(()=>{let t=e.querySelector(`[data-slot="editor-toggle"]`);return D(t).not.toBeNull(),t});await D(a.querySelector(`[aria-expanded="true"]`)).toBeNull(),await D(e.querySelector(`[data-slot="editor-band"]`)).toBeNull(),await A.click(M(e.querySelector(`[data-slot="origin-bar"]`)).getByRole(`button`,{name:F(`label.origin.back`,{title:`出库概览`})})),await C(e,`按仓库汇总`)}},R={...b,decorators:[P],play:async({canvasElement:e})=>{let[t]=await w(e),n=await C(e,`按仓库汇总`);a(t),await j(()=>D(o(n)).toBeVisible()),l(t);let r=await k.findByRole(`menu`);await j(()=>D(o(n)).not.toBeVisible()),a(t),await new Promise(e=>setTimeout(e,300)),await D(o(n)).not.toBeVisible(),await D(M(r).getAllByRole(`menuitem`)[0]).toBeVisible(),await A.keyboard(`{Escape}`),await j(()=>D(k.queryByRole(`menu`)).toBeNull()),a(t),await j(()=>D(o(n)).toBeVisible())}},z={...v,decorators:[P],args:{...v.args,onNavigate:O()},play:async({canvasElement:e,args:t})=>{let n=M(e);await C(e,`待出库明细`);let i=await n.findByRole(`region`,{name:r[`label.filters.bar`]});await A.click(M(i).getByRole(`combobox`,{name:`仓库`})),await A.click(await k.findByRole(`option`,{name:`华南`}));let a=await C(e,`待出库明细`);await j(()=>{let e=p(a.querySelector(`table`),`仓库`);D(e.length).toBeGreaterThan(0),D(e.every(e=>e===`华南`)).toBe(!0)}),await A.click(M(a).getByRole(`button`,{name:F(`label.panel.menu`,{title:`待出库明细`})})),await A.click(await k.findByRole(`menuitem`,{name:r[`label.panel.open`]})),await D(t.onNavigate).toHaveBeenLastCalledWith(D.objectContaining({kind:`view`,definitionId:`orders`,instanceId:`orders-pending`,scopeFilter:{op:`and`,children:[D.objectContaining({field:`status`,value:[`PENDING`,`SHIPPED`]})]},filter:{op:`and`,children:[D.objectContaining({field:`warehouse`,value:[`CN-SOUTH`]})]}})),await n.findByRole(`heading`,{level:2,name:`待出库订单`}),await j(()=>D(e.querySelector(`[data-slot="view-unsaved"]`)).not.toBeNull());let o=await n.findByRole(`region`,{name:r[`label.applied.title`]});await M(o).findByRole(`button`,{name:/^清空 仓库 是 华南/});let s=[...o.querySelectorAll(`[data-scoped]`)];await D(s).toHaveLength(1),await D(s[0].textContent).toMatch(/^状态 .*已发运/),await D(s[0].querySelector(`button`)).toBeNull();let c=await n.findByRole(`region`,{name:r[`label.origin.board-region`]});await A.click(M(c).getByRole(`button`,{name:F(`label.origin.back`,{title:`出库概览`})})),await D(k.queryByRole(`alertdialog`)).toBeNull(),await C(e,`待出库明细`);let l=await n.findByRole(`region`,{name:r[`label.filters.bar`]}),u=M(l).getByRole(`combobox`,{name:`仓库`});await j(()=>D(H(u)).toBe(`华南`));let d=await C(e,`待出库明细`);await A.click(M(d).getByRole(`button`,{name:F(`label.panel.menu`,{title:`待出库明细`})})),await A.click(await k.findByRole(`menuitem`,{name:r[`label.panel.open`]})),await n.findByRole(`heading`,{level:2,name:`待出库订单`});let f=await n.findByRole(`region`,{name:r[`label.applied.title`]});await A.click(await M(f).findByRole(`button`,{name:/^清空 仓库 是 华南/})),await j(()=>D(e.querySelector(`[data-slot="view-unsaved"]`)).toBeNull()),await D(M(f).queryByRole(`button`,{name:/^清空 仓库/})).toBeNull(),await D(f.querySelectorAll(`[data-scoped]`)).toHaveLength(1)}},B={...y,decorators:[P],play:async({canvasElement:e})=>{let t=await C(e,`按仓库汇总`);await D(M(t).getByText(F(`label.click.badge`,{filter:`仓库`}))).toBeVisible();let n=await w(e),i=n.length;l(n[0]);let a=await M(e).findByRole(`region`,{name:r[`label.filters.bar`]}),o=await M(a).findByText(F(`label.click.from`,{panel:`按仓库汇总`}));await D(o).toBeVisible();let s=M(a).getByRole(`group`,{name:`仓库`}),d=M(s).getByRole(`combobox`,{name:`仓库`}),m=()=>d.querySelector(`[data-slot="select-value"]`)?.textContent?.trim();await j(()=>D(m()).toBeTruthy());let h=m()??``,g=await C(e,`待出库明细`);await j(()=>{let e=g.querySelector(`table`);D(e).not.toBeNull();let t=p(e,`仓库`);D(t.length).toBeGreaterThan(0),D(t.every(e=>e===h)).toBe(!0)});let _=t.querySelector(`[data-slot="chart"]`);await j(()=>D(_?.dataset.highlighted).toBe(`1`)),await D(Number(_?.dataset.marks)).toBe(i),await j(()=>D(c(t)).toHaveLength(1));let v=f(c(t)[0]),y=u(t).map(f);await D(y).toHaveLength(i-1);for(let e of y)await D(e.ratio,JSON.stringify(e)).toBeGreaterThan(I),await D(v.ratio/e.ratio).toBeGreaterThan(2);l(c(t)[0]),await j(()=>D(M(a).queryByText(F(`label.click.from`,{panel:`按仓库汇总`}))).toBeNull()),await j(()=>D(_?.dataset.highlighted).toBe(`0`)),await j(()=>D(c(t).length).toBe(i))}},V={...b,decorators:[P],play:async({canvasElement:e})=>{let t=M(e);await C(e,`按仓库汇总`),await A.click(t.getByRole(`button`,{name:r[`label.dashboard.edit`]})),await A.click(await t.findByRole(`button`,{name:F(`label.panel.menu`,{title:`按仓库汇总`})})),await A.click(await k.findByRole(`menuitem`,{name:r[`label.click.menu-item`]}));let n=await k.findByRole(`dialog`,{name:F(`label.click.title`,{panel:`按仓库汇总`})});await A.click(M(n).getByRole(`radio`,{name:r[`label.click.filter`]})),await A.click(M(n).getByRole(`button`,{name:r[`label.click.save`]}));let i=await C(e,`按仓库汇总`);await D(await M(i).findByText(F(`label.click.badge`,{filter:`仓库`}))).toBeVisible(),await j(()=>D(document.activeElement).toBe(M(i).getByRole(`button`,{name:F(`label.panel.menu`,{title:`按仓库汇总`})})))}},H=e=>e.querySelector(`[data-slot="select-value"]`)?.textContent?.trim(),U={...S,decorators:[P],args:{...S.args,onNavigate:O()},play:async({canvasElement:e,args:t})=>{let[n]=await w(e);l(n),await j(()=>D(t.onNavigate).toHaveBeenCalledTimes(1)),await D(t.onNavigate).toHaveBeenCalledWith(D.objectContaining({kind:`dashboard`,instanceId:`overview-regional`,filters:{values:{region:[D.any(String)]}}})),await D(k.queryByRole(`menu`)).toBeNull(),await T(e)}},W={..._,decorators:[P],args:{..._.args,onNavigate:O()},play:async({canvasElement:e,args:t})=>{let[n]=await w(e);l(n);let r=await k.findByRole(`menu`);await D(r.querySelector(`[data-slot="drill-group"]`)?.textContent).toMatch(/^仓库 是 /),await D(t.onNavigate).not.toHaveBeenCalled(),await A.keyboard(`{Escape}`);let i=await C(e,`按仓库汇总`),a=await j(()=>{let e=i.querySelector(`[data-slot="panel-warning"]`);return D(e).not.toBeNull(),e});await D(a.getAttribute(`aria-label`)??``).toContain(F(`dashboard.click.board-filter-unknown`,{filter:`zone`}))}},G={...b,decorators:[P],args:{...b.args,onNavigate:O()},play:async({canvasElement:e,args:t})=>{let n=M(e);await C(e,`按仓库汇总`),await A.click(n.getByRole(`button`,{name:r[`label.dashboard.edit`]})),await A.click(await n.findByRole(`button`,{name:F(`label.panel.menu`,{title:`按仓库汇总`})})),await A.click(await k.findByRole(`menuitem`,{name:r[`label.click.menu-item`]}));let i=await k.findByRole(`dialog`,{name:F(`label.click.title`,{panel:`按仓库汇总`})});await A.click(M(i).getByRole(`radio`,{name:r[`label.click.go`]})),await A.click(M(i).getByRole(`button`,{name:r[`label.click.go-board`]})),await A.click(M(i).getByRole(`button`,{name:r[`label.click.board-pick`]}));let a=await k.findByRole(`dialog`,{name:F(`label.click.board-heading`,{panel:`按仓库汇总`})});await A.click(await M(a).findByText(`区域明细`));let o=await M(i).findByRole(`group`,{name:r[`label.click.board-values`]}),s=M(o).getByRole(`combobox`,{name:`仓库`});await D(H(s)).toBe(r[`label.click.board-skip`]);let c=M(o).getByRole(`combobox`,{name:`下单时间`});await D(c).toHaveAttribute(`data-disabled`),await D(M(o).getByText(r[`label.click.board-no-source`])).toBeVisible(),s.focus(),await A.keyboard(`{Enter}`);let u=await k.findByRole(`option`,{name:F(`label.click.board-value`,{dimension:`仓库`})});await A.click(u),await j(()=>D(H(s)).toBe(F(`label.click.board-value`,{dimension:`仓库`}))),await A.click(M(i).getByRole(`button`,{name:r[`label.click.save`]})),await j(()=>D(k.queryByRole(`dialog`)).toBeNull()),await A.click(n.getByRole(`button`,{name:r[`label.dashboard.save`]})),await j(()=>D(e.querySelector(`[data-slot="dashboard-edit-bar"]`)).toBeNull());let[d]=await w(e);l(d),await j(()=>D(t.onNavigate).toHaveBeenCalledWith(D.objectContaining({kind:`dashboard`,instanceId:`overview-regional`}))),await T(e)}},K={...S,decorators:[P],play:async({canvasElement:e})=>{let t=M(e);await C(e,`按仓库汇总`),await A.click(t.getByRole(`button`,{name:r[`label.dashboard.edit`]})),await A.click(await t.findByRole(`button`,{name:F(`label.panel.menu`,{title:`按仓库汇总`})})),await A.click(await k.findByRole(`menuitem`,{name:r[`label.click.menu-item`]}));let n=await k.findByRole(`dialog`,{name:F(`label.click.title`,{panel:`按仓库汇总`})});await D(M(n).getByRole(`button`,{name:r[`label.click.go-board`]})).toHaveAttribute(`aria-pressed`,`true`),await M(n).findByText(`区域明细`);let i=await M(n).findByRole(`group`,{name:r[`label.click.board-values`]}),a=M(i).getByRole(`combobox`,{name:`仓库`});await D(H(a)).toBe(F(`label.click.board-value`,{dimension:`仓库`})),M(n).getByRole(`button`,{name:r[`label.click.board-change`]}).focus(),await A.tab(),await D(a).toHaveFocus()}},q={...x,decorators:[P],args:{...x.args,onNavigate:O()},play:async({canvasElement:e,args:t})=>{let[n]=await w(e);l(n),await j(()=>D(t.onNavigate).toHaveBeenCalledTimes(1)),await D(t.onNavigate).toHaveBeenCalledWith(D.objectContaining({kind:`dashboard`,instanceId:`overview-regional`,filters:{values:{region:[D.any(String)],placed:{type:`absolute`,from:`2026-09-01`,to:`2026-09-30`}}}})),await T(e);let i=await M(e).findByRole(`region`,{name:r[`label.filters.bar`]});await D(M(i).getByRole(`button`,{name:F(`label.filters.clear-one`,{filter:`下单时间`})})).toBeVisible()}},J=[`BarOpensFollowUps`,`MenuClearOfTheTooltip`,`OpenInWorkbenchKeepsBoardFilters`,`CrossFilterFromABar`,`SetsCrossFilterWhenClicked`,`PressOpensAnotherBoard`,`StaleMappingFallsBack`,`SetsAnotherBoardWhenClicked`,`MappingRowsReadBack`,`CarriesThisBoardsFilter`],L.parameters={...L.parameters,docs:{...L.parameters?.docs,source:{originalSource:`{
  ...DisplayClicks,
  decorators: [DESK],
  args: {
    ...DisplayClicks.args,
    onNavigate: fn()
  },
  play: async ({
    canvasElement,
    args
  }) => {
    const [bar] = await bars(canvasElement);
    pressMark(bar!);
    const menu = await screen.findByRole('menu');
    await expect(menu.querySelector('[data-slot="drill-group"]')?.textContent).toMatch(/^仓库 是 /);
    await expect(menu.querySelectorAll('[data-slot="drill-away"]').length).toBeGreaterThan(0);
    await userEvent.click(within(menu).getByRole('menuitem', {
      name: new RegExp(zhCN['label.drill.records'])
    }));
    await waitFor(() => expect(args.onNavigate).toHaveBeenCalledTimes(1));
    await expect(args.onNavigate).toHaveBeenCalledWith(expect.objectContaining({
      kind: 'unsaved',
      definitionId: 'orders'
    }));
    // The host opened it: the records, named for the group, and a way back.
    await within(canvasElement).findByRole('heading', {
      level: 2,
      name: /^订单 · 仓库 是 /
    });
    // Opened with its conditions in hand, as a view opened from another's
    // group is: the editor stays folded, and the group is said once, on
    // 「正在显示」, not again in an unfolded band.
    const toggle = await waitFor(() => {
      const found = canvasElement.querySelector('[data-slot="editor-toggle"]');
      expect(found).not.toBeNull();
      return found!;
    });
    await expect(toggle.querySelector('[aria-expanded="true"]')).toBeNull();
    await expect(canvasElement.querySelector('[data-slot="editor-band"]')).toBeNull();
    // The way back is the workbench's own row (D26 Q33), routed by the host.
    await userEvent.click(within(canvasElement.querySelector<HTMLElement>('[data-slot="origin-bar"]')!).getByRole('button', {
      name: label('label.origin.back', {
        title: '出库概览'
      })
    }));
    await panelNamed(canvasElement, '按仓库汇总');
  }
}`,...L.parameters?.docs?.source},description:{story:`Screen H: a bar of 「按仓库汇总」 opens the analysis view's follow-up menu,
headed by the group and the board's filters over it, each item marked as
opening in the workbench; 「查看这些记录」 goes through the host's route,
which opens the records in the workbench under 仓库 and the board's value,
its editor folded.`,...L.parameters?.docs?.description}}},R.parameters={...R.parameters,docs:{...R.parameters?.docs,source:{originalSource:`{
  ...DisplayClicks,
  decorators: [DESK],
  play: async ({
    canvasElement
  }) => {
    const [bar] = await bars(canvasElement);
    const panel = await panelNamed(canvasElement, '按仓库汇总');
    hoverMark(bar!);
    await waitFor(() => expect(chartTooltip(panel)).toBeVisible());
    pressMark(bar!);
    const menu = await screen.findByRole('menu');
    await waitFor(() => expect(chartTooltip(panel)).not.toBeVisible());
    hoverMark(bar!);
    // Given the library's time to raise it, it still is not drawn.
    await new Promise(resolve => setTimeout(resolve, 300));
    await expect(chartTooltip(panel)).not.toBeVisible();
    await expect(within(menu).getAllByRole('menuitem')[0]).toBeVisible();
    await userEvent.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('menu')).toBeNull());
    hoverMark(bar!);
    await waitFor(() => expect(chartTooltip(panel)).toBeVisible());
  }
}`,...R.parameters?.docs?.source},description:{story:`The follow-up menu sits clear of the chart's tooltip (2026-09-24 walk):
the pointer over a bar raises its tooltip, the press puts it away, and
the least move on the bar after the press — before the menu's backdrop is
up — no longer raises it again over the menu's first items. Once the menu
has gone, the tooltip is back for the next pointer.`,...R.parameters?.docs?.description}}},z.parameters={...z.parameters,docs:{...z.parameters?.docs,source:{originalSource:`{
  ...DisplayOpenInWorkbench,
  decorators: [DESK],
  args: {
    ...DisplayOpenInWorkbench.args,
    onNavigate: fn()
  },
  play: async ({
    canvasElement,
    args
  }) => {
    const canvas = within(canvasElement);
    await panelNamed(canvasElement, '待出库明细');
    const filters = await canvas.findByRole('region', {
      name: zhCN['label.filters.bar']
    });
    await userEvent.click(within(filters).getByRole('combobox', {
      name: '仓库'
    }));
    await userEvent.click(await screen.findByRole('option', {
      name: '华南'
    }));
    // The panel runs under it before it is taken anywhere.
    const list = await panelNamed(canvasElement, '待出库明细');
    await waitFor(() => {
      const cells = readColumn(list.querySelector('table')!, '仓库');
      expect(cells.length).toBeGreaterThan(0);
      expect(cells.every(cell => cell === '华南')).toBe(true);
    });
    await userEvent.click(within(list).getByRole('button', {
      name: label('label.panel.menu', {
        title: '待出库明细'
      })
    }));
    await userEvent.click(await screen.findByRole('menuitem', {
      name: zhCN['label.panel.open']
    }));
    // The page's hold as the scope, the reader's value as the view's own.
    await expect(args.onNavigate).toHaveBeenLastCalledWith(expect.objectContaining({
      kind: 'view',
      definitionId: 'orders',
      instanceId: 'orders-pending',
      scopeFilter: {
        op: 'and',
        children: [expect.objectContaining({
          field: 'status',
          value: ['PENDING', 'SHIPPED']
        })]
      },
      filter: {
        op: 'and',
        children: [expect.objectContaining({
          field: 'warehouse',
          value: ['CN-SOUTH']
        })]
      }
    }));
    await canvas.findByRole('heading', {
      level: 2,
      name: '待出库订单'
    });
    // What the board added is a change to the view as saved.
    await waitFor(() => expect(canvasElement.querySelector('[data-slot="view-unsaved"]')).not.toBeNull());
    const applied = await canvas.findByRole('region', {
      name: zhCN['label.applied.title']
    });
    // The reader's 华南: the view's own, removable.
    await within(applied).findByRole('button', {
      name: /^清空 仓库 是 华南/
    });
    // The page's 状态: the scope, set by the page, with no ✕.
    const scoped = [...applied.querySelectorAll<HTMLElement>('[data-scoped]')];
    await expect(scoped).toHaveLength(1);
    await expect(scoped[0]!.textContent).toMatch(/^状态 .*已发运/);
    await expect(scoped[0]!.querySelector('button')).toBeNull();

    // The way back, drawn by the workbench and routed by the host.
    const back = await canvas.findByRole('region', {
      name: zhCN['label.origin.board-region']
    });
    await userEvent.click(within(back).getByRole('button', {
      name: label('label.origin.back', {
        title: '出库概览'
      })
    }));
    // Untouched since it was handed over: nothing is asked.
    await expect(screen.queryByRole('alertdialog')).toBeNull();
    await panelNamed(canvasElement, '待出库明细');
    const again = await canvas.findByRole('region', {
      name: zhCN['label.filters.bar']
    });
    const region = within(again).getByRole('combobox', {
      name: '仓库'
    });
    await waitFor(() => expect(chosen(region)).toBe('华南'));

    // Once more, and this time 华南 comes off in the workbench: taken off
    // whole, the view is the saved one again — no 「已修改」.
    const reopened = await panelNamed(canvasElement, '待出库明细');
    await userEvent.click(within(reopened).getByRole('button', {
      name: label('label.panel.menu', {
        title: '待出库明细'
      })
    }));
    await userEvent.click(await screen.findByRole('menuitem', {
      name: zhCN['label.panel.open']
    }));
    await canvas.findByRole('heading', {
      level: 2,
      name: '待出库订单'
    });
    const shown = await canvas.findByRole('region', {
      name: zhCN['label.applied.title']
    });
    await userEvent.click(await within(shown).findByRole('button', {
      name: /^清空 仓库 是 华南/
    }));
    await waitFor(() => expect(canvasElement.querySelector('[data-slot="view-unsaved"]')).toBeNull());
    await expect(within(shown).queryByRole('button', {
      name: /^清空 仓库/
    })).toBeNull();
    await expect(shown.querySelectorAll('[data-scoped]')).toHaveLength(1);
  }
}`,...z.parameters?.docs?.source},description:{story:`D26 Q30, the P0 of the joint review reversed: the page holds 状态 (locked)
and the reader sets 仓库 to 华南. 「在工作台中打开」 on 「待出库明细」 opens
「待出库订单」 「已修改」, 仓库 是 华南 among its own conditions — with a ✕
— and the page's 状态 as its scope, with none. The row under the title
bar goes back to the board, still on 华南, asking nothing (Q33). Opened
again, 华南 taken off with its ✕ leaves the saved view, not 「已修改」.`,...z.parameters?.docs?.description}}},B.parameters={...B.parameters,docs:{...B.parameters?.docs,source:{originalSource:`{
  ...DisplayCrossFilter,
  decorators: [DESK],
  play: async ({
    canvasElement
  }) => {
    const chart = await panelNamed(canvasElement, '按仓库汇总');
    await expect(within(chart).getByText(label('label.click.badge', {
      filter: '仓库'
    }))).toBeVisible();
    const before = await bars(canvasElement);
    const count = before.length;
    pressMark(before[0]!);
    const bar = await within(canvasElement).findByRole('region', {
      name: zhCN['label.filters.bar']
    });
    const from = await within(bar).findByText(label('label.click.from', {
      panel: '按仓库汇总'
    }));
    await expect(from).toBeVisible();
    const chip = within(bar).getByRole('group', {
      name: '仓库'
    });
    const picked = within(chip).getByRole('combobox', {
      name: '仓库'
    });
    // The value it shows, not the trigger's chevron.
    const shown = () => picked.querySelector('[data-slot="select-value"]')?.textContent?.trim();
    await waitFor(() => expect(shown()).toBeTruthy());
    const region = shown() ?? '';

    // The list runs under it: every row it shows is of that warehouse.
    const list = await panelNamed(canvasElement, '待出库明细');
    await waitFor(() => {
      const table = list.querySelector<HTMLElement>('table');
      expect(table).not.toBeNull();
      const cells = readColumn(table!, '仓库');
      expect(cells.length).toBeGreaterThan(0);
      expect(cells.every(cell => cell === region)).toBe(true);
    });
    // The panel pressed is unchanged: every bar still drawn, the others
    // faint beside the one marked.
    const frame = chart.querySelector<HTMLElement>('[data-slot="chart"]');
    await waitFor(() => expect(frame?.dataset.highlighted).toBe('1'));
    await expect(Number(frame?.dataset.marks)).toBe(count);
    // Only the one pressed is drawn at full strength.
    await waitFor(() => expect(drawnMarks(chart)).toHaveLength(1));
    // The others are still the panel's answer, and still read as bars on
    // the card (U-15): at the old 0.3 they measured 1.3～1.7:1 and all but
    // went. The one pressed stands clear of them.
    const lit = measureMarkContrast(drawnMarks(chart)[0]!);
    const faint = fadedMarks(chart).map(measureMarkContrast);
    await expect(faint).toHaveLength(count - 1);
    for (const measured of faint) {
      await expect(measured.ratio, JSON.stringify(measured)).toBeGreaterThan(FADED_FLOOR);
      await expect(lit.ratio / measured.ratio).toBeGreaterThan(2);
    }

    // Pressed again, it clears, and every bar is drawn as it was.
    pressMark(drawnMarks(chart)[0]!);
    await waitFor(() => expect(within(bar).queryByText(label('label.click.from', {
      panel: '按仓库汇总'
    }))).toBeNull());
    await waitFor(() => expect(frame?.dataset.highlighted).toBe('0'));
    await waitFor(() => expect(drawnMarks(chart).length).toBe(count));
  }
}`,...B.parameters?.docs?.source},description:{story:`Screen I: with 「按仓库汇总」 set to update 「仓库」, a bar pressed sets the
filter bar from it (「来自「按仓库汇总」」), the list runs under it, and
the chart keeps every bar with the pressed one marked; the same bar again
clears it.`,...B.parameters?.docs?.description}}},V.parameters={...V.parameters,docs:{...V.parameters?.docs,source:{originalSource:`{
  ...DisplayClicks,
  decorators: [DESK],
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await panelNamed(canvasElement, '按仓库汇总');
    await userEvent.click(canvas.getByRole('button', {
      name: zhCN['label.dashboard.edit']
    }));
    await userEvent.click(await canvas.findByRole('button', {
      name: label('label.panel.menu', {
        title: '按仓库汇总'
      })
    }));
    await userEvent.click(await screen.findByRole('menuitem', {
      name: zhCN['label.click.menu-item']
    }));
    const dialog = await screen.findByRole('dialog', {
      name: label('label.click.title', {
        panel: '按仓库汇总'
      })
    });
    await userEvent.click(within(dialog).getByRole('radio', {
      name: zhCN['label.click.filter']
    }));
    await userEvent.click(within(dialog).getByRole('button', {
      name: zhCN['label.click.save']
    }));
    const chart = await panelNamed(canvasElement, '按仓库汇总');
    await expect(await within(chart).findByText(label('label.click.badge', {
      filter: '仓库'
    }))).toBeVisible();
    // Back where it was opened from.
    await waitFor(() => expect(document.activeElement).toBe(within(chart).getByRole('button', {
      name: label('label.panel.menu', {
        title: '按仓库汇总'
      })
    })));
  }
}`,...V.parameters?.docs?.source},description:{story:`「点击时…」: while the board is built, a panel's 「⋯」 sets what a press
does. Choosing 「更新仪表盘筛选」 marks the panel 「点击筛选「仓库」」.`,...V.parameters?.docs?.description}}},U.parameters={...U.parameters,docs:{...U.parameters?.docs,source:{originalSource:`{
  ...DisplayToAnotherBoard,
  decorators: [DESK],
  args: {
    ...DisplayToAnotherBoard.args,
    onNavigate: fn()
  },
  play: async ({
    canvasElement,
    args
  }) => {
    const [bar] = await bars(canvasElement);
    pressMark(bar!);
    await waitFor(() => expect(args.onNavigate).toHaveBeenCalledTimes(1));
    await expect(args.onNavigate).toHaveBeenCalledWith(expect.objectContaining({
      kind: 'dashboard',
      instanceId: 'overview-regional',
      filters: {
        values: {
          region: [expect.any(String)]
        }
      }
    }));
    await expect(screen.queryByRole('menu')).toBeNull();
    await openedWithRegion(canvasElement);
  }
}`,...U.parameters?.docs?.source},description:{story:`D23 Q17: 「按仓库汇总」 opens 「区域明细」 with its 仓库 mapped from the
bar. The host is handed the board and the value, and opens it with the
value as its reader's — on the filter bar, the list under it.`,...U.parameters?.docs?.description}}},W.parameters={...W.parameters,docs:{...W.parameters?.docs,source:{originalSource:`{
  ...DisplayToAnotherBoardStale,
  decorators: [DESK],
  args: {
    ...DisplayToAnotherBoardStale.args,
    onNavigate: fn()
  },
  play: async ({
    canvasElement,
    args
  }) => {
    const [bar] = await bars(canvasElement);
    pressMark(bar!);
    const menu = await screen.findByRole('menu');
    await expect(menu.querySelector('[data-slot="drill-group"]')?.textContent).toMatch(/^仓库 是 /);
    await expect(args.onNavigate).not.toHaveBeenCalled();
    await userEvent.keyboard('{Escape}');
    // The panel warns from then on, in the words of the finding.
    const chart = await panelNamed(canvasElement, '按仓库汇总');
    const warning = await waitFor(() => {
      const found = chart.querySelector<HTMLElement>('[data-slot="panel-warning"]');
      expect(found).not.toBeNull();
      return found!;
    });
    await expect(warning.getAttribute('aria-label') ?? '').toContain(label('dashboard.click.board-filter-unknown', {
      filter: 'zone'
    }));
  }
}`,...W.parameters?.docs?.source},description:{story:`The same click mapped to a filter 「区域明细」 no longer has: the press
opens the follow-up menu instead, nothing is handed to the host, and the
panel warns from then on.`,...W.parameters?.docs?.description}}},G.parameters={...G.parameters,docs:{...G.parameters?.docs,source:{originalSource:`{
  ...DisplayClicks,
  decorators: [DESK],
  args: {
    ...DisplayClicks.args,
    onNavigate: fn()
  },
  play: async ({
    canvasElement,
    args
  }) => {
    const canvas = within(canvasElement);
    await panelNamed(canvasElement, '按仓库汇总');
    await userEvent.click(canvas.getByRole('button', {
      name: zhCN['label.dashboard.edit']
    }));
    await userEvent.click(await canvas.findByRole('button', {
      name: label('label.panel.menu', {
        title: '按仓库汇总'
      })
    }));
    await userEvent.click(await screen.findByRole('menuitem', {
      name: zhCN['label.click.menu-item']
    }));
    const dialog = await screen.findByRole('dialog', {
      name: label('label.click.title', {
        panel: '按仓库汇总'
      })
    });
    await userEvent.click(within(dialog).getByRole('radio', {
      name: zhCN['label.click.go']
    }));
    await userEvent.click(within(dialog).getByRole('button', {
      name: zhCN['label.click.go-board']
    }));
    await userEvent.click(within(dialog).getByRole('button', {
      name: zhCN['label.click.board-pick']
    }));
    const picker = await screen.findByRole('dialog', {
      name: label('label.click.board-heading', {
        panel: '按仓库汇总'
      })
    });
    await userEvent.click(await within(picker).findByText('区域明细'));
    const rows = await within(dialog).findByRole('group', {
      name: zhCN['label.click.board-values']
    });
    const region = within(rows).getByRole('combobox', {
      name: '仓库'
    });
    await expect(chosen(region)).toBe(zhCN['label.click.board-skip']);
    const placed = within(rows).getByRole('combobox', {
      name: '下单时间'
    });
    await expect(placed).toHaveAttribute('data-disabled');
    await expect(within(rows).getByText(zhCN['label.click.board-no-source'])).toBeVisible();
    // The keyboard reaches the row and picks from it.
    region.focus();
    await userEvent.keyboard('{Enter}');
    const option = await screen.findByRole('option', {
      name: label('label.click.board-value', {
        dimension: '仓库'
      })
    });
    await userEvent.click(option);
    await waitFor(() => expect(chosen(region)).toBe(label('label.click.board-value', {
      dimension: '仓库'
    })));
    await userEvent.click(within(dialog).getByRole('button', {
      name: zhCN['label.click.save']
    }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    await userEvent.click(canvas.getByRole('button', {
      name: zhCN['label.dashboard.save']
    }));
    await waitFor(() => expect(canvasElement.querySelector('[data-slot="dashboard-edit-bar"]')).toBeNull());
    const [bar] = await bars(canvasElement);
    pressMark(bar!);
    await waitFor(() => expect(args.onNavigate).toHaveBeenCalledWith(expect.objectContaining({
      kind: 'dashboard',
      instanceId: 'overview-regional'
    })));
    await openedWithRegion(canvasElement);
  }
}`,...G.parameters?.docs?.source},description:{story:`「点击时…」 → 「另一块仪表盘」, by keyboard where it counts: the board
picked, its filters listed one per row — 仓库 mapped to 「这一组的仓库」
with the arrow keys, 下单时间 offering nothing this panel can give — then
the board saved, and a bar pressed opens 「区域明细」 under that value.`,...G.parameters?.docs?.description}}},K.parameters={...K.parameters,docs:{...K.parameters?.docs,source:{originalSource:`{
  ...DisplayToAnotherBoard,
  decorators: [DESK],
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await panelNamed(canvasElement, '按仓库汇总');
    await userEvent.click(canvas.getByRole('button', {
      name: zhCN['label.dashboard.edit']
    }));
    await userEvent.click(await canvas.findByRole('button', {
      name: label('label.panel.menu', {
        title: '按仓库汇总'
      })
    }));
    await userEvent.click(await screen.findByRole('menuitem', {
      name: zhCN['label.click.menu-item']
    }));
    const dialog = await screen.findByRole('dialog', {
      name: label('label.click.title', {
        panel: '按仓库汇总'
      })
    });
    await expect(within(dialog).getByRole('button', {
      name: zhCN['label.click.go-board']
    })).toHaveAttribute('aria-pressed', 'true');
    await within(dialog).findByText('区域明细');
    const rows = await within(dialog).findByRole('group', {
      name: zhCN['label.click.board-values']
    });
    const region = within(rows).getByRole('combobox', {
      name: '仓库'
    });
    await expect(chosen(region)).toBe(label('label.click.board-value', {
      dimension: '仓库'
    }));
    within(dialog).getByRole('button', {
      name: zhCN['label.click.board-change']
    }).focus();
    await userEvent.tab();
    await expect(region).toHaveFocus();
  }
}`,...K.parameters?.docs?.source},description:{story:`A stored mapping read back in 「点击时…」, left open so axe judges the
rows: 仪表盘 chosen, 「区域明细」 named, 仓库 on 「这一组的仓库」, and
the keyboard going from 「换一块…」 straight to that row.`,...K.parameters?.docs?.description}}},q.parameters={...q.parameters,docs:{...q.parameters?.docs,source:{originalSource:`{
  ...DisplayToAnotherBoardWithOwnFilter,
  decorators: [DESK],
  args: {
    ...DisplayToAnotherBoardWithOwnFilter.args,
    onNavigate: fn()
  },
  play: async ({
    canvasElement,
    args
  }) => {
    const [bar] = await bars(canvasElement);
    pressMark(bar!);
    await waitFor(() => expect(args.onNavigate).toHaveBeenCalledTimes(1));
    await expect(args.onNavigate).toHaveBeenCalledWith(expect.objectContaining({
      kind: 'dashboard',
      instanceId: 'overview-regional',
      filters: {
        values: {
          region: [expect.any(String)],
          placed: {
            type: 'absolute',
            from: '2026-09-01',
            to: '2026-09-30'
          }
        }
      }
    }));
    await openedWithRegion(canvasElement);
    // 下单时间 holds the value carried: the chip offers to clear it.
    const filterBar = await within(canvasElement).findByRole('region', {
      name: zhCN['label.filters.bar']
    });
    await expect(within(filterBar).getByRole('button', {
      name: label('label.filters.clear-one', {
        filter: '下单时间'
      })
    })).toBeVisible();
  }
}`,...q.parameters?.docs?.source},description:{story:`「这块板的〈筛选〉」 as a source (D23 Q17, 2026-09-23): a bar pressed opens
「区域明细」 with 仓库 from the bar and 下单时间 as this board holds it —
its September default, since no reader changed it — both on the target's
filter bar.`,...q.parameters?.docs?.description}}}})))()}Y();export{L as BarOpensFollowUps,q as CarriesThisBoardsFilter,B as CrossFilterFromABar,K as MappingRowsReadBack,R as MenuClearOfTheTooltip,z as OpenInWorkbenchKeepsBoardFilters,U as PressOpensAnotherBoard,G as SetsAnotherBoardWhenClicked,V as SetsCrossFilterWhenClicked,W as StaleMappingFallsBack,J as __namedExportsOrder,N as default};
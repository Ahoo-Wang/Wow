import{n as e}from"./rolldown-runtime-DkW27tQK.js";import{t}from"./jsx-runtime-DeHZSEgm.js";import{R as n,a as r,h as i,z as a}from"./styles-Dpj2y9Rj.js";import{a as o,l as s}from"./chartDom-C6NvYOT8.js";import{C as c,S as l,_ as u,f as d,m as f,n as p,s as m,t as h,v as g}from"./Dashboard.stories-Dhw1Xda_.js";function _(e){return[...e.querySelectorAll(`.react-grid-item`)].map(e=>({box:e.getBoundingClientRect(),title:e.querySelector(`[data-slot="panel-title"]`)?.textContent??``})).sort((e,t)=>e.box.top-t.box.top||e.box.left-t.box.left).map(e=>e.title)}async function v(e,t){await D.click(k(e).getByRole(`button`,{name:a[`label.dashboard.add`]})),await D.click(await E.findByRole(`menuitem`,{name:t}))}async function y(e){let t=await E.findByRole(`dialog`,{name:a[`label.picker.add-heading`]}),n=await k(t).findByRole(`button`,{name:RegExp(`^${e}`)});return await D.click(n),await O(()=>T(E.queryByRole(`dialog`)).toBeNull()),n}async function b(e,t){let n=e.querySelector(`[data-slot="view-header"]`);await T(n.querySelector(`[data-slot="save-actions"]`)!==null).toBe(t),t||(await T(n.querySelector(`[data-slot="view-unsaved"]`)).toBeNull(),await T(n.querySelector(`[data-slot="view-revert"]`)).toBeNull())}async function x(e,t,n){await O(()=>T(document.querySelector(`[data-slot="dropdown-menu-content"]`)).toBeNull()),await D.click(k(e).getByRole(`button`,{name:M(`label.panel.menu`,{title:t})})),await D.click(await E.findByRole(`menuitem`,{name:n}))}function S(e){return M(`label.history.undo-step`,{what:e})}async function C(e){let t=k(e);await D.click(await t.findByRole(`button`,{name:a[`label.dashboard.edit`]})),await O(()=>T(e.querySelector(`[data-slot="dashboard-edit-bar"]`)).not.toBeNull())}var w,T,E,D,O,k,A,j,M,N,P,F,I,L,R,z,B,V,H,U,W,G,K,q;function J(){return(J=e((()=>{n(),l(),i(),s(),w=t(),{expect:T,screen:E,userEvent:D,waitFor:O,within:k}=__STORYBOOK_MODULE_TEST__,A={...c,title:`View Engine/仪表盘视图/Dashboard/搭建`,tags:[`!dev`,`!autodocs`,`test`],parameters:{...c.parameters}},j=e=>(0,w.jsx)(`div`,{style:{width:1280},children:(0,w.jsx)(e,{})}),M=(e,t={})=>Object.entries(t).reduce((e,[t,n])=>e.replace(`{${t}}`,n),a[e]),N={...m,decorators:[j],play:async({canvasElement:e})=>{let t=k(e);await D.click(await t.findByRole(`button`,{name:a[`label.dashboard.empty.add-view`]})),await T(e.querySelector(`[data-slot="dashboard-edit-bar"]`)).not.toBeNull(),await y(`待出库订单`),await O(()=>T(_(e)).toEqual([`待出库订单`]));let n=e.querySelector(`.react-grid-layout`).getBoundingClientRect(),r=e.querySelector(`.react-grid-item`).getBoundingClientRect();await T(r.width).toBeGreaterThan(n.width*.9),await v(e,a[`label.dashboard.add.saved-view`]),await y(`仓库金额分布`),await v(e,a[`label.dashboard.add.saved-view`]);let i=await E.findByRole(`dialog`),o=await k(i).findByRole(`button`,{name:/^我盯的大额单/});await T(o).toHaveTextContent(a[`label.picker.private`]),await T(k(i).getByRole(`button`,{name:/^待出库订单/})).toHaveTextContent(a[`label.picker.on-board`]),await y(`我盯的大额单`),await O(()=>T(_(e)).toHaveLength(3)),await v(e,a[`label.dashboard.add.heading`]);let s=await t.findByRole(`textbox`,{name:a[`label.panel.heading-input`]});await T(s).toHaveFocus(),await D.clear(s),await D.type(s,`出库{Enter}`),await O(()=>T(_(e)).toContain(`出库`)),await b(e,!1),await x(e,`仓库金额分布`,a[`label.panel.rename`]);let c=await t.findByRole(`textbox`,{name:a[`label.panel.title-input`]});await D.clear(c),await D.type(c,`仓库分布{Enter}`),await x(e,`我盯的大额单`,a[`label.panel.remove`]),await T(E.queryByRole(`alertdialog`)).toBeNull(),await O(()=>T([..._(e)].sort()).toEqual([`仓库分布`,`出库`,`待出库订单`].sort())),await D.click(t.getByRole(`button`,{name:a[`label.dashboard.save`]}));let l=await E.findByRole(`alertdialog`);await D.click(k(l).getByRole(`button`,{name:a[`label.save.shared-confirm`]})),await O(()=>T(e.querySelector(`[data-slot="dashboard-edit-bar"]`)).toBeNull()),await T(e.querySelector(`[data-slot="panel-grip"]`)).toBeNull(),await T(t.queryByText(a[`label.header.unsaved`])).toBeNull(),await T(t.getByRole(`button`,{name:a[`label.dashboard.edit`]})).toHaveFocus(),await b(e,!0)}},P={...p,decorators:[j],play:async({canvasElement:e})=>{let t=k(e);await D.click(await t.findByRole(`button`,{name:a[`label.dashboard.edit`]})),await v(e,a[`label.dashboard.add.heading`]),await D.keyboard(`{Enter}`),await O(()=>T(_(e)).toContain(a[`label.dashboard.new-heading`])),await b(e,!1),await D.click(t.getByRole(`button`,{name:a[`label.dialog.cancel`]}));let n=await E.findByRole(`alertdialog`);await D.click(k(n).getByRole(`button`,{name:a[`label.save.revert`]})),await O(()=>T(_(e)).not.toContain(a[`label.dashboard.new-heading`])),await T(_(e)).toHaveLength(3),await T(t.queryByText(a[`label.header.unsaved`])).toBeNull(),await T(e.querySelector(`[data-slot="dashboard-edit-bar"]`)).toBeNull(),await b(e,!0)}},F={...u,play:async({canvasElement:e})=>{let t=k(e);await t.findByRole(`heading`,{level:3,name:`待出库明细`}),await T(t.queryByRole(`button`,{name:a[`label.dashboard.edit`]})).toBeNull(),await T(t.getByRole(`button`,{name:a[`label.save.save-as`]})).toBeVisible()}},I={...h,decorators:[j],play:async({canvasElement:e})=>{let t=k(e);await t.findByRole(`heading`,{level:3,name:`待出库明细`}),await t.findAllByRole(`table`);for(let t of[`panel-grip`,`panel-arrange`,`panel-resize`])await T(e.querySelector(`[data-slot="${t}"]`)).toBeNull();await D.click(t.getByRole(`button`,{name:M(`label.panel.menu`,{title:`待出库明细`})}));let n=await E.findByRole(`menu`);await T(k(n).getAllByRole(`menuitem`).map(e=>e.textContent)).toEqual([a[`label.panel.refresh`],a[`label.panel.export`]]),await D.keyboard(`{Escape}`),await D.click(t.getByRole(`button`,{name:a[`label.dashboard.edit`]})),await O(()=>T(e.querySelectorAll(`[data-slot="panel-grip"]`).length).toBe(3))}},L={...h,decorators:[j],play:async({canvasElement:e})=>{let t=k(e);await t.findByRole(`heading`,{level:3,name:`按仓库汇总`}),await C(e);let n=M(`label.history.remove-panel`,{title:`按仓库汇总`});await x(e,`按仓库汇总`,a[`label.panel.remove`]),await T(E.queryByRole(`alertdialog`)).toBeNull(),await O(()=>T(_(e)).toEqual([`待出库明细`,`值班手册`]));let r=t.getByRole(`button`,{name:S(n)});await O(()=>T(r).toHaveFocus()),await T(t.getByText(M(`label.dashboard.removed`,{title:`按仓库汇总`}))).toBeInTheDocument(),await D.keyboard(`{Enter}`),await O(()=>T(_(e)).toEqual([`待出库明细`,`按仓库汇总`,`值班手册`])),await o(e),await T(t.getByText(M(`label.history.undone`,{what:n}))).toBeInTheDocument(),await T(t.getByRole(`button`,{name:M(`label.history.redo-step`,{what:n})})).toHaveFocus(),t.getByRole(`button`,{name:M(`label.panel.menu`,{title:`待出库明细`})}).focus(),await D.keyboard(`{Control>}{Shift>}z{/Shift}{/Control}`),await O(()=>T(_(e)).toEqual([`待出库明细`,`值班手册`])),await D.keyboard(`{Control>}z{/Control}`),await O(()=>T(_(e)).toHaveLength(3))}},R={...h,decorators:[e=>(0,w.jsx)(`div`,{style:{width:414},children:(0,w.jsx)(e,{})})],play:async({canvasElement:e})=>{let t=k(e);await O(()=>T(e.querySelector(`[data-slot="dashboard-grid"]`)).toHaveAttribute(`data-narrow`)),await C(e),await T(e.querySelector(`[data-slot="panel-grip"]`)).toBeNull();let n=t.getByRole(`button`,{name:M(`label.panel.order-down`,{title:`待出库明细`})});await T(t.getByRole(`button`,{name:M(`label.panel.order-up`,{title:`待出库明细`})})).toBeDisabled(),n.focus(),await D.keyboard(`{Enter}`),await O(()=>T(_(e)).toEqual([`按仓库汇总`,`待出库明细`,`值班手册`])),await T(t.getByText(M(`label.panel.reordered`,{title:`待出库明细`,index:`2`,total:`3`}))).toBeInTheDocument(),await O(()=>T(n).toHaveFocus()),await D.keyboard(`{Enter}`),await O(()=>T(_(e)).toEqual([`按仓库汇总`,`值班手册`,`待出库明细`])),await O(()=>T(t.getByRole(`button`,{name:M(`label.panel.order-up`,{title:`待出库明细`})})).toHaveFocus()),await T(n).toBeDisabled();let r=M(`label.history.move-panel`,{title:`待出库明细`});await D.click(t.getByRole(`button`,{name:S(r)})),await D.click(t.getByRole(`button`,{name:S(r)})),await O(()=>T(_(e)).toEqual([`待出库明细`,`按仓库汇总`,`值班手册`]))}},z={...p,decorators:[j],play:async({canvasElement:e})=>{await k(e).findByRole(`heading`,{level:3,name:`按仓库汇总`}),await C(e),await v(e,a[`label.dashboard.add.new-analysis`]);let t=await E.findByRole(`dialog`,{name:M(`label.panel.new-analysis.heading-of`,{definition:`订单`})}),n=k(t);await T(t.querySelector(`[data-slot="new-analysis-tray"]`)).not.toBeNull();let r=n.getByRole(`textbox`,{name:a[`label.panel.new-analysis.title`]});await O(()=>T(r).toHaveValue(`按仓库 · 记录数`)),await O(()=>T(t.contains(document.activeElement)).toBe(!0)),await D.clear(r),await D.type(r,`各仓订单数`,{skipClick:!0}),await O(()=>T(r).toHaveValue(`各仓订单数`)),await D.click(n.getByRole(`button`,{name:a[`label.panel.new-analysis.add`]})),await O(()=>T(E.queryByRole(`dialog`)).toBeNull()),await O(()=>T(_(e)).toContain(`各仓订单数`)),await O(()=>T(document.activeElement).toBe(k(e).getByRole(`button`,{name:a[`label.dashboard.add`]})))}},B={...d,decorators:[j],play:async({canvasElement:e})=>{let t=`本板自建：订单数按仓库`;await k(e).findByRole(`heading`,{level:3,name:t}),await C(e),await x(e,t,a[`label.panel.save-as-view`]);let n=await E.findByRole(`dialog`,{name:a[`label.panel.save-owned.heading`]}),r=k(n);await T(n).toHaveTextContent(`它会成为「订单」的一个视图`),await T(r.getByRole(`radio`,{name:a[`label.scope.everyone`]})).toBeChecked();let i=r.getByRole(`textbox`,{name:a[`label.save.title`]});await T(i).toHaveValue(t),await D.click(r.getByRole(`button`,{name:a[`label.panel.save-owned.submit`]})),await O(()=>T(E.queryByRole(`dialog`)).toBeNull()),await T(e.ownerDocument.querySelector(`[data-slot="dashboard-announcement"]`)).toHaveTextContent(`已另存为视图「${t}」`),await D.click(k(e).getByRole(`button`,{name:M(`label.panel.menu`,{title:t})}));let o=await E.findByRole(`menu`);await T(k(o).queryByRole(`menuitem`,{name:a[`label.panel.save-as-view`]})).toBeNull(),await D.keyboard(`{Escape}`)}},V={...p,decorators:[j],play:async({canvasElement:e})=>{let t=k(e),n=`按仓库汇总`;await t.findByRole(`heading`,{level:3,name:n}),await o(e),await C(e);let i=r.current,s=async()=>(await x(e,n,a[`label.panel.edit-presentation`]),E.findByRole(`dialog`,{name:M(`label.panel.presentation.heading`,{title:n})})),c=await s();await D.click(k(c).getByRole(`radio`,{name:`饼图`})),await T(await k(c).findByRole(`img`,{name:/^饼图/})).toBeVisible(),await D.click(k(c).getByRole(`button`,{name:a[`label.panel.presentation.done`]})),await O(()=>T(E.queryByRole(`dialog`)).toBeNull()),await T(t.getByText(`此处改为饼图`)).toBeVisible(),await T(await t.findByRole(`img`,{name:/^饼图/})).toBeVisible(),await T(r.current).toBe(i),await x(e,n,a[`label.panel.presentation.reset`]),await O(()=>T(t.queryByText(`此处改为饼图`)).toBeNull()),await T(await t.findByRole(`img`,{name:/^柱状图/})).toBeVisible(),c=await s(),await D.click(k(c).getByRole(`radio`,{name:`饼图`})),await D.click(k(c).getByRole(`button`,{name:a[`label.dialog.cancel`]})),await O(()=>T(E.queryByRole(`dialog`)).toBeNull()),await T(t.queryByText(`此处改为饼图`)).toBeNull(),await T(r.current).toBe(i)}},H={...g,decorators:[j],play:async({canvasElement:e})=>{let t=k(e),n=await t.findByRole(`tablist`,{name:a[`label.tabs.name`]});await t.findByRole(`heading`,{level:3,name:`按仓库汇总`}),await T(t.queryByRole(`heading`,{level:3,name:`按状态看金额`})).toBeNull(),await o(e);let i=r.current;await D.click(k(n).getByRole(`tab`,{name:`状态`})),await t.findByRole(`heading`,{level:3,name:`按状态看金额`}),await O(()=>T(r.current).toBeGreaterThan(i));let s=r.current;await D.click(k(n).getByRole(`tab`,{name:`出库`})),await t.findByRole(`heading`,{level:3,name:`按仓库汇总`}),await T(r.current).toBe(s),await D.click(k(n).getByRole(`tab`,{name:`状态`})),await t.findByRole(`heading`,{level:3,name:`按状态看金额`}),await D.click(await t.findByText(`异常概览`)),await O(()=>T(t.queryByRole(`tablist`)).toBeNull()),await D.click(await t.findByText(`出库概览`)),await O(()=>T(t.getByRole(`tab`,{name:`状态`})).toHaveAttribute(`aria-selected`,`true`)),await T(t.queryByRole(`heading`,{level:3,name:`待出库明细`})).toBeNull()}},U={...g,decorators:[j],play:async({canvasElement:e})=>{let t=k(e);await t.findByRole(`heading`,{level:3,name:`按仓库汇总`}),await C(e),await D.click(t.getByRole(`button`,{name:a[`label.tabs.add`]}));let n=await t.findByRole(`textbox`,{name:`标签页「标签页 3」的名字`});await O(()=>T(n).toHaveFocus()),await D.keyboard(`异常{Enter}`);let r=await t.findByRole(`button`,{name:`异常`});await T(r).toHaveAttribute(`aria-current`,`true`),await T(t.getByText(`这个标签页还没有面板`)).toBeVisible(),await D.click(t.getByRole(`button`,{name:`出库`})),await x(e,`按仓库汇总`,a[`label.panel.move-to-tab`]),await E.findByRole(`menuitem`,{name:`异常`}),await D.keyboard(`{ArrowRight}`),await O(()=>T(document.activeElement?.textContent).toBe(`状态`)),await D.keyboard(`{ArrowDown}`),await O(()=>T(document.activeElement?.textContent).toBe(`异常`)),await D.keyboard(`{Enter}`),await O(()=>T(t.queryByRole(`heading`,{level:3,name:`按仓库汇总`})).toBeNull()),await D.click(t.getByRole(`button`,{name:`异常`})),await T(await t.findByRole(`heading`,{level:3,name:`按仓库汇总`})).toBeVisible(),t.getByRole(`button`,{name:`调整「异常」的顺序`}).focus(),await D.keyboard(`{ArrowLeft}`),await O(()=>T(k(t.getByRole(`list`,{name:a[`label.tabs.name`]})).getAllByRole(`listitem`).map(e=>e.querySelector(`[data-slot="dashboard-tab"]`)?.textContent)).toEqual([`出库`,`异常`,`状态`]))}},W={...f,decorators:[j],play:async({canvasElement:e})=>{let t=k(e);await t.findAllByRole(`table`);let n=t.getByRole(`button`,{name:M(`label.panel.menu`,{title:`待出库明细`})});n.focus(),await D.keyboard(`{Enter}`);let r=await E.findByRole(`menu`),i=k(r).getByRole(`menuitem`,{name:a[`label.panel.export`]});for(let e=0;e<4&&document.activeElement!==i;e+=1)await D.keyboard(`{ArrowDown}`);await T(i).toHaveFocus(),await D.keyboard(`{Enter}`);let o=await E.findByRole(`dialog`,{name:a[`label.export.title`]});await T(k(o).queryByRole(`radio`)).toBeNull(),await T(o.textContent).toMatch(/文件：待出库明细-\d{4}-\d{2}-\d{2}\.csv/),await O(()=>T(k(o).getByRole(`button`,{name:a[`label.export.confirm`]})).toHaveFocus()),await D.keyboard(`{Escape}`),await O(()=>T(E.queryByRole(`dialog`)).toBeNull()),await O(()=>T(n).toHaveFocus()),await D.click(t.getByRole(`button`,{name:M(`label.panel.menu`,{title:`按仓库汇总`})})),await T(k(await E.findByRole(`menu`)).queryByRole(`menuitem`,{name:a[`label.panel.export`]})).toBeNull(),await D.keyboard(`{Escape}`)}},G={...f,decorators:[j],play:async({canvasElement:e})=>{let t=k(e),n=(await t.findByRole(`heading`,{level:3,name:`我盯的大额单`})).closest(`[data-slot="dashboard-panel"]`);await O(()=>T(n.querySelector(`[data-slot="panel-warning"]`)).not.toBeNull()),await C(e);let r=t.getByRole(`button`,{name:M(`label.panel.menu`,{title:`我盯的大额单`})});await x(e,`我盯的大额单`,a[`label.panel.copy-shared`]);let i=await E.findByRole(`dialog`,{name:a[`label.panel.copy-shared.heading`]});await T(k(i).queryByRole(`radio`)).toBeNull(),await T(k(i).getByRole(`textbox`,{name:a[`label.save.title`]})).toHaveValue(`我盯的大额单`),await D.click(k(i).getByRole(`button`,{name:a[`label.panel.copy-shared.submit`]})),await O(()=>T(E.queryByRole(`dialog`)).toBeNull()),await O(()=>T(n.querySelector(`[data-slot="panel-warning"]`)).toBeNull()),await T(t.getByRole(`heading`,{level:3,name:`我盯的大额单`})).toBeVisible(),await O(()=>T(r).toHaveFocus())}},K={...p,decorators:[j],play:async({canvasElement:e})=>{let t=k(e);await t.findByRole(`heading`,{level:3,name:`按仓库汇总`}),await C(e);let n=t.getByRole(`button`,{name:a[`label.dashboard.add`]}),r=e=>t.getByRole(`button`,{name:M(`label.panel.menu`,{title:e})}),i=[[n,`label.dashboard.add.saved-view`],[n,`label.dashboard.add.markdown`],[n,`label.dashboard.add.image`],[n,`label.dashboard.add.links`],[r(`按仓库汇总`),`label.panel.replace`],[r(`值班手册`),`label.panel.edit-content`]];for(let[e,t]of i){e.focus(),await D.keyboard(`{Enter}`);let n=await E.findByRole(`menu`);k(n).getByRole(`menuitem`,{name:a[t]}).focus(),await D.keyboard(`{Enter}`);let r=await E.findByRole(`dialog`);await O(()=>T(document.querySelector(`[data-slot="dropdown-menu-content"]`)).toBeNull()),await O(()=>T(r.contains(document.activeElement)&&document.activeElement?.matches(`input, textarea`),t).toBe(!0));let i=document.activeElement;await D.keyboard(`abc`),await T(i.value,t).toContain(`abc`),await D.keyboard(`{Escape}`),await O(()=>T(E.queryByRole(`dialog`)).toBeNull()),await O(()=>T(e,t).toHaveFocus())}}},q=[`BuildFromEmpty`,`CancelReverts`,`SystemDashboardHasNoEdit`,`NoGripsUntilBuilding`,`RemoveThenUndo`,`NarrowReorderByKeyboard`,`CreateOwnedAnalysis`,`PromoteOwnedAnalysis`,`OverrideToPieAndReset`,`TabsRunOnlyTheTabShown`,`TabsBuilt`,`PanelExportWindow`,`CopyPersonalViewAsShared`,`MenuItemsHandTheKeyboardToTheirDialog`],N.parameters={...N.parameters,docs:{...N.parameters?.docs,source:{originalSource:`{
  ...DisplayEmptySharedBoard,
  decorators: [DESK],
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await userEvent.click(await canvas.findByRole('button', {
      name: zhCN['label.dashboard.empty.add-view']
    }));
    // The first step starts the building with it (read off the page: the
    // picker is modal, and what is under it is out of the reading order).
    await expect(canvasElement.querySelector('[data-slot="dashboard-edit-bar"]')).not.toBeNull();
    await pick('待出库订单');
    await waitFor(() => expect(titles(canvasElement)).toEqual(['待出库订单']));
    // A table takes the whole width of the board.
    const grid = canvasElement.querySelector('.react-grid-layout')!.getBoundingClientRect();
    const first = canvasElement.querySelector('.react-grid-item')!.getBoundingClientRect();
    await expect(first.width).toBeGreaterThan(grid.width * 0.9);
    await addFromBar(canvasElement, zhCN['label.dashboard.add.saved-view']);
    await pick('仓库金额分布');
    await addFromBar(canvasElement, zhCN['label.dashboard.add.saved-view']);
    const picker = await screen.findByRole('dialog');
    const own = await within(picker).findByRole('button', {
      name: /^我盯的大额单/
    });
    await expect(own).toHaveTextContent(zhCN['label.picker.private']);
    // Already on the board, and still offered.
    await expect(within(picker).getByRole('button', {
      name: /^待出库订单/
    })).toHaveTextContent(zhCN['label.picker.on-board']);
    await pick('我盯的大额单');
    await waitFor(() => expect(titles(canvasElement)).toHaveLength(3));
    await addFromBar(canvasElement, zhCN['label.dashboard.add.heading']);
    const heading = await canvas.findByRole('textbox', {
      name: zhCN['label.panel.heading-input']
    });
    await expect(heading).toHaveFocus();
    await userEvent.clear(heading);
    await userEvent.type(heading, '出库{Enter}');
    await waitFor(() => expect(titles(canvasElement)).toContain('出库'));
    // Changed, and the title bar neither says so nor undoes it: 保存 and
    // 取消 on the edit bar are the one way to commit or roll back.
    await expectTitleBarCommits(canvasElement, false);
    await fromPanelMenu(canvasElement, '仓库金额分布', zhCN['label.panel.rename']);
    const title = await canvas.findByRole('textbox', {
      name: zhCN['label.panel.title-input']
    });
    await userEvent.clear(title);
    await userEvent.type(title, '仓库分布{Enter}');

    // Removed at once — 「撤销」 is the way back, and the keyboard is on it.
    await fromPanelMenu(canvasElement, '我盯的大额单', zhCN['label.panel.remove']);
    await expect(screen.queryByRole('alertdialog')).toBeNull();
    await waitFor(() => expect([...titles(canvasElement)].sort()).toEqual(['仓库分布', '出库', '待出库订单'].sort()));
    await userEvent.click(canvas.getByRole('button', {
      name: zhCN['label.dashboard.save']
    }));
    const confirm = await screen.findByRole('alertdialog');
    await userEvent.click(within(confirm).getByRole('button', {
      name: zhCN['label.save.shared-confirm']
    }));
    // Saved and read again: no bar, no handles, nothing left unsaved.
    await waitFor(() => expect(canvasElement.querySelector('[data-slot="dashboard-edit-bar"]')).toBeNull());
    await expect(canvasElement.querySelector('[data-slot="panel-grip"]')).toBeNull();
    await expect(canvas.queryByText(zhCN['label.header.unsaved'])).toBeNull();
    await expect(canvas.getByRole('button', {
      name: zhCN['label.dashboard.edit']
    })).toHaveFocus();
    await expectTitleBarCommits(canvasElement, true);
  }
}`,...N.parameters?.docs?.source},description:{story:`The board built from nothing with the screen alone (D22 A, B, D): three
saved views from the picker — one of them the author's own, which a
shared board marks 「只有你看得到」 before it goes on — and a heading named
in place; one panel renamed, one removed; 保存 asks, as Save does over a
shared view, and saves.`,...N.parameters?.docs?.description}}},P.parameters={...P.parameters,docs:{...P.parameters?.docs,source:{originalSource:`{
  ...DisplayBuilding,
  decorators: [DESK],
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await userEvent.click(await canvas.findByRole('button', {
      name: zhCN['label.dashboard.edit']
    }));
    await addFromBar(canvasElement, zhCN['label.dashboard.add.heading']);
    await userEvent.keyboard('{Enter}');
    await waitFor(() => expect(titles(canvasElement)).toContain(zhCN['label.dashboard.new-heading']));
    // One way to do one thing: the edit bar holds 保存 and 取消, so the
    // title bar has neither its Save nor its 「已修改 ↺」 beside them.
    await expectTitleBarCommits(canvasElement, false);
    await userEvent.click(canvas.getByRole('button', {
      name: zhCN['label.dialog.cancel']
    }));
    const question = await screen.findByRole('alertdialog');
    await userEvent.click(within(question).getByRole('button', {
      name: zhCN['label.save.revert']
    }));
    await waitFor(() => expect(titles(canvasElement)).not.toContain(zhCN['label.dashboard.new-heading']));
    await expect(titles(canvasElement)).toHaveLength(3);
    await expect(canvas.queryByText(zhCN['label.header.unsaved'])).toBeNull();
    await expect(canvasElement.querySelector('[data-slot="dashboard-edit-bar"]')).toBeNull();
    // Read again: the title bar saves as it did before 编辑.
    await expectTitleBarCommits(canvasElement, true);
  }
}`,...P.parameters?.docs?.source},description:{story:`取消 asks, then puts back the board as it was saved.`,...P.parameters?.docs?.description}}},F.parameters={...F.parameters,docs:{...F.parameters?.docs,source:{originalSource:`{
  ...DisplaySystemDashboard,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('heading', {
      level: 3,
      name: '待出库明细'
    });
    await expect(canvas.queryByRole('button', {
      name: zhCN['label.dashboard.edit']
    })).toBeNull();
    await expect(canvas.getByRole('button', {
      name: zhCN['label.save.save-as']
    })).toBeVisible();
  }
}`,...F.parameters?.docs?.source},description:{story:`The board the definition ships is read-only: 另存为, and no 编辑 (D4).`,...F.parameters?.docs?.description}}},I.parameters={...I.parameters,docs:{...I.parameters?.docs,source:{originalSource:`{
  ...DisplayAllPanels,
  decorators: [DESK],
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('heading', {
      level: 3,
      name: '待出库明细'
    });
    // Its rows on screen, so 「导出数据…」 is one of the things to do.
    await canvas.findAllByRole('table');
    for (const handle of ['panel-grip', 'panel-arrange', 'panel-resize']) await expect(canvasElement.querySelector(\`[data-slot="\${handle}"]\`)).toBeNull();
    await userEvent.click(canvas.getByRole('button', {
      name: label('label.panel.menu', {
        title: '待出库明细'
      })
    }));
    const menu = await screen.findByRole('menu');
    await expect(within(menu).getAllByRole('menuitem').map(item => item.textContent)).toEqual([zhCN['label.panel.refresh'], zhCN['label.panel.export']]);
    await userEvent.keyboard('{Escape}');
    await userEvent.click(canvas.getByRole('button', {
      name: zhCN['label.dashboard.edit']
    }));
    await waitFor(() => expect(canvasElement.querySelectorAll('[data-slot="panel-grip"]').length).toBe(3));
  }
}`,...I.parameters?.docs?.source},description:{story:`A board being read moves under nothing (D22 A): no grip, no corner, no
arrange menu, and the panel's 「⋯」 holds 「看」 alone — until 编辑.`,...I.parameters?.docs?.description}}},L.parameters={...L.parameters,docs:{...L.parameters?.docs,source:{originalSource:`{
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
    await startBuilding(canvasElement);
    const removal = label('label.history.remove-panel', {
      title: '按仓库汇总'
    });
    await fromPanelMenu(canvasElement, '按仓库汇总', zhCN['label.panel.remove']);
    await expect(screen.queryByRole('alertdialog')).toBeNull();
    await waitFor(() => expect(titles(canvasElement)).toEqual(['待出库明细', '值班手册']));
    const undo = canvas.getByRole('button', {
      name: undoOf(removal)
    });
    await waitFor(() => expect(undo).toHaveFocus());
    await expect(canvas.getByText(label('label.dashboard.removed', {
      title: '按仓库汇总'
    }))).toBeInTheDocument();
    await userEvent.keyboard('{Enter}');
    await waitFor(() => expect(titles(canvasElement)).toEqual(['待出库明细', '按仓库汇总', '值班手册']));
    await chartsDrawn(canvasElement);
    await expect(canvas.getByText(label('label.history.undone', {
      what: removal
    }))).toBeInTheDocument();
    // Nothing left to undo: the keyboard is on 「重做」 rather than nowhere.
    await expect(canvas.getByRole('button', {
      name: label('label.history.redo-step', {
        what: removal
      })
    })).toHaveFocus();

    // The board's keys, from a panel.
    canvas.getByRole('button', {
      name: label('label.panel.menu', {
        title: '待出库明细'
      })
    }).focus();
    await userEvent.keyboard('{Control>}{Shift>}z{/Shift}{/Control}');
    await waitFor(() => expect(titles(canvasElement)).toEqual(['待出库明细', '值班手册']));
    await userEvent.keyboard('{Control>}z{/Control}');
    await waitFor(() => expect(titles(canvasElement)).toHaveLength(3));
  }
}`,...L.parameters?.docs?.source},description:{story:`A panel removed at once, nothing asked, and brought back (D22 D, batch
B2): the keyboard lands on 「撤销」, which is named after the step it takes
back; Enter brings the panel back drawing again, and the keys the board
answers — ⇧⌘Z／Ctrl+Shift+Z, ⌘Z／Ctrl+Z — take the same step again and
back while the focus is on a panel.`,...L.parameters?.docs?.description}}},R.parameters={...R.parameters,docs:{...R.parameters?.docs,source:{originalSource:`{
  ...DisplayAllPanels,
  decorators: [Story => <div style={{
    width: 414
  }}>
        <Story />
      </div>],
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await waitFor(() => expect(canvasElement.querySelector('[data-slot="dashboard-grid"]')).toHaveAttribute('data-narrow'));
    await startBuilding(canvasElement);
    await expect(canvasElement.querySelector('[data-slot="panel-grip"]')).toBeNull();
    const down = canvas.getByRole('button', {
      name: label('label.panel.order-down', {
        title: '待出库明细'
      })
    });
    await expect(canvas.getByRole('button', {
      name: label('label.panel.order-up', {
        title: '待出库明细'
      })
    })).toBeDisabled();
    down.focus();
    await userEvent.keyboard('{Enter}');
    await waitFor(() => expect(titles(canvasElement)).toEqual(['按仓库汇总', '待出库明细', '值班手册']));
    await expect(canvas.getByText(label('label.panel.reordered', {
      title: '待出库明细',
      index: '2',
      total: '3'
    }))).toBeInTheDocument();
    await waitFor(() => expect(down).toHaveFocus());
    await userEvent.keyboard('{Enter}');
    await waitFor(() => expect(titles(canvasElement)).toEqual(['按仓库汇总', '值班手册', '待出库明细']));
    // The end of the column: 下移 has run out, the keyboard is on 上移.
    await waitFor(() => expect(canvas.getByRole('button', {
      name: label('label.panel.order-up', {
        title: '待出库明细'
      })
    })).toHaveFocus());
    await expect(down).toBeDisabled();
    const moving = label('label.history.move-panel', {
      title: '待出库明细'
    });
    await userEvent.click(canvas.getByRole('button', {
      name: undoOf(moving)
    }));
    await userEvent.click(canvas.getByRole('button', {
      name: undoOf(moving)
    }));
    await waitFor(() => expect(titles(canvasElement)).toEqual(['待出库明细', '按仓库汇总', '值班手册']));
  }
}`,...R.parameters?.docs?.source},description:{story:`The one-column reading built by keyboard (D22 J): a phone's width, where
a panel has 「上移」／「下移」 and nothing that places. Each press moves it
one place down the column and says where it came to; at the end of the
column the keyboard moves on to 「上移」; and 「撤销」 takes the moves back.`,...R.parameters?.docs?.description}}},z.parameters={...z.parameters,docs:{...z.parameters?.docs,source:{originalSource:`{
  ...DisplayBuilding,
  decorators: [DESK],
  play: async ({
    canvasElement
  }) => {
    await within(canvasElement).findByRole('heading', {
      level: 3,
      name: '按仓库汇总'
    });
    await startBuilding(canvasElement);
    await addFromBar(canvasElement, zhCN['label.dashboard.add.new-analysis']);
    const dialog = await screen.findByRole('dialog', {
      name: label('label.panel.new-analysis.heading-of', {
        definition: '订单'
      })
    });
    const inside = within(dialog);
    // The same tray as the workbench: dimensions and metrics.
    await expect(dialog.querySelector('[data-slot="new-analysis-tray"]')).not.toBeNull();
    const title = inside.getByRole('textbox', {
      name: zhCN['label.panel.new-analysis.title']
    });
    await waitFor(() => expect(title).toHaveValue('按仓库 · 记录数'));
    // Held inside while it is open: the keyboard starts on the first
    // question, which data.
    await waitFor(() => expect(dialog.contains(document.activeElement)).toBe(true));

    // Typed once the dialog has settled its own focus, and read back before
    // it is put on the board: the name its author gave it.
    await userEvent.clear(title);
    await userEvent.type(title, '各仓订单数', {
      skipClick: true
    });
    await waitFor(() => expect(title).toHaveValue('各仓订单数'));
    await userEvent.click(inside.getByRole('button', {
      name: zhCN['label.panel.new-analysis.add']
    }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    // On the board, under the name its author gave it.
    await waitFor(() => expect(titles(canvasElement)).toContain('各仓订单数'));
    await waitFor(() => expect(document.activeElement).toBe(within(canvasElement).getByRole('button', {
      name: zhCN['label.dashboard.add']
    })));
  }
}`,...z.parameters?.docs?.source},description:{story:`Screen C: a new analysis made inside the dashboard, from 「＋ 添加 ▾」. The
dialog is the analysis view — the tray and the result, running as it is
edited — named by what it shows, and 「放进仪表盘」 puts it on the board.
The keyboard is held inside while it is open and handed back to 「添加」.`,...z.parameters?.docs?.description}}},B.parameters={...B.parameters,docs:{...B.parameters?.docs,source:{originalSource:`{
  ...DisplayOwnedAnalysis,
  decorators: [DESK],
  play: async ({
    canvasElement
  }) => {
    const owned = '本板自建：订单数按仓库';
    await within(canvasElement).findByRole('heading', {
      level: 3,
      name: owned
    });
    await startBuilding(canvasElement);
    await fromPanelMenu(canvasElement, owned, zhCN['label.panel.save-as-view']);
    const dialog = await screen.findByRole('dialog', {
      name: zhCN['label.panel.save-owned.heading']
    });
    const inside = within(dialog);
    await expect(dialog).toHaveTextContent('它会成为「订单」的一个视图');
    await expect(inside.getByRole('radio', {
      name: zhCN['label.scope.everyone']
    })).toBeChecked();
    const title = inside.getByRole('textbox', {
      name: zhCN['label.save.title']
    });
    await expect(title).toHaveValue(owned);
    await userEvent.click(inside.getByRole('button', {
      name: zhCN['label.panel.save-owned.submit']
    }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    await expect(canvasElement.ownerDocument.querySelector('[data-slot="dashboard-announcement"]')).toHaveTextContent(\`已另存为视图「\${owned}」\`);
    await userEvent.click(within(canvasElement).getByRole('button', {
      name: label('label.panel.menu', {
        title: owned
      })
    }));
    const menu = await screen.findByRole('menu');
    await expect(within(menu).queryByRole('menuitem', {
      name: zhCN['label.panel.save-as-view']
    })).toBeNull();
    await userEvent.keyboard('{Escape}');
  }
}`,...B.parameters?.docs?.source},description:{story:`Screen C, the other half: an analysis the board owns saved as a view of
its own, from its 「⋯」. The dialog asks for a title and an audience —
the board's, a shared one, first — and the panel then shows that view:
its menu no longer offers to save it.`,...B.parameters?.docs?.description}}},V.parameters={...V.parameters,docs:{...V.parameters?.docs,source:{originalSource:`{
  ...DisplayBuilding,
  decorators: [DESK],
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    const name = '按仓库汇总';
    await canvas.findByRole('heading', {
      level: 3,
      name
    });
    await chartsDrawn(canvasElement);
    await startBuilding(canvasElement);
    const asked = aggregateCalls.current;
    const look = async () => {
      await fromPanelMenu(canvasElement, name, zhCN['label.panel.edit-presentation']);
      return screen.findByRole('dialog', {
        name: label('label.panel.presentation.heading', {
          title: name
        })
      });
    };
    let dialog = await look();
    await userEvent.click(within(dialog).getByRole('radio', {
      name: '饼图'
    }));
    // Beside the options, the panel as it will look: a pie.
    await expect(await within(dialog).findByRole('img', {
      name: /^饼图/
    })).toBeVisible();
    await userEvent.click(within(dialog).getByRole('button', {
      name: zhCN['label.panel.presentation.done']
    }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());

    // On the board: marked, and drawn as a pie from the rows it had.
    await expect(canvas.getByText('此处改为饼图')).toBeVisible();
    await expect(await canvas.findByRole('img', {
      name: /^饼图/
    })).toBeVisible();
    // Presentation never asks the source (D20).
    await expect(aggregateCalls.current).toBe(asked);

    // Put back from the menu: the view's own bars, and no mark.
    await fromPanelMenu(canvasElement, name, zhCN['label.panel.presentation.reset']);
    await waitFor(() => expect(canvas.queryByText('此处改为饼图')).toBeNull());
    await expect(await canvas.findByRole('img', {
      name: /^柱状图/
    })).toBeVisible();

    // 取消 in the dialog puts back what the panel had when it opened.
    dialog = await look();
    await userEvent.click(within(dialog).getByRole('radio', {
      name: '饼图'
    }));
    await userEvent.click(within(dialog).getByRole('button', {
      name: zhCN['label.dialog.cancel']
    }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    await expect(canvas.queryByText('此处改为饼图')).toBeNull();
    await expect(aggregateCalls.current).toBe(asked);
  }
}`,...V.parameters?.docs?.source},description:{story:`Screen D: a panel's own look, from its 「⋯」. The visualization panel
picks a pie for this panel alone; the panel says 「此处改为饼图」 and is
drawn as a pie from the rows it had — no query. 「恢复为视图的样子」 on the
same menu puts the view's own bars back, and 取消 in the dialog puts back
what the panel had when it opened.`,...V.parameters?.docs?.description}}},H.parameters={...H.parameters,docs:{...H.parameters?.docs,source:{originalSource:`{
  ...DisplayTabs,
  decorators: [DESK],
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    const tabs = await canvas.findByRole('tablist', {
      name: zhCN['label.tabs.name']
    });
    await canvas.findByRole('heading', {
      level: 3,
      name: '按仓库汇总'
    });
    await expect(canvas.queryByRole('heading', {
      level: 3,
      name: '按状态看金额'
    })).toBeNull();
    await chartsDrawn(canvasElement);
    const asked = aggregateCalls.current;
    await userEvent.click(within(tabs).getByRole('tab', {
      name: '状态'
    }));
    await canvas.findByRole('heading', {
      level: 3,
      name: '按状态看金额'
    });
    await waitFor(() => expect(aggregateCalls.current).toBeGreaterThan(asked));
    const second = aggregateCalls.current;
    await userEvent.click(within(tabs).getByRole('tab', {
      name: '出库'
    }));
    await canvas.findByRole('heading', {
      level: 3,
      name: '按仓库汇总'
    });
    await expect(aggregateCalls.current).toBe(second);

    // The reader's last tab is theirs: back on 状态, another board, and
    // this one again — on 状态.
    await userEvent.click(within(tabs).getByRole('tab', {
      name: '状态'
    }));
    await canvas.findByRole('heading', {
      level: 3,
      name: '按状态看金额'
    });
    await userEvent.click(await canvas.findByText('异常概览'));
    await waitFor(() => expect(canvas.queryByRole('tablist')).toBeNull());
    await userEvent.click(await canvas.findByText('出库概览'));
    await waitFor(() => expect(canvas.getByRole('tab', {
      name: '状态'
    })).toHaveAttribute('aria-selected', 'true'));
    await expect(canvas.queryByRole('heading', {
      level: 3,
      name: '待出库明细'
    })).toBeNull();
  }
}`,...H.parameters?.docs?.source},description:{story:`Screen E: tabs. Only the tab on screen runs — switching to the second
asks for its one panel and nothing else, switching back asks nothing —
and a board opened again, from the list, lands on the tab its reader
last read.`,...H.parameters?.docs?.description}}},U.parameters={...U.parameters,docs:{...U.parameters?.docs,source:{originalSource:`{
  ...DisplayTabs,
  decorators: [DESK],
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('heading', {
      level: 3,
      name: '按仓库汇总'
    });
    await startBuilding(canvasElement);
    await userEvent.click(canvas.getByRole('button', {
      name: zhCN['label.tabs.add']
    }));
    const name = await canvas.findByRole('textbox', {
      name: '标签页「标签页 3」的名字'
    });
    // Focused with its name selected: typing replaces it.
    await waitFor(() => expect(name).toHaveFocus());
    await userEvent.keyboard('异常{Enter}');
    // Being built, the bar is the tabs to arrange; the one on screen is
    // the one pressed in.
    const added = await canvas.findByRole('button', {
      name: '异常'
    });
    await expect(added).toHaveAttribute('aria-current', 'true');
    await expect(canvas.getByText('这个标签页还没有面板')).toBeVisible();

    // Back to the first tab, and a panel moved to the new one.
    await userEvent.click(canvas.getByRole('button', {
      name: '出库'
    }));
    await fromPanelMenu(canvasElement, '按仓库汇总', zhCN['label.panel.move-to-tab']);
    await screen.findByRole('menuitem', {
      name: '异常'
    });
    await userEvent.keyboard('{ArrowRight}');
    await waitFor(() => expect(document.activeElement?.textContent).toBe('状态'));
    await userEvent.keyboard('{ArrowDown}');
    await waitFor(() => expect(document.activeElement?.textContent).toBe('异常'));
    await userEvent.keyboard('{Enter}');
    await waitFor(() => expect(canvas.queryByRole('heading', {
      level: 3,
      name: '按仓库汇总'
    })).toBeNull());
    await userEvent.click(canvas.getByRole('button', {
      name: '异常'
    }));
    await expect(await canvas.findByRole('heading', {
      level: 3,
      name: '按仓库汇总'
    })).toBeVisible();

    // Reordered from the keyboard: the handle answers the arrows.
    const handle = canvas.getByRole('button', {
      name: '调整「异常」的顺序'
    });
    handle.focus();
    await userEvent.keyboard('{ArrowLeft}');
    await waitFor(() => expect(within(canvas.getByRole('list', {
      name: zhCN['label.tabs.name']
    })).getAllByRole('listitem').map(item => item.querySelector('[data-slot="dashboard-tab"]')?.textContent)).toEqual(['出库', '异常', '状态']));
  }
}`,...U.parameters?.docs?.source},description:{story:`Screen E while building: a tab added under the edit bar and named in
place, a panel moved to it from its 「⋯」, and the tab reordered from the
keyboard.`,...U.parameters?.docs?.description}}},W.parameters={...W.parameters,docs:{...W.parameters?.docs,source:{originalSource:`{
  ...DisplayPersonalViewOnSharedBoard,
  decorators: [DESK],
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await canvas.findAllByRole('table');
    const trigger = canvas.getByRole('button', {
      name: label('label.panel.menu', {
        title: '待出库明细'
      })
    });
    trigger.focus();
    await userEvent.keyboard('{Enter}');
    const menu = await screen.findByRole('menu');
    const item = within(menu).getByRole('menuitem', {
      name: zhCN['label.panel.export']
    });
    // Down the menu to it, the arrows being all a keyboard has here.
    for (let step = 0; step < 4 && document.activeElement !== item; step += 1) await userEvent.keyboard('{ArrowDown}');
    await expect(item).toHaveFocus();
    await userEvent.keyboard('{Enter}');
    const dialog = await screen.findByRole('dialog', {
      name: zhCN['label.export.title']
    });
    await expect(within(dialog).queryByRole('radio')).toBeNull();
    await expect(dialog.textContent).toMatch(/文件：待出库明细-\\d{4}-\\d{2}-\\d{2}\\.csv/);
    await waitFor(() => expect(within(dialog).getByRole('button', {
      name: zhCN['label.export.confirm']
    })).toHaveFocus());
    await userEvent.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    await waitFor(() => expect(trigger).toHaveFocus());
    await userEvent.click(canvas.getByRole('button', {
      name: label('label.panel.menu', {
        title: '按仓库汇总'
      })
    }));
    await expect(within(await screen.findByRole('menu')).queryByRole('menuitem', {
      name: zhCN['label.panel.export']
    })).toBeNull();
    await userEvent.keyboard('{Escape}');
  }
}`,...W.parameters?.docs?.source},description:{story:`「导出数据…」 from a record panel's 「⋯」, by keyboard alone (D22 运维): the
workbench's own export window over the panel's rows — named after the
panel, every row, no 「选中」 — and, as it closes, the keyboard back on
the 「⋯」 it was asked from. An analysis panel has no such item. Nothing
is exported: the file itself is asserted in jsdom
(test/dashboardPanelMenu.test.tsx).`,...W.parameters?.docs?.description}}},G.parameters={...G.parameters,docs:{...G.parameters?.docs,source:{originalSource:`{
  ...DisplayPersonalViewOnSharedBoard,
  decorators: [DESK],
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    const heading = await canvas.findByRole('heading', {
      level: 3,
      name: '我盯的大额单'
    });
    const panel = heading.closest<HTMLElement>('[data-slot="dashboard-panel"]')!;
    await waitFor(() => expect(panel.querySelector('[data-slot="panel-warning"]')).not.toBeNull());
    await startBuilding(canvasElement);
    const trigger = canvas.getByRole('button', {
      name: label('label.panel.menu', {
        title: '我盯的大额单'
      })
    });
    await fromPanelMenu(canvasElement, '我盯的大额单', zhCN['label.panel.copy-shared']);
    const dialog = await screen.findByRole('dialog', {
      name: zhCN['label.panel.copy-shared.heading']
    });
    await expect(within(dialog).queryByRole('radio')).toBeNull();
    await expect(within(dialog).getByRole('textbox', {
      name: zhCN['label.save.title']
    })).toHaveValue('我盯的大额单');
    await userEvent.click(within(dialog).getByRole('button', {
      name: zhCN['label.panel.copy-shared.submit']
    }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    await waitFor(() => expect(panel.querySelector('[data-slot="panel-warning"]')).toBeNull());
    await expect(canvas.getByRole('heading', {
      level: 3,
      name: '我盯的大额单'
    })).toBeVisible();
    await waitFor(() => expect(trigger).toHaveFocus());
  }
}`,...G.parameters?.docs?.source},description:{story:`「复制为共享视图并替换…」 (D22 B): a shared board's panel on the author's
personal view wears the warning that not every reader can open it; while
the board is built its 「⋯」 offers the copy, the dialog says what happens
— no audience to pick, the view's own name to start from — and pressing
it points the panel at a shared copy: the warning goes, the name stays,
and the keyboard is back on the 「⋯」.`,...G.parameters?.docs?.description}}},K.parameters={...K.parameters,docs:{...K.parameters?.docs,source:{originalSource:`{
  ...DisplayBuilding,
  decorators: [DESK],
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('heading', {
      level: 3,
      name: '按仓库汇总'
    });
    await startBuilding(canvasElement);
    const add = canvas.getByRole('button', {
      name: zhCN['label.dashboard.add']
    });
    const menuOf = (title: string) => canvas.getByRole('button', {
      name: label('label.panel.menu', {
        title
      })
    });
    const items: [HTMLElement, keyof typeof zhCN][] = [[add, 'label.dashboard.add.saved-view'], [add, 'label.dashboard.add.markdown'], [add, 'label.dashboard.add.image'], [add, 'label.dashboard.add.links'], [menuOf('按仓库汇总'), 'label.panel.replace'], [menuOf('值班手册'), 'label.panel.edit-content']];
    for (const [trigger, key] of items) {
      trigger.focus();
      await userEvent.keyboard('{Enter}');
      const menu = await screen.findByRole('menu');
      const item = within(menu).getByRole('menuitem', {
        name: zhCN[key]
      });
      item.focus();
      await userEvent.keyboard('{Enter}');
      const dialog = await screen.findByRole('dialog');
      // The menu gone for good, out of the document and not only out of the
      // accessibility tree: the end of its exit is when it used to take the
      // keyboard back.
      await waitFor(() => expect(document.querySelector('[data-slot="dropdown-menu-content"]')).toBeNull());
      await waitFor(() => expect(dialog.contains(document.activeElement) && document.activeElement?.matches('input, textarea'), key).toBe(true));
      const box = document.activeElement as HTMLInputElement;
      await userEvent.keyboard('abc');
      await expect(box.value, key).toContain('abc');
      await userEvent.keyboard('{Escape}');
      await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
      await waitFor(() => expect(trigger, key).toHaveFocus());
    }
  }
}`,...K.parameters?.docs?.source},description:{story:`Every menu item that opens a dialog hands it the keyboard (U-01): the
menu closes *after* the dialog has opened, and it used to take the
keyboard back to its trigger then — behind the modal, on the board it
covers, so what was typed went nowhere. Each item is chosen by keyboard,
the menu is let go all the way, and the keyboard is still in the dialog
and typing reaches its first box; Escape brings it back to the trigger.`,...K.parameters?.docs?.description}}}})))()}J();export{N as BuildFromEmpty,P as CancelReverts,G as CopyPersonalViewAsShared,z as CreateOwnedAnalysis,K as MenuItemsHandTheKeyboardToTheirDialog,R as NarrowReorderByKeyboard,I as NoGripsUntilBuilding,V as OverrideToPieAndReset,W as PanelExportWindow,B as PromoteOwnedAnalysis,L as RemoveThenUndo,F as SystemDashboardHasNoEdit,U as TabsBuilt,H as TabsRunOnlyTheTabShown,q as __namedExportsOrder,A as default};
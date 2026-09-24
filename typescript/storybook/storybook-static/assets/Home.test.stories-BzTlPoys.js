import{n as e}from"./rolldown-runtime-DkW27tQK.js";import{R as t,z as n}from"./styles-Dpj2y9Rj.js";import{h as r,l as i,o as a}from"./chartDom-C6NvYOT8.js";import{a as o,i as s,r as c}from"./readTable-DOunjEkP.js";import{i as l,r as u,t as d}from"./Home.stories-fnz4KpG0.js";function f(e){let t=[...e.querySelectorAll(`thead tr:first-child > th`)].filter(e=>e.getBoundingClientRect().width>0),n=[];for(let e of t.filter(e=>e.hasAttribute(`data-pin`))){let r=e.getBoundingClientRect();for(let i of t){if(i===e)continue;let t=i.getBoundingClientRect(),a=Math.min(r.right,t.right)-Math.max(r.left,t.left);a>.5&&n.push(`${e.textContent} over ${i.textContent}: ${a.toFixed(1)}`)}}return n}function p(e){return[...e.querySelectorAll(`.react-grid-item`)].map(e=>({box:e.getBoundingClientRect(),title:e.querySelector(`[data-slot="panel-title"]`)?.textContent??``})).sort((e,t)=>e.box.top-t.box.top||e.box.left-t.box.left).map(e=>e.title)}var m,h,g,_,v,y,b,x,S,C;function w(){return(w=e((()=>{t(),u(),s(),i(),{expect:m,screen:h,userEvent:g,waitFor:_,within:v}=__STORYBOOK_MODULE_TEST__,y={...l,title:`View Engine/首页/回归`,tags:[`!dev`,`!autodocs`,`test`],parameters:{...l.parameters,docs:{description:{component:`The home page over the fixture, on its fixed morning.

What can break here without a line of the page changing: the dashboard is
a system view of a dashboard definition whose panels reference saved views
of another definition and one of that definition's own system views, and
a rule change in View Engine can refuse any of those references, or the
relative dates they count by. So every panel is asserted to have drawn
its answer — the numbers the fixture's executions give on that morning —
and the page to fit its area sideways.`}}}},b=[`活动失败`,`其中不可恢复`,`今日新增`,`本月每日新增失败`,`按状态分布`,`最近的活动失败`,`活动失败最多的处理器`],x={...d,play:async({canvasElement:e})=>{let t=v(e);await m(t.getByRole(`heading`,{level:1,name:`运营概览`})).toBeVisible(),await m(t.getByText(`2026年9月22日星期二`)).toBeVisible();let n=e=>t.getByRole(`group`,{name:e});await _(()=>{for(let e of b)m(n(e)).toBeInTheDocument()}),await m(e.querySelector(`[data-slot="panel-failed"], [data-slot="panel-unavailable"]`)).toBeNull();let i=e=>n(e).querySelector(`[data-slot="metric-card"]`)?.textContent;await _(()=>m(i(`活动失败`)).toBe(`145`)),await m(i(`其中不可恢复`)).toBe(`37`),await m(i(`今日新增`)).toBe(`4`);for(let e of[`活动失败`,`其中不可恢复`,`今日新增`]){let t=n(e).closest(`[data-slot="dashboard-panel"]`);await m(t.getBoundingClientRect().height).toBeLessThanOrEqual(80),await m(n(e).scrollHeight).toBeLessThanOrEqual(n(e).clientHeight)}let s=e=>a(n(e)).length;await _(()=>m(s(`本月每日新增失败`)).toBe(22)),await _(()=>m(s(`按状态分布`)).toBe(3)),await _(()=>m(s(`活动失败最多的处理器`)).toBe(6));let l=n(`活动失败最多的处理器`),u=l.querySelector(`[data-slot="chart-plot"] svg`).getBoundingClientRect(),d=await _(()=>{let e=r(l);return m(e.length).toBeGreaterThan(0),e.map(e=>e.getBoundingClientRect())});for(let e of d)await m(e.left).toBeGreaterThanOrEqual(u.left-1),await m(e.right).toBeLessThanOrEqual(u.right+1);let p=await c(n(`最近的活动失败`));await _(()=>m(o(p,`处理器`)).toHaveLength(10)),await m(o(p,`最近更新`)[0]).toBe(`2026年9月22日 06:00:00`);let h=p.closest(`[data-slot="record-table"]`);await _(()=>m(h.hasAttribute(`data-overflowing`)).toBe(!0)),await m(f(p)).toEqual([]);let g=e.querySelector(`.story-app-page`);await _(()=>m(g.scrollWidth).toBeLessThanOrEqual(g.clientWidth)),await m(document.documentElement.scrollWidth).toBeLessThanOrEqual(document.documentElement.clientWidth)}},S={...d,name:`编辑首页`,play:async({canvasElement:e})=>{let t=v(e);await _(()=>m(p(e)).toEqual(m.arrayContaining(b))),await g.click(await t.findByRole(`button`,{name:n[`label.dashboard.edit`]})),await m(await t.findByRole(`region`,{name:n[`label.dashboard.editing`]})).toBeVisible(),await g.click(t.getByRole(`button`,{name:n[`label.dashboard.add`]})),await g.click(await h.findByRole(`menuitem`,{name:n[`label.dashboard.add.heading`]}));let r=await t.findByRole(`textbox`,{name:n[`label.panel.heading-input`]});await g.clear(r),await g.type(r,`本周重点{Enter}`),await _(()=>m(p(e)).toContain(`本周重点`)),await g.click(t.getByRole(`button`,{name:n[`label.dashboard.save`]})),await g.click(await h.findByRole(`button`,{name:n[`label.save.shared-confirm`]})),await _(()=>m(t.queryByRole(`region`,{name:n[`label.dashboard.editing`]})).toBeNull()),await m(p(e)).toContain(`本周重点`);let i=t.getByRole(`button`,{name:n[`label.dashboard.edit`]});await _(()=>m(i).toHaveFocus())}},C=[`Fixture`,`Editing`],x.parameters={...x.parameters,docs:{...x.parameters?.docs,source:{originalSource:`{
  ...DisplayFixture,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    // The host's own heading, dated by the runtime's clock and zone.
    await expect(canvas.getByRole('heading', {
      level: 1,
      name: '运营概览'
    })).toBeVisible();
    await expect(canvas.getByText('2026年9月22日星期二')).toBeVisible();

    // Every panel resolved its reference and drew; none says it could not.
    const panel = (name: string) => canvas.getByRole('group', {
      name
    });
    await waitFor(() => {
      for (const name of PANELS) expect(panel(name)).toBeInTheDocument();
    });
    await expect(canvasElement.querySelector('[data-slot="panel-failed"], [data-slot="panel-unavailable"]')).toBeNull();

    // The three counts.
    const card = (name: string) => panel(name).querySelector('[data-slot="metric-card"]')?.textContent;
    await waitFor(() => expect(card('活动失败')).toBe('145'));
    await expect(card('其中不可恢复')).toBe('37');
    await expect(card('今日新增')).toBe('4');
    // Each is a tile one grid row tall, and its number fits it: nothing to
    // scroll, where at two rows a tile was mostly empty and at one the
    // workbench's 3xl number overflowed.
    for (const name of ['活动失败', '其中不可恢复', '今日新增']) {
      const tile = panel(name).closest<HTMLElement>('[data-slot="dashboard-panel"]')!;
      await expect(tile.getBoundingClientRect().height).toBeLessThanOrEqual(80);
      await expect(panel(name).scrollHeight).toBeLessThanOrEqual(panel(name).clientHeight);
    }

    // One bar per day of September so far, one per status, and the
    // processors ahead first.
    const bars = (name: string) => drawnMarks(panel(name)).length;
    await waitFor(() => expect(bars('本月每日新增失败')).toBe(22));
    await waitFor(() => expect(bars('按状态分布')).toBe(3));
    await waitFor(() => expect(bars('活动失败最多的处理器')).toBe(6));
    // Each processor's count is written past its bar's end, and the longest
    // bar's stays whole inside the drawing: 「59.6万」 on the live service
    // lost its 「万」 to the frame (audit P0-5).
    const processors = panel('活动失败最多的处理器');
    const frame = processors.querySelector('[data-slot="chart-plot"] svg')!.getBoundingClientRect();
    const counts = await waitFor(() => {
      const found = valueLabels(processors);
      expect(found.length).toBeGreaterThan(0);
      return found.map(label => label.getBoundingClientRect());
    });
    for (const box of counts) {
      await expect(box.left).toBeGreaterThanOrEqual(frame.left - 1);
      await expect(box.right).toBeLessThanOrEqual(frame.right + 1);
    }

    // The newest active failures, newest first.
    const table = await findDataTable(panel('最近的活动失败'));
    await waitFor(() => expect(readColumn(table, '处理器')).toHaveLength(10));
    await expect(readColumn(table, '最近更新')[0]).toBe('2026年9月22日 06:00:00');

    // No column sits under a held one where the panel is read, at rest.
    // Five columns overflow this seven-twelfths panel, and a last column
    // held on the right sat over the middle before anything had scrolled —
    // 「已重试次数」 read as 「已重试次」 — while the pin cap (D17-4) kept
    // it, one column being well under half the port. A panel holds no end
    // (\`holdEnd\`), so every header is read whole. The overflow is asserted
    // first because it is what gives the measurement its meaning: a table
    // that fits covers nothing whatever it pins.
    const port = table.closest<HTMLElement>('[data-slot="record-table"]')!;
    await waitFor(() => expect(port.hasAttribute('data-overflowing')).toBe(true));
    await expect(covered(table)).toEqual([]);

    // The page fits its area sideways: only a genuinely taller page scrolls,
    // and only down. Waited for, because the grid learns its width from its
    // container once it is on screen and lays out at a default until then.
    const area = canvasElement.querySelector<HTMLElement>('.story-app-page')!;
    await waitFor(() => expect(area.scrollWidth).toBeLessThanOrEqual(area.clientWidth));
    await expect(document.documentElement.scrollWidth).toBeLessThanOrEqual(document.documentElement.clientWidth);
  }
}`,...x.parameters?.docs?.source}}},S.parameters={...S.parameters,docs:{...S.parameters?.docs,source:{originalSource:`{
  ...DisplayFixture,
  name: '编辑首页',
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await waitFor(() => expect(titles(canvasElement)).toEqual(expect.arrayContaining(PANELS)));
    await userEvent.click(await canvas.findByRole('button', {
      name: zhCN['label.dashboard.edit']
    }));
    await expect(await canvas.findByRole('region', {
      name: zhCN['label.dashboard.editing']
    })).toBeVisible();
    await userEvent.click(canvas.getByRole('button', {
      name: zhCN['label.dashboard.add']
    }));
    await userEvent.click(await screen.findByRole('menuitem', {
      name: zhCN['label.dashboard.add.heading']
    }));
    const heading = await canvas.findByRole('textbox', {
      name: zhCN['label.panel.heading-input']
    });
    await userEvent.clear(heading);
    await userEvent.type(heading, '本周重点{Enter}');
    await waitFor(() => expect(titles(canvasElement)).toContain('本周重点'));

    // The board is the team's: 保存 asks before it updates it for everyone.
    await userEvent.click(canvas.getByRole('button', {
      name: zhCN['label.dashboard.save']
    }));
    await userEvent.click(await screen.findByRole('button', {
      name: zhCN['label.save.shared-confirm']
    }));
    await waitFor(() => expect(canvas.queryByRole('region', {
      name: zhCN['label.dashboard.editing']
    })).toBeNull());
    await expect(titles(canvasElement)).toContain('本周重点');
    const edit = canvas.getByRole('button', {
      name: zhCN['label.dashboard.edit']
    });
    await waitFor(() => expect(edit).toHaveFocus());
  }
}`,...S.parameters?.docs?.source},description:{story:`The editable tier end to end (D22): the team's home board is built where
it is read. 「编辑」 brings up the edit bar; a heading added is named in
place; 「保存」 asks before it updates the board everyone reads, saves it,
and hands the keyboard back to 「编辑」.`,...S.parameters?.docs?.description}}}})))()}w();export{S as Editing,x as Fixture,C as __namedExportsOrder,y as default};
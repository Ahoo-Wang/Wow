import{n as e}from"./rolldown-runtime-DkW27tQK.js";import{l as t,o as n}from"./chartDom-C6NvYOT8.js";import{a as r,i}from"./readTable-DOunjEkP.js";import{i as a,r as o,t as s}from"./ProductPricingEvents.stories-DHNBjFrF.js";import{i as c,r as l,t as u}from"./productPricingService-D1xvgVVs.js";var d,f,p,m,h,g,_;function v(){return(v=e((()=>{o(),l(),i(),t(),{expect:d,userEvent:f,waitFor:p,within:m}=__STORYBOOK_MODULE_TEST__,h={...a,title:`View Engine/真实后端/商品定价/事件流分析台/回归`,tags:[`!dev`,`!autodocs`,`test`],parameters:{...a.parameters,docs:{description:{component:`The product pricing event stream console against a recorded service
instead of a live one.

The display story stays off CI because a live service answers differently
every time. Its data is what varies; the definition, the system views and
the way the console reads an event stream — its events inside an array a
condition reaches by element match and an analysis expands — do not.`}}},args:{host:u},beforeEach:c},g={...s,play:async({canvasElement:e})=>{let t=m(e),i=e=>t.getByRole(`button`,{name:RegExp(`^${e}`)});for(let e of[`最近的事件`,`定价历史`,`状态变更`,`事件类型分布`,`每月事件量`,`每日事件量`,`改动最多的定价`])await d(await t.findByRole(`button`,{name:RegExp(`^${e}`)})).toBeVisible();await d(i(`最近的事件`)).toHaveAttribute(`aria-current`,`true`);let a=await t.findByRole(`table`);await p(()=>d(r(a,`定价 ID`).slice(0,3)).toEqual([`PP-SK-1000J-40812-10`,`PP-SK-1000J-40812-1`,`PP-SK-QSH6-20000-10`])),await d(r(a,`事件`)).toHaveLength(16),await d(new Set(r(a,`事件`))).toEqual(new Set([`保存定价`,`变更状态`,`应用默认标签`])),(e=>[...a.querySelectorAll(`tbody tr`)].find(t=>t.textContent?.includes(e)))(`0VKveurV00h200V`).focus(),await f.keyboard(`{Enter}`);let o=await m(document.body).findByRole(`dialog`);await p(()=>d(o).toBeVisible());let[s]=o.querySelectorAll(`[data-slot="detail-element"]`);await d(m(s).getByText(`保存定价`)).toBeVisible(),await d(await m(s).findByText(/108\.90/)).toBeVisible(),await f.keyboard(`{Escape}`),await p(()=>d(m(document.body).queryByRole(`dialog`)).toBeNull()),await f.click(i(`定价历史`)),await p(()=>d(r(t.getByRole(`table`),`定价 ID`).slice(0,10)).toEqual([...Array.from({length:9},()=>`PP-SK-10001-10000-1`),`PP-SK-1000J-40812-1`])),await d(r(t.getByRole(`table`),`版本`).slice(0,3)).toEqual([`1`,`2`,`3`]),await f.click(i(`状态变更`)),await p(()=>d(r(t.getByRole(`table`),`版本`)).toEqual([`9`,`6`,`3`])),await f.click(i(`事件类型分布`)),await p(()=>d(n(e)).toHaveLength(3)),await f.click(i(`改动最多的定价`)),await p(()=>d(r(t.getByRole(`table`),`定价 ID`)).toEqual([`PP-SK-10001-10000-1`,`PP-SK-QSH6-20000-10`]));let[c]=r(t.getByRole(`table`),`最近一次改动`);await d(c).toMatch(/\d{4}年\d{1,2}月\d{1,2}日/),await d(c).not.toMatch(/^\d{12,}$/)}},_=[`EventStreamConsole`],g.parameters={...g.parameters,docs:{...g.parameters?.docs,source:{originalSource:`{
  ...DisplayEventStreamConsole,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    const view = (title: string) => canvas.getByRole('button', {
      name: new RegExp(\`^\${title}\`)
    });

    // The definition is admitted: every system view lists, and the first
    // opens.
    for (const title of ['最近的事件', '定价历史', '状态变更', '事件类型分布', '每月事件量', '每日事件量', '改动最多的定价']) await expect(await canvas.findByRole('button', {
      name: new RegExp(\`^\${title}\`)
    })).toBeVisible();
    await expect(view('最近的事件')).toHaveAttribute('aria-current', 'true');

    // The newest events first, each stream read by its event's type in the
    // type's words — Wow's own tags event too, which the schema omits.
    const table = await canvas.findByRole('table');
    await waitFor(() => expect(readColumn(table, '定价 ID').slice(0, 3)).toEqual(['PP-SK-1000J-40812-10', 'PP-SK-1000J-40812-1', 'PP-SK-QSH6-20000-10']));
    await expect(readColumn(table, '事件')).toHaveLength(16);
    await expect(new Set(readColumn(table, '事件'))).toEqual(new Set(['保存定价', '变更状态', '应用默认标签']));

    // One stream read whole: its saved price in the detail, as money.
    const rowOf = (id: string) => [...table.querySelectorAll<HTMLElement>('tbody tr')].find(row => row.textContent?.includes(id))!;
    rowOf('0VKveurV00h200V').focus();
    await userEvent.keyboard('{Enter}');
    const detail = await within(document.body).findByRole('dialog');
    await waitFor(() => expect(detail).toBeVisible());
    const [event] = detail.querySelectorAll<HTMLElement>('[data-slot="detail-element"]');
    await expect(within(event!).getByText('保存定价')).toBeVisible();
    await expect(await within(event!).findByText(/108\\.90/)).toBeVisible();
    await userEvent.keyboard('{Escape}');
    await waitFor(() => expect(within(document.body).queryByRole('dialog')).toBeNull());

    // The history template, still unfilled, reads every history in order.
    await userEvent.click(view('定价历史'));
    await waitFor(() => expect(readColumn(canvas.getByRole('table'), '定价 ID').slice(0, 10)).toEqual([...Array.from({
      length: 9
    }, () => 'PP-SK-10001-10000-1'), 'PP-SK-1000J-40812-1']));
    await expect(readColumn(canvas.getByRole('table'), '版本').slice(0, 3)).toEqual(['1', '2', '3']);

    // A condition on an event reaches into the stream's array.
    await userEvent.click(view('状态变更'));
    await waitFor(() => expect(readColumn(canvas.getByRole('table'), '版本')).toEqual(['9', '6', '3']));

    // The analysis counts events, one bar per type the streams hold.
    await userEvent.click(view('事件类型分布'));
    await waitFor(() => expect(drawnMarks(canvasElement)).toHaveLength(3));

    // The pricings changed after they were created, the most first, and when
    // the last change was — a date, not epoch milliseconds.
    await userEvent.click(view('改动最多的定价'));
    await waitFor(() => expect(readColumn(canvas.getByRole('table'), '定价 ID')).toEqual(['PP-SK-10001-10000-1', 'PP-SK-QSH6-20000-10']));
    const [latest] = readColumn(canvas.getByRole('table'), '最近一次改动');
    await expect(latest).toMatch(/\\d{4}年\\d{1,2}月\\d{1,2}日/);
    await expect(latest).not.toMatch(/^\\d{12,}$/);
  }
}`,...g.parameters?.docs?.source}}}})))()}v();export{g as EventStreamConsole,_ as __namedExportsOrder,h as default};
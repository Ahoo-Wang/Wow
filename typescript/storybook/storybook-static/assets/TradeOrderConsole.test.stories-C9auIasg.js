import{n as e}from"./rolldown-runtime-DkW27tQK.js";import{l as t,o as n}from"./chartDom-C6NvYOT8.js";import{a as r,c as i,i as a,t as o}from"./readTable-DOunjEkP.js";import{i as s,r as c,t as l}from"./TradeOrderConsole.stories-CU_Wq65T.js";import{i as u,n as d,t as f}from"./tradeOrderService-L0DdXZZv.js";var p,m,h,g,_,v,y;function b(){return(b=e((()=>{c(),a(),d(),t(),{expect:p,userEvent:m,waitFor:h,within:g}=__STORYBOOK_MODULE_TEST__,_={...s,title:`View Engine/真实后端/交易订单/快照控制台/回归`,tags:[`!dev`,`!autodocs`,`test`],parameters:{...s.parameters,docs:{description:{component:`The trade order console against a recorded service instead of a live one.

The display story stays off CI because a live service answers differently
every time. Its data is what varies; the definition, the system views and
the way the console reads an order — its lines inside an array a cell
reads by their codes and an analysis expands — do not, and a rule change
in View Engine can break them without any service involved.`}}},args:{host:f},beforeEach:u},v={...l,play:async({canvasElement:e})=>{let t=g(e),a=e=>t.getByRole(`button`,{name:RegExp(`^${e}`)});for(let e of[`待处理`,`待付款`,`已取消`,`全部订单`,`按状态分布`,`每日下单`,`客户排行`,`商品排行`])await p(await t.findByRole(`button`,{name:RegExp(`^${e}`)})).toBeVisible();await p(a(`待处理`)).toHaveAttribute(`aria-current`,`true`);let s=await t.findByRole(`table`);await h(()=>p(r(s,`订单号`)).toEqual([`TO-1`,`TO-3`,`TO-6`])),await p(r(s,`订单状态`)).toEqual([`待评审`,`待修改`,`待评审`]),await p(r(s,`客户`)).toEqual([`华东机电`,`北方五金`,`南方电气`]),await p(o(i(s,`应付金额`))).toBe(2568);let[c,l]=r(s,`商品`);await p(c).toBe(`BTN-22R`),await p(l).toContain(`BTN-22R`),await p(l).toContain(`LMP-16W`),await p(l).not.toContain(`{`),await m.click(a(`待付款`)),await h(()=>p(r(t.getByRole(`table`),`订单号`)).toEqual([`TO-4`,`TO-2`]));let[u]=r(t.getByRole(`table`),`自动取消时间`);await p(u).toMatch(/\d{4}年\d{1,2}月\d{1,2}日/),await m.click(a(`已取消`)),await h(()=>p(r(t.getByRole(`table`),`订单号`)).toEqual([`TO-5`])),await m.click(a(`按状态分布`)),await h(()=>p(n(e)).toHaveLength(4)),await m.click(a(`每日下单`)),await h(()=>p(r(t.getByRole(`table`),`订单数`).map(Number)).toSatisfy(e=>e.reduce((e,t)=>e+t)===6));let[d]=r(t.getByRole(`table`),`日期`);await p(d).toMatch(/^2026年9月1[34]日$/),await m.click(a(`客户排行`)),await h(()=>p(r(t.getByRole(`table`),`客户`)).toEqual([`华东机电`,`北方五金`,`南方电气`])),await m.click(a(`商品排行`)),await h(()=>p(r(t.getByRole(`table`),`订单行`)).toEqual([`3`,`2`,`2`]));let f=t.getByRole(`table`);await p(r(f,`商品`).map(e=>e.split(` `)[0])).toEqual([`BTN-22R`,`LMP-16W`,`BTN-22G`]),await p(r(f,`数量`)).toEqual([`17`,`3`,`4`]),await p(r(f,`金额`).map(o)).toEqual([2040,792,390.72])}},y=[`SnapshotConsole`],v.parameters={...v.parameters,docs:{...v.parameters?.docs,source:{originalSource:`{
  ...DisplaySnapshotConsole,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    const view = (title: string) => canvas.getByRole('button', {
      name: new RegExp(\`^\${title}\`)
    });

    // The definition is admitted: every system view lists — the records and
    // the analyses in one list — and the first opens.
    for (const title of ['待处理', '待付款', '已取消', '全部订单', '按状态分布', '每日下单', '客户排行', '商品排行']) await expect(await canvas.findByRole('button', {
      name: new RegExp(\`^\${title}\`)
    })).toBeVisible();
    await expect(view('待处理')).toHaveAttribute('aria-current', 'true');

    // The queue: waiting on a review or a revision, the longest waiting
    // first, and what it is worth in the footer.
    const table = await canvas.findByRole('table');
    await waitFor(() => expect(readColumn(table, '订单号')).toEqual(['TO-1', 'TO-3', 'TO-6']));
    await expect(readColumn(table, '订单状态')).toEqual(['待评审', '待修改', '待评审']);
    await expect(readColumn(table, '客户')).toEqual(['华东机电', '北方五金', '南方电气']);
    await expect(amountOf(readTotal(table, '应付金额'))).toBe(2568);
    // The lines read as their model codes, never as the JSON of them.
    const [one, two] = readColumn(table, '商品');
    await expect(one).toBe('BTN-22R');
    await expect(two).toContain('BTN-22R');
    await expect(two).toContain('LMP-16W');
    await expect(two).not.toContain('{');

    // The unpaid: the one closest to cancelling itself first, with when.
    await userEvent.click(view('待付款'));
    await waitFor(() => expect(readColumn(canvas.getByRole('table'), '订单号')).toEqual(['TO-4', 'TO-2']));
    const [cancelsAt] = readColumn(canvas.getByRole('table'), '自动取消时间');
    await expect(cancelsAt).toMatch(/\\d{4}年\\d{1,2}月\\d{1,2}日/);
    await userEvent.click(view('已取消'));
    await waitFor(() => expect(readColumn(canvas.getByRole('table'), '订单号')).toEqual(['TO-5']));

    // The analysis is a view of the same workbench: one bar per status.
    await userEvent.click(view('按状态分布'));
    await waitFor(() => expect(drawnMarks(canvasElement)).toHaveLength(4));

    // Every order placed, by the day it was placed — all six, whichever
    // time zone the browser reads the days in.
    await userEvent.click(view('每日下单'));
    await waitFor(() => expect(readColumn(canvas.getByRole('table'), '订单数').map(Number)).toSatisfy((counts: number[]) => counts.reduce((a, b) => a + b) === 6));
    const [day] = readColumn(canvas.getByRole('table'), '日期');
    await expect(day).toMatch(/^2026年9月1[34]日$/);

    // The customers by what they ordered, the largest first.
    await userEvent.click(view('客户排行'));
    await waitFor(() => expect(readColumn(canvas.getByRole('table'), '客户')).toEqual(['华东机电', '北方五金', '南方电气']));

    // Lines, not orders: the ranking expands \`items\` and counts each line.
    await userEvent.click(view('商品排行'));
    await waitFor(() => expect(readColumn(canvas.getByRole('table'), '订单行')).toEqual(['3', '2', '2']));
    const ranked = canvas.getByRole('table');
    await expect(readColumn(ranked, '商品').map(name => name.split(' ')[0])).toEqual(['BTN-22R', 'LMP-16W', 'BTN-22G']);
    await expect(readColumn(ranked, '数量')).toEqual(['17', '3', '4']);
    await expect(readColumn(ranked, '金额').map(amountOf)).toEqual([2040, 792, 390.72]);
  }
}`,...v.parameters?.docs?.source}}}})))()}b();export{v as SnapshotConsole,y as __namedExportsOrder,_ as default};
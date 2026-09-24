import{n as e}from"./rolldown-runtime-DkW27tQK.js";import{l as t,o as n}from"./chartDom-C6NvYOT8.js";import{a as r,i}from"./readTable-DOunjEkP.js";import{i as a,n as o,r as s}from"./CustomerDataConsole.stories-Cyy8k4Kk.js";import{i as c,n as l,t as u}from"./customerService-DQ8v8sEZ.js";var d,f,p,m,h,g,_,v;function y(){return(y=e((()=>{s(),l(),i(),t(),{expect:d,userEvent:f,waitFor:p,within:m}=__STORYBOOK_MODULE_TEST__,h={...a,title:`View Engine/真实后端/客户/快照控制台/回归`,tags:[`!dev`,`!autodocs`,`test`],parameters:{...a.parameters,docs:{description:{component:`The customer console against a recorded service instead of a live one.

The display story stays off CI because a live service answers differently
every time. Its data is what varies; the definition, the system views and
the way the console reads a customer — its nested paths, its contacts in
an array a cell reads by name and an analysis expands — do not, and a rule
change in View Engine can break them without any service involved.`}}},args:{host:u},beforeEach:c},g=e=>n(e),_={...o,play:async({canvasElement:e})=>{let t=m(e),n=e=>t.getByRole(`button`,{name:RegExp(`^${e}`)});for(let e of[`全部客户`,`最近变更`,`公海客户`,`已禁用`,`按负责人分布`,`按行业分布`,`按租户分布`,`联系人 · 按决策角色`,`每日新增客户`])await d(await t.findByRole(`button`,{name:RegExp(`^${e}`)})).toBeVisible();await d(n(`全部客户`)).toHaveAttribute(`aria-current`,`true`);let i=await t.findByRole(`table`);await p(()=>d(r(i,`客户 ID`)).toEqual([`CUS-6`,`CUS-5`,`CUS-4`,`CUS-3`,`CUS-2`,`CUS-1`])),await d(r(i,`客户状态`)).toEqual([`正常`,`已禁用`,`正常`,`正常`,`正常`,`正常`]),await d(r(i,`负责人`)).toEqual([``,`sales-a`,``,`sales-b`,``,`sales-a`]);let a=r(i,`联系人`);await d(a[3]).toBe(`联系人丁`),await d(a[4]).toContain(`联系人乙`),await d(a[4]).toContain(`联系人丙`),await d(a.join(``)).not.toMatch(/[{}[\]]/),await d(r(i,`创建时间`)[0]).toMatch(/\d{4}年\d{1,2}月\d{1,2}日/),await f.click(n(`最近变更`)),await p(()=>d(r(t.getByRole(`table`),`客户 ID`)).toEqual([`CUS-1`,`CUS-5`,`CUS-2`])),await f.click(n(`公海客户`)),await p(()=>d(r(t.getByRole(`table`),`客户 ID`)).toEqual([`CUS-6`,`CUS-4`,`CUS-2`])),await f.click(n(`已禁用`)),await p(()=>d(r(t.getByRole(`table`),`客户 ID`)).toEqual([`CUS-5`])),await f.click(n(`按负责人分布`)),await p(()=>d(g(e)).toHaveLength(3)),await f.click(n(`按行业分布`)),await p(()=>d(r(t.getByRole(`table`),`所属行业`).sort()).toEqual([`工业传感器`,`智能制造`,`设备集成`].sort())),await f.click(n(`按租户分布`)),await p(()=>d(g(e)).toHaveLength(2)),await f.click(n(`联系人 · 按决策角色`)),await p(()=>d(g(e)).toHaveLength(2)),await f.click(n(`每日新增客户`)),await p(()=>d(r(t.getByRole(`table`),`客户数`)).toEqual([`2`,`2`,`2`])),await d(r(t.getByRole(`table`),`日期`)[0]).toMatch(/\d{4}年\d{1,2}月\d{1,2}日/)}},v=[`DataConsole`],_.parameters={..._.parameters,docs:{..._.parameters?.docs,source:{originalSource:`{
  ...DisplayDataConsole,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    const view = (title: string) => canvas.getByRole('button', {
      name: new RegExp(\`^\${title}\`)
    });

    // The definition is admitted: every system view lists — the records and
    // the analyses in one list — and the first opens.
    for (const title of ['全部客户', '最近变更', '公海客户', '已禁用', '按负责人分布', '按行业分布', '按租户分布', '联系人 · 按决策角色', '每日新增客户']) await expect(await canvas.findByRole('button', {
      name: new RegExp(\`^\${title}\`)
    })).toBeVisible();
    await expect(view('全部客户')).toHaveAttribute('aria-current', 'true');

    // The newest customers first, read through the nested snapshot paths.
    const table = await canvas.findByRole('table');
    await waitFor(() => expect(readColumn(table, '客户 ID')).toEqual(['CUS-6', 'CUS-5', 'CUS-4', 'CUS-3', 'CUS-2', 'CUS-1']));
    await expect(readColumn(table, '客户状态')).toEqual(['正常', '已禁用', '正常', '正常', '正常', '正常']);
    await expect(readColumn(table, '负责人')).toEqual(['', 'sales-a', '', 'sales-b', '', 'sales-a']);
    // A customer's contacts read by name, though the page asked for the
    // names alone — never as the JSON of them.
    const contacts = readColumn(table, '联系人');
    await expect(contacts[3]).toBe('联系人丁');
    await expect(contacts[4]).toContain('联系人乙');
    await expect(contacts[4]).toContain('联系人丙');
    await expect(contacts.join('')).not.toMatch(/[{}[\\]]/);
    // The creation time reads as a date, not as epoch milliseconds.
    await expect(readColumn(table, '创建时间')[0]).toMatch(/\\d{4}年\\d{1,2}月\\d{1,2}日/);

    // What changed since it was created, the latest change first.
    await userEvent.click(view('最近变更'));
    await waitFor(() => expect(readColumn(canvas.getByRole('table'), '客户 ID')).toEqual(['CUS-1', 'CUS-5', 'CUS-2']));

    // The public pool is the customers nobody owns.
    await userEvent.click(view('公海客户'));
    await waitFor(() => expect(readColumn(canvas.getByRole('table'), '客户 ID')).toEqual(['CUS-6', 'CUS-4', 'CUS-2']));
    await userEvent.click(view('已禁用'));
    await waitFor(() => expect(readColumn(canvas.getByRole('table'), '客户 ID')).toEqual(['CUS-5']));

    // The pool counts as one owner: three bars, the pool, sales-a, sales-b.
    await userEvent.click(view('按负责人分布'));
    await waitFor(() => expect(bars(canvasElement)).toHaveLength(3));

    // The customers with an industry, and what they add up to.
    await userEvent.click(view('按行业分布'));
    await waitFor(() => expect(readColumn(canvas.getByRole('table'), '所属行业').sort()).toEqual(['工业传感器', '智能制造', '设备集成'].sort()));
    await userEvent.click(view('按租户分布'));
    await waitFor(() => expect(bars(canvasElement)).toHaveLength(2));

    // The analysis expands the contacts and counts contacts, not customers.
    await userEvent.click(view('联系人 · 按决策角色'));
    await waitFor(() => expect(bars(canvasElement)).toHaveLength(2));

    // Three days of new customers, newest first, each read as a date.
    await userEvent.click(view('每日新增客户'));
    await waitFor(() => expect(readColumn(canvas.getByRole('table'), '客户数')).toEqual(['2', '2', '2']));
    await expect(readColumn(canvas.getByRole('table'), '日期')[0]).toMatch(/\\d{4}年\\d{1,2}月\\d{1,2}日/);
  }
}`,..._.parameters?.docs?.source}}}})))()}y();export{_ as DataConsole,v as __namedExportsOrder,h as default};
import{n as e}from"./rolldown-runtime-DkW27tQK.js";import{l as t,o as n}from"./chartDom-C6NvYOT8.js";import{a as r,i}from"./readTable-DOunjEkP.js";import{n as a,t as o}from"./recordedWowService-BeFmKqKo.js";import{i as s,r as c,t as l}from"./EventStreamConsole.stories-ylQTnWEE.js";function u(e){return e.replace(/(?<!^)([A-Z])/g,`_$1`).toLowerCase()}function d(e,t,n,r,i){let a=`${e}-v${t}`;return{id:a,contextName:`compensation-service`,aggregateName:`execution_failed`,header:{upstream_name:u(n)},aggregateId:e,tenantId:`(0)`,ownerId:``,spaceId:``,commandId:`${a}-command`,requestId:`${a}-command`,version:t,body:[{id:`${a}-event`,name:u(n),revision:`0.0.1`,bodyType:`${h}.${n}`,body:i}],createTime:m+r*6e4}}function f(){return a({host:p,resource:`execution_failed/event`,documents:g})}var p,m,h,g;function _(){return(_=e((()=>{o(),p=`https://event-stream.example.test`,m=Date.parse(`2026-09-18T08:00:00.000Z`),h=`me.ahoo.wow.compensation.api`,g=[d(`EF-1`,1,`ExecutionFailedCreated`,0,{error:{errorCode:`BadRequest`,errorMsg:`Inventory refused.`},recoverable:`UNKNOWN`}),d(`EF-1`,2,`CompensationPrepared`,3,{retryState:{retries:1}}),d(`EF-1`,3,`ExecutionFailedApplied`,4,{error:{errorCode:`BadRequest`,errorMsg:`Inventory refused.`}}),d(`EF-1`,4,`CompensationPrepared`,7,{retryState:{retries:2}}),d(`EF-1`,5,`ExecutionSuccessApplied`,8,{}),d(`EF-2`,1,`ExecutionFailedCreated`,1,{error:{errorCode:`Timeout`,errorMsg:`Payment gateway timed out.`},recoverable:`UNKNOWN`}),d(`EF-2`,2,`RecoverableMarked`,6,{recoverable:`UNRECOVERABLE`}),d(`EF-3`,1,`ExecutionFailedCreated`,5,{error:{errorCode:`BadRequest`,errorMsg:`Address missing.`},recoverable:`UNKNOWN`})]})))()}var v,y,b,x,S,C,w;function T(){return(T=e((()=>{c(),_(),i(),t(),{expect:v,userEvent:y,waitFor:b,within:x}=__STORYBOOK_MODULE_TEST__,S={...s,title:`View Engine/真实后端/补偿控制台/事件流分析台/回归`,tags:[`!dev`,`!autodocs`,`test`],parameters:{...s.parameters,docs:{description:{component:`The event stream console against a recorded service instead of a live one.

The display story stays off CI because a live service answers differently
every time. Its data is what varies; the definition, the system views and
the way the console reads an event stream — its events inside an array a
condition reaches by element match and an analysis expands — do not, and a
rule change in View Engine can break them without any service involved.`}}},args:{host:p},beforeEach:f},C={...l,play:async({canvasElement:e})=>{let t=x(e),i=e=>t.getByRole(`button`,{name:RegExp(`^${e}`)});for(let e of[`最近的事件`,`执行历史`,`重试成功`,`人工干预`,`事件类型分布`,`每月事件量`,`每日事件量`,`每日重试成功`,`重试最多的执行`])await v(await t.findByRole(`button`,{name:RegExp(`^${e}`)})).toBeVisible();await v(i(`最近的事件`)).toHaveAttribute(`aria-current`,`true`);let a=await t.findByRole(`table`);await b(()=>v(r(a,`事件流 ID`)).toEqual([`EF-1-v5`,`EF-1-v4`,`EF-2-v2`,`EF-3-v1`,`EF-1-v3`,`EF-1-v2`,`EF-2-v1`,`EF-1-v1`])),await v(r(a,`事件`)).toEqual([`重试成功`,`准备重试`,`标记可恢复性`,`首次失败`,`重试失败`,`准备重试`,`首次失败`,`首次失败`]),a.querySelector(`tbody tr:last-child`).focus(),await y.keyboard(`{Enter}`);let o=await x(document.body).findByRole(`dialog`);await b(()=>v(o).toBeVisible());let[s]=o.querySelectorAll(`[data-slot="detail-element"]`);await v(x(s).getByText(`第 1 项`)).toBeVisible(),await v(x(s).getByText(`首次失败`)).toBeVisible(),await v(x(s).getByText(`errorMsg`)).toBeVisible(),await v(await x(s).findByText(`Inventory refused.`)).toBeVisible(),await y.keyboard(`{Escape}`),await b(()=>v(x(document.body).queryByRole(`dialog`)).toBeNull()),await y.click(i(`执行历史`)),await b(()=>v(r(t.getByRole(`table`),`事件流 ID`)).toEqual([`EF-1-v1`,`EF-1-v2`,`EF-1-v3`,`EF-1-v4`,`EF-1-v5`,`EF-2-v1`,`EF-2-v2`,`EF-3-v1`])),await y.click(i(`重试成功`)),await b(()=>v(r(t.getByRole(`table`),`事件流 ID`)).toEqual([`EF-1-v5`])),await y.click(i(`事件类型分布`)),await b(()=>v(n(e)).toHaveLength(5)),await y.click(i(`重试最多的执行`)),await b(()=>v(r(t.getByRole(`table`),`执行 ID`)).toEqual([`EF-1`]));let[c]=r(t.getByRole(`table`),`最近一次重试`);await v(c).toMatch(/\d{4}年\d{1,2}月\d{1,2}日/),await v(c).not.toMatch(/^\d{12,}$/)}},w=[`EventStreamConsole`],C.parameters={...C.parameters,docs:{...C.parameters?.docs,source:{originalSource:`{
  ...DisplayEventStreamConsole,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    const view = (title: string) => canvas.getByRole('button', {
      name: new RegExp(\`^\${title}\`)
    });

    // The definition is admitted: every system view lists — the records and
    // the analyses in one list — and the first opens.
    for (const title of ['最近的事件', '执行历史', '重试成功', '人工干预', '事件类型分布', '每月事件量', '每日事件量', '每日重试成功', '重试最多的执行']) await expect(await canvas.findByRole('button', {
      name: new RegExp(\`^\${title}\`)
    })).toBeVisible();
    await expect(view('最近的事件')).toHaveAttribute('aria-current', 'true');

    // The newest events first, whichever execution appended them.
    const table = await canvas.findByRole('table');
    await waitFor(() => expect(readColumn(table, '事件流 ID')).toEqual(['EF-1-v5', 'EF-1-v4', 'EF-2-v2', 'EF-3-v1', 'EF-1-v3', 'EF-1-v2', 'EF-2-v1', 'EF-1-v1']));
    // Each stream reads by what happened in it — its events by type, in the
    // type's words — though the page asked for the types alone.
    await expect(readColumn(table, '事件')).toEqual(['重试成功', '准备重试', '标记可恢复性', '首次失败', '重试失败', '准备重试', '首次失败', '首次失败']);

    // One stream read whole: its event laid out in the detail — the type,
    // the declared fields, and the payload no field declares, key by key.
    table.querySelector<HTMLElement>('tbody tr:last-child')!.focus();
    await userEvent.keyboard('{Enter}');
    const detail = await within(document.body).findByRole('dialog');
    await waitFor(() => expect(detail).toBeVisible());
    const [event] = detail.querySelectorAll<HTMLElement>('[data-slot="detail-element"]');
    await expect(within(event!).getByText('第 1 项')).toBeVisible();
    await expect(within(event!).getByText('首次失败')).toBeVisible();
    await expect(within(event!).getByText('errorMsg')).toBeVisible();
    await expect(await within(event!).findByText('Inventory refused.')).toBeVisible();
    await userEvent.keyboard('{Escape}');
    await waitFor(() => expect(within(document.body).queryByRole('dialog')).toBeNull());

    // The history template, still unfilled, reads every history in order.
    await userEvent.click(view('执行历史'));
    await waitFor(() => expect(readColumn(canvas.getByRole('table'), '事件流 ID')).toEqual(['EF-1-v1', 'EF-1-v2', 'EF-1-v3', 'EF-1-v4', 'EF-1-v5', 'EF-2-v1', 'EF-2-v2', 'EF-3-v1']));

    // A condition on an event reaches into the stream's array.
    await userEvent.click(view('重试成功'));
    await waitFor(() => expect(readColumn(canvas.getByRole('table'), '事件流 ID')).toEqual(['EF-1-v5']));

    // The analysis counts events, one bar per type the streams hold.
    await userEvent.click(view('事件类型分布'));
    await waitFor(() => expect(drawnMarks(canvasElement)).toHaveLength(5));

    // The execution retried most, and when it last was — the latest of its
    // retry events' times, read as a date and not as epoch milliseconds.
    await userEvent.click(view('重试最多的执行'));
    await waitFor(() => expect(readColumn(canvas.getByRole('table'), '执行 ID')).toEqual(['EF-1']));
    const [latest] = readColumn(canvas.getByRole('table'), '最近一次重试');
    await expect(latest).toMatch(/\\d{4}年\\d{1,2}月\\d{1,2}日/);
    await expect(latest).not.toMatch(/^\\d{12,}$/);
  }
}`,...C.parameters?.docs?.source}}}})))()}T();export{C as EventStreamConsole,w as __namedExportsOrder,S as default};
import{n as e}from"./rolldown-runtime-DkW27tQK.js";import{Es as t,R as n,Ts as r,z as i}from"./styles-Dpj2y9Rj.js";import{l as a,o}from"./chartDom-C6NvYOT8.js";import{a as s,c,i as l}from"./readTable-DOunjEkP.js";import{n as u,t as d}from"./recordedWowService-BeFmKqKo.js";import{i as f,r as p,t as m}from"./DataConsole.stories-CeW_AaFR.js";function h(e,t,n,r,i,a,o={errorCode:`BadRequest`,errorMsg:`Inventory refused.`}){let s=b+i*6e4;return{aggregateId:e,firstEventTime:b,eventTime:s,state:{id:e,status:t,recoverable:n,isRetryable:t!==`SUCCEEDED`,isBelowRetryThreshold:r<3,function:{contextName:`order-service`,processorName:a,name:`onOrderCreated`,functionKind:`EVENT`},eventId:{id:`${e}-event`,version:1,aggregateId:{contextName:`order-service`,aggregateName:`order`,aggregateId:`order-${e}`}},error:o,retrySpec:{maxRetries:3,minBackoff:180,executionTimeout:120},retryState:{retries:r,retryAt:s,nextRetryAt:s+18e4,timeoutAt:s+12e4}}}}function g(){return u({host:y,resource:`execution_failed/snapshot`,documents:x,command:_})}function _(e,t,n){let[,r,i,a]=t.split(`/`);if(r!==`execution_failed`)return;let o=e.find(e=>e.aggregateId===i)?.state;if(!o)return v(`NotFound`,`No execution ${i}.`);switch(a){case`prepare_compensation`:return o.isRetryable!==!0||o.isBelowRetryThreshold!==!0?v(`IllegalState`,`Retry threshold reached.`):(o.status=`PREPARED`,S);case`force_prepare_compensation`:return o.isRetryable===!0?(o.status=`PREPARED`,S):v(`IllegalState`,`Not retryable.`);case`mark_recoverable`:return o.recoverable=n.recoverable,S}}function v(e,t){return{errorCode:e,errorMsg:t}}var y,b,x,S;function C(){return(C=e((()=>{d(),y=`https://compensation.example.test`,b=Date.parse(`2026-09-18T08:00:00.000Z`),x=[h(`EF-1`,`FAILED`,`UNKNOWN`,2,1,`OrderSaga`),h(`EF-2`,`PREPARED`,`RECOVERABLE`,1,5,`PaymentSaga`,{errorCode:`Timeout`,errorMsg:`Payment gateway timed out.`}),h(`EF-3`,`SUCCEEDED`,`RECOVERABLE`,1,3,`OrderSaga`),h(`EF-4`,`FAILED`,`UNRECOVERABLE`,3,2,`InventorySaga`),h(`EF-5`,`FAILED`,`UNKNOWN`,4,4,`OrderSaga`)],S={errorCode:`Ok`,errorMsg:``}})))()}async function w(e,t){await E.click(e.getByRole(`button`,{name:`${t} 的操作`}));let n=await O(document.body).findByRole(`menu`);return await D(()=>T(n).toBeVisible()),{menu:n,item:e=>O(n).getByRole(`menuitem`,{name:e})}}var T,E,D,O,k,A,j,M,N,P;function F(){return(F=e((()=>{t(),n(),p(),C(),l(),a(),{expect:T,userEvent:E,waitFor:D,within:O}=__STORYBOOK_MODULE_TEST__,k={...f,title:`View Engine/真实后端/补偿控制台/快照控制台/回归`,tags:[`!dev`,`!autodocs`,`test`],parameters:{...f.parameters,docs:{description:{component:`The compensation console against a recorded service instead of a live one.

The display story stays off CI because a live service answers differently
every time. Its data is what varies; the definition, the system views, the
row and bulk commands and the way the console reads a snapshot do not, and
a rule change in View Engine can break them without any service involved.
This runs the same console over a fixed set of executions — whose
commands change them the way the service does — so that such a change
fails here instead of in front of someone opening the catalog.`}}},args:{host:y},beforeEach:g},A=e=>e.getAttribute(`aria-disabled`)!==`true`&&!e.hasAttribute(`data-disabled`),j={...m,play:async({canvasElement:e})=>{let t=O(e);for(let e of[`活动中`,`不可重试`,`不可恢复`,`已成功`,`全部`,`按状态分布`,`活动失败 · 按处理器`,`每日新增失败`])await T(await t.findByRole(`button`,{name:RegExp(`^${e}`)})).toBeVisible();await T(t.getByRole(`button`,{name:/^活动中/})).toHaveAttribute(`aria-current`,`true`);let n=await t.findByRole(`table`);await D(()=>T(s(n,`ID`)).toEqual([`EF-2`,`EF-5`,`EF-4`,`EF-1`])),await T(s(n,`状态`)).toEqual([`已准备重试`,`失败`,`失败`,`失败`]),await T(c(n,`已重试次数`).replace(/\D/g,``)).toBe(`10`);let r=await w(t,`EF-4`);await T(A(r.item(`重试`))).toBe(!1),await T(A(r.item(`强制重试`))).toBe(!0),await T(A(r.item(`不可恢复`))).toBe(!1),await E.keyboard(`{Escape}`),await D(()=>T(O(document.body).queryByRole(`menu`)).toBeNull());let i=await w(t,`EF-1`);await E.click(i.item(`重试`));let a=await D(()=>{let t=e.querySelector(`[data-slot="bulk-status"][data-state="settled"]`);return T(t).not.toBeNull(),t});await T(a).toHaveTextContent(`重试 · 1 项已完成`),await D(()=>T(s(n,`状态`)).toEqual([`已准备重试`,`失败`,`失败`,`已准备重试`]));let l=e=>t.getByRole(`button`,{name:`${e} 的操作`}).closest(`tr`);l(`EF-5`).focus(),await E.keyboard(`{ArrowDown}`),await D(()=>T(l(`EF-4`)).toHaveFocus()),await E.keyboard(`{Enter}`);let u=await O(document.body).findByRole(`dialog`);await D(()=>T(u).toBeVisible()),await T(await O(u).findByText(`Inventory refused.`)).toBeVisible(),await E.keyboard(`{Escape}`),await D(()=>T(O(document.body).queryByRole(`dialog`)).toBeNull()),await D(()=>T(l(`EF-4`)).toHaveFocus()),await E.click(t.getByRole(`button`,{name:/^按状态分布/})),await D(()=>T(o(e)).toHaveLength(3))}},M={...m,name:`快照控制台 · 条件值取自数据`,play:async({canvasElement:e})=>{let t=O(e),n=await t.findByRole(`table`);await D(()=>T(s(n,`ID`)).toEqual([`EF-2`,`EF-5`,`EF-4`,`EF-1`]));let a=t.getByRole(`button`,{name:RegExp(`^${i[`label.filter.panel`]}`)});a.getAttribute(`aria-expanded`)!==`true`&&await E.click(a),await E.click(await t.findByRole(`button`,{name:i[`label.filter.add`]}));let o=await O(document.body).findByRole(`dialog`);await E.click(O(o).getByRole(`checkbox`,{name:`处理器`})),await E.click(O(o).getByRole(`button`,{name:i[`label.filter.pick-done`]})),await D(()=>T(document.body.querySelector(`[role="dialog"]`)).toBeNull());let c=await t.findByRole(`combobox`,{name:r(i,`label.filter.value-of`,{field:`处理器`})});await T(c).toHaveAttribute(`placeholder`,i[`label.filter.pick-or-type`]),await E.click(c);let l=await O(document.body).findByRole(`listbox`);await D(()=>T(O(l).getAllByRole(`option`).map(e=>e.getAttribute(`aria-label`))).toEqual([`OrderSaga（3 条记录）`,`InventorySaga（1 条记录）`,`PaymentSaga（1 条记录）`])),await E.click(O(l).getByRole(`option`,{name:/^InventorySaga/})),await T(c).toHaveValue(`InventorySaga`),await E.click(t.getByRole(`button`,{name:i[`label.filter.apply`]})),await D(()=>T(s(n,`ID`)).toEqual([`EF-4`]))}},N={...m,name:`快照控制台 · 搜索错误`,play:async({canvasElement:e})=>{let t=O(e),n=await t.findByRole(`table`);await D(()=>T(s(n,`ID`)).toEqual([`EF-2`,`EF-5`,`EF-4`,`EF-1`]));let r=t.getByRole(`searchbox`,{name:`搜索错误`});await E.type(r,`gateway timed out`),await T(s(n,`ID`)).toHaveLength(4),await E.keyboard(`{Enter}`),await D(()=>T(s(n,`ID`)).toEqual([`EF-2`]));let a=e.querySelector(`[data-slot="applied-bar"]`);await T(a).toHaveTextContent(`gateway timed out`),await E.click(t.getByRole(`button`,{name:i[`label.search.clear`]})),await D(()=>T(s(n,`ID`)).toHaveLength(4)),await T(r).toHaveValue(``)}},P=[`DataConsole`,`ValuesFromTheData`,`SearchesTheErrors`],j.parameters={...j.parameters,docs:{...j.parameters?.docs,source:{originalSource:`{
  ...DisplayDataConsole,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    // The definition is admitted: every system view lists — the records and
    // the analyses in one list — and the first opens.
    for (const title of ['活动中', '不可重试', '不可恢复', '已成功', '全部', '按状态分布', '活动失败 · 按处理器', '每日新增失败']) await expect(await canvas.findByRole('button', {
      name: new RegExp(\`^\${title}\`)
    })).toBeVisible();
    await expect(canvas.getByRole('button', {
      name: /^活动中/
    })).toHaveAttribute('aria-current', 'true');

    // Active executions, newest first, read through the nested snapshot paths.
    const table = await canvas.findByRole('table');
    await waitFor(() => expect(readColumn(table, 'ID')).toEqual(['EF-2', 'EF-5', 'EF-4', 'EF-1']));
    await expect(readColumn(table, '状态')).toEqual(['已准备重试', '失败', '失败', '失败']);
    await expect(readTotal(table, '已重试次数').replace(/\\D/g, '')).toBe('10');

    // A row offers what its execution takes: past the retry limit only the
    // forced retry, and never the recoverability it already has.
    const past = await rowMenu(canvas, 'EF-4');
    await expect(enabled(past.item('重试'))).toBe(false);
    await expect(enabled(past.item('强制重试'))).toBe(true);
    await expect(enabled(past.item('不可恢复'))).toBe(false);
    await userEvent.keyboard('{Escape}');
    await waitFor(() => expect(within(document.body).queryByRole('menu')).toBeNull());

    // Retrying one from its row prepares it, says so, and the row shows it.
    const within1 = await rowMenu(canvas, 'EF-1');
    await userEvent.click(within1.item('重试'));
    // The workbench says what the command came to, above the rows.
    const settled = await waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>('[data-slot="bulk-status"][data-state="settled"]');
      expect(found).not.toBeNull();
      return found!;
    });
    await expect(settled).toHaveTextContent('重试 · 1 项已完成');
    await waitFor(() => expect(readColumn(table, '状态')).toEqual(['已准备重试', '失败', '失败', '已准备重试']));

    // One execution read whole, from the keyboard: the rows are one Tab
    // stop, the arrows walk them, and Enter opens the panel beside the list
    // with what no column holds — the error the service recorded.
    const rowOf = (id: string) => canvas.getByRole('button', {
      name: \`\${id} 的操作\`
    }).closest('tr')!;
    rowOf('EF-5').focus();
    await userEvent.keyboard('{ArrowDown}');
    await waitFor(() => expect(rowOf('EF-4')).toHaveFocus());
    await userEvent.keyboard('{Enter}');
    const detail = await within(document.body).findByRole('dialog');
    await waitFor(() => expect(detail).toBeVisible());
    await expect(await within(detail).findByText('Inventory refused.')).toBeVisible();
    // Closed, the reader is back on the row they opened.
    await userEvent.keyboard('{Escape}');
    await waitFor(() => expect(within(document.body).queryByRole('dialog')).toBeNull());
    await waitFor(() => expect(rowOf('EF-4')).toHaveFocus());

    // The analysis is a view of the same workbench, not another console.
    await userEvent.click(canvas.getByRole('button', {
      name: /^按状态分布/
    }));
    await waitFor(() => expect(drawnMarks(canvasElement)).toHaveLength(3));
  }
}`,...j.parameters?.docs?.source}}},M.parameters={...M.parameters,docs:{...M.parameters?.docs,source:{originalSource:`{
  ...DisplayDataConsole,
  name: '快照控制台 · 条件值取自数据',
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    await waitFor(() => expect(readColumn(table, 'ID')).toEqual(['EF-2', 'EF-5', 'EF-4', 'EF-1']));
    const toggle = canvas.getByRole('button', {
      name: new RegExp(\`^\${zhCN['label.filter.panel']}\`)
    });
    if (toggle.getAttribute('aria-expanded') !== 'true') await userEvent.click(toggle);
    await userEvent.click(await canvas.findByRole('button', {
      name: zhCN['label.filter.add']
    }));
    const picker = await within(document.body).findByRole('dialog');
    await userEvent.click(within(picker).getByRole('checkbox', {
      name: '处理器'
    }));
    await userEvent.click(within(picker).getByRole('button', {
      name: zhCN['label.filter.pick-done']
    }));
    await waitFor(() => expect(document.body.querySelector('[role="dialog"]')).toBeNull());
    const box = await canvas.findByRole('combobox', {
      name: formatMessage(zhCN, 'label.filter.value-of', {
        field: '处理器'
      })
    });
    await expect(box).toHaveAttribute('placeholder', zhCN['label.filter.pick-or-type']);
    await userEvent.click(box);
    // Every processor the executions name, the most frequent first, each with
    // its count — across the whole service, not only the active rows on
    // screen: what is offered is what the field can hold.
    const listbox = await within(document.body).findByRole('listbox');
    await waitFor(() => expect(within(listbox).getAllByRole('option').map(option => option.getAttribute('aria-label'))).toEqual(['OrderSaga（3 条记录）', 'InventorySaga（1 条记录）', 'PaymentSaga（1 条记录）']));
    await userEvent.click(within(listbox).getByRole('option', {
      name: /^InventorySaga/
    }));
    await expect(box).toHaveValue('InventorySaga');
    await userEvent.click(canvas.getByRole('button', {
      name: zhCN['label.filter.apply']
    }));
    await waitFor(() => expect(readColumn(table, 'ID')).toEqual(['EF-4']));
  }
}`,...M.parameters?.docs?.source},description:{story:`A condition on a processor, picked from the values the service holds
rather than typed blind.

\`处理器\` is text the definition lets the service group by value, so the
value box of its condition lists the processors the executions name, each
with how many executions failed in it — the service's own count, asked as a
\`TERMS\` aggregation (\`POST …/snapshot/aggregation\`) once the box opens.
Picking one and applying narrows the rows to it.`,...M.parameters?.docs?.description}}},N.parameters={...N.parameters,docs:{...N.parameters?.docs,source:{originalSource:`{
  ...DisplayDataConsole,
  name: '快照控制台 · 搜索错误',
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    await waitFor(() => expect(readColumn(table, 'ID')).toEqual(['EF-2', 'EF-5', 'EF-4', 'EF-1']));

    // Named by what it searches, and there without opening anything.
    const box = canvas.getByRole('searchbox', {
      name: '搜索错误'
    });
    await userEvent.type(box, 'gateway timed out');
    // Typing asks nothing yet: every key would be a query over the store.
    await expect(readColumn(table, 'ID')).toHaveLength(4);
    await userEvent.keyboard('{Enter}');
    await waitFor(() => expect(readColumn(table, 'ID')).toEqual(['EF-2']));

    // The search is one of the conditions: the band says so.
    const applied = canvasElement.querySelector<HTMLElement>('[data-slot="applied-bar"]')!;
    await expect(applied).toHaveTextContent('gateway timed out');

    // ✕ takes it away and asks again.
    await userEvent.click(canvas.getByRole('button', {
      name: zhCN['label.search.clear']
    }));
    await waitFor(() => expect(readColumn(table, 'ID')).toHaveLength(4));
    await expect(box).toHaveValue('');
  }
}`,...N.parameters?.docs?.source},description:{story:`搜索错误常驻在标题栏：输入一段错误、按 Enter，只剩那几次执行；✕ 撤掉搜索、
行回来。此前要打开条件、添加、勾「搜索错误」、完成、再输入，五步。`,...N.parameters?.docs?.description}}}})))()}F();export{j as DataConsole,N as SearchesTheErrors,M as ValuesFromTheData,P as __namedExportsOrder,k as default};
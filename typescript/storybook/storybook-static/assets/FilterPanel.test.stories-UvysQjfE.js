import{n as e}from"./rolldown-runtime-DkW27tQK.js";import{Es as t,R as n,Ts as r,z as i}from"./styles-Dpj2y9Rj.js";import{a,c as o,i as s,t as c}from"./readTable-DOunjEkP.js";import{a as l,c as u,i as d,l as f,o as p,r as m,s as h,t as g,u as _}from"./FilterPanel.stories-ZRmTRRha.js";var v,y,b,x,S,C,w,T,E,D,O,k,A,j,M,N,P,F,I;function L(){return(L=e((()=>{t(),n(),f(),s(),{expect:v,fireEvent:y,userEvent:b,waitFor:x,within:S}=__STORYBOOK_MODULE_TEST__,C={..._,title:`View Engine/数据视图/筛选编辑器/回归`,tags:[`!dev`,`!autodocs`,`test`],parameters:{..._.parameters}},w=e=>new Intl.NumberFormat(`zh-CN`,{style:`currency`,currency:`CNY`}).format(e),T=i[`label.filter.join`],E={...p,play:async({canvasElement:e})=>{let t=await S(e).findByRole(`table`);await x(()=>v(a(t,`订单号`)).toEqual([`SO-1001`,`SO-1003`,`SO-1005`,`SO-1006`])),await v(c(o(t,`金额`))).toBe(6470)}},D={...g,play:async({canvasElement:e})=>{let t=S(e);await v(await t.findByText(i[`label.record.empty-view`])).toBeVisible(),await v(t.getByRole(`button`,{name:i[`label.record.empty-edit`]})).toBeVisible();let n=[...t.getByRole(`region`,{name:i[`label.applied.title`]}).querySelectorAll(`[data-slot="badge"]`)].map(e=>e.textContent?.trim());await v(n).toEqual([`仓库 ${i[`label.relation.is`]} 华东`,`状态 ${i[`label.operator.IN`]} 待出库${T}已发运`,`金额 ${i[`label.operator.BETWEEN`]} ${w(100)} ~ ${w(5e3)}`,`创建时间 ${i[`label.operator.BETWEEN`]} ${i[`label.relative.preset.thisMonth`]}`,`${i[`label.filter.any-of`]} 仓库 ${i[`label.relation.is`]} 华北${T}金额 ${i[`label.operator.GT`]} ${w(2e4)}`,`商品行 ${i[`label.operator.ELEMENT_MATCH`]} ${i[`label.filter.all-of`]} SKU ${i[`label.relation.is`]} A-1${T}数量 ${i[`label.operator.GT`]} 2`,`删除状态 ${i[`label.operator.DELETION`]} ${i[`label.deletion.active`]} `+i[`label.applied.implied`]])}},O={...m,play:async({canvasElement:e})=>{let t=S(e),n=await t.findByRole(`table`),o=()=>[...t.getByRole(`region`,{name:i[`label.applied.title`]}).querySelectorAll(`[data-slot="badge"]`)].map(e=>e.textContent?.trim()),s=`状态 ${i[`label.relation.is`]} 已取消`;await x(()=>v(a(n,`订单号`)).toEqual([`SO-1001`,`SO-1003`,`SO-1004`,`SO-1005`,`SO-1006`])),await v(o()[0]).toBe(r(i,`label.filter.not-of`,{condition:s})),await b.click(await t.findByRole(`button`,{name:RegExp(`^${i[`label.filter.panel`]}`)}));let c=await t.findByRole(`group`,{name:r(i,`label.filter.condition-of`,{field:`状态`})}),l=S(c).getByRole(`button`,{name:r(i,`label.filter.negate-of`,{field:`状态`})});await v(l).toHaveAttribute(`aria-pressed`,`true`),await v(c.querySelector(`[data-slot="filter-negated"]`)).toHaveTextContent(i[`label.filter.negated`]),await b.click(l),await x(()=>v(S(t.getByRole(`group`,{name:r(i,`label.filter.condition-of`,{field:`状态`})})).getByRole(`button`,{name:r(i,`label.filter.negate-of`,{field:`状态`})})).toHaveAttribute(`aria-pressed`,`false`)),await b.click(t.getByRole(`button`,{name:i[`label.filter.apply`]})),await x(()=>v(a(t.getByRole(`table`),`订单号`)).toEqual([`SO-1002`])),await v(o()[0]).toBe(s)}},k={...l,play:async({canvasElement:e})=>{let t=S(e),n=S(document.body),o=await t.findByRole(`table`),s=()=>[...t.getByRole(`region`,{name:i[`label.applied.title`]}).querySelectorAll(`[data-slot="badge"]`)].map(e=>e.textContent?.trim());await x(()=>v(a(o,`订单号`)).toEqual([`SO-1001`,`SO-1003`])),await v(s()[0]).toBe(`客户 ${i[`label.relation.is`]} 晨光食品`),await b.click(await t.findByRole(`button`,{name:RegExp(`^${i[`label.filter.panel`]}`)}));let c=await t.findByRole(`group`,{name:r(i,`label.filter.condition-of`,{field:`客户`})});await v(S(c).getByText(`晨光食品`)).toBeVisible();let l=S(c).getByRole(`combobox`,{name:r(i,`label.filter.value-of`,{field:`客户`})});await b.click(l),await n.findByRole(`option`,{name:`宏远贸易`}),await v(n.getByRole(`button`,{name:i[`label.filter.more-candidates`]})).toBeVisible(),await b.type(l,`物流`),await x(()=>v(n.queryByRole(`option`,{name:`宏远贸易`})).toBeNull()),await b.click(await n.findByRole(`option`,{name:`蓝海物流`})),await v(S(c).getByText(`蓝海物流`)).toBeVisible(),await b.click(S(c).getByRole(`button`,{name:r(i,`label.filter.remove-value`,{value:`晨光食品`})})),await b.click(t.getByRole(`button`,{name:i[`label.filter.apply`]})),await x(()=>v(a(t.getByRole(`table`),`订单号`)).toEqual([`SO-1002`,`SO-1005`])),await v(s()[0]).toBe(`客户 ${i[`label.relation.is`]} 蓝海物流`)}},A={...p,play:async({canvasElement:e})=>{let t=S(e),n=S(document.body);await t.findByRole(`table`);let o=()=>t.getByRole(`region`,{name:i[`label.applied.title`]}),s=()=>o().querySelector(`[data-slot="badge"][data-implied]`);await x(()=>v(s()).not.toBeNull()),await v(s()?.textContent).toContain(`删除状态 ${i[`label.operator.DELETION`]} ${i[`label.deletion.active`]}`),await v(s()?.querySelector(`button`)).toBeNull(),await b.click(await t.findByRole(`button`,{name:RegExp(`^${i[`label.filter.panel`]}`)})),await b.click(t.getByRole(`button`,{name:i[`label.filter.add`]})),await b.click(await n.findByRole(`checkbox`,{name:`删除状态`})),await b.click(n.getByRole(`button`,{name:i[`label.filter.pick-done`]})),await b.click(t.getByRole(`combobox`,{name:r(i,`label.filter.value-of`,{field:`删除状态`})})),await b.click(await n.findByRole(`option`,{name:i[`label.deletion.all`]})),await b.click(t.getByRole(`button`,{name:i[`label.filter.apply`]})),await x(()=>v(o().textContent).toContain(i[`label.deletion.all`])),await v(s()).toBeNull(),await x(()=>v(a(t.getByRole(`table`),`订单号`)).toContain(`SO-1007`)),await v(S(o()).getByRole(`button`,{name:r(i,`label.filter.unset-of`,{condition:`删除状态 ${i[`label.operator.DELETION`]} ${i[`label.deletion.all`]}`})})).toBeVisible()}},j={...u,play:async({canvasElement:e})=>{let t=S(e);await b.click(await t.findByRole(`button`,{name:RegExp(`^${i[`label.filter.panel`]}`)}));let n=await t.findByLabelText(r(i,`label.filter.value-of`,{field:`创建时间`}));await b.click(n);let a=S(document.body),o=await a.findByLabelText(i[`label.date.time-from`]);await v(o).toHaveValue(``),await v(a.getByLabelText(i[`label.date.time-to`])).toHaveValue(``),await b.click(o),await v(o).toHaveFocus(),y.change(o,{target:{value:`15:30:00`}}),await x(()=>v(o).toHaveValue(`15:30:00`)),await b.keyboard(`{Escape}`);let s=(e,t)=>new Intl.DateTimeFormat(`zh-CN`,{dateStyle:`medium`,...t?{timeStyle:`medium`}:{},timeZone:`UTC`}).format(e);await x(()=>v(n.textContent).toBe(`${s(Date.UTC(2026,8,15,15,30),!0)} – `+s(Date.UTC(2026,8,17),!1))),await b.click(await t.findByRole(`button`,{name:i[`label.filter.apply`]}));let c=t.getByRole(`region`,{name:i[`label.applied.title`]});await x(()=>v([...c.querySelectorAll(`[data-slot="badge"]`)].map(e=>e.textContent?.trim())).toEqual([`创建时间 ${i[`label.operator.BETWEEN`]} ${s(Date.UTC(2026,8,15,15,30),!0)} ~ `+s(Date.UTC(2026,8,17),!1),`删除状态 ${i[`label.operator.DELETION`]} ${i[`label.deletion.active`]} `+i[`label.applied.implied`]]))}},M={...d,play:async({canvasElement:e})=>{let t=S(e);await b.click(await t.findByRole(`button`,{name:RegExp(`^${i[`label.filter.panel`]}`)}));let n=e=>r(i,`label.filter.remove-value`,{value:String(e)}),a=RegExp(`^${i[`label.filter.remove-value`].replace(`{value}`,String.raw`\d+`)}$`),o=()=>t.queryAllByRole(`button`,{name:a}).map(e=>e.getAttribute(`aria-label`)),s=e=>t.getByRole(`button`,{name:n(e)});await x(()=>v(o()).toEqual([100,1200,5e3].map(n)));let c=t.getByLabelText(r(i,`label.filter.new-value-of`,{field:r(i,`label.filter.value-of`,{field:`金额`})}));await b.type(c,`8888{Enter}`),await x(()=>v(o()).toContain(n(8888))),await v(c).toHaveValue(``),await b.click(s(1200)),await x(()=>v(o()).toEqual([100,5e3,8888].map(n)))}},N={...g,args:{instanceId:`orders-rich`,hostWidth:420},play:async({canvasElement:e})=>{let t=S(e);await b.click(await t.findByRole(`button`,{name:RegExp(`^${i[`label.filter.panel`]}`)}));let n=e.querySelector(`[data-pill-host]`);for(let t of[420,640,1024]){n.style.width=`${t}px`,await x(()=>v(e.querySelectorAll(`[data-slot="filter-condition"]`).length).toBeGreaterThan(0));for(let n of e.querySelectorAll(`[data-slot="filter-condition"]`)){let e=n.getBoundingClientRect(),r=[...n.querySelectorAll(`button`)],i=r[r.length-1].getBoundingClientRect(),a=n.querySelector(`[data-slot="filter-value"]`);for(let r of a.querySelectorAll(`[data-slot="select-trigger"], [data-slot="input"], button`)){let a=r.getBoundingClientRect();if(a.width===0)continue;let o=`${n.getAttribute(`aria-label`)} @ ${t}`;await v(a.right,o).toBeLessThanOrEqual(e.right),await v(a.left,o).toBeGreaterThanOrEqual(e.left),a.top<i.bottom&&i.top<a.bottom&&await v(a.right,`${o} vs ✕`).toBeLessThanOrEqual(i.left)}}}}},P={...u,play:async({canvasElement:e})=>{let t=S(e);await b.click(await t.findByRole(`button`,{name:RegExp(`^${i[`label.filter.panel`]}`)}));let n=await t.findByLabelText(r(i,`label.filter.value-of`,{field:`创建时间`}));await b.click(n);let a=S(document.body),o=await x(()=>{let e=document.body.querySelector(`[data-slot="popover-content"]`);return v(e).not.toBeNull(),e});await x(()=>{v(o).toBeVisible(),v(o.getAnimations({subtree:!0})).toHaveLength(0)});let s=new Intl.DateTimeFormat(`zh-CN`,{year:`numeric`,month:`long`}).format(new Date(2026,8,15));await x(()=>v(o.querySelector(`[class*="month_caption"]`)?.textContent?.trim()).toBe(s)),await v([...o.querySelectorAll(`th[aria-label]`)].map(e=>e.textContent?.trim())).toContain(new Intl.DateTimeFormat(`zh-CN`,{weekday:`short`}).format(new Date(2026,8,15))),await v(a.getByRole(`button`,{name:i[`label.date.calendar-next`]})).toBeVisible(),await v(o.getBoundingClientRect().width).toBeGreaterThanOrEqual(n.getBoundingClientRect().width);for(let e of[`label.date.time-from`,`label.date.time-to`]){let t=a.getByLabelText(i[e]),n=o.querySelector(`label[for="${t.id}"]`),r=document.createRange();r.selectNodeContents(n),await v(r.getClientRects().length,i[e]).toBe(1),await v(Math.round(t.getBoundingClientRect().width)).toBeGreaterThanOrEqual(Math.round(n.getBoundingClientRect().width))}}},F={...h,play:async({canvasElement:e})=>{let t=S(e),n=await x(()=>{let t=e.querySelector(`[data-slot="filter-unsupported"]`);if(!t)throw Error(`no read-only condition`);return t});await v(n).toHaveTextContent(`#ff8800`),await v(n).toHaveTextContent(r(i,`label.filter.kind-unregistered`,{kind:`swatch`})),await v(n.querySelector(`input, [role="combobox"]`)).toBeNull();let a=n.closest(`[data-slot="filter-condition"]`);await v(a).not.toBeNull(),await v(S(a).getByRole(`button`,{name:/移除/})).toBeVisible();let o=e.querySelectorAll(`[data-slot="filter-condition"] [role="combobox"]`);await v(o.length).toBeGreaterThan(0),await v(t.getByRole(`button`,{name:i[`label.filter.apply`]})).toBeDisabled()}},I=[`Simple`,`Advanced`,`Negated`,`Reference`,`DeletionReading`,`WithTime`,`NumberList`,`TheValueStaysInsideItsPill`,`TheCalendarSpeaksTheSurfaceLanguage`,`UnregisteredKind`],E.parameters={...E.parameters,docs:{...E.parameters?.docs,source:{originalSource:`{
  ...DisplaySimple,
  play: async ({
    canvasElement
  }) => {
    const table = await within(canvasElement).findByRole('table');
    await waitFor(() => expect(readColumn(table, '订单号')).toEqual(['SO-1001', 'SO-1003', 'SO-1005', 'SO-1006']));
    await expect(amountOf(readTotal(table, '金额'))).toBe(6470);
  }
}`,...E.parameters?.docs?.source},description:{story:`Pending and at least 100: every pending order, in the order they came.`,...E.parameters?.docs?.description}}},D.parameters={...D.parameters,docs:{...D.parameters?.docs,source:{originalSource:"{\n  ...DisplayAdvanced,\n  play: async ({\n    canvasElement\n  }) => {\n    const canvas = within(canvasElement);\n    await expect(await canvas.findByText(zhCN['label.record.empty-view'])).toBeVisible();\n    await expect(canvas.getByRole('button', {\n      name: zhCN['label.record.empty-edit']\n    })).toBeVisible();\n\n    // The bar over the result is the one place this tree is read back as\n    // sentences, and it is built from the parts each kind hands over. This\n    // is the only fixture carrying all three of the shapes that get it\n    // wrong: a named period, a nested group, and a predicate.\n    const applied = canvas.getByRole('region', {\n      name: zhCN['label.applied.title']\n    });\n    const badges = [...applied.querySelectorAll('[data-slot=\"badge\"]')].map(badge => badge.textContent?.trim());\n\n    // Every word below is read back out of the catalogue rather than typed\n    // in: the badge is the one line that has to change with `messages`, so a\n    // hard-coded expectation would only ever prove that nothing changed.\n    await expect(badges).toEqual([`仓库 ${zhCN['label.relation.is']} 华东`, `状态 ${zhCN['label.operator.IN']} 待出库${JOIN}已发运`,\n    // The field's own `numberFormat`, from the same Intl call the bar\n    // makes — which currency symbol ICU picks is not what this is about.\n    `金额 ${zhCN['label.operator.BETWEEN']} ${yuan(100)} ~ ${yuan(5000)}`,\n    // A period, not a range: the operator asks for the window it names.\n    `创建时间 ${zhCN['label.operator.BETWEEN']} ${zhCN['label.relative.preset.thisMonth']}`,\n    // A group says how its conditions combine before it lists them.\n    `${zhCN['label.filter.any-of']} 仓库 ${zhCN['label.relation.is']} 华北` + `${JOIN}金额 ${zhCN['label.operator.GT']} ${yuan(20000)}`,\n    // And a predicate reads its own conditions out once, under the one\n    // operator it holds them by.\n    `商品行 ${zhCN['label.operator.ELEMENT_MATCH']} ` + `${zhCN['label.filter.all-of']} SKU ${zhCN['label.relation.is']} A-1` + `${JOIN}数量 ${zhCN['label.operator.GT']} 2`,\n    // Last, the reading nobody wrote: the definition declares the\n    // soft-delete dimension and this tree leaves it blank, so the rows\n    // are the ones not deleted, and the bar says so (D17-2). The trailing\n    // words are the reader's note that this is the default.\n    `删除状态 ${zhCN['label.operator.DELETION']} ${zhCN['label.deletion.active']} ` + zhCN['label.applied.implied']]);\n  }\n}",...D.parameters?.docs?.source},description:{story:`The rich tree asks for 华东 and, in its OR group, for 华北 or more than
20,000 at once: no order is both, and the table says so — as the saved
view it is, asking what it was saved to ask, so the view is what is
empty, and its conditions are not offered up to be cleared.`,...D.parameters?.docs?.description}}},O.parameters={...O.parameters,docs:{...O.parameters?.docs,source:{originalSource:`{
  ...DisplayNegated,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    const badges = () => [...canvas.getByRole('region', {
      name: zhCN['label.applied.title']
    }).querySelectorAll('[data-slot="badge"]')].map(badge => badge.textContent?.trim());
    const status = \`状态 \${zhCN['label.relation.is']} 已取消\`;

    // Every order that is not cancelled.
    await waitFor(() => expect(readColumn(table, '订单号')).toEqual(['SO-1001', 'SO-1003', 'SO-1004', 'SO-1005', 'SO-1006']));
    await expect(badges()[0]).toBe(formatMessage(zhCN, 'label.filter.not-of', {
      condition: status
    }));

    // The pill: switch pressed, the word in front of the operator.
    await userEvent.click(await canvas.findByRole('button', {
      name: new RegExp(\`^\${zhCN['label.filter.panel']}\`)
    }));
    const pill = await canvas.findByRole('group', {
      name: formatMessage(zhCN, 'label.filter.condition-of', {
        field: '状态'
      })
    });
    const negate = within(pill).getByRole('button', {
      name: formatMessage(zhCN, 'label.filter.negate-of', {
        field: '状态'
      })
    });
    await expect(negate).toHaveAttribute('aria-pressed', 'true');
    await expect(pill.querySelector('[data-slot="filter-negated"]')).toHaveTextContent(zhCN['label.filter.negated']);

    // Pressed again, it is the plain condition — and only that row.
    await userEvent.click(negate);
    await waitFor(() => expect(within(canvas.getByRole('group', {
      name: formatMessage(zhCN, 'label.filter.condition-of', {
        field: '状态'
      })
    })).getByRole('button', {
      name: formatMessage(zhCN, 'label.filter.negate-of', {
        field: '状态'
      })
    })).toHaveAttribute('aria-pressed', 'false'));
    await userEvent.click(canvas.getByRole('button', {
      name: zhCN['label.filter.apply']
    }));
    await waitFor(() => expect(readColumn(canvas.getByRole('table'), '订单号')).toEqual(['SO-1002']));
    await expect(badges()[0]).toBe(status);
  }
}`,...O.parameters?.docs?.source},description:{story:`D18-7: a negated condition is a \`nor\` group of one, and simple mode shows
it as the pill with its switch pressed and the word in its sentence. The
bar says the whole condition under 排除; pressing the switch again and
applying is the plain condition, with the rows to match.`,...O.parameters?.docs?.description}}},k.parameters={...k.parameters,docs:{...k.parameters?.docs,source:{originalSource:`{
  ...DisplayReference,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    const body = within(document.body);
    const table = await canvas.findByRole('table');
    const badges = () => [...canvas.getByRole('region', {
      name: zhCN['label.applied.title']
    }).querySelectorAll('[data-slot="badge"]')].map(badge => badge.textContent?.trim());

    // The saved condition, said from the label it carries.
    await waitFor(() => expect(readColumn(table, '订单号')).toEqual(['SO-1001', 'SO-1003']));
    await expect(badges()[0]).toBe(\`客户 \${zhCN['label.relation.is']} 晨光食品\`);
    await userEvent.click(await canvas.findByRole('button', {
      name: new RegExp(\`^\${zhCN['label.filter.panel']}\`)
    }));
    const pill = await canvas.findByRole('group', {
      name: formatMessage(zhCN, 'label.filter.condition-of', {
        field: '客户'
      })
    });
    await expect(within(pill).getByText('晨光食品')).toBeVisible();

    // Opening lists the first page; typing narrows it at the source.
    const input = within(pill).getByRole('combobox', {
      name: formatMessage(zhCN, 'label.filter.value-of', {
        field: '客户'
      })
    });
    await userEvent.click(input);
    await body.findByRole('option', {
      name: '宏远贸易'
    });
    await expect(body.getByRole('button', {
      name: zhCN['label.filter.more-candidates']
    })).toBeVisible();
    await userEvent.type(input, '物流');
    await waitFor(() => expect(body.queryByRole('option', {
      name: '宏远贸易'
    })).toBeNull());
    await userEvent.click(await body.findByRole('option', {
      name: '蓝海物流'
    }));

    // Both are chips now; the first is removed by its own button.
    await expect(within(pill).getByText('蓝海物流')).toBeVisible();
    await userEvent.click(within(pill).getByRole('button', {
      name: formatMessage(zhCN, 'label.filter.remove-value', {
        value: '晨光食品'
      })
    }));
    await userEvent.click(canvas.getByRole('button', {
      name: zhCN['label.filter.apply']
    }));
    await waitFor(() => expect(readColumn(canvas.getByRole('table'), '订单号')).toEqual(['SO-1002', 'SO-1005']));
    await expect(badges()[0]).toBe(\`客户 \${zhCN['label.relation.is']} 蓝海物流\`);
  }
}`,...k.parameters?.docs?.source},description:{story:`F-04: a reference field's candidates come from the host's source. The saved
view carries the chosen customer's name beside its id, so the pill and the
bar say it without a lookup; the list is asked only once opened, and
again once typing pauses; a pick lands as \`{ id, label }\` and the rows
follow it.`,...k.parameters?.docs?.description}}},A.parameters={...A.parameters,docs:{...A.parameters?.docs,source:{originalSource:`{
  ...DisplaySimple,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    const body = within(document.body);
    await canvas.findByRole('table');
    const bar = () => canvas.getByRole('region', {
      name: zhCN['label.applied.title']
    });
    const implied = () => bar().querySelector('[data-slot="badge"][data-implied]');

    // Nothing written: the default reading is in force, said, and not
    // removable.
    await waitFor(() => expect(implied()).not.toBeNull());
    await expect(implied()?.textContent).toContain(\`删除状态 \${zhCN['label.operator.DELETION']} \${zhCN['label.deletion.active']}\`);
    await expect(implied()?.querySelector('button')).toBeNull();

    // Add the field, answer it, apply.
    await userEvent.click(await canvas.findByRole('button', {
      name: new RegExp(\`^\${zhCN['label.filter.panel']}\`)
    }));
    await userEvent.click(canvas.getByRole('button', {
      name: zhCN['label.filter.add']
    }));
    await userEvent.click(await body.findByRole('checkbox', {
      name: '删除状态'
    }));
    await userEvent.click(body.getByRole('button', {
      name: zhCN['label.filter.pick-done']
    }));
    await userEvent.click(canvas.getByRole('combobox', {
      name: formatMessage(zhCN, 'label.filter.value-of', {
        field: '删除状态'
      })
    }));
    await userEvent.click(await body.findByRole('option', {
      name: zhCN['label.deletion.all']
    }));
    await userEvent.click(canvas.getByRole('button', {
      name: zhCN['label.filter.apply']
    }));

    // Written, it is a condition like any other, and no longer the default.
    await waitFor(() => expect(bar().textContent).toContain(zhCN['label.deletion.all']));
    await expect(implied()).toBeNull();
    // And the rows answer it: the soft-deleted order the default hid is in.
    await waitFor(() => expect(readColumn(canvas.getByRole('table'), '订单号')).toContain('SO-1007'));
    await expect(within(bar()).getByRole('button', {
      name: formatMessage(zhCN, 'label.filter.unset-of', {
        condition: \`删除状态 \${zhCN['label.operator.DELETION']} \${zhCN['label.deletion.all']}\`
      })
    })).toBeVisible();
  }
}`,...A.parameters?.docs?.source},description:{story:`D17-2: a definition that declares the soft-delete dimension shows the
records that are not deleted unless a view says otherwise, and the bar
says that default without offering to remove it. Choosing «deleted
included» is then an ordinary condition — added through the picker,
answered in the pill, applied, and removable from the bar.`,...A.parameters?.docs?.description}}},j.parameters={...j.parameters,docs:{...j.parameters?.docs,source:{originalSource:`{
  ...DisplayWithTime,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await userEvent.click(await canvas.findByRole('button', {
      name: new RegExp(\`^\${zhCN['label.filter.panel']}\`)
    }));

    // The calendar and its clock are one control, opened from the pill.
    const trigger = await canvas.findByLabelText(formatMessage(zhCN, 'label.filter.value-of', {
      field: '创建时间'
    }));
    await userEvent.click(trigger);
    const popup = within(document.body);
    const from = await popup.findByLabelText(zhCN['label.date.time-from']);
    // Both boxes are empty to begin with: that is the whole day, not
    // midnight, and it is what the hint under them says.
    await expect(from).toHaveValue('');
    await expect(popup.getByLabelText(zhCN['label.date.time-to'])).toHaveValue('');

    // Reachable and editable from the keyboard: the box takes focus, and
    // the value is then set the way the browser's own spin fields set it.
    // Synthetic keystrokes do not drive a native time input's segments —
    // they are untrusted, so Chromium ignores them — which is why this
    // changes the value rather than typing six digits into it.
    await userEvent.click(from);
    await expect(from).toHaveFocus();
    fireEvent.change(from, {
      target: {
        value: '15:30:00'
      }
    });
    await waitFor(() => expect(from).toHaveValue('15:30:00'));
    await userEvent.keyboard('{Escape}');

    // The trigger reads the bound back with the time on the start edge and
    // without one on the end, through the surface's own formatter: a
    // wall-clock string names a time on a clock rather than a moment, so it
    // is shown as written whatever zone the browser is in.
    const shown = (utc: number, withTime: boolean) => new Intl.DateTimeFormat('zh-CN', {
      dateStyle: 'medium',
      ...(withTime ? {
        timeStyle: 'medium' as const
      } : {}),
      timeZone: 'UTC'
    }).format(utc);
    await waitFor(() => expect(trigger.textContent).toBe(\`\${shown(Date.UTC(2026, 8, 15, 15, 30), true)} – \` + shown(Date.UTC(2026, 8, 17), false)));
    await userEvent.click(await canvas.findByRole('button', {
      name: zhCN['label.filter.apply']
    }));

    // And the applied badge says the same: a bound with a time of day says
    // it, one without stays a day.
    const applied = canvas.getByRole('region', {
      name: zhCN['label.applied.title']
    });
    await waitFor(() => expect([...applied.querySelectorAll('[data-slot="badge"]')].map(badge => badge.textContent?.trim())).toEqual([\`创建时间 \${zhCN['label.operator.BETWEEN']} \` + \`\${shown(Date.UTC(2026, 8, 15, 15, 30), true)} ~ \` + shown(Date.UTC(2026, 8, 17), false),
    // The definition's soft-delete dimension, left blank: said as the
    // default (D17-2).
    \`删除状态 \${zhCN['label.operator.DELETION']} \${zhCN['label.deletion.active']} \` + zhCN['label.applied.implied']]));
  }
}`,...j.parameters?.docs?.source},description:{story:`A \`withTime\` field's condition carries a time of day, in the same control
as the calendar and with one submission (D17-1). The box starts empty,
which is the day itself — read at \`00:00:00.000\` as a start and at
\`23:59:59.999\` as an end — and the summary says the time only where one
was given, so the badge never claims a boundary the query did not run to.

Only a real browser can drive a native time input, which is why this lives
here and not in jsdom.`,...j.parameters?.docs?.description}}},M.parameters={...M.parameters,docs:{...M.parameters?.docs,source:{originalSource:`{
  ...DisplayNumberList,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    // A saved view opens with its editor folded.
    await userEvent.click(await canvas.findByRole('button', {
      name: new RegExp(\`^\${zhCN['label.filter.panel']}\`)
    }));

    // Each chip's ✕ names the value it drops, in the wording in force.
    const removeLabel = (value: number) => formatMessage(zhCN, 'label.filter.remove-value', {
      value: String(value)
    });
    const ANY_VALUE = new RegExp(\`^\${zhCN['label.filter.remove-value'].replace('{value}', String.raw\`\\d+\`)}$\`);
    const removes = () => canvas.queryAllByRole('button', {
      name: ANY_VALUE
    }).map(button => button.getAttribute('aria-label'));
    const remove = (value: number) => canvas.getByRole('button', {
      name: removeLabel(value)
    });

    // Three values, which the two boxes of a range could never have held.
    await waitFor(() => expect(removes()).toEqual([100, 1200, 5000].map(removeLabel)));

    // A fourth, entered by hand: Enter commits it and clears the field, and
    // it does not double as the panel's apply. The entry field is named after
    // the field it adds to, which is two catalogue entries deep.
    const entry = canvas.getByLabelText(formatMessage(zhCN, 'label.filter.new-value-of', {
      field: formatMessage(zhCN, 'label.filter.value-of', {
        field: '金额'
      })
    }));
    await userEvent.type(entry, '8888{Enter}');
    await waitFor(() => expect(removes()).toContain(removeLabel(8888)));
    await expect(entry).toHaveValue('');

    // And one taken back out, by the button that names it.
    await userEvent.click(remove(1200));
    await waitFor(() => expect(removes()).toEqual([100, 5000, 8888].map(removeLabel)));
  }
}`,...M.parameters?.docs?.source},description:{story:`A numeric \`IN\` takes as many values as the kernel compiles. The editor used
to borrow the range's pair of boxes, so a third value had nowhere to go —
and only a test that types into the editor catches that, because the kernel
itself never refused the array.`,...M.parameters?.docs?.description}}},N.parameters={...N.parameters,docs:{...N.parameters?.docs,source:{originalSource:`{
  ...DisplayAdvanced,
  args: {
    instanceId: 'orders-rich',
    hostWidth: 420
  },
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    // A saved view opens with its editor folded.
    await userEvent.click(await canvas.findByRole('button', {
      name: new RegExp(\`^\${zhCN['label.filter.panel']}\`)
    }));
    const host = canvasElement.querySelector<HTMLElement>('[data-pill-host]')!;

    // It mounts at the narrowest of the three and is widened from there: the
    // view list folds itself away for a narrow column at mount and stays
    // folded, so every step measures a strip and not the fold.
    for (const width of [420, 640, 1024]) {
      host.style.width = \`\${width}px\`;
      await waitFor(() => expect(canvasElement.querySelectorAll('[data-slot="filter-condition"]').length).toBeGreaterThan(0));
      for (const pill of canvasElement.querySelectorAll<HTMLElement>('[data-slot="filter-condition"]')) {
        const frame = pill.getBoundingClientRect();
        const buttons = [...pill.querySelectorAll('button')];
        const cross = buttons[buttons.length - 1].getBoundingClientRect();
        const value = pill.querySelector<HTMLElement>('[data-slot="filter-value"]')!;
        for (const control of value.querySelectorAll<HTMLElement>('[data-slot="select-trigger"], [data-slot="input"], button')) {
          const box = control.getBoundingClientRect();
          // Base UI keeps a hidden input beside the one on screen.
          if (box.width === 0) continue;
          const where = \`\${pill.getAttribute('aria-label')} @ \${width}\`;
          // Inside the pill it belongs to, on both edges.
          await expect(box.right, where).toBeLessThanOrEqual(frame.right);
          await expect(box.left, where).toBeGreaterThanOrEqual(frame.left);
          // And clear of the ✕ wherever the two share a line: a control the
          // remove button covers is a control the pointer cannot reach.
          const sameLine = box.top < cross.bottom && cross.top < box.bottom;
          if (sameLine) await expect(box.right, \`\${where} vs ✕\`).toBeLessThanOrEqual(cross.left);
        }
      }
    }
  }
}`,...N.parameters?.docs?.source},description:{story:`F-08: a condition pill keeps its value control inside its own border, and
off the ✕ beside it, at every width the strip is given.

The value select asked for \`min-w-40\`, and 160px is a floor a flex item
reports upwards however little room its container has: at 1280 it ran 37px
under the remove button and 6px past the pill's own border, at 420 104px
and 73px, and the chevron was no longer the element at its own
coordinates. Both boxes of a range did the same from the other end — 31px
past the ✕ once the separator was between them. Only a real browser lays
this out, which is why the rule is measured here rather than asserted as a
class name in jsdom.`,...N.parameters?.docs?.description}}},P.parameters={...P.parameters,docs:{...P.parameters?.docs,source:{originalSource:`{
  ...DisplayWithTime,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await userEvent.click(await canvas.findByRole('button', {
      name: new RegExp(\`^\${zhCN['label.filter.panel']}\`)
    }));
    const trigger = await canvas.findByLabelText(formatMessage(zhCN, 'label.filter.value-of', {
      field: '创建时间'
    }));
    await userEvent.click(trigger);
    const popup = within(document.body);
    const popover = await waitFor(() => {
      const found = document.body.querySelector<HTMLElement>('[data-slot="popover-content"]');
      expect(found).not.toBeNull();
      return found!;
    });
    // A popover appears in two steps, and everything below either measures it
    // or asks whether something inside it is visible — both of which the
    // steps answer wrongly. Base UI keeps the positioner hidden until it has
    // measured where to put the popup, and the popup then fades and scales in
    // (\`data-open:fade-in-0 zoom-in-95\` through \`animate-in\`, whose
    // \`animation-fill-mode: both\` pins the computed opacity at 0 until the
    // first frame). So \`toBeVisible\` says no, and a box read mid-animation is
    // 5% short — 271px of the 272 it settles at, and 204 at the start. This
    // waits for the whole of it rather than racing it; a fixed delay is a
    // guess a slower machine loses, which is exactly how this passed here and
    // failed on CI.
    await waitFor(() => {
      expect(popover).toBeVisible();
      expect(popover.getAnimations({
        subtree: true
      })).toHaveLength(0);
    });

    // The month and the weekday heads are \`Intl\` in the surface's language,
    // which is the same call the trigger above was formatted with.
    const september = new Intl.DateTimeFormat('zh-CN', {
      year: 'numeric',
      month: 'long'
    }).format(new Date(2026, 8, 15));
    // Read off the caption rather than found by text: the library also keeps
    // an \`aria-live\` span for announcing the month, which is empty between
    // announcements and would be the first thing a text query answered with.
    await waitFor(() => expect(popover.querySelector<HTMLElement>('[class*="month_caption"]')?.textContent?.trim()).toBe(september));
    await expect([...popover.querySelectorAll('th[aria-label]')].map(head => head.textContent?.trim())).toContain(new Intl.DateTimeFormat('zh-CN', {
      weekday: 'short'
    }).format(new Date(2026, 8, 15)));
    // And the words no formatter produces come from the catalogue.
    await expect(popup.getByRole('button', {
      name: zhCN['label.date.calendar-next']
    })).toBeVisible();

    // At least as wide as the control it belongs to: a popover narrower than
    // its own trigger reads as a different, smaller thing.
    await expect(popover.getBoundingClientRect().width).toBeGreaterThanOrEqual(trigger.getBoundingClientRect().width);

    // Each field's name on one line. A text node's client rects are one per
    // line it occupies, which is the only way to tell a name that wrapped
    // from one that happened to be tall.
    for (const key of ['label.date.time-from', 'label.date.time-to'] as const) {
      const field = popup.getByLabelText(zhCN[key]);
      const name = popover.querySelector<HTMLElement>(\`label[for="\${field.id}"]\`)!;
      const lines = document.createRange();
      lines.selectNodeContents(name);
      await expect(lines.getClientRects().length, zhCN[key]).toBe(1);
      // And the box under it takes the width the name is measured against,
      // rather than whatever a grid of digits left beside it.
      await expect(Math.round(field.getBoundingClientRect().width)).toBeGreaterThanOrEqual(Math.round(name.getBoundingClientRect().width));
    }
  }
}`,...P.parameters?.docs?.source},description:{story:"F-09: the calendar speaks the surface's language, and the popover is sized\nby what is in it rather than by the grid of day numbers at the top.\n\n`react-day-picker` reads its words out of a date-fns `Locale` object and\nfalls back to `en-US`, so this story's trigger said «2026年9月15日 –\n2026年9月17日» over a popover headed `September 2026` and\n`Su Mo Tu We Th Fr Sa`. Measured in Chromium: the popover was 212px wide,\nnarrower than its own 235px trigger, 「起始时刻」 was given 48px and broke\nacross two lines in the middle of a word, and the hint under it ran to\nthree.",...P.parameters?.docs?.description}}},F.parameters={...F.parameters,docs:{...F.parameters?.docs,source:{originalSource:`{
  ...DisplayUnregisteredKind,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    const unsupported = await waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>('[data-slot="filter-unsupported"]');
      if (!found) throw new Error('no read-only condition');
      return found;
    });
    await expect(unsupported).toHaveTextContent('#ff8800');
    await expect(unsupported).toHaveTextContent(formatMessage(zhCN, 'label.filter.kind-unregistered', {
      kind: 'swatch'
    }));
    // Nothing in it can be typed into; the row can still be taken away.
    await expect(unsupported.querySelector('input, [role="combobox"]')).toBeNull();
    const pill = unsupported.closest<HTMLElement>('[data-slot="filter-condition"]');
    await expect(pill).not.toBeNull();
    await expect(within(pill!).getByRole('button', {
      name: /移除/
    })).toBeVisible();
    // The other condition is an ordinary, editable one.
    const editable = canvasElement.querySelectorAll('[data-slot="filter-condition"] [role="combobox"]');
    await expect(editable.length).toBeGreaterThan(0);
    // Apply is refused, and says how many conditions want fixing.
    await expect(canvas.getByRole('button', {
      name: zhCN['label.filter.apply']
    })).toBeDisabled();
  }
}`,...F.parameters?.docs?.source},description:{story:`A condition on a field whose kind no registry knows (F-06): drawn
read-only with the stored value and the reason, its ✕ still working; the
condition beside it stays editable; Apply is refused with the count.`,...F.parameters?.docs?.description}}}})))()}L();export{D as Advanced,A as DeletionReading,O as Negated,M as NumberList,k as Reference,E as Simple,P as TheCalendarSpeaksTheSurfaceLanguage,N as TheValueStaysInsideItsPill,F as UnregisteredKind,j as WithTime,I as __namedExportsOrder,C as default};
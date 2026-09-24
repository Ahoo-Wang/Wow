import{n as e}from"./rolldown-runtime-DkW27tQK.js";import{t}from"./jsx-runtime-DeHZSEgm.js";import{D as n,Es as r,R as i,Ts as a,h as o,v as s,ws as c,z as l}from"./styles-Dpj2y9Rj.js";import{a as u,i as d,l as f,n as p,r as m}from"./contrast-BGE4rlQ_.js";import{a as h,c as g,i as ee,o as _,s as te,t as ne}from"./readTable-DOunjEkP.js";import{n as v,r as re,t as ie}from"./pointerDrag-CZRuWcId.js";import{B as y,C as ae,D as oe,E as se,F as ce,H as le,I as ue,L as de,M as fe,N as pe,O as me,P as he,R as ge,S as _e,T as ve,U as ye,V as be,W as b,_ as xe,a as Se,b as x,c as Ce,d as we,f as Te,g as Ee,h as De,i as Oe,j as ke,k as Ae,l as je,m as Me,n as Ne,o as Pe,p as Fe,r as Ie,s as Le,t as Re,u as ze,v as Be,w as Ve,x as He,y as Ue,z as We}from"./RecordWorkbench.stories-CBypcKb5.js";async function Ge(e){let t=j(e),n=await t.findByRole(`table`);await A(()=>O(h(n,`订单号`)).toEqual(M)),await et(n,`订单号`),await A(()=>O(rt(t.getByRole(`table`),`订单号`)).toBe(`2`))}async function Ke(e){await S(e.getByRole(`button`,{name:l[`label.save.save`]})),await qe()}async function qe(){let e=await yt.findByRole(`alertdialog`,{name:l[`label.save.shared-heading`]});await k.click(j(e).getByRole(`button`,{name:l[`label.save.shared-confirm`]})),await A(()=>O(yt.queryByRole(`alertdialog`,{name:l[`label.save.shared-heading`]})).toBeNull())}async function S(e){await A(()=>O(e).not.toBeDisabled()),await k.click(e)}async function Je(e,t){let n=(await j(e).findByText(t,{exact:!1})).closest(`[data-slot="write-outcome"]`);if(!n)throw Error(`"${t}" is not in an outcome band.`);return n}function Ye(e){return Je(e,l[`label.write.conflict`])}async function Xe(e,t){await k.click(await e.findByRole(`button`,{name:l[`label.manage.open`]})),await j(document.body).findByRole(`dialog`);let n=null;return await A(()=>{n=Ze(t)}),n}function Ze(e){let t=[...document.querySelectorAll(`[data-slot="view-manager-row"]`)].find(t=>t.textContent?.includes(e)||[...t.querySelectorAll(`input`)].some(t=>t.value.includes(e)));if(!t)throw Error(`no row for ${e}`);return t}async function Qe(e){return await j(document.body).findByText(l[`label.write.conflict`]),Ze(e)}async function $e(){return j(document.body).findByRole(`alertdialog`)}async function et(e,t){let n=k.setup();await n.keyboard(`{Shift>}`),await n.click(C(e,t).querySelector(`button`)),await n.keyboard(`{/Shift}`)}function C(e,t){let n=[...e.querySelectorAll(`thead th`)].find(e=>e.querySelector(`[data-slot="column-label"]`)?.textContent?.trim()===t);if(!n)throw Error(`No column is headed "${t}".`);return n}function tt(e,t){return((e.tBodies[0]?.rows[0])?.cells[C(e,t).cellIndex])?.querySelector(`[data-slot="badge"]`)??null}function w(e,t,n){let r=e.tBodies[0]?.rows[n]?.cells[C(e,t).cellIndex];if(!r)throw Error(`Row ${n} has no cell under "${t}".`);return r}function nt(e){return[...e.querySelectorAll(`[data-slot="badge"]`)].map(e=>e.textContent?.trim()??``)}function rt(e,t){return C(e,t).querySelector(`[data-slot="sort-position"]`)?.textContent?.trim()}function it(e){return[...e.querySelectorAll(`tfoot [data-slot="summary-scope"]`)].map(e=>e.textContent?.trim()??``)}function at(e){return[...e.querySelectorAll(`[data-slot]`)].map(e=>e.getAttribute(`data-slot`)??``).filter(e=>Kt.includes(e))}function ot(e,t){let n=[...e.querySelectorAll(`[data-slot="view-list"] [data-slot="view-group"] button`)].find(e=>e.textContent?.includes(t));if(!n)throw Error(`The list has no view called "${t}".`);return n}function st(e){let t=(e.scrollingElement??e.documentElement).style;return[`overflow-x`,`overflow-y`].map(e=>{let n=t.getPropertyPriority(e);return t.getPropertyValue(e)+(n?` !${n}`:``)})}function ct(e){let t=e.ownerDocument.defaultView,n=e.getBoundingClientRect();return Math.abs(n.left)<1&&Math.abs(n.top)<1&&Math.abs(n.width-t.innerWidth)<1&&Math.abs(n.height-t.innerHeight)<1}async function lt(e){await j(e).findByRole(`table`),await k.click(e.querySelector(`[data-control="columns"]`));let t=await A(()=>{let e=document.querySelector(`[data-slot="popover-content"]`);return O(e).not.toBeNull(),e}),n=t.querySelector(`[data-slot="column-list"]`),r=t.querySelector(`[data-slot="column-search"]`),i=()=>[...n.querySelectorAll(`[data-slot="column-setting"]`)].map(e=>e.dataset.field),a=()=>[...t.querySelectorAll(`[data-slot="column-region-heading"], [data-slot="column-group-heading"]`)].map(e=>e.textContent),o=t.getBoundingClientRect();await O(Math.round(o.top)).toBeGreaterThanOrEqual(0),await O(Math.round(o.bottom)).toBeLessThanOrEqual(Math.round(window.innerHeight)),await A(()=>O(n.scrollHeight).toBeGreaterThan(n.clientHeight));let s=Math.round(r.getBoundingClientRect().top);n.scrollTop=n.scrollHeight,await O(n.scrollTop).toBeGreaterThan(0),await O(Math.round(r.getBoundingClientRect().top)).toBe(s);let c=[...n.querySelectorAll(`li`)].at(-1);await O(Math.round(c.getBoundingClientRect().bottom)).toBeLessThanOrEqual(Math.round(n.getBoundingClientRect().bottom)+1),n.scrollTop=0,await O(a()).toEqual([l[`label.columns.pin.left`],l[`label.columns.pin.none`],`收发双方`,`运输`,`计费`,`时间`]);let u=i();await O(u.slice(0,8)).toEqual([`id`,`orderNo`,`status`,`tags`,`signed`,`trackingUrl`,`note`,`customer`]),await k.type(r,`运费`),await A(()=>O(i()).toEqual([`amount`])),await O(t.querySelector(`[data-slot="column-filtered"]`)?.textContent).toBe(l[`label.columns.filtered`]),await O(j(n).getByRole(`button`,{name:N(`label.columns.drag`,{field:`运费`})}).hasAttribute(`disabled`)).toBe(!0),await k.clear(r),await k.type(r,`zzz`),await A(()=>O(i()).toEqual([])),await O(t.querySelector(`[data-slot="column-none"]`)?.textContent).toContain(l[`label.field.none`]),await k.clear(r),await A(()=>O(i()).toEqual(u)),await k.keyboard(`{Escape}`)}function ut(e){return[...e.querySelectorAll(`thead [data-slot="column-label"]`)].map(e=>e.textContent?.trim()??``)}async function T(e){await A(async()=>{let t=e();if(await new Promise(e=>setTimeout(e,50)),e()!==t)throw Error(`The value is still moving.`)})}async function E(e){let t=e.closest(`[role="toolbar"]`);for(let n=0;n<80;n+=1){if(document.activeElement===e)return;if(t?.contains(document.activeElement))break;await k.tab()}for(let n=0;t&&n<20;n+=1){if(document.activeElement===e)return;await k.keyboard(`{ArrowRight}`)}if(document.activeElement!==e)throw Error(`Tab never reached the target.`)}function dt(e){for(let t=e.parentElement;t&&t!==e.ownerDocument.documentElement;t=t.parentElement){let e=getComputedStyle(t);if(e.position!==`static`&&e.zIndex!==`auto`||e.transform!==`none`||e.filter!==`none`||e.perspective!==`none`||e.isolation===`isolate`||e.mixBlendMode!==`normal`||e.contain.split(` `).some(e=>e===`paint`||e===`layout`||e===`strict`||e===`content`)||Number.parseFloat(e.opacity)<1)return t}return null}function ft(e){let t=e.getBoundingClientRect(),n=e.ownerDocument.elementFromPoint(Math.round(t.left+t.width/2),Math.round(t.top+t.height/2));return n!==null&&e.contains(n)}function pt(e){return[...e.querySelectorAll(`*`)].filter(e=>e.getBoundingClientRect().width>0)}function mt(e){return[...e.querySelector(`[data-slot="toolbar-arrangement"]`).children]}function ht(e){let t=e.querySelector(`[data-slot="toolbar-selection"]`);return[...t?[t]:[],...mt(e)]}function gt(e){let t=e.querySelector(`[data-slot="result-toolbar"]`),n=e.querySelector(`[data-slot="toolbar-arrangement"]`),r=t.getBoundingClientRect().right-parseFloat(getComputedStyle(t).paddingRight);return Math.abs(n.getBoundingClientRect().right-r)}function D(e){return e.querySelector(`[data-slot="tooltip-content"][data-open]`)}function _t(e,t){let n=e.getBoundingClientRect(),r=t.getBoundingClientRect();return n.left<r.right&&r.left<n.right&&n.top<r.bottom&&r.top<n.bottom}var vt,O,yt,k,A,j,bt,M,N,P,xt,St,F,I,L,R,z,B,V,H,U,W,G,K,q,J,Y,X,Z,Ct,wt,Tt,Et,Dt,Ot,kt,At,jt,Mt,Nt,Pt,Ft,It,Lt,Rt,zt,Bt,Vt,Ht,Ut,Wt,Gt,Kt,Q,qt,Jt,Yt,Xt,Zt,Qt,$t,en,tn,nn,rn,an,on,sn,cn,ln,un,dn,fn,pn,mn,hn,gn,_n,vn,yn,bn,xn,Sn,Cn,wn,Tn,En,Dn,On,kn,An,jn,Mn,Nn,Pn,Fn,In,Ln,Rn,zn,Bn,Vn,Hn,Un,Wn,Gn,Kn,qn,Jn,Yn,Xn,Zn,Qn,$n,er,tr,nr,rr,ir,ar,or,$,sr,cr,lr,ur;function dr(){return(dr=e((()=>{r(),i(),be(),p(),o(),ye(),re(),ee(),vt=t(),{expect:O,screen:yt,userEvent:k,waitFor:A,within:j}=__STORYBOOK_MODULE_TEST__,bt={...le,title:`View Engine/数据视图/Record 工作台/回归`,tags:[`!dev`,`!autodocs`,`test`],parameters:{...le.parameters}},M=[`SO-1003`,`SO-1005`,`SO-1001`,`SO-1006`],N=(e,t)=>a(l,e,t),P=e=>e.querySelector(`[data-slot="record-pagination"]`),xt=4.5,St={...y,play:async({canvasElement:e})=>{let t=j(e),n=await t.findByRole(`table`);await A(()=>O(h(n,`订单号`)).toEqual(M)),await O(ne(g(n,`金额`))).toBe(6470),await O(it(n)).toEqual([l[`label.summary.scope.total`]]),await O(g(n,`金额`)).toContain(l[`label.summary.fn.SUM`]),await O(tt(n,`状态`)).toHaveTextContent(`待出库`),await O(tt(n,`金额`)).toBeNull(),await O(C(n,`金额`)).toHaveAttribute(`aria-sort`,`descending`),await O(C(n,`订单号`).querySelector(`[data-slot="sort-available"]`)).not.toBeNull(),await O(C(n,`订单号`)).not.toHaveAttribute(`aria-sort`),await et(n,`订单号`),await A(()=>O(rt(n,`订单号`)).toBe(`2`)),await O(rt(n,`金额`)).toBe(`1`),await O(C(n,`金额`)).toHaveAttribute(`aria-sort`,`descending`),await O(C(n,`订单号`)).not.toHaveAttribute(`aria-sort`),await O(C(n,`订单号`).querySelector(`button`).getAttribute(`aria-label`)).toContain(N(`label.sort.at`,{position:2,count:2})),await O(h(n,`订单号`)).toEqual(M),await O(t.getByRole(`button`,{name:l[`label.export.title`]})).toBeVisible(),await O(at(e)).toEqual([`view-header`,`applied-bar`,`result-toolbar`,`record-pagination`]);let r=e.querySelector(`[data-slot="view-header"]`);await O(r).toHaveTextContent(`待出库订单`),await O(r).toHaveTextContent(l[`label.scope.tag.shared`]);let i=t.getByRole(`button`,{name:RegExp(`^${l[`label.filter.panel`]}`)});await O(i).toHaveAttribute(`aria-expanded`,`false`),await O(t.getByRole(`region`,{name:l[`label.applied.title`]})).toHaveTextContent(`待出库`);let a=P(e);await O(a).toHaveTextContent(N(`label.pagination.total`,{total:4})),await O(a).toHaveTextContent(N(`label.toolbar.page-of`,{index:1,pages:1})),await O(j(a).queryByRole(`button`,{name:l[`label.toolbar.next`]})).toBeNull(),await O(j(a).queryByRole(`button`,{name:l[`label.toolbar.previous`]})).toBeNull(),await O(j(a).getByRole(`combobox`,{name:l[`label.pagination.page-size`]})).toBeVisible(),await k.click(i),await O(await t.findByRole(`button`,{name:l[`label.filter.apply`]})).toBeVisible(),await O(at(e)).toEqual([`view-header`,`editor-band`,`applied-bar`,`result-toolbar`,`record-pagination`])}},F={...y,play:async({canvasElement:e})=>{let t=j(e),n=await t.findByRole(`table`);await A(()=>O(h(n,`订单号`)).toEqual(M)),await k.click(t.getByRole(`button`,{name:RegExp(`^${l[`label.filter.panel`]}`)}));let r=await t.findByRole(`button`,{name:l[`label.filter.apply`]});await k.click(t.getByRole(`button`,{name:N(`label.filter.remove-of`,{field:`状态`})})),await A(()=>O(r.querySelector(`[data-slot="pending-dot"]`)).not.toBeNull()),await k.click(C(n,`订单号`).querySelector(`button`)),await O(h(n,`订单号`)).toEqual(M),await O(C(n,`金额`)).toHaveAttribute(`aria-sort`,`descending`),await O(C(n,`订单号`)).not.toHaveAttribute(`aria-sort`),await O(C(n,`订单号`).querySelector(`button`)).toHaveAttribute(`aria-label`,`${N(`label.sort.descending`,{field:`订单号`})} · ${l[`label.sort.waiting.asc`]}`),await O(t.getByRole(`button`,{name:N(`label.sort.button`,{field:`订单号`,direction:l[`label.sort.asc`]})})).toBeVisible(),await O(r.querySelector(`[data-slot="pending-dot"]`)).not.toBeNull(),await k.click(r),await A(()=>O(h(n,`订单号`)).toContain(`SO-1002`));let i=h(n,`订单号`);await O(i.length).toBeGreaterThan(M.length),await O(i).toEqual([...i].sort()),await O(C(n,`订单号`)).toHaveAttribute(`aria-sort`,`ascending`),await O(r.querySelector(`[data-slot="pending-dot"]`)).toBeNull()}},I={...y,play:async({canvasElement:e})=>{let t=j(e);await t.findByRole(`table`),await k.click(t.getByRole(`button`,{name:RegExp(`^${l[`label.filter.panel`]}`)})),await A(()=>O(e.querySelector(`[data-slot="editor-band"]`)).not.toBeNull());let n=e.querySelector(`main`);await O(e.querySelectorAll(`main`)).toHaveLength(1);let r=getComputedStyle(n);await O(r.rowGap).toBe(`16px`),await O(r.gap).toBe(`16px`);let i=e.querySelector(`[data-slot="result-block"]`);await O(i).toHaveAttribute(`data-framed`,`true`),await O(getComputedStyle(i).rowGap).toBe(`normal`),await O(getComputedStyle(i).borderTopWidth).toBe(`1px`);let a=e.querySelector(`[data-slot="result-toolbar"]`);await O(getComputedStyle(a).borderBottomWidth).toBe(`1px`);let o=e.querySelector(`[data-slot="record-pagination"]`);await O(getComputedStyle(o).borderTopWidth).toBe(`1px`)}},L={...Ie,play:async({canvasElement:e})=>{let t=await j(e).findByRole(`table`);await A(()=>O(h(t,`订单号`)).toHaveLength(6));let n=tt(t,`状态`);await O(n).toHaveTextContent(`待出库`),await O(n).toHaveAttribute(`data-tone`,`warning`);let r=w(t,`状态`,1).querySelector(`[data-slot="badge"]`);await O(r).toHaveTextContent(`已取消`),await O(r).toHaveAttribute(`data-tone`,`danger`),await O(r).toHaveAttribute(`data-variant`,`destructive`),await O(nt(w(t,`标记`,0))).toEqual([`加急`,`易碎`]),await O(nt(w(t,`标记`,5))).toEqual([`vip`]);let i=w(t,`运单`,0).querySelector(`a`);await O(i).toHaveAttribute(`target`,`_blank`),await O(i).toHaveAttribute(`rel`,`noopener noreferrer`),await O(i).toHaveAccessibleName(`https://example.com/track/SO-1001`),await O(w(t,`运单`,4).querySelector(`a`)).toBeNull(),await O(w(t,`运单`,4)).toHaveTextContent(`javascript:alert(1)`);let a=w(t,`备注`,4).querySelector(`[data-slot="cell-text"]`);await O(a).toHaveAttribute(`title`,a.textContent),await O(getComputedStyle(a).whiteSpace).toBe(`nowrap`),await O(getComputedStyle(a).textOverflow).toBe(`ellipsis`),await O(a.scrollWidth).toBeGreaterThan(a.clientWidth);let o=w(t,`订单号`,0);await O(o).toHaveTextContent(`SO-1001`),await O(o.querySelector(`[data-slot="badge"]`)).toBeNull(),await O(o.querySelector(`a`)).toBeNull();let s=o.querySelector(`[data-slot="cell-copy"]`);await O(s).toHaveAccessibleName(N(`label.copy-of`,{value:`SO-1001`}))}},R={...Le,play:async({canvasElement:e})=>{let t=await j(e).findByRole(`table`);await A(()=>O(h(t,`订单号`)).toHaveLength(6));let n=e=>h(t,`订单号`).findIndex(t=>t.startsWith(e));await O(nt(w(t,`明细`,n(`SO-1001`)))).toEqual([`TEA-01`,`CUP-12`]),await O(nt(w(t,`明细`,n(`SO-1003`)))).toEqual([`CARD-07`,`TEA-01`,`BOX-02`]);let r=w(t,`明细`,n(`SO-1004`));await O(nt(r)).toEqual([`CUP-12`,`PLATE-05`]);let i=r.querySelector(`[data-slot="cell-elements-more"]`);await O(i).toHaveTextContent(`+3`),await O(i).toHaveAttribute(`aria-hidden`,`true`);let a=r.querySelector(`[data-slot="cell-elements"]`);await O(a).toHaveAttribute(`title`,`CUP-12、PLATE-05、BOWL-04、SPOON-09、TRAY-01`),await O(getComputedStyle(a).flexWrap).toBe(`nowrap`),await O(r).toHaveTextContent(/BOWL-04、SPOON-09、TRAY-01/);let o=e=>t.tBodies[0].rows[n(e)].getBoundingClientRect().height;await O(o(`SO-1004`)).toBe(o(`SO-1006`)),await O(w(t,`包裹`,n(`SO-1005`))).toHaveTextContent(`3 项`),await O(w(t,`包裹`,n(`SO-1001`))).toHaveTextContent(`1 项`),await O(w(t,`包裹`,n(`SO-1002`))).toHaveTextContent(``),await O(t.textContent).not.toMatch(/[{}]/)}},z={...Ie,play:async({canvasElement:e})=>{let t=await j(e).findByRole(`table`);await A(()=>O(h(t,`订单号`)).toHaveLength(6));let n=w(t,`订单号`,0),r=n.querySelector(`[data-slot="cell-copy"]`);await O(getComputedStyle(r).opacity).toBe(`0`),await O(getComputedStyle(r).display).not.toBe(`none`),await O(r.tabIndex).toBeGreaterThanOrEqual(0),await O(n.parentElement.className).toContain(`group/row`),await O(n.querySelector(`[data-slot="cell-copyable"]`).className).toContain(`group/copyable`),await k.click(r);let i=N(`label.copied`,{});await A(()=>O(r).toHaveAccessibleName(i)),await O(e.querySelector(`[data-slot="cell-copy-announcement"]`)).toHaveTextContent(i);try{await A(async()=>O(await navigator.clipboard.readText()).toBe(`SO-1001`))}catch{}await A(()=>O(r).toHaveAccessibleName(N(`label.copy-of`,{value:`SO-1001`})),{timeout:4e3})}},B={...Pe,play:async({canvasElement:e})=>{let t=await j(e).findByRole(`table`);await A(()=>O(h(t,`订单号`)).toHaveLength(6));let n=e=>new Intl.DateTimeFormat(`zh-CN`,{dateStyle:`medium`,timeStyle:`medium`}).format(new Date(e)),r=n(`2026-09-15T02:10:00.000Z`),i=n(`2026-09-17T08:45:00.000Z`);await O(it(t)).toEqual([l[`label.summary.scope.total`]]);let a=g(t,`创建时间`);await O(a).toContain(l[`label.summary.fn.date.MIN`]),await O(a).toContain(l[`label.summary.fn.date.MAX`]),await O(a).toContain(r),await O(a).toContain(i),await O(a).not.toContain(`2026-09-15T02:10`),await O(a).not.toContain(l[`label.summary.fn.MIN`]),await O(ne(g(t,`金额`))).toBe(10230),await O(g(t,`金额`)).toContain(l[`label.summary.fn.SUM`])}},V={...Ve,play:async({canvasElement:e})=>{let t=await j(e).findByRole(`table`);await A(()=>O(h(t,`订单号`)).toEqual([`SO-1001`,`SO-1002`]));let n=P(e);await O(n).toHaveTextContent(N(`label.pagination.total`,{total:6})),await O(n).toHaveTextContent(N(`label.toolbar.page-of`,{index:1,pages:3})),await O(it(t)).toEqual([l[`label.summary.scope.page`],l[`label.summary.scope.total`]]),await O(ne(g(t,`金额`))).toBe(10230),await O(ne(te(t,`金额`))).toBeLessThan(10230),await O(j(n).getByRole(`combobox`,{name:l[`label.pagination.page-size`]})).toHaveTextContent(N(`label.pagination.page-size-option`,{size:2}));let r=j(n).getByRole(`button`,{name:l[`label.toolbar.previous`]}),i=j(n).getByRole(`button`,{name:l[`label.toolbar.next`]});await O(r).toBeDisabled(),await k.click(i),await A(()=>O(h(t,`订单号`)).toEqual([`SO-1003`,`SO-1004`])),await O(P(e)).toHaveTextContent(N(`label.toolbar.page-of`,{index:2,pages:3})),await O(P(e)).toHaveTextContent(N(`label.pagination.total`,{total:6})),await O(j(P(e)).getByRole(`button`,{name:l[`label.toolbar.previous`]})).toBeEnabled(),await k.click(j(P(e)).getByRole(`button`,{name:l[`label.toolbar.previous`]})),await A(()=>O(h(t,`订单号`)).toEqual([`SO-1001`,`SO-1002`])),await O(P(e)).toHaveTextContent(N(`label.toolbar.page-of`,{index:1,pages:3}));let a=j(P(e)).getByRole(`textbox`,{name:l[`label.pagination.go-to`]});await O(a).toHaveValue(`1`),await k.clear(a),await k.type(a,`3{Enter}`),await A(()=>O(h(t,`订单号`)).toEqual([`SO-1005`,`SO-1006`])),await O(P(e)).toHaveTextContent(N(`label.toolbar.page-of`,{index:3,pages:3}));let o=j(P(e)).getByRole(`textbox`,{name:l[`label.pagination.go-to`]});await k.clear(o),await k.type(o,`99{Enter}`),await O(o).toHaveValue(`3`),await O(P(e)).toHaveTextContent(N(`label.toolbar.page-of`,{index:3,pages:3}))}},H={...ve,play:async({canvasElement:e})=>{let t=await j(e).findByRole(`table`);await A(()=>O(h(t,`订单号`)).toEqual([`SO-1001`,`SO-1002`]));let n=P(e);await O(n).toHaveTextContent(N(`label.pagination.total`,{total:6})),await O(n).toHaveTextContent(N(`label.toolbar.page-of`,{index:1,pages:2}));let r=N(`label.pagination.window`,{count:4});await O(n).toHaveTextContent(r),await O(n).toHaveAccessibleDescription(r);let i=j(n).getByRole(`textbox`,{name:l[`label.pagination.go-to`]});await k.clear(i),await k.type(i,`3{Enter}`),await A(()=>O(h(t,`订单号`)).toEqual([`SO-1003`,`SO-1004`])),await O(i).toHaveValue(`2`),await O(P(e)).toHaveTextContent(N(`label.toolbar.page-of`,{index:2,pages:2})),await O(j(P(e)).getByRole(`button`,{name:l[`label.toolbar.next`]})).toBeDisabled()}},U={...y,args:{...y.args,narrowHost:!0,narrowWidth:1300},play:async({canvasElement:e})=>{let t=await j(e).findByRole(`table`);await A(()=>O(h(t,`订单号`)).toEqual(M));let n=e.querySelector(`[data-slot="record-table"]`);await O(n.clientWidth).toBeGreaterThan(800),await O(n.scrollWidth).toBeLessThanOrEqual(n.clientWidth+1);let r=[...t.querySelectorAll(`thead tr:first-child>th`)],i=r[r.length-1];await O(i.dataset.column).toBe(`filler`);let a=r.slice(0,-1),o=Math.max(...a.map(e=>e.getBoundingClientRect().width));await O(o).toBeLessThan(200),await O(i.getBoundingClientRect().width).toBeGreaterThan(400);let s=n.getBoundingClientRect().right;for(let e of t.querySelectorAll(`tr`))await O(Math.round(e.getBoundingClientRect().right)).toBe(Math.round(s));let c=a[a.length-1];await O(c.dataset.pin).toBe(`right`),await O(n.hasAttribute(`data-overflowing`)).toBe(!1),await O(getComputedStyle(c).boxShadow).toBe(`none`),await O(Math.round(i.getBoundingClientRect().left-c.getBoundingClientRect().right)).toBe(0);for(let e of a){let t=e.querySelector(`[data-slot="column-label"]`);t&&await O(t.scrollWidth).toBeLessThanOrEqual(Math.ceil(t.getBoundingClientRect().width))}}},W={...y,decorators:[e=>(0,vt.jsx)(`div`,{"data-testid":`host-frame`,style:{display:`grid`,height:640},children:(0,vt.jsx)(e,{})})],play:async({canvasElement:e})=>{let t=j(e),n=await t.findByRole(`table`);await A(()=>O(h(n,`订单号`)).toEqual(M));let r=t.getByTestId(`host-frame`),i=e.querySelector(`[data-slot="result-block"]`),a=e.querySelector(`[data-slot="record-table"]`),o=e.querySelector(`[data-slot="record-pagination"]`),s=e=>e.getBoundingClientRect().bottom;await A(()=>O(Math.abs(s(i)-s(r))).toBeLessThanOrEqual(1)),await O(Math.abs(s(o)-s(i))).toBeLessThanOrEqual(1),await A(()=>O(n.querySelector(`[data-slot="row-room"]`)).not.toBeNull()),await O(Math.abs(s(n.tFoot)-s(a))).toBeLessThanOrEqual(1),await O(n.tBodies[0].rows).toHaveLength(M.length)}},G={...ge,play:async({canvasElement:e})=>{let t=j(e);await t.findByRole(`table`),await k.click(t.getByRole(`button`,{name:l[`label.layout.cards`]}));let n=await A(()=>{let t=e.querySelector(`[data-slot="record-cards"]`);if(!t)throw Error(`卡片区还没出来`);return t}),r=await A(()=>{let e=[...n.querySelectorAll(`[data-slot="card"]`)];return O(e.length).toBeGreaterThan(20),e});for(let e of r)await O(e.scrollHeight).toBeLessThanOrEqual(e.clientHeight+1);await O(n.scrollHeight).toBeGreaterThan(n.clientHeight+1)}},K={...ge,decorators:[e=>(0,vt.jsx)(`div`,{"data-content-sized":!0,style:{alignSelf:`start`},children:(0,vt.jsx)(e,{})})],play:async({canvasElement:e})=>{let t=await j(e).findByRole(`table`);await A(()=>O(t.tBodies[0].rows.length).toBeGreaterThan(10));let n=e.querySelector(`[data-content-sized] > .fve-root`),r=e.querySelector(`[data-slot="record-table"]`),i=e.querySelector(`[data-slot="record-pagination"]`),a=parseFloat(getComputedStyle(document.documentElement).fontSize);await A(()=>O(Math.abs(n.getBoundingClientRect().height-36*a)).toBeLessThanOrEqual(1)),await A(()=>O(r.scrollHeight).toBeGreaterThan(r.clientHeight+1));let o=n.getBoundingClientRect().bottom;await O(Math.abs(i.getBoundingClientRect().bottom-o)).toBeLessThanOrEqual(1);for(let e of t.querySelectorAll(`tfoot tr`))await O(e.getBoundingClientRect().bottom).toBeLessThanOrEqual(o)}},q={...We,play:async({canvasElement:e})=>{let t=j(e),n=await t.findByRole(`table`);await A(()=>O(h(n,`订单号`)).toEqual(M)),await O(j(e.querySelector(`[data-slot="view-header"]`)).getByRole(`button`,{name:`新建订单`})).toBeVisible(),await O(t.queryByRole(`button`,{name:`导出所选`})).toBeNull();let r=e.querySelector(`[data-slot="view-controls"]`),i=r.querySelector(`[data-slot="separator"]`),a=e=>e.top+e.height/2;await O(Math.abs(a(i.getBoundingClientRect())-a(r.getBoundingClientRect()))).toBeLessThanOrEqual(1),await O(t.getAllByRole(`button`,{name:`打开`})).toHaveLength(M.length),await O(t.getByRole(`columnheader`,{name:l[`label.toolbar.actions`]}).className).toContain(`sticky`),await k.click(t.getByLabelText(l[`label.record.select-all`])),await O(await t.findByRole(`button`,{name:`导出所选`})).toBeVisible()}},J={...We,play:async({canvasElement:e})=>{let t=j(e),n=await t.findByRole(`table`);await A(()=>O(h(n,`订单号`)).toEqual(M));let r=t=>A(()=>{let n=e.querySelector(`[data-slot="bulk-status"][data-state="${t}"]`);return O(n).not.toBeNull(),n});await k.click(t.getByLabelText(l[`label.record.select-all`])),await k.click(await t.findByRole(`button`,{name:`导出所选`})),await O(t.getByRole(`button`,{name:`导出所选`})).toBeDisabled();let i=await r(`running`);await O(j(i).getByRole(`button`,{name:l[`label.bulk.stop`]})).toBeVisible();let a=await r(`settled`);await O(a).toHaveAttribute(`role`,`status`),await O(a).toHaveAttribute(`data-tone`,`info`),await O(a).toHaveTextContent(N(`label.bulk.done`,{done:M.length})),await O(t.queryByRole(`button`,{name:`导出所选`})).toBeNull(),await k.click(j(a).getByRole(`button`,{name:l[`label.bulk.dismiss`]})),await A(()=>O(e.querySelector(`[data-slot="bulk-status"]`)).toBeNull()),await k.click(t.getByRole(`button`,{name:/^全部订单/})),await A(()=>O(h(t.getByRole(`table`),`订单号`)).toContain(`SO-1002`)),await k.click(t.getByLabelText(l[`label.record.select-all`])),await k.click(await t.findByRole(`button`,{name:`导出所选`}));let o=await r(`settled`);await O(o).toHaveAttribute(`data-tone`,`warning`),await O(o).toHaveTextContent(N(`label.bulk.reason`,{reason:`已取消的订单不能导出。`,count:1})),await O(o).toHaveTextContent(l[`label.bulk.left`]),await A(()=>O(t.getByLabelText(N(`label.record.select`,{key:`SO-1002`}))).toBeChecked()),await O(t.getByLabelText(N(`label.record.select`,{key:`SO-1001`}))).not.toBeChecked()}},Y={...We,play:async({canvasElement:e})=>{let t=j(e),n=await t.findByRole(`table`);await A(()=>O(h(n,`订单号`)).toEqual(M));let r=()=>[...n.querySelectorAll(`tbody [role="checkbox"]`)],i=()=>r().map(e=>e.getAttribute(`aria-checked`)===`true`),a=()=>[...n.tBodies[0].rows].map(e=>e.getAttribute(`data-state`)===`selected`),o=k.setup();await o.click(r()[0]),await o.keyboard(`{Shift>}`),await o.click(r()[2]),await o.keyboard(`{/Shift}`),await A(()=>O(i()).toEqual([!0,!0,!0,!1])),await O(a()).toEqual([!0,!0,!0,!1]),await O(await t.findByText(N(`label.toolbar.selected`,{count:3}))).toBeVisible(),r()[3].focus(),await o.keyboard(`{Shift>}[Space]{/Shift}`),await A(()=>O(i()).toEqual([!0,!0,!0,!0])),await o.keyboard(`{Shift>}`),await o.click(r()[1]),await o.keyboard(`{/Shift}`),await A(()=>O(i()).toEqual([!1,!1,!0,!0])),await O(await t.findByText(N(`label.toolbar.selected`,{count:2}))).toBeVisible();let s=new Set(r().map(e=>e.getAttribute(`aria-describedby`)));await O(s.size).toBe(1),await O(document.getElementById([...s][0])).toHaveTextContent(l[`label.record.select.hint`])}},X={...We,play:async({canvasElement:e})=>{let t=j(e);await t.findByRole(`table`);let n=t.getByRole(`button`,{name:`新建订单`});await k.click(t.getByRole(`button`,{name:RegExp(`^${l[`label.filter.panel`]}`)}));let r=await t.findByRole(`button`,{name:l[`label.filter.apply`]});await T(()=>getComputedStyle(r).backgroundColor);let i=getComputedStyle(r).backgroundColor,a=[...e.querySelectorAll(`button`)].filter(e=>getComputedStyle(e).backgroundColor===i).map(e=>e.textContent?.trim());await O(a,`painted ${i}`).toEqual([l[`label.filter.apply`]]);let o=getComputedStyle(n);await O(o.backgroundColor).not.toBe(i),await O(o.borderTopWidth).not.toBe(`0px`)}},Z={...Ue,play:async({canvasElement:e})=>{let t=j(e);await t.findByRole(`table`),await k.click(t.getByRole(`button`,{name:l[`label.manage.open`]})),await j(document.body).findByRole(`dialog`);let n=e=>{let t=[...document.querySelectorAll(`[data-slot="view-manager-row"]`)].find(t=>t.textContent?.includes(e)||[...t.querySelectorAll(`input`)].some(t=>t.value.includes(e)));if(!t)throw Error(`no row for ${e}`);return t};await A(()=>O(n(`待出库订单`)).toBeDefined()),await O(n(`仓库金额分布`)).toBeDefined(),await O(j(n(`全部订单`)).queryByRole(`button`,{name:l[`label.manage.delete`]})).toBeNull();let r=new Map;for(let e of document.querySelectorAll(`[data-slot="view-manager-row"]`))for(let t of e.querySelectorAll(`[data-slot="view-manager-actions"] button`)){let e=t.getAttribute(`aria-label`)??``;r.set(e,[...r.get(e)??[],Math.round(t.getBoundingClientRect().x)])}let i=[...r].filter(([,e])=>e.length>1);await O(i.filter(([,e])=>new Set(e).size>1)).toEqual([]),await O(i.map(([e])=>e).includes(l[`label.manage.set-default`])).toBe(!0),await O(r.get(l[`label.manage.delete`]).length).toBeLessThan(r.get(l[`label.manage.set-default`]).length);let a=l[`label.manage.drag`].split(`{title}`)[0],o=[...document.querySelectorAll(`[data-slot="view-manager-row"] button[aria-label^="${a}"]`)];await O(o).toHaveLength(4),await O(new Set(o.map(e=>Math.round(e.getBoundingClientRect().x))).size).toBe(1),await k.click(j(n(`我盯的大额单`)).getByRole(`button`,{name:l[`label.manage.rename`]}));let s=j(n(`我盯的大额单`)).getByLabelText(N(`label.manage.rename-of`,{title:`我盯的大额单`}));await k.clear(s),await k.type(s,`大额单`),await k.click(j(n(`大额单`)).getByRole(`button`,{name:l[`label.manage.rename-confirm`]})),await A(()=>O(n(`大额单`).textContent).toContain(`大额单`)),await k.click(j(n(`待出库订单`)).getByRole(`button`,{name:l[`label.manage.set-default`]})),await A(()=>O(j(n(`待出库订单`)).getByRole(`button`,{name:l[`label.manage.unset-default`]})).toHaveAttribute(`aria-pressed`,`true`)),await O(j(n(`待出库订单`)).getByRole(`button`,{name:l[`label.manage.unset-default`]}).querySelector(`svg`).classList).toContain(`fill-current`),await O(j(n(`全部订单`)).getByRole(`button`,{name:l[`label.manage.set-default`]}).querySelector(`svg`).classList).not.toContain(`fill-current`);let c=[`全部订单`,`待出库订单`,`仓库金额分布`],u=e=>[...document.querySelectorAll(`[data-slot="view-manager-group"][data-audience="${e}"] [data-slot="view-manager-row"]`)].map(e=>e.textContent??``),d=()=>u(`shared`).map(e=>c.find(t=>e.includes(t))??e),f=d();await O(f).toHaveLength(3),await v(j(n(f[1])).getByRole(`button`,{name:N(`label.manage.drag`,{title:f[1]})}),n(f[0])),await A(()=>O(d()).toEqual([f[1],f[0],f[2]])),await O(u(`personal`)).toHaveLength(1),await O(u(`personal`)[0]).toContain(`大额单`),await k.click(j(n(`大额单`)).getByRole(`button`,{name:l[`label.manage.delete`]}));let p=(await j(document.body).findByText(l[`label.delete.consequence`])).closest(`[role="alertdialog"]`);await k.click(j(p).getByRole(`button`,{name:l[`label.delete.keep`]})),await A(()=>O(j(document.body).queryByText(l[`label.delete.consequence`])).toBeNull())}},Ct={...Ce,play:async({canvasElement:e})=>{let t=j(e);await O(await t.findByText(l[`label.record.empty-view`])).toBeVisible();let n=t.getByRole(`button`,{name:l[`label.record.empty-edit`]});await O(t.queryByRole(`button`,{name:l[`label.record.empty-clear`]})).toBeNull(),await k.click(n),await O(await t.findByRole(`button`,{name:/^应用/})).toBeVisible();let r=e.querySelector(`[data-slot="applied-bar"]`);await O(r.querySelectorAll(`[data-slot="badge"]`)).toHaveLength(1)}},wt={...y,play:async({canvasElement:e})=>{let t=j(e);await t.findByRole(`table`),await O(e.querySelector(`[data-slot="record-summaries"]`)).not.toBeNull(),await k.click(t.getByRole(`button`,{name:l[`label.layout.cards`]})),await A(()=>O(e.querySelector(`[data-slot="record-cards"]`)).not.toBeNull());let n=e.querySelector(`[data-slot="record-summaries"][data-layout="card"]`);await O(n).not.toBeNull(),await O(n).toHaveTextContent(l[`label.summary.scope.total`]),await O(n).not.toHaveTextContent(l[`label.summary.scope.page`]);let r=e.querySelector(`[data-control="columns"]`);await O(r).toHaveAccessibleName(l[`label.toolbar.card`]),await k.click(r);let i=await j(document.body).findByRole(`dialog`,{name:l[`label.card.title`]}),o=e.querySelectorAll(`[data-slot="card-field"][data-field="createdAt"]`).length;await O(o).toBe(0),await k.click(await j(i).findByRole(`checkbox`,{name:`创建时间`})),await A(()=>O(e.querySelectorAll(`[data-slot="card-field"][data-field="createdAt"]`).length).toBeGreaterThan(0));let s=e.querySelectorAll(`[data-slot="record-cards"] > *`);await O(e.querySelectorAll(`[data-slot="card-field"][data-field="createdAt"]`)).toHaveLength(s.length),await k.click(await j(i).findByRole(`button`,{name:a(l,`label.card.per-row-option`,{count:2})})),await A(()=>O(e.querySelector(`[data-slot="record-cards"]`)).toHaveClass(`sm:grid-cols-2`))}},Tt={...Be,play:async({canvasElement:e})=>{let t=j(e),n=await t.findByRole(`table`);await O(n.querySelectorAll(`[data-slot=skeleton]`).length).toBeGreaterThan(0),await O(n.querySelector(`thead`)).toBeNull(),await O(t.queryByRole(`checkbox`,{name:l[`label.record.select-all`]})).toBeNull(),await O(e.querySelector(`[data-slot=record-pagination]`)).toBeNull(),await A(()=>O(h(n,`订单号`)).toEqual(M),{timeout:5e3})}},Et={...Ae,play:async({canvasElement:e})=>{let t=j(e),n=await t.findByRole(`alert`);await O(n).toHaveTextContent(`仓储服务暂时不可用`),await O(j(n).getByRole(`button`,{name:l[`label.query.retry`]})).toBeVisible(),await O(t.getByRole(`button`,{name:/^待出库订单/})).toHaveAttribute(`aria-current`,`true`),await O(t.queryByRole(`button`,{name:l[`label.export.title`]})).toBeNull(),await O(t.getByRole(`button`,{name:l[`label.toolbar.columns`]})).toBeVisible();let r=e.querySelector(`[data-slot="result-block"]`);await O(n.getBoundingClientRect().right).toBeLessThanOrEqual(r.getBoundingClientRect().right)}},Dt={...de,play:async({canvasElement:e})=>{let t=await j(e).findByRole(`table`);await A(()=>O(h(t,`订单号`)).toEqual(M));let n=t.querySelector(`tfoot`);await O(it(t)).toEqual([l[`label.summary.scope.page`]]),await O([...n.querySelectorAll(`tr`)].map(e=>e.dataset.scope)).toEqual([`page`]),await O(ne(te(t,`金额`))).toBe(6470);let r=await A(()=>{let t=e.querySelector(`[data-slot="status-strip"]`);if(!t)throw Error(`no status strip`);return t});await O(r).toHaveAttribute(`role`,`status`),await O(r).toHaveTextContent(l[`runtime.summary.page-only`])}},Ot={...He,play:async({canvasElement:e})=>{let t=j(e),n=await t.findByRole(`alert`);await O(n).toHaveTextContent(`removedColumn`),await O(n).not.toHaveTextContent(l[`label.view.needs-fixing`]),await O(j(n).queryByRole(`button`,{name:/1/})).toBeNull(),await O(t.queryByRole(`table`)).toBeNull(),await O(e.querySelector(`[data-slot="result-block"]`)).toBeNull(),await O(t.queryByRole(`button`,{name:l[`label.export.title`]})).toBeNull(),await k.click(j(n).getByRole(`button`,{name:l[`label.status.open-columns`]})),await j(document.body).findByText(l[`label.columns.title`]),await A(()=>O(document.body.querySelectorAll(`[data-slot="column-setting"]`).length).toBeGreaterThan(0)),await k.keyboard(`{Escape}`),await A(()=>O(j(document.body).queryByText(l[`label.columns.title`])).toBeNull())}},kt={...ae,play:async({canvasElement:e})=>{let t=await A(()=>{let t=e.querySelector(`[data-slot="opening-skeleton"]`);if(!t)throw Error(`no opening skeleton`);return t});await O(t).toHaveAttribute(`aria-busy`,`true`),await O(j(t).getByRole(`status`)).toHaveTextContent(l[`label.workbench.opening`]),await O(t.querySelector(`[data-slot="view-header-skeleton"]`)).not.toBeNull();let n=t.querySelector(`[data-slot="result-block"]`);await O(n).toHaveAttribute(`data-framed`,`true`),await O(n.firstElementChild).toHaveAttribute(`data-slot`,`result-toolbar`),await O(n.querySelectorAll(`[data-slot="result-rows-skeleton"] [data-slot="skeleton"]`).length).toBe(3),await O(e.querySelector(`table`)).toBeNull(),await O(t.querySelector(`button`)).toBeNull()}},At={..._e,play:async({canvasElement:e})=>{let t=j(e),n=await A(()=>{let t=e.querySelector(`[data-slot="view-none"]`);if(!t)throw Error(`no empty work area`);return t}),r=t.getByRole(`navigation`,{name:s.title});await O(j(r).getByRole(`button`,{name:l[`label.view.new`]})).toBeVisible();let i=e.querySelector(`[data-slot="view-list-body"]`);await O(i.querySelector(`[data-slot="view-list-empty"]`)).toHaveTextContent(l[`label.view.none`]),await O(j(i).queryByRole(`button`)).toBeNull(),await O(j(i).queryByText(l[`label.view.none-hint`])).toBeNull(),await k.click(j(n).getByRole(`button`,{name:l[`label.view.new`]})),await k.click(await j(document.body).findByRole(`menuitem`,{name:l[`label.kind.record`]})),await O(await t.findByRole(`heading`,{level:2,name:l[`label.view.new-title`]})).toBeVisible(),await O(t.getByText(l[`label.header.new-view`])).toBeVisible(),await O(await t.findByRole(`button`,{name:l[`label.filter.apply`]})).toBeVisible(),await t.findByRole(`table`),await k.click(t.getByRole(`button`,{name:l[`label.save.save`]}));let a=await j(document.body).findByRole(`dialog`,{name:l[`label.save.first-heading`]}),o=j(a).getByRole(`textbox`,{name:l[`label.save.title`]});await O(o).toHaveValue(l[`label.view.new-title`]),await k.clear(o),await k.type(o,`大额单`),await k.click(j(a).getByRole(`button`,{name:l[`label.save.save`]})),await O(await t.findByRole(`heading`,{level:2,name:`大额单`})).toBeVisible(),await O(await j(r).findByRole(`button`,{name:/大额单/})).toBeVisible(),await O(t.queryByText(l[`label.header.new-view`])).not.toBeInTheDocument()}},jt={...Ne,play:async({canvasElement:e})=>{let t=j(e),n=await t.findByRole(`alert`);await O(n).toHaveTextContent(l[`label.view.unopenable`]),await O(n).toHaveAttribute(`data-slot`,`view-unopenable`),await O(n.querySelector(`svg`)).not.toBeNull();let r=j(n).getByRole(`button`,{name:l[`label.view.open-default`]});await k.click(r),await t.findByRole(`table`),await O(t.queryByRole(`alert`)).toBeNull()}},Mt={...je,play:async({canvasElement:e})=>{let t=j(e);await t.findByRole(`table`),await O(e.querySelector(`[data-slot="view-header"]`)).toHaveTextContent(c[`label.scope.tag.shared`]),await O(t.getByRole(`button`,{name:RegExp(`^${c[`label.filter.panel`]}`)})).toBeVisible(),await O(t.getByRole(`button`,{name:c[`label.toolbar.refresh`]})).toBeVisible();let n=P(e);await O(n).toHaveTextContent(a(c,`label.pagination.total`,{total:4})),await O(j(n).getByRole(`combobox`,{name:c[`label.pagination.page-size`]})).toHaveTextContent(a(c,`label.pagination.page-size-option`,{size:20})),await O(t.queryByRole(`button`,{name:l[`label.toolbar.refresh`]})).toBeNull();let r=t.getByRole(`region`,{name:c[`label.applied.title`]}),i=`状态 ${c[`label.relation.is`]} 待出库`;await O(r).toHaveTextContent(i),await O(r).not.toHaveTextContent(l[`label.relation.is`]),await k.click(j(r).getByRole(`button`,{name:c[`label.filter.unset-of`].replace(`{condition}`,i)})),await A(()=>O(t.getByRole(`region`,{name:c[`label.applied.title`]})).toHaveTextContent(c[`label.applied.all`])),await A(()=>O(h(t.getByRole(`table`),`订单号`)).toHaveLength(6))}},Nt={...ue,play:async({canvasElement:e})=>{let t=j(e),r=await t.findByRole(`table`);await A(()=>O(h(r,`订单号`)).toEqual(M)),await k.click(e.querySelector(`[data-control="columns"]`));let i=j(document.body);await O(i.getAllByRole(`heading`,{level:3}).map(e=>e.textContent)).toEqual([l[`label.columns.pin.left`],l[`label.columns.pin.none`]]),await O(i.queryByRole(`checkbox`,{name:N(`label.columns.show`,{field:l[`label.toolbar.actions`]})})).toBeNull();let a=i.getByRole(`button`,{name:N(`label.columns.pin`,{field:`订单号`})});await O(a).toBeDisabled(),await O(a).toHaveAttribute(`aria-pressed`,`true`),i.getByRole(`button`,{name:N(`label.columns.drag`,{field:`状态`})}).focus(),await k.keyboard(`{ArrowUp}`),await O(document.querySelector(`[data-slot="column-announcement"]`)).toHaveTextContent(N(`label.columns.moved`,{field:`状态`,index:2,total:4}));let o=i.getByRole(`button`,{name:N(`label.columns.pin`,{field:`金额`})});await O(o).toBeEnabled(),await O(o).toHaveAttribute(`aria-pressed`,`false`);let s=N(`label.columns.pin`,{field:`仓库`});await k.click(i.getByRole(`button`,{name:s})),await A(()=>O(i.getByRole(`button`,{name:s})).toHaveAttribute(`aria-pressed`,`true`)),await O(document.querySelector(`[data-slot="column-announcement"]`)).toHaveTextContent(N(`label.columns.pinned.left`,{field:`仓库`})),await k.click(i.getByRole(`combobox`,{name:N(`label.columns.summary`,{field:`金额`})})),await k.click(await i.findByRole(`option`,{name:l[`label.summary.fn.AVG`]})),await k.keyboard(`{Escape}`),await k.click(e.querySelector(`[data-control="sort"]`)),await k.click(await j(document.body).findByRole(`button`,{name:N(`label.sort.direction`,{field:`金额`})})),await k.keyboard(`{Escape}`),await A(()=>O(_(t.getByRole(`table`))).toEqual([`订单号`,`仓库`,`状态`,`金额`])),await A(()=>O(h(t.getByRole(`table`),`订单号`)).toEqual([...M].reverse())),await k.click(t.getByRole(`button`,{name:l[`label.save.save`]})),await qe(),await A(async()=>{let e=await n.current.get(`orders-pending`);O(e.config).toMatchObject({table:{columns:[{field:`id`},{field:`status`},{field:`warehouse`,pinned:!0},{field:`amount`}]},summaries:[{field:`amount`,fn:`AVG`}],sort:[{field:`amount`,direction:`ASC`}]})})}},Pt={...ue,play:async({canvasElement:e})=>{let t=j(e),r=await t.findByRole(`table`);await A(()=>O(_(r)).toEqual([`订单号`,`仓库`,`状态`,`金额`])),await k.click(e.querySelector(`[data-control="columns"]`));let i=await j(document.body).findByRole(`button`,{name:N(`label.columns.drag`,{field:`仓库`})}),a=[...document.querySelectorAll(`[data-slot="column-region"][data-region="scrolling"] [data-slot="column-setting"]`)].filter(e=>e.querySelector(`button`)?.hasAttribute(`disabled`)===!1);await O(a.map(e=>e.dataset.field)).toEqual([`warehouse`,`status`,`amount`]),await v(i,a[1]),await A(()=>O(_(t.getByRole(`table`))).toEqual([`订单号`,`状态`,`仓库`,`金额`])),await k.keyboard(`{Escape}`),await k.click(t.getByRole(`button`,{name:l[`label.save.save`]})),await qe(),await A(async()=>{let e=await n.current.get(`orders-pending`);O(e.config.table.columns.map(e=>e.field)).toEqual([`id`,`status`,`warehouse`,`amount`])})}},Ft={...ue,play:async({canvasElement:e})=>{let t=j(e),r=await t.findByRole(`table`);await A(()=>O(_(r)).toEqual([`订单号`,`仓库`,`状态`,`金额`]));let i=[`id`,`warehouse`,`status`,`amount`],a=()=>[...document.querySelectorAll(`[data-slot="column-setting"]`)].map(e=>e.getAttribute(`data-field`)).filter(e=>e!==null&&i.includes(e)),o=async()=>k.click(e.querySelector(`[data-control="columns"]`)),s=()=>j(document.body).getByRole(`checkbox`,{name:N(`label.columns.show`,{field:`仓库`})});await o(),await k.click(s()),await A(()=>O(_(t.getByRole(`table`))).toEqual([`订单号`,`状态`,`金额`])),O(a()).toEqual(i),await O(j(document.body).getByRole(`button`,{name:N(`label.columns.drag`,{field:`仓库`})})).toBeEnabled(),await k.keyboard(`{Escape}`),await k.click(t.getByRole(`button`,{name:l[`label.save.save`]})),await qe(),await A(async()=>{let e=await n.current.get(`orders-pending`);O(e.config.table.columns).toEqual([{field:`id`,pinned:!0},{field:`warehouse`,hidden:!0},{field:`status`},{field:`amount`}])}),await o(),await k.click(s()),await k.keyboard(`{Escape}`),await A(()=>O(_(t.getByRole(`table`))).toEqual([`订单号`,`仓库`,`状态`,`金额`]))}},It={...ue,play:async({canvasElement:e})=>{let t=j(e),n=await t.findByRole(`table`);await A(()=>O(h(n,`订单号`)).toEqual(M));let r=e.querySelector(`[data-control="sort"]`);await k.click(r);let i=j(document.body);await k.click(await i.findByRole(`button`,{name:l[`label.sort.add`]})),await k.click(await i.findByRole(`menuitem`,{name:`订单号`}));let a=()=>[...document.querySelectorAll(`[data-slot="sort-entry"]`)];await A(()=>O(a().map(e=>e.dataset.field)).toEqual([`amount`,`id`]));let o=i.getByRole(`button`,{name:N(`label.sort.drag`,{field:`金额`})});await v(o,a()[1]),await A(()=>O(a().map(e=>e.dataset.field)).toEqual([`id`,`amount`])),await A(()=>O(C(t.getByRole(`table`),`订单号`)).toHaveAttribute(`aria-sort`,`ascending`));let s=t.getByRole(`table`);await O(C(s,`金额`)).not.toHaveAttribute(`aria-sort`),await O(rt(s,`订单号`)).toBe(`1`),await O(rt(s,`金额`)).toBe(`2`),await O(h(s,`订单号`)).toEqual([...M].sort()),await k.keyboard(`{Escape}`),await O(r).toHaveTextContent(`订单号${N(`label.sort.more`,{count:1})}`),await O(r).toHaveAccessibleName(`${N(`label.sort.button`,{field:`订单号`,direction:l[`label.sort.asc`]})} ${N(`label.sort.more`,{count:1})}`)}},Lt={...ue,play:async({canvasElement:e})=>{let t=j(e),r=await t.findByRole(`table`);await A(()=>O(_(r)).toEqual([`订单号`,`仓库`,`状态`,`金额`]));let i=t.getByRole(`separator`,{name:N(`label.columns.resize`,{field:`仓库`})}),a=i.closest(`th`).getBoundingClientRect().width;await ie(i,80);let o=Math.round(a+80);await A(()=>{let e=t.getByRole(`separator`,{name:N(`label.columns.resize`,{field:`仓库`})}).closest(`th`),n=t.getByRole(`table`).tBodies[0].rows[0].cells[e.cellIndex];O([Math.round(e.getBoundingClientRect().width),Math.round(n.getBoundingClientRect().width)]).toEqual([o,o])}),await k.click(t.getByRole(`button`,{name:l[`label.save.save`]})),await qe(),await A(async()=>{let e=(await n.current.get(`orders-pending`)).config.table.columns.find(e=>e.field===`warehouse`);O(e?.width).toBe(o)})}},Rt={...pe,play:async({canvasElement:e})=>{let t=j(e);await Ge(e),await Ke(t);let n=await Ye(e);await O([...n.querySelectorAll(`button`)].map(e=>e.textContent?.trim())).toEqual([l[`label.conflict.theirs`],l[`label.conflict.copy`],l[`label.conflict.mine`]]);let r=[...n.querySelectorAll(`button`)].map(e=>{let t=getComputedStyle(e);return[t.backgroundColor,t.borderTopColor,t.color].join(` | `)});await O(new Set(r),r.join(`; `)).toHaveProperty(`size`,1),await S(j(n).getByRole(`button`,{name:l[`label.conflict.mine`]}));let i=await j(document.body).findByRole(`alertdialog`);await O(i).toHaveTextContent(l[`label.conflict.confirm-mine`]),await O(i).toHaveTextContent(Wt),await O(i).toHaveTextContent(Gt),await k.click(j(i).getByRole(`button`,{name:l[`label.conflict.mine`]})),await A(async()=>{let e=await b.current.get(`orders-pending`);O(e.config).toMatchObject({pageSize:20,table:{columns:[{field:`id`},{field:`warehouse`},{field:`status`},{field:`amount`}]}}),O(e.config.sort).toHaveLength(2)}),await A(()=>O(t.queryByText(l[`label.write.conflict`])).toBeNull())}},zt={...pe,play:async({canvasElement:e})=>{let t=j(e);await Ge(e),await Ke(t);let n=await Ye(e);await S(j(n).getByRole(`button`,{name:l[`label.conflict.theirs`]}));let r=await j(document.body).findByRole(`alertdialog`);await O(r).toHaveTextContent(l[`label.conflict.confirm-theirs`]),await k.click(j(r).getByRole(`button`,{name:l[`label.conflict.theirs`]})),await A(()=>O(t.queryByText(l[`label.write.conflict`])).toBeNull()),await O(t.queryByText(l[`label.header.unsaved`])).toBeNull(),await k.click(e.querySelector(`[data-control="columns"]`)),await O(await j(document.body).findByRole(`checkbox`,{name:N(`label.columns.show`,{field:`创建时间`})})).toBeChecked();let i=await b.current.get(`orders-pending`);await O(i.config.pageSize).toBe(50)}},Bt={...ce,play:async({canvasElement:e})=>{let t=j(e);await Ge(e),await Ke(t);let n=await Je(e,l[`label.write.unknown`]);await O([...n.querySelectorAll(`button`)].map(e=>e.textContent?.trim())).toEqual([l[`label.unknown.retry`],l[`label.unknown.leave`]]),await S(j(n).getByRole(`button`,{name:l[`label.unknown.retry`]})),await A(async()=>{let e=await b.current.get(`orders-pending`);O(e.config.sort).toHaveLength(2)}),await A(()=>O(t.queryByText(l[`label.write.unknown`])).toBeNull())}},Vt={...he,play:async({canvasElement:e})=>{let t=j(e);await Ge(e),await Ke(t);let n=await Je(e,l[`view.write.invalid`]);await O(n).toHaveTextContent(`这个视图由运维托管，不接受修改`),await O([...n.querySelectorAll(`button`)].map(e=>e.textContent?.trim())).toEqual([l[`label.rejected.dismiss`]]),await S(j(n).getByRole(`button`,{name:l[`label.rejected.dismiss`]})),await A(()=>O(t.queryByText(l[`view.write.invalid`],{exact:!1})).toBeNull()),await Ke(t),await A(async()=>{let e=await b.current.get(`orders-pending`);O(e.config.sort).toHaveLength(2)})}},Ht={...ke,play:async({canvasElement:e})=>{await Xe(j(e),`我盯的大额单`),await k.click(j(Ze(`我盯的大额单`)).getByRole(`button`,{name:l[`label.manage.rename`]}));let t=j(Ze(`我盯的大额单`)).getByLabelText(N(`label.manage.rename-of`,{title:`我盯的大额单`}));await k.clear(t),await k.type(t,`大额单`),await k.click(j(Ze(`大额单`)).getByRole(`button`,{name:l[`label.manage.rename-confirm`]}));let n=await Qe(`大额单`);await O([...n.querySelectorAll(`button`)].map(e=>e.textContent?.trim())).toContain(l[`label.manage.reload`]),await O(j(n).queryByRole(`button`,{name:l[`label.conflict.theirs`]})).toBeNull(),await O(j(n).queryByRole(`button`,{name:l[`label.conflict.copy`]})).toBeNull(),await S(j(n).getByRole(`button`,{name:l[`label.conflict.mine`]})),await A(async()=>O((await b.current.get(`orders-mine`)).title).toBe(`大额单`))}},Ut={...Se,play:async({canvasElement:e})=>{await Xe(j(e),`待出库订单`),await k.click(j(Ze(`待出库订单`)).getByRole(`button`,{name:l[`label.manage.delete`]}));let t=await $e();await O(t).toHaveTextContent(l[`label.delete.shared-consequence`]),await k.click(j(t).getByRole(`button`,{name:l[`label.manage.delete`]}));let n=await Qe(`待出库订单`);await A(()=>O(j(document.body).queryByRole(`alertdialog`)).toBeNull()),await S(j(n).getByRole(`button`,{name:l[`label.conflict.mine`]}));let r=await $e();await O((await b.current.list(`orders`)).map(e=>e.id)).toContain(`orders-pending`),await k.click(j(r).getByRole(`button`,{name:l[`label.manage.delete`]})),await A(async()=>O((await b.current.list(`orders`)).map(e=>e.id)).not.toContain(`orders-pending`))}},Wt=N(`label.conflict.summary.record`,{pageSize:20,layout:l[`label.layout.table`],columns:4,sorts:2}),Gt=N(`label.conflict.summary.record`,{pageSize:50,layout:l[`label.layout.table`],columns:5,sorts:0}),Kt=[`view-header`,`editor-band`,`applied-bar`,`result-toolbar`,`record-pagination`],Q=e=>getComputedStyle(e).backgroundColor,qt=e=>getComputedStyle(e).fontSize,Jt={...y,play:async({canvasElement:e})=>{let t=await j(e).findByRole(`table`),n=ot(e,`待出库订单`),r=e.querySelector(`[data-slot="view-group-heading"]`);await O(qt(n)).toBe(`13px`),await O(qt(r)).toBe(`13px`);let i=t.querySelector(`thead th`),a=e.querySelector(`[data-slot="badge"]`),o=e.querySelector(`[data-slot="record-pagination"]`);for(let e of[i,a,o])await O(qt(e)).toBe(`13px`);let s=t.querySelector(`tbody td`),c=e.querySelector(`[data-slot="view-list-title"]`);await O(qt(s)).toBe(`14px`),await O(qt(c)).toBe(`16px`);let l=[...e.querySelectorAll(`*`)].filter(e=>qt(e)===`12.8px`);await O(l).toHaveLength(0)}},Yt={...y,play:async({canvasElement:e})=>{await j(e).findByRole(`table`);let t=e.querySelector(`[data-slot="view-list"]`),n=e.querySelector(`[data-slot="view-surface"]`),r=Q(t);await O(r).not.toBe(`rgba(0, 0, 0, 0)`),await O(Q(n)).not.toBe(`rgba(0, 0, 0, 0)`),await O(r).not.toBe(Q(n)),await O(parseFloat(getComputedStyle(t).borderRightWidth)).toBeGreaterThan(0);let i=e.querySelector(`[data-slot="view-list-header"]`),a=e.querySelector(`[data-slot="view-header-block"]`);await O(parseFloat(getComputedStyle(i).borderBottomWidth)).toBeGreaterThan(0),await O(Math.abs(i.getBoundingClientRect().bottom-a.getBoundingClientRect().bottom)).toBeLessThanOrEqual(1);let o=ot(e,`待出库订单`);await O(o.getAttribute(`aria-current`)).toBe(`true`),await O(Q(o)).toBe(Q(n)),await O(Q(o)).not.toBe(r);let s=getComputedStyle(o);await O(s.boxShadow).not.toBe(`none`),await O(s.boxShadow).not.toContain(`inset`),await O(s.borderLeftColor).not.toBe(`rgba(0, 0, 0, 0)`),await O(s.borderLeftColor).toBe(s.borderRightColor),await O(getComputedStyle(o).fontWeight).toBe(`500`);let c=ot(e,`我盯的大额单`);await O(Q(c)).toBe(`rgba(0, 0, 0, 0)`),await O(c.className).toContain(`hover:bg-sidebar-accent`);let l=await A(()=>{let e=t.appendChild(document.createElement(`div`));e.style.backgroundColor=`var(--sidebar-accent)`;let n=Q(e);return e.remove(),n});await O(l).not.toBe(r),await O(l).not.toBe(Q(o)),await O(c.querySelector(`svg`)).not.toBeNull();let u=ot(e,`全部订单`);await O(u.querySelector(`[data-slot="view-system-tag"]`)).not.toBeNull(),await O(u.querySelector(`[data-slot="badge"]`)).toBeNull()}},Xt={...Ue,play:async({canvasElement:e})=>{let t=j(e);await t.findByRole(`table`),await O(e.querySelector(`[data-slot="view-default-star"]`)).toBeNull(),await k.click(t.getByRole(`button`,{name:l[`label.manage.open`]}));let n=[...(await j(document.body).findByRole(`dialog`)).querySelectorAll(`[data-slot="view-manager-row"]`)].find(e=>e.textContent?.includes(`我盯的大额单`));await k.click(j(n).getByRole(`button`,{name:l[`label.manage.set-default`]})),await A(()=>O(j(n).getByRole(`button`,{name:l[`label.manage.unset-default`]})).toHaveAttribute(`aria-pressed`,`true`)),await k.keyboard(`{Escape}`),await A(()=>O(document.body.querySelector(`[role="dialog"]`)).toBeNull());let r=ot(e,`我盯的大额单`),i=r.querySelector(`[data-slot="view-default-star"]`);await O(i).not.toBeNull(),await O(i.classList).toContain(`fill-current`),await O(getComputedStyle(i).color).not.toBe(getComputedStyle(r).color),await O(e.querySelectorAll(`[data-slot="view-default-star"]`)).toHaveLength(1),await O(r.textContent).toContain(l[`label.manage.default`])}},Zt={...x,play:async({canvasElement:e})=>{let t=await j(e).findByRole(`table`),n=()=>e.querySelector(`[data-slot="view-sidebar"]`),r=e.querySelector(`[data-narrow-host]`);await O(r.getBoundingClientRect().width).toBe(375),await O(n()).toBeNull(),await O(t.getBoundingClientRect().top-e.querySelector(`[data-slot="view-surface"]`).getBoundingClientRect().top).toBeLessThan(260);let i=e.querySelector(`[data-slot="view-switcher"]`);await O(i).not.toBeNull(),await O(getComputedStyle(i).justifyContent).toBe(`flex-start`);let a=i.querySelector(`span`);await O(i.getBoundingClientRect().right-a.getBoundingClientRect().right).toBeLessThan(40);let o=e.querySelector(`[data-slot="view-header"]`),s=e.querySelector(`[data-slot="view-controls"]`);await O(Math.abs(s.getBoundingClientRect().right-o.getBoundingClientRect().right)).toBeLessThanOrEqual(1)}},Qt={...x,args:{...x.args,narrowWidth:1e3},play:async({canvasElement:e})=>{let t=j(e);await t.findByRole(`table`);let n=()=>e.querySelector(`[data-slot="view-sidebar"]`),r=e.querySelector(`[data-narrow-host]`),i=async e=>{r.style.width=`${e}px`,await O(r.getBoundingClientRect().width).toBe(e),await new Promise(e=>setTimeout(e,200))};await O(r.getBoundingClientRect().width).toBe(1e3),await O(n()).not.toBeNull(),await i(608),await O(n()).toBeNull(),await i(1e3),await O(n()).not.toBeNull(),await k.click(t.getByRole(`button`,{name:l[`label.workbench.collapse-sidebar`]})),await O(n()).toBeNull(),await i(608),await i(1e3),await O(n()).toBeNull()}},$t={...y,play:async({canvasElement:e})=>{let t=j(e);await t.findByRole(`table`);let n=()=>e.querySelector(`[data-slot="view-sidebar"]`);await O(n()).not.toBeNull(),await k.click(t.getByRole(`button`,{name:l[`label.workbench.expand-view`]})),await A(()=>O(n()).toBeNull()),await O(t.getByRole(`button`,{name:l[`label.workbench.switch-view`]})).toBeVisible(),await k.click(t.getByRole(`button`,{name:l[`label.workbench.collapse-view`]})),await A(()=>O(n()).not.toBeNull())}},en={...Oe,args:{...Oe.args,collapsed:!1},play:async({canvasElement:e})=>{let t=j(e);await t.findByRole(`table`);let n=()=>e.querySelector(`[data-slot="view-sidebar"]`);await O(n()).not.toBeNull(),await k.click(t.getByRole(`button`,{name:l[`label.workbench.collapse-sidebar`]})),await O(n()).toBeNull();let r=e.querySelector(`[data-slot="view-identity"]`);await O(r).toHaveTextContent(`订单`),await O(j(r).getByRole(`button`,{name:l[`label.workbench.switch-view`]})).toBeVisible(),await O(r.querySelector(`[data-slot="save-actions"]`)).not.toBeNull(),await k.click(j(r).getByRole(`button`,{name:l[`label.workbench.switch-view`]}));let i=await j(document.body).findByRole(`menu`);await O(i).toHaveTextContent(l[`label.scope.group.personal`]),await O(i).toHaveTextContent(l[`label.scope.tag.system`]),await k.click(j(i).getByRole(`menuitemradio`,{name:/我盯的大额单/})),await A(()=>O(e.querySelector(`[data-slot="view-title"]`)).toHaveTextContent(`我盯的大额单`)),await k.click(t.getByRole(`button`,{name:l[`label.workbench.expand-sidebar`]})),await O(n()).not.toBeNull(),await O(t.queryByRole(`button`,{name:l[`label.workbench.switch-view`]})).toBeNull(),await A(()=>O(document.body.querySelector(`[role="menu"]`)).toBeNull())}},tn={...y,play:async({canvasElement:e})=>{let t=j(e);await t.findByRole(`table`),await O(e.querySelector(`[data-slot="editor-band"]`)).toBeNull();let n=t.getByRole(`button`,{name:RegExp(`^${l[`label.filter.panel`]}`)});await O(n).toHaveAttribute(`aria-expanded`,`false`),await k.click(n),await O(e.querySelector(`[data-slot="editor-band"]`)).not.toBeNull(),await k.click(t.getByRole(`button`,{name:l[`label.workbench.editor-modes`]}));let r=await j(document.body).findByRole(`menu`);await k.click(j(r).getByRole(`menuitemradio`,{name:l[`label.filter.advanced`]})),await A(()=>O(e.querySelector(`[data-slot="filter-group"]`)).not.toBeNull()),await A(()=>O(document.body.querySelector(`[role="menu"]`)).toBeNull()),await O(t.getByRole(`button`,{name:RegExp(`^${l[`label.filter.panel`]}`)})).toHaveAccessibleName(l[`label.filter.panel`])}},nn={...y,play:async({canvasElement:e})=>{let t=j(e);await t.findByRole(`table`);let n=t.getByRole(`button`,{name:RegExp(`^${l[`label.filter.panel`]}`)});await O(n).toHaveAttribute(`aria-expanded`,`false`),await O(n).not.toHaveAttribute(`aria-controls`),await E(n),await k.keyboard(`{Enter}`);let r=await A(()=>{let t=e.querySelector(`[data-slot="editor-band"]`);if(!t)throw Error(`the band did not open`);return t});await O(n).toHaveAttribute(`aria-expanded`,`true`),await O(n).toHaveAttribute(`aria-controls`,r.id),await A(()=>O(r.contains(document.activeElement)).toBe(!0)),n.focus(),await k.keyboard(` `),await A(()=>O(e.querySelector(`[data-slot="editor-band"]`)).toBeNull()),await O(n).toHaveAttribute(`aria-expanded`,`false`),await O(n).not.toHaveAttribute(`aria-controls`);let i=t.getByRole(`toolbar`,{name:l[`label.toolbar.title`]});await O(i).toHaveAttribute(`aria-orientation`,`horizontal`);let a=[...i.querySelectorAll(`button`)];await O(a.filter(e=>e.tabIndex===0)).toHaveLength(1),await E(a[0]);for(let e=1;e<a.length;e+=1)await k.keyboard(`{ArrowRight}`),await O(document.activeElement).toBe(a[e]);await k.keyboard(`{ArrowRight}`),await O(document.activeElement).toBe(a[0]),await k.keyboard(`{ArrowLeft}`),await O(document.activeElement).toBe(a[a.length-1]),await O(t.getByRole(`button`,{name:l[`label.layout.table`]})).toHaveAttribute(`aria-pressed`,`true`),await O(t.getByRole(`table`)).toBeInTheDocument(),await k.tab(),await O(i.contains(document.activeElement)).toBe(!1),await k.tab({shift:!0}),await O(document.activeElement).toBe(a[a.length-1]),await E(t.getByRole(`button`,{name:l[`label.toolbar.columns`]})),await k.keyboard(`{Enter}`),await j(document.body).findByText(l[`label.columns.title`]);let o=e=>e.querySelectorAll(`[aria-live]:not([id^="dnd-kit"])`);await O(o(e)).toHaveLength(1);let s=await A(()=>{let e=document.body.querySelector(`[data-slot="popover-content"]`);if(!e)throw Error(`the popover did not open`);return e});await O(o(s)).toHaveLength(1),await k.keyboard(`{Escape}`),await A(()=>O(document.body.querySelector(`[data-slot="tooltip-content"]`)).toBeNull()),await k.keyboard(`{Escape}`),await A(()=>O(document.body.querySelector(`[data-slot="popover-content"]`)).toBeNull())}},rn={...y,play:async({canvasElement:e})=>{let t=await j(e).findByRole(`table`),n=()=>{let t=e.querySelector(`[data-slot="record-announcement"]`);if(!t)throw Error(`the result has no live region`);return t},r=N(`label.pagination.total`,{total:4});await A(()=>O(n()).toHaveTextContent(r));let i=[],a=new MutationObserver(()=>{let e=n().textContent?.trim()??``;e!==``&&i[i.length-1]!==e&&i.push(e)});a.observe(n(),{characterData:!0,childList:!0,subtree:!0}),await et(t,`订单号`),await A(()=>O(i).toContain(r)),a.disconnect(),await O(i).toEqual([l[`label.status.querying`],r]),await O(P(e)).toHaveTextContent(r);let o=e.querySelector(`[data-control="sort"]`);await A(()=>O(o).toHaveAccessibleName(`${N(`label.sort.button`,{field:`金额`,direction:l[`label.sort.desc`]})} ${N(`label.sort.more`,{count:1})}`)),await O(o).toHaveTextContent(`金额${N(`label.sort.more`,{count:1})}`),await k.click(e.querySelector(`[data-control="columns"]`));let s=j(document.body);await s.findByText(l[`label.columns.title`]),await k.click(s.getByRole(`button`,{name:N(`label.columns.pin`,{field:`仓库`})})),await A(()=>O(document.body.querySelector(`[data-slot="column-announcement"]`)).toHaveTextContent(/^仓库 已固定/)),await k.keyboard(`{Escape}`),await k.keyboard(`{Escape}`),await A(()=>O(document.body.querySelector(`[data-slot="popover-content"]`)).toBeNull())}},an={...y,play:async({canvasElement:e})=>{let t=j(e);await t.findByRole(`table`),await k.click(t.getByRole(`button`,{name:RegExp(`^${l[`label.filter.panel`]}`)})),await k.click(await t.findByRole(`button`,{name:l[`label.filter.add`]}));let n=await j(document.body).findByRole(`dialog`);await O(n).toHaveTextContent(l[`label.filter.pick-fields`]),await O(j(n).getByRole(`checkbox`,{name:`状态`})).toBeChecked(),await k.click(j(n).getByRole(`checkbox`,{name:`仓库`})),await k.click(j(n).getByRole(`checkbox`,{name:`金额`})),await k.click(j(n).getByRole(`textbox`,{name:l[`label.field.search`]})),await k.keyboard(`{Tab}`),await O(document.activeElement).toBe(j(n).getByRole(`checkbox`,{name:`订单号`})),await k.keyboard(` `),await O(j(n).getByRole(`checkbox`,{name:`订单号`})).toBeChecked(),await k.click(j(n).getByRole(`button`,{name:l[`label.filter.pick-done`]})),await A(()=>O(e.querySelectorAll(`[data-slot="filter-condition"]`)).toHaveLength(4)),await A(()=>O(document.body.querySelector(`[role="dialog"]`)).toBeNull())}},on={...Me,play:async({canvasElement:e})=>{let t=j(e),n=await t.findByRole(`table`),r=e.ownerDocument,i=e.querySelector(`[data-slot="view-surface"]`),a=i.parentElement,o=st(r),s=n.querySelector(`thead th[data-pin-index]`),c=e=>n.querySelector(`${e} tr`).cells[s.cellIndex],u=()=>({header:getComputedStyle(s).position,summary:getComputedStyle(n.querySelector(`tfoot td`)).position,pinned:getComputedStyle(c(`tbody`)).position}),d={header:`sticky`,summary:`sticky`,pinned:`sticky`};await O(u()).toEqual(d);let f=n.closest(`[data-slot="record-table"]`).getBoundingClientRect(),p=t.getByRole(`button`,{name:l[`label.workbench.expand-view`]});await O(p).toHaveAttribute(`aria-expanded`,`false`),await k.click(p),await A(()=>O(i).toHaveAttribute(`data-view-expanded`,`true`)),await O(getComputedStyle(i).position).toBe(`fixed`),await O(i.parentElement).toBe(a),await O(t.getByRole(`table`)).toBe(n),await O(ct(i)).toBe(!0),await O(st(r)).toEqual([`hidden !important`,`hidden !important`]),await O(u()).toEqual(d);let m=n.closest(`[data-slot="record-table"]`);await O(m).toHaveAttribute(`data-scrolls`),await O(getComputedStyle(m).maxHeight).toBe(`none`);let h=m.getBoundingClientRect();await O(h.height).toBeGreaterThan(f.height),await O(Math.round(h.bottom)).toBeLessThanOrEqual(Math.round(i.getBoundingClientRect().bottom)+1),await O(i.getAttribute(`aria-modal`)).toBeNull(),await O(i.getAttribute(`role`)).toBeNull(),await O(r.body.querySelector(`[inert]`)).toBeNull();let g=t.getByRole(`button`,{name:l[`label.workbench.collapse-view`]});await O(g).toBe(p),await O(g).toHaveAttribute(`aria-keyshortcuts`,`Escape`),g.focus(),await k.keyboard(`{Escape}`),await A(()=>O(i).not.toHaveAttribute(`data-view-expanded`)),await O(r.activeElement).toBe(p),await O(p).toHaveAttribute(`aria-expanded`,`false`),await O(st(r)).toEqual(o),await O(u()).toEqual(d)}},sn={...Ee,play:async({canvasElement:e})=>{let t=j(e);await t.findByRole(`table`);let n=e.ownerDocument,r=e.querySelector(`[data-transformed-host]`),i=r.querySelector(`[data-slot="view-surface"]`);await O(getComputedStyle(r).transform).not.toBe(`none`);let a=r.getBoundingClientRect();await O(a.height).toBeLessThan(window.innerHeight),await k.click(t.getByRole(`button`,{name:l[`label.workbench.expand-view`]})),await A(()=>O(i).toHaveAttribute(`data-view-expanded`,`true`)),await O(i.parentElement).toBe(r),await O(ct(i)).toBe(!0),await O(st(n)).toEqual([`hidden !important`,`hidden !important`]),await O(i.style.getPropertyValue(`--fve-expanded-w`)).toBe(`${window.innerWidth}px`),await k.keyboard(`{Escape}`),await A(()=>O(i).not.toHaveAttribute(`data-view-expanded`)),await O(i.style.getPropertyValue(`--fve-expanded-w`)).toBe(``),await O(Math.round(i.getBoundingClientRect().width)).toBeLessThanOrEqual(Math.round(a.width))}},cn={...De,play:async({canvasElement:e})=>{let t=j(e);await t.findByRole(`table`);let n=e.querySelector(`[data-scaled-host]`),r=n.querySelector(`[data-slot="view-surface"]`),i=new DOMMatrixReadOnly(getComputedStyle(n).transform);await O(i.a).toBeCloseTo(.75,2),await k.click(t.getByRole(`button`,{name:l[`label.workbench.expand-view`]})),await A(()=>O(r).toHaveAttribute(`data-view-expanded`,`true`)),await O(ct(r)).toBe(!0);let a=Number.parseFloat(r.style.getPropertyValue(`--fve-expanded-w`));await O(a).toBeGreaterThan(window.innerWidth),await O(a*.75).toBeCloseTo(window.innerWidth,0),await k.keyboard(`{Escape}`),await A(()=>O(r).not.toHaveAttribute(`data-view-expanded`)),await O(r.style.getPropertyValue(`--fve-expanded-w`)).toBe(``)}},ln={...fe,play:async({canvasElement:e})=>{let t=j(e);await t.findByRole(`table`);let n=t.getAllByRole(`row`);await k.click(j(n[1]).getByRole(`button`,{name:`弄坏`}));let r=await t.findByRole(`alert`);await O(r).toHaveAttribute(`data-boundary`,`result`),await O(r.closest(`[data-slot="result-block"]`)).not.toBeNull(),await O(t.queryByRole(`table`)).toBeNull(),await O(e.querySelector(`[data-slot="view-title"]`)).toBeVisible(),await O(t.getByRole(`button`,{name:l[`label.save.save`]})).toBeVisible(),await k.click(j(r).getByRole(`button`,{name:l[`label.render.retry`]})),await t.findByRole(`table`),await O(t.queryByRole(`alert`)).toBeNull()}},un={...se,play:async({canvasElement:e})=>{let t=await j(e).findByRole(`table`),n=t.closest(`[data-slot="record-table"]`);await O(n).toHaveAttribute(`data-scrolls`);let r=C(t,`订单号`),i=C(t,`金额`),a=C(t,`创建时间`),o=t.querySelector(`thead th[data-column="actions"]`);await O(r).toHaveAttribute(`data-pin`,`left`),await O(i).toHaveAttribute(`data-pin`,`left`),await O(a).not.toHaveAttribute(`data-pin`),await O(o).toHaveAttribute(`data-pin`,`right`);let s=e=>[`thead`,`tbody`,`tfoot`].map(n=>getComputedStyle(t.querySelector(`${n} tr`).cells[e.cellIndex]).boxShadow!==`none`),c=()=>({inner:s(r),left:s(i),end:s(a),actions:s(o)}),l=[!1,!1,!1],u=[!0,!0,!0],d={inner:l,left:l,end:l,actions:l};await A(()=>O(n.hasAttribute(`data-overflowing`)).toBe(!1)),await A(()=>O(c()).toEqual(d)),await O(getComputedStyle(t).borderCollapse).toBe(`separate`);let f=t.querySelector(`tbody tr`);await O(getComputedStyle(f.cells[1]).borderBottomWidth).toBe(`1px`),await k.hover(f.cells[1]);let p=e=>!e.startsWith(`rgba(`)&&!/\/\s*0?\.\d+\)/.test(e);await A(()=>{let e=getComputedStyle(f.cells[1]).backgroundColor;O(p(e)).toBe(!0),O(e).toBe(getComputedStyle(f).backgroundColor)}),await k.unhover(f.cells[1]);let m=()=>{let e=[...t.querySelectorAll(`thead th[data-pin]`)],n=e.filter(e=>e.dataset.pin===`left`&&e.dataset.column!==`select`),r=e.filter(e=>e.dataset.pin===`right`);return new Set([n.at(-1),r[0]])},h=()=>{let e=m();return{inner:e.has(r)?u:l,left:e.has(i)?u:l,end:e.has(a)?u:l,actions:e.has(o)?u:l}};n.style.maxWidth=`420px`,await A(()=>O(n.scrollWidth).toBeGreaterThan(n.clientWidth)),await A(()=>O(n.hasAttribute(`data-overflowing`)).toBe(!0)),await O(r).toHaveAttribute(`data-pin`,`left`),await A(()=>O(c()).toEqual(h()));for(let e of[40,n.scrollWidth,0])n.scrollLeft=e,await A(()=>O(c()).toEqual(h()));n.scrollLeft=60,await A(()=>O(n.scrollLeft).toBe(60));let g=r.getBoundingClientRect();for(let e of[.1,.3,.5,.7,.85]){let t=document.elementFromPoint(g.left+g.width*e,g.top+g.height/2);await O(r.contains(t),`at ${e}: ${t?.tagName} ${t?.dataset.slot??``}`).toBe(!0)}n.scrollLeft=0,await O(t).not.toHaveAttribute(`data-scrolled-left`),await O(t).not.toHaveAttribute(`data-scrolled-right`)}},dn=[`运单号`,`订单号`,`客户`,`收件人`,`目的城市`,`发货仓`,`承运商`,`运输方式`,`状态`,`时效`,`标记`,`件数`,`重量`,`运费`,`已保价`,`已签单`,`发运日期`,`创建时间`,`跟踪链接`,`备注`],fn={...ge,play:async({canvasElement:e})=>{let t=await j(e).findByRole(`table`),n=t.closest(`[data-slot="record-table"]`);await A(()=>O(t.querySelectorAll(`tbody tr`)).toHaveLength(50)),await O(ut(t)).toEqual(dn),await O(h(t,`运单号`).slice(0,2)).toEqual([`YD-1040`,`YD-1020`]),await O(ne(g(t,`运费`))).toBe(34480),await O(g(t,`件数`)).toContain(`197`),await O(g(t,`重量`)).toContain(`558.5`),await O(n).toHaveAttribute(`data-scrolls`),await A(()=>O(n.scrollWidth).toBeGreaterThan(n.clientWidth));let r=[...t.querySelectorAll(`tbody tr`)];await A(()=>{let e=r[0].getBoundingClientRect().height;for(let t of r)O(Math.abs(t.getBoundingClientRect().height-e)).toBeLessThan(1)});let i=[...t.querySelectorAll(`tbody [data-slot="cell-text"]`)].find(e=>e.textContent.includes(`
`));await O(i).toHaveAttribute(`title`,i.textContent),await O(i.getBoundingClientRect().height).toBeLessThan(24);let a=t.tHead,o=t.tFoot,s=C(t,`运单号`),c=C(t,`状态`),l=t.querySelector(`thead th[data-column="actions"]`);await O(s).toHaveAttribute(`data-pin`,`left`),await O(c).not.toHaveAttribute(`data-pin`),await O(l).toHaveAttribute(`data-pin`,`right`);let u=e=>[`thead`,`tbody`,`tfoot`].map(n=>getComputedStyle(t.querySelector(`${n} tr`).cells[e.cellIndex]).boxShadow!==`none`),d=[!0,!0,!0],f=[!1,!1,!1],p=()=>({key:u(s),middle:u(c),actions:u(l)}),m={key:d,middle:f,actions:d};await A(()=>O(p()).toEqual(m));let ee=()=>({head:getComputedStyle(a).position,foot:getComputedStyle(o).position,key:getComputedStyle(s).position,cell:getComputedStyle(t.querySelector(`tbody tr`).cells[s.cellIndex]).position}),_={head:`sticky`,foot:`sticky`,key:`sticky`,cell:`sticky`};await O(ee()).toEqual(_);let te=()=>({headTop:Math.round(a.getBoundingClientRect().top),areaTop:Math.round(n.getBoundingClientRect().top),keyLeft:Math.round(s.getBoundingClientRect().left)}),v=te();await O(v.headTop).toBe(v.areaTop);for(let e of[40,n.scrollWidth,n.scrollWidth/2,0])n.scrollLeft=e,await A(()=>O(p()).toEqual(m)),await O(ee()).toEqual(_),await O(te().keyLeft).toBe(v.keyLeft);n.scrollTop=n.scrollHeight,await A(()=>O(te().headTop).toBe(v.areaTop)),await O(ee()).toEqual(_),await O(ut(t)).toEqual(dn),await O(Math.round(o.getBoundingClientRect().bottom)).toBeLessThanOrEqual(Math.round(n.getBoundingClientRect().bottom)),n.scrollTop=0}},pn={...oe,play:async({canvasElement:e})=>{let t=j(e),n=await t.findByRole(`table`),r=n.closest(`[data-slot="record-table"]`),i=e.querySelector(`[data-narrow-host]`);await O(i.getBoundingClientRect().width).toBe(420);let a=C(n,`运单号`),o=e=>n.querySelector(`thead th[data-column="${e}"]`),s=o(`actions`),c=o(`select`),u=()=>[...n.querySelectorAll(`thead th[data-pin]`)].reduce((e,t)=>e+t.getBoundingClientRect().width,0),d=()=>(r.clientWidth-u())/r.clientWidth;await O(r.clientWidth).toBeLessThanOrEqual(420),await A(()=>O(r.scrollWidth).toBeGreaterThan(r.clientWidth)),await A(()=>O(d()).toBeGreaterThanOrEqual(.5)),await O(s).not.toHaveAttribute(`data-pin`),await O(a).toHaveAttribute(`data-pin`,`left`),await O(c).toHaveAttribute(`data-pin`,`left`),await O(getComputedStyle(s).position).not.toBe(`sticky`);let f=n.querySelector(`tbody tr`);await O(getComputedStyle(f.cells[s.cellIndex]).position).not.toBe(`sticky`),await O(getComputedStyle(a).position).toBe(`sticky`),await O(t.queryByText(l[`label.header.unsaved`])).toBeNull(),i.style.width=`1200px`,await A(()=>O(s).toHaveAttribute(`data-pin`,`right`)),await O(getComputedStyle(s).position).toBe(`sticky`),i.style.width=`420px`,await A(()=>O(s).not.toHaveAttribute(`data-pin`)),await O(d()).toBeGreaterThanOrEqual(.5),await O(t.queryByText(l[`label.header.unsaved`])).toBeNull()}},mn={...ge,play:async({canvasElement:e})=>{await lt(e)}},hn={...oe,play:async({canvasElement:e})=>{let t=e.querySelector(`[data-narrow-host]`);await O(t.getBoundingClientRect().width).toBe(420),await lt(e)}},gn={...xe,play:async({canvasElement:e})=>{let t=j(e),n=await t.findByRole(`table`),r=e.querySelector(`[data-slot="view-surface"]`),i=C(n,`金额`);await O(i).toHaveAttribute(`data-pin`,`left`),await O(C(n,`订单号`)).toHaveAttribute(`data-pin`,`left`);let a=()=>({header:getComputedStyle(i).position,summary:getComputedStyle(n.querySelector(`tfoot td`)).position,cell:getComputedStyle(n.querySelector(`tbody tr`).cells[i.cellIndex]).position}),o={header:`sticky`,summary:`sticky`,cell:`sticky`};await O(a()).toEqual(o);let s=t.getByRole(`button`,{name:l[`label.workbench.expand-view`]}),c=e.querySelector(`[data-slot="view-controls"]`);await O(c.contains(s)).toBe(!0),await O([...c.querySelectorAll(`[data-slot="editor-toggle"], [data-slot="view-expand"]`)].map(e=>e.getAttribute(`data-slot`))).toEqual([`editor-toggle`,`view-expand`]),await k.click(s),await A(()=>O(r).toHaveAttribute(`data-view-expanded`,`true`)),await O(ct(r)).toBe(!0);for(let t of e.querySelectorAll(`[data-slot="result-toolbar"] [data-slot="popover-trigger"]`)){await k.click(t);let e=await A(()=>{let e=document.body.querySelector(`[data-slot="popover-content"]`);if(!e)throw Error(`no popover`);return e});await O(e.closest(`[data-slot="view-surface"]`)).toBeNull(),await O(ft(e)).toBe(!0),await k.keyboard(`{Escape}`),await A(()=>O(document.body.querySelector(`[data-slot="popover-content"]`)).toBeNull()),await O(r).toHaveAttribute(`data-view-expanded`,`true`)}await O(getComputedStyle(r).zIndex).toBe(`0`);let u=n.closest(`[data-slot="record-table"]`),d=n.parentElement;d.scrollTop=d.scrollHeight,d.scrollLeft=d.scrollWidth,await O(u).toHaveAttribute(`data-scrolls`),await O(a()).toEqual(o),await O(Math.round(i.getBoundingClientRect().left)).toBeGreaterThanOrEqual(Math.round(d.getBoundingClientRect().left)-1),await k.keyboard(`{Escape}`),await A(()=>O(r).not.toHaveAttribute(`data-view-expanded`)),await O(a()).toEqual(o)}},_n={...Re,play:async({canvasElement:e})=>{let t=j(e);await t.findByRole(`table`);let n=()=>e.querySelector(`[data-slot="refresh-cadence"]`),r=N(`label.refresh.seconds`,{count:30}),i=()=>Number(n()?.textContent?.replace(/\D/g,``));await A(()=>O(i()).toBeGreaterThan(0));let a=i();await O(a).toBeLessThanOrEqual(30),await A(()=>O(i()).toBeLessThan(a),{timeout:4e3}),await O(e.querySelector(`[data-slot="refresh-now"]`)).toHaveAccessibleDescription(N(`label.refresh.on`,{interval:r})),await O(e.querySelector(`[data-slot="refresh-countdown"]`)).toHaveAttribute(`aria-hidden`,`true`);let o=e.querySelector(`[data-slot="refresh-countdown"]`),s=o.getBoundingClientRect().width;await A(()=>O(i()).toBeLessThan(a-1),{timeout:4e3}),await O(o.getBoundingClientRect().width).toBe(s);let c=e.querySelector(`[data-slot="refresh-interval"]`);await k.click(c);let u=await j(document.body).findByRole(`menu`);await O(u.closest(`[data-slot="view-surface"]`)).toBeNull(),await O(ft(u)).toBe(!0),await O(j(u).getAllByRole(`menuitemradio`).map(e=>e.textContent)).toEqual([l[`label.refresh.off`],...vn.map(yn)]),await O(j(u).getByRole(`menuitemradio`,{name:r})).toHaveAttribute(`aria-checked`,`true`);let d=N(`label.refresh.minutes`,{count:5});await k.click(j(u).getByRole(`menuitemradio`,{name:d})),await A(()=>O([d,N(`label.refresh.minutes`,{count:4})]).toContain(n()?.textContent)),await O(t.getByText(l[`label.header.unsaved`])).toBeVisible(),c.focus(),await k.keyboard(`{Enter}`);let f=await j(document.body).findByRole(`menu`);await k.click(j(f).getByRole(`menuitemradio`,{name:l[`label.refresh.off`]})),await A(()=>O(n()).toBeNull())}},vn=[30,60,300],yn=e=>e>=3600?N(`label.refresh.hours`,{count:e/3600}):e>=60?N(`label.refresh.minutes`,{count:e/60}):N(`label.refresh.seconds`,{count:e}),bn=[{name:`popover`,slot:`popover-content`,trigger:`[data-slot="result-toolbar"] [data-slot="popover-trigger"]`},{name:`menu`,slot:`dropdown-menu-content`,trigger:`[aria-haspopup="menu"]`},{name:`select`,slot:`select-content`,trigger:`[data-slot="select-trigger"]`},{name:`tooltip`,slot:`tooltip-content`,trigger:`[data-slot="tooltip-trigger"]`,hover:!0},{name:`dialog`,slot:`dialog-content`,trigger:`[aria-label="${l[`label.manage.open`]}"]`}],xn={...me,play:async({canvasElement:e})=>{await j(e).findByRole(`table`);let t=document.querySelector(`[data-raised-host]`);await O(getComputedStyle(t).zIndex).toBe(`10`),await O(dt(t)).toBeNull(),await O(ft(t)).toBe(!0);async function n(t){let n=[...e.querySelectorAll(t.trigger)].find(e=>getComputedStyle(e).pointerEvents!==`none`);await O(n,`no ${t.name} to open`).toBeDefined(),t.hover?await k.hover(n):await k.click(n);let r=await A(()=>{let e=document.body.querySelector(`[data-slot="${t.slot}"]`);if(!e||e.hasAttribute(`data-closed`))throw Error(`no open ${t.name}`);let n=e.getBoundingClientRect();if(n.width===0||n.height===0)throw Error(`the ${t.name} has no box yet`);return e});return await O(r.closest(`[data-slot="view-surface"]`)).toBeNull(),{popup:r,trigger:n}}async function r(e,t){e.hover?await k.unhover(t):await k.keyboard(`{Escape}`),await A(()=>{let t=document.body.querySelector(`[data-slot="${e.slot}"]`);O(t===null||t.hasAttribute(`data-closed`)).toBe(!0)})}let i=(e,t)=>e.slot===`dialog-content`?t:t.parentElement;for(let e of bn){let{popup:t,trigger:a}=await n(e);await O(getComputedStyle(i(e,t)).zIndex).toBe(`50`),await O(ft(t),`the ${e.name} is buried`).toBe(!0),await r(e,a)}let a=document.documentElement;try{a.style.setProperty(`--fve-popup-z-index`,`3`);let{popup:e,trigger:t}=await n(bn[0]);await O(getComputedStyle(i(bn[0],e)).zIndex).toBe(`3`),await O(ft(e)).toBe(!1),await r(bn[0],t)}finally{a.style.removeProperty(`--fve-popup-z-index`)}}},Sn=3,Cn=e=>({...y,args:{...y.args,theme:e},play:async({canvasElement:t})=>{let n=j(t);await n.findByRole(`table`),await O(t.querySelector(`[data-slot="view-surface"]`)).toHaveAttribute(`data-theme`,e),await k.click(n.getByRole(`button`,{name:RegExp(`^${l[`label.filter.panel`]}`)})),await k.click(await n.findByRole(`button`,{name:l[`label.filter.add`]}));let r=await j(document.body).findByRole(`dialog`);await k.click(j(r).getByRole(`checkbox`,{name:`金额`}));let i=m(j(r).getByRole(`textbox`,{name:l[`label.field.search`]}).closest(`[data-slot="input-group"]`));await k.click(j(r).getByRole(`button`,{name:l[`label.filter.pick-done`]}));let a=n.getByRole(`checkbox`,{name:l[`label.record.select-all`]});await O(a).not.toBeChecked();let o={checkbox:a,select:j(P(t)).getByRole(`combobox`,{name:l[`label.pagination.page-size`]})},s=[{name:`input`,...i},...Object.entries(o).map(([e,t])=>({name:e,...m(t)}))],c=s.map(({name:e,ratio:t,colors:n})=>`${e} ${t.toFixed(2)}:1 (${n.border} on ${n.fill} over ${n.surface})`).join(`; `);await O(Math.min(...s.map(({ratio:e})=>e)),`${e} — ${c}`).toBeGreaterThanOrEqual(Sn)}}),wn=e=>({...y,args:{...y.args,theme:e},play:async({canvasElement:t})=>{let n=j(t),r=await n.findByRole(`table`);await O(t.querySelector(`[data-slot="view-surface"]`)).toHaveAttribute(`data-theme`,e);let i=n.getByRole(`button`,{name:l[`label.toolbar.columns`]}),a=r.querySelector(`thead button`),o=n.getAllByRole(`button`,{name:RegExp(`^${l[`label.filter.unset-of`].split(` `)[0]}`)})[0],s=[];await E(i),await T(()=>getComputedStyle(i).borderTopColor),s.push({name:`button`,...m(i)}),await E(o),await T(()=>getComputedStyle(o).borderTopColor),s.push({name:`unset`,...m(o)}),await E(a),await T(()=>getComputedStyle(a).borderTopColor),s.push({name:`sort`,...m(a)});let c=s.map(({name:e,ratio:t,colors:n})=>`${e} ${t.toFixed(2)}:1 ${JSON.stringify(n)}`).join(`; `);await O(Math.min(...s.map(({ratio:e})=>e)),`${e} — ${c}`).toBeGreaterThanOrEqual(Sn);let u=getComputedStyle(a).borderTopColor,d=getComputedStyle(a).boxShadow;await E(i),await T(()=>getComputedStyle(i).borderTopColor),await O(getComputedStyle(i).borderTopColor).toBe(u),await O(getComputedStyle(i).boxShadow).toBe(d)}}),Tn=wn(`light`),En=wn(`dark`),Dn={...y,args:{...y.args,theme:`dark`},play:async({canvasElement:e})=>{let t=(await j(e).findByRole(`table`)).querySelector(`tbody tr`),{ratio:n,colors:r}=m(t,`bottom`);await O(n,`${r.border} on ${r.fill} over ${r.surface}`).toBeGreaterThanOrEqual(1.5)}},On=e=>({...Ie,args:{...Ie.args,theme:e},play:async({canvasElement:t})=>{let n=await j(t).findByRole(`table`);await A(()=>O(h(n,`订单号`)).toHaveLength(6));let r=[...n.tBodies[0].rows],i=(e,t)=>[...e.querySelectorAll(`[data-slot="badge"]`)].map(e=>({name:`${t} ${e.dataset.tone} "${e.textContent}"`,...m(e)}));await k.click(j(r[0]).getByRole(`checkbox`,{name:N(`label.record.select`,{key:`SO-1001`})})),await A(()=>O(r[0]).toHaveAttribute(`data-state`,`selected`)),await T(()=>getComputedStyle(r[0]).backgroundColor);let a=i(r[0],`selected`);await k.hover(r[1]),await T(()=>getComputedStyle(r[1]).backgroundColor),a.push(...i(r[1],`hovered`)),await k.unhover(r[1]),a.push(...i(r[2],`resting`));let o=a.map(({name:e,onSurface:t,colors:n})=>`${e} ${t.toFixed(2)}:1 (${n.border} over ${n.surface})`).join(`; `);await O(Math.min(...a.map(({onSurface:e})=>e)),`${e} — ${o}`).toBeGreaterThanOrEqual(kn)}}),kn=1.5,An=On(`light`),jn=On(`dark`),Mn=e=>({...y,args:{...y.args,theme:e},play:async({canvasElement:t})=>{let n=await j(t).findByRole(`table`);await A(()=>O(h(n,`订单号`)).toEqual(M)),await O(t.querySelector(`[data-slot="view-surface"]`)).toHaveAttribute(`data-theme`,e);let r=n.tHead.rows[0],i=n.tFoot.rows[0],a=n.tBodies[0].rows[0],o=d(r).colors.fill,s=d(i).colors.fill,c=d(a).colors.fill;await O(o,`${e} — 表头 ${o}，汇总 ${s}`).toBe(s),await O(o,`${e} — 带子与行底同色 ${o}`).not.toBe(c),await O(getComputedStyle(r.cells[0]).borderBottomWidth).toBe(`1px`);let l=[...r.cells].filter(e=>(e.textContent??``).trim().length>0).map(e=>({name:e.textContent.trim(),...f(e.querySelector(`button`)??e)})),u=l.map(({name:e,ratio:t,colors:n})=>`${e} ${t.toFixed(2)}:1 (${n.text} on ${n.background})`).join(`; `);await O(l.length).toBeGreaterThan(0),await O(Math.min(...l.map(({ratio:e})=>e)),`${e} — ${u}`).toBeGreaterThanOrEqual(xt)}}),Nn=Mn(`light`),Pn=Mn(`dark`),Fn=e=>({...Ie,args:{...Ie.args,theme:e},play:async({canvasElement:t})=>{let n=await j(t).findByRole(`table`);await A(()=>O(h(n,`订单号`)).toHaveLength(6)),await O(t.querySelector(`[data-slot="view-surface"]`)).toHaveAttribute(`data-theme`,e);let r=[...n.querySelectorAll(`[data-slot="badge"][data-tone]`)],i=new Map;for(let e of r){let t=(e.textContent??``).trim();await O(t,`一枚没有字的徽章只剩颜色`).not.toBe(``);let n=e.dataset.tone;i.set(n,(i.get(n)??new Set).add(t))}await O([...i.keys()].sort()).toEqual([`danger`,`neutral`,`success`,`warning`]);let a=[...i.values()].flatMap(e=>[...e]);await O(a.length).toBe(new Set(a).size);let o=(e,t)=>[...e.querySelectorAll(`[data-slot="badge"][data-tone]`)].map(e=>({name:`${t} ${e.dataset.tone} "${(e.textContent??``).trim()}"`,size:getComputedStyle(e).fontSize,...f(e)})),s=[...n.tBodies[0].rows],c=s.flatMap(e=>o(e,`resting`));await k.click(j(s[0]).getByRole(`checkbox`,{name:N(`label.record.select`,{key:`SO-1001`})})),await A(()=>O(s[0]).toHaveAttribute(`data-state`,`selected`)),await T(()=>getComputedStyle(s[0]).backgroundColor),c.push(...o(s[0],`selected`)),await k.hover(s[1]),await T(()=>getComputedStyle(s[1]).backgroundColor),c.push(...o(s[1],`hovered`)),await k.unhover(s[1]);let l=c.map(({name:e,size:t,ratio:n,colors:r})=>`${e} ${n.toFixed(2)}:1 @${t} (${r.text} on ${r.background})`).join(`; `);await O(Math.min(...c.map(({ratio:e})=>e)),`${e} — ${l}`).toBeGreaterThanOrEqual(xt)}}),In=Fn(`light`),Ln=Fn(`dark`),Rn=Cn(`light`),zn=Cn(`dark`),Bn=(e,t,n)=>({...t,args:{...t.args,theme:e},play:async({canvasElement:t})=>{await A(()=>O(t.querySelector(`[data-slot="view-surface"]`)).toHaveAttribute(`data-theme`,e));let r=await A(()=>{let e=t.querySelector(`[data-slot="status-strip"][data-tone="${n}"]`);if(!e)throw Error(`No ${n} status strip on screen.`);return e}),i=r.querySelector(`[data-slot="alert-title"]`),{ratio:a,colors:o}=f(i);await O(a,`${e} ${n} — ${o.text} on ${o.background}`).toBeGreaterThanOrEqual(xt),await O(r.getBoundingClientRect().height,`${e} ${n} — the strip is not one line high`).toBeLessThanOrEqual(Vn)}}),Vn=40,Hn=Bn(`light`,He,`error`),Un=Bn(`dark`,He,`error`),Wn=Bn(`light`,de,`warning`),Gn=Bn(`dark`,de,`warning`),Kn=e=>({...Ue,args:{...Ue.args,theme:e},play:async({canvasElement:t})=>{let n=j(t);await A(()=>O(t.querySelector(`[data-slot="view-surface"]`)).toHaveAttribute(`data-theme`,e));let r=await Xe(n,`待出库订单`),i=j(document.body).getByRole(`dialog`);await k.click(j(r).getByRole(`button`,{name:l[`label.manage.delete`]}));let o=await $e();await O(j(o).getByRole(`heading`).textContent).toBe(a(l,`label.delete.confirm`,{title:`待出库订单`}));let s=j(o).getByRole(`button`,{name:l[`label.manage.delete`]});await T(()=>getComputedStyle(s).backgroundColor);let{ratio:c,colors:d}=f(s);await O(c,`${e} — ${d.text} on ${d.background}`).toBeGreaterThanOrEqual(xt);let p=document.querySelector(`[data-slot="alert-dialog-overlay"]`),m=u(o,p,i);await O(m.ratio,`${e} — fill ${m.colors.front} ${m.onFill.toFixed(2)}:1, ring ${m.colors.ring} ${m.onRing.toFixed(2)}:1, over ${m.colors.behind}`).toBeGreaterThanOrEqual(qn),await k.click(j(o).getByRole(`button`,{name:l[`label.delete.keep`]}))}}),qn=3,Jn=Kn(`light`),Yn=Kn(`dark`),Xn=e=>new Set(e.map(e=>Math.round(e.getBoundingClientRect().top))).size,Zn={...x,args:{...x.args,withActions:!0},play:async({canvasElement:e})=>{let t=j(e);await t.findByRole(`table`),await k.click(t.getByRole(`button`,{name:RegExp(`^${l[`label.filter.panel`]}`)})),await A(()=>O(e.querySelector(`[data-slot="filter-conditions"]`)).not.toBeNull()),await k.click(t.getByLabelText(l[`label.record.select-all`])),await t.findByRole(`button`,{name:`导出所选`});let n=e.querySelector(`.fve-root`),r=e.querySelector(`main`),i=e.querySelector(`[data-slot="result-block"]`);await O(r.scrollWidth).toBeLessThanOrEqual(r.clientWidth),await O(i.scrollWidth).toBeLessThanOrEqual(i.clientWidth);let a=n.getBoundingClientRect().right,o=pt(r).filter(e=>e.closest(`[data-slot="record-table"]`)===null).filter(e=>e.getBoundingClientRect().right>a+1).map(e=>`${e.getAttribute(`data-slot`)??e.tagName} ends at ${Math.round(e.getBoundingClientRect().right)} of ${Math.round(a)}`);await O(o).toEqual([])}},Qn={...x,args:{...x.args,withActions:!0,collapsed:!0},play:async({canvasElement:e})=>{let t=j(e);await t.findByRole(`table`);let n=e.querySelector(`[data-narrow-host]`);for(let t of[768,375])n.style.width=`${t}px`,await O(e.querySelector(`[data-slot="toolbar-selection"]`)).toBeNull(),await O(Xn(ht(e)),`${t}px, nothing selected`).toBeLessThanOrEqual(2),await O(gt(e)).toBeLessThanOrEqual(1);n.style.width=`768px`,await k.click(t.getByLabelText(l[`label.record.select-all`])),await t.findByRole(`button`,{name:`导出所选`}),await O(Xn(ht(e)),`768px, four rows selected`).toBeLessThanOrEqual(2);for(let t of[768,375])n.style.width=`${t}px`,await O(Xn(mt(e)),`${t}px, four rows selected`).toBeLessThanOrEqual(2),await O(gt(e)).toBeLessThanOrEqual(1);n.style.width=`375px`}},$n={...Te,play:async({canvasElement:e})=>{let t=j(e),n=await t.findByRole(`table`);await A(()=>O(h(n,`订单号`)).toEqual(M)),await k.click(e.querySelector(`[data-control="export"]`));let r=await j(document.body).findByRole(`dialog`);await O(j(document.body).queryByRole(`menu`)).toBeNull(),await O(j(r).queryByRole(`radio`)).toBeNull(),await O(r.textContent).toContain(N(`label.export.rows`,{count:4})),await O(r.textContent).toContain(N(`label.export.columns`,{count:4,names:[`订单号`,`仓库`,`状态`,`金额`].join(l[`label.filter.join`])})),await k.keyboard(`{Escape}`),await A(()=>O(j(document.body).queryByRole(`dialog`)).toBeNull()),await k.click(t.getByLabelText(l[`label.record.select-all`])),await k.click(e.querySelector(`[data-control="export"]`));let i=await j(document.body).findByRole(`dialog`);await O(j(i).getAllByRole(`radio`).map(e=>e.getAttribute(`aria-checked`))).toEqual([`true`,`false`]),await O(i.textContent).toContain(N(`label.export.selected`,{count:4})),await O(i.textContent).toContain(N(`label.export.all`,{count:4})),await k.keyboard(`{Escape}`)}},er={...Fe,play:async({canvasElement:e})=>{await j(e).findByRole(`table`),await k.click(e.querySelector(`[data-control="export"]`));let t=await j(document.body).findByRole(`dialog`);await k.click(j(t).getByRole(`button`,{name:l[`label.export.confirm`]}));let n=await j(t).findByRole(`progressbar`,{name:l[`label.export.running`]});await O(n.getAttribute(`aria-valuemax`)).toBe(`4`),await O(j(t).getByRole(`status`).textContent).toBe(N(`label.export.progress`,{fetched:0,total:4})),await k.keyboard(`{Escape}`),await A(()=>O(j(document.body).queryByRole(`dialog`)).toBeNull()),await O(e.querySelector(`[data-slot="status-strip"]`)).toBeNull()}},tr={...ze,play:async({canvasElement:e})=>{await j(e).findByRole(`table`),await k.click(e.querySelector(`[data-control="export"]`));let t=await j(document.body).findByRole(`dialog`);await O(t.textContent).toContain(N(`label.export.over-limit`,{max:2})),await k.click(j(t).getByRole(`button`,{name:l[`label.export.confirm`]})),await j(t).findByText(N(`label.export.done`,{count:2})),await O(t.textContent).toContain(N(`label.export.done-capped`,{max:2,total:4}))}},nr={...we,play:async({canvasElement:e})=>{await j(e).findByRole(`table`),await k.click(e.querySelector(`[data-control="export"]`));let t=await j(document.body).findByRole(`dialog`);await k.click(j(t).getByRole(`button`,{name:l[`label.export.confirm`]})),await j(t).findByText(/导出失败/),await O(j(t).getByRole(`button`,{name:l[`label.export.retry`]})).toBeTruthy(),await O(e.querySelector(`[data-slot="status-strip"]`)).toBeNull()}},rr={...y,play:async({canvasElement:e})=>{let t=j(e);await t.findByRole(`table`);let n=e.ownerDocument,r=t.getByRole(`button`,{name:l[`label.workbench.expand-view`]});await k.hover(r),await A(()=>O(D(n)?.textContent).toBe(r.getAttribute(`aria-label`))),await O(D(n)).toHaveTextContent(l[`label.workbench.expand-view`]),await k.click(r),await A(()=>O(r).toHaveAttribute(`aria-expanded`,`true`)),await k.unhover(r),await A(()=>O(D(n)).toBeNull()),await k.hover(r),await A(()=>O(D(n)?.textContent).toBe(l[`label.workbench.collapse-view`])),await k.click(r),await A(()=>O(r).toHaveAttribute(`aria-expanded`,`false`))}},ir={...y,play:async({canvasElement:e})=>{await j(e).findByRole(`table`);let t=e.ownerDocument,n=ot(e,`待出库订单`),r=n.querySelector(`[data-slot="view-kind"]`);await O(getComputedStyle(r.querySelector(`svg`)).pointerEvents).toBe(`none`),await O(getComputedStyle(r).pointerEvents).not.toBe(`none`),await k.hover(r),await A(()=>O(D(t)).toHaveTextContent(l[`label.kind.record`])),await O(n).toHaveTextContent(`待出库订单`),await O(n.textContent).not.toContain(l[`label.kind.record`]),await k.unhover(r),await A(()=>O(D(t)).toBeNull())}},ar={...y,play:async({canvasElement:e})=>{await j(e).findByRole(`table`);let t=e.ownerDocument,n=j(t.body),r=e.querySelector(`[data-slot="refresh-interval"]`);await k.hover(r),await A(()=>O(D(t)).toHaveTextContent(l[`label.refresh.auto`])),await k.click(r);let i=await n.findByRole(`menu`);await k.hover(r),await new Promise(e=>setTimeout(e,200));let a=D(t);a&&await O(_t(a,i)).toBe(!1);for(let e of j(i).getAllByRole(`menuitemradio`))await O(ft(e)).toBe(!0);await k.keyboard(`{Escape}`),await A(()=>O(n.queryByRole(`menu`)).toBeNull())}},or={...ge,play:async({canvasElement:e})=>{let t=j(e),n=await t.findByRole(`table`),r=e=>e.closest(`th`).getAttribute(`data-field`),i=e=>n.querySelector(`thead th[data-field="${e}"] [data-slot="column-label"]`),a=[...n.querySelectorAll(`thead [data-slot="column-label"]`)];await O(a.filter(e=>e.scrollWidth>e.offsetWidth).map(r)).toEqual([]);let o=t.getByRole(`separator`,{name:N(`label.columns.resize`,{field:`订单号`})}),s=o.closest(`th`);await ie(o,48-s.getBoundingClientRect().width),await A(()=>O(i(`orderNo`).scrollWidth).toBeGreaterThan(i(`orderNo`).offsetWidth));let c=i(`orderNo`),l=c.textContent?.trim();await O(c.scrollWidth).toBeGreaterThan(c.offsetWidth),await O(l).toBeTruthy(),await O(c).not.toHaveAttribute(`title`),await k.hover(c);let u=await A(()=>{let e=document.body.querySelector(`[data-slot="tooltip-content"]`);return O(e).not.toBeNull(),e});await O(u.textContent?.trim()).toBe(l),await O(u.className).toContain(`fve-root`),await k.unhover(c)}},$={...y,play:async({canvasElement:e})=>{await j(e).findByRole(`table`);let t=e.ownerDocument,n=e.querySelector(`[data-slot="view-surface"]`),r=[...t.styleSheets].flatMap(e=>{try{return[...e.cssRules]}catch{return[]}}).filter(e=>e instanceof CSSMediaRule&&e.conditionText.includes(`prefers-reduced-motion`)).flatMap(e=>[...e.cssRules]).filter(e=>e instanceof CSSStyleRule&&[`.fve-root`,`.fve-tokens`].every(t=>e.selectorText.split(`,`).some(e=>e.trim()===t)));await O(r.length,`包级的 reduce 规则`).toBeGreaterThan(0);let i=r[0],a=(e,t)=>[e.style.getPropertyValue(t),e.style.getPropertyPriority(t)];for(let e of r)await O(a(e,`animation-duration`)).toEqual([`0.01ms`,`important`]),await O(a(e,`transition-duration`)).toEqual([`0.01ms`,`important`]);await k.click(e.querySelector(`[data-slot="refresh-interval"]`));let o=(await j(t.body).findByRole(`menu`)).closest(`[data-slot="dropdown-menu-content"]`);await O(o.matches(i.selectorText)).toBe(!0),await O(getComputedStyle(o).animationDuration).toBe(`0.1s`),await k.keyboard(`{Escape}`),await A(()=>O(j(t.body).queryByRole(`menu`)).toBeNull());let s=e=>{let r=t.createElement(`div`);r.dataset.slot=e,n.append(r);let a=r.matches(i.selectorText);return r.remove(),a};await O({spinner:s(`spinner`),skeleton:s(`skeleton`),badge:s(`badge`)}).toEqual({spinner:!1,skeleton:!1,badge:!0})}},sr=e=>({...y,args:{...y.args,theme:e},play:async({canvasElement:t})=>{let n=j(t);await n.findByRole(`table`),await O(t.querySelector(`[data-slot="view-surface"]`)).toHaveAttribute(`data-theme`,e),await k.click(n.getAllByRole(`button`,{name:/订单号/})[0]);let r=await A(()=>{let e=t.querySelector(`[data-slot="view-unsaved"]`);if(!e)throw Error(`the "edited" mark did not appear`);return e});await O(r.getAttribute(`data-variant`)).toBe(`outline`),await O(r.getAttribute(`class`)).toContain(`group/badge`);let{ratio:i,colors:a}=m(r);await O(i,`${e} — ${a.border} on ${a.surface}`).toBeGreaterThanOrEqual(3)}}),cr=sr(`light`),lr=sr(`dark`),ur=`WithData.HeaderSortWaitsForApply.BlockSpacing.CellFamily.ElementColumns.CopyADocumentNumber.EarliestAndLatest.Paged.PagedWindow.ColumnsKeepTheirWidthAndRowsFillTheFrame.FooterStaysAtTheBottom.CardsKeepTheirHeight.HeldAtItsFloor.WithActions.BulkOutcomeOutlivesTheSelection.ShiftSelectsARange.OnlyApplyIsPrimary.ManageViews.EmptyResult.CardsAreSetUpFromTheSameButton.Loading.QueryFailed.TotalCoversThisPageOnly.NeedsFixing.Opening.NewView.CannotOpen.English.TableSettings.TableSettingsPointerDrag.HiddenColumnKeepsItsPlace.SortEntriesPointerDrag.ColumnResize.SaveConflictKeepsMine.SaveConflictTakesTheirs.SaveResultNeverCameBack.SaveRefusedByTheStore.RenameConflictedInTheManager.DeleteConflictAsksTwice.TypeScaleIsThreeRungs.SidebarIsANavigationColumn.DefaultViewWearsTheStar.TheListFoldsItselfAwayWhereItCannotFit.TheListFollowsTheColumnItIsGiven.FillingTheScreenFoldsTheList.CollapseAndSwitch.EditorToggleAndModes.ToolbarAndFoldByKeyboard.QueryAnnouncedInTheResult.PickSeveralFields.FillTheScreen.FillTheScreenInTransformedHost.FillTheScreenInScaledHost.RenderFailure.PinnedEdges.WideTable.PinnedGroupCapped.WideTableColumnSettings.NarrowHostColumnSettings.FillTheScreenWithPopups.AutoRefresh.PopupsOverRaisedHostLayer.FocusIndicatorsInLightTheme.FocusIndicatorsInDarkTheme.DarkHairlines.BadgesOnRowsInLightTheme.BadgesOnRowsInDarkTheme.HeaderBandInLightTheme.HeaderBandInDarkTheme.ToneBadgeInkInLightTheme.ToneBadgeInkInDarkTheme.ControlBordersInLightTheme.ControlBordersInDarkTheme.ErrorCalloutInLightTheme.ErrorCalloutInDarkTheme.WarningCalloutInLightTheme.WarningCalloutInDarkTheme.DeleteActionContrastInLightTheme.DeleteActionContrastInDarkTheme.NarrowColumnHoldsTheWidth.ToolbarWrapsAsGroups.ExportWindow.ExportRunningWindow.ExportCappedWindow.ExportFailedWindow.IconButtonsSayTheirNameOnHover.AViewRowSaysItsKindOnHover.AMenuIsNotCoveredByItsOwnTooltip.ATruncatedColumnNameIsOneHoverAway.ReducedMotionIsHonoured.OutlineBadgeEdgesInLightTheme.OutlineBadgeEdgesInDarkTheme`.split(`.`),St.parameters={...St.parameters,docs:{...St.parameters?.docs,source:{originalSource:`{
  ...DisplayWithData,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    await waitFor(() => expect(readColumn(table, '订单号')).toEqual(PENDING_BY_AMOUNT));
    // The total covers what the conditions select rather than every order.
    // The four rows it covers are all on this one page, so there is one
    // summary row, not two: 「本页」 would repeat 「全部」 under another name
    // (D26 Q40). \`Paged\` below is where both scopes stand side by side.
    await expect(amountOf(readTotal(table, '金额'))).toBe(6470);
    await expect(scopeLabels(table)).toEqual([zhCN['label.summary.scope.total']]);
    // The function is named rather than left as the config's token.
    await expect(readTotal(table, '金额')).toContain(zhCN['label.summary.fn.SUM']);

    // A status is one of a set the definition names, so it reads as a badge
    // with the option's label rather than as the stored \`PENDING\`.
    await expect(badgeIn(table, '状态')).toHaveTextContent('待出库');
    // The number beside it is not a set, and wears no pill.
    await expect(badgeIn(table, '金额')).toBeNull();

    // The saved view orders by amount; Shift-clicking another sortable
    // header adds it (a plain click would sort by it alone), and each header
    // then says where it sits in that order.
    await expect(headerOf(table, '金额')).toHaveAttribute('aria-sort', 'descending');
    // A sortable column nobody sorted offers the affordance before it is
    // used, and says what a click would do.
    await expect(headerOf(table, '订单号').querySelector('[data-slot="sort-available"]')).not.toBeNull();
    // Nothing else claims to be sorted: ARIA marks the column the table is
    // ordered by, and there is one of those.
    await expect(headerOf(table, '订单号')).not.toHaveAttribute('aria-sort');
    await addSort(table, '订单号');
    await waitFor(() => expect(positionOf(table, '订单号')).toBe('2'));
    // Amount still decides, so it keeps the attribute and the first place;
    // the column that breaks its ties says where it sits in its own name.
    await expect(positionOf(table, '金额')).toBe('1');
    await expect(headerOf(table, '金额')).toHaveAttribute('aria-sort', 'descending');
    await expect(headerOf(table, '订单号')).not.toHaveAttribute('aria-sort');
    await expect(headerOf(table, '订单号').querySelector('button')!.getAttribute('aria-label')).toContain(say('label.sort.at', {
      position: 2,
      count: 2
    }));
    // Amount still decides, and the second column only breaks its ties, so
    // the rows are where they were.
    await expect(readColumn(table, '订单号')).toEqual(PENDING_BY_AMOUNT);

    // There are rows, so there is something to take away: Export is at the
    // end of the bar (U4 is the other half of this — with no result it is
    // not there at all, see \`QueryFailed\`).
    await expect(canvas.getByRole('button', {
      name: zhCN['label.export.title']
    })).toBeVisible();

    // The page reads from what the view is down to the rows and their
    // paging. A saved view opens folded, and folding unmounts the band
    // rather than hiding it, so it is not in this list yet.
    await expect(slots(canvasElement)).toEqual(['view-header', 'applied-bar', 'result-toolbar', 'record-pagination']);

    // The title bar names the view and says it is shared, without repeating
    // either in the save button.
    const header = canvasElement.querySelector<HTMLElement>('[data-slot="view-header"]');
    await expect(header).toHaveTextContent('待出库订单');
    await expect(header).toHaveTextContent(zhCN['label.scope.tag.shared']);

    // A saved view opens folded, and the bar above the rows says what they
    // were fetched under rather than what the editor now holds.
    const band = canvas.getByRole('button', {
      name: new RegExp(\`^\${zhCN['label.filter.panel']}\`)
    });
    await expect(band).toHaveAttribute('aria-expanded', 'false');
    await expect(canvas.getByRole('region', {
      name: zhCN['label.applied.title']
    })).toHaveTextContent('待出库');

    // Paging is under the rows it pages, not in the toolbar, and it counts
    // what the conditions select rather than what fitted on the screen.
    const paging = paginationBar(canvasElement);
    await expect(paging).toHaveTextContent(say('label.pagination.total', {
      total: 4
    }));
    // Four rows at twenty a page is the whole of it, said as such — and
    // with no arrows at all (D12 Ⅶ): two dead ones were the same fact in a
    // form that still cost two tab stops to read.
    await expect(paging).toHaveTextContent(say('label.toolbar.page-of', {
      index: 1,
      pages: 1
    }));
    await expect(within(paging).queryByRole('button', {
      name: zhCN['label.toolbar.next']
    })).toBeNull();
    await expect(within(paging).queryByRole('button', {
      name: zhCN['label.toolbar.previous']
    })).toBeNull();
    // The size control stays: how many rows a page holds is what makes it
    // one page, and it is the one thing still worth changing here.
    await expect(within(paging).getByRole('combobox', {
      name: zhCN['label.pagination.page-size']
    })).toBeVisible();

    // Opening the fold brings the editor back, in its own block between the
    // title bar and the result, with the one way out of it.
    await userEvent.click(band);
    await expect(await canvas.findByRole('button', {
      name: zhCN['label.filter.apply']
    })).toBeVisible();
    await expect(slots(canvasElement)).toEqual(['view-header', 'editor-band', 'applied-bar', 'result-toolbar', 'record-pagination']);
  }
}`,...St.parameters?.docs?.source}}},F.parameters={...F.parameters,docs:{...F.parameters?.docs,source:{originalSource:`{
  ...DisplayWithData,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    await waitFor(() => expect(readColumn(table, '订单号')).toEqual(PENDING_BY_AMOUNT));
    await userEvent.click(canvas.getByRole('button', {
      name: new RegExp(\`^\${zhCN['label.filter.panel']}\`)
    }));
    const apply = await canvas.findByRole('button', {
      name: zhCN['label.filter.apply']
    });
    await userEvent.click(canvas.getByRole('button', {
      name: say('label.filter.remove-of', {
        field: '状态'
      })
    }));
    await waitFor(() => expect(apply.querySelector('[data-slot="pending-dot"]')).not.toBeNull());
    await userEvent.click(headerOf(table, '订单号').querySelector('button')!);

    // Nothing ran: the same rows in the same order, and the headers still
    // say that order rather than the one waiting.
    await expect(readColumn(table, '订单号')).toEqual(PENDING_BY_AMOUNT);
    await expect(headerOf(table, '金额')).toHaveAttribute('aria-sort', 'descending');
    await expect(headerOf(table, '订单号')).not.toHaveAttribute('aria-sort');
    // The header's name says what its next press does from what waits, and
    // that it waits — the arrow still says what ran.
    await expect(headerOf(table, '订单号').querySelector('button')).toHaveAttribute('aria-label', \`\${say('label.sort.descending', {
      field: '订单号'
    })} · \${zhCN['label.sort.waiting.asc']}\`);
    // The sort control reads the draft: it says what Apply is about to run.
    await expect(canvas.getByRole('button', {
      name: say('label.sort.button', {
        field: '订单号',
        direction: zhCN['label.sort.asc']
      })
    })).toBeVisible();
    await expect(apply.querySelector('[data-slot="pending-dot"]')).not.toBeNull();

    // Apply runs the two together: the orders that are not pending are in,
    // and every order is in its number's place.
    await userEvent.click(apply);
    await waitFor(() => expect(readColumn(table, '订单号')).toContain('SO-1002'));
    const numbers = readColumn(table, '订单号');
    await expect(numbers.length).toBeGreaterThan(PENDING_BY_AMOUNT.length);
    await expect(numbers).toEqual([...numbers].sort());
    await expect(headerOf(table, '订单号')).toHaveAttribute('aria-sort', 'ascending');
    await expect(apply.querySelector('[data-slot="pending-dot"]')).toBeNull();
  }
}`,...F.parameters?.docs?.source},description:{story:`表头排序不替人应用别的修改。

范围里删掉「状态」这条条件、还没按「应用」，这时按「订单号」表头：从前
这一下把整份草稿都跑了，删掉的条件跟着生效。现在排序只并进待应用——行
不动、表头的箭头仍说屏幕上这些行的次序（金额降序）、「应用」上亮起那颗
点——按「应用」时两件事一起跑：不止待出库的那几单，按订单号升序。`,...F.parameters?.docs?.description}}},I.parameters={...I.parameters,docs:{...I.parameters?.docs,source:{originalSource:`{
  ...DisplayWithData,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');

    // Out, so all three blocks of the column are on the page at once.
    await userEvent.click(canvas.getByRole('button', {
      name: new RegExp(\`^\${zhCN['label.filter.panel']}\`)
    }));
    await waitFor(() => expect(canvasElement.querySelector('[data-slot="editor-band"]')).not.toBeNull());
    const main = canvasElement.querySelector<HTMLElement>('main')!;
    await expect(canvasElement.querySelectorAll('main')).toHaveLength(1);
    const column = getComputedStyle(main);
    await expect(column.rowGap).toBe('16px');
    await expect(column.gap).toBe('16px');

    // And the result block is one framed region (D12 Ⅳ–Ⅶ): no row gap of
    // its own — the toolbar is its first row and the pagination its last,
    // each ruled off from the rows between them with a hairline, so the
    // space inside the frame is the slots' padding, not a gap.
    const result = canvasElement.querySelector<HTMLElement>('[data-slot="result-block"]')!;
    await expect(result).toHaveAttribute('data-framed', 'true');
    await expect(getComputedStyle(result).rowGap).toBe('normal');
    await expect(getComputedStyle(result).borderTopWidth).toBe('1px');
    const toolbar = canvasElement.querySelector<HTMLElement>('[data-slot="result-toolbar"]')!;
    await expect(getComputedStyle(toolbar).borderBottomWidth).toBe('1px');
    const pagination = canvasElement.querySelector<HTMLElement>('[data-slot="record-pagination"]')!;
    await expect(getComputedStyle(pagination).borderTopWidth).toBe('1px');
  }
}`,...I.parameters?.docs?.source},description:{story:`The ruler between the blocks of the main column, the right way up.

This workbench handed the shell a \`className="gap-2"\` and \`cn\` let it beat
\`SPACE.BLOCKS\`, so with the conditions out the column measured header →
8px → editor band → 8px → result while the rows *inside* each block sat
12px apart: two blocks stood closer together than two buttons do, and
nothing on the screen read as a group. Analysis and Dashboard, on the very
same shell, measured 16px throughout.

It is measured here rather than in jsdom because what a class is worth in
pixels is the stylesheet's answer, and jsdom lays out nothing: the package's
jsdom suite can pin the class (\`test/recordWorkbench.test.tsx\`) and no more.
Both steps of the ladder are read, because the bug was never one number on
its own — it was the two of them in the wrong order.`,...I.parameters?.docs?.description}}},L.parameters={...L.parameters,docs:{...L.parameters?.docs,source:{originalSource:`{
  ...DisplayCellFamily,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    await waitFor(() => expect(readColumn(table, '订单号')).toHaveLength(6));

    // A status is one badge in the tone its own option declares. The colour
    // is never the only difference — the label says which status this is.
    const pending = badgeIn(table, '状态')!;
    await expect(pending).toHaveTextContent('待出库');
    await expect(pending).toHaveAttribute('data-tone', 'warning');
    const cancelled = cellAt(table, '状态', 1).querySelector('[data-slot="badge"]')!;
    await expect(cancelled).toHaveTextContent('已取消');
    await expect(cancelled).toHaveAttribute('data-tone', 'danger');
    // Danger is the one tone the registry itself has a variant for.
    await expect(cancelled).toHaveAttribute('data-variant', 'destructive');

    // A list is one badge per entry, and an entry the options stopped
    // naming shows the code it came as rather than disappearing.
    await expect(badgeTexts(cellAt(table, '标记', 0))).toEqual(['加急', '易碎']);
    await expect(badgeTexts(cellAt(table, '标记', 5))).toEqual(['vip']);

    // A URL is a link out of the application, so the document it opens must
    // not reach back through \`window.opener\` nor arrive knowing where from.
    const link = cellAt(table, '运单', 0).querySelector('a')!;
    await expect(link).toHaveAttribute('target', '_blank');
    await expect(link).toHaveAttribute('rel', 'noopener noreferrer');
    await expect(link).toHaveAccessibleName('https://example.com/track/SO-1001');
    // And a scheme the content rules refuse is text, never a live link.
    await expect(cellAt(table, '运单', 4).querySelector('a')).toBeNull();
    await expect(cellAt(table, '运单', 4)).toHaveTextContent('javascript:alert(1)');

    // A note takes one line in a table, with the whole of it one hover
    // away — the one assertion that needs a browser to lay the box out.
    // Three lines are a card's, where there is no column to read down and
    // a taller row costs nothing (\`test/recordCells.test.tsx\` holds that
    // half — it is a class either way, and only this side needs a layout).
    const note = cellAt(table, '备注', 4).querySelector<HTMLElement>('[data-slot="cell-text"]')!;
    await expect(note).toHaveAttribute('title', note.textContent!);
    await expect(getComputedStyle(note).whiteSpace).toBe('nowrap');
    await expect(getComputedStyle(note).textOverflow).toBe('ellipsis');
    await expect(note.scrollWidth).toBeGreaterThan(note.clientWidth);

    // And the copyable column is the text it always was — no pill around it
    // and nothing to click — with one button beside it, named after the
    // value it would take away. What that button does is the next story.
    const key = cellAt(table, '订单号', 0);
    await expect(key).toHaveTextContent('SO-1001');
    await expect(key.querySelector('[data-slot="badge"]')).toBeNull();
    await expect(key.querySelector('a')).toBeNull();
    const copy = key.querySelector<HTMLElement>('[data-slot="cell-copy"]')!;
    await expect(copy).toHaveAccessibleName(say('label.copy-of', {
      value: 'SO-1001'
    }));
  }
}`,...L.parameters?.docs?.source},description:{story:`Every reading a definition can declare, in a real browser.

The three that jsdom cannot answer for are here: whether a link really
carries the two attributes that keep the opened document from reaching
back, whether the tone reaches the badge as a variant rather than only as
an attribute, and whether an undeclared column is still exactly what it
always was.`,...L.parameters?.docs?.description}}},R.parameters={...R.parameters,docs:{...R.parameters?.docs,source:{originalSource:`{
  ...DisplayElementColumns,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    await waitFor(() => expect(readColumn(table, '订单号')).toHaveLength(6));
    const row = (id: string) => readColumn(table, '订单号').findIndex(text => text.startsWith(id));
    await expect(badgeTexts(cellAt(table, '明细', row('SO-1001')))).toEqual(['TEA-01', 'CUP-12']);
    // Three lines are three badges: an event stream of three reads whole.
    await expect(badgeTexts(cellAt(table, '明细', row('SO-1003')))).toEqual(['CARD-07', 'TEA-01', 'BOX-02']);

    // Five are the first two and a count, on one line, with the whole list
    // one hover away and the hidden three read out rather than drawn.
    const five = cellAt(table, '明细', row('SO-1004'));
    await expect(badgeTexts(five)).toEqual(['CUP-12', 'PLATE-05']);
    const more = five.querySelector('[data-slot="cell-elements-more"]')!;
    await expect(more).toHaveTextContent('+3');
    await expect(more).toHaveAttribute('aria-hidden', 'true');
    const list = five.querySelector<HTMLElement>('[data-slot="cell-elements"]')!;
    await expect(list).toHaveAttribute('title', 'CUP-12、PLATE-05、BOWL-04、SPOON-09、TRAY-01');
    await expect(getComputedStyle(list).flexWrap).toBe('nowrap');
    await expect(five).toHaveTextContent(/BOWL-04、SPOON-09、TRAY-01/);

    // A table row is one line: the five-line order's row is as tall as the
    // one-line order's.
    const height = (id: string) => (table as HTMLTableElement).tBodies[0].rows[row(id)].getBoundingClientRect().height;
    await expect(height('SO-1004')).toBe(height('SO-1006'));

    // No title declared: counted, and an empty list says nothing at all.
    await expect(cellAt(table, '包裹', row('SO-1005'))).toHaveTextContent('3 项');
    await expect(cellAt(table, '包裹', row('SO-1001'))).toHaveTextContent('1 项');
    await expect(cellAt(table, '包裹', row('SO-1002'))).toHaveTextContent('');
    await expect(table.textContent).not.toMatch(/[{}]/);
  }
}`,...R.parameters?.docs?.source},description:{story:`An array of objects reads as its elements in a real table: each order's
lines by their SKU, one line high, the fifth line of a five-line order
counted rather than drawn, and the parcels — which name no title — as how
many there are. Nowhere a brace: the JSON these cells used to hold is what
pushed a real event stream's table off the screen.`,...R.parameters?.docs?.description}}},z.parameters={...z.parameters,docs:{...z.parameters?.docs,source:{originalSource:`{
  ...DisplayCellFamily,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    await waitFor(() => expect(readColumn(table, '订单号')).toHaveLength(6));
    const key = cellAt(table, '订单号', 0);
    const copy = key.querySelector<HTMLElement>('[data-slot="cell-copy"]')!;
    // Resting invisible, which is only true where there is a pointer to
    // reveal it with: the runner reports \`(hover: hover)\`, so the utility
    // that hides it is in force here and the one that would show it hangs
    // off the groups the row and the cell carry. The reveal itself cannot be
    // driven from a play — the runner's pointer events put no real \`:hover\`
    // on an element (see 「侧栏当前行」 below) — so what is measured is that
    // the button is hidden *without being taken away*: laid out, in the tab
    // order, and one keyboard focus from showing itself.
    await expect(getComputedStyle(copy).opacity).toBe('0');
    await expect(getComputedStyle(copy).display).not.toBe('none');
    await expect(copy.tabIndex).toBeGreaterThanOrEqual(0);
    await expect(key.parentElement!.className).toContain('group/row');
    await expect(key.querySelector('[data-slot="cell-copyable"]')!.className).toContain('group/copyable');
    await userEvent.click(copy);

    // The tick, and the word said to whoever cannot see it.
    const copied = say('label.copied', {});
    await waitFor(() => expect(copy).toHaveAccessibleName(copied));
    await expect(canvasElement.querySelector('[data-slot="cell-copy-announcement"]')).toHaveTextContent(copied);
    try {
      await waitFor(async () => expect(await navigator.clipboard.readText()).toBe('SO-1001'));
    } catch {
      // Reading the clipboard was refused, which is the browser's right:
      // the state above is then all this play can pin, and it is pinned.
    }

    // And the answer stands down again, so the row stops claiming it.
    await waitFor(() => expect(copy).toHaveAccessibleName(say('label.copy-of', {
      value: 'SO-1001'
    })), {
      timeout: 4_000
    });
  }
}`,...z.parameters?.docs?.source},description:{story:`Taking a document number away, in a real browser (user request
2026-09-22).

jsdom can be told what \`navigator.clipboard\` is; only a browser has one.
So the button is pressed here and the clipboard is asked what it now
holds. That read is the part a browser may refuse — \`clipboard-read\` is a
permission of its own, and Chromium grants it to the story context but
nothing promises it will — so it is wrapped: what has to hold either way
is that the button answered, in the words the catalogue gives it, and then
stood down again.`,...z.parameters?.docs?.description}}},B.parameters={...B.parameters,docs:{...B.parameters?.docs,source:{originalSource:`{
  ...DisplayEarliestAndLatest,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    // No condition, so all six orders are on screen — the seventh is
    // soft-deleted and a Wow source does not answer it.
    await waitFor(() => expect(readColumn(table, '订单号')).toHaveLength(6));

    // They were created between these two moments, read as the column reads
    // a cell: the surface's language, the engine's zone.
    const shown = (iso: string) => new Intl.DateTimeFormat('zh-CN', {
      dateStyle: 'medium',
      timeStyle: 'medium'
    }).format(new Date(iso));
    const earliest = shown('2026-09-15T02:10:00.000Z');
    const latest = shown('2026-09-17T08:45:00.000Z');

    // All six are one page, so the footer is the one 「全部」 row (D26 Q40);
    // the page row reads dates the same way (test/recordSummaries.test.tsx
    // 「reads a date summary the way the column reads its cells」).
    await expect(scopeLabels(table)).toEqual([zhCN['label.summary.scope.total']]);
    const cell = readTotal(table, '创建时间');
    await expect(cell).toContain(zhCN['label.summary.fn.date.MIN']);
    await expect(cell).toContain(zhCN['label.summary.fn.date.MAX']);
    await expect(cell).toContain(earliest);
    await expect(cell).toContain(latest);
    // Not the stored value, and not the number it was compared as.
    await expect(cell).not.toContain('2026-09-15T02:10');
    await expect(cell).not.toContain(zhCN['label.summary.fn.MIN']);
    // The money beside it is unchanged: a sum, in its own format.
    await expect(amountOf(readTotal(table, '金额'))).toBe(10230);
    await expect(readTotal(table, '金额')).toContain(zhCN['label.summary.fn.SUM']);
  }
}`,...B.parameters?.docs?.source},description:{story:`A date column's earliest and latest, in the footer.

Three things have to hold at once and only a browser shows all three: the
word is the one a moment takes (「最早」, never 「最小」), the value goes
through the same reading the cells above it go through — the surface's
language and the engine's zone, so neither thirteen digits nor a raw ISO
string reaches the screen — and the money in the same row is still a sum
with its currency, so one reading has not swallowed the other.`,...B.parameters?.docs?.description}}},V.parameters={...V.parameters,docs:{...V.parameters?.docs,source:{originalSource:`{
  ...DisplayPaged,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    await waitFor(() => expect(readColumn(table, '订单号')).toEqual(['SO-1001', 'SO-1002']));
    const bar = paginationBar(canvasElement);
    // The total is every order the conditions select, not the two on screen.
    await expect(bar).toHaveTextContent(say('label.pagination.total', {
      total: 6
    }));
    await expect(bar).toHaveTextContent(say('label.toolbar.page-of', {
      index: 1,
      pages: 3
    }));
    // Three pages, so the page is a part of the result and both summary rows
    // stand: the two rows on screen, and every order the conditions select.
    await expect(scopeLabels(table)).toEqual([zhCN['label.summary.scope.page'], zhCN['label.summary.scope.total']]);
    await expect(amountOf(readTotal(table, '金额'))).toBe(10230);
    await expect(amountOf(readPage(table, '金额'))).toBeLessThan(10230);
    // The size in force is offered back with its unit attached.
    await expect(within(bar).getByRole('combobox', {
      name: zhCN['label.pagination.page-size']
    })).toHaveTextContent(say('label.pagination.page-size-option', {
      size: 2
    }));

    // Page one has nowhere to go back to.
    const previous = within(bar).getByRole('button', {
      name: zhCN['label.toolbar.previous']
    });
    const next = within(bar).getByRole('button', {
      name: zhCN['label.toolbar.next']
    });
    await expect(previous).toBeDisabled();

    // Forward: new rows, a new page sentence, the same total.
    await userEvent.click(next);
    await waitFor(() => expect(readColumn(table, '订单号')).toEqual(['SO-1003', 'SO-1004']));
    await expect(paginationBar(canvasElement)).toHaveTextContent(say('label.toolbar.page-of', {
      index: 2,
      pages: 3
    }));
    await expect(paginationBar(canvasElement)).toHaveTextContent(say('label.pagination.total', {
      total: 6
    }));

    // And back again, which is now open.
    await expect(within(paginationBar(canvasElement)).getByRole('button', {
      name: zhCN['label.toolbar.previous']
    })).toBeEnabled();
    await userEvent.click(within(paginationBar(canvasElement)).getByRole('button', {
      name: zhCN['label.toolbar.previous']
    }));
    await waitFor(() => expect(readColumn(table, '订单号')).toEqual(['SO-1001', 'SO-1002']));
    await expect(paginationBar(canvasElement)).toHaveTextContent(say('label.toolbar.page-of', {
      index: 1,
      pages: 3
    }));

    // And the page said outright rather than stepped to (D18 ruling Ⅷ). The
    // box carries the page that landed, so typing over it is a jump from
    // where the reader is; past the end it is clamped rather than refused.
    const goTo = within(paginationBar(canvasElement)).getByRole('textbox', {
      name: zhCN['label.pagination.go-to']
    });
    await expect(goTo).toHaveValue('1');
    await userEvent.clear(goTo);
    await userEvent.type(goTo, '3{Enter}');
    await waitFor(() => expect(readColumn(table, '订单号')).toEqual(['SO-1005', 'SO-1006']));
    await expect(paginationBar(canvasElement)).toHaveTextContent(say('label.toolbar.page-of', {
      index: 3,
      pages: 3
    }));
    const past = within(paginationBar(canvasElement)).getByRole('textbox', {
      name: zhCN['label.pagination.go-to']
    });
    await userEvent.clear(past);
    await userEvent.type(past, '99{Enter}');
    await expect(past).toHaveValue('3');
    await expect(paginationBar(canvasElement)).toHaveTextContent(say('label.toolbar.page-of', {
      index: 3,
      pages: 3
    }));
  }
}`,...V.parameters?.docs?.source},description:{story:`The bar under the rows, on a result that has somewhere to go.

It is one row: how many records there are in all on the left, and on the
right how many are shown per page, which page this is, and the two steps
out of it. \`WithData\` above only ever sees the single-page form of it.`,...V.parameters?.docs?.description}}},H.parameters={...H.parameters,docs:{...H.parameters?.docs,source:{originalSource:`{
  ...DisplayPagedWindow,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    await waitFor(() => expect(readColumn(table, '订单号')).toEqual(['SO-1001', 'SO-1002']));
    const bar = paginationBar(canvasElement);
    // The total is still every order; the pages are the ones within reach.
    await expect(bar).toHaveTextContent(say('label.pagination.total', {
      total: 6
    }));
    await expect(bar).toHaveTextContent(say('label.toolbar.page-of', {
      index: 1,
      pages: 2
    }));
    const line = say('label.pagination.window', {
      count: 4
    });
    await expect(bar).toHaveTextContent(line);
    await expect(bar).toHaveAccessibleDescription(line);

    // A jump past the window lands on its last page rather than failing.
    const goTo = within(bar).getByRole('textbox', {
      name: zhCN['label.pagination.go-to']
    });
    await userEvent.clear(goTo);
    await userEvent.type(goTo, '3{Enter}');
    await waitFor(() => expect(readColumn(table, '订单号')).toEqual(['SO-1003', 'SO-1004']));
    await expect(goTo).toHaveValue('2');
    await expect(paginationBar(canvasElement)).toHaveTextContent(say('label.toolbar.page-of', {
      index: 2,
      pages: 2
    }));

    // And the last reachable page is the last page: Next is spent.
    await expect(within(paginationBar(canvasElement)).getByRole('button', {
      name: zhCN['label.toolbar.next']
    })).toBeDisabled();
  }
}`,...H.parameters?.docs?.source},description:{story:`A source with a paging window (Wow over Elasticsearch refuses a page past
row 10 000): six orders two at a time under a window of four. The bar
counts the pages the window lets it reach, stops Next on the last of them,
lands a jump past it on that page, and says in one line why — and the line
is the bar's accessible description, so a screen reader hears it too.`,...H.parameters?.docs?.description}}},U.parameters={...U.parameters,docs:{...U.parameters?.docs,source:{originalSource:`{
  ...DisplayWithData,
  args: {
    ...DisplayWithData.args,
    narrowHost: true,
    narrowWidth: 1300
  },
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    await waitFor(() => expect(readColumn(table, '订单号')).toEqual(PENDING_BY_AMOUNT));
    const port = canvasElement.querySelector<HTMLElement>('[data-slot="record-table"]')!;
    // The result area is wide and nothing scrolls sideways in it.
    await expect(port.clientWidth).toBeGreaterThan(800);
    await expect(port.scrollWidth).toBeLessThanOrEqual(port.clientWidth + 1);
    const heads = [...table.querySelectorAll<HTMLElement>('thead tr:first-child>th')];
    const filler = heads[heads.length - 1]!;
    await expect(filler.dataset.column).toBe('filler');

    // No real column is a sea of empty: the widest is nowhere near the 446px
    // the same fixture drew when the surplus was shared out among them.
    const columns = heads.slice(0, -1);
    const widest = Math.max(...columns.map(head => head.getBoundingClientRect().width));
    await expect(widest).toBeLessThan(200);
    // And the surplus is real: it went somewhere, and that somewhere is the
    // cell nobody is told about.
    await expect(filler.getBoundingClientRect().width).toBeGreaterThan(400);

    // Every row reaches the frame — the header, a body row and both summary
    // rows — so a hairline, a hover band and the muted footer are whole.
    const edge = port.getBoundingClientRect().right;
    for (const row of table.querySelectorAll<HTMLElement>('tr')) await expect(Math.round(row.getBoundingClientRect().right)).toBe(Math.round(edge));

    // D13's right edge belongs to the last *data* column, which is where
    // the columns end; past it there is nothing rather than more table. And
    // at 1300px nothing scrolls, so the edge is not drawn (P-23): a line
    // there cut the surplus off the column and made the filler read as an
    // empty column.
    const amount = columns[columns.length - 1]!;
    await expect(amount.dataset.pin).toBe('right');
    await expect(port.hasAttribute('data-overflowing')).toBe(false);
    await expect(getComputedStyle(amount).boxShadow).toBe('none');
    await expect(Math.round(filler.getBoundingClientRect().left - amount.getBoundingClientRect().right)).toBe(0);

    // And the names survive it. The sort button carries \`max-w-full\`, which
    // is the cell's content box, while it pulls that padding back out with
    // \`-mx-2\`: capped there it was 16px short and \`订单号\` read \`订…\` — a
    // name the tooltip could give back, from a column that had the room.
    for (const head of columns) {
      const label = head.querySelector<HTMLElement>('[data-slot="column-label"]');
      if (!label) continue;
      await expect(label.scrollWidth).toBeLessThanOrEqual(Math.ceil(label.getBoundingClientRect().width));
    }
  }
}`,...U.parameters?.docs?.source},description:{story:`列各占自己要的宽度，行照样通到框边（P-11）。

注册表的表是 \`w-full\`，自动布局把富余按比例分给各列：1300px 的结果区里，
四列表把「金额」画成 446px，里面是一句 \`¥2,450.00\`——一眼从订单号扫到
金额要横穿大半个屏幕。现在富余归末尾那一格空格子，各列落回自己内容要的
宽度，而行（发丝线、悬停底色、两条汇总行）仍然与端口同宽。宿主宽度写死
成 1300px（\`narrowHost\` 那个定宽壳子，这里给的是一个宽数——场景框自己
限宽 1040px，量的是布局不是画面），量四件事：没有一列是那 446px、每一行
都通到端口右缘、D13 右边那道线还在最后一个数据列上、列名一个字也没被
截掉。`,...U.parameters?.docs?.description}}},W.parameters={...W.parameters,docs:{...W.parameters?.docs,source:{originalSource:`{
  ...DisplayWithData,
  decorators: [Story => <div data-testid="host-frame" style={{
    display: 'grid',
    height: 640
  }}>
        <Story />
      </div>],
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    const table = (await canvas.findByRole('table')) as HTMLTableElement;
    await waitFor(() => expect(readColumn(table, '订单号')).toEqual(PENDING_BY_AMOUNT));
    const host = canvas.getByTestId('host-frame');
    const frame = canvasElement.querySelector<HTMLElement>('[data-slot="result-block"]')!;
    const port = canvasElement.querySelector<HTMLElement>('[data-slot="record-table"]')!;
    const pagination = canvasElement.querySelector<HTMLElement>('[data-slot="record-pagination"]')!;
    const bottom = (element: Element) => element.getBoundingClientRect().bottom;

    // The frame reaches the host's bottom: it is a band that bleeds through
    // the work column's padding, so its bottom is the workbench's own.
    await waitFor(() => expect(Math.abs(bottom(frame) - bottom(host))).toBeLessThanOrEqual(1));
    // The pagination is the frame's last row, at its bottom edge.
    await expect(Math.abs(bottom(pagination) - bottom(frame))).toBeLessThanOrEqual(1);
    // Four rows leave room, and the totals sit at the bottom of the port —
    // beside the pagination — rather than under the last row.
    await waitFor(() => expect(table.querySelector('[data-slot="row-room"]')).not.toBeNull());
    await expect(Math.abs(bottom(table.tFoot!) - bottom(port))).toBeLessThanOrEqual(1);
    // The room row is not a row: the rows' own body still holds only rows.
    await expect(table.tBodies[0].rows).toHaveLength(PENDING_BY_AMOUNT.length);
  }
}`,...W.parameters?.docs?.source},description:{story:`宿主给了定高，工作台就填满它：分页贴在底边，本页／全部合计贴在分页上面，行
少时空白留在表格里（2026-09-23，借鉴 legacy 控制台）。

从前结果区只是「最多长到视口底」：四行数据时合计紧跟第四行、分页紧跟合计，
两个页脚一起浮在半屏处，每换一页位置都不同。这里把工作台放进一个 640px 高的
宿主框里，量三件事：分页的下边就是结果框的下边，合计的下边就是表格滚动区的
下边，结果框的下边就是宿主框底（结果区是贴边的带，穿过工作列的内边距）。`,...W.parameters?.docs?.description}}},G.parameters={...G.parameters,docs:{...G.parameters?.docs,source:{originalSource:`{
  ...DisplayWideTable,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    await userEvent.click(canvas.getByRole('button', {
      name: zhCN['label.layout.cards']
    }));
    const region = await waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>('[data-slot="record-cards"]');
      if (!found) throw new Error('卡片区还没出来');
      return found;
    });
    const cards = await waitFor(() => {
      const found = [...region.querySelectorAll<HTMLElement>('[data-slot="card"]')];
      expect(found.length).toBeGreaterThan(20);
      return found;
    });
    // Nothing of a card is cut off: what it holds fits the box it has.
    for (const card of cards) await expect(card.scrollHeight).toBeLessThanOrEqual(card.clientHeight + 1);
    // And more cards than room: the region scrolls rather than squeezing.
    await expect(region.scrollHeight).toBeGreaterThan(region.clientHeight + 1);
  }
}`,...G.parameters?.docs?.source},description:{story:`卡片一张不少地排下来，每张都有它自己那么高。

工作台填满容器之后，卡片区是一个定高的网格；网格按它有的空间分行高而不是按
卡片的内容，而卡片裁掉自己的溢出、最少要的是零——五十张卡在 900px 里被压成
一排排只剩标题的条（用户 2026-09-23 走查发现）。这里切到卡片，五十张：每张
卡的内容都在它自己的框里（没有被裁掉），卡片区自己滚动。`,...G.parameters?.docs?.description}}},K.parameters={...K.parameters,docs:{...K.parameters?.docs,source:{originalSource:`{
  ...DisplayWideTable,
  decorators: [Story => <div data-content-sized style={{
    alignSelf: 'start'
  }}>
        <Story />
      </div>],
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    const table = (await canvas.findByRole('table')) as HTMLTableElement;
    await waitFor(() => expect(table.tBodies[0].rows.length).toBeGreaterThan(10));
    const root = canvasElement.querySelector<HTMLElement>('[data-content-sized] > .fve-root')!;
    const port = canvasElement.querySelector<HTMLElement>('[data-slot="record-table"]')!;
    const pagination = canvasElement.querySelector<HTMLElement>('[data-slot="record-pagination"]')!;
    const rem = parseFloat(getComputedStyle(document.documentElement).fontSize);

    // The floor is the whole of its height, and the rows scroll inside it.
    await waitFor(() => expect(Math.abs(root.getBoundingClientRect().height - 36 * rem)).toBeLessThanOrEqual(1));
    await waitFor(() => expect(port.scrollHeight).toBeGreaterThan(port.clientHeight + 1));
    // The footer is at the root's bottom: the pagination's edge is its edge,
    // and both summary rows are inside it.
    const bottom = root.getBoundingClientRect().bottom;
    await expect(Math.abs(pagination.getBoundingClientRect().bottom - bottom)).toBeLessThanOrEqual(1);
    for (const row of table.querySelectorAll<HTMLElement>('tfoot tr')) await expect(row.getBoundingClientRect().bottom).toBeLessThanOrEqual(bottom);
  }
}`,...K.parameters?.docs?.source},description:{story:`宿主没给高度，工作台就停在它的保底高度上，页脚照样贴底。

高度布局只有一种（2026-09-23，用户按推荐定）：工作台永远填满容器。容器没有
确定高度时 \`h-full\` 什么也不是，36rem 的保底（\`--fve-workbench-min-height\`）
就是它的全部——从前这里是「页面流」：表格按量出来的视窗剩余空间封顶，行少时
分页浮在半屏。这里把工作台放在一个按内容定高的外层里，五十行的宽表：根的高
度正是 36rem，表格在自己的滚动口里滚，两行合计与分页都在根的底边以内，分页
的下边就是根的下边。`,...K.parameters?.docs?.description}}},q.parameters={...q.parameters,docs:{...q.parameters?.docs,source:{originalSource:`{
  ...DisplayWithActions,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    await waitFor(() => expect(readColumn(table, '订单号')).toEqual(PENDING_BY_AMOUNT));
    await expect(within(canvasElement.querySelector<HTMLElement>('[data-slot="view-header"]')!).getByRole('button', {
      name: '新建订单'
    })).toBeVisible();
    await expect(canvas.queryByRole('button', {
      name: '导出所选'
    })).toBeNull();

    // The line between this package's controls and the host's own action
    // stands centred on the row it parts — not parked at its top, which is
    // where a stretched item with a height of its own ends up.
    const controls = canvasElement.querySelector<HTMLElement>('[data-slot="view-controls"]')!;
    const divider = controls.querySelector<HTMLElement>('[data-slot="separator"]')!;
    const middle = (rect: DOMRect) => rect.top + rect.height / 2;
    await expect(Math.abs(middle(divider.getBoundingClientRect()) - middle(controls.getBoundingClientRect()))).toBeLessThanOrEqual(1);

    // One row action per row, in a column pinned to the end of the table.
    await expect(canvas.getAllByRole('button', {
      name: '打开'
    })).toHaveLength(PENDING_BY_AMOUNT.length);
    await expect(canvas.getByRole('columnheader', {
      name: zhCN['label.toolbar.actions']
    }).className).toContain('sticky');
    await userEvent.click(canvas.getByLabelText(zhCN['label.record.select-all']));
    await expect(await canvas.findByRole('button', {
      name: '导出所选'
    })).toBeVisible();
  }
}`,...q.parameters?.docs?.source},description:{story:`The host's three slots, each where it belongs: over the view, over a
selection, and on one row. The middle one exists only while rows are picked.`,...q.parameters?.docs?.description}}},J.parameters={...J.parameters,docs:{...J.parameters?.docs,source:{originalSource:`{
  ...DisplayWithActions,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    await waitFor(() => expect(readColumn(table, '订单号')).toEqual(PENDING_BY_AMOUNT));
    const line = (state: 'running' | 'settled') => waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>(\`[data-slot="bulk-status"][data-state="\${state}"]\`);
      expect(found).not.toBeNull();
      return found!;
    });
    await userEvent.click(canvas.getByLabelText(zhCN['label.record.select-all']));
    await userEvent.click(await canvas.findByRole('button', {
      name: '导出所选'
    }));

    // While it runs the button is disabled, so a second press cannot send a
    // second write over the same rows, and the line counts as it goes.
    await expect(canvas.getByRole('button', {
      name: '导出所选'
    })).toBeDisabled();
    const running = await line('running');
    await expect(within(running).getByRole('button', {
      name: zhCN['label.bulk.stop']
    })).toBeVisible();
    const done = await line('settled');
    // A run everything took is a note, not an interruption.
    await expect(done).toHaveAttribute('role', 'status');
    await expect(done).toHaveAttribute('data-tone', 'info');
    await expect(done).toHaveTextContent(say('label.bulk.done', {
      done: PENDING_BY_AMOUNT.length
    }));
    // Everything took it, so the selection is let go — and the line is not.
    await expect(canvas.queryByRole('button', {
      name: '导出所选'
    })).toBeNull();
    await userEvent.click(within(done).getByRole('button', {
      name: zhCN['label.bulk.dismiss']
    }));
    await waitFor(() => expect(canvasElement.querySelector('[data-slot="bulk-status"]')).toBeNull());

    // Over every order, the cancelled one is refused: its reason is said
    // with how many gave it, and it is the one row left selected.
    await userEvent.click(canvas.getByRole('button', {
      name: /^全部订单/
    }));
    await waitFor(() => expect(readColumn(canvas.getByRole('table'), '订单号')).toContain('SO-1002'));
    await userEvent.click(canvas.getByLabelText(zhCN['label.record.select-all']));
    await userEvent.click(await canvas.findByRole('button', {
      name: '导出所选'
    }));
    const partial = await line('settled');
    await expect(partial).toHaveAttribute('data-tone', 'warning');
    await expect(partial).toHaveTextContent(say('label.bulk.reason', {
      reason: '已取消的订单不能导出。',
      count: 1
    }));
    await expect(partial).toHaveTextContent(zhCN['label.bulk.left']);
    await waitFor(() => expect(canvas.getByLabelText(say('label.record.select', {
      key: 'SO-1002'
    }))).toBeChecked());
    await expect(canvas.getByLabelText(say('label.record.select', {
      key: 'SO-1001'
    }))).not.toBeChecked();
  }
}`,...J.parameters?.docs?.source},description:{story:`批量命令从按下到落定，工作台在行的上方说清它：跑到第几条、结局是什么、为什么
有的没做成，而没做成的那几行仍选着——它们就是还要处理的行。

宿主只写了「导出一单」这一件事，其余（几条一起跑、进度、逐条原因、选择怎么办、
刷新）都来自 \`useBulkCommand\`，那一条由工作台画在结果区（\`record.bulk\`）。先在
待出库订单里全选导出，全都做成：选择放开，那一条还在；再到「全部订单」全选导出，
已取消的 SO-1002 被拒：那一条说出拒绝的原因与条数，SO-1002 仍选着。`,...J.parameters?.docs?.description}}},Y.parameters={...Y.parameters,docs:{...Y.parameters?.docs,source:{originalSource:`{
  ...DisplayWithActions,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    await waitFor(() => expect(readColumn(table, '订单号')).toEqual(PENDING_BY_AMOUNT));
    const boxes = () => [...table.querySelectorAll<HTMLElement>('tbody [role="checkbox"]')];
    const picked = () => boxes().map(box => box.getAttribute('aria-checked') === 'true');
    // The rows' own body: in a host that gives the workbench a height, the
    // room the rows leave is a second, hidden body with a row of its own.
    const selectedRows = () => [...(table as HTMLTableElement).tBodies[0].rows].map(row => row.getAttribute('data-state') === 'selected');
    // One instance for the whole gesture, so the Shift held down is still
    // held when the press lands.
    const user = userEvent.setup();

    // A plain press sets the anchor; a Shift+press two rows down takes the
    // rows between with it.
    await user.click(boxes()[0]);
    await user.keyboard('{Shift>}');
    await user.click(boxes()[2]);
    await user.keyboard('{/Shift}');
    await waitFor(() => expect(picked()).toEqual([true, true, true, false]));
    await expect(selectedRows()).toEqual([true, true, true, false]);
    await expect(await canvas.findByText(say('label.toolbar.selected', {
      count: 3
    }))).toBeVisible();

    // Shift+Space on a focused checkbox is the same gesture, and the anchor
    // is still the first row: the range did not move it.
    boxes()[3].focus();
    await user.keyboard('{Shift>}[Space]{/Shift}');
    await waitFor(() => expect(picked()).toEqual([true, true, true, true]));

    // Shift on a picked row clears the range, the way that row goes.
    await user.keyboard('{Shift>}');
    await user.click(boxes()[1]);
    await user.keyboard('{/Shift}');
    await waitFor(() => expect(picked()).toEqual([false, false, true, true]));
    await expect(await canvas.findByText(say('label.toolbar.selected', {
      count: 2
    }))).toBeVisible();

    // What Shift does is said once, and every row checkbox points at it.
    const hints = new Set(boxes().map(box => box.getAttribute('aria-describedby')));
    await expect(hints.size).toBe(1);
    await expect(document.getElementById([...hints][0]!)).toHaveTextContent(zhCN['label.record.select.hint']);
  }
}`,...Y.parameters?.docs?.source},description:{story:"按住 Shift 勾选一段：从上一次平点的那一行到按下的这一行，按结果顺序整段选中\n或取消（`RecordTableController.toggle(key, { range })`）。\n\n真浏览器里走一遍 jsdom 走不到的那条链：Base UI 的勾选框把根上的点击连同修饰\n键转发给它藏着的 `<input>`，`onCheckedChange` 读到的原生事件才带着 Shift；\n键盘那一路是根上 keyup 的空格被转成同样带修饰键的点击。两条路任何一环丢了\nShift，这里读到的就只剩一行。工具栏的计数与每行的 `data-state` 是用户眼里的\n两处读数，一并核对。",...Y.parameters?.docs?.description}}},X.parameters={...X.parameters,docs:{...X.parameters?.docs,source:{originalSource:`{
  ...DisplayWithActions,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    const host = canvas.getByRole('button', {
      name: '新建订单'
    });
    await userEvent.click(canvas.getByRole('button', {
      name: new RegExp(\`^\${zhCN['label.filter.panel']}\`)
    }));
    const apply = await canvas.findByRole('button', {
      name: zhCN['label.filter.apply']
    });
    // The vendored button has \`transition-all\`, so the steady colour is what
    // is read — and the band has just opened.
    await settled(() => getComputedStyle(apply).backgroundColor);
    const primary = getComputedStyle(apply).backgroundColor;
    const alike = [...canvasElement.querySelectorAll<HTMLElement>('button')].filter(button => getComputedStyle(button).backgroundColor === primary).map(button => button.textContent?.trim());
    await expect(alike, \`painted \${primary}\`).toEqual([zhCN['label.filter.apply']]);
    // And the host's own action is drawn as an outline button: a fill it
    // shares with the surface behind it, and an edge of its own.
    const drawn = getComputedStyle(host);
    await expect(drawn.backgroundColor).not.toBe(primary);
    await expect(drawn.borderTopWidth).not.toBe('0px');
  }
}`,...X.parameters?.docs?.source},description:{story:`一屏只有一个 primary，它是跑查询的那个 Apply——宿主的全局动作不是（D12 Ⅰ）。

D12 Ⅰ 原本写的是「宿主的主功能按钮，同屏唯一 primary」，而[动作槽位](
typescript/wow-view-engine/docs/design/ui/README.md)一直写着相反的规矩：编辑带一
展开，屏幕上就有两个 primary。2026-09-21 用户裁定了后者——排在最右说的是
「这是业务的去处」，不是「这是这一屏最该按的东西」。

这里不认类名认颜色：把编辑带打开，量遍这一屏上每一颗按钮的实际底色，与
Apply 同色的应当只有 Apply 自己。jsdom 不套样式表，这个数只有真浏览器给得
出（\`test/analysisUi.test.tsx\` 按 variant 的类名钉的是结构那一半）。`,...X.parameters?.docs?.description}}},Z.parameters={...Z.parameters,docs:{...Z.parameters?.docs,source:{originalSource:`{
  ...DisplayManageViews,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    await userEvent.click(canvas.getByRole('button', {
      name: zhCN['label.manage.open']
    }));
    // The dialog portals out of the canvas, so it is found on the document.
    await within(document.body).findByRole('dialog');
    const row = (title: string) => {
      const found = [...document.querySelectorAll<HTMLElement>('[data-slot="view-manager-row"]')].find(candidate => candidate.textContent?.includes(title) || [...candidate.querySelectorAll('input')].some(field => field.value.includes(title)));
      if (!found) throw new Error(\`no row for \${title}\`);
      return found;
    };

    // Every view of the definition is here, grouped as the sidebar groups
    // them; a system view ships with the definition, so it cannot be
    // deleted. The dialog fades in, so the rows are awaited rather than read
    // at once.
    await waitFor(() => expect(row('待出库订单')).toBeDefined());
    // The definition's analysis view sits in the same list as its record
    // views (D20): one data workbench draws both kinds, so the manager
    // orders both.
    await expect(row('仓库金额分布')).toBeDefined();
    await expect(within(row('全部订单')).queryByRole('button', {
      name: zhCN['label.manage.delete']
    })).toBeNull();

    // A column of icons looks like a column, so the same action has to sit at
    // the same x on every row. The cluster used to be sized to its contents
    // and pushed right, and rows do not all carry the same actions: the
    // system row's icons landed under the other rows' last ones, so each of
    // them sat exactly where a different action sits above it. It is
    // left-aligned in a slot as wide as the fullest row now — and the missing
    // actions are still missing rather than drawn greyed out, which is why
    // this is worth measuring at all.
    const drift = new Map<string, number[]>();
    for (const managed of document.querySelectorAll<HTMLElement>('[data-slot="view-manager-row"]')) for (const button of managed.querySelectorAll<HTMLElement>('[data-slot="view-manager-actions"] button')) {
      const name = button.getAttribute('aria-label') ?? '';
      drift.set(name, [...(drift.get(name) ?? []), Math.round(button.getBoundingClientRect().x)]);
    }
    // Shared actions only: one row's own button has nothing to line up with.
    const shared = [...drift].filter(([, xs]) => xs.length > 1);
    await expect(shared.filter(([, xs]) => new Set(xs).size > 1)).toEqual([]);
    // And the check is not vacuous: the rows really do differ in what they
    // carry, which is the only reason any of them could drift.
    await expect(shared.map(([name]) => name).includes(zhCN['label.manage.set-default'])).toBe(true);
    await expect(drift.get(zhCN['label.manage.delete'])!.length).toBeLessThan(drift.get(zhCN['label.manage.set-default'])!.length);

    // The handle is the other end of the row and lines up the same way: it
    // leads every row, because a list that is dragged says so before it is
    // read. It is a list-wide permission, so either every row has one or
    // none does.
    // The handle's name is the catalogue's, so the selector is built from
    // the part of it that comes before the title.
    const DRAG = zhCN['label.manage.drag'].split('{title}')[0];
    const handles = [...document.querySelectorAll<HTMLElement>(\`[data-slot="view-manager-row"] button[aria-label^="\${DRAG}"]\`)];
    // Four rows: the three record views and the analysis view beside them.
    await expect(handles).toHaveLength(4);
    await expect(new Set(handles.map(grip => Math.round(grip.getBoundingClientRect().x))).size).toBe(1);

    // Renaming happens in the row, and the list follows it.
    await userEvent.click(within(row('我盯的大额单')).getByRole('button', {
      name: zhCN['label.manage.rename']
    }));
    // The field is named after the view it renames, which is what tells one
    // row's field from the next one's.
    const title = within(row('我盯的大额单')).getByLabelText(say('label.manage.rename-of', {
      title: '我盯的大额单'
    }));
    await userEvent.clear(title);
    await userEvent.type(title, '大额单');
    await userEvent.click(within(row('大额单')).getByRole('button', {
      name: zhCN['label.manage.rename-confirm']
    }));
    await waitFor(() => expect(row('大额单').textContent).toContain('大额单'));

    // Which view opens first is the list's to choose, and it is marked where
    // it is set.
    await userEvent.click(within(row('待出库订单')).getByRole('button', {
      name: zhCN['label.manage.set-default']
    }));
    // The star is the mark, pressed in — no 「默认」 badge beside it.
    await waitFor(() => expect(within(row('待出库订单')).getByRole('button', {
      name: zhCN['label.manage.unset-default']
    })).toHaveAttribute('aria-pressed', 'true'));
    // Said on the star itself and not only in the badge beside the title:
    // the attribute was there and nothing was drawn from it, so pressing the
    // button changed nothing the button itself showed.
    await expect(within(row('待出库订单')).getByRole('button', {
      name: zhCN['label.manage.unset-default']
    }).querySelector('svg')!.classList).toContain('fill-current');
    await expect(within(row('全部订单')).getByRole('button', {
      name: zhCN['label.manage.set-default']
    }).querySelector('svg')!.classList).not.toContain('fill-current');

    // And the order is the user's: a row is carried by its handle rather
    // than clicked up one step at a time. This is the half jsdom cannot
    // run — \`@dnd-kit/dom\` picks its drop target by measuring boxes, and
    // every box there is 0×0 at the origin (see pointerDrag.ts).
    // The shared analysis view is in the same sortable list as the shared
    // record views (D20): one workbench, one order.
    const SHARED = ['全部订单', '待出库订单', '仓库金额分布'];
    const listed = (audience: string) => [...document.querySelectorAll<HTMLElement>(\`[data-slot="view-manager-group"][data-audience="\${audience}"] [data-slot="view-manager-row"]\`)].map(managed => managed.textContent ?? '');
    const order = () => listed('shared').map(text => SHARED.find(title => text.includes(title)) ?? text);
    const before = order();
    await expect(before).toHaveLength(3);
    await dragHandleOnto(within(row(before[1])).getByRole('button', {
      name: say('label.manage.drag', {
        title: before[1]
      })
    }), row(before[0]));

    // The shared group reordered, and the personal one did not: the two
    // audiences are two sortable lists, so nothing can be carried across the
    // line between them.
    await waitFor(() => expect(order()).toEqual([before[1], before[0], before[2]]));
    await expect(listed('personal')).toHaveLength(1);
    await expect(listed('personal')[0]).toContain('大额单');

    // Deleting asks first, and says what it costs — then the scene backs out
    // of it, because nothing here is meant to be written.
    await userEvent.click(within(row('大额单')).getByRole('button', {
      name: zhCN['label.manage.delete']
    }));
    const confirm = (await within(document.body).findByText(zhCN['label.delete.consequence'])).closest('[role="alertdialog"]') as HTMLElement;
    await userEvent.click(within(confirm).getByRole('button', {
      name: zhCN['label.delete.keep']
    }));
    await waitFor(() => expect(within(document.body).queryByText(zhCN['label.delete.consequence'])).toBeNull());
  }
}`,...Z.parameters?.docs?.source},description:{story:`Renaming, deleting, reordering and the default view: all of it about the
list rather than about the view on screen, so all of it in one dialog
behind the sidebar's gear.`,...Z.parameters?.docs?.description}}},Ct.parameters={...Ct.parameters,docs:{...Ct.parameters?.docs,source:{originalSource:`{
  ...DisplayEmptyResult,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    // The saved view asks exactly what it was saved to ask, so what it says
    // is that the view is empty right now — not that "the conditions" match
    // nothing, as though they were something to take away.
    await expect(await canvas.findByText(zhCN['label.record.empty-view'])).toBeVisible();

    // One way out, and not the one that would turn 「待出库订单」 into every
    // order under its name: its condition is what it is, so the way on is to
    // ask something else, and the button opens the conditions.
    const edit = canvas.getByRole('button', {
      name: zhCN['label.record.empty-edit']
    });
    await expect(canvas.queryByRole('button', {
      name: zhCN['label.record.empty-clear']
    })).toBeNull();
    await userEvent.click(edit);
    await expect(await canvas.findByRole('button', {
      name: /^应用/
    })).toBeVisible();

    // Nothing was taken away: the band still names the view's condition.
    const applied = canvasElement.querySelector<HTMLElement>('[data-slot="applied-bar"]')!;
    await expect(applied.querySelectorAll('[data-slot="badge"]')).toHaveLength(1);
  }
}`,...Ct.parameters?.docs?.source}}},wt.parameters={...wt.parameters,docs:{...wt.parameters?.docs,source:{originalSource:`{
  ...DisplayWithData,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    // The table's footer is on screen before the switch, so the cards are
    // measured against something that was there.
    await expect(canvasElement.querySelector('[data-slot="record-summaries"]')).not.toBeNull();
    await userEvent.click(canvas.getByRole('button', {
      name: zhCN['label.layout.cards']
    }));
    await waitFor(() => expect(canvasElement.querySelector('[data-slot="record-cards"]')).not.toBeNull());
    const summaries = canvasElement.querySelector<HTMLElement>('[data-slot="record-summaries"][data-layout="card"]');
    await expect(summaries).not.toBeNull();
    // One page, so one line: 「全部」 alone, as under the table (D26 Q40).
    await expect(summaries).toHaveTextContent(zhCN['label.summary.scope.total']);
    await expect(summaries).not.toHaveTextContent(zhCN['label.summary.scope.page']);

    // The same button, now about cards.
    const arrange = canvasElement.querySelector<HTMLElement>('[data-control="columns"]')!;
    await expect(arrange).toHaveAccessibleName(zhCN['label.toolbar.card']);
    await userEvent.click(arrange);
    const dialog = await within(document.body).findByRole('dialog', {
      name: zhCN['label.card.title']
    });
    const before = canvasElement.querySelectorAll('[data-slot="card-field"][data-field="createdAt"]').length;
    await expect(before).toBe(0);
    // Awaited: the popover fades in, and its rows are not in the tree
    // until it has.
    await userEvent.click(await within(dialog).findByRole('checkbox', {
      name: '创建时间'
    }));
    // Every card grows the field, at the end of its body.
    await waitFor(() => expect(canvasElement.querySelectorAll('[data-slot="card-field"][data-field="createdAt"]').length).toBeGreaterThan(0));
    const cards = canvasElement.querySelectorAll('[data-slot="record-cards"] > *');
    await expect(canvasElement.querySelectorAll('[data-slot="card-field"][data-field="createdAt"]')).toHaveLength(cards.length);
    // Two in a row, said as pressed, and the grid follows.
    await userEvent.click(await within(dialog).findByRole('button', {
      name: formatMessage(zhCN, 'label.card.per-row-option', {
        count: 2
      })
    }));
    await waitFor(() => expect(canvasElement.querySelector('[data-slot="record-cards"]')).toHaveClass('sm:grid-cols-2'));
  }
}`,...wt.parameters?.docs?.source},description:{story:`The card layout answers with the table's own furniture (D18 V/VI): the
summaries stay under the cards, and the arrangement button — the same
place in the toolbar — opens the card settings instead of the column
settings. Ticking a body field draws it on every card at once.`,...wt.parameters?.docs?.description}}},Tt.parameters={...Tt.parameters,docs:{...Tt.parameters?.docs,source:{originalSource:`{
  ...DisplayLoading,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    await expect(table.querySelectorAll('[data-slot=skeleton]').length).toBeGreaterThan(0);

    // And nothing around the skeleton that counts rows there are not any of
    // yet. The columns come from a result there has not been one of, so the
    // header used to be the dead table's — one cell with a tab-reachable
    // "Select all rows" in it — and the bar below it said "0 on this page"
    // beside a live Next page.
    await expect(table.querySelector('thead')).toBeNull();
    await expect(canvas.queryByRole('checkbox', {
      name: zhCN['label.record.select-all']
    })).toBeNull();
    await expect(canvasElement.querySelector('[data-slot=record-pagination]')).toBeNull();
    await waitFor(() => expect(readColumn(table, '订单号')).toEqual(PENDING_BY_AMOUNT), {
      timeout: 5_000
    });
  }
}`,...Tt.parameters?.docs?.source}}},Et.parameters={...Et.parameters,docs:{...Et.parameters?.docs,source:{originalSource:`{
  ...DisplayQueryFailed,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    // One line, saying the failure itself rather than that there was one.
    const alert = await canvas.findByRole('alert');
    await expect(alert).toHaveTextContent('仓储服务暂时不可用');
    await expect(within(alert).getByRole('button', {
      name: zhCN['label.query.retry']
    })).toBeVisible();
    // Only the data is gone: the view stays open under its conditions.
    await expect(canvas.getByRole('button', {
      name: /^待出库订单/
    })).toHaveAttribute('aria-current', 'true');

    // And nothing in the bar above offers to take rows away that are not
    // there (U4, user 2026-09-22): the frame stands because the strip is
    // what it holds, but Export over no result opened a window onto an
    // empty file. A control that cannot apply is absent, not disabled
    // (P-17) — the two that say how the result is drawn stay, because the
    // view is still a view and still worth setting up.
    await expect(canvas.queryByRole('button', {
      name: zhCN['label.export.title']
    })).toBeNull();
    await expect(canvas.getByRole('button', {
      name: zhCN['label.toolbar.columns']
    })).toBeVisible();

    // And it is as wide as the room it was given, margins deducted. The
    // registry's \`Alert\` is \`w-full\` — a length, 100% of the containing
    // block with nothing taken off for the \`m-3\` the frame gives this strip
    // — so its right edge used to run out under the border (F-14). Only a
    // real browser lays this out.
    const frame = canvasElement.querySelector<HTMLElement>('[data-slot="result-block"]')!;
    await expect(alert.getBoundingClientRect().right).toBeLessThanOrEqual(frame.getBoundingClientRect().right);
  }
}`,...Et.parameters?.docs?.source}}},Dt.parameters={...Dt.parameters,docs:{...Dt.parameters?.docs,source:{originalSource:`{
  ...DisplayTotalCoversThisPageOnly,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    await waitFor(() => expect(readColumn(table, '订单号')).toEqual(PENDING_BY_AMOUNT));

    // One row, naming the scope it really answers for and carrying it in the
    // attribute a host can style on: the row that would have covered every
    // matching record has no number, and none is invented for it.
    const footer = table.querySelector<HTMLElement>('tfoot')!;
    await expect(scopeLabels(table)).toEqual([zhCN['label.summary.scope.page']]);
    await expect([...footer.querySelectorAll('tr')].map(row => row.dataset.scope)).toEqual(['page']);
    // The rows on screen add up to exactly what that row shows.
    await expect(amountOf(readPage(table, '金额'))).toBe(6470);

    // And one line above the result says why it is only a page total. It is
    // a warning, not an alert: nothing was blocked.
    // Addressed as the strip rather than as "the status on the page": the
    // result block carries a live region of its own, which is one too.
    const strip = await waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>('[data-slot="status-strip"]');
      if (!found) throw new Error('no status strip');
      return found;
    });
    await expect(strip).toHaveAttribute('role', 'status');
    await expect(strip).toHaveTextContent(zhCN['runtime.summary.page-only']);
  }
}`,...Dt.parameters?.docs?.source},description:{story:`The summary row outliving its own query, and saying so.

The number stays — a page total is worth having — but it stops calling
itself a total, and the strip above says which query failed. What this
guards against is the silent version: 1280 + 2450 of four rows wearing the
word "Total" while the conditions match forty thousand.`,...Dt.parameters?.docs?.description}}},Ot.parameters={...Ot.parameters,docs:{...Ot.parameters?.docs,source:{originalSource:`{
  ...DisplayNeedsFixing,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    const alert = await canvas.findByRole('alert');
    // One finding is the line itself (F-14): said outright, with no heading
    // over it and no fold under it. "这个视图需要修复才能运行 · 还有 1 项"
    // was a heading with one thing beneath it, and the one thing it hid was
    // the only sentence that said what to fix.
    await expect(alert).toHaveTextContent('removedColumn');
    await expect(alert).not.toHaveTextContent(zhCN['label.view.needs-fixing']);
    await expect(within(alert).queryByRole('button', {
      name: /1/
    })).toBeNull();

    // A config the definition refuses is never run, so there is no result —
    // and with no result there is no result block either. What used to be
    // drawn was a frame around a toolbar with a pressable Export in it, over
    // nothing at all.
    await expect(canvas.queryByRole('table')).toBeNull();
    await expect(canvasElement.querySelector('[data-slot="result-block"]')).toBeNull();
    await expect(canvas.queryByRole('button', {
      name: zhCN['label.export.title']
    })).toBeNull();

    // So the way to fix it is on this line: the same panel the toolbar's
    // button opens, opened from the only thing on screen that says why
    // there is no toolbar.
    await userEvent.click(within(alert).getByRole('button', {
      name: zhCN['label.status.open-columns']
    }));
    await within(document.body).findByText(zhCN['label.columns.title']);
    await waitFor(() => expect(document.body.querySelectorAll('[data-slot="column-setting"]').length).toBeGreaterThan(0));

    // Left closed behind it: the panel is a layer over the page, and a
    // story that walks off leaving one open hands the next one a popup.
    await userEvent.keyboard('{Escape}');
    await waitFor(() => expect(within(document.body).queryByText(zhCN['label.columns.title'])).toBeNull());
  }
}`,...Ot.parameters?.docs?.source}}},kt.parameters={...kt.parameters,docs:{...kt.parameters?.docs,source:{originalSource:`{
  ...DisplayOpening,
  play: async ({
    canvasElement
  }) => {
    const shape = await waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>('[data-slot="opening-skeleton"]');
      if (!found) throw new Error('no opening skeleton');
      return found;
    });
    await expect(shape).toHaveAttribute('aria-busy', 'true');
    // Said once, by a live region of its own: \`aria-busy\` on a live region
    // holds its announcements back, and this one never turns false.
    await expect(within(shape).getByRole('status')).toHaveTextContent(zhCN['label.workbench.opening']);
    await expect(shape.querySelector('[data-slot="view-header-skeleton"]')).not.toBeNull();
    const result = shape.querySelector<HTMLElement>('[data-slot="result-block"]');
    await expect(result).toHaveAttribute('data-framed', 'true');
    await expect(result!.firstElementChild).toHaveAttribute('data-slot', 'result-toolbar');
    await expect(result!.querySelectorAll('[data-slot="result-rows-skeleton"] [data-slot="skeleton"]').length).toBe(3);

    // The shape of an answer is not an answer: nothing in it is read out,
    // and nothing in it can be pressed.
    await expect(canvasElement.querySelector('table')).toBeNull();
    await expect(shape.querySelector('button')).toBeNull();
  }
}`,...kt.parameters?.docs?.source},description:{story:`打开视图时屏幕上的那一块（P-13）：形状与打开后一致——标题栏、带边的结果块、
工具栏那一行、底下几行行——而不是一条说"有东西在加载"的灰条。`,...kt.parameters?.docs?.description}}},At.parameters={...At.parameters,docs:{...At.parameters?.docs,source:{originalSource:`{
  ...DisplayNoViews,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    // The sentence names both rooms — the sidebar's line and the work
    // area's title — so the work area is found by its slot, not its words.
    const workArea = await waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>('[data-slot="view-none"]');
      if (!found) throw new Error('no empty work area');
      return found;
    });
    // Three ways in: the work area's button, the sidebar's \`+\`. The switcher
    // item is the third, behind a fold this story keeps open.
    // The view list, not the host application's navigation beside it: both
    // are navigation landmarks, and the list is the one named after the
    // definition it lists.
    const sidebar = canvas.getByRole('navigation', {
      name: ordersDefinition.title
    });
    await expect(within(sidebar).getByRole('button', {
      name: zhCN['label.view.new']
    })).toBeVisible();
    // And the sidebar draws that one control and no more: where the views
    // would be there is a line and nothing to press (user, 2026-09-22) —
    // the state is stated in full once, in the room the view will fill.
    const body = canvasElement.querySelector<HTMLElement>('[data-slot="view-list-body"]')!;
    await expect(body.querySelector('[data-slot="view-list-empty"]')).toHaveTextContent(zhCN['label.view.none']);
    await expect(within(body).queryByRole('button')).toBeNull();
    await expect(within(body).queryByText(zhCN['label.view.none-hint'])).toBeNull();
    await userEvent.click(within(workArea).getByRole('button', {
      name: zhCN['label.view.new']
    }));
    // Both kinds may be made here, so the button is a menu of the two (D20
    // Ⅱ): the kind decides the kernel, and is asked before the view opens.
    await userEvent.click(await within(document.body).findByRole('menuitem', {
      name: zhCN['label.kind.record']
    }));
    await expect(await canvas.findByRole('heading', {
      level: 2,
      name: zhCN['label.view.new-title']
    })).toBeVisible();
    await expect(canvas.getByText(zhCN['label.header.new-view'])).toBeVisible();
    // The conditions are out: a view with nothing in it is about to be shaped.
    await expect(await canvas.findByRole('button', {
      name: zhCN['label.filter.apply']
    })).toBeVisible();
    await canvas.findByRole('table');
    await userEvent.click(canvas.getByRole('button', {
      name: zhCN['label.save.save']
    }));
    const dialog = await within(document.body).findByRole('dialog', {
      name: zhCN['label.save.first-heading']
    });
    const title = within(dialog).getByRole('textbox', {
      name: zhCN['label.save.title']
    });
    await expect(title).toHaveValue(zhCN['label.view.new-title']);
    await userEvent.clear(title);
    await userEvent.type(title, '大额单');
    await userEvent.click(within(dialog).getByRole('button', {
      name: zhCN['label.save.save']
    }));
    await expect(await canvas.findByRole('heading', {
      level: 2,
      name: '大额单'
    })).toBeVisible();
    await expect(await within(sidebar).findByRole('button', {
      name: /大额单/
    })).toBeVisible();
    await expect(canvas.queryByText(zhCN['label.header.new-view'])).not.toBeInTheDocument();
  }
}`,...At.parameters?.docs?.source},description:{story:`From nothing to a listed view: the work area's button opens a view that
is on screen at once, unsaved and with its editor out; the first save
asks the copy's two questions under a "save" heading; what the store took
is then listed in the sidebar and open. Nothing was measured that jsdom
could not measure — this pins that the three entries and the dialog exist
with the real popups and the real catalogue in front of them.`,...At.parameters?.docs?.description}}},jt.parameters={...jt.parameters,docs:{...jt.parameters?.docs,source:{originalSource:`{
  ...DisplayCannotOpen,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    const alert = await canvas.findByRole('alert');
    await expect(alert).toHaveTextContent(zhCN['label.view.unopenable']);

    // The empty state's form rather than a block of red: an icon, what
    // happened, and one way off the screen.
    await expect(alert).toHaveAttribute('data-slot', 'view-unopenable');
    await expect(alert.querySelector('svg')).not.toBeNull();
    const back = within(alert).getByRole('button', {
      name: zhCN['label.view.open-default']
    });
    await userEvent.click(back);
    await canvas.findByRole('table');
    await expect(canvas.queryByRole('alert')).toBeNull();
  }
}`,...jt.parameters?.docs?.source}}},Mt.parameters={...Mt.parameters,docs:{...Mt.parameters?.docs,source:{originalSource:`{
  ...DisplayEnglish,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');

    // The audience tag and the fold above the rows, both from the catalogue.
    await expect(canvasElement.querySelector<HTMLElement>('[data-slot="view-header"]')).toHaveTextContent(defaultMessages['label.scope.tag.shared']);
    await expect(canvas.getByRole('button', {
      name: new RegExp(\`^\${defaultMessages['label.filter.panel']}\`)
    })).toBeVisible();
    await expect(canvas.getByRole('button', {
      name: defaultMessages['label.toolbar.refresh']
    })).toBeVisible();

    // The bar under the rows, where the count and the page size are sentences
    // with numbers in them rather than numbers with words beside them.
    const bar = paginationBar(canvasElement);
    await expect(bar).toHaveTextContent(formatMessage(defaultMessages, 'label.pagination.total', {
      total: 4
    }));
    await expect(within(bar).getByRole('combobox', {
      name: defaultMessages['label.pagination.page-size']
    })).toHaveTextContent(formatMessage(defaultMessages, 'label.pagination.page-size-option', {
      size: 20
    }));

    // And nothing is left in Chinese behind it.
    await expect(canvas.queryByRole('button', {
      name: zhCN['label.toolbar.refresh']
    })).toBeNull();

    // The most visible line of the result area, which each kind used to hand
    // over as a finished English sentence: field label from the definition,
    // operator from the catalogue, option label from the definition again.
    const applied = canvas.getByRole('region', {
      name: defaultMessages['label.applied.title']
    });
    // One value reads 'is' however it was stored (\`label.relation.is\`).
    const badge = \`状态 \${defaultMessages['label.relation.is']} 待出库\`;
    await expect(applied).toHaveTextContent(badge);
    await expect(applied).not.toHaveTextContent(zhCN['label.relation.is']);

    // And it is operable: the ✕ takes the condition out of force and the
    // query runs again, which is what leaves every order on screen.
    await userEvent.click(within(applied).getByRole('button', {
      name: defaultMessages['label.filter.unset-of'].replace('{condition}', badge)
    }));
    await waitFor(() => expect(canvas.getByRole('region', {
      name: defaultMessages['label.applied.title']
    })).toHaveTextContent(defaultMessages['label.applied.all']));
    await waitFor(() => expect(readColumn(canvas.getByRole('table'), '订单号')).toHaveLength(6));
  }
}`,...Mt.parameters?.docs?.source},description:{story:`The English catalogue — what the package ships, and what every story here
now opts out of.

The fixtures are Chinese, so the rest of this file asserts the Chinese
wording; this one screen is what keeps the shipped default covered. The
field labels and the option labels stay Chinese either way: they come from
the definition, which is the application's data rather than the package's
wording.`,...Mt.parameters?.docs?.description}}},Nt.parameters={...Nt.parameters,docs:{...Nt.parameters?.docs,source:{originalSource:`{
  ...DisplayTableSettings,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    await waitFor(() => expect(readColumn(table, '订单号')).toEqual(PENDING_BY_AMOUNT));
    await userEvent.click(canvasElement.querySelector<HTMLElement>('[data-control="columns"]')!);
    const popover = within(document.body);

    // Two region headings and no row for the host's actions: a column is
    // pinned to the left or not at all (D19), and the right edge is the
    // engine's frame rather than anything a reader sets here.
    await expect(popover.getAllByRole('heading', {
      level: 3
    }).map(heading => heading.textContent)).toEqual([zhCN['label.columns.pin.left'], zhCN['label.columns.pin.none']]);
    await expect(popover.queryByRole('checkbox', {
      name: say('label.columns.show', {
        field: zhCN['label.toolbar.actions']
      })
    })).toBeNull();

    // The key column is held and says so — \`aria-pressed\`, on a toggle that
    // is refused because the projection decides this one. The rest of the
    // list is the order the table is in.
    const key = popover.getByRole('button', {
      name: say('label.columns.pin', {
        field: '订单号'
      })
    });
    await expect(key).toBeDisabled();
    await expect(key).toHaveAttribute('aria-pressed', 'true');

    // Reorder by keyboard: 状态 up one place, past 仓库.
    const handle = popover.getByRole('button', {
      name: say('label.columns.drag', {
        field: '状态'
      })
    });
    handle.focus();
    await userEvent.keyboard('{ArrowUp}');
    await expect(document.querySelector('[data-slot="column-announcement"]')).toHaveTextContent(say('label.columns.moved', {
      field: '状态',
      index: 2,
      total: 4
    }));

    // 金额 is the column the table draws last, so the table holds it
    // against the right edge (D13) — and that is the frame rather than a
    // setting (D19), so its row wears the same live toggle as any other.
    const last = popover.getByRole('button', {
      name: say('label.columns.pin', {
        field: '金额'
      })
    });
    await expect(last).toBeEnabled();
    await expect(last).toHaveAttribute('aria-pressed', 'false');

    // Pin 仓库, which is one of the three that scroll, then summarise 金额
    // as an average rather than a sum.
    const pinName = say('label.columns.pin', {
      field: '仓库'
    });
    await userEvent.click(popover.getByRole('button', {
      name: pinName
    }));
    // The press is reported as the toggle's own state, and said in the
    // panel's live region — which is where the column and the edge are
    // named, because "pressed" names neither. Read back off the panel
    // rather than off the node that was pressed: the row moves to the
    // other area's list, so React mounts it again there.
    await waitFor(() => expect(popover.getByRole('button', {
      name: pinName
    })).toHaveAttribute('aria-pressed', 'true'));
    await expect(document.querySelector('[data-slot="column-announcement"]')).toHaveTextContent(say('label.columns.pinned.left', {
      field: '仓库'
    }));
    await userEvent.click(popover.getByRole('combobox', {
      name: say('label.columns.summary', {
        field: '金额'
      })
    }));
    await userEvent.click(await popover.findByRole('option', {
      name: zhCN['label.summary.fn.AVG']
    }));
    await userEvent.keyboard('{Escape}');

    // The sort button reads the sort back; turning 金额 around turns the
    // rows around, because sorting applies at once.
    await userEvent.click(canvasElement.querySelector<HTMLElement>('[data-control="sort"]')!);
    await userEvent.click(await within(document.body).findByRole('button', {
      name: say('label.sort.direction', {
        field: '金额'
      })
    }));
    await userEvent.keyboard('{Escape}');

    // What is on screen: the areas a table draws in. \`订单号\` and the newly
    // pinned \`仓库\` are held on the left — pinning is what moves a column
    // between the areas, since \`sticky\` only fixes an element where it
    // already is — \`状态\` scrolls, and \`金额\` is held at the right end for
    // being drawn last.
    await waitFor(() => expect(readHeaders(canvas.getByRole('table'))).toEqual(['订单号', '仓库', '状态', '金额']));
    await waitFor(() => expect(readColumn(canvas.getByRole('table'), '订单号')).toEqual([...PENDING_BY_AMOUNT].reverse()));

    // And what was written: all four changes, in the saved config.
    await userEvent.click(canvas.getByRole('button', {
      name: zhCN['label.save.save']
    }));
    await confirmSharedSave();
    await waitFor(async () => {
      const saved = await tableSettingsStore.current!.get('orders-pending');
      // The config keeps the order the reorder committed; where a pinned
      // column is *drawn* is the projection's answer, not something a saved
      // view has an opinion about — the same split as the row key's pin.
      expect(saved.config as RecordViewConfig).toMatchObject({
        table: {
          columns: [{
            field: 'id'
          }, {
            field: 'status'
          }, {
            field: 'warehouse',
            pinned: true
          }, {
            field: 'amount'
          }]
        },
        summaries: [{
          field: 'amount',
          fn: 'AVG'
        }],
        sort: [{
          field: 'amount',
          direction: 'ASC'
        }]
      });
    });
  }
}`,...Nt.parameters?.docs?.source},description:{story:`The column settings and the sort control, driven the way a keyboard user
drives them, and then saved.

Four changes in one pass — order, pinning, a summary and the sort — because
they are one question ("what does a row look like") and because each of
them has to survive the others: the pin is written onto the column the
reorder moved, and the summary onto a column that is now somewhere else.
The table is asserted for what is on screen, and the store for what was
actually written; the draft would satisfy the first on its own.`,...Nt.parameters?.docs?.description}}},Pt.parameters={...Pt.parameters,docs:{...Pt.parameters?.docs,source:{originalSource:`{
  ...DisplayTableSettings,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    await waitFor(() => expect(readHeaders(table)).toEqual(['订单号', '仓库', '状态', '金额']));
    await userEvent.click(canvasElement.querySelector<HTMLElement>('[data-control="columns"]')!);
    const popover = within(document.body);
    const handle = await popover.findByRole('button', {
      name: say('label.columns.drag', {
        field: '仓库'
      })
    });

    // The rows a drag moves within: the ones that scroll *and* are shown.
    // A hidden field has no place in \`table.columns\` and so no order to
    // drag — its handle is refused — and the row key is held in the other
    // area, which is a sortable list of its own. Read by what the panel
    // offers rather than by a list of fields, so a definition that grows
    // another field does not turn this into a test about the fixture.
    const draggable = [...document.querySelectorAll<HTMLElement>('[data-slot="column-region"][data-region="scrolling"] [data-slot="column-setting"]')].filter(row => row.querySelector('button')?.hasAttribute('disabled') === false);
    await expect(draggable.map(row => row.dataset.field)).toEqual(['warehouse', 'status', 'amount']);
    await dragHandleOnto(handle, draggable[1]);

    // Dropped on the row below it, 仓库 takes its place and 状态 closes up
    // behind it. The table is the witness: the panel's own list would show
    // the same thing whether or not the controller heard it.
    await waitFor(() => expect(readHeaders(canvas.getByRole('table'))).toEqual(['订单号', '状态', '仓库', '金额']));
    await userEvent.keyboard('{Escape}');
    await userEvent.click(canvas.getByRole('button', {
      name: zhCN['label.save.save']
    }));
    await confirmSharedSave();
    await waitFor(async () => {
      const saved = await tableSettingsStore.current!.get('orders-pending');
      expect((saved.config as RecordViewConfig).table.columns.map(column => column.field)).toEqual(['id', 'status', 'warehouse', 'amount']);
    });
  }
}`,...Pt.parameters?.docs?.source},description:{story:`The same panel, reordered the way a mouse reorders it.

The keyboard path above commits through \`onMove\`, and the drop calculation
has unit tests of its own — but "press the handle, move onto the third row,
let go" only ever ran inside the library's own suite. It cannot run in
jsdom: \`@dnd-kit/dom\` picks the drop target by measuring boxes against each
other, and every box there is 0×0 at the origin. So this one lives in the
browser project, shares the table-settings fixture with the play above, and
asserts both ends of the chain — the order the table draws, and the order
the save wrote.`,...Pt.parameters?.docs?.description}}},Ft.parameters={...Ft.parameters,docs:{...Ft.parameters?.docs,source:{originalSource:`{
  ...DisplayTableSettings,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    await waitFor(() => expect(readHeaders(table)).toEqual(['订单号', '仓库', '状态', '金额']));

    // The view's own columns, in the order the panel lists them. The
    // definition offers more fields than this view shows, and those are
    // listed after them — the place being checked here is the place among
    // the columns the table has.
    const own = ['id', 'warehouse', 'status', 'amount'];
    const columnRows = () => [...document.querySelectorAll('[data-slot="column-setting"]')].map(row => row.getAttribute('data-field')).filter(field => field !== null && own.includes(field));
    const open = async () => userEvent.click(canvasElement.querySelector<HTMLElement>('[data-control="columns"]')!);
    const checkbox = () => within(document.body).getByRole('checkbox', {
      name: say('label.columns.show', {
        field: '仓库'
      })
    });
    await open();
    await userEvent.click(checkbox());

    // Off in the table, still second in the panel — with a handle that
    // works, because a place in the order is exactly what it kept.
    await waitFor(() => expect(readHeaders(canvas.getByRole('table'))).toEqual(['订单号', '状态', '金额']));
    expect(columnRows()).toEqual(own);
    await expect(within(document.body).getByRole('button', {
      name: say('label.columns.drag', {
        field: '仓库'
      })
    })).toBeEnabled();
    await userEvent.keyboard('{Escape}');

    // And what a save writes is the switch, in place — not a list with one
    // column missing, which is what made the place impossible to keep.
    await userEvent.click(canvas.getByRole('button', {
      name: zhCN['label.save.save']
    }));
    await confirmSharedSave();
    await waitFor(async () => {
      const saved = await tableSettingsStore.current!.get('orders-pending');
      expect((saved.config as RecordViewConfig).table.columns).toEqual([{
        field: 'id',
        pinned: true
      }, {
        field: 'warehouse',
        hidden: true
      }, {
        field: 'status'
      }, {
        field: 'amount'
      }]);
    });

    // Back on, and back in its own slot: second, between the key column and
    // 状态, rather than at the end of the table.
    await open();
    await userEvent.click(checkbox());
    await userEvent.keyboard('{Escape}');
    await waitFor(() => expect(readHeaders(canvas.getByRole('table'))).toEqual(['订单号', '仓库', '状态', '金额']));
  }
}`,...Ft.parameters?.docs?.source},description:{story:`A column switched off keeps its place, and comes back to it (D17-8).

The whole of the member is that one round trip: untick a column in the
middle of the table, tick it again, and it is the same table. Before it,
the entry was deleted from \`table.columns\`, so the column came back at
the far end and had to be dragged home — and the config that was saved in
between had simply lost it.

It walks all three places the answer has to be the same in: the panel
(the row stays in its slot, unticked), the table (the column goes and
comes back where it was) and the store (the saved config carries the
switch rather than a shorter list).`,...Ft.parameters?.docs?.description}}},It.parameters={...It.parameters,docs:{...It.parameters?.docs,source:{originalSource:`{
  ...DisplayTableSettings,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    await waitFor(() => expect(readColumn(table, '订单号')).toEqual(PENDING_BY_AMOUNT));
    const button = canvasElement.querySelector<HTMLElement>('[data-control="sort"]')!;
    await userEvent.click(button);
    const popover = within(document.body);

    // A second field to order by, so there is an order to argue about. It
    // joins at the end, ascending, and breaks the ties of the first.
    await userEvent.click(await popover.findByRole('button', {
      name: zhCN['label.sort.add']
    }));
    await userEvent.click(await popover.findByRole('menuitem', {
      name: '订单号'
    }));
    const entries = () => [...document.querySelectorAll<HTMLElement>('[data-slot="sort-entry"]')];
    await waitFor(() => expect(entries().map(entry => entry.dataset.field)).toEqual(['amount', 'id']));

    // Carry 金额 down onto 订单号: what was breaking the ties becomes what
    // the rows are ordered by, and the other one closes up above it.
    const handle = popover.getByRole('button', {
      name: say('label.sort.drag', {
        field: '金额'
      })
    });
    await dragHandleOnto(handle, entries()[1]);
    await waitFor(() => expect(entries().map(entry => entry.dataset.field)).toEqual(['id', 'amount']));

    // The table is the witness: an order that was not applied is an order
    // nobody can see. \`aria-sort\` marks the column the rows are actually in
    // the order of, and there is one of those.
    await waitFor(() => expect(headerOf(canvas.getByRole('table'), '订单号')).toHaveAttribute('aria-sort', 'ascending'));
    const sorted = canvas.getByRole('table');
    await expect(headerOf(sorted, '金额')).not.toHaveAttribute('aria-sort');
    await expect(positionOf(sorted, '订单号')).toBe('1');
    await expect(positionOf(sorted, '金额')).toBe('2');
    await expect(readColumn(sorted, '订单号')).toEqual([...PENDING_BY_AMOUNT].sort());

    // And the button under the popover reads the new first entry back — on
    // screen as the field and the count, and to a reader as the name that
    // also says what kind of control it is.
    await userEvent.keyboard('{Escape}');
    await expect(button).toHaveTextContent(\`订单号\${say('label.sort.more', {
      count: 1
    })}\`);
    await expect(button).toHaveAccessibleName(\`\${say('label.sort.button', {
      field: '订单号',
      direction: zhCN['label.sort.asc']
    })} \${say('label.sort.more', {
      count: 1
    })}\`);
  }
}`,...It.parameters?.docs?.source},description:{story:`The sort popover, put in order the way a mouse puts it in order.

Which field comes first is the whole of what that list says, and until it
could be dragged the only way to change it was to remove an entry and add
it again at the end. The drop calculation has unit tests of its own; what
only a real browser can run is the gesture — \`@dnd-kit/dom\` picks its drop
target by measuring boxes, and in jsdom every box is 0×0 at the origin.

Both ends of the chain are asserted, and neither of them is the popover's
own list: the table's \`aria-sort\` and the rows underneath it, because
sorting applies at once, and the toolbar button, whose summary follows
whatever is now first.`,...It.parameters?.docs?.description}}},Lt.parameters={...Lt.parameters,docs:{...Lt.parameters?.docs,source:{originalSource:`{
  ...DisplayTableSettings,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    await waitFor(() => expect(readHeaders(table)).toEqual(['订单号', '仓库', '状态', '金额']));
    const edge = canvas.getByRole('separator', {
      name: say('label.columns.resize', {
        field: '仓库'
      })
    });
    const head = edge.closest('th')!;
    const before = head.getBoundingClientRect().width;
    await dragEdgeBy(edge, 80);

    // The column, not only the cell the handle is in: the header and the
    // rows under it have to come out the same width, or the "width" is a
    // header that has parted company with its column.
    const wanted = Math.round(before + 80);
    await waitFor(() => {
      const now = canvas.getByRole('separator', {
        name: say('label.columns.resize', {
          field: '仓库'
        })
      }).closest('th')!;
      const body = canvas.getByRole('table') as HTMLTableElement;
      const cell = body.tBodies[0].rows[0].cells[now.cellIndex];
      expect([Math.round(now.getBoundingClientRect().width), Math.round(cell.getBoundingClientRect().width)]).toEqual([wanted, wanted]);
    });

    // And the number the release committed is the number a save writes.
    await userEvent.click(canvas.getByRole('button', {
      name: zhCN['label.save.save']
    }));
    await confirmSharedSave();
    await waitFor(async () => {
      const saved = await tableSettingsStore.current!.get('orders-pending');
      const column = (saved.config as RecordViewConfig).table.columns.find(entry => entry.field === 'warehouse');
      expect(column?.width).toBe(wanted);
    });
  }
}`,...Lt.parameters?.docs?.source},description:{story:`A column's width, dragged onto its header's edge.

It cannot be measured anywhere but here. The gesture reads the header's
box on the way in and writes a width on the way through, and in jsdom
every box is 0×0 — the unit suite drives the same handle with a keyboard
and checks what was written, which is a different question from whether
the column ends up that wide. This one asks the browser: the header is
wider by what the pointer travelled, the rows under it are the same width
as the header (a width on the header alone is a suggestion an auto-laid-out
table sizes straight past), and the number reached the saved config.`,...Lt.parameters?.docs?.description}}},Rt.parameters={...Rt.parameters,docs:{...Rt.parameters?.docs,source:{originalSource:`{
  ...DisplaySaveConflicted,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await dirtyTheDraft(canvasElement);
    await save(canvas);
    const band = await conflictBand(canvasElement);
    // Every way out of a conflict, and only the ways out: taking theirs,
    // keeping a copy, and writing over them.
    await expect([...band.querySelectorAll('button')].map(button => button.textContent?.trim())).toEqual([zhCN['label.conflict.theirs'], zhCN['label.conflict.copy'], zhCN['label.conflict.mine']]);

    // Safest first, and none of the three set apart from the others: the
    // overwrite used to be the solid primary, which made the most dangerous
    // way out the only emphasised thing on the screen (D12 Ⅰ). Which of them
    // costs least depends on what is in each config, and the screen does not
    // decide that for anyone. Painted colours, which only a browser has.
    const painted = [...band.querySelectorAll<HTMLElement>('button')].map(button => {
      const style = getComputedStyle(button);
      return [style.backgroundColor, style.borderTopColor, style.color].join(' | ');
    });
    await expect(new Set(painted), painted.join('; ')).toHaveProperty('size', 1);
    await pressWhenEnabled(within(band).getByRole('button', {
      name: zhCN['label.conflict.mine']
    }));

    // Put once more, because the button that offered it cannot show what it
    // costs: the two configs are summarised beside each other, and here they
    // differ in three of the four things the summary counts.
    const dialog = await within(document.body).findByRole('alertdialog');
    await expect(dialog).toHaveTextContent(zhCN['label.conflict.confirm-mine']);
    await expect(dialog).toHaveTextContent(MINE);
    await expect(dialog).toHaveTextContent(THEIRS);
    await userEvent.click(within(dialog).getByRole('button', {
      name: zhCN['label.conflict.mine']
    }));

    // And the overwrite lands: the stored config is the user's again, at the
    // revision the conflict reported rather than the stale one.
    await waitFor(async () => {
      const saved = await outcomesStore.current!.get('orders-pending');
      expect(saved.config as RecordViewConfig).toMatchObject({
        pageSize: 20,
        table: {
          columns: [{
            field: 'id'
          }, {
            field: 'warehouse'
          }, {
            field: 'status'
          }, {
            field: 'amount'
          }]
        }
      });
      expect((saved.config as RecordViewConfig).sort).toHaveLength(2);
    });
    await waitFor(() => expect(canvas.queryByText(zhCN['label.write.conflict'])).toBeNull());
  }
}`,...Rt.parameters?.docs?.source},description:{story:`Somebody else saved this view first, and the open view says so.

The line under the title bar is \`WriteOutcome\`, and it is the one place
this package puts a decision the user has to make about their own work. So
the regression walks the whole of it: that the three ways out are offered,
that the destructive one is put again with both ways of looking side by
side, and that confirming it really writes.`,...Rt.parameters?.docs?.description}}},zt.parameters={...zt.parameters,docs:{...zt.parameters?.docs,source:{originalSource:`{
  ...DisplaySaveConflicted,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await dirtyTheDraft(canvasElement);
    await save(canvas);
    const band = await conflictBand(canvasElement);
    await pressWhenEnabled(within(band).getByRole('button', {
      name: zhCN['label.conflict.theirs']
    }));
    const dialog = await within(document.body).findByRole('alertdialog');
    await expect(dialog).toHaveTextContent(zhCN['label.conflict.confirm-theirs']);
    await userEvent.click(within(dialog).getByRole('button', {
      name: zhCN['label.conflict.theirs']
    }));

    // The line is settled, and with it the edit that caused it: the draft is
    // theirs now, so there is nothing unsaved left to mark.
    await waitFor(() => expect(canvas.queryByText(zhCN['label.write.conflict'])).toBeNull());
    await expect(canvas.queryByText(zhCN['label.header.unsaved'])).toBeNull();

    // And the draft really is theirs: the column settings read it, and the
    // one column this view never showed is shown in it. The rows on screen
    // are still the ones the last query returned — adopting a config is not
    // running it — which is exactly the split the panel reads across.
    await userEvent.click(canvasElement.querySelector<HTMLElement>('[data-control="columns"]')!);
    await expect(await within(document.body).findByRole('checkbox', {
      name: say('label.columns.show', {
        field: '创建时间'
      })
    })).toBeChecked();

    // The store never heard from this view at all: it still holds theirs.
    const saved = await outcomesStore.current!.get('orders-pending');
    await expect((saved.config as RecordViewConfig).pageSize).toBe(50);
  }
}`,...zt.parameters?.docs?.source},description:{story:`The other answer to the same question: the server's copy is adopted and the
draft goes with it.

Nothing is written — a reload is the one recovery that writes nothing at
all — so what proves it is the draft: the view is holding their config,
and the edit that caused the conflict is gone with it.`,...zt.parameters?.docs?.description}}},Bt.parameters={...Bt.parameters,docs:{...Bt.parameters?.docs,source:{originalSource:`{
  ...DisplaySaveResultUnknown,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await dirtyTheDraft(canvasElement);
    await save(canvas);
    const band = await outcomeBand(canvasElement, zhCN['label.write.unknown']);
    await expect([...band.querySelectorAll('button')].map(button => button.textContent?.trim())).toEqual([zhCN['label.unknown.retry'], zhCN['label.unknown.leave']]);
    await pressWhenEnabled(within(band).getByRole('button', {
      name: zhCN['label.unknown.retry']
    }));

    // The replay lands, so the line comes down and the view is saved — the
    // config the save was carrying, not whatever the draft became since.
    await waitFor(async () => {
      const saved = await outcomesStore.current!.get('orders-pending');
      expect((saved.config as RecordViewConfig).sort).toHaveLength(2);
    });
    await waitFor(() => expect(canvas.queryByText(zhCN['label.write.unknown'])).toBeNull());
  }
}`,...Bt.parameters?.docs?.source},description:{story:`The request left and nothing came back.

It is neither a success nor a failure, so the line offers neither an
apology nor an overwrite — only the same request again under the same
\`requestId\`, for the server to recognise, or a way to stop holding it.`,...Bt.parameters?.docs?.description}}},Vt.parameters={...Vt.parameters,docs:{...Vt.parameters?.docs,source:{originalSource:`{
  ...DisplaySaveRefused,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await dirtyTheDraft(canvasElement);
    await save(canvas);
    const band = await outcomeBand(canvasElement, zhCN['view.write.invalid']);
    // The catalogue's sentence, and the store's own reason after it: an open
    // view has room for both, and the reason is the only part that says
    // *what* was wrong.
    await expect(band).toHaveTextContent('这个视图由运维托管，不接受修改');
    await expect([...band.querySelectorAll('button')].map(button => button.textContent?.trim())).toEqual([zhCN['label.rejected.dismiss']]);
    await pressWhenEnabled(within(band).getByRole('button', {
      name: zhCN['label.rejected.dismiss']
    }));
    await waitFor(() => expect(canvas.queryByText(zhCN['view.write.invalid'], {
      exact: false
    })).toBeNull());

    // A refusal is a definite answer, so the draft is still there and saving
    // again is a new intent rather than a recovery — and this one lands.
    await save(canvas);
    await waitFor(async () => {
      const saved = await outcomesStore.current!.get('orders-pending');
      expect((saved.config as RecordViewConfig).sort).toHaveLength(2);
    });
  }
}`,...Vt.parameters?.docs?.source},description:{story:`The store took a look and refused.

Nothing was written, so there is nothing to retry or overwrite — the line
says why, in the catalogue's sentence and then the store's own words, and
offers only a way to have done with it. Dismissing is what frees the view:
the engine holds the refused write until somebody settles it.`,...Vt.parameters?.docs?.description}}},Ht.parameters={...Ht.parameters,docs:{...Ht.parameters?.docs,source:{originalSource:`{
  ...DisplayRenameConflicted,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await openManager(canvas, '我盯的大额单');
    await userEvent.click(within(managerRow('我盯的大额单')).getByRole('button', {
      name: zhCN['label.manage.rename']
    }));
    const title = within(managerRow('我盯的大额单')).getByLabelText(say('label.manage.rename-of', {
      title: '我盯的大额单'
    }));
    await userEvent.clear(title);
    await userEvent.type(title, '大额单');
    await userEvent.click(within(managerRow('大额单')).getByRole('button', {
      name: zhCN['label.manage.rename-confirm']
    }));
    const line = await conflictLine('大额单');
    await expect([...line.querySelectorAll('button')].map(button => button.textContent?.trim())).toContain(zhCN['label.manage.reload']);
    // The list is what comes back, so the row says that rather than "take
    // theirs"; and a row has nowhere to put a copy, so none is offered.
    await expect(within(line).queryByRole('button', {
      name: zhCN['label.conflict.theirs']
    })).toBeNull();
    await expect(within(line).queryByRole('button', {
      name: zhCN['label.conflict.copy']
    })).toBeNull();
    await pressWhenEnabled(within(line).getByRole('button', {
      name: zhCN['label.conflict.mine']
    }));
    await waitFor(async () => expect((await outcomesStore.current!.get('orders-mine')).title).toBe('大额单'));
  }
}`,...Ht.parameters?.docs?.source},description:{story:`A write no open view owns, reported under the row that started it.

The manager's line is the same component in its smaller clothes, and it
differs in exactly two places: taking the server's copy is about the list
here, so it says so, and there is nowhere to put a copy so no copy is
offered.`,...Ht.parameters?.docs?.description}}},Ut.parameters={...Ut.parameters,docs:{...Ut.parameters?.docs,source:{originalSource:`{
  ...DisplayDeleteConflicted,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await openManager(canvas, '待出库订单');
    await userEvent.click(within(managerRow('待出库订单')).getByRole('button', {
      name: zhCN['label.manage.delete']
    }));
    const first = await deleteDialog();
    // A shared view is somebody else's too, and the first question says so.
    await expect(first).toHaveTextContent(zhCN['label.delete.shared-consequence']);
    await userEvent.click(within(first).getByRole('button', {
      name: zhCN['label.manage.delete']
    }));
    const line = await conflictLine('待出库订单');
    // The first question is off the screen before the second is asked, which
    // is what makes "twice" mean anything.
    await waitFor(() => expect(within(document.body).queryByRole('alertdialog')).toBeNull());
    await pressWhenEnabled(within(line).getByRole('button', {
      name: zhCN['label.conflict.mine']
    }));

    // Asked again — and nothing has been deleted yet: the destructive answer
    // belongs to the dialog, not to the line.
    const second = await deleteDialog();
    await expect((await outcomesStore.current!.list('orders')).map(item => item.id)).toContain('orders-pending');
    await userEvent.click(within(second).getByRole('button', {
      name: zhCN['label.manage.delete']
    }));
    await waitFor(async () => expect((await outcomesStore.current!.list('orders')).map(item => item.id)).not.toContain('orders-pending'));
  }
}`,...Ut.parameters?.docs?.source},description:{story:`A delete that conflicted is confirmed twice.

The first confirmation was about the view as the list had it; what the
conflict reports is a view that has changed since. So "Keep mine" on the
line does not delete — it puts the question again with the server's copy in
hand, and only that second answer writes.`,...Ut.parameters?.docs?.description}}},Jt.parameters={...Jt.parameters,docs:{...Jt.parameters?.docs,source:{originalSource:`{
  ...DisplayWithData,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');

    // The pair that was 0.8px apart, now one rung.
    const item = listItem(canvasElement, '待出库订单');
    const heading = canvasElement.querySelector<HTMLElement>('[data-slot="view-group-heading"]')!;
    await expect(sizeOf(item)).toBe('13px');
    await expect(sizeOf(heading)).toBe('13px');

    // And everything else on that rung: the column headers, the enum
    // badges in the cells, the pagination line.
    const head = table.querySelector<HTMLElement>('thead th')!;
    const badge = canvasElement.querySelector<HTMLElement>('[data-slot="badge"]')!;
    const pagination = canvasElement.querySelector<HTMLElement>('[data-slot="record-pagination"]')!;
    for (const node of [head, badge, pagination]) await expect(sizeOf(node)).toBe('13px');

    // The two rungs above it, so what is asserted is a scale and not one
    // number: the rows are the body size, the definition's name is the h1.
    const cell = table.querySelector<HTMLElement>('tbody td')!;
    const title = canvasElement.querySelector<HTMLElement>('[data-slot="view-list-title"]')!;
    await expect(sizeOf(cell)).toBe('14px');
    await expect(sizeOf(title)).toBe('16px');

    // Nothing anywhere on the screen is still set in the step that went.
    const stray = [...canvasElement.querySelectorAll('*')].filter(node => sizeOf(node) === '12.8px');
    await expect(stray).toHaveLength(0);
  }
}`,...Jt.parameters?.docs?.source},description:{story:`Three rungs of type, not four.

One screen used to carry 12, 12.8, 14 and 16px. The middle pair is the
problem: 0.8px is not a rank, so a sidebar view item (12.8, the registry's
\`sm\` control size) and the group label right above it (12, \`text-xs\`) read
as one size drawn badly rather than as two; and 12.8px lands off the pixel
grid, which is what made 中文 at that size look blurry. Both are now the
one step under the body size — \`--text-ui\`, 13px — so the scale is
13 / 14 / 16 and every gap in it is one the eye can name.

Measured rather than asserted in a class name, because the whole point is
what the cascade resolves: the sidebar item takes its size from a vendored
\`Button\`, which the theme reaches through the utility it names.`,...Jt.parameters?.docs?.description}}},Yt.parameters={...Yt.parameters,docs:{...Yt.parameters?.docs,source:{originalSource:`{
  ...DisplayWithData,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    const column = canvasElement.querySelector<HTMLElement>('[data-slot="view-list"]')!;
    // The work area paints nothing of its own: what shows through \`main\` is
    // the surface's \`--background\`, which is the colour to compare against.
    const work = canvasElement.querySelector<HTMLElement>('[data-slot="view-surface"]')!;
    const ground = paintOf(column);

    // The column has a ground of its own, and the work area is not it.
    await expect(ground).not.toBe('rgba(0, 0, 0, 0)');
    await expect(paintOf(work)).not.toBe('rgba(0, 0, 0, 0)');
    await expect(ground).not.toBe(paintOf(work));
    // And an edge between the two, drawn once.
    await expect(parseFloat(getComputedStyle(column).borderRightWidth)).toBeGreaterThan(0);

    // Two columns, two heads, one line under both. The sidebar's header is
    // ruled off at exactly the height the title bar is, so the screen reads
    // as one page in two columns rather than as two pages side by side.
    const listHead = canvasElement.querySelector<HTMLElement>('[data-slot="view-list-header"]')!;
    const titleBar = canvasElement.querySelector<HTMLElement>('[data-slot="view-header-block"]')!;
    await expect(parseFloat(getComputedStyle(listHead).borderBottomWidth)).toBeGreaterThan(0);
    await expect(Math.abs(listHead.getBoundingClientRect().bottom - titleBar.getBoundingClientRect().bottom)).toBeLessThanOrEqual(1);

    // The open view: a sheet of the work area's own ground lifted off the
    // column — an edge all the way round and a small shadow, no bar (an
    // inset bar bent round the corners into a "("). Neither is another step
    // of the same grey.
    const current = listItem(canvasElement, '待出库订单');
    await expect(current.getAttribute('aria-current')).toBe('true');
    await expect(paintOf(current)).toBe(paintOf(work));
    await expect(paintOf(current)).not.toBe(ground);
    const sheet = getComputedStyle(current);
    await expect(sheet.boxShadow).not.toBe('none');
    await expect(sheet.boxShadow).not.toContain('inset');
    await expect(sheet.borderLeftColor).not.toBe('rgba(0, 0, 0, 0)');
    await expect(sheet.borderLeftColor).toBe(sheet.borderRightColor);
    await expect(getComputedStyle(current).fontWeight).toBe('500');

    // A row that is not open carries no fill of its own, so what shows is
    // the column. Hovering it is a third colour: distinguishable from the
    // ground it sits on *and* from the open row beside it, which is the pair
    // that used to be identical.
    const other = listItem(canvasElement, '我盯的大额单');
    await expect(paintOf(other)).toBe('rgba(0, 0, 0, 0)');
    await expect(other.className).toContain('hover:bg-sidebar-accent');
    // Painted rather than hovered: the runner's pointer events do not put a
    // real \`:hover\` on the element, and what broke before was never the
    // pseudo-class — it was the two tokens resolving to one grey. So the
    // token is put on the page the way the hover would put it, in the
    // column's own cascade, and read back in the same form as the rest.
    const hovered = await waitFor(() => {
      const probe = column.appendChild(document.createElement('div'));
      probe.style.backgroundColor = 'var(--sidebar-accent)';
      const painted = paintOf(probe);
      probe.remove();
      return painted;
    });
    await expect(hovered).not.toBe(ground);
    await expect(hovered).not.toBe(paintOf(current));

    // The kind icon is on every row, because one definition holds record and
    // analysis views together and the name alone does not say which is which.
    await expect(other.querySelector('svg')).not.toBeNull();
    // And where a view came from is a lock at the row's end, beside where
    // the star goes — a mark, not a badge.
    const system = listItem(canvasElement, '全部订单');
    await expect(system.querySelector('[data-slot="view-system-tag"]')).not.toBeNull();
    await expect(system.querySelector('[data-slot="badge"]')).toBeNull();
  }
}`,...Yt.parameters?.docs?.source},description:{story:`The sidebar is a navigation column, and the three states on it are three
colours.

This is the measurement the design could not be argued into: four states
used to share one 3% grey — a hovered row, the open row, a selected table
row and a pressed segment — so the list had no "you are here" at all, and
the column itself was the same white as the work area beside it. The
ratios are tiny on purpose; what is asserted is that they are not *one*,
which is what a token collapse looks like from here.

The bar down the open row's leading edge is the other half: a mark of a
different kind, which no theming can turn into the fill beside it.`,...Yt.parameters?.docs?.description}}},Xt.parameters={...Xt.parameters,docs:{...Xt.parameters?.docs,source:{originalSource:`{
  ...DisplayManageViews,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');

    // Nothing opens first until someone says so, so nothing wears a star.
    await expect(canvasElement.querySelector('[data-slot="view-default-star"]')).toBeNull();
    await userEvent.click(canvas.getByRole('button', {
      name: zhCN['label.manage.open']
    }));
    const dialog = await within(document.body).findByRole('dialog');
    const managed = [...dialog.querySelectorAll<HTMLElement>('[data-slot="view-manager-row"]')].find(row => row.textContent?.includes('我盯的大额单'))!;
    await userEvent.click(within(managed).getByRole('button', {
      name: zhCN['label.manage.set-default']
    }));
    await waitFor(() => expect(within(managed).getByRole('button', {
      name: zhCN['label.manage.unset-default']
    })).toHaveAttribute('aria-pressed', 'true'));
    await userEvent.keyboard('{Escape}');
    await waitFor(() => expect(document.body.querySelector('[role="dialog"]')).toBeNull());

    // The same preference, on the row in the list: filled, in the primary
    // colour, and at the row's end.
    const starred = listItem(canvasElement, '我盯的大额单');
    const star = starred.querySelector<HTMLElement>('[data-slot="view-default-star"]')!;
    await expect(star).not.toBeNull();
    // Filled and in the primary colour, so it reads as a mark rather than as
    // one more outline among the icons.
    await expect(star.classList).toContain('fill-current');
    await expect(getComputedStyle(star).color).not.toBe(getComputedStyle(starred).color);
    // One star and no more: the fact is about one view.
    await expect(canvasElement.querySelectorAll('[data-slot="view-default-star"]')).toHaveLength(1);
    // And a reader hears it rather than only seeing it.
    await expect(starred.textContent).toContain(zhCN['label.manage.default']);
  }
}`,...Xt.parameters?.docs?.source},description:{story:`The star on the view that opens first, read off the preference the manager
writes.

Two screens showing the same fact is only worth having while they cannot
disagree, so this sets the default where it is set — in the manager — and
then looks for it where it is read: on the row in the list.`,...Xt.parameters?.docs?.description}}},Zt.parameters={...Zt.parameters,docs:{...Zt.parameters?.docs,source:{originalSource:`{
  ...DisplayNarrowTitleBar,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    const sidebar = () => canvasElement.querySelector('[data-slot="view-sidebar"]');
    const host = canvasElement.querySelector<HTMLElement>('[data-narrow-host]')!;

    // Nobody passed \`defaultSidebarOpen\`: the shell measured the column it
    // was given and folded the list for it.
    await expect(host.getBoundingClientRect().width).toBe(375);
    await expect(sidebar()).toBeNull();
    // Which is what the fold buys: the rows start at the top of the column
    // rather than under 204px of navigation.
    await expect(table.getBoundingClientRect().top - canvasElement.querySelector('[data-slot="view-surface"]')!.getBoundingClientRect().top).toBeLessThan(260);

    // The list is still reachable, as one control in the title bar.
    const switcher = canvasElement.querySelector<HTMLElement>('[data-slot="view-switcher"]')!;
    await expect(switcher).not.toBeNull();
    // Sized to what it says and reading from its beginning — not a 470px
    // pill with a short name floating in the middle of it.
    await expect(getComputedStyle(switcher).justifyContent).toBe('flex-start');
    const label = switcher.querySelector<HTMLElement>('span')!;
    await expect(switcher.getBoundingClientRect().right - label.getBoundingClientRect().right).toBeLessThan(40);
    // And the right-hand group still ends the bar, on whichever line it is.
    const header = canvasElement.querySelector<HTMLElement>('[data-slot="view-header"]')!;
    const controls = canvasElement.querySelector<HTMLElement>('[data-slot="view-controls"]')!;
    await expect(Math.abs(controls.getBoundingClientRect().right - header.getBoundingClientRect().right)).toBeLessThanOrEqual(1);
  }
}`,...Zt.parameters?.docs?.source},description:{story:`The fold the shell decides for itself, and the one filling the screen asks
for.

A 375px column has no room for a 224px list beside it — below \`md\` the
list is not beside the view at all but stacked over it, and the table
started 204px down. Filling the screen is the same argument made by the
user: the gesture is about the rows, and navigation is the first thing
that is not the rows.`,...Zt.parameters?.docs?.description}}},Qt.parameters={...Qt.parameters,docs:{...Qt.parameters?.docs,source:{originalSource:`{
  ...DisplayNarrowTitleBar,
  // Wide enough to open with the list beside the view; the play is what
  // takes the room away and gives it back.
  args: {
    ...DisplayNarrowTitleBar.args,
    narrowWidth: 1000
  },
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    const sidebar = () => canvasElement.querySelector('[data-slot="view-sidebar"]');
    const host = canvasElement.querySelector<HTMLElement>('[data-narrow-host]')!;
    const resizeTo = async (width: number) => {
      host.style.width = \`\${width}px\`;
      await expect(host.getBoundingClientRect().width).toBe(width);
      // A \`ResizeObserver\` reports on the frame after the box changed, so
      // the answer is never the one on screen at this instant.
      await new Promise(settle => setTimeout(settle, 200));
    };
    await expect(host.getBoundingClientRect().width).toBe(1000);
    await expect(sidebar()).not.toBeNull();

    // Dragged below \`md\`: the list would no longer be *beside* the view but
    // stacked over it, which is the whole reason a narrow column folds.
    await resizeTo(608);
    await expect(sidebar()).toBeNull();

    // And back, because the room it was folded for is there again.
    await resizeTo(1000);
    await expect(sidebar()).not.toBeNull();

    // Then the user answers, and the measurement stops answering: folded by
    // hand in a column with room to spare, it stays folded through a trip
    // down to 608 and back. Undoing that under their hands, once per drag,
    // is worse than a list folded where it would have fitted.
    await userEvent.click(canvas.getByRole('button', {
      name: zhCN['label.workbench.collapse-sidebar']
    }));
    await expect(sidebar()).toBeNull();
    await resizeTo(608);
    await resizeTo(1000);
    await expect(sidebar()).toBeNull();
  }
}`,...Qt.parameters?.docs?.source},description:{story:`The same rule after arrival: the fold follows the column it is given, in
both directions, and stops following once the user has answered for
themselves.

jsdom lays nothing out, so the unit test drives a fake observer over a
faked width; this is the one place a real \`ResizeObserver\` on a real box
is weighed. The width is the host's, as a host's is — a split pane dragged
narrower, a panel opened beside the page, a window resized — and the
workbench is told nothing but the box it ends up in.`,...Qt.parameters?.docs?.description}}},$t.parameters={...$t.parameters,docs:{...$t.parameters?.docs,source:{originalSource:`{
  ...DisplayWithData,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    const sidebar = () => canvasElement.querySelector('[data-slot="view-sidebar"]');
    await expect(sidebar()).not.toBeNull();
    await userEvent.click(canvas.getByRole('button', {
      name: zhCN['label.workbench.expand-view']
    }));
    await waitFor(() => expect(sidebar()).toBeNull());
    // Not lost, only folded: the switcher is the list while it is away.
    await expect(canvas.getByRole('button', {
      name: zhCN['label.workbench.switch-view']
    })).toBeVisible();

    // And leaving reads the page's own answer again.
    await userEvent.click(canvas.getByRole('button', {
      name: zhCN['label.workbench.collapse-view']
    }));
    await waitFor(() => expect(sidebar()).not.toBeNull());
  }
}`,...$t.parameters?.docs?.source},description:{story:`Filling the screen gives the rows the room, and the list is the first
thing that is not the rows.`,...$t.parameters?.docs?.description}}},en.parameters={...en.parameters,docs:{...en.parameters?.docs,source:{originalSource:`{
  ...DisplayCollapsedSidebar,
  // Starts open on purpose: the fold itself is half of what is asserted.
  args: {
    ...DisplayCollapsedSidebar.args,
    collapsed: false
  },
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    const sidebar = () => canvasElement.querySelector('[data-slot="view-sidebar"]');
    await expect(sidebar()).not.toBeNull();
    await userEvent.click(canvas.getByRole('button', {
      name: zhCN['label.workbench.collapse-sidebar']
    }));
    await expect(sidebar()).toBeNull();

    // What the sidebar was carrying is now in the title bar, in one group
    // with the commands that save the view.
    const identity = canvasElement.querySelector<HTMLElement>('[data-slot="view-identity"]')!;
    await expect(identity).toHaveTextContent('订单');
    await expect(within(identity).getByRole('button', {
      name: zhCN['label.workbench.switch-view']
    })).toBeVisible();
    // The save group moved left, next to the view's name: it changes the
    // config under that name, so it belongs to it rather than to the row's end.
    await expect(identity.querySelector('[data-slot="save-actions"]')).not.toBeNull();

    // The switcher opens the same views the sidebar listed, grouped the same
    // way, and choosing one opens it.
    await userEvent.click(within(identity).getByRole('button', {
      name: zhCN['label.workbench.switch-view']
    }));
    const menu = await within(document.body).findByRole('menu');
    await expect(menu).toHaveTextContent(zhCN['label.scope.group.personal']);
    await expect(menu).toHaveTextContent(zhCN['label.scope.tag.system']);
    await userEvent.click(within(menu).getByRole('menuitemradio', {
      name: /我盯的大额单/
    }));
    await waitFor(() => expect(canvasElement.querySelector('[data-slot="view-title"]')).toHaveTextContent('我盯的大额单'));

    // And back: the list returns, and the header gives up the switcher.
    await userEvent.click(canvas.getByRole('button', {
      name: zhCN['label.workbench.expand-sidebar']
    }));
    await expect(sidebar()).not.toBeNull();
    await expect(canvas.queryByRole('button', {
      name: zhCN['label.workbench.switch-view']
    })).toBeNull();
    // Nothing left open: Base UI parks focus-guard sentinels beside an open
    // popup, and axe judges the page as the play leaves it.
    await waitFor(() => expect(document.body.querySelector('[role="menu"]')).toBeNull());
  }
}`,...en.parameters?.docs?.source},description:{story:`The sidebar folded away, and the list still reachable.

Folding takes the one control that opens another view off the screen, so
the title bar has to grow its replacement in the same gesture: the way
back, the definition's name, and the list as one dropdown. This walks the
whole round trip — fold, switch, unfold — because the failure worth
catching is the one where a user folds the list and cannot get back to it.`,...en.parameters?.docs?.description}}},tn.parameters={...tn.parameters,docs:{...tn.parameters?.docs,source:{originalSource:`{
  ...DisplayWithData,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');

    // A saved view opens folded, so the panel is not on the page at all.
    await expect(canvasElement.querySelector('[data-slot="editor-band"]')).toBeNull();
    const toggle = canvas.getByRole('button', {
      name: new RegExp(\`^\${zhCN['label.filter.panel']}\`)
    });
    await expect(toggle).toHaveAttribute('aria-expanded', 'false');
    await userEvent.click(toggle);
    await expect(canvasElement.querySelector('[data-slot="editor-band"]')).not.toBeNull();

    // The mode lives beside the toggle now, and drives the panel below it.
    await userEvent.click(canvas.getByRole('button', {
      name: zhCN['label.workbench.editor-modes']
    }));
    const modes = await within(document.body).findByRole('menu');
    await userEvent.click(within(modes).getByRole('menuitemradio', {
      name: zhCN['label.filter.advanced']
    }));

    // Advanced draws the root as a framed block with its operator on it.
    await waitFor(() => expect(canvasElement.querySelector('[data-slot="filter-group"]')).not.toBeNull());
    // The menu is gone before the story settles. Base UI parks focus-guard
    // sentinels beside an open popup, and axe judges the page as the play
    // leaves it — so a play that opened something closes it, which is what
    // a user does anyway.
    await waitFor(() => expect(document.body.querySelector('[role="menu"]')).toBeNull());
    await expect(canvas.getByRole('button', {
      name: new RegExp(\`^\${zhCN['label.filter.panel']}\`)
    })).toHaveAccessibleName(
    // Called what it opens: the mode is the menu's checked item, not a
    // word on the button.
    zhCN['label.filter.panel']);
  }
}`,...tn.parameters?.docs?.source},description:{story:`The editor's fold is driven from the title bar, and its mode from the
chevron beside it.

Both moved out of the panel: the panel is the conditions, and a control
for *how to edit them* sitting among them was a line of chrome over every
filter ever written. The dot on the toggle is the one credential a folded
editor can still show, so it is asserted here rather than assumed.`,...tn.parameters?.docs?.description}}},nn.parameters={...nn.parameters,docs:{...nn.parameters?.docs,source:{originalSource:`{
  ...DisplayWithData,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');

    // The fold, from the handle in the title bar. A saved view opens folded.
    const toggle = canvas.getByRole('button', {
      name: new RegExp(\`^\${zhCN['label.filter.panel']}\`)
    });
    await expect(toggle).toHaveAttribute('aria-expanded', 'false');
    await expect(toggle).not.toHaveAttribute('aria-controls');
    await tabTo(toggle);
    await userEvent.keyboard('{Enter}');
    const band = await waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>('[data-slot="editor-band"]');
      if (!found) throw new Error('the band did not open');
      return found;
    });
    await expect(toggle).toHaveAttribute('aria-expanded', 'true');
    await expect(toggle).toHaveAttribute('aria-controls', band.id);
    // A press takes the keyboard into what it opened (the 2026-09-23 audit,
    // P2-13): the band's first control, not Refresh and Fill on the way.
    await waitFor(() => expect(band.contains(document.activeElement)).toBe(true));
    // Back on the handle, Space closes it again, and the panel leaves the
    // page with it — so the reference the handle was making leaves too.
    toggle.focus();
    await userEvent.keyboard(' ');
    await waitFor(() => expect(canvasElement.querySelector('[data-slot="editor-band"]')).toBeNull());
    await expect(toggle).toHaveAttribute('aria-expanded', 'false');
    await expect(toggle).not.toHaveAttribute('aria-controls');

    // The bar: one stop, the arrows inside it.
    const bar = canvas.getByRole('toolbar', {
      name: zhCN['label.toolbar.title']
    });
    await expect(bar).toHaveAttribute('aria-orientation', 'horizontal');
    const controls = [...bar.querySelectorAll('button')];
    await expect(controls.filter(control => control.tabIndex === 0)).toHaveLength(1);
    await tabTo(controls[0]!);
    for (let at = 1; at < controls.length; at += 1) {
      await userEvent.keyboard('{ArrowRight}');
      await expect(document.activeElement).toBe(controls[at]);
    }
    // Both ends wrap.
    await userEvent.keyboard('{ArrowRight}');
    await expect(document.activeElement).toBe(controls[0]);
    await userEvent.keyboard('{ArrowLeft}');
    await expect(document.activeElement).toBe(controls[controls.length - 1]);

    // Walking moves focus and nothing else: the layout switch is still on
    // the layout it was on, and the rows are still a table.
    await expect(canvas.getByRole('button', {
      name: zhCN['label.layout.table']
    })).toHaveAttribute('aria-pressed', 'true');
    await expect(canvas.getByRole('table')).toBeInTheDocument();

    // Tab leaves the whole bar rather than stepping to the next control in
    // it, and comes back to the one the bar was left on.
    await userEvent.tab();
    await expect(bar.contains(document.activeElement)).toBe(false);
    await userEvent.tab({
      shift: true
    });
    await expect(document.activeElement).toBe(controls[controls.length - 1]);

    // An item of the bar is still the popup's trigger.
    await tabTo(canvas.getByRole('button', {
      name: zhCN['label.toolbar.columns']
    }));
    await userEvent.keyboard('{Enter}');
    await within(document.body).findByText(zhCN['label.columns.title']);
    // One live region per surface, however many lists the popup holds: the
    // workbench's own sits in the result block and reads its queries back,
    // and the popover carries one for the arrow keys and the pin toggles
    // inside it. They answer different presses — a pinned column says where
    // it landed, the query that press started says it is running — and a
    // polite region queues rather than interrupts, so the two never read
    // over each other. \`@dnd-kit\` keeps a third beside them
    // (\`#dnd-kit-announcement-*\`) for what the library itself drives.
    const regions = (root: ParentNode) => root.querySelectorAll('[aria-live]:not([id^="dnd-kit"])');
    await expect(regions(canvasElement)).toHaveLength(1);
    const popup = await waitFor(() => {
      const found = document.body.querySelector('[data-slot="popover-content"]');
      if (!found) throw new Error('the popover did not open');
      return found;
    });
    await expect(regions(popup)).toHaveLength(1);
    // Closed again before the story settles, as every play that opens a
    // popup does: axe judges the page as the play leaves it. Twice, because
    // a control reached by the keyboard is showing its own tooltip and that
    // is the top layer — the first Escape is the tooltip's.
    await userEvent.keyboard('{Escape}');
    await waitFor(() => expect(document.body.querySelector('[data-slot="tooltip-content"]')).toBeNull());
    await userEvent.keyboard('{Escape}');
    await waitFor(() => expect(document.body.querySelector('[data-slot="popover-content"]')).toBeNull());
  }
}`,...nn.parameters?.docs?.source},description:{story:`键盘上的两件事，只有真浏览器答得出来：原生按钮被 Enter／空格激活是浏览器的
默认动作，漫游焦点是 Base UI 在真实 keydown 上做的事。

一、**结果工具栏是一条 toolbar**：整条栏只有一个 Tab 站，方向键在栏内左右
走并在两端回绕，走过去只移动焦点——布局切换的档位不会被走成按下；离开这条
栏的 Tab 直接落到表上。二、**折叠带的开关是 \`CollapsibleTrigger\`**：Enter
开、空格关，\`aria-expanded\` 跟着翻，\`aria-controls\` 只在带子在页面上时存在；
按开之后键盘落进带子里。`,...nn.parameters?.docs?.description}}},rn.parameters={...rn.parameters,docs:{...rn.parameters?.docs,source:{originalSource:`{
  ...DisplayWithData,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    const region = () => {
      const found = canvasElement.querySelector<HTMLElement>('[data-slot="record-announcement"]');
      if (!found) throw new Error('the result has no live region');
      return found;
    };
    const landed = say('label.pagination.total', {
      total: 4
    });

    // The query that opened the view has already been read back.
    await waitFor(() => expect(region()).toHaveTextContent(landed));

    // Everything it says from here on, in order and without repeats.
    const heard: string[] = [];
    const observer = new MutationObserver(() => {
      const text = region().textContent?.trim() ?? '';
      if (text !== '' && heard[heard.length - 1] !== text) heard.push(text);
    });
    // \`characterData\` as well as \`childList\`: React writes a new sentence
    // into the text node that is already there, which is not a child list
    // changing.
    observer.observe(region(), {
      characterData: true,
      childList: true,
      subtree: true
    });
    await addSort(table, '订单号');
    await waitFor(() => expect(heard).toContain(landed));
    observer.disconnect();

    // Two sentences for one query, each said once: nothing repeated, and
    // the running one in between is what makes a second identical result
    // audible at all.
    await expect(heard).toEqual([zhCN['label.status.querying'], landed]);

    // And the bar the sentence was taken from says the same thing, which is
    // the whole reason it is that sentence and not a second wording.
    await expect(paginationBar(canvasElement)).toHaveTextContent(landed);

    // The button that ran it can now be heard as a sort. On screen it is
    // the first field, an arrow and a count — 订单号 joined the saved sort
    // rather than replacing it — and none of that says what the control is.
    const sortButton = canvasElement.querySelector<HTMLElement>('[data-control="sort"]');
    await waitFor(() => expect(sortButton).toHaveAccessibleName(\`\${say('label.sort.button', {
      field: '金额',
      direction: zhCN['label.sort.desc']
    })} \${say('label.sort.more', {
      count: 1
    })}\`));
    // And the words on it are unchanged by the name it was given.
    await expect(sortButton).toHaveTextContent(\`金额\${say('label.sort.more', {
      count: 1
    })}\`);

    // A toggle whose own state a reader hears as one word, so the panel
    // says which column and which edge as well.
    await userEvent.click(canvasElement.querySelector<HTMLElement>('[data-control="columns"]')!);
    const body = within(document.body);
    await body.findByText(zhCN['label.columns.title']);
    await userEvent.click(body.getByRole('button', {
      name: say('label.columns.pin', {
        field: '仓库'
      })
    }));
    await waitFor(() => expect(document.body.querySelector('[data-slot="column-announcement"]')).toHaveTextContent(/^仓库 已固定/));

    // Closed before the story settles, as every play that opens a popup
    // does: axe judges the page as the play leaves it. Twice, because the
    // control just pressed is showing its own tooltip on top.
    await userEvent.keyboard('{Escape}');
    await userEvent.keyboard('{Escape}');
    await waitFor(() => expect(document.body.querySelector('[data-slot="popover-content"]')).toBeNull());
  }
}`,...rn.parameters?.docs?.source},description:{story:`What a query says out loud, in a browser where a live region is read
rather than an attribute in a tree.

The rows change under a reader who is not looking at them, and until this
every \`[aria-live]\` on the screen stayed empty from the first press to the
last. What is regressed here is the pair, in order and once each: the
query says it is running, and then says what came back in the sentence the
pagination bar is showing. Sorting is the shortest honest query — it is
one \`edit\` and one \`apply\`, the same round trip a condition makes.`,...rn.parameters?.docs?.description}}},an.parameters={...an.parameters,docs:{...an.parameters?.docs,source:{originalSource:`{
  ...DisplayWithData,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    await userEvent.click(canvas.getByRole('button', {
      name: new RegExp(\`^\${zhCN['label.filter.panel']}\`)
    }));
    await userEvent.click(await canvas.findByRole('button', {
      name: zhCN['label.filter.add']
    }));
    const picker = await within(document.body).findByRole('dialog');
    await expect(picker).toHaveTextContent(zhCN['label.filter.pick-fields']);
    // The view's saved condition is already a tick, which is what makes the
    // list a statement about the filter rather than a menu of things to add.
    await expect(within(picker).getByRole('checkbox', {
      name: '状态'
    })).toBeChecked();
    await userEvent.click(within(picker).getByRole('checkbox', {
      name: '仓库'
    }));
    await userEvent.click(within(picker).getByRole('checkbox', {
      name: '金额'
    }));
    // And the third with the keyboard alone: from the search line one Tab
    // steps into the grid and Space is what a tick means. The jsdom suite
    // asserts the same thing; this one asserts it in a browser, where the
    // checkbox is a \`span\` carrying a role rather than an input, and Space
    // is somebody's own key handler rather than the platform's.
    await userEvent.click(within(picker).getByRole('textbox', {
      name: zhCN['label.field.search']
    }));
    await userEvent.keyboard('{Tab}');
    await expect(document.activeElement).toBe(within(picker).getByRole('checkbox', {
      name: '订单号'
    }));
    await userEvent.keyboard(' ');
    await expect(within(picker).getByRole('checkbox', {
      name: '订单号'
    })).toBeChecked();
    await userEvent.click(within(picker).getByRole('button', {
      name: zhCN['label.filter.pick-done']
    }));

    // Four conditions now: the saved one, the two ticked with the pointer
    // and the one ticked with the keyboard.
    await waitFor(() => expect(canvasElement.querySelectorAll('[data-slot="filter-condition"]')).toHaveLength(4));
    // And the picker is shut, sentinels and all — see CollapseAndSwitch.
    await waitFor(() => expect(document.body.querySelector('[role="dialog"]')).toBeNull());
  }
}`,...an.parameters?.docs?.source},description:{story:`Three fields ticked in one visit to the picker, and three pills for it.

The picker used to close on every pick, which made four conditions four
round trips. It stays open now, so the thing worth regressing is that a
second tick lands while the first is still on screen — and that the third
can be ticked without a pointer at all.`,...an.parameters?.docs?.description}}},on.parameters={...on.parameters,docs:{...on.parameters?.docs,source:{originalSource:`{
  ...DisplayFillTheScreen,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    const doc = canvasElement.ownerDocument;
    const surface = canvasElement.querySelector<HTMLElement>('[data-slot="view-surface"]')!;
    const parent = surface.parentElement;
    const before = held(doc);

    // Which column is frozen, read off the header that declares it: only the
    // headers carry \`data-pin\`, and a body cell is sticky one cell at a time
    // — freezing the header and letting the cells under it slide away is
    // worse than not freezing at all, so the cell is what is checked.
    const pinnedHead = table.querySelector<HTMLTableCellElement>('thead th[data-pin-index]')!;
    const cellUnder = (section: string) => table.querySelector<HTMLTableRowElement>(\`\${section} tr\`)!.cells[pinnedHead.cellIndex];
    /** The three layers that must not come unstuck, whatever is tall. */
    const sticky = () => ({
      header: getComputedStyle(pinnedHead).position,
      summary: getComputedStyle(table.querySelector('tfoot td')!).position,
      pinned: getComputedStyle(cellUnder('tbody')).position
    });
    const STUCK = {
      header: 'sticky',
      summary: 'sticky',
      pinned: 'sticky'
    };
    await expect(sticky()).toEqual(STUCK);
    const before70vh = table.closest<HTMLElement>('[data-slot="record-table"]')!.getBoundingClientRect();
    const toggle = canvas.getByRole('button', {
      name: zhCN['label.workbench.expand-view']
    });
    await expect(toggle).toHaveAttribute('aria-expanded', 'false');
    await userEvent.click(toggle);

    // Pinned to the viewport, and nowhere else: \`position: fixed\` is the
    // whole of the visual change, and it is one attribute on the element
    // that was already there.
    await waitFor(() => expect(surface).toHaveAttribute('data-view-expanded', 'true'));
    await expect(getComputedStyle(surface).position).toBe('fixed');
    await expect(surface.parentElement).toBe(parent);
    await expect(canvas.getByRole('table')).toBe(table);
    await expect(onViewport(surface)).toBe(true);
    // The background cannot be scrolled out from under it — and as important
    // as the value, the priority: a host stylesheet's \`!important\` would
    // otherwise outrank a plain inline declaration and go on scrolling. It is
    // taken on whatever actually scrolls this document (\`<html>\` here) and
    // one axis at a time, because the shorthand can neither read back nor
    // hand back a page that set only one of them.
    await expect(held(doc)).toEqual(['hidden !important', 'hidden !important']);
    // Expanding changes which box is tall; it must not change what sticks.
    await expect(sticky()).toEqual(STUCK);

    // And the height goes where the expansion was for. The rows are the
    // point of a bigger screen, so the table takes what the header, the
    // strips, the toolbar and the pagination left — not 70vh of it and a
    // blank half-screen underneath.
    const area = table.closest<HTMLElement>('[data-slot="record-table"]')!;
    await expect(area).toHaveAttribute('data-scrolls');
    await expect(getComputedStyle(area).maxHeight).toBe('none');
    const filled = area.getBoundingClientRect();
    await expect(filled.height).toBeGreaterThan(before70vh.height);
    // Everything still fits inside one screen: the page under it is locked,
    // so anything spilling past the fold would be unreachable.
    await expect(Math.round(filled.bottom)).toBeLessThanOrEqual(Math.round(surface.getBoundingClientRect().bottom) + 1);

    // Not a modal, and it says so by omission: nothing here claims one.
    await expect(surface.getAttribute('aria-modal')).toBeNull();
    await expect(surface.getAttribute('role')).toBeNull();
    await expect(doc.body.querySelector('[inert]')).toBeNull();

    // Escape is the way out, and focus comes back to the control that opened
    // it — the key is announced on the button rather than spent on a tooltip.
    const back = canvas.getByRole('button', {
      name: zhCN['label.workbench.collapse-view']
    });
    await expect(back).toBe(toggle);
    await expect(back).toHaveAttribute('aria-keyshortcuts', 'Escape');
    back.focus();
    await userEvent.keyboard('{Escape}');
    await waitFor(() => expect(surface).not.toHaveAttribute('data-view-expanded'));
    await expect(doc.activeElement).toBe(toggle);
    await expect(toggle).toHaveAttribute('aria-expanded', 'false');
    // The page is the page it was, and the table is still stuck together.
    await expect(held(doc)).toEqual(before);
    await expect(sticky()).toEqual(STUCK);
  }
}`,...on.parameters?.docs?.source},description:{story:`The view filling the screen, and the page given back.

The three things that go wrong here are all asserted rather than looked
at: the surface must expand **in place** (the same table node, under the
same parent — a portal would remount it and take the draft with it), the
document's scrolling must be locked while it is open and handed back
exactly as it was, and the table's sticky layers must still hold against
whatever actually scrolls, in both states.`,...on.parameters?.docs?.description}}},sn.parameters={...sn.parameters,docs:{...sn.parameters?.docs,source:{originalSource:`{
  ...DisplayFillTheScreenInTransformedHost,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    const doc = canvasElement.ownerDocument;
    const host = canvasElement.querySelector<HTMLElement>('[data-transformed-host]')!;
    const surface = host.querySelector<HTMLElement>('[data-slot="view-surface"]')!;

    // The premise: this host really is a containing block, and it really is
    // smaller than the screen. Without both, the test proves nothing.
    await expect(getComputedStyle(host).transform).not.toBe('none');
    const hostBox = host.getBoundingClientRect();
    await expect(hostBox.height).toBeLessThan(window.innerHeight);
    await userEvent.click(canvas.getByRole('button', {
      name: zhCN['label.workbench.expand-view']
    }));
    await waitFor(() => expect(surface).toHaveAttribute('data-view-expanded', 'true'));

    // Still in place — the node never moved, which is the point of the whole
    // design — and still on the viewport rather than on its host's box.
    await expect(surface.parentElement).toBe(host);
    await expect(onViewport(surface)).toBe(true);
    await expect(held(doc)).toEqual(['hidden !important', 'hidden !important']);
    // The correction is written back as geometry, not guessed from a list of
    // properties that would go stale.
    await expect(surface.style.getPropertyValue('--fve-expanded-w')).toBe(\`\${window.innerWidth}px\`);
    await userEvent.keyboard('{Escape}');
    await waitFor(() => expect(surface).not.toHaveAttribute('data-view-expanded'));
    // And the host's element is handed back without our arithmetic on it.
    await expect(surface.style.getPropertyValue('--fve-expanded-w')).toBe('');
    await expect(Math.round(surface.getBoundingClientRect().width)).toBeLessThanOrEqual(Math.round(hostBox.width));
  }
}`,...sn.parameters?.docs?.source},description:{story:'The same expansion inside a host that owns the containing block.\n\n`transform` — and `filter`, `perspective`, `backdrop-filter`,\n`will-change`, `contain`, `container-type` — makes an ancestor the\ncontaining block for every `position: fixed` inside it, so "fill the\nscreen" would fill *that container*. Animated panels and GPU-hinted grid\nshells do it as a matter of course, and an embedded view is meant to sit\nin an arbitrary host, so this is the case that decides whether the feature\nworks at all outside a plain page.\n\nEnumerating the triggers is a list that goes stale with the next CSS\nmodule, so the hook measures the box the browser actually gave it: the\ndifference from the viewport *is* the correction. This play is the check\nthat the measurement is real.',...sn.parameters?.docs?.description}}},cn.parameters={...cn.parameters,docs:{...cn.parameters?.docs,source:{originalSource:`{
  ...DisplayFillTheScreenInScaledHost,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    const host = canvasElement.querySelector<HTMLElement>('[data-scaled-host]')!;
    const surface = host.querySelector<HTMLElement>('[data-slot="view-surface"]')!;

    // The premise: this host really does scale, and by the ratio the story
    // set. Without it the test proves nothing.
    const matrix = new DOMMatrixReadOnly(getComputedStyle(host).transform);
    await expect(matrix.a).toBeCloseTo(0.75, 2);
    await userEvent.click(canvas.getByRole('button', {
      name: zhCN['label.workbench.expand-view']
    }));
    await waitFor(() => expect(surface).toHaveAttribute('data-view-expanded', 'true'));

    // In screen pixels — the only ones a reader has — exactly the viewport.
    await expect(onViewport(surface)).toBe(true);
    // And the written width is *larger* than the viewport by the ratio,
    // which is the whole of the second pass: the naive value would have been
    // the viewport's own width and would have painted three quarters of it.
    const written = Number.parseFloat(surface.style.getPropertyValue('--fve-expanded-w'));
    await expect(written).toBeGreaterThan(window.innerWidth);
    await expect(written * 0.75).toBeCloseTo(window.innerWidth, 0);
    await userEvent.keyboard('{Escape}');
    await waitFor(() => expect(surface).not.toHaveAttribute('data-view-expanded'));
    await expect(surface.style.getPropertyValue('--fve-expanded-w')).toBe('');
  }
}`,...cn.parameters?.docs?.source},description:{story:"The same correction, through a host that *scales* rather than only moves.\n\n`translateZ(0)` above makes an ancestor the containing block without\nchanging any size, so it exercises only half of `transform`. A\n`scale(.75)` host exercises the other half, and it is the half that reads\nbackwards: `getBoundingClientRect()` already reports screen pixels, while\nthe four `--fve-expanded-*` are read in the element's own coordinates,\nwhere one pixel is `.75` of a screen pixel. Handing the measured difference\nstraight back would leave the surface at three quarters of the screen and\nstill short of the corner — so the ratio between what was asked for and\nwhat appeared is measured too, and divided out.",...cn.parameters?.docs?.description}}},ln.parameters={...ln.parameters,docs:{...ln.parameters?.docs,source:{originalSource:`{
  ...DisplayRenderFailure,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    const rows = canvas.getAllByRole('row');
    await userEvent.click(within(rows[1]).getByRole('button', {
      name: '弄坏'
    }));
    const alert = await canvas.findByRole('alert');
    await expect(alert).toHaveAttribute('data-boundary', 'result');
    await expect(alert.closest('[data-slot="result-block"]')).not.toBeNull();
    await expect(canvas.queryByRole('table')).toBeNull();
    await expect(canvasElement.querySelector('[data-slot="view-title"]')).toBeVisible();
    await expect(canvas.getByRole('button', {
      name: zhCN['label.save.save']
    })).toBeVisible();
    await userEvent.click(within(alert).getByRole('button', {
      name: zhCN['label.render.retry']
    }));
    await canvas.findByRole('table');
    await expect(canvas.queryByRole('alert')).toBeNull();
  }
}`,...ln.parameters?.docs?.source},description:{story:`宿主的行动作抛错时，结果块换成可复原的错误态，其余部分照常可用。

按第一行的「弄坏」让那个动作在渲染时抛错，然后看三件事：结果块里是一条
\`role="alert"\` 加「重试」而不是白屏；标题栏与保存按钮还在；按「重试」之后行
回来了——动作组件被重新挂载，它那个「坏了」的状态一起归零。`,...ln.parameters?.docs?.description}}},un.parameters={...un.parameters,docs:{...un.parameters?.docs,source:{originalSource:`{
  ...DisplayPinnedEdges,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    const area = table.closest<HTMLElement>('[data-slot="record-table"]')!;
    await expect(area).toHaveAttribute('data-scrolls');

    // Two columns frozen left by the config, and the last column frozen
    // right by the projection whatever the config asked for.
    const inner = headerOf(table, '订单号');
    const left = headerOf(table, '金额');
    const end = headerOf(table, '创建时间');
    const actions = table.querySelector<HTMLTableCellElement>('thead th[data-column="actions"]')!;
    await expect(inner).toHaveAttribute('data-pin', 'left');
    await expect(left).toHaveAttribute('data-pin', 'left');
    // With a row-action column the host's slot is the end (D13): the last
    // data column lets go, and the actions wear the right edge.
    await expect(end).not.toHaveAttribute('data-pin');
    await expect(actions).toHaveAttribute('data-pin', 'right');

    /** Whether each cell draws an edge, on all three layers of one column. */
    const edged = (head: HTMLTableCellElement) => ['thead', 'tbody', 'tfoot'].map(layer => getComputedStyle(table.querySelector<HTMLTableRowElement>(\`\${layer} tr\`)!.cells[head.cellIndex]).boxShadow !== 'none');
    const edges = () => ({
      inner: edged(inner),
      left: edged(left),
      end: edged(end),
      actions: edged(actions)
    });
    const NONE = [false, false, false];
    const ALL = [true, true, true];
    const UNFRAMED = {
      inner: NONE,
      left: NONE,
      end: NONE,
      actions: NONE
    };

    // Still, and wide enough that nothing has to scroll: no edge at all
    // (P-23, D13 as amended). The edge says "the middle scrolls under here",
    // and there is no middle to scroll yet — it appears the moment there
    // is, below, before anything has moved.
    await waitFor(() => expect(area.hasAttribute('data-overflowing')).toBe(false));
    await waitFor(() => expect(edges()).toEqual(UNFRAMED));

    // Computed is not painted: in collapsed-border mode Chromium draws no
    // outer box-shadow on a cell, and the edges above were on the page for
    // a week without a pixel of shadow. Separate borders is what paints it,
    // and the hairline between rows then has to be the cells' own.
    await expect(getComputedStyle(table).borderCollapse).toBe('separate');
    const firstRow = table.querySelector<HTMLTableRowElement>('tbody tr')!;
    await expect(getComputedStyle(firstRow.cells[1]).borderBottomWidth).toBe('1px');

    // A pinned cell inherits its row's colour, so the hover has to be
    // opaque: a wash over the column it holds the place of would show that
    // column's text through it — which is what the user saw.
    await userEvent.hover(firstRow.cells[1]);
    const opaque = (colour: string) => !colour.startsWith('rgba(') && !/\\/\\s*0?\\.\\d+\\)/.test(colour);
    await waitFor(() => {
      const hovered = getComputedStyle(firstRow.cells[1]).backgroundColor;
      expect(opaque(hovered)).toBe(true);
      expect(hovered).toBe(getComputedStyle(firstRow).backgroundColor);
    });
    await userEvent.unhover(firstRow.cells[1]);

    // Narrowed until the middle really does scroll, and then scrolled from
    // one end to the other.
    //
    // *Which* columns are still held is not this story's to say any more:
    // at 420px the held group is over half the port, so the cap lets the
    // outermost pins go until it fits (D17-4, \`PinnedGroupCapped\`). What
    // this story is about holds either way — the two cells facing the
    // middle wear the edge and nobody else does — so the expectation is
    // read off the pinning the table is drawing rather than naming columns.
    const boundaries = () => {
      const held = [...table.querySelectorAll<HTMLTableCellElement>('thead th[data-pin]')];
      const lefts = held.filter(cell => cell.dataset.pin === 'left' && cell.dataset.column !== 'select');
      const rights = held.filter(cell => cell.dataset.pin === 'right');
      return new Set([lefts.at(-1), rights[0]]);
    };
    const framed = () => {
      const edged = boundaries();
      return {
        inner: edged.has(inner) ? ALL : NONE,
        left: edged.has(left) ? ALL : NONE,
        end: edged.has(end) ? ALL : NONE,
        actions: edged.has(actions) ? ALL : NONE
      };
    };
    area.style.maxWidth = '420px';
    await waitFor(() => expect(area.scrollWidth).toBeGreaterThan(area.clientWidth));
    // Overflow is said on the port, and the frame is there before anything
    // has been scrolled — which is the whole of D13.
    await waitFor(() => expect(area.hasAttribute('data-overflowing')).toBe(true));
    // The key is what the cap may never take, so there is always a left
    // boundary to look at.
    await expect(inner).toHaveAttribute('data-pin', 'left');
    await waitFor(() => expect(edges()).toEqual(framed()));
    for (const scrolled of [40, area.scrollWidth, 0]) {
      area.scrollLeft = scrolled;
      await waitFor(() => expect(edges()).toEqual(framed()));
    }

    // A column that slid under the frozen header stays under it, handle
    // and all. The resize handle is \`absolute z-20\`; in a header cell that
    // was merely \`relative\` that 20 competed at the row's level and beat
    // the pinned cells' 10, so the scrolled column's edge line painted
    // through the frozen header (user, 2026-09-22). Every point across the
    // frozen key header — short of its own handle at the right — must
    // resolve to that header.
    area.scrollLeft = 60;
    await waitFor(() => expect(area.scrollLeft).toBe(60));
    const box = inner.getBoundingClientRect();
    for (const at of [0.1, 0.3, 0.5, 0.7, 0.85]) {
      const hit = document.elementFromPoint(box.left + box.width * at, box.top + box.height / 2);
      await expect(inner.contains(hit), \`at \${at}: \${hit?.tagName} \${(hit as HTMLElement | null)?.dataset.slot ?? ''}\`).toBe(true);
    }
    area.scrollLeft = 0;

    // And nothing on the table says where it is scrolled to any more.
    await expect(table).not.toHaveAttribute('data-scrolled-left');
    await expect(table).not.toHaveAttribute('data-scrolled-right');
  }
}`,...un.parameters?.docs?.source},description:{story:`冻结列的边说的是「这两端钉着」，所以它一直都在（D13）。

首列（主键）与末列（这里是「创建时间」，宿主的操作列坐在它外侧）各带一道
\`--border\` 发丝线加一段软阴影，静止时就有，滚到中间、滚到尽头都不变。只有
**边界**格子画边：最后一个左冻结列与第一个右冻结列面对着会滚的中间，两个冻
结列之间从来没有东西经过；操作列此时不是边界，因为它前面已经有一列钉在右边
了。表头、数据行、汇总行三层拿的是同一个类名，所以同一列在三层上同起同落。
jsdom 不算布局也不套样式表，\`box-shadow\` 的真值只有这里量得到。`,...un.parameters?.docs?.description}}},fn.parameters={...fn.parameters,docs:{...fn.parameters?.docs,source:{originalSource:`{
  ...DisplayWideTable,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    const area = table.closest<HTMLElement>('[data-slot="record-table"]')!;

    // The premise, both halves of it: 20 columns and 50 rows on one page.
    await waitFor(() => expect(table.querySelectorAll('tbody tr')).toHaveLength(50));
    await expect(columnLabels(table)).toEqual(WIDE_COLUMNS);
    // Sorted by ship date, then by amount, then by number: the two orders
    // that left on the 20th, dearest first.
    await expect(readColumn(table, '运单号').slice(0, 2)).toEqual(['YD-1040', 'YD-1020']);
    // The three summaries are over all 50 rows, not over what fits.
    await expect(amountOf(readTotal(table, '运费'))).toBe(34480);
    await expect(readTotal(table, '件数')).toContain('197');
    await expect(readTotal(table, '重量')).toContain('558.5');

    // It scrolls sideways — which is the point of the fixture, and what no
    // other story could produce.
    await expect(area).toHaveAttribute('data-scrolls');
    await waitFor(() => expect(area.scrollWidth).toBeGreaterThan(area.clientWidth));

    /**
     * **Every row is the same height** — one line each, over 20 columns and
     * 50 rows (U3, user's 2026-09-22 review).
     *
     * A table is read down a column, and a row that is two lines tall
     * wherever a note is long or a second tag appears turns that straight
     * line into a staircase. Measured here, the 50 rows came in at 41, 61
     * and 77 pixels: a \`text\` cell clamped to three lines, and a pair of
     * tags wrapped by a column three characters wide. Both now take one
     * line in a table and keep the room they had on a card, so the whole
     * page is one height (\`ui/record/cells.tsx\`, \`docs/design/ui/record.md\`
     * 「一列怎么读」). Only a browser lays text out, so this assertion can
     * only live here.
     */
    const rows = [...table.querySelectorAll<HTMLTableRowElement>('tbody tr')];
    await waitFor(() => {
      const first = rows[0].getBoundingClientRect().height;
      // A whole pixel of slack and no more: the rows are laid out from the
      // same font at the same size, and a second line is 20 of them.
      for (const row of rows) expect(Math.abs(row.getBoundingClientRect().height - first)).toBeLessThan(1);
    });
    // And the note that used to be three lines is still all there, on the
    // hover — cut on screen, whole in the tooltip.
    const note = [...table.querySelectorAll<HTMLElement>('tbody [data-slot="cell-text"]')].find(found => found.textContent!.includes('\\n'))!;
    await expect(note).toHaveAttribute('title', note.textContent!);
    await expect(note.getBoundingClientRect().height).toBeLessThan(24);
    const head = (table as HTMLTableElement).tHead!;
    const foot = (table as HTMLTableElement).tFoot!;
    const key = headerOf(table, '运单号');
    const middle = headerOf(table, '状态');
    const actions = table.querySelector<HTMLTableCellElement>('thead th[data-column="actions"]')!;
    await expect(key).toHaveAttribute('data-pin', 'left');
    await expect(middle).not.toHaveAttribute('data-pin');
    await expect(actions).toHaveAttribute('data-pin', 'right');

    /** Whether each cell draws an edge, on all three layers of one column. */
    const edged = (head: HTMLTableCellElement) => ['thead', 'tbody', 'tfoot'].map(layer => getComputedStyle(table.querySelector<HTMLTableRowElement>(\`\${layer} tr\`)!.cells[head.cellIndex]).boxShadow !== 'none');
    const ALL = [true, true, true];
    const NONE = [false, false, false];
    const edges = () => ({
      key: edged(key),
      middle: edged(middle),
      actions: edged(actions)
    });
    const FRAMED = {
      key: ALL,
      middle: NONE,
      actions: ALL
    };
    await waitFor(() => expect(edges()).toEqual(FRAMED));

    /**
     * Every layer that has to keep holding while the middle moves.
     *
     * The header sticks as a \`<thead>\` and the summaries as a \`<tfoot>\` —
     * an unpinned \`<th>\` is \`relative\`, which is what lets a pinned one be
     * its own positioned ancestor — so the rows are what is read here, and
     * the cells only where the pinning is what makes them stick.
     */
    const sticky = () => ({
      head: getComputedStyle(head).position,
      foot: getComputedStyle(foot).position,
      key: getComputedStyle(key).position,
      cell: getComputedStyle(table.querySelector<HTMLTableRowElement>('tbody tr')!.cells[key.cellIndex]).position
    });
    const STUCK = {
      head: 'sticky',
      foot: 'sticky',
      key: 'sticky',
      cell: 'sticky'
    };
    await expect(sticky()).toEqual(STUCK);

    /** Where the three layers actually sit, to the pixel. */
    const held = () => ({
      headTop: Math.round(head.getBoundingClientRect().top),
      areaTop: Math.round(area.getBoundingClientRect().top),
      keyLeft: Math.round(key.getBoundingClientRect().left)
    });
    const resting = held();
    await expect(resting.headTop).toBe(resting.areaTop);

    // Scrolled to each end and back: the same two edges throughout, and the
    // frozen column has not moved a pixel.
    for (const left of [40, area.scrollWidth, area.scrollWidth / 2, 0]) {
      area.scrollLeft = left;
      await waitFor(() => expect(edges()).toEqual(FRAMED));
      await expect(sticky()).toEqual(STUCK);
      await expect(held().keyLeft).toBe(resting.keyLeft);
    }

    // And down past the fortieth row, which is the half no five-column story
    // could reach: the header is still at the top of the box.
    area.scrollTop = area.scrollHeight;
    await waitFor(() => expect(held().headTop).toBe(resting.areaTop));
    await expect(sticky()).toEqual(STUCK);
    // The header is still the header: at 20 columns, a column nobody can
    // name any more is a column nobody can read.
    await expect(columnLabels(table)).toEqual(WIDE_COLUMNS);
    await expect(Math.round(foot.getBoundingClientRect().bottom)).toBeLessThanOrEqual(Math.round(area.getBoundingClientRect().bottom));
    area.scrollTop = 0;
  }
}`,...fn.parameters?.docs?.source},description:{story:`20 columns and 50 rows: the shape every sticky rule was written for and
none of them could be checked against.

Until this fixture existed the widest story was five columns, so "the
header stays put", "both summary rows stay put" and "the two frozen edges
hold while the middle scrolls" were only ever exercised on a table with
nothing much to scroll. Here the middle really does scroll, in both
directions at once, and the frozen edges have 18 columns passing under
them.`,...fn.parameters?.docs?.description}}},pn.parameters={...pn.parameters,docs:{...pn.parameters?.docs,source:{originalSource:`{
  ...DisplayPinnedGroupCapped,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    const area = table.closest<HTMLElement>('[data-slot="record-table"]')!;
    const host = canvasElement.querySelector<HTMLElement>('[data-narrow-host]')!;
    await expect(host.getBoundingClientRect().width).toBe(420);
    const key = headerOf(table, '运单号');
    const cell = (column: string) => table.querySelector<HTMLTableCellElement>(\`thead th[data-column="\${column}"]\`)!;
    const actions = cell('actions');
    const select = cell('select');

    /** Everything still held, and what that leaves of the visible width. */
    const held = () => [...table.querySelectorAll<HTMLElement>('thead th[data-pin]')].reduce((sum, node) => sum + node.getBoundingClientRect().width, 0);
    const middleShare = () => (area.clientWidth - held()) / area.clientWidth;

    // The premise: a result area far narrower than the table it holds — no
    // wider than the 420px host, which the result band now runs edge to edge.
    await expect(area.clientWidth).toBeLessThanOrEqual(420);
    await waitFor(() => expect(area.scrollWidth).toBeGreaterThan(area.clientWidth));

    // The rule itself. Before the cap this read 0.19 — 54px of middle
    // against nineteen columns, the narrowest of them 44px wide.
    await waitFor(() => expect(middleShare()).toBeGreaterThanOrEqual(0.5));

    // What was let go is the outermost of the group, and what stays is the
    // row's identity: the key, and the checkbox beside it.
    await expect(actions).not.toHaveAttribute('data-pin');
    await expect(key).toHaveAttribute('data-pin', 'left');
    await expect(select).toHaveAttribute('data-pin', 'left');
    // The whole column and not the header alone — the buttons travel with
    // their row now.
    await expect(getComputedStyle(actions).position).not.toBe('sticky');
    const firstRow = table.querySelector<HTMLTableRowElement>('tbody tr')!;
    await expect(getComputedStyle(firstRow.cells[actions.cellIndex]).position).not.toBe('sticky');
    await expect(getComputedStyle(key).position).toBe('sticky');

    // And nothing was written: the view is exactly as it was saved.
    await expect(canvas.queryByText(zhCN['label.header.unsaved'])).toBeNull();

    // Widened, the pin comes back on its own.
    host.style.width = '1200px';
    await waitFor(() => expect(actions).toHaveAttribute('data-pin', 'right'));
    await expect(getComputedStyle(actions).position).toBe('sticky');

    // Narrowed again, it goes again — and the floor holds a second time.
    host.style.width = '420px';
    await waitFor(() => expect(actions).not.toHaveAttribute('data-pin'));
    await expect(middleShare()).toBeGreaterThanOrEqual(0.5);
    await expect(canvas.queryByText(zhCN['label.header.unsaved'])).toBeNull();
  }
}`,...pn.parameters?.docs?.source},description:{story:`The same 20 columns in a 420px column, where the held group is capped at
half the result area (D17-4).

This is the one story that can weigh the rule, because it is the only one
where the held columns are a large share of the port: 232px of frozen
chrome — checkbox, waybill number, the host's actions — against a result
area that measures 286. jsdom lays nothing out, so the unit test can pin
which pin is let go but not what it buys; the number read here is the one
the reader actually gets, "middle ÷ result area", and it has a floor.

Both directions, on the host's own width: the pin goes as the column
narrows and comes back as it widens, from the measurement rather than from
anything stored — the view is never marked unsaved, because the cap is a
rendering decision and the config still says \`pinned\`.`,...pn.parameters?.docs?.description}}},mn.parameters={...mn.parameters,docs:{...mn.parameters?.docs,source:{originalSource:`{
  ...DisplayWideTable,
  play: async ({
    canvasElement
  }) => {
    await readColumnSettings(canvasElement);
  }
}`,...mn.parameters?.docs?.source},description:{story:`Twenty columns in a full-width workbench.`,...mn.parameters?.docs?.description}}},hn.parameters={...hn.parameters,docs:{...hn.parameters?.docs,source:{originalSource:`{
  ...DisplayPinnedGroupCapped,
  play: async ({
    canvasElement
  }) => {
    const host = canvasElement.querySelector<HTMLElement>('[data-narrow-host]')!;
    await expect(host.getBoundingClientRect().width).toBe(420);
    await readColumnSettings(canvasElement);
  }
}`,...hn.parameters?.docs?.source},description:{story:`The same panel in a 420px column — phone, split screen, a host's side
panel. The popup is portalled to the body and placed against a trigger
near the right edge of a narrow host, which is where a popup that cannot
scroll and one that cannot fit look the same.`,...hn.parameters?.docs?.description}}},gn.parameters={...gn.parameters,docs:{...gn.parameters?.docs,source:{originalSource:`{
  ...DisplayFillTheScreenWithPopups,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    const surface = canvasElement.querySelector<HTMLElement>('[data-slot="view-surface"]')!;

    // The premise: \`金额\` is frozen because the saved config says so, and it
    // is not the row key.
    const pinned = headerOf(table, '金额');
    await expect(pinned).toHaveAttribute('data-pin', 'left');
    await expect(headerOf(table, '订单号')).toHaveAttribute('data-pin', 'left');

    /** Every layer that must keep holding, whichever box is the tall one. */
    const sticky = () => ({
      header: getComputedStyle(pinned).position,
      summary: getComputedStyle(table.querySelector('tfoot td')!).position,
      cell: getComputedStyle(table.querySelector<HTMLTableRowElement>('tbody tr')!.cells[pinned.cellIndex]).position
    });
    const STUCK = {
      header: 'sticky',
      summary: 'sticky',
      cell: 'sticky'
    };
    await expect(sticky()).toEqual(STUCK);
    const toggle = canvas.getByRole('button', {
      name: zhCN['label.workbench.expand-view']
    });
    // Still where the toolbar row puts it, after the editor's fold and before
    // whatever the host adds — #1555 rearranged the row under it, not this.
    const controls = canvasElement.querySelector<HTMLElement>('[data-slot="view-controls"]')!;
    await expect(controls.contains(toggle)).toBe(true);
    await expect([...controls.querySelectorAll('[data-slot="editor-toggle"], [data-slot="view-expand"]')].map(node => node.getAttribute('data-slot'))).toEqual(['editor-toggle', 'view-expand']);
    await userEvent.click(toggle);
    await waitFor(() => expect(surface).toHaveAttribute('data-view-expanded', 'true'));
    await expect(onViewport(surface)).toBe(true);

    // Each popover, opened over the expanded view and found by the only test
    // that matters: what the browser hands back at the middle of its own box.
    for (const trigger of canvasElement.querySelectorAll<HTMLElement>('[data-slot="result-toolbar"] [data-slot="popover-trigger"]')) {
      await userEvent.click(trigger);
      const popup = await waitFor(() => {
        const found = document.body.querySelector<HTMLElement>('[data-slot="popover-content"]');
        if (!found) throw new Error('no popover');
        return found;
      });
      // Portalled out of the surface, which is exactly why this can go wrong.
      await expect(popup.closest('[data-slot="view-surface"]')).toBeNull();
      await expect(inFrontOf(popup)).toBe(true);
      await userEvent.keyboard('{Escape}');
      // The popup took the key, and only the popup.
      await waitFor(() => expect(document.body.querySelector('[data-slot="popover-content"]')).toBeNull());
      await expect(surface).toHaveAttribute('data-view-expanded', 'true');
    }
    // Level 0 and no higher, which is the whole of the fix — said here as
    // well, so a regression names the cause and not only the symptom.
    await expect(getComputedStyle(surface).zIndex).toBe('0');

    // And the layers still hold once a different box is the tall one — with
    // the scrollport scrolled as far as it goes, in both directions, so this
    // is what the rows actually do and not only what the rule says.
    const area = table.closest<HTMLElement>('[data-slot="record-table"]')!;
    const port = table.parentElement!;
    port.scrollTop = port.scrollHeight;
    port.scrollLeft = port.scrollWidth;
    await expect(area).toHaveAttribute('data-scrolls');
    await expect(sticky()).toEqual(STUCK);
    // The frozen header stays inside the scrollport's own left edge rather
    // than riding away with the columns beside it.
    await expect(Math.round(pinned.getBoundingClientRect().left)).toBeGreaterThanOrEqual(Math.round(port.getBoundingClientRect().left) - 1);
    await userEvent.keyboard('{Escape}');
    await waitFor(() => expect(surface).not.toHaveAttribute('data-view-expanded'));
    await expect(sticky()).toEqual(STUCK);
  }
}`,...gn.parameters?.docs?.source},description:{story:"The promise the whole normal-layer decision was made to keep: a popup still\nopens *in front of* a view that fills the screen.\n\nEvery popup here is portalled to `document.body` inside a positioner the\nlayout engine gives `transform: translate(...)` — which makes the\npositioner a stacking context — and the `isolate z-50` on it is a Tailwind\nutility this package pins to `:where(.fve-root, .fve-root *)`. The popup's\n*content* carries `fve-root`; the positioner does not, so its `z-50`\nmatches nothing and it stays at `z-index: auto`. Any positive `z-index` on\nthe expanded surface therefore buries every popup in the package, content\n`z-50` and all, because a `z-index` inside a transformed ancestor cannot\nescape it. The column-settings and sort popovers did not exist when that\ndecision was written down, so this is the play that holds it.\n\nIt also holds the sticky layers against a column the *config* froze rather\nthan the row key, which the projection pins left whatever anyone asked for.",...gn.parameters?.docs?.description}}},_n.parameters={..._n.parameters,docs:{..._n.parameters?.docs,source:{originalSource:`{
  ...DisplayAutoRefresh,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    const cadence = () => canvasElement.querySelector<HTMLElement>('[data-slot="refresh-cadence"]');
    const seconds = say('label.refresh.seconds', {
      count: 30
    });
    /** The number on the key, whatever second of the count it is. */
    const count = () => Number(cadence()?.textContent?.replace(/\\D/g, ''));

    // The saved view already refreshes itself, so the credential is on the
    // button before anything is pressed — and it is counting down to the
    // next refresh rather than repeating the cadence the menu holds.
    await waitFor(() => expect(count()).toBeGreaterThan(0));
    const started = count();
    await expect(started).toBeLessThanOrEqual(30);
    // Real seconds, ticking: this is the one thing jsdom cannot show, since
    // a second there is whatever the test says it is.
    await waitFor(() => expect(count()).toBeLessThan(started), {
      timeout: 4_000
    });
    // The sentence a screen reader gets is the **cadence**, not the count:
    // a number that changes every second must not be read out every second,
    // which is why the count itself is \`aria-hidden\`.
    await expect(canvasElement.querySelector('[data-slot="refresh-now"]')).toHaveAccessibleDescription(say('label.refresh.on', {
      interval: seconds
    }));
    await expect(canvasElement.querySelector('[data-slot="refresh-countdown"]')).toHaveAttribute('aria-hidden', 'true');
    // The box is as wide as the widest reading this interval can produce, so
    // "10s" → "9s" never walks the \`▾\` beside it across the bar.
    const box = canvasElement.querySelector<HTMLElement>('[data-slot="refresh-countdown"]')!;
    const width = box.getBoundingClientRect().width;
    await waitFor(() => expect(count()).toBeLessThan(started - 1), {
      timeout: 4_000
    });
    await expect(box.getBoundingClientRect().width).toBe(width);
    const chevron = canvasElement.querySelector<HTMLElement>('[data-slot="refresh-interval"]')!;
    await userEvent.click(chevron);
    const menu = await within(document.body).findByRole('menu');
    // Portalled out of the surface, and still the thing a click at its
    // middle reaches.
    await expect(menu.closest('[data-slot="view-surface"]')).toBeNull();
    await expect(inFrontOf(menu)).toBe(true);

    // Off, then the ladder the limits admit — nothing disabled, because an
    // interval the kernel would refuse is not offered at all.
    await expect(within(menu).getAllByRole('menuitemradio').map(item => item.textContent)).toEqual([zhCN['label.refresh.off'], ...LADDER.map(cadenceOf)]);
    // The one in force is the one marked.
    await expect(within(menu).getByRole('menuitemradio', {
      name: seconds
    })).toHaveAttribute('aria-checked', 'true');

    // Choosing edits the view's own config and applies it, so the cadence
    // moves and the title bar says the view is now unsaved.
    const minutes = say('label.refresh.minutes', {
      count: 5
    });
    await userEvent.click(within(menu).getByRole('menuitemradio', {
      name: minutes
    }));
    // Counting down from the new interval — the fifth minute reads "4 min"
    // for all but its first second, so either is the right answer here.
    await waitFor(() => expect([minutes, say('label.refresh.minutes', {
      count: 4
    })]).toContain(cadence()?.textContent));
    await expect(canvas.getByText(zhCN['label.header.unsaved'])).toBeVisible();

    // And off again, from the keyboard: the chevron opens on Enter and hands
    // focus to the options.
    chevron.focus();
    await userEvent.keyboard('{Enter}');
    const reopened = await within(document.body).findByRole('menu');
    await userEvent.click(within(reopened).getByRole('menuitemradio', {
      name: zhCN['label.refresh.off']
    }));
    await waitFor(() => expect(cadence()).toBeNull());
  }
}`,..._n.parameters?.docs?.source},description:{story:`The way in to auto refresh: the \`▾\` beside the refresh button.

jsdom can say what the menu holds; only a browser can say that it opens in
front of the workbench and that a click on a rung lands on the rung — a
control added to a toolbar opens a popup portalled out of it, and where
that popup paints is decided by the whole page (see
\`PopupsOverRaisedHostLayer\` below).`,..._n.parameters?.docs?.description}}},xn.parameters={...xn.parameters,docs:{...xn.parameters?.docs,source:{originalSource:`{
  ...DisplayPopupsOverRaisedHostLayer,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    const raised = document.querySelector<HTMLElement>('[data-raised-host]')!;

    // The premise, in the three parts it has.
    await expect(getComputedStyle(raised).zIndex).toBe('10');
    await expect(trappedIn(raised)).toBeNull();
    await expect(inFrontOf(raised)).toBe(true);

    /** The popup of that kind, open and placed, and what opened it. */
    async function open(kind: (typeof POPUP_KINDS)[number]) {
      // The first trigger a pointer can reach: an icon inside a button is
      // \`pointer-events: none\` by the button's own rule, and a tooltip on one
      // is no more reachable for the user than it is here.
      const trigger = [...canvasElement.querySelectorAll<HTMLElement>(kind.trigger)].find(candidate => getComputedStyle(candidate).pointerEvents !== 'none');
      await expect(trigger, \`no \${kind.name} to open\`).toBeDefined();
      // The raised layer covers the trigger as it covers everything else, so
      // the event goes to the element rather than to a point on the screen.
      if (kind.hover) await userEvent.hover(trigger!);else await userEvent.click(trigger!);

      // Open, and laid out: a popup is in the document before it is placed,
      // and a hit test on a box of no size answers about the page behind it.
      const popup = await waitFor(() => {
        const found = document.body.querySelector<HTMLElement>(\`[data-slot="\${kind.slot}"]\`);
        if (!found || found.hasAttribute('data-closed')) throw new Error(\`no open \${kind.name}\`);
        const box = found.getBoundingClientRect();
        if (box.width === 0 || box.height === 0) throw new Error(\`the \${kind.name} has no box yet\`);
        return found;
      });
      // Portalled out of the surface, which is exactly why this can go wrong.
      await expect(popup.closest('[data-slot="view-surface"]')).toBeNull();
      return {
        popup,
        trigger: trigger!
      };
    }

    /**
     * Shut again before the next one opens. A popup on its way out stays in
     * the document for the length of its animation, so what is waited for is
     * that it is no longer open.
     */
    async function close(kind: (typeof POPUP_KINDS)[number], trigger: HTMLElement) {
      if (kind.hover) await userEvent.unhover(trigger);else await userEvent.keyboard('{Escape}');
      await waitFor(() => {
        const leaving = document.body.querySelector(\`[data-slot="\${kind.slot}"]\`);
        expect(leaving === null || leaving.hasAttribute('data-closed')).toBe(true);
      });
    }

    /** The element the level is written on: a dialog has no positioner. */
    const layerOf = (kind: (typeof POPUP_KINDS)[number], popup: HTMLElement) => kind.slot === 'dialog-content' ? popup : popup.parentElement!;
    for (const kind of POPUP_KINDS) {
      const {
        popup,
        trigger
      } = await open(kind);
      // The cause, and then the effect the user sees.
      await expect(getComputedStyle(layerOf(kind, popup)).zIndex).toBe('50');
      await expect(inFrontOf(popup), \`the \${kind.name} is buried\`).toBe(true);
      await close(kind, trigger);
    }

    // And the number really is what decides, which is what \`--fve-popup-z-index\`
    // offers a host whose own chrome stacks above 50. Turned *below* what this
    // host raised, the same popover goes behind it — the variable is read from
    // \`:root\`, where a host sets it beside the colour tokens.
    const root = document.documentElement;
    try {
      root.style.setProperty('--fve-popup-z-index', '3');
      const {
        popup,
        trigger
      } = await open(POPUP_KINDS[0]);
      await expect(getComputedStyle(layerOf(POPUP_KINDS[0], popup)).zIndex).toBe('3');
      await expect(inFrontOf(popup)).toBe(false);
      await close(POPUP_KINDS[0], trigger);
    } finally {
      root.style.removeProperty('--fve-popup-z-index');
    }
  }
}`,...xn.parameters?.docs?.source},description:{story:`The layer this package's popups paint on, and the one a host can move.

Whatever the host raises, a popup has to come out in front of it — the fix
is a \`z-index\` on the *positioner*, written as a style in \`ui/popups.tsx\`
because the positioner is no \`.fve-root\` and every rule of the stylesheet
is pinned inside one. Before it, a positioner stayed at \`z-index: auto\` and
every popup here painted at level 0, in front of the page only because its
portal is last in the body: a host layer at \`z-index: 1\` covered the lot.

The premise is checked as carefully as the claim. A raised layer proves
nothing if some ancestor trapped it in a stacking context of its own, since
it would then rank by document order and the popups would win without any
of this — so the layer is read for its level, for the absence of such an
ancestor, and for actually covering the view before a single popup opens.`,...xn.parameters?.docs?.description}}},Tn.parameters={...Tn.parameters,docs:{...Tn.parameters?.docs,source:{originalSource:`focusIndicators('light')`,...Tn.parameters?.docs?.source}}},En.parameters={...En.parameters,docs:{...En.parameters?.docs,source:{originalSource:`focusIndicators('dark')`,...En.parameters?.docs?.source}}},Dn.parameters={...Dn.parameters,docs:{...Dn.parameters?.docs,source:{originalSource:`{
  ...DisplayWithData,
  args: {
    ...DisplayWithData.args,
    theme: 'dark'
  },
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    const row = table.querySelector<HTMLElement>('tbody tr')!;
    const {
      ratio,
      colors
    } = measureBorderContrast(row, 'bottom');
    await expect(ratio, \`\${colors.border} on \${colors.fill} over \${colors.surface}\`).toBeGreaterThanOrEqual(1.5);
  }
}`,...Dn.parameters?.docs?.source},description:{story:`暗色下的行线看得见。

分隔线不是控件，不欠 3:1，但 10% 白在暗色卡片上量到 1.32:1——一张没有行的
表。这里量的是 \`tbody\` 行的下边线压在它自己的底色与卡片之上的层叠色。`,...Dn.parameters?.docs?.description}}},An.parameters={...An.parameters,docs:{...An.parameters?.docs,source:{originalSource:`badgesOnRows('light')`,...An.parameters?.docs?.source}}},jn.parameters={...jn.parameters,docs:{...jn.parameters?.docs,source:{originalSource:`badgesOnRows('dark')`,...jn.parameters?.docs?.source}}},Nn.parameters={...Nn.parameters,docs:{...Nn.parameters?.docs,source:{originalSource:`headerBand('light')`,...Nn.parameters?.docs?.source}}},Pn.parameters={...Pn.parameters,docs:{...Pn.parameters?.docs,source:{originalSource:`headerBand('dark')`,...Pn.parameters?.docs?.source}}},In.parameters={...In.parameters,docs:{...In.parameters?.docs,source:{originalSource:`toneBadgeInk('light')`,...In.parameters?.docs?.source}}},Ln.parameters={...Ln.parameters,docs:{...Ln.parameters?.docs,source:{originalSource:`toneBadgeInk('dark')`,...Ln.parameters?.docs?.source}}},Rn.parameters={...Rn.parameters,docs:{...Rn.parameters?.docs,source:{originalSource:`controlBorders('light')`,...Rn.parameters?.docs?.source},description:{story:"The light theme's `--input`, over the card and the header it sits on.",...Rn.parameters?.docs?.description}}},zn.parameters={...zn.parameters,docs:{...zn.parameters?.docs,source:{originalSource:`controlBorders('dark')`,...zn.parameters?.docs?.source},description:{story:"The same controls with the surface pinned dark, where the token is white at\nan opacity and carries the `bg-input/30` fill with it.",...zn.parameters?.docs?.description}}},Hn.parameters={...Hn.parameters,docs:{...Hn.parameters?.docs,source:{originalSource:`calloutTone('light', DisplayNeedsFixing, 'error')`,...Hn.parameters?.docs?.source}}},Un.parameters={...Un.parameters,docs:{...Un.parameters?.docs,source:{originalSource:`calloutTone('dark', DisplayNeedsFixing, 'error')`,...Un.parameters?.docs?.source}}},Wn.parameters={...Wn.parameters,docs:{...Wn.parameters?.docs,source:{originalSource:`calloutTone('light', DisplayTotalCoversThisPageOnly, 'warning')`,...Wn.parameters?.docs?.source}}},Gn.parameters={...Gn.parameters,docs:{...Gn.parameters?.docs,source:{originalSource:`calloutTone('dark', DisplayTotalCoversThisPageOnly, 'warning')`,...Gn.parameters?.docs?.source}}},Jn.parameters={...Jn.parameters,docs:{...Jn.parameters?.docs,source:{originalSource:`deleteActionContrast('light')`,...Jn.parameters?.docs?.source}}},Yn.parameters={...Yn.parameters,docs:{...Yn.parameters?.docs,source:{originalSource:`deleteActionContrast('dark')`,...Yn.parameters?.docs?.source}}},Zn.parameters={...Zn.parameters,docs:{...Zn.parameters?.docs,source:{originalSource:`{
  ...DisplayNarrowTitleBar,
  args: {
    ...DisplayNarrowTitleBar.args,
    withActions: true
  },
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');

    // The conditions have to be on screen to overflow: the band is folded on
    // a saved view, and the grid that pinned its tracks is inside it.
    await userEvent.click(canvas.getByRole('button', {
      name: new RegExp(\`^\${zhCN['label.filter.panel']}\`)
    }));
    await waitFor(() => expect(canvasElement.querySelector('[data-slot="filter-conditions"]')).not.toBeNull());

    // And the toolbar has to be carrying its heaviest row: a count, a way to
    // drop the selection, and the host's bulk action.
    await userEvent.click(canvas.getByLabelText(zhCN['label.record.select-all']));
    await canvas.findByRole('button', {
      name: '导出所选'
    });
    const surface = canvasElement.querySelector<HTMLElement>('.fve-root')!;
    const main = canvasElement.querySelector<HTMLElement>('main')!;
    const result = canvasElement.querySelector<HTMLElement>('[data-slot="result-block"]')!;
    await expect(main.scrollWidth).toBeLessThanOrEqual(main.clientWidth);
    await expect(result.scrollWidth).toBeLessThanOrEqual(result.clientWidth);

    // The table is the one thing allowed to scroll sideways — that is what a
    // frozen column is for — so it answers for where its own box ends and
    // the rows inside it are not walked into.
    const edge = surface.getBoundingClientRect().right;
    const spilling = descendantsOf(main).filter(node => node.closest('[data-slot="record-table"]') === null).filter(node => node.getBoundingClientRect().right > edge + 1).map(node => \`\${node.getAttribute('data-slot') ?? node.tagName} ends at \` + \`\${Math.round(node.getBoundingClientRect().right)} of \${Math.round(edge)}\`);
    await expect(spilling).toEqual([]);
  }
}`,...Zn.parameters?.docs?.source},description:{story:`The whole shell inside a phone, with nothing hanging off the side of it.

Measured at 341px of root and 309px of result card, four things were
painted outside the column they belong to: the pagination row could not
wrap, so Next's right edge was 365.6 against a card ending at 342, and
"4 records in all" broke over three lines with the Chinese "共 4 条记录"
split mid-word; the condition band's \`minmax(20rem, 1fr)\` pinned every
track to 320px, so a pill ended at 366 against an editor band ending at
342; and the toolbar's selection group could not wrap, so the host's bulk
action hung off the end of it.

The assertion is the general one rather than four specific ones: the main
column and the result block scroll no wider than they are, and nothing
under the surface ends past the surface's own right edge. The title bar is
the one part allowed an honest overflow once it runs out of irreducible
room — at this width it has not, because the audience tag is down to its
icon and Save to its own — so it is walked here like everything else.`,...Zn.parameters?.docs?.description}}},Qn.parameters={...Qn.parameters,docs:{...Qn.parameters?.docs,source:{originalSource:`{
  ...DisplayNarrowTitleBar,
  // The list is folded by the host, not by the shell's own measurement: the
  // widths below are the *toolbar's* subject, and a shell that follows the
  // column would put a 224px list back at 768 and take it away again a
  // frame later, so every number here would be measuring the sidebar.
  args: {
    ...DisplayNarrowTitleBar.args,
    withActions: true,
    collapsed: true
  },
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    const host = canvasElement.querySelector<HTMLElement>('[data-narrow-host]')!;

    // Nothing selected: no placeholder box, and the block still ends the bar.
    for (const width of [768, 375]) {
      host.style.width = \`\${width}px\`;
      await expect(canvasElement.querySelector('[data-slot="toolbar-selection"]')).toBeNull();
      await expect(lineCount(toolbarGroups(canvasElement)), \`\${width}px, nothing selected\`).toBeLessThanOrEqual(2);
      await expect(rightGroupEndsTheBar(canvasElement)).toBeLessThanOrEqual(1);
    }

    // And with a selection. At 768 the whole bar is one line; at 375 the
    // selection takes the first and the three groups wrap between the two
    // below it, because they measure 112 + 167 + 111 with two 8px gaps in a
    // 317px bar and no amount of wrapping fits 406 into 317. What they may
    // not do — and what they did — is scatter: the block stays a block, it
    // never takes more than two lines of its own, and it ends the bar.
    host.style.width = '768px';
    await userEvent.click(canvas.getByLabelText(zhCN['label.record.select-all']));
    await canvas.findByRole('button', {
      name: '导出所选'
    });
    await expect(lineCount(toolbarGroups(canvasElement)), '768px, four rows selected').toBeLessThanOrEqual(2);
    for (const width of [768, 375]) {
      host.style.width = \`\${width}px\`;
      await expect(lineCount(arrangementGroups(canvasElement)), \`\${width}px, four rows selected\`).toBeLessThanOrEqual(2);
      await expect(rightGroupEndsTheBar(canvasElement)).toBeLessThanOrEqual(1);
    }
    host.style.width = '375px';
  }
}`,...Qn.parameters?.docs?.source},description:{story:`The result toolbar wraps as groups, not as a spill.

It used to be \`[selection][flex-1 spacer][layout][columns/sort][refresh]\`,
and a spacer is the worst thing to wrap around: it took a line of its own
width, stranded the layout switch alone at the right of the first line,
dropped the arrange group to the left of the second and the refresh split
button to a third — 2 lines at 1280 with a selection, 3 at 768 and 3 at
375, where it stood 104px tall; with nothing selected the placeholder box
held 32px of nothing.

The three right-hand groups are one block now. With nothing selected the
bar is at most two lines at both widths, and at 768 it is one line even
with four rows picked. At 375 with a selection it is still three, and
honestly so: the three groups measure 112 + 167 + 111 with two 8px gaps,
and 406px does not go into a 317px bar however it wraps. Closing that
last line would mean taking the words off the toolbar's controls — the
sort button's summary among them, which \`ui/record.md\` says has to be
readable where it stands. What the block may not do, and did, is
scatter.`,...Qn.parameters?.docs?.description}}},$n.parameters={...$n.parameters,docs:{...$n.parameters?.docs,source:{originalSource:`{
  ...DisplayExportResult,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    await waitFor(() => expect(readColumn(table, '订单号')).toEqual(PENDING_BY_AMOUNT));

    // No menu anywhere: the toolbar's part in this is the one button.
    await userEvent.click(canvasElement.querySelector<HTMLElement>('[data-control="export"]')!);
    const unpicked = await within(document.body).findByRole('dialog');
    await expect(within(document.body).queryByRole('menu')).toBeNull();
    // Nothing picked, so there is no choice to draw — only what the file
    // will hold.
    await expect(within(unpicked).queryByRole('radio')).toBeNull();
    await expect(unpicked.textContent).toContain(say('label.export.rows', {
      count: 4
    }));
    await expect(unpicked.textContent).toContain(say('label.export.columns', {
      count: 4,
      names: ['订单号', '仓库', '状态', '金额'].join(zhCN['label.filter.join'])
    }));
    await userEvent.keyboard('{Escape}');
    await waitFor(() => expect(within(document.body).queryByRole('dialog')).toBeNull());

    // The picked scope exists only once something is picked (D4), and it is
    // the one the window opens on.
    await userEvent.click(canvas.getByLabelText(zhCN['label.record.select-all']));
    await userEvent.click(canvasElement.querySelector<HTMLElement>('[data-control="export"]')!);
    const picked = await within(document.body).findByRole('dialog');
    await expect(within(picked).getAllByRole('radio').map(radio => radio.getAttribute('aria-checked'))).toEqual(['true', 'false']);
    await expect(picked.textContent).toContain(say('label.export.selected', {
      count: 4
    }));
    // The one scope whose rows are not the ones on screen says so.
    await expect(picked.textContent).toContain(say('label.export.all', {
      count: 4
    }));
    await userEvent.keyboard('{Escape}');
  }
}`,...$n.parameters?.docs?.source},description:{story:`The export window: one button, one window, the whole journey (D14).

Nothing is actually exported here. The file itself — its name, its header
and every value in it — is asserted in jsdom, where the browser's half is
a stub (\`test/recordExportUi.test.tsx\`); a real click would hand this
browser a download for nothing. What only a browser can answer is what the
window says at each step, and that it says it in the language the data is
in.`,...$n.parameters?.docs?.description}}},er.parameters={...er.parameters,docs:{...er.parameters?.docs,source:{originalSource:`{
  ...DisplayExportRunning,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    await userEvent.click(canvasElement.querySelector<HTMLElement>('[data-control="export"]')!);
    const dialog = await within(document.body).findByRole('dialog');
    await userEvent.click(within(dialog).getByRole('button', {
      name: zhCN['label.export.confirm']
    }));
    const bar = await within(dialog).findByRole('progressbar', {
      name: zhCN['label.export.running']
    });
    await expect(bar.getAttribute('aria-valuemax')).toBe('4');
    await expect(within(dialog).getByRole('status').textContent).toBe(say('label.export.progress', {
      fetched: 0,
      total: 4
    }));
    await userEvent.keyboard('{Escape}');
    await waitFor(() => expect(within(document.body).queryByRole('dialog')).toBeNull());
    // A cancel says nothing: it is the answer the user gave.
    await expect(canvasElement.querySelector('[data-slot="status-strip"]')).toBeNull();
  }
}`,...er.parameters?.docs?.source},description:{story:`The window while the pages come in, and Escape as the answer it is.

The menu this replaced had to refuse both Escape and a click outside,
because the cancel lived inside it; a window may be dismissed, and being
dismissed *is* the cancel.`,...er.parameters?.docs?.description}}},tr.parameters={...tr.parameters,docs:{...tr.parameters?.docs,source:{originalSource:`{
  ...DisplayExportCapped,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    await userEvent.click(canvasElement.querySelector<HTMLElement>('[data-control="export"]')!);
    const dialog = await within(document.body).findByRole('dialog');
    await expect(dialog.textContent).toContain(say('label.export.over-limit', {
      max: 2
    }));
    await userEvent.click(within(dialog).getByRole('button', {
      name: zhCN['label.export.confirm']
    }));
    await within(dialog).findByText(say('label.export.done', {
      count: 2
    }));
    await expect(dialog.textContent).toContain(say('label.export.done-capped', {
      max: 2,
      total: 4
    }));
  }
}`,...tr.parameters?.docs?.source},description:{story:`The ceiling, said before the button and again after the file: the four
orders the saved condition matches, against a ceiling of two.`,...tr.parameters?.docs?.description}}},nr.parameters={...nr.parameters,docs:{...nr.parameters?.docs,source:{originalSource:`{
  ...DisplayExportFailed,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    await userEvent.click(canvasElement.querySelector<HTMLElement>('[data-control="export"]')!);
    const dialog = await within(document.body).findByRole('dialog');
    await userEvent.click(within(dialog).getByRole('button', {
      name: zhCN['label.export.confirm']
    }));
    await within(dialog).findByText(/导出失败/);
    // The way out is in the window, not back through the toolbar.
    await expect(within(dialog).getByRole('button', {
      name: zhCN['label.export.retry']
    })).toBeTruthy();
    // And nothing was said above the rows about it.
    await expect(canvasElement.querySelector('[data-slot="status-strip"]')).toBeNull();
  }
}`,...nr.parameters?.docs?.source},description:{story:`A failed export, reported where it happened and offered again from there.`,...nr.parameters?.docs?.description}}},rr.parameters={...rr.parameters,docs:{...rr.parameters?.docs,source:{originalSource:`{
  ...DisplayWithData,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    const doc = canvasElement.ownerDocument;
    const toggle = canvas.getByRole('button', {
      name: zhCN['label.workbench.expand-view']
    });
    await userEvent.hover(toggle);
    await waitFor(() =>
    // One message, two channels: the tooltip is the accessible name said
    // out loud to a pointer, never a second wording of it.
    expect(tooltipOn(doc)?.textContent).toBe(toggle.getAttribute('aria-label')));
    await expect(tooltipOn(doc)).toHaveTextContent(zhCN['label.workbench.expand-view']);

    // The label follows the state. It is the same button — what pressing it
    // does has changed, so what it is called changes with it.
    await userEvent.click(toggle);
    await waitFor(() => expect(toggle).toHaveAttribute('aria-expanded', 'true'));
    await userEvent.unhover(toggle);
    await waitFor(() => expect(tooltipOn(doc)).toBeNull());
    await userEvent.hover(toggle);
    await waitFor(() => expect(tooltipOn(doc)?.textContent).toBe(zhCN['label.workbench.collapse-view']));

    // Back out the way it came, so the story leaves the screen as it found
    // it — \`FillTheScreen\` is where the Escape route is held to account.
    await userEvent.click(toggle);
    await waitFor(() => expect(toggle).toHaveAttribute('aria-expanded', 'false'));
  }
}`,...rr.parameters?.docs?.source},description:{story:`D12 puts every function into an icon button, so hovering one is how a
pointer learns what it is. The name the reader hears and the label the
pointer sees are one string (\`src/ui/IconButton.tsx\`), and this asks the
question a jsdom suite cannot: is it actually on screen, and does it say
what the button says *now* rather than what it said before it was pressed?`,...rr.parameters?.docs?.description}}},ir.parameters={...ir.parameters,docs:{...ir.parameters?.docs,source:{originalSource:`{
  ...DisplayWithData,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    const doc = canvasElement.ownerDocument;
    const row = listItem(canvasElement, '待出库订单');
    const kind = row.querySelector<HTMLElement>('[data-slot="view-kind"]')!;
    // The premise: the glyph itself still refuses the pointer, which is why
    // it cannot be the trigger and the wrapper is.
    await expect(getComputedStyle(kind.querySelector('svg')!).pointerEvents).toBe('none');
    await expect(getComputedStyle(kind).pointerEvents).not.toBe('none');
    await userEvent.hover(kind);
    await waitFor(() => expect(tooltipOn(doc)).toHaveTextContent(zhCN['label.kind.record']));

    // And the row is still called by the view it opens, not by its kind:
    // a list where every name starts with the same two syllables is a list
    // that has stopped distinguishing its items.
    await expect(row).toHaveTextContent('待出库订单');
    await expect(row.textContent).not.toContain(zhCN['label.kind.record']);
    await userEvent.unhover(kind);
    await waitFor(() => expect(tooltipOn(doc)).toBeNull());
  }
}`,...ir.parameters?.docs?.source},description:{story:"侧栏每一行的种类图标，指上去要说得出自己是什么（D-2）。\n\n从前 `TooltipTrigger` 直接挂在那个 `<svg>` 上，而它在 `Button` 里——\n`[&_svg]:pointer-events-none` 让这个 svg 根本收不到指针，标签永远打不开：\n源码里写着的名字，屏幕上谁也拿不到。现在挂在外面那层 `span` 上，指针落在\n图标上会穿到父元素，于是它就是触发器。jsdom 量不出这一条——`pointer-events`\n要真的做命中测试才算数——所以钉在浏览器里。",...ir.parameters?.docs?.description}}},ar.parameters={...ar.parameters,docs:{...ar.parameters?.docs,source:{originalSource:`{
  ...DisplayWithData,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    const doc = canvasElement.ownerDocument;
    const body = within(doc.body);
    const chevron = canvasElement.querySelector<HTMLElement>('[data-slot="refresh-interval"]')!;
    await userEvent.hover(chevron);
    await waitFor(() => expect(tooltipOn(doc)).toHaveTextContent(zhCN['label.refresh.auto']));
    await userEvent.click(chevron);
    const menu = await body.findByRole('menu');
    await userEvent.hover(chevron);
    // A beat for the tooltip to do whatever it is going to do: with the
    // provider's zero delay, anything it has in mind has happened by now.
    await new Promise(settle => setTimeout(settle, 200));
    const tip = tooltipOn(doc);
    if (tip) await expect(overlapping(tip, menu)).toBe(false);
    for (const item of within(menu).getAllByRole('menuitemradio')) await expect(inFrontOf(item)).toBe(true);
    await userEvent.keyboard('{Escape}');
    await waitFor(() => expect(body.queryByRole('menu')).toBeNull());
  }
}`,...ar.parameters?.docs?.source},description:{story:`A tooltip round a menu's trigger must not fight the menu.

The two hang off one button and the pointer that opened the menu is still
sitting on it, which is the one arrangement where a tooltip can end up
over the thing it was meant to explain. What is held here is the part the
user can see: whether the label is still up or not, every option is the
thing a click at its middle reaches, and nothing black is lying over the
list. Whether Base UI keeps the tooltip shut after the click or lets the
resting pointer bring it back is its own business, and it does both
depending on how the press is timed — the label sits above the button,
where the menu is not, so neither way costs the user anything.`,...ar.parameters?.docs?.description}}},or.parameters={...or.parameters,docs:{...or.parameters?.docs,source:{originalSource:`{
  ...DisplayWideTable,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    const nameOf = (label: HTMLElement) => label.closest('th')!.getAttribute('data-field');
    const labelFor = (field: string) => table.querySelector<HTMLElement>(\`thead th[data-field="\${field}"] [data-slot="column-label"]\`)!;

    // Nothing is cut before a reader asks for it to be: every header holds
    // its own name at the width the columns settle on (P-11).
    const labels = [...table.querySelectorAll<HTMLElement>('thead [data-slot="column-label"]')];
    await expect(labels.filter(label => label.scrollWidth > label.offsetWidth).map(nameOf)).toEqual([]);

    // Now take one down to the floor, which is the reader's own doing and
    // the one width at which a name has nowhere to go.
    const edge = canvas.getByRole('separator', {
      name: say('label.columns.resize', {
        field: '订单号'
      })
    });
    const head = edge.closest('th')!;
    await dragEdgeBy(edge, 48 - head.getBoundingClientRect().width);
    await waitFor(() => expect(labelFor('orderNo').scrollWidth).toBeGreaterThan(labelFor('orderNo').offsetWidth));
    const name = labelFor('orderNo');
    const whole = name.textContent?.trim();
    // Cut on screen and whole in the DOM, which is what a reader hears.
    await expect(name.scrollWidth).toBeGreaterThan(name.offsetWidth);
    await expect(whole).toBeTruthy();
    // And not the one only a mouse can open.
    await expect(name).not.toHaveAttribute('title');
    await userEvent.hover(name);
    const tip = await waitFor(() => {
      const found = document.body.querySelector<HTMLElement>('[data-slot="tooltip-content"]');
      expect(found).not.toBeNull();
      return found!;
    });
    await expect(tip.textContent?.trim()).toBe(whole);
    // Themed from \`ui/popups.tsx\` like every other popup here: it is drawn
    // outside the surface, where the tokens do not reach on their own.
    await expect(tip.className).toContain('fve-root');
    await userEvent.unhover(name);
  }
}`,...or.parameters?.docs?.source},description:{story:`F-15: a column name the header cannot hold gives the whole of it back on
hover.

Measured in Chromium on this fixture at 1280: 运单号 was drawn in 17px of
the 37px it asks for — «运..» — 订单号 in 21px and 件数 in 16px of 25,
and the span carried neither a \`title\` nor a tooltip, so the rest of the
name was reachable by no input device at all. It is a \`Tooltip\` rather
than the native \`title\` (D16-6): \`title\` opens for a mouse and for nothing
else, and these headers are buttons a keyboard reaches. Only a real
browser truncates, so the pixels are checked here and the structure in
jsdom (\`test/recordTable.test.tsx\`).

**Those three names now fit** (P-11): the sort button was capped at the
cell's content box while its negative margins reach the padding box, so
every header was 16px short of the room its own cell had. What is left is
the case the tooltip is actually for — a column the *user* narrowed, down
to the 48px floor the handle stops at, where the name cannot fit however
the cell is measured. So the premise is made rather than found.`,...or.parameters?.docs?.description}}},$.parameters={...$.parameters,docs:{...$.parameters?.docs,source:{originalSource:`{
  ...DisplayWithData,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    const doc = canvasElement.ownerDocument;
    const surface = canvasElement.querySelector<HTMLElement>('[data-slot="view-surface"]')!;

    // 本包发的那一条：条件是 reduce，作用域点到两个边界。宿主页面与 vendored
    // 的 \`.shimmer\` 各有各的一条，按边界认出自己这条。
    const reduced = [...doc.styleSheets].flatMap(sheet => {
      try {
        return [...sheet.cssRules];
      } catch {
        return [];
      }
    }).filter((rule): rule is CSSMediaRule => rule instanceof CSSMediaRule && rule.conditionText.includes('prefers-reduced-motion')).flatMap(media => [...media.cssRules]).filter((rule): rule is CSSStyleRule => rule instanceof CSSStyleRule &&
    // 两个边界**本身**各是它的一个选择器分支，而不是某个类恰好落在边界
    // 里——vendored 的 \`.shimmer\` 也有一条 reduce 规则，作用域同样点到
    // 两个边界，说的却只是它自己那一个类。
    ['.fve-root', '.fve-tokens'].every(boundary => rule.selectorText.split(',').some(part => part.trim() === boundary)));
    // 一条，或者同一条被加载了不止一次（dev 的 HMR 与测试进程各挂一份），所以
    // 数的是「有」而不是「恰好一份」，而每一份都得说同一件事。
    await expect(reduced.length, '包级的 reduce 规则').toBeGreaterThan(0);
    const rule = reduced[0];

    // 削到察觉不到，而不是削到零：Base UI 的弹层靠自己退场动画结束的那一下
    // 卸载，时长拿掉就没有那一下了。
    const declared = (one: CSSStyleRule, property: string) => [one.style.getPropertyValue(property), one.style.getPropertyPriority(property)];
    for (const one of reduced) {
      await expect(declared(one, 'animation-duration')).toEqual(['0.01ms', 'important']);
      await expect(declared(one, 'transition-duration')).toEqual(['0.01ms', 'important']);
    }

    // 真实的弹层：它匹配这条规则，而它此刻的动画正是规则要削的那 100ms。
    await userEvent.click(canvasElement.querySelector<HTMLElement>('[data-slot="refresh-interval"]')!);
    const menu = await within(doc.body).findByRole('menu');
    const popup = menu.closest<HTMLElement>('[data-slot="dropdown-menu-content"]')!;
    await expect(popup.matches(rule.selectorText)).toBe(true);
    await expect(getComputedStyle(popup).animationDuration).toBe('0.1s');
    await userEvent.keyboard('{Escape}');
    await waitFor(() => expect(within(doc.body).queryByRole('menu')).toBeNull());

    // 会说话的那两种不让。这一屏已经查完、也没在写，spinner 与 skeleton 都不
    // 在场（它们分别是 \`RefreshControl\` 与 \`ViewList\` 的在途状态），所以问的是
    // 选择器本身：探针戴上上游给它们的 slot，放进这块面里问一句再拿走。
    const excludes = (slot: string) => {
      const probe = doc.createElement('div');
      probe.dataset.slot = slot;
      surface.append(probe);
      const matched = probe.matches(rule.selectorText);
      probe.remove();
      return matched;
    };
    await expect({
      spinner: excludes('spinner'),
      skeleton: excludes('skeleton'),
      badge: excludes('badge')
    }).toEqual({
      spinner: false,
      skeleton: false,
      badge: true
    });
  }
}`,...$.parameters?.docs?.source},description:{story:"这块面的动效让给 `prefers-reduced-motion: reduce`，而会说话的那两种动画不让。\n\n屏幕上会动的东西没有一件是调用处写的：弹层由 vendored 组件带着\n`data-open:animate-in zoom-in-95 slide-in-from-top-2` 进场，对话框带着遮罩\n淡入，按钮、徽章与行普遍带 `transition-all`——评审当时量到菜单弹层\n`animation-name: enter`、`animation-duration: 0.1s`，中途 `transform` 缩在\n0.986、`opacity` 0.727。所以让步只能在主题里做一次（`styles.css`，与两处\nvendored 字号钉在同一个地方、同一个理由），而不是去改三百处 class。\n\n**reduce 不等于 remove**：spinner 说的是「还在写／还在查」，skeleton 的脉动\n说的是「屏幕上这些还不是数据」；停掉它们是把「进行中」画成「卡住了」，所以\n这两个 slot 被排除在外。\n\n媒体查询本身在故事里开不动（Playwright 的 `reducedMotion` 只在测试进程里有，\n浏览器矩阵也不是每种都给得出），所以这里量的是**规则本身**：它在不在、作用\n域有没有同时点到两个边界、屏幕上真实的弹层匹不匹配它、被排除的那两个 slot\n匹不匹配，以及它此刻要削掉的是多长的一段动画。",...$.parameters?.docs?.description}}},cr.parameters={...cr.parameters,docs:{...cr.parameters?.docs,source:{originalSource:`outlineBadgeEdges('light')`,...cr.parameters?.docs?.source}}},lr.parameters={...lr.parameters,docs:{...lr.parameters?.docs,source:{originalSource:`outlineBadgeEdges('dark')`,...lr.parameters?.docs?.source}}}})))()}dr();export{ar as AMenuIsNotCoveredByItsOwnTooltip,or as ATruncatedColumnNameIsOneHoverAway,ir as AViewRowSaysItsKindOnHover,_n as AutoRefresh,jn as BadgesOnRowsInDarkTheme,An as BadgesOnRowsInLightTheme,I as BlockSpacing,J as BulkOutcomeOutlivesTheSelection,jt as CannotOpen,wt as CardsAreSetUpFromTheSameButton,G as CardsKeepTheirHeight,L as CellFamily,en as CollapseAndSwitch,Lt as ColumnResize,U as ColumnsKeepTheirWidthAndRowsFillTheFrame,zn as ControlBordersInDarkTheme,Rn as ControlBordersInLightTheme,z as CopyADocumentNumber,Dn as DarkHairlines,Xt as DefaultViewWearsTheStar,Yn as DeleteActionContrastInDarkTheme,Jn as DeleteActionContrastInLightTheme,Ut as DeleteConflictAsksTwice,B as EarliestAndLatest,tn as EditorToggleAndModes,R as ElementColumns,Ct as EmptyResult,Mt as English,Un as ErrorCalloutInDarkTheme,Hn as ErrorCalloutInLightTheme,tr as ExportCappedWindow,nr as ExportFailedWindow,er as ExportRunningWindow,$n as ExportWindow,on as FillTheScreen,cn as FillTheScreenInScaledHost,sn as FillTheScreenInTransformedHost,gn as FillTheScreenWithPopups,$t as FillingTheScreenFoldsTheList,En as FocusIndicatorsInDarkTheme,Tn as FocusIndicatorsInLightTheme,W as FooterStaysAtTheBottom,Pn as HeaderBandInDarkTheme,Nn as HeaderBandInLightTheme,F as HeaderSortWaitsForApply,K as HeldAtItsFloor,Ft as HiddenColumnKeepsItsPlace,rr as IconButtonsSayTheirNameOnHover,Tt as Loading,Z as ManageViews,Zn as NarrowColumnHoldsTheWidth,hn as NarrowHostColumnSettings,Ot as NeedsFixing,At as NewView,X as OnlyApplyIsPrimary,kt as Opening,lr as OutlineBadgeEdgesInDarkTheme,cr as OutlineBadgeEdgesInLightTheme,V as Paged,H as PagedWindow,an as PickSeveralFields,un as PinnedEdges,pn as PinnedGroupCapped,xn as PopupsOverRaisedHostLayer,rn as QueryAnnouncedInTheResult,Et as QueryFailed,$ as ReducedMotionIsHonoured,Ht as RenameConflictedInTheManager,ln as RenderFailure,Rt as SaveConflictKeepsMine,zt as SaveConflictTakesTheirs,Vt as SaveRefusedByTheStore,Bt as SaveResultNeverCameBack,Y as ShiftSelectsARange,Yt as SidebarIsANavigationColumn,It as SortEntriesPointerDrag,Nt as TableSettings,Pt as TableSettingsPointerDrag,Zt as TheListFoldsItselfAwayWhereItCannotFit,Qt as TheListFollowsTheColumnItIsGiven,Ln as ToneBadgeInkInDarkTheme,In as ToneBadgeInkInLightTheme,nn as ToolbarAndFoldByKeyboard,Qn as ToolbarWrapsAsGroups,Dt as TotalCoversThisPageOnly,Jt as TypeScaleIsThreeRungs,Gn as WarningCalloutInDarkTheme,Wn as WarningCalloutInLightTheme,fn as WideTable,mn as WideTableColumnSettings,q as WithActions,St as WithData,ur as __namedExportsOrder,bt as default};
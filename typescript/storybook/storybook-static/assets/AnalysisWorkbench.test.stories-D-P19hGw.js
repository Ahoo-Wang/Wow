import{n as e}from"./rolldown-runtime-DkW27tQK.js";import{t}from"./jsx-runtime-DeHZSEgm.js";import{Es as n,Nf as r,R as i,Ts as a,a as o,h as s,ws as c,z as l}from"./styles-Dpj2y9Rj.js";import{t as u}from"./src-DsG2EjN4.js";import{A as d,C as f,E as p,O as ee,S as m,T as h,_ as g,a as te,b as ne,c as re,d as ie,f as ae,g as oe,h as se,i as ce,j as le,k as ue,l as de,m as fe,n as pe,o as me,p as he,s as ge,u as _e,v as ve,w as ye,x as be,y as xe}from"./AnalysisWorkbench.stories-DyF9m040.js";import{a as _,f as Se,h as Ce,l as we,m as Te,n as v,o as Ee,p as De,r as Oe,t as ke,u as Ae}from"./chartDom-C6NvYOT8.js";import{a as y,c as je,i as Me,n as Ne,o as Pe,r as b,t as Fe}from"./readTable-DOunjEkP.js";async function Ie(e,t){for(let n of e){let e=n.getBoundingClientRect();await S(e.left).toBeGreaterThanOrEqual(t.left-1),await S(e.right).toBeLessThanOrEqual(t.right+1),await S(e.top).toBeGreaterThanOrEqual(t.top-1),await S(e.bottom).toBeLessThanOrEqual(t.bottom+1)}}function Le(e){let t=v(e,`bottom`).map(e=>e.getBoundingClientRect()),n=Math.max(...t.map(e=>e.bottom)),r=Oe(e).map(e=>e.getBoundingClientRect()).find(e=>e.top>=n-1);if(!r)throw Error(`no title under the ticks`);return r.top-n}function Re(e,t){let n=e.findIndex((e,n)=>e!==t[n]);return n!==-1&&e[n]>t[n]}async function ze(e){return T(()=>{let t=e.querySelector(`[data-slot="chart-reading"] table`);if(!t)throw Error(`指标卡旁边没有读屏表`);let n=[...t.tBodies[0].rows].map(e=>[e.cells[0]?.textContent??``,e.cells[1]?.textContent??``]).filter(([e])=>Qe(e).length===3);return S(n.length).toBeGreaterThan(10),n})}async function Be(e,t,n){let r=await tt();return await S(r).toHaveTextContent(RegExp(`^${nt}$`)),await S(E(e).getByRole(`heading`,{level:2,name:t})).toBeVisible(),await T(()=>S(E(it(e)).getByText(n)).toBeVisible()),await S(E(e).getAllByText(n).filter(e=>e.closest(`h2`)===null)).toHaveLength(1),await S(e.querySelector(`[data-slot="editor-toggle"] [aria-expanded="true"]`)).toBeNull(),r}async function Ve(e){return await Promise.all(e.getAnimations().map(e=>e.finished)),e.getBoundingClientRect()}async function He(e){let t=(await e.findAllByRole(`status`)).find(e=>e.getAttribute(`data-slot`)===`status-strip`);if(!t)throw Error(`no status strip`);return t}function Ue(e,t){let n=e,r=Ne(e,t);return[n.tHead.rows[0].cells[r],...[...n.tBodies[0].rows].map(e=>e.cells[r]),...n.tFoot?[n.tFoot.rows[0].cells[r]]:[]]}function We(e){let t=document.createRange();return t.selectNodeContents(e.querySelector(`[data-slot="column-label"]`)??e),e.getBoundingClientRect().right-parseFloat(getComputedStyle(e).paddingRight)-t.getBoundingClientRect().right}function Ge(e){return E(e.querySelector(`[data-slot="editor-toggle"]`)).getByRole(`button`)}async function x(e){return await w.click(Ge(e)),await T(()=>S(kt(e)).not.toBeNull()),kt(e)}async function Ke(e){await E(e).findByRole(`table`);let t=await x(e),n=t=>e.querySelector(`[data-slot="${t}"]`),r=t.querySelector(`[data-slot="analysis-tray-slots"]`);return{main:e.querySelector(`.fve-root > main`),band:n(`editor-band`),slots:r,order:n(`analysis-order`),footer:n(`analysis-tray-actions`),result:n(`result-block`)}}async function qe(e,t,n=!0){let r=[...e.querySelectorAll(`[data-slot="chart-tile"]`)],i=new Map;for(let e of r){let t=Math.round(e.getBoundingClientRect().top);i.set(t,[...i.get(t)??[],e])}for(let e of i.values())await S(new Set(e.map(e=>Math.round(e.getBoundingClientRect().height))).size).toBe(1);for(let e of r){let t=e.getBoundingClientRect(),n=[...e.children].filter(e=>e.getAttribute(`data-slot`)!==`chart-recommended`).map(e=>e.getBoundingClientRect()),r=Math.min(...n.map(e=>e.top))-t.top,i=t.bottom-Math.max(...n.map(e=>e.bottom));await S(Math.abs(r-i)).toBeLessThanOrEqual(2)}for(let e of r)await S(e.querySelector(`button`)).toBeNull();let o=Q(e,t),s=o.querySelector(`[data-slot="chart-recommended"]`);if(s){let e=s.getBoundingClientRect(),t=o.getBoundingClientRect(),i=o.querySelector(`svg`).getBoundingClientRect(),a=document.getElementById(o.getAttribute(`aria-labelledby`)).getBoundingClientRect();await S(Ht(e,a)).toBe(!1),await S(Ht(e,i)).toBe(!1);for(let t of r.filter(e=>e!==o))await S(Ht(e,t.getBoundingClientRect())).toBe(!1);n&&(await S(e.left).toBeGreaterThanOrEqual(t.left-.5),await S(e.right).toBeLessThanOrEqual(t.right+.5))}let c=e.querySelectorAll(`[data-slot="chart-options-open"]`);await S(c).toHaveLength(1);let u=c[0];await S(u).toBeVisible(),await S(u).toHaveAccessibleName(a(l,`label.chart.options`,{name:t===`table`?l[`label.layout.table`]:l[`label.chart.type.${t}`]}));let d=u.getBoundingClientRect(),f=e.querySelector(`[role="radiogroup"]`).getBoundingClientRect(),p=e.getBoundingClientRect();await S(d.height).toBeGreaterThanOrEqual(28),await S(d.left).toBeGreaterThanOrEqual(p.left),await S(d.right).toBeLessThanOrEqual(p.right),await S(Math.abs(d.width-f.width)).toBeLessThan(1),await S(d.top).toBeGreaterThanOrEqual(f.bottom);for(let e of r)await S(Ht(d,e.getBoundingClientRect())).toBe(!1);s&&await S(Ht(d,s.getBoundingClientRect())).toBe(!1)}var Je,S,C,w,T,E,Ye,Xe,D,O,Ze,k,A,j,M,N,P,F,I,L,R,z,B,V,H,Qe,$e,U,W,G,K,q,et,J,tt,nt,Y,rt,it,at,ot,st,ct,lt,ut,dt,ft,pt,mt,ht,gt,_t,vt,yt,bt,xt,St,Ct,wt,X,Tt,Et,Dt,Ot,kt,At,jt,Mt,Nt,Pt,Ft,Z,It,Lt,Rt,zt,Bt,Vt,Ht,Q,Ut,Wt,Gt,$,Kt;function qt(){return(qt=e((()=>{n(),i(),d(),u(),s(),Me(),we(),Je=t(),{expect:S,screen:C,userEvent:w,waitFor:T,within:E}=__STORYBOOK_MODULE_TEST__,Ye=e=>{let t=e.map(e=>e.getBoundingClientRect());return t.every((e,n)=>t.slice(n+1).every(t=>!Se(e,t)))},Xe={...le,title:`View Engine/分析视图/分析工作台/回归`,tags:[`!dev`,`!autodocs`,`test`],parameters:{...le.parameters}},D=a(l,`label.summary.of`,{field:`金额`,fn:l[`label.summary.fn.SUM`]}),O=l[`label.analysis.row-count`],Ze=r(`oklch`),k=e=>Ee(e),A=e=>{let t=Ae(e);return Te(e).map((e,n)=>({name:t[n]??null,fill:e.getAttribute(`fill`),drawn:(e.getAttribute(`d`)??``).length>0}))},j={...pe,play:async({canvasElement:e})=>{await T(()=>S(k(e)).toHaveLength(4)),await T(()=>S(v(e,`left`).map(e=>e.textContent).join(` `)).toContain(`¥`))}},M={...g,args:{...g.args,layout:`chart`},play:async({canvasElement:e})=>{await _(e),await T(()=>S(k(e).length).toBeGreaterThan(0));let t=await T(()=>{let t=v(e,`left`).map(e=>(e.textContent??``).trim());return S(t.length).toBeGreaterThan(1),t});await S(t.every(e=>/^\d+$/.test(e))).toBe(!0),await S(new Set(t).size).toBe(t.length)}},N={...pe,play:async({canvasElement:e})=>{await T(()=>S(k(e)).toHaveLength(4));let t=e.querySelector(`[data-slot="analysis-caption"]`);await S(t.textContent).toMatch(/^正在显示 4 组，耗时 <?[\d.]+ 秒$/);let n=t.parentElement;await S(n.dataset.slot).toBe(`result-block`),await S(n.lastElementChild).toBe(t),await S(Math.abs(t.getBoundingClientRect().bottom-n.getBoundingClientRect().bottom)).toBeLessThanOrEqual(1)}},P={...pe,play:async({canvasElement:e})=>{await _(e),await T(()=>S(k(e)).toHaveLength(4));let t=e.querySelector(`[data-slot="chart-plot"] svg`).getBoundingClientRect(),n=e.querySelector(`[data-slot="result-block"]`).getBoundingClientRect();await S(t.left-n.left).toBeGreaterThanOrEqual(15),await S(n.right-t.right).toBeGreaterThanOrEqual(15);let r=ke(e);await S(r.length).toBeGreaterThan(0),await S(Ye(r)).toBe(!0);for(let e of r){let n=e.getBoundingClientRect();await S(n.left).toBeGreaterThanOrEqual(t.left-1),await S(n.right).toBeLessThanOrEqual(t.right+1)}}},F={...oe,play:async({canvasElement:e})=>{await _(e),await T(()=>S(k(e)).toHaveLength(4));let t=await T(()=>{let t=Ce(e);return S(t).toHaveLength(4),t}),n=e.querySelector(`[data-slot="chart-plot"] svg`).getBoundingClientRect();await Ie(t,n),await Ie(ke(e),n);let r=k(e).map(e=>e.getBoundingClientRect()).reduce((e,t)=>t.right>e.right?t:e),i=t.map(e=>e.getBoundingClientRect()).find(e=>e.top<r.bottom&&e.bottom>r.top);await S(i.left).toBeGreaterThanOrEqual(r.right)}},I={...pe,play:async({canvasElement:e})=>{await _(e),await T(()=>S(k(e)).toHaveLength(4));let t=v(e,`bottom`);await S(t).toHaveLength(4),await S(t.every(e=>e.getBoundingClientRect().height<20)).toBe(!0),await T(()=>S(Le(e)).toBeGreaterThan(10))}},L={...ie,play:async({canvasElement:e})=>{let t=E(e);await w.click(await t.findByRole(`button`,{name:l[`label.layout.chart`]})),await _(e),await w.click(t.getByRole(`button`,{name:l[`label.analysis.visualize`]})),await w.click(await T(()=>{let t=e.querySelector(`[data-slot="chart-options-open"]`);if(!t)throw Error(`没有选项按钮`);return t})),await w.click(await t.findByRole(`tab`,{name:l[`label.chart.tab.display`]})),await w.click(t.getByRole(`checkbox`,{name:l[`label.chart.horizontal`]})),await T(()=>S(e.querySelector(`[data-slot="chart"]`)).toHaveAttribute(`data-orientation`,`vertical`)),await _(e),await T(()=>S(k(e)).toHaveLength(9)),await T(()=>S(v(e,`bottom`).some(e=>e.getBoundingClientRect().height>30)).toBe(!0)),await T(()=>S(Le(e)).toBeGreaterThan(10));let n=e.querySelector(`[data-slot="chart-plot"] svg`).getBoundingClientRect();await Ie(ke(e),n)}},R={...ue,play:async({canvasElement:e})=>{await _(e),await T(()=>S(k(e).length).toBeGreaterThan(10));let t=await T(()=>{let t=Ce(e);return S(t.length).toBeGreaterThan(5),t});await S(Ye(t)).toBe(!0);let n=e.querySelector(`[data-slot="chart-plot"] svg`).getBoundingClientRect();for(let e of t){let t=e.getBoundingClientRect();await S(t.left).toBeGreaterThanOrEqual(n.left-1),await S(t.right).toBeLessThanOrEqual(n.right+1),await S(t.top).toBeGreaterThanOrEqual(n.top-1)}await S(t.every(e=>/^\d+$/.test(e.textContent??``))).toBe(!0)}},z={...ve,play:async({canvasElement:e})=>{await _(e);let t=await T(()=>{let t=Ee(e);return S(t).toHaveLength(8),t.map(e=>e.getBoundingClientRect())}),n=v(e,`left`).map(e=>e.getBoundingClientRect()),r=v(e,`right`).map(e=>e.getBoundingClientRect()),i=Math.min(...t.map(e=>e.left)),a=Math.max(...t.map(e=>e.right));await S(i-Math.max(...n.map(e=>e.right))).toBeGreaterThan(20),await S(Math.min(...r.map(e=>e.left))-a).toBeGreaterThan(20),await S(Oe(e).map(e=>e.textContent)).toEqual(S.arrayContaining([D,O]))}},B={...se,play:async({canvasElement:e})=>{await _(e);let t=e.querySelector(`[data-slot="chart"][data-chart="heatmap"]`),n=await T(()=>{let e=[...t.querySelectorAll(`[data-slot="chart-plot"] svg path`)].filter(e=>(e.getAttribute(`fill`)??``).startsWith(`rgb`)&&Number(e.getAttribute(`fill-opacity`)??1)>0);return S(e.length).toBe(Number(t.getAttribute(`data-marks`))),e.map(e=>e.getBoundingClientRect())}),r=t.querySelector(`[data-slot="chart-plot"]`).getBoundingClientRect(),i=Math.max(...n.map(e=>e.right))-Math.min(...n.map(e=>e.left));await S(i/r.width).toBeGreaterThan(.6),await S(Ye(Ce(t))).toBe(!0),await S(Ce(t).length).toBeGreaterThan(0)}},V={...be,play:async({canvasElement:e})=>{await _(e),await T(()=>S(k(e)).toHaveLength(1));let[t]=k(e);await S(t.getBoundingClientRect().width).toBeLessThanOrEqual(48.5)}},H={...he,play:async({canvasElement:e})=>{await T(()=>S(k(e)).toHaveLength(4)),await _(e);let[t]=k(e),n=t.getBoundingClientRect();t.dispatchEvent(new MouseEvent(`mousemove`,{bubbles:!0,clientX:n.left+n.width/2,clientY:n.top+n.height/2}));let r=()=>e.querySelector(`[data-slot="chart-tooltip"]`);await T(()=>S(r()).toBeVisible()),De(t);let i=await J(),a=await Ve(i);await T(()=>S(r()).not.toBeVisible()),t.dispatchEvent(new MouseEvent(`mousemove`,{bubbles:!0,clientX:n.left+n.width/2+1,clientY:n.top+n.height/2})),await new Promise(e=>setTimeout(e,300)),await S(r()).not.toBeVisible();let o=n.left+n.width/2,s=n.top+n.height/2,c=(e,t)=>Math.abs(e-t)<24;await S(c(a.left,o)||c(a.right,o)).toBe(!0),await S(c(a.top,s)||c(a.bottom,s)).toBe(!0),await S(E(i).getAllByRole(`menuitem`).map(e=>e.textContent)).toContain(l[`label.drill.records`]),await w.keyboard(`{Escape}`)}},Qe=e=>(e.match(/\d+/g)??[]).map(Number),$e=e=>e.every((t,n)=>n===0||Re(t,e[n-1])),U={...me,play:async({canvasElement:e})=>{await _(e),await T(()=>S(k(e).length).toBeGreaterThan(10));let t=await T(()=>{let t=v(e,`bottom`);return S(t.length).toBeGreaterThan(2),t.sort((e,t)=>e.getBoundingClientRect().left-t.getBoundingClientRect().left)}),n=t.map(e=>e.textContent??``);await S(n[0]).toMatch(/^\d{4}年\d{1,2}月\d{1,2}日$/),await S(n.slice(1).every(e=>/^\d{1,2}月\d{1,2}日$/.test(e))).toBe(!0),await S(t.every(e=>e.getBoundingClientRect().height<20)).toBe(!0);let[r]=Qe(n[0]),i=n.map(e=>{let t=Qe(e);return t.length===3?t:[r,...t]});await S($e(i)).toBe(!0),await w.click(E(e).getByRole(`button`,{name:l[`label.layout.table`]}));let o=await b(e),s=y(o,a(l,`label.analysis.dated.DAY`,{field:`创建时间`})).map(Qe);await S(s.length).toBeGreaterThan(10),await S($e([...s].reverse())).toBe(!0)}},W={...re,play:async({canvasElement:e})=>{let t=await T(()=>{let t=e.querySelector(`[data-slot="chart-reading"] table`);if(!t)throw Error(`指标卡旁边没有读屏表`);let n=[...t.tBodies[0].rows].map(e=>Qe(e.cells[0]?.textContent??``)).filter(e=>e.length===3);return S(n.length).toBeGreaterThan(10),n});await S($e(t)).toBe(!0)}},G=(e,t)=>e.querySelector(`[data-slot="${t}"]`),K={...re,play:async({canvasElement:e})=>{let t=E(e),n=await ze(e),[r,i]=n[n.length-1];await T(()=>S(G(e,`metric-period`)).toHaveTextContent(r)),await S(G(e,`metric-value`)).toHaveTextContent(i);let a=G(e,`metric-change`);await S(a).toHaveTextContent(l[`label.chart.change.against`]);let s=a.getAttribute(`data-direction`),c=a.querySelector(`[data-slot="badge"]`)?.getAttribute(`data-tone`);await S({up:`success`,down:`danger`,flat:`neutral`}[s??``]).toBe(c);let u=o.current;await w.click(t.getByRole(`button`,{name:l[`label.analysis.visualize`]})),await w.click(await T(()=>{let t=e.querySelector(`[data-slot="chart-options-open"]`);if(!t)throw Error(`没有选项按钮`);return t}));let d=await T(()=>{let t=e.querySelector(`[data-slot="metric-headline"]`);if(!t)throw Error(`选项里没有「大数字」`);return t});await w.click(E(d).getByRole(`button`,{name:l[`label.chart.headline.whole`]})),await T(()=>S(G(e,`metric-period`)).toHaveTextContent(l[`label.chart.period.whole`]));let f=n.reduce((e,[,t])=>e+Number(t.replace(/[^\d]/g,``)),0);await T(()=>S(G(e,`metric-value`)).toHaveTextContent(String(f))),await S(G(e,`metric-change`)).toBeNull(),await S(o.current).toBeGreaterThan(u),await S(d).toHaveTextContent(l[`label.chart.headline.whole.hint`]),await w.click(E(d).getByRole(`button`,{name:l[`label.chart.headline.last`]})),await T(()=>S(G(e,`metric-period`)).toHaveTextContent(r)),await S(G(e,`metric-change`)).not.toBeNull()}},q={...ge,play:async({canvasElement:e})=>{await _(e);let t=await T(()=>{let t=e.querySelector(`[data-slot="chart-reading"] table`);if(!t)throw Error(`折线旁边没有读屏表`);let n=[...t.tBodies[0].rows].map(e=>({day:Qe(e.cells[0]?.textContent??``),count:Number((e.cells[1]?.textContent??``).replace(/[^\d]/g,``))}));return S(n.length).toBeGreaterThan(10),n}),n=t.map(({day:[e,t,n]})=>Date.UTC(e,t-1,n));await S(n.every((e,t)=>t===0||e-n[t-1]===864e5)).toBe(!0),await S(t.filter(e=>e.count===0).length).toBeGreaterThan(5),await S(t.reduce((e,t)=>e+t.count,0)).toBe(10);let r=await T(()=>{let n=Ee(e);return S(n).toHaveLength(t.length),n.map(e=>{let t=e.getBoundingClientRect();return(t.left+t.right)/2})}),i=r.slice(1).map((e,t)=>e-r[t]);await S(Math.max(...i)-Math.min(...i)).toBeLessThan(1)}},et={...p,play:async({canvasElement:e})=>{await _(e);let t=await T(()=>{let t=Te(e);return S(t).toHaveLength(8),t.map(e=>getComputedStyle(e).fill)});await S(new Set(t).size).toBe(8);let n=t.map(e=>Ze(e)?.c??0);await S(n.at(-1)).toBeLessThan(.02),await S(n.slice(0,-1).every(e=>e>.1)).toBe(!0),await S(Ae(e)).toContain(l[`label.chart.other`])}},J=()=>T(()=>{let e=document.body.querySelector(`[data-slot="drill-menu"]`);if(!e)throw Error(`追问菜单没有弹出来`);return S(e).toBeVisible(),e}),tt=()=>T(()=>{let e=document.body.querySelector(`[data-slot="origin-bar"]`);if(!e)throw Error(`没有「返回」那一条`);return e}),nt=a(l,`label.origin.back`,{title:`仓库金额分布`}),Y=`仓库 ${l[`label.relation.is`]} 华南`,rt=(e,t)=>a(l,`label.drill.titled`,{subject:e,group:t}),it=e=>E(e).getByRole(`region`,{name:l[`label.applied.title`]}),at={...he,play:async({canvasElement:e})=>{let t=E(e);await T(()=>S(k(e)).toHaveLength(4)),await _(e),De(k(e)[2]);let n=await J();await S(E(n).getByText(Y)).toBeVisible(),await S(E(n).getAllByRole(`menuitem`).map(e=>e.textContent)).toEqual([l[`label.drill.records`],l[`label.drill.split`],l[`label.drill.focus`]]),await w.click(E(n).getByRole(`menuitem`,{name:l[`label.drill.records`]}));let r=await b(e);await T(()=>S(y(r,`订单号`)).toEqual([`SO-1004`,`SO-1005`]));let i=await Be(e,rt(`订单`,Y),Y),a=o.current;await w.click(E(i).getByRole(`button`,{name:nt})),await T(()=>S(k(e)).toHaveLength(4)),await S(t.getByRole(`heading`,{level:2,name:`仓库金额分布`})).not.toHaveAttribute(`data-dirty`),await S(document.body.querySelector(`[data-slot="origin-bar"]`)).toBeNull(),await S(o.current).toBe(a)}},ot={...m,play:async({canvasElement:e})=>{let t=E(e);await _(e);let n=await T(()=>{let t=A(e).map(e=>e.name);return S(t).toHaveLength(3),S(t.every(e=>e!==null)).toBe(!0),t});De(Te(e)[0]);let r=await J();await S(E(r).getAllByRole(`menuitem`).map(e=>e.textContent)).toEqual([l[`label.drill.split`],l[`label.drill.focus`]]),await w.click(E(r).getByRole(`menuitem`,{name:l[`label.drill.focus`]})),await T(()=>S(A(e).map(e=>e.name)).toEqual([`华南`]));let i=await Be(e,rt(`仓库金额分布`,Y),Y),a=o.current;await w.click(E(i).getByRole(`button`,{name:nt})),await T(()=>S(A(e).map(e=>e.name)).toEqual(n)),await S(t.getByRole(`heading`,{level:2,name:`仓库金额分布`})).not.toHaveAttribute(`data-dirty`),await S(document.body.querySelector(`[data-slot="origin-bar"]`)).toBeNull(),await S(o.current).toBe(a)}},st={...fe,play:async({canvasElement:e})=>{let t=[`¥0～500`,`¥500～1,000`,`¥1,000～1,500`];await _(e),await T(()=>S(k(e)).toHaveLength(3));let n=await T(()=>{let t=v(e,`bottom`).sort((e,t)=>e.getBoundingClientRect().left-t.getBoundingClientRect().left).map(e=>e.textContent);return S(t).toHaveLength(3),t});await S(n).toEqual(t);let r=e.querySelector(`[data-slot="chart-reading"] table`);await S(r).not.toBeNull();for(let e of t)await S(E(r).getByText(e)).toBeInTheDocument();await w.click(E(e).getByRole(`button`,{name:l[`label.layout.table`]}));let i=await b(e);await T(()=>S(y(i,`运费`)).toEqual(t));let o=e.querySelector(`tr[data-pickable]`);await w.click(o.cells[1]);let s=await J();await S(s.querySelector(`[data-slot="drill-group"]`)).toHaveTextContent(RegExp(`^${a(l,`label.drill.bucket`,{field:`运费`,bucket:t[0]})}$`))}},ct={...me,args:{...me.args,layout:`table`},play:async({canvasElement:e})=>{let t=await T(()=>{let t=e.querySelector(`tr[data-pickable]`);if(!t)throw Error(`结果还没有行`);return t}),n=t.cells[0].textContent??``;await S(n).toMatch(/^\d{4}年\d{1,2}月\d{1,2}日$/);let r=a(l,`label.filter.period`,{field:`创建时间`,period:n});await w.click(t.cells[1]);let i=await J();await S(i.querySelector(`[data-slot="drill-group"]`)).toHaveTextContent(RegExp(`^${r}$`)),await w.click(E(i).getByRole(`menuitem`,{name:l[`label.drill.focus`]})),await tt(),await S(E(e).getByRole(`heading`,{level:2,name:rt(`运单分析`,r)})).toBeVisible();let o=E(e).getByRole(`region`,{name:l[`label.applied.title`]});await S(E(o).getByText(r)).toBeVisible()}},lt={...he,args:{...he.args,layout:`table`},play:async({canvasElement:e})=>{let t=E(e),n=await b(e);await T(()=>S(y(n,`仓库`)).toEqual([`华东`,`华北`,`华南`,`西南`]));let r=e.querySelectorAll(`tr[data-pickable]`)[2];await S(r).toHaveAttribute(`aria-haspopup`,`menu`),r.focus(),await w.keyboard(`{Enter}`);let i=await J();await w.hover(E(i).getByRole(`menuitem`,{name:l[`label.drill.split`]}));let a=await T(()=>{let e=document.body.querySelector(`[data-slot="dropdown-menu-sub-content"]`);if(!e)throw Error(`子菜单没有展开`);return e});await S(E(a).getAllByRole(`menuitem`).map(e=>e.textContent)).toEqual([`状态`]),await w.click(E(a).getByRole(`menuitem`,{name:`状态`}));let s=await b(e);await T(()=>S(y(s,`状态`)).toEqual([`已发运`,`待出库`]));let c=await Be(e,rt(`仓库金额分布`,Y),Y),u=o.current;await w.click(E(c).getByRole(`button`,{name:nt})),await T(async()=>S(y(await b(e),`仓库`)).toEqual([`华东`,`华北`,`华南`,`西南`])),await S(t.getByRole(`heading`,{level:2,name:`仓库金额分布`})).not.toHaveAttribute(`data-dirty`),await S(document.body.querySelector(`[data-slot="origin-bar"]`)).toBeNull(),await S(o.current).toBe(u)}},ut={...he,args:{...he.args,layout:`table`},play:async({canvasElement:e})=>{let t=await b(e);await T(()=>S(y(t,`仓库`)).toHaveLength(4));let n=t.getBoundingClientRect().width,r=e.querySelectorAll(`tr[data-pickable]`)[1],i=r.cells[1];await w.click(i);let a=await Ve(await J()),o=i.getBoundingClientRect();await S(a.width).toBeGreaterThanOrEqual(223.5),await S(a.width).toBeLessThanOrEqual(320.5),await S(a.width).toBeLessThan(n/2),await S(a.top).toBeGreaterThanOrEqual(o.bottom),await S(a.left).toBeLessThanOrEqual(o.right),await S(a.right).toBeGreaterThanOrEqual(o.left),await w.keyboard(`{Escape}`),await T(()=>S(document.body.querySelector(`[data-slot="drill-menu"]`)).toBeNull()),await T(()=>S(document.activeElement).toBe(r)),await w.keyboard(`{Enter}`),a=await Ve(await J());let s=r.cells[0].getBoundingClientRect();await S(Math.abs(a.left-s.left)).toBeLessThan(1),await S(a.top).toBeGreaterThanOrEqual(s.bottom),await S(a.width).toBeLessThan(n/2),await w.keyboard(`{Escape}`)}},dt={...ee,play:async({canvasElement:e})=>{await _(e),await T(()=>S(k(e)).toHaveLength(8)),await T(()=>S(v(e,`right`).length).toBeGreaterThan(1)),await S(v(e,`left`).length).toBeGreaterThan(1)}},ft={...h,play:async({canvasElement:e})=>{let t=await E(e).findByRole(`table`);await T(()=>S(y(t,`仓库`)).toEqual([`华东`,`华北`,`华南`,`西南`])),await S(y(t,O)).toEqual([`2`,`1`,`2`,`1`]),await S(y(t,D).map(Fe)).toEqual([1920,2450,4880,980]),await S(je(t,O)).toBe(`6`),await S(Fe(je(t,D))).toBe(10230)}},pt={...m,play:async({canvasElement:e})=>{await T(()=>S(A(e).map(e=>e.name)).toEqual([`华南`,`华北`,l[`label.chart.other`]])),await S(A(e).every(e=>e.drawn)).toBe(!0)}},mt=/^[<>]?\d{1,3}\.\d%$/,ht={...m,decorators:[e=>(0,Je.jsx)(`div`,{style:{width:414},children:(0,Je.jsx)(e,{})})],play:async({canvasElement:e})=>{await _(e);let t=e.querySelector(`[data-slot="chart"]`);await S(t.getBoundingClientRect().width).toBeLessThan(480),await S(t).toHaveAttribute(`data-legend`,`bottom`);let n=t.querySelector(`[data-slot="chart-plot"]`).getBoundingClientRect(),r=t.querySelector(`[data-slot="chart-legend"]`).getBoundingClientRect();await S(r.top).toBeGreaterThanOrEqual(n.bottom-1);let i=Ce(e).filter(e=>e.getBoundingClientRect().width>0);for(let e of i){await S((e.textContent??``).trim()).toMatch(mt);let t=e.getBoundingClientRect();await S(t.left).toBeGreaterThanOrEqual(n.left-1),await S(t.right).toBeLessThanOrEqual(n.right+1)}let a=[...t.querySelectorAll(`[data-slot="chart-legend-item"]`)].map(e=>e.lastElementChild?.textContent??``);await S(a).toEqual([`47.7%`,`23.9%`,`28.3%`])}},gt={...f,play:async({canvasElement:e})=>{await T(()=>S(A(e)).toHaveLength(3));let[t,...n]=A(e);await S(t).toEqual({name:`华南`,fill:`rgb(124, 58, 237)`,drawn:!0});for(let e of n)await S(e.fill).not.toBe(`rgb(124, 58, 237)`)}},_t=l[`analysis.result.more-groups`].replace(`{limit}`,`2`),vt={...ce,play:async({canvasElement:e})=>{let t=E(e);await T(()=>S(A(e)).toHaveLength(2)),await S(A(e).map(e=>e.name)).toEqual([`华南`,`华北`]),await S(A(e).every(e=>e.drawn)).toBe(!0),await S(e.querySelector(`[data-slot="pie-measure"]`)).toHaveTextContent(l[`label.chart.share-basis`]);let n=await He(t);await S(n).toHaveTextContent(_t),await S(n.closest(`[data-slot="analysis-cut-short"]`)).not.toBe(null),await S(e.querySelector(`[data-slot="status-line"]`)).not.toHaveTextContent(_t)}},yt={...te,play:async({canvasElement:e})=>{let t=E(e),n=await t.findByRole(`table`);await T(()=>S(y(n,`仓库`)).toEqual([`华南`,`华北`])),await S(Fe(je(n,D))).toBe(10230),await S(e.querySelector(`[data-slot="totals-hidden"]`)).toHaveTextContent(a(l,`label.analysis.totals-hidden.cut`,{limit:`2`})),await S(t.queryByText(_t)).toBeNull(),await S(e.querySelector(`[data-slot="analysis-cut-short"]`)).toBeNull()}},bt={...h,play:async({canvasElement:e})=>{let t=await b(e);await T(()=>S(y(t,D).map(Fe)).toEqual([1920,2450,4880,980]));for(let e of[D,O])for(let n of Ue(t,e))await S(getComputedStyle(n).textAlign).toBe(`right`),await S(getComputedStyle(n).fontVariantNumeric).toContain(`tabular-nums`),await S(We(n)).toBeLessThan(4);let[,n]=Ue(t,`仓库`);await S(We(n)).toBeGreaterThan(20);let r=t.querySelector(`[data-slot="totals-heading"]`),i=r.querySelector(`[data-slot="totals-scope"]`);await S(i).toBeVisible(),await S(i).toHaveTextContent(l[`label.analysis.totals-scope`]),await S(i.getBoundingClientRect().top).toBeGreaterThan(r.getBoundingClientRect().top+parseFloat(getComputedStyle(r).paddingTop)+4),await S(t.tFoot.querySelectorAll(`button, a, input, [tabindex]`)).toHaveLength(0)}},xt={...h,play:async({canvasElement:e})=>{let t=await b(e);await T(()=>S(y(t,`仓库`)).toEqual([`华东`,`华北`,`华南`,`西南`]));let n=e=>t.tHead.rows[0].cells[Ne(t,e)],r=()=>[...t.tHead.rows[0].cells].filter(e=>e.hasAttribute(`aria-sort`)).map(e=>e.getAttribute(`aria-sort`)),i=()=>y(t,D).map(Fe),a=[...t.querySelectorAll(`thead button`)];await S(a.filter(e=>e.tabIndex===0)).toHaveLength(1),E(n(`仓库`)).getByRole(`button`).focus(),await w.keyboard(`{ArrowRight}{ArrowRight}`),await S(document.activeElement).toBe(E(n(D)).getByRole(`button`)),await w.keyboard(`{Enter}`),await T(()=>S(i()).toEqual([980,1920,2450,4880])),await S(n(D)).toHaveAttribute(`aria-sort`,`ascending`),await w.click(E(n(D)).getByRole(`button`)),await T(()=>S(i()).toEqual([4880,2450,1920,980])),await S(n(D)).toHaveAttribute(`aria-sort`,`descending`),await w.click(E(n(D)).getByRole(`button`)),await T(()=>S(y(t,`仓库`)).toEqual([`华东`,`华北`,`华南`,`西南`])),await S(r()).toEqual([])}},St={...ae,play:async({canvasElement:e})=>{let t=await b(e);await T(()=>S(y(t,`处理器`)).toEqual([`OrderItemReservedTrackEventProcessor`,`InventorySnapshotProjectionHandler`]));let n=()=>[...t.tHead.rows[0].querySelectorAll(`th:not([aria-hidden])`)].map(e=>{let t=e.getBoundingClientRect();return{left:Math.round(t.left),width:Math.round(t.width)}});for(let e of Ue(t,`处理器`).slice(1,3))await S(e.scrollWidth).toBeLessThanOrEqual(e.clientWidth);let r=n();await w.click(E(t.tHead.rows[0].cells[Ne(t,O)]).getByRole(`button`)),await T(()=>S(y(t,`处理器`)).toEqual([`Mailer`,`Audit`])),await S(n()).toEqual(r)}},Ct={...ie,play:async({canvasElement:e})=>{let t=await b(e),n=await T(()=>{let e=[...t.querySelectorAll(`[data-slot="identifier"]`)];if(e.length===0)throw Error(`no identifier yet`);return e});await S(n[0]).toHaveTextContent(/^0b5f\d{4}-7c1e-/);for(let e of n)await S(getComputedStyle(e).fontFamily).toMatch(/mono/i);let[,r]=Ue(t,O);await S(getComputedStyle(r).fontFamily).not.toMatch(/mono/i)}},wt=e=>e.querySelector(`[data-slot="result-toolbar"]:not([aria-hidden])`),X=e=>{let t=e.getBoundingClientRect();return{top:Math.round(t.top),bottom:Math.round(t.bottom)}},Tt={...xe,play:async({canvasElement:e})=>{let t=await T(()=>{let t=e.querySelector(`[data-slot="analysis-table-skeleton"]`);return S(t).not.toBeNull(),t});await S(t.querySelectorAll(`tr`)).toHaveLength(3);let n=wt(e);await S(n).toHaveTextContent(`仓库`);let r=e.querySelector(`[data-slot="applied-bar"]`);await S(r).toBeVisible();let i=e.querySelector(`[data-slot="analysis-caption"]`);await S(i.dataset.loading).toBe(``);let a={toolbar:X(n),applied:X(r),caption:X(i)};await T(()=>S(e.querySelector(`[data-slot="analysis-table"]`)).not.toBeNull(),{timeout:5e3}),await S(e.querySelector(`[data-slot="analysis-table-skeleton"]`)).toBeNull();let o=e.querySelector(`[data-slot="analysis-caption"]`);await S(o.dataset.loading).toBeUndefined(),await S(wt(e)).toBe(n),await S({toolbar:X(n),applied:X(r),caption:X(o)}).toEqual(a)}},Et={...ne,play:async({canvasElement:e})=>{let t=await T(()=>{let t=e.querySelector(`[data-slot="analysis-chart-skeleton"]`);return S(t).not.toBeNull(),t});await S(t.getBoundingClientRect().height).toBeGreaterThan(150);let n=wt(e),r=e.querySelector(`[data-slot="analysis-caption"]`),i={toolbar:X(n),caption:X(r)};await T(()=>S(k(e)).toHaveLength(4),{timeout:5e3}),await _(e),await S({toolbar:X(wt(e)),caption:X(e.querySelector(`[data-slot="analysis-caption"]`))}).toEqual(i)}},Dt={...de,play:async({canvasElement:e})=>{let t=E(e);await S(await t.findByText(l[`label.analysis.empty`])).toBeVisible(),await S(wt(e)).toBeVisible(),await S(t.getByText(l[`label.analysis.empty-none`])).toBeVisible();let n=e.querySelector(`[data-slot="analysis-empty"]`);await S(E(n).queryByRole(`button`)).toBeNull(),await S(e.querySelector(`[data-slot="analysis-caption"]`)).toHaveTextContent(/^正在显示 0 组/)}},Ot={...ye,play:async({canvasElement:e})=>{let t=await E(e).findByRole(`alert`);await S(t).toHaveTextContent(`没能加载数据：仓储服务暂时不可用`);let n=wt(e);await S(n).toBeVisible(),await S(X(t).top).toBeGreaterThanOrEqual(X(n).bottom),await S(e.querySelector(`[data-slot="applied-bar"]`)).toBeVisible(),await w.click(E(n).getByRole(`button`,{name:l[`label.layout.table`]})),await S(E(n).getByRole(`button`,{name:l[`label.layout.table`],pressed:!0})).toBeVisible();let r=o.current;await w.click(E(t).getByRole(`button`,{name:l[`label.query.retry`]})),await T(()=>S(o.current).toBeGreaterThan(r))}},kt=e=>e.querySelector(`[data-slot="analysis-tray"]`),At={...h,play:async({canvasElement:e})=>{let t=E(e);await t.findByRole(`table`),await S(kt(e)).toBeNull(),await S(Ge(e)).toHaveAttribute(`aria-expanded`,`false`);let n=await x(e);await S([...n.querySelectorAll(`[data-slot^="analysis-slot-"]`)].map(e=>e.getAttribute(`data-slot`))).toEqual([`analysis-slot-range`,`analysis-slot-dimensions`,`analysis-slot-metrics`,`analysis-slot-result`]);for(let e of[l[`label.analysis.slot.range`],l[`label.analysis.slot.dimensions`],l[`label.analysis.slot.metrics`],l[`label.analysis.slot.result`]])await S(t.getByRole(`region`,{name:e})).toBeVisible();await w.click(Mt(e));let r=jt(e);await T(()=>S(r).toHaveAttribute(`data-emphasis`,`primary`));let i=getComputedStyle(r).backgroundColor,a=[...e.querySelectorAll(`[data-slot="button"]`)].filter(e=>getComputedStyle(e).backgroundColor===i);await S(a.map(e=>e.textContent?.trim())).toEqual([l[`label.filter.apply`]])}},jt=e=>E(e.querySelector(`[data-slot="analysis-tray-actions"]`)).getByRole(`button`,{name:l[`label.filter.apply`]}),Mt=e=>E(e.querySelector(`[data-slot="auto-run"]`)).getByRole(`checkbox`),Nt={...h,play:async({canvasElement:e})=>{let t=E(e);await t.findByRole(`table`);let n=()=>e.querySelector(`[data-slot="analysis-reading"]`)?.textContent,r=n(),i=await x(e);await S(i.querySelectorAll(`[data-slot="dimension-card"]`)).toHaveLength(1),await w.click(Mt(e)),await T(()=>S(Mt(e)).toHaveAttribute(`aria-checked`,`false`)),await w.click(t.getByRole(`button`,{name:l[`label.analysis.add-group`]})),await w.click(await C.findByRole(`menuitem`,{name:`状态`})),await T(()=>S(kt(e).querySelectorAll(`[data-slot="dimension-card"]`)).toHaveLength(2));let o=t.getByLabelText(a(l,`label.analysis.function-of`,{name:`金额`}));await w.click(o),await w.click(await C.findByRole(`option`,{name:l[`label.summary.fn.AVG`]})),await T(()=>S(o).toHaveTextContent(`平均`));let s=E(e.querySelector(`[data-slot="analysis-tray-actions"]`)).getByRole(`button`,{name:l[`label.filter.apply`]});await S(s).toHaveAttribute(`data-pending`),await w.click(s),await T(()=>S(s).not.toHaveAttribute(`data-pending`)),await T(()=>S(n()).not.toBe(r))}},Pt=a(l,`label.summary.of`,{field:`成本`,fn:l[`label.summary.fn.SUM`]}),Ft={...h,play:async({canvasElement:e})=>{let t=E(e),n=await b(e);await T(()=>S(Pe(n)).toEqual([`仓库`,O,D])),await x(e),await w.click(Mt(e)),await T(()=>S(Mt(e)).toHaveAttribute(`aria-checked`,`false`)),await w.click(t.getByRole(`button`,{name:l[`label.analysis.add-metric`]})),await w.click(await C.findByRole(`menuitem`,{name:`成本`})),await w.click(t.getByRole(`button`,{name:l[`label.analysis.add-group`]})),await w.click(await C.findByRole(`menuitem`,{name:`状态`})),await w.click(jt(e)),await T(async()=>S(Pe(await b(e))).toEqual([`仓库`,O,D,`状态`,Pt]));let r=await b(e),i=y(r,`仓库`),a=y(r,`状态`),o=i.map((e,t)=>`${e}|${a[t]}`);await S(new Set(o).size).toBe(o.length),await S(i.filter(e=>e===`华东`).length).toBeGreaterThan(1),await S(y(r,Pt).every(Boolean)).toBe(!0)}},Z={...h,play:async({canvasElement:e})=>{let t=E(e);await t.findByRole(`table`),await x(e);let n=()=>t.getByRole(`region`,{name:l[`label.analysis.slot.range`]});await S(E(n()).queryByRole(`group`,{name:l[`label.filter.all-conditions`]})).toBeNull(),await w.click(E(n()).getByRole(`button`,{name:a(l,`label.analysis.conditions-mode`,{mode:l[`label.filter.simple`]})})),await w.click(await C.findByRole(`menuitemradio`,{name:l[`label.filter.advanced`]})),await T(()=>S(E(n()).getByRole(`group`,{name:l[`label.filter.all-conditions`]})).toBeVisible())}},It={...h,play:async({canvasElement:e})=>{await E(e).findByRole(`table`);let t=await x(e);await S(getComputedStyle(t).rowGap).toBe(`12px`),await S(getComputedStyle(t.querySelector(`.grid`)).columnGap).toBe(`16px`),await S(getComputedStyle(t.querySelector(`[data-slot="analysis-slot-metrics"]`)).rowGap).toBe(`8px`)}},Lt={...h,play:async({canvasElement:e})=>{let t=E(e);await t.findByRole(`table`);let n=await x(e),r=t.getByRole(`region`,{name:l[`label.analysis.slot.result`]}),i=t.getByRole(`region`,{name:l[`label.analysis.slot.metrics`]});await S(r.getBoundingClientRect().top).toBeGreaterThanOrEqual(i.getBoundingClientRect().bottom);let a=E(r).getByRole(`group`,{name:l[`label.sort.title`]}),o=E(r).getByLabelText(l[`label.analysis.row-limit`]),s=o.closest(`[data-slot="analysis-limit"]`),c=e=>e.getBoundingClientRect();await S(E(r).queryByRole(`group`,{name:l[`label.analysis.having-title`]})).toBeNull();let u=E(r).getByRole(`button`,{name:l[`label.analysis.having-first`]}),d=e=>(c(e).top+c(e).bottom)/2;await S(Math.abs(d(a)-d(s))).toBeLessThan(1),await S(Math.abs(d(a)-d(u))).toBeLessThan(1),await S(c(a).right).toBeLessThanOrEqual(c(s).left),await S(c(s).right).toBeLessThanOrEqual(c(u).left);let f=a.querySelector(`[data-slot="field-label"]`);await S(c(f).bottom).toBeGreaterThan(c(a).top),await S(c(f).right).toBeLessThanOrEqual(c(a.querySelector(`button`)).left);let p=s.querySelector(`[data-slot="limit-unsorted"]`);await S(p).toBeVisible(),await S(p).toHaveTextContent(l[`label.analysis.row-limit-unsorted`]),await S(o).toHaveAttribute(`aria-describedby`,p.id),await w.click(u);let ee=await E(r).findByRole(`group`,{name:l[`label.analysis.having-title`]}),m=[ee.querySelector(`legend`),f,r.querySelector(`label[for="${o.id}"]`)];await S(m.map(e=>e?.textContent)).toEqual([l[`label.analysis.having-title`],l[`label.sort.title`],l[`label.analysis.row-limit`]]);for(let e of m)await S(e).toBeVisible();await S(c(ee).bottom).toBeLessThanOrEqual(c(a).top);let h=getComputedStyle(Mt(e)).backgroundColor,g=jt(e);await S(g).toHaveAttribute(`data-emphasis`,`quiet`),await S(getComputedStyle(g).backgroundColor).not.toBe(h);let te=e.querySelector(`[data-slot="auto-run-hint"]`);await S(te).toBeVisible(),await S(te).toHaveTextContent(l[`label.analysis.auto-run-hint`]);let ne=t.getByRole(`region`,{name:l[`label.analysis.slot.range`]});await w.click(E(ne).getByRole(`button`,{name:l[`label.filter.add`]}));let re=await C.findByRole(`dialog`,{name:l[`label.filter.pick-fields`]});for(let e of[`订单号`,`仓库`,`状态`,`标记`,`备注`,`金额`])await w.click(E(re).getByRole(`checkbox`,{name:e}));await w.click(E(re).getByRole(`button`,{name:l[`label.filter.pick-done`]})),await T(()=>S(g).toHaveAttribute(`data-emphasis`,`primary`)),await S(getComputedStyle(g).backgroundColor).toBe(h),await S(te).toHaveTextContent(l[`label.analysis.auto-run-held`]),await S(te).toHaveAttribute(`data-held`);for(let e=0;e<2;e++)await w.click(n.querySelector(`[data-slot="add-having"]`));let ie=e.querySelector(`.fve-root > main`),ae=e.querySelector(`[data-slot="editor-band"]`),oe=n.querySelector(`[data-slot="analysis-tray-slots"]`),se=n.querySelector(`[data-slot="analysis-tray-actions"]`);await T(()=>S(oe.scrollHeight).toBeGreaterThan(oe.clientHeight)),await S(c(ae).height).toBeLessThanOrEqual(ie.getBoundingClientRect().height/2+1),await S(c(se).top).toBeGreaterThanOrEqual(c(ae).top),await S(c(se).bottom).toBeLessThanOrEqual(c(ae).bottom),await S(g).toBeVisible()}},Rt={viewport:{options:{desk:{name:`1440×900`,styles:{width:`1440px`,height:`900px`}},phone:{name:`414×896`,styles:{width:`414px`,height:`896px`}}}}},zt={...h,parameters:{...h.parameters,...Rt},globals:{viewport:{value:`desk`}},play:async({canvasElement:e})=>{await S(window.innerWidth).toBe(1440);let{main:t,band:n,slots:r,order:i,footer:a,result:o}=await Ke(e),s=e=>e.getBoundingClientRect();await T(()=>S(r.scrollHeight).toBeLessThanOrEqual(r.clientHeight+1)),await S(s(i).bottom).toBeLessThanOrEqual(s(n).bottom),await S(s(i).top).toBeGreaterThanOrEqual(s(n).top),await S(s(n).height).toBeLessThanOrEqual(s(t).height/2+1),await S(s(i).height).toBeLessThan(40),await S(s(a).height).toBeLessThan(40),await S(s(o).height).toBeGreaterThan(340)}},Bt={...h,parameters:{...h.parameters,...Rt},globals:{viewport:{value:`phone`}},play:async({canvasElement:e})=>{await S(window.innerWidth).toBe(414);let{main:t,band:n,footer:r,result:i}=await Ke(e),a=e=>e.getBoundingClientRect();await S(a(n).height).toBeLessThanOrEqual(a(t).height*.36),await S(a(i).height).toBeGreaterThan(340),await S(a(r).bottom).toBeLessThanOrEqual(a(n).bottom+1),await S(r.getBoundingClientRect().height).toBeLessThan(60),await S(jt(e)).toBeVisible()}},Vt={...xe,play:async({canvasElement:e})=>{let t=E(e);await t.findByRole(`table`,{},{timeout:5e3}),await x(e);let n=()=>e.querySelector(`[data-slot="analysis-result"]`),r=o.current;await w.click(t.getByRole(`button`,{name:l[`label.analysis.add-group`]})),await w.click(await C.findByRole(`menuitem`,{name:`状态`})),await T(()=>S(o.current).toBeGreaterThan(r)),await new Promise(e=>setTimeout(e,600)),await S(n()).toHaveAttribute(`data-stale`),await S(Number(getComputedStyle(n()).opacity)).toBeLessThan(1),await T(()=>S(n()).not.toHaveAttribute(`data-stale`),{timeout:5e3}),await S(Pe(await b(e))).toContain(`状态`)}},Ht=(e,t)=>e.left<t.right-.5&&t.left<e.right-.5&&e.top<t.bottom-.5&&t.top<e.bottom-.5,Q=(e,t)=>e.querySelector(`[data-slot="chart-tile"][data-chart-type="${t}"]`),Ut={...pe,play:async({canvasElement:e})=>{let t=E(e);await T(()=>S(k(e)).toHaveLength(4));let n=e.querySelector(`[data-slot="view-sidebar"]`);await S(n).not.toBeNull();let r=e.querySelector(`.fve-root > main`),i=n.getBoundingClientRect().width,a=r.getBoundingClientRect().left;await w.click(t.getByRole(`button`,{name:l[`label.analysis.visualize`]}));let s=e.querySelector(`[data-slot="view-panel"]`);await S(s).toBeVisible(),await S(e.querySelector(`[data-slot="view-sidebar"]`)).toBeNull(),await S(s.getBoundingClientRect().width).toBeCloseTo(i,0),await S(r.getBoundingClientRect().left).toBeCloseTo(a,0),await S([...s.querySelectorAll(`[data-slot="chart-tile"]`)].map(e=>e.getAttribute(`data-chart-type`))).toEqual([`bar`,`line`,`area`,`combo`,`pie`,`heatmap`,`scatter`,`funnel`,`metric`,`table`]);let u=Q(s,`bar`);await S(u).toHaveAttribute(`aria-checked`,`true`),await S(u).toHaveAttribute(`data-recommended`),await S(u).toHaveTextContent(l[`label.chart.recommended`]);let d=Q(s,`heatmap`);await S(d).toHaveAttribute(`aria-disabled`,`true`),await S(d).toHaveTextContent(l[`chart.fit.needs-two-dimensions`]),await qe(s,`bar`);let f=Q(s,`bar`).querySelector(`[data-slot="chart-recommended"]`);f.textContent=c[`label.chart.recommended`],await qe(s,`bar`,!1),f.textContent=l[`label.chart.recommended`],Q(s,`bar`).focus(),await w.tab(),await S(s.querySelector(`[data-slot="chart-options-open"]`)).toHaveFocus();let p=o.current;await w.click(Q(s,`pie`)),await T(()=>S(A(e).length).toBeGreaterThan(0)),await S(A(e).every(e=>e.drawn)).toBe(!0),await S(o.current).toBe(p),await S(e.querySelector(`[data-slot="pie-measure"]`)).toHaveTextContent(D),await T(()=>S(Ce(e).some(e=>/%$/.test(e.textContent??``))).toBe(!0),{timeout:4e3}),await S(Q(s,`pie`)).toHaveAttribute(`aria-checked`,`true`),await qe(s,`pie`),await S(document.querySelector(`[data-slot="chart-options"]`)).toBeNull(),await S(e.querySelector(`[data-slot="editor-toggle"] [data-slot="pending-dot"]`)).toBeNull(),await w.click(Q(s,`table`)),await t.findByRole(`table`),await S(A(e)).toHaveLength(0),await S(o.current).toBe(p),await qe(s,`table`);let ee=s.querySelector(`[data-slot="chart-options-open"]`);await w.click(ee);let m=await T(()=>{let e=s.querySelector(`[data-slot="chart-options"]`);if(!e)throw Error(`选项页没有打开`);return e});await S(E(m).getByRole(`checkbox`,{name:l[`label.analysis.totals`]})).toBeVisible(),await w.click(E(m).getByRole(`button`,{name:l[`label.chart.options-back`]})),await T(()=>S(s.querySelector(`[data-slot="chart-options-open"]`)).toHaveFocus()),await w.click(E(s).getByRole(`button`,{name:l[`label.chart.picker-back`]})),await T(()=>S(e.querySelector(`[data-slot="view-sidebar"]`)).not.toBeNull()),await S(e.querySelector(`[data-slot="view-panel"]`)).toBeNull()}},Wt={...h,play:async({canvasElement:e})=>{let t=E(e);await t.findByRole(`table`),await x(e);let n=e=>t.getByRole(`button`,{name:a(l,`label.analysis.card-menu`,{name:e})});await w.click(n(`仓库`)),await w.click(await C.findByRole(`menuitem`,{name:l[`label.analysis.rename`]}));let r=await C.findByLabelText(a(l,`label.analysis.display-name`,{name:`仓库`}));await w.clear(r),await w.type(r,`门店{Enter}`),await T(()=>S(e.querySelector(`[data-slot="card-name"]`)).toHaveTextContent(`门店`));let i=E(e.querySelector(`[data-slot="analysis-tray-actions"]`)).getByRole(`button`,{name:l[`label.filter.apply`]});await w.click(i),await T(()=>S(t.getByRole(`columnheader`,{name:`门店`})).toBeVisible()),await S(e.querySelector(`[data-slot="analysis-reading"]`)).toHaveTextContent(`门店`),await w.click(n(`门店`));let o=()=>C.getByRole(`menuitemcheckbox`,{name:l[`label.analysis.missing-bucket`]});await T(()=>S(o()).toHaveAttribute(`aria-checked`,`false`)),await w.click(o()),await T(()=>S(o()).toHaveAttribute(`aria-checked`,`true`))}},Gt={...h,play:async({canvasElement:e})=>{let t=E(e),n=a(l,`label.analysis.metric-where`,{metric:D,value:`已发运`}),r=async(t=D)=>y(await b(e),t).map(Fe),i=async()=>y(await b(e),O),o=await r(),s=await i();await x(e);let c=(e=D)=>t.getByRole(`button`,{name:a(l,`label.analysis.condition-of`,{name:e})});await S(c()).toHaveAttribute(`aria-pressed`,`false`),await w.click(c());let u=await T(()=>{let t=e.querySelector(`[data-slot="card-conditions"]`);if(!t)throw Error(`条件块没有打开`);return t});await S(u).toHaveTextContent(l[`label.analysis.condition-title`]),await S(t.queryByRole(`alert`)).toBeNull(),await S(t.queryByText(l[`analysis.metricFilter.empty`])).toBeNull(),await S(t.queryByRole(`button`,{name:l[`label.analysis.open-editor`]})).toBeNull(),await S(E(u).getByRole(`button`,{name:`${D} ${l[`label.filter.add-condition`]}`})).toHaveTextContent(RegExp(`^${l[`label.filter.add-condition`]}$`)),await w.click(E(u).getByRole(`button`,{name:`${D} ${l[`label.filter.add-condition`]}`}));let d=await C.findByRole(`dialog`,{name:l[`label.filter.pick-fields`]});await w.click(E(d).getByRole(`checkbox`,{name:`状态`})),await w.click(E(d).getByRole(`button`,{name:l[`label.filter.pick-done`]})),await w.click(E(u).getByRole(`combobox`,{name:a(l,`label.filter.value-of`,{field:`状态`})})),await w.click(await C.findByRole(`option`,{name:`已发运`})),await w.keyboard(`{Escape}`),await w.click(E(e.querySelector(`[data-slot="analysis-tray-actions"]`)).getByRole(`button`,{name:l[`label.filter.apply`]})),await T(async()=>S(await r(n)).not.toEqual(o)),await S(await i()).toEqual(s);let f=await b(e),p=(f.tHead.rows[0].cells[Ne(f,n)].querySelector(`[aria-describedby]`)?.getAttribute(`aria-describedby`)??``).split(` `).map(t=>e.ownerDocument.getElementById(t)?.textContent);await S(p.join(`
`)).toContain(`只算 状态`),await S(p.join(`
`)).toContain(`已发运`),await S(e.querySelector(`[data-slot="analysis-reading"]`)).toHaveTextContent(n),await w.click(E(u).getByRole(`button`,{name:l[`label.analysis.condition-close`]}));let ee=await T(()=>{let t=e.querySelector(`[data-slot="metric-condition-line"]`);if(!t)throw Error(`卡片上没有「只算 …」那一句`);return t});await S(ee).toHaveTextContent(`状态`),await S(ee).toHaveTextContent(`已发运`),await S(c(n)).toHaveAttribute(`data-held`)}},$={..._e,play:async({canvasElement:e})=>{let t=E(e);await t.findByRole(`table`);let n=await x(e),r=()=>e.querySelectorAll(`[data-slot="element-card"]`),i=()=>e.querySelector(`[data-slot="counting-unit"]`)?.textContent;await S([...n.querySelectorAll(`[data-slot^="analysis-slot-"]`)].map(e=>e.getAttribute(`data-slot`))).toEqual([`analysis-slot-range`,`analysis-slot-elements`,`analysis-slot-dimensions`,`analysis-slot-metrics`,`analysis-slot-result`]),await S(i()).toBe(a(l,`label.analysis.unit`,{name:`订单`})),await w.click(t.getByRole(`button`,{name:a(l,`label.analysis.expand-into`,{name:`明细项`})})),await T(()=>S(r()).toHaveLength(1)),await S(i()).toBe(a(l,`label.analysis.unit`,{name:`明细项`})),await S(e.querySelectorAll(`[data-slot="dimension-card"]`)).toHaveLength(0),await w.click(t.getByRole(`button`,{name:a(l,`label.analysis.expand-into`,{name:`批次`})})),await T(()=>S(r()).toHaveLength(2)),await S(e.querySelector(`[data-slot="expand-into"]`)).toBeNull(),await w.click(t.getAllByRole(`button`,{name:a(l,`label.analysis.collapse`,{name:`明细项`})})[0]),await T(()=>S(r()).toHaveLength(0)),await S(i()).toBe(a(l,`label.analysis.unit`,{name:`订单`}))}},Kt=`BarChart.WholeTicks.CaptionHoldsTheReport.TicksInsideTheChart.HorizontalLabelsInFrame.TitleClearOfTicks.TitleClearOfSlantedTicks.ValueLabelsApart.LineKeepsOffTheEdges.HeatmapFillsItsPlot.OneBarKeepsItsWidth.FollowUpFromABar.TimeRunsForward.SparklineRunsForward.TrendCardReadsLastPeriod.DailyHolesRunEvenly.EightColoursThenOther.FollowUpToRecords.FollowUpFocus.BandsReadAsRanges.FollowUpOnADay.FollowUpSplit.FollowUpMenuFitsItsWords.TwoMetrics.TableWithTotals.PieChart.PieOnAPhone.PinnedCategoryColor.CutShort.CutShortTable.TableReadsLikeATable.HeaderSorts.ColumnsHoldStill.IdentifiersInMonospace.LoadingKeepsItsPlace.LoadingChartKeepsItsPlace.EmptyResult.QueryFailed.TrayFolds.TrayEdits.AddedColumnsShow.ReachesAdvancedMode.EditorRowSpacing.TrayReadsClearly.TrayFitsADesk.TrayFitsAPhone.FadesUntilAnswered.VisualizePanel.TrayCardMenu.MetricCondition.TrayExpansion`.split(`.`),j.parameters={...j.parameters,docs:{...j.parameters?.docs,source:{originalSource:`{
  ...DisplayBarChart,
  play: async ({
    canvasElement
  }) => {
    await waitFor(() => expect(bars(canvasElement)).toHaveLength(4));

    // The numbers on the axis are the column's: money, written short.
    await waitFor(() => expect(axisTicks(canvasElement, 'left').map(tick => tick.textContent).join(' ')).toContain('¥'));
    // The tooltip reads through the same labeller, whole; what it says is
    // pinned in the package (test/cartesianOption.test.ts).
  }
}`,...j.parameters?.docs?.source},description:{story:`One bar per warehouse: the source grouped the rows it was asked to. The
numbers read as the column reads them — the amount metric is money, so the
axis and the tooltip say ¥ exactly as the table does, in the surface's own
language rather than the machine's.`,...j.parameters?.docs?.description}}},M.parameters={...M.parameters,docs:{...M.parameters?.docs,source:{originalSource:`{
  ...DisplayLatestPerWarehouse,
  args: {
    ...DisplayLatestPerWarehouse.args,
    layout: 'chart'
  },
  play: async ({
    canvasElement
  }) => {
    await chartsDrawn(canvasElement);
    await waitFor(() => expect(bars(canvasElement).length).toBeGreaterThan(0));
    const labels = await waitFor(() => {
      const found = axisTicks(canvasElement, 'left').map(tick => (tick.textContent ?? '').trim());
      expect(found.length).toBeGreaterThan(1);
      return found;
    });
    await expect(labels.every(label => /^\\d+$/.test(label))).toBe(true);
    await expect(new Set(labels).size).toBe(labels.length);
  }
}`,...M.parameters?.docs?.source},description:{story:`数的是记录，刻度就只有整数。

四个仓库各有一到三单，纵轴从 0 到 3：比例尺本来会在中间放 0.5、1.5，而订单数
的格式把它们写成「1」「2」，轴上读成 3、2、2、1、1、0（真实补偿服务上发现，
2026-09-23）。轴上每个数都是整数时就不给小数刻度，所以这里量两件事：刻度都是
整数，且没有两个刻度写成同一个字。`,...M.parameters?.docs?.description}}},N.parameters={...N.parameters,docs:{...N.parameters?.docs,source:{originalSource:`{
  ...DisplayBarChart,
  play: async ({
    canvasElement
  }) => {
    await waitFor(() => expect(bars(canvasElement)).toHaveLength(4));
    const caption = canvasElement.querySelector<HTMLElement>('[data-slot="analysis-caption"]')!;
    await expect(caption.textContent).toMatch(/^正在显示 4 组，耗时 <?[\\d.]+ 秒$/);
    const block = caption.parentElement!;
    await expect(block.dataset.slot).toBe('result-block');
    await expect(block.lastElementChild).toBe(caption);
    await expect(Math.abs(caption.getBoundingClientRect().bottom - block.getBoundingClientRect().bottom)).toBeLessThanOrEqual(1);
  }
}`,...N.parameters?.docs?.source},description:{story:`报表有个底：结果区最后一行固定写「正在显示 N 组，耗时 X 秒」。

分析结果从前在最后一根柱子、最后一行下面就结束了，下面的空白读起来像报表
掉了下去（用户 2026-09-23）。这里量三件事：那一行说的是屏幕上的行数与耗时，
它是结果区的最后一行，它的下边就是结果区的下边——工作台填满容器，所以这就是
工作区的底。`,...N.parameters?.docs?.description}}},P.parameters={...P.parameters,docs:{...P.parameters?.docs,source:{originalSource:`{
  ...DisplayBarChart,
  play: async ({
    canvasElement
  }) => {
    await chartsDrawn(canvasElement);
    await waitFor(() => expect(bars(canvasElement)).toHaveLength(4));
    const surface = canvasElement.querySelector('[data-slot="chart-plot"] svg')!.getBoundingClientRect();
    // And the drawing keeps the column's 16px gutter: the result band runs
    // to the edge, and the axis numbers used to sit against it.
    const block = canvasElement.querySelector('[data-slot="result-block"]')!.getBoundingClientRect();
    await expect(surface.left - block.left).toBeGreaterThanOrEqual(15);
    // On both sides: a chart that is the band's full width and then pushed
    // 16px in overhangs the right edge, and the band clips its last tick
    // (「2026年9」 on the real service's monthly line).
    await expect(block.right - surface.right).toBeGreaterThanOrEqual(15);
    const ticks = axisTexts(canvasElement);
    await expect(ticks.length).toBeGreaterThan(0);
    // And no two of them on each other.
    await expect(apart(ticks)).toBe(true);
    for (const tick of ticks) {
      const box = tick.getBoundingClientRect();
      await expect(box.left).toBeGreaterThanOrEqual(surface.left - 1);
      await expect(box.right).toBeLessThanOrEqual(surface.right + 1);
    }
  }
}`,...P.parameters?.docs?.source},description:{story:`每一个刻度的字都在图里。

刻度字以刻度为中心，最后一个会伸出绘图区半个字宽：真实补偿服务上最后一天读成
「2026年9月22E」，横向图最后一个数读成「600,00(」；横向图的分类轴从前是写死的
96px，把长处理器名从左边截成「kEventProcessor」（2026-09-23）。这里量每一个刻度
字的框都在图的 \`svg\` 之内，且图离结果区的左右两边都留着工作列的 16px。`,...P.parameters?.docs?.description}}},F.parameters={...F.parameters,docs:{...F.parameters?.docs,source:{originalSource:`{
  ...DisplayHorizontalBars,
  play: async ({
    canvasElement
  }) => {
    await chartsDrawn(canvasElement);
    await waitFor(() => expect(bars(canvasElement)).toHaveLength(4));
    const labels = await waitFor(() => {
      const found = valueLabels(canvasElement);
      expect(found).toHaveLength(4);
      return found;
    });
    const surface = canvasElement.querySelector('[data-slot="chart-plot"] svg')!.getBoundingClientRect();
    await allWithin(labels, surface);
    await allWithin(axisTexts(canvasElement), surface);
    // The longest bar's label stands past its end, whole.
    const longest = bars(canvasElement).map(bar => bar.getBoundingClientRect()).reduce((a, b) => b.right > a.right ? b : a);
    const beside = labels.map(label => label.getBoundingClientRect()).find(box => box.top < longest.bottom && box.bottom > longest.top)!;
    await expect(beside.left).toBeGreaterThanOrEqual(longest.right);
  }
}`,...F.parameters?.docs?.source},description:{story:`横向柱最长那根的数也整个在图里：首页「活动失败最多的处理器」最长那根的
「59.6万」越过图框，「万」被切掉一半，读成「59.6」（2026-09-23 审查 P0-5）。
这里量每一个数值标签、每一个刻度字都在图的 \`svg\` 之内，且最长那根的标签
在它的柱子右边。`,...F.parameters?.docs?.description}}},I.parameters={...I.parameters,docs:{...I.parameters?.docs,source:{originalSource:`{
  ...DisplayBarChart,
  play: async ({
    canvasElement
  }) => {
    await chartsDrawn(canvasElement);
    await waitFor(() => expect(bars(canvasElement)).toHaveLength(4));
    const ticks = axisTicks(canvasElement, 'bottom');
    await expect(ticks).toHaveLength(4);
    // Flat: each tick a single line of text.
    await expect(ticks.every(tick => tick.getBoundingClientRect().height < 20)).toBe(true);
    await waitFor(() => expect(titleGap(canvasElement)).toBeGreaterThan(10));
  }
}`,...I.parameters?.docs?.source},description:{story:`横轴标题离刻度字一整行空：从前标题落在居中那个刻度字下面 4px，类目数为奇数
时「已成功」叠在「状态」上，读成一个两行的类目名（2026-09-23 审查 P1-2）。
平排的刻度。`,...I.parameters?.docs?.description}}},L.parameters={...L.parameters,docs:{...L.parameters?.docs,source:{originalSource:`{
  ...DisplayFailingAggregates,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await userEvent.click(await canvas.findByRole('button', {
      name: zhCN['label.layout.chart']
    }));
    await chartsDrawn(canvasElement);
    await userEvent.click(canvas.getByRole('button', {
      name: zhCN['label.analysis.visualize']
    }));
    await userEvent.click(await waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>('[data-slot="chart-options-open"]');
      if (!found) throw new Error('没有选项按钮');
      return found;
    }));
    await userEvent.click(await canvas.findByRole('tab', {
      name: zhCN['label.chart.tab.display']
    }));
    await userEvent.click(canvas.getByRole('checkbox', {
      name: zhCN['label.chart.horizontal']
    }));
    await waitFor(() => expect(canvasElement.querySelector('[data-slot="chart"]')).toHaveAttribute('data-orientation', 'vertical'));
    await chartsDrawn(canvasElement);
    await waitFor(() => expect(bars(canvasElement)).toHaveLength(9));
    // Slanted: a tick's box is taller than a line of text.
    await waitFor(() => expect(axisTicks(canvasElement, 'bottom').some(tick => tick.getBoundingClientRect().height > 30)).toBe(true));
    await waitFor(() => expect(titleGap(canvasElement)).toBeGreaterThan(10));
    const surface = canvasElement.querySelector('[data-slot="chart-plot"] svg')!.getBoundingClientRect();
    await allWithin(axisTexts(canvasElement), surface);
  }
}`,...L.parameters?.docs?.source},description:{story:`同一条规矩，刻度斜排时：九个聚合 ID 在自己那一格里放不下，斜 45°，标题
被推到它们下面，中间仍隔着一段空。长名字的柱子缺省横放（2026-09-23 审查，
\`LongNamesLieDown\`），所以这里先在显示页把「横向」取消、让它们竖着站。`,...L.parameters?.docs?.description}}},R.parameters={...R.parameters,docs:{...R.parameters?.docs,source:{originalSource:`{
  ...DisplayValueLabels,
  play: async ({
    canvasElement
  }) => {
    await chartsDrawn(canvasElement);
    await waitFor(() => expect(bars(canvasElement).length).toBeGreaterThan(10));
    const labels = await waitFor(() => {
      const found = valueLabels(canvasElement);
      expect(found.length).toBeGreaterThan(5);
      return found;
    });
    await expect(apart(labels)).toBe(true);
    const surface = canvasElement.querySelector('[data-slot="chart-plot"] svg')!.getBoundingClientRect();
    for (const label of labels) {
      const box = label.getBoundingClientRect();
      await expect(box.left).toBeGreaterThanOrEqual(surface.left - 1);
      await expect(box.right).toBeLessThanOrEqual(surface.right + 1);
      await expect(box.top).toBeGreaterThanOrEqual(surface.top - 1);
    }
    // The labels read as the ticks do: whole counts, never 「1.0」.
    await expect(labels.every(label => /^\\d+$/.test(label.textContent ?? ''))).toBe(true);
  }
}`,...R.parameters?.docs?.source},description:{story:`每根柱上的数：三十天里写得下的都写了，没有两个压在一起，也没有一个跑出图外。
数写得短——与刻度同一个读法（D21）。`,...R.parameters?.docs?.description}}},z.parameters={...z.parameters,docs:{...z.parameters?.docs,source:{originalSource:`{
  ...DisplayLineChart,
  play: async ({
    canvasElement
  }) => {
    await chartsDrawn(canvasElement);
    // Two metrics over four warehouses: a dot on every point.
    const dots = await waitFor(() => {
      const found = drawnMarks(canvasElement);
      expect(found).toHaveLength(8);
      return found.map(dot => dot.getBoundingClientRect());
    });
    const left = axisTicks(canvasElement, 'left').map(tick => tick.getBoundingClientRect());
    const right = axisTicks(canvasElement, 'right').map(tick => tick.getBoundingClientRect());
    const firstDot = Math.min(...dots.map(dot => dot.left));
    const lastDot = Math.max(...dots.map(dot => dot.right));
    await expect(firstDot - Math.max(...left.map(tick => tick.right))).toBeGreaterThan(20);
    await expect(Math.min(...right.map(tick => tick.left)) - lastDot).toBeGreaterThan(20);
    await expect(axisTitles(canvasElement).map(title => title.textContent)).toEqual(expect.arrayContaining([AMOUNT_HEADER, COUNT_HEADER]));
  }
}`,...z.parameters?.docs?.source},description:{story:`折线的每个点都有一颗圆点，两端的点离左右两根轴都有距离——线不贴着绘图区的
边（另一会话在真实服务上报：折线碰到两端、没有点），两根数值轴各有标题。`,...z.parameters?.docs?.description}}},B.parameters={...B.parameters,docs:{...B.parameters?.docs,source:{originalSource:`{
  ...DisplayHeatmapChart,
  play: async ({
    canvasElement
  }) => {
    await chartsDrawn(canvasElement);
    const frame = canvasElement.querySelector<HTMLElement>('[data-slot="chart"][data-chart="heatmap"]')!;
    // A cell is as see-through as its value is low, so every shade counts.
    const cells = await waitFor(() => {
      const found = [...frame.querySelectorAll('[data-slot="chart-plot"] svg path')].filter(path => (path.getAttribute('fill') ?? '').startsWith('rgb') && Number(path.getAttribute('fill-opacity') ?? 1) > 0);
      expect(found.length).toBe(Number(frame.getAttribute('data-marks')));
      return found.map(cell => cell.getBoundingClientRect());
    });
    const plot = frame.querySelector('[data-slot="chart-plot"]')!.getBoundingClientRect();
    const span = Math.max(...cells.map(cell => cell.right)) - Math.min(...cells.map(cell => cell.left));
    await expect(span / plot.width).toBeGreaterThan(0.6);
    await expect(apart(valueLabels(frame))).toBe(true);
    await expect(valueLabels(frame).length).toBeGreaterThan(0);
  }
}`,...B.parameters?.docs?.source},description:{story:`热力图铺满它的绘图区，格子上的数两两不相交，底下有一条色标：量的是格子占了
图的大半宽，而不是挤在一角。`,...B.parameters?.docs?.description}}},V.parameters={...V.parameters,docs:{...V.parameters?.docs,source:{originalSource:`{
  ...DisplayOneBar,
  play: async ({
    canvasElement
  }) => {
    await chartsDrawn(canvasElement);
    await waitFor(() => expect(bars(canvasElement)).toHaveLength(1));
    const [bar] = bars(canvasElement);
    await expect(bar!.getBoundingClientRect().width).toBeLessThanOrEqual(48.5);
  }
}`,...V.parameters?.docs?.source},description:{story:"一组也只是一根柱子的宽，不是一整块（`BAR_MAX_WIDTH`）。",...V.parameters?.docs?.description}}},H.parameters={...H.parameters,docs:{...H.parameters?.docs,source:{originalSource:`{
  ...DisplayFollowUps,
  play: async ({
    canvasElement
  }) => {
    await waitFor(() => expect(bars(canvasElement)).toHaveLength(4));
    await chartsDrawn(canvasElement);
    const [bar] = bars(canvasElement);
    const box = bar!.getBoundingClientRect();
    // The pointer over the bar raises its tooltip first, as a reader's does.
    bar!.dispatchEvent(new MouseEvent('mousemove', {
      bubbles: true,
      clientX: box.left + box.width / 2,
      clientY: box.top + box.height / 2
    }));
    const tooltip = () => canvasElement.querySelector<HTMLElement>('[data-slot="chart-tooltip"]');
    await waitFor(() => expect(tooltip()).toBeVisible());
    pressMark(bar!);
    const menu = await drillMenu();
    const opened = await settled(menu);
    // The menu is the answer to the press: the tooltip steps aside rather
    // than sit over its first items.
    await waitFor(() => expect(tooltip()).not.toBeVisible());
    // And stays aside while the menu is open: the least move on the bar
    // before the menu's backdrop is up raised it again over the menu's
    // first items (2026-09-24 walk).
    bar!.dispatchEvent(new MouseEvent('mousemove', {
      bubbles: true,
      clientX: box.left + box.width / 2 + 1,
      clientY: box.top + box.height / 2
    }));
    await new Promise(resolve => setTimeout(resolve, 300));
    await expect(tooltip()).not.toBeVisible();
    // Hung from the point pressed — an edge of the menu at it — not from
    // the chart's corner.
    const x = box.left + box.width / 2;
    const y = box.top + box.height / 2;
    const near = (a: number, b: number) => Math.abs(a - b) < 24;
    await expect(near(opened.left, x) || near(opened.right, x)).toBe(true);
    await expect(near(opened.top, y) || near(opened.bottom, y)).toBe(true);
    await expect(within(menu).getAllByRole('menuitem').map(item => item.textContent)).toContain(zhCN['label.drill.records']);
    await userEvent.keyboard('{Escape}');
  }
}`,...H.parameters?.docs?.source},description:{story:`按下一根柱子，追问菜单挂在按下的那一点上（D20 追问）：柱子是图库画的，
按下交出的是这一组与指针的位置，与表格的一行交出的是同一个菜单。`,...H.parameters?.docs?.description}}},U.parameters={...U.parameters,docs:{...U.parameters?.docs,source:{originalSource:`{
  ...DisplayDailyNewestFirst,
  play: async ({
    canvasElement
  }) => {
    await chartsDrawn(canvasElement);
    await waitFor(() => expect(bars(canvasElement).length).toBeGreaterThan(10));
    const ticks = await waitFor(() => {
      const found = axisTicks(canvasElement, 'bottom');
      expect(found.length).toBeGreaterThan(2);
      return found.sort((a, b) => a.getBoundingClientRect().left - b.getBoundingClientRect().left);
    });
    // A time axis writes its ticks short and flat (audit P1-1): the year on
    // the first alone — these thirty days are all of one year — and each
    // tick one line of text rather than a slant.
    const written = ticks.map(tick => tick.textContent ?? '');
    await expect(written[0]).toMatch(/^\\d{4}年\\d{1,2}月\\d{1,2}日$/);
    await expect(written.slice(1).every(text => /^\\d{1,2}月\\d{1,2}日$/.test(text))).toBe(true);
    await expect(ticks.every(tick => tick.getBoundingClientRect().height < 20)).toBe(true);
    // Read with the year the first tick names, a day later each.
    const [year] = dayOf(written[0]);
    const drawn = written.map(text => {
      const parts = dayOf(text);
      return parts.length === 3 ? parts : [year, ...parts];
    });
    await expect(runsForward(drawn)).toBe(true);
    await userEvent.click(within(canvasElement).getByRole('button', {
      name: zhCN['label.layout.table']
    }));
    const table = await findDataTable(canvasElement);
    // The time dimension's header says what one row spans: 「创建时间（按日）」
    // (2026-09-23 audit) — a column of dates does not say it alone.
    const listed = readColumn(table, formatMessage(zhCN, 'label.analysis.dated.DAY', {
      field: '创建时间'
    })).map(dayOf);
    await expect(listed.length).toBeGreaterThan(10);
    await expect(runsForward([...listed].reverse())).toBe(true);
  }
}`,...U.parameters?.docs?.source},description:{story:`时间轴从左往右走，不管视图怎么排序。

「每日新增失败」按日倒序存着——表格今天在最上面——而同一批行照着这个顺序画成
柱，今天落在原点、昨天在它右边，整张图读反了（2026-09-23 审查）。这里量画出来
的横轴：刻度按屏幕上的左右排好，读出来的日子一天比一天晚；再切到表格，同一批
日子是倒着的——表格仍是视图自己的顺序。`,...U.parameters?.docs?.description}}},W.parameters={...W.parameters,docs:{...W.parameters?.docs,source:{originalSource:`{
  ...DisplayDailyTrendCard,
  play: async ({
    canvasElement
  }) => {
    const reading = await waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>('[data-slot="chart-reading"] table');
      if (!found) throw new Error('指标卡旁边没有读屏表');
      const days = [...(found as HTMLTableElement).tBodies[0].rows].map(row => dayOf(row.cells[0]?.textContent ?? ''))
      // The headline and its comparison are rows too; a day has three
      // numbers in it, and neither of those names one.
      .filter(day => day.length === 3);
      expect(days.length).toBeGreaterThan(10);
      return days;
    });
    await expect(runsForward(reading)).toBe(true);
  }
}`,...W.parameters?.docs?.source},description:{story:`指标卡的迷你趋势同样从最早的一天画起：它没有刻度，所以读的是图旁边那张读屏
表——它与那根线出自同一份投影，从前它和线一起倒着走。`,...W.parameters?.docs?.description}}},K.parameters={...K.parameters,docs:{...K.parameters?.docs,source:{originalSource:`{
  ...DisplayDailyTrendCard,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    const reading = await trendReading(canvasElement);

    // The last day in the trend is over (the waybills end on the 20th), so
    // it is the headline, named over it, its number the trend's own.
    const [lastDay, lastCount] = reading[reading.length - 1]!;
    await waitFor(() => expect(cardSlot(canvasElement, 'metric-period')).toHaveTextContent(lastDay!));
    await expect(cardSlot(canvasElement, 'metric-value')).toHaveTextContent(lastCount!);

    // The change: its direction on the element, its tone agreeing with it,
    // and what it is measured against in words.
    const change = cardSlot(canvasElement, 'metric-change')!;
    await expect(change).toHaveTextContent(zhCN['label.chart.change.against']);
    const direction = change.getAttribute('data-direction');
    const tone = change.querySelector('[data-slot="badge"]')?.getAttribute('data-tone');
    await expect({
      up: 'success',
      down: 'danger',
      flat: 'neutral'
    }[direction ?? '']).toBe(tone);

    // Switched to the whole in the options: the sum of the days.
    const before = aggregateCalls.current;
    await userEvent.click(canvas.getByRole('button', {
      name: zhCN['label.analysis.visualize']
    }));
    await userEvent.click(await waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>('[data-slot="chart-options-open"]');
      if (!found) throw new Error('没有选项按钮');
      return found;
    }));
    const headline = await waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>('[data-slot="metric-headline"]');
      if (!found) throw new Error('选项里没有「大数字」');
      return found;
    });
    await userEvent.click(within(headline).getByRole('button', {
      name: zhCN['label.chart.headline.whole']
    }));
    await waitFor(() => expect(cardSlot(canvasElement, 'metric-period')).toHaveTextContent(zhCN['label.chart.period.whole']));
    const sum = reading.reduce((total, [, count]) => total + Number(count!.replace(/[^\\d]/g, '')), 0);
    await waitFor(() => expect(cardSlot(canvasElement, 'metric-value')).toHaveTextContent(String(sum)));
    await expect(cardSlot(canvasElement, 'metric-change')).toBeNull();
    // The whole is its own question, asked once.
    await expect(aggregateCalls.current).toBeGreaterThan(before);
    await expect(headline).toHaveTextContent(zhCN['label.chart.headline.whole.hint']);

    // And back: the last day again, redrawn from the rows on screen.
    await userEvent.click(within(headline).getByRole('button', {
      name: zhCN['label.chart.headline.last']
    }));
    await waitFor(() => expect(cardSlot(canvasElement, 'metric-period')).toHaveTextContent(lastDay!));
    await expect(cardSlot(canvasElement, 'metric-change')).not.toBeNull();
  }
}`,...K.parameters?.docs?.source},description:{story:`带走势的指标卡读最后一期（2026-09-23 用户拍板，审查 P1-6）：大数字上方写它是
哪一天，数就是迷你线上那一天的数（从前是全部时间的合计，迷你线却只画截断后的
那些天，屏幕上不说两者跨度不同）；下面一枚带色的徽标写较上一期的变化，方向与
颜色一致。可视化选项里把「大数字」换成「全部」，上方改写「范围内全部」、数变成
各天之和、变化不再画；换回「最后一期」又是那一天。`,...K.parameters?.docs?.description}}},q.parameters={...q.parameters,docs:{...q.parameters?.docs,source:{originalSource:`{
  ...DisplayDailyQuietDays,
  play: async ({
    canvasElement
  }) => {
    await chartsDrawn(canvasElement);
    const read = await waitFor(() => {
      const found = canvasElement.querySelector<HTMLTableElement>('[data-slot="chart-reading"] table');
      if (!found) throw new Error('折线旁边没有读屏表');
      const rows = [...found.tBodies[0].rows].map(row => ({
        day: dayOf(row.cells[0]?.textContent ?? ''),
        count: Number((row.cells[1]?.textContent ?? '').replace(/[^\\d]/g, ''))
      }));
      expect(rows.length).toBeGreaterThan(10);
      return rows;
    });
    // One row a day, not one skipped.
    const DAY = 86_400_000;
    const at = read.map(({
      day: [year, month, date]
    }) => Date.UTC(year!, month! - 1, date));
    await expect(at.every((ms, index) => index === 0 || ms - at[index - 1]! === DAY)).toBe(true);
    // The quiet days are there as 0, and the busy ones still add up to the
    // ten waybills sent to the two cities.
    await expect(read.filter(row => row.count === 0).length).toBeGreaterThan(5);
    await expect(read.reduce((sum, row) => sum + row.count, 0)).toBe(10);

    // A dot a day, evenly spaced: a quiet day is a step along the axis.
    const dots = await waitFor(() => {
      const found = drawnMarks(canvasElement);
      expect(found).toHaveLength(read.length);
      return found.map(dot => {
        const box = dot.getBoundingClientRect();
        return (box.left + box.right) / 2;
      });
    });
    const steps = dots.slice(1).map((x, index) => x - dots[index]!);
    await expect(Math.max(...steps) - Math.min(...steps)).toBeLessThan(1);
  }
}`,...q.parameters?.docs?.source},description:{story:`没单的日子也占一格，读作 0。

「每日重试成功」的横轴从 8/20 直接接到 8/23：画出来的只有有行的那几天，点与点
挨得一样近，折线把中间三天的 0 抹成一道斜坡（2026-09-23 图表审查 P0-4）。这里
只看发往杭州、上海的运单，只有几天有单：量读屏表——它与那条线出自同一份投影
——一行一天、一天不缺，没单的日子写 0，加起来仍是那十单；再量画出来的点，
一天一颗，左右等距。`,...q.parameters?.docs?.description}}},et.parameters={...et.parameters,docs:{...et.parameters?.docs,source:{originalSource:`{
  ...DisplayTenCities,
  play: async ({
    canvasElement
  }) => {
    await chartsDrawn(canvasElement);
    const drawn = await waitFor(() => {
      const paths = slicesInOrder(canvasElement);
      expect(paths).toHaveLength(8);
      return paths.map(path => getComputedStyle(path).fill);
    });
    await expect(new Set(drawn).size).toBe(8);
    // The remainder is grey — no hue to speak of — and every slot has one.
    const chroma = drawn.map(fill => toOklch(fill)?.c ?? 0);
    await expect(chroma.at(-1)).toBeLessThan(0.02);
    await expect(chroma.slice(0, -1).every(c => c > 0.1)).toBe(true);
    await expect(legendNames(canvasElement)).toContain(zhCN['label.chart.other']);
  }
}`,...et.parameters?.docs?.source},description:{story:`十个目的城市，八种颜色，不重复。

色板从前只有五色、循环取用，第六个类目起与前面的同色——两片一个颜色，图例分
不出谁是谁（2026-09-23 审查）。现在色板八色，饼图在第八片把尾巴并进「其他」：
这里量浏览器真正画出来的填充色，八片八种；最后一片是「其他」，它是灰的，不占
任何一个类目的颜色。`,...et.parameters?.docs?.description}}},at.parameters={...at.parameters,docs:{...at.parameters?.docs,source:{originalSource:`{
  ...DisplayFollowUps,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await waitFor(() => expect(bars(canvasElement)).toHaveLength(4));

    // 第三根是华南：结果按仓库分的四组，顺序就是画上去的顺序。
    await chartsDrawn(canvasElement);
    pressMark(bars(canvasElement)[2]!);
    const menu = await drillMenu();
    await expect(within(menu).getByText(SOUTH)).toBeVisible();
    await expect(within(menu).getAllByRole('menuitem').map(item => item.textContent)).toEqual([zhCN['label.drill.records'], zhCN['label.drill.split'], zhCN['label.drill.focus']]);
    await userEvent.click(within(menu).getByRole('menuitem', {
      name: zhCN['label.drill.records']
    }));

    // 记录视图，不是聚合：华南的两单，按明细列出来。
    const table = await findDataTable(canvasElement);
    await waitFor(() => expect(readColumn(table, '订单号')).toEqual(['SO-1004', 'SO-1005']));
    const line = await saysEachThingOnce(canvasElement, titled('订单', SOUTH), SOUTH);
    const ran = aggregateCalls.current;
    await userEvent.click(within(line).getByRole('button', {
      name: BACK
    }));
    await waitFor(() => expect(bars(canvasElement)).toHaveLength(4));
    await expect(canvas.getByRole('heading', {
      level: 2,
      name: '仓库金额分布'
    })).not.toHaveAttribute('data-dirty');
    await expect(document.body.querySelector('[data-slot="origin-bar"]')).toBeNull();
    await expect(aggregateCalls.current).toBe(ran);
  }
}`,...at.parameters?.docs?.source},description:{story:`追问（D20 Ⅳ）：按下一根柱子，弹出这一组的三项。

菜单没有自己的触发控件——按下去的那根柱子就是触发——所以它是真的被那根柱子
的点击打开的，而不是被某个按钮打开的；标题是这一组的条件，用的是「正在显示」
那条用的同一套词。

「查看这些记录」在同一个工作台里开出一个未保存的记录视图，叫「订单 · 仓库
是 华南」——它是订单里华南那一组；标题栏下一颗「返回 仓库金额分布」，条件
只在「正在显示」那条上，编辑器收着；下面是华南那两单。按「返回」回到原来
那次聚合结果——图还在，没有重跑。`,...at.parameters?.docs?.description}}},ot.parameters={...ot.parameters,docs:{...ot.parameters?.docs,source:{originalSource:`{
  ...DisplayPieChart,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await chartsDrawn(canvasElement);
    // Named once the legend has listed every slice, not as the first one
    // lands: under load the pie drew before its legend had all three.
    const before = await waitFor(() => {
      const names = slices(canvasElement).map(slice => slice.name);
      expect(names).toHaveLength(3);
      expect(names.every(name => name !== null)).toBe(true);
      return names;
    });
    pressMark(slicesInOrder(canvasElement)[0]!);
    const menu = await drillMenu();
    await expect(within(menu).getAllByRole('menuitem').map(item => item.textContent)).toEqual([zhCN['label.drill.split'], zhCN['label.drill.focus']]);
    await userEvent.click(within(menu).getByRole('menuitem', {
      name: zhCN['label.drill.focus']
    }));

    // 图上只剩这一组；它是一个自己的视图，每件事只说一遍。
    await waitFor(() => expect(slices(canvasElement).map(slice => slice.name)).toEqual(['华南']));
    const line = await saysEachThingOnce(canvasElement, titled('仓库金额分布', SOUTH), SOUTH);
    const ran = aggregateCalls.current;
    await userEvent.click(within(line).getByRole('button', {
      name: BACK
    }));

    // 原来那次结果，原样回来：不重跑，也没有什么要保存的。
    await waitFor(() => expect(slices(canvasElement).map(slice => slice.name)).toEqual(before));
    await expect(canvas.getByRole('heading', {
      level: 2,
      name: '仓库金额分布'
    })).not.toHaveAttribute('data-dirty');
    await expect(document.body.querySelector('[data-slot="origin-bar"]')).toBeNull();
    await expect(aggregateCalls.current).toBe(ran);
  }
}`,...ot.parameters?.docs?.source},description:{story:`同一个菜单，从一枚扇区上弹出来——图表家族换了，手势没换。

这个工作台只列分析视图，所以没有「查看这些记录」：下钻开出来的是记录视图，
开不出来的地方就不摆这一项。「只看这一组」是同一个问题只问这一组，开在
原来那个旁边（2026-09-23 审查）：一个未保存的分析视图，叫「仓库金额分布 ·
仓库 是 华南」，图上只剩华南，和「查看这些记录」一样有一颗「返回」——
按下去是原来那次结果，四个仓库都在，不重跑，原来那个视图也没被改脏。`,...ot.parameters?.docs?.description}}},st.parameters={...st.parameters,docs:{...st.parameters?.docs,source:{originalSource:`{
  ...DisplayFreightBands,
  play: async ({
    canvasElement
  }) => {
    const bands = ['¥0～500', '¥500～1,000', '¥1,000～1,500'];
    await chartsDrawn(canvasElement);
    await waitFor(() => expect(bars(canvasElement)).toHaveLength(3));
    const ticks = await waitFor(() => {
      const found = axisTicks(canvasElement, 'bottom').sort((a, b) => a.getBoundingClientRect().left - b.getBoundingClientRect().left).map(tick => tick.textContent);
      expect(found).toHaveLength(3);
      return found;
    });
    await expect(ticks).toEqual(bands);
    const reading = canvasElement.querySelector<HTMLElement>('[data-slot="chart-reading"] table');
    await expect(reading).not.toBeNull();
    for (const band of bands) await expect(within(reading!).getByText(band)).toBeInTheDocument();
    await userEvent.click(within(canvasElement).getByRole('button', {
      name: zhCN['label.layout.table']
    }));
    const table = await findDataTable(canvasElement);
    await waitFor(() => expect(readColumn(table, '运费')).toEqual(bands));
    const row = canvasElement.querySelector<HTMLTableRowElement>('tr[data-pickable]');
    await userEvent.click(row!.cells[1]!);
    const menu = await drillMenu();
    await expect(menu.querySelector('[data-slot="drill-group"]')).toHaveTextContent(new RegExp(\`^\${formatMessage(zhCN, 'label.drill.bucket', {
      field: '运费',
      bucket: bands[0]
    })}$\`));
  }
}`,...st.parameters?.docs?.source},description:{story:`运费区间读成一段一段（2026-09-23 真实后端走查）。

按 500 一档分组，一档的键是它的下界，从前横轴与表格读成「¥0.00」「¥500.00」，
说不出一行是哪一段。这里量画出来的横轴、读屏表、切到表格后的那一列与按下
一行弹出的追问菜单标题：都读成「¥0～500」「¥500～1,000」「¥1,000～1,500」。`,...st.parameters?.docs?.description}}},ct.parameters={...ct.parameters,docs:{...ct.parameters?.docs,source:{originalSource:`{
  ...DisplayDailyNewestFirst,
  args: {
    ...DisplayDailyNewestFirst.args,
    layout: 'table'
  },
  play: async ({
    canvasElement
  }) => {
    const row = await waitFor(() => {
      const found = canvasElement.querySelector<HTMLTableRowElement>('tr[data-pickable]');
      if (!found) throw new Error('结果还没有行');
      return found;
    });
    const day = row.cells[0]!.textContent ?? '';
    await expect(day).toMatch(/^\\d{4}年\\d{1,2}月\\d{1,2}日$/);
    const group = formatMessage(zhCN, 'label.filter.period', {
      field: '创建时间',
      period: day
    });
    await userEvent.click(row.cells[1]!);
    const menu = await drillMenu();
    await expect(menu.querySelector('[data-slot="drill-group"]')).toHaveTextContent(new RegExp(\`^\${group}$\`));
    await userEvent.click(within(menu).getByRole('menuitem', {
      name: zhCN['label.drill.focus']
    }));
    await originBar();
    await expect(within(canvasElement).getByRole('heading', {
      level: 2,
      name: titled('运单分析', group)
    })).toBeVisible();
    const applied = within(canvasElement).getByRole('region', {
      name: zhCN['label.applied.title']
    });
    await expect(within(applied).getByText(group)).toBeVisible();
  }
}`,...ct.parameters?.docs?.source},description:{story:`按日分组的一行，菜单标题读作表格那一格读的样子——「创建时间 在 2026年9月
21日」——而不是它背后那两个精确到毫秒的时刻（2026-09-23 审查）。只看这一组
开出来的视图也照这个说法起名，它的「正在显示」也是同一句话：一个条件一种
说法（2026-09-23 审查 P2）。`,...ct.parameters?.docs?.description}}},lt.parameters={...lt.parameters,docs:{...lt.parameters?.docs,source:{originalSource:`{
  ...DisplayFollowUps,
  args: {
    ...DisplayFollowUps.args,
    layout: 'table'
  },
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    const table = await findDataTable(canvasElement);
    await waitFor(() => expect(readColumn(table, '仓库')).toEqual(['华东', '华北', '华南', '西南']));

    // 表格布局是键盘走的那条路（F10）：行能聚焦，回车弹出同一个菜单。
    const row = canvasElement.querySelectorAll<HTMLElement>('tr[data-pickable]')[2];
    await expect(row).toHaveAttribute('aria-haspopup', 'menu');
    row.focus();
    await userEvent.keyboard('{Enter}');
    const menu = await drillMenu();
    await userEvent.hover(within(menu).getByRole('menuitem', {
      name: zhCN['label.drill.split']
    }));
    // 已经分了的那一维不在里面：按它再细分分不出东西来。
    const split = await waitFor(() => {
      const found = document.body.querySelector<HTMLElement>('[data-slot="dropdown-menu-sub-content"]');
      if (!found) throw new Error('子菜单没有展开');
      return found;
    });
    await expect(within(split).getAllByRole('menuitem').map(item => item.textContent)).toEqual(['状态']);
    await userEvent.click(within(split).getByRole('menuitem', {
      name: '状态'
    }));
    const after = await findDataTable(canvasElement);
    await waitFor(() => expect(readColumn(after, '状态')).toEqual(['已发运', '待出库']));
    const line = await saysEachThingOnce(canvasElement, titled('仓库金额分布', SOUTH), SOUTH);
    const ran = aggregateCalls.current;
    await userEvent.click(within(line).getByRole('button', {
      name: BACK
    }));

    // 原来那次按仓库分的结果，原样回来：不重跑，标题栏也没有未保存的改动。
    await waitFor(async () => expect(readColumn(await findDataTable(canvasElement), '仓库')).toEqual(['华东', '华北', '华南', '西南']));
    await expect(canvas.getByRole('heading', {
      level: 2,
      name: '仓库金额分布'
    })).not.toHaveAttribute('data-dirty');
    await expect(document.body.querySelector('[data-slot="origin-bar"]')).toBeNull();
    await expect(aggregateCalls.current).toBe(ran);
  }
}`,...lt.parameters?.docs?.source},description:{story:`「按其他维度细分…」是一层子菜单，而子菜单在真浏览器里是**悬停**展开的（点一下
反而是在开与关之间来回）——这是只有真指针验得了的一条，jsdom 里点开与悬停
展开是同一回事。

拆完之后是同一个问题换一个维度问：范围收到这一组，维度换成状态，上一维度
的名字从排序、表列与图表槽位里一并退场（\`analysis/drill.ts\` 的 \`splitBy\`）。
它和另外两项一样开在旁边（用户 2026-09-23 拍板）：一个未保存的分析视图，
叫「仓库金额分布 · 仓库 是 华南」，带「返回」；按下去是原来那次按仓库分的
结果，不重跑，原来那个视图也没被改脏。`,...lt.parameters?.docs?.description}}},ut.parameters={...ut.parameters,docs:{...ut.parameters?.docs,source:{originalSource:`{
  ...DisplayFollowUps,
  args: {
    ...DisplayFollowUps.args,
    layout: 'table'
  },
  play: async ({
    canvasElement
  }) => {
    const table = await findDataTable(canvasElement);
    await waitFor(() => expect(readColumn(table, '仓库')).toHaveLength(4));
    const width = table.getBoundingClientRect().width;
    const row = canvasElement.querySelectorAll<HTMLTableRowElement>('tr[data-pickable]')[1]!;

    // A pointer on the row's second cell: the menu opens under that cell,
    // as wide as its words and never as wide as the row.
    const cell = row.cells[1]!;
    await userEvent.click(cell);
    let box = await settled(await drillMenu());
    const pressed = cell.getBoundingClientRect();
    await expect(box.width).toBeGreaterThanOrEqual(224 - 0.5);
    await expect(box.width).toBeLessThanOrEqual(320 + 0.5);
    await expect(box.width).toBeLessThan(width / 2);
    await expect(box.top).toBeGreaterThanOrEqual(pressed.bottom);
    await expect(box.left).toBeLessThanOrEqual(pressed.right);
    await expect(box.right).toBeGreaterThanOrEqual(pressed.left);
    await userEvent.keyboard('{Escape}');
    await waitFor(() => expect(document.body.querySelector('[data-slot="drill-menu"]')).toBeNull());
    // Handed back to the row it came from, which is where Enter is pressed.
    await waitFor(() => expect(document.activeElement).toBe(row));

    // A key has no point: the row's first cell, the menu's start on its start.
    await userEvent.keyboard('{Enter}');
    box = await settled(await drillMenu());
    const first = row.cells[0]!.getBoundingClientRect();
    await expect(Math.abs(box.left - first.left)).toBeLessThan(1);
    await expect(box.top).toBeGreaterThanOrEqual(first.bottom);
    await expect(box.width).toBeLessThan(width / 2);
    await userEvent.keyboard('{Escape}');
  }
}`,...ut.parameters?.docs?.source},description:{story:`追问菜单按自己的字那么宽，挂在按下去的那一格下面（2026-09-23 审查）。

它从前锚在整行上，而弹层配方的宽是 \`--anchor-width\`，于是菜单和整张表一样
宽——横在结果上的一条带子。这里量两条路：指针按在一格上，菜单从那一格下面
弹出、宽度不到表的一半；键盘在行上回车，菜单挂在这一行的第一格下面，左边
与它对齐。`,...ut.parameters?.docs?.description}}},dt.parameters={...dt.parameters,docs:{...dt.parameters?.docs,source:{originalSource:`{
  ...DisplayTwoMetrics,
  play: async ({
    canvasElement
  }) => {
    await chartsDrawn(canvasElement);
    await waitFor(() => expect(bars(canvasElement)).toHaveLength(8));
    // The count has an axis of its own on the right, beside the amount's.
    await waitFor(() => expect(axisTicks(canvasElement, 'right').length).toBeGreaterThan(1));
    await expect(axisTicks(canvasElement, 'left').length).toBeGreaterThan(1);
  }
}`,...dt.parameters?.docs?.source}}},ft.parameters={...ft.parameters,docs:{...ft.parameters?.docs,source:{originalSource:`{
  ...DisplayTableWithTotals,
  play: async ({
    canvasElement
  }) => {
    const table = await within(canvasElement).findByRole('table');
    await waitFor(() => expect(readColumn(table, '仓库')).toEqual(['华东', '华北', '华南', '西南']));
    // A metric is headed by its two parts, never by the alias the query
    // carried: 「金额的总和」, and a count of records by what it counts.
    await expect(readColumn(table, COUNT_HEADER)).toEqual(['2', '1', '2', '1']);
    await expect(readColumn(table, AMOUNT_HEADER).map(amountOf)).toEqual([1920, 2450, 4880, 980]);
    // The totals row comes from its own ungrouped query over the same rows.
    await expect(readTotal(table, COUNT_HEADER)).toBe('6');
    await expect(amountOf(readTotal(table, AMOUNT_HEADER))).toBe(10230);
  }
}`,...ft.parameters?.docs?.source}}},pt.parameters={...pt.parameters,docs:{...pt.parameters?.docs,source:{originalSource:`{
  ...DisplayPieChart,
  play: async ({
    canvasElement
  }) => {
    // The two smallest warehouses merge into one slice.
    await waitFor(() => expect(slices(canvasElement).map(slice => slice.name)).toEqual(['华南', '华北',
    // The tail the kernel merges is named by the catalogue in force.
    zhCN['label.chart.other']]));
    // Drawn, not merely present: the legend alone is not a pie.
    await expect(slices(canvasElement).every(slice => slice.drawn)).toBe(true);
  }
}`,...pt.parameters?.docs?.source}}},ht.parameters={...ht.parameters,docs:{...ht.parameters?.docs,source:{originalSource:`{
  ...DisplayPieChart,
  decorators: [Story => <div style={{
    width: 414
  }}>
        <Story />
      </div>],
  play: async ({
    canvasElement
  }) => {
    await chartsDrawn(canvasElement);
    const frame = canvasElement.querySelector<HTMLElement>('[data-slot="chart"]')!;
    await expect(frame.getBoundingClientRect().width).toBeLessThan(480);
    await expect(frame).toHaveAttribute('data-legend', 'bottom');
    const plot = frame.querySelector('[data-slot="chart-plot"]')!.getBoundingClientRect();
    const legend = frame.querySelector('[data-slot="chart-legend"]')!.getBoundingClientRect();
    await expect(legend.top).toBeGreaterThanOrEqual(plot.bottom - 1);

    // Each label outside a slice is a whole share, inside the drawing.
    const labels = valueLabels(canvasElement).filter(label => label.getBoundingClientRect().width > 0);
    for (const label of labels) {
      await expect((label.textContent ?? '').trim()).toMatch(WHOLE_SHARE);
      const box = label.getBoundingClientRect();
      await expect(box.left).toBeGreaterThanOrEqual(plot.left - 1);
      await expect(box.right).toBeLessThanOrEqual(plot.right + 1);
    }
    // The legend holds every share, whatever the labels could say.
    const shares = [...frame.querySelectorAll('[data-slot="chart-legend-item"]')].map(item => item.lastElementChild?.textContent ?? '');
    await expect(shares).toEqual(['47.7%', '23.9%', '28.3%']);
  }
}`,...ht.parameters?.docs?.source},description:{story:`手机宽度（414）上的饼图（2026-09-23 图表审查 P0-6）：图例原来贴在饼的右侧，
占掉两成宽度，饼挤在剩下的地方，片外的占比没处写——47.7% 被库截成「4」，
28.3% 成了「28....」。一个「4」也是一个数，比不写还糟。现在图框窄于 480px
时图例到饼的下方，饼拿到整个宽度；片外标签要么整条写得下，要么不写（饼先
让出一些位置，让不出就交给图例与提示框）。这里量：图例在绘图区下面，每条
片外标签都是完整的占比、都在绘图区里，图例里三片的占比都在。`,...ht.parameters?.docs?.description}}},gt.parameters={...gt.parameters,docs:{...gt.parameters?.docs,source:{originalSource:`{
  ...DisplayPinnedCategoryColor,
  play: async ({
    canvasElement
  }) => {
    await waitFor(() => expect(slices(canvasElement)).toHaveLength(3));
    const [south, ...others] = slices(canvasElement);
    // Pinned as \`#7c3aed\`, handed to the drawing as the colour it is.
    await expect(south).toEqual({
      name: '华南',
      fill: 'rgb(124, 58, 237)',
      drawn: true
    });
    for (const slice of others) await expect(slice.fill).not.toBe('rgb(124, 58, 237)');
  }
}`,...gt.parameters?.docs?.source}}},vt.parameters={...vt.parameters,docs:{...vt.parameters?.docs,source:{originalSource:`{
  ...DisplayCutShort,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await waitFor(() => expect(slices(canvasElement)).toHaveLength(2));
    await expect(slices(canvasElement).map(slice => slice.name)).toEqual(['华南', '华北']);
    await expect(slices(canvasElement).every(slice => slice.drawn)).toBe(true);
    // The shares are of the two shown, and the pie says so where its key is.
    await expect(canvasElement.querySelector('[data-slot="pie-measure"]')).toHaveTextContent(zhCN['label.chart.share-basis']);

    // The strip, not the result's live region: both are \`status\`. It is
    // drawn just above the chart it is about (2026-09-23 audit), not on the
    // status line a screen away — and a pie keeps it even sorted by a
    // metric, since its slices are shares of what is shown.
    const strip = await findStrip(canvas);
    await expect(strip).toHaveTextContent(CUT_SHORT);
    await expect(strip.closest('[data-slot="analysis-cut-short"]')).not.toBe(null);
    await expect(canvasElement.querySelector('[data-slot="status-line"]')).not.toHaveTextContent(CUT_SHORT);
  }
}`,...vt.parameters?.docs?.source},description:{story:`A pie drawn from part of a grouping. Two of the four warehouses are on the
chart, and each slice's share is of those two — which is exactly the
reading a pie invites and exactly the one that is wrong here. The query
asked for three rows and got three, so the line above it says there are
more groups rather than guessing that there might be.`,...vt.parameters?.docs?.description}}},yt.parameters={...yt.parameters,docs:{...yt.parameters?.docs,source:{originalSource:`{
  ...DisplayCutShortTable,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    await waitFor(() => expect(readColumn(table, '仓库')).toEqual(['华南', '华北']));
    // The totals row answers its own ungrouped query, so it still covers
    // every order — which is how two rows can add up to less than the total
    // under them without either number being wrong.
    await expect(amountOf(readTotal(table, AMOUNT_HEADER))).toBe(10230);
    // And it says, where it is, what it holds that the two rows do not
    // (2026-09-23 audit): the groups past the first two.
    await expect(canvasElement.querySelector('[data-slot="totals-hidden"]')).toHaveTextContent(formatMessage(zhCN, 'label.analysis.totals-hidden.cut', {
      limit: '2'
    }));

    // Sorted by a metric and cut at two: a ranking, which left the rest out
    // on purpose — so no 「只显示了前 2 组」 note tells its author what they
    // asked for, neither over the table nor in the status line.
    await expect(canvas.queryByText(CUT_SHORT)).toBeNull();
    await expect(canvasElement.querySelector('[data-slot="analysis-cut-short"]')).toBeNull();
  }
}`,...yt.parameters?.docs?.source},description:{story:`The same cut as rows: the table gets the same line, from the same result.`,...yt.parameters?.docs?.description}}},bt.parameters={...bt.parameters,docs:{...bt.parameters?.docs,source:{originalSource:`{
  ...DisplayTableWithTotals,
  play: async ({
    canvasElement
  }) => {
    const table = await findDataTable(canvasElement);
    await waitFor(() => expect(readColumn(table, AMOUNT_HEADER).map(amountOf)).toEqual([1920, 2450, 4880, 980]));
    for (const header of [AMOUNT_HEADER, COUNT_HEADER]) for (const cell of columnCells(table, header)) {
      await expect(getComputedStyle(cell).textAlign).toBe('right');
      await expect(getComputedStyle(cell).fontVariantNumeric).toContain('tabular-nums');
      // Right against the edge, not merely declared so: the digits end
      // where the cell's content ends, the header's name with them.
      await expect(gapOnTheRight(cell)).toBeLessThan(4);
    }
    // The dimension reads from the left: its text ends well short of the edge.
    const [, warehouse] = columnCells(table, '仓库');
    await expect(gapOnTheRight(warehouse!)).toBeGreaterThan(20);
    const heading = table.querySelector<HTMLElement>('[data-slot="totals-heading"]')!;
    const scope = heading.querySelector('[data-slot="totals-scope"]');
    await expect(scope).toBeVisible();
    await expect(scope).toHaveTextContent(zhCN['label.analysis.totals-scope']);
    // Under the word, not beside it: a line of its own.
    await expect(scope!.getBoundingClientRect().top).toBeGreaterThan(heading.getBoundingClientRect().top + parseFloat(getComputedStyle(heading).paddingTop) + 4);
    await expect((table as HTMLTableElement).tFoot!.querySelectorAll('button, a, input, [tabindex]')).toHaveLength(0);
  }
}`,...bt.parameters?.docs?.source},description:{story:`一张分析师的表（2026-09-23 审查 P1）：数字靠右、等宽数字，表头与合计格同一
条右边线；维度靠左。「合计」下面看得见一行「范围内全部记录」——它是文字，不是
控件，页脚里没有可聚焦的东西。`,...bt.parameters?.docs?.description}}},xt.parameters={...xt.parameters,docs:{...xt.parameters?.docs,source:{originalSource:`{
  ...DisplayTableWithTotals,
  play: async ({
    canvasElement
  }) => {
    const table = await findDataTable(canvasElement);
    await waitFor(() => expect(readColumn(table, '仓库')).toEqual(['华东', '华北', '华南', '西南']));
    const head = (name: string) => (table as HTMLTableElement).tHead!.rows[0]!.cells[columnIndex(table, name)]!;
    const sorted = () => [...(table as HTMLTableElement).tHead!.rows[0]!.cells].filter(cell => cell.hasAttribute('aria-sort')).map(cell => cell.getAttribute('aria-sort'));
    const amounts = () => readColumn(table, AMOUNT_HEADER).map(amountOf);

    // One stop for the whole header row.
    const buttons = [...table.querySelectorAll<HTMLElement>('thead button')];
    await expect(buttons.filter(button => button.tabIndex === 0)).toHaveLength(1);
    within(head('仓库')).getByRole('button').focus();
    await userEvent.keyboard('{ArrowRight}{ArrowRight}');
    await expect(document.activeElement).toBe(within(head(AMOUNT_HEADER)).getByRole('button'));
    await userEvent.keyboard('{Enter}');
    await waitFor(() => expect(amounts()).toEqual([980, 1920, 2450, 4880]));
    await expect(head(AMOUNT_HEADER)).toHaveAttribute('aria-sort', 'ascending');
    await userEvent.click(within(head(AMOUNT_HEADER)).getByRole('button'));
    await waitFor(() => expect(amounts()).toEqual([4880, 2450, 1920, 980]));
    await expect(head(AMOUNT_HEADER)).toHaveAttribute('aria-sort', 'descending');

    // The third press is the view's own order again, not "unsorted".
    await userEvent.click(within(head(AMOUNT_HEADER)).getByRole('button'));
    await waitFor(() => expect(readColumn(table, '仓库')).toEqual(['华东', '华北', '华南', '西南']));
    await expect(sorted()).toEqual([]);
  }
}`,...xt.parameters?.docs?.source},description:{story:`点表头排序（2026-09-23 审查 P1）：金额 升序 → 降序 → 回到视图自己的次序；
\`aria-sort\` 只在排着的那一列上。整行表头是一个 Tab 停靠点，←／→ 在列间走，
回车按下——与记录视图的表头同一个组件。`,...xt.parameters?.docs?.description}}},St.parameters={...St.parameters,docs:{...St.parameters?.docs,source:{originalSource:`{
  ...DisplayFailingProcessors,
  play: async ({
    canvasElement
  }) => {
    const table = await findDataTable(canvasElement);
    await waitFor(() => expect(readColumn(table, '处理器')).toEqual(['OrderItemReservedTrackEventProcessor', 'InventorySnapshotProjectionHandler']));
    const heads = () => [...(table as HTMLTableElement).tHead!.rows[0]!.querySelectorAll<HTMLElement>('th:not([aria-hidden])')].map(cell => {
      const box = cell.getBoundingClientRect();
      return {
        left: Math.round(box.left),
        width: Math.round(box.width)
      };
    });
    // Opened fitting its rows: the long names are whole, not cut.
    for (const cell of columnCells(table, '处理器').slice(1, 3)) await expect(cell.scrollWidth).toBeLessThanOrEqual(cell.clientWidth);
    const before = heads();
    await userEvent.click(within((table as HTMLTableElement).tHead!.rows[0]!.cells[columnIndex(table, COUNT_HEADER)]!).getByRole('button'));
    await waitFor(() => expect(readColumn(table, '处理器')).toEqual(['Mailer', 'Audit']));
    await expect(heads()).toEqual(before);
  }
}`,...St.parameters?.docs?.source},description:{story:`列宽不随结果跳（2026-09-23 审查 P1）。失败最多的两个处理器名字很长——表一
打开就合身，名字不截断；按表头把次数改成升序，留下的是「Mailer」「Audit」，
而每一列的左边与宽都和按之前一样。自动布局下处理器那一列会跟着名字缩回去，
后面每一列都往左挪。`,...St.parameters?.docs?.description}}},Ct.parameters={...Ct.parameters,docs:{...Ct.parameters?.docs,source:{originalSource:`{
  ...DisplayFailingAggregates,
  play: async ({
    canvasElement
  }) => {
    const table = await findDataTable(canvasElement);
    const ids = await waitFor(() => {
      const found = [...table.querySelectorAll<HTMLElement>('[data-slot="identifier"]')];
      if (found.length === 0) throw new Error('no identifier yet');
      return found;
    });
    await expect(ids[0]).toHaveTextContent(/^0b5f\\d{4}-7c1e-/);
    for (const id of ids) await expect(getComputedStyle(id).fontFamily).toMatch(/mono/i);
    const [, count] = columnCells(table, COUNT_HEADER);
    await expect(getComputedStyle(count!).fontFamily).not.toMatch(/mono/i);
  }
}`,...Ct.parameters?.docs?.source},description:{story:`ID 维度等宽（2026-09-23 审查 P1）：聚合 ID 在记录视图里读作可复制的值，
分析表里用与它同一个等宽字；数字列不是。`,...Ct.parameters?.docs?.description}}},Tt.parameters={...Tt.parameters,docs:{...Tt.parameters?.docs,source:{originalSource:`{
  ...DisplayLoading,
  play: async ({
    canvasElement
  }) => {
    const skeleton = await waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>('[data-slot="analysis-table-skeleton"]');
      expect(found).not.toBeNull();
      return found!;
    });
    await expect(skeleton.querySelectorAll('tr')).toHaveLength(3);
    const toolbar = resultToolbar(canvasElement)!;
    // The reading is the question's, before any row has said it.
    await expect(toolbar).toHaveTextContent('仓库');
    const applied = canvasElement.querySelector('[data-slot="applied-bar"]')!;
    await expect(applied).toBeVisible();
    const caption = canvasElement.querySelector<HTMLElement>('[data-slot="analysis-caption"]')!;
    await expect(caption.dataset.loading).toBe('');
    const before = {
      toolbar: edges(toolbar),
      applied: edges(applied),
      caption: edges(caption)
    };

    // The source answers after 1.5 s; the skeleton is itself a \`table\`, so
    // the rows are waited for by their own slot.
    await waitFor(() => expect(canvasElement.querySelector('[data-slot="analysis-table"]')).not.toBeNull(), {
      timeout: 5_000
    });
    await expect(canvasElement.querySelector('[data-slot="analysis-table-skeleton"]')).toBeNull();
    const landed = canvasElement.querySelector<HTMLElement>('[data-slot="analysis-caption"]')!;
    await expect(landed.dataset.loading).toBeUndefined();
    await expect(resultToolbar(canvasElement)).toBe(toolbar);
    await expect({
      toolbar: edges(toolbar),
      applied: edges(applied),
      caption: edges(landed)
    }).toEqual(before);
  }
}`,...Tt.parameters?.docs?.source},description:{story:`第一次的答案在路上时，框已经站好了（2026-09-23 审查 P1）。

从前结果区在数据回来之前是空白的，回来那一刻工具栏、条件带与页脚一起冒出来，
结果被往下推。这里在骨架还在时量一次工具栏、条件带与页脚的位置，数据落地后
再量一次：三者都在原地，骨架是表格那几行灰条，页脚先是一根灰条再换成那句话。`,...Tt.parameters?.docs?.description}}},Et.parameters={...Et.parameters,docs:{...Et.parameters?.docs,source:{originalSource:`{
  ...DisplayLoadingChart,
  play: async ({
    canvasElement
  }) => {
    const area = await waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>('[data-slot="analysis-chart-skeleton"]');
      expect(found).not.toBeNull();
      return found!;
    });
    // An area, not a sliver: it takes the height the chart will take.
    await expect(area.getBoundingClientRect().height).toBeGreaterThan(150);
    const toolbar = resultToolbar(canvasElement)!;
    const caption = canvasElement.querySelector('[data-slot="analysis-caption"]')!;
    const before = {
      toolbar: edges(toolbar),
      caption: edges(caption)
    };

    // The source answers after 1.5 s.
    await waitFor(() => expect(bars(canvasElement)).toHaveLength(4), {
      timeout: 5_000
    });
    await chartsDrawn(canvasElement);
    await expect({
      toolbar: edges(resultToolbar(canvasElement)!),
      caption: edges(canvasElement.querySelector('[data-slot="analysis-caption"]')!)
    }).toEqual(before);
  }
}`,...Et.parameters?.docs?.source},description:{story:`保存的是图表时，骨架是一块绘图区，工具栏与页脚同样不动。`,...Et.parameters?.docs?.description}}},Dt.parameters={...Dt.parameters,docs:{...Dt.parameters?.docs,source:{originalSource:`{
  ...DisplayEmptyResult,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await expect(await canvas.findByText(zhCN['label.analysis.empty'])).toBeVisible();
    await expect(resultToolbar(canvasElement)).toBeVisible();
    await expect(canvas.getByText(zhCN['label.analysis.empty-none'])).toBeVisible();
    const empty = canvasElement.querySelector<HTMLElement>('[data-slot="analysis-empty"]')!;
    await expect(within(empty).queryByRole('button')).toBeNull();
    await expect(canvasElement.querySelector('[data-slot="analysis-caption"]')).toHaveTextContent(/^正在显示 0 组/);
  }
}`,...Dt.parameters?.docs?.source},description:{story:`没有组落进来：工具栏还在，空状态说清是什么情况。没有任何条件时范围已是全部
记录，托盘里做什么都分不出组，所以只有标题与那一句原因，没有按钮（用户对
#1800 的裁定）；页脚照样说「正在显示 0 组」。`,...Dt.parameters?.docs?.description}}},Ot.parameters={...Ot.parameters,docs:{...Ot.parameters?.docs,source:{originalSource:`{
  ...DisplayQueryFailed,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    const alert = await canvas.findByRole('alert');
    await expect(alert).toHaveTextContent('没能加载数据：仓储服务暂时不可用');
    const toolbar = resultToolbar(canvasElement)!;
    await expect(toolbar).toBeVisible();
    // Under the toolbar, inside the result block.
    await expect(edges(alert).top).toBeGreaterThanOrEqual(edges(toolbar).bottom);
    await expect(canvasElement.querySelector('[data-slot="applied-bar"]')).toBeVisible();
    await userEvent.click(within(toolbar).getByRole('button', {
      name: zhCN['label.layout.table']
    }));
    await expect(within(toolbar).getByRole('button', {
      name: zhCN['label.layout.table'],
      pressed: true
    })).toBeVisible();
    const asked = aggregateCalls.current;
    await userEvent.click(within(alert).getByRole('button', {
      name: zhCN['label.query.retry']
    }));
    await waitFor(() => expect(aggregateCalls.current).toBeGreaterThan(asked));
  }
}`,...Ot.parameters?.docs?.source},description:{story:`查询失败：工具栏、条件带都还在，失败说在工具栏下面那一行里，用读者的话，
末尾是「重试」；表格／图表照样能切，重试会重新去问。`,...Ot.parameters?.docs?.description}}},At.parameters={...At.parameters,docs:{...At.parameters?.docs,source:{originalSource:`{
  ...DisplayTableWithTotals,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    await expect(tray(canvasElement)).toBeNull();
    await expect(trayToggle(canvasElement)).toHaveAttribute('aria-expanded', 'false');
    const opened = await openTray(canvasElement);
    await expect([...opened.querySelectorAll('[data-slot^="analysis-slot-"]')].map(slot => slot.getAttribute('data-slot'))).toEqual(['analysis-slot-range', 'analysis-slot-dimensions', 'analysis-slot-metrics', 'analysis-slot-result']);
    for (const name of [zhCN['label.analysis.slot.range'], zhCN['label.analysis.slot.dimensions'], zhCN['label.analysis.slot.metrics'], zhCN['label.analysis.slot.result']]) await expect(canvas.getByRole('region', {
      name
    })).toBeVisible();

    // One primary on the screen at most, and it is Apply (D17-3): there is
    // no Run any more, because the range and the question are one
    // execution. With auto-run on it rests until something waits for it;
    // switched off, it is the one filled button — read off the pixels,
    // since the fill is what "primary" comes to.
    await userEvent.click(autoRunBox(canvasElement));
    const apply = applyButton(canvasElement);
    await waitFor(() => expect(apply).toHaveAttribute('data-emphasis', 'primary'));
    const fill = getComputedStyle(apply).backgroundColor;
    const sharing = [...canvasElement.querySelectorAll<HTMLElement>('[data-slot="button"]')].filter(button => getComputedStyle(button).backgroundColor === fill);
    await expect(sharing.map(button => button.textContent?.trim())).toEqual([zhCN['label.filter.apply']]);
  }
}`,...At.parameters?.docs?.source},description:{story:`The tray folds where the record view's filter panel folds (D20, Q2
settled): a saved view opens folded, the title bar's 「分析」 opens it, and
what is inside is the question — range, dimensions, metrics — under one
Apply. Nothing about *how* the result is looked at is in there.`,...At.parameters?.docs?.description}}},Nt.parameters={...Nt.parameters,docs:{...Nt.parameters?.docs,source:{originalSource:`{
  ...DisplayTableWithTotals,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    const reading = () => canvasElement.querySelector<HTMLElement>('[data-slot="analysis-reading"]')?.textContent;
    const before = reading();
    const opened = await openTray(canvasElement);
    await expect(opened.querySelectorAll('[data-slot="dimension-card"]')).toHaveLength(1);
    // Apply is how the question runs in this story: with auto-run on each
    // edit below would run itself (\`RunsAsEdited\`).
    await userEvent.click(autoRunBox(canvasElement));
    await waitFor(() => expect(autoRunBox(canvasElement)).toHaveAttribute('aria-checked', 'false'));

    // A dimension from the menu of groupable fields.
    await userEvent.click(canvas.getByRole('button', {
      name: zhCN['label.analysis.add-group']
    }));
    await userEvent.click(await screen.findByRole('menuitem', {
      name: '状态'
    }));
    await waitFor(() => expect(tray(canvasElement)!.querySelectorAll('[data-slot="dimension-card"]')).toHaveLength(2));

    // A metric's summary: one list of the ways Wow can measure the field.
    const summary = canvas.getByLabelText(formatMessage(zhCN, 'label.analysis.function-of', {
      name: '金额'
    }));
    await userEvent.click(summary);
    await userEvent.click(await screen.findByRole('option', {
      name: zhCN['label.summary.fn.AVG']
    }));
    await waitFor(() => expect(summary).toHaveTextContent('平均'));

    // Edited, not run: the dot is on the one button that runs it.
    const apply = within(canvasElement.querySelector<HTMLElement>('[data-slot="analysis-tray-actions"]')!).getByRole('button', {
      name: zhCN['label.filter.apply']
    });
    await expect(apply).toHaveAttribute('data-pending');
    await userEvent.click(apply);
    await waitFor(() => expect(apply).not.toHaveAttribute('data-pending'));
    // The reading is the result's, so it only moves once the query lands.
    await waitFor(() => expect(reading()).not.toBe(before));
  }
}`,...Nt.parameters?.docs?.source},description:{story:`The tray edited: a dimension out of the menu, a metric's summary changed,
the toggle wearing the dot while the draft has not run, and one Apply that
runs everything and changes the reading on the result's first row.`,...Nt.parameters?.docs?.description}}},Ft.parameters={...Ft.parameters,docs:{...Ft.parameters?.docs,source:{originalSource:`{
  ...DisplayTableWithTotals,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    const before = await findDataTable(canvasElement);
    await waitFor(() => expect(readHeaders(before)).toEqual(['仓库', COUNT_HEADER, AMOUNT_HEADER]));
    await openTray(canvasElement);
    // One Apply runs both edits, so the table is read once, after both.
    await userEvent.click(autoRunBox(canvasElement));
    await waitFor(() => expect(autoRunBox(canvasElement)).toHaveAttribute('aria-checked', 'false'));
    await userEvent.click(canvas.getByRole('button', {
      name: zhCN['label.analysis.add-metric']
    }));
    await userEvent.click(await screen.findByRole('menuitem', {
      name: '成本'
    }));
    await userEvent.click(canvas.getByRole('button', {
      name: zhCN['label.analysis.add-group']
    }));
    await userEvent.click(await screen.findByRole('menuitem', {
      name: '状态'
    }));
    await userEvent.click(applyButton(canvasElement));
    await waitFor(async () => expect(readHeaders(await findDataTable(canvasElement))).toEqual(['仓库', COUNT_HEADER, AMOUNT_HEADER, '状态', COST_HEADER]));
    // Every row says which group it is: no two rows share a warehouse and a
    // status, and 华东 now has a row per status instead of two alike.
    const after = await findDataTable(canvasElement);
    const warehouses = readColumn(after, '仓库');
    const statuses = readColumn(after, '状态');
    const keys = warehouses.map((warehouse, row) => \`\${warehouse}|\${statuses[row]}\`);
    await expect(new Set(keys).size).toBe(keys.length);
    await expect(warehouses.filter(warehouse => warehouse === '华东').length).toBeGreaterThan(1);
    await expect(readColumn(after, COST_HEADER).every(Boolean)).toBe(true);
  }
}`,...Ft.parameters?.docs?.source},description:{story:`加进来的就是一列（2026-09-23 审查 P0-1）。这个视图钉住了
\`table.columns\`——仓库、记录数、金额——而那份列表只管顺序与宽度：托盘里
加的指标与维度跑完就在表里，接在列出的那几列后面，维度在前、指标在后。
它从前是白名单：读法说了「按仓库、状态」，表里却只有仓库一列，同一个
「华东」出现两行而没有一列分得开。`,...Ft.parameters?.docs?.description}}},Z.parameters={...Z.parameters,docs:{...Z.parameters?.docs,source:{originalSource:`{
  ...DisplayTableWithTotals,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    await openTray(canvasElement);
    const range = () => canvas.getByRole('region', {
      name: zhCN['label.analysis.slot.range']
    });
    await expect(within(range()).queryByRole('group', {
      name: zhCN['label.filter.all-conditions']
    })).toBeNull();
    await userEvent.click(within(range()).getByRole('button', {
      name: formatMessage(zhCN, 'label.analysis.conditions-mode', {
        mode: zhCN['label.filter.simple']
      })
    }));
    await userEvent.click(await screen.findByRole('menuitemradio', {
      name: zhCN['label.filter.advanced']
    }));

    // Advanced draws the root as a group, which is what carries the operator.
    await waitFor(() => expect(within(range()).getByRole('group', {
      name: zhCN['label.filter.all-conditions']
    })).toBeVisible());
  }
}`,...Z.parameters?.docs?.source},description:{story:`An analysis view can still reach advanced mode.

There is no simple/advanced *tray* (D20): capability grows out of the
slots. The one simple/advanced left is the grammar of the condition tree,
and it is stated where the conditions are — a ghost menu in the range
slot's heading. Without it, \`defaultAnalysisConfig\` starting at simple
would mean an analysis view could never express OR, NOR or a nested group
at all — a capability lost, not a tidier screen.`,...Z.parameters?.docs?.description}}},It.parameters={...It.parameters,docs:{...It.parameters?.docs,source:{originalSource:`{
  ...DisplayTableWithTotals,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    const opened = await openTray(canvasElement);
    await expect(getComputedStyle(opened).rowGap).toBe('12px');
    await expect(getComputedStyle(opened.querySelector<HTMLElement>('.grid')!).columnGap).toBe('16px');
    await expect(getComputedStyle(opened.querySelector<HTMLElement>('[data-slot="analysis-slot-metrics"]')!).rowGap).toBe('8px');
  }
}`,...It.parameters?.docs?.source},description:{story:`The steps between the tray's parts, measured.

jsdom applies no stylesheet and so can only pin the class the call site
passes (\`test/analysisTray.test.tsx\`); the pixels are this project's to
read. The ruler (\`ui/README.md\`): a slot's cards stand 8px apart, the
slots 12px, and the two columns are two blocks at 16px.`,...It.parameters?.docs?.description}}},Lt.parameters={...Lt.parameters,docs:{...Lt.parameters?.docs,source:{originalSource:`{
  ...DisplayTableWithTotals,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    const opened = await openTray(canvasElement);

    // 一、the result step, each part labelled on the screen, in Wow's order.
    const result = canvas.getByRole('region', {
      name: zhCN['label.analysis.slot.result']
    });
    const metrics = canvas.getByRole('region', {
      name: zhCN['label.analysis.slot.metrics']
    });
    await expect(result.getBoundingClientRect().top).toBeGreaterThanOrEqual(metrics.getBoundingClientRect().bottom);
    const sort = within(result).getByRole('group', {
      name: zhCN['label.sort.title']
    });
    const limitBox = within(result).getByLabelText(zhCN['label.analysis.row-limit']);
    const limit = limitBox.closest<HTMLElement>('[data-slot="analysis-limit"]')!;
    const box = (element: HTMLElement) => element.getBoundingClientRect();
    // No row kept yet: no 「只保留」 block, only 「只保留…」 on the line.
    await expect(within(result).queryByRole('group', {
      name: zhCN['label.analysis.having-title']
    })).toBeNull();
    const firstKeep = within(result).getByRole('button', {
      name: zhCN['label.analysis.having-first']
    });
    // One line: the three stand on one centre line, in Wow's order.
    const middle = (element: HTMLElement) => (box(element).top + box(element).bottom) / 2;
    await expect(Math.abs(middle(sort) - middle(limit))).toBeLessThan(1);
    await expect(Math.abs(middle(sort) - middle(firstKeep))).toBeLessThan(1);
    await expect(box(sort).right).toBeLessThanOrEqual(box(limit).left);
    await expect(box(limit).right).toBeLessThanOrEqual(box(firstKeep).left);
    // Each label beside its control, on the control's line.
    const sortLabel = sort.querySelector<HTMLElement>('[data-slot="field-label"]')!;
    await expect(box(sortLabel).bottom).toBeGreaterThan(box(sort).top);
    await expect(box(sortLabel).right).toBeLessThanOrEqual(box(sort.querySelector<HTMLElement>('button')!).left);
    // Nothing sorts the groups, so which N come back is the source's.
    const unsorted = limit.querySelector<HTMLElement>('[data-slot="limit-unsorted"]')!;
    await expect(unsorted).toBeVisible();
    await expect(unsorted).toHaveTextContent(zhCN['label.analysis.row-limit-unsorted']);
    await expect(limitBox).toHaveAttribute('aria-describedby', unsorted.id);
    await userEvent.click(firstKeep);
    const keep = await within(result).findByRole('group', {
      name: zhCN['label.analysis.having-title']
    });
    // The names are text a sighted analyst reads, not only a reader's.
    const visible = [keep.querySelector('legend'), sortLabel, result.querySelector(\`label[for="\${limitBox.id}"]\`)];
    await expect(visible.map(label => label?.textContent)).toEqual([zhCN['label.analysis.having-title'], zhCN['label.sort.title'], zhCN['label.analysis.row-limit']]);
    for (const label of visible) await expect(label).toBeVisible();
    await expect(box(keep).bottom).toBeLessThanOrEqual(box(sort).top);

    // 二、Apply rests while auto-run leaves it nothing to do. The checked
    // box wears the primary fill, which is what a filled Apply would share.
    const primary = getComputedStyle(autoRunBox(canvasElement)).backgroundColor;
    const apply = applyButton(canvasElement);
    await expect(apply).toHaveAttribute('data-emphasis', 'quiet');
    await expect(getComputedStyle(apply).backgroundColor).not.toBe(primary);
    const hint = canvasElement.querySelector<HTMLElement>('[data-slot="auto-run-hint"]')!;
    await expect(hint).toBeVisible();
    await expect(hint).toHaveTextContent(zhCN['label.analysis.auto-run-hint']);

    // A condition in the range waits for Apply, so Apply fills again.
    const range = canvas.getByRole('region', {
      name: zhCN['label.analysis.slot.range']
    });
    await userEvent.click(within(range).getByRole('button', {
      name: zhCN['label.filter.add']
    }));
    const picker = await screen.findByRole('dialog', {
      name: zhCN['label.filter.pick-fields']
    });
    for (const field of ['订单号', '仓库', '状态', '标记', '备注', '金额']) await userEvent.click(within(picker).getByRole('checkbox', {
      name: field
    }));
    await userEvent.click(within(picker).getByRole('button', {
      name: zhCN['label.filter.pick-done']
    }));
    await waitFor(() => expect(apply).toHaveAttribute('data-emphasis', 'primary'));
    await expect(getComputedStyle(apply).backgroundColor).toBe(primary);
    // And the sentence by the switch says why nothing runs on its own now.
    await expect(hint).toHaveTextContent(zhCN['label.analysis.auto-run-held']);
    await expect(hint).toHaveAttribute('data-held');

    // Two rows kept on top of six conditions: a tray taller than half.
    for (let i = 0; i < 2; i++) await userEvent.click(opened.querySelector<HTMLElement>('[data-slot="add-having"]')!);

    // 三、capped at half the work column, the slots scrolling inside and
    // the footer standing where it can be pressed.
    const main = canvasElement.querySelector<HTMLElement>('.fve-root > main')!;
    const band = canvasElement.querySelector<HTMLElement>('[data-slot="editor-band"]')!;
    const slots = opened.querySelector<HTMLElement>('[data-slot="analysis-tray-slots"]')!;
    const footer = opened.querySelector<HTMLElement>('[data-slot="analysis-tray-actions"]')!;
    await waitFor(() => expect(slots.scrollHeight).toBeGreaterThan(slots.clientHeight));
    await expect(box(band).height).toBeLessThanOrEqual(main.getBoundingClientRect().height / 2 + 1);
    await expect(box(footer).top).toBeGreaterThanOrEqual(box(band).top);
    await expect(box(footer).bottom).toBeLessThanOrEqual(box(band).bottom);
    await expect(apply).toBeVisible();
  }
}`,...Lt.parameters?.docs?.source},description:{story:`托盘读得清（2026-09-23 审查，P1）。

一、「只保留」「排序」「前 N 组」自成「结果」一步，排在维度与指标后面，每一项
都有看得见的名字，按 Wow 施加它们的顺序：排序、前 N 组与「只保留…」同在一
行（名字在控件左边，不再各占一行）；加了一条「只保留」，它的块在那一行上面。
没有排序时，前 N 组旁边说「未排序时由数据源决定取哪几组」。
二、「自动运行」开着、没有东西等应用时，应用是描边按钮，不是全屏最实的那一
颗；范围里加了条件（它要等应用）才回到实心。开关旁边那句说它管什么——范围里
有没应用的条件时，改说为什么现在不自动运行。
三、托盘封顶工作列的一半，槽在里面滚，底行（自动运行／清空／应用）不滚、总看
得见；结果不再被挤到它的下限。`,...Lt.parameters?.docs?.description}}},zt.parameters={...zt.parameters,docs:{...zt.parameters?.docs,source:{originalSource:`{
  ...DisplayTableWithTotals,
  parameters: {
    ...DisplayTableWithTotals.parameters,
    ...TRAY_VIEWPORTS
  },
  globals: {
    viewport: {
      value: 'desk'
    }
  },
  play: async ({
    canvasElement
  }) => {
    await expect(window.innerWidth).toBe(1440);
    const {
      main,
      band,
      slots,
      order,
      footer,
      result
    } = await openedTrayBoxes(canvasElement);
    const box = (element: HTMLElement) => element.getBoundingClientRect();
    await waitFor(() => expect(slots.scrollHeight).toBeLessThanOrEqual(slots.clientHeight + 1));
    await expect(box(order).bottom).toBeLessThanOrEqual(box(band).bottom);
    await expect(box(order).top).toBeGreaterThanOrEqual(box(band).top);
    await expect(box(band).height).toBeLessThanOrEqual(box(main).height / 2 + 1);
    // One row each: the result's line and the footer.
    await expect(box(order).height).toBeLessThan(40);
    await expect(box(footer).height).toBeLessThan(40);
    await expect(box(result).height).toBeGreaterThan(340);
  }
}`,...zt.parameters?.docs?.source},description:{story:`托盘在 1440×900 下不用滚就看得到「排序／前 N 组」（2026-09-23 审查 P1）。

从前结果槽每个控件的名字各占一行、「只保留」一个图例压着一颗按钮，底行三行，
托盘要滚才露出排序。现在结果槽一行、底行一行：这里量槽不需要滚、排序那一行
整个在编辑带里，托盘仍在工作列一半之内，结果块比从前高（从前约 305px）。`,...zt.parameters?.docs?.description}}},Bt.parameters={...Bt.parameters,docs:{...Bt.parameters?.docs,source:{originalSource:`{
  ...DisplayTableWithTotals,
  parameters: {
    ...DisplayTableWithTotals.parameters,
    ...TRAY_VIEWPORTS
  },
  globals: {
    viewport: {
      value: 'phone'
    }
  },
  play: async ({
    canvasElement
  }) => {
    await expect(window.innerWidth).toBe(414);
    const {
      main,
      band,
      footer,
      result
    } = await openedTrayBoxes(canvasElement);
    const box = (element: HTMLElement) => element.getBoundingClientRect();
    await expect(box(band).height).toBeLessThanOrEqual(box(main).height * 0.36);
    await expect(box(result).height).toBeGreaterThan(340);
    await expect(box(footer).bottom).toBeLessThanOrEqual(box(band).bottom + 1);
    await expect(footer.getBoundingClientRect().height).toBeLessThan(60);
    await expect(applyButton(canvasElement)).toBeVisible();
  }
}`,...Bt.parameters?.docs?.source},description:{story:`414 宽的手机上结果不再只剩约 230px（2026-09-23 审查 P1）。窄屏托盘封顶约
三分之一（\`styles.css\`），槽在里面滚、底行不滚；自动运行那句说明换到按钮下面
一整行，底行不再是三行。这里量结果块至少 340px（从前约 275px）、托盘不过
工作列的 36%、应用按钮仍在编辑带里看得见。`,...Bt.parameters?.docs?.description}}},Vt.parameters={...Vt.parameters,docs:{...Vt.parameters?.docs,source:{originalSource:`{
  ...DisplayLoading,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table', {}, {
      timeout: 5_000
    });
    await openTray(canvasElement);
    const result = () => canvasElement.querySelector<HTMLElement>('[data-slot="analysis-result"]')!;
    const before = aggregateCalls.current;
    await userEvent.click(canvas.getByRole('button', {
      name: zhCN['label.analysis.add-group']
    }));
    await userEvent.click(await screen.findByRole('menuitem', {
      name: '状态'
    }));

    // Sent — past the debounce — and not back yet: still faded, on screen.
    await waitFor(() => expect(aggregateCalls.current).toBeGreaterThan(before));
    await new Promise(resolve => setTimeout(resolve, 600));
    await expect(result()).toHaveAttribute('data-stale');
    await expect(Number(getComputedStyle(result()).opacity)).toBeLessThan(1);

    // The answer lands: two dimensions, full strength again.
    await waitFor(() => expect(result()).not.toHaveAttribute('data-stale'), {
      timeout: 5_000
    });
    await expect(readHeaders(await findDataTable(canvasElement))).toContain('状态');
  }
}`,...Vt.parameters?.docs?.source},description:{story:`慢查询在路上时旧结果一直淡着（2026-09-23 审查 P1）。从前只在改动后那 300ms
去抖里淡，查询一发出就恢复满格——一个要 1.5 秒的查询，旧的行在它回来之前
满格站着，读起来就是新答案。这里在数据源每次都慢 1.5 秒的视图上加一个维度：
查询已经发出（过了去抖）之后结果仍是淡的，浏览器真的把它画淡了；新的行落地
后恢复。`,...Vt.parameters?.docs?.description}}},Ut.parameters={...Ut.parameters,docs:{...Ut.parameters?.docs,source:{originalSource:`{
  ...DisplayBarChart,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await waitFor(() => expect(bars(canvasElement)).toHaveLength(4));
    // The list is in the column, and the panel is not.
    const list = canvasElement.querySelector<HTMLElement>('[data-slot="view-sidebar"]');
    await expect(list).not.toBeNull();
    const main = canvasElement.querySelector<HTMLElement>('.fve-root > main')!;
    const listWidth = list!.getBoundingClientRect().width;
    const mainLeft = main.getBoundingClientRect().left;
    await userEvent.click(canvas.getByRole('button', {
      name: zhCN['label.analysis.visualize']
    }));

    // The panel takes the column the list had: one column, two occupants.
    const panel = canvasElement.querySelector<HTMLElement>('[data-slot="view-panel"]')!;
    await expect(panel).toBeVisible();
    await expect(canvasElement.querySelector('[data-slot="view-sidebar"]')).toBeNull();
    // At the list's width, so the work area stays where it was: a panel a
    // size wider pushed everything right by 32px as it opened.
    await expect(panel.getBoundingClientRect().width).toBeCloseTo(listWidth, 0);
    await expect(main.getBoundingClientRect().left).toBeCloseTo(mainLeft, 0);

    // Every type the definition declares is a tile, and the table is one too.
    await expect([...panel.querySelectorAll('[data-slot="chart-tile"]')].map(tile => tile.getAttribute('data-chart-type'))).toEqual(['bar', 'line', 'area', 'combo', 'pie', 'heatmap', 'scatter', 'funnel', 'metric', 'table']);

    // One dimension that is not a date reads best as bars, and the mark says
    // so in a word; a shape a type cannot draw greys its tile and writes
    // what it lacks under it.
    const bar = chartTile(panel, 'bar');
    await expect(bar).toHaveAttribute('aria-checked', 'true');
    await expect(bar).toHaveAttribute('data-recommended');
    await expect(bar).toHaveTextContent(zhCN['label.chart.recommended']);
    const heatmap = chartTile(panel, 'heatmap');
    await expect(heatmap).toHaveAttribute('aria-disabled', 'true');
    await expect(heatmap).toHaveTextContent(zhCN['chart.fit.needs-two-dimensions']);

    // The tiles only pick; the way on to the chosen type's options is one
    // labelled button under them (2026-09-23 review: a 24px gear hanging off
    // the tile's corner was seen by nobody). A row is one height, no tile half empty.
    await expectPickerLayout(panel, 'bar');
    // And the mark keeps clear in English too, the widest word it has: the
    // same tile with "Recommended" written in it, put back afterwards.
    const mark = chartTile(panel, 'bar').querySelector<HTMLElement>('[data-slot="chart-recommended"]')!;
    mark.textContent = defaultMessages['label.chart.recommended'];
    await expectPickerLayout(panel, 'bar', false);
    mark.textContent = zhCN['label.chart.recommended'];

    // Tab after the group lands on the button; the arrows stay the group's.
    chartTile(panel, 'bar').focus();
    await userEvent.tab();
    await expect(panel.querySelector('[data-slot="chart-options-open"]')).toHaveFocus();

    // A pick is a redraw: the pie is drawn, and no aggregation went out.
    const before = aggregateCalls.current;
    await userEvent.click(chartTile(panel, 'pie'));
    await waitFor(() => expect(slices(canvasElement).length).toBeGreaterThan(0));
    await expect(slices(canvasElement).every(slice => slice.drawn)).toBe(true);
    await expect(aggregateCalls.current).toBe(before);
    // The pie measures what the bars measured: a type is how the numbers are
    // drawn, not which (audit P0-10). Its legend leads with that column's
    // title, and each slice says its share.
    await expect(canvasElement.querySelector('[data-slot="pie-measure"]')).toHaveTextContent(AMOUNT_HEADER);
    // The labels land with the sweep; the story browser does not ask for
    // less motion, so the pie sweeps as a reader's would.
    await waitFor(() => expect(valueLabels(canvasElement).some(label => /%$/.test(label.textContent ?? ''))).toBe(true), {
      timeout: 4_000
    });
    await expect(chartTile(panel, 'pie')).toHaveAttribute('aria-checked', 'true');
    // The button follows the choice: named after the pie now. A pick opens
    // nothing by itself.
    await expectPickerLayout(panel, 'pie');
    await expect(document.querySelector('[data-slot="chart-options"]')).toBeNull();
    // Nothing is waiting to be applied, so the editor's fold wears no dot.
    await expect(canvasElement.querySelector('[data-slot="editor-toggle"] [data-slot="pending-dot"]')).toBeNull();

    // The table is a tile, so "back to the table" is the same one gesture.
    await userEvent.click(chartTile(panel, 'table'));
    await canvas.findByRole('table');
    await expect(slices(canvasElement)).toHaveLength(0);
    await expect(aggregateCalls.current).toBe(before);

    // The table's options are its totals row: 「表格选项」 opens that page, and
    // back from it lands on the button the user left by.
    await expectPickerLayout(panel, 'table');
    const options = panel.querySelector<HTMLElement>('[data-slot="chart-options-open"]')!;
    await userEvent.click(options);
    const page = await waitFor(() => {
      const found = panel.querySelector<HTMLElement>('[data-slot="chart-options"]');
      if (!found) throw new Error('选项页没有打开');
      return found;
    });
    await expect(within(page).getByRole('checkbox', {
      name: zhCN['label.analysis.totals']
    })).toBeVisible();
    await userEvent.click(within(page).getByRole('button', {
      name: zhCN['label.chart.options-back']
    }));
    await waitFor(() => expect(panel.querySelector('[data-slot="chart-options-open"]')).toHaveFocus());

    // And the way back is the panel's own: the list returns to the column.
    await userEvent.click(within(panel).getByRole('button', {
      name: zhCN['label.chart.picker-back']
    }));
    await waitFor(() => expect(canvasElement.querySelector('[data-slot="view-sidebar"]')).not.toBeNull());
    await expect(canvasElement.querySelector('[data-slot="view-panel"]')).toBeNull();
  }
}`,...Ut.parameters?.docs?.source},description:{story:`可视化：结果工具栏呼出左侧栏，先选图型 (D20 屏 I).

The one thing only a browser can show about this panel is that picking a
type **draws** the new family — jsdom lays nothing out, so a pie there is
a container with no sectors in it. Beside that it holds the two halves of
the gesture that jsdom can only assert one at a time: the column changes
hands, and the source is never asked again, because the layout and the
chart draw the rows that already came back.`,...Ut.parameters?.docs?.description}}},Wt.parameters={...Wt.parameters,docs:{...Wt.parameters?.docs,source:{originalSource:`{
  ...DisplayTableWithTotals,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    await openTray(canvasElement);
    const cardMenu = (name: string) => canvas.getByRole('button', {
      name: formatMessage(zhCN, 'label.analysis.card-menu', {
        name
      })
    });

    // 改显示名：菜单 → 输入框 → 回车。
    await userEvent.click(cardMenu('仓库'));
    await userEvent.click(await screen.findByRole('menuitem', {
      name: zhCN['label.analysis.rename']
    }));
    const box = await screen.findByLabelText(formatMessage(zhCN, 'label.analysis.display-name', {
      name: '仓库'
    }));
    await userEvent.clear(box);
    await userEvent.type(box, '门店{Enter}');
    await waitFor(() => expect(canvasElement.querySelector('[data-slot="card-name"]')).toHaveTextContent('门店'));

    // 名字是问题的一部分，跑过才算数：应用之后列头与读法才改口。
    const apply = within(canvasElement.querySelector<HTMLElement>('[data-slot="analysis-tray-actions"]')!).getByRole('button', {
      name: zhCN['label.filter.apply']
    });
    await userEvent.click(apply);
    await waitFor(() => expect(canvas.getByRole('columnheader', {
      name: '门店'
    })).toBeVisible());
    await expect(canvasElement.querySelector('[data-slot="analysis-reading"]')).toHaveTextContent('门店');

    // 卡片现在按新名字自称，菜单也是——同一张卡上的两个控件不该各叫各的。
    await userEvent.click(cardMenu('门店'));
    const missing = () => screen.getByRole('menuitemcheckbox', {
      name: zhCN['label.analysis.missing-bucket']
    });
    await waitFor(() => expect(missing()).toHaveAttribute('aria-checked', 'false'));
    await userEvent.click(missing());
    // 勾选项不关菜单——设置是在读它的地方切换的。
    await waitFor(() => expect(missing()).toHaveAttribute('aria-checked', 'true'));
  }
}`,...Wt.parameters?.docs?.source},description:{story:`卡片自己的菜单（D20 屏 B）：显示名与空值单独一组。

卡片上只放问题本身的那两三个控件，别的都收进一颗按卡片命名的菜单里——
一张摆着六个控件的卡片读起来是张表单，不是一句话。改完名字，列头、结果
那句读法与图例都跟着改（\`columnTitle\`：给了名字，名字就是整个标题，后面
不再缀「的总和」）；空值单独一组是分析师的选择，勾上 Wow 才会把缺值的
记录单独归一组，而不是悄悄把它们丢掉。`,...Wt.parameters?.docs?.description}}},Gt.parameters={...Gt.parameters,docs:{...Gt.parameters?.docs,source:{originalSource:`{
  ...DisplayTableWithTotals,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    const shippedHeader = formatMessage(zhCN, 'label.analysis.metric-where', {
      metric: AMOUNT_HEADER,
      value: '已发运'
    });
    const amounts = async (header = AMOUNT_HEADER) => readColumn(await findDataTable(canvasElement), header).map(amountOf);
    const counts = async () => readColumn(await findDataTable(canvasElement), COUNT_HEADER);
    const beforeAmounts = await amounts();
    const beforeCounts = await counts();
    await openTray(canvasElement);
    const funnel = (name = AMOUNT_HEADER) => canvas.getByRole('button', {
      name: formatMessage(zhCN, 'label.analysis.condition-of', {
        name
      })
    });
    await expect(funnel()).toHaveAttribute('aria-pressed', 'false');
    await userEvent.click(funnel());
    const block = await waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>('[data-slot="card-conditions"]');
      if (!found) throw new Error('条件块没有打开');
      return found;
    });
    await expect(block).toHaveTextContent(zhCN['label.analysis.condition-title']);
    // Just opened, nothing done: nothing is red, Save is not greyed for it,
    // and the status line offers no 「打开分析」 over the open tray
    // (2026-09-23 audit P1).
    await expect(canvas.queryByRole('alert')).toBeNull();
    await expect(canvas.queryByText(zhCN['analysis.metricFilter.empty'])).toBeNull();
    await expect(canvas.queryByRole('button', {
      name: zhCN['label.analysis.open-editor']
    })).toBeNull();
    // Its way in says what it adds, and only that.
    await expect(within(block).getByRole('button', {
      name: \`\${AMOUNT_HEADER} \${zhCN['label.filter.add-condition']}\`
    })).toHaveTextContent(new RegExp(\`^\${zhCN['label.filter.add-condition']}$\`));

    // 条件用的就是范围那一套手势：字段清单勾一个，完成，再选值。
    await userEvent.click(within(block).getByRole('button', {
      name: \`\${AMOUNT_HEADER} \${zhCN['label.filter.add-condition']}\`
    }));
    const picker = await screen.findByRole('dialog', {
      name: zhCN['label.filter.pick-fields']
    });
    await userEvent.click(within(picker).getByRole('checkbox', {
      name: '状态'
    }));
    await userEvent.click(within(picker).getByRole('button', {
      name: zhCN['label.filter.pick-done']
    }));
    await userEvent.click(within(block).getByRole('combobox', {
      name: formatMessage(zhCN, 'label.filter.value-of', {
        field: '状态'
      })
    }));
    // 能装好几个值的单子写完不会自己关上，下一个手势不在它里面。
    await userEvent.click(await screen.findByRole('option', {
      name: '已发运'
    }));
    await userEvent.keyboard('{Escape}');
    await userEvent.click(within(canvasElement.querySelector<HTMLElement>('[data-slot="analysis-tray-actions"]')!).getByRole('button', {
      name: zhCN['label.filter.apply']
    }));
    // 表头按它算的是什么改了名：没有发运的地区那一格 ¥0.00 是「没有已发运
    // 的销售」，不是「没有销售」。
    await waitFor(async () => expect(await amounts(shippedHeader)).not.toEqual(beforeAmounts));
    // 只有这一个指标被收窄：记录数还是全部，名字也还是原来的。
    await expect(await counts()).toEqual(beforeCounts);
    const table = (await findDataTable(canvasElement)) as HTMLTableElement;
    const header = table.tHead!.rows[0]!.cells[columnIndex(table, shippedHeader)]!;
    // 整条条件在表头的说明里：悬停读得到，读屏也念得到。
    const described = header.querySelector('[aria-describedby]');
    const sentences = (described?.getAttribute('aria-describedby') ?? '').split(' ').map(id => canvasElement.ownerDocument.getElementById(id)?.textContent);
    await expect(sentences.join('\\n')).toContain('只算 状态');
    await expect(sentences.join('\\n')).toContain('已发运');
    // 读法那一行说的是同一个名字。
    await expect(canvasElement.querySelector('[data-slot="analysis-reading"]')).toHaveTextContent(shippedHeader);

    // 收起条件，卡片上留下那句「只算 …」——一个数的读法不该藏在图标后面。
    await userEvent.click(within(block).getByRole('button', {
      name: zhCN['label.analysis.condition-close']
    }));
    const line = await waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>('[data-slot="metric-condition-line"]');
      if (!found) throw new Error('卡片上没有「只算 …」那一句');
      return found;
    });
    await expect(line).toHaveTextContent('状态');
    await expect(line).toHaveTextContent('已发运');
    // 卡片上的控件也按新名字自称：两张同字段的卡差在条件上，名字就差在那儿。
    await expect(funnel(shippedHeader)).toHaveAttribute('data-held');
  }
}`,...Gt.parameters?.docs?.source},description:{story:`指标自己的条件（D20 屏 H）：漏斗在卡片上，条件块就是范围那一套药丸。

一个数是在哪些记录上算出来的，这件事只有两处说得清楚：算它之前，和算它的
那张卡上。所以入口是卡片上的漏斗，而不是菜单里的一项、更不是一个对话框
——条件属于它收窄的那个指标，就长在那儿；写完收起来，卡片上留下一句
「只算 …」，于是一屏卡片里两个「金额的总和」为什么不一样，读得出来。
条件是这一个指标自己的：应用之后金额跟着变，旁边的记录数一颗不落。

它也换了名字（审计 P0-3，D20 显示名）：表头、读法那一行、卡片上每个控件
都说「金额的总和 · 已发运」。从前表头还是「金额的总和」，没有发运的地区
一格 ¥0.00，读起来就是「没有销售」；而「只算 …」那一句只在托盘的卡片上，
打开一个存好的视图时托盘是收着的。表头的说明（悬停与读屏）说出整条条件。`,...Gt.parameters?.docs?.description}}},$.parameters={...$.parameters,docs:{...$.parameters?.docs,source:{originalSource:`{
  ...DisplayExpandable,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    const opened = await openTray(canvasElement);
    const cards = () => canvasElement.querySelectorAll('[data-slot="element-card"]');
    const unit = () => canvasElement.querySelector('[data-slot="counting-unit"]')?.textContent;

    // 展开夹在范围与那两列之间：它改的是问题问的是什么，不是问题的答案。
    await expect([...opened.querySelectorAll('[data-slot^="analysis-slot-"]')].map(slot => slot.getAttribute('data-slot'))).toEqual(['analysis-slot-range', 'analysis-slot-elements', 'analysis-slot-dimensions', 'analysis-slot-metrics', 'analysis-slot-result']);
    await expect(unit()).toBe(formatMessage(zhCN, 'label.analysis.unit', {
      name: '订单'
    }));
    await userEvent.click(canvas.getByRole('button', {
      name: formatMessage(zhCN, 'label.analysis.expand-into', {
        name: '明细项'
      })
    }));
    await waitFor(() => expect(cards()).toHaveLength(1));
    await expect(unit()).toBe(formatMessage(zhCN, 'label.analysis.unit', {
      name: '明细项'
    }));
    await expect(canvasElement.querySelectorAll('[data-slot="dimension-card"]')).toHaveLength(0);

    // 一条线，一次一步：能再展开的只有能力声明的下一层。
    await userEvent.click(canvas.getByRole('button', {
      name: formatMessage(zhCN, 'label.analysis.expand-into', {
        name: '批次'
      })
    }));
    await waitFor(() => expect(cards()).toHaveLength(2));
    await expect(canvasElement.querySelector('[data-slot="expand-into"]')).toBeNull();

    // 收起最外那一层，里面那层跟着走：批次只在明细项里存在。
    await userEvent.click(canvas.getAllByRole('button', {
      name: formatMessage(zhCN, 'label.analysis.collapse', {
        name: '明细项'
      })
    })[0]);
    await waitFor(() => expect(cards()).toHaveLength(0));
    await expect(unit()).toBe(formatMessage(zhCN, 'label.analysis.unit', {
      name: '订单'
    }));
  }
}`,...$.parameters?.docs?.source},description:{story:`展开（D20 屏 G）：一条链，计数单位跟着最内层走。

展开改的是「数的是什么」：展开到明细项，一行就是一个明细项，而仓库是订单
的字段——在明细项里它什么也不指，所以那个维度跟着这一步离开。「展开：…」只
给声明出来的下一步，收起一层连里面的一起带走。故事的数据源不求值
\`elements\`（\`rowSource.ts\` 明着拒绝），所以这一趟到托盘为止，不按「应用」；
查询里带出去的是什么，由 test/elementsSlot.test.tsx 与
test/analysisCompile.test.ts 钉着。`,...$.parameters?.docs?.description}}}})))()}qt();export{Ft as AddedColumnsShow,st as BandsReadAsRanges,j as BarChart,N as CaptionHoldsTheReport,St as ColumnsHoldStill,vt as CutShort,yt as CutShortTable,q as DailyHolesRunEvenly,It as EditorRowSpacing,et as EightColoursThenOther,Dt as EmptyResult,Vt as FadesUntilAnswered,ot as FollowUpFocus,H as FollowUpFromABar,ut as FollowUpMenuFitsItsWords,ct as FollowUpOnADay,lt as FollowUpSplit,at as FollowUpToRecords,xt as HeaderSorts,B as HeatmapFillsItsPlot,F as HorizontalLabelsInFrame,Ct as IdentifiersInMonospace,z as LineKeepsOffTheEdges,Et as LoadingChartKeepsItsPlace,Tt as LoadingKeepsItsPlace,Gt as MetricCondition,V as OneBarKeepsItsWidth,pt as PieChart,ht as PieOnAPhone,gt as PinnedCategoryColor,Ot as QueryFailed,Z as ReachesAdvancedMode,W as SparklineRunsForward,bt as TableReadsLikeATable,ft as TableWithTotals,P as TicksInsideTheChart,U as TimeRunsForward,L as TitleClearOfSlantedTicks,I as TitleClearOfTicks,Wt as TrayCardMenu,Nt as TrayEdits,$ as TrayExpansion,zt as TrayFitsADesk,Bt as TrayFitsAPhone,At as TrayFolds,Lt as TrayReadsClearly,K as TrendCardReadsLastPeriod,dt as TwoMetrics,R as ValueLabelsApart,Ut as VisualizePanel,M as WholeTicks,Kt as __namedExportsOrder,Xe as default};
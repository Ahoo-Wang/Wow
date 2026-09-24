import{n as e,r as t}from"./rolldown-runtime-DkW27tQK.js";import{t as n}from"./react-Q1GcV6wX.js";import{t as r}from"./jsx-runtime-DeHZSEgm.js";import{$m as i,C as a,Cs as ee,F as o,P as s,Qm as c,S as l,Ss as u,Vo as d,Xm as te,b as f,c as p,g as m,gp as ne,h,hp as re,i as g,n as _,o as v,r as ie,s as ae,u as y,v as b,zo as oe}from"./styles-Dpj2y9Rj.js";import{C as se,dt as ce,ft as le,ln as ue,un as x,ut as de,w as S}from"./AnalysisParts-B9WGI7R-.js";import{a as fe,c as C,d as pe,f as me,h as he,i as ge,l as _e,m as w,n as ve,o as ye,p as be,r as T,s as xe,t as Se,u as Ce}from"./EmbeddedDashboard-rnP9JnOV.js";import{a as we,c as Te,i as Ee,n as De,o as Oe,r as ke,s as E,t as Ae}from"./DataWorkbench-CFjlp2jX.js";function D({engine:e,definitionId:t,instanceId:n,onInstanceChange:r,onNavigate:i,theme:a,messages:o,locale:s,optionsFor:c,defaultSidebarOpen:l,onSidebarOpenChange:u,expandable:d,onRenderFailure:te,template:f,features:p,initialTab:m,onTabChange:ne,initialFilters:h,onFiltersChange:g}){let _=ee(o,s),v=(0,O.useRef)({instanceId:n,initialTab:m,initialFilters:h});(0,O.useEffect)(()=>{v.current={instanceId:n,initialTab:m,initialFilters:h}},[n,m,h]);let ie=(0,O.useRef)(!1),ae=(0,O.useCallback)(e=>{let{instanceId:t,initialTab:n,initialFilters:r}=v.current;if(t==null?!ie.current:t===e)return{...n==null?{}:{tab:n},...r==null?{}:{filters:r}}},[]),y=Te(e,t,{kinds:je,instanceId:n,onInstanceChange:r,opening:ae,newView:{title:_.label(`label.dashboard.new-title`),...f?{templates:{dashboard:f}}:{}}}),{filter:b,runtime:ce,state:x}=y,S=ce?.kind===`dashboard`?ce:null;(0,O.useEffect)(()=>{S&&(ie.current=!0)},[S]);let C=he(S),me=S?C.tab:void 0;(0,O.useEffect)(()=>{me!==void 0&&ne?.(me)},[me,ne]);let w=S?C.filters:void 0;(0,O.useEffect)(()=>{w!==void 0&&g?.(w)},[w,g]);let ve=x?.saved?.id,ye=n=>{ve&&e.rememberTab(t,ve,n)},T=S!==null&&C.building,Se=S!==null&&y.commands.can.save,Ce=C.setBuilding,Ee=(0,O.useRef)(null),De=(0,O.useRef)(T);(0,O.useLayoutEffect)(()=>{let e=De.current&&!T;if(De.current=T,!e)return;let t=document.activeElement;(t===null||t===document.body)&&Ee.current?.focus()},[T]);let Oe=be(),E=le(`dashboard-announcement`),{extensions:Ae,dialogs:D}=ge({engine:e,board:S,dashboard:C,messages:_,optionsFor:c,say:E.say,tabBar:S&&(0,k.jsx)(_e,{dashboard:C,editing:T?S:null,onShow:ye})}),Me=C.issues.filter(e=>e.severity===`warning`),Ne=xe(C,x?.draft,_),Pe=C.panels.some(e=>{let t=e.runtime?.getSnapshot();return t!==void 0&&(t.result!==null||t.query.status!==`idle`)});return(0,k.jsx)(ke,{workbench:y,title:e.definitions.get(t)?.title,theme:a,messages:o,locale:s,timeZone:e.environment.timeZone,defaultSidebarOpen:l,onSidebarOpenChange:u,expandable:d,manage:se(p).manage,build:Se&&!T&&(0,k.jsxs)(oe,{ref:Ee,"data-slot":`dashboard-edit`,variant:`outline`,size:`sm`,onClick:()=>Ce(!0),children:[(0,k.jsx)(ue,{"data-icon":`inline-start`}),_.label(`label.dashboard.edit`)]}),commitElsewhere:T,onRenderFailure:te,resultFramed:!1,hasResult:Pe,warnings:Me,nameIssue:Ne,strips:null,freshness:(0,k.jsx)(we,{refresh:y.refresh,variant:`outline`,busy:C.resolving||C.loading,note:_.label(`label.refresh.panels`)}),applied:!1,result:x&&(0,k.jsxs)(pe.Provider,{value:{...Ae,...Oe},children:[(0,k.jsxs)(de,{say:E.say,children:[(0,k.jsx)(fe,{engine:e,dashboard:C,commands:y.commands,title:x.title,shared:re(x.scope)===`shared`,canEdit:Se,editing:T,onEditingChange:Ce,onSaved:y.onSaved,onNavigate:i,onRenderFailure:te,refusedFilters:S?.refusedFilters,fixed:b.fixed,reading:{panelExport:se(p).export}}),D]}),E.region]})})}var O,k,je;function Me(){return(Me=e((()=>{O=n(),x(),ne(),w(),E(),d(),ce(),ye(),Oe(),u(),S(),Ee(),Ce(),C(),T(),me(),k=r(),je=[`dashboard`]})))()}var Ne=t({AllPanels:()=>N,Building:()=>F,Clicks:()=>G,CrossFilter:()=>K,EmptyDashboard:()=>Q,EmptySharedBoard:()=>I,Filters:()=>W,GlobalFilter:()=>P,LegacyLayout:()=>$,Loading:()=>H,OpenInWorkbench:()=>Z,OwnedAnalysis:()=>R,PanelUnavailable:()=>V,PersonalViewOnSharedBoard:()=>L,PreBatchCCondition:()=>X,QueryFailed:()=>U,SystemDashboard:()=>B,Tabs:()=>z,ToAnotherBoard:()=>q,ToAnotherBoardStale:()=>J,ToAnotherBoardWithOwnFilter:()=>Y,__namedExportsOrder:()=>$e,default:()=>Qe});function Pe({behaviour:e=`data`,variant:t=`panels`,onFiltersChange:n,onNavigate:r}){return(0,j.jsx)(_,{create:()=>ae({behaviour:e,definitions:[Je,t===`system`?Ge:f],instances:[Ye,...t===`unavailable`?[a[1]]:a,{...l,...t===`empty-shared`||t===`owned`||t===`personal`?{scope:`shared`}:{},config:Le(t)},...t===`tabs`?[Xe]:[],...We.includes(t)?[M]:[]]}),children:e=>We.includes(t)?(0,j.jsx)(Fe,{engine:e,holdsState:t===`handed`,onFiltersChange:n,onNavigate:r}):(0,j.jsx)(D,{engine:e,definitionId:`overview`,instanceId:t===`system`?`system:overview:ops`:l.id,initialFilters:t===`filtered`?Ke:void 0,onFiltersChange:n,...g})})}function Fe({engine:e,holdsState:t=!1,onFiltersChange:n,onNavigate:r}){let[i,a]=(0,A.useState)(null),[ee,o]=(0,A.useState)(),[s,c]=(0,A.useState)(),u=e=>{r?.(e),e.kind===`dashboard`&&e.instanceId===l.id?(o(e.filters),c(e.tab),a(null)):a(e)},d={initialFilters:ee,onFiltersChange:e=>{o(e),n?.(e)},onNavigate:u,...g};return i===null?t?(0,j.jsx)(Se,{engine:e,instanceId:l.id,interaction:`interactive`,withTitle:!0,filterModes:{state:`locked`},pageValues:{values:{state:qe}},...d}):(0,j.jsx)(D,{engine:e,definitionId:`overview`,instanceId:l.id,initialTab:s,...d}):i.kind===`view`||i.kind===`unsaved`?(0,j.jsx)(Ae,{engine:e,definitionId:i.definitionId,handOver:i,onNavigate:u,...g}):(0,j.jsxs)(`div`,{style:{display:`flex`,flexDirection:`column`,gap:8},children:[(0,j.jsx)(`button`,{type:`button`,"data-slot":`host-back`,style:{alignSelf:`flex-start`},onClick:()=>a(null),children:`← 回到出库概览`}),i.kind===`url`?(0,j.jsxs)(`p`,{"data-slot":`host-page`,children:[`宿主页面：`,i.url]}):(0,j.jsx)(D,{engine:e,definitionId:i.definitionId,instanceId:i.instanceId,initialFilters:i.filters,...g},i.instanceId)]})}function Ie(){let e=p();return{...e,fields:[...e.fields,{name:`state`,label:`状态`,kind:`enum`,multiple:!0,options:[{value:`PENDING`,label:`待出库`},{value:`SHIPPED`,label:`已发运`},{value:`CANCELLED`,label:`已取消`}]}],panels:e.panels.map(e=>e.kind===`view`?{...e,bindings:[...e.bindings??[],{globalField:`state`,panelField:`status`}]}:e)}}function Le(e){return e===`filters`?He():e===`cross`?p({panels:p().panels.map(e=>e.id===`by-warehouse`?{...e,click:{kind:`filter`,filter:`region`}}:e)}):e===`to-board`||e===`to-board-stale`?p({panels:p().panels.map(t=>t.id===`by-warehouse`?{...t,click:{kind:`dashboard`,instanceId:M.id,values:e===`to-board`?{region:{dimension:`warehouse`}}:{zone:{dimension:`warehouse`}}}}:t)}):e===`to-board-own`?Ue():e===`handed`?Ie():e===`tabs`?Be():e===`owned`?Ve():e===`empty`||e===`empty-shared`?y():e===`legacy`?m():e===`pre-c`?Re():e===`personal`?ze():p()}function Re(){let e={...p(),filter:{op:`and`,children:[{field:`region`,operator:`IN`,value:[`CN-SOUTH`]},{field:`region`,operator:`NOT_IN`,value:[`CN-WEST`]}]},filterMode:`simple`};return delete e.fixed,e}function ze(){let e=p();return{...e,panels:[...e.panels,{id:`mine`,kind:`view`,instanceId:`orders-mine`,bindings:[{globalField:`region`,panelField:`warehouse`}],layout:{x:0,y:8,w:24,h:4}}]}}function Be(){let e=p();return{...e,tabs:[{id:`tab-outbound`,title:`出库`},{id:`tab-status`,title:`状态`}],panels:[...e.panels.map(e=>({...e,tab:`tab-outbound`})),{id:`by-status`,kind:`view`,title:`按状态看金额`,owned:{definitionId:`orders`,config:v({groups:[{alias:`status`,field:`status`,type:`TERMS`}],chart:{type:`bar`,cartesian:{x:`status`,series:[{metric:`amount`}]}}})},bindings:[{globalField:`region`,panelField:`warehouse`}],layout:{x:0,y:0,w:12,h:4},tab:`tab-status`}]}}function Ve(){let e=p();return{...e,panels:[...e.panels,{id:`owned`,kind:`view`,title:`本板自建：订单数按仓库`,owned:{definitionId:`orders`,config:v({chart:{type:`bar`,cartesian:{x:`warehouse`,series:[{metric:`orders`}]}}})},bindings:[{globalField:`region`,panelField:`warehouse`}],layout:{x:8,y:4,w:16,h:4}}]}}function He(){let e=p(),t={globalField:`created`,panelField:`createdAt`},n={globalField:`phase`,panelField:`status`};return{...e,fields:[{name:`created`,label:`创建时间`,kind:`datetime`,required:!0,default:{type:`absolute`,from:`2026-09-01`,to:`2026-09-30`}},...e.fields,{name:`phase`,label:`状态`,kind:`string`},{name:`order`,label:`订单号`,kind:`string`}],timeGrouping:{units:[`DAY`,`MONTH`],default:`DAY`},panels:[...e.panels.map(e=>e.kind===`view`?{...e,bindings:[...e.bindings,t,n,...e.id===`pending`?[{globalField:`order`,panelField:`id`}]:[]]}:e),{id:`trend`,kind:`view`,title:`每日订单`,instanceId:`orders-trend`,bindings:[t,n],layout:{x:8,y:4,w:16,h:4}}]}}function Ue(){let e=p(),t={globalField:`placed`,panelField:`createdAt`};return{...e,fields:[...e.fields,{name:`placed`,label:`下单时间`,kind:`datetime`,default:{type:`absolute`,from:`2026-09-01`,to:`2026-09-30`}}],panels:e.panels.map(e=>e.kind===`view`?{...e,bindings:[...e.bindings,t],...e.id===`by-warehouse`?{click:{kind:`dashboard`,instanceId:M.id,values:{region:{dimension:`warehouse`},placed:{filter:`placed`}}}}:{}}:e)}}var A,j,We,Ge,Ke,qe,Je,Ye,Xe,M,Ze,Qe,N,P,F,I,L,R,z,B,V,H,U,W,G,K,q,J,Y,X,Z,Q,$,$e;function et(){return(et=e((()=>{A=n(),Me(),De(),ve(),i(),o(),h(),ie(),j=r(),We=[`handed`,`clicks`,`cross`,`to-board`,`to-board-stale`,`to-board-own`],Ge={...f,views:[{id:`ops`,title:`出库概览（系统）`,config:p()}]},Ke={values:{region:[`CN-SOUTH`]}},qe=[`PENDING`,`SHIPPED`],Je={...b,analysis:{...b.analysis,fields:[...b.analysis.fields,{field:`id`,groups:[c.TERMS],functions:[]},{field:`createdAt`,groups:[c.DATE_HISTOGRAM],functions:[],dateUnits:[te.DAY,te.MONTH]}]}},Ye={id:`orders-trend`,definitionId:`orders`,title:`每日订单`,scope:`shared`,revision:`1`,config:v({groups:[{alias:`createdAt`,field:`createdAt`,type:`DATE_HISTOGRAM`,unit:`DAY`}],chart:{type:`bar`,cartesian:{x:`createdAt`,series:[{metric:`orders`}]}}})},Xe={...l,id:`overview-other`,title:`异常概览`,config:p()},M={...l,id:`overview-regional`,title:`区域明细`,scope:`shared`,config:p({fields:[...p().fields,{name:`placed`,label:`下单时间`,kind:`datetime`}],panels:p().panels.map(e=>e.kind===`view`?{...e,bindings:[...e.bindings,{globalField:`placed`,panelField:`createdAt`}]}:e)})},Ze=`内存 ViewStore · 两个被引用的共享视图 · 一个内容面板`,Qe={parameters:{layout:`fullscreen`,docs:{description:{component:`**仪表盘视图 · 仪表盘**

把已保存的明细与分析放在一页，用一个全局筛选统一收口径。

- **数据源**：${Ze}。
- **准备**：每次挂载都新建引擎与存储；面板引用的实例随场景增减。
- **操作**：打开任一场景，它放在宿主应用里——顶部导航与左侧应用导航是宿主的，中间那一块是视图引擎——像使用者看到的一样。
- **观察**：每个面板各自加载、各自出错；一个面板不可用不影响其余面板。`}}},decorators:[e=>(0,j.jsx)(s,{current:`dashboard`,service:{fixture:Ze},children:(0,j.jsx)(e,{})})],title:`View Engine/仪表盘视图/Dashboard`,component:Pe,args:{behaviour:`data`,variant:`panels`},argTypes:{behaviour:{control:`inline-radio`,options:[`data`,`empty`,`slow`,`failing`]},variant:{table:{disable:!0}}}},N={args:{variant:`panels`}},P={args:{variant:`filtered`}},F={args:{variant:`panels`}},I={args:{variant:`empty-shared`}},L={name:`共享板上的个人视图`,args:{variant:`personal`}},R={name:`板内分析与改展示`,args:{variant:`owned`}},z={name:`标签页`,args:{variant:`tabs`}},B={args:{variant:`system`}},V={args:{variant:`unavailable`}},H={args:{behaviour:`slow`}},U={args:{behaviour:`failing`}},W={name:`筛选条`,args:{variant:`filters`}},G={name:`点击：追问菜单`,args:{variant:`clicks`}},K={name:`点击：交叉筛选`,args:{variant:`cross`}},q={name:`点击：去另一块仪表盘`,args:{variant:`to-board`}},J={name:`点击：去另一块仪表盘（映射失效）`,args:{variant:`to-board-stale`}},Y={name:`点击：去另一块仪表盘（带这块板的筛选）`,args:{variant:`to-board-own`}},X={name:`批 C 之前的整板条件`,args:{variant:`pre-c`}},Z={name:`在工作台中打开：带着板上的筛选`,args:{variant:`handed`}},Q={args:{variant:`empty`}},$={name:`旧的 12 列布局`,args:{variant:`legacy`}},$e=[`AllPanels`,`GlobalFilter`,`Building`,`EmptySharedBoard`,`PersonalViewOnSharedBoard`,`OwnedAnalysis`,`Tabs`,`SystemDashboard`,`PanelUnavailable`,`Loading`,`QueryFailed`,`Filters`,`Clicks`,`CrossFilter`,`ToAnotherBoard`,`ToAnotherBoardStale`,`ToAnotherBoardWithOwnFilter`,`PreBatchCCondition`,`OpenInWorkbench`,`EmptyDashboard`,`LegacyLayout`],N.parameters={...N.parameters,docs:{...N.parameters?.docs,source:{originalSource:`{
  args: {
    variant: 'panels'
  }
}`,...N.parameters?.docs?.source},description:{story:`Two data panels and one content panel, under one filter.`,...N.parameters?.docs?.description}}},P.parameters={...P.parameters,docs:{...P.parameters?.docs,source:{originalSource:`{
  args: {
    variant: 'filtered'
  }
}`,...P.parameters?.docs?.source},description:{story:`The filter bar's 仓库 set to 华南: each panel answers for that warehouse
alone, through its own field, and neither referenced view changes. No
「正在显示」 band (D27): the bar is what the panels are showing.`,...P.parameters?.docs?.description}}},F.parameters={...F.parameters,docs:{...F.parameters?.docs,source:{originalSource:`{
  args: {
    variant: 'panels'
  }
}`,...F.parameters?.docs?.source},description:{story:`Press 「编辑」 to build the board (D22 A): the edit bar comes up with
「＋ 添加」, 取消 and 保存, every panel's 「⋯」 gains 「改」, and panels can be
dragged by their grip or resized by their corner — or either from the
keyboard: both handles answer the arrow keys, and the menu beside the grip
says the same eight commands in words. Panels run as the board changes;
保存 saves, 取消 puts back what was saved.`,...F.parameters?.docs?.description}}},I.parameters={...I.parameters,docs:{...I.parameters?.docs,source:{originalSource:`{
  args: {
    variant: 'empty-shared'
  }
}`,...I.parameters?.docs?.source},description:{story:`A board shared with everyone and nothing on it yet: its first steps are
offered under the empty state, and a personal view put on it is marked
「只有你看得到」 in the picker (D22 B).`,...I.parameters?.docs?.description}}},L.parameters={...L.parameters,docs:{...L.parameters?.docs,source:{originalSource:`{
  name: '共享板上的个人视图',
  args: {
    variant: 'personal'
  }
}`,...L.parameters?.docs?.source},description:{story:`A shared board with a panel on the author's personal view (D22 B): the
panel wears the warning that its view is not open to every reader, and
while the board is built its 「⋯」 offers 「复制为共享视图并替换…」 — the
view copied as a shared one, the panel pointed at the copy, looking and
filtering as before. Every record panel's 「⋯」 also offers 「导出数据…」,
the workbench's export window over its rows.`,...L.parameters?.docs?.description}}},R.parameters={...R.parameters,docs:{...R.parameters?.docs,source:{originalSource:`{
  name: '板内分析与改展示',
  args: {
    variant: 'owned'
  }
}`,...R.parameters?.docs?.source},description:{story:`A board that owns one analysis (D22 C): while it is built, its 「⋯」 offers
「另存为视图…」, which makes it a view of its own; 「＋ 添加 ▾」 offers
「新建分析…」, the analysis view in a dialog; and an analysis panel offers
「改这里的展示…」, its own look (D22 D).`,...R.parameters?.docs?.description}}},z.parameters={...z.parameters,docs:{...z.parameters?.docs,source:{originalSource:`{
  name: '标签页',
  args: {
    variant: 'tabs'
  }
}`,...z.parameters?.docs?.source},description:{story:`Two tabs (D22 E): only the tab on screen runs, the reader's last tab is
where the board opens next, and while it is built the bar adds, renames,
reorders and deletes tabs.`,...z.parameters?.docs?.description}}},B.parameters={...B.parameters,docs:{...B.parameters?.docs,source:{originalSource:`{
  args: {
    variant: 'system'
  }
}`,...B.parameters?.docs?.source},description:{story:`The board the definition ships: 另存为, and no 编辑 (D4).`,...B.parameters?.docs?.description}}},V.parameters={...V.parameters,docs:{...V.parameters?.docs,source:{originalSource:`{
  args: {
    variant: 'unavailable'
  }
}`,...V.parameters?.docs?.source},description:{story:`A referenced view that was deleted: only that panel says so.`,...V.parameters?.docs?.description}}},H.parameters={...H.parameters,docs:{...H.parameters?.docs,source:{originalSource:`{
  args: {
    behaviour: 'slow'
  }
}`,...H.parameters?.docs?.source},description:{story:`Each panel loads on its own, so they arrive independently.`,...H.parameters?.docs?.description}}},U.parameters={...U.parameters,docs:{...U.parameters?.docs,source:{originalSource:`{
  args: {
    behaviour: 'failing'
  }
}`,...U.parameters?.docs?.source},description:{story:`A failing backend leaves the layout and the filter intact.`,...U.parameters?.docs?.description}}},W.parameters={...W.parameters,docs:{...W.parameters?.docs,source:{originalSource:`{
  name: '筛选条',
  args: {
    variant: 'filters'
  }
}`,...W.parameters?.docs?.source},description:{story:`The filter bar (D22 F): 创建时间 is required — starred, never empty —
仓库 reaches the two panels over it and not the trend, which says so once
仓库 holds a value, and 按日｜按月 regroups the trend. While the board is
built, 「添加筛选」 adds one, and 「接线」 wires it (D22 G).`,...W.parameters?.docs?.description}}},G.parameters={...G.parameters,docs:{...G.parameters?.docs,source:{originalSource:`{
  name: '点击：追问菜单',
  args: {
    variant: 'clicks'
  }
}`,...G.parameters?.docs?.source},description:{story:`Pressing a group (D22 H): a bar of 「按仓库汇总」 or a row of its table
opens the analysis view's follow-up menu, headed by the group and the
board's filters over it; each item opens in the workbench (↗) through the
host's route — here the page swaps the board for a \`DataWorkbench\` on the
view nobody saved, with a way back.`,...G.parameters?.docs?.description}}},K.parameters={...K.parameters,docs:{...K.parameters?.docs,source:{originalSource:`{
  name: '点击：交叉筛选',
  args: {
    variant: 'cross'
  }
}`,...K.parameters?.docs?.source},description:{story:`Cross-filtering (D22 I): 「按仓库汇总」 is set to update 「仓库」 on a
press. A bar pressed sets the filter bar (「来自「按仓库汇总」」) and the
list runs under it; the chart keeps every bar and marks the one pressed;
the same bar again clears it.`,...K.parameters?.docs?.description}}},q.parameters={...q.parameters,docs:{...q.parameters?.docs,source:{originalSource:`{
  name: '点击：去另一块仪表盘',
  args: {
    variant: 'to-board'
  }
}`,...q.parameters?.docs?.source},description:{story:`Another board (D23 Q17): 「按仓库汇总」 is set to open 「区域明细」 with
its 仓库 taken from the bar pressed. The host opens that board with the
value as its reader's — the filter bar shows it, its list runs under it —
and nothing is written into either board.`,...q.parameters?.docs?.description}}},J.parameters={...J.parameters,docs:{...J.parameters?.docs,source:{originalSource:`{
  name: '点击：去另一块仪表盘（映射失效）',
  args: {
    variant: 'to-board-stale'
  }
}`,...J.parameters?.docs?.source},description:{story:`The same click, mapped to a filter 「区域明细」 no longer has: a press
says so and opens the follow-up menu instead, and the panel warns from
then on.`,...J.parameters?.docs?.description}}},Y.parameters={...Y.parameters,docs:{...Y.parameters?.docs,source:{originalSource:`{
  name: '点击：去另一块仪表盘（带这块板的筛选）',
  args: {
    variant: 'to-board-own'
  }
}`,...Y.parameters?.docs?.source},description:{story:`Another board, carrying this board's filter too (D23 Q17, 2026-09-23):
「区域明细」 opens with 仓库 from the bar pressed and 下单时间 as this
board's holds it.`,...Y.parameters?.docs?.description}}},X.parameters={...X.parameters,docs:{...X.parameters?.docs,source:{originalSource:`{
  name: '批 C 之前的整板条件',
  args: {
    variant: 'pre-c'
  }
}`,...X.parameters?.docs?.source},description:{story:`批 C 之前存下的整板条件（D23 Q16、D26 Q31）：筛选收得下的「仓库 属于 华南」
读成仓库筛选的默认值，筛选条上就是华南、读者能改；收不下的「仓库 不是 西南」
读成仪表盘的固定范围，在筛选条那一行只读地写作「固定范围」，没有 ✕（D27）。
打开不变脏，存回才写新形式（\`fixed\`），之后作者怎么改筛选设置都不再迁。`,...X.parameters?.docs?.description}}},Z.parameters={...Z.parameters,docs:{...Z.parameters?.docs,source:{originalSource:`{
  name: '在工作台中打开：带着板上的筛选',
  args: {
    variant: 'handed'
  }
}`,...Z.parameters?.docs?.source},description:{story:`Leaving the board (D26 Q30, Q33): the page holds 状态 to the orders still
in play (locked); set 仓库 to 华南 and 「⋯ → 在工作台中打开」 「待出库明细」.
The workbench opens 「待出库订单」 「已修改」: 仓库 是 华南 is its own
condition, with a ✕, while the page's 状态 is its scope, with none. The
row under the title bar, 「返回 出库概览」, goes back to the board under
华南.`,...Z.parameters?.docs?.description}}},Q.parameters={...Q.parameters,docs:{...Q.parameters?.docs,source:{originalSource:`{
  args: {
    variant: 'empty'
  }
}`,...Q.parameters?.docs?.source},description:{story:`A dashboard with nothing on it yet.`,...Q.parameters?.docs?.description}}},$.parameters={...$.parameters,docs:{...$.parameters?.docs,source:{originalSource:`{
  name: '旧的 12 列布局',
  args: {
    variant: 'legacy'
  }
}`,...$.parameters?.docs?.source},description:{story:"一块在栅格还是 12 列时存下的仪表盘（没有 `columns`）：打开时按 24 列读，\n`x`、`w` 乘 2，每个面板落在原来的像素上（D22 E）。不变脏，存回才写新格式。",...$.parameters?.docs?.description}}}})))()}export{Qe as C,et as S,B as _,Ne as a,J as b,W as c,Z as d,R as f,U as g,X as h,K as i,P as l,L as m,F as n,Q as o,V as p,G as r,I as s,N as t,$ as u,z as v,Y as x,q as y};
import{n as e,r as t}from"./rolldown-runtime-DkW27tQK.js";import{t as n}from"./react-Q1GcV6wX.js";import{t as r}from"./jsx-runtime-DeHZSEgm.js";import{$i as i,B as a,Bn as o,C as s,Cs as c,Da as l,Ea as u,Et as d,F as f,Gn as p,H as m,Hn as ee,In as h,Jn as g,K as _,Kt as v,Ln as y,Mi as b,Mn as x,Nn as S,Nr as C,Oa as w,P as T,Pn as E,Rn as D,Rt as O,Sa as k,Ss as te,St as ne,Ta as re,Tt as ie,U as ae,Ua as oe,Un as A,V as se,Va as ce,Vn as le,Vo as ue,W as de,Wn as fe,X as pe,Y as me,Z as he,ao as ge,at as _e,b as ve,ba as ye,bi as be,co as xe,dt as Se,ea as Ce,er as we,ft as Te,gr as Ee,h as De,hr as Oe,i as ke,it as Ae,ji as je,lo as Me,n as Ne,oo as Pe,qt as Fe,r as Ie,rt as Le,s as Re,st as ze,tr as Be,v as Ve,vi as He,wa as Ue,wt as j,xa as We,xi as Ge,ya as Ke,yi as qe,zn as Je,zo as Ye,zt as Xe}from"./styles-Dpj2y9Rj.js";import{a as M,i as Ze,n as Qe,o as $e,r as et,t as tt}from"./RecordPagination-DgNHCVUW.js";import{a as nt,c as rt,i as it,n as at,o as ot,r as st,t as ct}from"./EmbedHead-BumWnDL1.js";function lt({runtime:e,interactive:t,onNavigate:n,head:r,notices:i}){let a=p(e),s=ee(e),c=h(e),l=(0,N.useCallback)(()=>({scopeFilter:e.scopeFilter,filter:null}),[e]),u=rt(e,t?n:void 0,l),d=o(e,s,u),{view:f,chart:m,chartData:_}=d,y=ae(s,d.ran?.sort??ut),b=t&&(d.ran?.groups.length??0)>0,[x,S]=(0,N.useState)(null),C=x?d.followUp(x.row):null,w=t&&n!==void 0&&d.pickable,T=w?(e,t,n)=>S({row:e,anchor:t,...n?{origin:n}:{}}):void 0,E=a?.query.status===`error`,D=E?a.query.error:null,O=t?()=>e.refresh():void 0,k;return k=E&&!f?(0,P.jsx)(j,{error:D,stale:!1,onRetry:O}):f?(0,P.jsxs)(P.Fragment,{children:[(0,P.jsx)(j,{error:D,stale:!0,onRetry:O}),t&&(0,P.jsx)(de,{analysis:s,columns:d.columns}),(0,P.jsx)(`div`,{"data-slot":`analysis-result`,className:`flex min-w-0 flex-col`,children:f.rows.length===0?(0,P.jsx)(be,{}):s.layout===`chart`&&_?(0,P.jsx)(je,{data:_,spec:m,columns:f.schema??f.columns,cutShort:f.truncated||f.atLimit!==void 0,onPick:T,menuOpen:C!==null&&x!==null}):(0,P.jsx)(He,{view:f,onPick:T,...b?{sorting:y}:{}})}),w&&(0,P.jsx)(ce,{pick:C?x:null,onClose:()=>S(null),followUp:C,away:!0})]}):(0,P.jsx)(v,{className:`h-24 w-full`}),(0,P.jsxs)(P.Fragment,{children:[r(null),i,(0,P.jsx)(M,{filter:c,asked:g(a),readOnly:!0}),k]})}var N,P,ut;function dt(){return(dt=e((()=>{N=n(),le(),Je(),E(),ot(),A(),b(),qe(),_(),oe(),Ge(),m(),$e(),Fe(),d(),P=r(),ut=[]})))()}function ft({engine:e,runtime:t,interactive:n,withSearch:r,withExport:i,rowActions:o,head:s,notices:l}){let u=p(t),d=S(t),f=h(t),m=D(t),ee=c(),_=Pe(),y=Ae({runtime:t,table:d,filter:f,title:u?.title??``,messages:ee,display:_,now:e.environment.now}),b=i&&d.hasResult&&(0,F.jsx)(_e,{...y,columns:d.columns,max:Oe(t.limits,t.definition.record).max}),x=r&&m&&(0,F.jsx)(a,{search:m}),C=d.status===`error`,w=n?()=>t.refresh():void 0,T=i&&n,E;return E=C&&!d.hasResult?(0,F.jsx)(j,{error:d.error,stale:!1,onRetry:w}):d.loading&&d.rows.length===0?(0,F.jsx)(v,{className:`h-24 w-full`}):(0,F.jsxs)(F.Fragment,{children:[(0,F.jsx)(j,{error:C?d.error:null,stale:!0,onRetry:w}),d.layout===`card`?(0,F.jsx)(et,{table:d,rowActions:o,selectable:T}):(0,F.jsx)(O,{table:d,rowActions:o,selectable:T,readOnly:!n}),n&&(0,F.jsx)(tt,{table:d})]}),(0,F.jsxs)(F.Fragment,{children:[s(b),l,x?(0,F.jsxs)(`div`,{"data-slot":`applied-row`,className:`flex flex-wrap items-center gap-2`,children:[(0,F.jsx)(M,{filter:f,asked:g(u),readOnly:!0,className:`min-w-0 grow`}),(0,F.jsx)(`div`,{className:`ml-auto`,children:x})]}):(0,F.jsx)(M,{filter:f,asked:g(u),readOnly:!0}),E]})}var F;function pt(){return(pt=e((()=>{Ee(),E(),x(),y(),A(),$e(),Fe(),ze(),te(),Ze(),Qe(),Xe(),Le(),d(),ge(),se(),F=r()})))()}function mt(e){let{engine:t,instanceId:n,scopeFilter:r=null}=e,i=fe(t,n,r);return(0,I.jsx)(it,{engine:t,opened:i,kinds:gt,props:e,children:t=>(0,I.jsx)(ht,{runtime:t,props:e})})}function ht({runtime:e,props:t}){let{engine:n,instanceId:r,interaction:i=`read-only`,withTitle:a=!1,headingLevel:o=2,withSearch:s=!1,withExport:l=!1,openInWorkbench:u=!0,onNavigate:d,rowActions:f}=t,m=e,h=p(m),g=c(),_=i===`interactive`,v=Se(ee(m),g),y=(h?.issues??[]).map(v),b=y.filter(e=>e.severity===`error`),x=[...y,...C(h?.result?.data)],S=a?h?.title:void 0,w=_&&u&&d&&(0,I.jsx)(at,{to:{kind:`view`,definitionId:m.definition.id,instanceId:r,scopeFilter:m.scopeFilter,filter:null},onNavigate:d}),T=e=>(0,I.jsx)(ct,{title:S,headingLevel:o,children:w||e?(0,I.jsxs)(I.Fragment,{children:[w,e]}):null});if(b.length>0)return(0,I.jsxs)(I.Fragment,{children:[T(null),(0,I.jsx)(ne,{issues:b}),(0,I.jsx)(ie,{issues:x})]});let E=(0,I.jsx)(ie,{issues:x});return Be(m)?(0,I.jsx)(ft,{engine:n,runtime:m,interactive:_,withSearch:s,withExport:l,rowActions:f,head:T,notices:E}):(0,I.jsx)(lt,{runtime:m,interactive:_,onNavigate:d,head:T,notices:E})}var I,gt;function _t(){return(_t=e((()=>{we(),le(),A(),Te(),nt(),st(),dt(),pt(),te(),d(),I=r(),gt=[`record`,`analysis`]})))()}var vt=t({AnalysisEmbed:()=>Y,AnalysisInteractive:()=>$,Default:()=>V,EmptyResult:()=>G,FillTheScreen:()=>J,Interactive:()=>Z,Loading:()=>q,QueryFailed:()=>K,ReadOnlyExport:()=>Q,ScopeRefused:()=>U,ScopeRefusedOnOpen:()=>W,ScopedByHost:()=>H,TotalCoversThisPageOnly:()=>X,__namedExportsOrder:()=>Dt,default:()=>Et});function yt({engine:e,instanceId:t,scopeFilter:n,caption:r,scopeChoice:a,onScope:o,embed:s}){let c=(0,L.useRef)(null),[u,d]=(0,L.useState)(null),f=(0,L.useRef)(null),p=Me(c,f);return(0,R.jsxs)(`div`,{"data-host-page":!0,className:`fve-tokens bg-background text-foreground flex min-h-0 flex-col gap-4`,children:[(0,R.jsxs)(`div`,{className:`flex flex-wrap items-center justify-between gap-2`,children:[(0,R.jsxs)(`div`,{className:`flex min-w-0 flex-col gap-0.5`,children:[(0,R.jsx)(`p`,{className:`text-muted-foreground text-xs`,children:`订单中心 / 客户 / 明远商贸`}),(0,R.jsx)(`h3`,{className:`truncate text-base font-semibold`,children:`明远商贸 · 客户详情`})]}),(0,R.jsx)(Ye,{size:`sm`,onClick:()=>alert(`新建工单`),children:`新建工单`})]}),(0,R.jsx)(i,{}),(0,R.jsxs)(`div`,{className:`grid min-w-0 gap-4 md:grid-cols-[minmax(180px,220px)_minmax(0,1fr)]`,children:[(0,R.jsxs)(Ke,{children:[(0,R.jsxs)(Ue,{children:[(0,R.jsx)(re,{children:`客户资料`}),(0,R.jsx)(k,{children:`宿主自己的那一栏。`}),(0,R.jsx)(ye,{children:(0,R.jsx)(l,{variant:`secondary`,children:`月结`})})]}),(0,R.jsx)(We,{className:`flex flex-col gap-3`,children:xt.map(([e,t])=>(0,R.jsxs)(`div`,{className:`flex flex-col gap-0.5`,children:[(0,R.jsx)(`span`,{className:`text-muted-foreground text-xs`,children:e}),(0,R.jsx)(`span`,{className:`text-sm`,children:t})]},e))})]}),(0,R.jsxs)(Ke,{className:`min-w-0`,style:wt,children:[(0,R.jsxs)(Ue,{children:[(0,R.jsx)(re,{children:`最近运单`}),(0,R.jsx)(k,{children:r}),(0,R.jsx)(ye,{children:(0,R.jsx)(Ye,{ref:f,variant:`outline`,size:`sm`,"aria-expanded":p.expanded,onClick:p.toggle,children:p.expanded?`退出全屏`:`全屏查看`})})]}),(0,R.jsxs)(We,{className:`flex min-w-0 flex-col gap-3`,children:[o&&(0,R.jsx)(me,{value:[a??`none`],onValueChange:e=>{let t=e[0];z.includes(t)&&o(t)},variant:`outline`,size:`sm`,"aria-label":`页面范围`,className:`flex-wrap`,children:z.map(e=>(0,R.jsx)(pe,{value:e,children:Ct[e]},e))}),(0,R.jsx)(mt,{ref:c,className:`host-embed`,engine:e,instanceId:t,scopeFilter:n,messages:ke.messages,locale:ke.locale,onNavigate:d,...s})]})]})]}),(0,R.jsx)(i,{}),(0,R.jsx)(`p`,{className:`text-muted-foreground text-xs`,children:`数据来自运单中心 · 每 5 分钟同步一次`}),u&&(0,R.jsxs)(`p`,{"data-host-route":!0,className:`text-muted-foreground font-mono text-xs break-all`,children:[`宿主路由：`,u.kind===`view`?`打开视图 ${u.instanceId} · 作用域 ${JSON.stringify(u.scopeFilter)}`:u.kind===`unsaved`?`打开「${u.title}」 · 作用域 ${JSON.stringify(u.scopeFilter)} · 条件 ${JSON.stringify(u.config.filter)}`:u.kind===`dashboard`?`打开仪表盘 ${u.instanceId}`:u.url]})]})}function bt({behaviour:e=`data`,instanceId:t,scope:n=`none`,scopePicker:r=!1,caption:i=`这份共享视图筛的是待出库的单，按金额倒序。`,embed:a}){let[o,c]=(0,L.useState)(n),l=r?o:n;return(0,R.jsx)(Ne,{create:()=>Re({behaviour:e,definitions:[St,ve]}),children:e=>(0,R.jsx)(yt,{engine:e,instanceId:t??s[0].id,scopeFilter:Tt[l],caption:i,embed:a,...r?{scopeChoice:o,onScope:c}:{}})})}var L,R,xt,St,Ct,z,wt,Tt,B,Et,V,H,U,W,G,K,q,J,Y,X,Z,Q,$,Dt;function Ot(){return(Ot=e((()=>{L=n(),_t(),xe(),w(),ue(),u(),Ce(),he(),f(),De(),Ie(),R=r(),xt=[[`客户编号`,`C-8812`],[`联系人`,`周涛`],[`联系电话`,`138 2025 0137`],[`信用额度`,`¥ 200,000`],[`结算方式`,`月结 30 天`]],St={...Ve,fields:[...Ve.fields,{name:`q`,label:`搜索订单`,kind:`search`,searchFields:[`id`,`note`]}]},Ct={none:`不收窄`,east:`只看华东仓`,unknown:`按客户收窄`},z=[`none`,`east`,`unknown`],wt={"--fve-background":`var(--card)`,"--fve-dark-background":`var(--card)`},Tt={none:null,east:{op:`and`,children:[{field:`warehouse`,operator:`IN`,value:[`CN-EAST`]}]},unknown:{op:`and`,children:[{field:`customerId`,operator:`EQ`,value:`C-8812`}]}},B=`内存 ViewStore · 六条订单`,Et={parameters:{layout:`fullscreen`,docs:{description:{component:`**数据视图 · 嵌入视图**

别人定好的一份观察，摆进一张业务页面里：只有结果。

- **数据源**：${B}，嵌在一张假的客户详情页里。
- **准备**：每次挂载都新建引擎与存储；铺满屏幕的按钮由宿主画在自己的卡片头上。
- **操作**：打开任一场景。客户详情页放在宿主应用的页面区里——顶部导航与左侧应用导航是宿主的，页面也是宿主的，只有「最近运单」卡片里那一块是视图引擎——嵌入本来就是这样。
- **观察**：没有标题栏、没有工具栏、没有保存——变的只有结果和它自己的说明。`}}},decorators:[e=>(0,R.jsx)(T,{current:`embedded`,service:{fixture:B},padded:!0,children:(0,R.jsx)(e,{})})],title:`View Engine/数据视图/EmbeddedView`,component:bt,args:{behaviour:`data`},argTypes:{behaviour:{control:`inline-radio`,options:[`data`,`empty`,`slow`,`failing`,`no-aggregate`]},instanceId:{table:{disable:!0}},scope:{table:{disable:!0}},scopePicker:{table:{disable:!0}},caption:{table:{disable:!0}},embed:{table:{disable:!0}}}},V={args:{behaviour:`data`}},H={args:{scope:`east`,caption:`宿主又收了一档：只看华东仓。`}},U={args:{scope:`east`,scopePicker:!0,caption:`按一下「按客户收窄」——这份定义没有 customerId。`}},W={name:`收窄被拒（一开就拒）`,args:{scope:`unknown`,caption:`一打开就带着一个这份定义不认的收窄条件。`}},G={args:{behaviour:`empty`}},K={args:{behaviour:`failing`}},q={args:{behaviour:`slow`}},J={args:{caption:`按右上角「全屏查看」——那颗按钮是宿主画的，不是视图的。`}},Y={name:`嵌一个分析视图`,args:{instanceId:s[1].id,caption:`同一个壳，存的是「仓库金额分布」。`}},X={args:{behaviour:`no-aggregate`}},Z={name:`可交互`,args:{scope:`east`,caption:`可交互：排序、翻页、搜索、导出、在工作台中打开。`,embed:{interaction:`interactive`,withSearch:!0,withExport:!0}}},Q={name:`只读，开了导出`,args:{caption:`只读一档开了导出：没有行勾选，导出整份结果。`,embed:{withExport:!0}}},$={name:`分析视图（可交互）`,args:{instanceId:s[1].id,caption:`可交互：表格｜图表切换、按一组追问。`,embed:{interaction:`interactive`}}},Dt=[`Default`,`ScopedByHost`,`ScopeRefused`,`ScopeRefusedOnOpen`,`EmptyResult`,`QueryFailed`,`Loading`,`FillTheScreen`,`AnalysisEmbed`,`TotalCoversThisPageOnly`,`Interactive`,`ReadOnlyExport`,`AnalysisInteractive`],V.parameters={...V.parameters,docs:{...V.parameters?.docs,source:{originalSource:`{
  args: {
    behaviour: 'data'
  }
}`,...V.parameters?.docs?.source},description:{story:`默认：结果，以及结果是在什么条件下取来的。

屏幕上只有三样东西——已应用条件、表格、汇总行。条件条是**只读**的：没有 ✕，
因为这里没有编辑器，而视图自己的条件是它作者存下来的；一个能撤条件的 ✕ 会
让读者把「这个客户的单」悄悄变成「所有人的单」。

没有「铺满屏幕」按钮——那颗在宿主的卡片头上，写着「全屏查看」。`,...V.parameters?.docs?.description}}},H.parameters={...H.parameters,docs:{...H.parameters?.docs,source:{originalSource:`{
  args: {
    scope: 'east',
    caption: '宿主又收了一档：只看华东仓。'
  }
}`,...H.parameters?.docs?.source},description:{story:"宿主替这张页面把视图再收窄一档：`scopeFilter` 只留华东仓。\n\n收窄和配置一起进引擎（`useOpenView` 把它交给 `engine.open`），所以**第一次\n查询就已经是收窄过的**；它按用户自己的条件那一套准入，宿主因此没法把一个视\n图放宽到它的定义不允许的范围，也永远不会写进保存的配置里。已应用条上读得出\n视图自己的条件加上这一条。",...H.parameters?.docs?.description}}},U.parameters={...U.parameters,docs:{...U.parameters?.docs,source:{originalSource:`{
  args: {
    scope: 'east',
    scopePicker: true,
    caption: '按一下「按客户收窄」——这份定义没有 customerId。'
  }
}`,...U.parameters?.docs?.source},description:{story:`收窄被**拒**：宿主中途把范围换成一个这份定义没有的字段。

卡片头下面那一排是**宿主自己的**范围按钮（视图没有这种东西）。从「只看华东
仓」换到「按客户收窄」：这个字段这份定义没有，收窄于是不被接受。

这是这一屏唯一不能默不作声的结局。被拒的收窄留下的是**更宽**的那个结果——
页面要的是这一个客户的单，不说的话它就会安安静静地把所有人的单列出来。所以
上面是一条 destructive 的告警，说清是哪一条没被接受，而行还照着上一次被接
受的范围跑着。

手动可以在三档之间来回切：换回「只看华东仓」，告警消失，结果跟着收回去。`,...U.parameters?.docs?.description}}},W.parameters={...W.parameters,docs:{...W.parameters?.docs,source:{originalSource:`{
  name: '收窄被拒（一开就拒）',
  args: {
    scope: 'unknown',
    caption: '一打开就带着一个这份定义不认的收窄条件。'
  }
}`,...W.parameters?.docs?.source},description:{story:`同一件事发生在**第一次打开**的时候，说的是同一句话（D17-5）。

视图一开就带着一个不被接受的 \`scopeFilter\`。被拒的是**页面加上去的**那个条
件，不是视图——视图好好的，宿主也改不了别人存的配置——所以这里说的是「页面
的作用域条件对这个视图不适用」，和上面那条（先跑起来再换范围）一字不差。

结局也一样：那个条件根本没进准入，视图照它作者存的样子跑，**没收窄的那份结
果留在屏幕上**，告警压在上面。页面要的范围没生效，不等于连宽的那份也不给。`,...W.parameters?.docs?.description}}},G.parameters={...G.parameters,docs:{...G.parameters?.docs,source:{originalSource:`{
  args: {
    behaviour: 'empty'
  }
}`,...G.parameters?.docs?.source},description:{story:`查询成功了，一条也没匹配上——这不是错误，所以画的也不是错误。`,...G.parameters?.docs?.description}}},K.parameters={...K.parameters,docs:{...K.parameters?.docs,source:{originalSource:`{
  args: {
    behaviour: 'failing'
  }
}`,...K.parameters?.docs?.source},description:{story:`查询失败：视图和它的条件都还在，没有的只是数据。

这里**没有「重试」**——嵌入的视图没有工具栏，一颗孤零零的按钮会让整块看起来
像是一个控件；它按自己的节奏重跑。`,...K.parameters?.docs?.description}}},q.parameters={...q.parameters,docs:{...q.parameters?.docs,source:{originalSource:`{
  args: {
    behaviour: 'slow'
  }
}`,...q.parameters?.docs?.source},description:{story:`第一次执行还没回来的样子：一块骨架，没有别的。`,...q.parameters?.docs?.description}}},J.parameters={...J.parameters,docs:{...J.parameters?.docs,source:{originalSource:`{
  args: {
    caption: '按右上角「全屏查看」——那颗按钮是宿主画的，不是视图的。'
  }
}`,...J.parameters?.docs?.source},description:{story:"铺满屏幕，而按钮是**宿主的**。\n\n按卡片头上的「全屏查看」：这块 `.fve-root` 就地撑满整页——不进 portal、什么\n都不重新挂载——把面包屑、左边那张事实表、页脚全盖住。`data-view-expanded`\n就在这块 surface 上。\n\n一铺开，宿主那颗按钮自己就在面的**下面**了。所以 `ViewSurface` 会把一直藏\n着的那颗 `view-exit` 亮出来：桌面用户也许会猜 Esc，触屏根本没有 Esc 键，没\n有这颗按钮就真的出不来。收起时焦点回到宿主那颗按钮上，页面的滚动连\n`!important` 一起原样还回去。",...J.parameters?.docs?.description}}},Y.parameters={...Y.parameters,docs:{...Y.parameters?.docs,source:{originalSource:`{
  name: '嵌一个分析视图',
  args: {
    instanceId: savedViews[1].id,
    caption: '同一个壳，存的是「仓库金额分布」。'
  }
}`,...Y.parameters?.docs?.source},description:{story:"同一个壳，嵌的是一个分析视图：一张图，外加它是在什么条件下算出来的。\n\n明细还是汇总由 `EmbeddedView` 自己分，宿主只给了一个 `instanceId`；仪表盘\n按资源分开，是 `EmbeddedDashboard` 的。",...Y.parameters?.docs?.description}}},X.parameters={...X.parameters,docs:{...X.parameters?.docs,source:{originalSource:`{
  args: {
    behaviour: 'no-aggregate'
  }
}`,...X.parameters?.docs?.source},description:{story:`汇总查询失败，明细照常：合计退回本页口径。

结果自己说得出的那些 warning，在嵌入的视图上比在工作台里更要紧——这里没有编
辑器、没有工具栏，一个写着「总计」的数字如果是本页合计，屏幕上没有第二处能
纠正它。`,...X.parameters?.docs?.description}}},Z.parameters={...Z.parameters,docs:{...Z.parameters?.docs,source:{originalSource:`{
  name: '可交互',
  args: {
    scope: 'east',
    caption: '可交互：排序、翻页、搜索、导出、在工作台中打开。',
    embed: {
      interaction: 'interactive',
      withSearch: true,
      withExport: true
    }
  }
}`,...Z.parameters?.docs?.source},description:{story:`可交互一档（D22）：读者可以按表头排序、翻页、搜索、导出，也可以「在工作台中
打开」——经宿主的路由，带着页面的收窄。没有保存、没有条件编辑器：改的都是这一
次看的样子。

默认一档是只读：上面那几条故事里表头按不动、没有分页。`,...Z.parameters?.docs?.description}}},Q.parameters={...Q.parameters,docs:{...Q.parameters?.docs,source:{originalSource:`{
  name: '只读，开了导出',
  args: {
    caption: '只读一档开了导出：没有行勾选，导出整份结果。',
    embed: {
      withExport: true
    }
  }
}`,...Q.parameters?.docs?.source},description:{story:`只读一档开了导出（D26 Q36）：导出是开关、不改档位，只读仍然「没有勾选」——
行前没有复选框，导出窗口不问「所有／选中」，导出整份结果，与仪表盘面板的
「导出数据…」一样。`,...Q.parameters?.docs?.description}}},$.parameters={...$.parameters,docs:{...$.parameters?.docs,source:{originalSource:`{
  name: '分析视图（可交互）',
  args: {
    instanceId: savedViews[1].id,
    caption: '可交互：表格｜图表切换、按一组追问。',
    embed: {
      interaction: 'interactive'
    }
  }
}`,...$.parameters?.docs?.source},description:{story:`分析视图，可交互一档：表格｜图表切换，按一组弹出追问菜单，每一项经宿主的路由
在工作台打开。`,...$.parameters?.docs?.description}}}})))()}export{G as a,K as c,W as d,H as f,Et as h,vt as i,Q as l,Ot as m,$ as n,J as o,X as p,V as r,Z as s,Y as t,U as u};
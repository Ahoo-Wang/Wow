import{n as e,r as t}from"./rolldown-runtime-DkW27tQK.js";import{t as n}from"./react-Q1GcV6wX.js";import{t as r}from"./jsx-runtime-DeHZSEgm.js";import{A as ee,C as i,D as te,F as ne,I as re,L as a,P as ie,T as ae,Vo as oe,_ as se,b as ce,cp as le,dp as ue,h as de,i as fe,j as pe,lp as me,n as he,r as ge,s as o,up as _e,v as ve,w as ye,x as s,zo as c}from"./styles-Dpj2y9Rj.js";import{X as be,Y as xe}from"./AnalysisParts-B9WGI7R-.js";import{l as Se,n as Ce,t as we,u as Te}from"./DataWorkbench-CFjlp2jX.js";function Ee(){return s({filter:{op:`and`,children:[{field:`status`,operator:`IN`,value:[`PENDING`]}]},pageSize:50,sort:[],table:{columns:[{field:`id`,pinned:!0},{field:`warehouse`},{field:`status`},{field:`amount`},{field:`createdAt`}]}})}var De,Oe,ke;function l(){return(l=e((()=>{a(),me(),de(),De={unknown:`网关超时，写入结果未知`,rejected:`这个视图由运维托管，不接受修改`},Oe=class{constructor(e={}){this.staged=null,this.races=0,this.inner=new re(e)}stage(e){this.staged=e}list(e){return this.inner.list(e)}get(e){return this.inner.get(e)}getPreferences(e){return this.inner.getPreferences(e)}create(e,t){return this.through(null,()=>this.inner.create(e,t))}save(e,t,n,r){return this.through(()=>this.race(e),()=>this.inner.save(e,t,n,r))}rename(e,t,n,r){return this.through(()=>this.race(e),()=>this.inner.rename(e,t,n,r))}delete(e,t,n){return this.through(()=>this.race(e),()=>this.inner.delete(e,t,n))}setPreferences(e,t,n){return this.through(()=>this.racePreferences(e),()=>this.inner.setPreferences(e,t,n))}async through(e,t){let n=this.staged;if(n===null)return t();if(n===`conflict`)return e===null?t():(this.staged=null,await e(),t());throw this.staged=null,n===`unknown`?new le(`UNAVAILABLE`,De.unknown):new le(`INVALID`,De.rejected)}async race(e){let t=await this.inner.get(e);this.races+=1,await this.inner.save(e,Ee(),t.revision,{requestId:`someone-else-${this.races}`})}async racePreferences(e){let t=await this.inner.getPreferences(e);this.races+=1,await this.inner.setPreferences(e,{...t,order:[...t.order].reverse()},{requestId:`someone-else-${this.races}`})}},ke={current:null}})))()}var Ae=t({AutoRefresh:()=>R,CannotOpen:()=>T,CellFamily:()=>b,CollapsedSidebar:()=>L,DeleteConflicted:()=>Z,EarliestAndLatest:()=>S,ElementColumns:()=>x,EmptyResult:()=>g,English:()=>j,ExportCapped:()=>F,ExportFailed:()=>I,ExportResult:()=>N,ExportRunning:()=>P,FillTheScreen:()=>z,FillTheScreenInScaledHost:()=>V,FillTheScreenInTransformedHost:()=>B,FillTheScreenWithPopups:()=>H,Loading:()=>_,ManageViews:()=>A,NarrowTitleBar:()=>K,NeedsFixing:()=>C,NoViews:()=>E,Opening:()=>w,Paged:()=>D,PagedWindow:()=>O,PinnedEdges:()=>U,PinnedGroupCapped:()=>$,PopupsOverRaisedHostLayer:()=>G,QueryFailed:()=>v,RenameConflicted:()=>X,RenderFailure:()=>W,SaveConflicted:()=>q,SaveRefused:()=>Y,SaveResultUnknown:()=>J,TableSettings:()=>M,TotalCoversThisPageOnly:()=>y,WideTable:()=>Q,WithActions:()=>k,WithData:()=>h,__namedExportsOrder:()=>Ge,default:()=>We});function u({behaviour:e=`data`,instanceId:t,broken:n=!1,paged:r=!1,pagingWindow:ne,withActions:a=!1,english:ie=!1,keepStore:oe=!1,collapsed:le=!1,transformedHost:ue=!1,scaledHost:de=!1,raisedHost:me=!1,pinnedColumn:ge=!1,refreshing:c=!1,narrowHost:be=!1,narrowWidth:xe=375,writeOutcome:Se,theme:Ce,breakable:Ee=!1,cellFamily:De=!1,elements:l=!1,dated:Ae=!1,exporting:u,wide:d=!1,noViews:je=!1,opening:Ne=!1}){let p=Te(),m=(0,f.jsx)(f.Fragment,{children:(0,f.jsx)(he,{create:()=>{if(Ne)return o({store:new Ve({instances:i})});if(je)return o({definitions:[{...ve,views:[]},ce],instances:[],behaviour:e});if(l)return o({definitions:[se,ce],instances:[Le],behaviour:e});if(d)return o({definitions:[pe],instances:ye,source:ee(e)});if(Se){let t=new Oe({instances:i});return t.stage(Se),ke.current=t,o({behaviour:e,store:t})}let t=oe?new re({instances:i}):void 0;return t&&(te.current=t),o({behaviour:e,...t?{store:t}:{},...ne===void 0?{}:{definitions:[{...ve,record:{rowKey:`id`,paging:`paged`,layouts:[`table`,`card`],maxWindow:ne}},ce]},...u===`capped`?{limits:{exportMax:2}}:{},...u===`slow`||u===`failing`?{source:ae(u,_e.maxPageSize)}:{},instances:n?[{...i[0],title:`待修复视图`,config:s({table:{columns:[{field:`removedColumn`}]}})}]:r?[Pe]:ge?[Fe]:c?[ze]:be?[Be]:De?[Ie]:Ae?[Re]:i})},children:e=>(0,f.jsx)(we,{engine:e,definitionId:d?pe.id:`orders`,instanceId:je?void 0:t??(d?ye[0].id:i[0].id),messages:ie?void 0:fe.messages,locale:ie?`en-US`:fe.locale,theme:Ce,defaultSidebarOpen:!le&&void 0,record:{actions:Ee?He:a?Me(p):void 0,bulk:a?p:void 0}})})});return be?(0,f.jsx)(`div`,{"data-narrow-host":!0,style:{width:xe,overflow:`hidden`},children:m}):ue?(0,f.jsx)(`div`,{"data-transformed-host":!0,style:{transform:`translateZ(0)`},children:m}):de?(0,f.jsx)(`div`,{"data-transformed-host":!0,"data-scaled-host":!0,style:{transform:`scale(0.75)`,transformOrigin:`top left`},children:m}):me?(0,f.jsxs)(f.Fragment,{children:[m,(0,f.jsx)(`div`,{"data-raised-host":!0,style:{position:`fixed`,inset:0,zIndex:10,display:`grid`,alignContent:`end`,justifyItems:`center`,padding:16,background:`rgb(23 37 84 / 24%)`},children:(0,f.jsx)(`span`,{style:{borderRadius:999,background:`#1d39c4`,padding:`4px 12px`,color:`#fff`,font:`600 13px system-ui, sans-serif`},children:`宿主抬到 z-index: 10 的一层`})})]}):m}function d({row:e}){let[t,n]=(0,Ne.useState)(!1);if(t)throw Error(`Row action for ${String(e.key)} threw`);return(0,f.jsx)(c,{variant:`ghost`,size:`xs`,onClick:()=>n(!0),children:`弄坏`})}function je(e){return new Promise((t,n)=>setTimeout(()=>m.includes(String(e))?n(Error(`已取消的订单不能导出。`)):t(),400))}function Me(e){return{global:()=>(0,f.jsx)(c,{variant:`outline`,size:`sm`,onClick:()=>alert(`新建订单`),children:`新建订单`}),bulk:t=>(0,f.jsxs)(c,{variant:`outline`,size:`sm`,"aria-busy":e.running!==null,disabled:e.running!==null,onClick:()=>e.run(t,{title:p,each:je}),children:[e.running!==null&&(0,f.jsx)(xe,{"data-icon":`inline-start`,role:void 0,"aria-label":void 0,"aria-hidden":`true`}),p]}),row:({row:e,refresh:t})=>(0,f.jsxs)(f.Fragment,{children:[(0,f.jsx)(c,{variant:`ghost`,size:`xs`,onClick:()=>alert(`打开 ${e.key}`),children:`打开`}),(0,f.jsx)(c,{variant:`ghost`,size:`xs`,onClick:()=>{alert(`取消 ${e.key}`),t()},children:`取消`})]})}}var Ne,f,Pe,Fe,Ie,Le,Re,ze,Be,Ve,He,p,m,Ue,We,h,g,_,v,y,b,x,S,C,w,T,E,D,O,k,A,j,M,N,P,F,I,L,R,z,B,V,H,U,W,G,K,q,J,Y,X,Z,Q,$,Ge;function Ke(){return(Ke=e((()=>{Ne=n(),ue(),a(),Se(),Ce(),oe(),be(),ne(),de(),l(),ge(),f=r(),Pe={...i[0],title:`逐页翻看`,config:s({pageSize:2,sort:[{field:`createdAt`,direction:`ASC`}]})},Fe={...i[0],title:`冻结两列`,config:s({table:{columns:[{field:`id`,pinned:!0},{field:`amount`,pinned:!0},{field:`warehouse`},{field:`status`},{field:`createdAt`}]}})},Ie={...i[0],title:`单元格读法`,config:s({summaries:[],table:{columns:[{field:`id`,pinned:!0},{field:`status`},{field:`tags`},{field:`trackingUrl`},{field:`note`}]},card:{title:`id`,fields:[`status`,`tags`,`trackingUrl`,`note`]}})},Le={...i[0],title:`按元素读`,config:s({summaries:[],table:{columns:[{field:`id`,pinned:!0},{field:`status`},{field:`lines`},{field:`parcels`}]},card:{title:`id`,fields:[`status`,`lines`,`parcels`]}})},Re={...i[0],title:`最早与最晚下单`,config:s({summaries:[{field:`amount`,fn:`SUM`},{field:`createdAt`,fn:`MIN`},{field:`createdAt`,fn:`MAX`}],table:{columns:[{field:`id`,pinned:!0},{field:`warehouse`},{field:`status`},{field:`amount`},{field:`createdAt`}]},card:{title:`id`,fields:[`warehouse`,`status`,`amount`,`createdAt`]}})},ze={...i[0],title:`每 30 秒自刷`,config:s({refresh:{interval:30}})},Be={...i[0],title:`全部订单 · 华东仓 · 待出库 · 按金额倒序 · 2026 年第三季度复核清单`},Ve=class extends re{get(){return new Promise(()=>{})}},He={global:()=>(0,f.jsx)(c,{size:`sm`,onClick:()=>alert(`新建订单`),children:`新建订单`}),row:({row:e})=>(0,f.jsx)(d,{row:e})},p=`导出所选`,m=[`SO-1002`],Ue=`内存 ViewStore · 六条订单 · 可切换的数据源行为`,We={parameters:{layout:`fullscreen`,docs:{description:{component:`**数据视图 · Record 工作台**

明细、汇总、筛选与保存，全部来自一份配置。

- **数据源**：${Ue}。
- **准备**：每次挂载都新建引擎与存储，场景之间不共享已保存的视图。
- **操作**：打开任一场景，它放在宿主应用里——顶部导航与左侧应用导航是宿主的，中间那一块是视图引擎——像使用者看到的一样。
- **观察**：表格、汇总行与提示反映这一次执行的口径，而不是草稿。`}}},decorators:[e=>(0,f.jsx)(ie,{current:`records`,service:{fixture:Ue},children:(0,f.jsx)(e,{})})],title:`View Engine/数据视图/Record 工作台`,component:u,args:{behaviour:`data`},argTypes:{behaviour:{control:`inline-radio`,options:[`data`,`empty`,`slow`,`failing`,`no-aggregate`]},broken:{table:{disable:!0}},opening:{table:{disable:!0}},paged:{table:{disable:!0}},pagingWindow:{table:{disable:!0}},instanceId:{table:{disable:!0}},withActions:{table:{disable:!0}},english:{table:{disable:!0}},keepStore:{table:{disable:!0}},collapsed:{table:{disable:!0}},transformedHost:{table:{disable:!0}},refreshing:{table:{disable:!0}},raisedHost:{table:{disable:!0}},narrowHost:{table:{disable:!0}},narrowWidth:{table:{disable:!0}},writeOutcome:{table:{disable:!0}},theme:{table:{disable:!0}},cellFamily:{table:{disable:!0}},elements:{table:{disable:!0}},exporting:{table:{disable:!0}},wide:{table:{disable:!0}}}},h={args:{behaviour:`data`}},g={args:{behaviour:`empty`}},_={args:{behaviour:`slow`}},v={args:{behaviour:`failing`}},y={args:{behaviour:`no-aggregate`}},b={args:{cellFamily:!0}},x={args:{elements:!0}},S={args:{dated:!0}},C={args:{broken:!0}},w={args:{opening:!0}},T={args:{instanceId:`deleted`}},E={args:{noViews:!0}},D={args:{paged:!0}},O={args:{paged:!0,pagingWindow:4}},k={args:{withActions:!0}},A={args:{behaviour:`data`}},j={name:`英文目录`,args:{english:!0}},M={args:{keepStore:!0}},N={name:`导出`,args:{behaviour:`data`}},P={name:`导出/进行中`,args:{exporting:`slow`}},F={name:`导出/超上限`,args:{exporting:`capped`}},I={name:`导出/失败`,args:{exporting:`failing`}},L={args:{collapsed:!0}},R={args:{refreshing:!0}},z={args:{behaviour:`data`}},B={args:{transformedHost:!0}},V={args:{scaledHost:!0}},H={args:{pinnedColumn:!0}},U={args:{pinnedColumn:!0,withActions:!0}},W={args:{breakable:!0}},G={args:{raisedHost:!0,paged:!0}},K={args:{narrowHost:!0}},q={args:{writeOutcome:`conflict`}},J={args:{writeOutcome:`unknown`}},Y={args:{writeOutcome:`rejected`}},X={args:{writeOutcome:`conflict`}},Z={args:{writeOutcome:`conflict`,instanceId:i[2].id}},Q={name:`宽表 · 20 列 50 行`,args:{wide:!0,withActions:!0}},$={name:`宽表 · 420 窄栏里的封顶`,args:{wide:!0,withActions:!0,narrowHost:!0,narrowWidth:420}},Ge=`WithData.EmptyResult.Loading.QueryFailed.TotalCoversThisPageOnly.CellFamily.ElementColumns.EarliestAndLatest.NeedsFixing.Opening.CannotOpen.NoViews.Paged.PagedWindow.WithActions.ManageViews.English.TableSettings.ExportResult.ExportRunning.ExportCapped.ExportFailed.CollapsedSidebar.AutoRefresh.FillTheScreen.FillTheScreenInTransformedHost.FillTheScreenInScaledHost.FillTheScreenWithPopups.PinnedEdges.RenderFailure.PopupsOverRaisedHostLayer.NarrowTitleBar.SaveConflicted.SaveResultUnknown.SaveRefused.RenameConflicted.DeleteConflicted.WideTable.PinnedGroupCapped`.split(`.`),h.parameters={...h.parameters,docs:{...h.parameters?.docs,source:{originalSource:`{
  args: {
    behaviour: 'data'
  }
}`,...h.parameters?.docs?.source},description:{story:`Rows, the summary row and the saved conditions of a shared view.`,...h.parameters?.docs?.description}}},g.parameters={...g.parameters,docs:{...g.parameters?.docs,source:{originalSource:`{
  args: {
    behaviour: 'empty'
  }
}`,...g.parameters?.docs?.source},description:{story:`A query that succeeded and matched nothing, which is not an error.`,...g.parameters?.docs?.description}}},_.parameters={..._.parameters,docs:{..._.parameters?.docs,source:{originalSource:`{
  args: {
    behaviour: 'slow'
  }
}`,..._.parameters?.docs?.source},description:{story:`What the first execution looks like before the answer arrives.`,..._.parameters?.docs?.description}}},v.parameters={...v.parameters,docs:{...v.parameters?.docs,source:{originalSource:`{
  args: {
    behaviour: 'failing'
  }
}`,...v.parameters?.docs?.source},description:{story:`A failed query keeps the view and its conditions; only the data is gone.`,...v.parameters?.docs?.description}}},y.parameters={...y.parameters,docs:{...y.parameters?.docs,source:{originalSource:`{
  args: {
    behaviour: 'no-aggregate'
  }
}`,...y.parameters?.docs?.source},description:{story:`汇总查询失败，明细照常。合计因此退回本页合计——数字留着，因为本页合计本身
有用——但行尾的口径标签改说「本页」，上方多一条 warning 说明为什么。默默
顶替才是这里唯一的错误：读者看到「总计」，会当成全部命中记录的总计。`,...y.parameters?.docs?.description}}},b.parameters={...b.parameters,docs:{...b.parameters?.docs,source:{originalSource:`{
  args: {
    cellFamily: true
  }
}`,...b.parameters?.docs?.source},description:{story:`一列怎么读，由定义说了算。

「状态」是一枚带语气的徽章（待出库=warning、已发运=success、已取消=danger，
颜色取主题 token，定义不能写任意色值）；「标记」一个数组一枚一枚地画，
选项没命名过的码原样画出来；「运单」是外链，\`target="_blank"\` 且
\`rel="noopener noreferrer"\`，读不出的 scheme（SO-1005 那条）落回纯文本，
绝不画成能点的链接；「备注」截到三行，整段留在 title 里，换行照留。
「订单号」是 \`copyable\`：字一个没变，旁边多一颗复制按钮——指针划到这一行
才现身，键盘 Tab 到它则始终现身，按下去图标翻成对勾、名字改说「已复制」，
约 1.5 秒后复原。切到卡片，同一份读法。`,...b.parameters?.docs?.description}}},x.parameters={...x.parameters,docs:{...x.parameters?.docs,source:{originalSource:`{
  args: {
    elements: true
  }
}`,...x.parameters?.docs?.source},description:{story:`按元素读的一列：对象数组在单元格里是它的一个个元素，而不是一段 JSON。

「明细」声明了以货号为元素的标题，于是一格是一枚一枚的货号；一单五条明细
的那一行只画前两枚、第三个位置写「+3」——表格一行就是一行，整张清单悬停
即得，读屏器听到的是被收起的那三个货号本身。「包裹」没有声明标题，于是一格
只说它装了几项（「2 项」），空的那单什么也不说。切到卡片，五条明细全部
画出、折行。`,...x.parameters?.docs?.description}}},S.parameters={...S.parameters,docs:{...S.parameters?.docs,source:{originalSource:`{
  args: {
    dated: true
  }
}`,...S.parameters?.docs?.source},description:{story:`一列时刻的最早与最晚。

页脚同一行里两种读法并排：「金额」是合计，一个带货币格式的数；「创建时间」
是「最早」与「最晚」，两个按这一列画单元格的读法画出来的时刻——宿主的语言
与时区里的那个日子，而不是十三位毫秒，也不是原样的 ISO 串。函数名也跟着
换：一列时刻没有「最小」，它有「最早」。两份口径都在——「全部」那一行来自
它自己那一次聚合，「本页」那一行是屏幕上这几行自己比出来的；这份视图不带
条件，六单都在一页上，于是两行说的是同一件事，而这正是那两个标签的用处。`,...S.parameters?.docs?.description}}},C.parameters={...C.parameters,docs:{...C.parameters?.docs,source:{originalSource:`{
  args: {
    broken: true
  }
}`,...C.parameters?.docs?.source},description:{story:"A saved config the definition outgrew: `apply` is refused until it is fixed.",...C.parameters?.docs?.description}}},w.parameters={...w.parameters,docs:{...w.parameters?.docs,source:{originalSource:`{
  args: {
    opening: true
  }
}`,...w.parameters?.docs?.source},description:{story:`打开视图的那一刻（P-13）：标题栏一块、结果块一块——工具栏一行、几行行，
该有边的地方有边。从前这里只有一条 \`h-8\` 的灰条，它说的是"有东西在加载"，
而不是"正在来的那一页长这样"，于是视图一到就是整页换一个形状。`,...w.parameters?.docs?.description}}},T.parameters={...T.parameters,docs:{...T.parameters?.docs,source:{originalSource:`{
  args: {
    instanceId: 'deleted'
  }
}`,...T.parameters?.docs?.source},description:{story:`No saved view under this id, reported instead of an empty frame.`,...T.parameters?.docs?.description}}},E.parameters={...E.parameters,docs:{...E.parameters?.docs,source:{originalSource:`{
  args: {
    noViews: true
  }
}`,...E.parameters?.docs?.source},description:{story:`一个还没有任何视图的定义：定义不声明系统视图，store 里也没有。

工作区说「还没有视图」，底下一句为什么、一颗「新建视图」——这一状态只在这里
说全；侧栏在视图本该出现的地方只有一行安静的「还没有视图」，头上那颗 \`+\` 是它
唯一的入口，列表折起时切换器菜单里还有同一项——三处通向 \`useWorkbench.create\`
这一条命令（用户 2026-09-22）。按下去
视图立刻以「新视图」打开、标着「尚未保存」、编辑带默认展开；第一次保存问
名字和给谁看（与另存同一张表），存下的那一个随即列进侧栏并打开。没改过
的新视图切走时不问；改过才问。`,...E.parameters?.docs?.description}}},D.parameters={...D.parameters,docs:{...D.parameters?.docs,source:{originalSource:`{
  args: {
    paged: true
  }
}`,...D.parameters?.docs?.source},description:{story:`结果不止一页时，表格下面那一行：左边说一共多少条，右边是每页几条、第几页，
以及前后两步。翻页不写进配置，改每页条数则是一次编辑，会立刻应用。`,...D.parameters?.docs?.description}}},O.parameters={...O.parameters,docs:{...O.parameters?.docs,source:{originalSource:`{
  args: {
    paged: true,
    pagingWindow: 4
  }
}`,...O.parameters?.docs?.source},description:{story:`源自己有分页窗口时（\`RecordCapability.maxWindow\`，Wow 走 Elasticsearch 时是
一万条）：六单每页两单，窗口四条，于是只翻得到两页。页数按窗口算，最后一页
的「下一页」按不动，跳页超出就落在最后一页，左边多一句「只能翻到前 4 条，缩
小范围看其余」——其余的要靠上面的条件缩小，而不是翻页。`,...O.parameters?.docs?.description}}},k.parameters={...k.parameters,docs:{...k.parameters?.docs,source:{originalSource:`{
  args: {
    withActions: true
  }
}`,...k.parameters?.docs?.source},description:{story:`The host's own commands in the three places they belong: over the view, over
a selection, and on one row. Pick rows to see the middle one appear.`,...k.parameters?.docs?.description}}},A.parameters={...A.parameters,docs:{...A.parameters?.docs,source:{originalSource:`{
  args: {
    behaviour: 'data'
  }
}`,...A.parameters?.docs?.source},description:{story:`Managing the list rather than looking at one view: rename, delete, reorder
and choose which view opens first. Open it from the gear beside the sidebar
heading — every button is there only where the store permits it.`,...A.parameters?.docs?.description}}},j.parameters={...j.parameters,docs:{...j.parameters?.docs,source:{originalSource:`{
  name: '英文目录',
  args: {
    english: true
  }
}`,...j.parameters?.docs?.source},description:{story:"英文目录。这些故事的数据是中文的（订单号、待出库、华东仓），所以整册默认把\n包里带的 `zhCN` 交给 `messages`、把 `locale` 设成 `zh-CN`；这一条是唯一反过\n来的——什么都不传，于是用的是包自己的 `defaultMessages` 与 `en-US`。\n\n它存在是为了让英文目录仍然有人看着：`messages` 是本地化的入口，两本目录里\n任何一本掉了键，都应该有一屏能看出来。要改其中几句，铺开再覆盖：\n`{ ...zhCN, 'label.filter.apply': '确定' }`。\n\n打开的是那个带条件的共享视图，所以结果上方的 Showing 里就有一枚可操作的条件\nbadge：字段名与候选项标签来自定义（它们是数据，仍然是中文），操作符来自目\n录，按 ✕ 把它撤下会立刻重跑查询。",...j.parameters?.docs?.description}}},M.parameters={...M.parameters,docs:{...M.parameters?.docs,source:{originalSource:`{
  args: {
    keepStore: true
  }
}`,...M.parameters?.docs?.source},description:{story:`表格设置：工具栏右端的「列设置」与「排序」。

列设置里一行一列——拖动手柄、显隐、列名、汇总函数、固定开关。拖动只在同一区域
内生效：主键「订单号」钉在左侧，宿主的操作列钉在右侧（这个故事没有行动作，所以
右侧那一行不出现），中间几列随意排。手柄也可以用键盘：Tab 到手柄，方向键上下移
一位，移完会播报落在第几位。旁边的排序按钮把当前排序读成话，点开可以逐条翻方
向、删掉，或者添加一个还没用到的可排序字段。改完点「Save」，重开这个视图就是
现在这副样子——列设置与排序改的都是视图本身，不是这一次打开。`,...M.parameters?.docs?.description}}},N.parameters={...N.parameters,docs:{...N.parameters?.docs,source:{originalSource:`{
  name: '导出',
  args: {
    behaviour: 'data'
  }
}`,...N.parameters?.docs?.source},description:{story:`导出：工具栏右端那颗下载图标，点开是一个窗口，整件事都在这个窗口里（D14）。

**第一步先摆清楚**：有勾选时上面是一组单选——「选中（N）」（默认选它）与
「所有（N，按当前筛选）」；没勾选就没有单选，只有「所有」。底下四行说的是
文件里会有什么：多少条、按什么条件（和结果条件带同一套读法）、哪几列（列设
置里可见的那几列，按表上的顺序）、文件叫什么名字。条数超过 \`limits.exportMax\`
时这里多一行警告——**按下「导出」就是同意**，所以同意的是什么得先摆在眼前，
而不是下载完才说。

**第二步是同一个窗口**：一条进度条加「已拉取 {fetched} / {total} 条」，只有一
个「取消」。在途时 Esc 与点遮罩**就是取消**——不是被拒掉：取消是用户自己的
答复，它可以关窗。「选中」那一路的行本来就在手上，从确认直接到结果。

**第三步还是同一个窗口**：「已导出 N 条」加文件名，被上限截断时多一句「文件只
含前 N 条（共 M 条匹配）」；失败则是失败那句话加「重试」。所以结果区上方的状态
行里不再有导出的事——一次旅程一个壳。

拿到的是一个 UTF-8 带 BOM 的 CSV：列与顺序就是窗口里列出的那几列，每个值按单元
格的读法写（枚举用标签、时间按这个界面的时区与语言、数字按 \`numberFormat\`），
文件名就是窗口里报的那一个。「所有」在后台按已应用条件分页拉，屏幕上的行、翻页
与勾选都不受影响。宿主想留痕的，\`DataWorkbench\` 的 \`record.onExported\` 会把文件原样
交出来。`,...N.parameters?.docs?.description}}},P.parameters={...P.parameters,docs:{...P.parameters?.docs,source:{originalSource:`{
  name: '导出/进行中',
  args: {
    exporting: 'slow'
  }
}`,...P.parameters?.docs?.source},description:{story:`导出跑起来的样子：后台那一页故意拖慢，进度条与「已拉取 0 / 4 条」停在屏幕
上。这时候 Esc、遮罩和「取消」是同一个答复——停下来，什么也不说。`,...P.parameters?.docs?.description}}},F.parameters={...F.parameters,docs:{...F.parameters?.docs,source:{originalSource:`{
  name: '导出/超上限',
  args: {
    exporting: 'capped'
  }
}`,...F.parameters?.docs?.source},description:{story:"超过上限：把 `limits.exportMax` 压到 2，这个视图匹配的四条就超了。警告在按钮\n之前，导出完窗口再说一遍文件里实际有几条、总共匹配几条。",...F.parameters?.docs?.description}}},I.parameters={...I.parameters,docs:{...I.parameters?.docs,source:{originalSource:`{
  name: '导出/失败',
  args: {
    exporting: 'failing'
  }
}`,...I.parameters?.docs?.source},description:{story:`导出失败：视图照常出数，只有导出那一次请求被拒。失败那句话就在窗口里，旁边
是「重试」——不用关掉窗口再从工具栏开一遍。`,...I.parameters?.docs?.description}}},L.parameters={...L.parameters,docs:{...L.parameters?.docs,source:{originalSource:`{
  args: {
    collapsed: true
  }
}`,...L.parameters?.docs?.source},description:{story:`侧栏收起后的样子：标题栏最左边是展开按钮、定义标题与视图切换下拉，结果拿回
侧栏占掉的那点宽度。下拉按受众分组、当前项打勾、系统视图带标签，末尾是「管理
视图」——和侧栏齿轮开的是同一个对话框。切换照样先过离开守卫。`,...L.parameters?.docs?.description}}},R.parameters={...R.parameters,docs:{...R.parameters?.docs,source:{originalSource:`{
  args: {
    refreshing: true
  }
}`,...R.parameters?.docs?.source},description:{story:"自动刷新：工具栏「数据新鲜度」那一组里，刷新按钮右边多了一个 `▾`。\n\n主键还是原来那一下——点一次，跑一次。`▾` 里是间隔：关闭，以及三档（30 秒、\n1 分钟、5 分钟）。档位再按 `runtime.limits` 的 `minRefreshInterval`／\n`maxRefreshInterval` 裁剪，**限制不允许的档位根本不出现**，而不是灰着让人\n点一下才知道不行（D4）。\n\n选中即改视图自己的 `refresh.interval`——和排序、列设置一样，是一次 `edit`\n加 `apply`，因此标题旁立刻出现「已修改」，`Save` 才会把它存下来。\n\n**这个故事看的是按钮上那处凭据在走**：开着的时候它写的不是节奏而是**还剩\n多久**（30 秒 → 29 秒 → … → 1 秒），数到头就刷新（图标换成 spinner），落\n地后从头再数。数的是运行时公布的下一次到期时刻，界面不另起一只钟；请求在\n途时写「0 秒」，而运行时因另外三条理由停表时退回那一档本身。那一格的宽度\n按该档位最宽的读数预留，所以数字变短时旁边的 `▾` 不会跟着挪。倒计时不念给\n屏幕阅读器——朗读的是 `label.refresh.on` 那一句节奏。\n\n什么时候停表由运行时说了算，界面不另立规矩：草稿有 error、编辑器正被输入、\n页面不可见、上一次请求还在途——四种情况下计时器暂停（见 runtime.md）。",...R.parameters?.docs?.description}}},z.parameters={...z.parameters,docs:{...z.parameters?.docs,source:{originalSource:`{
  args: {
    behaviour: 'data'
  }
}`,...z.parameters?.docs?.source},description:{story:`铺满屏幕：按标题栏右端那个方框按钮（「铺满屏幕」），视图就地撑满整页；再按
一次、或按 Esc 回来。

**就地**是这件事的全部要点——视图不被搬到 portal 里去，什么都不重新挂载，
所以半句没写完的筛选、选中的行、开着的弹层全都还在原处。它**不是模态**：
没有 \`aria-modal\`、不困住焦点、也不把页面其余部分设成 inert——什么也没在
问，它还是刚才那些内容、还站在宿主页面里。唯一借来的是背景不滚：一次看不
见效果的滚动就是一个悄悄丢掉的滚动位置。收起时连 \`!important\` 一起原样还
回去，同一页上两个铺满的面各记各的数，谁先收都不会把另一个锁在那里。

表格的粘性在两种状态下都成立：表头粘在顶、两行汇总粘在底、\`订单号\` 冻结在
左。铺满改变的是「哪个盒子高」，不是谁在滚。`,...z.parameters?.docs?.description}}},B.parameters={...B.parameters,docs:{...B.parameters?.docs,source:{originalSource:`{
  args: {
    transformedHost: true
  }
}`,...B.parameters?.docs?.source},description:{story:"同一件事，但工作台被放进一个自带 containing block 的宿主容器里（这里是\n`transform: translateZ(0)`，动画面板与要 GPU 提示的栅格外壳天天这么写）。\n\n这种祖先会接管 `position: fixed` 的坐标系，于是「铺满屏幕」本来只会铺满**那\n个容器**。`transform`、`filter`、`perspective`、`backdrop-filter`、\n`will-change`、`contain`、`container-type` 都算，逐个列举是一份会过期的清\n单，所以 `useViewExpansion` 改为**量**浏览器实际给的那个盒子：不是视口，差\n值就是修正量，一次到位。按下按钮，面仍然落在视口上。",...B.parameters?.docs?.description}}},V.parameters={...V.parameters,docs:{...V.parameters?.docs,source:{originalSource:`{
  args: {
    scaledHost: true
  }
}`,...V.parameters?.docs?.source},description:{story:"同一件事，但宿主容器把孩子**缩放**了（`transform: scale(.75)`，缩略预览、\n演示模式与自适应画布常见的一种写法）。\n\n这一半和平移不同：`getBoundingClientRect()` 报的已经是屏幕像素，而\n`--fve-expanded-*` 是按元素自己的坐标读的——一个本地像素等于 `scale` 个屏幕\n像素。把量到的差值原样写回去，面会照这个比例缩水，偏移也差同一个倍数。所以\n修正量本身也是**量**出来的：先写朴素值，再看浏览器把它变成了多大，要的和到\n手的之比就是那个 scale。按下按钮，面仍然正好落在视口上。",...V.parameters?.docs?.description}}},H.parameters={...H.parameters,docs:{...H.parameters?.docs,source:{originalSource:`{
  args: {
    pinnedColumn: true
  }
}`,...H.parameters?.docs?.source},description:{story:"铺满屏幕时，弹层仍然在面的**前面**——这正是「不进 top layer」当初要保住的东\n西，而列设置与排序这两个弹层是后来才有的。\n\n本包所有弹层都 portal 到 `document.body`，`ui/popups.tsx` 给每个 positioner\n写上 `z-index: var(--fve-popup-z-index, 50)`，所以它们有自己的一层；铺满的面\n取 `z-index: 0`，管的只是它与**宿主页面**的高低。两件事分开之后，这条故事问\n的仍是同一句话：面铺开时，菜单打得开吗。\n\n顺带把冻结列也换成配置自己指定的那一列：行键无论如何都会被投影钉在左边，只\n钉行键证明不了 `pinned` 还管不管用。",...H.parameters?.docs?.description}}},U.parameters={...U.parameters,docs:{...U.parameters?.docs,source:{originalSource:`{
  args: {
    pinnedColumn: true,
    withActions: true
  }
}`,...U.parameters?.docs?.source},description:{story:`冻结列的边只在有行滑到它下面时才出现。

左边冻着行键与 \`金额\`，右边冻着宿主的操作列；把结果区拉窄到中间那几列不得不
滚，然后看两侧的边：滚动条在起点时左边没有边（没有东西在它下面），在终点时
右边没有；两个冻结列之间也没有边——那里从来没有东西经过。`,...U.parameters?.docs?.description}}},W.parameters={...W.parameters,docs:{...W.parameters?.docs,source:{originalSource:`{
  args: {
    breakable: true
  }
}`,...W.parameters?.docs?.source},description:{story:`宿主的行动作在渲染时抛错，只毁掉结果块。

按任一行的「弄坏」，那个动作从此在渲染时抛错：结果块换成一句说明加「重试」，
标题栏、编辑带、保存都还在。按「重试」重新绘制这一块，行回来了。`,...W.parameters?.docs?.description}}},G.parameters={...G.parameters,docs:{...G.parameters?.docs,source:{originalSource:`{
  args: {
    raisedHost: true,
    paged: true
  }
}`,...G.parameters?.docs?.source},description:{story:"宿主在自己页面上抬起了一层（这里是 `z-index: 10` 的一块浮层，可命中、盖住整\n个视口），弹层仍然在它**前面**。\n\n这是弹层层级的底线。每个弹层都 portal 到 `document.body`，外面那层\npositioner 由布局引擎写上 `transform: translate(...)`，于是它自己就是一个\nstacking context——弹层内容里的 `z-50` 出不去；而 positioner 上那句\n`isolate z-50` 是 Tailwind utility，构建又把本样式表每条规则都钉在\n`:where(.fve-root, .fve-root *)` 里，positioner 不带 `fve-root`，那句话谁也没\n匹配上。所以层级只能写在 positioner **自己**身上：`ui/popups.tsx` 把\n`z-index: var(--fve-popup-z-index, 50)` 作为 style 写上去，样式表在不在都成\n立；宿主自己的 chrome 比 50 还高时，在 `:root` 上改这一个变量即可。\n\n浮层是可命中的，所以这一屏**看而不点**：要手动操作请看上面的故事。",...G.parameters?.docs?.description}}},K.parameters={...K.parameters,docs:{...K.parameters?.docs,source:{originalSource:`{
  args: {
    narrowHost: true
  }
}`,...K.parameters?.docs?.source},description:{story:`窄栏里的标题栏：一根 375px 宽的柱子——手机、分屏、宿主的侧边面板都是这个
宽度——装一个名字长得放不下的视图。侧栏没人交代过收起，是外壳自己量出来
的：窄于 \`md\` 的一栏里，列表不在视图旁边而是堆在它上面（D12／L1）。

标题栏读作两组：左边「这是哪个视图」（种类、受众、名字、保存命令），右边
「我在怎么看它」（筛选带的开合、铺满屏幕、宿主自己的按钮）。窄到两组并排
放不下时，**先让名字缩，缩到不能再缩就换行**——右边那组整组挪到第二行，而
不是压在左边那组上面。

以前是压上去的：左边那组带着 \`min-w-0\`，于是它可以被压到比自己内容还窄
（实测 96.8px 装 206.9px 的内容），父级因此以为一行放得下、\`flex-wrap\` 永远
不触发，\`Save\` 被画在筛选开关**底下** 102px。在 375px 的视口上，\`Save\` 左
缘、正中、右缘三点 \`elementFromPoint\` 全部答的是筛选开关：键盘还够得着，指
针和手指一下也点不到——一个手机宽度的用户存不了盘。

现在左边那组按自己的内容定尺寸，名字是组里唯一的弹簧（\`w-0 grow\`）：
\`truncate\` 自己做不到这件事，一行不折的标题照样按整串文字要宽度，组会跟着
名字一起长出去，溢出只是换了条路。`,...K.parameters?.docs?.description}}},q.parameters={...q.parameters,docs:{...q.parameters?.docs,source:{originalSource:`{
  args: {
    writeOutcome: 'conflict'
  }
}`,...q.parameters?.docs?.source},description:{story:`保存撞上了别人：**冲突**。

手动走一遍：点一下 \`订单号\` 的表头（排序会立刻应用，标题旁出现「已修改」），
再按 \`Save\`。存储在这一次写入之前替别人先落了一份配置——整份五列、每页 50
条、不排序——于是乐观版本对不上，引擎把结果记成 \`kind: 'conflict'\`，标题栏
下面那条带边框的行就是它。

三条出路都画在那一行上，**按代价从小到大**：**Take theirs** 丢掉草稿改用服
务端那份，**Save my copy** 另存一份、两边都留着，**Keep mine** 把自己这份盖
上去。三颗都是 \`outline\`——哪一条损失最小取决于两份配置里各有什么，屏幕不替
谁作主；这一屏唯一的 primary 始终是跑查询的那个 Apply（D12 Ⅰ）。从前最危险
的那一条（盖上去）是实心 primary，也就是整屏唯一被强调的东西。

取服务端那份与盖上去都会先把同一个选择再问一遍（\`ConflictConfirm\`），因为两
边都有从按钮上看不见的损失——对话框里用 \`describeConfig\` 把两份配置各说成一
句话摆在一起，这里正好三处不同：每页多少条、几列、几条排序。

冲突只安排了一次：答完之后的写入照常落库，所以「盖上去」是真的盖上去了。`,...q.parameters?.docs?.description}}},J.parameters={...J.parameters,docs:{...J.parameters?.docs,source:{originalSource:`{
  args: {
    writeOutcome: 'unknown'
  }
}`,...J.parameters?.docs?.source},description:{story:"保存发出去了，结果没回来：**未知**。\n\n同样先点一下表头再按 `Save`。存储抛 `UNAVAILABLE`——按 management.md 的分类\n这不是失败也不是成功：请求已经出门，服务端可能写了也可能没写。所以那一行只\n给两个按钮：**Retry** 用**同一个** `requestId` 与同一份正文再发一次，交给服\n务端去重；**Abandon** 放下它，草稿仍在，下次保存是一次新的意图。\n\n未结清的未知会挡住这个视图上的新写入（`view.write.unknown-pending`），所以\n这两个按钮不是装饰——不答它，`Save` 就一直不听话。",...J.parameters?.docs?.description}}},Y.parameters={...Y.parameters,docs:{...Y.parameters?.docs,source:{originalSource:`{
  args: {
    writeOutcome: 'rejected'
  }
}`,...Y.parameters?.docs?.source},description:{story:`服务端看过了，不收：**拒绝**。

点表头，按 \`Save\`。存储抛 \`INVALID\`，那一行说的是目录里的句子
（\`view.write.invalid\`），后面跟着存储自己的原话——已打开的视图有地方放这句
话，管理器的行里只放前半句。

拒绝是一个明确答案：什么也没写进去，也就没有可重试、可覆盖的东西，只有
**Dismiss** 把这行拿掉。拿掉不是装饰——引擎还替这次写入留着位置，不结清它，
这行会在屏幕上待一整个会话。改完再按一次 \`Save\`，那是一次新的意图，会落库。`,...Y.parameters?.docs?.description}}},X.parameters={...X.parameters,docs:{...X.parameters?.docs,source:{originalSource:`{
  args: {
    writeOutcome: 'conflict'
  }
}`,...X.parameters?.docs?.source},description:{story:`没打开的那一行也会撞上别人：**管理器行内的结局**。

从侧栏齿轮打开「管理视图」，给任意一行按铅笔改个名、按 ✓ 确认。改名同样撞上
先落的那份配置，于是冲突画在**它自己那一行下面**——一行小字加两个小按钮，而
不是把整张列表推下去：下面那些行还是真的，一条横幅把它们挤走就是用一个问题
报告另一个问题。

行里的措辞与打开的视图不同：取服务端那份在这里叫 **Reload list**（这一行讲
的是它所在的那张列表，列表会按存储的版本重新读回来），盖上去仍叫 **Keep
mine**。行里没有「另存」——管理器没有地方放一份副本。`,...X.parameters?.docs?.description}}},Z.parameters={...Z.parameters,docs:{...Z.parameters?.docs,source:{originalSource:`{
  args: {
    writeOutcome: 'conflict',
    instanceId: savedViews[2].id
  }
}`,...Z.parameters?.docs?.source},description:{story:`删除撞上冲突，要问第二遍（management.md「冲突与未知结果」）。

这里打开的是个人视图「我盯的大额单」，要删的是另一行、共享的「待出库订
单」——删的不是眼前这个，正是管理器存在的理由。（打开的那个视图筛的是五千以
上的单子，这份数据里一条也没有，所以下面空着；这一屏看的是对话框。）打开
「管理视图」，给那一行按垃圾桶，第一个对话框说清代价（共享视图：所有人都会
失去它），按 \`Delete\`。

删除这时撞上别人刚落的那份：第一次确认说的是**当时那个视图**，而冲突报回来
的是一个此后变过的视图——它可能已经变成共享的，删掉就连带别人一起。所以行里
按 **Keep mine** 不会直接删，而是拿着服务端刚回读的那份摘要**再问一遍**，第
二个对话框按 \`Delete\` 才真的删。`,...Z.parameters?.docs?.description}}},Q.parameters={...Q.parameters,docs:{...Q.parameters?.docs,source:{originalSource:`{
  name: '宽表 · 20 列 50 行',
  args: {
    wide: true,
    withActions: true
  }
}`,...Q.parameters?.docs?.source},description:{story:`一张真的很宽的表：20 列、50 行，外加宿主的操作列。

订单那套定义只有八个字段，故事里最多摆五列，于是每一条与「宽」有关的规矩都
只在没什么可滚的桌面上看过。这一条把它们摆在一起看：

- **横滚只发生在中间**。左边冻着运单号（配置自己钉的，也正好是行键），右边
  冻着宿主的操作列（D13），中间 19 列随滚动条走。两侧的边只在真有东西滑到
  它下面时才出现。
- **表头粘在顶、两行汇总粘在底**。50 行一页放不下，纵向滚起来之后列名和
  「件数／重量／运费」三个合计都还在原地——一张 20 列的表，滚到第 40 行还认
  得出哪一列是哪一列，靠的就是这个。
- **一列一种读法**。枚举四套（发货仓／承运商／运输方式／时效）、带语气的状
  态徽章、一列标记数组、三种数字格式（整数件数、公斤、人民币）、两列布尔、
  一个日期与一个时刻、一个外链、一段截到三行的备注。
- **列设置、排序、导出这三个弹层在 20 列上才有分量**：列设置里 20 行可拖加
  一行还没加进来的「联系电话」，排序弹层里已经堆了三个字段（发运日期、运
  费、运单号），导出窗口的列清单要一口气念完这 20 列。

窄到 420px（手机、分屏、宿主的侧边面板）时这张表不会变窄，它**就是**要横
滚：把 20 列挤进一柱宽度只会把每一列都挤成看不懂的样子。窄屏要验的是两条冻
结列没有把中间吃光，以及滚动条到得了两头。`,...Q.parameters?.docs?.description}}},$.parameters={...$.parameters,docs:{...$.parameters?.docs,source:{originalSource:`{
  name: '宽表 · 420 窄栏里的封顶',
  args: {
    wide: true,
    withActions: true,
    narrowHost: true,
    narrowWidth: 420
  }
}`,...$.parameters?.docs?.source},description:{story:`同一张宽表，装进一根 420px 的柱子——手机、分屏、宿主的侧边面板都是这个宽
度。这是 **D17-4 那条封顶**唯一看得见的地方。

封顶之前：钉住的三列（勾选 42 + \`运单号\` 86 + 宿主操作列 104）合计 232px。
这张故事里结果区量到 371px，232 就占掉 **63%**；用户 2026-09-21 在真机
420×860 上量到的结果区只有 286px，占 **81%**，留给其余 19 列的只有 54px——
中间最窄的一列 44px、最宽的 247px，横滚到哪儿都在看半列。冻结列的宽度是固
定的，视口越窄它占的比例越大，而此前没有任何一处封顶。

封顶之后：钉住的一组不得超过结果区可视宽的**一半**，超出就从**最外侧**开始
放掉冻结——这里放掉的是宿主的操作列，剩下 128px（34%），中间拿回 243px
（66%）。放掉的只是这一次的渲染，配置里那一条 \`pinned\` 一个字没动：把这根
柱子拉宽，操作列自己就回到右边钉住。**主键永远不放**，一行滚到哪儿都还说得
出自己是哪一单。

**放得下就一个都不放**：中间要滚，冻结列才吃得掉东西；一张本来就放得下的表
上放掉冻结，一个像素也换不回来，只是把 D13 的框拆了。

代价写在这里：操作列一旦放掉冻结，右边那道 D13 的边就跟着走（除非配置自己
在右边钉了一列）。中间读不出来的时候，框的那一头没有它值钱。`,...$.parameters?.docs?.description}}}})))()}export{Ae as A,h as B,w as C,$ as D,U as E,J as F,We as H,M as I,y as L,W as M,q as N,G as O,Y as P,Q as R,E as S,O as T,l as U,Ke as V,ke as W,H as _,Z as a,K as b,g as c,I as d,N as f,B as g,V as h,L as i,X as j,v as k,j as l,z as m,T as n,S as o,P as p,b as r,x as s,R as t,F as u,_ as v,D as w,C as x,A as y,k as z};
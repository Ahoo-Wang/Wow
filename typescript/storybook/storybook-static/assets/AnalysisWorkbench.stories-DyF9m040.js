import{n as e,r as t}from"./rolldown-runtime-DkW27tQK.js";import{t as n}from"./jsx-runtime-DeHZSEgm.js";import{$m as r,A as i,C as a,F as ee,O as o,P as te,Qm as ne,b as s,d as re,eh as c,f as ie,h as l,hd as u,i as d,k as ae,l as oe,m as se,md as f,n as p,o as m,p as ce,r as h,s as g,th as _}from"./styles-Dpj2y9Rj.js";import{n as v,t as y}from"./DataWorkbench-CFjlp2jX.js";var b=t({BarChart:()=>T,CountBars:()=>k,CutShort:()=>P,CutShortTable:()=>F,DailyNewestFirst:()=>V,DailyQuietDays:()=>K,DailyTrendCard:()=>q,EmptyResult:()=>Z,Expandable:()=>$,FailingAggregates:()=>L,FailingProcessors:()=>I,FollowUps:()=>E,FreightBands:()=>Y,HeatmapChart:()=>U,HorizontalBars:()=>D,LatestPerWarehouse:()=>R,LineChart:()=>W,Loading:()=>z,LoadingChart:()=>B,NoVisualization:()=>A,OneBar:()=>G,PieChart:()=>M,PinnedCategoryColor:()=>N,QueryFailed:()=>Q,TableWithTotals:()=>j,TenCities:()=>J,ThreeDimensions:()=>X,TwoMetrics:()=>O,ValueLabels:()=>H,__namedExportsOrder:()=>he,default:()=>w});function x({behaviour:e=`data`,layout:t=`chart`,chart:n=`bar`,series:r=`amount`,pinned:ee=!1,records:te=!1,expandable:ne=!1,visualization:l=!0,latest:u=!1,limit:h,kept:_,waybills:v,failures:b,savedFunnel:x,labels:C=!1,heatmap:w=!1,horizontal:T=!1}){let{groups:E,metrics:D}=m(),O=f({type:n},E,D),k=m({layout:t,...h===void 0?{}:{limit:h,sort:[{alias:`amount`,direction:c.DESC}]},..._===void 0?{}:{having:{type:`CONDITION`,metric:`amount`,operator:`GT`,value:_}},chart:{...O,...O.pie?{pie:{...O.pie,value:`amount`,maxSlices:3}}:{cartesian:{...O.cartesian,series:r===`both`?[{metric:`amount`},{metric:`orders`,axis:`right`}]:[{metric:r===`orders`?`orders`:`amount`}],...T?{orientation:`horizontal`}:{}}},...ee?{colors:pe}:{},...C?{labels:!0}:{}},table:{columns:[{alias:`warehouse`},{alias:`orders`},{alias:`amount`}],totals:!0}}),A=w?le(t,C):u?ue(t):x?{...k,chart:{type:`funnel`,funnel:{stages:{from:`group`,category:`warehouse`,...x}}}}:k;if(b){let t=se(de(b));return(0,S.jsx)(p,{create:()=>g({behaviour:e,definitions:[ie,s],source:ce(e),instances:[t]}),children:e=>(0,S.jsx)(y,{engine:e,definitionId:ie.id,instanceId:t.id,...d,kinds:[`analysis`],features:{visualization:l}})})}if(v){let n=fe(v,t),r=ae(C?{...n,chart:{...n.chart,labels:!0}}:n),a=v===`three-way`?me:o;return(0,S.jsx)(p,{create:()=>g({behaviour:e,definitions:[a,s],source:i(e),instances:[r]}),children:e=>(0,S.jsx)(y,{engine:e,definitionId:o.id,instanceId:r.id,...d,kinds:[`analysis`],features:{visualization:l}})})}return(0,S.jsx)(p,{create:()=>g({behaviour:e,instances:[{...a[1],config:A}],...ne?{definitions:[re,s]}:u?{definitions:[oe,s]}:{}}),children:e=>(0,S.jsx)(y,{engine:e,definitionId:`orders`,instanceId:a[1].id,...d,kinds:te?[`record`,`analysis`]:[`analysis`],features:{visualization:l}})})}function le(e,t){let n=[{alias:`warehouse`,field:`warehouse`,type:`TERMS`},{alias:`status`,field:`status`,type:`TERMS`}],r=[{alias:`amount`,type:`NUMERIC`,function:`SUM`,expression:{type:`FIELD`,field:`amount`}}];return m({layout:e,groups:n,metrics:r,table:{columns:[]},chart:{...f({type:`heatmap`},n,r),...t?{labels:!0}:{}}})}function ue(e){let{groups:t}=m(),n=[{alias:`orders`,type:`COUNT`},{alias:`latest`,type:`NUMERIC`,function:`MAX`,expression:{type:`FIELD`,field:`createdAt`}}];return m({layout:e,metrics:n,sort:[{alias:`latest`,direction:c.DESC}],table:{columns:[]},chart:f({type:`bar`},t,n,new Set([`latest`]))})}function de(e){let t=e===`processor`,n=[t?{type:`TERMS`,field:`processor`,alias:`processor`}:{type:`TERMS`,field:`aggregateId`,alias:`aggregate`}],r={alias:`failures`,type:`COUNT`},i=t?[r,{alias:`retries`,type:`NUMERIC`,function:`SUM`,expression:{type:`FIELD`,field:`retries`}}]:[r];return m({layout:`table`,groups:n,metrics:i,sort:[{alias:`failures`,direction:c.DESC}],limit:t?2:100,table:{columns:[]},chart:f({type:`bar`},n,i)})}function fe(e,t){if(e===`three-way`){let e=[{type:`TERMS`,field:`destination`,alias:`city`},{type:`TERMS`,field:`carrier`,alias:`carrier`},{type:`TERMS`,field:`channel`,alias:`channel`}],n=[{alias:`waybills`,type:`COUNT`}];return m({layout:t,groups:e,metrics:n,sort:[{alias:`waybills`,direction:c.DESC}],table:{columns:[]},chart:f({type:`bar`},e,n)})}if(e===`bands`){let e=[{type:`HISTOGRAM`,field:`amount`,alias:`band`,interval:500}],n=[{alias:`waybills`,type:`COUNT`}];return m({layout:t,groups:e,metrics:n,sort:[{alias:`band`,direction:c.ASC}],table:{columns:[]},chart:f({type:`bar`},e,n)})}if(e===`cities`){let e=[{type:`TERMS`,field:`destination`,alias:`city`}],n=[{alias:`amount`,type:`NUMERIC`,function:`SUM`,expression:{type:`FIELD`,field:`amount`}}];return m({layout:t,groups:e,metrics:n,sort:[{alias:`amount`,direction:c.DESC}],table:{columns:[]},chart:f({type:`pie`},e,n)})}let n=[{type:`DATE_HISTOGRAM`,field:`createdAt`,alias:`day`,unit:`DAY`}],r=[{alias:`waybills`,type:`COUNT`}];return m({layout:t,...e===`daily-quiet`?{filter:{op:`and`,children:[{field:`destination`,operator:`IN`,value:[`杭州`,`上海`]}]}}:{},groups:n,metrics:r,sort:[{alias:`day`,direction:c.DESC}],limit:30,table:{columns:[]},chart:f({type:e===`daily`?`bar`:e===`daily-quiet`?`line`:`metric`},n,r)})}var S,pe,me,C,w,T,E,D,O,k,A,j,M,N,P,F,I,L,R,z,B,V,H,U,W,G,K,q,J,Y,X,Z,Q,$,he;function ge(){return(ge=e((()=>{r(),_(),u(),v(),ee(),l(),h(),S=n(),pe={"CN-SOUTH":`#7c3aed`,amount:`#0f766e`},me={...o,analysis:{...o.analysis,fields:[...o.analysis.fields,...[`carrier`,`channel`].map(e=>({field:e,groups:[ne.TERMS],functions:[]}))]}},C=`内存 ViewStore · 四个仓库的聚合结果`,w={parameters:{layout:`fullscreen`,docs:{description:{component:`**分析视图 · 分析工作台**

分组与指标进去，图表或表格出来。

- **数据源**：${C}。
- **准备**：每次挂载都新建引擎与存储；分组、指标与图型来自保存的配置。
- **操作**：打开任一场景，它放在宿主应用里——顶部导航与左侧应用导航是宿主的，中间那一块是视图引擎——像使用者看到的一样。
- **观察**：切换 Table／Chart 会重新执行，因为图表整形发生在投影层。`}}},decorators:[e=>(0,S.jsx)(te,{current:`analysis`,service:{fixture:C},children:(0,S.jsx)(e,{})})],title:`View Engine/分析视图/分析工作台`,component:x,args:{behaviour:`data`,layout:`chart`,chart:`bar`,series:`amount`,pinned:!1,records:!1,expandable:!1,visualization:!0,latest:!1,labels:!1,heatmap:!1},argTypes:{heatmap:{control:`boolean`},labels:{control:`boolean`},latest:{control:`boolean`},limit:{table:{disable:!0}},kept:{table:{disable:!0}},savedFunnel:{table:{disable:!0}},waybills:{control:`inline-radio`,options:[void 0,`daily`,`daily-card`,`daily-quiet`,`cities`,`bands`,`three-way`]},records:{control:`boolean`},expandable:{control:`boolean`},behaviour:{control:`inline-radio`,options:[`data`,`empty`,`slow`,`failing`]},layout:{control:`inline-radio`,options:[`table`,`chart`]},chart:{control:`inline-radio`,options:[`bar`,`line`,`pie`]},series:{control:`inline-radio`,options:[`amount`,`both`]},pinned:{control:`boolean`},visualization:{control:`boolean`}}},T={args:{layout:`chart`,chart:`bar`}},E={args:{layout:`chart`,chart:`bar`,records:!0}},D={args:{layout:`chart`,chart:`bar`,horizontal:!0}},O={args:{layout:`chart`,chart:`bar`,series:`both`}},k={args:{layout:`chart`,chart:`bar`,series:`orders`}},A={args:{layout:`chart`,chart:`bar`,visualization:!1}},j={args:{layout:`table`}},M={args:{layout:`chart`,chart:`pie`}},N={args:{layout:`chart`,chart:`pie`,pinned:!0}},P={args:{layout:`chart`,chart:`pie`,limit:2}},F={args:{layout:`table`,limit:2}},I={args:{failures:`processor`}},L={args:{failures:`aggregate`}},R={args:{layout:`table`,latest:!0}},z={args:{behaviour:`slow`,layout:`table`}},B={args:{behaviour:`slow`,layout:`chart`}},V={args:{layout:`chart`,waybills:`daily`}},H={args:{layout:`chart`,waybills:`daily`,labels:!0}},U={args:{layout:`chart`,heatmap:!0,labels:!0}},W={args:{layout:`chart`,chart:`line`,series:`both`}},G={args:{layout:`chart`,limit:1}},K={args:{layout:`chart`,waybills:`daily-quiet`}},q={args:{layout:`chart`,waybills:`daily-card`}},J={args:{layout:`chart`,waybills:`cities`}},Y={args:{layout:`chart`,waybills:`bands`}},X={args:{layout:`table`,waybills:`three-way`}},Z={args:{behaviour:`empty`,layout:`table`}},Q={args:{behaviour:`failing`}},$={args:{layout:`table`,expandable:!0}},he=`BarChart.FollowUps.HorizontalBars.TwoMetrics.CountBars.NoVisualization.TableWithTotals.PieChart.PinnedCategoryColor.CutShort.CutShortTable.FailingProcessors.FailingAggregates.LatestPerWarehouse.Loading.LoadingChart.DailyNewestFirst.ValueLabels.HeatmapChart.LineChart.OneBar.DailyQuietDays.DailyTrendCard.TenCities.FreightBands.ThreeDimensions.EmptyResult.QueryFailed.Expandable`.split(`.`),T.parameters={...T.parameters,docs:{...T.parameters?.docs,source:{originalSource:`{
  args: {
    layout: 'chart',
    chart: 'bar'
  }
}`,...T.parameters?.docs?.source},description:{story:`The default: a bar chart of one metric across four warehouses.`,...T.parameters?.docs?.description}}},E.parameters={...E.parameters,docs:{...E.parameters?.docs,source:{originalSource:`{
  args: {
    layout: 'chart',
    chart: 'bar',
    records: true
  }
}`,...E.parameters?.docs?.source},description:{story:`追问（D20 Ⅳ）：按下一根柱子——或表格布局里的一行——弹出三项，
「查看这些记录」「按其他维度细分…」「只看这一组」。这个工作台同时列着记录视图，
所以第一项在：它在同一个工作台里开出一个未保存的记录视图，叫「订单 · 这一组」，
标题栏下一颗「返回」。另外两项同样开在旁边、同样能返回，原来那个视图不变脏。`,...E.parameters?.docs?.description}}},D.parameters={...D.parameters,docs:{...D.parameters?.docs,source:{originalSource:`{
  args: {
    layout: 'chart',
    chart: 'bar',
    horizontal: true
  }
}`,...D.parameters?.docs?.source},description:{story:`横向柱，每根柱子右端写着它的金额：最长那根的数也整个留在图框里（从前首页
「活动失败最多的处理器」最长那根的「59.6万」被图框切掉「万」，读成「59.6」）。`,...D.parameters?.docs?.description}}},O.parameters={...O.parameters,docs:{...O.parameters?.docs,source:{originalSource:`{
  args: {
    layout: 'chart',
    chart: 'bar',
    series: 'both'
  }
}`,...O.parameters?.docs?.source},description:{story:`Two metrics on one chart: a series each, the count on a right-hand axis,
and the legend a cartesian chart shows only once it has more than one.`,...O.parameters?.docs?.description}}},k.parameters={...k.parameters,docs:{...k.parameters?.docs,source:{originalSource:`{
  args: {
    layout: 'chart',
    chart: 'bar',
    series: 'orders'
  }
}`,...k.parameters?.docs?.source},description:{story:`每个仓库的订单数画成柱。从这里在图型网格里选「组合图」：金额的总和作为折线
加进来，量的是钱而不是个数，于是坐到右轴上、右轴写它的列标题，两根轴的刻度
落在同一组网格线上。`,...k.parameters?.docs?.description}}},A.parameters={...A.parameters,docs:{...A.parameters?.docs,source:{originalSource:`{
  args: {
    layout: 'chart',
    chart: 'bar',
    visualization: false
  }
}`,...A.parameters?.docs?.source},description:{story:`宿主关掉了可视化（D18 Ⅺ）：结果照它保存的样子画，工具栏上没有「可视化」
按钮，左侧栏也没有那块面板——关掉的功能不存在，而不是置灰。`,...A.parameters?.docs?.description}}},j.parameters={...j.parameters,docs:{...j.parameters?.docs,source:{originalSource:`{
  args: {
    layout: 'table'
  }
}`,...j.parameters?.docs?.source},description:{story:`The same result as rows, with the totals row from its own ungrouped query.`,...j.parameters?.docs?.description}}},M.parameters={...M.parameters,docs:{...M.parameters?.docs,source:{originalSource:`{
  args: {
    layout: 'chart',
    chart: 'pie'
  }
}`,...M.parameters?.docs?.source},description:{story:`A pie needs a category and one value, and the kernel merges the tail.`,...M.parameters?.docs?.description}}},N.parameters={...N.parameters,docs:{...N.parameters?.docs,source:{originalSource:`{
  args: {
    layout: 'chart',
    chart: 'pie',
    pinned: true
  }
}`,...N.parameters?.docs?.source},description:{story:`\`chart.colors\` names a category — 华南 — and the slice takes that colour
while the other slices keep their palette slots. Flip to a bar to see the
same map colour a series by its alias instead.`,...N.parameters?.docs?.description}}},P.parameters={...P.parameters,docs:{...P.parameters?.docs,source:{originalSource:`{
  args: {
    layout: 'chart',
    chart: 'pie',
    limit: 2
  }
}`,...P.parameters?.docs?.source},description:{story:`四个仓库、上限两行：引擎多要一行（发出去的 \`limit\` 是 3），第三行回来了，
于是"还有更多未列出"是问出来的答案而不是猜的——那一行只回答问题，不上屏。
饼图是最坏的一种：每个扇区的占比都是拿"已显示的部分"当分母算出来的，所以
上方多一条 warning。`,...P.parameters?.docs?.description}}},F.parameters={...F.parameters,docs:{...F.parameters?.docs,source:{originalSource:`{
  args: {
    layout: 'table',
    limit: 2
  }
}`,...F.parameters?.docs?.source},description:{story:`The same cut, as rows: the table says it too, with the same one line — and
the totals row under it still covers every order, because it comes from its
own ungrouped query. Hover it to read the scope.`,...F.parameters?.docs?.description}}},I.parameters={...I.parameters,docs:{...I.parameters?.docs,source:{originalSource:`{
  args: {
    failures: 'processor'
  }
}`,...I.parameters?.docs?.source},description:{story:`每个仓库最晚的一单（生产审查）：创建时间的最大值是一个时刻，读作界面
语言与时区下的日期时间，而不是十三位毫秒；表头说「创建时间的最晚」。
切到图表，柱子量的是订单数——时刻没有零点可以让柱子从那里长。
失败最多的两个处理器（2026-09-23 审查 P1）：名字很长，表一打开就合身；按表头
把次数改成升序，留下失败最少的两个，名字只有几个字母——列宽是第一次画时量
的，之后钉住，列一格不挪。`,...I.parameters?.docs?.description}}},L.parameters={...L.parameters,docs:{...L.parameters?.docs,source:{originalSource:`{
  args: {
    failures: 'aggregate'
  }
}`,...L.parameters?.docs?.source},description:{story:`每个聚合失败几次：聚合 ID 在记录视图里读作可复制的值，这里用与它同一个
等宽字，0 和 O、l 和 1 分得开，一列码上下对齐。`,...L.parameters?.docs?.description}}},R.parameters={...R.parameters,docs:{...R.parameters?.docs,source:{originalSource:`{
  args: {
    layout: 'table',
    latest: true
  }
}`,...R.parameters?.docs?.source}}},z.parameters={...z.parameters,docs:{...z.parameters?.docs,source:{originalSource:`{
  args: {
    behaviour: 'slow',
    layout: 'table'
  }
}`,...z.parameters?.docs?.source},description:{story:`第一次的答案还在路上（数据源慢 1.5 秒）：结果区先画出答案的形状——表格是
几行灰条，图表是一块绘图区——工具栏、条件带与页脚已经在各自的位置上，行落地
时换的是框里的内容，不是任何东西的位置。`,...z.parameters?.docs?.description}}},B.parameters={...B.parameters,docs:{...B.parameters?.docs,source:{originalSource:`{
  args: {
    behaviour: 'slow',
    layout: 'chart'
  }
}`,...B.parameters?.docs?.source},description:{story:`同上，保存的是图表：骨架是一块绘图区。`,...B.parameters?.docs?.description}}},V.parameters={...V.parameters,docs:{...V.parameters?.docs,source:{originalSource:`{
  args: {
    layout: 'chart',
    waybills: 'daily'
  }
}`,...V.parameters?.docs?.source},description:{story:`每天几单，按日倒序存着——表格今天在最上面。画成柱，时间仍从左往右：
投影层按时间排时间轴，表格留着视图自己的排序（2026-09-23 审查）。`,...V.parameters?.docs?.description}}},H.parameters={...H.parameters,docs:{...H.parameters?.docs,source:{originalSource:`{
  args: {
    layout: 'chart',
    waybills: 'daily',
    labels: true
  }
}`,...H.parameters?.docs?.source},description:{story:`三十天的柱，每根柱上写着它的数：写得下的都写，会压到别的数上的那一个不写
——而不是叠在一起（ECharts 的 \`labelLayout.hideOverlap\`，D21）。数写得短，
与刻度同一个读法。`,...H.parameters?.docs?.description}}},U.parameters={...U.parameters,docs:{...U.parameters?.docs,source:{originalSource:`{
  args: {
    layout: 'chart',
    heatmap: true,
    labels: true
  }
}`,...U.parameters?.docs?.source},description:{story:`仓库 × 状态的热力图：格子铺满绘图区、第一行在上，深浅按金额，底下一条色标
读得回数；格子上写着金额（从前是挤在一角的灰格子，没有色标也没有数）。`,...U.parameters?.docs?.description}}},W.parameters={...W.parameters,docs:{...W.parameters?.docs,source:{originalSource:`{
  args: {
    layout: 'chart',
    chart: 'line',
    series: 'both'
  }
}`,...W.parameters?.docs?.source},description:{story:`两个指标画成折线：每个点一颗圆点，线的两端各离绘图区的边半格，金额在左轴、
订单数在右轴，两根轴各有标题（D21 第二批）。`,...W.parameters?.docs?.description}}},G.parameters={...G.parameters,docs:{...G.parameters?.docs,source:{originalSource:`{
  args: {
    layout: 'chart',
    limit: 1
  }
}`,...G.parameters?.docs?.source},description:{story:`只剩一组时柱子也只有它该有的宽：从前一组就是一整块铺满绘图区的色板
（定价「按状态分布」，真实后端 2026-09-23）。`,...G.parameters?.docs?.description}}},K.parameters={...K.parameters,docs:{...K.parameters?.docs,source:{originalSource:`{
  args: {
    layout: 'chart',
    waybills: 'daily-quiet'
  }
}`,...K.parameters?.docs?.source},description:{story:`只看发往杭州、上海的运单：几天有单，其余的日子一单也没有。折线一天一格，
没单的日子落到 0，而不是从有单的那天直接连到下一个有单的日子（2026-09-23
图表审查 P0-4）。`,...K.parameters?.docs?.description}}},q.parameters={...q.parameters,docs:{...q.parameters?.docs,source:{originalSource:`{
  args: {
    layout: 'chart',
    waybills: 'daily-card'
  }
}`,...q.parameters?.docs?.source},description:{story:`同一个按日倒序的问题画成指标卡：迷你趋势同样从最早的一天画起。`,...q.parameters?.docs?.description}}},J.parameters={...J.parameters,docs:{...J.parameters?.docs,source:{originalSource:`{
  args: {
    layout: 'chart',
    waybills: 'cities'
  }
}`,...J.parameters?.docs?.source},description:{story:`十个目的城市的运费：色板八色，饼图画出七个城市加一片灰色的「其他」，
八片八种颜色——从前色板只有五色，第六片起与前面的同色。`,...J.parameters?.docs?.description}}},Y.parameters={...Y.parameters,docs:{...Y.parameters?.docs,source:{originalSource:`{
  args: {
    layout: 'chart',
    waybills: 'bands'
  }
}`,...Y.parameters?.docs?.source},description:{story:`运费区间：按 500 一档，每档几单。一档的键只是它的下界，从前读成「¥0.00」
「¥500.00」，说不出是哪一段（2026-09-23 真实后端走查）；现在横轴、提示、
读屏表、表格与追问菜单的标题都读成「¥0～500」，按界面语言写短（万、亿）。`,...Y.parameters?.docs?.description}}},X.parameters={...X.parameters,docs:{...X.parameters?.docs,source:{originalSource:`{
  args: {
    layout: 'table',
    waybills: 'three-way'
  }
}`,...X.parameters?.docs?.source},description:{story:`三个维度：目的城市 × 承运商 × 运输方式的单数。没有一种图画得了三个维度，
它以表格跑出来；切到「图表」仍是这张表，状态行说一句为什么；「可视化」里
每种图都置灰并写原因。从前图表在表格布局下也参与校验，整次查询被拦下。`,...X.parameters?.docs?.description}}},Z.parameters={...Z.parameters,docs:{...Z.parameters?.docs,source:{originalSource:`{
  args: {
    behaviour: 'empty',
    layout: 'table'
  }
}`,...Z.parameters?.docs?.source},description:{story:`An aggregation that matched no group keeps its toolbar. With no condition
in force the range is already every record, so there is nothing to change
in the tray: the empty result says why, and offers no button.`,...Z.parameters?.docs?.description}}},Q.parameters={...Q.parameters,docs:{...Q.parameters?.docs,source:{originalSource:`{
  args: {
    behaviour: 'failing'
  }
}`,...Q.parameters?.docs?.source},description:{story:`A failed aggregation keeps the toolbar and the conditions on screen, and
says the failure under the toolbar with 「重试」.`,...Q.parameters?.docs?.description}}},$.parameters={...$.parameters,docs:{...$.parameters?.docs,source:{originalSource:`{
  args: {
    layout: 'table',
    expandable: true
  }
}`,...$.parameters?.docs?.source},description:{story:`一份声明了展开链的定义：托盘里多出「展开」那一槽（D20 屏 G）。展开改的是
「数的是什么」——展开到明细项，问题就是关于明细项的，仓库那个维度跟着离开。
故事的数据源不求值 \`elements\`，所以这个故事到托盘为止，不按「应用」。`,...$.parameters?.docs?.description}}}})))()}export{ge as A,N as C,X as D,J as E,O,M as S,j as T,R as _,F as a,B as b,q as c,L as d,I as f,D as g,U as h,P as i,w as j,H as k,Z as l,Y as m,T as n,V as o,E as p,k as r,K as s,b as t,$ as u,W as v,Q as w,G as x,z as y};
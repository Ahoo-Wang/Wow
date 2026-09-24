import{n as e,r as t}from"./rolldown-runtime-DkW27tQK.js";import{t as n}from"./react-Q1GcV6wX.js";import{t as r}from"./jsx-runtime-DeHZSEgm.js";import{$n as i,C as a,E as o,F as s,Ff as ee,If as te,In as c,P as ne,Pn as l,Tr as u,ao as d,dp as f,er as p,h as m,i as h,io as g,n as _,or as v,r as y,s as b,sr as x,up as S,v as C,wr as w,x as T}from"./styles-Dpj2y9Rj.js";import{l as re,u as E}from"./AnalysisParts-B9WGI7R-.js";import{n as D,t as O}from"./DataWorkbench-CFjlp2jX.js";var k=t({Advanced:()=>G,Negated:()=>q,NumberList:()=>Y,Reference:()=>J,Simple:()=>K,UnregisteredKind:()=>Z,WithTime:()=>X,__namedExportsOrder:()=>Q,default:()=>W});function A(){return i({id:`orders-stale`,definition:{...C,fields:[...C.fields,{name:`tone`,label:`色板`,kind:`swatch`}]},config:T({filter:{op:`and`,children:[{field:`warehouse`,operator:`NOT_IN`,value:[`CN-EAST`]},{field:`tone`,operator:`EQ`,value:`#ff8800`}]}}),title:`类型已下线`,scope:`personal`,saved:null,kinds:ee,limits:S,environment:w(),source:o(),runner:new v})}function j(){let[e]=(0,N.useState)(A);(0,N.useEffect)(()=>()=>e.dispose(),[e]);let t=c(e);return(0,P.jsx)(g,{...h,children:(0,P.jsx)(re,{filter:t})})}function M({instanceId:e,hostWidth:t}){let n=(0,P.jsx)(_,{create:()=>b({definitions:[F],instances:[L,R,z,B,V,H]}),children:t=>(0,P.jsx)(O,{engine:t,definitionId:`orders`,instanceId:e,...h})});return t===void 0?n:(0,P.jsx)(`div`,{"data-pill-host":!0,style:{width:t,overflow:`hidden`},children:n})}var N,P,F,I,L,R,z,B,V,H,U,W,G,K,q,J,Y,X,Z,Q;function $(){return($=e((()=>{N=n(),te(),p(),f(),u(),x(),l(),D(),E(),d(),s(),m(),y(),P=r(),F={...C,fieldGroups:[{id:`basics`,label:`基础信息`,fields:[`warehouse`,`status`]},{id:`money`,label:`金额`,fields:[`amount`]},{id:`time`,label:`时间`,fields:[`createdAt`]}],fields:[...C.fields,{name:`items`,label:`商品行`,kind:`elementMatch`,elements:[{name:`sku`,label:`SKU`,kind:`string`},{name:`qty`,label:`数量`,kind:`number`}]},{name:`@deleted`,label:`删除状态`,kind:`deletion`}]},I={op:`and`,children:[{field:`warehouse`,operator:`IN`,value:[`CN-EAST`]},{field:`status`,operator:`IN`,value:[`PENDING`,`SHIPPED`]},{field:`amount`,operator:`BETWEEN`,value:[100,5e3]},{field:`createdAt`,operator:`BETWEEN`,value:{type:`preset`,preset:`thisMonth`}},{field:`id`,operator:`EQ`,value:``},{op:`or`,children:[{field:`warehouse`,operator:`IN`,value:[`CN-NORTH`]},{field:`amount`,operator:`GT`,value:2e4}]},{field:`items`,operator:`ELEMENT_MATCH`,value:{op:`and`,children:[{field:`items.sku`,operator:`EQ`,value:`A-1`},{field:`items.qty`,operator:`GT`,value:2}]}}]},L={...a[0],id:`orders-rich`,title:`条件齐全的视图`,config:T({filterMode:`advanced`,filter:I})},R={...a[0],id:`orders-simple`,title:`简单条件`,config:T({filter:{op:`and`,children:[{field:`status`,operator:`IN`,value:[`PENDING`]},{field:`amount`,operator:`GTE`,value:100}]}})},z={...a[0],id:`orders-negated`,title:`取反条件`,config:T({filter:{op:`and`,children:[{op:`nor`,children:[{field:`status`,operator:`IN`,value:[`CANCELLED`]}]},{field:`amount`,operator:`GTE`,value:100}]}})},B={...a[0],id:`orders-reference`,title:`客户条件`,config:T({filter:{op:`and`,children:[{field:`customer`,operator:`IN`,value:{items:[{id:`c-03`,label:`晨光食品`}]}}]}})},V={...a[0],id:`orders-number-list`,title:`数值多选`,config:T({filter:{op:`and`,children:[{field:`amount`,operator:`IN`,value:[100,1200,5e3]}]}})},H={...a[0],id:`orders-with-time`,title:`带时刻的日期`,config:T({filter:{op:`and`,children:[{field:`createdAt`,operator:`BETWEEN`,value:{type:`absolute`,from:`2026-09-15`,to:`2026-09-17`}}]}})},U=`内存 ViewStore · 带商品行数组字段的订单定义`,W={parameters:{layout:`fullscreen`,docs:{description:{component:`**数据视图 · 筛选编辑器**

分组是块，条件是内联的 pill；持有谓词的条件和分组一样是块。

- **数据源**：${U}。
- **准备**：每次挂载都新建引擎与存储。
- **操作**：打开任一场景，展开标题栏上的「筛选」。编辑器在宿主应用的页面区里——顶部导航与左侧应用导航是宿主的，中间那一块是视图引擎——像使用者看到的一样。
- **观察**：同一分组内每个字段只出现一次，添加条件的菜单只列出尚未使用的字段；未填写的条件是虚线，出错的条件标红。`}}},decorators:[e=>(0,P.jsx)(ne,{current:`filters`,service:{fixture:U},padded:!0,children:(0,P.jsx)(e,{})})],title:`View Engine/数据视图/筛选编辑器`,component:M,args:{instanceId:`orders-rich`},argTypes:{instanceId:{table:{disable:!0}},hostWidth:{table:{disable:!0}}}},G={args:{instanceId:`orders-rich`}},K={args:{instanceId:`orders-simple`}},q={args:{instanceId:`orders-negated`}},J={args:{instanceId:`orders-reference`}},Y={args:{instanceId:`orders-number-list`}},X={args:{instanceId:`orders-with-time`}},Z={render:()=>(0,P.jsx)(j,{})},Q=[`Advanced`,`Simple`,`Negated`,`Reference`,`NumberList`,`WithTime`,`UnregisteredKind`],G.parameters={...G.parameters,docs:{...G.parameters?.docs,source:{originalSource:`{
  args: {
    instanceId: 'orders-rich'
  }
}`,...G.parameters?.docs?.source},description:{story:`Every shape of condition at once: pills, a nested group, an element match.`,...G.parameters?.docs?.description}}},K.parameters={...K.parameters,docs:{...K.parameters?.docs,source:{originalSource:`{
  args: {
    instanceId: 'orders-simple'
  }
}`,...K.parameters?.docs?.source},description:{story:`Simple mode: the root's conditions as one strip, nothing else.`,...K.parameters?.docs?.description}}},q.parameters={...q.parameters,docs:{...q.parameters?.docs,source:{originalSource:`{
  args: {
    instanceId: 'orders-negated'
  }
}`,...q.parameters?.docs?.source},description:{story:`简单模式里的取反：展开「筛选」，「状态」那条 pill 的开关是按下的，句子里操作符
前面多了一个「不」；已应用条把它说成「排除 …」。再按一次开关就是原来的条件。`,...q.parameters?.docs?.description}}},J.parameters={...J.parameters,docs:{...J.parameters?.docs,source:{originalSource:`{
  args: {
    instanceId: 'orders-reference'
  }
}`,...J.parameters?.docs?.source},description:{story:`引用字段的候选是搜出来的：展开「筛选」，「客户」那条 pill 里已选的客户是一枚
chip，输入框说「输入以搜索」；点进去先列第一页，打字停下才再问源，尾部有「更多」；
拿掉 chip、再选一个，「应用」后表格随之变。`,...J.parameters?.docs?.description}}},Y.parameters={...Y.parameters,docs:{...Y.parameters?.docs,source:{originalSource:`{
  args: {
    instanceId: 'orders-number-list'
  }
}`,...Y.parameters?.docs?.source},description:{story:`A numeric \`IN\` as a list that grows: one chip per value with a remove
button of its own, an entry field, and the button that commits it. Unfold
筛选 in the title bar to type a fourth value.`,...Y.parameters?.docs?.description}}},X.parameters={...X.parameters,docs:{...X.parameters?.docs,source:{originalSource:`{
  args: {
    instanceId: 'orders-with-time'
  }
}`,...X.parameters?.docs?.source},description:{story:`A date condition on a field that carries a time of day. Unfold 筛选 and
open the calendar: the clock is under it, in the same popover, and leaving
a box empty keeps that end of the range at the day itself — the first
millisecond as a start, the last as an end.`,...X.parameters?.docs?.description}}},Z.parameters={...Z.parameters,docs:{...Z.parameters?.docs,source:{originalSource:`{
  render: () => <UnregisteredKindDemo />
}`,...Z.parameters?.docs?.source},description:{story:`一条没人能编辑的条件。色板那条画成只读——字段名、操作符的那个
词、配置里存着的原值，外加一句说明它为什么不能改——✕ 照常可按，「查询」被挡
住并在旁边报出待修正的条数。旁边那条仓库条件一切如常，只读只针对那一条。`,...Z.parameters?.docs?.description}}}})))()}export{J as a,X as c,Y as i,$ as l,k as n,K as o,q as r,Z as s,G as t,W as u};
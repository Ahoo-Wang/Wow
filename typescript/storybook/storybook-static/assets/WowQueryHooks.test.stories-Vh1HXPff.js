import{n as e}from"./rolldown-runtime-DkW27tQK.js";import{a as t,c as n,i as r,n as i,r as a,s as o,t as s}from"./WowQueryHooks.stories-C9FqjFQa.js";async function c(e,t){let n=d(e);await u.click(n.getByRole(`button`,{name:`Run query`})),await l(await n.findByText(t)).toBeVisible()}var l,u,d,f,p,m,h,g,_,v;function y(){return(y=e((()=>{o(),{expect:l,userEvent:u,within:d}=__STORYBOOK_MODULE_TEST__,f={...n,title:`React Hooks/Wow Queries/回归`,tags:[`!dev`,`!autodocs`,`test`]},p={...r,tags:[`!dev`,`!autodocs`,`test`],play:({canvasElement:e})=>c(e,`Single · Ada`)},m={...i,tags:[`!dev`,`!autodocs`,`test`],play:({canvasElement:e})=>c(e,`List · Ada, Lin`)},h={...a,tags:[`!dev`,`!autodocs`,`test`],play:({canvasElement:e})=>c(e,`Paged · 2 of 2`)},g={...s,tags:[`!dev`,`!autodocs`,`test`],play:({canvasElement:e})=>c(e,`Count · 2`)},_={...t,tags:[`!dev`,`!autodocs`,`test`],play:({canvasElement:e})=>c(e,`Stream · Ada, Lin`)},v=[`Single`,`List`,`Paged`,`Count`,`Streaming`],p.parameters={...p.parameters,docs:{...p.parameters?.docs,source:{originalSource:`{
  ...DisplaySingle,
  tags: ['!dev', '!autodocs', 'test'],
  play: ({
    canvasElement
  }) => queryAndExpect(canvasElement, 'Single · Ada')
}`,...p.parameters?.docs?.source}}},m.parameters={...m.parameters,docs:{...m.parameters?.docs,source:{originalSource:`{
  ...DisplayList,
  tags: ['!dev', '!autodocs', 'test'],
  play: ({
    canvasElement
  }) => queryAndExpect(canvasElement, 'List · Ada, Lin')
}`,...m.parameters?.docs?.source}}},h.parameters={...h.parameters,docs:{...h.parameters?.docs,source:{originalSource:`{
  ...DisplayPaged,
  tags: ['!dev', '!autodocs', 'test'],
  play: ({
    canvasElement
  }) => queryAndExpect(canvasElement, 'Paged · 2 of 2')
}`,...h.parameters?.docs?.source}}},g.parameters={...g.parameters,docs:{...g.parameters?.docs,source:{originalSource:`{
  ...DisplayCount,
  tags: ['!dev', '!autodocs', 'test'],
  play: ({
    canvasElement
  }) => queryAndExpect(canvasElement, 'Count · 2')
}`,...g.parameters?.docs?.source}}},_.parameters={..._.parameters,docs:{..._.parameters?.docs,source:{originalSource:`{
  ...DisplayStreaming,
  tags: ['!dev', '!autodocs', 'test'],
  play: ({
    canvasElement
  }) => queryAndExpect(canvasElement, 'Stream · Ada, Lin')
}`,..._.parameters?.docs?.source}}}})))()}y();export{g as Count,m as List,h as Paged,p as Single,_ as Streaming,v as __namedExportsOrder,f as default};
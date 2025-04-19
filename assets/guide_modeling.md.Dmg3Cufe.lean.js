import{_ as h,c as k,ag as e,G as t,w as l,j as i,a,B as p,o as d}from"./chunks/framework.wRLzwZz-.js";const r="/assets/aggregation-pattern.C2giArjJ.svg",E="/assets/single-class-pattern.DF_fHEBW.svg",g="/assets/inheritance-pattern.BuKlktF0.svg",B=JSON.parse('{"title":"聚合根建模","description":"","frontmatter":{},"headers":[],"relativePath":"guide/modeling.md","filePath":"guide/modeling.md","lastUpdated":1744971827000}'),o={name:"guide/modeling.md"};function y(c,s,m,F,u,C){const n=p("center");return d(),k("div",null,[s[3]||(s[3]=e("",4)),t(n,null,{default:l(()=>s[0]||(s[0]=[i("p",null,[i("img",{src:r,alt:"Aggregation Class - Modeling"})],-1)])),_:1}),s[4]||(s[4]=i("h3",{id:"单一类模式",tabindex:"-1"},[a("单一类模式 "),i("a",{class:"header-anchor",href:"#单一类模式","aria-label":'Permalink to "单一类模式"'},"​")],-1)),s[5]||(s[5]=i("p",null,"单一类模式将命令函数、溯源函数以及聚合状态数据放置在一起，这样做的好处是简单直接。",-1)),s[6]||(s[6]=i("div",{class:"danger custom-block"},[i("p",{class:"custom-block-title"},"DANGER"),i("p",null,[a("但是因为所在同一个类中，所以命令函数是可以直接变更聚合状态数据的，这违反了"),i("code",null,"EventSourcing"),a("的原则。 要求开发人员时刻谨记，命令函数只能返回领域事件并交由溯源函数来变更聚合状态数据。")])],-1)),t(n,null,{default:l(()=>s[1]||(s[1]=[i("p",null,[i("img",{src:E,alt:"Single Class - Modeling"})],-1)])),_:1}),s[7]||(s[7]=i("h3",{id:"继承模式",tabindex:"-1"},[a("继承模式 "),i("a",{class:"header-anchor",href:"#继承模式","aria-label":'Permalink to "继承模式"'},"​")],-1)),s[8]||(s[8]=i("p",null,[a("继承模式将状态聚合根作为基类，并且将"),i("code",null,"setter"),a("访问器设置为"),i("code",null,"private"),a("。以避免命令聚合根在命令函数中修改聚合状态数据。")],-1)),t(n,null,{default:l(()=>s[2]||(s[2]=[i("p",null,[i("img",{src:g,alt:"Inheritance - Modeling"})],-1)])),_:1}),s[9]||(s[9]=e("",9))])}const D=h(o,[["render",y]]);export{B as __pageData,D as default};

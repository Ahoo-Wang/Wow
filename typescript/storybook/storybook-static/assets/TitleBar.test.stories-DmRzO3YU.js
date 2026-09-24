import{n as e}from"./rolldown-runtime-DkW27tQK.js";import{R as t,z as n}from"./styles-Dpj2y9Rj.js";import{H as r,V as i,b as a,i as o,z as s}from"./RecordWorkbench.stories-CBypcKb5.js";function c(e,t,n){let r=e.ownerDocument.elementFromPoint(Math.round(t),Math.round(n));return r!==null&&r.closest(`[data-slot="save-actions"]`)!==null}function l(e){return e.querySelector(`[data-slot="view-switcher"]`)?.querySelector(`span`)??e.querySelector(`[data-slot="view-title"]`)}function u(e){return[...e.children].flatMap(e=>{let t=getComputedStyle(e).display;return t===`none`?[]:t===`contents`?u(e):[e]})}function d(e,t,n){return e.style.width=`${t}px`,b(n).width}function f(e,t,n,r=4){let i=[];for(let a=t;a>=n;a-=r){e.style.width=`${a}px`;let t=e.querySelector(`[data-slot="view-header"]`),n=e.querySelector(`[data-slot="view-identity"]`),r=e.querySelector(`[data-slot="view-controls"]`),o=e.querySelector(`[data-slot="save-actions"]`);t.scrollIntoView({block:`center`});let s=e=>i.push(`${a}px: ${e}`);y(b(n),b(r))&&s(`the identity group and the view controls overlap`);for(let e of u(n))v(b(e),b(n))||s(`${e.getAttribute(`data-slot`)??e.tagName} spills out`);v(b(n),b(t))||s(`the identity group is ${Math.round(b(n).width)}px in a ${Math.round(b(t).width)}px bar`);let d=l(e);b(d).width<x(d)-1&&s(`the name is down to ${Math.round(b(d).width)}px`),v(b(n),b(t))&&Math.abs(b(r).right-b(t).right)>1&&s(`the view controls do not end the bar`);let f=b(o.querySelector(`button`)),p=f.top+f.height/2;c(o,f.left+1,p)||s(`Save loses its left edge`),c(o,f.left+f.width/2,p)||s(`Save loses its middle`),c(o,f.right-1,p)||s(`Save loses its right edge`)}return i}var p,m,h,g,_,v,y,b,x,S,C,w,T,E,D,O;function k(){return(k=e((()=>{t(),i(),{expect:p,userEvent:m,waitFor:h,within:g}=__STORYBOOK_MODULE_TEST__,_={...r,title:`View Engine/数据视图/标题栏/回归`,tags:[`!dev`,`!autodocs`,`test`],parameters:{...r.parameters,docs:{description:{component:`How the title bar narrows.

It lives in the browser project and nowhere else: what it asks is what
*width* a flex item resolved to and what the engine would hand a click at
a given point, and jsdom — where the rest of this package's UI suites run —
lays nothing out and hit-tests nothing. Every assertion below reads
\`getBoundingClientRect()\` or \`elementFromPoint()\`, so in jsdom they would
all be zeroes agreeing with each other.`}}}},v=(e,t)=>e.left>=t.left-1&&e.right<=t.right+1,y=(e,t)=>e.left<t.right-1&&t.left<e.right-1&&e.top<t.bottom-1&&t.top<e.bottom-1,b=e=>e.getBoundingClientRect(),x=e=>6*parseFloat(getComputedStyle(e).fontSize),S={...a,play:async({canvasElement:e})=>{let t=g(e);await t.findByRole(`table`);let r=e.querySelector(`[data-narrow-host]`),i=e.querySelector(`[data-slot="view-title"]`);await p(f(r,760,300)).toEqual([]);let a=e.querySelector(`[data-slot="view-switcher"]`);await p(d(r,760,a),`switcher shrinks`).toBeGreaterThan(d(r,320,a)),r.style.width=`760px`,await p(getComputedStyle(a).justifyContent).toBe(`flex-start`);let o=a.querySelector(`span`);await p(b(a).right-b(o).right,`trigger ends at its label`).toBeLessThan(40);let s=e.querySelector(`[data-slot="definition-title"]`);r.style.width=`360px`,await p(getComputedStyle(s).display).toBe(`none`),r.style.width=`760px`,await p(getComputedStyle(s).display).not.toBe(`none`),await m.click(t.getAllByRole(`button`,{name:/订单号/})[0]),await h(()=>p(t.getByRole(`button`,{name:n[`label.save.save`]})).toBeEnabled()),await p(f(r,760,364)).toEqual([]),await m.click(t.getByRole(`button`,{name:n[`label.workbench.expand-sidebar`]})),await h(()=>p(e.querySelector(`[data-slot="view-sidebar"]`)).not.toBeNull()),await p(f(r,1e3,600)).toEqual([]),await p(d(r,1e3,i),`title shrinks`).toBeGreaterThan(d(r,900,i)),await p(i.scrollWidth,`title clipped`).toBeGreaterThan(i.clientWidth),r.style.width=`375px`}},C=e=>{let t=getComputedStyle(e);return`${parseFloat(t.fontSize)}/${t.fontWeight}`},w={...o,play:async({canvasElement:e})=>{let t=g(e);await t.findByRole(`table`);let r=e.querySelector(`[data-slot="definition-title"]`),i=e.querySelector(`[data-slot="view-switcher"] span`);await p(C(r),`定义名`).toBe(`16/600`),await p(C(i),`切换器里的视图名`).toBe(`14/500`),await p(parseFloat(getComputedStyle(r).fontSize)-parseFloat(getComputedStyle(i).fontSize)).toBe(2),await m.click(t.getByRole(`button`,{name:n[`label.workbench.expand-sidebar`]})),await h(()=>p(e.querySelector(`[data-slot="view-list-title"]`)).not.toBeNull()),await p(C(e.querySelector(`[data-slot="view-list-title"]`)),`侧栏标题`).toBe(`16/600`)}},T=e=>({...s,args:{...s.args,theme:e},play:async({canvasElement:t})=>{await g(t).findByRole(`table`),await p(t.querySelector(`[data-slot="view-surface"]`)).toHaveAttribute(`data-theme`,e);let n=t.querySelector(`[data-slot="view-controls"]`),r=n.querySelector(`[data-slot="separator"]`);await p(r,`两端都有东西时才画这根线`).not.toBeNull(),await p(r.getBoundingClientRect().height).toBe(20);let i=g(n).getByRole(`button`,{name:`新建订单`});await p(getComputedStyle(r).backgroundColor).toBe(getComputedStyle(i).borderTopColor);let a=e=>e.top+e.height/2;await p(Math.abs(a(r.getBoundingClientRect())-a(n.getBoundingClientRect()))).toBeLessThanOrEqual(1)}}),E=T(`light`),D=T(`dark`),O=[`NarrowColumn`,`CollapsedPathIsTwoLevels`,`TitleBarDividerInLightTheme`,`TitleBarDividerInDarkTheme`],S.parameters={...S.parameters,docs:{...S.parameters?.docs,source:{originalSource:`{
  ...DisplayNarrowTitleBar,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    const host = canvasElement.querySelector<HTMLElement>('[data-narrow-host]')!;
    const title = canvasElement.querySelector<HTMLElement>('[data-slot="view-title"]')!;

    // As saved, with the list folded away: a phone, a side panel, a split
    // pane. 300px of column is below every phone there is.
    await expect(walk(host, 760, 300)).toEqual([]);

    // The switcher is what gives here, because with the list folded away it
    // is the switcher that carries the view's name.
    const switcher = canvasElement.querySelector<HTMLElement>('[data-slot="view-switcher"]')!;
    await expect(widthAt(host, 760, switcher), 'switcher shrinks').toBeGreaterThan(widthAt(host, 320, switcher));

    // And it is a button, not the room around it: \`grow\` used to hand it the
    // whole collapsed group, which at 470px was a pill with a short name
    // centred in it. Sized to what it says, the label starts where the icon
    // ends and the group ends where the label does.
    host.style.width = '760px';
    await expect(getComputedStyle(switcher).justifyContent).toBe('flex-start');
    const label = switcher.querySelector<HTMLElement>('span')!;
    await expect(box(switcher).right - box(label).right, 'trigger ends at its label').toBeLessThan(40);

    // The definition's title is measured against this bar, not the page it is
    // on: the window here is far wider than the old viewport \`sm:\`, and the
    // 360px column is what decides.
    const definition = canvasElement.querySelector<HTMLElement>('[data-slot="definition-title"]')!;
    host.style.width = '360px';
    await expect(getComputedStyle(definition).display).toBe('none');
    host.style.width = '760px';
    await expect(getComputedStyle(definition).display).not.toBe('none');

    // Edited, which is the state a narrow screen most needs the bar in: Save
    // is a live command rather than a disabled one, and the "Edited" badge
    // has joined the line, so the same walk is over wider contents — four
    // pixels' worth, which is why this rung stops at 364 and the one above
    // reaches 300. The badge is a word with no icon to fall back to, so it
    // is one of the irreducible things the bar would rather overflow than
    // clip.
    await userEvent.click(canvas.getAllByRole('button', {
      name: /订单号/
    })[0]);
    await waitFor(() => expect(canvas.getByRole('button', {
      name: zhCN['label.save.save']
    })).toBeEnabled());
    await expect(walk(host, 760, 364)).toEqual([]);

    // And the same bar with the view list beside it, which is the third set
    // of contents: the heading is on the line, and the switcher is not. The
    // list takes 224px of the column before the bar sees any of it.
    await userEvent.click(canvas.getByRole('button', {
      name: zhCN['label.workbench.expand-sidebar']
    }));
    await waitFor(() => expect(canvasElement.querySelector('[data-slot="view-sidebar"]')).not.toBeNull());
    await expect(walk(host, 1000, 600)).toEqual([]);

    // The name is what gives here too, and it is *clipped* rather than laid
    // out at its full width: the group is sized to its contents, so a title
    // that went on asking for its whole string would push the save commands
    // out of the column instead of truncating. A \`max-w-fit\` on the heading
    // could not have delivered this half — it caps growth, it does not stop
    // a nowrap heading asking for its whole text.
    // Two widths on which the bar is still one line: with the freshness
    // control in the title bar (D12) the controls wrap to a line of their
    // own below ~700px here, and on that line the name has the whole width
    // back — so the comparison is made above the wrap, where the name is
    // the one thing giving way.
    await expect(widthAt(host, 1000, title), 'title shrinks').toBeGreaterThan(widthAt(host, 900, title));
    await expect(title.scrollWidth, 'title clipped').toBeGreaterThan(title.clientWidth);
    host.style.width = '375px';
  }
}`,...S.parameters?.docs?.source},description:{story:`The bar in a column that keeps getting narrower, with a name too long for
any of those widths.

On \`main\` the identity group carried \`min-w-0\`, which let it be squeezed
below what its own contents need — 96.8px of box around 206.9px of
content — so the row above it went on believing everything fitted and
never wrapped, and the save commands were painted over the view controls.
At a phone's width every point of Save answered "filter toggle": the one
command a narrow screen needs was reachable by keyboard and by nothing
else.

Three sets of contents share that one line at different times, and each is
walked on its own: the view as it was saved, the same view once it has
been edited (which adds a badge and turns Save into a live command), and
the bar with the view list beside it rather than folded into a switcher.

Each walk stops where the bar's own irreducible contents stop fitting the
column — icons, badges and the save commands, none of which can be made
smaller, since a truncated "Shared" is worse than an honest overflow.
Below that the bar is wider than the column it is in and is clipped there;
what it must never do, and what the walk is for, is fit by painting one of
its own groups over another.

The walk runs on the Chinese catalogue like every other story here: its
widths are the fixture, and the Chinese set once reached a 520–528px window
where the row kept both groups on one line and squeezed the identity
group — closed when that group took an \`auto\` flex basis (D12 shell PR),
so the bar wraps instead of squeezing.`,...S.parameters?.docs?.description}}},w.parameters={...w.parameters,docs:{...w.parameters?.docs,source:{originalSource:`{
  ...DisplayCollapsedSidebar,
  play: async ({
    canvasElement
  }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    const definition = canvasElement.querySelector<HTMLElement>('[data-slot="definition-title"]')!;
    const label = canvasElement.querySelector<HTMLElement>('[data-slot="view-switcher"] span')!;
    await expect(typeOf(definition), '定义名').toBe('16/600');
    await expect(typeOf(label), '切换器里的视图名').toBe('14/500');
    // 一级之差，不是半级。
    await expect(parseFloat(getComputedStyle(definition).fontSize) - parseFloat(getComputedStyle(label).fontSize)).toBe(2);

    // 列表叫回来之后是同一对数——收起只是把 \`h1\` 挪了个地方。
    await userEvent.click(canvas.getByRole('button', {
      name: zhCN['label.workbench.expand-sidebar']
    }));
    await waitFor(() => expect(canvasElement.querySelector('[data-slot="view-list-title"]')).not.toBeNull());
    await expect(typeOf(canvasElement.querySelector<HTMLElement>('[data-slot="view-list-title"]')!), '侧栏标题').toBe('16/600');
  }
}`,...w.parameters?.docs?.source},description:{story:"侧栏收起之后，路径仍是两级标题，而不是一级半。\n\n收起时定义名从侧栏标题搬到标题栏的 `leading` 槽里，它**换了地方而不是降了\n级**：两边都是这一屏的 `h1`。此前搬过去就成了 14/600，而切换器里的视图名是\n13/500（registry `sm` 按钮的 `text-[0.8rem]`，被主题钉到 13px 那一档——侧栏\n行与分组标签的那一档），于是「订单 / 待出库订单」两半只差了一级的一半，\n这一屏的名字反而比它装着的那个视图名还小。现在是 16/600 与 14/500：正是列表\n展开时 `view-list-title` 与 `h2` 的那一对，也仍然落在 13/14/16 三档上。\n\n量的是层叠之后的值而不是类名：切换器的字号来自 vendored 的 `Button`，`cn`\n把调用处的 `text-sm` 与它自带的 `text-[0.8rem]` 合并掉，`styles.css` 里把\n那个 utility 钉到 13px 的规则因此不再匹配这颗按钮——这一串只有真浏览器算\n得出来。",...w.parameters?.docs?.description}}},E.parameters={...E.parameters,docs:{...E.parameters?.docs,source:{originalSource:`titleBarDivider('light')`,...E.parameters?.docs?.source}}},D.parameters={...D.parameters,docs:{...D.parameters?.docs,source:{originalSource:`titleBarDivider('dark')`,...D.parameters?.docs?.source}}}})))()}k();export{w as CollapsedPathIsTwoLevels,S as NarrowColumn,D as TitleBarDividerInDarkTheme,E as TitleBarDividerInLightTheme,O as __namedExportsOrder,_ as default};
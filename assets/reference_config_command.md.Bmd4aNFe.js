import{_ as s,c as e,ag as i,o as a}from"./chunks/framework.wRLzwZz-.js";const k=JSON.parse('{"title":"命令总线","description":"","frontmatter":{},"headers":[],"relativePath":"reference/config/command.md","filePath":"reference/config/command.md","lastUpdated":1737762762000}'),n={name:"reference/config/command.md"};function d(o,t,l,r,h,p){return a(),e("div",null,t[0]||(t[0]=[i(`<h1 id="命令总线" tabindex="-1">命令总线 <a class="header-anchor" href="#命令总线" aria-label="Permalink to &quot;命令总线&quot;">​</a></h1><ul><li>配置类：<a href="https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/command/CommandProperties.kt" target="_blank" rel="noreferrer">CommandProperties</a></li><li>前缀：<code>wow.command.</code></li></ul><table tabindex="0"><thead><tr><th>名称</th><th>数据类型</th><th>说明</th><th>默认值</th></tr></thead><tbody><tr><td><code>bus</code></td><td><code>BusProperties</code></td><td><a href="./basic.html#busproperties">BusProperties</a></td><td></td></tr><tr><td><code>idempotency</code></td><td><code>IdempotencyProperties</code></td><td>命令幂等性</td><td></td></tr></tbody></table><p><strong>YAML 配置样例</strong></p><div class="language-yaml vp-adaptive-theme"><button title="Copy Code" class="copy"></button><span class="lang">yaml</span><pre class="shiki shiki-themes github-light github-dark vp-code" tabindex="0"><code><span class="line"><span style="--shiki-light:#22863A;--shiki-dark:#85E89D;">wow</span><span style="--shiki-light:#24292E;--shiki-dark:#E1E4E8;">:</span></span>
<span class="line"><span style="--shiki-light:#22863A;--shiki-dark:#85E89D;">  command</span><span style="--shiki-light:#24292E;--shiki-dark:#E1E4E8;">:</span></span>
<span class="line"><span style="--shiki-light:#22863A;--shiki-dark:#85E89D;">    bus</span><span style="--shiki-light:#24292E;--shiki-dark:#E1E4E8;">:</span></span>
<span class="line"><span style="--shiki-light:#22863A;--shiki-dark:#85E89D;">      type</span><span style="--shiki-light:#24292E;--shiki-dark:#E1E4E8;">: </span><span style="--shiki-light:#032F62;--shiki-dark:#9ECBFF;">kafka</span></span>
<span class="line"><span style="--shiki-light:#22863A;--shiki-dark:#85E89D;">      local-first</span><span style="--shiki-light:#24292E;--shiki-dark:#E1E4E8;">:</span></span>
<span class="line"><span style="--shiki-light:#22863A;--shiki-dark:#85E89D;">        enabled</span><span style="--shiki-light:#24292E;--shiki-dark:#E1E4E8;">: </span><span style="--shiki-light:#005CC5;--shiki-dark:#79B8FF;">true</span></span>
<span class="line"><span style="--shiki-light:#22863A;--shiki-dark:#85E89D;">    idempotency</span><span style="--shiki-light:#24292E;--shiki-dark:#E1E4E8;">:</span></span>
<span class="line"><span style="--shiki-light:#22863A;--shiki-dark:#85E89D;">      enabled</span><span style="--shiki-light:#24292E;--shiki-dark:#E1E4E8;">: </span><span style="--shiki-light:#005CC5;--shiki-dark:#79B8FF;">true</span></span>
<span class="line"><span style="--shiki-light:#22863A;--shiki-dark:#85E89D;">      bloom-filter</span><span style="--shiki-light:#24292E;--shiki-dark:#E1E4E8;">:</span></span>
<span class="line"><span style="--shiki-light:#22863A;--shiki-dark:#85E89D;">        expected-insertions</span><span style="--shiki-light:#24292E;--shiki-dark:#E1E4E8;">: </span><span style="--shiki-light:#005CC5;--shiki-dark:#79B8FF;">1000000</span></span>
<span class="line"><span style="--shiki-light:#22863A;--shiki-dark:#85E89D;">        ttl</span><span style="--shiki-light:#24292E;--shiki-dark:#E1E4E8;">: </span><span style="--shiki-light:#032F62;--shiki-dark:#9ECBFF;">PT60S</span></span>
<span class="line"><span style="--shiki-light:#22863A;--shiki-dark:#85E89D;">        fpp</span><span style="--shiki-light:#24292E;--shiki-dark:#E1E4E8;">: </span><span style="--shiki-light:#005CC5;--shiki-dark:#79B8FF;">0.00001</span></span></code></pre></div><h2 id="idempotencyproperties" tabindex="-1">IdempotencyProperties <a class="header-anchor" href="#idempotencyproperties" aria-label="Permalink to &quot;IdempotencyProperties&quot;">​</a></h2><ul><li>配置类：<a href="https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/command/CommandProperties.kt" target="_blank" rel="noreferrer">IdempotencyProperties</a></li></ul><table tabindex="0"><thead><tr><th>名称</th><th>数据类型</th><th>说明</th><th>默认值</th></tr></thead><tbody><tr><td><code>enabled</code></td><td><code>boolean</code></td><td>是否启用</td><td><code>true</code></td></tr><tr><td><code>bloom-filter</code></td><td><code>BloomFilter</code></td><td>BloomFilter</td><td></td></tr></tbody></table><h3 id="bloomfilter" tabindex="-1">BloomFilter <a class="header-anchor" href="#bloomfilter" aria-label="Permalink to &quot;BloomFilter&quot;">​</a></h3><table tabindex="0"><thead><tr><th>名称</th><th>数据类型</th><th>说明</th><th>默认值</th></tr></thead><tbody><tr><td><code>ttl</code></td><td><code>Duration</code></td><td>存活时间</td><td><code>Duration.ofMinutes(1)</code></td></tr><tr><td><code>expected-insertions</code></td><td><code>Long</code></td><td></td><td><code>1000_000</code></td></tr><tr><td><code>fpp</code></td><td><code>Double</code></td><td></td><td><code>0.00001</code></td></tr></tbody></table>`,10)]))}const E=s(n,[["render",d]]);export{k as __pageData,E as default};
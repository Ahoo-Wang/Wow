/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import type { ReactNode } from 'react';
import {
  ArrowRightIcon,
  InboxIcon,
  LayoutDashboardIcon,
  PanelsTopLeftIcon,
  RefreshCcwIcon,
  SigmaIcon,
} from 'lucide-react';

/*
 * The pieces of the guided intro (`Intro.mdx`, docs/scenarios.md 5.2).
 * They hold no links of their own: every link is written in the MDX, where
 * `scripts/verify-storybook.mjs` finds it and checks it against the index.
 */

function Step({ verb }: { verb: string }) {
  return (
    <li className="story-intro-step" aria-hidden>
      <ArrowRightIcon />
      {verb}
    </li>
  );
}

/**
 * The value chain of the design README: a definition (code) and a config
 * (data) compile to a Wow query, whose result is projected into what the
 * reader sees; what the reader changes is saved as config and opened again.
 */
export function ValueChain() {
  return (
    <figure className="story-intro" style={{ margin: '16px 0 0' }}>
      <ol
        className="story-intro-chain"
        aria-label="定义与配置编译成 Wow 查询，执行得到结果，投影成呈现"
      >
        <li className="story-intro-inputs">
          <div className="story-intro-node">
            <strong>定义（代码）</strong>
            <span>研发声明能怎样观察：字段、操作符、能分组与聚合的字段</span>
          </div>
          <div className="story-intro-node" data-accent>
            <strong>配置（数据）</strong>
            <span>用户决定这次怎样观察：筛选、列、分组、指标、图型、面板</span>
          </div>
        </li>
        <Step verb="编译" />
        <li className="story-intro-node">
          <strong>Wow 查询</strong>
          <span>分页、游标或聚合，由查询服务执行</span>
        </li>
        <Step verb="执行" />
        <li className="story-intro-node">
          <strong>结果</strong>
          <span>一行行记录，或一组组分组与指标</span>
        </li>
        <Step verb="投影" />
        <li className="story-intro-node">
          <strong>呈现</strong>
          <span>表格、卡片、11 种图、指标卡、仪表盘</span>
        </li>
      </ol>
      <p className="story-intro-loop">
        <RefreshCcwIcon aria-hidden />
        <strong>保存 · 打开</strong>
        呈现里改过的观察方式存回配置——个人的、共享的或系统的视图；下次按同一份配置打开，还是这个样子。
      </p>
    </figure>
  );
}

const KIND_ICONS = {
  record: InboxIcon,
  analysis: SigmaIcon,
  dashboard: LayoutDashboardIcon,
  embed: PanelsTopLeftIcon,
} as const;

/** One of the four ways to look at data, and where it is best seen. */
export function KindCard({
  kind,
  title,
  children,
}: {
  kind: keyof typeof KIND_ICONS;
  title: string;
  /** The sentence, then the link to the scene that shows it best. */
  children: ReactNode;
}) {
  const Icon = KIND_ICONS[kind];
  return (
    <li className="story-intro-card">
      <h3>
        <Icon aria-hidden />
        {title}
      </h3>
      {children}
    </li>
  );
}

/** An answer the reader opens after looking for it themselves. */
export function Answer({ children }: { children: ReactNode }) {
  return (
    <details className="story-intro story-intro-answer">
      <summary>答案</summary>
      {children}
    </details>
  );
}

/** The four cards, side by side where there is room. */
export function KindCards({ children }: { children: ReactNode }) {
  return (
    <ul className="story-intro story-intro-cards" aria-label="四种观察方式">
      {children}
    </ul>
  );
}

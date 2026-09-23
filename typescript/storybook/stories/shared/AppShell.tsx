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
import { useId, useState, type ReactNode } from 'react';
import {
  ActivityIcon,
  BellIcon,
  CircleHelpIcon,
  ClipboardListIcon,
  PanelLeftCloseIcon,
  PanelLeftOpenIcon,
  ServerIcon,
  UserIcon,
} from 'lucide-react';
import { IconButton } from '@/ui/IconButton';
import { ToneBadge } from '@/ui/variants';

/** The pages of this host, one per real-backend scene. */
export type ConsolePage = 'snapshots' | 'event-streams';

const PAGES: readonly {
  page: ConsolePage;
  title: string;
  story: string;
  icon: typeof ActivityIcon;
}[] = [
  {
    page: 'snapshots',
    title: '快照控制台',
    story: 'view-engine-真实后端-补偿控制台-快照控制台--data-console',
    icon: ClipboardListIcon,
  },
  {
    page: 'event-streams',
    title: '事件流分析台',
    story: 'view-engine-真实后端-补偿控制台-事件流分析台--event-stream-console',
    icon: ActivityIcon,
  },
];

/**
 * The application a console lives in: a top bar and a navigation column
 * that belong to the host, around the one block View Engine draws.
 *
 * A console is never the whole screen in a real product. It sits under the
 * host's own bar — product, environment, the signed-in operator — and
 * beside the host's own navigation, and the workbench's view list is a
 * second column next to that one. Judging the workbench's look without them
 * judges it against a blank page, which is not where anyone will see it.
 * So the scene draws them, and draws them as a host would: in the host's
 * markup, reading the theme's tokens through `fve-tokens` (D17-10) rather
 * than being a second View Engine surface.
 *
 * Nothing here pretends: the navigation holds the two real scenes, linked to
 * each other, and the service line is the `host` the scene talks to. The
 * column folds to its icons, as a host's would. What sizes the workbench is
 * unchanged — the table measures the room left to the viewport's bottom,
 * wherever the bar has put its top.
 */
export function AppShell({
  current,
  host,
  children,
}: {
  current: ConsolePage;
  host: string;
  children: ReactNode;
}) {
  const [folded, setFolded] = useState(false);
  const navId = useId();
  return (
    <div className="fve-tokens story-app" data-folded={folded || undefined}>
      <header className="story-app-bar">
        <span className="story-app-logo" aria-hidden>
          W
        </span>
        <span className="story-app-product">Wow</span>
        <span className="story-app-divider" aria-hidden />
        <span className="story-app-system">补偿运维中心</span>
        <ToneBadge tone="warning">测试环境</ToneBadge>
        <span className="story-app-spacer" />
        <IconButton label="通知" variant="ghost" size="icon-sm">
          <BellIcon />
        </IconButton>
        <IconButton label="帮助" variant="ghost" size="icon-sm">
          <CircleHelpIcon />
        </IconButton>
        <span className="story-app-user">
          <span className="story-app-avatar" aria-hidden>
            <UserIcon />
          </span>
          运维值班
        </span>
      </header>

      <nav id={navId} className="story-app-nav" aria-label="应用导航">
        <p className="story-app-group">补偿</p>
        {PAGES.map(({ page, title, story, icon: Icon }) => (
          <a
            key={page}
            className="story-app-item"
            // The whole Storybook moves to the other scene, as a host's
            // navigation moves the whole page.
            href={`/?path=/story/${story}`}
            target="_top"
            aria-current={page === current ? 'page' : undefined}
            title={folded ? title : undefined}
          >
            <Icon aria-hidden />
            <span className="story-app-label">{title}</span>
          </a>
        ))}

        <p className="story-app-group">服务</p>
        <p className="story-app-service" title={host}>
          <ServerIcon aria-hidden />
          <span className="story-app-label">{host}</span>
        </p>

        <span className="story-app-spacer" />
        <button
          type="button"
          className="story-app-item story-app-fold"
          aria-controls={navId}
          aria-expanded={!folded}
          aria-label={folded ? '展开导航' : '收起导航'}
          onClick={() => setFolded(value => !value)}
        >
          {folded ? (
            <PanelLeftOpenIcon aria-hidden />
          ) : (
            <PanelLeftCloseIcon aria-hidden />
          )}
          <span className="story-app-label">收起导航</span>
        </button>
      </nav>

      {/* Not a `main`: the workbench draws its own, and a page has one. */}
      <div className="story-app-page">{children}</div>
    </div>
  );
}

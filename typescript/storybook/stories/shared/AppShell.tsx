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
  DatabaseIcon,
  FunnelIcon,
  HouseIcon,
  InboxIcon,
  LayoutDashboardIcon,
  PaletteIcon,
  PanelLeftCloseIcon,
  PanelLeftOpenIcon,
  PanelsTopLeftIcon,
  ServerIcon,
  SigmaIcon,
  UserIcon,
} from 'lucide-react';
import { IconButton } from '@/ui/IconButton';
import { ToneBadge } from '@/ui/variants';

/** The pages of this host, one per View Engine scene. */
export type ScenePage =
  | 'home'
  // 业务场景
  | 'ops-daily'
  | 'sales-review'
  | 'fulfilment'
  | 'retail-orders'
  | 'retail-after-sales'
  | 'retail-analysis'
  | 'retail-waybills'
  | 'retail-order-events'
  | 'member-detail'
  | 'order-detail'
  // 能力
  | 'display'
  | 'references'
  | 'time-axis'
  | 'brush'
  | 'board-search'
  // Not in the navigation yet: a nav entry redraws the three key screens'
  // baselines, which the preset removal (#3490) is re-taking; it is added
  // with the next baseline update.
  | 'narrowing'
  | 'presets'
  // 组件状态
  | 'records'
  | 'analysis'
  | 'dashboard'
  | 'embedded'
  | 'embedded-dashboard'
  | 'filters'
  // 真实后端
  | 'compensation-overview'
  | 'snapshots'
  | 'event-streams'
  | 'customer-snapshots'
  | 'customer-event-streams'
  | 'trade-order-snapshots'
  | 'trade-order-event-streams'
  | 'pricing-snapshots'
  | 'pricing-event-streams';

interface NavItem {
  page: ScenePage;
  title: string;
  /** The scene's first story, which is where its link lands. */
  story: string;
  icon: typeof ActivityIcon;
}

/**
 * The navigation, grouped the way the catalog is (docs/scenarios.md 5.1):
 * the business scenes, one page per capability, the components' states,
 * then the real services. The kinds wear the icons the workbench gives them
 * (`ui/kinds.ts`), so a Record page in this column and a Record view in the
 * workbench's own list read as one thing.
 *
 * The home page comes first and on its own, as a host's does: it is where
 * the application opens, not one scene among a group of them, so it has no
 * group heading over it. The catalog's guided intro (导览) is a document,
 * not a page of this host, so it has no entry here.
 */
const GROUPS: readonly { title?: string; items: readonly NavItem[] }[] = [
  {
    items: [
      {
        page: 'home',
        title: '首页',
        story: 'view-engine-首页--daily-report',
        icon: HouseIcon,
      },
    ],
  },
  // 栖木生活的零售数据集上的业务场景（docs/scenarios.md 3、4 节）：三块板、
  // 五个工作台与两张嵌入页，按目录的次序。
  {
    title: '业务场景',
    items: [
      {
        page: 'ops-daily',
        title: '运营日报',
        story: 'view-engine-业务场景-运营日报--daily-report',
        icon: LayoutDashboardIcon,
      },
      {
        page: 'sales-review',
        title: '销售复盘',
        story: 'view-engine-业务场景-销售复盘--overview',
        icon: LayoutDashboardIcon,
      },
      {
        page: 'fulfilment',
        title: '履约与售后',
        story: 'view-engine-业务场景-履约与售后--fulfilment-tab',
        icon: LayoutDashboardIcon,
      },
      {
        page: 'retail-orders',
        title: '订单工作台',
        story: 'view-engine-业务场景-订单工作台--order-workbench-scene',
        icon: InboxIcon,
      },
      {
        page: 'retail-after-sales',
        title: '售后工作台',
        story: 'view-engine-业务场景-售后工作台--after-sale-workbench',
        icon: InboxIcon,
      },
      {
        page: 'retail-analysis',
        title: '分析工作台',
        story: 'view-engine-业务场景-分析工作台--order-analysis',
        icon: SigmaIcon,
      },
      {
        page: 'retail-waybills',
        title: '运单宽表',
        story: 'view-engine-业务场景-运单宽表--waybill-wide-table',
        icon: InboxIcon,
      },
      {
        page: 'retail-order-events',
        title: '订单事件流',
        story: 'view-engine-业务场景-订单事件流--order-event-stream',
        icon: ActivityIcon,
      },
      {
        page: 'member-detail',
        title: '会员详情页',
        story: 'view-engine-业务场景-会员详情页--member-detail-page',
        icon: PanelsTopLeftIcon,
      },
      {
        page: 'order-detail',
        title: '订单详情页',
        story: 'view-engine-业务场景-订单详情页--order-detail-page',
        icon: PanelsTopLeftIcon,
      },
    ],
  },
  // One page per capability: the smallest scene that shows it.
  {
    title: '能力',
    items: [
      {
        page: 'display',
        title: '显示收口',
        story: 'view-engine-能力-显示收口--log-scale',
        icon: SigmaIcon,
      },
      {
        page: 'references',
        title: '参考与算出的系列',
        story: 'view-engine-能力-参考与算出的系列--average-and-target',
        icon: SigmaIcon,
      },
      {
        page: 'time-axis',
        title: '长时间轴',
        story: 'view-engine-能力-长时间轴--daily-gmv',
        icon: SigmaIcon,
      },
      {
        page: 'brush',
        title: '框选与追问',
        story: 'view-engine-能力-框选与追问--in-the-workbench',
        icon: SigmaIcon,
      },
      {
        page: 'board-search',
        title: '板上的搜索',
        story: 'view-engine-能力-板上的搜索--build-a-search',
        icon: LayoutDashboardIcon,
      },
      {
        page: 'presets',
        title: '主题与预设',
        story: 'view-engine-能力-主题与预设-逐套预设--neutral',
        icon: PaletteIcon,
      },
    ],
  },
  // Each component in its states — empty, loading, failed, in conflict,
  // narrow — on the small hand-picked fixtures its regressions assert.
  {
    title: '组件状态',
    items: [
      {
        page: 'records',
        title: '记录工作台',
        story: 'view-engine-组件状态-记录工作台--with-data',
        icon: InboxIcon,
      },
      {
        page: 'analysis',
        title: '分析工作台',
        story: 'view-engine-组件状态-分析工作台--bar-chart',
        icon: SigmaIcon,
      },
      {
        page: 'dashboard',
        title: '仪表盘',
        story: 'view-engine-组件状态-仪表盘--all-panels',
        icon: LayoutDashboardIcon,
      },
      {
        page: 'embedded',
        title: '嵌入视图',
        story: 'view-engine-组件状态-embeddedview--default',
        icon: PanelsTopLeftIcon,
      },
      {
        page: 'embedded-dashboard',
        title: '嵌入仪表盘',
        story: 'view-engine-组件状态-embeddeddashboard--customer-detail',
        icon: PanelsTopLeftIcon,
      },
      {
        page: 'filters',
        title: '筛选编辑器',
        story: 'view-engine-组件状态-筛选编辑器--advanced',
        icon: FunnelIcon,
      },
    ],
  },
  // The real services, one group per service as the catalog has one
  // directory per service; each has the same two consoles.
  {
    title: '真实后端 · 补偿',
    items: [
      {
        page: 'compensation-overview',
        title: '运营概览',
        story: 'view-engine-真实后端-补偿控制台-运营概览--fixture',
        icon: LayoutDashboardIcon,
      },
      {
        page: 'snapshots',
        title: '快照控制台',
        story: 'view-engine-真实后端-补偿控制台-快照控制台--data-console',
        icon: ClipboardListIcon,
      },
      {
        page: 'event-streams',
        title: '事件流分析台',
        story:
          'view-engine-真实后端-补偿控制台-事件流分析台--event-stream-console',
        icon: ActivityIcon,
      },
    ],
  },
  {
    title: '真实后端 · 客户',
    items: [
      {
        page: 'customer-snapshots',
        title: '快照控制台',
        story: 'view-engine-真实后端-客户-快照控制台--data-console',
        icon: ClipboardListIcon,
      },
      {
        page: 'customer-event-streams',
        title: '事件流分析台',
        story: 'view-engine-真实后端-客户-事件流分析台--event-stream-console',
        icon: ActivityIcon,
      },
    ],
  },
  {
    title: '真实后端 · 交易订单',
    items: [
      {
        page: 'trade-order-snapshots',
        title: '快照控制台',
        story: 'view-engine-真实后端-交易订单-快照控制台--snapshot-console',
        icon: ClipboardListIcon,
      },
      {
        page: 'trade-order-event-streams',
        title: '事件流分析台',
        story:
          'view-engine-真实后端-交易订单-事件流分析台--event-stream-console',
        icon: ActivityIcon,
      },
    ],
  },
  {
    title: '真实后端 · 商品定价',
    items: [
      {
        page: 'pricing-snapshots',
        title: '快照控制台',
        story: 'view-engine-真实后端-商品定价-快照控制台--snapshot-console',
        icon: ClipboardListIcon,
      },
      {
        page: 'pricing-event-streams',
        title: '事件流分析台',
        story:
          'view-engine-真实后端-商品定价-事件流分析台--event-stream-console',
        icon: ActivityIcon,
      },
    ],
  },
];

/**
 * What a scene talks to: a real service at a host, or the fixture the scene
 * answers from in memory. The shell says which, and says it plainly.
 */
export type SceneService = { host: string } | { fixture: string };

/**
 * The application a View Engine scene lives in: a top bar and a navigation
 * column that belong to the host, around the one block View Engine draws.
 *
 * A workbench is never the whole screen in a real product. It sits under the
 * host's own bar — product, environment, the signed-in operator — and
 * beside the host's own navigation, and the workbench's view list is a
 * second column next to that one. Judging the workbench's look without them
 * judges it against a blank page, which is not where anyone will see it. So
 * every View Engine scene draws them — the real-backend consoles and the
 * fixture scenes alike — and draws them as a host would: in the host's
 * markup, reading the theme's tokens through `fve-tokens` (D17-10) rather
 * than being a second View Engine surface.
 *
 * Nothing here pretends. The navigation holds the scenes the catalog has —
 * the home page first, then the rest grouped as the catalog groups them —
 * each linked to its first story; the
 * environment badge and the service line say what the scene really talks
 * to — the `host` of a real service, or the fixture a scene answers from in
 * memory, in the scene's own words. The column folds to its icons, as a
 * host's would.
 *
 * The page area has a definite height, as a host's content region does, so a
 * workbench placed in it fills it and keeps its footer at the bottom, as a
 * workbench does in any container; anything taller than the region scrolls inside it, and
 * the bar never scrolls away. A scene that is not a workbench on its own — a
 * host page with a view embedded in it, a component a host places in its own
 * layout, the home page with its dashboard — asks for `padded`, the gutter
 * a host's content region gives its pages. A page laid out in cards — the
 * home page, a board embedded on its own — also asks for `grouped`: it
 * stands on View Engine's `canvas`, the ground a theme gives card layouts
 * (a grey page under white cards in `porcelain`, the page itself in the
 * rest), so the host's page and the board on it are one ground.
 */
export function AppShell({
  current,
  service,
  padded = false,
  grouped = false,
  children,
}: {
  current: ScenePage;
  service: SceneService;
  padded?: boolean;
  grouped?: boolean;
  children: ReactNode;
}) {
  const [folded, setFolded] = useState(false);
  const navId = useId();
  const live = 'host' in service;
  const serviceLabel = live ? service.host : service.fixture;
  const ServiceIcon = live ? ServerIcon : DatabaseIcon;
  return (
    <div className="fve-tokens story-app" data-folded={folded || undefined}>
      <header className="story-app-bar">
        <span className="story-app-logo" aria-hidden>
          W
        </span>
        <span className="story-app-product">Wow</span>
        <span className="story-app-divider" aria-hidden />
        <span className="story-app-system">运营中心</span>
        {/* What the data is: a test service's, or a fixture's that never
            left the browser. */}
        {live ? (
          <ToneBadge tone="warning">测试环境</ToneBadge>
        ) : (
          <ToneBadge tone="neutral">示例数据</ToneBadge>
        )}
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
          运营值班
        </span>
      </header>

      <nav id={navId} className="story-app-nav" aria-label="应用导航">
        {GROUPS.map(group => (
          <div key={group.title ?? ''} className="story-app-section">
            {group.title && <p className="story-app-group">{group.title}</p>}
            {group.items.map(({ page, title, story, icon: Icon }) => (
              <a
                key={page}
                className="story-app-item"
                // The whole Storybook moves to the other scene, as a host's
                // navigation moves the whole page. Relative to the page, not
                // the site root: this anchor lives in `iframe.html`, whose
                // directory is the Storybook root wherever it is served — `/`
                // locally, `/storybook/` on GitHub Pages — where `/?path=`
                // would leave the published Storybook for the site's root.
                href={`./?path=/story/${story}`}
                target="_top"
                aria-current={page === current ? 'page' : undefined}
                title={folded ? title : undefined}
              >
                <Icon aria-hidden />
                <span className="story-app-label">{title}</span>
              </a>
            ))}
          </div>
        ))}

        <div className="story-app-section">
          <p className="story-app-group">服务</p>
          <p
            className="story-app-service"
            data-fixture={live ? undefined : ''}
            title={serviceLabel}
          >
            <ServiceIcon aria-hidden />
            <span className="story-app-label">{serviceLabel}</span>
          </p>
        </div>

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
      <div
        className="story-app-page"
        data-padded={padded || undefined}
        data-grouped={grouped || undefined}
      >
        {children}
      </div>
    </div>
  );
}

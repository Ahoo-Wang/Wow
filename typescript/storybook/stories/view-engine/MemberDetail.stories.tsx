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
import { useMemo, type CSSProperties } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import type { DashboardFilters } from '@ahoo-wang/wow-view-engine';
import { EmbeddedDashboard } from '@ahoo-wang/wow-view-engine/ui';
import { Badge } from '@/ui/components/badge';
import {
  Card,
  CardAction,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/ui/components/card';
import { Separator } from '@/ui/components/separator';
import { AppShell } from '../shared/AppShell.js';
import { HOST_LANGUAGE } from './fixtures.js';
import { StoryEngine } from './StoryEngine.js';
import {
  CHANNEL_OPTIONS,
  LEVEL_OPTIONS,
  RETAIL_ENVIRONMENT,
  createRetailEngine,
  retailData,
} from './retail/boardDefinitions.js';
import { MEMBER_BOARD, retailInstances } from './retail/boards.js';
import { RoutedBoard, useNudges } from './retail/RetailHost.js';
import '@ahoo-wang/wow-view-engine/styles.css';

/** The member the page opens on: the shop's largest buyer this year. */
const MEMBER_ID = 'M101795';

/** An embed on a card paints the card's colour (see `EmbeddedView` stories). */
const ON_CARD = {
  '--fve-background': 'var(--card)',
  '--fve-dark-background': 'var(--card)',
} as CSSProperties;

const labelOf = (
  options: { value: unknown; label: string }[],
  value: unknown,
) => options.find(option => option.value === value)?.label ?? String(value);

const money = new Intl.NumberFormat(HOST_LANGUAGE.locale, {
  style: 'currency',
  currency: 'CNY',
});
const day = new Intl.DateTimeFormat(HOST_LANGUAGE.locale, {
  dateStyle: 'medium',
  timeZone: RETAIL_ENVIRONMENT.timeZone,
});

/**
 * 会员详情页（docs/scenarios.md 4.1「嵌入页」）：宿主画会员资料，右边嵌一块
 * 「会员概览」板——买家由页面锁定成这一位（读作他的名字、带锁、没有控件），
 * 下单时间归读者，默认今年。页面的锁定从第一次查询起就在，也不进宿主的地址。
 */
function MemberDetail({ memberId }: { memberId: string }) {
  const member = useMemo(
    () => retailData().members.find(({ state }) => state.id === memberId),
    [memberId],
  );
  const nudges = useNudges();
  if (!member) return <p>没有会员 {memberId}。</p>;
  const { state } = member;
  const name = `${state.nick}（${state.id}）`;
  // What the page holds: the member it is about, from its route.
  const page: DashboardFilters = {
    values: { buyer: { items: [{ id: state.id, label: name }] } },
  };
  const facts: [string, string][] = [
    ['会员号', state.id],
    ['注册渠道', labelOf(CHANNEL_OPTIONS, state.registerChannel)],
    ['注册时间', day.format(state.registeredAt)],
    ['所在城市', `${state.province} ${state.city}`],
    ['首单时间', state.firstOrderAt ? day.format(state.firstOrderAt) : '—'],
    ['累计已付款主单', `${state.orderCount} 单`],
    ['累计实付', money.format(state.totalPaid)],
  ];
  return (
    <div
      data-host-page
      className="fve-tokens bg-background text-foreground flex min-w-0 flex-col gap-4"
    >
      <div className="flex min-w-0 flex-col gap-0.5">
        <p className="text-muted-foreground text-xs">
          会员中心 / 会员 / {name}
        </p>
        <h1 className="truncate text-xl font-semibold">
          {state.nick} · 会员详情
        </h1>
      </div>
      <Separator />
      <div className="flex min-w-0 flex-col gap-4">
        <Card>
          <CardHeader>
            <CardTitle>会员资料</CardTitle>
            <CardAction>
              <Badge variant="secondary">
                {labelOf(LEVEL_OPTIONS, state.level)}
              </Badge>
            </CardAction>
          </CardHeader>
          {/* A row of facts over the board, so the board has the page's
              width: beside it, a 1280 screen left the board a phone's column. */}
          <CardContent>
            <dl className="grid grid-cols-[repeat(auto-fill,minmax(140px,1fr))] gap-x-6 gap-y-3">
              {facts.map(([label, value]) => (
                <div key={label} className="flex min-w-0 flex-col gap-0.5">
                  <dt className="text-muted-foreground text-xs">{label}</dt>
                  <dd className="text-sm tabular-nums">{value}</dd>
                </div>
              ))}
            </dl>
          </CardContent>
        </Card>
        <Card className="min-w-0" style={ON_CARD}>
          <CardHeader>
            <CardTitle>消费与售后</CardTitle>
            <CardDescription>
              这块板锁定在这位会员上；下单时间可以换。
            </CardDescription>
          </CardHeader>
          <CardContent className="flex min-w-0 flex-col gap-3">
            <StoryEngine
              create={() => createRetailEngine({ instances: retailInstances })}
            >
              {engine => (
                <RoutedBoard
                  engine={engine}
                  home={MEMBER_BOARD}
                  nudges={nudges}
                  board={reader => (
                    <EmbeddedDashboard
                      className="host-embed"
                      engine={engine}
                      instanceId={MEMBER_BOARD}
                      interaction="interactive"
                      withExport
                      expandable
                      filterModes={{ buyer: 'locked' }}
                      pageValues={page}
                      {...reader}
                      {...HOST_LANGUAGE}
                    />
                  )}
                />
              )}
            </StoryEngine>
          </CardContent>
        </Card>
      </div>
    </div>
  );
}

const FIXTURE = '内存 ViewStore · 栖木生活 2 万张子订单（示例数据）';

const description = `**业务场景 · 会员详情页**

宿主自己的会员详情页，嵌一块「会员概览」仪表盘（\`EmbeddedDashboard\`，可交互一档）。

- **数据源**：${FIXTURE}；时钟钉在 2026-09-22 上午 10 点。这一页是今年买得最多的黑卡会员。
- **筛选**：「买家」由页面锁定成这一位（\`filterModes: { buyer: 'locked' }\` + \`pageValues\`）——读作他的名字、带一把锁、没有控件，读者改不了，也不进宿主的地址；「下单时间」归读者，默认今年。
- **面板**：这段时间的实付与订单数、每月实付、他的订单（可导出）、他的售后。「⋯ → 在工作台中打开」进宿主的订单工作台，锁定的买家成了那边拿不掉的作用域。
- **注意**：锁定不是安全边界——条件是在浏览器里拼进查询的，租户、归属与权限必须由 Wow 后端强制。`;

const meta = {
  title: 'View Engine/业务场景/会员详情页',
  component: MemberDetail,
  parameters: {
    layout: 'fullscreen',
    docs: { description: { component: description } },
  },
  args: { memberId: MEMBER_ID },
  argTypes: {
    memberId: { control: 'text', description: '会员号，例如 M101164。' },
  },
  decorators: [
    Story => (
      <AppShell current="member-detail" service={{ fixture: FIXTURE }} padded>
        <Story />
      </AppShell>
    ),
  ],
} satisfies Meta<typeof MemberDetail>;

export default meta;

type Story = StoryObj<typeof meta>;

export const MemberDetailPage: Story = { name: '会员详情页' };

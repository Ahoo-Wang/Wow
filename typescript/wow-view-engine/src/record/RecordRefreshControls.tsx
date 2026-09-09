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

import { useEffect, useId, useState, type RefObject } from 'react';
import { ChevronDownIcon, RefreshCwIcon } from 'lucide-react';
import { Button } from '../components/ui/button.js';
import { ButtonGroup } from '../components/ui/button-group.js';
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from '../components/ui/tooltip.js';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuTrigger,
} from '../components/ui/dropdown-menu.js';
import type { ViewEngine } from './ViewEngine.js';
import type { RecordSession } from './recordModel.js';
import { getRecordRefreshBlockReason } from './recordRefreshPolicy.js';
import { cn } from '../lib/utils.js';

const INTERVALS = [
  { value: 0, label: '关闭自动刷新' },
  { value: 30000, label: '30 秒' },
  { value: 60000, label: '1 分钟' },
  { value: 300000, label: '5 分钟' },
];
const PAUSE_MESSAGES: Record<
  NonNullable<ReturnType<typeof getRecordRefreshBlockReason>>,
  string
> = {
  reload: '视图已变化，重新加载后恢复。',
  error: '查询失败，重试成功后恢复。',
  write: '正在保存视图，完成后恢复。',
  filter: '筛选尚未查询，查询或撤销修改后恢复。',
  selection: '已选择记录，取消选择后恢复。',
  query: '等待查询完成后恢复。',
  summary: '等待所有汇总完成后恢复。',
  cursor: '游标后续页暂停，点击查询返回第一页后恢复。',
  refresh: '正在刷新当前结果。',
};

export function RecordRefreshControls({
  engine,
  session,
  root,
  paused = false,
  onRefresh,
}: {
  engine: ViewEngine;
  session: RecordSession;
  root: RefObject<HTMLElement | null>;
  paused?: boolean;
  onRefresh(): void;
}) {
  const [interval, setInterval] = useState(0);
  const [remaining, setRemaining] = useState<number | null>(null);
  const [activityPause, setActivityPause] = useState<string | null>(null);
  const statusId = useId();
  const id = session.instance.id;
  const querying = session.queryStatus === 'loading' || session.refreshing;
  const reason = getRecordRefreshBlockReason(session);
  const pauseReason =
    reason === 'reload' || reason === 'error'
      ? PAUSE_MESSAGES[reason]
      : paused
        ? '业务操作进行中，完成后恢复。'
        : reason
          ? PAUSE_MESSAGES[reason]
          : null;
  const suspended = pauseReason !== null;
  useEffect(() => {
    const doc = root.current?.ownerDocument;
    if (!interval || !doc) return;
    let stopped = false;
    let inFlight = false;
    let deadline: number | null = null;
    let timer: ReturnType<typeof setTimeout> | undefined;
    async function tick() {
      if (stopped || inFlight) return;
      clearTimeout(timer);
      const editing = doc!.activeElement?.closest(
        'input, textarea, [contenteditable]:not([contenteditable="false"]), [role="dialog"], [role="alertdialog"], [role="menu"], [role="listbox"]',
      );
      const activity =
        doc!.visibilityState !== 'visible'
          ? '页面处于后台，返回页面后恢复。'
          : editing
            ? '正在编辑或使用弹层，完成后自动恢复。'
            : engine.getSnapshot().selectedInstanceId !== id
              ? '已切换视图。'
              : null;
      setActivityPause(activity);
      if (suspended || querying || activity) {
        deadline = null;
        setRemaining(null);
        return;
      }
      const now = Date.now();
      deadline ??= now + interval;
      const milliseconds = Math.max(0, deadline - now);
      setRemaining(Math.ceil(milliseconds / 1000));
      if (!milliseconds) {
        deadline = null;
        inFlight = true;
        await engine.refresh(id, { background: true }).catch(() => {});
        inFlight = false;
        if (!stopped) void tick();
      } else {
        timer = setTimeout(
          () => {
            void tick();
          },
          Math.min(1000, milliseconds),
        );
      }
    }
    doc.addEventListener('visibilitychange', tick);
    doc.addEventListener('focusin', tick);
    doc.addEventListener('focusout', tick);
    timer = setTimeout(() => {
      void tick();
    }, 0);
    return () => {
      stopped = true;
      clearTimeout(timer);
      doc.removeEventListener('visibilitychange', tick);
      doc.removeEventListener('focusin', tick);
      doc.removeEventListener('focusout', tick);
    };
  }, [engine, id, interval, suspended, querying, root]);
  const countdown =
    remaining === null
      ? null
      : `${String(Math.floor(remaining / 60)).padStart(2, '0')}:${String(remaining % 60).padStart(2, '0')}`;
  const hint = interval
    ? querying
      ? '正在刷新当前结果。'
      : (pauseReason ??
        activityPause ??
        '自动刷新已开启；点击可立即刷新当前结果。')
    : '刷新当前已查询条件下的结果。';
  return (
    <TooltipProvider>
      <Tooltip>
        <DropdownMenu>
          <ButtonGroup aria-label="刷新控制">
            <TooltipTrigger
              render={
                <Button variant="outline" size={interval ? 'sm' : 'icon-sm'} />
              }
              aria-label="刷新"
              aria-describedby={interval ? statusId : undefined}
              title={hint}
              aria-description={hint}
              onClick={onRefresh}
              disabled={querying}
            >
              <RefreshCwIcon
                aria-hidden="true"
                className={cn(
                  querying && 'fve:animate-spin fve:motion-reduce:animate-none',
                )}
              />
              {interval > 0 && (
                <span
                  id={statusId}
                  className="fve:tabular-nums"
                  aria-live="off"
                >
                  {INTERVALS.find(option => option.value === interval)?.label}
                  {' · '}
                  {querying
                    ? '刷新中'
                    : suspended || countdown === null
                      ? '已暂停'
                      : countdown}
                </span>
              )}
            </TooltipTrigger>
            <DropdownMenuTrigger
              render={
                <Button
                  variant="outline"
                  size="icon-sm"
                  aria-label="自动刷新设置"
                  title="自动刷新设置"
                />
              }
            >
              <ChevronDownIcon aria-hidden="true" />
            </DropdownMenuTrigger>
          </ButtonGroup>
          <DropdownMenuContent align="end" className="fve:min-w-40">
            <DropdownMenuRadioGroup
              value={String(interval)}
              onValueChange={value => {
                const next = Number(value);
                if (INTERVALS.some(option => option.value === next)) {
                  setInterval(next);
                  setRemaining(next / 1000);
                }
              }}
            >
              {INTERVALS.map(({ value, label }) => (
                <DropdownMenuRadioItem
                  key={value}
                  value={String(value)}
                  closeOnClick
                >
                  {value ? `每 ${label}` : label}
                </DropdownMenuRadioItem>
              ))}
            </DropdownMenuRadioGroup>
          </DropdownMenuContent>
        </DropdownMenu>
        <TooltipContent>{hint}</TooltipContent>
      </Tooltip>
    </TooltipProvider>
  );
}

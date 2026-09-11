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

import { useCallback, type RefObject } from 'react';
import type { ViewEngine } from '../engine/ViewEngine.js';
import type { RecordSession } from '../contracts/viewModel.js';
import { getRecordRefreshBlockReason } from './recordRefreshPolicy.js';
import { ViewRefreshControls } from '../view/ViewRefreshControls.js';
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
  const onAutoRefresh = useCallback(
    () => engine.record(id).refresh({ background: true }),
    [engine, id],
  );
  return (
    <ViewRefreshControls
      engine={engine}
      id={id}
      root={root}
      querying={querying}
      pauseReason={pauseReason}
      onRefresh={onRefresh}
      onAutoRefresh={onAutoRefresh}
    />
  );
}

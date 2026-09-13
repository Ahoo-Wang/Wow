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

import { useState } from 'react';
import { Button } from '../components/ui/button.js';
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogFooter,
} from '../components/ui/dialog.js';
import type {
  ViewSession,
  ViewInstanceConflict,
} from '../contracts/viewModel.js';
import type { ViewEngine } from '../engine/ViewEngine.js';
import { SaveAsForm } from './SaveAsForm.js';
import { useViewPermissions } from './useViewCapabilities.js';

export function ViewConflict({
  engine,
  session,
}: {
  engine: ViewEngine;
  session: ViewSession;
}) {
  const permissions = useViewPermissions(engine, session.instance.id);
  const [choice, setChoice] = useState<'remote' | 'overwrite' | 'copy' | null>(
    null,
  );
  const [review, setReview] = useState<ViewInstanceConflict>();
  const [error, setError] = useState<string | null>(null);
  if (!session.conflict) return null;
  const busy = session.writeStatus !== 'idle' || session.requiresReload;
  function open(choice: 'remote' | 'overwrite' | 'copy') {
    setReview(session.conflict);
    setError(null);
    setChoice(choice);
  }
  return (
    <Dialog
      open={choice !== null}
      onOpenChange={open => {
        if (!open) setChoice(null);
      }}
    >
      <section
        role="alert"
        aria-label="视图版本冲突"
        className="fve:rounded-lg fve:border fve:p-3 fve:space-y-2"
      >
        <p>远端视图已变更，本地编辑已保留。请选择如何处理。</p>
        <div className="fve:flex fve:flex-wrap fve:gap-2">
          <Button
            variant="outline"
            disabled={busy}
            onClick={() => open('remote')}
          >
            使用最新版本
          </Button>
          {(permissions.saveAsPersonal || permissions.saveAsShared) && (
            <Button
              variant="outline"
              disabled={busy || session.validation.length > 0}
              onClick={() => open('copy')}
            >
              另存我的配置
            </Button>
          )}
          {permissions.save && (
            <Button
              variant="outline"
              disabled={busy || session.validation.length > 0}
              onClick={() => open('overwrite')}
            >
              覆盖远端版本
            </Button>
          )}
        </div>
      </section>
      <DialogContent>
        {choice === 'copy' ? (
          <SaveAsForm
            engine={engine}
            session={session}
            onSaved={() => setChoice(null)}
          />
        ) : (
          <>
            <DialogHeader>
              <DialogTitle>
                {choice === 'remote' ? '确认使用最新版本' : '确认覆盖远端版本'}
              </DialogTitle>
              <DialogDescription>
                {choice === 'remote'
                  ? '本地未保存的编辑将被以下远端版本替换。'
                  : '将用以下本地完整配置覆盖远端版本，包括筛选、排序与列设置。'}
              </DialogDescription>
            </DialogHeader>
            {review && (
              <div className="fve:grid fve:gap-3 fve:sm:grid-cols-2">
                {(
                  [
                    ['本地编辑', review.local],
                    ['远端版本', review.remote],
                  ] as const
                ).map(([label, instance]) => (
                  <section
                    key={label}
                    aria-label={label}
                    className="fve:rounded fve:border fve:p-3"
                  >
                    <h3>{label}</h3>
                    <p>{instance.title}</p>
                    {instance.kind === 'record' ? (
                      <>
                        <p>每页 {instance.config.pagination.size} 条</p>
                        <p>
                          {instance.config.presentation.layout === 'table'
                            ? '表格'
                            : '卡片'}{' '}
                          ·{' '}
                          {instance.config.presentation.table?.columns.length ??
                            0}{' '}
                          列 · {instance.config.sort.length} 项排序
                        </p>
                      </>
                    ) : instance.kind === 'dashboard' ? (
                      <p>
                        {instance.config.panels.length} 个面板 ·{' '}
                        {instance.config.filters.length} 项全局筛选
                      </p>
                    ) : (
                      <>
                        <p>
                          {instance.config.dimensions.length} 个维度 ·{' '}
                          {instance.config.metrics.length} 个指标
                        </p>
                        <p>
                          {instance.config.metrics
                            .map(metric => metric.title)
                            .join('、')}
                        </p>
                        <p>
                          最多 {instance.config.limit} 行 ·{' '}
                          {instance.config.sort.length} 项排序
                        </p>
                      </>
                    )}
                  </section>
                ))}
              </div>
            )}
            {error && <p role="alert">{error}</p>}
            <DialogFooter>
              <Button variant="outline" onClick={() => setChoice(null)}>
                取消
              </Button>
              <Button
                disabled={
                  busy ||
                  !review ||
                  (choice === 'overwrite' &&
                    (!permissions.save || session.validation.length > 0))
                }
                onClick={() => {
                  if (!review) return;
                  setError(null);
                  void (
                    choice === 'remote'
                      ? engine.useRemoteInstance(review, session.instance.id)
                      : engine.overwriteInstance(review, session.instance.id)
                  )
                    .then(() => setChoice(null))
                    .catch(error =>
                      setError(
                        error instanceof Error ? error.message : '操作失败',
                      ),
                    );
                }}
              >
                {choice === 'remote' ? '确认使用最新版本' : '确认覆盖远端版本'}
              </Button>
            </DialogFooter>
          </>
        )}
      </DialogContent>
    </Dialog>
  );
}

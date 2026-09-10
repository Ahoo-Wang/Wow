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

import { useRef, useState } from 'react';
import { Button } from '../components/ui/button.js';
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '../components/ui/dialog.js';
import type { ViewEngine } from './ViewEngine.js';
import { DeleteViewDialog } from './page/DeleteViewDialog.js';
import { ViewManagerGroup } from './page/ViewManagerGroup.js';
import type { InstanceGroup } from './page/ViewNavigation.js';
import { useViewManagerAction } from './page/useViewManagerAction.js';

/** Manages instance metadata and this user's ordering without submitting record-view drafts. */
export function ViewManager({
  engine,
  groups,
  open,
  onOpenChange,
  finalFocus,
}: {
  engine: ViewEngine;
  groups: InstanceGroup[];
  open: boolean;
  onOpenChange(open: boolean): void;
  finalFocus(): HTMLElement | null;
}) {
  const [confirmId, setConfirmId] = useState<string | null>(null);
  const { busy, busyRef, error, setError, execute } = useViewManagerAction();
  const titleRef = useRef<HTMLHeadingElement>(null);
  const deleteTrigger = useRef<HTMLButtonElement>(null);
  const target = groups
    .flatMap(group => group.sessions)
    .find(session => session.instance.id === confirmId);
  function closeDelete() {
    setConfirmId(null);
    setError(null);
  }
  return (
    <Dialog
      open={open}
      onOpenChange={next => {
        if (busyRef.current) return;
        onOpenChange(next);
        if (!next) closeDelete();
      }}
    >
      <DialogContent
        className="fve:sm:max-w-xl"
        initialFocus={titleRef}
        finalFocus={finalFocus}
        showCloseButton={!busy}
      >
        <DialogHeader>
          <DialogTitle ref={titleRef} tabIndex={-1}>
            管理视图
          </DialogTitle>
          <DialogDescription>
            点击编辑图标修改名称；拖动手柄调整组内顺序，排序仅对你生效。
            默认视图仅对你生效，下次进入时自动打开。
          </DialogDescription>
        </DialogHeader>
        <span role="status" aria-live="polite" className="fve:sr-only">
          {busy ? '正在保存…' : ''}
        </span>
        {error && !target && (
          <p role="alert" className="fve:text-sm fve:text-destructive">
            {error}
          </p>
        )}
        <div className="fve:flex fve:max-h-[60vh] fve:flex-col fve:gap-4 fve:overflow-y-auto">
          {!groups.length && (
            <p className="fve:text-sm fve:text-muted-foreground">暂无视图</p>
          )}
          {groups.map(group => (
            <ViewManagerGroup
              key={`${group.id}:${open}`}
              engine={engine}
              group={group}
              busy={busy}
              busyRef={busyRef}
              execute={execute}
              fallbackFocus={titleRef}
              onDelete={(id, trigger) => {
                deleteTrigger.current = trigger;
                setError(null);
                setConfirmId(id);
              }}
            />
          ))}
        </div>
        <DialogFooter>
          <div className="fve:flex fve:justify-end">
            <DialogClose render={<Button variant="outline" disabled={busy} />}>
              完成
            </DialogClose>
            <DeleteViewDialog
              engine={engine}
              target={target}
              busy={busy}
              busyRef={busyRef}
              error={error}
              execute={execute}
              deleteTrigger={deleteTrigger}
              titleRef={titleRef}
              onClose={closeDelete}
            />
          </div>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

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

import { useRef, type RefObject } from 'react';
import { Button } from '../../components/ui/button.js';
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '../../components/ui/dialog.js';
import type { RecordSession } from '../recordModel.js';
import type { ViewEngine } from '../ViewEngine.js';
import type { ExecuteViewManagerAction } from './useViewManagerAction.js';

import { useViewCapabilities } from './useViewCapabilities.js';

export function DeleteViewDialog({
  engine,
  target,
  busy,
  busyRef,
  error,
  execute,
  deleteTrigger,
  titleRef,
  onClose,
}: {
  engine: ViewEngine;
  target: RecordSession | undefined;
  busy: boolean;
  busyRef: RefObject<boolean>;
  error: string | null;
  execute: ExecuteViewManagerAction;
  deleteTrigger: RefObject<HTMLButtonElement | null>;
  titleRef: RefObject<HTMLHeadingElement | null>;
  onClose(): void;
}) {
  const capabilities = useViewCapabilities(engine);
  const cancelRef = useRef<HTMLButtonElement>(null);
  return (
    <Dialog
      open={Boolean(target)}
      onOpenChange={next => {
        if (!next && !busyRef.current) {
          onClose();
        }
      }}
    >
      <DialogContent
        initialFocus={cancelRef}
        finalFocus={() =>
          deleteTrigger.current?.isConnected
            ? deleteTrigger.current
            : titleRef.current
        }
        showCloseButton={!busy}
      >
        <DialogHeader>
          <DialogTitle>删除视图</DialogTitle>
          <DialogDescription>
            删除“{target?.instance.title}
            ”？这只会删除视图配置，不会删除业务数据。
            {target?.instance.scope.type === 'public' &&
              '其他使用者也将无法使用此公共视图。'}
            {(target?.dirty || target?.filterPending) &&
              '未保存或待查询的修改也会丢弃。'}
          </DialogDescription>
        </DialogHeader>
        {error && (
          <p role="alert" className="fve:text-sm fve:text-destructive">
            {error}
          </p>
        )}
        <DialogFooter>
          <DialogClose
            render={
              <Button ref={cancelRef} variant="outline" disabled={busy} />
            }
          >
            取消
          </DialogClose>
          <Button
            variant="destructive"
            disabled={
              busy ||
              !target ||
              !capabilities.instances[target.instance.id]?.permissions.delete
            }
            onClick={() => {
              if (target)
                void execute(
                  () => engine.deleteInstance(target.instance.id),
                  () => {
                    onClose();
                  },
                );
            }}
          >
            {busy ? '删除中…' : '删除视图'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

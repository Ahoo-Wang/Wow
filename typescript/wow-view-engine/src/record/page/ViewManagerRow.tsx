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

import {
  CheckIcon,
  GripVerticalIcon,
  PencilIcon,
  Trash2Icon,
  XIcon,
} from 'lucide-react';
import {
  useLayoutEffect,
  useRef,
  useState,
  type DragEvent,
  type RefObject,
} from 'react';
import { Button } from '../../components/ui/button.js';
import { Input } from '../../components/ui/input.js';
import { cn } from '../../lib/utils.js';
import type { ViewEngine } from '../ViewEngine.js';
import type { RecordSession } from '../recordModel.js';
import type { ExecuteViewManagerAction } from './useViewManagerAction.js';

/** A row owns its transient name buffer and returns focus after rename or cancellation. */
import { useViewCapabilities } from './useViewCapabilities.js';
import { deniedPermissions } from '../engine/instancePermissions.js';

export function ViewManagerRow({
  engine,
  session,
  busy,
  execute,
  fallbackFocus,
  movable,
  instructions,
  dragging,
  dropEdge,
  onDragStart,
  onDragEnd,
  onMove,
  onDelete,
}: {
  engine: ViewEngine;
  session: RecordSession;
  busy: boolean;
  execute: ExecuteViewManagerAction;
  fallbackFocus: RefObject<HTMLElement | null>;
  movable: boolean;
  instructions: string;
  dragging: boolean;
  dropEdge: 'top' | 'bottom' | null;
  onDragStart(event: DragEvent<HTMLButtonElement>): void;
  onDragEnd(): void;
  onMove(direction: -1 | 1, trigger: HTMLButtonElement): void;
  onDelete(trigger: HTMLButtonElement): void;
}) {
  const { id, scope } = session.instance;
  const title = session.baseline.title;
  const capabilities = useViewCapabilities(engine).instances[id];
  const permissions = capabilities?.permissions ?? deniedPermissions;
  const system = scope.type === 'public' && scope.source === 'system';
  const writing =
    busy || session.writeStatus !== 'idle' || session.requiresReload;
  const [name, setName] = useState<string | null>(null);
  const nameInput = useRef<HTMLInputElement>(null);
  const editButton = useRef<HTMLButtonElement>(null);
  const focusEditing = useRef<boolean | null>(null);
  const changed = name !== null && name.trim() !== title;
  useLayoutEffect(() => {
    if (busy || focusEditing.current === null) return;
    const element = focusEditing.current
      ? nameInput.current
      : editButton.current;
    (element ?? fallbackFocus.current)?.focus();
    if (focusEditing.current) nameInput.current?.select();
    focusEditing.current = null;
  }, [busy, name, title, permissions.rename, fallbackFocus]);
  function finishName() {
    focusEditing.current = false;
    setName(null);
  }
  function rename() {
    focusEditing.current = true;
    void execute(() => engine.renameInstance(name ?? title, id), finishName);
  }
  return (
    <li
      aria-label={title}
      className="fve:relative fve:flex fve:min-h-9 fve:items-center fve:gap-2 fve:data-dragging:opacity-50"
      data-dragging={dragging || undefined}
    >
      {dropEdge && (
        <span
          data-slot="view-drop-indicator"
          aria-hidden="true"
          className={cn(
            'fve:pointer-events-none fve:absolute fve:inset-x-0 fve:border-t-2 fve:border-primary',
            dropEdge === 'top' ? 'fve:-top-1' : 'fve:-bottom-1',
          )}
        />
      )}
      <Button
        variant="ghost"
        size="icon-sm"
        className="fve:cursor-grab fve:active:cursor-grabbing"
        aria-label={`拖动调整${title}顺序`}
        aria-describedby={instructions}
        draggable={movable}
        disabled={!movable}
        onDragStart={event => {
          if (!movable) {
            event.preventDefault();
            return;
          }
          onDragStart(event);
        }}
        onDragEnd={onDragEnd}
        onKeyDown={event => {
          if (event.key !== 'ArrowUp' && event.key !== 'ArrowDown') return;
          event.preventDefault();
          onMove(event.key === 'ArrowUp' ? -1 : 1, event.currentTarget);
        }}
      >
        <GripVerticalIcon aria-hidden="true" />
      </Button>
      {permissions.rename && name !== null ? (
        <>
          <Input
            ref={nameInput}
            aria-label={`${title}名称`}
            value={name}
            disabled={writing}
            onChange={event => setName(event.target.value)}
            onKeyDown={event => {
              if (event.key === 'Escape') {
                event.preventDefault();
                event.stopPropagation();
                finishName();
              } else if (event.key === 'Enter' && name.trim()) {
                event.preventDefault();
                if (changed) rename();
                else finishName();
              }
            }}
          />
          <Button
            variant="ghost"
            size="icon-sm"
            aria-label={`保存${title}名称`}
            title="保存名称"
            disabled={writing || !changed || !name.trim()}
            onClick={rename}
          >
            <CheckIcon aria-hidden="true" />
          </Button>
          <Button
            variant="ghost"
            size="icon-sm"
            aria-label={`取消编辑${title}名称`}
            title="取消编辑"
            disabled={writing}
            onClick={finishName}
          >
            <XIcon aria-hidden="true" />
          </Button>
        </>
      ) : (
        <>
          <span className="fve:min-w-0 fve:flex-1 fve:break-words fve:text-sm">
            {title}
          </span>
          {permissions.rename && (
            <Button
              ref={editButton}
              variant="ghost"
              size="icon-sm"
              aria-label={`编辑${title}名称`}
              title="编辑名称"
              disabled={writing}
              onClick={() => {
                focusEditing.current = true;
                setName(title);
              }}
            >
              <PencilIcon aria-hidden="true" />
            </Button>
          )}
        </>
      )}
      {system && (
        <span className="fve:inline-flex fve:h-5 fve:shrink-0 fve:items-center fve:rounded-4xl fve:border fve:border-border fve:px-2 fve:text-xs fve:font-medium">
          系统
        </span>
      )}
      {permissions.delete && (
        <Button
          variant="ghost"
          size="icon-sm"
          aria-label={`删除${title}`}
          title="删除视图"
          disabled={
            busy ||
            session.writeStatus !== 'idle' ||
            (session.requiresReload && !capabilities?.retryDelete)
          }
          onClick={event => onDelete(event.currentTarget)}
        >
          <Trash2Icon aria-hidden="true" />
        </Button>
      )}
    </li>
  );
}

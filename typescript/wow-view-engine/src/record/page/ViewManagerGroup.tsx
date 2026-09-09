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
  useId,
  useLayoutEffect,
  useRef,
  useState,
  type DragEvent,
  type RefObject,
} from 'react';
import type { ViewEngine } from '../ViewEngine.js';
import type { ExecuteViewManagerAction } from './useViewManagerAction.js';
import { ViewManagerRow } from './ViewManagerRow.js';
import type { InstanceGroup } from './ViewNavigation.js';

/** Group-local drag and keyboard ordering share the same persistence operation. */
import { useViewCapabilities } from './useViewCapabilities.js';

export function ViewManagerGroup({
  engine,
  group,
  busy,
  busyRef,
  execute,
  fallbackFocus,
  onDelete,
}: {
  engine: ViewEngine;
  group: InstanceGroup;
  busy: boolean;
  busyRef: RefObject<boolean>;
  execute: ExecuteViewManagerAction;
  fallbackFocus: RefObject<HTMLElement | null>;
  onDelete(id: string, trigger: HTMLButtonElement): void;
}) {
  const capabilities = useViewCapabilities(engine);
  const [dragged, setDragged] = useState<string | null>(null);
  const [drop, setDrop] = useState<number | null>(null);
  const [announcement, setAnnouncement] = useState('');
  const instructions = useId();
  const dragFocus = useRef<HTMLButtonElement>(null);
  const restoreFocus = useRef<HTMLButtonElement | null>(null);
  const movable = !busy && capabilities.reorder && group.sessions.length > 1;
  useLayoutEffect(() => {
    if (busy || !restoreFocus.current) return;
    (restoreFocus.current.isConnected
      ? restoreFocus.current
      : fallbackFocus.current
    )?.focus();
    restoreFocus.current = null;
  }, [busy, group, fallbackFocus]);
  function endDrag() {
    setDragged(null);
    setDrop(null);
  }
  function resolveDrop(event: DragEvent<HTMLOListElement>) {
    if (busyRef.current || !engine.canReorderInstances() || !dragged)
      return null;
    const source = group.sessions.findIndex(
      session => session.instance.id === dragged,
    );
    if (source < 0) return null;
    const bounds = Array.from(event.currentTarget.children, row =>
      row.getBoundingClientRect(),
    );
    const before = bounds.findIndex(
      row => event.clientY < row.top + row.height / 2,
    );
    const boundary = before < 0 ? bounds.length : before;
    return {
      source,
      boundary,
      target: boundary > source ? boundary - 1 : boundary,
    };
  }
  function move(
    source: number,
    target: number,
    focus: HTMLButtonElement | null,
  ) {
    if (
      busyRef.current ||
      !engine.canReorderInstances() ||
      source === target ||
      !group.sessions[source] ||
      !group.sessions[target]
    )
      return;
    const ids = [...engine.getSnapshot().instanceIds];
    const from = ids.indexOf(group.sessions[source].instance.id);
    const to = ids.indexOf(group.sessions[target].instance.id);
    if (from < 0 || to < 0) return;
    const [id] = ids.splice(from, 1);
    ids.splice(to, 0, id);
    restoreFocus.current = focus;
    void execute(
      () => engine.reorderInstances(ids),
      () => setAnnouncement(`已移至${group.label}第 ${target + 1} 项`),
    );
  }
  return (
    <section aria-label={group.label}>
      <h3 className="fve:mb-2 fve:text-xs fve:font-medium fve:text-muted-foreground">
        {group.label}
      </h3>
      <p id={instructions} className="fve:sr-only">
        拖动调整顺序，或聚焦手柄后按上、下方向键移动。
      </p>
      <span role="status" aria-live="polite" className="fve:sr-only">
        {announcement}
      </span>
      <ol
        aria-label={`${group.label}顺序`}
        className="fve:m-0 fve:flex fve:list-none fve:flex-col fve:gap-2 fve:p-1"
        onDragOver={event => {
          const next = resolveDrop(event);
          setDrop(next && next.source !== next.target ? next.boundary : null);
          event.dataTransfer.dropEffect = next ? 'move' : 'none';
          if (next) event.preventDefault();
        }}
        onDragLeave={event => {
          if (
            !(event.relatedTarget instanceof Node) ||
            !event.currentTarget.contains(event.relatedTarget)
          )
            setDrop(null);
        }}
        onDrop={event => {
          const next = resolveDrop(event);
          if (next) {
            event.preventDefault();
            move(next.source, next.target, dragFocus.current);
          }
          endDrag();
        }}
      >
        {group.sessions.map((session, index) => (
          <ViewManagerRow
            key={session.instance.id}
            engine={engine}
            session={session}
            busy={busy}
            execute={execute}
            fallbackFocus={fallbackFocus}
            movable={movable}
            instructions={instructions}
            dragging={dragged === session.instance.id}
            dropEdge={
              drop === index
                ? 'top'
                : drop === group.sessions.length &&
                    index === group.sessions.length - 1
                  ? 'bottom'
                  : null
            }
            onDragStart={event => {
              event.dataTransfer.effectAllowed = 'move';
              event.dataTransfer.setData('text/plain', session.instance.id);
              dragFocus.current = event.currentTarget;
              setDragged(session.instance.id);
            }}
            onDragEnd={endDrag}
            onMove={(direction, trigger) =>
              move(index, index + direction, trigger)
            }
            onDelete={trigger => onDelete(session.instance.id, trigger)}
          />
        ))}
      </ol>
    </section>
  );
}

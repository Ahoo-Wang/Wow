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
  createContext,
  useContext,
  useLayoutEffect,
  useRef,
  useState,
  type ButtonHTMLAttributes,
  type LiHTMLAttributes,
  type ReactNode,
} from 'react';
import {
  DragDropProvider,
  useDragDropManager,
  type DragStartEvent,
  type DragOverEvent,
  type DragEndEvent,
} from '@dnd-kit/react';
import { isSortable, useSortable } from '@dnd-kit/react/sortable';
import {
  Accessibility,
  KeyboardSensor,
  PointerSensor,
  Feedback,
} from '@dnd-kit/dom';
import { OptimisticSortingPlugin } from '@dnd-kit/dom/sortable';
import { cn } from './utils.js';
import { message } from './snapshot.js';

type Props<T extends { id: string }> = {
  items: readonly T[];
  owner: unknown;
  disabled?: boolean;
  titleOf(item: T): string;
  canMove?(item: T, target: T): boolean;
  onChange(
    items: T[],
    move: { id: string; from: number; to: number },
  ): void | Promise<boolean>;
  children: ReactNode;
};
type Gesture = { owner: unknown; signature: string };
const Context = createContext<{
  ids: string[];
  disabled: boolean;
  active: boolean;
  allows(from: string, to: string): boolean;
} | null>(null);

/** One shared gesture mechanism; callers retain the configuration and ordering rules. */
export function ListOrder<T extends { id: string }>(props: Props<T>) {
  const { items, owner, disabled = false, canMove, children } = props;
  const ids = items.map(item => item.id);
  const byId = new Map(items.map(item => [item.id, item]));
  const allows = (from: string, to: string) => {
    const a = byId.get(from),
      b = byId.get(to);
    return (
      !disabled && !!a && !!b && (from === to || !canMove || canMove(a, b))
    );
  };
  const signature = JSON.stringify([
    ids,
    items.map((item, i) => [
      i > 0 && allows(item.id, ids[i - 1]),
      i + 1 < items.length && allows(item.id, ids[i + 1]),
    ]),
  ]);
  const [gesture, setGesture] = useState<Gesture | null>(null);
  const dropAllowed = useRef(true);
  if (
    gesture &&
    (gesture.owner !== owner || gesture.signature !== signature || disabled)
  ) {
    setGesture(null);
  }
  const [announcement, setAnnouncement] = useState('');
  const [error, setError] = useState<string | null>(null);
  const latest = useRef<Props<T> | null>(null);
  const submission = useRef(0);
  useLayoutEffect(
    () => () => {
      submission.current++;
    },
    [owner],
  );
  useLayoutEffect(() => {
    latest.current = props;
    return () => {
      latest.current = null;
    };
  });
  async function move(id: string, target: number, handle: Element | undefined) {
    const current = latest.current;
    if (!current || current.owner !== owner || current.disabled) return;
    const from = current.items.findIndex(item => item.id === id),
      to = current.items[target];
    const item = current.items[from];
    if (
      !item ||
      !to ||
      from === target ||
      (current.canMove && !current.canMove(item, to))
    )
      return;
    const next = [...current.items];
    next.splice(from, 1);
    next.splice(target, 0, item);
    const token = ++submission.current;
    setError(null);
    const active = () =>
      !!latest.current &&
      latest.current.owner === owner &&
      token === submission.current;
    try {
      const result = current.onChange(next, { id, from, to: target });
      const success = result === undefined ? undefined : await result;
      if (!active()) return;
      if (success !== false)
        setAnnouncement(`${current.titleOf(item)}已移至第 ${target + 1} 项`);
    } catch (reason) {
      if (active()) setError(message(reason));
    } finally {
      requestAnimationFrame(() => {
        if (active() && handle instanceof HTMLElement && handle.isConnected)
          handle.focus();
      });
    }
  }

  const title = (id: string) => {
    const current = latest.current;
    const item = current?.items.find(item => item.id === id);
    return item ? current!.titleOf(item) : id;
  };
  const instruction =
    '聚焦手柄后按空格或 Enter 拾起，方向键移动，空格或 Enter 放下，Escape 取消。';
  return (
    <DragDropProvider
      sensors={[PointerSensor, KeyboardSensor]}
      plugins={defaults =>
        defaults.map(plugin =>
          plugin === Accessibility
            ? Accessibility.configure({
                screenReaderInstructions: { draggable: instruction },
                announcements: {
                  dragstart: ({ operation }: DragStartEvent) =>
                    `已拾起${title(String(operation.source?.id))}`,
                  dragover: ({ operation }: DragOverEvent) =>
                    operation.status.dragging && isSortable(operation.target)
                      ? `移动至第 ${operation.target.index + 1} 项`
                      : undefined,
                  dragend: ({ canceled }: DragEndEvent) =>
                    canceled ? '已取消排序' : undefined,
                },
              })
            : plugin,
        )
      }
      onBeforeDragStart={event => {
        if (!latest.current || latest.current.disabled) event.preventDefault();
      }}
      onDragStart={() => {
        dropAllowed.current = true;
        setGesture({ owner, signature });
      }}
      onDragOver={event => {
        const { source, target } = event.operation;
        dropAllowed.current =
          !!source && !!target && allows(String(source.id), String(target.id));
        if (!dropAllowed.current) event.preventDefault();
      }}
      onDragEnd={event => {
        if (event.canceled) event.nativeEvent?.stopPropagation();
        setGesture(null);
        if (
          event.canceled ||
          !dropAllowed.current ||
          !gesture ||
          gesture.owner !== owner ||
          gesture.signature !== signature ||
          disabled
        )
          return;
        const { source, target } = event.operation;
        if (isSortable(source) && isSortable(target))
          void move(String(source.id), target.index, source.handle);
      }}
    >
      <Context.Provider
        value={{
          ids,
          disabled,
          active: gesture !== null,
          allows,
        }}
      >
        <GestureLifetime gesture={gesture} />
        {children}
        <span
          role="status"
          aria-label="排序结果"
          aria-live="polite"
          aria-atomic="true"
          className="fve:sr-only"
        >
          {announcement}
        </span>
        {error && <p role="alert">{error}</p>}
      </Context.Provider>
    </DragDropProvider>
  );
}
function GestureLifetime({ gesture }: { gesture: Gesture | null }) {
  const manager = useDragDropManager();
  useLayoutEffect(() => {
    if (!gesture || !manager) return;
    const event = manager.dragOperation.activatorEvent;
    const sourceHandle = event instanceof KeyboardEvent ? event.target : null;
    return () => {
      if (
        manager.dragOperation.status.dragging ||
        manager.dragOperation.status.initializing
      )
        manager.actions.stop({ canceled: true });
      if (sourceHandle instanceof HTMLElement)
        requestAnimationFrame(() => {
          if (
            sourceHandle.isConnected ||
            document.activeElement !== document.body
          )
            return;
          const handle = [...manager.registry.draggables].find(
            item => !item.disabled && item.handle?.isConnected,
          )?.handle;
          if (handle instanceof HTMLElement) handle.focus();
        });
    };
  }, [manager, gesture]);
  return null;
}

export type ListOrderBindings = {
  handleRef(element: Element | null): void;
  handleProps: ButtonHTMLAttributes<HTMLButtonElement>;
};
export function ListOrderItem({
  id,
  children,
  className,
  ...attributes
}: Omit<LiHTMLAttributes<HTMLLIElement>, 'children'> & {
  id: string;
  children(bindings: ListOrderBindings): ReactNode;
}) {
  const context = useContext(Context);
  if (!context) throw new Error('排序项目缺少列表上下文');
  const { ids, allows } = context,
    index = ids.indexOf(id);
  const movable =
    !context.disabled &&
    [ids[index - 1], ids[index + 1]].some(
      target => target && allows(id, target),
    );
  const {
    ref,
    handleRef: sortableHandleRef,
    isDragging,
    isDropTarget,
    isDragSource,
  } = useSortable({
    id,
    index,
    disabled: { draggable: !movable, droppable: context.disabled },
    // Keep React-owned DOM ordering authoritative, including failed asynchronous writes.
    plugins: defaults => [
      ...defaults.filter(plugin => plugin !== OptimisticSortingPlugin),
      Feedback.configure({ feedback: 'clone' }),
    ],
    transition: { duration: 0 },
  });
  return (
    <li
      {...attributes}
      ref={ref}
      className={cn(
        'fve:data-dragging:opacity-50 fve:data-drop-target:outline-2 fve:data-drop-target:outline-primary',
        className,
      )}
      data-dragging={isDragging || undefined}
      data-drop-target={
        (isDropTarget && !isDragSource && context.active) || undefined
      }
    >
      {children({
        handleRef: sortableHandleRef,
        handleProps: {
          disabled: !movable,
        },
      })}
    </li>
  );
}

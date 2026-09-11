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
  useState,
  type DragEvent,
  type HTMLAttributes,
  type ButtonHTMLAttributes,
} from 'react';

/** Shared pointer and keyboard ordering; callers own domain constraints. */
export function useListOrder<T extends { id: string }>({
  items,
  onChange,
  canMove: permitsMove,
  titleOf,
  disabled,
  orientation = 'vertical',
}: {
  items: readonly T[];
  onChange(items: T[]): void;
  canMove?(item: T, target: T): boolean;
  titleOf(item: T): string;
  disabled?: boolean;
  orientation?: 'vertical' | 'horizontal';
}) {
  const [draggedId, setDraggedId] = useState<string | null>(null);
  const [dropBoundary, setDropBoundary] = useState<number | null>(null);
  const [announcement, setAnnouncement] = useState('');
  const instructionsId = useId();
  const draggedIndex = items.findIndex(item => item.id === draggedId);
  function canMove(index: number, target: number): boolean {
    return Boolean(
      !disabled &&
      items[index] &&
      items[target] &&
      index !== target &&
      (!permitsMove || permitsMove(items[index], items[target])),
    );
  }
  function move(index: number, target: number) {
    if (!canMove(index, target)) return;
    const next = [...items];
    const [item] = next.splice(index, 1);
    next.splice(target, 0, item);
    onChange(next);
    setAnnouncement(`${titleOf(item)}已移至第 ${target + 1} 项`);
  }
  function endDrag() {
    setDraggedId(null);
    setDropBoundary(null);
  }
  function resolveDrop(event: DragEvent<HTMLOListElement>) {
    if (disabled || draggedIndex < 0) return null;
    const bounds = Array.from(event.currentTarget.children, row =>
      row.getBoundingClientRect(),
    );
    const hovered = bounds.findIndex(
      row =>
        event.clientY >= row.top &&
        event.clientY <= row.bottom &&
        (orientation === 'vertical' ||
          (event.clientX >= row.left && event.clientX <= row.right)),
    );
    if (
      hovered >= 0 &&
      hovered !== draggedIndex &&
      !canMove(draggedIndex, hovered)
    )
      return null;
    const before = bounds.findIndex(row =>
      orientation === 'vertical'
        ? event.clientY < row.top + row.height / 2
        : event.clientY < row.top ||
          (event.clientY <= row.bottom &&
            event.clientX < row.left + row.width / 2),
    );
    const boundary = before < 0 ? bounds.length : before;
    const target = boundary > draggedIndex ? boundary - 1 : boundary;
    return target === draggedIndex || canMove(draggedIndex, target)
      ? { boundary, target }
      : null;
  }
  const listProps: HTMLAttributes<HTMLOListElement> = {
    onDragOver: event => {
      const drop = resolveDrop(event);
      setDropBoundary(
        drop && drop.target !== draggedIndex ? drop.boundary : null,
      );
      event.dataTransfer.dropEffect = drop ? 'move' : 'none';
      if (drop) event.preventDefault();
    },
    onDragLeave: event => {
      if (
        !(event.relatedTarget instanceof Node) ||
        !event.currentTarget.contains(event.relatedTarget)
      )
        setDropBoundary(null);
    },
    onDrop: event => {
      const drop = resolveDrop(event);
      if (drop) {
        event.preventDefault();
        move(draggedIndex, drop.target);
      }
      endDrag();
    },
  };
  function handleProps(index: number): ButtonHTMLAttributes<HTMLButtonElement> {
    const item = items[index];
    const movable = canMove(index, index - 1) || canMove(index, index + 1);
    return {
      disabled: !movable,
      draggable: movable,
      onDragStart: event => {
        if (!movable) {
          event.preventDefault();
          return;
        }
        event.dataTransfer.effectAllowed = 'move';
        event.dataTransfer.setData('text/plain', item.id);
        setDraggedId(item.id);
      },
      onDragEnd: endDrag,
      onKeyDown: event => {
        if (
          ![
            'ArrowUp',
            'ArrowDown',
            ...(orientation === 'horizontal'
              ? ['ArrowLeft', 'ArrowRight']
              : []),
          ].includes(event.key)
        )
          return;
        event.preventDefault();
        move(
          index,
          index + (['ArrowUp', 'ArrowLeft'].includes(event.key) ? -1 : 1),
        );
      },
    };
  }
  return {
    draggedId,
    dropBoundary,
    announcement,
    instructionsId,
    endDrag,
    listProps,
    handleProps,
  };
}

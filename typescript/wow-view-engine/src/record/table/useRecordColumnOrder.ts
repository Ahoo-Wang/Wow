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
import { type RecordColumn } from '../recordModel.js';
import {
  getRecordColumnPinning,
  orderRecordColumns,
} from '../recordColumns.js';
import type { RecordColumnSettingsProps } from '../recordReactTypes.js';

/** Reordering stays inside each pinned region and supports pointer and keyboard input. */
export function useRecordColumnOrder({
  definition,
  columns: configuredColumns,
  onChange,
  disabled,
}: RecordColumnSettingsProps) {
  const [draggedId, setDraggedId] = useState<string | null>(null);
  const [dropBoundary, setDropBoundary] = useState<number | null>(null);
  const [announcement, setAnnouncement] = useState('');
  const instructionsId = useId();
  const columns = orderRecordColumns(configuredColumns, definition.rowKey);
  const draggedIndex = columns.findIndex(column => column.id === draggedId);
  function isLocked(column: RecordColumn): boolean {
    return column.kind === 'actions' || column.field === definition.rowKey;
  }
  function titleOf(column: RecordColumn): string {
    return (
      column.title ??
      (column.kind === 'field'
        ? (definition.fields.find(field => field.field === column.field)
            ?.label ?? column.field)
        : '操作')
    );
  }
  function canMove(index: number, target: number): boolean {
    const column = columns[index];
    const other = columns[target];
    return Boolean(
      !disabled &&
      column &&
      other &&
      index !== target &&
      getRecordColumnPinning(column, definition.rowKey) ===
        getRecordColumnPinning(other, definition.rowKey) &&
      isLocked(column) === isLocked(other),
    );
  }
  function move(index: number, target: number) {
    if (!canMove(index, target)) return;
    const next = [...columns];
    const [column] = next.splice(index, 1);
    next.splice(target, 0, column);
    onChange(next);
    setAnnouncement(`${titleOf(column)}已移至第 ${target + 1} 列`);
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
      row => event.clientY >= row.top && event.clientY <= row.bottom,
    );
    if (
      hovered >= 0 &&
      hovered !== draggedIndex &&
      !canMove(draggedIndex, hovered)
    )
      return null;
    const before = bounds.findIndex(
      row => event.clientY < row.top + row.height / 2,
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
    const column = columns[index];
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
        event.dataTransfer.setData('text/plain', column.id);
        setDraggedId(column.id);
      },
      onDragEnd: endDrag,
      onKeyDown: event => {
        if (event.key !== 'ArrowUp' && event.key !== 'ArrowDown') return;
        event.preventDefault();
        move(index, index + (event.key === 'ArrowUp' ? -1 : 1));
      },
    };
  }
  return {
    columns,
    draggedId,
    dropBoundary,
    announcement,
    instructionsId,
    isLocked,
    titleOf,
    endDrag,
    listProps,
    handleProps,
  };
}

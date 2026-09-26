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

/**
 * The tab bar while the board is being built (D22 E): the tabs to arrange,
 * each carried by a handle — its arrows move it too — shown by a press,
 * renamed in place, moved or deleted from its menu; a way to add one; and
 * the question a tab that carries panels is asked before it goes.
 */

import { DragDropProvider } from '@dnd-kit/react';
import { useSortable } from '@dnd-kit/react/sortable';
import { EllipsisIcon, PlusIcon } from 'lucide-react';
import type { DashboardTab } from '../../model/index.js';
import { DragHandle, moveTarget } from '../DragHandle.js';
import { IconButton } from '../IconButton.js';
import { dragAccessibility } from '../dragAnnounce.js';
import { dropped } from '../dragDrop.js';
import { sortableList, withoutOptimisticSorting } from '../dragPlugins.js';
import { dragWording } from '../dragWording.js';
import { useViewMessages } from '../MessagesProvider.js';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '../components/alert-dialog.js';
import { Button } from '../components/button.js';
import {
  DropdownMenu,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '../components/dropdown-menu.js';
import { AlertDialogContent, DropdownMenuContent } from '../popups.js';
import { RenameInput } from '../RenameInput.js';

/**
 * The bar being built: a list whose rows carry a handle, the press that
 * shows the tab and its menu — a `tablist` may hold tabs and nothing else —
 * and 「添加标签页」 after them. Every edit is handed back by the tab's id.
 */
export function EditableTabBar({
  tabs,
  shown,
  renaming,
  titleOf,
  triggerRef,
  onShow,
  onRename,
  onRenamed,
  onRenameCancel,
  onMove,
  onRemove,
  onAdd,
}: {
  tabs: readonly DashboardTab[];
  /** The tab on screen. */
  shown: string;
  /** The tab whose name is being typed in place, if any. */
  renaming: string | null;
  titleOf(tab: DashboardTab, index: number): string;
  triggerRef(tabId: string): (element: HTMLElement | null) => void;
  onShow(tabId: string): void;
  onRename(tabId: string): void;
  onRenamed(tabId: string, title: string): void;
  onRenameCancel(tabId: string): void;
  onMove(tabId: string, to: number): void;
  onRemove(tab: DashboardTab): void;
  onAdd(): void;
}) {
  const messages = useViewMessages();
  return (
    <div className="flex min-w-0 flex-wrap items-center gap-1 border-b pb-1">
      <DragDropProvider
        {...sortableList(
          dragAccessibility(dragWording(messages, TAB_DRAG_WORDING), id => {
            const at = tabs.findIndex(tab => tab.id === id);
            return at < 0 ? id : titleOf(tabs[at], at);
          }),
        )}
        onDragEnd={({ operation, canceled }) => {
          const drop = dropped(operation, canceled);
          if (!drop) return;
          onMove(
            drop.source,
            tabs.findIndex(tab => tab.id === drop.target),
          );
        }}
      >
        <ul
          aria-label={messages.label('label.tabs.name')}
          className="flex min-w-0 flex-wrap items-center gap-1"
        >
          {tabs.map((tab, index) => (
            <EditableTab
              key={tab.id}
              tab={tab}
              index={index}
              total={tabs.length}
              title={titleOf(tab, index)}
              current={tab.id === shown}
              renaming={renaming === tab.id}
              triggerRef={triggerRef(tab.id)}
              onShow={() => onShow(tab.id)}
              onRename={() => onRename(tab.id)}
              onRenamed={title => onRenamed(tab.id, title)}
              onRenameCancel={() => onRenameCancel(tab.id)}
              onMove={to => onMove(tab.id, to)}
              onRemove={() => onRemove(tab)}
            />
          ))}
        </ul>
      </DragDropProvider>
      <IconButton
        data-slot="dashboard-tab-add"
        label={messages.label('label.tabs.add')}
        variant="ghost"
        size="icon-sm"
        onClick={onAdd}
      >
        <PlusIcon />
      </IconButton>
    </div>
  );
}

/** The question a tab that carries panels is asked before it goes. */
export function TabRemovalDialog({
  tab,
  title,
  count,
  onConfirm,
  onClose,
}: {
  /** The tab asked about; `null` while nothing is. */
  tab: DashboardTab | null;
  title: string;
  count: number;
  onConfirm(tab: DashboardTab): void;
  onClose(): void;
}) {
  return (
    <AlertDialog
      open={tab !== null}
      onOpenChange={open => {
        if (!open) onClose();
      }}
    >
      <AlertDialogContent data-slot="dashboard-tab-remove">
        {tab && (
          <TabRemoval
            title={title}
            count={count}
            onConfirm={() => onConfirm(tab)}
          />
        )}
      </AlertDialogContent>
    </AlertDialog>
  );
}

/** Where the tab bar's drag sentences live in the catalogue. */
const TAB_DRAG_WORDING = {
  picked: 'label.tabs.picked',
  cancelled: 'label.tabs.cancelled',
  placeholder: 'title',
} as const;

/**
 * One tab while the board is being built: the handle that carries it, the
 * press that shows it — or the field its name is typed into — and its menu.
 */
function EditableTab({
  tab,
  index,
  total,
  title,
  current,
  renaming,
  triggerRef,
  onShow,
  onRename,
  onRenamed,
  onRenameCancel,
  onMove,
  onRemove,
}: {
  tab: DashboardTab;
  index: number;
  total: number;
  title: string;
  /** Whether it is the tab on screen. */
  current: boolean;
  renaming: boolean;
  triggerRef(element: HTMLElement | null): void;
  onShow(): void;
  onRename(): void;
  onRenamed(title: string): void;
  onRenameCancel(): void;
  onMove(to: number): void;
  onRemove(): void;
}) {
  const messages = useViewMessages();
  const { ref, handleRef, isDragging } = useSortable({
    id: tab.id,
    index,
    plugins: withoutOptimisticSorting,
  });
  return (
    <li
      ref={ref}
      data-slot="dashboard-tab-item"
      data-dragging={isDragging || undefined}
      className="flex items-center"
    >
      <DragHandle
        ref={handleRef}
        axis="horizontal"
        label={messages.label('label.tabs.reorder', { title })}
        index={index}
        total={total}
        dragging={isDragging}
        onMove={move => onMove(moveTarget(move, index, total))}
      />
      {renaming ? (
        // A tab is never left without a name to be called by.
        <RenameInput
          data-slot="dashboard-tab-name"
          initial={tab.title}
          label={messages.label('label.tabs.rename-field', { title })}
          required
          onCommit={onRenamed}
          onCancel={onRenameCancel}
          className="h-7 w-36"
        />
      ) : (
        <Button
          ref={triggerRef}
          data-slot="dashboard-tab"
          // The tab on screen is the one pressed in: the look and the word
          // are the same fact.
          variant={current ? 'secondary' : 'ghost'}
          size="sm"
          aria-current={current ? 'true' : undefined}
          onClick={onShow}
          onDoubleClick={onRename}
        >
          {title}
        </Button>
      )}
      <DropdownMenu>
        <DropdownMenuTrigger
          render={
            <IconButton
              data-slot="dashboard-tab-menu"
              label={messages.label('label.tabs.actions', { title })}
              variant="ghost"
              size="icon-xs"
            />
          }
        >
          <EllipsisIcon />
        </DropdownMenuTrigger>
        <DropdownMenuContent align="start">
          <DropdownMenuGroup>
            <DropdownMenuItem onClick={onRename}>
              {messages.label('label.tabs.rename')}
            </DropdownMenuItem>
          </DropdownMenuGroup>
          <DropdownMenuSeparator />
          <DropdownMenuGroup>
            <DropdownMenuItem variant="destructive" onClick={onRemove}>
              {messages.label('label.tabs.remove')}
            </DropdownMenuItem>
          </DropdownMenuGroup>
        </DropdownMenuContent>
      </DropdownMenu>
    </li>
  );
}

/** The question a tab that carries panels is asked before it goes. */
function TabRemoval({
  title,
  count,
  onConfirm,
}: {
  title: string;
  count: number;
  onConfirm(): void;
}) {
  const messages = useViewMessages();
  return (
    <>
      <AlertDialogHeader>
        <AlertDialogTitle>
          {messages.label('label.tabs.remove-heading', { title })}
        </AlertDialogTitle>
        <AlertDialogDescription>
          {messages.label('label.tabs.remove-description', { count })}
        </AlertDialogDescription>
      </AlertDialogHeader>
      <AlertDialogFooter>
        <AlertDialogCancel>
          {messages.label('label.dialog.cancel')}
        </AlertDialogCancel>
        <AlertDialogAction variant="destructive" onClick={onConfirm}>
          {messages.label('label.tabs.remove-confirm')}
        </AlertDialogAction>
      </AlertDialogFooter>
    </>
  );
}

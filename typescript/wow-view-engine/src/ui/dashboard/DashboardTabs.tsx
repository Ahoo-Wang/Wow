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

import { useEffect, useRef, useState } from 'react';
import { DragDropProvider } from '@dnd-kit/react';
import { useSortable } from '@dnd-kit/react/sortable';
import { Accessibility } from '@dnd-kit/dom';
import { OptimisticSortingPlugin } from '@dnd-kit/dom/sortable';
import { EllipsisIcon, PlusIcon } from 'lucide-react';
import type { DashboardTab } from '../../model/index.js';
import type { DashboardController } from '../../react/index.js';
import type { DashboardEditing } from '../../runtime/index.js';
import { useSurfaceAnnouncer } from '../Announcer.js';
import { DragHandle } from '../DragHandle.js';
import { IconButton } from '../IconButton.js';
import { dragAccessibility } from '../dragAnnounce.js';
import { dropped } from '../dragDrop.js';
import { dragWording } from '../dragWording.js';
import {
  useViewMessages,
  type MessageFormatters,
} from '../MessagesProvider.js';
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
import { Input } from '../components/input.js';
import { Tabs, TabsList, TabsTrigger } from '../components/tabs.js';
import { AlertDialogContent, DropdownMenuContent } from '../popups.js';

export interface DashboardTabsProps {
  dashboard: DashboardController;
  /**
   * The board's edits, while it is being built: the bar then adds, renames,
   * reorders and deletes tabs. Left out, it only switches between them.
   */
  editing?: DashboardEditing | null;
  /** Told when the reader picks a tab — the workbench remembers it. */
  onShow?(tabId: string): void;
}

/**
 * A board's tabs (D22 E): a bar over the grid — under the edit bar while the
 * board is built — drawn only when there are two or more; one tab reads as
 * none. The global filter stays above it, since it applies to every tab.
 * Pressing a tab shows it (`DashboardController.showTab`): the grid draws
 * that tab's panels alone, and only they run. The workbench hands it to the
 * board as `DashboardEditExtensions.tabBar`; an embed draws it as the
 * grid's header.
 *
 * While the board is being built the bar is the tabs to arrange: a way to
 * add a tab (on a board with one or none, the only thing on the bar), and
 * for each tab a handle to carry it — its arrows move it too — the press
 * that shows it, and a menu to rename it in place, move it, or delete it; a
 * tab that carries panels is asked about first. A `tablist` holds tabs and
 * nothing else, so that bar is a list, as every sortable list here is.
 * Nothing here writes anything: every edit is the board's draft, and
 * finishing the edit is what saves it.
 */
export function DashboardTabs({
  dashboard,
  editing,
  onShow,
}: DashboardTabsProps) {
  const messages = useViewMessages();
  const { say, region } = useSurfaceAnnouncer('tabs-announcement');
  const { tabs, tab: current } = dashboard;
  const [renaming, setRenaming] = useState<string | null>(null);
  const [confirming, setConfirming] = useState<DashboardTab | null>(null);
  const triggers = useRef(new Map<string, HTMLElement>());
  // Where the keyboard goes once an edit that took it away is done: back to
  // the tab it was about, which is still on the bar. Every such edit
  // changes what is drawn, so the render after it is where it lands.
  const refocusing = useRef<string | null>(null);
  const setRefocus = (tabId: string) => {
    refocusing.current = tabId;
  };
  useEffect(() => {
    const tabId = refocusing.current;
    if (tabId === null) return;
    refocusing.current = null;
    triggers.current.get(tabId)?.focus();
  });

  const titleOf = (tab: DashboardTab, index: number) =>
    tabTitle(tab, index, messages);
  const show = (tabId: string) => {
    if (tabId === current) return;
    dashboard.showTab(tabId);
    onShow?.(tabId);
  };
  const panelsOn = (tabId: string) =>
    dashboard.panels.filter(panel => panel.tab === tabId).length;

  const add = () => {
    if (!editing) return;
    const id = editing.addTab(
      messages.label('label.tabs.new-title', {
        index: Math.max(tabs.length, 1) + 1,
      }),
      messages.label('label.tabs.first-title'),
    );
    if (id === null) return;
    // A new tab is where the author is going: shown, and its name ready to
    // be typed over.
    show(id);
    setRenaming(id);
  };
  const move = (tabId: string, to: number) => {
    if (!editing) return;
    const from = tabs.findIndex(tab => tab.id === tabId);
    if (from < 0 || to < 0 || to >= tabs.length || to === from) return;
    editing.moveTab(tabId, to);
    say(
      messages.label('label.tabs.moved', {
        title: titleOf(tabs[from], from),
        index: to + 1,
        total: tabs.length,
      }),
    );
  };
  const remove = (tab: DashboardTab) => {
    if (!editing) return;
    const index = tabs.findIndex(entry => entry.id === tab.id);
    editing.removeTab(tab.id);
    setConfirming(null);
    say(messages.label('label.tabs.removed', { title: titleOf(tab, index) }));
    // The tab it was is gone; the keyboard lands on the one now shown.
    const next = tabs.find(entry => entry.id !== tab.id);
    if (next) setRefocus(current === tab.id ? next.id : (current ?? next.id));
  };
  const rename = (tabId: string, title: string) => {
    editing?.renameTab(tabId, title);
    setRenaming(null);
    setRefocus(tabId);
  };

  const building = editing != null;
  if (tabs.length < 2)
    return building ? (
      <div data-slot="dashboard-tabs" className="flex items-center">
        <Button variant="ghost" size="sm" onClick={add}>
          <PlusIcon data-icon="inline-start" />
          {messages.label('label.tabs.add')}
        </Button>
        {region}
      </div>
    ) : null;

  const shown = current ?? tabs[0].id;
  const confirmation = (
    <AlertDialog
      open={confirming !== null}
      onOpenChange={open => {
        if (!open) setConfirming(null);
      }}
    >
      <AlertDialogContent data-slot="dashboard-tab-remove">
        {confirming && (
          <TabRemoval
            title={titleOf(
              confirming,
              tabs.findIndex(tab => tab.id === confirming.id),
            )}
            count={panelsOn(confirming.id)}
            onConfirm={() => remove(confirming)}
          />
        )}
      </AlertDialogContent>
    </AlertDialog>
  );

  // Being built, the bar is the tabs to arrange: a list whose rows carry a
  // handle, the press that shows the tab and its menu — a `tablist` may hold
  // tabs and nothing else, so the arranging is a list of its own, as every
  // sortable list in this package is. Read, it is the tabs.
  if (building)
    return (
      <div data-slot="dashboard-tabs" className="flex flex-col gap-3">
        <div className="flex min-w-0 flex-wrap items-center gap-1 border-b pb-1">
          <DragDropProvider
            plugins={defaults =>
              defaults.map(plugin =>
                plugin === Accessibility
                  ? Accessibility.configure(
                      dragAccessibility(
                        dragWording(messages, TAB_DRAG_WORDING),
                        id => {
                          const at = tabs.findIndex(tab => tab.id === id);
                          return at < 0 ? id : titleOf(tabs[at], at);
                        },
                      ),
                    )
                  : plugin,
              )
            }
            onDragEnd={({ operation, canceled }) => {
              const drop = dropped(operation, canceled);
              if (!drop) return;
              move(
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
                  triggerRef={element => {
                    if (element) triggers.current.set(tab.id, element);
                    else triggers.current.delete(tab.id);
                  }}
                  onShow={() => show(tab.id)}
                  onRename={() => setRenaming(tab.id)}
                  onRenamed={title => rename(tab.id, title)}
                  onRenameCancel={() => {
                    setRenaming(null);
                    setRefocus(tab.id);
                  }}
                  onMove={to => move(tab.id, to)}
                  onRemove={() =>
                    panelsOn(tab.id) > 0 ? setConfirming(tab) : remove(tab)
                  }
                />
              ))}
            </ul>
          </DragDropProvider>
          <IconButton
            data-slot="dashboard-tab-add"
            label={messages.label('label.tabs.add')}
            variant="ghost"
            size="icon-sm"
            onClick={add}
          >
            <PlusIcon />
          </IconButton>
        </div>
        {region}
        {confirmation}
      </div>
    );

  return (
    <Tabs
      data-slot="dashboard-tabs"
      value={shown}
      onValueChange={value => {
        if (typeof value === 'string') show(value);
      }}
      className="gap-3"
    >
      <TabsList
        variant="line"
        aria-label={messages.label('label.tabs.name')}
        className="max-w-full flex-wrap"
      >
        {tabs.map((tab, index) => (
          <TabsTrigger
            key={tab.id}
            value={tab.id}
            data-slot="dashboard-tab"
            ref={(element: HTMLElement | null) => {
              if (element) triggers.current.set(tab.id, element);
              else triggers.current.delete(tab.id);
            }}
            className="flex-none"
          >
            {titleOf(tab, index)}
          </TabsTrigger>
        ))}
      </TabsList>
      {region}
    </Tabs>
  );
}

/** What a tab is called on the bar: its title, else its place. */
export function tabTitle(
  tab: DashboardTab,
  index: number,
  messages: MessageFormatters,
): string {
  return tab.title.trim()
    ? tab.title
    : messages.label('label.dashboard.tab.untitled', { index: index + 1 });
}

/** Where the tab bar's drag sentences live in the catalogue. */
const TAB_DRAG_WORDING = {
  instructions: 'label.tabs.instructions',
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
    plugins: defaults =>
      defaults.filter(plugin => plugin !== OptimisticSortingPlugin),
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
        dragging={isDragging}
        onMove={step => onMove(index + step)}
      />
      {renaming ? (
        <TabNameField
          title={tab.title}
          label={messages.label('label.tabs.rename-field', { title })}
          onDone={onRenamed}
          onCancel={onRenameCancel}
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
            <DropdownMenuItem
              disabled={index === 0}
              onClick={() => onMove(index - 1)}
            >
              {messages.label('label.tabs.move-left')}
            </DropdownMenuItem>
            <DropdownMenuItem
              disabled={index === total - 1}
              onClick={() => onMove(index + 1)}
            >
              {messages.label('label.tabs.move-right')}
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

/**
 * A tab's name, typed in place: Enter or leaving the field keeps it, Escape
 * puts the old one back. A blank name keeps the old one too — the kernel
 * refuses it, and a tab is never left without a name to be called by.
 */
function TabNameField({
  title,
  label,
  onDone,
  onCancel,
}: {
  title: string;
  label: string;
  onDone(title: string): void;
  onCancel(): void;
}) {
  const input = useRef<HTMLInputElement | null>(null);
  const done = useRef(false);
  // Taken once the menu that asked for it has let the keyboard go: the
  // menu hands focus back to its trigger as it closes, which is after the
  // press that opened this field.
  useEffect(() => {
    const frame = requestAnimationFrame(() => {
      input.current?.focus();
      input.current?.select();
    });
    return () => cancelAnimationFrame(frame);
  }, []);
  const finish = (keep: boolean) => {
    if (done.current) return;
    done.current = true;
    if (keep) onDone(input.current?.value ?? title);
    else onCancel();
  };
  return (
    <Input
      ref={input}
      data-slot="dashboard-tab-name"
      aria-label={label}
      defaultValue={title}
      className="h-7 w-36"
      onBlur={() => finish(true)}
      onKeyDown={event => {
        if (event.key === 'Enter') {
          event.preventDefault();
          finish(true);
        } else if (event.key === 'Escape') {
          event.preventDefault();
          event.stopPropagation();
          finish(false);
        }
      }}
    />
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
          {count === 1
            ? messages.label('label.tabs.remove-description-one')
            : messages.label('label.tabs.remove-description', { count })}
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

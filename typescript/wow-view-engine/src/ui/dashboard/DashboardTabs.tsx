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

import { useEffect, useRef, useState, type ReactNode } from 'react';
import { PlusIcon } from 'lucide-react';
import type { DashboardTab } from '../../model/index.js';
import type { DashboardController } from '../../react/index.js';
import type { DashboardEditing } from '../../runtime/index.js';
import { useSurfaceAnnouncer } from '../Announcer.js';
import {
  useViewMessages,
  type MessageFormatters,
} from '../MessagesProvider.js';
import { Button } from '../components/button.js';
import { Tabs, TabsList, TabsTrigger } from '../components/tabs.js';
import { EditableTabBar, TabRemovalDialog } from './EditableTabs.js';

export interface DashboardTabsProps {
  dashboard: DashboardController;
  /**
   * The board's edits, while it is being built: the bar then adds, renames,
   * reorders and deletes tabs. Left out, it only switches between them.
   */
  editing?: DashboardEditing | null;
  /** Told when the reader picks a tab — the workbench remembers it. */
  onShow?(tabId: string): void;
  /**
   * A control of the surface's own at the end of the tabs' row, read — an
   * embed's 「铺满屏幕」 when neither a first row nor a filter bar is there
   * to hold it (D10). It is not a tab, so it stands beside the `tablist`.
   */
  end?: ReactNode;
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
  end,
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

  const triggerRef = (tabId: string) => (element: HTMLElement | null) => {
    if (element) triggers.current.set(tabId, element);
    else triggers.current.delete(tabId);
  };
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
    <TabRemovalDialog
      tab={confirming}
      title={
        confirming
          ? titleOf(
              confirming,
              tabs.findIndex(tab => tab.id === confirming.id),
            )
          : ''
      }
      count={confirming ? panelsOn(confirming.id) : 0}
      onConfirm={remove}
      onClose={() => setConfirming(null)}
    />
  );

  // Being built, the bar is the tabs to arrange: a list whose rows carry a
  // handle, the press that shows the tab and its menu — a `tablist` may hold
  // tabs and nothing else, so the arranging is a list of its own, as every
  // sortable list in this package is. Read, it is the tabs.
  if (building)
    return (
      <div data-slot="dashboard-tabs" className="flex flex-col gap-3">
        <EditableTabBar
          tabs={tabs}
          shown={shown}
          renaming={renaming}
          titleOf={titleOf}
          triggerRef={triggerRef}
          onShow={show}
          onRename={setRenaming}
          onRenamed={rename}
          onRenameCancel={tabId => {
            setRenaming(null);
            setRefocus(tabId);
          }}
          onMove={move}
          onRemove={tab =>
            panelsOn(tab.id) > 0 ? setConfirming(tab) : remove(tab)
          }
          onAdd={add}
        />
        {region}
        {confirmation}
      </div>
    );

  const list = (
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
          ref={triggerRef(tab.id)}
          className="flex-none"
        >
          {titleOf(tab, index)}
        </TabsTrigger>
      ))}
    </TabsList>
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
      {end ? (
        <div className="flex items-center gap-2">
          {list}
          <div className="ml-auto flex shrink-0 items-center gap-2">{end}</div>
        </div>
      ) : (
        list
      )}
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

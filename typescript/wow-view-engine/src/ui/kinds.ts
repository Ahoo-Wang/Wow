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
  InboxIcon,
  LayoutDashboardIcon,
  LockIcon,
  SigmaIcon,
  UserIcon,
  UsersIcon,
} from 'lucide-react';
import { createContext, useContext } from 'react';
import type { ViewAudience, ViewKind } from '../model/index.js';
import type { MessageKey } from './messages.js';

/**
 * The face each kind and each audience wears.
 *
 * The sidebar, the title bar and the manager all draw the same view, so the
 * mapping lives once: a kind that looked like an inbox in the list and like
 * something else in the header would read as two different things.
 */
export const KIND_ICON: Record<ViewKind, typeof InboxIcon> = {
  record: InboxIcon,
  analysis: SigmaIcon,
  dashboard: LayoutDashboardIcon,
};

/**
 * Who a view is for: one person or several. A system view is a shared view —
 * `audienceOf` says so — and where it came from is `SYSTEM_ICON`'s to say.
 *
 * Not a lock for "personal": a lock is what a system view wears, because the
 * one thing a reader has to know about it is that it cannot be renamed or
 * deleted, and one glyph meaning "only mine" in the title bar and "cannot be
 * changed" in the list is a picture with two readings.
 */
export const AUDIENCE_ICON: Record<ViewAudience, typeof UserIcon> = {
  personal: UserIcon,
  shared: UsersIcon,
};

/**
 * A view that came with the definition: it travels with the code, so it is
 * never renamed or deleted here, and a lock is that fact as a picture.
 */
export const SYSTEM_ICON = LockIcon;

/**
 * The one kind of view a surface has open, where it has one: a dashboard
 * workbench or an embedded board says `dashboard`, a workbench of several
 * kinds says nothing. It is what the chrome around the view calls the thing
 * it makes, saves and deletes (`useKindWord`).
 */
export const SurfaceKind = createContext<ViewKind | undefined>(undefined);

/**
 * What a dashboard says where any other view says 视图 (D26 Q34): new,
 * save, the shared-save question, delete, the manager, the sidebar's three
 * groups and the line an unopenable one gets. Only the chrome
 * that names the thing open reads through it — a board's panels still show
 * views, and the picker, 另存为视图 and the rest say so in keys of their own.
 */
const DASHBOARD_WORDS: Partial<Record<MessageKey, MessageKey>> = {
  'label.view.new': 'label.dashboard.new',
  'label.view.none': 'label.dashboard.none',
  'label.view.unopenable': 'label.dashboard.unopenable',
  'label.view.open-default': 'label.dashboard.open-default',
  'label.save.group': 'label.dashboard.save-group',
  'label.save.saved-announce': 'label.dashboard.saved-announce',
  'label.save.shared-heading': 'label.dashboard.shared-heading',
  'label.save.first-heading': 'label.dashboard.first-heading',
  'label.save-as.heading': 'label.dashboard.save-as.heading',
  'label.save-as.description': 'label.dashboard.save-as.description',
  'label.save-as.submit': 'label.dashboard.save-as.submit',
  'label.delete.consequence': 'label.dashboard.delete-consequence',
  'label.manage.open': 'label.dashboard.manage',
  'label.manage.heading': 'label.dashboard.manage',
  'label.manage.description': 'label.dashboard.manage-description',
  'label.scope.group.personal': 'label.dashboard.group.personal',
  'label.scope.group.shared': 'label.dashboard.group.shared',
  'label.scope.group.system': 'label.dashboard.group.system',
  'label.header.more': 'label.dashboard.more',
  'label.leave.heading': 'label.dashboard.leave-heading',
  'label.refresh.on': 'label.dashboard.refresh-on',
  'label.view.list': 'label.dashboard.list',
  'label.manage.view-group': 'label.dashboard.manage-group',
  'label.manage.instructions': 'label.dashboard.manage-instructions',
  'label.workbench.collapse-sidebar': 'label.dashboard.collapse-sidebar',
  'label.workbench.expand-sidebar': 'label.dashboard.expand-sidebar',
  'label.workbench.switch-view': 'label.dashboard.switch',
  'label.workbench.choose-view': 'label.dashboard.choose',
  'label.workbench.opening': 'label.dashboard.opening',
  'label.write.conflict': 'label.dashboard.write-conflict',
  'label.scope.refused': 'label.dashboard.scope-refused',
  'label.render.failed-hint': 'label.dashboard.render-hint',
  'label.view.needs-fixing': 'label.dashboard.needs-fixing',
};

/** The key a kind says `key` with: its own where it has one. */
export function kindWord(
  key: MessageKey,
  kind: ViewKind | undefined,
): MessageKey {
  return kind === 'dashboard' ? (DASHBOARD_WORDS[key] ?? key) : key;
}

/** `kindWord` for the kind the surface has open (`SurfaceKind`). */
export function useKindWord(): (key: MessageKey) => MessageKey {
  const kind = useContext(SurfaceKind);
  return key => kindWord(key, kind);
}

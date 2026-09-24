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
import type { Issue, ViewAudience, ViewKind } from '../model/index.js';
import { defaultMessages, type MessageKey } from './messages.js';

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
 * groups and the line an unopenable one gets — and what the engine reports
 * about the thing open, by code (`kindIssue`). Only the chrome
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
  // What the engine reports about the thing open (`kindIssue`), by the entry
  // its code reads. Only the entries whose sentence names a view: the rest
  // say a write, a server or a definition, and read the same on a board.
  'view.config.invalid': 'label.dashboard.config-invalid',
  'view.create.forbidden': 'label.dashboard.create-forbidden',
  'view.delete.failed': 'label.dashboard.delete-failed',
  'view.delete.forbidden': 'label.dashboard.delete-forbidden',
  'view.list.failed': 'label.dashboard.list-failed',
  'view.list.failed.unavailable': 'label.dashboard.list-unavailable',
  'view.list.reserved-id': 'label.dashboard.reserved-id',
  'view.change.notify-failed': 'label.dashboard.notify-failed',
  'view.open.failed': 'label.dashboard.open-failed',
  'view.open.not-found': 'label.dashboard.not-found',
  'view.open.wrong-kind': 'label.dashboard.wrong-kind',
  'view.open.failed.not_found': 'label.dashboard.gone',
  'view.open.failed.forbidden': 'label.dashboard.open-forbidden',
  'view.open.failed.unavailable': 'label.dashboard.open-unavailable',
  'view.preferences.default-forbidden': 'label.dashboard.default-forbidden',
  'view.preferences.failed': 'label.dashboard.preferences-failed',
  'view.preferences.load-failed': 'label.dashboard.preferences-load-failed',
  'view.preferences.reorder-forbidden': 'label.dashboard.reorder-forbidden',
  'view.rename.failed': 'label.dashboard.rename-failed',
  'view.rename.forbidden': 'label.dashboard.rename-forbidden',
  'view.runtime.not-owned': 'label.dashboard.not-open',
  'view.save-as.failed': 'label.dashboard.save-as-failed',
  'view.save.failed': 'label.dashboard.save-failed',
  'view.save.forbidden': 'label.dashboard.save-forbidden',
  'view.system.read-only': 'label.dashboard.system-read-only',
  'view.title.empty': 'label.dashboard.title-empty',
  'view.write.conflict': 'label.dashboard.write-conflict',
  'view.write.forbidden': 'label.dashboard.write-forbidden',
  'view.write.in-flight': 'label.dashboard.write-in-flight',
  'view.write.not_found': 'label.dashboard.gone',
  // The part of a config every kind stores, judged for the board as for
  // any view (a board has no conditions of its own, D27, so the two about
  // them never reach one); and a definition that offers no board at all.
  'config.invalid': 'label.dashboard.unreadable',
  'config.refresh.missing': 'label.dashboard.refresh-missing',
  'runtime.kind.not-declared': 'label.dashboard.not-declared',
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

/**
 * An issue about the thing open, said as its kind says it: 「仪表盘保存失败」
 * on a board where a view says 「视图保存失败」.
 *
 * The engine raises the same code for every kind — the write, the list and
 * the opening are one protocol — so the choice is the surface's, as it is
 * for a label (`kindWord`). A code reads the entry it falls back to
 * (`formatMessage`: the code itself, else its longest prefix the catalogue
 * ships), so `view.save.failed.unavailable` reads as `view.save.failed`
 * does, and that entry's own word stands in. Only the chrome that reports
 * on the thing open asks: a panel's view, the picker's list and a board's
 * analysis promoted to a view are views, and are said so.
 */
export function kindIssue(found: Issue, kind: ViewKind | undefined): Issue {
  if (kind !== 'dashboard') return found;
  const entry = shippedEntry(found.code);
  const own = entry === undefined ? undefined : DASHBOARD_WORDS[entry];
  return own === undefined ? found : { ...found, code: own };
}

/** `kindIssue` for the kind the surface has open (`SurfaceKind`). */
export function useKindIssue(): (found: Issue) => Issue {
  const kind = useContext(SurfaceKind);
  return found => kindIssue(found, kind);
}

/** The shipped entry a code is worded by, walking back along its dots. */
function shippedEntry(code: string): MessageKey | undefined {
  for (let candidate = code; ;) {
    if (isShipped(candidate)) return candidate;
    const cut = candidate.lastIndexOf('.');
    if (cut < 0) return undefined;
    candidate = candidate.slice(0, cut);
  }
}

function isShipped(key: string): key is MessageKey {
  return Object.prototype.hasOwnProperty.call(defaultMessages, key);
}

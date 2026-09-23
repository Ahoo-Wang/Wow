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
import type { ViewAudience, ViewKind } from '../model/index.js';

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

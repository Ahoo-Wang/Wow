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
 * Who a view is for. A system view is a shared view — `audienceOf` says so —
 * so it wears the shared face and its tag says where it came from.
 */
export const AUDIENCE_ICON: Record<ViewAudience, typeof LockIcon> = {
  personal: LockIcon,
  shared: UsersIcon,
};

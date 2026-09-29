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
 * What a host binds to a resource (host-integration.md 4, D67): the
 * behaviour the headless core cannot hold — where a view of it lives in the
 * host's address, how one of its records is read, and, until H3's
 * declarative actions, the host's commands on its records. Registered once
 * on `ViewHost`, by the definition's id, and found by every
 * surface that draws that definition: its workbench, a board's record
 * panel, an embed. The host writes no `definition.id ===` branch.
 */

import type { RoutedTarget } from '../runtime/routes.js';
import type { BulkCommand, RecordActionSlots } from '../react/index.js';
import type { RecordDetailOptions } from './workbench/RecordParts.js';

export interface ViewBindingOptions {
  /**
   * Where a view or a board of this definition lives in the host's
   * address: the path of the page that opens `instanceId` — `null` for a
   * view nobody saved (a follow-up on a group, a board's own analysis),
   * which opens on the page's default and is handed over whole. The engine
   * hands the host's router (or `navigate`) the path with what the page
   * opens with (`ViewRoute.state`); a definition with no route is handed
   * over raw. `target` is absent where the engine asks for a link rather
   * than a way off: the resource's place in `useViewNavigation`.
   */
  route?(instanceId: string | null, target?: RoutedTarget): string;
  /**
   * How one of its records is read (D60): the detail's own reading of the
   * record, its title, its sections, who holds which record is open — what
   * `DataWorkbench` takes as `record.detail`, and what an `EmbeddedView`'s
   * `detail` opens with. A surface's own prop wins.
   */
  reading?: RecordDetailOptions;
  /**
   * The host's commands on its records — a row's, a selection's, the
   * view's — wherever its records are drawn: the workbench, and every
   * record panel of a board over it (D39). Today's slots; H3 declares them
   * instead (host-integration.md 5), and a surface's own prop wins.
   */
  actions?: RecordActionSlots;
  /** The bulk command whose line the record views over it say (`useBulkCommand`). */
  bulk?: BulkCommand;
}

/** One definition's binding, as `ViewHost` takes it. */
export interface ViewBinding extends ViewBindingOptions {
  readonly definitionId: string;
}

/** Binds a definition's behaviour in the host (host-integration.md 4). */
export function bind(
  definitionId: string,
  options: ViewBindingOptions = {},
): ViewBinding {
  return { ...options, definitionId };
}

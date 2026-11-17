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

import { useState, type ReactNode } from 'react';
import {
  isBlankLeafValue,
  isFilterLeaf,
  walkFilter,
  type FieldKindRegistry,
} from '../../filter/index.js';
import type { FieldDefinition, ViewConfig } from '../../model/index.js';

/**
 * The editor's fold, which belongs to one opening of one view.
 *
 * The state is tagged with the runtime it was made for rather than reset by
 * an effect: switching views re-reads the default in the same render that
 * shows the new view, so the band is never briefly the previous view's.
 */
export function useEditorFold({
  controlled,
  fallback,
  runtimeId,
  onChange,
}: {
  controlled: boolean | undefined;
  fallback: boolean;
  runtimeId: string | null;
  onChange?(open: boolean): void;
}): { open: boolean; set(open: boolean): void } {
  const [held, setHeld] = useState<{ id: string | null; open: boolean } | null>(
    null,
  );
  const mine = held !== null && held.id === runtimeId;
  return {
    open: controlled ?? (mine ? held.open : fallback),
    set(open) {
      setHeld({ id: runtimeId, open });
      onChange?.(open);
    },
  };
}

/**
 * Whether a saved view was left with a condition for its reader to fill in:
 * a leaf anywhere in its tree that `isBlankLeafValue` reads as blank. Its
 * editor opens out (#6297). A saved view folds because its author already
 * decided, and an author who left a condition without a value decided the
 * reader says what it compares to — folded, the condition is nowhere on
 * screen, since a blank one is neither in the query nor in the applied bar.
 *
 * It reads the saved baseline, not the draft, so filling the condition in
 * does not fold the editor under the reader's hands. A config without a tree
 * (a dashboard's) and a registry that is not there yet leave nothing blank.
 */
export function leftBlank(
  config: ViewConfig,
  fields: readonly FieldDefinition[],
  kinds: FieldKindRegistry | undefined,
): boolean {
  if (kinds === undefined || !('filter' in config)) return false;
  for (const { node } of walkFilter(config.filter)) {
    if (!isFilterLeaf(node)) continue;
    const field = fields.find(candidate => candidate.name === node.field);
    const kind = field && kinds.get(field.kind);
    if (
      field &&
      kind &&
      isBlankLeafValue(node.value, node.operator, field, kind, kinds)
    )
      return true;
  }
  return false;
}

/**
 * Whether a slot was given something that will draw.
 *
 * A workbench fills a slot with `condition && <Thing/>`, so an unfilled one
 * arrives as `false` rather than as nothing at all — and a block that
 * counted it as content would be the empty card the layout rule forbids.
 */
export function filled(slot: ReactNode): boolean {
  return slot !== null && slot !== undefined && slot !== false;
}

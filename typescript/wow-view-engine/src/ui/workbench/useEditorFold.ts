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
 * Whether a slot was given something that will draw.
 *
 * A workbench fills a slot with `condition && <Thing/>`, so an unfilled one
 * arrives as `false` rather than as nothing at all — and a block that
 * counted it as content would be the empty card the layout rule forbids.
 */
export function filled(slot: ReactNode): boolean {
  return slot !== null && slot !== undefined && slot !== false;
}

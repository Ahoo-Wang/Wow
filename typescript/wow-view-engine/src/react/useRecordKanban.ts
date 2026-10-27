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

import { useCallback, useMemo, useState } from 'react';
import {
  MAX_KANBAN_LANES,
  type DataViewDefinition,
  type FieldDefinition,
  type FieldOption,
  type RecordKanbanSpec,
  type RecordViewConfig,
} from '../model/index.js';
import {
  defaultKanbanSpec,
  laneFields,
  type RecordLaneView,
} from '../record/index.js';
import type { RecordViewRuntime, ViewResult } from '../runtime/index.js';
import { pendingBesides } from '../runtime/pending.js';
import { useViewRuntime } from './useViewEngine.js';

/**
 * A kanban as its lanes render it, beside the table controller: the
 * cards, rows, status, detail and row actions are that controller's, and
 * this is only what lanes add.
 */
export interface RecordKanbanController {
  /** The lanes of the result on screen; empty when it is no kanban's. */
  lanes: readonly RecordLaneView[];
  /** The lane whose next page is on its way, or `null`. */
  loadingLane: string | null;
  /** Asks for one lane's next page (`RecordViewRuntime.loadLane`). */
  loadLane(laneId: string): void;
  /** The draft's kanban half, or `null` when it has none. */
  spec: RecordKanbanSpec | null;
  /**
   * Edits the kanban half and runs it while nothing else waits, like
   * `setCard`. A new lane field starts with its first `maxLanes` lanes
   * shown, unless the same write says which to fold.
   */
  setKanban(patch: Partial<RecordKanbanSpec>): void;
  /** The fields lanes can be laid out by, in the definition's order. */
  laneFields: readonly FieldDefinition[];
  /** Every option of the draft's lane field, folded ones included. */
  laneOptions: readonly FieldOption[];
  /** How many lanes may be shown at once (`MAX_KANBAN_LANES`). */
  maxLanes: number;
}

const NO_LANES: readonly RecordLaneView[] = [];
const NO_FIELDS: readonly FieldDefinition[] = [];
const NO_OPTIONS: readonly FieldOption[] = [];

/** Which lane was asked for more, over which result. */
interface Asked {
  laneId: string;
  result: ViewResult<RecordViewConfig> | null;
}

export function useRecordKanban(
  runtime: RecordViewRuntime | null,
): RecordKanbanController {
  const state = useViewRuntime(runtime);
  const data = state?.result?.data;
  const lanes =
    data?.kind === 'record' ? (data.view.lanes ?? NO_LANES) : NO_LANES;
  const definition =
    runtime?.definition.kind === 'data' ? runtime.definition : undefined;
  const fields = useMemo(
    () => (definition ? laneFields(definition) : NO_FIELDS),
    [definition],
  );
  const spec = state?.draft.kanban ?? null;
  const laneOptions = useMemo(
    () =>
      fields.find(field => field.name === spec?.lane)?.options ?? NO_OPTIONS,
    [fields, spec?.lane],
  );

  // The lane asked for more stays "loading" while the quiet request it
  // started is the one out: the result it asked over is still the one on
  // screen and the query has not turned to anything else. The answer
  // landing, a failure or a whole query each end it on their own.
  const [asked, setAsked] = useState<Asked | null>(null);
  const loadingLane =
    asked && state?.result === asked.result && state.query.status === 'success'
      ? asked.laneId
      : null;

  const loadLane = useCallback(
    (laneId: string) => {
      if (!runtime) return;
      const before = runtime.getSnapshot();
      runtime.loadLane(laneId);
      const after = runtime.getSnapshot();
      // Only a lane the runtime really asked for: one with nothing more, or
      // a result not yet settled, is a no-op, and would spin for nothing.
      if (after.query.requestId !== before.query.requestId)
        setAsked({ laneId, result: after.result });
    },
    [runtime],
  );

  const setKanban = useCallback(
    (patch: Partial<RecordKanbanSpec>) => {
      if (!runtime || !definition) return;
      const snapshot = runtime.getSnapshot();
      const kanban = editedSpec(definition, snapshot.draft.kanban, patch);
      if (!kanban) return;
      const others = pendingBesides(snapshot, { kanban });
      runtime.edit({ kanban });
      if (!others) runtime.apply();
    },
    [definition, runtime],
  );

  return {
    lanes,
    loadingLane,
    loadLane,
    spec,
    setKanban,
    laneFields: fields,
    laneOptions,
    maxLanes: MAX_KANBAN_LANES,
  };
}

/**
 * The kanban half after one edit. A lane field changed without saying what
 * to fold folds what the default would for that field; folding nothing
 * deletes `hidden` rather than writing `[]`, so a lane put back is the same
 * JSON the view started as.
 */
function editedSpec(
  definition: DataViewDefinition,
  current: RecordKanbanSpec | undefined,
  patch: Partial<RecordKanbanSpec>,
): RecordKanbanSpec | undefined {
  const base = current ?? defaultKanbanSpec(definition);
  if (!base) return undefined;
  const lane = patch.lane ?? base.lane;
  const hidden =
    patch.hidden ??
    (lane === base.lane
      ? base.hidden
      : defaultKanbanSpec({
          ...definition,
          fields: definition.fields.filter(field => field.name === lane),
        })?.hidden);
  const { hidden: _dropped, ...rest } = { ...base, ...patch, lane };
  void _dropped;
  return hidden && hidden.length > 0 ? { ...rest, hidden } : rest;
}

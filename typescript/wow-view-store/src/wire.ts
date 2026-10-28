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

/*
 * The V10 view store's shapes, as far as the store reads them, and their
 * reading as the port's. The server stores the engine's `ViewConfig` whole
 * and never reads its meaning; it keeps the config's `kind` beside it, as
 * `state.kind`, which a list projects and filters on.
 */

import type { TargetScope } from '@ahoo-wang/wow-client';
import type {
  DatasetOverrides,
  ViewConfig,
  ViewInstance,
  ViewInstanceSummary,
} from '@ahoo-wang/wow-view-engine';
import { revisionOf } from './views.js';
import {
  SHARED_OWNER_ID,
  SYSTEM_OWNER_ID,
  SYSTEM_TENANT_ID,
  type Place,
} from './paths.js';
import type {
  DatasetWordingState,
  ViewState,
} from './generated/view-store/types.js';

/** The `Wow-Scope` of the shared views: the reserved owner `(shared)`. */
export const SHARED_SCOPE: TargetScope = Object.freeze({
  ownerId: SHARED_OWNER_ID,
});

/** The `Wow-Scope` of the stored system views: `(system)` of `(platform)`. */
export const SYSTEM_SCOPE: TargetScope = Object.freeze({
  tenantId: SYSTEM_TENANT_ID,
  ownerId: SYSTEM_OWNER_ID,
});

/**
 * The `Wow-Scope` a request at `place` carries: none for the caller's own
 * views, whose owner the server takes from the principal.
 */
export function scopeAt(place: Place): TargetScope | undefined {
  switch (place) {
    case 'shared':
      return SHARED_SCOPE;
    case 'system':
      return SYSTEM_SCOPE;
    default:
      return undefined;
  }
}

/** A view as a list reads it: its id, version and the fields of a summary. */
export interface ViewSummaryRecord {
  id: string;
  version: number;
  state: Pick<ViewState, 'definitionId' | 'title' | 'audience' | 'kind'>;
}

/** A view read whole: a summary's fields and its config. */
export interface ViewRecord extends ViewSummaryRecord {
  state: ViewSummaryRecord['state'] & Pick<ViewState, 'config'>;
}

/** The fields a list reads, and of the config only its `kind`, kept apart. */
export const SUMMARY_FIELDS = [
  'aggregateId',
  'version',
  'state.definitionId',
  'state.title',
  'state.audience',
  'state.kind',
] as const;

export function toInstance({ id, version, state }: ViewRecord): ViewInstance {
  return {
    id,
    definitionId: state.definitionId,
    title: state.title,
    scope: state.audience,
    revision: revisionOf(version),
    config: state.config as unknown as ViewConfig,
  };
}

export function toSummary({
  id,
  version,
  state,
}: ViewSummaryRecord): ViewInstanceSummary {
  return {
    id,
    definitionId: state.definitionId,
    title: state.title,
    scope: state.audience,
    kind: state.kind,
    revision: revisionOf(version),
  };
}

/** The engine's overrides from a stored wording: what is stored, nothing more. */
export function wordingOf(
  state: Pick<DatasetWordingState, 'title' | 'recordNoun' | 'fields'>,
): DatasetOverrides {
  return {
    ...(state.title == null ? {} : { title: state.title }),
    ...(state.recordNoun == null ? {} : { recordNoun: state.recordNoun }),
    ...(state.fields == null
      ? {}
      : { fields: state.fields as DatasetOverrides['fields'] }),
  };
}

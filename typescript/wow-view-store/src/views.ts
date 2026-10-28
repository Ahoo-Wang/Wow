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
 * The server's shapes, as far as the store reads them, and their reading as
 * the port's. The server stores the engine's `ViewConfig` whole and never
 * reads its meaning, so a config comes back exactly as it was saved.
 */

import type {
  SystemView,
  ViewEntry,
  ViewPreferencesState,
} from './generated/view-store/types.js';
import type {
  ViewConfig,
  ViewInstance,
  ViewInstanceSummary,
  ViewPreferences,
} from '@ahoo-wang/wow-view-engine';

/**
 * Whether the server stores system view `view` (else it configures it; a
 * saved view has no source). A stored one carries the port's
 * `stored: true`, the views an `editSystem` permission may write;
 * configured and code system views carry none.
 */
export function isStored(view: SystemView | ViewEntry): boolean {
  return view.source === 'stored' && typeof view.version === 'number';
}

/**
 * One owner's preferences in one definition: the generated
 * `ViewPreferencesState`, and the aggregate version they were read at.
 */
export type PreferencesBody = Pick<
  ViewPreferencesState,
  'definitionId' | 'order' | 'defaultInstanceId' | 'autoRun' | 'lastTabs'
> & { version: number };

/** A snapshot's revision: the aggregate's version, compared as a string. */
export function revisionOf(version: number): string {
  return String(version);
}

/**
 * A view read by id as the port's: a saved view's revision is its version as
 * text, a system view's its content hash, either as the server answers it.
 */
export function viewInstance(view: ViewEntry): ViewInstance {
  return {
    id: view.id,
    definitionId: view.definitionId,
    title: view.title,
    scope: view.scope,
    revision: view.revision,
    config: view.config as unknown as ViewConfig,
    ...(isStored(view) ? { stored: true as const } : {}),
  };
}

export function systemSummary(view: SystemView): ViewInstanceSummary {
  return {
    id: view.id,
    definitionId: view.definitionId,
    title: view.title,
    scope: 'system',
    kind: view.kind,
    revision: view.revision,
    ...(isStored(view) ? { stored: true as const } : {}),
  };
}

/**
 * The port's preferences. A member the server holds as `null` is one never
 * set: `defaultInstanceId` reads as `null`, and `autoRun` and `lastTabs` are
 * left out, as the port's own `emptyPreferences()` leaves them.
 */
export function toPreferences(body: PreferencesBody): ViewPreferences {
  return preferencesAt(
    {
      order: body.order,
      defaultInstanceId: body.defaultInstanceId ?? null,
      autoRun: body.autoRun ?? undefined,
      lastTabs: body.lastTabs ?? undefined,
    },
    body.version,
  );
}

/** What a preferences write sends: everything but the revision. */
export function preferencesInput(
  preferences: Omit<ViewPreferences, 'revision'>,
): Omit<ViewPreferences, 'revision'> {
  const { order, defaultInstanceId, autoRun, lastTabs } = preferences;
  return {
    order: [...order],
    defaultInstanceId: defaultInstanceId ?? null,
    ...(autoRun === undefined ? {} : { autoRun }),
    ...(lastTabs === undefined ? {} : { lastTabs: { ...lastTabs } }),
  };
}

/** `preferences` as written at `version`, in the port's shape. */
export function preferencesAt(
  preferences: Omit<ViewPreferences, 'revision'>,
  version: number,
): ViewPreferences {
  return { ...preferencesInput(preferences), revision: revisionOf(version) };
}

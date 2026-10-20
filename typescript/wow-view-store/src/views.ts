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
  ViewAudience,
  ViewConfig,
  ViewInstance,
  ViewInstanceSummary,
  ViewKind,
  ViewPreferences,
} from '@ahoo-wang/wow-view-engine';

/** A system view the server serves (`SystemView`); `scope` is always `system`. */
export interface SystemViewBody {
  id: string;
  definitionId: string;
  title: string;
  kind: ViewKind;
  /** A hash of its content, whatever its source. */
  revision: string;
  config: ViewConfig;
  /**
   * `configured` (read-only) or `stored` (a view of `tenant/(platform)/owner/(system)`,
   * written through the view routes); absent from a server before it,
   * which served configured views only.
   */
  source?: 'configured' | 'stored';
  /** A stored view's aggregate version, which its writes expect. */
  version?: number | null;
}

/**
 * Whether the server stores `view` (else it configures it). A stored one
 * carries the port's `stored: true` (D81), the views an `editSystem`
 * permission may write; configured and code system views carry none.
 */
export function isStored(view: SystemViewBody): boolean {
  return view.source === 'stored' && typeof view.version === 'number';
}

/** One owner's preferences in one definition (`ViewPreferencesView`). */
export interface PreferencesBody {
  definitionId: string;
  order?: string[] | null;
  defaultInstanceId?: string | null;
  autoRun?: boolean | null;
  lastTabs?: Record<string, string> | null;
  /** `0` for preferences never written. */
  version: number;
}

/** A snapshot's revision: the aggregate's version, compared as a string. */
export function revisionOf(version: number): string {
  return String(version);
}

export function systemInstance(view: SystemViewBody): ViewInstance {
  return {
    id: view.id,
    definitionId: view.definitionId,
    title: view.title,
    scope: 'system',
    revision: view.revision,
    config: view.config,
    ...(isStored(view) ? { stored: true as const } : {}),
  };
}

export function systemSummary(view: SystemViewBody): ViewInstanceSummary {
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
      order: body.order ?? [],
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

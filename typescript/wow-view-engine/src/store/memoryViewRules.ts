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
 * The view store service's refusals as `MemoryViewStore` answers them
 * (internal): the checks the service makes of a view's definition id,
 * title, config and preferences before a write reaches its aggregate
 * (`ViewConfigs`, `ViewPreferences.problemOf` of `platform/view-store`), in
 * its words, each as an `INVALID` `ViewStoreError`, which panels the
 * service reads as showing a view, the refusal of a call whose signal is
 * aborted as `WowViewStore` answers one, and the store's own reading of its
 * list cursors and revisions.
 */

import {
  configBytes,
  MAX_VIEW_CONFIG_BYTES,
  MAX_VIEW_TITLE_LENGTH,
  titleProblem,
  VIEW_KINDS,
  ViewStoreError,
  type ViewConfig,
  type ViewInstance,
  type ViewPreferences,
} from '../model/index.js';

/**
 * The longest definition id, panel reference and preferences id: the
 * service's `ViewConfigs.MAX_ID_LENGTH`, which every store indexes whole.
 */
export const MAX_VIEW_ID_LENGTH = 256;

/** The most panels a config holds: the service's `ViewConfigs.MAX_PANELS`. */
export const MAX_VIEW_PANELS = 1000;

/** Where a panel references a saved view: the service's `ViewConfigs.PANEL_REFERENCES`. */
const PANEL_REFERENCES = ['instanceId', 'opens', 'click.instanceId'] as const;

function invalid(message: string): ViewStoreError {
  return new ViewStoreError('INVALID', message);
}

/**
 * The refusal of a call whose signal is aborted, read or write, before it
 * does anything. `WowViewStore` hands the signal to fetch, so such a call
 * never reaches the service, and answers it as it answers any request that
 * reached nothing: `UNAVAILABLE`, the outcome unknown. Its `cause` here is
 * the signal's reason; `WowViewStore`'s is what its request threw, which
 * the port does not promise. A replayed `requestId` is refused alike: it
 * was never sent.
 */
export function abortRefusal(
  signal: AbortSignal | undefined,
): ViewStoreError | undefined {
  if (signal?.aborted !== true) return undefined;
  const reason: unknown = signal.reason;
  return new ViewStoreError(
    'UNAVAILABLE',
    `The view store could not be reached: ${reasonOf(reason)}`,
    { cause: reason },
  );
}

/** What a refusal said — a `DOMException` is an `Error` only in some realms. */
export function reasonOf(error: unknown): string {
  if (typeof error === 'object' && error !== null) {
    const { name, message } = error as { name?: unknown; message?: unknown };
    if (typeof message === 'string')
      return typeof name === 'string' ? `${name}: ${message}` : message;
  }
  return String(error);
}

/**
 * Whether a stored panel references the saved view `id`, by any of the
 * three members the server reads (`ViewConfigs.PANEL_REFERENCES`, and
 * `DashboardPanel`): the view it shows (`instanceId`, unless the board owns
 * the view, `owned`), the view 「在工作台中打开」 opens (`opens`), and the
 * one a press opens (`click.instanceId`).
 */
export function shows(panel: unknown, id: string): boolean {
  if (typeof panel !== 'object' || panel === null) return false;
  const { instanceId, owned, opens, click } = panel as {
    instanceId?: unknown;
    owned?: unknown;
    opens?: unknown;
    click?: unknown;
  };
  if (owned === undefined && instanceId === id) return true;
  if (opens === id) return true;
  return (
    typeof click === 'object' &&
    click !== null &&
    (click as { instanceId?: unknown }).instanceId === id
  );
}

/** A definition id the service refuses (`ViewConfigs.definitionProblem`). */
export function definitionRefusal(
  definitionId: string,
): ViewStoreError | undefined {
  if (definitionId.trim() === '')
    return invalid("A view's definitionId must not be blank.");
  return definitionId.length > MAX_VIEW_ID_LENGTH
    ? invalid(
        `A view's definitionId is longer than ${MAX_VIEW_ID_LENGTH} characters.`,
      )
    : undefined;
}

/** A title the service refuses (`ViewConfigs.titleProblem`). */
export function titleRefusal(title: string): ViewStoreError | undefined {
  switch (titleProblem(title)) {
    case 'empty':
      return invalid("A view's title must not be blank.");
    case 'too-long':
      return invalid(
        `A view's title is longer than ${MAX_VIEW_TITLE_LENGTH} characters.`,
      );
    default:
      return undefined;
  }
}

/**
 * A config the service refuses (`ViewConfigs.problemOf`): one whose `kind`
 * names no view kind, one larger than it keeps, and one whose panels it
 * cannot index (not an array of at most `MAX_VIEW_PANELS` objects, or a
 * reference that is no string of at most `MAX_VIEW_ID_LENGTH` characters).
 */
export function configRefusal(config: ViewConfig): ViewStoreError | undefined {
  const kind: unknown = (config as { kind?: unknown }).kind;
  if (!(VIEW_KINDS as readonly unknown[]).includes(kind))
    return invalid(
      `A view's config kind must be one of [${VIEW_KINDS.join(', ')}].`,
    );
  const bytes = configBytes(config);
  if (bytes > MAX_VIEW_CONFIG_BYTES)
    return invalid(
      `A view's config is ${bytes} bytes; the limit is ${MAX_VIEW_CONFIG_BYTES}.`,
    );
  return panelsRefusal((config as { panels?: unknown }).panels);
}

function panelsRefusal(panels: unknown): ViewStoreError | undefined {
  if (panels === undefined || panels === null) return undefined;
  if (!Array.isArray(panels))
    return invalid("A view's config panels must be an array.");
  if (panels.length > MAX_VIEW_PANELS)
    return invalid(
      `A view's config holds ${panels.length} panels; the limit is ${MAX_VIEW_PANELS}.`,
    );
  for (const panel of panels as unknown[]) {
    if (typeof panel !== 'object' || panel === null || Array.isArray(panel))
      return invalid("Each of a view's config panels must be an object.");
    for (const path of PANEL_REFERENCES) {
      const refused = referenceRefusal(panel, path);
      if (refused) return refused;
    }
  }
  return undefined;
}

function referenceRefusal(
  panel: object,
  path: string,
): ViewStoreError | undefined {
  let node: unknown = panel;
  const segments = path.split('.');
  for (const [index, segment] of segments.entries()) {
    const value: unknown = (node as Record<string, unknown>)[segment];
    if (value === undefined || value === null) return undefined;
    if (index === segments.length - 1) {
      return typeof value !== 'string' || value.length > MAX_VIEW_ID_LENGTH
        ? invalid(
            `A panel's ${path} must be a string of at most ${MAX_VIEW_ID_LENGTH} characters.`,
          )
        : undefined;
    }
    if (typeof value !== 'object' || Array.isArray(value))
      return invalid(`A panel's ${segment} must be an object.`);
    node = value;
  }
  return undefined;
}

/**
 * Preferences the service refuses (`ViewPreferences.problemOf`): a blank
 * definition id, or an id of theirs longer than it indexes.
 */
export function preferencesRefusal(
  definitionId: string,
  preferences: ViewPreferences,
): ViewStoreError | undefined {
  if (definitionId.trim() === '')
    return invalid("The preferences' definitionId must not be blank.");
  const ids: [string, readonly string[]][] = [
    ['definitionId', [definitionId]],
    ['order', preferences.order],
    ...(preferences.defaultInstanceId === null
      ? []
      : ([['defaultInstanceId', [preferences.defaultInstanceId]]] as [
          string,
          readonly string[],
        ][])),
  ];
  const long = ids.find(([, values]) =>
    values.some(value => value.length > MAX_VIEW_ID_LENGTH),
  );
  return long
    ? invalid(
        `The preferences' ${long[0]} holds an id longer than ${MAX_VIEW_ID_LENGTH} characters.`,
      )
    : undefined;
}

/**
 * Whether `asked` sets nothing `held` does not hold already: the service
 * records no event for it (`ViewPreferences.onSet`), so its revision stays.
 */
export function samePreferences(
  held: ViewPreferences,
  asked: ViewPreferences,
): boolean {
  return (
    held.order.length === asked.order.length &&
    held.order.every((id, at) => id === asked.order[at]) &&
    held.defaultInstanceId === asked.defaultInstanceId &&
    (held.autoRun ?? null) === (asked.autoRun ?? null) &&
    sameTabs(held.lastTabs, asked.lastTabs)
  );
}

function sameTabs(
  held: Record<string, string> | undefined,
  asked: Record<string, string> | undefined,
): boolean {
  if (held === undefined || asked === undefined) return held === asked;
  const keys = Object.keys(held);
  return (
    keys.length === Object.keys(asked).length &&
    keys.every(
      key =>
        Object.prototype.hasOwnProperty.call(asked, key) &&
        held[key] === asked[key],
    )
  );
}

/** System views first, then shared, then personal: the port's list order. */
export const AUDIENCE_RANK: Readonly<Record<ViewInstance['scope'], number>> = {
  system: 0,
  shared: 1,
  personal: 2,
};

/**
 * Where a list cursor says the last page ended; none for the first page. A
 * cursor this store did not issue, `<rank>:<place>`, is answered as the
 * `INVALID` refusal `WowViewStore` and the service give one (D482 ⑧).
 */
export function readCursor(
  cursor: string | null | undefined,
): { rank: number; place: number } | ViewStoreError | undefined {
  if (cursor === undefined || cursor === null) return undefined;
  const match = /^([0-2]):([1-9]\d{0,14})$/.exec(cursor);
  return match
    ? { rank: Number(match[1]), place: Number(match[2]) }
    : invalid(`Not a list cursor of this store: ${cursor}`);
}

/**
 * The revision after `current`. Revisions are opaque to the port, and a host
 * may seed any string; one this store did not write as a count (`'ops-1'`)
 * starts the count again at `'1'`, which it can never have been, so a write
 * against it still conflicts. A count goes on counting.
 */
export function nextRevision(current: string): string {
  return /^(0|[1-9]\d{0,14})$/.test(current)
    ? String(Number(current) + 1)
    : '1';
}

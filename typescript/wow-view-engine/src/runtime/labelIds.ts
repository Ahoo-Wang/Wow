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
  labelRemoteOf,
  readsAsNames,
  type FieldDefinition,
  type FilterLeaf,
  type RecordData,
} from '../model/index.js';
import {
  isFilterLeaf,
  isReferenceFilterValue,
  walkFilterShape,
} from '../filter/index.js';
import { readPath } from '../record/path.js';
import type { ReferenceId, ReferenceLabels } from './referenceLabels.js';
import type { ProjectedView } from './source.js';

/** Where a result holds ids to read as names: the remote, and how to read. */
export interface Holder {
  readonly remote?: string | undefined;
  readonly path: string;
  /** A result row's alias, read as a key rather than a path. */
  readonly flat?: true;
}

/**
 * The reference ids a result shows, by remote: every row's value of
 * each column, card field or group that names a remote. A page's ids are
 * asked together, one request per remote, so this collects them whole.
 */
export function labelIds(data: ProjectedView): Map<string, Set<ReferenceId>> {
  const holders: Holder[] = [];
  let rows: readonly RecordData[];
  if (data.kind === 'record') {
    const { view } = data;
    for (const column of view.columns)
      holders.push({ remote: column.remote, path: column.field });
    for (const field of [view.card.titleField, ...view.card.fields])
      if (field) holders.push({ remote: field.remote, path: field.field });
    rows = view.rows.map(row => row.data);
  } else {
    // A group's keys are its field's values, by the group's alias.
    for (const column of data.view.schema ?? data.view.columns)
      if (column.role === 'group')
        holders.push({ remote: column.remote, path: column.alias, flat: true });
    rows = data.view.rows;
  }
  return idsOf(rows, holders);
}

/** The ids `rows` hold under each holder that names a remote. */
export function idsOf(
  rows: readonly RecordData[],
  holders: readonly Holder[],
): Map<string, Set<ReferenceId>> {
  const byRemote = new Map<string, Set<ReferenceId>>();
  for (const { remote, path, flat } of holders) {
    if (!remote) continue;
    let ids = byRemote.get(remote);
    if (!ids) byRemote.set(remote, (ids = new Set()));
    for (const row of rows) {
      const value = flat ? row[path] : readPath(row, path);
      if (typeof value === 'string' || typeof value === 'number')
        ids.add(value);
    }
  }
  return byRemote;
}

/**
 * Every reference field of a definition that names a remote, by its path:
 * what a record read whole (`fetchRecord`) shows of it.
 */
export function fieldHolders(fields: readonly FieldDefinition[]): Holder[] {
  return fields.flatMap(field => {
    const { remote } = labelRemoteOf(field);
    return remote ? [{ remote, path: field.name }] : [];
  });
}

/**
 * The ids of the reference conditions in `leaves` that read as themselves
 *, added to `byRemote` by their field's remote. A condition keeps
 * the label it was chosen with, so one picked in the editor reads as a name
 * already; one that came from a link, a host's scope or a board's default
 * holds only its id, and shows an id the page may never show.
 */
export function filterIds(
  fields: readonly Pick<FieldDefinition, 'name' | 'kind' | 'remote'>[],
  leaves: Iterable<Pick<FilterLeaf, 'field' | 'value'>>,
  byRemote = new Map<string, Set<ReferenceId>>(),
): Map<string, Set<ReferenceId>> {
  const byName = new Map(fields.map(field => [field.name, field]));
  for (const { field: name, value } of leaves) {
    const field = byName.get(name);
    if (!field?.remote || !readsAsNames(field)) continue;
    if (!isReferenceFilterValue(value)) continue;
    let ids = byRemote.get(field.remote);
    if (!ids) byRemote.set(field.remote, (ids = new Set()));
    for (const { id, label } of value.items)
      if (label === String(id)) ids.add(id);
  }
  return byRemote;
}

/** Every condition of a filter tree, at any depth. */
export function* leavesOf(tree: unknown): Generator<FilterLeaf> {
  for (const { node } of walkFilterShape(tree))
    if (isFilterLeaf(node)) yield node;
}

/**
 * Asks the names of the ids a result shows, one request per remote,
 * beside the result rather than before it: the rows draw at once and a name
 * replaces its id as it arrives. The ids of the reference conditions it ran
 * under — the view's own, the host's scope merged into `config` — go in the
 * same request (`filterIds`).
 */
export function askLabels(
  view: {
    readonly labels: ReferenceLabels;
    readonly fields: readonly FieldDefinition[];
  },
  result: {
    readonly data: ProjectedView;
    readonly config: { filter: unknown };
  },
  previous?: AbortController,
): AbortController {
  const shown = labelIds(result.data);
  const ids = filterIds(view.fields, leavesOf(result.config.filter), shown);
  return wantAll(view.labels, ids, previous);
}

/**
 * Asks each remote's ids in one `want`. The reads asked before (`previous`)
 * are let go after these have asked, so the ids both hold stay in flight;
 * the caller lets the last go when it is done.
 */
export function wantAll(
  labels: ReferenceLabels,
  byRemote: ReadonlyMap<string, ReadonlySet<ReferenceId>>,
  previous?: AbortController,
): AbortController {
  const controller = new AbortController();
  for (const [remote, ids] of byRemote)
    labels
      .want(remote, ids, { signal: controller.signal })
      .catch(() => undefined);
  previous?.abort();
  return controller;
}

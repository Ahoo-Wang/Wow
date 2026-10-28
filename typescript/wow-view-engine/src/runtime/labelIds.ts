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

import type { RecordData } from '../model/index.js';
import { readPath } from '../record/path.js';
import type { ReferenceId } from './referenceLabels.js';
import type { ProjectedView } from './source.js';

/** Where a result holds ids to read as names: the remote, and how to read. */
interface Holder {
  readonly remote?: string | undefined;
  readonly path: string;
  /** A result row's alias, read as a key rather than a path. */
  readonly flat?: true;
}

/**
 * The reference ids a result shows, by remote (D354): every row's value of
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

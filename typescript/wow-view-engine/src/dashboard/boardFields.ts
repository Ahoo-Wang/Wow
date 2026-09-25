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
 * Deliberately not re-exported by `dashboard/index.ts`: only the runtime's
 * `fieldsOf`, the panel reach and the wiring strip read it, so it is not part
 * of the root entry's surface. A host that builds its own wiring UI would be
 * the reason to export it.
 */

import {
  filterTypeOf,
  type DataViewConfig,
  type FieldDefinition,
} from '../model/index.js';

/**
 * The fields of a panel's view a board filter can be wired to: its
 * definition's, but a search box (`kind: 'search'`) only on a record view.
 * A board's search finds rows (订单号、买家昵称、商品名) in the detail
 * panels; an analysis panel counts groups, and a search reaching it would
 * quietly change its numbers — so a search filter is never wired to one,
 * by hand or by auto-connect (D36, the search filter on the board).
 */
export function boardFieldsOf(
  view: DataViewConfig['kind'],
  fields: readonly FieldDefinition[],
): readonly FieldDefinition[] {
  return view === 'record'
    ? fields
    : fields.filter(field => filterTypeOf(field.kind) !== 'search');
}

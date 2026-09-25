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

// The request shapes the query clients and `QueryApi` accept: the one place
// outside `src/legacy/` that imports from it. In v10 the unions narrow to the
// `Filter*` queries here and the `Condition` re-export goes, which leaves the
// `count()` parameters (marked where they are declared) as the only other
// lines to change.

// compat(wow<9): the request unions admit the Condition-based queries of `/legacy`, which Wow < 8.11 needs; narrow them to the Filter* queries in v10.
import type {
  ListQuery,
  PagedQuery,
  SingleQuery,
} from '../../legacy/queryable.js';
import type {
  FilterListQuery,
  FilterPagedQuery,
  FilterSingleQuery,
} from '../../dsl/queryable.js';

// compat(wow<9): count() accepts the deprecated Condition, which Wow < 8.11 needs; drop this re-export in v10.
export type { Condition } from '../../legacy/condition.js';

/**
 * The `single` query bodies the query clients send: a `FilterSingleQuery`, or
 * the Condition-based `SingleQuery` a Wow 8.10 server needs.
 *
 * @deprecated Use FilterSingleQuery instead. Removed in v10.
 */
export type SingleQueryRequest<FIELDS extends string = string> =
  FilterSingleQuery<FIELDS> | SingleQuery<FIELDS>;

/**
 * The `list` query bodies the query clients send: a `FilterListQuery`, or the
 * Condition-based `ListQuery` a Wow 8.10 server needs.
 *
 * @deprecated Use FilterListQuery instead. Removed in v10.
 */
export type ListQueryRequest<FIELDS extends string = string> =
  FilterListQuery<FIELDS> | ListQuery<FIELDS>;

/**
 * The `paged` query bodies the query clients send: a `FilterPagedQuery`, or
 * the Condition-based `PagedQuery` a Wow 8.10 server needs.
 *
 * @deprecated Use FilterPagedQuery instead. Removed in v10.
 */
export type PagedQueryRequest<FIELDS extends string = string> =
  FilterPagedQuery<FIELDS> | PagedQuery<FIELDS>;

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
 * What a host writes to `defineView`: its choices — which fields, under
 * which words, narrowed how — and nothing the descriptor already says
 * (host-integration.md 3, D67). Every member that narrows is checked
 * against the descriptor: asking for more is an admission error.
 */

import type {
  AnalysisSpec,
  FieldAnalysisSpec,
  FieldCellId,
  FieldDefinition,
  FieldGroupDefinition,
  FieldKindId,
  FieldOption,
  FilterOperatorName,
  RecordCapability,
  SearchModeName,
  SummaryFunction,
  SystemView,
  TimePrecision,
} from '../../model/index.js';

/** One data definition's choices over its descriptor. */
export interface DefineViewSpec {
  id: string;
  /** The key a host resolves the definition's source by. */
  source: string;
  title: string;
  recordNoun?: string;
  /** See `DataViewDefinition.timeField`: a date field listed in `fields`. */
  timeField?: string;
  /**
   * The fields a reader sees, by root path, in the order listed: a field
   * the descriptor has and this does not list does not appear (D67 Q1). A
   * string is the label alone.
   */
  fields: Readonly<Record<string, FieldSpec | string>>;
  fieldGroups?: FieldGroupDefinition[];
  /**
   * Records: the row key and paging default to the descriptor's (its
   * identity; paged where it pages, else by cursor), shown as a table.
   * `false` offers no records.
   */
  record?: Partial<RecordCapability> | false;
  /** Analyses, as the descriptor offers them; `false` offers none. */
  analysis?: AnalysisSpec | false;
  views?: SystemView[];
}

/** One value of a category: its words, or its words and tone; `false` hides it. */
export type OptionSpec = string | Omit<FieldOption, 'value'> | false;

/** One field: what the host says of it, over what its path is. */
export interface FieldSpec {
  /**
   * The word a reader knows it by. Left out, the descriptor's description,
   * else the path — and admission says so (`definition.field.unlabelled`).
   */
  label?: string;
  /**
   * The kind, where the descriptor leaves a choice: `copyable`-style
   * reference ids, a string read as a category. Left out, the descriptor's
   * value type says it (text, number, yes/no, a moment, a category).
   */
  kind?: FieldKindId;
  cell?: FieldCellId;
  /**
   * How finely a table cell writes its time of day: `'second'` where the
   * seconds are the point, an event stream's times; the minute when left
   * out (`FieldDefinition.timePrecision`).
   */
  timePrecision?: TimePrecision;
  /** The comparisons offered, a subset of the path's. */
  operators?: FilterOperatorName[];
  /** `false` offers no sort on a path that sorts. */
  sortable?: boolean;
  /** The footer summaries offered, among the functions the path feeds. */
  summary?: SummaryFunction[];
  /**
   * The category's values in the order listed, each with its words and
   * tone, `false` to hide one; a value not listed keeps its descriptor
   * description. On a path the descriptor gives no values for, the list is
   * the host's own closed list. A record keeps its order unless its keys
   * look like integers, which JavaScript puts first: a category of numbers
   * is written as a list of `[value, words]`.
   */
  options?:
    | Readonly<Record<string, OptionSpec>>
    | readonly (readonly [FieldOption['value'], OptionSpec])[];
  /** How it analyses, narrowed; `false` keeps it out of analyses. */
  analysis?: FieldAnalysisSpec | false;
  /** Why it is kept although the descriptor deprecates it. */
  deprecated?: { message?: string };
  /**
   * An array's entries: their fields by path within the entry, as
   * `fields` is written. The path must be an element the descriptor lists.
   */
  elements?: Readonly<Record<string, FieldSpec | string>>;
  elementTitle?: string;
  /**
   * A search box rather than a path: the key is a handle, `fields` the
   * paths it searches, each one the descriptor has.
   */
  search?: { fields: string[]; mode?: SearchModeName };
  /** Anything else a field may say of itself, as written by hand. */
  more?: Partial<
    Pick<
      FieldDefinition,
      'numberFormat' | 'numeric' | 'temporal' | 'remote' | 'stringComparison'
    >
  >;
}

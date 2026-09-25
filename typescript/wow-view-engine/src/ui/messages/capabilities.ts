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
 * A source's capability descriptor, read against a definition
 * (capabilities.md). Like definition admission these are read by whoever
 * wrote the release, through `onIssue`: they name the declaration the
 * deployment does not bear out.
 */
export const capabilitiesMessages = {
  'capability.field.unknown':
    'The source does not list {field}: it is shown, but no condition, sort or summary uses it.',
  'capability.field.unfilterable': 'The source admits no condition on {field}.',
  'capability.field.operators-narrowed':
    'The source does not admit {operators} on {field}.',
  'capability.field.unsortable': 'The source does not sort by {field}.',
  'capability.field.not-projectable':
    'The source does not return {field}: its column reads empty.',
  'capability.field.summary-narrowed':
    'The source cannot take the {summaries} summary of {field}.',
  'capability.field.temporal-mismatch':
    '{field} is declared as {declared}, but the source keeps it as {described}: its date conditions would be written wrong.',
  'capability.field.options-undescribed':
    '{field} lists {values}, which the source does not declare.',
  'capability.search.unavailable':
    'The source offers no search {field} can use.',
  'capability.search.as-terms':
    'The source matches no phrase, so {field} searches by words.',
  'capability.search.fields-narrowed':
    '{field} cannot search {fields} on this source.',
  'capability.record.paging':
    'The record view pages by {paging}, which the source does not offer.',
  'capability.record.cursor-appended':
    'The source ends a cursor on {appended}, not on the row key {field}.',
  'capability.record.row-key-unsortable':
    'The source cannot sort by the row key {field}.',
  'capability.analysis.count': 'The source does not count records.',
  'capability.analysis.expressions':
    'The source takes no formulas or derived metrics.',
  'capability.analysis.having': 'The source keeps no groups by a metric.',
  'capability.analysis.metric-sort':
    'The source orders groups by their dimensions only.',
  'capability.analysis.dense': 'The source fills no gaps in a time dimension.',
  'capability.analysis.unavailable':
    'The source can aggregate none of what the analysis declares.',
  'capability.analysis.element-unavailable':
    'The source cannot aggregate over the elements of {path}.',
  'capability.analysis.field-unavailable':
    'The source cannot aggregate {field}.',
  'capability.analysis.field-narrowed':
    'The source does not offer {dropped} on {field}.',
  'capability.descriptor.unavailable':
    'The capability descriptor of {source} could not be read; its views run on the definition alone.',
} as const;

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
 * Definition admission.
 *
 * A definition is code, so these are read by whoever wrote the release rather
 * than by the person using the view — they name the declaration at fault.
 */
export const definitionMessages = {
  'definition.field.search-fields-unknown':
    '{field} searches {missing}, which the definition does not declare.',
  'definition.field.element-search-fields-required':
    '{field} searches inside an entry, so it has to name the entry fields it looks in.',
  'definition.field.search-mode-invalid':
    '{field} declares an unknown search mode: {value}.',
  'definition.analysis.date-part-not-temporal':
    '{field} offers grouping by weekday or hour, but holds no date or time.',
  'definition.analysis.date-part-unknown':
    '{field} declares an unknown calendar part: {value}.',
  'definition.analysis.default-limit-too-large':
    'The default row limit exceeds the maximum.',
  'definition.analysis.element-field-unknown':
    '{path} declares no field named {field}.',
  'definition.analysis.element-undeclared':
    'The analysis expands {path}, which is not a field holding elements.',
  'definition.analysis.elements-too-many':
    'The analysis declares more than {max} nested levels.',
  'definition.analysis.field-unknown':
    'The analysis capability names {field}, which the definition does not declare.',
  'definition.analysis.limit-invalid':
    'An analysis limit must be a positive whole number.',
  'definition.analysis.no-metric':
    'The analysis capability offers no metric to start from.',
  'definition.field.cell-invalid':
    '{field} declares an unknown cell renderer: {value}.',
  'definition.field.element-title-unknown':
    '{field} titles its elements by {title}, which its elements do not declare.',
  'definition.field.element-title-not-a-value':
    '{field} titles its elements by {title}, which holds no value of its own to read.',
  'definition.field.editor-removed':
    '{field} declares an editor, which no longer exists; delete the member.',
  'definition.field.temporal-invalid':
    '{field} declares a time storage the engine cannot write: {value}.',
  'definition.field.temporal-misplaced':
    '{field} declares a time storage, but its type {kind} writes no time.',
  'definition.field.tone-invalid':
    '{field} declares an unknown option tone: {value}.',
  'definition.field.duplicate': 'The field {field} is declared twice.',
  'definition.fieldGroup.duplicate': 'The group {group} is declared twice.',
  'definition.fieldGroup.field-duplicate':
    'The group {group} lists {field}, which is already listed under another group.',
  'definition.fieldGroup.field-unknown':
    'The group {group} lists {field}, which the definition does not declare.',
  'definition.fieldGroup.invalid': 'A field group needs an id and a label.',
  'definition.field.string-comparison-invalid':
    '{field} declares an unknown text comparison: {value}.',
  'definition.field.kind-unregistered':
    '{field} uses the unregistered type {kind}.',
  'definition.field.name-invalid': '{field} is not a usable field name.',
  'definition.id.separator': 'A definition id cannot contain {separator}.',
  'definition.record.layouts-empty': 'The record capability offers no layout.',
  'definition.record.max-window-invalid':
    'The paging window must be a whole number of rows above zero, not {value}.',
  'definition.record.max-sort-fields-invalid':
    'The sort bound must be a whole number of fields, not {value}.',
  'definition.record.max-window-cursor':
    'A paging window bounds pages, and this source pages by cursor.',
  'definition.record.row-key-unknown':
    'The row key {field} is not a declared field.',
  'definition.record.row-field-unknown':
    'The row field {field} is not a declared field.',
  'definition.record.row-field-not-a-path':
    'The row field {field} is a search or metadata handle, not a value a row holds.',
  'definition.record.row-key-unsortable':
    'The row key {field} must be sortable: every page is ordered by it last, so a row never shows on two pages.',
  'definition.view.id-duplicate': 'Two views share the id {id}.',
  'definition.view.id-separator': 'A view id cannot contain {separator}.',
  'definition.view.kind-mismatch':
    'A {kind} view does not belong to a {definition} definition.',
} as const satisfies Record<string, string>;

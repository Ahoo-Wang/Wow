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
 * A board's filters, batch C2 (D22 F, G): the filter bar, a panel a filter
 * does not reach, a filter's settings, and wiring it to the panels.
 */
export const filtersMessages = {
  // The filter bar (screen F).
  'label.filters.bar': 'Filters',
  'label.filters.required': '(required)',
  'label.filters.clear': 'Clear',
  'label.filters.clear-one': 'Clear “{filter}”',
  'label.filters.back-to-default': 'Put “{filter}” back to its default',
  'label.filters.idle':
    'Nothing on this tab is filtered by “{filter}”, so it changes nothing here.',
  'label.filters.grouping': 'Time grouping',
  'label.filters.grouping-remove': 'Remove the time grouping',
  // The board's fixed scope (D26 Q31, D27): in force beside the filters,
  // read-only, and nobody's to take out while reading; its author takes it
  // out whole while building (D23 Q16).
  'label.filters.fixed': 'Fixed scope',
  'label.filters.fixed-note':
    'The dashboard itself holds every panel to this; it cannot be changed here.',
  'label.filters.fixed-remove': 'Remove the fixed scope',
  'label.filters.fixed-removed': 'Removed the fixed scope',
  // Narrower than md the bar is one button and a sheet (D26 Q38).
  'label.filters.sheet-set': 'Filters ({count} set)',
  // A panel a filter holding a value does not reach, named in its header.
  'label.filters.not-reached': 'Not filtered by {filters}',
  'label.filters.name-quoted': '“{name}”',
  // 「添加筛选」 (screen G).
  'label.filters.add': 'Add filter',
  'label.filters.add-grouping': 'Time grouping',
  'label.filters.type.date': 'Date',
  'label.filters.type.text': 'Text or category',
  'label.filters.type.id': 'ID',
  'label.filters.type.number': 'Number',
  'label.filters.type.boolean': 'Yes or no',
  'label.filters.type.search': 'Search',
  // A search filter's empty box: an invitation to type, not a missing value.
  'label.filters.search-placeholder': 'Search…',
  'label.filters.removed': 'Removed the filter “{filter}”',
  // A filter's settings.
  'label.filters.settings-of': 'Settings of “{filter}”',
  'label.filters.type': 'Type',
  'label.filters.name': 'Name',
  'label.filters.default': 'Default',
  'label.filters.multiple': 'Several values',
  'label.filters.required-toggle': 'Required',
  'label.filters.required-hint':
    'Always has a value: clearing it goes back to the default.',
  'label.filters.one-day-toggle': 'One day only',
  'label.filters.one-day-hint':
    'A day off the calendar, or today, yesterday or the day before; never a range.',
  // An optional date filter holding nothing narrows no panel.
  'label.filters.date-own': "Each panel's own dates",
  'label.filters.required-needs-default':
    'A required filter needs a default to start at.',
  'label.filters.source': 'Values from',
  'label.filters.source.fields': 'The wired fields',
  'label.filters.source.list': 'A list of its own',
  'label.filters.source.fields-hint':
    'The values the wired fields declare; where they declare none, the values the data holds, with how many records hold each.',
  'label.filters.source.list-hint': 'A few fixed values, typed here.',
  'label.filters.list': 'The values',
  'label.filters.wire': 'Wire to panels',
  'label.filters.remove': 'Remove the filter',
  // Wiring (screen G).
  'label.filters.wiring': 'Wiring “{filter}”',
  'label.filters.wiring-hint':
    'On each panel, pick the field it is filtered through.',
  'label.filters.wiring-done': 'Done wiring',
  'label.filters.wire-field': 'Filter field',
  'label.filters.wire-field-of': 'Field of “{panel}” filtered by “{filter}”',
  'label.filters.unwired': 'Not wired',
  'label.filters.no-field': 'No field to wire',
  'label.filters.manual': 'Manual',
  'label.filters.manual-hint': 'Wired by hand rather than by name',
  'label.filters.auto-wired':
    'Wired {count} more panels with a “{field}” field automatically',
  'label.filters.auto-wired-one':
    'Wired 1 more panel with a “{field}” field automatically',
  // A search is wired to each record view's search box, whatever it is called.
  'label.filters.auto-wired-search':
    'Wired {count} more panels with a search box automatically',
  'label.filters.auto-wired-search-one':
    'Wired 1 more panel with a search box automatically',
  // Not 「撤销」: that is the edit bar's step back (X-09). This keeps the
  // panel just picked and unwires the ones auto-connect added.
  'label.filters.only-picked': 'Only the panel picked',
  'label.filters.dismiss': 'Dismiss',
  'label.filters.toasts': 'Wiring notices',
  'label.filters.refused': 'Some of the filters in the link could not be used',
  // Putting the filters in another order while the board is built.
  'label.filters.reorder': 'Reorder “{filter}”',
  'label.filters.picked': 'Picked up the filter “{filter}”.',
  'label.filters.cancelled': 'The filter “{filter}” stayed where it was.',
  'label.filters.moved': '“{filter}” is now filter {index} of {total}',
} as const;

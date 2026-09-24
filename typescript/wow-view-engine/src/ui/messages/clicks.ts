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
 * A press on a dashboard panel, batch D (D22 H, I): the follow-up menu's
 * board line, cross-filtering and what it says, 「点击时…」, and the kernel
 * and runtime findings about a panel's click.
 */
export const clicksMessages = {
  // The follow-up menu on a panel (screen H).
  'label.drill.board': 'Board filters: {conditions}',
  'label.drill.away': '(opens in the workbench)',
  // Cross-filtering (screen I).
  'label.click.badge': 'Click filters “{filter}”',
  'label.click.badge-note':
    'Pressing a group of this panel sets “{filter}” for the rest of the board; pressing it again clears it.',
  'label.click.from': 'from “{panel}”',
  'label.click.filtered': '“{filter}” now filters by the group pressed',
  'label.click.cleared': '“{filter}” no longer filters by the group pressed',
  'label.click.no-value': 'This group has no value “{filter}” can take',
  // 「点击时…」 (screen I).
  'label.click.menu-item': 'When clicked…',
  'label.click.title': 'When “{panel}” is clicked',
  'label.click.description':
    'What pressing one group of this panel does — a bar, a slice, a row of its table.',
  'label.click.choice': 'A press',
  'label.click.menu': 'Open the follow-up menu',
  'label.click.menu-hint':
    'See these records, split the group by another dimension, or ask of it alone — each opens in the workbench.',
  'label.click.menu-no-route':
    'This page opens no workbench, so a press does nothing here.',
  'label.click.filter': 'Update a dashboard filter',
  'label.click.filter-hint':
    'The other wired panels are filtered by the value pressed; this panel keeps every group and marks the one pressed. Press it again to clear.',
  'label.click.filter-none':
    'Wire a filter to a field this panel groups by first.',
  'label.click.filter-pick': 'Filter',
  'label.click.go': 'Go to another view or page',
  'label.click.go-hint': 'The value pressed goes along.',
  'label.click.go-kind': 'Destination',
  'label.click.go-view': 'View',
  'label.click.go-url': 'Web address',
  'label.click.view-pick': 'Choose a view…',
  'label.click.view-change': 'Choose another…',
  'label.click.view-missing': 'Choose the view a press opens.',
  'label.click.view-heading': 'Where a press on “{panel}” goes',
  'label.click.url': 'Address',
  'label.click.url-hint':
    'Write one of these where the value pressed goes: {fields}',
  'label.click.url-hint-none': 'This panel has no dimension to pass on.',
  'label.click.url-invalid':
    'Only http, https, mailto or an address inside this application.',
  'label.click.save': 'Done',
  // Kernel and runtime findings.
  'dashboard.click.invalid':
    'This panel’s click setting cannot be read; pressing it opens the follow-up menu.',
  'dashboard.click.unpressable':
    'This panel has no groups to press — a record view, or an analysis over expanded elements — so its click setting does nothing.',
  'dashboard.click.filter-unknown':
    'The filter this panel’s click sets is no longer on the board; pressing it opens the follow-up menu.',
  'dashboard.click.filter-unwired':
    '“{filter}” is not wired to this panel, so a press cannot set it; pressing it opens the follow-up menu.',
  'dashboard.click.filter-ungrouped':
    'This panel does not group by {field}, which “{filter}” is wired to, so a press has no value for it; pressing it opens the follow-up menu.',
  'dashboard.click.url-unsafe':
    'The click’s address is not http, https, mailto or inside this application; pressing it opens the follow-up menu.',
  'dashboard.click.url-unknown-field':
    'The click’s address names {field}, which this panel does not group by; pressing it opens the follow-up menu.',
  'dashboard.click.destination-unavailable':
    'The view a press goes to was deleted, or you may not open it.',
  'dashboard.click.destination-unsupported':
    'A press can go to a record or an analysis view, not to a dashboard.',
} as const satisfies Record<string, string>;

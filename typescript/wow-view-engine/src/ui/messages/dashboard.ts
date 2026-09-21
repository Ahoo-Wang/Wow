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

/** The dashboard grid, its panels, and the dashboard kernel behind them. */
export const dashboardMessages = {
  'label.dashboard.needs-fixing': 'This dashboard needs fixing before it runs',
  'label.dashboard.empty': 'No panels yet',
  'label.dashboard.empty-hint':
    'Add a saved record or analysis view to see it here.',
  'label.panel.unavailable': 'This panel is unavailable',
  'label.panel.unavailable-hint': 'The view it shows could not be opened.',
  // Placing a panel: the two handles, the menu that says the same commands
  // in words, and what a keyboard hears once a command has landed.
  'label.panel.move': 'Move {title} with the arrow keys',
  'label.panel.resize': 'Resize this panel with the arrow keys',
  'label.panel.arrange': 'Place {title}',
  'label.panel.arrange-move': 'Move',
  'label.panel.arrange-size': 'Size',
  'label.panel.move-up': 'Move up',
  'label.panel.move-down': 'Move down',
  'label.panel.move-left': 'Move left',
  'label.panel.move-right': 'Move right',
  'label.panel.wider': 'Wider',
  'label.panel.narrower': 'Narrower',
  'label.panel.taller': 'Taller',
  'label.panel.shorter': 'Shorter',
  'label.panel.placed':
    '{title} is at column {column}, row {row}, {w} columns by {h} rows',

  // Dashboard kernel.
  'dashboard.binding.global-duplicate':
    '{field} is mapped twice on this panel.',
  'dashboard.binding.global-unknown':
    '{field} is not a filter field of this dashboard.',
  'dashboard.binding.kind-mismatch':
    'A {global} field cannot filter a {panel} field.',
  'dashboard.binding.missing':
    'This panel does not carry the {field} filter, so it cannot be filtered with the others.',
  'dashboard.binding.panel-unknown':
    '{field} is not a field of the view this panel shows.',
  'dashboard.field.duplicate': 'The filter field {field} is declared twice.',
  'dashboard.field.name-empty': 'A filter field needs a name.',
  'dashboard.field.name-invalid': '{field} is not a usable field name.',
  'dashboard.layout.invalid': 'This panel has an unusable position or size.',
  'dashboard.layout.missing': 'This panel has no position.',
  'dashboard.layout.out-of-grid':
    'This panel reaches past the {columns} columns of the grid.',
  'dashboard.link.label-empty': 'A link needs a label.',
  'dashboard.links.too-many': 'A links panel holds at most {max} links.',
  'dashboard.markdown.too-long': 'A note holds at most {max} characters.',
  'dashboard.panel.failed': 'This panel could not be shown: {reason}',
  'dashboard.panel.id-duplicate': 'Two panels share the id {id}.',
  'dashboard.panel.id-empty': 'A panel needs an id.',
  'dashboard.panel.kind-unsupported':
    '{instance} is not a record or analysis view.',
  'dashboard.panel.scope-too-narrow':
    'A {scope} dashboard cannot show a {instance} view, which others cannot read.',
  'dashboard.panel.unavailable': 'The view this panel shows is unavailable.',
  'dashboard.panel.unknown-kind': 'The {kind} panel type is not available.',
  'dashboard.panels.too-many': 'A dashboard holds at most {max} panels.',
  'dashboard.shape.invalid':
    'This part of the dashboard is not in the expected shape.',
  'dashboard.url.unsupported-scheme':
    'Only http, https, mailto and relative links can be shown.',
} as const satisfies Record<string, string>;

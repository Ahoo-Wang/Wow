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
  // What an empty dashboard is, and nothing it cannot keep: there is no way
  // to add a panel yet, so the empty state says what a dashboard shows and
  // that this one has nothing on it, and invites nobody to press anything.
  'label.dashboard.empty': 'This dashboard has no panels yet',
  'label.dashboard.empty-hint':
    'A dashboard puts saved record and analysis views side by side.',
  // A panel that has no title of its own is named after what it shows, and
  // one that shows nothing nameable after where it stands — never its id.
  'label.panel.untitled': 'Panel {index}',
  // Two panels the board names alike are told apart by a number, in
  // reading order: 「Note」, 「Note 2」.
  'label.panel.numbered': '{name} {n}',
  'label.panel.kind.heading': 'Heading',
  'label.panel.kind.markdown': 'Note',
  'label.panel.kind.image': 'Image',
  'label.panel.kind.links': 'Links',
  // A panel that cannot show anything says why, in the reader's words, and
  // who can get it back — never the id it points at, a scope code or what a
  // store threw. Each reason is one finding the kernels raise, mapped.
  'label.panel.out.missing':
    'The view this panel shows was deleted, or you do not have access to it',
  'label.panel.out.failed': 'The view this panel shows could not be opened',
  'label.panel.out.kind':
    'This panel points at something that is not a record or analysis view',
  'label.panel.out.private':
    'The view this panel shows is not open to everyone who reads this dashboard',
  'label.panel.out.filter': "The dashboard's filters do not fit this panel",
  'label.panel.out.refused':
    'The view this panel shows was saved with settings that no longer work',
  'label.panel.out.blocked': 'The dashboard itself needs fixing first',
  'label.panel.out.unknown-kind': 'This type of panel cannot be shown here',
  'label.panel.out.settings': "This panel's own settings cannot be used",
  'label.panel.way-out.maintainer':
    'Ask whoever maintains this dashboard to replace or remove this panel.',
  'label.panel.way-out.share':
    "Ask the view's owner to share it with you, or whoever maintains this dashboard to replace or remove this panel.",
  'label.panel.way-out.widen':
    "Ask the view's owner to share it as widely as this dashboard, or whoever maintains this dashboard to replace or remove this panel.",
  'label.panel.way-out.author':
    "Ask the view's owner to open it and fix it, or whoever maintains this dashboard to replace this panel.",
  'label.panel.way-out.dashboard':
    'This panel runs again once the dashboard is fixed.',
  // A finding about one panel, said where the panel is not in view.
  'label.panel.finding': '{panel}: {finding}',
  // Placing a panel: the two handles, the menu that says the same commands
  // in words, and what a keyboard hears once a command has landed.
  'label.panel.move': 'Move “{title}”',
  'label.panel.resize': 'Resize “{title}”',
  'label.panel.resize-any': 'Resize this panel',
  'label.panel.arrange': 'Place “{title}”',
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
    '{title} is at column {column}, row {row}, {width} by {height}',
  // English counts: one column, two columns — the sentence picks the key.
  'label.panel.columns': '{count} columns',
  'label.panel.columns-one': '1 column',
  'label.panel.rows': '{count} rows',
  'label.panel.rows-one': '1 row',
  // A link on a panel opens in a tab of its own; a reader is told before
  // the page they were on is suddenly behind another one.
  'label.link.new-tab': '(opens in a new tab)',

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
  'dashboard.grid.unsupported':
    'This dashboard is laid out on a grid that cannot be drawn here; panels are placed on {columns} columns.',
  'dashboard.heading.too-long': 'A heading holds at most {max} characters.',
  'dashboard.layout.invalid': 'This panel has an unusable position or size.',
  'dashboard.layout.missing': 'This panel has no position.',
  'dashboard.layout.out-of-grid':
    'This panel reaches past the {columns} columns of the grid.',
  'dashboard.link.label-empty': 'A link needs a label.',
  'dashboard.links.too-many': 'A links panel holds at most {max} links.',
  'dashboard.markdown.too-long': 'A note holds at most {max} characters.',
  'dashboard.panel.definition-unknown':
    'The data the analysis in this panel was built on is no longer available.',
  'dashboard.panel.failed': 'The view this panel shows could not be opened.',
  'dashboard.panel.id-duplicate': 'Two panels share the id {id}.',
  'dashboard.panel.id-empty': 'A panel needs an id.',
  'dashboard.panel.kind-unsupported':
    'This panel points at something that is not a record or analysis view.',
  'dashboard.panel.not-owned':
    'Only an analysis that lives in this dashboard can be saved as a view.',
  'dashboard.panel.owned-invalid':
    'The analysis this panel holds is not in the expected shape.',
  'dashboard.panel.presentation-dropped':
    "How this panel was set to look no longer fits its view, so it shows the view's own look.",
  'dashboard.panel.scope-too-narrow':
    'The view this panel shows is not open to everyone who reads this dashboard.',
  'dashboard.panel.source-invalid':
    'This panel should show either a saved view or an analysis of its own.',
  'dashboard.panel.tab-unknown':
    'This panel is on no tab of this dashboard, so it is shown on the first.',
  'dashboard.panel.unavailable':
    'The view this panel shows was deleted, or you do not have access to it.',
  'dashboard.panel.unknown-kind': 'This type of panel is not available.',
  'dashboard.panels.too-many': 'A dashboard holds at most {max} panels.',
  'dashboard.shape.invalid':
    'This part of the dashboard is not in the expected shape.',
  'dashboard.tab.id-duplicate': 'Two tabs share the id {id}.',
  'dashboard.tab.id-empty': 'A tab needs an id.',
  'dashboard.tab.title-empty':
    'A tab has no name, so it is called by its place.',
  'dashboard.tabs.too-many': 'A dashboard holds at most {max} tabs.',
  'dashboard.url.unsupported-scheme':
    'Only http, https, mailto and relative links can be shown.',
} as const satisfies Record<string, string>;

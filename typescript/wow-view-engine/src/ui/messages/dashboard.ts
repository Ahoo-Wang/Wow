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
  // What an empty dashboard is, and nothing it cannot keep: the first things
  // to add are offered under it only to whoever may build the board.
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

  // Building the board (D22 A): the way in, the bar it is built under, and the ways out.
  'label.dashboard.edit': 'Edit',
  'label.dashboard.editing': 'Editing',
  'label.dashboard.editing-hint':
    'Panels run as you change them; nothing is saved until Done.',
  'label.dashboard.done': 'Done',
  'label.dashboard.add': 'Add',
  'label.dashboard.add.data': 'Data',
  'label.dashboard.add.content': 'Content',
  'label.dashboard.add.saved-view': 'Saved view…',
  'label.dashboard.add.new-analysis': 'New analysis…',
  'label.dashboard.add.heading': 'Heading',
  'label.dashboard.add.markdown': 'Text…',
  'label.dashboard.add.image': 'Image…',
  'label.dashboard.add.links': 'Links…',
  'label.dashboard.empty.add-view': 'Add a view…',
  'label.dashboard.empty.add-heading': 'Add a heading',
  'label.dashboard.new-heading': 'New section',
  'label.dashboard.added': 'Added “{title}”',
  'label.dashboard.duplicated': 'Copied “{title}”',
  'label.dashboard.removed': 'Removed “{title}”',
  'label.dashboard.tab.untitled': 'Tab {index}',
  // The picker a saved view is added or swapped in from (D22 B).
  'label.picker.add-heading': 'Add a saved view',
  'label.picker.replace-heading': 'Replace the view in “{title}”',
  'label.picker.description':
    'Pick a record or analysis view to show on this dashboard.',
  'label.picker.search': 'Search by name',
  'label.picker.kind': 'Kind',
  'label.picker.kind.all': 'All',
  'label.picker.kind.record': 'Records',
  'label.picker.kind.analysis': 'Analyses',
  'label.picker.definition': 'Data',
  'label.picker.definition.all': 'All data',
  'label.picker.on-board': 'On the board',
  'label.picker.private': 'Only you can see it',
  'label.picker.none': 'No view matches.',
  'label.picker.empty': 'There is no saved record or analysis view yet.',
  'label.picker.loading': 'Loading views…',
  // One panel's menu (D22 D): how it is looked at, and — while the board is built — how it is changed.
  'label.panel.menu': 'Actions for “{title}”',
  'label.panel.menu.view': 'View',
  'label.panel.menu.edit': 'Change',
  'label.panel.open': 'Open in the workbench',
  'label.panel.refresh': 'Refresh this panel',
  'label.panel.rename': 'Rename',
  'label.panel.edit-presentation': 'Change how it looks here…',
  'label.panel.edit-content': 'Edit content…',
  'label.panel.replace': 'Replace view…',
  'label.panel.duplicate': 'Duplicate',
  'label.panel.move-to-tab': 'Move to tab',
  'label.panel.save-as-view': 'Save as a view…',
  'label.panel.remove': 'Remove from dashboard',
  'label.panel.remove-heading': 'Remove “{title}” from this dashboard?',
  'label.panel.remove-view': 'The view it shows is not deleted.',
  'label.panel.remove-owned':
    'The analysis in it belongs to this dashboard and goes with it.',
  'label.panel.remove-content': 'What it holds goes with it.',
  'label.panel.remove-undo': 'Cancel on the editing bar still brings it back.',
  'label.panel.keep': 'Keep it',
  'label.panel.title-input': 'Panel title',
  'label.panel.heading-input': 'Heading text',
  'label.panel.way-out.edit':
    'Replace it with another view, or remove it from the dashboard.',
  'label.panel.way-out.edit-content':
    'Change what it holds, or remove it from the dashboard.',
  // What a content panel holds, edited in a small form (D22 A).
  'label.content.markdown.add': 'Add text',
  'label.content.markdown.edit': 'Edit text',
  'label.content.markdown.field': 'Text',
  'label.content.markdown.hint':
    'Markdown: **bold**, lists, [links](https://…).',
  'label.content.markdown.default': 'Write what this part of the board is for.',
  'label.content.image.add': 'Add an image',
  'label.content.image.edit': 'Edit image',
  'label.content.image.src': 'Image address',
  'label.content.image.alt': 'Description',
  'label.content.image.alt-hint': 'Read out to whoever cannot see the image.',
  'label.content.image.href': 'Opens when pressed (optional)',
  'label.content.image.fit': 'Fit',
  'label.content.image.fit.contain': 'Whole image',
  'label.content.image.fit.cover': 'Fill and crop',
  'label.content.links.add': 'Add links',
  'label.content.links.edit': 'Edit links',
  'label.content.links.item': 'Link {n}',
  'label.content.links.label': 'Text',
  'label.content.links.href': 'Address',
  'label.content.links.description': 'Note (optional)',
  'label.content.links.more': 'Add a link',
  'label.content.links.drop': 'Remove link {n}',
  'label.content.title': 'Panel title (optional)',
  'label.content.required': 'Fill this in.',
  'label.content.submit-add': 'Add',
  'label.content.submit-edit': 'Update',

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
  'dashboard.field.not-multiple':
    'The filter {field} takes one value, and this holds several.',
  'dashboard.field.options-empty':
    'The filter {field} picks from a list of its own, and the list is empty.',
  'dashboard.field.required-no-default':
    'The filter {field} always has a value, so it needs a default to start at.',
  'dashboard.fields.too-many': 'A dashboard holds at most {max} filters.',
  'dashboard.filter.unknown': 'This dashboard has no filter {field}.',
  'dashboard.filter.held':
    'The page holds the filter {field}; it cannot be changed here.',
  'dashboard.grouping.kept':
    'This panel keeps its own time grouping: its data cannot be grouped the way the dashboard is.',
  'dashboard.grouping.unit-duplicate': 'The time grouping offers {unit} twice.',
  'dashboard.grouping.unit-unknown':
    '{unit} is not a time grouping this dashboard offers.',
  'dashboard.grouping.units-empty':
    'The time grouping offers nothing to choose from.',
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

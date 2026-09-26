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
  // The dashboard workbench calls what it has open a dashboard wherever
  // another workbench says view (D26 Q34, `kindWord`): new, save, the
  // shared-save question, delete and the manager.
  'label.dashboard.new': 'New dashboard',
  'label.dashboard.new-title': 'Untitled dashboard',
  'label.dashboard.none': 'No dashboard yet',
  'label.dashboard.more': 'More dashboard actions',
  'label.dashboard.leave-heading': 'Leave this dashboard?',
  'label.dashboard.refresh-on':
    'This dashboard refreshes itself every {interval}.',
  'label.dashboard.list': 'Dashboards',
  'label.dashboard.manage-group': 'What to do with this dashboard',
  'label.dashboard.collapse-sidebar': 'Hide the dashboard list',
  'label.dashboard.expand-sidebar': 'Show the dashboard list',
  'label.dashboard.switch': 'Switch dashboard',
  'label.dashboard.choose': 'Choose a dashboard',
  'label.dashboard.opening': 'Opening the dashboard',
  'label.dashboard.write-conflict': 'Someone else saved this dashboard first',
  'label.dashboard.scope-refused': 'This page could not narrow this dashboard',
  'label.dashboard.render-hint':
    'The rest of the dashboard still works. Try again to draw this part.',
  'label.dashboard.unopenable': 'This dashboard could not be opened',
  'label.dashboard.open-default': 'Open the default dashboard',
  'label.dashboard.save-group': 'Dashboard actions',
  'label.dashboard.saved-announce': 'Dashboard saved',
  'label.dashboard.shared-heading': 'Update the dashboard for everyone?',
  'label.dashboard.first-heading': 'Save this dashboard',
  'label.dashboard.save-as.heading': 'Save as a new dashboard',
  'label.dashboard.save-as.description':
    'The dashboard you are looking at stays as it is.',
  'label.dashboard.save-as.submit': 'Create dashboard',
  'label.dashboard.delete-consequence':
    'Only the dashboard is removed: its records and the saved views its panels show stay, and the analyses made inside it go with it.',
  'label.dashboard.group.personal': 'My dashboards',
  'label.dashboard.group.shared': 'Shared dashboards',
  'label.dashboard.group.system': 'System dashboards',
  'label.dashboard.manage': 'Manage dashboards',
  'label.dashboard.manage-description':
    'Rename, reorder and delete dashboards, and pick the one that opens by default.',
  // What the engine reports about the board itself, said as a board's where
  // the same code on another surface says view (D26 Q34, `kindIssue`): an
  // issue's code reads the entry it falls back to, and that entry's board
  // sentence stands in for it. A code whose sentence names no view keeps it.
  'label.dashboard.config-invalid':
    'Fix what this dashboard reports before saving it.',
  'label.dashboard.create-forbidden': 'You may not create dashboards here.',
  'label.dashboard.delete-failed': 'This dashboard could not be deleted.',
  'label.dashboard.delete-forbidden':
    'You may not delete this dashboard; ask whoever owns it to remove it.',
  'label.dashboard.list-failed': 'The list of dashboards could not be loaded.',
  'label.dashboard.list-unavailable':
    'The list of dashboards could not be loaded: the server could not be reached.',
  'label.dashboard.reserved-id':
    'The stored dashboard {id} uses a reserved id and was skipped.',
  'label.dashboard.notify-failed':
    'A listener on dashboard changes failed: {reason}. The list may be a revision behind.',
  'label.dashboard.open-failed': 'This dashboard could not be opened.',
  'label.dashboard.not-found': 'No dashboard named {id}.',
  // Opened, it turned out to be a record or an analysis view: what it is
  // not is the one thing the reader of this page needs to know.
  'label.dashboard.wrong-kind':
    'This is not a dashboard ({kind}), so this page cannot show it.',
  'label.dashboard.gone': 'This dashboard no longer exists.',
  'label.dashboard.open-forbidden': 'You may not open this dashboard.',
  'label.dashboard.open-unavailable':
    'This dashboard could not be loaded: the server could not be reached.',
  'label.dashboard.default-forbidden': 'You may not set the default dashboard.',
  'label.dashboard.preferences-failed':
    'Your dashboard preferences could not be saved.',
  'label.dashboard.preferences-load-failed':
    'Your dashboard preferences could not be loaded; the dashboards are in the server’s order.',
  'label.dashboard.reorder-forbidden': 'You may not reorder dashboards.',
  'label.dashboard.rename-failed': 'This dashboard could not be renamed.',
  'label.dashboard.rename-forbidden':
    'You may not rename this dashboard; save a copy of your own instead.',
  'label.dashboard.not-open': 'This dashboard is not open here any more.',
  'label.dashboard.save-as-failed':
    'This dashboard could not be saved as a copy.',
  'label.dashboard.save-failed': 'This dashboard could not be saved.',
  'label.dashboard.save-forbidden':
    'You may not save changes to this dashboard; save a copy of your own instead.',
  'label.dashboard.system-read-only':
    'A built-in dashboard cannot be changed ({action}).',
  'label.dashboard.title-empty': 'A dashboard needs a title.',
  'label.dashboard.write-in-flight':
    'This dashboard is already being saved; wait for that to finish.',
  'label.dashboard.write-forbidden': 'You may not write to this dashboard.',
  'label.dashboard.unreadable': 'This dashboard could not be read.',
  'label.dashboard.refresh-missing': 'This dashboard has no refresh setting.',
  'label.dashboard.not-declared': '{definition} does not offer dashboards.',
  // What an empty dashboard is, and nothing it cannot keep: the first things
  // to add are offered under it only to whoever may build the board.
  'label.dashboard.empty': 'This dashboard has no panels yet',
  'label.dashboard.empty-hint':
    'A dashboard puts saved record and analysis views side by side.',
  // A panel that has no title of its own is named after what it shows, and
  // one that shows nothing nameable after where it stands — never its id.
  // A panel wired to a date filter whose control waits for a day.
  'label.panel.awaiting-date': 'Pick a date',
  'label.panel.awaiting-date-hint':
    '{filter} is set to a specific date; pick one to see the numbers for it.',
  'label.panel.untitled': 'Panel {index}',
  // What a panel's body holds past its edges (P1-3), said on the body's
  // bottom edge: the rows under it, the columns past its end, or, where the
  // body holds no table, that there is more.
  'label.panel.more-rows': '{count} more rows below',
  'label.panel.more-rows-one': '1 more row below',
  'label.panel.more-columns': '{count} more columns to the right',
  'label.panel.more-columns-one': '1 more column to the right',
  'label.panel.more-both': '{rows}, {columns}',
  'label.panel.more-below': 'More below',
  // Two panels the board names alike are told apart by a number, in
  // reading order: 「Text」, 「Text 2」.
  'label.panel.numbered': '{name} {n}',
  'label.panel.kind.heading': 'Heading',
  'label.panel.kind.markdown': 'Text',
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
  'label.panel.resize': 'Resize “{title}”',
  'label.panel.handle': 'Move or resize “{title}”',
  'label.panel.handle-hint':
    'Drag it, or press Enter to arrange it with the keyboard: the arrows move it, Shift and the arrows resize it, Enter finishes, Escape puts it back.',
  'label.panel.arranging':
    'Arranging “{title}”: the arrows move it, Shift and the arrows resize it, Enter finishes, Escape puts it back',
  'label.panel.arranged': '“{title}” stays where it is',
  'label.panel.arrange-cancelled': '“{title}” is back where it was',
  'label.panel.arrange-stuck': '“{title}” cannot go that way',
  'label.panel.resize-any': 'Resize this panel',
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
    'Panels run as you change them; nothing is kept until you save.',
  'label.dashboard.save': 'Save',
  // The board's width while it is built (D31): two segments, each an icon
  // named by its word.
  'label.dashboard.width': 'Dashboard width',
  'label.dashboard.width-fixed': 'Fixed width',
  'label.dashboard.width-full': 'Full width',
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
  'label.dashboard.new-heading': 'New heading',
  'label.dashboard.added': 'Added “{title}”',
  'label.dashboard.duplicated': 'Copied “{title}”',
  'label.dashboard.removed': 'Removed “{title}”. Undo brings it back.',
  // Building a board, one step at a time: the edit bar's undo and redo, and
  // what each says it takes back (「撤销：移除「北区订单」」).
  'label.history.undo': 'Undo',
  'label.history.redo': 'Redo',
  'label.history.undo-step': 'Undo {what}',
  'label.history.redo-step': 'Redo {what}',
  'label.history.undone': 'Undone: {what}',
  'label.history.redone': 'Redone: {what}',
  'label.history.add-panel': 'adding “{title}”',
  'label.history.remove-panel': 'removing “{title}”',
  'label.history.move-panel': 'moving “{title}”',
  'label.history.change-panel': 'the change to “{title}”',
  'label.history.add-tab': 'adding a tab',
  'label.history.remove-tab': 'deleting a tab',
  'label.history.change-tabs': 'the change to the tabs',
  'label.history.add-filter': 'adding the filter “{title}”',
  'label.history.remove-filter': 'removing the filter “{title}”',
  'label.history.change-filter': 'the change to the filter “{title}”',
  'label.history.change-grouping': 'the change to the time grouping',
  'label.history.remove-fixed': 'removing the fixed scope',
  'label.history.change-width': 'the change to the dashboard width',
  'label.history.change-board': 'the last change',
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
  'label.picker.on-board': 'On the dashboard',
  'label.picker.private': 'Only you can see it',
  'label.picker.none': 'No view matches.',
  'label.picker.empty': 'There is no saved record or analysis view yet.',
  'label.picker.loading': 'Loading views…',
  // One panel's menu (D22 D): how it is looked at, and — while the board is built — how it is changed.
  'label.panel.menu': 'Actions for “{title}”',
  'label.panel.pagination': 'Pages of “{title}”',
  'label.panel.menu.view': 'View',
  'label.panel.menu.edit': 'Change',
  'label.panel.open': 'Open in the workbench',
  'label.panel.refresh': 'Refresh this panel',
  'label.panel.export': 'Export data…',
  'label.panel.rename': 'Rename',
  'label.panel.edit-presentation': 'Change how it looks here…',
  'label.panel.edit-content': 'Edit content…',
  'label.panel.replace': 'Replace view…',
  'label.panel.copy-shared': 'Copy as a shared view and replace…',
  'label.panel.duplicate': 'Duplicate',
  'label.panel.move-to-tab': 'Move to tab',
  'label.panel.save-as-view': 'Save as a view…',
  'label.panel.remove': 'Remove from dashboard',
  // The one-column reading's order (D22 J): the handle it is carried by,
  // what a pick-up says, and where the panel came to.
  'label.panel.reorder': 'Reorder “{title}”',
  'label.panel.picked': 'Picked up the panel “{title}”.',
  'label.panel.cancelled': 'The panel “{title}” stayed where it was.',
  'label.panel.reordered': '“{title}” is now panel {index} of {total}',
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
  'label.content.markdown.placeholder': 'Write what this dashboard is for.',
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
  'label.content.links.label': 'Link text',
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
  'dashboard.field.not-one-day':
    'The filter {field} holds one day: a day off the calendar, or today, yesterday or the day before.',
  'dashboard.field.one-day-not-date':
    'The filter {field} is not a date filter, so it cannot be held to one day.',
  'dashboard.field.options-empty':
    'The filter {field} picks from a list of its own, and the list is empty.',
  'dashboard.field.required-no-default':
    'The filter {field} always has a value, so it needs a default to start at.',
  'dashboard.fields.too-many': 'A dashboard holds at most {max} filters.',
  'dashboard.filter.unknown': 'This dashboard has no filter {field}.',
  'dashboard.filter.held':
    'The page holds the filter {field}; it cannot be changed here.',
  'dashboard.scope.unsupported':
    'A dashboard takes no outer condition: lock or hide its filters instead.',
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
  'dashboard.markdown.too-long': 'A text panel holds at most {max} characters.',
  'dashboard.panel.definition-unknown':
    'The data the analysis in this panel was built on is no longer available.',
  'dashboard.panel.failed': 'The view this panel shows could not be opened.',
  'dashboard.panel.id-duplicate': 'Two panels share the id {id}.',
  'dashboard.panel.id-empty': 'A panel needs an id.',
  'dashboard.panel.kind-unsupported':
    'This panel points at something that is not a record or analysis view.',
  'dashboard.panel.not-owned':
    'Only an analysis that lives in this dashboard can be saved as a view.',
  'dashboard.panel.not-referenced':
    'This panel does not show a saved view you have open, so there is nothing to copy.',
  'dashboard.panel.owned-invalid':
    'The analysis this panel holds is not in the expected shape.',
  'dashboard.panel.opens-invalid':
    "The view this panel was set to open in the workbench is not named properly, so it opens the panel's own view.",
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
  'dashboard.width.unknown':
    'This dashboard’s width “{width}” is not one this release knows, so it is shown full width. Choose fixed width or full width while editing.',
} as const satisfies Record<string, string>;

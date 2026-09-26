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
 * Building a board, batch B3 (D22 C–E): the tab bar, a new analysis made
 * inside the dashboard and saving it as a view, and a panel's own look.
 */
export const buildingMessages = {
  // The tab bar (D22 E). A tab with no name is called by its place.
  'label.tabs.name': 'Tabs',
  'label.tabs.add': 'Add a tab',
  // What a new tab is called until it is renamed, and what the tab the
  // panels already on the board go onto is called when the first one is
  // added.
  'label.tabs.new-title': 'Tab {index}',
  'label.tabs.first-title': 'Overview',
  'label.tabs.actions': 'Tab “{title}”',
  'label.tabs.rename': 'Rename',
  'label.tabs.rename-field': 'Name of tab “{title}”',
  'label.tabs.remove': 'Delete tab',
  'label.tabs.reorder': 'Reorder “{title}”',
  'label.tabs.picked': 'Picked up the tab “{title}”.',
  'label.tabs.cancelled': 'The tab “{title}” stayed where it was.',
  'label.tabs.moved': '“{title}” is now tab {index} of {total}',
  'label.tabs.removed': 'The tab “{title}” was deleted',
  'label.tabs.remove-heading': 'Delete the tab “{title}”?',
  'label.tabs.remove-description':
    'Its {count} panels are deleted with it. Nothing is saved until you finish editing; Undo brings them back.',
  'label.tabs.remove-description-one':
    'Its one panel is deleted with it. Nothing is saved until you finish editing; Undo brings it back.',
  'label.tabs.remove-confirm': 'Delete tab and panels',
  // A tab with nothing on it, as the empty board says the same of a board.
  'label.tabs.empty': 'This tab has no panels yet',
  'label.tabs.empty-hint':
    'Add a view or an analysis while it is shown, or move a panel here from another tab.',
  'label.panel.moved-to-tab': '“{title}” moved to the tab “{tab}”',

  // A new analysis made inside the dashboard (D22 C).
  'label.panel.new-analysis.heading': 'New analysis',
  'label.panel.new-analysis.heading-of': 'New analysis · {definition}',
  'label.panel.new-analysis.description':
    'It belongs to this dashboard: saved, shared and deleted with it, and never listed among the views.',
  'label.panel.new-analysis.data': 'Data',
  'label.panel.new-analysis.pick': 'Choose the data to analyse',
  'label.panel.new-analysis.pick-hint':
    'Only data that can be analysed is listed here.',
  'label.panel.new-analysis.none': 'None of the data here can be analysed',
  'label.panel.new-analysis.failed': 'This data could not be opened: {reason}',
  // The visualization panel's way back, where there is no view list to go
  // back to.
  'label.panel.new-analysis.close-visualization': 'Close the visualization',
  'label.panel.new-analysis.title': 'Title',
  'label.panel.new-analysis.title-hint':
    'Named after what it shows until you name it.',
  'label.panel.new-analysis.add': 'Put on the dashboard',
  'label.panel.new-analysis.blocked':
    'Fix what the analysis says above before putting it on the dashboard.',
  'label.panel.new-analysis.added': '“{title}” was put on the dashboard',
  'label.panel.new-analysis.full':
    'This dashboard holds as many panels as it can.',

  // Saving an analysis the board owns as a view of its own (D22 C).
  'label.panel.save-owned.heading': 'Save as a view',
  'label.panel.save-owned.description':
    'It becomes a view of {definition} that can be opened in the workbench, and this panel shows that view from now on.',
  'label.panel.save-owned.submit': 'Save view',
  'label.panel.save-owned.saved':
    '“{title}” was saved as a view; this panel shows it now',

  // A shared board's panel on someone's personal view, copied for its readers (D22 B).
  'label.panel.copy-shared.heading': 'Copy as a shared view and replace',
  'label.panel.copy-shared.description':
    '“{view}” is a personal view, so the other readers of this shared dashboard see nothing in this panel. It is copied as a shared view of {definition}, saved right away, and this panel shows the copy — looking and filtering as it does now. The personal view stays as it is. Done saves the dashboard.',
  'label.panel.copy-shared.submit': 'Copy and replace',
  'label.panel.copy-shared.saved':
    '“{title}” was copied as a shared view; this panel shows it now',

  // A panel's own look (D22 D).
  'label.panel.presentation.reset': 'Look as the view does',
  'label.panel.presentation.heading': 'How “{title}” looks here',
  'label.panel.presentation.description':
    'Only this panel changes. The view it shows keeps its own look, and what it asks stays the same.',
  'label.panel.presentation.done': 'Done',
  'label.panel.presentation.preview': 'This panel as it will look',
  'label.panel.presentation.nothing':
    'This panel has no result to draw yet. Try again once it has one.',
  // The mark on a panel whose look is its own, not its view's.
  'label.panel.presentation.as': 'Shown here as {type}',
  'label.panel.presentation.changed': 'Shown differently here',
  'label.panel.presentation.note':
    'This panel looks different from the view it shows, on purpose: it was changed for this dashboard only.',
} as const satisfies Record<string, string>;

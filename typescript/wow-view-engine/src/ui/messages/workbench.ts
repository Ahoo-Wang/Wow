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
 * The frame around a view rather than the view itself: the sidebar's two
 * states, the switcher that stands in for the list once it is away, and the
 * two states of the view filling the screen.
 *
 * They are the accessible names of icon-only controls, which is why they
 * describe what happens rather than what is drawn: "Hide the view list" is
 * what the button does, "Collapse" is only where it points.
 */
export const workbenchMessages = {
  'label.workbench.collapse-sidebar': 'Hide the view list',
  'label.workbench.expand-sidebar': 'Show the view list',
  // Not "Full screen": that is the browser's own, on F11, and it takes the
  // address bar with it. This one says what actually happens — the view
  // fills the page it is on — and the way back says the same in reverse.
  'label.workbench.expand-view': 'Fill the screen',
  'label.workbench.collapse-view': 'Leave full screen',
  // The switcher is the list while the list is away, so it is named for the
  // job and not for the shape: a user never reads "dropdown".
  'label.workbench.switch-view': 'Switch view',
  // The same control with no view behind it — the screen that reports one
  // that will not open, and the moment before the first one arrives. It
  // *is* the label then, so the button reads the same to the eye and to a
  // screen reader; "Switch view" over a blank button would name a view that
  // is not there and offer to leave it.
  'label.workbench.choose-view': 'Choose a view',
  // Said once, by the live region beside the opening skeleton. The shape on
  // screen is what the page will look like; this is what it means.
  'label.workbench.opening': 'Opening the view',
  // The chevron beside the editor's toggle. "Options" rather than "modes":
  // the filter puts its two modes in there, another editor may put something
  // else, and the name has to fit whatever the editor offers.
  'label.workbench.editor-modes': 'Editor options',
} as const satisfies Record<string, string>;

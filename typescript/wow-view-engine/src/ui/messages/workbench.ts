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
 * states, and the switcher that stands in for the list once it is away.
 *
 * All three are accessible names of icon-only controls, which is why they
 * describe what happens rather than what is drawn: "Hide the view list" is
 * what the button does, "Collapse" is only where it points.
 */
export const workbenchMessages = {
  'label.workbench.collapse-sidebar': 'Hide the view list',
  'label.workbench.expand-sidebar': 'Show the view list',
  // The switcher is the list while the list is away, so it is named for the
  // job and not for the shape: a user never reads "dropdown".
  'label.workbench.switch-view': 'Switch view',
  // The chevron beside the editor's toggle. "Options" rather than "modes":
  // the filter puts its two modes in there, another editor may put something
  // else, and the name has to fit whatever the editor offers.
  'label.workbench.editor-modes': 'Editor options',
} as const satisfies Record<string, string>;

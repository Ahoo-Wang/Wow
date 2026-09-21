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
 * Who a view is for.
 *
 * The audience picker asks it of the person saving; the list answers it with
 * the group a view sits in, so the two are worded differently and keyed apart.
 * Nothing names the shared or personal audience on the row itself — the group
 * above it already did.
 */
export const scopeMessages = {
  'label.scope.only-me': 'Only me',
  'label.scope.everyone': 'Everyone',
  // The two headings that divide the navigation column (D12). They name what
  // is under them — views — rather than the audience in the abstract: a lone
  // "Personal" above a list of names reads as a property of the heading, and
  // the same two words have to serve as the switcher's menu sections too.
  'label.scope.group.personal': 'My views',
  'label.scope.group.shared': 'Shared views',
  'label.scope.system': 'Shipped with the definition',
  'label.scope.tag.system': 'system',
  'label.scope.tag.personal': 'personal',
  'label.scope.tag.shared': 'shared',
  'label.scope.personal.description': 'Only you see it.',
  'label.scope.shared.description': 'Everyone who uses this data sees it.',
  // A scope the user cannot create in is offered and disabled rather than
  // hidden: an option that is missing looks like an option that is gone.
  'label.scope.no-permission': '(no permission to create)',
  // Not an audience: the host asked to narrow this view and the view would
  // not take it. It is keyed here because it is the same `label.scope` family.
  'label.scope.refused': 'This page could not narrow this view',
} as const satisfies Record<string, string>;

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

/** A view itself: which kind it is, the list it sits in, and the commands. */
export const viewMessages = {
  // Which kind of view a row names: one data definition holds record and
  // analysis views together, so the list says which is which.
  'label.kind.record': 'Record view',
  'label.kind.analysis': 'Analysis view',
  'label.kind.dashboard': 'Dashboard',
  'label.view.list': 'Views',
  'label.view.list-failed': 'The list could not be loaded.',
  'label.view.none-hint': 'Save the current conditions to make one.',
  'label.view.unopenable': 'This view could not be opened',
  'label.view.needs-fixing': 'This view needs fixing before it runs',
  'label.view.warnings': 'Worth noting',
  // Several of them collapse to one line, so the line has to say how many.
  'label.view.warnings-count': '{count} things worth noting',
  'label.view.none': 'No view yet',

  // Runtime and commands.
  'runtime.kind.not-declared': '{definition} does not offer a {kind} view.',
  'runtime.options.unresolved':
    'No candidate source is configured for {source}.',
  'runtime.query.failed': 'The source answered: {reason}',
  'runtime.query.queue-full':
    'Too many queries at once; try again in a moment.',
  'view.abandon.failed': 'That write could not be set aside.',
  'view.config.invalid': 'Fix what this view reports before saving it.',
  'view.create.forbidden': 'You may not create views here.',
  'view.definition.invalid':
    'The {id} definition has {issues} problem(s) and cannot be opened.',
  'view.definition.not-found': 'No definition named {id}.',
  'view.delete.failed': 'This view could not be deleted.',
  'view.list.failed': 'The list of views could not be loaded.',
  'view.list.reserved-id':
    'The stored view {id} uses a reserved id and was skipped.',
  'view.open.failed': 'This view could not be opened.',
  'view.open.not-found': 'No view named {id}.',
  'view.open.wrong-kind':
    'This view is of another kind ({kind}), so this page cannot show it.',
  'view.preferences.default-forbidden': 'You may not set the default view.',
  'view.preferences.failed': 'Your view preferences could not be saved.',
  'view.preferences.reorder-forbidden': 'You may not reorder views.',
  'view.rename.failed': 'This view could not be renamed.',
  'view.resolve.failed': 'That conflict could not be resolved.',
  'view.retry.failed': 'That write could not be retried.',
  'view.runtime.not-owned': 'This view is not open here any more.',
  'view.save-as.failed': 'This view could not be saved as a copy.',
  'view.save.failed': 'This view could not be saved.',
  'view.system.read-only': 'A built-in view cannot be changed ({action}).',
  'view.title.empty': 'A view needs a title.',
  'view.write.conflict-unreadable':
    'Someone else saved first, and their version could not be read.',
  'view.write.not-a-conflict': 'That write is {kind}, not a conflict.',
  'view.write.not-pending': 'That write is already settled.',
  'view.write.in-flight':
    'This view is already being saved; wait for that to finish.',
  'view.write.unknown-pending':
    'The last {action} has not been confirmed yet. Retry it or abandon it before writing again.',
  'view.write.conflict': 'Someone else saved this view first.',
  'view.write.forbidden': 'You may not write to this view.',
  'view.write.invalid': 'The server refused this write.',
  'view.write.not_found': 'This view no longer exists.',
  'view.write.unavailable': 'The server could not be reached.',

  // `/react` composes `<command>.<outcome>`; these say more than the command
  // alone, and anything not named here falls back along the dots.
  'view.open.failed.not_found': 'This view no longer exists.',
  'view.open.failed.forbidden': 'You may not open this view.',
  'view.open.failed.unavailable':
    'This view could not be loaded: the server could not be reached.',
  'view.list.failed.unavailable':
    'The list of views could not be loaded: the server could not be reached.',
} as const satisfies Record<string, string>;

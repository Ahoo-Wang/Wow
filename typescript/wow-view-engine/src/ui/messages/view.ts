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
  'label.view.none-hint': 'Make one to start looking at the data.',
  // The command and the name it opens under: one is a verb on a button, the
  // other a title in a heading, and the two catalogues word them apart. The
  // title says the view has no name yet rather than repeating the button —
  // it is what the title bar, the first save's dialog and the list all read,
  // and "New view" there said the command, not the thing (user ruling
  // 2026-09-22, Ⅱ).
  'label.view.new': 'New view',
  'label.view.new-title': 'Untitled view',
  'label.view.unopenable': 'This view could not be opened',
  'label.view.open-default': 'Open the default view',
  'label.view.needs-fixing': 'This view needs fixing before it runs',
  // Several of them collapse to one line, so the line has to say how many.
  'label.view.warnings-count': '{count} things worth noting',
  'label.view.none': 'No view yet',
  // The line under the title bar of a view opened from another (D20): the
  // way back, which names where this one came from — `{title}` is the
  // origin's name, said once. The conditions the follow-up added are the
  // applied bar's to show, not this line's.
  'label.origin.back': 'Back to {title}',
  'label.origin.region': 'Opened from another view',
  // The follow-up menu on one group of an analysis result (D20 追问): the
  // records behind it, the same question by another dimension, or the
  // question asked of the group alone.
  'label.drill.menu': 'This group',
  'label.drill.records': 'See these records',
  'label.drill.split': 'Split this group by…',
  'label.drill.focus': 'Only this group',
  // A view opened from a group, named by what it is: `{subject}` is the
  // records' name (the definition's) or the view it narrows, `{group}` the
  // group pressed as the menu heads it.
  'label.drill.titled': '{subject} · {group}',
  // A date dimension of the group pressed, as its column prints the bucket;
  // a week's value is only the day it starts.
  'label.drill.bucket': '{field} in {bucket}',
  'label.drill.bucket-week': '{field} in the week of {bucket}',

  // Runtime and commands.
  'runtime.kind.not-declared': '{definition} does not offer a {kind} view.',
  'runtime.options.unresolved':
    'No candidate source is configured for {source}.',
  'runtime.query.failed': 'The source answered: {reason}',
  'record.detail.failed': 'The whole record could not be read: {reason}',
  'runtime.query.queue-full':
    'Too many queries at once; try again in a moment.',
  // The summary row survived its own query failing, at a narrower scope than
  // it was asked for. The row says which scope it is; this says why.
  'runtime.summary.page-only':
    'The totals query failed, so the summary adds up only the rows on this page.',
  'view.abandon.failed': 'That write could not be set aside.',
  'view.change.notify-failed':
    'A listener on view changes failed: {reason}. The list may be a revision behind.',
  'view.config.invalid': 'Fix what this view reports before saving it.',
  'view.create.forbidden': 'You may not create views here.',
  'view.definition.invalid':
    'The {id} definition has {issues} problem(s) and cannot be opened.',
  'view.definition.not-found': 'No definition named {id}.',
  'view.delete.failed': 'This view could not be deleted.',
  // One per action the permission guard refuses (`view.<action>.forbidden`,
  // `runtime/permissions.ts`). They used to have no entry at all, so the
  // code itself reached the screen; each says which command was refused and
  // what is left to do about it, since the store's answer is not the user's
  // to change (B1).
  'view.delete.forbidden':
    'You may not delete this view; ask whoever owns it to remove it.',
  'view.list.failed': 'The list of views could not be loaded.',
  'view.list.reserved-id':
    'The stored view {id} uses a reserved id and was skipped.',
  'view.open.failed': 'This view could not be opened.',
  'view.open.not-found': 'No view named {id}.',
  'view.open.wrong-kind':
    'This view is of another kind ({kind}), so this page cannot show it.',
  'view.preferences.default-forbidden': 'You may not set the default view.',
  'view.preferences.failed': 'Your view preferences could not be saved.',
  // Loading them is the other half, and a different sentence: the list is
  // still here, in the order the server gave it.
  'view.preferences.load-failed':
    'Your view preferences could not be loaded; the views are in the server’s order.',
  'view.preferences.reorder-forbidden': 'You may not reorder views.',
  'view.rename.failed': 'This view could not be renamed.',
  'view.rename.forbidden':
    'You may not rename this view; save a copy of your own instead.',
  'view.resolve.failed': 'That conflict could not be resolved.',
  'view.retry.failed': 'That write could not be retried.',
  'view.runtime.not-owned': 'This view is not open here any more.',
  'view.save-as.failed': 'This view could not be saved as a copy.',
  'view.save.failed': 'This view could not be saved.',
  'view.save.forbidden':
    'You may not save changes to this view; save a copy of your own instead.',
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

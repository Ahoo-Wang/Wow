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
 * The save commands, their dialogs, and what a write settled as.
 *
 * Conflict and delete carry the most weight of anything this package says, so
 * they are the last copy that should be stranded in JSX where no application
 * can reword or translate it.
 */
export const saveMessages = {
  'label.save.save': 'Save',
  'label.save.save-as': 'Save as',
  'label.save.revert': 'Revert',
  'label.save.title': 'Title',
  'label.save.audience': 'Who can see it',
  'label.save.group': 'View actions',
  'label.save.saving': 'Saving…',
  // What the button says once a save has landed, and what a screen reader is
  // told: the button's own word is read off a control the user just pressed,
  // so on its own it announces nothing.
  'label.save.saved': 'Saved',
  'label.save.saved-announce': 'View saved',
  // Saving over a shared view changes it for everyone who sees it, so the
  // one-click save asks first (2026-09-23 audit); a personal view is only
  // the author's, and saves as it always did.
  'label.save.shared-heading': 'Update it for everyone?',
  'label.save.shared-description':
    'This updates “{title}” for everyone who can see it.',
  'label.save.shared-confirm': 'Update for everyone',
  // Named, because the dialog covers the row it is about: a manager row is
  // one of a list, the confirmation opens over it, and "this view" then
  // points at something the reader can no longer see.
  'label.delete.confirm': 'Delete “{title}”?',
  // The base sentence says the one thing that is true of every delete, and
  // says it without the two below: they are composed after it, and a base
  // that already spoke for everyone would repeat the shared one.
  'label.delete.consequence': 'Only the view is removed; its records stay.',
  // Said only when they apply, and always after the sentence above: a shared
  // view is somebody else's too, and a dirty one takes edits down with it.
  'label.delete.shared-consequence': 'Everyone who uses it loses it.',
  'label.delete.dirty-consequence': 'Unsaved changes go with it.',
  'label.delete.keep': 'Keep it',
  'label.dialog.cancel': 'Cancel',
  'label.dialog.close': 'Close',
  'label.save-as.heading': 'Save as a new view',
  'label.save-as.description': 'The view you are looking at stays as it is.',
  'label.save-as.copy-title': '{title} copy',
  'label.save-as.submit': 'Create view',
  // The first save of a view made from nothing: a create, so the same form
  // asks the same two questions, under a heading that says what this is.
  'label.save.first-heading': 'Save this view',
  'label.save.first-description': 'Name it and say who it is for.',
  'label.conflict.choice':
    'Take their version and lose your edits, or write yours over theirs.',
  'label.conflict.theirs': 'Take theirs',
  'label.conflict.mine': 'Keep mine',
  'label.conflict.copy': 'Save my copy',
  // Either choice loses something, so each is put once more as a question,
  // with the two configs side by side under these two headings.
  'label.conflict.confirm-theirs': 'Take their version?',
  'label.conflict.confirm-mine': 'Write yours over theirs?',
  'label.conflict.local': 'Mine',
  'label.conflict.remote': 'Theirs',
  'label.conflict.summary.record':
    '{pageSize} per page · {layout} · {columns} columns · {sorts} sorts',
  'label.conflict.summary.analysis':
    '{groups} dimensions · {metrics} metrics · top {limit} groups',
  'label.conflict.summary.dashboard': '{count} panels',
  'label.conflict.summary.dashboard-one': '1 panel',
  // A conflicting config comes from the store as it is: this release may
  // never have written it, and a shape the summary cannot count says so
  // rather than taking the dialog down with it.
  'label.conflict.summary.malformed': 'Cannot be read',
  // Not "Leave it": the button clears the write state, so the intent behind
  // it is given up and nobody can retry or overwrite it afterwards. The
  // engine calls that `abandonWrite`, and so should the word on the button.
  'label.unknown.leave': 'Abandon',
  'label.unknown.retry': 'Try again',
  // A refusal never reached the store, so there is nothing to recover — only
  // a line to take down once it has been read, which is what frees the view
  // to be written again.
  'label.rejected.dismiss': 'Dismiss',

  // Leaving a view with edits nobody has saved, or with a write whose result
  // never came back.
  'label.leave.heading': 'Leave this view?',
  'label.leave.consequence': 'Unsaved changes will be lost.',
  'label.leave.stay': 'Stay',
  'label.leave.leave': 'Leave',

  // Reverting loses exactly what leaving loses, so it is asked in the same
  // words: a question naming what goes back, one sentence for the cost, the
  // answer that stays and the answer named after the command it carries out.
  'label.revert.heading': 'Go back to the saved version?',
  'label.revert.consequence': 'Unsaved changes will be lost.',
  'label.revert.keep': 'Keep editing',
} as const satisfies Record<string, string>;

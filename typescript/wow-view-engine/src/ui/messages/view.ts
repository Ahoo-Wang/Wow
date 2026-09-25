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
  'label.origin.board-region': 'Opened from a dashboard',
  // The follow-up menu on one group of an analysis result (D20 追问): the
  // records behind it, the same question by another dimension, or the
  // question asked of the group alone.
  'label.drill.menu': 'This group',
  'label.drill.records': 'See these records',
  'label.drill.gap.nested-elements':
    'Expanded more than one level deep: a condition on the records matches the first level only.',
  'label.drill.split': 'Split this group by…',
  'label.drill.focus': 'Only this group',
  // The same menu over a stretch of a time axis (D33 Q52): brushed along the
  // chart, or two rows of the table picked with Shift. `{filter}` is a
  // dashboard's date filter the stretch can be set into; `{group}` the
  // stretch as the menu heads it.
  'label.drill.menu-span': 'This period',
  'label.drill.split-span': 'Split this period by…',
  'label.drill.focus-span': 'Only this period',
  'label.drill.set-filter': 'Set “{filter}” to this period',
  'label.drill.spanned': 'Selected {group}. Follow-up menu open.',
  'label.drill.span-hint':
    'Hold Shift and pick a second row to follow up on the period between the two.',
  'label.drill.tap-again': 'Tap again to follow up',
  // A view opened from a group, named by what it is: `{subject}` is the
  // records' name (the definition's) or the view it narrows, `{group}` the
  // group pressed as the menu heads it.
  'label.drill.titled': '{subject} · {group}',

  // Runtime and commands.
  'runtime.kind.not-declared': '{definition} does not offer a {kind} view.',
  'runtime.options.unresolved':
    'No candidate source is configured for {source}.',
  // Said to whoever reads the screen, not to whoever wrote the source: what
  // did not happen, in their words, then the source's own reason. 「The
  // source answered」 read as a transcript of a machine, and a failure
  // worded for its reader is one they know what to do about — the line
  // ends in Try again.
  'runtime.query.failed': 'Could not load the data: {reason}',
  // The source refused the reader rather than the query (HTTP 403, Wow's
  // IllegalAccess* codes): a permission, which asking again does not change,
  // so it is said as one and offers no retry.
  'runtime.query.forbidden': 'You do not have permission to view this data.',
  // A query a Wow service rejected names the rule it broke
  // (`runtime.query.failed.<code>`, D40): each worded for the reader, the
  // service's own words where they say what the rule alone cannot. A rule
  // a newer service adds falls back along the dots to the line above.
  'runtime.query.failed.invalid_json':
    'The service could not read the query this view sent: {reason}',
  'runtime.query.failed.body_not_object':
    'The service could not read the query this view sent: {reason}',
  'runtime.query.failed.empty_body':
    'The service received an empty query: {reason}',
  'runtime.query.failed.unknown_property':
    'The service does not understand part of this query; it may be older than this view engine: {reason}',
  'runtime.query.failed.unknown_type':
    'The service does not know an operator or metric this view uses; it may be older than this view engine: {reason}',
  'runtime.query.failed.unknown_value':
    'The service does not know a value this view sent: {reason}',
  'runtime.query.failed.invalid_value':
    'A value in this query is missing or of the wrong kind: {reason}',
  'runtime.query.failed.invalid_request':
    'The service refused the query: {reason}',
  'runtime.query.failed.unknown_field':
    'The service does not know the field {field}. The view may be ahead of the data; remove the condition, column or group on it.',
  'runtime.query.failed.unsupported_capability':
    '{field} cannot be used this way on this data: {reason}',
  'runtime.query.failed.element_scope_required':
    "{field} can only be filtered inside its list, with a condition on the list's elements.",
  'runtime.query.failed.value_mismatch': 'A value is not one {field} can hold.',
  'runtime.query.failed.not_collection':
    '{field} is not a list, so it cannot be checked for being empty.',
  'runtime.query.failed.not_single_string':
    '{field} is not a single text value, so it cannot be checked for being empty.',
  'runtime.query.failed.model_search_unsupported':
    'This data does not support search.',
  'runtime.query.failed.cursor_not_allowed':
    '{field} cannot be used to page through this data.',
  'runtime.query.failed.protected_aggregation':
    '{field} is protected and cannot be summarised.',
  'runtime.query.failed.protected_comparison':
    '{field} is protected and cannot be used to filter, sort or search.',
  'runtime.query.failed.missing_key_requires_string':
    'Only a single text field can group its missing values; {field} is not one.',
  'runtime.query.failed.any_requires_single_value':
    '“Any value” needs a field that holds a single value.',
  'runtime.query.failed.incomplete_projection':
    'The service cannot return whole records here; choose the columns to show.',
  'runtime.query.failed.metric_filter_search':
    "A metric's own condition cannot use search.",
  'runtime.query.failed.metric_filter_element_match':
    "A metric's own condition cannot match list elements.",
  'runtime.query.failed.metric_filter_array_field':
    "A metric's own condition cannot use the list field {field}.",
  'runtime.query.failed.cursor_sort_duplicate':
    'The sort names {field} twice; keep it once.',
  'runtime.query.failed.cursor_sort_too_many':
    'The sort has too many fields to page through this data; remove some.',
  'runtime.query.failed.not_projectable':
    '{field} cannot be shown as a column of this data.',
  'runtime.query.failed.event_projection_type_required':
    "An event's payload cannot be shown without its type; keep the event type column.",
  'runtime.query.failed.temporal_representation_required':
    '{field} is not stored as a known date or time, so a relative time cannot be asked of it.',
  'runtime.query.failed.temporal_configuration_conflict':
    'The relative time on {field} does not agree with how the field keeps its time: {reason}',
  'runtime.query.failed.parallel_array_sort':
    '{field} and another list field in the sort cannot be sorted by together; keep one of them.',
  'runtime.query.failed.array_equality':
    '{field} cannot be compared with a whole list here; match its items instead.',
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
  // A record the source refused to this reader (HTTP 401/403) — a link to
  // one outside what they may see. Trying again changes nothing, so the
  // sentence says whose call it is rather than what failed.
  'record.detail.forbidden': 'You do not have permission to read this record.',

  // `/react` composes `<command>.<outcome>`; these say more than the command
  // alone, and anything not named here falls back along the dots.
  'view.open.failed.not_found': 'This view no longer exists.',
  'view.open.failed.forbidden': 'You may not open this view.',
  'view.open.failed.unavailable':
    'This view could not be loaded: the server could not be reached.',
  'view.list.failed.unavailable':
    'The list of views could not be loaded: the server could not be reached.',
} as const satisfies Record<string, string>;

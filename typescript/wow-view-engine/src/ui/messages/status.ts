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
 * What the view says about itself: what failed, what is still on screen, and
 * whether something is running right now.
 */
export const statusMessages = {
  'label.status.more': '{count} more',
  // Said once the findings are open: the button still folds them away, and
  // "{count} more" beside what is already on screen promises more of it.
  'label.status.less': 'Show less',
  // The busy announcement. `components/spinner.tsx` is vendored and hardcodes
  // `aria-label="Loading"`, so every call site hands it this instead — the
  // seam is sewn where the component is used.
  'label.status.loading': 'Loading',
  // Said out loud when a query starts, by the one live region of the
  // surface it started on. The spinner beside the refresh control says the
  // same thing to whoever can see it; this is the other channel.
  'label.status.querying': 'Running the query',
  // What an analysis query landed with; the record view's rows say their
  // pagination sentence instead.
  'label.status.groups': '{count} groups',
  'label.status.groups-one': '1 group',
  // The way out of the one error the strip says outright: the columns are
  // what a record view's own findings are nearly always about — a column
  // the definition no longer has, one pinned where it may not be — and the
  // panel that fixes them is the one the toolbar opens. It names the panel
  // rather than the fix, because the fix is the reader's to choose.
  'label.status.open-columns': 'Open column settings',
  'label.query.failed': 'The query failed',
  'label.query.forbidden': 'No permission',
  // A failed query does not clear the table: what is on screen is the last
  // result that did come back, and saying so is the only way to know. It is
  // said on the failure's own line, after the failure (`{error}`).
  'label.query.stale': '{error} · Showing the last successful result',
  'label.query.retry': 'Try again',
  'label.write.conflict': 'Someone else saved this view first',
  'label.write.unknown': 'The result never came back',
  'label.image.failed': 'This image could not be loaded',
  // An image that is a link and has no words of its own still needs a name.
  'label.image.link': 'Open the linked page',
} as const satisfies Record<string, string>;

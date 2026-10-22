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

import {
  coverageCell,
  curatedCoverage as curatedCoverageIn,
  firstSentence,
  itemsOf,
  parseIndex,
  referenceFolder,
  renderIndex as renderIndexOf,
} from '../../../.github/scripts/symbol-index.mjs';

/**
 * The view engine's API reference index: the shared generator
 * (`.github/scripts/symbol-index.mjs`) bound to this package's entries and
 * pages. `scripts/api-report.mjs` renders the pages; `test/referenceDocs.test.ts`
 * holds the names and the coverage column without a build.
 */

/** The English reference folder, where the index pages are written and the curated pages read. */
export const REFERENCE = referenceFolder('wow-view-engine');

/** Each entry's report name → its specifier and its index page. */
export const INDEX_PAGES = {
  root: { entry: '@ahoo-wang/wow-view-engine', page: 'symbols.md' },
  react: {
    entry: '@ahoo-wang/wow-view-engine/react',
    page: 'symbols-react.md',
  },
  ui: { entry: '@ahoo-wang/wow-view-engine/ui', page: 'symbols-ui.md' },
  testing: {
    entry: '@ahoo-wang/wow-view-engine/testing',
    page: 'symbols-testing.md',
  },
  'react-router': {
    entry: '@ahoo-wang/wow-view-engine/react-router',
    page: 'symbols-react-router.md',
  },
};

export { coverageCell, firstSentence, itemsOf, parseIndex };

/** Which curated page covers each name. */
export function curatedCoverage(dir = REFERENCE) {
  return curatedCoverageIn(dir);
}

/** One entry's index page. */
export function renderIndex(report, items, coverage) {
  return renderIndexOf({
    packageDir: 'wow-view-engine',
    entry: INDEX_PAGES[report].entry,
    report,
    items,
    coverage,
  });
}

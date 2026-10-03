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

/** The English reference folder the index pages live in; see the `.mjs`. */
export const REFERENCE: string;

/** Each entry's report name → its specifier and its index page. */
export const INDEX_PAGES: Readonly<
  Record<
    'root' | 'react' | 'ui' | 'testing' | 'react-router',
    { entry: string; page: string }
  >
>;

/** One exported name as the index lists it. */
export interface IndexItem {
  name: string;
  kind: string;
  summary: string;
}

/** Where a curated page covers a name. */
export interface Covered {
  title: string;
  href: string;
}

export function firstSentence(docComment: string | undefined): string;

export function itemsOf(apiJson: unknown): IndexItem[];

export function curatedCoverage(dir?: string): Map<string, Covered>;

export function coverageCell(
  name: string,
  coverage: ReadonlyMap<string, Covered>,
): string;

export function renderIndex(
  report: keyof typeof INDEX_PAGES,
  items: readonly IndexItem[],
  coverage: ReadonlyMap<string, Covered>,
): string;

export function parseIndex(markdown: string): Map<string, string>;

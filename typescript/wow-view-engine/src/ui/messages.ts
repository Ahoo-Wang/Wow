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

import type { Issue } from '../model/index.js';
import { formatNumber } from './display.js';
import { en } from './messages/en.js';

/**
 * Wording, by key.
 *
 * The model carries `code` and `params` and no copy at all, which is what
 * lets an application translate or reword any of it. This is the other half
 * of that decision: without a catalogue the codes reach the screen, and
 * `record.summary.unsupported` is not a sentence anybody should read.
 *
 * Two namespaces share one flat map: an issue's `code`, and a `label.*` key
 * for the text a component writes itself. A missing key falls back to the key,
 * so a gap shows up as the code it always used to show rather than as nothing.
 *
 * A host's overrides stay a plain string map: it may key wording of its own
 * off the same catalogue, and nothing here ever reads a key it did not ship.
 */
export type ViewMessages = Readonly<Record<string, string>>;

/**
 * Every key this package ships, as a union.
 *
 * It is what a component may ask for by name, so a key deleted from the
 * catalogue — or misspelt in JSX — is a compile error rather than a code on
 * screen. It does not constrain what a host adds: `ViewMessages` stays open.
 */
export type MessageKey = keyof typeof en;

/** `{field}` and friends are replaced from `Issue.params`. */
const PLACEHOLDER = /\{(\w+)\}/g;

/**
 * One entry with its params filled in.
 *
 * A param that is a number is a quantity — a count, a total, a page, a
 * limit — and it is printed the way every other number on the surface is
 * (`formatNumber`, in `locale`): 「共 624,082 条记录」 beside a summary that
 * reads 「534,897」, not 「共 624082 条记录」. A caller whose number is a name
 * rather than an amount — an id, a year, a row key — hands it over as a
 * string, and a string is left exactly as it came.
 */
export function formatMessage(
  messages: ViewMessages,
  key: string,
  params?: Issue['params'],
  locale?: string,
): string {
  const template = lookup(messages, key);
  if (template === undefined) return key;
  if (!params) return template;
  return template.replace(PLACEHOLDER, (whole, name: string) => {
    const value = params[name];
    if (value === undefined) return whole;
    return typeof value === 'number'
      ? formatNumber(value, undefined, locale)
      : value;
  });
}

/**
 * The closest entry: the key itself, else the longest prefix that has one.
 *
 * `/react` composes a code from a command and what the store said, as in
 * `view.open.failed.not_found`, so the catalogue would otherwise need an
 * entry for every command crossed with every store code. Falling back along
 * the dots keeps it to the commands, and an application that wants to name
 * one of those combinations still can, by adding the longer key.
 */
function lookup(messages: ViewMessages, key: string): string | undefined {
  let candidate = key;
  for (;;) {
    const found = messages[candidate];
    if (found !== undefined) return found;
    const cut = candidate.lastIndexOf('.');
    if (cut < 0) return undefined;
    candidate = candidate.slice(0, cut);
  }
}

/** One issue as a sentence. */
export function formatIssue(
  messages: ViewMessages,
  issue: Issue,
  locale?: string,
): string {
  return formatMessage(messages, issue.code, issue.params, locale);
}

/** Several issues, in order, joined for one alert. */
export function formatIssues(
  messages: ViewMessages,
  issues: readonly Issue[],
  locale?: string,
): string {
  return issues.map(found => formatIssue(messages, found, locale)).join(' ');
}

/**
 * The English catalogue, which is what every component reads until a host
 * says otherwise. The keys live in `messages/`, one file per prefix family.
 */
export const defaultMessages: ViewMessages = Object.freeze(en);

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
 * What the engine says of its findings while a host is being built
 * (host-integration.md 4, 「开发期的 onIssue 缺省」): a host that passes no
 * `onIssue` still hears, in development, what the engine found about the
 * resources it registered — grouped by resource, each finding with how to
 * fix it. In production, and under a test runner, it says nothing.
 */

import type { Issue } from '../../model/index.js';

/** Told of one finding, with the resource it is about where there is one. */
export type IssueReporter = (found: Issue, resource?: string) => void;

/** The part of `console` the report writes to. */
export interface IssueConsole {
  groupCollapsed(...data: unknown[]): void;
  groupEnd(): void;
  warn(...data: unknown[]): void;
  error(...data: unknown[]): void;
  info(...data: unknown[]): void;
}

/**
 * How to fix what a code says, by the longest prefix of it listed here.
 * Written for whoever works on the host, never shown to its readers, so it
 * is English only and not in the message catalogue.
 */
const HINTS: readonly (readonly [string, string])[] = [
  [
    'definition.text.unknown',
    "Give the key words in the Provider's `messages` (or `ViewEngineOptions.text`).",
  ],
  [
    'definition.text.fallback',
    "Give the key words in the Provider's `messages` for this language: the engine's starting words are showing in its place.",
  ],
  [
    'definition.source.unregistered',
    'Register the definition with its source: `resources: [{ definition, source }]`.',
  ],
  [
    'binding.definition.unknown',
    'Bind the definition by the id it is registered under: `bind(definition.id, …)`.',
  ],
  [
    'definition.time',
    'Name a time field the definition declares, of a temporal kind (`defineView` `timeField`).',
  ],
  [
    'definition.field',
    'Fix the field in the definition: `defineView` takes the facts from the descriptor, so narrow it rather than restate it.',
  ],
  [
    'definition.view',
    'Fix the system view: a unique id without the separator, of a kind the definition declares.',
  ],
  [
    'definition.descriptor',
    "Commit the source's descriptor snapshot and pass it to `admit` in a test.",
  ],
  [
    'definition.',
    'Fix the definition; `admit` from `@ahoo-wang/wow-view-engine/testing` lists every finding in a unit test.',
  ],
  [
    'capability.descriptor.unavailable',
    "The source's `describe` failed: the views run on the definition as declared until it answers.",
  ],
  [
    'capability.descriptor.disagrees',
    'The service refused what its own descriptor admits: a defect of the service, not the host.',
  ],
  [
    'capability.',
    'The source admits less than the definition declares on this deployment: narrow the definition, or accept that the control is not offered here.',
  ],
  [
    'record.action.unfetched',
    "List the field in the definition's `record.rowFields`: a row brings only the shown columns, the row key, the card's fields and what the sort and the summaries read, so the action's rule reads it as empty on every row.",
  ],
  [
    'view.list.reserved-id',
    "The store returned an id in the system views' namespace; only a definition declares one.",
  ],
  [
    'view.change.notify-failed',
    'A listener given to `engine.subscribe` threw; the write itself landed.',
  ],
];

/** The hint for `code`, or `undefined` for a code with none. */
export function issueHint(code: string): string | undefined {
  let found: readonly [string, string] | undefined;
  for (const entry of HINTS)
    if (
      code.startsWith(entry[0]) &&
      (!found || entry[0].length > found[0].length)
    )
      found = entry;
  return found?.[1];
}

/**
 * A reporter that writes to `sink`, one collapsed group per resource for
 * the findings of the same turn — an engine's start reports every
 * definition's at once — each finding on its own line with its hint.
 */
export function consoleIssueReporter(sink: IssueConsole): IssueReporter {
  const pending = new Map<string, Issue[]>();
  const flush = () => {
    for (const [resource, found] of pending) {
      sink.groupCollapsed(
        `[view-engine] ${resource}: ${found.length} finding${found.length === 1 ? '' : 's'}`,
      );
      for (const entry of found) {
        const at = entry.path.length > 0 ? ` at ${entry.path.join('.')}` : '';
        const hint = issueHint(entry.code);
        const line = `${entry.code}${at}${hint ? ` — ${hint}` : ''}`;
        if (entry.severity === 'error') sink.error(line, entry.params);
        else if (entry.severity === 'warning') sink.warn(line, entry.params);
        else sink.info(line, entry.params);
      }
      sink.groupEnd();
    }
    pending.clear();
  };
  return (found, resource) => {
    const key =
      resource ??
      stringParam(found, 'definition') ??
      stringParam(found, 'source') ??
      'engine';
    if (pending.size === 0) void Promise.resolve().then(flush);
    const list = pending.get(key);
    if (list) list.push(found);
    else pending.set(key, [found]);
  };
}

function stringParam(found: Issue, name: string): string | undefined {
  const value = found.params?.[name];
  return typeof value === 'string' ? value : undefined;
}

// Replaced by the bundler (Vite, webpack, esbuild) as the literal it is; an
// environment that neither defines nor replaces it is not development.
declare const process: { env: { NODE_ENV?: string } };

/** True in a development build: `NODE_ENV` says `development`. */
export function inDevelopment(): boolean {
  try {
    return process.env.NODE_ENV === 'development';
  } catch {
    return false;
  }
}

/**
 * The reporter an engine uses when its host passes no `onIssue`: the
 * console's, in development; none otherwise.
 */
export function defaultIssueReporter(): IssueReporter | undefined {
  return inDevelopment() ? consoleIssueReporter(console) : undefined;
}

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
 * What the column and sort settings are opened against: the shared record
 * controller with its commands replaced by spies, and the formatters a
 * component would have got from a provider. Both let a suite assert what a
 * control writes without an engine, a runtime or a source behind it.
 */

import { vi } from 'vitest';
import type { RecordTableController } from '../../src/react/index.js';
import type { MessageFormatters } from '../../src/ui/MessagesProvider.js';
import {
  formatIssue,
  formatIssues,
  formatMessage,
  type ViewMessages,
} from '../../src/ui/messages.js';
import { recordTableController } from './ui.js';

export function tableController(
  overrides: Partial<RecordTableController> = {},
): RecordTableController {
  return recordTableController({
    // Nothing is on screen here — these suites open a popover, not a table —
    // and every command is a spy, because what is being tested is the call
    // the control makes rather than what a runtime does with it.
    columns: [],
    rows: [],
    paging: null,
    layouts: ['table'],
    columnFields: [],
    maxSortFields: 8,
    setColumns: vi.fn(),
    setColumnOrder: vi.fn(),
    setPinned: vi.fn(),
    setSummary: vi.fn(),
    setSort: vi.fn(),
    ...overrides,
  });
}

/** The formatters a provider would hand down, for a function tested alone. */
export function formattersFor(messages: ViewMessages): MessageFormatters {
  return {
    label: (key, params, fallback) => {
      const found = formatMessage(messages, key, params);
      return found === key && fallback !== undefined ? fallback : found;
    },
    issue: found => formatIssue(messages, found),
    issues: found => formatIssues(messages, found),
  };
}

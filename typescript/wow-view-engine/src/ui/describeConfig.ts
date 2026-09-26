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

import type { ViewConfig } from '../model/index.js';
import type { MessageFormatters } from './MessagesProvider.js';

/**
 * One config as a single line.
 *
 * A conflict asks the user to choose between two configs, and neither is
 * something they can read: a config is a tree of ids. This says the handful
 * of facts that actually differ between two ways of looking at the same
 * data, so the choice is made on what changes rather than on trust.
 *
 * It is a pure function of the config and the wording — no clock, no
 * definition, no field labels — because a conflicting remote config may name
 * fields this release has never heard of.
 *
 * And it reads every part of it defensively, for the same reason. One side of
 * the conflict is whatever the store handed back: a config written by another
 * release, or one that lost its shape. A `.length` on something that is not
 * an array would throw out of a render and take the dialog with it — the one
 * dialog whose whole job is to recover — so a shape the kind does not promise
 * is a sentence saying so, and the choice can still be made on the other side.
 */
export function describeConfig(
  config: ViewConfig,
  messages: MessageFormatters,
): string {
  switch (config.kind) {
    case 'record': {
      const pageSize = numberOf(read(config, 'pageSize'));
      const columns = countOf(read(read(config, 'table'), 'columns'));
      const sorts = countOf(read(config, 'sort'));
      if (pageSize === null || columns === null || sorts === null)
        return unreadable(messages);
      return messages.label('label.conflict.summary.record', {
        pageSize,
        // The layout is a word the catalogue already owns; the stored value
        // is `card`, and the label that names it is the plural one.
        layout: messages.label(
          read(config, 'layout') === 'card'
            ? 'label.layout.cards'
            : 'label.layout.table',
        ),
        columns,
        sorts,
      });
    }
    case 'analysis': {
      const groups = countOf(read(config, 'groups'));
      const metrics = countOf(read(config, 'metrics'));
      const limit = numberOf(read(config, 'limit'));
      if (groups === null || metrics === null || limit === null)
        return unreadable(messages);
      return messages.label('label.conflict.summary.analysis', {
        groups,
        metrics,
        limit,
      });
    }
    case 'dashboard': {
      const panels = countOf(read(config, 'panels'));
      if (panels === null) return unreadable(messages);
      return messages.label('label.conflict.summary.dashboard', {
        count: panels,
      });
    }
    // A kind this release does not know is as unreadable as a broken one,
    // and it is the same sentence: there is nothing here to count.
    default:
      return unreadable(messages);
  }
}

function unreadable(messages: MessageFormatters): string {
  return messages.label('label.conflict.summary.malformed');
}

/** One property of something that was promised to be an object. */
function read(value: unknown, key: string): unknown {
  return typeof value === 'object' && value !== null
    ? (value as Record<string, unknown>)[key]
    : undefined;
}

/** How many entries an array holds; `null` when it is not an array. */
function countOf(value: unknown): number | null {
  return Array.isArray(value) ? value.length : null;
}

/** A number as stored; `null` when it is anything else, `NaN` included. */
function numberOf(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

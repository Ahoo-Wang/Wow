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

import { createContext, useContext, useMemo, type ReactNode } from 'react';
import type { Issue } from '../model/index.js';
import {
  defaultMessages,
  formatIssue,
  formatIssues,
  formatMessage,
  type MessageKey,
  type ViewMessages,
} from './messages.js';

const MessagesContext = createContext<ViewMessages>(defaultMessages);

export interface MessagesProviderProps {
  /**
   * Merged over the wording already in force, so an application overrides
   * what it cares to: the defaults, or what an outer provider set.
   */
  messages?: ViewMessages;
  children: ReactNode;
}

/**
 * The wording every default component reads.
 *
 * `ViewSurface` renders it, so passing `messages` there is enough; this exists
 * on its own for a host that builds its own surface out of the components.
 * Each provider merges over the one above it, not over the defaults: an
 * application that sets its wording once around its views keeps it inside
 * every surface, where a reset used to put the defaults back.
 */
export function MessagesProvider({
  messages,
  children,
}: MessagesProviderProps) {
  const merged = useMerged(useMessages(), messages);
  return (
    <MessagesContext.Provider value={merged}>
      {children}
    </MessagesContext.Provider>
  );
}

export function useMessages(): ViewMessages {
  return useContext(MessagesContext);
}

/** `messages` over `inherited`; the same object when there is nothing to add. */
function useMerged(
  inherited: ViewMessages,
  messages: ViewMessages | undefined,
): ViewMessages {
  return useMemo(
    () => (messages ? { ...inherited, ...messages } : inherited),
    [inherited, messages],
  );
}

/** What a component needs: a label by key, an issue or a list as a sentence. */
export interface MessageFormatters {
  /**
   * Wording by key. `fallback` is for text a component can derive itself:
   * an operator reads acceptably as `between` without a catalogue entry and
   * not at all as `ids`, so the catalogue names the ones worth naming and the
   * rest fall back rather than printing their key.
   *
   * The key is one this package ships (`MessageKey`), so a component cannot
   * ask for wording the catalogue does not carry. A host's own keys are read
   * by the host, from its own map, not through here.
   */
  label(key: MessageKey, params?: Issue['params'], fallback?: string): string;
  issue(found: Issue): string;
  issues(found: readonly Issue[]): string;
}

/**
 * The formatters for the wording in force here, with `messages` merged over
 * it. A workbench passes the wording it hands its own surface: that surface's
 * provider is below it, so without this the workbench's own alerts would miss
 * the translation every component inside it gets.
 */
export function useViewMessages(messages?: ViewMessages): MessageFormatters {
  const merged = useMerged(useMessages(), messages);
  return useMemo(
    () => ({
      label: (key, params, fallback) => {
        const found = formatMessage(merged, key, params);
        return found === key && fallback !== undefined ? fallback : found;
      },
      issue: found => formatIssue(merged, found),
      issues: found => formatIssues(merged, found),
    }),
    [merged],
  );
}

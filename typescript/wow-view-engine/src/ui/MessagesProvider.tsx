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
  type ViewMessages,
} from './messages.js';

const MessagesContext = createContext<ViewMessages>(defaultMessages);

export interface MessagesProviderProps {
  /** Merged over the defaults, so an application overrides what it cares to. */
  messages?: ViewMessages;
  children: ReactNode;
}

/**
 * The wording every default component reads.
 *
 * `ViewSurface` renders it, so passing `messages` there is enough; this exists
 * on its own for a host that builds its own surface out of the components.
 */
export function MessagesProvider({
  messages,
  children,
}: MessagesProviderProps) {
  const merged = useMemo(
    () => (messages ? { ...defaultMessages, ...messages } : defaultMessages),
    [messages],
  );
  return (
    <MessagesContext.Provider value={merged}>
      {children}
    </MessagesContext.Provider>
  );
}

export function useMessages(): ViewMessages {
  return useContext(MessagesContext);
}

/** What a component needs: a label by key, an issue or a list as a sentence. */
export interface MessageFormatters {
  /**
   * Wording by key. `fallback` is for text a component can derive itself:
   * an operator reads acceptably as `between` without a catalogue entry and
   * not at all as `ids`, so the catalogue names the ones worth naming and the
   * rest fall back rather than printing their key.
   */
  label(key: string, params?: Issue['params'], fallback?: string): string;
  issue(found: Issue): string;
  issues(found: readonly Issue[]): string;
}

export function useViewMessages(): MessageFormatters {
  const messages = useMessages();
  return useMemo(
    () => ({
      label: (key, params, fallback) => {
        const found = formatMessage(messages, key, params);
        return found === key && fallback !== undefined ? fallback : found;
      },
      issue: found => formatIssue(messages, found),
      issues: found => formatIssues(messages, found),
    }),
    [messages],
  );
}

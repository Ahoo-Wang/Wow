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
  createContext,
  useContext,
  useMemo,
  useState,
  type ReactNode,
} from 'react';
import {
  say,
  textKeyOf,
  type Issue,
  type TextResolver,
} from '../../model/index.js';
import {
  defaultMessages,
  formatIssue,
  formatIssues,
  formatMessage,
  type MessageKey,
  type ViewMessages,
} from './messages.js';

const MessagesContext = createContext<ViewMessages>(defaultMessages);

/**
 * The words the engine on hand was built with (`ViewEngineOptions.text`):
 * what a definition's key is said in where the wording in force lacks it.
 */
const StartingWordsContext = createContext<TextResolver | undefined>(undefined);

/**
 * Hands the words `engine` was built with down to what shows its
 * definitions (`useSay`): a Provider does for its engine, and a surface
 * given an engine of its own does for that one.
 */
export function StartingWords({
  engine,
  children,
}: {
  engine: { startingWord(key: string): string | undefined } | undefined;
  children: ReactNode;
}) {
  const outer = useContext(StartingWordsContext);
  const words = useMemo<TextResolver | undefined>(
    () => (engine ? key => engine.startingWord(key) : outer),
    [engine, outer],
  );
  return (
    <StartingWordsContext.Provider value={words}>
      {children}
    </StartingWordsContext.Provider>
  );
}

/**
 * The language a number inside a sentence is grouped in. It travels with the
 * wording because it is part of it: 「共 624,082 条记录」 is one sentence, and
 * the digits in it read the way the words around them do.
 */
const LocaleContext = createContext<string | undefined>(undefined);

export interface MessagesProviderProps {
  /**
   * Merged over the wording already in force, so an application overrides
   * what it cares to: the defaults, or what an outer provider set.
   */
  messages?: ViewMessages;
  /**
   * The language the numbers in that wording are grouped in; the outer
   * provider's when left out, and the runtime's when none sets one.
   * `ViewSurface` passes its own `locale` here.
   */
  locale?: string;
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
  locale,
  children,
}: MessagesProviderProps) {
  const merged = useMerged(useMessages(), messages);
  const inherited = useContext(LocaleContext);
  return (
    <MessagesContext.Provider value={merged}>
      <LocaleContext.Provider value={locale ?? inherited}>
        {children}
      </LocaleContext.Provider>
    </MessagesContext.Provider>
  );
}

function useMessages(): ViewMessages {
  return useContext(MessagesContext);
}

/**
 * The wording in force here with `messages` merged over it, as a
 * `MessagesProvider` given them would hand its children: what
 * `ViewHost` says a definition's keys in.
 */
export function useMergedMessages(messages?: ViewMessages): ViewMessages {
  return useMerged(useMessages(), messages);
}

/** A definition's words as they are shown; see `useSay`. */
export type Say = (value: string) => string;

/**
 * How a definition's keys (`text(key)`) are said here: in the wording in
 * force — the nearest provider's `messages`, merged over the ones above —
 * else in the words the engine was built with, else as the key itself
 * (D2). Every label a definition carries passes through this where it is
 * shown or leaves the engine — a cell, a chart's option, an export, an
 * accessible name — and nowhere else: the state, the snapshots and what an
 * editor takes and gives back keep their keys. A change of language is a
 * new function, so what reads it draws again.
 */
export function useSay(messages?: ViewMessages): Say {
  const merged = useMerged(useMessages(), messages);
  const start = useContext(StartingWordsContext);
  return useMemo(() => sayIn(merged, start), [merged, start]);
}

/**
 * `value` with every string in it as `say` shows it: a drawing's input —
 * a chart's series, lines and stages, its spec's axis names — said as it
 * reaches the code that draws it, and never kept (D2). The same object
 * where nothing in it is a key; a class instance, a `Date` or a `Map` is
 * left as it is.
 */
export function sayAll<T>(value: T, say: Say): T {
  const walk = (node: unknown, depth: number): unknown => {
    if (typeof node === 'string') return say(node);
    if (node === null || typeof node !== 'object' || depth > SAY_DEPTH)
      return node;
    if (Array.isArray(node)) {
      let changed = false;
      const next = node.map(entry => {
        const said = walk(entry, depth + 1);
        if (said !== entry) changed = true;
        return said;
      });
      return changed ? next : node;
    }
    const prototype: unknown = Object.getPrototypeOf(node);
    if (prototype !== Object.prototype && prototype !== null) return node;
    let changed = false;
    const next: Record<string, unknown> = {};
    for (const [key, entry] of Object.entries(node)) {
      const said = walk(entry, depth + 1);
      if (said !== entry) changed = true;
      next[key] = said;
    }
    return changed ? next : node;
  };
  return walk(value, 0) as T;
}

/** How deep `sayAll` reads: a drawing's words sit a few levels in. */
const SAY_DEPTH = 16;

/**
 * What an editor gives back for a label it showed as `say(original)`: the
 * original — a key stays a key — where the reader left the words as they
 * were shown, else what they typed. An editor takes a key and shows it in
 * words; it must not hand the words back as though the reader wrote them,
 * or the label would stop following the language.
 *
 * `shown` is the words the box opened on, for a box that keeps a draft of
 * its own: the language may change while it is open, and a draft left as
 * it opened is still the original, whichever language it was said in.
 */
export function keptKey(
  typed: string,
  original: string,
  say: Say,
  shown?: string,
): string {
  return typed === say(original) || (shown !== undefined && typed === shown)
    ? original
    : typed;
}

/**
 * A box that edits a label as it is typed: what it shows — `value` in
 * words — and what a keystroke gives back (`keptKey`, against the value the
 * box opened on), so words typed back to what was shown are the key again.
 *
 * A `value` the box did not give back itself — another row's, after the
 * list it sits in lost one; an undo — opens it again on that value.
 */
export function useSaidText(value: string | undefined): {
  shown: string;
  back(typed: string): string;
} {
  const say = useSay();
  const current = value ?? '';
  const [held, setHeld] = useState(() => ({
    opened: current,
    given: current,
  }));
  let opened = held.opened;
  if (current !== held.given) {
    opened = current;
    setHeld({ opened: current, given: current });
  }
  return {
    shown: say(current),
    back: typed => {
      const next = keptKey(typed, opened, say);
      setHeld({ opened, given: next });
      return next;
    },
  };
}

function sayIn(merged: ViewMessages, start: TextResolver | undefined): Say {
  const resolve: TextResolver = key =>
    (Object.prototype.hasOwnProperty.call(merged, key)
      ? merged[key]
      : undefined) ?? start?.(key);
  return value => say(value, resolve);
}

/** The language set by the nearest provider that set one. */
export function useInheritedLocale(): string | undefined {
  return useContext(LocaleContext);
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
  /**
   * A definition's words as they are shown here (`useSay`): its keys
   * (`text(key)`) said in this wording. `label`, `issue` and `issues` say
   * the keys their parameters carry already.
   */
  say: Say;
}

/** The plural forms a catalogue may word apart from the general one. */
const PLURAL_FORMS = ['zero', 'one', 'two', 'few', 'many'] as const;

/** A host's words with numbers and words put into them; see `useSayWith`. */
export type SayWith = (value: string, params?: Issue['params']) => string;

/**
 * How a host's words with numbers and words in them are said here — a
 * declared action's question, 「准备 {count} 条执行记录？」: `value` is a key
 * (`text(key)`) or words, said in the wording in force, with `{name}`s
 * filled from `params` (a parameter that is a key is said too), and a key's
 * count said apart where its catalogue words it so (`key-one`), as the
 * package's own sentences are. `messages` and `locale` are merged over the
 * wording in force, as `useViewMessages` takes them, for a part drawn above
 * the surface whose provider holds the host's words.
 */
export function useSayWith(messages?: ViewMessages, locale?: string): SayWith {
  const merged = useMerged(useMessages(), messages);
  const inherited = useContext(LocaleContext);
  const language = locale ?? inherited;
  const start = useContext(StartingWordsContext);
  return useMemo(() => {
    const said = sayIn(merged, start);
    return (value, params) => {
      const key = textKeyOf(value);
      if (key === null)
        return said(formatMessage({ value }, 'value', params, language));
      const lookup = (name: string): string | undefined =>
        (Object.prototype.hasOwnProperty.call(merged, name)
          ? merged[name]
          : undefined) ?? start?.(name);
      const catalogue: Record<string, string> = { [key]: said(value) };
      for (const form of PLURAL_FORMS) {
        const found = lookup(`${key}-${form}`);
        if (found !== undefined) catalogue[`${key}-${form}`] = found;
      }
      return said(formatMessage(catalogue, key, params, language));
    };
  }, [merged, language, start]);
}

/**
 * The formatters for the wording in force here, with `messages` merged over
 * it and its numbers grouped in `locale`. A workbench passes the wording and
 * the language it hands its own surface: that surface's provider is below it,
 * so without this the workbench's own alerts would miss the translation every
 * component inside it gets, and count in the machine's language besides.
 */
export function useViewMessages(
  messages?: ViewMessages,
  locale?: string,
): MessageFormatters {
  const merged = useMerged(useMessages(), messages);
  const inherited = useContext(LocaleContext);
  const start = useContext(StartingWordsContext);
  const language = locale ?? inherited;
  return useMemo(() => {
    // A parameter may carry a definition's label, key and all: the
    // sentence is said as a whole, where it is shown.
    const said = sayIn(merged, start);
    return {
      label: (key, params, fallback) => {
        const found = formatMessage(merged, key, params, language);
        return said(found === key && fallback !== undefined ? fallback : found);
      },
      issue: found => said(formatIssue(merged, found, language)),
      issues: found => said(formatIssues(merged, found, language)),
      say: said,
    };
  }, [merged, language, start]);
}

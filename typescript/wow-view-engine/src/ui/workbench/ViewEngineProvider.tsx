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
  useLayoutEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react';
import type { ViewEngine, ViewNavigation } from '../../runtime/index.js';
import type { RecordDetailControl } from '../../react/index.js';
import {
  MessagesProvider,
  StartingWords,
  useInheritedLocale,
  useMergedMessages,
} from '../kit/MessagesProvider.js';
import type { ViewMessages } from '../kit/messages.js';
import type { ViewBinding } from './bindings.js';
import {
  resolveNavigation,
  type ViewDestination,
  type ViewRouter,
} from '../../runtime/routes.js';

/** What a surface finds above it; see `ViewEngineProvider`. */
interface EngineContext {
  engine: ViewEngine | undefined;
  /** The host's route, as it takes a resolved target. */
  navigate: ((to: ViewDestination) => void) | undefined;
  bindings: ReadonlyMap<string, ViewBinding>;
  /** The engines a provider above already checks the words of. */
  worded: ReadonlySet<ViewEngine>;
  /** The host's router (`ViewHost`'s `router`), where it gave one. */
  router: ViewRouter | undefined;
  /**
   * The record open in the address (`?id=`), for every bound resource's
   * record detail that holds none of its own; see `ViewHost`.
   */
  detail: RecordDetailControl | undefined;
  /** Whether a `ViewHost` is above: only the outermost one paints `<html>`. */
  hosted: boolean;
}

const NO_BINDINGS: ReadonlyMap<string, ViewBinding> = new Map();

const Context = createContext<EngineContext>({
  engine: undefined,
  navigate: undefined,
  bindings: NO_BINDINGS,
  worded: new Set(),
  router: undefined,
  detail: undefined,
  hosted: false,
});

interface ViewEngineProviderProps {
  /**
   * The application's engine, built once (host-integration.md 4); an inner
   * provider without one uses the outer one's. The engine's definitions
   * are said where they are shown, in the wording in force there (`useSay`),
   * so a provider nested in another language says them in its words as it
   * says the engine's own messages. The words of the outermost provider
   * that names the engine are the ones checked for keys they lack
   * (`ViewEngine.setText`).
   */
  engine?: ViewEngine;
  /**
   * The language values are shown in; the outer provider's when left out.
   * A change of it redraws what is open and rebuilds nothing.
   */
  locale?: string;
  /**
   * Wording, merged over what is already in force — the engine's own and
   * the definitions' keys (`text(key)`) alike: every label a definition
   * carries is said in these words where it is shown, so switching them
   * redraws every open view in them without reopening, re-querying or
   * dirtying anything (3.1).
   */
  messages?: ViewMessages;
  /**
   * The host's route: every way off a board or a view — 在工作台中打开, a
   * follow-up on a group, a panel's destination, the way back to a board —
   * reaches it through the route of the definition it leads to
   * (`ViewBindingOptions.route`) as a `ViewRoute`; a URL, and a definition
   * with no route, reach it as they came.
   */
  navigate?(to: ViewDestination): void;
  /**
   * Each definition's behaviour in this host (`bind`); an inner one wins by
   * id.
   */
  bindings?: readonly ViewBinding[];
  /** The host's router; the outer provider's when left out. */
  router?: ViewRouter;
  /** The record open in the address; the outer provider's when left out. */
  detail?: RecordDetailControl;
  /** Set by `ViewHost`: what is under it has a host above. */
  hosted?: boolean;
  children: ReactNode;
}

/**
 * The engine, its words and its bindings, for every surface under it
 * (host-integration.md 4) — `ViewHost`'s inside, not an entry of its own
 * (4.2): one engine for the application, so a surface
 * takes only what differs where it stands — `<DataWorkbench
 * definitionId={ORDERS} />`. Providers nest, the inner one over the outer
 * one; a surface's own `engine`, `messages`, `locale` or `onNavigate` still
 * wins, so a page with two engines is as easy to write as before.
 *
 * The definitions keep their keys, and every surface under it says them in
 * the wording in force where it shows them (`useSay`): a change of
 * `messages` or `locale` redraws what is open in the new words — the same
 * runtimes, no query run again. The provider that names an engine checks
 * its words for the keys they lack (`ViewEngine.setText`).
 */
export function ViewEngineProvider({
  engine: own,
  locale,
  messages,
  navigate,
  bindings,
  router,
  detail,
  hosted,
  children,
}: ViewEngineProviderProps) {
  const outer = useContext(Context);
  const engine = own ?? outer.engine;
  const wording = useMergedMessages(messages);
  const inherited = useInheritedLocale();
  // The words checked are the outermost provider's that names the engine
  // (`setText` is the engine's, not a subtree's), whatever order React runs
  // their effects in.
  useCheckedWords(
    own && !outer.worded.has(own) ? own : undefined,
    wording,
    locale ?? inherited ?? '',
  );

  const bound = useMemo(() => {
    if (!bindings?.length) return outer.bindings;
    const next = new Map(outer.bindings);
    for (const binding of bindings) next.set(binding.definitionId, binding);
    return next;
  }, [outer.bindings, bindings]);

  const value = useMemo<EngineContext>(
    () => ({
      engine,
      navigate: navigate ?? outer.navigate,
      bindings: bound,
      worded:
        own && !outer.worded.has(own)
          ? new Set([...outer.worded, own])
          : outer.worded,
      router: router ?? outer.router,
      detail: detail ?? outer.detail,
      hosted: hosted ?? outer.hosted,
    }),
    [
      engine,
      own,
      navigate,
      outer.navigate,
      bound,
      outer.worded,
      router,
      outer.router,
      detail,
      outer.detail,
      hosted,
      outer.hosted,
    ],
  );
  return (
    <Context.Provider value={value}>
      <StartingWords engine={engine}>
        <MessagesProvider messages={messages} locale={locale}>
          {children}
        </MessagesProvider>
      </StartingWords>
    </Context.Provider>
  );
}

/**
 * Checks `engine`'s definitions against `wording` from the first draw on
 * (`setText`): a key it lacks is told once per language. Nothing is said
 * or redrawn by it.
 */
function useCheckedWords(
  engine: ViewEngine | undefined,
  wording: ViewMessages,
  language: string,
): void {
  const resolver = useMemo(
    () =>
      (key: string): string | undefined =>
        wording[key],
    [wording],
  );
  // Before the first draw, as the engine is given to its provider.
  useState(() => engine?.setText(resolver, language));
  useLayoutEffect(() => {
    engine?.setText(resolver, language);
  }, [engine, resolver, language]);
}

/**
 * The engine a surface draws: its own, else its provider's. A surface with
 * neither is a host's mistake, said as one.
 *
 * A host's own component under a `ViewHost` reads the one engine here too —
 * a record's reading that asks a resource's source of its own
 * (`engine.resolveSource`), say — rather than having it threaded down.
 */
export function useEngine(own?: ViewEngine): ViewEngine {
  const { engine } = useContext(Context);
  const found = own ?? engine;
  if (!found)
    throw new Error(
      'No view engine: pass `engine`, or render inside a <ViewHost engine={…}>.',
    );
  return found;
}

/**
 * The route a surface hands its ways off: its own, else its provider's,
 * each target resolved through the route of the definition it leads to,
 * as the nearest provider binds it.
 */
export function useRoutedNavigate(
  own?: (to: ViewNavigation) => void,
): ((to: ViewNavigation) => void) | undefined {
  const { navigate, bindings } = useContext(Context);
  const routed = useMemo(
    () =>
      navigate &&
      ((to: ViewNavigation) =>
        navigate(resolveNavigation(to, id => bindings.get(id)))),
    [navigate, bindings],
  );
  return own ?? routed;
}

/**
 * What the host bound to a definition (`bind`), from the providers above.
 * Under a router, a bound resource's record detail follows the address
 * (`?id=`) where its `reading` holds no open record of its own.
 */
export function useBindings(): (
  definitionId: string,
) => ViewBinding | undefined {
  const { bindings, detail } = useContext(Context);
  // Built whole, never filled in as asked: one object per binding for as
  // long as the bindings and the record open stay put.
  const addressed = useMemo(
    () =>
      detail
        ? new Map(
            [...bindings].map(([id, binding]) => [
              id,
              binding.reading?.open !== undefined
                ? binding
                : { ...binding, reading: { ...binding.reading, ...detail } },
            ]),
          )
        : bindings,
    [bindings, detail],
  );
  return useMemo(() => (id: string) => addressed.get(id), [addressed]);
}

/** The host's router, from the `ViewHost` above; `undefined` without one. */
export function useViewRouter(): ViewRouter | undefined {
  return useContext(Context).router;
}

/** Whether a `ViewHost` is above. */
export function useHosted(): boolean {
  return useContext(Context).hosted;
}

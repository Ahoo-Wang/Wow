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
  useEffect,
  useLayoutEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react';
import type { ViewEngine, ViewNavigation } from '../runtime/index.js';
import { MessagesProvider, useMergedMessages } from './MessagesProvider.js';
import type { ViewMessages } from './messages.js';
import {
  resolveNavigation,
  type ViewBinding,
  type ViewDestination,
} from './bindings.js';

/** What a surface finds above it; see `ViewEngineProvider`. */
interface EngineContext {
  engine: ViewEngine | undefined;
  /** The host's route, as it takes a resolved target. */
  navigate: ((to: ViewDestination) => void) | undefined;
  bindings: ReadonlyMap<string, ViewBinding>;
  /** Advanced whenever the engine's words change, so readers draw again. */
  words: number;
  /** The engines a provider above already says the words of. */
  worded: ReadonlySet<ViewEngine>;
}

const NO_BINDINGS: ReadonlyMap<string, ViewBinding> = new Map();

const Context = createContext<EngineContext>({
  engine: undefined,
  navigate: undefined,
  bindings: NO_BINDINGS,
  words: 0,
  worded: new Set(),
});

export interface ViewEngineProviderProps {
  /**
   * The application's engine, built once (host-integration.md 4); an inner
   * provider without one uses the outer one's. The engine's definitions
   * speak the `messages` of the outermost provider that names it: nesting a
   * provider in another language — naming the engine again at any depth,
   * or naming none — rewords the engine's own messages under it, not the
   * definitions' keys; one engine speaks one language at a time. Two
   * sibling providers naming one engine must give it the same words (the
   * last to draw would win).
   */
  engine?: ViewEngine;
  /**
   * The language values are shown in; the outer provider's when left out.
   * A change of it redraws what is open and rebuilds nothing.
   */
  locale?: string;
  /**
   * Wording, merged over what is already in force — the engine's own and
   * the definitions' keys (`text(key)`) alike: the engine says its
   * definitions' keys in these words, at render time, so switching them
   * switches every open view's words without reopening it (3.1).
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
  children: ReactNode;
}

/**
 * The engine, its words and its bindings, for every surface under it
 * (host-integration.md 4): one engine for the application, so a surface
 * takes only what differs where it stands — `<DataWorkbench
 * definitionId={ORDERS} />`. Providers nest, the inner one over the outer
 * one; a surface's own `engine`, `messages`, `locale` or `onNavigate` still
 * wins, so a page with two engines is as easy to write as before.
 *
 * The provider that names an engine says that engine's definitions in its
 * wording (`ViewEngine.setText`): the definitions keep their keys, and a
 * change of `messages` or `locale` redraws what is open in the new words —
 * the same runtimes, no query run again.
 */
export function ViewEngineProvider({
  engine: own,
  locale,
  messages,
  navigate,
  bindings,
  children,
}: ViewEngineProviderProps) {
  const outer = useContext(Context);
  const engine = own ?? outer.engine;
  const wording = useMergedMessages(messages);
  // The words of an engine are the outermost provider's that names it: one
  // engine speaks one language at a time (`setText` is the engine's, not a
  // subtree's), so an inner provider naming it again, or naming none,
  // rewords the engine's own messages under it but never its definitions'
  // keys — whatever order React runs their effects in.
  const words = useEngineWords(
    own && !outer.worded.has(own) ? own : undefined,
    wording,
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
      words: outer.words + words,
      worded:
        own && !outer.worded.has(own)
          ? new Set([...outer.worded, own])
          : outer.worded,
    }),
    [
      engine,
      own,
      navigate,
      outer.navigate,
      bound,
      outer.words,
      outer.worded,
      words,
    ],
  );
  return (
    <Context.Provider value={value}>
      <MessagesProvider messages={messages} locale={locale}>
        {children}
      </MessagesProvider>
    </Context.Provider>
  );
}

/**
 * Says `engine`'s keys in `wording` from the first draw on, and counts each
 * change of its words, so what reads the engine draws again.
 */
function useEngineWords(
  engine: ViewEngine | undefined,
  wording: ViewMessages,
): number {
  const resolver = useMemo(
    () =>
      (key: string): string | undefined =>
        wording[key],
    [wording],
  );
  // Before the first draw, so nothing is drawn in keys: an engine is given
  // to its provider before anything of it is open.
  useState(() => engine?.setText(resolver));
  useLayoutEffect(() => {
    engine?.setText(resolver);
  }, [engine, resolver]);
  const [words, setWords] = useState(0);
  useEffect(
    () => engine?.subscribeText(() => setWords(count => count + 1)),
    [engine],
  );
  return words;
}

/**
 * The engine a surface draws: its own, else its provider's. A surface with
 * neither is a host's mistake, said as one.
 */
export function useEngine(own?: ViewEngine): ViewEngine {
  // Read either way, so a change of the provider's words draws it again.
  const { engine } = useContext(Context);
  const found = own ?? engine;
  if (!found)
    throw new Error(
      'No view engine: pass `engine`, or render inside a <ViewEngineProvider engine={…}>.',
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

/** What the host bound to a definition (`bind`), from the providers above. */
export function useBindings(): (
  definitionId: string,
) => ViewBinding | undefined {
  const { bindings } = useContext(Context);
  return useMemo(() => (id: string) => bindings.get(id), [bindings]);
}

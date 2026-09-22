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
 * Who is subscribed, and how they are told — the half of a store that is the
 * same wherever one is written.
 *
 * Every store in this package has the shape `useSyncExternalStore` asks of
 * one: `subscribe` returns the way to stop listening, and a change notifies
 * whoever is still listening. Four of them wrote that out by hand — the two
 * runtimes, the list-change notifications and the countdown React reads a
 * refresh off — and each copy carried the same two rules in a comment of its
 * own: notify **after** the new state is committed, and walk a **copy** of
 * the set, so a listener that unsubscribes from inside its call does not
 * decide who else hears this one.
 *
 * A factory rather than a class, because a store hands `subscribe` over as a
 * bare function: `useSyncExternalStore(store.subscribe, …)` calls it with no
 * receiver, which a method reading `this.listeners` does not survive.
 */
export interface Listeners<A extends unknown[] = []> {
  /** Starts listening; the returned function stops. */
  subscribe(listener: (...args: A) => void): () => void;
  /** Tells everyone listening, over a copy of the set. */
  emit(...args: A): void;
  /** Forgets everyone, so a disposed store leaves no listener behind. */
  clear(): void;
}

export function listenerSet<A extends unknown[] = []>(
  /**
   * What to do with a listener that throws. Left out, the throw is the
   * caller's, which is what a store whose `emit` is a render's own business
   * wants; given, every listener is heard from whatever the ones before it
   * did — the shape needed where notifying happens inside a write that has
   * already landed, and where letting the throw out would report a confirmed
   * write as one to retry.
   */
  contain?: (error: unknown) => void,
): Listeners<A> {
  const subscribed = new Set<(...args: A) => void>();
  return {
    subscribe: listener => {
      subscribed.add(listener);
      return () => {
        subscribed.delete(listener);
      };
    },
    emit: (...args) => {
      for (const listener of [...subscribed]) {
        if (!contain) {
          listener(...args);
          continue;
        }
        try {
          listener(...args);
        } catch (error) {
          contain(error);
        }
      }
    },
    clear: () => {
      subscribed.clear();
    },
  };
}

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

import { text } from '../../src/index.js';

/** Every member a definition, a view or a board writes words in. */
const LABELS = new Set(['label', 'title', 'description', 'content', 'alt']);

/** A card's `title` names a field, not words. */
const NOT_WORDS = new Set(['card']);

/**
 * `value` with every label it carries written as a key (`text(key)`), and
 * the words of each key put in `words` as `say(original)`: a definition, a
 * view or a board as a host of several languages declares it. A surface
 * drawn from the keyed value under `words` shows what `say` makes of the
 * literal one.
 */
export function keyed<T>(
  value: T,
  words: Record<string, string>,
  prefix: string,
  say: (original: string) => string = original => original,
): T {
  let next = 0;
  const walk = (node: unknown, parent: string): unknown => {
    if (Array.isArray(node)) return node.map(entry => walk(entry, parent));
    if (node === null || typeof node !== 'object') return node;
    const out: Record<string, unknown> = {};
    for (const [key, entry] of Object.entries(node)) {
      if (
        LABELS.has(key) &&
        !NOT_WORDS.has(parent) &&
        typeof entry === 'string' &&
        entry !== ''
      ) {
        const name = `${prefix}.${next++}`;
        words[name] = say(entry);
        out[key] = text(name);
      } else out[key] = walk(entry, key);
    }
    return out;
  };
  return walk(value, '') as T;
}

/** Where a key's marker shows in `root`: its text, its attributes, its fields' values. */
export function markersIn(root: ParentNode): string[] {
  const found: string[] = [];
  const marked = (value: string | null | undefined) =>
    value != null && /[]/.test(value);
  const all = [
    ...(root instanceof Element ? [root] : []),
    ...root.querySelectorAll('*'),
  ];
  for (const element of all) {
    for (const attribute of element.attributes)
      if (marked(attribute.value))
        found.push(`${element.tagName}[${attribute.name}]=${attribute.value}`);
    for (const child of element.childNodes)
      if (child.nodeType === 3 && marked(child.textContent))
        found.push(`${element.tagName}: ${child.textContent}`);
    if (
      (element instanceof HTMLInputElement ||
        element instanceof HTMLTextAreaElement) &&
      marked(element.value)
    )
      found.push(`${element.tagName}.value=${element.value}`);
  }
  if (marked(document.title)) found.push(`document.title=${document.title}`);
  return found;
}

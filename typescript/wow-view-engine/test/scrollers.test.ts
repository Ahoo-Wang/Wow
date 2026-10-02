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

import { readdirSync, readFileSync } from 'node:fs';
import { join, relative } from 'node:path';
import postcss from 'postcss';
import ts from 'typescript';
import { describe, expect, it } from 'vitest';

/**
 * Every region the engine scrolls is the containing block of what it holds.
 *
 * An absolutely placed descendant — an `sr-only` word above all, which is
 * `position: absolute` — is laid out against the nearest positioned box, not
 * the nearest scroller. Under a scroller that is not positioned, the words
 * of the rows below its fold escape its clip and stretch the next region
 * out: that one scrolls too, into blank space (the view manager's dialog,
 * the work column under the cards: 3741px of content in 811). So a scroller
 * is `relative` unless it is already placed some other way. The browser half
 * is in Storybook (`CardsScrollAlone`, `ManagerFitsAShortScreen`); this half
 * reads the source, so a new scroller cannot forget it.
 *
 * A class list is one string: a string carrying `overflow-auto`,
 * `overflow-y-auto`, `overflow-x-scroll`… (any variant) must carry
 * `relative`, `absolute`, `fixed` or `sticky` too. A rule of `styles.css`
 * that scrolls declares a `position` beside it.
 */

const SRC = join(import.meta.dirname, '..', 'src');

const SCROLLS =
  /(?:^|\s)fve:(?:[^\s]*:)?overflow(?:-[xy])?-(?:auto|scroll)(?=\s|$)/;
const PLACED = /(?:^|\s)fve:(?:relative|absolute|fixed|sticky)(?=\s|$)/;

/**
 * Scrollers placed by something outside their own string, by file and the
 * declaration that holds the string.
 */
const PLACED_ELSEWHERE: ReadonlySet<string> = new Set([
  // The list sits in the combobox popup, which is `relative` and clips; its
  // one caller passes `fve:relative` as well (`filter/inputs/suggested.tsx`).
  'ui/components/combobox.tsx ComboboxList',
  // Never drawn: the engine renders the themed copy in `kit/popups.tsx`
  // (`MENU_POPUP_CLASS`), which is `relative`; `test/popups.test.tsx`
  // renders this one only to hold that copy to it.
  'ui/components/dropdown-menu.tsx DropdownMenuContent',
]);

/** Rules whose scroller is positioned by another rule, and which one. */
const CSS_PLACED_ELSEWHERE: Record<string, string> = {
  // The columns, and the root while it fills the screen, keep the position
  // their own rules give them (`relative`; `fixed`).
  ".fve-root[data-view-expanded='true'] > [data-slot='view-sidebar'], .fve-root[data-view-expanded='true'] > [data-slot='view-panel'], .fve-root[data-view-expanded='true'] > [data-slot='workbench-main']":
    ".fve-root > [data-slot='workbench-main']",
  ".fve-root[data-view-expanded='true']:not(:has(> [data-slot='workbench-main']))":
    ".fve-root[data-view-expanded='true']",
  // Heavier than the expanded root's `fixed`, so the position is on the
  // lighter rule it shares with every filled embed.
  ".fve-root[data-embed-size='fill']:has([data-slot='dashboard-grid'])":
    ".fve-root[data-embed-size='fill']",
};

function sources(directory: string): string[] {
  return readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) return sources(path);
    return /\.tsx?$/.test(entry.name) ? [path] : [];
  });
}

/** The declaration a string stands in: a variable, a function, a class. */
function holderOf(node: ts.Node): string {
  for (let at = node.parent; at !== undefined; at = at.parent) {
    if (
      (ts.isVariableDeclaration(at) || ts.isFunctionDeclaration(at)) &&
      at.name !== undefined &&
      ts.isIdentifier(at.name)
    )
      return at.name.text;
  }
  return '';
}

function unplacedScrollers(): string[] {
  const found: string[] = [];
  for (const file of sources(SRC)) {
    const text = readFileSync(file, 'utf8');
    const source = ts.createSourceFile(
      file,
      text,
      ts.ScriptTarget.Latest,
      true,
    );
    const name = relative(SRC, file).split('\\').join('/');
    const visit = (node: ts.Node) => {
      if (
        (ts.isStringLiteral(node) ||
          ts.isNoSubstitutionTemplateLiteral(node)) &&
        SCROLLS.test(node.text) &&
        !PLACED.test(node.text) &&
        !PLACED_ELSEWHERE.has(`${name} ${holderOf(node)}`)
      ) {
        const { line } = source.getLineAndCharacterOfPosition(node.getStart());
        found.push(`${name}:${line + 1} ${holderOf(node)}`);
      }
      ts.forEachChild(node, visit);
    };
    visit(source);
  }
  return found;
}

describe('every scroller is the containing block of what it holds', () => {
  it('in a class list of src', () => {
    expect(unplacedScrollers()).toEqual([]);
  });

  it('in a rule of styles.css', () => {
    const root = postcss.parse(readFileSync(join(SRC, 'styles.css'), 'utf8'));
    const unplaced: string[] = [];
    root.walkRules(rule => {
      const scrolls = rule.nodes.some(
        node =>
          node.type === 'decl' &&
          /^overflow(-[xy])?$/.test(node.prop) &&
          /\b(auto|scroll)\b/.test(node.value),
      );
      if (!scrolls) return;
      const placed = rule.nodes.some(
        node => node.type === 'decl' && node.prop === 'position',
      );
      const selector = rule.selector.replace(/\s+/g, ' ').trim();
      if (!placed && CSS_PLACED_ELSEWHERE[selector] === undefined)
        unplaced.push(selector);
    });
    expect(unplaced).toEqual([]);
  });

  it('names only exceptions that are still there', () => {
    // An exception whose string is gone, or now placed itself, is a stale
    // line in the list: it would quietly cover the next scroller of that
    // name.
    const css = readFileSync(join(SRC, 'styles.css'), 'utf8').replace(
      /\s+/g,
      ' ',
    );
    for (const [selector, by] of Object.entries(CSS_PLACED_ELSEWHERE)) {
      expect(css).toContain(`${selector} {`);
      expect(css).toContain(`${by} {`);
    }
    for (const key of PLACED_ELSEWHERE) {
      const [file, holder] = key.split(' ');
      const text = readFileSync(join(SRC, file), 'utf8');
      expect(text).toMatch(new RegExp(`function ${holder}\\b`));
      expect(text).toMatch(SCROLLS);
    }
  });
});

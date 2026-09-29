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
import ts from 'typescript';
import { beforeAll, describe, expect, it } from 'vitest';
import {
  isVariantMarker,
  KNOWN_MISSES,
  utilityCheck,
  type UtilityCheck,
} from './fixtures/utilities.js';

/**
 * Every Tailwind class in `src` carries the engine's prefix (D66). The build
 * is `prefix(fve)`, so a class written without it — `truncate` for
 * `fve:truncate` — generates nothing, and nothing says so: the element just
 * loses its styling. A prefixed one the design system does not know —
 * `fve:truncat` — is the same silence. This suite reads every string in
 * `src` off the TypeScript AST and asks the engine's design system about each
 * word (`fixtures/utilities.ts`); its runtime half, in `setup.ts`, reads the
 * classes of every element a suite renders.
 *
 * A string is read as a **class list** when it carries a `fve:` word, is the
 * value of a `class="…"` inside HTML (the chart tooltip builds some), or sits
 * inside a `cn`, `cva` or `classList` call: every word in it that `fve:`
 * would turn into a utility is a miss, and every `fve:` word must be a
 * utility itself, bar the `fve:group` / `fve:peer` markers. A string with no
 * `fve:` word is prose or data — `'filter'`, `'table'`, `'outline'` — unless
 * it could only be classes: several words, every one a utility once prefixed;
 * or a single word that is one, standing anywhere but where the engine keeps
 * data (`dataContext`). A miss already being fixed elsewhere is named, dated, in
 * `KNOWN_MISSES` (`fixtures/utilities.ts`) until the fix lands.
 */

const SRC = join(import.meta.dirname, '..', 'src');

/** A word shaped like a class name at all: no prose punctuation, no markup. */
const CLASS_SHAPED = /^!?[a-z@[*-][\w\-:/[\].()%#,'=&>~*+!@]*$/;

/** Calls whose arguments are classes, however deep: `cn`, `cva`, `clsx`. */
const CLASS_CALLS = /^(cn|cva|clsx|cx)$/;

/**
 * Names under which a single-word string is a value of the model, of a
 * library or of the DOM — `type: 'table'`, `variant="outline"`,
 * `overflow: 'hidden'`, `fallback: 'border'` (a token's) — never a class.
 */
const DATA_NAMES =
  /^(type|kind|layout|overflow|variant|mode|value|width|name|shape|position|visibility|display|role|tone|size|side|align|state|status|group|tier|fallback|ink|placeholder|choice|interaction|words|cursor|resize|trigger|scope|key|id)$/i;

interface Finding {
  file: string;
  line: number;
  token: string;
  why: 'unprefixed' | 'unknown';
}

/** Whether a declared type says the value is not a free string. */
function typedAsData(type: ts.TypeNode | undefined): boolean {
  return type !== undefined && !/\bstring\b|class/i.test(type.getText());
}

/** The name a value stands under: a property, a variable, an attribute. */
function nameOf(node: ts.Node): string {
  const name = (node as { name?: ts.Node }).name;
  if (name === undefined) return '';
  if (ts.isIdentifier(name) || ts.isStringLiteral(name)) return name.text;
  if (ts.isJsxNamespacedName(name)) return name.name.text;
  return '';
}

/** A call that takes classes: `cn(…)`, `cva(…)`, `el.classList.add(…)`. */
function isClassCall(node: ts.Node): boolean {
  if (!ts.isCallExpression(node)) return false;
  const callee = node.expression;
  if (ts.isIdentifier(callee)) return CLASS_CALLS.test(callee.text);
  return (
    ts.isPropertyAccessExpression(callee) &&
    ts.isPropertyAccessExpression(callee.expression) &&
    callee.expression.name.text === 'classList'
  );
}

/** Whether a string sits inside a class call's arguments, however deep. */
function inClassCall(node: ts.Node): boolean {
  for (let at = node.parent; at !== undefined; at = at.parent) {
    if (isClassCall(at)) return true;
    if (ts.isFunctionLike(at) || ts.isSourceFile(at)) return false;
  }
  return false;
}

/**
 * Whether a single-word string stands where the engine keeps data: a type,
 * a comparison, a key, a path or list of choices, a call's argument, a value
 * whose declared type or name says what it is, a `data-*` or `aria-*`
 * attribute, a style property.
 */
function dataContext(literal: ts.Node): boolean {
  let node = literal;
  let parent = node.parent;
  // See through what passes a value on unchanged: parentheses, casts and a
  // choice (`a ? 'x' : 'y'`, `a ?? 'x'`).
  while (
    ts.isParenthesizedExpression(parent) ||
    ts.isAsExpression(parent) ||
    ts.isSatisfiesExpression(parent) ||
    ts.isTypeAssertionExpression(parent) ||
    (ts.isConditionalExpression(parent) && parent.condition !== node) ||
    (ts.isBinaryExpression(parent) &&
      parent.right === node &&
      [
        ts.SyntaxKind.QuestionQuestionToken,
        ts.SyntaxKind.BarBarToken,
        ts.SyntaxKind.AmpersandAmpersandToken,
      ].includes(parent.operatorToken.kind)) ||
    ts.isJsxExpression(parent)
  ) {
    node = parent;
    parent = node.parent;
  }
  // An argument of `cn` or `cva`, or an element of an array there, is a class.
  if (isClassCall(parent) || isClassCall(parent.parent)) return false;
  if (
    ts.isLiteralTypeNode(parent) ||
    ts.isCaseClause(parent) ||
    ts.isElementAccessExpression(parent) ||
    ts.isComputedPropertyName(parent) ||
    ts.isArrayLiteralExpression(parent) ||
    ts.isCallExpression(parent) ||
    ts.isNewExpression(parent)
  )
    return true;
  if (ts.isBinaryExpression(parent)) {
    const operator = parent.operatorToken.kind;
    if (operator === ts.SyntaxKind.EqualsToken) {
      // `cell.style.overflow = 'hidden'`, `el.dataset.state = 'open'`
      const target = parent.left;
      return (
        ts.isPropertyAccessExpression(target) &&
        (DATA_NAMES.test(target.name.text) ||
          (ts.isPropertyAccessExpression(target.expression) &&
            /^(style|dataset)$/.test(target.expression.name.text)))
      );
    }
    return [
      ts.SyntaxKind.EqualsEqualsEqualsToken,
      ts.SyntaxKind.ExclamationEqualsEqualsToken,
      ts.SyntaxKind.EqualsEqualsToken,
      ts.SyntaxKind.ExclamationEqualsToken,
      ts.SyntaxKind.InKeyword,
    ].includes(operator);
  }
  if (
    (ts.isPropertyAssignment(parent) || ts.isPropertySignature(parent)) &&
    parent.name === node
  )
    return true;
  if (ts.isJsxAttribute(parent)) {
    const name = nameOf(parent);
    return (
      name.startsWith('data-') ||
      name.startsWith('aria-') ||
      DATA_NAMES.test(name)
    );
  }
  if (
    ts.isVariableDeclaration(parent) ||
    ts.isParameter(parent) ||
    ts.isPropertyDeclaration(parent)
  )
    return typedAsData(parent.type) || DATA_NAMES.test(nameOf(parent));
  if (ts.isPropertyAssignment(parent) || ts.isBindingElement(parent))
    return DATA_NAMES.test(nameOf(parent));
  if (ts.isReturnStatement(parent) || ts.isArrowFunction(parent)) {
    let at: ts.Node = parent;
    while (!ts.isFunctionLike(at)) at = at.parent;
    return typedAsData((at as ts.SignatureDeclaration).type);
  }
  return false;
}

/** The words of every `class="…"` in a piece of HTML, open to its end. */
function htmlClassLists(text: string): string[][] {
  return [...text.matchAll(/\bclass="([^"]*)/g)].map(match =>
    match[1].split(/\s+/).filter(Boolean),
  );
}

/** Every miss in one source file. */
function scanSource(
  file: string,
  text: string,
  isUtility: UtilityCheck,
): Finding[] {
  const findings: Finding[] = [];
  const source = ts.createSourceFile(
    file,
    text,
    ts.ScriptTarget.Latest,
    true,
    file.endsWith('.tsx') ? ts.ScriptKind.TSX : ts.ScriptKind.TS,
  );
  const report = (node: ts.Node, token: string, why: Finding['why']) =>
    findings.push({
      file,
      line: source.getLineAndCharacterOfPosition(node.getStart()).line + 1,
      token,
      why,
    });
  const classList = (node: ts.Node, words: readonly string[]) => {
    for (const word of words) {
      if (word.startsWith('fve:')) {
        if (!isVariantMarker(word) && !isUtility(word))
          report(node, word, 'unknown');
      } else if (CLASS_SHAPED.test(word) && isUtility(`fve:${word}`)) {
        report(node, word, 'unprefixed');
      }
    }
  };
  const visit = (node: ts.Node): void => {
    if (
      ts.isStringLiteral(node) ||
      ts.isNoSubstitutionTemplateLiteral(node) ||
      ts.isTemplateHead(node) ||
      ts.isTemplateMiddle(node) ||
      ts.isTemplateTail(node)
    ) {
      const parent = node.parent;
      const specifier =
        ts.isImportDeclaration(parent) ||
        ts.isExportDeclaration(parent) ||
        ts.isExternalModuleReference(parent) ||
        ts.isModuleDeclaration(parent) ||
        (ts.isCallExpression(parent) &&
          parent.expression.kind === ts.SyntaxKind.ImportKeyword);
      if (!specifier) {
        const html = htmlClassLists(node.text);
        const words = node.text.split(/\s+/).filter(Boolean);
        if (html.length > 0) {
          // Markup: the classes are its `class` values; the rest is tags,
          // attributes and text.
          for (const list of html) classList(node, list);
        } else if (
          words.some(word => word.startsWith('fve:')) ||
          (words.length > 1 && inClassCall(node))
        ) {
          classList(node, words);
        } else if (
          words.length > 1 &&
          words.every(
            word => CLASS_SHAPED.test(word) && isUtility(`fve:${word}`),
          )
        ) {
          classList(node, words);
        } else if (
          // A lone word, inside a class call or not: `cn(…, x === 'table'
          // && …)` compares data there too.
          words.length === 1 &&
          CLASS_SHAPED.test(words[0]) &&
          isUtility(`fve:${words[0]}`) &&
          !dataContext(node)
        ) {
          report(node, words[0], 'unprefixed');
        }
      }
    }
    ts.forEachChild(node, visit);
  };
  visit(source);
  return findings;
}

function sources(dir: string, out: string[] = []): string[] {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) sources(path, out);
    else if (/\.tsx?$/.test(entry.name) && !entry.name.endsWith('.d.ts'))
      out.push(path);
  }
  return out;
}

describe('every Tailwind class in src carries the prefix fve: (D66)', () => {
  let isUtility: UtilityCheck;
  let findings: Finding[];

  beforeAll(async () => {
    isUtility = await utilityCheck();
    findings = sources(SRC).flatMap(path =>
      scanSource(
        relative(SRC, path).split('\\').join('/'),
        readFileSync(path, 'utf8'),
        isUtility,
      ),
    );
  });

  it('no string in src holds a utility without fve:, nor a fve: word that is none', () => {
    const known = (finding: Finding) =>
      KNOWN_MISSES.some(
        entry => entry.file === finding.file && entry.token === finding.token,
      );
    expect(findings.filter(finding => !known(finding))).toEqual([]);
  });

  it('every known miss is still in its file (a stale entry fails)', () => {
    const stale = KNOWN_MISSES.filter(
      entry =>
        !findings.some(
          finding =>
            finding.file === entry.file && finding.token === entry.token,
        ),
    );
    expect(stale).toEqual([]);
  });

  it('catches the misses it is for', () => {
    const missed = (code: string, file = 'probe.tsx') =>
      scanSource(file, code, isUtility).map(
        ({ token, why }) => `${why} ${token}`,
      );
    // A bare constant, the #3792 shape.
    expect(missed(`export const CLIPPED_CELL = 'truncate';`)).toEqual([
      'unprefixed truncate',
    ]);
    // One word left bare in a prefixed list, a class attribute, a cn call.
    expect(missed(`const a = 'fve:flex items-center fve:gap-2';`)).toEqual([
      'unprefixed items-center',
    ]);
    expect(missed(`<div className="truncate" />`)).toEqual([
      'unprefixed truncate',
    ]);
    expect(
      missed(`cn('fve:flex', open && 'hidden', cva('border', {}))`),
    ).toEqual(['unprefixed hidden', 'unprefixed border']);
    expect(missed(`el.classList.add('hidden')`)).toEqual(['unprefixed hidden']);
    // A variant's single class inside `cva`, under a name of its own.
    expect(
      missed(`cva('fve:flex', { variants: { size: { sm: 'truncate' } } })`),
    ).toEqual(['unprefixed truncate']);
    // A whole list without the prefix.
    expect(missed(`const x = 'flex items-center gap-2';`)).toEqual([
      'unprefixed flex',
      'unprefixed items-center',
      'unprefixed gap-2',
    ]);
    // HTML built as a string, a substitution inside the attribute.
    expect(
      missed(
        'const h = `<div class="fve:grid gap-1 ${x}">${y}</div>`;',
        'probe.ts',
      ),
    ).toEqual(['unprefixed gap-1']);
    // A typo behind the prefix; the variant markers are not typos.
    expect(
      missed(`const a = 'fve:truncat fve:group/row fve:peer fve:group';`),
    ).toEqual(['unknown fve:truncat']);
  });

  it('lets the data through', () => {
    const missed = (code: string) =>
      scanSource('probe.tsx', code, isUtility).map(({ token }) => token);
    expect(
      missed(`
        type Layout = 'table' | 'grid';
        const layout: Layout = 'table';
        const width: string = 'fixed';
        if (config.layout === 'table') path.push('filter');
        const path = ['table', 'columns', 0, 'hidden'];
        const t = { outline: 1, 'shadow': 2, fallback: 'border', kind: 'shadow' };
        const fixed = (mode: Mode): Mode => mode === 'hidden' ? 'hidden' : 'locked';
        cell.style.overflow = 'hidden';
        const note = 'The table draws its columns in the order listed here.';
        const hover = 'hovered outline button';
        const el = <Button variant="outline" data-state="hidden" aria-sort="none" />;
      `),
    ).toEqual([]);
  });
});

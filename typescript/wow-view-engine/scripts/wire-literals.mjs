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

import { readdirSync, readFileSync, statSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import ts from 'typescript';

/**
 * Where Wow's wire values may be spelled out, and where they may not (D353 ⑥):
 * the engine's source, its tests and its stories read each value off
 * wow-client's enums, so when Wow respells them (D352) they change in
 * wow-client alone. `test/architecture.test.ts` holds the source and the
 * tests to it, and the stories' own suite holds them.
 */

/** A test that is about the spelling itself carries this, with its reason. */
export const WIRE_SPELLING_MARKER = 'wire spelling (D352b)';

const WOW_DSL = resolve(
  dirname(fileURLToPath(import.meta.url)),
  '../../wow-client/src/dsl',
);

function sources(directory) {
  return readdirSync(directory).flatMap(name => {
    const path = join(directory, name);
    if (statSync(path).isDirectory()) return sources(path);
    return /\.tsx?$/.test(name) && !/\.(test|d)\./.test(name) ? [path] : [];
  });
}

/** A TypeScript or TSX file, parsed with its parents set. */
export function parseSource(path) {
  return ts.createSourceFile(
    path,
    readFileSync(path, 'utf8'),
    ts.ScriptTarget.Latest,
    true,
    path.endsWith('.tsx') ? ts.ScriptKind.TSX : ts.ScriptKind.TS,
  );
}

/**
 * The values of Wow's query protocol: every string member of an enum, and
 * of a frozen `{ NAME: 'VALUE' }` constant, in the wow-client DSL sources.
 */
export function wireValues(dsl = WOW_DSL) {
  const values = new Set();
  for (const path of sources(dsl)) {
    const visit = node => {
      if (ts.isEnumMember(node) && node.initializer) {
        if (ts.isStringLiteral(node.initializer))
          values.add(node.initializer.text);
      } else if (
        ts.isCallExpression(node) &&
        node.expression.getText() === 'Object.freeze'
      ) {
        let argument = node.arguments[0];
        while (argument && ts.isAsExpression(argument))
          argument = argument.expression;
        if (argument && ts.isObjectLiteralExpression(argument))
          for (const property of argument.properties)
            if (
              ts.isPropertyAssignment(property) &&
              ts.isStringLiteral(property.initializer) &&
              /^[A-Z][A-Z0-9_]*$/.test(property.initializer.text)
            )
              values.add(property.initializer.text);
      }
      ts.forEachChild(node, visit);
    };
    visit(parseSource(path));
  }
  return values;
}

/** Whether the marker sits on the node's line or leads a node holding it. */
function marked(file, node) {
  const text = file.text;
  const { line } = file.getLineAndCharacterOfPosition(node.getStart(file));
  if (text.split('\n')[line].includes(WIRE_SPELLING_MARKER)) return true;
  for (let current = node; current; current = current.parent) {
    const comments = ts.getLeadingCommentRanges(text, current.getFullStart());
    if (
      comments?.some(({ pos, end }) =>
        text.slice(pos, end).includes(WIRE_SPELLING_MARKER),
      )
    )
      return true;
    if (ts.isSourceFile(current)) return false;
  }
  return false;
}

/**
 * The string literals of a file, and the property names of its object
 * literals, that spell a Wow wire value; import specifiers and test titles
 * are left out. With `honourMarker`, so are those {@link WIRE_SPELLING_MARKER}
 * covers.
 */
export function wireLiterals(file, values, { honourMarker = false } = {}) {
  const found = [];
  const note = (node, value) => {
    if (!values.has(value)) return;
    if (honourMarker && marked(file, node)) return;
    found.push({
      value,
      line: file.getLineAndCharacterOfPosition(node.getStart(file)).line + 1,
    });
  };
  const visit = node => {
    if (
      ts.isImportDeclaration(node) ||
      ts.isExportDeclaration(node) ||
      ts.isImportTypeNode(node)
    )
      return;
    if (ts.isStringLiteral(node) || ts.isNoSubstitutionTemplateLiteral(node)) {
      const call = node.parent;
      const title =
        ts.isCallExpression(call) &&
        call.arguments[0] === node &&
        /^(describe|it|test)\b/.test(call.expression.getText());
      if (!title) note(node, node.text);
    } else if (
      ts.isPropertyAssignment(node) &&
      (ts.isIdentifier(node.name) || ts.isStringLiteral(node.name)) &&
      ts.isObjectLiteralExpression(node.parent)
    )
      note(node.name, node.name.text);
    ts.forEachChild(node, visit);
  };
  visit(file);
  return found;
}

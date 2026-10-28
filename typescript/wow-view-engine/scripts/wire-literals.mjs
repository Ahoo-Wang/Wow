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

import { execFileSync } from 'node:child_process';
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
 *
 * Wow spells its values in lower-kebab (D352b), the words the engine uses
 * for its own things too: 'day', 'in', 'numeric'. A literal is therefore
 * judged by the type it is checked against, not by its spelling: it is a
 * wire value when that type admits every value of the Wow enum that holds
 * it — a `${AggregationDateUnit}` slot, a `Record` keyed by
 * `${AggregationFunction}`. The engine's own narrower vocabularies
 * (`RelativeDateUnit`, the filter tree's `and`/`or`/`nor`) and plain strings
 * are its words. A value written where only `string` is asked is not seen;
 * the types are what carry the protocol.
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
function parseSource(path) {
  return ts.createSourceFile(
    path,
    readFileSync(path, 'utf8'),
    ts.ScriptTarget.Latest,
    true,
    path.endsWith('.tsx') ? ts.ScriptKind.TSX : ts.ScriptKind.TS,
  );
}

/**
 * The vocabularies of Wow's query protocol, each the values of one enum, or
 * of one frozen `{ NAME: 'value' }` table, in the wow-client DSL sources.
 */
export function wireVocabularies(dsl = WOW_DSL) {
  const vocabularies = [];
  for (const path of sources(dsl)) {
    const visit = node => {
      if (ts.isEnumDeclaration(node)) {
        const values = node.members
          .map(member => member.initializer)
          .filter(value => value && ts.isStringLiteral(value))
          .map(value => value.text);
        if (values.length) vocabularies.push(new Set(values));
      } else if (
        ts.isCallExpression(node) &&
        node.expression.getText() === 'Object.freeze'
      ) {
        let argument = node.arguments[0];
        while (argument && ts.isAsExpression(argument))
          argument = argument.expression;
        if (argument && ts.isObjectLiteralExpression(argument)) {
          const values = argument.properties
            .filter(
              property =>
                ts.isPropertyAssignment(property) &&
                ts.isStringLiteral(property.initializer),
            )
            .map(property => property.initializer.text);
          if (values.length) vocabularies.push(new Set(values));
        }
      }
      ts.forEachChild(node, visit);
    };
    visit(parseSource(path));
  }
  return vocabularies;
}

/**
 * The program of a tsconfig, type-checked as its own build checks it: over
 * [roots] when given (the files a scan reads, which pull in what they
 * import), else over the tsconfig's own files.
 */
export function wireProgram(tsconfig, roots) {
  const parsed = ts.getParsedCommandLineOfConfigFile(
    tsconfig,
    {},
    { ...ts.sys, onUnRecoverableConfigFileDiagnostic: () => {} },
  );
  return ts.createProgram({
    rootNames: roots ?? parsed.fileNames,
    options: {
      ...parsed.options,
      noEmit: true,
      skipLibCheck: true,
      composite: false,
      incremental: false,
    },
  });
}

/**
 * The wire literals of each scope of [request] — `{ tsconfig, scopes: {
 * name: { files, honourMarker } } }` — over one program of all their files,
 * by scope name.
 */
export function scanWireLiterals({ tsconfig, scopes }) {
  const vocabularies = wireVocabularies();
  const program = wireProgram(tsconfig, [
    ...new Set(Object.values(scopes).flatMap(scope => scope.files)),
  ]);
  return Object.fromEntries(
    Object.entries(scopes).map(([name, { files, honourMarker = false }]) => [
      name,
      files.flatMap(path =>
        wireLiterals(program, path, vocabularies, { honourMarker }).map(
          found => ({ path, ...found }),
        ),
      ),
    ]),
  );
}

const SELF = fileURLToPath(import.meta.url);

/**
 * [scanWireLiterals] in a Node process of its own. Type-checking the engine
 * is seconds of compiler work, and a test run's coverage instruments every
 * function it executes — the compiler's too — which made the scan ten times
 * slower on CI; the child process is not instrumented.
 */
export function scanWireLiteralsApart(request) {
  return JSON.parse(
    execFileSync(process.execPath, [SELF], {
      input: JSON.stringify(request),
      encoding: 'utf8',
      maxBuffer: 64 * 1024 * 1024,
    }),
  );
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
 * The string literals a type admits, as values. A member of a wow-client
 * enum admits its enum: a discriminated union narrows `{ type: 'terms' }`
 * to the variant typed `AggregationGroupType.TERMS`, and that is still the
 * enum's slot.
 */
function admitted(type) {
  if (!type) return new Set();
  const members = type.isUnion() ? type.types : [type];
  const values = new Set();
  for (const member of members) {
    if (!member.isStringLiteral()) continue;
    values.add(member.value);
    const declaration = member.symbol?.valueDeclaration;
    if (
      member.flags & ts.TypeFlags.EnumLiteral &&
      declaration &&
      ts.isEnumMember(declaration) &&
      /wow-client/.test(declaration.getSourceFile().fileName)
    )
      for (const sibling of declaration.parent.members)
        if (sibling.initializer && ts.isStringLiteral(sibling.initializer))
          values.add(sibling.initializer.text);
  }
  return values;
}

const EQUALITY = new Set([
  ts.SyntaxKind.EqualsEqualsEqualsToken,
  ts.SyntaxKind.ExclamationEqualsEqualsToken,
  ts.SyntaxKind.EqualsEqualsToken,
  ts.SyntaxKind.ExclamationEqualsToken,
]);

/**
 * The type an expression is declared with, before any narrowing: a `switch`
 * past an `if` that returned on two units still asks a unit.
 */
function declaredType(checker, expression) {
  const symbol = checker.getSymbolAtLocation(
    ts.isPropertyAccessExpression(expression) ? expression.name : expression,
  );
  return symbol?.valueDeclaration
    ? checker.getTypeOfSymbolAtLocation(symbol, symbol.valueDeclaration)
    : checker.getTypeAtLocation(expression);
}

/**
 * The values the slot of a string literal admits: what it is compared with,
 * the `switch` it is a case of, or the type it is written to.
 */
function slotValues(checker, node) {
  const parent = node.parent;
  if (ts.isBinaryExpression(parent) && EQUALITY.has(parent.operatorToken.kind))
    return admitted(
      declaredType(checker, parent.left === node ? parent.right : parent.left),
    );
  if (ts.isCaseClause(parent) && parent.expression === node)
    return admitted(declaredType(checker, parent.parent.parent.expression));
  if (
    ts.isElementAccessExpression(parent) &&
    parent.argumentExpression === node
  )
    return keys(checker, checker.getTypeAtLocation(parent.expression));
  const values = admitted(checker.getContextualType(node));
  if (
    ts.isPropertyAssignment(parent) &&
    parent.initializer === node &&
    ts.isObjectLiteralExpression(parent.parent)
  ) {
    // A discriminated union narrows `{ type: 'terms' }` to its variant, typed
    // `${AggregationGroupType.TERMS}`: the declaration still names the enum.
    const object = checker.getContextualType(parent.parent);
    const property =
      object &&
      checker.getPropertyOfType(
        checker.getNonNullableType(object),
        parent.name.getText(),
      );
    for (const declaration of property?.declarations ?? [])
      if (declaration.type)
        for (const value of enumsNamed(checker, declaration.type))
          values.add(value);
  }
  return values;
}

/** The values of every wow-client enum a type node names, or a member of. */
function enumsNamed(checker, typeNode) {
  const values = new Set();
  const visit = node => {
    if (ts.isIdentifier(node)) {
      let symbol = checker.getSymbolAtLocation(node);
      if (symbol && symbol.flags & ts.SymbolFlags.Alias)
        symbol = checker.getAliasedSymbol(symbol);
      let declaration = symbol?.valueDeclaration;
      if (declaration && ts.isEnumMember(declaration))
        declaration = declaration.parent;
      if (
        declaration &&
        ts.isEnumDeclaration(declaration) &&
        /wow-client/.test(declaration.getSourceFile().fileName)
      )
        for (const member of declaration.members)
          if (member.initializer && ts.isStringLiteral(member.initializer))
            values.add(member.initializer.text);
    }
    ts.forEachChild(node, visit);
  };
  visit(typeNode);
  return values;
}

/**
 * The keys a mapped type admits, `Record<K, V>`'s `K`: the properties no
 * declaration names one by one. An interface that happens to declare a
 * `day` and an `hour` (`Intl.DateTimeFormatOptions`) is not keyed by a
 * vocabulary.
 */
function keys(checker, type) {
  if (!type) return new Set();
  return new Set(
    checker
      .getPropertiesOfType(checker.getNonNullableType(type))
      .filter(property =>
        (property.declarations ?? []).every(ts.isMappedTypeNode),
      )
      .map(property => property.name),
  );
}

/**
 * The string literals of a file, and the property names of its object
 * literals, that are a Wow wire value by the type they are checked against;
 * import specifiers and test titles are left out. With `honourMarker`, so
 * are those {@link WIRE_SPELLING_MARKER} covers.
 */
export function wireLiterals(
  program,
  path,
  vocabularies,
  { honourMarker = false } = {},
) {
  const file = program.getSourceFile(path);
  if (!file) throw new Error(`${path} is not in the program`);
  const checker = program.getTypeChecker();
  const found = [];
  const wire = new Set(vocabularies.flatMap(vocabulary => [...vocabulary]));
  // A Wow slot is a union of Wow's vocabularies, each admitted whole: a
  // calendar's `year`…`second` holds `DateDiffUnit` whole, but `year` and
  // `month` only as part of `AggregationDateUnit`, and dayjs's units admit
  // 'millisecond' besides.
  const isWire = (value, admits) => {
    const whole = vocabularies.filter(vocabulary =>
      [...vocabulary].every(member => admits.has(member)),
    );
    return (
      whole.some(vocabulary => vocabulary.has(value)) &&
      [...admits].every(member =>
        whole.some(vocabulary => vocabulary.has(member)),
      )
    );
  };
  const note = (node, value, slot) => {
    if (!wire.has(value) || !isWire(value, slot())) return;
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
        /^(describe|it|test)\b/.test(call.expression.getText(file));
      if (!title) note(node, node.text, () => slotValues(checker, node));
    } else if (
      ts.isPropertyAssignment(node) &&
      (ts.isIdentifier(node.name) || ts.isStringLiteral(node.name)) &&
      ts.isObjectLiteralExpression(node.parent)
    )
      note(node.name, node.name.text, () =>
        keys(checker, checker.getContextualType(node.parent)),
      );
    ts.forEachChild(node, visit);
  };
  visit(file);
  return found;
}

// Run as a script: the scan of the request on stdin, as JSON on stdout.
if (process.argv[1] && resolve(process.argv[1]) === SELF)
  process.stdout.write(
    JSON.stringify(scanWireLiterals(JSON.parse(readFileSync(0, 'utf8')))),
  );

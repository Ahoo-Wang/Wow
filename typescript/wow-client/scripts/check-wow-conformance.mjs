#!/usr/bin/env node
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
 * Diffs the conformance register against a Wow checkout.
 *
 * The register in `test/query/wowConformance.test.ts` says how this package
 * treats each rule Wow enforces, and its tests keep those answers honest. What
 * they cannot see is a rule Wow added after the register was written: nothing
 * fails, the rule is simply absent, and the first sign of it is a 400 in
 * someone's application. This script is that missing half.
 *
 * It reads the protocol package — `wow-api`'s query package, which is the
 * contract a client speaks — and reports any message the register does not
 * name, and any name the register still carries that Wow no longer throws.
 *
 *   node scripts/check-wow-conformance.mjs ../../../Wow
 *   WOW_HOME=/path/to/Wow node scripts/check-wow-conformance.mjs
 */

import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const REGISTER = join(here, '..', 'test', 'query', 'wowConformance.test.ts');

/** The protocol a client speaks. Schema and backend rules live elsewhere. */
const PROTOCOL_DIR = join(
  'wow-api',
  'src',
  'main',
  'kotlin',
  'me',
  'ahoo',
  'wow',
  'api',
  'query',
);

/**
 * Messages in that package the register deliberately leaves out.
 *
 * `LegacyConditionAdapter.kt` converts the deprecated `Condition` shape into a
 * `FilterExpression` on the server. This package can still build a `Condition`
 * for an older server, but the conversion is not a rule its builders can break.
 */
const OUT_OF_SCOPE_FILES = ['LegacyConditionAdapter.kt'];

function kotlinFiles(root) {
  const dir = join(root, PROTOCOL_DIR);
  if (!statSync(dir, { throwIfNoEntry: false })?.isDirectory()) {
    throw new Error(`Not a Wow checkout: no ${PROTOCOL_DIR} under ${root}`);
  }
  const out = [];
  const walk = current => {
    for (const entry of readdirSync(current, { withFileTypes: true })) {
      const path = join(current, entry.name);
      if (entry.isDirectory()) walk(path);
      else if (
        entry.name.endsWith('.kt') &&
        !OUT_OF_SCOPE_FILES.includes(entry.name)
      )
        out.push(path);
    }
  };
  walk(dir);
  return out;
}

/**
 * Reduces a message to what can be compared across the two sources.
 *
 * Kotlin interpolates (`${AggregationQuery.MAX_SORT_FIELDS}`), the register
 * writes the constant's bare name, and neither spelling is the rule. Collapsing
 * every interpolation to one token compares what the rule actually says.
 */
function normalize(message) {
  return message
    .replace(/\$\{[^}]*\}/g, '$')
    .replace(/\$[A-Za-z_]\w*/g, '$')
    .replace(/\s+/g, ' ')
    .trim();
}

/*
 * A small Kotlin lexer, just enough to find where a call's arguments end.
 *
 * A bracket inside a string, a char literal or a comment is not syntax, and
 * treating it as syntax is how this script has gone blind before:
 * `require(value != ")") { "…" }` closed the call at the quoted `)` and the
 * rule vanished, with the checker still reporting success. Each helper takes
 * the index of the token's first character and returns the index just past it.
 */

function skipLineComment(source, index) {
  const end = source.indexOf('\n', index);
  return end < 0 ? source.length : end + 1;
}

function skipBlockComment(source, index) {
  const end = source.indexOf('*/', index + 2);
  return end < 0 ? source.length : end + 2;
}

function skipRawString(source, index) {
  const end = source.indexOf('"""', index + 3);
  return end < 0 ? source.length : end + 3;
}

/** A `"…"` string, including `${…}` templates that may hold strings of their own. */
function skipString(source, index) {
  let at = index + 1;
  while (at < source.length) {
    const char = source[at];
    if (char === '\\') at += 2;
    else if (char === '"') return at + 1;
    else if (char === '$' && source[at + 1] === '{')
      at = skipBalanced(source, at + 2, '{', '}');
    else at += 1;
  }
  return at;
}

function skipChar(source, index) {
  let at = index + 1;
  while (at < source.length && source[at] !== "'")
    at += source[at] === '\\' ? 2 : 1;
  return at + 1;
}

/** Skips a token that brackets inside it cannot count against, or returns -1. */
function skipOpaque(source, index) {
  const char = source[index];
  const next = source[index + 1];
  if (char === '/' && next === '/') return skipLineComment(source, index);
  if (char === '/' && next === '*') return skipBlockComment(source, index);
  if (source.startsWith('"""', index)) return skipRawString(source, index);
  if (char === '"') return skipString(source, index);
  if (char === "'") return skipChar(source, index);
  return -1;
}

/** Index just past the bracket that closes one opened before `index`. */
function skipBalanced(source, index, open, close) {
  let depth = 1;
  let at = index;
  while (at < source.length && depth > 0) {
    const skipped = skipOpaque(source, at);
    if (skipped >= 0) {
      at = skipped;
      continue;
    }
    if (source[at] === open) depth += 1;
    else if (source[at] === close) depth -= 1;
    at += 1;
  }
  return at;
}

/** Past whitespace and comments, which may sit between a call and its block. */
function skipTrivia(source, index) {
  let at = index;
  while (at < source.length) {
    if (/\s/.test(source[at])) at += 1;
    else if (source.startsWith('//', at)) at = skipLineComment(source, at);
    else if (source.startsWith('/*', at)) at = skipBlockComment(source, at);
    else break;
  }
  return at;
}

/** The text of the string literal starting at `index`, or null if none does. */
function literalAt(source, index) {
  if (source.startsWith('"""', index))
    return source.slice(index + 3, skipRawString(source, index) - 3);
  if (source[index] === '"')
    return source.slice(index + 1, skipString(source, index) - 1);
  return null;
}

/**
 * Every message a rule is stated with in one Kotlin file.
 *
 * Kotlin states a rule in more than one way, and a checker that knows only one
 * reports success while missing the rest: `require`, `check` and their
 * `NotNull` forms take a trailing `{ "…" }` block; `error(…)` and a `throw`
 * take the message as their first argument.
 */
function messagesIn(source) {
  const found = [];
  const push = message => {
    if (message !== null) found.push(message);
  };

  for (const match of source.matchAll(
    /\b(?:require|check)(?:NotNull)?\s*\(/g,
  )) {
    const closed = skipBalanced(
      source,
      match.index + match[0].length,
      '(',
      ')',
    );
    const block = skipTrivia(source, closed);
    if (source[block] === '{')
      push(literalAt(source, skipTrivia(source, block + 1)));
  }
  for (const match of source.matchAll(
    /\bthrow\s+\w*(?:Exception|Error)\s*\(|\berror\s*\(/g,
  ))
    push(literalAt(source, skipTrivia(source, match.index + match[0].length)));

  return found;
}

/** Every rule Wow states in the protocol package, normalized. */
function wowRules(root) {
  const found = new Map();
  for (const file of kotlinFiles(root))
    for (const message of messagesIn(readFileSync(file, 'utf8')))
      found.set(normalize(message), message);
  return found;
}

/** Every rule the register names, with the disambiguating suffix removed. */
function registeredRules() {
  const source = readFileSync(REGISTER, 'utf8');
  const names = new Map();
  for (const match of source.matchAll(
    /\bwow:\s*\n?\s*(?:'((?:[^'\\]|\\.)*)'|"((?:[^"\\]|\\.)*)")/g,
  )) {
    const raw = (match[1] ?? match[2] ?? '').replace(/\\(['"\\])/g, '$1');
    names.set(normalize(raw.replace(/\s*\([^)]*\)$/, '')), raw);
  }
  if (names.size === 0) throw new Error(`No rules found in ${REGISTER}`);
  return names;
}

const root = resolve(process.argv[2] ?? process.env.WOW_HOME ?? '');
if (!process.argv[2] && !process.env.WOW_HOME) {
  console.error(
    'Usage: node scripts/check-wow-conformance.mjs <path-to-Wow>  (or set WOW_HOME)',
  );
  process.exit(2);
}

const wow = wowRules(root);
const registered = registeredRules();

const missing = [...wow]
  .filter(([key]) => !registered.has(key))
  .map(([, message]) => message)
  .sort();
const stale = [...registered]
  .filter(([key]) => !wow.has(key))
  .map(([, message]) => message)
  .sort();

console.log(
  `wow-api query rules: ${wow.size}   register entries: ${registered.size}`,
);

if (missing.length > 0) {
  console.error(
    `\n${missing.length} rule(s) Wow enforces that the register does not name:`,
  );
  for (const rule of missing) console.error(`  + ${rule}`);
} else {
  console.log('\nEvery wow-api query rule is named in the register.');
}

if (stale.length > 0) {
  console.log(
    `\n${stale.length} register entr(ies) not found in wow-api — expected for` +
      ' wow-query and backend rules, worth a look otherwise:',
  );
  for (const rule of stale) console.log(`  ? ${rule}`);
}

process.exit(missing.length > 0 ? 1 : 0);

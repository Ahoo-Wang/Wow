/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { trackedFiles } from './docs-layout.mjs';

// Holds the code samples of the documentation to the sources they show, so a
// sample compiles and its tests pass, or this check fails. A fenced block that
// follows a sample comment, with only blank lines between them,
//
//   <!-- sample: examples/quickstart/src/main/kotlin/.../Account.kt -->
//   <!-- sample: examples/eshop/ordering/ordering-domain/src/.../Order.kt#decide -->
//
// is that file of the repository (without its license header), or the region
// of that name in it: the lines between `// region <name>` (or `# region`,
// `<!-- region -->`) and the next `endregion`, without their common
// indentation. Region markers never show in a sample. The modules that own the
// sources compile and test them.
//
// A Kotlin or Java block that holds a whole file (a `package` or an `import`
// line) must be a sample. One that only illustrates says so with
// `<!-- sample: fragment -->`, which this check does not compare. A block
// that shows 9.x code, as a migration does, says `<!-- sample: 9.x -->`.
//
// A Kotlin or Java block that is no sample (a fragment, or a snippet) imports
// from Wow only what its published modules declare: each `import me.ahoo.wow.…`
// names a class, a top-level function or property, or a package of an ABI
// dump (`<module>/api/*.api`), so `@InternalWowApi` declarations and names that
// are gone fail. A sample's imports are its source's, which compiles.
//
// Read: the site's pages, the root READMEs, and the Skills (not their evals).

const ROOT = fileURLToPath(new URL('../..', import.meta.url));

/** The tracked Markdown whose samples this check holds. */
export const isSampled = path =>
  path.endsWith('.md') &&
  (path.startsWith('documentation/docs/') ||
    /^README(?:\.zh-CN)?\.md$/.test(path) ||
    (path.startsWith('skills/') && !/\/evals\//.test(path)));

const SAMPLE = /^<!-- sample: (\S+) -->[ \t]*$/;
const FENCE = /^(`{3,})([\w-]*)/;
const WHOLE_FILE = new Set(['kotlin', 'kt', 'java']);
const MARKER = /^\s*(?:\/\/|#|<!--)\s*(end)?region\b\s*([\w.-]*)/;
const LICENSE_HEADER = /^\/\*[\s\S]*?\*\/\n+/;

/** The fenced blocks of a page: `{ line, info, body, sample }`, sample the comment before it, if any. */
export function blocks(text) {
  const lines = text.split('\n');
  const found = [];
  let sample;
  for (let i = 0; i < lines.length; i++) {
    const comment = SAMPLE.exec(lines[i]);
    if (comment) {
      sample = { path: comment[1], line: i + 1 };
      continue;
    }
    const fence = FENCE.exec(lines[i]);
    if (fence) {
      const end = lines.findIndex(
        (line, j) =>
          j > i && line.startsWith(fence[1]) && line.trim() === fence[1],
      );
      const close = end < 0 ? lines.length : end;
      found.push({
        line: i + 1,
        info: fence[2],
        body: lines.slice(i + 1, close).join('\n'),
        sample,
      });
      sample = undefined;
      i = close;
      continue;
    }
    if (lines[i].trim() !== '') sample = undefined;
  }
  return found;
}

const dedent = lines => {
  const indent = Math.min(
    ...lines
      .filter(line => line.trim())
      .map(line => /^\s*/.exec(line)[0].length),
  );
  return lines.map(line => line.slice(Number.isFinite(indent) ? indent : 0));
};

/** The text a sample shows: the file without its license header, or one region; undefined if there is none. */
export function sampleText(source, region) {
  const lines = source.replace(LICENSE_HEADER, '').split('\n');
  if (!region) {
    return lines
      .filter(line => !MARKER.test(line))
      .join('\n')
      .trimEnd();
  }
  const start = lines.findIndex(line => {
    const marker = MARKER.exec(line);
    return marker && !marker[1] && marker[2] === region;
  });
  if (start < 0) return undefined;
  // Its own endregion: regions nest.
  let depth = 0;
  let end = lines.length;
  for (let i = start + 1; i < lines.length; i++) {
    const marker = MARKER.exec(lines[i]);
    if (!marker) continue;
    if (!marker[1]) depth++;
    else if (depth-- === 0) {
      end = i;
      break;
    }
  }
  const body = lines.slice(start + 1, end).filter(line => !MARKER.test(line));
  return dedent(body).join('\n').trimEnd();
}

/** The names the ABI dumps declare: a predicate over imported names. */
export function abiNames(dumps) {
  const names = new Set();
  for (const dump of dumps) {
    let type;
    for (const line of dump.split('\n')) {
      const declared = /^\S.*?\b((?:[\w$]+\/)+[\w$]+)\b/.exec(line);
      if (declared) {
        // The type and its packages; an outer type counts only by its own line (an internal type's
        // companion can show in a dump without it).
        const slash = declared[1].lastIndexOf('/');
        type = declared[1].replaceAll('/', '.').replaceAll('$', '.');
        names.add(type);
        const packages = declared[1].slice(0, slash).split('/');
        for (let i = 1; i <= packages.length; i++)
          names.add(packages.slice(0, i).join('.'));
        continue;
      }
      const member =
        type && /^\s+public (.*?)\b(?:fun|field) (\w+) /.exec(line);
      if (!member) continue;
      const [, modifiers, name] = member;
      const property = /^(?:get|is)([A-Z]\w*)$/.exec(name)?.[1];
      const named = property
        ? [name, `${property[0].toLowerCase()}${property.slice(1)}`]
        : [name];
      // A member of a type; a static one may also be a top-level function or property of its file facade
      // (`FooKt`, or the name `@file:JvmName` gives it), imported from the package.
      const owners = /\bstatic\b/.test(modifiers)
        ? [type, type.slice(0, type.lastIndexOf('.'))]
        : [type];
      for (const owner of owners)
        for (const each of named) names.add(`${owner}.${each}`);
    }
  }
  return name => names.has(name);
}

/** The Wow names a block imports: `me.ahoo.wow.a.B`, or `me.ahoo.wow.a` for `me.ahoo.wow.a.*`. */
export const wowImports = body =>
  [
    ...body.matchAll(
      /^\s*import\s+(?:static\s+)?(me\.ahoo\.wow(?:\.\w+)+?)(\.\*)?(?:\s+as\s+\w+)?\s*;?\s*$/gm,
    ),
  ].map(match => match[1]);

let published;
const readDumps = root =>
  abiNames(
    trackedFiles(root)
      .filter(path => /(?:^|\/)api\/[^/]+\.api$/.test(path))
      .map(path => readFileSync(join(root, path), 'utf8')),
  );

/** The problems of one page, its samples read from `root`; `declared` tells the names the ABI dumps declare (the repository's by default). */
export function sampleProblems(page, text, root = ROOT, declared) {
  const problems = [];
  for (const block of blocks(text)) {
    const where = `${page}:${block.line}`;
    if (block.sample?.path === '9.x') continue;
    if (
      WHOLE_FILE.has(block.info) &&
      (!block.sample || block.sample.path === 'fragment')
    ) {
      const known = declared ?? (published ??= readDumps(ROOT));
      for (const name of wowImports(block.body).filter(name => !known(name)))
        problems.push(
          `${where}: ${name} is not in the ABI dump of any published module`,
        );
    }
    if (!block.sample) {
      if (
        WHOLE_FILE.has(block.info) &&
        /^(?:package|import) /m.test(block.body)
      ) {
        problems.push(
          `${where}: a ${block.info} block with a package or import line is a whole file: show it as a sample (<!-- sample: <path> -->), or mark it <!-- sample: fragment -->`,
        );
      }
      continue;
    }
    if (block.sample.path === 'fragment') continue;
    const [path, region] = block.sample.path.split('#');
    const file = join(root, path);
    if (!existsSync(file)) {
      problems.push(`${where}: the sample's source ${path} does not exist`);
      continue;
    }
    const expected = sampleText(readFileSync(file, 'utf8'), region);
    if (expected === undefined) {
      problems.push(`${where}: ${path} has no region ${region}`);
    } else if (block.body.trimEnd() !== expected) {
      problems.push(
        `${where}: the sample differs from ${block.sample.path}; copy the source into the page`,
      );
    }
  }
  return problems;
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(process.argv[1]).href
) {
  const pages = trackedFiles().filter(isSampled);
  const problems = pages.flatMap(page =>
    sampleProblems(page, readFileSync(join(ROOT, page), 'utf8')),
  );
  for (const problem of problems) console.error(problem);
  if (problems.length > 0) process.exit(1);
  console.log(
    `The documentation samples match their sources (${pages.length} pages)`,
  );
}

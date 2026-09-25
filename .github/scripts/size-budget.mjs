/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import { readFileSync } from 'node:fs';
import { dirname, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { gzipSync } from 'node:zlib';

const ROOT = fileURLToPath(new URL('../..', import.meta.url));

// Size regression guards for the built packages, run by each package's
// `scripts/verify-package.mjs` (so by its `build`, in CI's package and
// verify steps, and by the release preflight). Size is not a target:
// function and experience come first. A ceiling sits about 15–20% above
// what was measured when it was set, so real features fit under it, and
// only an accidental blow-up — a dependency bundled in by mistake, a chunk
// that stopped being lazy — trips it.
//
// What an entry weighs is the code importing it loads: the entry file and
// every file of the package it imports statically, however deep, as one
// text gzipped at level 9. External packages are not counted; they are the
// host's. A dynamic `import()` is not followed: that code arrives later,
// and a lazily loaded chunk is measured on its own, less what its importer
// has already loaded.

/** `import … from './x.js'`, `import './x.js'` and `export … from './x.js'`. */
const STATIC_IMPORT =
  /(?:^|[\s;}])(?:import|export)\s*(?:[\w$*{}\s,]*?\s*from\s*)?["'](\.{1,2}\/[^"']+)["']/g;
/** `import('./x.js')`, the one form of dynamic import a bundler writes. */
const DYNAMIC_IMPORT = /\bimport\(\s*["'](\.{1,2}\/[^"']+)["']\s*\)/g;

/** The package's own files one built module imports, statically and lazily. */
export function importsOf(file) {
  const text = readFileSync(file, 'utf8');
  const at = specifier => resolve(dirname(file), specifier);
  return {
    static: [...text.matchAll(STATIC_IMPORT)].map(match => at(match[1])),
    dynamic: [...text.matchAll(DYNAMIC_IMPORT)].map(match => at(match[1])),
  };
}

/**
 * Every file the entry loads before its first line runs: itself and its
 * static imports, transitively, in a stable order. Files in `loaded` are
 * already on the page and are left out.
 */
export function staticClosure(entry, loaded = new Set()) {
  const seen = new Set();
  const visit = file => {
    if (seen.has(file) || loaded.has(file)) return;
    seen.add(file);
    for (const next of importsOf(file).static) visit(next);
  };
  visit(resolve(entry));
  return [...seen].sort();
}

/** The chunks the files load lazily, by `import()`, not already among them. */
export function lazyChunks(files) {
  const among = new Set(files);
  const lazy = new Set();
  for (const file of files)
    for (const next of importsOf(file).dynamic)
      if (!among.has(next)) lazy.add(next);
  return [...lazy].sort();
}

/** The files, concatenated in the order given, gzipped at level 9. */
export function gzippedSize(files) {
  return gzipSync(Buffer.concat(files.map(file => readFileSync(file))), {
    level: 9,
  }).length;
}

/**
 * What a package's entries weigh against their ceilings, as one line to
 * print, and every problem found: an entry over its ceiling, an entry with
 * no ceiling, a ceiling with no entry. A problem says which entry, what it
 * measured, what its ceiling is, and how to raise it on purpose.
 */
export function judgeSizes({ packageName, budgetFile, measured, ceilings }) {
  const problems = [];
  const where = relative(ROOT, budgetFile);
  const how =
    `If the growth is intended, raise the ceiling in ${where} in the same ` +
    'pull request — to about 15–20% above the new measured size — and say ' +
    'why in its "reason". These ceilings guard against accidental growth; ' +
    'size is not a target.';
  for (const [entry, bytes] of Object.entries(measured)) {
    const ceiling = ceilings[entry];
    if (!ceiling) {
      problems.push(
        `${packageName} ${named(entry)} measured ${format(bytes)} gzipped and has ` +
          `no ceiling. Add one to ${where}: about 15–20% above what it measures, with a "reason".`,
      );
      continue;
    }
    if (bytes > ceiling.gzip)
      problems.push(
        `${packageName} ${named(entry)} is ${format(bytes)} gzipped, over its ` +
          `ceiling of ${format(ceiling.gzip)} by ${format(bytes - ceiling.gzip)} ` +
          `(+${(((bytes - ceiling.gzip) / ceiling.gzip) * 100).toFixed(1)}%). ${how}`,
      );
  }
  for (const entry of Object.keys(ceilings))
    if (!(entry in measured))
      problems.push(
        `${packageName} ${where} holds a ceiling for ${named(entry)}, which the ` +
          'package no longer builds: remove it, or measure the entry again.',
      );
  const summary = Object.entries(measured)
    .map(
      ([entry, bytes]) =>
        `${named(entry)} ${format(bytes)} of ${ceilings[entry] ? format(ceilings[entry].gzip) : '?'}`,
    )
    .join(', ');
  return { summary, problems };
}

/**
 * Reads the ceilings and judges the measured sizes: answers the line to
 * print when all is well, and otherwise prints every problem, plainly and
 * without a stack, and ends the run with a failure.
 */
export function checkSizes({ packageName, budgetFile, measured }) {
  const { ceilings } = JSON.parse(readFileSync(budgetFile, 'utf8'));
  const { summary, problems } = judgeSizes({
    packageName,
    budgetFile,
    measured,
    ceilings,
  });
  if (problems.length > 0) {
    console.error(`\nSize budget failed:\n- ${problems.join('\n- ')}\n`);
    process.exit(1);
  }
  return summary;
}

/** An entry as a reader names it: the root entry, or its subpath. */
function named(entry) {
  return entry === '.' ? 'root entry' : entry;
}

function format(bytes) {
  return `${bytes.toLocaleString('en-US')} B`;
}

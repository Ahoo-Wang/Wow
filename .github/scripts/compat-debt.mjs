/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

// Keeps docs/compat-debt.md and the compatibility markers in the TypeScript
// sources in step: every marked file is listed by an entry, every entry points
// at files that still hold a marker, and every @deprecated says when it goes.

const ROOT = fileURLToPath(new URL('../..', import.meta.url));
export const LEDGER = 'docs/compat-debt.md';
const REMOVED = 'Removed in v10.';
const COMPAT = /\bcompat\([^)]+\):\s*\S/;
const SOURCE = /\.(?:[cm]?[jt]sx?)$/;

/** True when the text holds a compatibility marker of either kind. */
export function hasMarker(text) {
  return text.includes(REMOVED) || COMPAT.test(text);
}

/** Every @deprecated doc comment that does not say `Removed in v10.`. */
export function unscheduledDeprecations(text) {
  const found = [];
  for (const match of text.matchAll(/\/\*\*[\s\S]*?\*\//g)) {
    if (!match[0].includes('@deprecated') || match[0].includes(REMOVED))
      continue;
    const at = match.index + match[0].indexOf('@deprecated');
    found.push(text.slice(0, at).split('\n').length);
  }
  return found;
}

/** The ledger's entries: `### ` headings with the files their Markers line lists. */
export function parseLedger(markdown) {
  const entries = [];
  let entry;
  for (const line of markdown.split('\n')) {
    const heading = /^### (.+)$/.exec(line);
    if (heading) {
      entry = { title: heading[1].trim(), files: [] };
      entries.push(entry);
    } else if (/^## /.test(line)) {
      entry = undefined;
    } else if (entry && /^- \*\*Markers\*\*:/.test(line)) {
      for (const [, path] of line.matchAll(/`([^`]+)`/g))
        entry.files.push(path);
    }
  }
  return entries;
}

/** Source files under typescript/<package>/src, relative to root. */
export function sourceFiles(root = ROOT) {
  const files = [];
  const walk = dir => {
    for (const entry of readdirSync(join(root, dir), { withFileTypes: true })) {
      const path = `${dir}/${entry.name}`;
      if (entry.isDirectory()) walk(path);
      else if (SOURCE.test(entry.name)) files.push(path);
    }
  };
  for (const entry of readdirSync(join(root, 'typescript'), {
    withFileTypes: true,
  }))
    if (
      entry.isDirectory() &&
      existsSync(join(root, 'typescript', entry.name, 'src'))
    )
      walk(`typescript/${entry.name}/src`);
  return files.sort();
}

/** Every way the ledger and the markers disagree. */
export function ledgerProblems(root = ROOT) {
  const problems = [];
  const read = path => readFileSync(join(root, path), 'utf8');
  const entries = parseLedger(read(LEDGER));
  const listed = new Set(entries.flatMap(entry => entry.files));
  for (const file of sourceFiles(root)) {
    const text = read(file);
    for (const line of unscheduledDeprecations(text))
      problems.push(`${file}:${line}: @deprecated without "${REMOVED}"`);
    if (hasMarker(text) && !listed.has(file))
      problems.push(
        `${file}: has compatibility markers but no ${LEDGER} entry lists it`,
      );
  }
  if (entries.length === 0) problems.push(`${LEDGER}: no ### entries`);
  for (const entry of entries) {
    if (entry.files.length === 0)
      problems.push(`${LEDGER} "${entry.title}": lists no marked file`);
    for (const file of entry.files) {
      if (!existsSync(join(root, file)))
        problems.push(`${LEDGER} "${entry.title}": ${file} does not exist`);
      else if (!hasMarker(read(file)))
        problems.push(
          `${LEDGER} "${entry.title}": ${file} holds no compatibility marker`,
        );
    }
  }
  return problems;
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(process.argv[1]).href
) {
  const problems = ledgerProblems();
  for (const problem of problems) console.error(problem);
  if (problems.length > 0) process.exit(1);
  console.log(`${LEDGER} matches every compatibility marker`);
}

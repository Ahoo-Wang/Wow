/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { basename, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

// Keeps the repository free of leftovers that nothing reads: version catalog
// entries no build script uses, ignore and labeler entries that name paths no
// longer in the tree, and CI scripts nothing calls. It reads only the tracked
// files (no Gradle, no dependencies). Dead `main` source links are checked by
// documentation/test/source-links.test.mjs. A workflow trigger names only this
// line's branch, `main`, the site's `gh-pages`, or a pattern (`bench-run/**`):
// after a branch is renamed, a trigger still naming the old one never fires.
//
// Run by ci.yml (the docs-layout job, on every pull request):
//   node .github/scripts/repo-hygiene.mjs

const ROOT = fileURLToPath(new URL('../..', import.meta.url));

/**
 * Known leftovers, each removed by the change that owns it. An entry that is no
 * longer a problem fails the check too, so the change that fixes it deletes it
 * here and the check tightens. Keys are what the problem names:
 * `<file>: <entry>`; values name the owner.
 */
export const ALLOWED = new Map([]);

export function trackedFiles(cwd = ROOT) {
  return execFileSync('git', ['ls-files', '-z'], { cwd, encoding: 'utf8' })
    .split('\0')
    .filter(Boolean);
}

/**
 * The entries of a Gradle version catalog: `{ section, name, line }`, one per
 * `name = …` line (an entry is written on one line, as this catalog does).
 */
export function catalogEntries(toml) {
  const entries = [];
  let section = null;
  for (const raw of toml.split('\n')) {
    const line = raw.replace(/#.*$/, '').trim();
    const header = /^\[(\w+)\]$/.exec(line);
    if (header) section = header[1];
    const entry = /^([A-Za-z0-9_.-]+)\s*=/.exec(line);
    if (entry && section) entries.push({ section, name: entry[1], line });
  }
  return entries;
}

/** The type-safe accessor path of a catalog entry, as a build script writes it. */
function accessor({ section, name }) {
  const path = name.replace(/[-_.]/g, '.');
  return section === 'libraries' ? path : `${section}.${path}`;
}

/**
 * Catalog entries no build script uses, as `<section>.<name>`. A used bundle
 * uses its libraries, and a used entry's `version.ref` uses that version.
 */
export function unusedCatalogEntries(toml, scripts) {
  const chains = new Set();
  for (const script of scripts) {
    for (const [, chain] of script.matchAll(/\blibs\.([A-Za-z0-9_.]+)/g)) {
      chains.add(chain.replace(/\.(get|asProvider|orNull)$/, ''));
    }
  }
  const key = ({ section, name }) => `${section}.${name}`;
  const entries = catalogEntries(toml);
  const used = new Set(
    entries.filter(entry => chains.has(accessor(entry))).map(key),
  );
  for (const entry of entries) {
    if (entry.section !== 'bundles' || !used.has(key(entry))) continue;
    const members = entry.line.slice(entry.line.indexOf('='));
    for (const [, library] of members.matchAll(/"([A-Za-z0-9_.-]+)"/g)) {
      used.add(`libraries.${library}`);
    }
  }
  for (const entry of entries) {
    const ref = /version\.ref\s*=\s*"([^"]+)"/.exec(entry.line);
    if (ref && used.has(key(entry))) used.add(`versions.${ref[1]}`);
  }
  return entries.map(key).filter(entry => !used.has(entry));
}

/** A gitignore-style glob as a regular expression over a whole path. */
function globRegExp(glob) {
  let source = '';
  for (let i = 0; i < glob.length; i++) {
    const c = glob[i];
    if (c === '*' && glob[i + 1] === '*') {
      source += '.*';
      i++;
      if (glob[i + 1] === '/') i++;
    } else if (c === '*') source += '[^/]*';
    else if (c === '?') source += '[^/]';
    else source += c.replace(/[.+^${}()|[\]\\]/g, '\\$&');
  }
  return new RegExp(`^${source}`);
}

const hasGlob = segment => /[*?[]/.test(segment);

/**
 * The concrete path of an ignore pattern, or null for a pure wildcard: a
 * pattern whose first segment is a glob, or a bare name an unanchored pattern
 * matches at any depth (`node_modules/`, `.DS_Store`).
 */
export function concretePath(pattern) {
  const line = pattern.trim();
  if (!line || line.startsWith('#')) return null;
  const anchored = line.replace(/^!/, '').startsWith('/');
  const path = line.replace(/^!/, '').replace(/^\//, '').replace(/\/$/, '');
  if (!path || hasGlob(path.split('/')[0])) return null;
  if (!anchored && !path.includes('/')) return null;
  return path;
}

const matches = (files, path) => {
  const pattern = globRegExp(path);
  return files.some(
    file => pattern.test(file) && /^(\/|$)/.test(file.replace(pattern, '')),
  );
};

/**
 * Concrete paths of an ignore file that match nothing. A .prettierignore entry
 * names tracked files, so it must match one; a .gitignore entry names output
 * beside tracked files, so its directory must hold a tracked file (a single
 * root entry such as `/build/` names local output and is not checked).
 */
export function staleIgnoreEntries(text, files, { tracked }) {
  return text
    .split('\n')
    .map(concretePath)
    .filter(Boolean)
    .filter(path => {
      if (tracked) return !matches(files, path);
      const parent = path.split('/').slice(0, -1).join('/');
      return parent !== '' && !matches(files, parent);
    });
}

/** Globs of .github/labeler.yml that match no tracked file. */
export function staleLabelerGlobs(yaml, files) {
  return [...yaml.matchAll(/^\s*-\s*'([^']+)'\s*$/gm)]
    .map(([, glob]) => glob)
    .filter(glob => !glob.startsWith('^')) // head-branch patterns
    .filter(glob => !hasGlob(glob.split('/')[0]))
    .filter(glob => !matches(files, glob));
}

/** The branches a workflow trigger may name: this line's and the site's. */
export const BRANCHES = ['main', 'gh-pages'];

/**
 * The branch names of a workflow's `branches:` and `branches-ignore:` filters,
 * inline (`[main, v9]`) or as a list, that are neither in `branches` nor a
 * pattern.
 */
export function foreignBranches(yaml, branches = BRANCHES) {
  const names = [];
  const lines = yaml.split('\n');
  lines.forEach((line, index) => {
    const filter = /^(\s*)branches(?:-ignore)?:\s*(.*)$/.exec(line);
    if (!filter) return;
    const inline = filter[2].replace(/#.*$/, '').trim();
    if (inline.startsWith('[')) {
      names.push(...inline.slice(1, -1).split(','));
      return;
    }
    for (const item of lines.slice(index + 1)) {
      const entry = /^\s*-\s*(.+)$/.exec(item);
      if (!entry) break;
      names.push(entry[1]);
    }
  });
  return names
    .map(name =>
      name
        .replace(/#.*$/, '')
        .trim()
        .replace(/^['"]|['"]$/g, ''),
    )
    .filter(name => name && !hasGlob(name) && !branches.includes(name));
}

/**
 * `.github/scripts/*.mjs` (tests aside) that no workflow, package.json script
 * or other script (`.github/scripts/`, a package's `scripts/`) names.
 */
export function uncalledScripts(files, read) {
  const scripts = files.filter(
    file =>
      /^\.github\/scripts\/[^/]+\.mjs$/.test(file) &&
      !file.endsWith('.test.mjs'),
  );
  const callers = files.filter(
    file =>
      /^\.github\/workflows\/[^/]+\.ya?ml$/.test(file) ||
      /(^|\/)package\.json$/.test(file) ||
      (/(^|\/)scripts\/[^/]+\.(c?js|mjs|ts|sh)$/.test(file) &&
        !/\.test\.m?[jt]s$/.test(file)),
  );
  const texts = new Map(callers.map(file => [file, read(file)]));
  return scripts.filter(script => {
    const name = basename(script);
    return ![...texts].some(
      ([file, text]) => file !== script && text.includes(name),
    );
  });
}

export function hygieneProblems(cwd = ROOT, allowed = ALLOWED) {
  const files = trackedFiles(cwd);
  const read = file => readFileSync(join(cwd, file), 'utf8');
  const problems = [];
  const found = new Set();
  const report = (file, entry, why) => {
    found.add(`${file}: ${entry}`);
    if (!allowed.has(`${file}: ${entry}`))
      problems.push(`${file}: ${entry}: ${why}`);
  };
  const catalog = 'gradle/libs.versions.toml';
  if (files.includes(catalog)) {
    const scripts = files
      .filter(
        file =>
          /\.gradle\.kts$/.test(file) || /^build-logic\/.*\.kts?$/.test(file),
      )
      .map(read);
    for (const entry of unusedCatalogEntries(read(catalog), scripts)) {
      report(catalog, entry, 'no build script uses it');
    }
  }
  for (const [file, tracked] of [
    ['.gitignore', false],
    ['.prettierignore', true],
  ]) {
    if (!files.includes(file)) continue;
    for (const path of staleIgnoreEntries(read(file), files, { tracked })) {
      report(
        file,
        path,
        tracked
          ? 'matches no tracked file'
          : 'its directory holds no tracked file',
      );
    }
  }
  const labeler = '.github/labeler.yml';
  if (files.includes(labeler)) {
    for (const glob of staleLabelerGlobs(read(labeler), files)) {
      report(labeler, glob, 'matches no tracked file');
    }
  }
  for (const workflow of files.filter(file =>
    /^\.github\/workflows\/[^/]+\.ya?ml$/.test(file),
  )) {
    for (const branch of foreignBranches(read(workflow))) {
      report(
        workflow,
        branch,
        `names a branch other than ${BRANCHES.join(', ')}`,
      );
    }
  }
  for (const script of uncalledScripts(files, read)) {
    report(
      script,
      basename(script),
      'no workflow, package.json script or other script runs it',
    );
  }
  for (const [entry, owner] of allowed) {
    if (!found.has(entry))
      problems.push(
        `${entry}: allowed for ${owner}, but no longer a problem; delete it from ALLOWED`,
      );
  }
  return problems;
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(process.argv[1]).href
) {
  const problems = hygieneProblems();
  if (problems.length > 0) {
    console.error(
      `Repository hygiene (.github/scripts/repo-hygiene.mjs):\n${problems.map(p => `- ${p}`).join('\n')}`,
    );
    process.exit(1);
  }
  console.log('Repository hygiene holds');
}

/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import { execFileSync } from 'node:child_process';
import { appendFileSync, readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

// A pull request's patch coverage from its own run's reports, the coverage
// part of ci.yml's merge signal (the coverage job writes the verdict, ci-gate
// reads it):
//
//   BASE=<merge base> HEAD_SHA=<sha> GITHUB_OUTPUT=<file> \
//     node .github/scripts/patch-coverage.mjs <reports directory>
//
// The reports are the run's coverage artifacts as the coverage job downloads
// them, one directory each: the JaCoCo XML of the check and integration shards
// (already without their closing-brace lines, jacoco-braces.mjs) and each
// TypeScript package's Istanbul JSON (`coverage-final.json`, vitest's report,
// which Codecov reads too). A line counts when some report instruments it:
// covered when a report ran it and took every branch, partial when a branch
// was left, missed when nothing ran it. Patch coverage is covered ÷ counted
// over the main-source lines the pull request adds or changes against its
// merge base with main, with codecov.yml's `ignore` left out, and must reach
// codecov.yml's patch target less its threshold (90% − 1%), as Codecov's
// patch status did. A pull request whose changed lines no report counts has
// no patch to judge.
//
// A changed main-source file no report instruments is named. When no report
// of its kind ran at all (the JVM shards for a Kotlin or Java file, the
// package's unit job for a TypeScript one), its tests did not run, and the
// verdict fails. Otherwise it warns: on main (ddb557db7b, 2026-10-29) 386 of
// 2,160 main-source files have no line any report counts, and they are files
// with no executable line (annotations, interfaces, constants) or modules
// outside the coverage aggregation (skills, benchmarks, rehearsals).

/** The patch target and the threshold below it that still passes (codecov.yml). */
export const TARGET = 90;
export const THRESHOLD = 1;

/** Kotlin and Java main sources, and the TypeScript packages' sources. */
const MAIN_SOURCE = [
  /(^|\/)src\/main\/(kotlin|java)\/.+\.(kt|java)$/,
  /^typescript\/(wow-[\w-]+)\/src\/.+\.tsx?$/,
];
const NOT_SOURCE = /\.d\.ts$|\.(test|spec|stories)\.tsx?$/;

export function isMainSource(path) {
  return (
    MAIN_SOURCE.some(pattern => pattern.test(path)) && !NOT_SOURCE.test(path)
  );
}

/** The report a main-source file's lines come from: `jvm`, or its TypeScript package. */
export function reportKind(path) {
  const typescript = MAIN_SOURCE[1].exec(path);
  return typescript ? typescript[1] : 'jvm';
}

/** codecov.yml's `ignore` entries: paths and globs (`*`, `**`). */
export function ignorePatterns(codecovYaml) {
  const block = /\nignore:\n((?:[ \t]+(?:-.*|#.*)?\n)+)/.exec(
    `\n${codecovYaml}`,
  );
  if (!block) return [];
  return [...block[1].matchAll(/^[ \t]+-\s*["']?([^"'#\s]+)["']?/gm)].map(
    ([, glob]) =>
      new RegExp(
        `^${glob
          .replace(/[.+^${}()|[\]\\]/g, '\\$&')
          .replace(/\*\*\/?/g, '\u0000')
          .replace(/\*/g, '[^/]*')
          .replace(/\u0000/g, '.*')}$`,
      ),
  );
}

/** The lines a unified diff (`-U0`) adds or changes, by the new file's path. */
export function changedLines(diff) {
  const changed = new Map();
  let file = null;
  let line = 0;
  for (const text of diff.split('\n')) {
    if (text.startsWith('+++ ')) {
      file = text === '+++ /dev/null' ? null : text.slice(6);
      continue;
    }
    const hunk = /^@@ -\d+(?:,\d+)? \+(\d+)(?:,\d+)? @@/.exec(text);
    if (hunk) {
      line = Number(hunk[1]);
      continue;
    }
    if (!file || text.startsWith('---')) continue;
    if (text.startsWith('+')) {
      if (!changed.has(file)) changed.set(file, new Set());
      changed.get(file).add(line++);
    }
  }
  return changed;
}

/** Merges a line's state into a file's lines: a line any report covers is covered. */
const RANK = { missed: 0, partial: 1, covered: 2 };
function merge(coverage, path, nr, state) {
  if (!coverage.has(path)) coverage.set(path, new Map());
  const lines = coverage.get(path);
  const was = lines.get(nr);
  if (was === undefined || RANK[state] > RANK[was]) lines.set(nr, state);
}

/** A JaCoCo XML report's lines, by repository path (the report's package is the file's directory). */
export function addJacoco(coverage, xml) {
  for (const [, pkg, body] of xml.matchAll(
    /<package name="([^"]*)">([\s\S]*?)<\/package>/g,
  ))
    for (const [, name, lines] of body.matchAll(
      /<sourcefile name="([^"]*)">([\s\S]*?)<\/sourcefile>/g,
    ))
      for (const [, attributes] of lines.matchAll(/<line ([^>]*?)\/>/g)) {
        const at = Object.fromEntries(
          [...attributes.matchAll(/(\w+)="(\d+)"/g)].map(([, k, v]) => [
            k,
            Number(v),
          ]),
        );
        if (at.mi + at.ci === 0) continue;
        const state =
          at.ci === 0 ? 'missed' : at.mb > 0 ? 'partial' : 'covered';
        merge(coverage, `${pkg}/${name}`, at.nr, state);
      }
  return coverage;
}

/**
 * An Istanbul JSON report's lines, by repository path (`root` is the checkout
 * the absolute paths start with). A line's count is its statements' highest,
 * as Istanbul's own line coverage; a run line with an untaken branch is partial.
 */
export function addIstanbul(coverage, json, root) {
  const prefix = root.endsWith('/') ? root : `${root}/`;
  for (const file of Object.values(json)) {
    const path = file.path.startsWith(prefix)
      ? file.path.slice(prefix.length)
      : file.path;
    const counts = new Map();
    for (const [id, location] of Object.entries(file.statementMap ?? {})) {
      const line = location.start.line;
      counts.set(line, Math.max(counts.get(line) ?? 0, file.s[id] ?? 0));
    }
    const untaken = new Set();
    for (const [id, branch] of Object.entries(file.branchMap ?? {}))
      (file.b[id] ?? []).forEach((count, i) => {
        if (count === 0)
          untaken.add((branch.locations?.[i] ?? branch.loc).start.line);
      });
    for (const [line, count] of counts)
      merge(
        coverage,
        path,
        line,
        count === 0 ? 'missed' : untaken.has(line) ? 'partial' : 'covered',
      );
  }
  return coverage;
}

/**
 * The verdict over the changed lines. `kinds` are the report kinds the run
 * has (`jvm`, the TypeScript packages), `ignored` codecov.yml's patterns.
 */
export function patchVerdict({
  changed,
  coverage,
  kinds,
  ignored = [],
  target = TARGET,
  threshold = THRESHOLD,
}) {
  let covered = 0;
  let counted = 0;
  const uncovered = [];
  const untested = [];
  const noData = [];
  for (const [path, lines] of changed) {
    if (!isMainSource(path) || ignored.some(pattern => pattern.test(path)))
      continue;
    const file = coverage.get(path);
    if (!file) {
      (kinds.has(reportKind(path)) ? noData : untested).push(path);
      continue;
    }
    for (const nr of [...lines].sort((a, b) => a - b)) {
      const state = file.get(nr);
      if (state === undefined) continue;
      counted++;
      if (state === 'covered') covered++;
      else
        uncovered.push(
          `${path}:${nr}${state === 'partial' ? ' (partial)' : ''}`,
        );
    }
  }
  const percent = counted ? (covered / counted) * 100 : null;
  const floor = target - threshold;
  const short = percent !== null && percent < floor;
  const state = untested.length || short ? 'fail' : counted ? 'pass' : 'none';
  const parts = [];
  if (percent !== null)
    parts.push(
      `patch coverage ${percent.toFixed(2)}% (${covered} of ${counted} changed lines), ${short ? 'below' : 'at least'} ${floor}% (target ${target}%, threshold ${threshold}%)`,
    );
  else parts.push('no changed line that a report instruments');
  if (untested.length)
    parts.push(
      `no report of their kind ran, so their tests did not: ${untested.join(', ')}`,
    );
  return {
    state,
    percent,
    covered,
    counted,
    uncovered,
    untested,
    noData,
    message: parts.join('; '),
  };
}

/** The verdict as Markdown, for the job summary. */
export function summary(verdict) {
  const lines = [`### Patch coverage: ${verdict.state}`, '', verdict.message];
  if (verdict.uncovered.length)
    lines.push(
      '',
      '**Changed lines no test runs:**',
      '',
      ...verdict.uncovered.map(line => `- \`${line}\``),
    );
  if (verdict.noData.length)
    lines.push(
      '',
      '**Changed files no report instruments** (no executable line, or outside the coverage aggregation):',
      '',
      ...verdict.noData.map(path => `- \`${path}\``),
    );
  return `${lines.join('\n')}\n`;
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(process.argv[1]).href
) {
  const [directory] = process.argv.slice(2);
  const { BASE, HEAD_SHA, GITHUB_OUTPUT, GITHUB_STEP_SUMMARY } = process.env;
  if (!directory || !BASE || !HEAD_SHA)
    throw new Error(
      'usage: BASE=<sha> HEAD_SHA=<sha> patch-coverage.mjs <reports directory>',
    );
  const coverage = new Map();
  const kinds = new Set();
  for (const report of readdirSync(directory)) {
    const kind =
      /^coverage-typescript-(wow-[\w-]+)$/.exec(report)?.[1] ?? 'jvm';
    for (const name of readdirSync(join(directory, report))) {
      const text = readFileSync(join(directory, report, name), 'utf8');
      if (name.endsWith('.xml')) addJacoco(coverage, text);
      else if (name.endsWith('.json'))
        addIstanbul(coverage, JSON.parse(text), process.cwd());
      else continue;
      kinds.add(kind);
    }
  }
  const diff = execFileSync(
    'git',
    ['diff', '-U0', '--no-renames', '--no-color', BASE, HEAD_SHA],
    { encoding: 'utf8', maxBuffer: 256 * 1024 * 1024 },
  );
  const verdict = patchVerdict({
    changed: changedLines(diff),
    coverage,
    kinds,
    ignored: ignorePatterns(readFileSync('codecov.yml', 'utf8')),
  });
  const text = summary(verdict);
  console.log(text);
  for (const path of verdict.noData)
    console.log(
      `::warning file=${path}::no coverage report instruments this changed file`,
    );
  if (GITHUB_STEP_SUMMARY) appendFileSync(GITHUB_STEP_SUMMARY, text);
  const uncovered = verdict.uncovered.slice(0, 20).join(', ');
  const more =
    verdict.uncovered.length > 20
      ? ` and ${verdict.uncovered.length - 20} more`
      : '';
  appendFileSync(
    GITHUB_OUTPUT,
    `patch=${verdict.state}\npatch-message=${verdict.message}${uncovered ? `; uncovered: ${uncovered}${more}` : ''}\n`,
  );
}

/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import { execFileSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';
import { isBreaking } from './release-admission.mjs';

// Labels a pull request `breaking-change` when its title is a breaking
// Conventional Commit (`type(scope)!: …`), or its description says it breaks
// something: a `BREAKING CHANGE:` footer line, the template's breaking box
// ticked, or a `## Breaking` / `## Breaking changes` section that says more
// than "None." (.github/PULL_REQUEST_TEMPLATE). A "Behaviour changes" section
// does not label: in the release notes it holds the user-visible changes that
// are not breaking (typescript/RELEASING.md「发布说明」). The squash commit
// takes the title, so the title rule is the one release admission applies to
// the commit; admission also reads this label (release-admission.mjs), which
// is how a break the title does not mark still keeps a patch release out and
// must be named in the x.Y.0 release notes. The label also puts the PR under
// "Breaking Changes" in GitHub's generated release notes (.github/release.yml).
//
// It also labels a pull request whose diff takes a line out of a public
// surface record: a Kotlin ABI dump (`<module>/api/<module>.api`), an API
// Extractor report (`typescript/*/test/api/*.api.md`) or a surface list
// (`typescript/*/test/surface/*.txt`). These files are one declaration per
// line, so a removed or renamed declaration is a `-` line and a changed
// signature is a `-`/`+` pair; a pure addition is `+` lines only. See
// `removedSurfaceLines` for the lines that do not count.
// Run by pr-labeler.yml:
//
//   PR_TITLE=… PR_BODY=… PR_NUMBER=… GITHUB_REPOSITORY=… GH_TOKEN=… node .github/scripts/breaking-label.mjs
//
// It only adds the label: a maintainer may add it by hand to a PR that does
// not say so, and removing a `!` later is rare enough to undo by hand too.

export const LABEL = 'breaking-change';

/** Whether a pull request title marks a breaking change. */
export function isBreakingTitle(title) {
  // Only the subject counts: a title has no body, so no BREAKING CHANGE footer.
  return isBreaking(title.split('\n')[0].trim());
}

// A level-2 or level-3 heading that is exactly "Breaking" or "Breaking
// changes", optionally qualified in parentheses ("### Breaking (SPI)") or
// followed by a colon. "Breaking down the work" is not one.
const BREAKING_HEADING =
  /^#{2,3}[ \t]+Breaking(?:[ \t]+changes?)?(?:[ \t]*\([^)\n]*\))?[ \t]*:?[ \t]*#*[ \t]*$/i;
const HEADING = /^(#{1,6})[ \t]/;
// A section body that says there is nothing: "None.", "No", "N/A", "_None_",
// "No breaking changes."
const NOTHING =
  /^[*_]*(?:none|no|n\/?a)(?:[ \t]+breaking[ \t]+changes?)?[*_]*\.?[*_]*$/i;

/**
 * Whether a pull request description declares a breaking change: a
 * `BREAKING CHANGE:` line, the template's breaking box ticked, or a
 * `## Breaking` / `## Breaking changes` section (level 2 or 3) whose body is
 * not empty and not None/No/N/A. Text inside code fences and HTML comments
 * does not count.
 */
export function isBreakingBody(body) {
  const prose = (body ?? '')
    .replace(/\r\n?/g, '\n')
    .replace(/^```[\s\S]*?^```/gm, '')
    .replace(/<!--[\s\S]*?-->/g, '');
  if (
    /^BREAKING[ -]CHANGE:/m.test(prose) ||
    /^\s*[-*]\s+\[[xX]\]\s+\*\*Breaking\*\*/m.test(prose)
  )
    return true;
  const lines = prose.split('\n');
  return lines.some((line, index) => {
    if (!BREAKING_HEADING.test(line.trim())) return false;
    // The section runs to the next heading of the same or a higher level;
    // its subsections ("### SPI" under "## Breaking") are part of it.
    const level = line.trim().match(HEADING)[1].length;
    const end = lines.findIndex((next, at) => {
      const heading = next.trim().match(HEADING);
      return at > index && heading !== null && heading[1].length <= level;
    });
    const section = lines
      .slice(index + 1, end < 0 ? undefined : end)
      .join(' ')
      .trim();
    return section !== '' && !NOTHING.test(section);
  });
}

// Public surface records, one declaration per line.
const SURFACE_FILES = [
  /(?:^|\/)api\/[^/]+\.api$/,
  /^typescript\/[^/]+\/test\/api\/[^/]+\.api\.md$/,
  /^typescript\/[^/]+\/test\/surface\/[^/]+\.txt$/,
];

/** Whether a repository path is a public surface record. */
export function isSurfaceFile(path) {
  return SURFACE_FILES.some(pattern => pattern.test(path ?? ''));
}

const normalize = line => line.replace(/\s+/g, ' ').trim();

/**
 * The removed lines of one changed file (an entry of the REST API's
 * "list pull request files") that take something out of the public surface.
 * A file is judged by its own diff, so a renamed or moved record with the
 * same content removes nothing, and a deleted one removes every line. Lines
 * that do not count: blank lines; the `#` header of a surface list (it
 * carries the name count, which an addition changes); the `//` comments of an
 * API report (release tags and `(undocumented)` markers, which change when
 * TSDoc is added; a removed declaration still shows its own line); and a
 * removed line
 * that comes back as an added line of the same file once whitespace is
 * collapsed (re-indented, or moved within the file). Anything else,
 * including half of a changed signature's `-`/`+` pair, counts. When GitHub
 * leaves out the patch (a very large diff), a file with any deletion counts
 * as one line, since nothing else can be read.
 */
export function removedSurfaceLines(file) {
  if (!isSurfaceFile(file.filename) && !isSurfaceFile(file.previous_filename))
    return [];
  if (typeof file.patch !== 'string')
    return file.status === 'removed' || file.deletions > 0
      ? [`(${file.deletions ?? 'all'} deleted lines; no patch to read)`]
      : [];
  const removed = [];
  const added = new Map();
  for (const line of file.patch.split('\n')) {
    if (line.startsWith('+')) {
      const key = normalize(line.slice(1));
      added.set(key, (added.get(key) ?? 0) + 1);
    } else if (line.startsWith('-')) removed.push(line.slice(1));
  }
  const comment = /\.txt$/.test(file.filename)
    ? '#'
    : /\.api\.md$/.test(file.filename)
      ? '//'
      : null;
  return removed.filter(line => {
    const key = normalize(line);
    if (key === '' || (comment !== null && key.startsWith(comment)))
      return false;
    const count = added.get(key) ?? 0;
    if (count === 0) return true;
    added.set(key, count - 1);
    return false;
  });
}

/** The surface records a pull request's files take lines out of. */
export function breakingSurfaceFiles(files) {
  return files
    .map(file => ({ file: file.filename, lines: removedSurfaceLines(file) }))
    .filter(({ lines }) => lines.length > 0);
}

function pullRequestFiles(repo, number) {
  // At most 3000 files, 100 per page (the endpoint's limits).
  const files = [];
  for (let page = 1; page <= 30; page++) {
    const batch = JSON.parse(
      execFileSync(
        'gh',
        [
          'api',
          `repos/${repo}/pulls/${number}/files?per_page=100&page=${page}`,
        ],
        { encoding: 'utf8', maxBuffer: 256 * 1024 * 1024 },
      ),
    );
    files.push(...batch);
    if (batch.length < 100) break;
  }
  return files;
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(process.argv[1]).href
) {
  const {
    PR_TITLE: title = '',
    PR_BODY: body = '',
    PR_NUMBER: number,
    GITHUB_REPOSITORY: repo,
  } = process.env;
  if (!/^\d+$/.test(number ?? '') || !/^[\w.-]+\/[\w.-]+$/.test(repo ?? ''))
    throw new Error('PR_NUMBER and GITHUB_REPOSITORY are required');
  let breaking = isBreakingTitle(title) || isBreakingBody(body);
  if (!breaking) {
    const removals = breakingSurfaceFiles(pullRequestFiles(repo, number));
    for (const { file, lines } of removals)
      console.log(
        `${file}: ${lines.length} removed line(s), first: ${lines[0]}`,
      );
    breaking = removals.length > 0;
  }
  if (!breaking) {
    console.log(
      `#${number}: neither title, description nor a surface record is breaking; no label`,
    );
  } else {
    execFileSync(
      'gh',
      [
        'api',
        '--method',
        'POST',
        `repos/${repo}/issues/${number}/labels`,
        '-f',
        `labels[]=${LABEL}`,
      ],
      { stdio: ['ignore', 'ignore', 'inherit'] },
    );
    console.log(`#${number}: labelled ${LABEL}`);
  }
}

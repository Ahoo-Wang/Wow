/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import { execFileSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';

// Labels a pull request by the type of its Conventional Commit title, which
// the squash commit keeps, so the label always agrees with the commit. The
// labels are the categories of GitHub's generated release notes
// (.github/release.yml). A `deps` scope is a dependency update whatever its
// type. A title that is not a Conventional Commit, such as Renovate's
// `fix(deps) Update …` without a colon, gets no type label here; the branch
// rules in .github/labeler.yml cover Renovate and Dependabot.
//
// Run by pr-labeler.yml:
//
//   PR_TITLE=… PR_NUMBER=… GITHUB_REPOSITORY=… GH_TOKEN=… node .github/scripts/title-label.mjs
//
// It only adds the label, like breaking-label.mjs: a retitled pull request
// keeps the old one until a maintainer removes it.

export const TYPE_LABELS = Object.freeze({
  feat: 'enhancement',
  fix: 'bug',
  perf: 'performance',
  docs: 'documentation',
  refactor: 'maintenance',
  test: 'maintenance',
  chore: 'maintenance',
  build: 'maintenance',
  ci: 'maintenance',
  style: 'maintenance',
  revert: 'maintenance',
});

const DEPENDENCIES = 'dependencies';
const CONVENTIONAL = /^([a-z]+)(?:\(([^)]*)\))?!?:[ \t]+\S/;

/** The release-notes label for a pull request title, or `null` when the title names no known type. */
export function labelForTitle(title) {
  const match = CONVENTIONAL.exec((title ?? '').split('\n')[0].trim());
  if (match === null) return null;
  const [, type, scope] = match;
  if (
    scope !== undefined &&
    scope.split(',').some(part => part.trim() === 'deps')
  )
    return DEPENDENCIES;
  return Object.hasOwn(TYPE_LABELS, type) ? TYPE_LABELS[type] : null;
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(process.argv[1]).href
) {
  const {
    PR_TITLE: title = '',
    PR_NUMBER: number,
    GITHUB_REPOSITORY: repo,
  } = process.env;
  if (!/^\d+$/.test(number ?? '') || !/^[\w.-]+\/[\w.-]+$/.test(repo ?? ''))
    throw new Error('PR_NUMBER and GITHUB_REPOSITORY are required');
  const label = labelForTitle(title);
  if (label === null) {
    console.log(
      `#${number}: the title names no Conventional Commit type; no label`,
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
        `labels[]=${label}`,
      ],
      { stdio: ['ignore', 'ignore', 'inherit'] },
    );
    console.log(`#${number}: labelled ${label}`);
  }
}

/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import { execFileSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';
import { isBreaking } from './release-admission.mjs';

// Labels a pull request `breaking-change` when its title is a breaking
// Conventional Commit (`type(scope)!: …`). The squash commit takes the title,
// so this is the same rule release admission applies to the commit, and the
// label puts the PR under "Breaking Changes" in GitHub's generated release
// notes (.github/release.yml). Run by pr-labeler.yml:
//
//   PR_TITLE=… PR_NUMBER=… GITHUB_REPOSITORY=… GH_TOKEN=… node .github/scripts/breaking-label.mjs
//
// It only adds the label: a maintainer may add it by hand to a PR whose
// title does not say so, and removing a `!` later is rare enough to undo by
// hand too.

export const LABEL = 'breaking-change';

/** Whether a pull request title marks a breaking change. */
export function isBreakingTitle(title) {
  // Only the subject counts: a title has no body, so no BREAKING CHANGE footer.
  return isBreaking(title.split('\n')[0].trim());
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
  if (!isBreakingTitle(title)) {
    console.log(`#${number}: not a breaking title; no label`);
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

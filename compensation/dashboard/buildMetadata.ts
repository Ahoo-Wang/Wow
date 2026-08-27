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

import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const FULL_COMMIT_SHA = /^[0-9a-f]{40}$/i;

export const UNKNOWN_COMMIT_SHA = "unknown";

interface BuildEnvironment {
  GITHUB_SHA?: string;
  VITE_APP_COMMIT_SHA?: string;
}

interface ResolveCommitShaOptions {
  environment?: BuildEnvironment;
  readGitCommit?: () => string;
}

function normalizeCommitSha(value?: string): string | undefined {
  const normalized = value?.trim();
  return normalized && FULL_COMMIT_SHA.test(normalized)
    ? normalized.toLowerCase()
    : undefined;
}

function readCommitFromGit(): string {
  return execFileSync("git", ["rev-parse", "HEAD"], {
    cwd: fileURLToPath(new URL(".", import.meta.url)),
    encoding: "utf8",
  });
}

export function resolveCommitSha({
  environment = process.env,
  readGitCommit = readCommitFromGit,
}: ResolveCommitShaOptions = {}): string {
  const environmentSha =
    normalizeCommitSha(environment.GITHUB_SHA) ??
    normalizeCommitSha(environment.VITE_APP_COMMIT_SHA);
  if (environmentSha) {
    return environmentSha;
  }

  try {
    return normalizeCommitSha(readGitCommit()) ?? UNKNOWN_COMMIT_SHA;
  } catch {
    return UNKNOWN_COMMIT_SHA;
  }
}

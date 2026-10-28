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

import { CircleAlertIcon } from "lucide-react";
import {
  CommandRejectedError,
  WowProblemError,
  type CommandResult,
  type Violation,
} from "@ahoo-wang/wow-client";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";

/** What went wrong, as the UI shows it. */
export interface ShownProblem {
  readonly title: string;
  readonly status?: number;
  readonly code?: string;
  readonly detail?: string;
  readonly violations: readonly Violation[];
}

/**
 * Reads an error as the problem it carries: an RFC 9457 problem
 * (`WowProblemError`, with its `code` and violations), a command's result
 * that was rejected after admission, or anything else.
 */
export function problemOf(error: unknown): ShownProblem {
  // A rejection shows as the problem it came in, else as its result.
  if (error instanceof CommandRejectedError) {
    return error.cause instanceof WowProblemError
      ? problemOf(error.cause)
      : problemOfResult(error.result as CommandResult);
  }
  if (error instanceof WowProblemError) {
    return {
      title: error.problem.title,
      status: error.status,
      code: error.code,
      detail: error.problem.detail ?? error.result?.error?.message,
      violations: error.violations,
    };
  }
  if (isRejectedResult(error)) return problemOfResult(error);
  return {
    title: "Request failed",
    detail: error instanceof Error ? error.message : String(error),
    violations: [],
  };
}

/** A command result that did not succeed, as a problem. */
export function problemOfResult(result: CommandResult): ShownProblem {
  return {
    title: result.status === "rejected" ? "Rejected" : "Failed",
    code: result.error?.code,
    detail: result.error?.message,
    violations: result.error?.violations ?? [],
  };
}

function isRejectedResult(value: unknown): value is CommandResult {
  return (
    typeof value === "object" &&
    value !== null &&
    "status" in value &&
    "ref" in value &&
    (value as CommandResult).status !== "succeeded"
  );
}

/** Shows an error: its title and status, its code, its detail and each violation. */
export function ProblemAlert({ error }: { error: unknown }) {
  const problem = problemOf(error);
  return (
    <Alert variant="destructive" role="alert">
      <CircleAlertIcon />
      <AlertTitle>
        {problem.title}
        {problem.status ? ` (${problem.status})` : ""}
      </AlertTitle>
      <AlertDescription>
        {problem.code && (
          <code data-testid="problem-code">{problem.code}</code>
        )}
        {problem.detail && <p>{problem.detail}</p>}
        {problem.violations.length > 0 && (
          <ul>
            {problem.violations.map((violation) => (
              <li key={`${violation.path}:${violation.code}`}>
                <code>{violation.path}</code>: {violation.message}
              </li>
            ))}
          </ul>
        )}
      </AlertDescription>
    </Alert>
  );
}

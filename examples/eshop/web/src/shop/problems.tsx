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

import { useEffect, useRef } from "react";
import { CircleAlertIcon, RefreshCwIcon } from "lucide-react";
import {
  CommandRejectedError,
  CommandResultStatus,
  WowProblemError,
  type CommandResult,
  type Violation,
} from "@ahoo-wang/wow-client";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";

/** What went wrong, as the UI shows it. */
export interface ShownProblem {
  readonly title: string;
  readonly status?: number;
  readonly code?: string;
  readonly detail?: string;
  readonly violations: readonly Violation[];
}

/** The statuses of a service that did not answer, through the dev server's proxy or a gateway. */
const UNAVAILABLE = [502, 503, 504];

/**
 * Reads an error as the problem it carries: an RFC 9457 problem
 * (`WowProblemError`, with its `code` and violations), a command's result
 * that was rejected after admission, or anything else. A gateway's answer for
 * a service that did not answer (502, 503 or 504 without a Wow code) says so,
 * naming `service` when the caller knows which one it asked.
 */
export function problemOf(error: unknown, service?: string): ShownProblem {
  // A rejection shows as the problem it came in, else as its result.
  if (error instanceof CommandRejectedError) {
    return error.cause instanceof WowProblemError
      ? problemOf(error.cause)
      : problemOfResult(error.result as CommandResult);
  }
  if (
    error instanceof WowProblemError &&
    !error.code &&
    UNAVAILABLE.includes(error.status)
  ) {
    return {
      title: "Service unavailable",
      status: error.status,
      detail: `The ${service ?? "shop's"} service did not answer (${error.status} ${error.problem.title}). Try again in a moment.`,
      violations: [],
    };
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
    title: titleOf(result.status),
    code: result.error?.code,
    detail: result.error?.message,
    violations: result.error?.violations ?? [],
  };
}

/**
 * A result's status as a title. A status this client does not know (a server
 * that spells it otherwise, or a newer one) is shown as itself: the client
 * cannot read it as success, but nor does it know the command failed.
 */
function titleOf(status: CommandResult["status"]): string {
  switch (status) {
    case CommandResultStatus.SUCCEEDED:
      return "Succeeded";
    case CommandResultStatus.REJECTED:
      return "Rejected";
    case CommandResultStatus.FAILED:
      return "Failed";
    default:
      return `Unknown status "${status}"`;
  }
}

function isRejectedResult(value: unknown): value is CommandResult {
  return (
    typeof value === "object" &&
    value !== null &&
    "status" in value &&
    "ref" in value &&
    (value as CommandResult).status !== CommandResultStatus.SUCCEEDED
  );
}

/**
 * Shows an error: its title and status, its code, its detail and each
 * violation, and a button that tries again when there is `onRetry`. It
 * scrolls itself into view when it appears, so an error answered to a control
 * far down the page is seen where it is shown.
 */
export function ProblemAlert({
  error,
  service,
  onRetry,
}: {
  error: unknown;
  /** The service that was asked, for an error that says it did not answer. */
  service?: string;
  onRetry?: () => void;
}) {
  const problem = problemOf(error, service);
  const shown = useRef<HTMLDivElement>(null);
  useEffect(() => {
    shown.current?.scrollIntoView?.({ block: "nearest" });
  }, [error]);
  return (
    <Alert variant="destructive" role="alert" ref={shown}>
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
        {onRetry && (
          <Button variant="outline" size="sm" className="mt-2" onClick={onRetry}>
            <RefreshCwIcon data-icon="inline-start" />
            Try again
          </Button>
        )}
      </AlertDescription>
    </Alert>
  );
}

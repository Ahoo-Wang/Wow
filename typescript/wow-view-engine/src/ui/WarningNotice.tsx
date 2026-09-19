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

import { cn } from 'cn';
import { TriangleAlertIcon } from 'lucide-react';
import type { Issue } from '../model/index.js';
import { Alert, AlertDescription, AlertTitle } from './components/alert.js';
import { useViewMessages } from './MessagesProvider.js';

export interface WarningNoticeProps {
  /** Every finding the runtime reports; only the warnings are shown. */
  issues: readonly Issue[];
  className?: string;
}

/**
 * The findings that do not block, said once.
 *
 * An error stops a view from running and is the workbench's loudest alert.
 * A warning stops nothing — the query runs, save is allowed — but the view is
 * not quite what its author saved: a filter opened in the other editor, a
 * panel whose view is gone. Reporting it without blocking is the whole of
 * what `warning` means, and a finding nobody can see was never reported.
 *
 * It is a status, not an alert, so a screen reader mentions it without
 * interrupting whatever the user was doing.
 */
export function WarningNotice({ issues, className }: WarningNoticeProps) {
  const messages = useViewMessages();
  const warnings = distinct(
    issues.filter(found => found.severity === 'warning'),
  );
  if (warnings.length === 0) return null;
  return (
    <Alert
      role="status"
      data-slot="view-warnings"
      // The theme's `warning` token, where the destructive variant has its own.
      className={cn(
        'border-warning text-warning *:data-[slot=alert-description]:text-warning/90',
        className,
      )}
    >
      <TriangleAlertIcon />
      <AlertTitle>{messages.label('label.view.warnings')}</AlertTitle>
      <AlertDescription>{messages.issues(warnings)}</AlertDescription>
    </Alert>
  );
}

/**
 * One of each sentence. A dashboard validates a global condition once as
 * its own and once more per panel it maps onto, and two leaves can trip the
 * same rule; the code and the params are the sentence, and the same sentence
 * twice tells nobody anything more.
 */
function distinct(warnings: readonly Issue[]): Issue[] {
  const kept: Issue[] = [];
  for (const found of warnings)
    if (!kept.some(said => sameWording(said, found))) kept.push(found);
  return kept;
}

function sameWording(a: Issue, b: Issue): boolean {
  if (a.code !== b.code) return false;
  const left = a.params ?? {};
  const right = b.params ?? {};
  const keys = Object.keys(left);
  return (
    keys.length === Object.keys(right).length &&
    keys.every(key => left[key] === right[key])
  );
}

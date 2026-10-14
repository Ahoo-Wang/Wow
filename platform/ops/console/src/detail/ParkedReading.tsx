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

import { useState, type ReactNode } from "react";
import type { RecordRow } from "@ahoo-wang/wow-view-engine";
import { Check, Copy } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { useI18n } from "@/i18n.tsx";
import { PARKED_WORDS } from "@/views/parked.ts";
import {
  entryOf,
  failureOf,
  formatTime,
  optionWord,
  type ParkedField,
} from "./parkedEntry.ts";

const STATUS_VARIANT: Record<string, "destructive" | "secondary" | "outline"> =
  {
    EXHAUSTED: "destructive",
    UNRECOVERABLE: "destructive",
    SCHEDULED: "secondary",
    RESOLVED: "outline",
  };

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="flex flex-col gap-2">
      <h3 className="text-sm font-semibold">{title}</h3>
      {children}
    </section>
  );
}

function Facts({ rows }: { rows: [string, ReactNode][] }) {
  const { t } = useI18n();
  return (
    <dl className="grid grid-cols-[minmax(8rem,auto)_1fr] gap-x-4 gap-y-1 text-sm">
      {rows.map(([label, value]) => (
        <div key={label} className="contents">
          <dt className="text-muted-foreground">{label}</dt>
          <dd className="min-w-0 break-words">
            {value === undefined || value === null || value === ""
              ? t("none")
              : value}
          </dd>
        </div>
      ))}
    </dl>
  );
}

/** Copies `value` to the clipboard, and says so for a moment. */
function CopyButton({ value }: { value: string }) {
  const { t } = useI18n();
  const [copied, setCopied] = useState(false);
  return (
    <Button
      type="button"
      variant="ghost"
      size="icon-sm"
      aria-label={t("copyCause")}
      title={t("copyCause")}
      onClick={() => {
        void navigator.clipboard?.writeText(value).then(() => {
          setCopied(true);
          setTimeout(() => setCopied(false), 1500);
        });
      }}
    >
      {copied ? <Check /> : <Copy />}
    </Button>
  );
}

/**
 * Why the entry is parked, the 9.x dashboard's error view: the failure's
 * type and its message, as the owning service recorded them. The service
 * records the exception's class and message (`<class>: <message>`), not
 * its stack trace, so there is none to show.
 */
function Failure({ reason }: { reason: string | undefined }) {
  const { locale, t } = useI18n();
  if (!reason)
    return <p className="text-sm text-muted-foreground">{t("none")}</p>;
  const { type, message } = failureOf(reason);
  return (
    <div className="flex flex-col gap-2" data-testid="parked-failure">
      <div className="flex items-center gap-2">
        <span className="text-xs text-muted-foreground">
          {PARKED_WORDS[locale].errorType}
        </span>
        <code className="min-w-0 flex-1 truncate font-mono text-sm font-semibold">
          {type}
        </code>
        <CopyButton value={reason} />
      </div>
      {message ? (
        <pre className="max-h-64 overflow-auto rounded-md bg-muted p-3 font-mono text-xs whitespace-pre-wrap">
          {message}
        </pre>
      ) : null}
    </div>
  );
}

/**
 * A parked entry read the way an operator asks of it (the 9.x dashboard's
 * reading of a failed execution): why it is parked, where — the owning
 * service, its subscription and the aggregate it parks — how far its
 * retries went and by which spec, what an operator last asked of it, and
 * how it was resolved, then its history (`history`). The actions are the
 * engine's, in the same panel.
 */
export function ParkedReading({
  row,
  history,
}: {
  row: RecordRow;
  history?: ReactNode;
}) {
  const { locale, t } = useI18n();
  const words = PARKED_WORDS[locale];
  const entry = entryOf(row);
  const option = (kind: string, value: string | null | undefined) =>
    value ? optionWord(locale, kind, value) : undefined;
  const time = (value: number | null | undefined) => formatTime(value, locale);
  const label = (key: ParkedField) => words[key];
  const spec = entry.retrySpec;
  const aggregate = entry.aggregate;

  return (
    <div className="flex flex-col gap-5" data-testid="parked-reading">
      <div className="flex flex-wrap items-center gap-2">
        {entry.status ? (
          <Badge variant={STATUS_VARIANT[entry.status] ?? "secondary"}>
            {option("status", entry.status)}
          </Badge>
        ) : null}
        {entry.recoverable ? (
          <Badge variant="outline">
            {option("recoverable", entry.recoverable)}
          </Badge>
        ) : null}
      </div>
      <Section title={t("cause")}>
        <Failure reason={entry.reason} />
      </Section>
      <Section title={t("owner")}>
        <Facts
          rows={[
            [label("service"), entry.service],
            [label("context"), entry.context],
            [label("subscription"), entry.subscription],
            [
              label("aggregateName"),
              aggregate ? `${aggregate.context}.${aggregate.name}` : undefined,
            ],
            [label("aggregateId"), aggregate?.id],
            [label("aggregateTenant"), aggregate?.tenantId],
            [label("fromVersion"), entry.fromVersion],
            [t("position"), entry.fromPosition],
          ]}
        />
      </Section>
      <Section title={t("attempts")}>
        <Facts
          rows={[
            [
              label("retries"),
              entry.retries === undefined
                ? undefined
                : t("retriesOf", {
                    retries: entry.retries,
                    max: spec?.maxRetries ?? t("none"),
                  }),
            ],
            [label("retryAt"), time(entry.retryAt)],
            [label("nextRetryAt"), time(entry.nextRetryAt)],
          ]}
        />
      </Section>
      <Section title={t("retrySpec")}>
        <Facts
          rows={[
            [label("maxRetries"), spec?.maxRetries],
            [label("minBackoff"), spec?.minBackoff],
            [label("executionTimeout"), spec?.executionTimeout],
            ...(spec?.applied ? [[t("applied"), "✓"] as [string, string]] : []),
          ]}
        />
      </Section>
      {entry.requested ? (
        <Section title={t("request")}>
          <Facts
            rows={[[label("requested"), option("requested", entry.requested)]]}
          />
          <p className="text-sm text-muted-foreground">{t("pendingRequest")}</p>
        </Section>
      ) : null}
      {entry.outcome ? (
        <Section title={t("resolution")}>
          <Facts
            rows={[
              [label("outcome"), option("outcome", entry.outcome)],
              [label("resolvedAt"), time(entry.resolvedAt)],
            ]}
          />
        </Section>
      ) : null}
      <Section title={t("times")}>
        <Facts
          rows={[
            [label("failedAt"), time(entry.failedAt)],
            [label("recordedAt"), time(entry.recordedAt)],
          ]}
        />
      </Section>
      {history}
    </div>
  );
}

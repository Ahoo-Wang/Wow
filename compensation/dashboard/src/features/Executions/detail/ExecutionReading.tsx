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

import type { RecordRow, ViewEngine } from "@ahoo-wang/wow-view-engine";
import {
  useEngine,
  type EmbeddedViewProps,
} from "@ahoo-wang/wow-view-engine/ui";
import { ChevronDown, TriangleAlert } from "lucide-react";
import { useId, useMemo, useState, type ReactNode } from "react";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from "@/components/ui/collapsible";
import { Progress } from "@/components/ui/progress";
import { Separator } from "@/components/ui/separator";
import { cn } from "@/lib/utils";
import { Skeleton } from "@/components/ui/skeleton";
import type { ChangeFunction } from "@/generated";
import { useI18n, type Message, type Translate } from "@/i18n.tsx";
import { EXECUTION_HISTORY_SOURCE } from "@/views/executionHistory.ts";
import { engineMessages } from "@/views/messages.ts";
import { formatSeconds } from "@/utils/durations.ts";
import { formatClock, formatMoment, formatRelative } from "@/utils/time.ts";
import type { ExecutionCommands } from "../executionCommands.ts";
import { repeatedFailures, type Moment } from "./attempts.ts";
import { ExecutionHistory } from "./ExecutionHistory.tsx";
import {
  EditableCard,
  FunctionForm,
  RetrySpecForm,
} from "./ExecutionForms.tsx";
import { CopyValue } from "./CopyValue.tsx";
import { StackTrace } from "./StackTrace.tsx";
import { STORY_LIMIT, useMoments, type MomentsRead } from "./useMoments.ts";
import {
  recoverabilityLabel,
  stateOf,
  type ExecutionState,
} from "./executionState.ts";
import { useNow } from "@/utils/useNow.ts";

const STATUS: Record<NonNullable<ExecutionState["status"]>, Message> = {
  FAILED: "Failed",
  PREPARED: "Prepared",
  SUCCEEDED: "Succeeded",
};

export interface ExecutionReadingProps {
  row: RecordRow;
  /** Whether `row` is the whole record: the forms wait for it. */
  complete: boolean;
  refresh(): void;
  commands: ExecutionCommands;
}

/**
 * A failed execution read the way an operator opens one (D60): what state
 * it is in and when it runs next; why it failed, and whether trying again
 * as it is can help — the same error attempt after attempt says it cannot;
 * what happened to it, attempt by attempt; then what it ran and on which
 * event, each changeable where it is read; the ids last.
 *
 * The story is read off the host's one engine (`useEngine`): the event
 * streams' source, and the embedded history over them.
 */
export function ExecutionReading({
  row,
  complete,
  refresh,
  commands,
}: ExecutionReadingProps) {
  const engine = useEngine();
  const { locale } = useI18n();
  const messages = engineMessages(locale);
  const history = useMemo(
    () => engine.resolveSource(EXECUTION_HISTORY_SOURCE),
    [engine],
  );
  const id = String(row.key);
  const state = stateOf(row);
  const revision = String(row.data.eventTime ?? "");
  const story = useMoments(history, id, revision);
  const errorCode = state.error?.errorCode ?? "";
  const repeated =
    story.status === "read" ? repeatedFailures(story.moments, errorCode) : 0;
  return (
    <div className="flex flex-col gap-6">
      <Standing state={state} firstFailedAt={Number(row.data.firstEventTime)} />
      <WhyItFailed state={state} repeated={repeated} />
      <Story
        read={story}
        engine={engine}
        id={id}
        revision={revision}
        locale={locale}
        messages={messages}
      />
      <div className="grid grid-cols-[repeat(auto-fit,minmax(16rem,1fr))] gap-4">
        <HandlerCard
          id={id}
          state={state}
          complete={complete}
          commands={commands}
          refresh={refresh}
        />
        <EventCard state={state} />
      </div>
      <RetrySpecCard
        id={id}
        state={state}
        complete={complete}
        commands={commands}
        refresh={refresh}
      />
      <Identities row={row} state={state} />
    </div>
  );
}

/** A labelled figure of the standing strip. */
function Figure({
  label,
  value,
  note,
  children,
}: {
  label: string;
  value: ReactNode;
  note?: ReactNode;
  children?: ReactNode;
}) {
  return (
    <div className="flex min-w-0 flex-col gap-1 bg-card px-3 py-2.5">
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="flex flex-col gap-1.5">
        <span className="text-base font-semibold tabular-nums">{value}</span>
        {note && <span className="text-xs text-muted-foreground">{note}</span>}
        {children}
      </dd>
    </div>
  );
}

/**
 * Where the execution stands: its status and recoverability, then four
 * figures — how many of its retries are spent, when it runs next, since
 * when it has been failing, how long one attempt may take.
 */
function Standing({
  state,
  firstFailedAt,
}: {
  state: ExecutionState;
  firstFailedAt: number;
}) {
  const { locale, t } = useI18n();
  const now = useNow();
  const retries = state.retryState?.retries ?? 0;
  const max = state.retrySpec?.maxRetries;
  const next = state.retryState?.nextRetryAt;
  const unrecoverable = state.recoverable === "UNRECOVERABLE";
  const exhausted = state.isBelowRetryThreshold === false;
  const nextValue =
    state.status === "SUCCEEDED" || unrecoverable || exhausted
      ? "—"
      : next
        ? formatClock(next, locale)
        : "—";
  const nextNote =
    state.status === "SUCCEEDED"
      ? t("This execution has already succeeded.")
      : unrecoverable
        ? t("Not retried: marked unrecoverable")
        : exhausted
          ? t("Retry limit reached")
          : next
            ? next <= now
              ? t("Due for retry")
              : formatRelative(next, now, locale)
            : undefined;
  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-2">
        {state.status && (
          <Badge
            variant={state.status === "SUCCEEDED" ? "secondary" : "destructive"}
          >
            {t(STATUS[state.status])}
          </Badge>
        )}
        <Badge variant="secondary">
          {t("Recoverability: {value}", {
            value: t(recoverabilityLabel(state.recoverable)),
          })}
        </Badge>
      </div>
      <dl className="grid grid-cols-[repeat(auto-fit,minmax(8.5rem,1fr))] gap-px overflow-hidden rounded-lg bg-border ring-1 ring-border">
        <Figure
          label={t("Retries")}
          value={max === undefined ? retries : `${retries} / ${max}`}
        >
          {max !== undefined && max > 0 && (
            <Progress
              aria-label={t("Retries")}
              value={Math.min(retries, max)}
              max={max}
            />
          )}
        </Figure>
        <Figure
          label={t("Next automatic retry")}
          value={nextValue}
          note={nextNote}
        />
        <Figure
          label={t("First failed")}
          value={
            Number.isFinite(firstFailedAt)
              ? formatClock(firstFailedAt, locale)
              : "—"
          }
          note={
            Number.isFinite(firstFailedAt)
              ? formatRelative(firstFailedAt, now, locale)
              : undefined
          }
        />
        <Figure
          label={t("Timeout per attempt")}
          value={
            state.retrySpec
              ? formatSeconds(state.retrySpec.executionTimeout, locale)
              : "—"
          }
          note={
            state.retrySpec &&
            t("Backoff at least {duration}", {
              duration: formatSeconds(state.retrySpec.minBackoff, locale),
            })
          }
        />
      </dl>
    </div>
  );
}

/** A section of the reading, headed so a screen reader moves by it. */
function Part({
  title,
  aside,
  children,
}: {
  title: string;
  aside?: ReactNode;
  children: ReactNode;
}) {
  const heading = useId();
  return (
    <section aria-labelledby={heading} className="flex flex-col gap-3">
      <div className="flex items-baseline justify-between gap-2">
        <h3 id={heading} className="text-sm font-semibold">
          {title}
        </h3>
        {aside}
      </div>
      {children}
    </section>
  );
}

/**
 * Why it failed: the error's code the way a log names it, its message as a
 * sentence, whether retrying can help, and the stack for whoever reads on.
 */
function WhyItFailed({
  state,
  repeated,
}: {
  state: ExecutionState;
  repeated: number;
}) {
  const { t } = useI18n();
  const code = state.error?.errorCode ?? "";
  const message = state.error?.errorMsg ?? "";
  return (
    <Part title={t("Why it failed")}>
      <div className="flex flex-col gap-1">
        {code !== "" && (
          <code className="text-sm font-medium text-destructive">{code}</code>
        )}
        <p className="text-base break-words whitespace-pre-wrap">
          {message !== "" ? message : t("No error message")}
        </p>
      </div>
      {/* Two in a row is already a pattern: a passing fault rarely repeats
          its code exactly. */}
      {repeated >= 2 && state.status !== "SUCCEEDED" && (
        <Alert>
          <TriangleAlert />
          <AlertTitle>
            {t("The last {count} attempts failed with this same error", {
              count: repeated,
            })}
          </AlertTitle>
          <AlertDescription>
            {t(
              "It looks like a fault in the code or its data rather than a passing one, so retrying as it is will likely fail again. Fix the handler and change the function, or mark it unrecoverable.",
            )}
          </AlertDescription>
        </Alert>
      )}
      <section aria-label={t("Stack trace")}>
        <StackTrace value={state.error?.stackTrace ?? ""} />
      </section>
    </Part>
  );
}

function momentLabel(moment: Moment, t: Translate): string {
  switch (moment.kind) {
    case "created":
      return t("First failed");
    case "attempt":
      return t("Attempt {number}", { number: moment.number });
    case "functionChanged":
      return t("Function changed");
    case "retrySpecApplied":
      return t("Retry specification changed");
    case "recoverableMarked":
      return t("Marked {value}", {
        value: t(recoverabilityLabel(moment.recoverable)),
      });
  }
}

function MomentResult({ moment }: { moment: Moment }) {
  const { t } = useI18n();
  if (moment.kind === "created")
    return <code className="text-destructive">{moment.errorCode}</code>;
  if (moment.kind !== "attempt") return null;
  if (moment.outcome === "running")
    return <span className="text-muted-foreground">{t("Executing")}</span>;
  if (moment.outcome === "succeeded") return <span>{t("Succeeded")}</span>;
  return (
    <code className="text-destructive">
      {moment.errorCode === "" ? t("Failed") : moment.errorCode}
    </code>
  );
}

/**
 * What happened to it, the newest first: each attempt with what came of it
 * and how long it took, and the changes an operator made between them. The
 * event streams themselves are one press below, read whole on demand.
 */
function Story({
  read,
  engine,
  id,
  revision,
  locale,
  messages,
}: {
  read: MomentsRead;
  engine: ViewEngine;
  id: string;
  revision: string;
  locale: string;
  messages: EmbeddedViewProps["messages"];
}) {
  const { locale: language, t } = useI18n();
  const [all, setAll] = useState(false);
  return (
    <Part title={t("Attempts")}>
      {read.status === "reading" ? (
        <div aria-hidden="true" className="flex flex-col gap-2">
          <Skeleton className="h-4 w-full" />
          <Skeleton className="h-4 w-3/4" />
        </div>
      ) : read.status === "failed" ? (
        <p className="text-sm text-muted-foreground">
          {t("Could not read the attempts.")}
        </p>
      ) : (
        <ol className="flex flex-col overflow-hidden rounded-lg ring-1 ring-border">
          {read.moments.map((moment, index) => (
            <li
              key={`${moment.kind}-${moment.at}-${index}`}
              // One layout at every width: inside the engine's panel its
              // own utilities outrank a host's breakpoint variants.
              className="grid grid-cols-[5.5rem_minmax(0,1fr)_auto] items-baseline gap-x-3 border-t px-3 py-2 text-sm first:border-t-0"
            >
              <span className="font-medium">{momentLabel(moment, t)}</span>
              <span className="flex min-w-0 flex-wrap items-baseline gap-x-3">
                <span className="text-muted-foreground tabular-nums">
                  {formatMoment(moment.at, language)}
                </span>
                <span className="min-w-0 truncate text-xs">
                  <MomentResult moment={moment} />
                </span>
              </span>
              <span className="text-right text-muted-foreground tabular-nums">
                {moment.kind === "attempt" && moment.endedAt !== null
                  ? formatSeconds(
                      Math.max(
                        0,
                        Math.round((moment.endedAt - moment.at) / 1000),
                      ),
                      language,
                    )
                  : null}
              </span>
            </li>
          ))}
        </ol>
      )}
      {read.status === "read" && read.total > STORY_LIMIT && (
        <p className="text-sm text-muted-foreground">
          {t("Only the latest {count} events are read here.", {
            count: STORY_LIMIT,
          })}
        </p>
      )}
      <Collapsible open={all} onOpenChange={setAll}>
        <CollapsibleTrigger
          render={
            <Button type="button" variant="ghost" size="sm" className="-ml-2" />
          }
        >
          <ChevronDown
            data-icon="inline-start"
            className={all ? "rotate-180" : undefined}
          />
          {read.status === "read"
            ? t("All events ({count})", { count: read.total })
            : t("All events")}
        </CollapsibleTrigger>
        <CollapsibleContent>
          <section aria-label={t("Execution history")} className="pt-2">
            <ExecutionHistory
              // Read again once the execution has changed.
              key={revision}
              engine={engine}
              id={id}
              locale={locale}
              messages={messages}
            />
          </section>
        </CollapsibleContent>
      </Collapsible>
    </Part>
  );
}

/** Label and value pairs, the label a column of its own. */
function Pairs({ children }: { children: ReactNode }) {
  return (
    <dl className="grid grid-cols-[minmax(4.5rem,max-content)_minmax(0,1fr)] gap-x-3 gap-y-1.5 text-sm">
      {children}
    </dl>
  );
}

function Pair({ label, children }: { label: string; children: ReactNode }) {
  return (
    <>
      <dt className="text-muted-foreground">{label}</dt>
      <dd className="min-w-0 [overflow-wrap:anywhere]">{children ?? "—"}</dd>
    </>
  );
}

const KIND_LABELS: Record<string, Message> = {
  EVENT: "Event",
  STATE_EVENT: "State event",
};

interface ChangeProps {
  id: string;
  state: ExecutionState;
  complete: boolean;
  commands: ExecutionCommands;
  refresh(): void;
}

/** What it ran: the handler, which the next retry can be pointed elsewhere. */
function HandlerCard({ id, state, complete, commands, refresh }: ChangeProps) {
  const { t } = useI18n();
  const target = state.function;
  const values = (
    <Pairs>
      <Pair label={t("Service")}>{target?.contextName}</Pair>
      <Pair label={t("Processor")}>{target?.processorName}</Pair>
      <Pair label={t("Function name")}>{target?.name}</Pair>
      <Pair label={t("Function kind")}>
        {target && t(KIND_LABELS[target.functionKind] ?? "Event")}
      </Pair>
    </Pairs>
  );
  // The form starts from the record's values, so it waits for the whole
  // record; the page's row may not carry them.
  if (!complete || !target)
    return <ReadCard title={t("Handler")}>{values}</ReadCard>;
  return (
    <EditableCard
      title={t("Handler")}
      label={t("Change function")}
      done={t("Function updated")}
      form={(close) => (
        <FunctionForm
          key={JSON.stringify(target)}
          target={target satisfies ChangeFunction}
          change={(next) => commands.changeFunction(id, next)}
          onChanged={() => {
            close();
            refresh();
          }}
        />
      )}
    >
      {values}
    </EditableCard>
  );
}

/** A card that is read only. */
function ReadCard({ title, children }: { title: string; children: ReactNode }) {
  const heading = useId();
  return (
    <Card size="sm" role="region" aria-labelledby={heading}>
      <CardHeader>
        <CardTitle>
          <h3 id={heading}>{title}</h3>
        </CardTitle>
      </CardHeader>
      <CardContent>{children}</CardContent>
    </Card>
  );
}

/** On which event: the aggregate whose event the handler failed on. */
function EventCard({ state }: { state: ExecutionState }) {
  const { t } = useI18n();
  const event = state.eventId;
  const aggregate = event?.aggregateId;
  return (
    <ReadCard title={t("Triggering event")}>
      <Pairs>
        <Pair label={t("Service")}>{aggregate?.contextName}</Pair>
        <Pair label={t("Aggregate")}>{aggregate?.aggregateName}</Pair>
        <Pair label={t("Aggregate ID")}>
          {aggregate?.aggregateId && (
            <code className="text-xs">
              <CopyValue value={aggregate.aggregateId} />
            </code>
          )}
        </Pair>
        <Pair label={t("Event version")}>{event?.version}</Pair>
        {aggregate?.tenantId && (
          <Pair label={t("Tenant")}>{aggregate.tenantId}</Pair>
        )}
      </Pairs>
    </ReadCard>
  );
}

/** How it is retried: the limit, the backoff and the timeout, changeable. */
function RetrySpecCard({
  id,
  state,
  complete,
  commands,
  refresh,
}: ChangeProps) {
  const { locale, t } = useI18n();
  const spec = state.retrySpec;
  const summary = spec ? (
    <p className="text-sm">
      {t(
        "Up to {max} retries · backoff at least {backoff} · timeout {timeout}",
        {
          max: spec.maxRetries,
          backoff: formatSeconds(spec.minBackoff, locale),
          timeout: formatSeconds(spec.executionTimeout, locale),
        },
      )}
    </p>
  ) : (
    <p className="text-sm text-muted-foreground">—</p>
  );
  if (!complete || !spec)
    return <ReadCard title={t("Retry specification")}>{summary}</ReadCard>;
  return (
    <EditableCard
      title={t("Retry specification")}
      label={t("Apply retry specification")}
      done={t("Retry specification updated")}
      form={(close) => (
        <RetrySpecForm
          key={JSON.stringify(spec)}
          spec={spec}
          apply={(next) => commands.applyRetrySpec(id, next)}
          onApplied={() => {
            close();
            refresh();
          }}
        />
      )}
    >
      {summary}
    </EditableCard>
  );
}

/** The ids and times, for a search or a ticket: last, and quiet. */
function Identities({ row, state }: { row: RecordRow; state: ExecutionState }) {
  const { locale, t } = useI18n();
  const executed = state.executeAt;
  const updated = Number(row.data.eventTime);
  return (
    <footer className="flex flex-col gap-3">
      <Separator />
      <dl className="grid grid-cols-[repeat(auto-fit,minmax(15rem,1fr))] gap-x-6 gap-y-1 text-xs text-muted-foreground">
        <Identity label={t("Execution ID")} mono>
          <CopyValue value={String(row.key)} />
        </Identity>
        <Identity label={t("Event ID")} mono>
          {state.eventId?.id ? <CopyValue value={state.eventId.id} /> : "—"}
        </Identity>
        <Identity label={t("Last executed")}>
          {executed ? formatMoment(executed, locale) : "—"}
        </Identity>
        <Identity label={t("Last updated")}>
          {Number.isFinite(updated) ? formatMoment(updated, locale) : "—"}
        </Identity>
      </dl>
    </footer>
  );
}

/** One id or time of the footer: its label, then it. */
function Identity({
  label,
  mono = false,
  children,
}: {
  label: string;
  mono?: boolean;
  children: ReactNode;
}) {
  return (
    <div className="flex min-w-0 gap-3">
      <dt className="w-20 shrink-0">{label}</dt>
      <dd
        className={cn(
          "min-w-0 text-foreground tabular-nums [overflow-wrap:anywhere]",
          mono && "font-mono",
        )}
      >
        {children}
      </dd>
    </div>
  );
}

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

import { useEffect, useState } from "react";
import { useI18n, type Message } from "@/i18n.tsx";
import { PARKED_WORDS } from "@/views/parked.ts";
import { formatTime, optionWord } from "./parkedEntry.ts";
import {
  historyOf,
  type HistoryItem,
  type HistoryKind,
  type HistorySource,
} from "./entryHistory.ts";

const KIND_WORDS: Record<HistoryKind, Message> = {
  parked: "historyParked",
  parkedAgain: "historyParkedAgain",
  retried: "historyRetried",
  updated: "historyUpdated",
  resumeRequested: "historyResumeRequested",
  skipRequested: "historySkipRequested",
  markRecoverableRequested: "historyMarkRecoverableRequested",
  applyRetrySpecRequested: "historyApplyRetrySpecRequested",
  resolved: "historyResolved",
  unknown: "historyUnknown",
};

/** Where a step stands on the timeline: what failed, what a person asked, how it ended. */
const KIND_TONE: Partial<Record<HistoryKind, string>> = {
  parked: "bg-destructive",
  parkedAgain: "bg-destructive",
  resolved: "bg-emerald-600",
  resumeRequested: "bg-primary",
  skipRequested: "bg-primary",
  markRecoverableRequested: "bg-primary",
  applyRetrySpecRequested: "bg-primary",
};

type Loaded =
  | { state: "loading" }
  | { state: "failed"; message: string }
  | { state: "loaded"; items: HistoryItem[] };

function Step({ item }: { item: HistoryItem }) {
  const { locale, t } = useI18n();
  const words = PARKED_WORDS[locale];
  const { facts } = item;
  const details: string[] = [];
  if (facts.status) details.push(optionWord(locale, "status", facts.status));
  if (facts.retries !== undefined)
    details.push(
      `${words.retries} ${t("retriesOf", {
        retries: facts.retries,
        max: facts.maxRetries ?? t("none"),
      })}`,
    );
  else if (facts.maxRetries !== undefined)
    details.push(`${words.maxRetries} ${facts.maxRetries}`);
  if (facts.minBackoff) details.push(`${words.minBackoff} ${facts.minBackoff}`);
  if (facts.executionTimeout)
    details.push(`${words.executionTimeout} ${facts.executionTimeout}`);
  if (facts.recoverable)
    details.push(optionWord(locale, "recoverable", facts.recoverable));
  if (facts.outcome) details.push(optionWord(locale, "outcome", facts.outcome));
  return (
    <li className="relative flex flex-col gap-0.5 pb-3 pl-5 text-sm last:pb-0">
      <span
        aria-hidden
        className={`absolute top-1.5 left-0 size-2.5 rounded-full ${
          KIND_TONE[item.kind] ?? "bg-muted-foreground"
        }`}
      />
      <div className="flex flex-wrap items-baseline gap-x-2">
        <span className="font-medium">
          {item.kind === "unknown" ? item.name : t(KIND_WORDS[item.kind])}
        </span>
        <time
          className="text-xs text-muted-foreground"
          dateTime={new Date(item.at).toISOString()}
        >
          {formatTime(item.at, locale)}
        </time>
        <span className="text-xs text-muted-foreground">
          {t("historyVersion", { version: item.version })}
        </span>
      </div>
      {details.length > 0 ? (
        <span className="text-muted-foreground">{details.join(" · ")}</span>
      ) : null}
      {facts.reason ? (
        <span className="break-words whitespace-pre-wrap">{facts.reason}</span>
      ) : null}
      {item.operator ? (
        <span className="text-xs text-muted-foreground">
          {t("historyBy", { operator: item.operator })}
        </span>
      ) : null}
    </li>
  );
}

/**
 * A parked entry's own history as a timeline, newest first (the 9.x
 * dashboard's execution history): when the owning service parked it and
 * parked it again, each retry it recorded, each operator's request and who
 * asked, and how it was resolved. Read from the entry's event streams when
 * the detail opens, and again whenever the entry it shows changes
 * (`revision`: its snapshot's version).
 */
export function ParkedHistory({
  id,
  revision,
  source,
}: {
  id: string;
  revision?: unknown;
  source: HistorySource;
}) {
  const { t } = useI18n();
  const [loaded, setLoaded] = useState<Loaded>({ state: "loading" });

  useEffect(() => {
    let current = true;
    source(id).then(
      (streams) => {
        if (current) setLoaded({ state: "loaded", items: historyOf(streams) });
      },
      (error: unknown) => {
        if (current)
          setLoaded({
            state: "failed",
            message: error instanceof Error ? error.message : String(error),
          });
      },
    );
    return () => {
      current = false;
    };
  }, [id, revision, source]);

  return (
    <section className="flex flex-col gap-2" data-testid="parked-history">
      <h3 className="text-sm font-semibold">{t("history")}</h3>
      {loaded.state === "loading" ? (
        <p className="text-sm text-muted-foreground">{t("historyLoading")}</p>
      ) : loaded.state === "failed" ? (
        <p role="alert" className="text-sm text-destructive">
          {t("historyFailed", { message: loaded.message })}
        </p>
      ) : loaded.items.length === 0 ? (
        <p className="text-sm text-muted-foreground">{t("historyEmpty")}</p>
      ) : (
        <ol className="border-l border-border pl-0 [&>li]:-ml-[5px]">
          {loaded.items.map((item) => (
            <Step key={item.key} item={item} />
          ))}
        </ol>
      )}
    </section>
  );
}

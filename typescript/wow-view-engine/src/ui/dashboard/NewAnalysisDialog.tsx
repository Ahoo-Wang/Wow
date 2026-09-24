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

import { useEffect, useId, useState } from 'react';
import { ChartColumnIcon } from 'lucide-react';
import { defaultAnalysisConfig } from '../../analysis/index.js';
import type {
  AnalysisViewConfig,
  DataViewDefinition,
  FieldOption,
} from '../../model/index.js';
import type { ViewEngine, ViewRuntime } from '../../runtime/index.js';
import { stopsSave } from '../../runtime/dashboard/panels.js';
import { toIssue, useFilterEditor, useViewRuntime } from '../../react/index.js';
import { analysisReading } from '../analysis/AnalysisToolbar.js';
import { ignore } from '../analysis/VisualizationPanel.js';
import { useViewMessages } from '../MessagesProvider.js';
import { ErrorStrip, QueryStrip, WarningStrip } from '../StatusStrip.js';
import { Button } from '../components/button.js';
import {
  Dialog,
  DialogClose,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '../components/dialog.js';
import {
  Empty,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
} from '../components/empty.js';
import {
  Field,
  FieldDescription,
  FieldError,
  FieldLabel,
} from '../components/field.js';
import { Input } from '../components/input.js';
import {
  Select,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '../components/select.js';
import { DialogContent, SelectContent } from '../popups.js';
import {
  AnalysisParts,
  type AnalysisHost,
} from '../workbench/AnalysisParts.js';
import { ShellResult } from '../workbench/ResultBlock.js';
import type { FinalFocus } from './commands.js';
import type { WorkbenchFeatures } from '../features.js';

/** The dialog's analysis offers no export of its own (D25 Q28). */
const NO_EXPORT: WorkbenchFeatures = { export: false };

/** What 「放进仪表盘」 hands the board: the analysis it owns, and its title. */
export interface NewAnalysis {
  definitionId: string;
  config: AnalysisViewConfig;
  title: string;
}

export interface NewAnalysisDialogProps {
  engine: ViewEngine;
  open: boolean;
  onOpenChange(open: boolean): void;
  /**
   * Puts the analysis on the board (`DashboardEditing.addPanel` with
   * `owned`); `false` when the board holds as many panels as it may, and
   * the dialog stays open to say so.
   */
  onAdd(analysis: NewAnalysis): boolean;
  optionsFor?(remote: string): FieldOption[] | undefined;
  /**
   * Where the keyboard goes as it closes (`FinalFocus`): the control that
   * asked — a menu's trigger, since the item that was pressed went with the
   * menu. Base UI's own choice when left out.
   */
  finalFocus?: FinalFocus;
}

/**
 * A new analysis made inside the dashboard (D22 C): a large dialog that is
 * the analysis view itself — first the data it is about, then the same tray
 * and the same result as the workbench's (`AnalysisParts`), running as it is
 * edited under the reader's own 「改了就跑」 — and a title that follows what
 * it shows until its author names it. 「放进仪表盘」 puts it on the board as
 * a view the board owns: saved, shared and deleted with it, never listed.
 *
 * The view is held here, unsaved, for as long as the dialog is open, and let
 * go when it closes or another data is chosen: nothing of it outlives the
 * dialog but the panel it became.
 */
export function NewAnalysisDialog({
  engine,
  open,
  onOpenChange,
  onAdd,
  optionsFor,
  finalFocus,
}: NewAnalysisDialogProps) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent
        {...(finalFocus ? { finalFocus } : {})}
        data-slot="new-analysis"
        className="flex max-h-[calc(100dvh-2rem)] flex-col sm:max-w-6xl"
      >
        {/* A fresh question each time it opens. */}
        {open && (
          <NewAnalysisForm
            engine={engine}
            onAdd={analysis => {
              if (!onAdd(analysis)) return false;
              onOpenChange(false);
              return true;
            }}
            optionsFor={optionsFor}
          />
        )}
      </DialogContent>
    </Dialog>
  );
}

/** The data a new analysis may be about: what declares one and is usable. */
function analysable(engine: ViewEngine): DataViewDefinition[] {
  return [...engine.definitions.values()].filter(
    (definition): definition is DataViewDefinition =>
      definition.kind === 'data' &&
      definition.analysis !== undefined &&
      !engine
        .definitionIssues(definition.id)
        .some(found => found.severity === 'error'),
  );
}

function NewAnalysisForm({
  engine,
  onAdd,
  optionsFor,
}: {
  engine: ViewEngine;
  onAdd(analysis: NewAnalysis): boolean;
  optionsFor?(remote: string): FieldOption[] | undefined;
}) {
  const messages = useViewMessages();
  const id = useId();
  const [definitions] = useState(() => analysable(engine));
  // One data to choose from is chosen already.
  const [definitionId, setDefinitionId] = useState<string | null>(
    definitions.length === 1 ? definitions[0].id : null,
  );
  const definition = definitions.find(entry => entry.id === definitionId);
  const opened = useHeldAnalysis(engine, definition ?? null);
  const runtime = opened.runtime;
  const state = useViewRuntime(runtime);
  const filter = useFilterEditor(runtime);

  // 「改了就跑」 as the reader has it for this data, as in the workbench.
  const [autoRun, setAutoRunState] = useState(true);
  useEffect(() => {
    if (!definitionId) return;
    let live = true;
    engine.preferences(definitionId).then(
      preferences => {
        if (live) setAutoRunState(preferences.autoRun ?? true);
      },
      () => undefined,
    );
    return () => {
      live = false;
    };
  }, [engine, definitionId]);
  useEffect(() => {
    runtime?.setAutoApply(autoRun);
  }, [runtime, autoRun]);

  const host: AnalysisHost = {
    filter,
    state,
    autoRun,
    setAutoRun: async on => {
      setAutoRunState(on);
      if (definitionId) await engine.setAutoRun(definitionId, on);
    },
    canDrill: false,
    drill: ignore,
    follow: ignore,
  };

  // Named by what it shows — the reading the result's toolbar says — until
  // the author types a name of their own.
  const data = state?.result?.data;
  const columns =
    data?.kind === 'analysis' ? (data.view.schema ?? data.view.columns) : null;
  const reading = columns ? analysisReading(columns, messages) : '';
  const [typed, setTyped] = useState<string | null>(null);
  const title = typed ?? reading;
  const [full, setFull] = useState(false);

  const draft = state?.draft.kind === 'analysis' ? state.draft : null;
  const blocked = state ? stopsSave('analysis', state.issues) : true;
  const ready =
    definitionId !== null &&
    draft !== null &&
    !blocked &&
    title.trim().length > 0;
  const add = () => {
    if (!ready || !definitionId || !draft) return;
    setFull(!onAdd({ definitionId, config: draft, title: title.trim() }));
  };

  return (
    <>
      <DialogHeader>
        <DialogTitle>
          {definition
            ? messages.label('label.panel.new-analysis.heading-of', {
                definition: definition.title,
              })
            : messages.label('label.panel.new-analysis.heading')}
        </DialogTitle>
        <DialogDescription>
          {messages.label('label.panel.new-analysis.description')}
        </DialogDescription>
      </DialogHeader>

      {definitions.length === 0 ? (
        <Empty data-slot="new-analysis-none">
          <EmptyHeader>
            <EmptyMedia variant="icon">
              <ChartColumnIcon />
            </EmptyMedia>
            <EmptyTitle>
              {messages.label('label.panel.new-analysis.none')}
            </EmptyTitle>
          </EmptyHeader>
        </Empty>
      ) : (
        <Field orientation="horizontal" className="w-auto">
          <FieldLabel htmlFor={`${id}-data`} className="flex-none">
            {messages.label('label.panel.new-analysis.data')}
          </FieldLabel>
          <Select
            items={definitions.map(entry => ({
              value: entry.id,
              label: entry.title,
            }))}
            value={definitionId}
            onValueChange={next => {
              if (typeof next !== 'string') return;
              setDefinitionId(next);
              setTyped(null);
            }}
          >
            <SelectTrigger
              id={`${id}-data`}
              data-slot="new-analysis-data"
              className="min-w-48"
            >
              <SelectValue
                placeholder={messages.label('label.panel.new-analysis.pick')}
              />
            </SelectTrigger>
            <SelectContent>
              <SelectGroup>
                {definitions.map(entry => (
                  <SelectItem key={entry.id} value={entry.id}>
                    {entry.title}
                  </SelectItem>
                ))}
              </SelectGroup>
            </SelectContent>
          </Select>
        </Field>
      )}

      <div className="-mx-4 min-h-0 flex-1 overflow-y-auto px-4">
        {definitions.length > 0 && !definition && (
          <Empty data-slot="new-analysis-pick">
            <EmptyHeader>
              <EmptyMedia variant="icon">
                <ChartColumnIcon />
              </EmptyMedia>
              <EmptyTitle>
                {messages.label('label.panel.new-analysis.pick')}
              </EmptyTitle>
              <EmptyDescription>
                {messages.label('label.panel.new-analysis.pick-hint')}
              </EmptyDescription>
            </EmptyHeader>
          </Empty>
        )}
        {opened.error && (
          <ErrorStrip
            issues={[
              {
                code: 'label.panel.new-analysis.failed',
                path: [],
                severity: 'error',
                params: { reason: messages.issue(opened.error) },
              },
            ]}
          />
        )}
        {runtime && state && (
          <AnalysisParts
            workbench={host}
            runtime={runtime}
            optionsFor={optionsFor}
            followUps={false}
            // Nothing to take away yet: the draft becomes a panel, and a
            // panel's 「⋯」 exports what it shows (D25 Q28).
            features={NO_EXPORT}
            visualizationBack={messages.label(
              'label.panel.new-analysis.close-visualization',
            )}
          >
            {parts => (
              <div className="flex min-w-0 flex-col gap-4">
                <ErrorStrip
                  issues={filter.unmarked.map(parts.nameIssue ?? same)}
                  action={
                    typeof parts.errorAction === 'function'
                      ? parts.errorAction(true)
                      : parts.errorAction
                  }
                />
                <WarningStrip
                  issues={state.issues
                    .filter(found => found.severity === 'warning')
                    .map(parts.nameIssue ?? same)}
                />
                <div data-slot="new-analysis-tray">{parts.editor}</div>
                <div className="flex min-w-0 gap-4">
                  {parts.panel && (
                    <aside
                      data-slot="new-analysis-panel"
                      className="w-72 flex-none border-r pr-4"
                    >
                      {parts.panel}
                    </aside>
                  )}
                  <div className="min-w-0 flex-1">
                    <ShellResult
                      framed
                      slots={parts.resultSlots}
                      toolbar={parts.toolbar}
                      strip={
                        state.query.status === 'error' &&
                        state.query.error && (
                          <QueryStrip
                            error={state.query.error}
                            stale={state.result !== null}
                            onRetry={() => runtime.refresh()}
                          />
                        )
                      }
                      result={parts.result}
                      resetKeys={[runtime.id]}
                    />
                  </div>
                </div>
              </div>
            )}
          </AnalysisParts>
        )}
      </div>

      {/* The title and the two buttons share the footer's row where there
          is room; on a phone the registry's footer stacks its children the
          other way up, which put 「放进仪表盘」 over the title it adds by —
          seen before what is to be filled in, and Tab going the other way
          (U-14). So the stack reads down, the title first. */}
      <DialogFooter className="items-end max-sm:flex-col max-sm:items-stretch sm:justify-between">
        <Field className="sm:max-w-md" data-invalid={full || undefined}>
          <FieldLabel htmlFor={`${id}-title`}>
            {messages.label('label.panel.new-analysis.title')}
          </FieldLabel>
          <Input
            id={`${id}-title`}
            data-slot="new-analysis-title"
            value={title}
            disabled={!runtime}
            onChange={event => setTyped(event.target.value)}
            onKeyDown={event => {
              if (event.key === 'Enter') add();
            }}
          />
          {full ? (
            <FieldError>
              {messages.label('label.panel.new-analysis.full')}
            </FieldError>
          ) : (
            <FieldDescription>
              {runtime && blocked
                ? messages.label('label.panel.new-analysis.blocked')
                : messages.label('label.panel.new-analysis.title-hint')}
            </FieldDescription>
          )}
        </Field>
        <div className="flex justify-end gap-2">
          <DialogClose render={<Button variant="outline" />}>
            {messages.label('label.dialog.cancel')}
          </DialogClose>
          <Button data-slot="new-analysis-add" disabled={!ready} onClick={add}>
            {messages.label('label.panel.new-analysis.add')}
          </Button>
        </div>
      </DialogFooter>
    </>
  );
}

/**
 * The unsaved analysis the dialog holds over the data chosen: made from the
 * definition's default (a count, no dimension — one number from the start)
 * and let go when the data changes or the dialog closes.
 */
function useHeldAnalysis(
  engine: ViewEngine,
  definition: DataViewDefinition | null,
): {
  runtime: ViewRuntime<AnalysisViewConfig> | null;
  error: ReturnType<typeof toIssue> | null;
} {
  const [held, setHeld] = useState<{
    definitionId: string;
    runtime: ViewRuntime<AnalysisViewConfig> | null;
    error: ReturnType<typeof toIssue> | null;
  } | null>(null);
  useEffect(() => {
    if (!definition) return;
    let runtime: ViewRuntime<AnalysisViewConfig> | null = null;
    let cancelled = false;
    // Made a moment after mounting, as `useOpenView` opens a view: what is
    // made is answered in a callback, and a rehearsal mount that is taken
    // down at once leaves nothing open behind it.
    void Promise.resolve().then(() => {
      if (cancelled) return;
      try {
        runtime = engine.create(definition.id, {
          title: definition.title,
          scope: 'personal',
          config: defaultAnalysisConfig(definition, engine.limits),
        });
        setHeld({ definitionId: definition.id, runtime, error: null });
      } catch (error) {
        setHeld({
          definitionId: definition.id,
          runtime: null,
          error: toIssue(error, 'view.open.failed'),
        });
      }
    });
    return () => {
      cancelled = true;
      if (runtime) engine.close(runtime);
    };
  }, [engine, definition]);
  return held && held.definitionId === definition?.id
    ? { runtime: held.runtime, error: held.error }
    : { runtime: null, error: null };
}

function same<T>(value: T): T {
  return value;
}

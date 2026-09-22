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

import type { AnalysisViewConfig, FieldOption } from '../model/index.js';
import type { ViewEngine } from '../runtime/index.js';
import { useWorkbench } from '../react/index.js';
import type { ViewMessages } from './messages.js';
import { useViewMessages } from './MessagesProvider.js';
import { featuresOf, type WorkbenchFeatures } from './features.js';
import { WorkbenchShell } from './WorkbenchShell.js';
import type { RenderFailureHandler } from './RenderBoundary.js';
import { AnalysisParts } from './workbench/AnalysisParts.js';
import type { RenderParts } from './workbench/parts.js';

export interface AnalysisWorkbenchProps {
  engine: ViewEngine;
  definitionId: string;
  /**
   * Which view is open, as `value` is on an input: leaving it out lets the
   * workbench own it from the effective default on, and passing it — a
   * string, or `null` for that default — puts a host's route in charge, every
   * later change opening what it names. It goes through the leave guard, so a
   * pushed view never takes an unsaved draft away without asking
   * (`WorkbenchOptions.instanceId`).
   */
  instanceId?: string | null;
  /**
   * Told which view is open whenever that changes, in the same vocabulary
   * `instanceId` is written in — `null` is the effective default — so a host
   * can put it straight into a route and get the same view back from the
   * link.
   */
  onInstanceChange?(id: string | null): void;
  /**
   * What a new view starts from: `defaultAnalysisConfig` when left out. It
   * opens unsaved, and the first save asks for its name and audience.
   */
  template?: AnalysisViewConfig;
  theme?: 'light' | 'dark';
  /** Wording, merged over what is already in force: where a host translates. */
  messages?: ViewMessages;
  /**
   * The language dates and times show in; the runtime's when left out. It is
   * the same choice as `messages`, made for values rather than words.
   */
  locale?: string;
  optionsFor?(remote: string): FieldOption[] | undefined;
  /**
   * The sidebar this workbench opens on. It is view state and nothing else —
   * never saved, never asked about by the leave guard — so a host sets where
   * it starts and the shell owns it from there. Left out, a column narrower
   * than `md` opens folded and a wider one opens with the list beside it.
   */
  defaultSidebarOpen?: boolean;
  /** Told whenever the sidebar opens or closes, for a host that mirrors it. */
  onSidebarOpenChange?(open: boolean): void;
  /**
   * Whether this workbench offers to fill the screen. On by default, and the
   * fold itself belongs to the shell — a host only says whether the control
   * exists, because a page that is already one full-screen view of one thing
   * has nothing to gain from a second way to say so.
   */
  expandable?: boolean;
  /**
   * Which of the workbench's own controls are on screen (D18 XI). Only
   * `manage` applies here — the view manager's gear and switcher item; the
   * others name record-view controls.
   */
  features?: Pick<WorkbenchFeatures, 'manage'>;
  /**
   * Told of a render failure one of the workbench's boundaries caught — the
   * host's action slots, the editor, the result, a panel. The part shows a
   * recoverable error state in place regardless; this is the host's copy.
   */
  onRenderFailure?: RenderFailureHandler;
}

/** The one kind this workbench draws, held once so the list is not re-narrowed per render. */
const ANALYSIS = ['analysis'] as const;

/**
 * The default Analysis workbench: the view list, the conditions, what to
 * aggregate, and the result as a table or a chart.
 *
 * `useWorkbench` finds, opens and leaves the view; `WorkbenchShell` draws the
 * frame; `AnalysisParts` is what makes an analysis view an analysis view.
 * This component only joins the three.
 */
export function AnalysisWorkbench({
  engine,
  definitionId,
  instanceId,
  onInstanceChange,
  theme,
  messages: wording,
  locale,
  optionsFor,
  defaultSidebarOpen,
  onSidebarOpenChange,
  expandable,
  onRenderFailure,
  template,
  features,
}: AnalysisWorkbenchProps) {
  const messages = useViewMessages(wording);
  const workbench = useWorkbench(engine, definitionId, {
    kinds: ANALYSIS,
    instanceId,
    onInstanceChange,
    newView: {
      title: messages.label('label.view.new-title'),
      ...(template ? { templates: { analysis: template } } : {}),
    },
  });
  const { runtime } = workbench;

  const frame: RenderParts = parts => (
    <WorkbenchShell
      workbench={workbench}
      title={engine.definitions.get(definitionId)?.title}
      theme={theme}
      messages={wording}
      locale={locale}
      timeZone={engine.environment.timeZone}
      defaultSidebarOpen={defaultSidebarOpen}
      onSidebarOpenChange={onSidebarOpenChange}
      expandable={expandable}
      manage={featuresOf(features).manage}
      onRenderFailure={onRenderFailure}
      {...parts}
    />
  );

  // The parts are a component of the runtime, mounted whatever is open, and
  // the shell is drawn inside them (`workbench/parts.ts`).
  return (
    <AnalysisParts
      workbench={workbench}
      runtime={runtime?.kind === 'analysis' ? runtime : null}
      messages={wording}
      optionsFor={optionsFor}
    >
      {frame}
    </AnalysisParts>
  );
}

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

import type {
  AnalysisViewConfig,
  FieldOption,
  RecordViewConfig,
} from '../model/index.js';
import type {
  ViewEngine,
  ViewHandOver,
  ViewNavigation,
} from '../runtime/index.js';
import { useWorkbench } from '../react/index.js';
import { useViewMessages } from './MessagesProvider.js';
import type { ViewMessages } from './messages.js';
import { featuresOf, type WorkbenchFeatures } from './features.js';
import { WorkbenchShell } from './WorkbenchShell.js';
import type { RenderFailureHandler } from './RenderBoundary.js';
import { AnalysisParts } from './workbench/AnalysisParts.js';
import { RecordParts, type RecordViewProps } from './workbench/RecordParts.js';

/** The kinds of view a data definition holds; a dashboard is a definition of its own. */
export type DataViewKind = 'record' | 'analysis';

export interface DataWorkbenchProps {
  engine: ViewEngine;
  definitionId: string;
  /**
   * Which kinds this workbench lists and draws, in the order the "new view"
   * menu offers them. Both by default (D20): one data definition holds its
   * record views and its analysis views in one list, and the user switches
   * between them as between any two views. A host that wants a page of one
   * kind names that one (D9), which is a real narrowing — the other kind is
   * neither listed nor openable there.
   */
  kinds?: readonly DataViewKind[];
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
   * A view a dashboard or an embed handed the host's route, to open here as
   * it came (`ViewNavigation` of kind `view` or `unsaved`, D26 Q30): the
   * saved view with what the reader set on the board among its own
   * conditions — 「已修改」, each removable, 「还原」 takes them off — or a
   * view nobody saved (a follow-up on a group, a board's own analysis).
   * What the page holds is its scope, which nobody here takes off. Each new
   * object opens once, through the leave guard
   * (`WorkbenchOptions.handOver`).
   */
  handOver?: ViewHandOver | null;
  /**
   * The host's route, for the way back to the board a view was handed from:
   * with it, the workbench draws 「返回〈仪表盘〉」 under the title bar and
   * hands the board's own target here when it is pressed (D26 Q33).
   */
  onNavigate?(to: ViewNavigation): void;
  /**
   * What a new view of each kind starts from, for a host with a better first
   * view than the definition's default: `defaultRecordConfig` and
   * `defaultAnalysisConfig` when left out. The view still opens unsaved,
   * under the catalogue's "New view", and the first save asks for its name
   * and audience.
   */
  templates?: { record?: RecordViewConfig; analysis?: AnalysisViewConfig };
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
   * Which of the workbench's own controls are on screen (D18 XI): export,
   * the layout switch, column settings, sort settings, the visualization
   * panel, the view manager. All on by default; one turned off is absent,
   * not disabled. The first four name record-view controls, `visualization`
   * the analysis view's, and `manage` applies to every kind.
   */
  features?: WorkbenchFeatures;
  /**
   * Told of a render failure one of the workbench's boundaries caught — the
   * host's action slots, the editor, the result, a panel. The part shows a
   * recoverable error state in place regardless; this is the host's copy.
   */
  onRenderFailure?: RenderFailureHandler;
  /**
   * What the host says about the record views: its business actions, how a
   * cell is read, whether rows can be picked, what an empty result says.
   * They are one object because none of them means anything to an analysis
   * view (D20: an analysis view has no business actions), and a workbench of
   * both kinds would otherwise wear seven props that apply to half of what
   * it draws.
   */
  record?: RecordViewProps;
}

/** What the workbench draws when the host names no kinds. */
const DATA_KINDS: readonly DataViewKind[] = ['record', 'analysis'];

/**
 * The data workbench: one view list of record and analysis views, and the
 * editor and result of whichever is open.
 *
 * `useWorkbench` finds, opens and leaves the view, for every kind alike;
 * `WorkbenchShell` draws the frame; `RecordParts` and `AnalysisParts` are
 * what make each kind of view what it is, and each hands back its slots only
 * while a view of its kind is open — the shell never learns which kind it is
 * drawing (D18-1). Both parts stay mounted whatever is open, so the shell is
 * drawn at one place in the tree and keeps the screen's posture across a
 * switch (`workbench/parts.ts`).
 */
export function DataWorkbench({
  engine,
  definitionId,
  kinds = DATA_KINDS,
  instanceId,
  onInstanceChange,
  handOver,
  onNavigate,
  templates,
  theme,
  messages: wording,
  locale,
  optionsFor,
  defaultSidebarOpen,
  onSidebarOpenChange,
  expandable,
  features,
  onRenderFailure,
  record,
}: DataWorkbenchProps) {
  // The host's wording, resolved here rather than read off the provider:
  // `ViewSurface` is inside `WorkbenchShell`, so this component is above the
  // context and would otherwise name a new view in English on a translated
  // page.
  const messages = useViewMessages(wording, locale);
  const workbench = useWorkbench(engine, definitionId, {
    kinds,
    instanceId,
    onInstanceChange,
    handOver,
    onNavigate,
    newView: {
      title: messages.label('label.view.new-title'),
      ...(templates ? { templates } : {}),
    },
  });
  const { runtime } = workbench;

  return (
    <RecordParts
      workbench={workbench}
      runtime={runtime?.kind === 'record' ? runtime : null}
      messages={wording}
      locale={locale}
      optionsFor={optionsFor}
      features={features}
      {...record}
    >
      {recordParts => (
        <AnalysisParts
          workbench={workbench}
          runtime={runtime?.kind === 'analysis' ? runtime : null}
          messages={wording}
          locale={locale}
          optionsFor={optionsFor}
          features={features}
        >
          {analysisParts => (
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
              // At most one of the two says anything at a time: the other
              // hands back no parts, so the spread is a merge, not a fight.
              {...recordParts}
              {...analysisParts}
            />
          )}
        </AnalysisParts>
      )}
    </RecordParts>
  );
}

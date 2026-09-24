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

/**
 * Transient state: what is open, what is in flight, what came back.
 *
 * This is the only layer with a clock, a timer and a queue, and it reaches all
 * three through an injected `RuntimeEnvironment`, so the kernels below it stay
 * pure and the React layer above it only subscribes.
 *
 * This file is the runtime's **public** face, named export by export (A-16,
 * D29): what a host holds — the engine, the runtime contracts and every type
 * their signatures name, the environment and the source ports — and nothing
 * the engine is built from. The scheduler, the store the runtimes are made
 * of, the timers, the listener sets, the runtime classes and the readings
 * `/react` and `/ui` share stay behind it; those two layers import them from
 * their own modules. A name added here is a name promised, and
 * `test/publicSurface.test.ts` says so.
 */

// The engine: the one way a host opens, creates and writes views.
export {
  ViewEngine,
  type CreateInput,
  type ViewEngineOptions,
  type ViewListing,
} from './viewEngine.js';
export type { ConflictChoice, WriteTarget } from './writeLedger.js';
export {
  validateDefinition,
  type ValidateDefinitionOptions,
} from './validateDefinition.js';

// What opening a view hands back, and the one reading of its state.
export {
  hasAsked,
  hasResult,
  type AnyViewRuntime,
  type DefinitionFor,
  type OpenOptions,
  type QueryStatus,
  type RecordViewRuntime,
  type RuntimeFor,
  type ViewQueryState,
  type ViewResult,
  type ViewRuntime,
  type ViewRuntimeState,
} from './viewRuntimeTypes.js';
export { isRecordRuntime } from './recordRuntime.js';
export {
  ExportCancelled,
  isExportCancelled,
  type ExportedRows,
  type ExportRowsOptions,
} from './exportRows.js';
export type { ValueCandidateSource } from './valueCandidates.js';

// A dashboard's runtime, and what its state and commands are made of.
export type {
  DashboardRuntime,
  DashboardRuntimeState,
  HeldFilters,
} from './dashboard/contract.js';
export type { DashboardPanelState } from './dashboard/panels.js';
export type {
  DashboardEditing,
  DashboardFilterEditing,
} from './dashboard/editing.js';
export type {
  EditCommand,
  EditHistoryState,
  EditStep,
} from './dashboard/history.js';
export type { PanelGrouping } from './dashboard/grouping.js';
export type {
  CrossFilterOutcome,
  DestinationBoard,
  PressDestination,
} from './dashboard/press.js';

// Where a way off a board or an embed goes, for the host's route.
export type {
  BoardOrigin,
  DashboardTarget,
  GroupNaming,
  HandOver,
  SavedViewTarget,
  UnsavedViewTarget,
  ViewHandOver,
  ViewNavigation,
} from './navigation.js';

// Writes: what is in flight, and what a command throws.
export {
  isViewCommandError,
  isViewWriteError,
  ViewCommandError,
  ViewWriteError,
  type WriteAction,
  type WriteHandle,
  type WritePayload,
  type WriteState,
} from './write.js';
export type {
  ViewChange,
  ViewChangeKind,
  ViewChangeListener,
} from './viewChanges.js';

// The host's side: the clock and page visibility, and where data comes from.
export {
  ALWAYS_VISIBLE,
  defaultRuntimeEnvironment,
  type RuntimeEnvironment,
  type VisibilitySource,
} from './environment.js';
export type {
  OptionSource,
  ProjectedAnalysis,
  ProjectedBase,
  ProjectedRecord,
  ProjectedView,
  ViewSource,
} from './source.js';

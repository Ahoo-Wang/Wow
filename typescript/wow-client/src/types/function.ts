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
 * Function kind enum
 *
 * Represents the different types of functions in the system.
 */
export enum FunctionKind {
  /**
   * Command function kind
   */
  COMMAND = 'COMMAND',

  /**
   * Error function kind
   */
  ERROR = 'ERROR',

  /**
   * Event function kind
   */
  EVENT = 'EVENT',

  /**
   * Sourcing function kind
   */
  SOURCING = 'SOURCING',

  /**
   * State event function kind
   */
  STATE_EVENT = 'STATE_EVENT',
}

/**
 * Interface for function information.
 */
export interface FunctionInfo {
  functionKind: FunctionKind;
  contextName: string;
  processorName: string;
  name: string;
}

/**
 * Interface for objects that have function information.
 */
export interface FunctionInfoCapable {
  function: FunctionInfo;
}

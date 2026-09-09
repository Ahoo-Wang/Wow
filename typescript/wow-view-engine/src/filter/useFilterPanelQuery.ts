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

import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import type {
  FilterCompileResult,
  FilterConfiguration,
} from './filterModel.js';
import type { FilterPanelProps } from './filterReactTypes.js';
import { sameFilterQuery } from './filterTree.js';
import { message } from './filterPanelUtils.js';

/** Submission identity prevents old acknowledgements from changing a replacement session. */
export function useFilterPanelQuery(
  props: FilterPanelProps,
  configuration: FilterConfiguration,
  applied: FilterCompileResult['expression'],
  compiled: FilterCompileResult,
  valid: boolean,
  generation: object,
  setBaseline: (configuration: FilterConfiguration) => void,
) {
  const latest = useRef({
    props,
    configuration,
    applied,
    compiled,
    valid,
    generation,
  });
  useLayoutEffect(() => {
    latest.current = {
      props,
      configuration,
      applied,
      compiled,
      valid,
      generation,
    };
  }, [props, configuration, applied, compiled, valid, generation]);
  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);
  const inFlight = useRef<
    | {
        pending: boolean;
        generation: object;
        expression: NonNullable<FilterCompileResult['expression']>;
      }
    | undefined
  >(undefined);
  const [submission, setSubmission] = useState<typeof inFlight.current>();
  const [error, setError] = useState<{ generation: object; message: string }>();
  const applyError =
    error?.generation === generation ? error.message : undefined;
  function setApplyError(message?: string) {
    setError(message === undefined ? undefined : { generation, message });
  }
  const submitting =
    (submission?.generation === generation &&
      (submission.pending || props.querying) &&
      sameFilterQuery(compiled.expression, submission.expression)) ||
    (!!props.querying && sameFilterQuery(compiled.expression, applied));
  function apply() {
    const current = latest.current;
    if (
      !mounted.current ||
      current.props.disabled ||
      !current.valid ||
      !current.compiled.expression ||
      (inFlight.current?.generation === current.generation &&
        (inFlight.current.pending || current.props.querying) &&
        sameFilterQuery(
          current.compiled.expression,
          inFlight.current.expression,
        )) ||
      (current.props.querying &&
        sameFilterQuery(current.compiled.expression, current.applied))
    )
      return;
    const configuration = structuredClone(current.configuration);
    const expression = structuredClone(current.compiled.expression);
    const submission = {
      pending: true,
      generation: current.generation,
      expression,
    };
    inFlight.current = submission;
    const active = () =>
      mounted.current &&
      latest.current.generation === submission.generation &&
      inFlight.current === submission;
    const success = () => {
      if (!active()) return;
      setBaseline(configuration);
      submission.pending = false;
      setSubmission({ ...submission });
      setApplyError(undefined);
    };
    const failure = (error: unknown) => {
      if (!active()) return;
      inFlight.current = undefined;
      setSubmission(undefined);
      setError({ generation: submission.generation, message: message(error) });
    };
    try {
      const result = current.props.onApply({ configuration, expression });
      if (result && typeof result.then === 'function') {
        setSubmission(submission);
        void Promise.resolve(result).then(success, failure);
      } else success();
    } catch (error) {
      failure(error);
    }
  }
  return { apply, applyError, setApplyError, submitting };
}

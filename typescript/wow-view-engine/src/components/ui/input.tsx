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

import type * as React from 'react';
import { Input as InputPrimitive } from '@base-ui/react/input';
import { cn } from '../../lib/utils.js';

function Input({ className, type, ...props }: React.ComponentProps<'input'>) {
  return (
    <InputPrimitive
      type={type}
      data-slot="input"
      className={cn(
        'fve:h-8 fve:w-full fve:min-w-0 fve:rounded-lg fve:border fve:border-input fve:bg-transparent fve:px-2.5 fve:py-1 fve:text-base fve:transition-colors fve:outline-none fve:file:inline-flex fve:file:h-6 fve:file:border-0 fve:file:bg-transparent fve:file:text-sm fve:file:font-medium fve:file:text-foreground fve:placeholder:text-muted-foreground fve:focus-visible:border-ring fve:focus-visible:ring-3 fve:focus-visible:ring-ring/50 fve:disabled:pointer-events-none fve:disabled:cursor-not-allowed fve:disabled:bg-input/50 fve:disabled:opacity-50 fve:aria-invalid:border-destructive fve:aria-invalid:ring-3 fve:aria-invalid:ring-destructive/20 fve:md:text-sm fve:dark:bg-input/30 fve:dark:disabled:bg-input/80 fve:dark:aria-invalid:border-destructive/50 fve:dark:aria-invalid:ring-destructive/40',
        className,
      )}
      {...props}
    />
  );
}

export { Input };

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

import { Tag } from '@ahoo-wang/fetcher-openapi';
import { PartialBy } from '@ahoo-wang/fetcher';
import { AggregateDefinition, TagAliasAggregate } from './aggregate';

/**
 * Checks if a tag name follows the alias aggregate pattern and extracts its components.
 *
 * This function determines if a tag name follows the format "contextAlias.aggregateName"
 * and returns the components if it matches.
 *
 * @param tagName - The tag name to check
 * @returns A tuple with [contextAlias, aggregateName] if the pattern matches, null otherwise
 */
export function isAliasAggregate(tagName: string): [string, string] | null {
  const parts = tagName.split('.');
  if (parts.length != 2 || parts[0].length === 0 || parts[1].length === 0) {
    return null;
  }
  return parts as [string, string];
}

/**
 * Converts a Tag to a TagAliasAggregate if it follows the alias pattern.
 *
 * This function checks if a tag name follows the alias aggregate pattern and
 * creates a TagAliasAggregate object with the tag and its parsed components.
 *
 * @param tag - The Tag to convert
 * @returns A TagAliasAggregate if the tag name matches the pattern, null otherwise
 */
export function tagToAggregate(tag: Tag): TagAliasAggregate | null {
  const parts = isAliasAggregate(tag.name);
  if (!parts) {
    return null;
  }

  return {
    tag,
    contextAlias: parts[0],
    aggregateName: parts[1],
  };
}

/**
 * Converts an array of Tags to a map of aggregates.
 *
 * This function processes an array of tags, converts those that follow the alias pattern
 * to aggregates, and returns a map where keys are tag names and values are partial
 * aggregate definitions.
 *
 * @param tags - Optional array of Tags to process
 * @returns A map of aggregate definitions keyed by tag name
 */
export function tagsToAggregates(
  tags?: Tag[],
): Map<string, PartialBy<AggregateDefinition, 'state' | 'fields'>> {
  const tagAliasAggregates = tags
    ?.map(tag => tagToAggregate(tag))
    .filter(tag => tag !== null);
  if (!tagAliasAggregates) {
    return new Map();
  }
  const aggregates = new Map<
    string,
    PartialBy<AggregateDefinition, 'state' | 'fields'>
  >();
  tagAliasAggregates.forEach(tagAliasAggregate => {
    aggregates.set(tagAliasAggregate.tag.name, {
      aggregate: tagAliasAggregate,
      commands: new Map(),
      events: new Map(),
    });
  });
  return aggregates;
}

/**
 * Extracts the command name from an operation ID.
 *
 * This function parses an operation ID expected to follow the format
 * "context.aggregate.command" and returns the command part.
 *
 * @param operationId - Optional operation ID to parse
 * @returns The command name if the operation ID follows the expected format, null otherwise
 */
export function operationIdToCommandName(operationId?: string): string | null {
  if (!operationId) {
    return null;
  }
  const parts = operationId.split('.');
  if (parts.length != 3) {
    return null;
  }
  return parts[2];
}

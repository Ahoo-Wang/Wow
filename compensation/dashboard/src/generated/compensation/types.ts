/**
 * - key: compensation.Link
 * - schema: 
 * ```json
 * {
 *   "type": "object",
 *   "properties": {
 *     "href": {
 *       "type": "string"
 *     },
 *     "templated": {
 *       "type": "boolean"
 *     }
 *   }
 * }
 * ```
 */
export interface Link {
    href: string;
    templated: boolean;
}

/**
 * - key: compensation.StringLinkMap
 * - schema: 
 * ```json
 * {
 *   "type": "object",
 *   "additionalProperties": {
 *     "$ref": "#/components/schemas/compensation.Link"
 *   }
 * }
 * ```
 */
export type StringLinkMap = Record<string, Link>;
/**
 * - key: compensation.StringObjectMap
 * - schema: 
 * ```json
 * {
 *   "type": "object"
 * }
 * ```
 */
export type StringObjectMap = Record<string, any>;
/**
 * - key: compensation.StringStringListMap
 * - schema: 
 * ```json
 * {
 *   "type": "object",
 *   "additionalProperties": {
 *     "type": "array",
 *     "items": {
 *       "type": "string"
 *     }
 *   }
 * }
 * ```
 */
export type StringStringListMap = Record<string, string[]>;

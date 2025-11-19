/**
 * - key: compensation.ApiVersion
 * - schema: 
 * ```json
 * {
 *   "type": "string",
 *   "enum": [
 *     "V2",
 *     "V3"
 *   ]
 * }
 * ```
 */
export enum ApiVersion {
    V2 = `V2`,
    V3 = `V3`
}

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
 * - key: compensation.SecurityContext
 * - schema: 
 * ```json
 * {
 *   "type": "object"
 * }
 * ```
 */
export type SecurityContext = Record<string, any>;
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
 * - key: compensation.WebServerNamespace
 * - schema: 
 * ```json
 * {
 *   "type": "object",
 *   "properties": {
 *     "value": {
 *       "type": "string"
 *     }
 *   }
 * }
 * ```
 */
export interface WebServerNamespace {
    value: string;
}

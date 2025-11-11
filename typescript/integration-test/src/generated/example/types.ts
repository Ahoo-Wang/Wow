/** - key: example.ApiVersion */
export enum ApiVersion {
  V2 = 'V2',
  V3 = 'V3',
}

/** - key: example.Link */
export interface Link {
  href: string;
  templated: boolean;
}

/** - key: example.SecurityContext */
export type SecurityContext = Record<string, any>;
/** - key: example.StringLinkMap */
export type StringLinkMap = Record<string, Link>;
/** - key: example.StringObjectMap */
export type StringObjectMap = Record<string, any>;

/** - key: example.WebServerNamespace */
export interface WebServerNamespace {
  value: string;
}

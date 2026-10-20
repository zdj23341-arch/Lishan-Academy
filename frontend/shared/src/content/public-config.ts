import type { ApiClient } from '../http';

export type AgreementKind = 'terms' | 'privacy';
export interface PublicAgreementLink {
  title: string;
  version: string;
  articleId: string;
}
export interface PublicAgreementConfiguration {
  agreementVersions: Record<string, string>;
  /** Optional while an older backend is running; absence never implies consent. */
  agreementLinks?: Partial<Record<AgreementKind, PublicAgreementLink>>;
}
export interface PublicConfiguration extends PublicAgreementConfiguration {
  storeName: string;
  customerService: string | null;
  allowedFileFormats: string[];
}
export function createPublicConfigurationApi(client: ApiClient) {
  return {
    get: () => client.request<PublicConfiguration>('/config/public', { authentication: 'none' }),
  };
}

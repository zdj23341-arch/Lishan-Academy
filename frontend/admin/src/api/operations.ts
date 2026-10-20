import { createAdminOperationsApi } from '@jxsc/shared';
import { client } from './client';
import { session } from './session';
import { environment } from './environment';
import { createExportHistory } from './export-history';

const api = createAdminOperationsApi(client);
export function exportHistory() {
  const actor = session.get()?.admin?.id;
  return actor
    ? createExportHistory(localStorage, [
        environment.mode,
        environment.provider,
        environment.apiBaseUrl,
        actor,
      ])
    : null;
}
export function forgetExportTask(id: string) {
  exportHistory()?.remove(id);
}
export const adminOperationsApi = {
  ...api,
  async createExport(...args: Parameters<typeof api.createExport>) {
    const history = exportHistory();
    const generation = session.getGeneration();
    const task = await api.createExport(...args);
    if (generation === session.getGeneration()) history?.save(task);
    return task;
  },
  async getExportTask(id: string) {
    const history = exportHistory();
    const generation = session.getGeneration();
    const task = await api.getExportTask(id);
    if (generation === session.getGeneration()) history?.save(task);
    return task;
  },
};

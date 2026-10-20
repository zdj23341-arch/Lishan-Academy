import { ApiError } from '../http';
import { createPager } from '../pagination';
import type { CommerceApi } from './api';
import type { AddressWrite } from './types';

export function validateAddress(input: AddressWrite): AddressWrite {
  const body: AddressWrite = {
    version: input.version,
    name: input.name.trim(),
    phone: input.phone.trim(),
    provinceCode: input.provinceCode.trim(),
    cityCode: input.cityCode.trim(),
    districtCode: input.districtCode.trim(),
    detail: input.detail.trim(),
    isDefault: input.isDefault,
  };
  const errors: Record<string, string> = {};
  if (!body.name || body.name.length > 100) errors.name = '收货人应为1至100字';
  if (!/^1[3-9][0-9]{9}$/.test(body.phone)) errors.phone = '请输入有效大陆手机号';
  for (const key of ['provinceCode', 'cityCode', 'districtCode'] as const)
    if (!/^\d{6}$/.test(body[key])) errors[key] = '请选择完整地区';
  if (!body.detail || body.detail.length > 500) errors.detail = '详细地址应为1至500字';
  if (!Number.isSafeInteger(body.version) || body.version < 0)
    errors.version = '地址版本无效，请刷新';
  if (typeof body.isDefault !== 'boolean') errors.isDefault = '默认地址标记无效';
  if (Object.keys(errors).length)
    throw new ApiError('VALIDATION_ERROR', '请检查地址信息', null, errors);
  return body;
}
export function createAddressController(api: CommerceApi) {
  const pager = createPager((_filter: null, page) => api.addresses(page));
  const state = {
    saving: false,
    error: null as Error | null,
    fieldErrors: {} as Record<string, string>,
  };
  async function mutate<T>(action: () => Promise<T>): Promise<T> {
    if (state.saving) throw new Error('地址正在保存');
    state.saving = true;
    state.error = null;
    state.fieldErrors = {};
    try {
      const value = await action();
      await pager.reset(null);
      return value;
    } catch (error) {
      state.error = error instanceof Error ? error : new Error('地址保存失败');
      state.fieldErrors = error instanceof ApiError ? error.fieldErrors : {};
      throw state.error;
    } finally {
      state.saving = false;
    }
  }
  return {
    state,
    pager,
    save(input: AddressWrite, id?: string) {
      return mutate(() => {
        const body = validateAddress(input);
        return id ? api.updateAddress(id, body) : api.createAddress(body);
      });
    },
    remove(id: string) {
      return mutate(() => api.deleteAddress(id));
    },
  };
}

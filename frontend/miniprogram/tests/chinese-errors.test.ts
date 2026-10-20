// @vitest-environment jsdom
import { expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import AsyncState from '../src/components/AsyncState.vue';
import { ApiError } from '@jxsc/shared';
import { customerMessage } from '../src/utils/customer-text';

it('localizes authentication errors without losing the original diagnostic or replacing Chinese reasons', () => {
  const error = new ApiError('UNAUTHORIZED', 'Authentication required', 'request-1', {}, 401);
  expect(customerMessage(error, '加载失败')).toContain('登录');
  expect(error.message).toBe('Authentication required');
  expect(error.requestId).toBe('request-1');
  expect(error.status).toBe(401);
  expect(customerMessage(new ApiError('FORBIDDEN', 'Access denied'), '操作失败')).toContain('无权');
  expect(customerMessage(new ApiError('FORBIDDEN', '会员已过期，请续费'), '操作失败')).toBe(
    '会员已过期，请续费',
  );
});

it('keeps Chinese error details but gives English errors a Chinese recovery prompt', async () => {
  const wrapper = mount(AsyncState, { props: { loading: false, error: 'Service unavailable' } });
  expect(wrapper.text()).not.toContain('Service unavailable');
  expect(wrapper.text()).toContain('加载失败，请稍后重试');
  await wrapper.setProps({ error: '课程权益已过期，请续购' });
  expect(wrapper.text()).toContain('课程权益已过期，请续购');
  await wrapper.setProps({ error: '原提现结果尚未确认（PROCESSING），请继续查询原单' });
  expect(wrapper.text()).toContain('原提现结果尚未确认（处理中），请继续查询原单');
  expect(wrapper.text()).not.toContain('PROCESSING');
  wrapper.unmount();
});

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { ElButton } from 'element-plus';
import { formatFen } from '@jxsc/shared';
import type { AdminMembershipPlan } from '@jxsc/shared/learning/admin-types';
import { validateMembershipPlan } from '@jxsc/shared/learning/admin-validation';
import { learningApi } from '../../api/learning';
import { useLearningPage } from '../../composables/useLearningPage';
const items = ref<AdminMembershipPlan[]>([]),
  page = ref(1),
  total = ref(0),
  selected = ref<AdminMembershipPlan | null>(null),
  price = ref('');
const { allowed, busy, error, message, conflict, run, validate } = useLearningPage(() => {
  items.value = [];
  selected.value = null;
  price.value = '';
  page.value = 1;
  total.value = 0;
});
let requestedPage = 1;
async function load(next = 1) {
  requestedPage = next;
  await run(async (current) => {
    const result = await learningApi.membershipPlans({ page: next, pageSize: 20 });
    if (current()) {
      items.value = result.items;
      page.value = result.page;
      total.value = result.total;
    }
  });
}
function select(item: AdminMembershipPlan) {
  if (busy.value || !allowed.value) return;
  selected.value = { ...item };
  price.value = formatFen(item.priceFen);
  error.value = message.value = '';
  conflict.value = false;
}
async function reload() {
  if (!selected.value) return;
  const id = selected.value.id;
  await load(page.value);
  if (!error.value) {
    const item = items.value.find((item) => item.id === id);
    if (item) select(item);
    else selected.value = null;
  }
}
async function save() {
  if (!selected.value) return;
  await run(async (current) => {
    const { id, ...input } = selected.value!;
    const body = { ...input };
    validate(validateMembershipPlan(body));
    const value = await learningApi.saveMembershipPlan(id, body);
    if (!current()) return;
    selected.value = { ...value };
    price.value = formatFen(value.priceFen);
    items.value = items.value.map((item) => (item.id === value.id ? value : item));
  }, '会员计划已保存');
}
onMounted(() => load());
</script>
<template>
  <main class="ls-admin-page">
    <header class="page-header">
      <div>
        <h1>会员套餐</h1>
        <span class="page-desc">管理会员周期、启用状态和关联商品</span>
      </div>
      <router-link class="ls-button" to="/learning/membership/records">会员记录</router-link>
    </header>
    <p v-if="!allowed" role="alert">没有内容管理权限</p>
    <template v-else>
      <p class="plan-notice">
        修改周期或禁用前，请先下架关联商品，再保存计划，最后按需重新上架。售价与介绍在关联商品页维护。
      </p>
      <details class="plan-help">
        <summary>会员权益说明</summary>
        <p>修改仅影响新订单，已有订单快照保持不变。会员不解锁单独购买的课程。</p>
      </details>
      <p v-if="busy" role="status">处理中</p>
      <p v-if="error" role="alert">{{ error }}</p>
      <p v-if="message" role="status">{{ message }}</p>
      <section class="admin-card">
        <div class="admin-card__header">
          <h2>套餐列表</h2>
          <el-button :disabled="busy" @click="load(requestedPage)">{{
            error ? '重试' : '刷新计划列表'
          }}</el-button>
        </div>
        <div class="ls-table-scroll">
          <table>
            <thead>
              <tr>
                <th>周期</th>
                <th>价格（元）</th>
                <th>启用</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="item in items" :key="item.id">
                <td>{{ item.durationMonths }}个月</td>
                <td>{{ formatFen(item.priceFen) }}</td>
                <td>
                  <span class="ls-badge" :class="{ 'ls-badge--success': item.enabled }">{{
                    item.enabled ? '已启用' : '已停用'
                  }}</span>
                </td>
                <td><el-button :disabled="busy" @click="select(item)">编辑计划</el-button></td>
              </tr>
            </tbody>
          </table>
        </div>
        <p v-if="!items.length && !busy" class="empty-state">暂无会员计划，请先配置会员商品</p>
        <div class="pagination-bar">
          <span>共 {{ total }} 条 · 每页 20 条</span>
          <div class="pagination-actions">
            <el-button :disabled="busy || page <= 1" @click="load(page - 1)">上一页</el-button
            ><span>{{ page }} / {{ Math.max(1, Math.ceil(total / 20)) }}，共 {{ total }} 条</span
            ><el-button :disabled="busy || page * 20 >= total" @click="load(page + 1)"
              >下一页</el-button
            >
          </div>
        </div>
      </section>
      <form v-if="selected" @submit.prevent="save">
        <fieldset class="plan-editor" :disabled="busy">
          <legend>编辑会员计划</legend>
          <details>
            <summary>计划详情</summary>
            <p>商品编号：{{ selected.productId }} · 版本 {{ selected.version }}</p>
          </details>
          <div class="plan-editor-grid">
            <label
              >周期
              <select v-model="selected.durationMonths" name="durationMonths">
                <option :value="6">6个月</option>
                <option :value="12">12个月</option>
              </select></label
            >
            <div class="plan-price">
              <span>当前售价</span>
              <strong>{{ formatFen(selected.priceFen) }} 元</strong>
            </div>
          </div>
          <router-link
            class="plan-product-link"
            :to="`/content/products/${encodeURIComponent(selected.productId)}`"
            >维护商品售价与介绍 / 下架商品</router-link
          >
          <p v-if="conflict">版本已变化，本地输入已保留。请先复制需保留的修改，再重新读取。</p>
          <div class="plan-editor-footer">
            <label
              ><input v-model="selected.enabled" name="enabled" type="checkbox" />启用计划</label
            >
            <div class="plan-editor-actions">
              <el-button :disabled="busy" @click="reload">重新读取计划（丢弃本地修改）</el-button>
              <el-button type="primary" native-type="submit" :disabled="busy || conflict"
                >保存计划</el-button
              >
            </div>
          </div>
        </fieldset>
      </form>
    </template>
  </main>
</template>
<style scoped>
.plan-notice {
  padding: 12px 16px;
  background: var(--ls-tint);
  color: var(--ls-primary);
  border-radius: 8px;
  font-size: 13px;
}
.plan-help {
  margin-bottom: 18px;
  color: var(--ls-sub);
  font-size: 13px;
}
.plan-help summary {
  cursor: pointer;
}
.admin-card__header h2 {
  margin: 0;
}
table {
  min-width: 460px;
}
.plan-editor > details {
  margin-bottom: 16px;
}
.plan-editor-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 20px;
}
.plan-editor label {
  min-width: 0;
  margin: 0;
}
.plan-price {
  display: flex;
  flex-direction: column;
  gap: 7px;
  color: var(--ls-sub);
  font-size: 13px;
}
.plan-price strong {
  display: flex;
  align-items: center;
  min-height: 38px;
  color: var(--ls-ink);
  font-size: 18px;
}
.plan-product-link {
  display: inline-block;
  margin-top: 16px;
}
.plan-editor-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  margin-top: 20px;
  padding-top: 16px;
  border-top: 1px solid var(--ls-line);
}
.plan-editor-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.plan-editor-actions .el-button {
  margin: 0;
}
@media (max-width: 560px) {
  .plan-editor-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>

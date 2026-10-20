<script setup lang="ts">
import { formatShanghaiDateTime } from '@jxsc/shared';
import { onMounted, ref } from 'vue';
import { ElButton } from 'element-plus';
import type { AdminMembershipRecord } from '@jxsc/shared/learning/admin-types';
import { learningApi } from '../../api/learning';
import { useLearningPage } from '../../composables/useLearningPage';
const empty = () => ({ userId: '', status: '' as '' | 'NONE' | 'ACTIVE' | 'EXPIRED' | 'PENDING' });
const query = ref(empty()),
  items = ref<AdminMembershipRecord[]>([]),
  page = ref(1),
  total = ref(0);
const { allowed, busy, error, run } = useLearningPage(() => {
  query.value = empty();
  items.value = [];
  page.value = 1;
  total.value = 0;
}, 'ORDER_OPERATE');
let requestedPage = 1;
async function load(next = 1) {
  requestedPage = next;
  await run(async (current) => {
    const result = await learningApi.membershipRecords({
      page: next,
      pageSize: 20,
      ...(query.value.userId.trim() ? { userId: query.value.userId.trim() } : {}),
      ...(query.value.status ? { status: query.value.status } : {}),
    });
    if (current()) {
      items.value = result.items;
      page.value = result.page;
      total.value = result.total;
    }
  });
}
onMounted(() => load());
</script>
<template>
  <div class="page-container">
    <div class="page-header">
      <div class="header-left">
        <h2 class="page-title">会员记录</h2>
        <span class="page-desc">查询学员会员资格状态、有效期与权益版本</span>
      </div>
      <div class="header-actions">
        <router-link to="/" class="back-link">返回后台首页</router-link>
        <router-link to="/learning/membership/plans" class="plan-btn-link">
          <el-button type="primary">会员计划</el-button>
        </router-link>
        <el-button :disabled="busy" @click="load()">刷新列表</el-button>
      </div>
    </div>

    <div v-if="!allowed" class="alert-box alert-error" role="alert">没有订单操作权限</div>

    <template v-else>
      <form class="filter-card filter-form" @submit.prevent="load(1)">
        <label
          >用户ID<input
            name="userId"
            v-model="query.userId"
            :disabled="busy"
            inputmode="numeric"
            maxlength="20"
        /></label>
        <label
          >当前权益<select name="status" v-model="query.status" :disabled="busy">
            <option value="">全部</option>
            <option value="NONE">无权益</option>
            <option value="ACTIVE">已生效</option>
            <option value="EXPIRED">已到期</option>
            <option value="PENDING">未生效</option>
          </select></label
        >
        <button :disabled="busy">筛选</button
        ><button
          type="button"
          :disabled="busy"
          @click="
            query = empty();
            load(1);
          "
        >
          重置
        </button>
      </form>
      <div class="alert-box alert-info">
        <span class="info-icon">ℹ️</span>
        <span>本页展示服务器当前会员权益，只读。购买、续费与退款事件记录暂未提供。</span>
      </div>

      <div v-if="error" class="alert-box alert-warning" role="alert">
        <span>{{ error }}</span>
        <el-button size="small" type="primary" :disabled="busy" @click="load(requestedPage)"
          >重试</el-button
        >
      </div>

      <div class="table-card">
        <div v-if="busy" class="loading-bar" role="status">正在读取会员记录...</div>

        <div class="ls-table-scroll">
          <table class="modern-table">
            <thead>
              <tr>
                <th style="width: 25%">用户</th>
                <th style="width: 20%">级别</th>
                <th style="width: 35%">有效区间</th>
                <th style="width: 10%; text-align: right">状态</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="item in items" :key="item.id" class="table-row">
                <td>
                  <div class="user-meta">
                    <span class="user-avatar-tag"></span>
                    <div class="user-info">
                      <span class="user-id">{{ item.userId }}</span>
                      <details>
                        <summary>记录详情</summary>
                        <p>记录编号：{{ item.id }} · 版本：{{ item.version }}</p>
                      </details>
                    </div>
                  </div>
                </td>
                <td>
                  <span class="level-badge">
                    {{ item.level === 'STANDARD' || !item.level ? '标准会员' : item.level }}
                  </span>
                </td>
                <td>
                  <div class="validity-time-box">
                    <span class="time-range"
                      >{{ formatShanghaiDateTime(item.startsAt) }} —
                      {{ formatShanghaiDateTime(item.expiresAt) }}</span
                    >
                  </div>
                </td>
                <td style="text-align: right">
                  <span
                    class="status-tag"
                    :class="{
                      'status-active': item.status === 'ACTIVE',
                      'status-expired': item.status === 'EXPIRED',
                    }"
                  >
                    {{
                      { ACTIVE: '有效中', EXPIRED: '已过期', NONE: '无权益', PENDING: '未生效' }[
                        item.status
                      ] || item.status
                    }}
                  </span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <div v-if="!items.length && !busy" class="empty-state">
          <p>没有符合条件的会员记录</p>
        </div>

        <div class="pagination-bar">
          <span class="total-info">
            {{ page }} / {{ Math.max(1, Math.ceil(total / 20)) }}，共 {{ total }} 条
          </span>
          <div class="page-btns">
            <el-button size="small" :disabled="busy || page <= 1" @click="load(page - 1)"
              >上一页</el-button
            >
            <el-button size="small" :disabled="busy || page * 20 >= total" @click="load(page + 1)"
              >下一页</el-button
            >
          </div>
        </div>
      </div>
    </template>
  </div>
</template>
<style lang="scss" scoped>
.ls-row-cover {
  width: 42px;
  height: 54px;
  flex-shrink: 0;
}
.ls-row-cover :deep(.ls-cover-title) {
  font-size: 14px;
  line-height: 1.3;
  letter-spacing: 0;
}
.ls-row-cover :deep(.ls-cover-art) {
  padding: 7px;
}
.ls-row-cover :deep(.ls-cover-caption) {
  display: none;
}
.raw-status {
  font-size: 11px;
  opacity: 0.65;
}
.filter-form {
  width: 100%;
}
.filter-grid {
  width: 100%;
}
.form-row {
  margin: 18px 0;
}
.form-label {
  width: 100%;
}
.editor-fieldset {
  border: 0;
  padding: 0;
}
.editor-card {
  margin-top: 24px;
}
.article-meta {
  display: flex;
  align-items: center;
  gap: 12px;
}
.article-title {
  font-weight: 600;
}
.article-id {
  display: block;
  color: #617088;
  font-size: 12px;
}
.goods-info {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 6px;
}
.goods-qty {
  font-size: 12px;
  color: #617088;
}
.amount {
  font-weight: 600;
}
.btn {
  display: inline-flex;
  gap: 8px;
  align-items: center;
  text-decoration: none;
}
.btn--primary {
  background: #2855b7;
  color: #fff;
  border-color: #2855b7;
}
.col-checkbox {
  min-width: 36px;
}
.action-link {
  margin-right: 10px;
}
.error-icon {
  width: 18px;
  height: 18px;
}
.error-bar {
  display: flex;
  align-items: center;
  gap: 12px;
}
.dirty-badge {
  font-size: 12px;
  color: #617088;
}
.dirty-badge--active {
  color: #976014;
}
.alert-box {
  padding: 12px 14px;
  border-radius: 8px;
  margin: 12px 0;
}
.alert-info {
  background: #edf3ff;
  color: #2855b7;
}
.info-icon {
  width: 18px;
  height: 18px;
}
</style>

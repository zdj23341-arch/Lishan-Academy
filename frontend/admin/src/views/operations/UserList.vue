<script setup lang="ts">
import { formatShanghaiDateTime } from '@jxsc/shared';
import { onMounted } from 'vue';
import { ElMessageBox } from 'element-plus';
import { useUserDirectory } from '../../composables/useUserDirectory';
const membershipLabels: Record<string, string> = {
  ACTIVE: '会员有效',
  EXPIRED: '会员已到期',
  PENDING: '会员未生效',
  NONE: '无会员权益',
};
const {
  allowed,
  loading,
  updating,
  list,
  total,
  page,
  pageSize,
  keyword,
  statusFilter,
  error,
  message,
  loadData,
  handleSearch,
  handlePageChange,
  handleSizeChange,
  handleToggleStatus,
} = useUserDirectory({
  askReason: async (row, action) => {
    const { value } = await ElMessageBox.prompt(
      `请输入将用户【${row.nickname || row.id}】${action}的原因：`,
      '用户状态变更',
      {
        confirmButtonText: `确认${action}`,
        cancelButtonText: '取消',
        inputPattern: /\S+/,
        inputErrorMessage: '原因不能为空',
      },
    );
    return value;
  },
});
onMounted(loadData);
</script>

<template>
  <div class="admin-layout">
    <header class="admin-header">
      <div class="header-brand">
        <router-link to="/" class="back-link">← 返回工作台</router-link>
        <h1 class="brand-title">用户中心</h1>
      </div>
    </header>

    <main class="admin-content">
      <p v-if="!allowed" role="alert">没有用户管理权限</p>
      <p v-if="error" role="alert">{{ error }}</p>
      <p v-if="message" role="status">{{ message }}</p>
      <div class="admin-card filter-card">
        <div class="filter-row">
          <el-input
            v-model="keyword"
            placeholder="搜索用户ID / 昵称"
            clearable
            size="small"
            style="width: 240px"
            @keyup.enter="handleSearch"
          />
          <el-select
            v-model="statusFilter"
            placeholder="账号状态"
            clearable
            size="small"
            style="width: 140px"
          >
            <el-option label="正常" value="NORMAL" />
            <el-option label="已封禁" value="BLOCKED" /><el-option label="已注销" value="CLOSED" />
          </el-select>
          <el-button type="primary" size="small" @click="handleSearch">查询</el-button>
        </div>
      </div>

      <div class="admin-card">
        <el-table :data="list" v-loading="loading" stripe style="width: 100%">
          <el-table-column prop="id" label="用户ID" width="120" />
          <el-table-column prop="username" label="登录账号" width="140" />
          <el-table-column prop="nickname" label="昵称" width="140" />
          <el-table-column prop="phone" label="联系手机" width="130" />
          <el-table-column label="当前会员" min-width="140">
            <template #default="{ row }">
              {{
                row.membership
                  ? membershipLabels[row.membership.status] || row.membership.status
                  : '暂无权益数据'
              }}
            </template>
          </el-table-column>
          <el-table-column label="账号状态" width="120">
            <template #default="{ row }">
              <el-tag v-if="row.status === 'NORMAL'" type="success">正常</el-tag>
              <el-tag v-else-if="row.status === 'BLOCKED'" type="danger">已封禁</el-tag
              ><el-tag v-else>已注销</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="registeredAt" label="注册时间" width="160">
            <template #default="{ row }">
              {{ formatShanghaiDateTime(row.registeredAt) }}
            </template>
          </el-table-column>
          <el-table-column prop="lastLoginAt" label="最近登录" width="160">
            <template #default="{ row }">
              {{ formatShanghaiDateTime(row.lastLoginAt) }}
            </template>
          </el-table-column>
          <el-table-column label="操作" min-width="120">
            <template #default="{ row }">
              <el-button
                size="small"
                :type="row.status === 'NORMAL' ? 'danger' : 'success'"
                link
                v-if="row.status !== 'CLOSED'"
                :disabled="updating || !allowed"
                @click="handleToggleStatus(row)"
              >
                {{ row.status === 'NORMAL' ? '封禁账号' : '解除封禁' }}
              </el-button>
            </template>
          </el-table-column>
        </el-table>

        <div style="display: flex; justify-content: flex-end; margin-top: 16px">
          <el-pagination
            v-model:current-page="page"
            v-model:page-size="pageSize"
            :total="total"
            :page-sizes="[10, 20, 50]"
            layout="total, sizes, prev, pager, next, jumper"
            @size-change="handleSizeChange"
            @current-change="handlePageChange"
          />
        </div>
      </div>
    </main>
  </div>
</template>

<style lang="scss" scoped>
.back-link {
  font-size: 13px;
  color: #6b7280;
  text-decoration: none;
  &:hover {
    color: #2855b7;
  }
}
.filter-card {
  padding: 16px 20px;
  margin-bottom: 16px;
  .filter-row {
    display: flex;
    align-items: center;
    gap: 12px;
  }
}
</style>

<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { adminDistributionApi } from '../../api/distribution';
import type { AdminDistributor, DistributorStatus, ProductDistributorRule } from '@jxsc/shared';
import { parsePercent, validateDistributionRules } from '@jxsc/shared';

const loading = ref(false);
const list = ref<AdminDistributor[]>([]);
const total = ref(0);
const page = ref(1);
const pageSize = ref(20);

// 指定新分销员弹窗
const showAssignDialog = ref(false);
const assignUserId = ref('');
const assignReason = ref('运营特约指派');
const assigning = ref(false);

// 商品分销规则弹窗
const showRuleDialog = ref(false);
const currentProductId = ref('');
const loadedProductId = ref<string | null>(null);
interface EditableRule {
  distributorId: string;
  distributorName: string;
  ratePercent: string;
  enabled: boolean;
  effectiveAt?: string | null;
  expiresAt?: string | null;
  ruleVersion?: number;
}
const editableRules = ref<EditableRule[]>([]);
const currentProductVersion = ref(1);
const ruleUpdateReason = ref('更新商品分销配置');
const savingRules = ref(false);

async function loadData() {
  loading.value = true;
  try {
    const res = await adminDistributionApi.listDistributors({
      page: page.value,
      pageSize: pageSize.value,
    });
    list.value = res.items;
    total.value = res.total;
  } catch (e) {
    ElMessage.error((e instanceof Error ? e.message : '') || '加载分销员列表失败');
  } finally {
    loading.value = false;
  }
}

function handlePageChange(p: number) {
  page.value = p;
  loadData();
}

function handleSizeChange(size: number) {
  pageSize.value = size;
  page.value = 1;
  loadData();
}

async function handleAssign() {
  if (!assignUserId.value.trim()) {
    ElMessage.warning('请输入注册用户的用户ID');
    return;
  }
  assigning.value = true;
  try {
    await adminDistributionApi.assignDistributor(
      assignUserId.value.trim(),
      assignReason.value.trim() || '运营特约指派',
    );
    ElMessage.success('指定分销员成功');
    showAssignDialog.value = false;
    assignUserId.value = '';
    await loadData();
  } catch (e) {
    ElMessage.error((e instanceof Error ? e.message : '') || '指定分销员失败');
  } finally {
    assigning.value = false;
  }
}

async function handleStatusChange(
  row: AdminDistributor,
  nextStatus: DistributorStatus,
  riskBlocked = false,
) {
  try {
    const actionLabel = riskBlocked
      ? '风险冻结'
      : nextStatus === 'ACTIVE'
        ? '恢复推广'
        : '停用推广';
    const { value: reason } = await ElMessageBox.prompt(
      `请输入将分销员【${row.nickname || row.username || row.userId || row.id}】执行【${actionLabel}】的原因：`,
      '状态变更理由',
      {
        confirmButtonText: '确定变更',
        cancelButtonText: '取消',
        inputPattern: /\S+/,
        inputErrorMessage: '原因不能为空',
      },
    );

    await adminDistributionApi.updateDistributorStatus(
      row.id,
      nextStatus,
      reason,
      row.version,
      riskBlocked,
    );
    ElMessage.success('状态变更成功');
    await loadData();
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error((e instanceof Error ? e.message : '') || '状态变更失败');
    }
  }
}

function openRuleDialog() {
  showRuleDialog.value = true;
  currentProductId.value = '';
  loadedProductId.value = null;
  editableRules.value = [];
}
async function openRuleConfig() {
  const productId = currentProductId.value.trim();
  loadedProductId.value = null;
  editableRules.value = [];
  if (!productId) {
    ElMessage.warning('请输入真实商品ID');
    return;
  }
  savingRules.value = true;
  try {
    const res = await adminDistributionApi.getProductRules(productId);
    if (currentProductId.value.trim() !== productId || !showRuleDialog.value) return;
    if (String(res.productId) !== productId) throw new Error('商品规则归属不一致，请重新加载');
    loadedProductId.value = productId;
    currentProductVersion.value = res.version;
    editableRules.value = (res.rules || []).map((r) => ({
      ...r,
      distributorName: r.distributorName || `分销员 ${r.distributorId}`,
      ratePercent: (r.rateBps / 100).toFixed(2),
    }));
  } catch (cause) {
    ElMessage.error(cause instanceof Error ? cause.message : '加载商品分销规则失败');
  } finally {
    savingRules.value = false;
  }
}

function addRuleRow() {
  const defaultDist =
    list.value.find((d) => !editableRules.value.some((r) => r.distributorId === d.id)) ||
    list.value[0];
  editableRules.value.push({
    distributorId: defaultDist ? defaultDist.id : '',
    distributorName: defaultDist
      ? defaultDist.nickname || defaultDist.username || defaultDist.userId
      : '分销顾问',
    ratePercent: '15.00',
    enabled: true,
  });
}

function removeRuleRow(index: number) {
  editableRules.value.splice(index, 1);
}

function onDistributorSelect(row: EditableRule, id: string) {
  row.distributorId = id;
  const found = list.value.find((d) => d.id === id);
  if (found) {
    row.distributorName = found.nickname || found.username || found.userId;
  }
}

async function saveRules() {
  if (!loadedProductId.value || loadedProductId.value !== currentProductId.value.trim()) {
    ElMessage.warning('目标商品已变化，请先加载该商品规则');
    return;
  }
  const targetProductId = loadedProductId.value;
  const targetVersion = currentProductVersion.value;
  if (editableRules.value.length === 0) {
    ElMessage.warning('请至少保留或配置一条分销规则，若无需分销可将规则设置为停用');
    return;
  }
  const rules: ProductDistributorRule[] = [];
  for (const item of editableRules.value) {
    if (!item.distributorId.trim()) {
      ElMessage.error('分销员ID不能为空');
      return;
    }
    let rateBps: number;
    try {
      rateBps = parsePercent(item.ratePercent);
    } catch {
      ElMessage.error(`分销员【${item.distributorName || item.distributorId}】的佣金比例格式无效`);
      return;
    }
    rules.push({
      distributorId: item.distributorId.trim(),
      distributorName: item.distributorName || '分销顾问',
      rateBps,
      enabled: item.enabled,
      effectiveAt: item.effectiveAt,
      expiresAt: item.expiresAt,
    });
  }

  const err = validateDistributionRules(rules);
  if (err) {
    ElMessage.error(err);
    return;
  }

  savingRules.value = true;
  try {
    await adminDistributionApi.updateProductRules(
      targetProductId,
      rules,
      targetVersion,
      ruleUpdateReason.value.trim() || '更新商品分销配置',
    );
    ElMessage.success('商品佣金规则已更新（仅影响新产生订单）');
    showRuleDialog.value = false;
  } catch (e) {
    ElMessage.error((e instanceof Error ? e.message : '') || '更新规则失败');
  } finally {
    savingRules.value = false;
  }
}

onMounted(() => {
  loadData();
});
</script>

<template>
  <div class="admin-layout">
    <header class="admin-header">
      <div class="header-brand">
        <router-link to="/" class="back-link">← 返回工作台</router-link>
        <h1 class="brand-title">分销员管理</h1>
      </div>
      <div style="display: flex; gap: 10px">
        <el-button type="success" size="small" @click="openRuleDialog">配置商品佣金规则</el-button>
        <el-button type="primary" size="small" @click="showAssignDialog = true"
          >+ 指定特约分销员</el-button
        >
      </div>
    </header>

    <main class="admin-content">
      <p>按完整分页展示分销员；当前接口暂不支持关键词与状态筛选。</p>

      <div class="admin-card">
        <el-table :data="list" v-loading="loading" stripe style="width: 100%">
          <el-table-column label="分销员" min-width="180"
            ><template #default="{ row }"
              >{{ row.nickname || row.username || row.userId
              }}<small class="ls-muted"> · {{ row.id }}</small></template
            ></el-table-column
          >
          <el-table-column label="收款身份" width="110"
            ><template #default="{ row }">{{
              row.receiptReady ? '已绑定' : '未绑定'
            }}</template></el-table-column
          >
          <el-table-column label="更多资料" min-width="160"
            ><template #default="{ row }"
              ><details class="ls-secondary-details">
                <summary>查看资料与资金</summary>
                <p>用户编号：{{ row.userId }} · 版本：{{ row.version }}</p>
                <p>
                  登录账号：{{ row.username || '未提供' }} · 联系手机：{{ row.phone || '未提供' }}
                </p>
                <p>
                  产生总佣金：{{
                    row.totalCommissionFen == null
                      ? '接口未提供'
                      : '¥' + (row.totalCommissionFen / 100).toFixed(2)
                  }}
                </p>
                <p>
                  已提现出款：{{
                    row.withdrawnFen == null
                      ? '接口未提供'
                      : '¥' + (row.withdrawnFen / 100).toFixed(2)
                  }}
                </p>
              </details></template
            ></el-table-column
          >
          <el-table-column label="推广状态" width="120">
            <template #default="{ row }">
              <el-tag v-if="row.status === 'ACTIVE'" type="success">正常推广</el-tag>
              <el-tag v-else type="info">已停用</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="风控状态" width="120">
            <template #default="{ row }">
              <el-tag v-if="row.riskBlocked" type="danger">风险冻结</el-tag>
              <el-tag v-else type="success">正常</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" min-width="220">
            <template #default="{ row }">
              <el-button
                v-if="row.status !== 'ACTIVE'"
                size="small"
                type="success"
                link
                @click="handleStatusChange(row, 'ACTIVE', !!row.riskBlocked)"
                >启用推广</el-button
              >
              <el-button
                v-if="row.status === 'ACTIVE'"
                size="small"
                type="warning"
                link
                @click="handleStatusChange(row, 'DISABLED', !!row.riskBlocked)"
                >停用推广</el-button
              >
              <el-button
                v-if="!row.riskBlocked"
                size="small"
                type="danger"
                link
                @click="handleStatusChange(row, row.status, true)"
                >风险冻结</el-button
              >
              <el-button
                v-else
                size="small"
                type="primary"
                link
                @click="handleStatusChange(row, row.status, false)"
                >解除冻结</el-button
              >
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

    <!-- 指定新分销员弹窗 -->
    <el-dialog v-model="showAssignDialog" title="指定注册用户为分销员" width="460px">
      <p style="font-size: 13px; color: #6b7280; margin-bottom: 12px">
        仅支持指定平台已注册用户，输入其用户系统 ID：
      </p>
      <el-form label-width="80px">
        <el-form-item label="用户ID">
          <el-input v-model="assignUserId" placeholder="请输入用户 ID，例如 user-101" />
        </el-form-item>
        <el-form-item label="指派原因">
          <el-input v-model="assignReason" placeholder="请输入指派理由" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showAssignDialog = false">取消</el-button>
        <el-button type="primary" :loading="assigning" @click="handleAssign">确认指定</el-button>
      </template>
    </el-dialog>

    <!-- 商品分销多规则配置弹窗 -->
    <el-dialog v-model="showRuleDialog" title="配置商品分销佣金规则" width="680px">
      <div style="display: flex; align-items: center; gap: 10px; margin-bottom: 14px">
        <span style="font-size: 14px; font-weight: 500">目标商品ID：</span>
        <el-input v-model="currentProductId" placeholder="请输入真实商品ID" style="width: 220px" />
        <el-button size="small" type="primary" :loading="savingRules" @click="openRuleConfig"
          >加载商品规则</el-button
        >
        <span style="font-size: 12px; color: #9ca3af"
          >已加载商品: {{ loadedProductId || '尚未加载' }}；规则版本: v{{
            currentProductVersion
          }}</span
        >
      </div>
      <p style="font-size: 12px; color: #6b7280; margin-bottom: 12px; line-height: 1.5">
        注意：修改规则仅对保存后产生的新订单生效，不影响历史订单。各分销员比例总和不可超过
        100%，单分销员比例介于 0% ~ 100% 之间。
      </p>

      <el-table :data="editableRules" size="small" border style="width: 100%; margin-bottom: 14px">
        <el-table-column label="分销员" min-width="180">
          <template #default="{ row }">
            <el-select
              :model-value="row.distributorId"
              filterable
              placeholder="选择分销员"
              style="width: 100%"
              @change="(val: string) => onDistributorSelect(row, val)"
            >
              <el-option
                v-for="d in list"
                :key="d.id"
                :label="`${d.nickname || d.username || d.userId} (${d.id})`"
                :value="d.id"
              />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="佣金比例 (%)" width="140">
          <template #default="{ row }">
            <el-input v-model="row.ratePercent" placeholder="例如 15.00" size="small">
              <template #append>%</template>
            </el-input>
          </template>
        </el-table-column>
        <el-table-column label="启用状态" width="100" align="center">
          <template #default="{ row }">
            <el-switch v-model="row.enabled" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="80" align="center">
          <template #default="{ $index }">
            <el-button type="danger" link size="small" @click="removeRuleRow($index)"
              >移除</el-button
            >
          </template>
        </el-table-column>
      </el-table>

      <div
        style="
          display: flex;
          justify-content: space-between;
          align-items: center;
          margin-bottom: 14px;
        "
      >
        <el-button type="primary" plain size="small" @click="addRuleRow"
          >+ 添加分销员规则</el-button
        >
        <div style="display: flex; align-items: center; gap: 8px">
          <span style="font-size: 13px; color: #4b5563">修改原因:</span>
          <el-input
            v-model="ruleUpdateReason"
            placeholder="必填理由"
            size="small"
            style="width: 220px"
          />
        </div>
      </div>

      <template #footer>
        <el-button @click="showRuleDialog = false">取消</el-button>
        <el-button
          type="primary"
          :loading="savingRules"
          :disabled="!loadedProductId || loadedProductId !== currentProductId.trim()"
          @click="saveRules"
          >保存生效</el-button
        >
      </template>
    </el-dialog>
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

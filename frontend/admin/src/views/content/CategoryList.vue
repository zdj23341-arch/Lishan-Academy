<script setup lang="ts">
import { uiStatus } from '../../components/ui/labels';
import { onMounted, ref } from 'vue';
import { ElButton } from 'element-plus';
import type { AdminCategory, CategoryWrite } from '@jxsc/shared/content/admin-types';
import { contentApi } from '../../api/content';
import { useContentEditor } from '../../composables/useContentEditor';
const { busy, error, message, run } = useContentEditor();
const items = ref<AdminCategory[]>([]),
  page = ref(1),
  total = ref(0),
  keyword = ref(''),
  type = ref('');
const editing = ref<string | null>(null),
  form = ref<CategoryWrite | null>(null);
async function query(next: number) {
  const result = await contentApi.categories({
    keyword: keyword.value,
    type: type.value || undefined,
    page: next,
    pageSize: 20,
  });
  items.value = result.items;
  total.value = result.total;
  page.value = result.page;
}
async function load(next = 1) {
  await run(() => query(next));
}
function edit(item?: AdminCategory) {
  editing.value = item?.id ?? null;
  form.value = item
    ? {
        version: item.version,
        name: item.name,
        type: item.type,
        parentId: item.parentId,
        sortNo: item.sortNo,
        enabled: item.enabled,
      }
    : { version: 0, name: '', type: 'BOOK', parentId: null, sortNo: 0, enabled: true };
}
async function save() {
  if (!form.value) return;
  if (!form.value.name.trim()) {
    error.value = '请填写分类名称';
    return;
  }
  await run(async () => {
    const value = await contentApi.saveCategory(editing.value, {
      ...form.value!,
      name: form.value!.name.trim(),
      parentId: form.value!.parentId || null,
    });
    edit(value);
    await query(page.value);
  }, '分类已保存');
}
onMounted(() => load());
</script>
<template>
  <main class="ls-admin-page">
    <header class="page-header">
      <div>
        <h1>分类管理</h1>
        <span class="page-desc">管理商品分类、层级和展示顺序</span>
      </div>
      <el-button type="primary" :disabled="busy" @click="edit()">新建分类</el-button>
    </header>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="message" role="status">{{ message }}</p>
    <section class="admin-card">
      <form class="filter-form" @submit.prevent="load()">
        <label>名称 <input v-model="keyword" /></label
        ><label
          >商品类型
          <select v-model="type">
            <option value="">全部</option>
            <option value="BOOK">图书</option>
            <option value="COURSE">课程</option>
            <option value="MEMBERSHIP">会员</option>
          </select></label
        ><el-button type="primary" native-type="submit" :disabled="busy">筛选</el-button>
      </form>
      <div class="ls-table-scroll">
        <table>
          <thead>
            <tr>
              <th>名称 / ID</th>
              <th>类型</th>
              <th>上级</th>
              <th>排序</th>
              <th>启用</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in items" :key="item.id">
              <td>{{ item.name }} / {{ item.id }}</td>
              <td>{{ uiStatus(item.type) }}</td>
              <td>{{ item.parentId || '无' }}</td>
              <td>{{ item.sortNo }}</td>
              <td>{{ item.enabled ? '是' : '否' }}</td>
              <td><el-button :disabled="busy" @click="edit(item)">编辑</el-button></td>
            </tr>
          </tbody>
        </table>
      </div>
      <p v-if="!items.length && !busy" class="empty-state">没有匹配分类</p>
      <div class="pagination-bar">
        <span>共 {{ total }} 条 · 每页 20 条</span>
        <div class="pagination-actions">
          <el-button :disabled="busy || page <= 1" @click="load(page - 1)">上一页</el-button
          ><span>{{ page }} 页，共 {{ total }} 条</span
          ><el-button :disabled="busy || page * 20 >= total" @click="load(page + 1)"
            >下一页</el-button
          >
        </div>
      </div>
    </section>
    <form v-if="form" @submit.prevent="save">
      <fieldset class="category-editor" :disabled="busy">
        <legend>{{ editing ? '编辑分类' : '新建分类' }}</legend>
        <div class="category-editor-grid">
          <label>分类名称 <input v-model="form.name" maxlength="100" required /></label>
          <label
            >类型
            <select v-model="form.type">
              <option value="BOOK">图书</option>
              <option value="COURSE">课程</option>
              <option value="MEMBERSHIP">会员</option>
            </select></label
          >
          <label>上级分类 ID <input v-model="form.parentId" /></label>
          <label>排序 <input v-model.number="form.sortNo" type="number" step="1" /></label>
        </div>
        <div class="category-editor-footer">
          <label><input v-model="form.enabled" type="checkbox" />启用分类</label>
          <div class="category-editor-actions">
            <el-button @click="form = null">关闭编辑</el-button>
            <el-button type="primary" native-type="submit" :disabled="busy">保存分类</el-button>
          </div>
        </div>
      </fieldset>
    </form>
  </main>
</template>
<style scoped>
.filter-form {
  margin-bottom: 18px;
}
.filter-form label {
  margin: 0;
}
table {
  min-width: 600px;
}
.category-editor-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
}
.category-editor label {
  min-width: 0;
  margin: 0;
}
.category-editor-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  margin-top: 20px;
  padding-top: 16px;
  border-top: 1px solid var(--ls-line);
}
.category-editor-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.category-editor-actions .el-button {
  margin: 0;
}
@media (max-width: 900px) {
  .category-editor-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (max-width: 560px) {
  .category-editor-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>

<template>
  <j-page-container>
    <pro-search :columns="columns" target="numeric-rule" @search="params = $event" />
    <FullPage :fixed="false">
      <JProTable
        ref="tableRef"
        :columns="columns"
        :request="queryNumericRules"
        :defaultParams="{ sorts: [{ name: 'createTime', order: 'desc' }] }"
        :params="params"
        modeValue="TABLE"
      >
        <template #headerLeftRender>
          <j-permission-button
            type="primary"
            hasPermission="rule-engine/Scene:add"
            @click="handleAdd"
          >
            <template #icon><AIcon type="PlusOutlined" /></template>
            新增数值告警规则
          </j-permission-button>
        </template>

        <template #product="slotProps">
          <j-ellipsis>
            {{ slotProps.trigger?.device?.productId || '--' }}
          </j-ellipsis>
        </template>

        <template #devices="slotProps">
          <j-ellipsis>
            {{ deviceText(slotProps) }}
          </j-ellipsis>
        </template>

        <template #conditions="slotProps">
          <j-ellipsis :tooltip="{ title: conditionText(slotProps) }">
            {{ conditionText(slotProps) || '--' }}
          </j-ellipsis>
        </template>

        <template #state="slotProps">
          <JBadgeStatus
            :status="slotProps.state?.value"
            :text="slotProps.state?.text"
            :statusNames="{ started: 'processing', disable: 'error' }"
          />
        </template>

        <template #action="slotProps">
          <a-space :size="16">
            <j-permission-button
              type="link"
              style="padding: 0"
              hasPermission="rule-engine/Scene:view"
              tooltip="编辑"
              @click="handleEdit(slotProps)"
            >
              <template #icon><AIcon type="EditOutlined" /></template>
            </j-permission-button>
            <j-permission-button
              type="link"
              style="padding: 0"
              hasPermission="rule-engine/Scene:save"
              :popConfirm="{
                title: slotProps.state?.value === 'disable' ? '确认启用规则？' : '确认停用规则？',
                onConfirm: () => toggleState(slotProps),
              }"
            >
              <template #icon>
                <AIcon :type="slotProps.state?.value === 'disable' ? 'CheckCircleOutlined' : 'StopOutlined'" />
              </template>
            </j-permission-button>
            <j-permission-button
              type="link"
              danger
              style="padding: 0"
              hasPermission="rule-engine/Scene:delete"
              :disabled="slotProps.state?.value !== 'disable'"
              :popConfirm="{ title: '规则停用后才能删除，确认删除？', onConfirm: () => removeRule(slotProps.id) }"
            >
              <template #icon><AIcon type="DeleteOutlined" /></template>
            </j-permission-button>
          </a-space>
        </template>
      </JProTable>
    </FullPage>
    <SaveModal v-if="visible" :data="current" @close="handleClose" @saved="handleSaved" />
  </j-page-container>
</template>

<script setup lang="ts" name="NumericRule">
import { onlyMessage } from '@jetlinks-web/utils';
import { _action, _delete } from '../../../api/scene';
import { queryNumericRules } from '../../../api/numericRule';
import SaveModal from './SaveModal.vue';

const params = ref<Record<string, any>>({});
const tableRef = ref<Record<string, any>>({});
const visible = ref(false);
const current = ref<Record<string, any>>({});

const columns = [
  {
    title: '规则名称',
    dataIndex: 'name',
    key: 'name',
    width: 240,
    ellipsis: true,
    search: { type: 'string' },
  },
  {
    title: '产品',
    dataIndex: 'product',
    key: 'product',
    scopedSlots: true,
    width: 220,
  },
  {
    title: '设备范围',
    dataIndex: 'devices',
    key: 'devices',
    scopedSlots: true,
    width: 180,
  },
  {
    title: '数值条件',
    dataIndex: 'conditions',
    key: 'conditions',
    scopedSlots: true,
    ellipsis: true,
  },
  {
    title: '状态',
    dataIndex: 'state',
    key: 'state',
    scopedSlots: true,
    width: 120,
    search: {
      type: 'select',
      options: [
        { label: '启用', value: 'started' },
        { label: '禁用', value: 'disable' },
      ],
    },
  },
  {
    title: '操作',
    key: 'action',
    scopedSlots: true,
    fixed: 'right',
    width: 150,
  },
];

const operatorText: Record<string, string> = {
  eq: '等于',
  neq: '不等于',
  gt: '大于',
  gte: '大于等于',
  lt: '小于',
  lte: '小于等于',
  btw: '区间内',
  nbtw: '区间外',
};

const valueText = (value: any) => {
  const actual = value && typeof value === 'object' && 'value' in value ? value.value : value;
  return Array.isArray(actual) ? actual.join(' ~ ') : actual ?? '';
};

const conditionText = (rule: any) => {
  const branch = (rule?.branches || []).find((item: any) =>
    (item?.then || []).some((thenItem: any) =>
      (thenItem?.actions || []).some((action: any) => action?.alarm?.mode === 'trigger'),
    ),
  );
  const group = findUserConditionGroup(branch?.when || []);
  return (group?.terms || [])
    .map((term: any) => `${term.column || '--'} ${operatorText[term.termType] || term.termType || '--'} ${valueText(term.value)}`)
    .join(group?.type === 'or' ? ' 或 ' : ' 且 ');
};

const isPresenceGuard = (term: any) =>
  term?.termType === 'notnull' && String(term?.column || '').startsWith('properties.');

const findUserConditionGroup = (terms: any[]): any => {
  for (const item of terms) {
    if (!Array.isArray(item?.terms) || !item.terms.length) continue;
    const nested = findUserConditionGroup(item.terms);
    if (nested) return nested;
    if (item.terms.some((term: any) => !isPresenceGuard(term))) return item;
  }
  return undefined;
};

const deviceText = (rule: any) => {
  const device = rule?.trigger?.device;
  if (!device || device.selector !== 'fixed') return '全部设备';
  const values = device.selectorValues || [];
  return values.length ? `${values.length} 台设备` : '全部设备';
};

const handleAdd = () => {
  current.value = {};
  visible.value = true;
};

const handleEdit = (rule: Record<string, any>) => {
  current.value = rule;
  visible.value = true;
};

const handleClose = () => {
  visible.value = false;
  current.value = {};
};

const handleSaved = () => {
  handleClose();
  tableRef.value?.reload?.();
};

const toggleState = async (rule: any) => {
  const next = rule.state?.value === 'disable' ? '_enable' : '_disable';
  const response = await _action(rule.id, next);
  if (response?.status === 200 || response?.success) {
    onlyMessage('操作成功');
    tableRef.value?.reload?.();
  } else {
    onlyMessage(response?.message || '操作失败', 'error');
  }
};

const removeRule = async (id: string) => {
  const response = await _delete(id);
  if (response?.status === 200 || response?.success) {
    onlyMessage('删除成功');
    tableRef.value?.reload?.();
  } else {
    onlyMessage(response?.message || '删除失败', 'error');
  }
};

</script>

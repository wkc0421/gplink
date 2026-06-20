<template>
  <j-page-container>
    <pro-search
      :columns="columns"
      target="search-numeric-alarm-rule"
      @search="handleSearch"
    />
    <JProTable
      ref="tableRef"
      :columns="columns"
      :request="queryRules"
      :params="params"
      :defaultParams="{
        sorts: [{ name: 'createTime', order: 'desc' }],
      }"
    >
      <template #headerLeftRender>
        <j-permission-button
          type="primary"
          @click="openSave()"
          hasPermission="rule-engine/Alarm/NumericRule:add"
        >
          <template #icon><AIcon type="PlusOutlined" /></template>
          新增规则
        </j-permission-button>
      </template>

      <template #alarmConfigId="record">
        <j-ellipsis>{{ alarmName(record.alarmConfigId) || record.alarmConfigId }}</j-ellipsis>
      </template>

      <template #productId="record">
        <j-ellipsis>{{ productName(record.productId) || record.productId }}</j-ellipsis>
      </template>

      <template #selector="record">
        {{ record.selector?.value === 'fixed' || record.selector === 'fixed' ? '指定设备' : '全部设备' }}
      </template>

      <template #condition="record">
        <j-ellipsis style="max-width: 360px">
          {{ conditionText(record.condition) }}
        </j-ellipsis>
      </template>

      <template #state="record">
        <j-badgeStatus
          :text="stateValue(record.state) === 'enabled' ? '启用' : '禁用'"
          :status="stateValue(record.state)"
          :statusNames="{ enabled: 'processing', disabled: 'error' }"
        />
      </template>

      <template #action="record">
        <a-space :size="14">
          <j-permission-button
            type="link"
            style="padding: 0"
            @click="openSave(record)"
            hasPermission="rule-engine/Alarm/NumericRule:update"
          >
            <template #icon><AIcon type="EditOutlined" /></template>
          </j-permission-button>
          <j-permission-button
            type="link"
            style="padding: 0"
            :popConfirm="{
              title: stateValue(record.state) === 'enabled' ? '确认禁用该规则？' : '确认启用该规则？',
              onConfirm: () => toggleState(record),
            }"
            hasPermission="rule-engine/Alarm/NumericRule:action"
          >
            <template #icon>
              <AIcon :type="stateValue(record.state) === 'enabled' ? 'StopOutlined' : 'CheckCircleOutlined'" />
            </template>
          </j-permission-button>
          <j-permission-button
            type="link"
            danger
            style="padding: 0"
            :popConfirm="{
              title: '确认删除该规则？',
              onConfirm: () => deleteRule(record),
            }"
            hasPermission="rule-engine/Alarm/NumericRule:delete"
          >
            <template #icon><AIcon type="DeleteOutlined" /></template>
          </j-permission-button>
        </a-space>
      </template>
    </JProTable>

    <a-drawer
      v-model:open="drawerVisible"
      :width="780"
      :title="form.id ? '编辑数值告警规则' : '新增数值告警规则'"
      destroyOnClose
      @close="closeDrawer"
    >
      <a-form ref="formRef" layout="vertical" :model="form" :rules="rules">
        <a-row :gutter="16">
          <a-col :span="12">
            <a-form-item label="规则名称" name="name">
              <a-input v-model:value="form.name" :maxlength="64" placeholder="请输入规则名称" />
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item label="状态" name="state">
              <a-radio-group v-model:value="form.state">
                <a-radio-button value="enabled">启用</a-radio-button>
                <a-radio-button value="disabled">禁用</a-radio-button>
              </a-radio-group>
            </a-form-item>
          </a-col>
        </a-row>

        <a-row :gutter="16">
          <a-col :span="12">
            <a-form-item label="告警配置" name="alarmConfigId">
              <a-select
                v-model:value="form.alarmConfigId"
                show-search
                :filter-option="false"
                :options="alarmOptions"
                placeholder="请选择设备类型告警配置"
                @search="fetchAlarmOptions"
                @focus="fetchAlarmOptions()"
              />
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item label="产品" name="productId">
              <a-select
                v-model:value="form.productId"
                show-search
                :filter-option="false"
                :options="productOptions"
                placeholder="请选择产品"
                @change="handleProductChange"
                @search="fetchProductOptions"
                @focus="fetchProductOptions()"
              />
            </a-form-item>
          </a-col>
        </a-row>

        <a-row :gutter="16">
          <a-col :span="12">
            <a-form-item label="设备范围" name="selector">
              <a-segmented
                v-model:value="form.selector"
                :options="[
                  { label: '全部设备', value: 'all' },
                  { label: '指定设备', value: 'fixed' },
                ]"
              />
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item label="条件关系" name="condition.logic">
              <a-segmented
                v-model:value="form.condition.logic"
                :options="[
                  { label: '全部满足', value: 'and' },
                  { label: '任一满足', value: 'or' },
                ]"
              />
            </a-form-item>
          </a-col>
        </a-row>

        <a-form-item
          v-if="form.selector === 'fixed'"
          label="指定设备"
          name="deviceIds"
        >
          <a-select
            v-model:value="form.deviceIds"
            mode="multiple"
            show-search
            :filter-option="false"
            :disabled="!form.productId"
            :options="deviceOptions"
            placeholder="请选择该产品下的设备"
            @search="fetchDeviceOptions"
            @focus="fetchDeviceOptions()"
          />
        </a-form-item>

        <a-form-item label="属性条件" required>
          <div class="condition-list">
            <div
              v-for="(term, index) in form.condition.terms"
              :key="index"
              class="condition-row"
            >
              <a-select
                v-model:value="term.property"
                class="condition-property"
                :options="propertyOptions"
                :disabled="!form.productId"
                placeholder="属性"
                @change="(value) => handlePropertyChange(term, value)"
              />
              <a-select
                v-model:value="term.operator"
                class="condition-operator"
                :options="operatorOptions"
                placeholder="比较符"
              />
              <a-input-number
                v-model:value="term.value"
                class="condition-value"
                placeholder="阈值"
                string-mode
              />
              <a-button
                type="text"
                danger
                :disabled="form.condition.terms.length <= 1"
                @click="removeCondition(index)"
              >
                <template #icon><AIcon type="DeleteOutlined" /></template>
              </a-button>
            </div>
            <a-button type="dashed" block @click="addCondition">
              <template #icon><AIcon type="PlusOutlined" /></template>
              添加条件
            </a-button>
          </div>
        </a-form-item>

        <a-form-item label="说明" name="description">
          <a-textarea
            v-model:value="form.description"
            :maxlength="256"
            show-count
            placeholder="请输入说明"
          />
        </a-form-item>
      </a-form>

      <template #footer>
        <a-space>
          <a-button @click="closeDrawer">取消</a-button>
          <j-permission-button
            type="primary"
            :loading="saving"
            @click="submit"
            :hasPermission="form.id ? 'rule-engine/Alarm/NumericRule:update' : 'rule-engine/Alarm/NumericRule:add'"
          >
            保存
          </j-permission-button>
        </a-space>
      </template>
    </a-drawer>
  </j-page-container>
</template>

<script setup lang="ts">
import { onlyMessage } from '@jetlinks-web/utils';
import {
  disableRule,
  enableRule,
  queryAlarmConfigOptions,
  queryDeviceOptions,
  queryProductDetail,
  queryProductOptions,
  queryRules,
  removeRule,
  saveRule,
  updateRule,
  type DeviceSelector,
  type NumericAlarmRule,
  type NumericCondition,
  type NumericLogic,
  type NumericOperator,
} from '@rule-engine-manager-ui/api/numericAlarmRule';

const tableRef = ref();
const formRef = ref();
const params = ref<Record<string, any>>({});
const drawerVisible = ref(false);
const saving = ref(false);
const alarmOptions = ref<Array<{ label: string; value: string }>>([]);
const productOptions = ref<Array<{ label: string; value: string; metadata?: string }>>([]);
const deviceOptions = ref<Array<{ label: string; value: string }>>([]);
const propertyOptions = ref<Array<{ label: string; value: string; type?: string }>>([]);
const alarmOptionMap = ref<Record<string, string>>({});
const productOptionMap = ref<Record<string, string>>({});

const operatorOptions: Array<{ label: string; value: NumericOperator }> = [
  { label: '大于', value: 'gt' },
  { label: '大于等于', value: 'gte' },
  { label: '小于', value: 'lt' },
  { label: '小于等于', value: 'lte' },
  { label: '等于', value: 'eq' },
  { label: '不等于', value: 'neq' },
];

const columns = [
  {
    title: '规则名称',
    dataIndex: 'name',
    key: 'name',
    ellipsis: true,
    search: { type: 'string', first: true },
    width: 220,
  },
  {
    title: '告警配置',
    dataIndex: 'alarmConfigId',
    key: 'alarmConfigId',
    scopedSlots: true,
    width: 220,
  },
  {
    title: '产品',
    dataIndex: 'productId',
    key: 'productId',
    scopedSlots: true,
    search: { type: 'string' },
    width: 220,
  },
  {
    title: '设备范围',
    dataIndex: 'selector',
    key: 'selector',
    scopedSlots: true,
    search: {
      type: 'select',
      options: [
        { label: '全部设备', value: 'all' },
        { label: '指定设备', value: 'fixed' },
      ],
    },
    width: 120,
  },
  {
    title: '条件',
    dataIndex: 'condition',
    key: 'condition',
    scopedSlots: true,
    width: 360,
  },
  {
    title: '状态',
    dataIndex: 'state',
    key: 'state',
    scopedSlots: true,
    search: {
      type: 'select',
      options: [
        { label: '启用', value: 'enabled' },
        { label: '禁用', value: 'disabled' },
      ],
    },
    width: 100,
  },
  {
    title: '操作',
    dataIndex: 'action',
    key: 'action',
    scopedSlots: true,
    width: 140,
    fixed: 'right',
  },
];

const defaultTerm = (): NumericCondition => ({
  property: undefined,
  propertyName: undefined,
  operator: 'gt',
  value: undefined,
});

const createDefaultForm = (): NumericAlarmRule => ({
  name: '',
  alarmConfigId: '',
  productId: '',
  selector: 'all',
  deviceIds: [],
  condition: {
    logic: 'and',
    terms: [defaultTerm()],
  },
  state: 'enabled',
  description: '',
});

const form = reactive<NumericAlarmRule>(createDefaultForm());

const rules = {
  name: [{ required: true, message: '请输入规则名称' }],
  alarmConfigId: [{ required: true, message: '请选择告警配置' }],
  productId: [{ required: true, message: '请选择产品' }],
  selector: [{ required: true, message: '请选择设备范围' }],
};

const handleSearch = (data: Record<string, any>) => {
  params.value = data;
};

const resultList = (resp: any) => {
  const result = resp?.result;
  if (Array.isArray(result)) {
    return result;
  }
  if (Array.isArray(result?.data)) {
    return result.data;
  }
  return [];
};

const searchTerms = (keyword?: string) => {
  if (!keyword) {
    return [];
  }
  return [
    {
      column: 'name',
      termType: 'like',
      value: keyword,
    },
  ];
};

const fetchAlarmOptions = async (keyword = '') => {
  const resp = await queryAlarmConfigOptions({
    paging: false,
    sorts: [{ name: 'createTime', order: 'desc' }],
    terms: [
      {
        terms: [
          { column: 'targetType', value: 'device' },
          ...searchTerms(keyword),
        ],
      },
    ],
  });
  const options: Array<{ label: string; value: string }> = resultList(resp).map((item: any) => ({
    label: item.name,
    value: item.id,
  }));
  alarmOptions.value = mergeOptions(options, form.alarmConfigId, alarmOptionMap.value);
  options.forEach((item: { label: string; value: string }) => {
    alarmOptionMap.value[item.value] = item.label;
  });
};

const fetchProductOptions = async (keyword = '') => {
  const resp = await queryProductOptions({
    pageIndex: 0,
    pageSize: 50,
    sorts: [{ name: 'createTime', order: 'desc' }],
    terms: searchTerms(keyword),
  });
  const options: Array<{ label: string; value: string }> = resultList(resp).map((item: any) => ({
    label: item.name,
    value: item.id,
  }));
  productOptions.value = mergeOptions(options, form.productId, productOptionMap.value);
  options.forEach((item: { label: string; value: string }) => {
    productOptionMap.value[item.value] = item.label;
  });
};

const fetchDeviceOptions = async (keyword = '') => {
  if (!form.productId) {
    deviceOptions.value = [];
    return;
  }
  const resp = await queryDeviceOptions({
    pageIndex: 0,
    pageSize: 50,
    sorts: [{ name: 'createTime', order: 'desc' }],
    terms: [
      {
        terms: [
          { column: 'productId', value: form.productId },
          ...searchTerms(keyword),
        ],
      },
    ],
  });
  const options = resultList(resp).map((item: any) => ({
    label: item.name ? `${item.name}(${item.id})` : item.id,
    value: item.id,
  }));
  const selected = (form.deviceIds || []).map((id) => ({
    label: id,
    value: id,
  }));
  deviceOptions.value = uniqueOptions([...selected, ...options]);
};

const mergeOptions = (
  options: Array<{ label: string; value: string }>,
  current: string | undefined,
  labelMap: Record<string, string>,
) => {
  if (!current) {
    return options;
  }
  return uniqueOptions([
    { label: labelMap[current] || current, value: current },
    ...options,
  ]);
};

const uniqueOptions = (options: Array<{ label: string; value: string }>) => {
  const map = new Map<string, { label: string; value: string }>();
  options.forEach((item) => {
    if (item.value) {
      map.set(item.value, item);
    }
  });
  return Array.from(map.values());
};

const openSave = async (record?: any) => {
  resetForm();
  await Promise.all([fetchAlarmOptions(), fetchProductOptions()]);
  if (record) {
    Object.assign(form, normalizeRecord(record));
    await loadProductMetadata(form.productId);
    if (form.selector === 'fixed') {
      await fetchDeviceOptions();
    }
  }
  drawerVisible.value = true;
};

const normalizeRecord = (record: any): NumericAlarmRule => {
  const condition = record.condition || {};
  const terms = Array.isArray(condition.terms) && condition.terms.length
    ? condition.terms.map((item: any) => ({
      property: item.property || item.propertyId,
      propertyId: item.propertyId,
      propertyName: item.propertyName,
      operator: item.operator?.value || item.operator || 'gt',
      value: item.value,
    }))
    : [defaultTerm()];
  return {
    id: record.id,
    name: record.name,
    alarmConfigId: record.alarmConfigId,
    productId: record.productId,
    selector: record.selector?.value || record.selector || 'all',
    deviceIds: record.deviceIds || [],
    condition: {
      logic: condition.logic?.value || condition.logic || condition.type?.value || condition.type || 'and',
      terms,
    },
    state: record.state?.value || record.state || 'enabled',
    description: record.description,
  };
};

const resetForm = () => {
  Object.assign(form, createDefaultForm());
  delete (form as any).shakeLimit;
  delete (form as any).shakeTimeMs;
  propertyOptions.value = [];
  deviceOptions.value = [];
  formRef.value?.clearValidate?.();
};

const closeDrawer = () => {
  drawerVisible.value = false;
};

const handleProductChange = async () => {
  form.deviceIds = [];
  form.condition.terms = [defaultTerm()];
  await loadProductMetadata(form.productId);
  if (form.selector === 'fixed') {
    await fetchDeviceOptions();
  }
};

const loadProductMetadata = async (productId: string) => {
  if (!productId) {
    propertyOptions.value = [];
    return;
  }
  const resp = await queryProductDetail(productId);
  const detail = resp?.result || {};
  if (detail.name) {
    productOptionMap.value[productId] = detail.name;
    productOptions.value = mergeOptions(productOptions.value, productId, productOptionMap.value);
  }
  const metadata = safeParseMetadata(detail.metadata);
  propertyOptions.value = (metadata.properties || [])
    .filter((item: any) => ['int', 'long', 'float', 'double'].includes(item.valueType?.type))
    .map((item: any) => ({
      label: item.name ? `${item.name}(${item.id})` : item.id,
      value: item.id,
      type: item.valueType?.type,
    }));
};

const safeParseMetadata = (metadata: any) => {
  if (!metadata) {
    return {};
  }
  if (typeof metadata === 'object') {
    return metadata;
  }
  try {
    return JSON.parse(metadata);
  } catch (error) {
    return {};
  }
};

const handlePropertyChange = (term: NumericCondition, value: any) => {
  const property = String(value);
  const option = propertyOptions.value.find((item) => item.value === property);
  term.propertyName = option?.label?.replace(/\(.+\)$/, '') || property;
  term.propertyId = property;
};

const addCondition = () => {
  form.condition.terms.push(defaultTerm());
};

const removeCondition = (index: number) => {
  form.condition.terms.splice(index, 1);
};

const validateConditions = () => {
  if (!form.condition.terms.length) {
    onlyMessage('至少配置一个属性条件', 'error');
    return false;
  }
  const invalid = form.condition.terms.some((term) =>
    !term.property || !term.operator || term.value === undefined || term.value === null || term.value === '',
  );
  if (invalid) {
    onlyMessage('请完善属性条件', 'error');
    return false;
  }
  if (form.selector === 'fixed' && !(form.deviceIds || []).length) {
    onlyMessage('请选择指定设备', 'error');
    return false;
  }
  return true;
};

const submit = async () => {
  try {
    await formRef.value?.validate();
  } catch (error) {
    return;
  }
  if (!validateConditions()) {
    return;
  }
  saving.value = true;
  try {
    const payload: NumericAlarmRule = {
      ...form,
      deviceIds: form.selector === 'fixed' ? form.deviceIds : [],
      condition: {
        logic: form.condition.logic as NumericLogic,
        terms: form.condition.terms.map((term) => ({
          property: term.property,
          propertyId: term.property,
          propertyName: term.propertyName,
          operator: term.operator,
          value: term.value,
        })),
      },
    };
    const resp = form.id ? await updateRule(payload) : await saveRule(payload);
    if (resp.status === 200 || resp.success) {
      onlyMessage('操作成功');
      closeDrawer();
      tableRef.value?.reload();
    } else {
      onlyMessage('操作失败', 'error');
    }
  } finally {
    saving.value = false;
  }
};

const toggleState = async (record: any) => {
  const resp = stateValue(record.state) === 'enabled'
    ? await disableRule(record.id)
    : await enableRule(record.id);
  if (resp.status === 200 || resp.success) {
    onlyMessage('操作成功');
    tableRef.value?.reload();
  } else {
    onlyMessage('操作失败', 'error');
  }
};

const deleteRule = async (record: any) => {
  const resp = await removeRule(record.id);
  if (resp.status === 200 || resp.success) {
    onlyMessage('操作成功');
    tableRef.value?.reload();
  } else {
    onlyMessage('操作失败', 'error');
  }
};

const stateValue = (state: any) => state?.value || state || 'enabled';

const alarmName = (id: string) => alarmOptionMap.value[id];

const productName = (id: string) => productOptionMap.value[id];

const operatorLabel = (operator?: NumericOperator) =>
  operatorOptions.find((item) => item.value === operator)?.label || operator || '-';

const conditionText = (condition: any) => {
  const logic = condition?.logic?.value || condition?.logic || condition?.type?.value || condition?.type || 'and';
  const terms = Array.isArray(condition?.terms) ? condition.terms : [];
  const joiner = logic === 'or' ? ' 或 ' : ' 且 ';
  return terms
    .map((term: any) => {
      const property = term.propertyName || term.property || term.propertyId || '-';
      const operator = operatorLabel(term.operator?.value || term.operator);
      return `${property} ${operator} ${term.value ?? '-'}`;
    })
    .join(joiner);
};

onMounted(() => {
  fetchAlarmOptions();
  fetchProductOptions();
});
</script>

<style scoped lang="less">
.condition-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.condition-row {
  display: grid;
  grid-template-columns: minmax(180px, 1fr) 120px minmax(120px, 160px) 36px;
  gap: 8px;
  align-items: center;
}

.condition-property,
.condition-operator,
.condition-value {
  width: 100%;
}
</style>

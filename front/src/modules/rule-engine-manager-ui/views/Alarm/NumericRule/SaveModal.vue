<template>
  <a-modal
    open
    :title="form.id ? '编辑数值告警规则' : '新增数值告警规则'"
    :width="1000"
    :confirm-loading="loading"
    :mask-closable="false"
    @cancel="emit('close')"
    @ok="submit"
  >
    <a-form ref="formRef" layout="vertical" :model="form">
      <a-row :gutter="16">
        <a-col :span="12">
          <a-form-item label="规则名称" name="name">
            <a-input v-model:value="form.name" maxlength="64" placeholder="请输入规则名称" />
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
              @search="loadProducts"
              @change="handleProductChange"
            />
          </a-form-item>
        </a-col>
      </a-row>

      <a-form-item label="设备">
        <a-select
          v-model:value="form.deviceIds"
          mode="multiple"
          allow-clear
          :disabled="!form.productId"
          :options="deviceOptions"
          placeholder="不选择则匹配该产品下全部设备"
        />
      </a-form-item>

      <a-divider orientation="left">数值条件</a-divider>
      <div class="condition-toolbar">
        <span>多个条件之间</span>
        <a-radio-group v-model:value="form.type" option-type="button" button-style="solid">
          <a-radio-button value="and">同时满足（AND）</a-radio-button>
          <a-radio-button value="or">满足任一（OR）</a-radio-button>
        </a-radio-group>
      </div>

      <div v-for="(term, index) in form.terms" :key="term.key" class="condition-row">
        <a-select
          v-model:value="term.column"
          class="condition-column"
          :options="propertyOptions"
          placeholder="选择数值属性"
          @change="(value) => handleColumnChange(term, value)"
        />
        <a-select
          v-model:value="term.termType"
          class="condition-operator"
          :options="getTermTypes(term.column)"
          placeholder="比较条件"
          @change="(value) => handleTermTypeChange(term, value)"
        />
        <template v-if="isRange(term.termType)">
          <a-input-number
            v-model:value="term.value[0]"
            class="condition-value"
            :controls="false"
            placeholder="最小值"
          />
          <span class="range-separator">至</span>
          <a-input-number
            v-model:value="term.value[1]"
            class="condition-value"
            :controls="false"
            placeholder="最大值"
          />
        </template>
        <a-input
          v-else-if="isList(term.termType)"
          v-model:value="term.valueText"
          class="condition-value condition-value-single"
          placeholder="多个数值用逗号分隔"
        />
        <a-input-number
          v-else
          v-model:value="term.value"
          class="condition-value condition-value-single"
          :controls="false"
          placeholder="请输入数值"
        />
        <a-button
          type="text"
          danger
          :disabled="form.terms.length === 1"
          @click="removeTerm(index)"
        >
          <AIcon type="DeleteOutlined" />
        </a-button>
      </div>
      <a-button type="dashed" block @click="addTerm">
        <AIcon type="PlusOutlined" />
        添加条件
      </a-button>
      <a-alert
        v-if="invalidTermMessage"
        type="warning"
        show-icon
        :message="invalidTermMessage"
        style="margin-top: 12px"
      />

      <a-divider orientation="left">告警抖动</a-divider>
      <div class="shake-limit">
        <a-checkbox v-model:checked="form.shakeLimit.enabled">启用抖动限制</a-checkbox>
        <template v-if="form.shakeLimit.enabled">
          <span>持续</span>
          <a-input-number v-model:value="form.shakeLimit.time" :min="1" :precision="0" />
          <span>秒，达到</span>
          <a-input-number v-model:value="form.shakeLimit.threshold" :min="1" :precision="0" />
          <span>次后告警</span>
          <a-checkbox v-model:checked="form.shakeLimit.alarmFirst">首次满足条件立即告警</a-checkbox>
        </template>
      </div>
    </a-form>
  </a-modal>
</template>

<script setup lang="ts" name="NumericRuleSaveModal">
import { onlyMessage } from '@jetlinks-web/utils';
import { queryNoPagingPost, queryProductList, productDetail } from '../../../api/others';
import {
  createNumericRule,
  getNumericRuleDetail,
  parseNumericRuleTerms,
  updateNumericRule,
  type NumericRulePayload,
} from '../../../api/numericRule';

type NumericTerm = {
  key: string;
  column?: string;
  termType?: string;
  value: number | number[] | undefined;
  valueText?: string;
};

type NumericForm = {
  id?: string;
  name: string;
  productId?: string;
  deviceIds: string[];
  type: 'and' | 'or';
  terms: NumericTerm[];
  shakeLimit: NumericRulePayload['shakeLimit'];
};

const props = defineProps<{
  data?: Record<string, any>;
}>();

const emit = defineEmits<{
  (event: 'close'): void;
  (event: 'saved'): void;
}>();

const formRef = ref();
const loading = ref(false);
const productOptions = ref<any[]>([]);
const deviceOptions = ref<any[]>([]);
const propertyOptions = ref<any[]>([]);
const propertyMap = ref(new Map<string, any>());
const invalidTermMessage = ref('');

const numericTypes = new Set(['int', 'long', 'float', 'double', 'number', 'decimal']);
const rangeTypes = new Set(['btw', 'nbtw']);
const listTypes = new Set(['in', 'nin']);
const fallbackTermTypes = [
  { label: '等于', value: 'eq' },
  { label: '不等于', value: 'neq' },
  { label: '大于', value: 'gt' },
  { label: '大于等于', value: 'gte' },
  { label: '小于', value: 'lt' },
  { label: '小于等于', value: 'lte' },
  { label: '区间内', value: 'btw' },
  { label: '区间外', value: 'nbtw' },
  { label: '属于', value: 'in' },
  { label: '不属于', value: 'nin' },
];

const newTerm = (): NumericTerm => ({
  key: `${Date.now()}_${Math.random()}`,
  termType: 'gt',
  value: undefined,
});

const form = reactive<NumericForm>({
  name: '',
  deviceIds: [],
  type: 'and',
  terms: [newTerm()],
  shakeLimit: {
    enabled: false,
    time: 1,
    threshold: 1,
    alarmFirst: true,
  },
});

const unwrapValue = (value: any): number | number[] | undefined => {
  if (value && typeof value === 'object' && 'value' in value) {
    return value.value;
  }
  return value;
};

const flattenOptions = (items: any[] = []): any[] =>
  items.flatMap((item) => [item, ...flattenOptions(item.children || [])]);

const normalizeMetadata = (value: any) => {
  if (!value) return {};
  if (typeof value === 'string') {
    try {
      return JSON.parse(value);
    } catch {
      return {};
    }
  }
  return value;
};

const isNumericProperty = (item: any) => {
  const type = item?.valueType?.type || item?.dataType || item?.type;
  return numericTypes.has(String(type).toLowerCase());
};

const loadProducts = async (keyword = '') => {
  const response: any = await queryProductList({
    paging: false,
    sorts: [{ name: 'name', order: 'asc' }],
    terms: keyword
      ? [{ column: 'name', termType: 'like$', value: keyword }]
      : [],
  });
  const result = Array.isArray(response?.result)
    ? response.result
    : response?.result?.data || [];
  productOptions.value = result.map((item: any) => ({
    label: `${item.name} (${item.id})`,
    value: item.id,
  }));
};

const loadProductContext = async (productId?: string, keepTerms = false) => {
  propertyOptions.value = [];
  propertyMap.value = new Map();
  deviceOptions.value = [];
  if (!productId) return;

  const [productResponse, deviceResponse] = await Promise.all([
    productDetail(productId),
    queryNoPagingPost({
      terms: [{ column: 'productId', termType: 'eq', value: productId }],
    }),
  ]);

  const product = productResponse?.result || {};
  const metadata = normalizeMetadata(product.metadata);
  const metadataProperties = (metadata.properties || []).filter(isNumericProperty);

  const trigger = {
    type: 'device',
    device: {
      productId,
      selector: 'all',
      selectorValues: [],
      operation: { operator: 'reportProperty' },
    },
  };
  const parseResponse: any = await parseNumericRuleTerms({ trigger, branches: [] }).catch(() => null);
  const parsedProperties = flattenOptions(parseResponse?.result || [])
    .filter(isNumericProperty)
    .reduce((map, item) => map.set(item.column || item.id, item), new Map());

  const options = metadataProperties.map((item: any) => {
    const id = item.id;
    // The parent metadata entry (for example `U`) is only a display group.
    // A runnable device-scene term must use the real current-value column.
    const parsed = parsedProperties.get(`properties.${id}.current`) || {};
    const option = {
      ...parsed,
      ...item,
      value: parsed.column || `properties.${id}.current`,
      label: `${item.name || id} (${id})`,
      dataType: item.valueType?.type || item.dataType,
      termTypes: parsed.termTypes || item.termTypes || [],
    };
    propertyMap.value.set(option.value, option);
    return option;
  });
  propertyOptions.value = options;

  if (keepTerms) {
    // Keep older rules editable when they stored the metadata parent column.
    form.terms.forEach((term) => {
      if (term.column && !propertyMap.value.has(term.column)) {
        const legacyColumn = `properties.${term.column}.current`;
        if (propertyMap.value.has(legacyColumn)) term.column = legacyColumn;
      }
    });
  }

  const devices = Array.isArray(deviceResponse?.result)
    ? deviceResponse.result
    : deviceResponse?.result?.data || [];
  deviceOptions.value = devices.map((item: any) => ({
    label: `${item.name} (${item.id})`,
    value: item.id,
  }));

  if (!keepTerms) {
    form.terms.splice(0, form.terms.length, newTerm());
  }
  invalidTermMessage.value = '';
};

const getTermTypes = (column?: string) => {
  const types = propertyMap.value.get(column || '')?.termTypes || [];
  if (!types.length) return fallbackTermTypes;
  return types.filter((item: any) => !['notnull', 'isnull'].includes(item.id || item.value)).map((item: any) => ({
    label: item.name || item.label || item.id,
    value: item.id || item.value,
  }));
};

const isRange = (termType?: string) => rangeTypes.has(termType || '');
const isList = (termType?: string) => listTypes.has(termType || '');

const handleColumnChange = (term: NumericTerm, value: string) => {
  term.column = value;
  const first = getTermTypes(value)[0];
  term.termType = first?.value || 'gt';
  term.value = isRange(term.termType) ? [undefined, undefined] : undefined;
  term.valueText = isList(term.termType) ? '' : undefined;
  invalidTermMessage.value = '';
};

const handleTermTypeChange = (term: NumericTerm, value: string) => {
  term.termType = value;
  if (isRange(value)) {
    const oldValue = Array.isArray(term.value) ? term.value[0] : term.value;
    term.value = [oldValue, undefined];
    term.valueText = undefined;
  } else if (isList(value)) {
    const oldValue = Array.isArray(term.value) ? term.value : term.value === undefined ? [] : [term.value];
    term.value = oldValue;
    term.valueText = oldValue.join(',');
  } else if (Array.isArray(term.value)) {
    term.value = term.value[0];
    term.valueText = undefined;
  }
};

const handleProductChange = async (productId: string) => {
  form.productId = productId;
  form.deviceIds = [];
  await loadProductContext(productId);
};

const addTerm = () => form.terms.push(newTerm());

const removeTerm = (index: number) => {
  if (form.terms.length > 1) form.terms.splice(index, 1);
};

const validateForm = () => {
  invalidTermMessage.value = '';
  if (!form.name.trim()) return '请输入规则名称';
  if (!form.productId) return '请选择产品';
  if (!form.terms.length) return '请至少配置一个数值条件';

  for (const term of form.terms) {
    if (!term.column || !propertyMap.value.has(term.column)) {
      invalidTermMessage.value = '存在已失效或未选择的数值属性，请重新选择';
      return invalidTermMessage.value;
    }
    if (!term.termType) return '请选择比较条件';
    if (isRange(term.termType)) {
      if (!Array.isArray(term.value) || term.value.length !== 2 || term.value.some((item) => !Number.isFinite(Number(item)))) {
        return '区间条件需要填写两个数值';
      }
      if (Number(term.value[0]) > Number(term.value[1])) return '区间最小值不能大于最大值';
    } else if (isList(term.termType)) {
      const values = (term.valueText || '')
        .split(',')
        .map((item) => item.trim())
        .filter(Boolean)
        .map(Number);
      if (!values.length || values.some((item) => !Number.isFinite(item))) {
        return '请输入用逗号分隔的有效数值';
      }
    } else if (!Number.isFinite(Number(term.value))) {
      return '请填写有效的数值';
    }
  }
  if (form.shakeLimit.enabled && (!form.shakeLimit.time || !form.shakeLimit.threshold)) {
    return '请填写有效的抖动限制参数';
  }
  return '';
};

const toPayload = (): NumericRulePayload => ({
  name: form.name.trim(),
  productId: form.productId!,
  deviceIds: form.deviceIds.join(';'),
  termList: form.terms.map((term) => ({
    column: term.column!,
    termType: term.termType!,
    value: isList(term.termType)
      ? (term.valueText || '').split(',').map((item) => Number(item.trim())).filter((item) => Number.isFinite(item))
      : Array.isArray(term.value)
      ? term.value.map((item) => Number(item))
      : Number(term.value),
  })),
  type: form.type,
  shakeLimit: { ...form.shakeLimit },
});

const sceneToForm = (scene: any) => {
  const device = scene?.trigger?.device || {};
  const triggerBranch = (scene?.branches || []).find((branch: any) =>
    (branch?.then || []).some((thenItem: any) =>
      (thenItem?.actions || []).some((action: any) => action?.alarm?.mode === 'trigger'),
    ),
  );
  // Backend adds internal `notnull` guards before the user condition so a
  // report missing a monitored property cannot accidentally relieve an alarm.
  // Do not expose those guards as editable numeric conditions.
  const conditionGroup = findUserConditionGroup(triggerBranch?.when || []) || {};
  const selectorValues = device.selector === 'fixed' ? device.selectorValues || [] : [];

  form.id = scene.id;
  form.name = scene.name || '';
  form.productId = device.productId;
  form.deviceIds = selectorValues.map((item: any) => item.value || item.id).filter(Boolean);
  form.type = conditionGroup.type === 'or' ? 'or' : 'and';
  form.shakeLimit = {
    enabled: !!triggerBranch?.shakeLimit?.enabled,
    time: triggerBranch?.shakeLimit?.time || 1,
    threshold: triggerBranch?.shakeLimit?.threshold || 1,
    alarmFirst: triggerBranch?.shakeLimit?.alarmFirst !== false,
  };
  const terms = (conditionGroup.terms || []).map((item: any) => ({
    key: `${Date.now()}_${Math.random()}`,
    column: item.column,
    termType: item.termType,
    value: unwrapValue(item.value),
    valueText: listTypes.has(item.termType)
      ? (Array.isArray(unwrapValue(item.value))
        ? unwrapValue(item.value).join(',')
        : String(unwrapValue(item.value) || ''))
      : undefined,
  }));
  form.terms.splice(0, form.terms.length, ...(terms.length ? terms : [newTerm()]));
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

const initialize = async () => {
  await loadProducts();
  if (!props.data?.id) {
    await loadProductContext(form.productId);
    return;
  }
  const response: any = await getNumericRuleDetail(props.data.id);
  if (!response?.success || !response.result) {
    onlyMessage('无法读取数值告警规则详情', 'error');
    emit('close');
    return;
  }
  sceneToForm(response.result);
  await loadProductContext(form.productId, true);
  const product = productOptions.value.find((item) => item.value === form.productId);
  if (!product) {
    productOptions.value.push({ label: form.productId, value: form.productId });
  }
};

const submit = async () => {
  const error = validateForm();
  if (error) {
    onlyMessage(error, 'error');
    return;
  }
  loading.value = true;
  try {
    const payload = toPayload();
    const response: any = form.id
      ? await updateNumericRule(form.id, payload)
      : await createNumericRule(payload);
    if (response?.success) {
      onlyMessage(form.id ? '数值告警规则更新成功' : '数值告警规则创建成功');
      emit('saved');
    } else {
      onlyMessage(response?.message || '保存数值告警规则失败', 'error');
    }
  } finally {
    loading.value = false;
  }
};

initialize();
</script>

<style scoped lang="less">
.condition-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.condition-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.condition-column {
  flex: 1.3;
}

.condition-operator {
  flex: 0.9;
}

.condition-value {
  flex: 0.8;
}

.condition-value-single {
  min-width: 160px;
}

.range-separator {
  color: var(--app-text-secondary);
}

.shake-limit {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}
</style>

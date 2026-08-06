<template>
  <div class="modbus-polling-editor">
    <a-alert
      type="info"
      show-icon
      message="轮询默认关闭；人工读取与轮询进入同一网关 FIFO，不会并发占用 Modbus 总线。"
    />

    <a-form layout="vertical" class="poll-form">
      <a-form-item v-if="mode === 'device'" label="覆盖产品轮询配置">
        <a-switch v-model:checked="model.pollOverrideEnabled" />
        <span class="hint">关闭时完整继承产品配置</span>
      </a-form-item>

      <fieldset :disabled="deviceInherited">
        <a-row :gutter="16">
          <a-col :span="8">
            <a-form-item label="启用轮询">
              <a-switch v-model:checked="model.pollEnabled" />
            </a-form-item>
          </a-col>
          <a-col :span="8">
            <a-form-item label="调度方式">
              <a-select v-model:value="model.pollScheduleType">
                <a-select-option value="FIXED_DELAY">固定延迟</a-select-option>
                <a-select-option value="CRON">Cron</a-select-option>
              </a-select>
            </a-form-item>
          </a-col>
          <a-col :span="8">
            <a-form-item label="失败重试次数">
              <a-input-number
                v-model:value="model.pollRetryCount"
                :min="0"
                :max="10"
                style="width: 100%"
              />
            </a-form-item>
          </a-col>
        </a-row>

        <a-row :gutter="16">
          <a-col v-if="model.pollScheduleType !== 'CRON'" :span="8">
            <a-form-item label="固定延迟(ms)">
              <a-input-number
                v-model:value="model.pollIntervalMs"
                :min="1000"
                :max="86400000"
                style="width: 100%"
              />
            </a-form-item>
          </a-col>
          <a-col v-else :span="8">
            <a-form-item label="Cron表达式">
              <a-input v-model:value="model.pollCron" placeholder="0/30 * * * * ?" />
            </a-form-item>
          </a-col>
          <a-col :span="8">
            <a-form-item label="设备间隔(ms)">
              <a-input-number
                v-model:value="model.pollDeviceIntervalMs"
                :min="0"
                :max="60000"
                style="width: 100%"
              />
            </a-form-item>
          </a-col>
          <a-col :span="8">
            <a-form-item label="帧间隔(ms)">
              <a-input-number
                v-model:value="model.pollFrameIntervalMs"
                :min="0"
                :max="60000"
                style="width: 100%"
              />
            </a-form-item>
          </a-col>
        </a-row>

        <a-form-item label="采集项">
          <a-select
            v-model:value="model.pollPropertyIds"
            mode="multiple"
            allow-clear
            :options="propertyOptions"
            placeholder="留空表示全部可读点位"
          />
        </a-form-item>

        <a-collapse v-if="mode === 'product' || mode === 'device'" ghost>
          <a-collapse-panel key="read-window" header="协议读取窗口">
            <a-row :gutter="16">
              <a-col :span="8">
                <a-form-item label="FC3/4单窗寄存器数">
                  <a-input-number
                    v-model:value="model.maxReadRegistersPerRequest"
                    :min="1"
                    :max="125"
                    style="width: 100%"
                  />
                </a-form-item>
              </a-col>
              <a-col :span="8">
                <a-form-item label="FC1/2单窗位数">
                  <a-input-number
                    v-model:value="model.maxReadBitsPerRequest"
                    :min="1"
                    :max="2000"
                    style="width: 100%"
                  />
                </a-form-item>
              </a-col>
              <a-col :span="8">
                <a-form-item label="最大地址空洞">
                  <a-input-number
                    v-model:value="model.maxReadAddressGap"
                    :min="0"
                    :max="2"
                    style="width: 100%"
                  />
                </a-form-item>
              </a-col>
            </a-row>
          </a-collapse-panel>
        </a-collapse>
      </fieldset>
    </a-form>
  </div>
</template>

<script setup lang="ts">
import { computed, watch } from 'vue'

type Mode = 'product' | 'device'

const props = withDefaults(
  defineProps<{
      modelValue?: Record<string, any>
      mode?: Mode
      readableProperties?: Array<{ label: string; value: string }>
      inheritedConfig?: Record<string, any>
  }>(),
  {
    modelValue: () => ({}),
    mode: 'product',
    readableProperties: () => [],
    inheritedConfig: () => ({}),
  },
)

const model = computed<Record<string, any>>(() => props.modelValue || {})

const propertyOptions = computed(() => props.readableProperties)
const deviceInherited = computed(
  () => props.mode === 'device' && !model.value.pollOverrideEnabled,
)

watch(
  () => [props.modelValue, props.inheritedConfig],
  ([value, inherited]) => {
    const next = value || {}
    const inheritedValues = inherited || {}
    const defaults: Record<string, any> = {
      pollEnabled: false,
      pollScheduleType: 'FIXED_DELAY',
      pollIntervalMs: Number(next.probeIntervalMs || inheritedValues.pollIntervalMs) || 30000,
      pollCron: '0/30 * * * * ?',
      pollDeviceIntervalMs: Number(inheritedValues.pollDeviceIntervalMs) || 100,
      pollFrameIntervalMs: Number(inheritedValues.pollFrameIntervalMs) || 100,
      pollPropertyIds: [],
      pollRetryCount: Number(inheritedValues.pollRetryCount) || 0,
      maxReadRegistersPerRequest: Number(inheritedValues.maxReadRegistersPerRequest) || 60,
      maxReadBitsPerRequest: Number(inheritedValues.maxReadBitsPerRequest) || 512,
      maxReadAddressGap: Number(inheritedValues.maxReadAddressGap) || 2,
    }
    Object.entries(defaults).forEach(([key, defaultValue]) => {
      if (next[key] === undefined || next[key] === null) {
        next[key] = defaultValue
      }
    })
  },
  { immediate: true },
)
</script>

<style scoped lang="less">
.modbus-polling-editor {
  margin-top: 16px;
}

.poll-form {
  margin-top: 16px;
}

.hint {
  margin-left: 8px;
  color: rgba(0, 0, 0, 0.45);
}

fieldset {
  min-width: 0;
  padding: 0;
  margin: 0;
  border: 0;
}

fieldset:disabled {
  opacity: 0.55;
  pointer-events: none;
}
</style>

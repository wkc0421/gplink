<template>
    <j-page-container>
        <FullPage :fixed="false">
            <div class="modbus-access-page">
                <div class="page-head">
                    <div>
                        <div class="page-title">Modbus 接入向导</div>
                        <div class="page-subtitle">按连接、主机、从机产品、从机实例和采集策略完成接入。</div>
                    </div>
                    <a-space>
                        <a-button :disabled="saving || testing" @click="resetWizard">
                            <template #icon><AIcon type="ReloadOutlined" /></template>
                            重置
                        </a-button>
                        <j-permission-button
                            type="primary"
                            :loading="saving"
                            hasPermission="device/ModbusAccess:save"
                            @click="handleSaveAndTest"
                        >
                            <template #icon><AIcon type="SaveOutlined" /></template>
                            保存并测试
                        </j-permission-button>
                    </a-space>
                </div>

                <div class="steps-wrap">
                    <a-steps :current="currentStep">
                        <a-step v-for="item in steps" :key="item" :title="item" />
                    </a-steps>
                </div>

                <div class="content-panel">
                    <a-spin :spinning="saving || testing">
                        <a-form layout="vertical">
                            <section v-show="currentStep === 0" class="step-panel">
                                <div class="section-title">连接方式</div>
                                <a-row :gutter="16">
                                    <a-col :span="24">
                                        <a-form-item label="接入名称">
                                            <a-input v-model:value="accessForm.name" placeholder="例如：一号车间 Modbus 接入" />
                                        </a-form-item>
                                    </a-col>
                                </a-row>
                                <a-form-item label="连接模式">
                                    <a-radio-group v-model:value="connectionForm.mode" button-style="solid">
                                        <a-radio-button value="PLATFORM_CONNECTS_GATEWAY">平台连接主机</a-radio-button>
                                        <a-radio-button value="GATEWAY_CONNECTS_PLATFORM">主机连接平台</a-radio-button>
                                    </a-radio-group>
                                </a-form-item>
                                <a-row v-if="connectionForm.mode === 'PLATFORM_CONNECTS_GATEWAY'" :gutter="16">
                                    <a-col :span="12">
                                        <a-form-item label="主机 IP">
                                            <a-input v-model:value="connectionForm.host" placeholder="192.168.1.10" />
                                        </a-form-item>
                                    </a-col>
                                    <a-col :span="12">
                                        <a-form-item label="主机端口">
                                            <a-input-number v-model:value="connectionForm.port" :min="1" :max="65535" style="width: 100%" />
                                        </a-form-item>
                                    </a-col>
                                </a-row>
                                <a-row v-else :gutter="16">
                                    <a-col :span="12">
                                        <a-form-item label="监听地址">
                                            <a-input v-model:value="connectionForm.listenHost" placeholder="0.0.0.0" />
                                        </a-form-item>
                                    </a-col>
                                    <a-col :span="12">
                                        <a-form-item label="监听端口">
                                            <a-input-number v-model:value="connectionForm.listenPort" :min="1" :max="65535" style="width: 100%" />
                                        </a-form-item>
                                    </a-col>
                                </a-row>
                            </section>

                            <section v-show="currentStep === 1" class="step-panel">
                                <div class="section-title">主机/网关</div>
                                <a-row :gutter="16">
                                    <a-col :span="12">
                                        <a-form-item label="主机名称">
                                            <a-input v-model:value="hostForm.name" placeholder="例如：一号车间主机" />
                                        </a-form-item>
                                    </a-col>
                                    <a-col :span="12">
                                        <a-form-item label="主机编号">
                                            <a-input v-model:value="hostForm.deviceId" placeholder="留空则自动生成" />
                                        </a-form-item>
                                    </a-col>
                                    <a-col :span="24">
                                        <a-form-item label="说明">
                                            <a-textarea v-model:value="hostForm.description" :rows="3" />
                                        </a-form-item>
                                    </a-col>
                                </a-row>
                            </section>

                            <section v-show="currentStep === 2" class="step-panel">
                                <div class="section-title">从机产品</div>
                                <div class="type-layout">
                                    <div class="type-list">
                                        <a-button type="dashed" block @click="addSlaveType">
                                            <template #icon><AIcon type="PlusOutlined" /></template>
                                            添加从机产品
                                        </a-button>
                                        <div
                                            v-for="item in slaveTypes"
                                            :key="item.key"
                                            class="type-item"
                                            :class="{ active: item.key === selectedSlaveTypeKey }"
                                            @click="selectedSlaveTypeKey = item.key"
                                        >
                                            <div class="type-name">{{ item.name || item.id || '未命名产品' }}</div>
                                            <a-button
                                                v-if="slaveTypes.length > 1"
                                                type="text"
                                                danger
                                                size="small"
                                                @click.stop="removeSlaveType(item.key)"
                                            >
                                                <template #icon><AIcon type="DeleteOutlined" /></template>
                                            </a-button>
                                        </div>
                                    </div>
                                    <div class="type-editor" v-if="selectedSlaveType">
                                        <a-row :gutter="16">
                                            <a-col :span="12">
                                                <a-form-item label="产品编号">
                                                    <a-input v-model:value="selectedSlaveType.id" placeholder="例如：temperature_meter" />
                                                </a-form-item>
                                            </a-col>
                                            <a-col :span="12">
                                                <a-form-item label="产品名称">
                                                    <a-input v-model:value="selectedSlaveType.name" placeholder="例如：温湿度表" />
                                                </a-form-item>
                                            </a-col>
                                        </a-row>

                                        <div class="table-toolbar">
                                            <a-space>
                                                <a-upload accept=".csv,.txt" :show-upload-list="false" :before-upload="beforeRegisterUpload">
                                                    <a-button>
                                                        <template #icon><AIcon type="UploadOutlined" /></template>
                                                        导入
                                                    </a-button>
                                                </a-upload>
                                                <a-button @click="downloadRegisterTemplate">
                                                    <template #icon><AIcon type="DownloadOutlined" /></template>
                                                    下载模板
                                                </a-button>
                                                <a-button @click="openPaste('register')">
                                                    <template #icon><AIcon type="CopyOutlined" /></template>
                                                    粘贴
                                                </a-button>
                                                <a-button type="dashed" @click="addRegisterRow">
                                                    <template #icon><AIcon type="PlusOutlined" /></template>
                                                    添加寄存器
                                                </a-button>
                                            </a-space>
                                        </div>

                                        <a-table
                                            row-key="key"
                                            size="small"
                                            bordered
                                            :pagination="false"
                                            :columns="registerColumns"
                                            :data-source="selectedSlaveType.registerRows"
                                            :scroll="{ x: 1380 }"
                                        >
                                            <template #bodyCell="{ column, record, index }">
                                                <template v-if="column.key === 'propertyId'">
                                                    <a-input v-model:value="record.propertyId" size="small" placeholder="属性ID" />
                                                </template>
                                                <template v-else-if="column.key === 'propertyName'">
                                                    <a-input v-model:value="record.propertyName" size="small" placeholder="名称" />
                                                </template>
                                                <template v-else-if="column.key === 'functionCode'">
                                                    <a-select v-model:value="record.functionCode" size="small" style="width: 100%">
                                                        <a-select-option :value="1">0x01 读线圈</a-select-option>
                                                        <a-select-option :value="2">0x02 读离散输入</a-select-option>
                                                        <a-select-option :value="3">0x03 读保持寄存器</a-select-option>
                                                        <a-select-option :value="4">0x04 读输入寄存器</a-select-option>
                                                        <a-select-option :value="5">0x05 写单线圈</a-select-option>
                                                        <a-select-option :value="6">0x06 写单寄存器</a-select-option>
                                                        <a-select-option :value="15">0x0F 写多线圈</a-select-option>
                                                        <a-select-option :value="16">0x10 写多寄存器</a-select-option>
                                                    </a-select>
                                                </template>
                                                <template v-else-if="column.key === 'address'">
                                                    <a-input-number v-model:value="record.address" size="small" :min="0" :max="65535" style="width: 100%" />
                                                </template>
                                                <template v-else-if="column.key === 'quantity'">
                                                    <a-input-number v-model:value="record.quantity" size="small" :min="1" style="width: 100%" />
                                                </template>
                                                <template v-else-if="column.key === 'dataType'">
                                                    <a-select v-model:value="record.dataType" size="small" style="width: 100%">
                                                        <a-select-option v-for="item in dataTypeOptions" :key="item" :value="item">{{ item }}</a-select-option>
                                                    </a-select>
                                                </template>
                                                <template v-else-if="column.key === 'byteOrder'">
                                                    <a-select v-model:value="record.byteOrder" size="small" style="width: 100%">
                                                        <a-select-option v-for="item in byteOrderOptions" :key="item" :value="item">{{ item }}</a-select-option>
                                                    </a-select>
                                                </template>
                                                <template v-else-if="column.key === 'scale'">
                                                    <a-input-number v-model:value="record.scale" size="small" style="width: 100%" />
                                                </template>
                                                <template v-else-if="column.key === 'offset'">
                                                    <a-input-number v-model:value="record.offset" size="small" style="width: 100%" />
                                                </template>
                                                <template v-else-if="column.key === 'writable'">
                                                    <a-switch v-model:checked="record.writable" size="small" />
                                                </template>
                                                <template v-else-if="column.key === 'unit'">
                                                    <a-input v-model:value="record.unit" size="small" />
                                                </template>
                                                <template v-else-if="column.key === 'action'">
                                                    <a-button type="text" danger size="small" @click="removeRegisterRow(index)">
                                                        <template #icon><AIcon type="DeleteOutlined" /></template>
                                                    </a-button>
                                                </template>
                                            </template>
                                        </a-table>
                                    </div>
                                </div>
                            </section>

                            <section v-show="currentStep === 3" class="step-panel">
                                <div class="section-title">从机实例</div>
                                <div class="table-toolbar">
                                    <a-space>
                                        <a-upload accept=".csv,.txt" :show-upload-list="false" :before-upload="beforeSlaveUpload">
                                            <a-button>
                                                <template #icon><AIcon type="UploadOutlined" /></template>
                                                导入
                                            </a-button>
                                        </a-upload>
                                        <a-button @click="downloadSlaveTemplate">
                                            <template #icon><AIcon type="DownloadOutlined" /></template>
                                            下载模板
                                        </a-button>
                                        <a-button @click="openPaste('slave')">
                                            <template #icon><AIcon type="CopyOutlined" /></template>
                                            粘贴
                                        </a-button>
                                        <a-button type="dashed" @click="addSlaveRow">
                                            <template #icon><AIcon type="PlusOutlined" /></template>
                                            添加从机
                                        </a-button>
                                    </a-space>
                                </div>
                                <a-table
                                    row-key="key"
                                    size="small"
                                    bordered
                                    :pagination="false"
                                    :columns="slaveColumns"
                                    :data-source="slaveRows"
                                    :scroll="{ x: 1120 }"
                                >
                                    <template #bodyCell="{ column, record, index }">
                                        <template v-if="column.key === 'slaveId'">
                                            <a-input-number v-model:value="record.slaveId" size="small" :min="1" :max="247" style="width: 100%" @change="fillSlaveGeneratedFields(record)" />
                                        </template>
                                        <template v-else-if="column.key === 'typeId'">
                                            <a-select v-model:value="record.typeId" size="small" style="width: 100%">
                                                <a-select-option v-for="item in slaveTypeOptions" :key="item.value" :value="item.value">{{ item.label }}</a-select-option>
                                            </a-select>
                                        </template>
                                        <template v-else-if="column.key === 'deviceId'">
                                            <a-input v-model:value="record.deviceId" size="small" @change="record.autoDeviceId = false" />
                                        </template>
                                        <template v-else-if="column.key === 'deviceName'">
                                            <a-input v-model:value="record.deviceName" size="small" @change="record.autoDeviceName = false" />
                                        </template>
                                        <template v-else-if="column.key === 'description'">
                                            <a-input v-model:value="record.description" size="small" />
                                        </template>
                                        <template v-else-if="column.key === 'action'">
                                            <a-button type="text" danger size="small" @click="removeSlaveRow(index)">
                                                <template #icon><AIcon type="DeleteOutlined" /></template>
                                            </a-button>
                                        </template>
                                    </template>
                                </a-table>
                            </section>

                            <section v-show="currentStep === 4" class="step-panel">
                                <div class="section-title">采集策略</div>
                                <a-row :gutter="16">
                                    <a-col :span="6">
                                        <a-form-item label="启用采集">
                                            <a-switch v-model:checked="defaultPolicy.collectEnabled" />
                                        </a-form-item>
                                    </a-col>
                                    <a-col :span="6">
                                        <a-form-item label="采集频率(ms)">
                                            <a-input-number v-model:value="defaultPolicy.scanIntervalMs" :min="1" style="width: 100%" />
                                        </a-form-item>
                                    </a-col>
                                    <a-col :span="6">
                                        <a-form-item label="下发间隔(ms)">
                                            <a-input-number v-model:value="defaultPolicy.dispatchIntervalMs" :min="0" style="width: 100%" />
                                        </a-form-item>
                                    </a-col>
                                    <a-col :span="6">
                                        <a-form-item label="存储频率(ms)">
                                            <a-input-number v-model:value="defaultPolicy.storageIntervalMs" :min="1" style="width: 100%" />
                                        </a-form-item>
                                    </a-col>
                                    <a-col :span="6">
                                        <a-form-item label="响应超时(ms)">
                                            <a-input-number v-model:value="defaultPolicy.responseTimeoutMs" :min="1" style="width: 100%" />
                                        </a-form-item>
                                    </a-col>
                                </a-row>

                                <div class="section-title sub">从机产品策略</div>
                                <a-table
                                    row-key="key"
                                    size="small"
                                    bordered
                                    :pagination="false"
                                    :columns="typePolicyColumns"
                                    :data-source="slaveTypes"
                                    :scroll="{ x: 980 }"
                                >
                                    <template #bodyCell="{ column, record }">
                                        <template v-if="column.key === 'name'">{{ record.name || record.id }}</template>
                                        <template v-else-if="column.key === 'collectEnabled'">
                                            <a-switch v-model:checked="record.collectionPolicy.collectEnabled" size="small" />
                                        </template>
                                        <template v-else-if="column.key === 'scanIntervalMs'">
                                            <a-input-number v-model:value="record.collectionPolicy.scanIntervalMs" size="small" :min="1" style="width: 100%" />
                                        </template>
                                        <template v-else-if="column.key === 'dispatchIntervalMs'">
                                            <a-input-number v-model:value="record.collectionPolicy.dispatchIntervalMs" size="small" :min="0" style="width: 100%" />
                                        </template>
                                        <template v-else-if="column.key === 'storageIntervalMs'">
                                            <a-input-number v-model:value="record.collectionPolicy.storageIntervalMs" size="small" :min="1" style="width: 100%" />
                                        </template>
                                        <template v-else-if="column.key === 'responseTimeoutMs'">
                                            <a-input-number v-model:value="record.collectionPolicy.responseTimeoutMs" size="small" :min="1" style="width: 100%" />
                                        </template>
                                    </template>
                                </a-table>

                                <div class="section-title sub">从机实例覆盖</div>
                                <a-table
                                    row-key="key"
                                    size="small"
                                    bordered
                                    :pagination="false"
                                    :columns="slavePolicyColumns"
                                    :data-source="slaveRows"
                                    :scroll="{ x: 980 }"
                                >
                                    <template #bodyCell="{ column, record }">
                                        <template v-if="column.key === 'deviceName'">{{ record.deviceName || record.deviceId }}</template>
                                        <template v-else-if="column.key === 'scanIntervalMs'">
                                            <a-input-number v-model:value="record.scanIntervalMs" size="small" :min="1" placeholder="继承" style="width: 100%" />
                                        </template>
                                        <template v-else-if="column.key === 'dispatchIntervalMs'">
                                            <a-input-number v-model:value="record.dispatchIntervalMs" size="small" :min="0" placeholder="继承" style="width: 100%" />
                                        </template>
                                        <template v-else-if="column.key === 'storageIntervalMs'">
                                            <a-input-number v-model:value="record.storageIntervalMs" size="small" :min="1" placeholder="继承" style="width: 100%" />
                                        </template>
                                        <template v-else-if="column.key === 'responseTimeoutMs'">
                                            <a-input-number v-model:value="record.responseTimeoutMs" size="small" :min="1" placeholder="继承" style="width: 100%" />
                                        </template>
                                    </template>
                                </a-table>
                            </section>

                            <section v-show="currentStep === 5" class="step-panel">
                                <div class="section-title">保存并测试</div>
                                <a-row :gutter="16">
                                    <a-col :span="12">
                                        <a-form-item label="测试属性">
                                            <a-select
                                                v-model:value="testPropertyIds"
                                                mode="multiple"
                                                allow-clear
                                                :options="readablePropertyOptions"
                                                placeholder="默认测试第一个可读属性"
                                            />
                                        </a-form-item>
                                    </a-col>
                                    <a-col :span="12">
                                        <a-form-item label="测试从机">
                                            <a-select
                                                v-model:value="testDeviceIds"
                                                mode="multiple"
                                                allow-clear
                                                :options="slaveDeviceOptions"
                                                placeholder="默认测试全部从机"
                                            />
                                        </a-form-item>
                                    </a-col>
                                </a-row>
                                <a-space class="test-actions">
                                    <j-permission-button type="primary" :loading="saving" hasPermission="device/ModbusAccess:save" @click="handleSaveAndTest">
                                        <template #icon><AIcon type="SaveOutlined" /></template>
                                        保存并测试
                                    </j-permission-button>
                                    <a-button :disabled="!savedDeviceIds.length" :loading="testing" @click="runCommunicationTest(savedDeviceIds)">重新测试</a-button>
                                </a-space>

                                <a-table
                                    v-if="testResults.length"
                                    class="result-table"
                                    row-key="key"
                                    size="small"
                                    bordered
                                    :columns="testColumns"
                                    :data-source="testResults"
                                    :pagination="false"
                                >
                                    <template #bodyCell="{ column, record }">
                                        <template v-if="column.key === 'status'">
                                            <a-tag :color="record.status === 'success' ? 'green' : 'red'">
                                                {{ record.status === 'success' ? '成功' : '失败' }}
                                            </a-tag>
                                        </template>
                                        <template v-else-if="column.key === 'value'">{{ formatValue(record.value) }}</template>
                                    </template>
                                </a-table>

                                <a-collapse v-if="saveResult" class="advanced-info">
                                    <a-collapse-panel key="advanced" header="高级信息">
                                        <a-descriptions size="small" bordered :column="2">
                                            <a-descriptions-item label="网络组件ID">{{ saveResult.networkId }}</a-descriptions-item>
                                            <a-descriptions-item label="接入网关ID">{{ saveResult.gatewayId }}</a-descriptions-item>
                                            <a-descriptions-item label="主机产品ID">{{ saveResult.gatewayProductId }}</a-descriptions-item>
                                            <a-descriptions-item label="主机设备ID">{{ saveResult.gatewayDeviceId }}</a-descriptions-item>
                                            <a-descriptions-item label="从机产品ID">{{ (saveResult.slaveProductIds || []).join(', ') }}</a-descriptions-item>
                                            <a-descriptions-item label="从机设备ID">{{ (saveResult.slaveDeviceIds || []).join(', ') }}</a-descriptions-item>
                                        </a-descriptions>
                                    </a-collapse-panel>
                                </a-collapse>
                            </section>
                        </a-form>
                    </a-spin>
                </div>

                <div class="footer-actions">
                    <a-space>
                        <a-button :disabled="currentStep === 0" @click="currentStep -= 1">上一步</a-button>
                        <a-button v-if="currentStep < steps.length - 1" type="primary" @click="goNext">下一步</a-button>
                        <j-permission-button
                            v-else
                            type="primary"
                            :loading="saving"
                            hasPermission="device/ModbusAccess:save"
                            @click="handleSaveAndTest"
                        >
                            保存并测试
                        </j-permission-button>
                    </a-space>
                </div>

                <a-modal v-model:visible="pasteState.visible" title="粘贴导入" width="720px" @ok="applyPaste">
                    <a-textarea v-model:value="pasteState.text" :rows="12" placeholder="支持 CSV 或制表符分隔内容，第一行为表头。" />
                </a-modal>

                <a-modal v-model:visible="importErrorVisible" title="校验提示" :footer="null">
                    <a-alert type="warning" show-icon message="请处理以下问题后继续" />
                    <ul class="error-list">
                        <li v-for="item in importErrors" :key="item">{{ item }}</li>
                    </ul>
                </a-modal>
            </div>
        </FullPage>
    </j-page-container>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { onlyMessage } from '@jetlinks-web/utils'
import { saveModbusAccess } from '../../../api/modbusAccess'
import { testReadProperties } from '../../../api/instance'
import type { CollectionPolicy, RegisterMappingRow, SlaveRow, TestResultRow } from './types'
import {
    BYTE_ORDER_OPTIONS,
    DATA_TYPE_OPTIONS,
    DEFAULT_COLLECTION_POLICY,
    buildSlaveDeviceId,
    createRegisterRow,
    createSlaveRow,
    getReadablePropertyIds,
    parseRegisterMapText,
    parseSlaveText,
    validateRegisterRows,
    validateSlaveRows,
} from './utils'

type ConnectionMode = 'PLATFORM_CONNECTS_GATEWAY' | 'GATEWAY_CONNECTS_PLATFORM'

interface SlaveTypeForm {
    key: string
    id: string
    name: string
    registerRows: RegisterMappingRow[]
    collectionPolicy: CollectionPolicy
}

interface SlaveForm extends SlaveRow {
    scanIntervalMs?: number
    dispatchIntervalMs?: number
    storageIntervalMs?: number
    responseTimeoutMs?: number
}

const steps = ['连接方式', '主机/网关', '从机产品', '从机实例', '采集策略', '保存并测试']
const currentStep = ref(0)
const saving = ref(false)
const testing = ref(false)
const importErrors = ref<string[]>([])
const importErrorVisible = ref(false)
const saveResult = ref<any>()
const savedDeviceIds = ref<string[]>([])
const testPropertyIds = ref<string[]>([])
const testDeviceIds = ref<string[]>([])
const testResults = ref<TestResultRow[]>([])

const accessForm = reactive({
    id: '',
    name: 'Modbus 接入',
})

const connectionForm = reactive({
    mode: 'PLATFORM_CONNECTS_GATEWAY' as ConnectionMode,
    host: '',
    port: 502,
    listenHost: '0.0.0.0',
    listenPort: 1502,
})

const hostForm = reactive({
    deviceId: '',
    name: 'Modbus 主机 1',
    description: '',
})

const defaultPolicy = reactive<CollectionPolicy>({ ...DEFAULT_COLLECTION_POLICY })
const slaveTypes = ref<SlaveTypeForm[]>([createSlaveType(1)])
const selectedSlaveTypeKey = ref(slaveTypes.value[0].key)
const slaveRows = ref<SlaveForm[]>([createSlaveForm(1)])

const pasteState = reactive<{
    visible: boolean
    type: 'register' | 'slave'
    text: string
}>({
    visible: false,
    type: 'register',
    text: '',
})

const dataTypeOptions = DATA_TYPE_OPTIONS
const byteOrderOptions = BYTE_ORDER_OPTIONS

const registerColumns = [
    { title: '属性ID', key: 'propertyId', width: 150 },
    { title: '属性名称', key: 'propertyName', width: 150 },
    { title: '功能码', key: 'functionCode', width: 180 },
    { title: '地址', key: 'address', width: 90 },
    { title: '数量', key: 'quantity', width: 80 },
    { title: '数据类型', key: 'dataType', width: 120 },
    { title: '字节序', key: 'byteOrder', width: 110 },
    { title: '比例', key: 'scale', width: 90 },
    { title: '偏移', key: 'offset', width: 90 },
    { title: '可写', key: 'writable', width: 70 },
    { title: '单位', key: 'unit', width: 100 },
    { title: '操作', key: 'action', width: 80, fixed: 'right' },
]

const slaveColumns = [
    { title: 'slaveId', key: 'slaveId', width: 110 },
    { title: '从机产品', key: 'typeId', width: 180 },
    { title: '从机编号', key: 'deviceId', width: 240 },
    { title: '从机名称', key: 'deviceName', width: 180 },
    { title: '说明', key: 'description', width: 220 },
    { title: '操作', key: 'action', width: 80, fixed: 'right' },
]

const typePolicyColumns = [
    { title: '从机产品', key: 'name', width: 180 },
    { title: '启用', key: 'collectEnabled', width: 90 },
    { title: '采集频率(ms)', key: 'scanIntervalMs', width: 170 },
    { title: '下发间隔(ms)', key: 'dispatchIntervalMs', width: 170 },
    { title: '存储频率(ms)', key: 'storageIntervalMs', width: 170 },
    { title: '响应超时(ms)', key: 'responseTimeoutMs', width: 170 },
]

const slavePolicyColumns = [
    { title: '从机', key: 'deviceName', width: 220 },
    { title: '采集频率(ms)', key: 'scanIntervalMs', width: 170 },
    { title: '下发间隔(ms)', key: 'dispatchIntervalMs', width: 170 },
    { title: '存储频率(ms)', key: 'storageIntervalMs', width: 170 },
    { title: '响应超时(ms)', key: 'responseTimeoutMs', width: 170 },
]

const testColumns = [
    { title: '从机编号', dataIndex: 'deviceId', key: 'deviceId', width: 220 },
    { title: '属性ID', dataIndex: 'propertyId', key: 'propertyId', width: 160 },
    { title: '状态', dataIndex: 'status', key: 'status', width: 90 },
    { title: '值', dataIndex: 'value', key: 'value', width: 220 },
    { title: '消息', dataIndex: 'message', key: 'message' },
]

const selectedSlaveType = computed(() => slaveTypes.value.find(item => item.key === selectedSlaveTypeKey.value) || slaveTypes.value[0])

const slaveTypeOptions = computed(() => slaveTypes.value.map(item => ({
    label: item.name || item.id,
    value: item.id,
})))

const readablePropertyIds = computed(() => {
    const ids = new Set<string>()
    slaveTypes.value.forEach(type => getReadablePropertyIds(type.registerRows).forEach(id => ids.add(id)))
    return Array.from(ids)
})

const readablePropertyOptions = computed(() => readablePropertyIds.value.map(id => ({
    label: id,
    value: id,
})))

const slaveDeviceOptions = computed(() => slaveRows.value
    .filter(item => item.deviceId)
    .map(item => ({
        label: item.deviceName ? `${item.deviceName}（${item.deviceId}）` : item.deviceId,
        value: item.deviceId,
    })))

watch(slaveTypes, () => {
    if (!slaveTypes.value.find(item => item.key === selectedSlaveTypeKey.value)) {
        selectedSlaveTypeKey.value = slaveTypes.value[0]?.key
    }
    const firstType = slaveTypes.value[0]?.id
    slaveRows.value.forEach((row) => {
        if (!row.typeId || !slaveTypes.value.some(type => type.id === row.typeId)) {
            row.typeId = firstType
        }
    })
}, { deep: true })

function createPolicy(): CollectionPolicy {
    return { ...DEFAULT_COLLECTION_POLICY }
}

function createSlaveType(index: number): SlaveTypeForm {
    return {
        key: `${Date.now()}_${Math.random().toString(16).slice(2)}`,
        id: `type_${index}`,
        name: `从机产品 ${index}`,
        registerRows: [createRegisterRow()],
        collectionPolicy: createPolicy(),
    }
}

function createSlaveForm(slaveId?: number): SlaveForm {
    const row = createSlaveRow(slaveId, hostForm.deviceId || 'modbus_host') as SlaveForm
    row.typeId = slaveTypes.value[0]?.id
    return row
}

function resetWizard() {
    currentStep.value = 0
    saveResult.value = undefined
    savedDeviceIds.value = []
    testResults.value = []
}

function addSlaveType() {
    const next = createSlaveType(slaveTypes.value.length + 1)
    slaveTypes.value.push(next)
    selectedSlaveTypeKey.value = next.key
}

function removeSlaveType(key: string) {
    const index = slaveTypes.value.findIndex(item => item.key === key)
    if (index < 0 || slaveTypes.value.length <= 1) return
    const removed = slaveTypes.value[index]
    slaveTypes.value.splice(index, 1)
    const fallback = slaveTypes.value[0]?.id
    slaveRows.value.forEach((row) => {
        if (row.typeId === removed.id) row.typeId = fallback
    })
}

function addRegisterRow() {
    selectedSlaveType.value.registerRows.push(createRegisterRow())
}

function removeRegisterRow(index: number) {
    selectedSlaveType.value.registerRows.splice(index, 1)
}

function addSlaveRow() {
    const nextSlaveId = findNextSlaveId()
    slaveRows.value.push(createSlaveForm(nextSlaveId))
}

function removeSlaveRow(index: number) {
    slaveRows.value.splice(index, 1)
}

function findNextSlaveId() {
    const used = new Set(slaveRows.value.map(item => Number(item.slaveId)).filter(Number.isFinite))
    for (let index = 1; index <= 247; index++) {
        if (!used.has(index)) return index
    }
    return undefined
}

function fillSlaveGeneratedFields(row: SlaveForm) {
    if (!row.slaveId) return
    const hostId = hostForm.deviceId || 'modbus_host'
    if (!row.deviceId || row.autoDeviceId) row.deviceId = buildSlaveDeviceId(hostId, row.slaveId)
    if (!row.deviceName || row.autoDeviceName) row.deviceName = `从机${row.slaveId}`
    if (!row.typeId) row.typeId = slaveTypes.value[0]?.id
}

function readFileAsText(file: File) {
    return new Promise<string>((resolve, reject) => {
        const reader = new FileReader()
        reader.onload = () => resolve(String(reader.result || ''))
        reader.onerror = () => reject(reader.error)
        reader.readAsText(file)
    })
}

function beforeRegisterUpload(file: File) {
    readFileAsText(file).then(applyRegisterImport)
    return false
}

function beforeSlaveUpload(file: File) {
    readFileAsText(file).then(applySlaveImport)
    return false
}

const csvCell = (value: unknown) => {
    const text = String(value ?? '')
    return /[",\r\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text
}

const downloadCsv = (fileName: string, rows: unknown[][]) => {
    const content = rows.map(row => row.map(csvCell).join(',')).join('\r\n')
    const blob = new Blob(['\uFEFF', content], { type: 'text/csv;charset=utf-8;' })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = fileName
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    URL.revokeObjectURL(url)
}

function downloadRegisterTemplate() {
    downloadCsv('modbus-register-template.csv', [
        ['propertyId', 'propertyName', 'functionCode', 'address', 'quantity', 'dataType', 'byteOrder', 'scale', 'offset', 'writable', 'unit'],
        ['temperature', '温度', 3, 0, 1, 'INT16', 'ABCD', 0.1, 0, false, '℃'],
        ['humidity', '湿度', 3, 1, 1, 'UINT16', 'ABCD', 0.1, 0, false, '%'],
        ['runStatus', '运行状态', 1, 0, 1, 'BIT', 'ABCD', 1, 0, false, ''],
        ['setTemp', '设定温度', 6, 10, 1, 'INT16', 'ABCD', 0.1, 0, true, '℃'],
    ])
}

function downloadSlaveTemplate() {
    const gatewayDeviceId = hostForm.deviceId || 'modbus_host'
    downloadCsv('modbus-slave-template.csv', [
        ['slaveId', 'deviceId', 'deviceName', 'description'],
        [1, buildSlaveDeviceId(gatewayDeviceId, 1), '从机1', '一号从机'],
        [2, buildSlaveDeviceId(gatewayDeviceId, 2), '从机2', '二号从机'],
    ])
}

function openPaste(type: 'register' | 'slave') {
    pasteState.type = type
    pasteState.text = ''
    pasteState.visible = true
}

function applyPaste() {
    if (pasteState.type === 'register') {
        applyRegisterImport(pasteState.text)
    } else {
        applySlaveImport(pasteState.text)
    }
    pasteState.visible = false
}

function applyRegisterImport(text: string) {
    const result = parseRegisterMapText(text)
    if (result.rows.length) {
        selectedSlaveType.value.registerRows = result.rows
    }
    showImportErrors(result.errors)
    if (!result.errors.length) onlyMessage('寄存器表导入成功')
}

function applySlaveImport(text: string) {
    const result = parseSlaveText(text, hostForm.deviceId || 'modbus_host')
    if (result.rows.length) {
        slaveRows.value = result.rows.map(item => ({
            ...item,
            typeId: item.typeId || slaveTypes.value[0]?.id,
        }))
    }
    showImportErrors(result.errors)
    if (!result.errors.length) onlyMessage('从机列表导入成功')
}

function showImportErrors(errors: string[]) {
    importErrors.value = errors
    importErrorVisible.value = !!errors.length
    if (errors.length) {
        onlyMessage('存在校验问题，请查看提示并修正', 'warning')
    }
}

function validatePolicy(policy: Partial<CollectionPolicy>, name: string) {
    const errors: string[] = []
    const scan = Number(policy.scanIntervalMs)
    const dispatch = Number(policy.dispatchIntervalMs)
    const storage = Number(policy.storageIntervalMs)
    const timeout = Number(policy.responseTimeoutMs)
    if (!Number.isFinite(scan) || scan <= 0) errors.push(`${name}: 采集频率必须大于 0`)
    if (!Number.isFinite(dispatch) || dispatch < 0) errors.push(`${name}: 下发间隔不能小于 0`)
    if (!Number.isFinite(storage) || storage <= 0) errors.push(`${name}: 存储频率必须大于 0`)
    if (!Number.isFinite(timeout) || timeout <= 0) errors.push(`${name}: 响应超时必须大于 0`)
    if (Number.isFinite(scan) && Number.isFinite(storage) && storage < scan) {
        errors.push(`${name}: 存储频率不能小于采集频率`)
    }
    return errors
}

function validateStep(step: number) {
    const errors: string[] = []
    if (step === 0) {
        if (!accessForm.name) errors.push('请输入接入名称')
        if (connectionForm.mode === 'PLATFORM_CONNECTS_GATEWAY') {
            if (!connectionForm.host) errors.push('请输入主机 IP')
            if (!connectionForm.port) errors.push('请输入主机端口')
        } else if (!connectionForm.listenPort) {
            errors.push('请输入监听端口')
        }
    }
    if (step === 1 && !hostForm.name) {
        errors.push('请输入主机名称')
    }
    if (step === 2) {
        slaveTypes.value.forEach((type, index) => {
            if (!type.id) errors.push(`第 ${index + 1} 个从机产品缺少产品编号`)
            if (!type.name) errors.push(`第 ${index + 1} 个从机产品缺少产品名称`)
            errors.push(...validateRegisterRows(type.registerRows).map(item => `${type.name || type.id}: ${item}`))
        })
    }
    if (step === 3) {
        slaveRows.value.forEach(fillSlaveGeneratedFields)
        errors.push(...validateSlaveRows(slaveRows.value))
        slaveRows.value.forEach((row, index) => {
            if (!row.typeId) errors.push(`从机列表第 ${index + 1} 行: 请选择从机产品`)
        })
    }
    if (step === 4) {
        errors.push(...validatePolicy(defaultPolicy, '默认策略'))
        slaveTypes.value.forEach(type => errors.push(...validatePolicy(type.collectionPolicy, `${type.name || type.id} 策略`)))
        slaveRows.value.forEach((row) => {
            const override = slavePolicyPayload(row)
            if (override) {
                const type = slaveTypes.value.find(item => item.id === row.typeId)
                errors.push(...validatePolicy({ ...(type?.collectionPolicy || defaultPolicy), ...override }, `${row.deviceName || row.deviceId} 覆盖策略`))
            }
        })
    }
    return errors
}

function validateAll() {
    return [0, 1, 2, 3, 4].flatMap(validateStep)
}

function goNext() {
    const errors = validateStep(currentStep.value)
    if (errors.length) {
        showImportErrors(errors)
        return
    }
    currentStep.value += 1
}

function isOk(resp: any) {
    if (resp?.success === false) return false
    return resp?.success || resp?.status === 200
}

function buildRegisterPayload(rows: RegisterMappingRow[]) {
    return rows
        .filter(item => item.propertyId)
        .map(item => ({
            propertyId: item.propertyId,
            propertyName: item.propertyName || item.propertyId,
            functionCode: Number(item.functionCode),
            address: Number(item.address),
            quantity: Number(item.quantity),
            dataType: item.dataType,
            byteOrder: item.byteOrder,
            scale: Number(item.scale),
            offset: Number(item.offset),
            writable: !!item.writable,
            unit: item.unit || undefined,
        }))
}

function slavePolicyPayload(row: SlaveForm) {
    const payload: Record<string, any> = {}
    ;(['scanIntervalMs', 'dispatchIntervalMs', 'storageIntervalMs', 'responseTimeoutMs'] as const).forEach((key) => {
        if (row[key] !== undefined && row[key] !== null) {
            payload[key] = row[key]
        }
    })
    return Object.keys(payload).length ? payload : undefined
}

function buildPayload() {
    slaveRows.value.forEach(fillSlaveGeneratedFields)
    return {
        id: accessForm.id || undefined,
        name: accessForm.name,
        connection: { ...connectionForm },
        host: {
            deviceId: hostForm.deviceId || undefined,
            name: hostForm.name,
            description: hostForm.description,
        },
        slaveTypes: slaveTypes.value.map(type => ({
            id: type.id,
            name: type.name,
            registerMap: buildRegisterPayload(type.registerRows),
            collectionPolicy: { ...type.collectionPolicy },
        })),
        slaves: slaveRows.value.map(row => ({
            deviceId: row.deviceId,
            name: row.deviceName,
            description: row.description,
            typeId: row.typeId,
            slaveId: Number(row.slaveId),
            collectionPolicy: slavePolicyPayload(row),
        })),
        defaultPolicy: { ...defaultPolicy },
    }
}

async function handleSaveAndTest() {
    const errors = validateAll()
    if (errors.length) {
        showImportErrors(errors)
        return
    }

    saving.value = true
    testResults.value = []
    try {
        const resp = await saveModbusAccess(buildPayload())
        if (!isOk(resp)) throw new Error(resp?.message || '保存失败')
        saveResult.value = resp?.result || resp
        savedDeviceIds.value = saveResult.value?.slaveDeviceIds || slaveRows.value.map(item => item.deviceId).filter(Boolean)
        await runCommunicationTest(savedDeviceIds.value)
        currentStep.value = 5
        onlyMessage('Modbus 接入配置已保存')
    } catch (error: any) {
        onlyMessage(error?.message || '保存失败', 'error')
    } finally {
        saving.value = false
    }
}

function resolveTestPropertyIds() {
    if (testPropertyIds.value.length) return testPropertyIds.value
    return readablePropertyIds.value.slice(0, 1)
}

async function runCommunicationTest(deviceIds: string[]) {
    const propertyIds = resolveTestPropertyIds()
    const targets = testDeviceIds.value.length ? testDeviceIds.value : deviceIds
    if (!propertyIds.length || !targets.length) return

    testing.value = true
    const rows: TestResultRow[] = []
    try {
        for (const deviceId of targets) {
            for (const propertyId of propertyIds) {
                try {
                    const resp = await testReadProperties(deviceId, [propertyId])
                    rows.push({
                        key: `${deviceId}_${propertyId}`,
                        deviceId,
                        propertyId,
                        status: isOk(resp) ? 'success' : 'error',
                        value: pickPropertyValue(resp?.result, propertyId),
                        message: isOk(resp) ? '读取成功' : (resp?.message || '读取失败'),
                    })
                } catch (error: any) {
                    rows.push({
                        key: `${deviceId}_${propertyId}`,
                        deviceId,
                        propertyId,
                        status: 'error',
                        message: error?.message || '读取异常',
                    })
                }
            }
        }
        testResults.value = rows
    } finally {
        testing.value = false
    }
}

function pickPropertyValue(result: any, propertyId: string) {
    if (Array.isArray(result)) {
        const item = result.find(value => value?.property === propertyId || value?.propertyId === propertyId)
        return item?.value ?? item?.data?.value ?? item ?? result
    }
    if (result && typeof result === 'object' && propertyId in result) {
        const value = result[propertyId]
        return value?.value ?? value?.data?.value ?? value
    }
    return result
}

function formatValue(value: unknown) {
    if (value === undefined || value === null) return '--'
    if (typeof value === 'string') return value
    return JSON.stringify(value)
}
</script>

<style scoped lang="less">
.modbus-access-page {
    min-height: 100%;
    padding: 24px;
    background: #f5f7fb;
}

.page-head,
.steps-wrap,
.content-panel,
.footer-actions {
    max-width: 1280px;
    margin: 0 auto;
}

.page-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    margin-bottom: 16px;
}

.page-title {
    color: #1f2937;
    font-weight: 600;
    font-size: 22px;
    line-height: 30px;
}

.page-subtitle {
    color: rgba(0, 0, 0, 0.55);
    font-size: 13px;
}

.steps-wrap {
    padding: 18px 24px;
    margin-bottom: 16px;
    background: #fff;
    border: 1px solid #edf0f5;
    border-radius: 6px;
}

.content-panel {
    min-height: 560px;
    padding: 24px;
    background: #fff;
    border: 1px solid #edf0f5;
    border-radius: 6px;
}

.step-panel {
    min-height: 500px;
}

.section-title {
    margin-bottom: 16px;
    color: #1f2937;
    font-weight: 600;
    font-size: 16px;
}

.section-title.sub {
    margin-top: 24px;
}

.type-layout {
    display: grid;
    grid-template-columns: 220px minmax(0, 1fr);
    gap: 16px;
}

.type-list {
    padding: 12px;
    border: 1px solid #edf0f5;
    border-radius: 6px;
}

.type-item {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
    padding: 10px 8px;
    margin-top: 8px;
    cursor: pointer;
    border: 1px solid transparent;
    border-radius: 4px;
}

.type-item.active {
    background: #eef6ff;
    border-color: #8cc8ff;
}

.type-name {
    overflow: hidden;
    color: #1f2937;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.table-toolbar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 12px;
}

.test-actions {
    margin-bottom: 16px;
}

.result-table,
.advanced-info {
    margin-top: 16px;
}

.footer-actions {
    display: flex;
    justify-content: flex-end;
    padding: 16px 0 4px;
}

.error-list {
    max-height: 320px;
    padding-left: 20px;
    margin: 16px 0 0;
    overflow: auto;
}

@media (max-width: 960px) {
    .page-head {
        align-items: flex-start;
        flex-direction: column;
    }

    .type-layout {
        grid-template-columns: 1fr;
    }
}
</style>

<template>
    <j-page-container>
        <FullPage :fixed="false">
            <div class="modbus-access-page">
                <div class="page-head">
                    <div>
                        <div class="page-title">Modbus 接入向导</div>
                        <div class="page-subtitle">
                            集中完成网关、从机产品、寄存器映射、从机列表和保存后通讯测试配置。
                        </div>
                    </div>
                    <a-space>
                        <a-button :loading="loading" @click="loadBaseData">
                            <template #icon><AIcon type="ReloadOutlined" /></template>
                            刷新
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
                        <a-step title="接入网关" />
                        <a-step title="网关设备" />
                        <a-step title="点位映射" />
                        <a-step title="从机列表" />
                        <a-step title="保存测试" />
                    </a-steps>
                </div>

                <div class="content-panel">
                    <a-spin :spinning="loading || saving || testing">
                        <a-form layout="vertical">
                            <section v-show="currentStep === 0" class="step-panel">
                                <div class="section-title">选择已有 Modbus 接入网关</div>
                                <a-alert
                                    show-icon
                                    type="info"
                                    message="第一版只复用已有设备接入网关，不在此页面创建网络组件或接入网关。"
                                />
                                <a-row :gutter="16" class="form-row">
                                    <a-col :span="12">
                                        <a-form-item label="接入网关">
                                            <a-select
                                                v-model:value="selectedAccessId"
                                                show-search
                                                option-filter-prop="label"
                                                placeholder="请选择 Modbus RTU 接入网关"
                                            >
                                                <a-select-option
                                                    v-for="item in modbusAccessList"
                                                    :key="item.id"
                                                    :value="item.id"
                                                    :label="item.name"
                                                >
                                                    {{ item.name }}
                                                </a-select-option>
                                            </a-select>
                                        </a-form-item>
                                    </a-col>
                                    <a-col :span="12">
                                        <a-form-item label="协议">
                                            <a-input :value="selectedAccessProtocol" disabled />
                                        </a-form-item>
                                    </a-col>
                                </a-row>
                                <a-empty
                                    v-if="!modbusAccessList.length"
                                    description="未查询到 Modbus RTU 接入网关，请先在设备接入中创建。"
                                />
                            </section>

                            <section v-show="currentStep === 1" class="step-panel">
                                <div class="section-title">网关产品与网关设备</div>
                                <a-row :gutter="16">
                                    <a-col :span="12">
                                        <a-form-item label="网关产品">
                                            <a-radio-group v-model:value="gatewayProductMode" button-style="solid">
                                                <a-radio-button value="select">选择已有</a-radio-button>
                                                <a-radio-button value="create">新建</a-radio-button>
                                            </a-radio-group>
                                        </a-form-item>
                                        <a-form-item v-if="gatewayProductMode === 'select'" label="选择网关产品">
                                            <a-select
                                                v-model:value="selectedGatewayProductId"
                                                show-search
                                                allow-clear
                                                option-filter-prop="label"
                                                placeholder="请选择 deviceType=gateway 的产品"
                                            >
                                                <a-select-option
                                                    v-for="item in gatewayProducts"
                                                    :key="item.id"
                                                    :value="item.id"
                                                    :label="item.name"
                                                >
                                                    {{ item.name }}（{{ item.id }}）
                                                </a-select-option>
                                            </a-select>
                                        </a-form-item>
                                        <template v-else>
                                            <a-form-item label="产品 ID">
                                                <a-input v-model:value="gatewayProductForm.id" placeholder="留空由系统生成" />
                                            </a-form-item>
                                            <a-form-item label="产品名称">
                                                <a-input v-model:value="gatewayProductForm.name" placeholder="例如：Modbus 网关产品" />
                                            </a-form-item>
                                        </template>
                                    </a-col>

                                    <a-col :span="12">
                                        <a-form-item label="网关设备">
                                            <a-radio-group v-model:value="gatewayDeviceMode" button-style="solid">
                                                <a-radio-button value="select">选择已有</a-radio-button>
                                                <a-radio-button value="create">新建</a-radio-button>
                                            </a-radio-group>
                                        </a-form-item>
                                        <a-form-item v-if="gatewayDeviceMode === 'select'" label="选择网关设备">
                                            <a-select
                                                v-model:value="selectedGatewayDeviceId"
                                                show-search
                                                allow-clear
                                                option-filter-prop="label"
                                                placeholder="请选择网关产品下的设备"
                                            >
                                                <a-select-option
                                                    v-for="item in gatewayDeviceList"
                                                    :key="item.id"
                                                    :value="item.id"
                                                    :label="item.name"
                                                >
                                                    {{ item.name }}（{{ item.id }}）
                                                </a-select-option>
                                            </a-select>
                                        </a-form-item>
                                        <template v-else>
                                            <a-form-item label="设备 ID">
                                                <a-input v-model:value="gatewayDeviceForm.id" placeholder="留空由系统生成" />
                                            </a-form-item>
                                            <a-form-item label="设备名称">
                                                <a-input v-model:value="gatewayDeviceForm.name" placeholder="例如：Modbus 网关 1" />
                                            </a-form-item>
                                        </template>
                                    </a-col>
                                </a-row>

                                <div class="section-title sub">通讯参数</div>
                                <a-row :gutter="16">
                                    <a-col :span="8">
                                        <a-form-item label="响应超时(ms)">
                                            <a-input-number
                                                v-model:value="communicationForm.responseTimeoutMs"
                                                :min="100"
                                                :max="60000"
                                                style="width: 100%"
                                            />
                                        </a-form-item>
                                    </a-col>
                                    <a-col :span="8">
                                        <a-form-item label="探测周期(ms)">
                                            <a-input-number
                                                v-model:value="communicationForm.probeIntervalMs"
                                                :min="1000"
                                                :max="3600000"
                                                style="width: 100%"
                                            />
                                        </a-form-item>
                                    </a-col>
                                    <a-col :span="8">
                                        <a-form-item label="保活超时(s)">
                                            <a-input-number
                                                v-model:value="communicationForm.keepOnlineTimeout"
                                                :min="10"
                                                :max="86400"
                                                style="width: 100%"
                                            />
                                        </a-form-item>
                                    </a-col>
                                </a-row>
                                <a-alert
                                    show-icon
                                    type="warning"
                                    message="当前协议读取超时会从从机产品配置读取；保存时会把通讯参数同时写入网关产品和从机产品。"
                                />
                            </section>

                            <section v-show="currentStep === 2" class="step-panel">
                                <div class="section-title">从机产品与 registerMap</div>
                                <a-row :gutter="16">
                                    <a-col :span="12">
                                        <a-form-item label="从机产品">
                                            <a-radio-group v-model:value="slaveProductMode" button-style="solid">
                                                <a-radio-button value="select">选择已有</a-radio-button>
                                                <a-radio-button value="create">新建</a-radio-button>
                                            </a-radio-group>
                                        </a-form-item>
                                        <a-form-item v-if="slaveProductMode === 'select'" label="选择从机产品">
                                            <a-select
                                                v-model:value="selectedSlaveProductId"
                                                show-search
                                                allow-clear
                                                option-filter-prop="label"
                                                placeholder="请选择 deviceType=childrenDevice 的产品"
                                            >
                                                <a-select-option
                                                    v-for="item in slaveProducts"
                                                    :key="item.id"
                                                    :value="item.id"
                                                    :label="item.name"
                                                >
                                                    {{ item.name }}（{{ item.id }}）
                                                </a-select-option>
                                            </a-select>
                                        </a-form-item>
                                        <template v-else>
                                            <a-form-item label="产品 ID">
                                                <a-input v-model:value="slaveProductForm.id" placeholder="留空由系统生成" />
                                            </a-form-item>
                                            <a-form-item label="产品名称">
                                                <a-input v-model:value="slaveProductForm.name" placeholder="例如：Modbus 从机产品" />
                                            </a-form-item>
                                        </template>
                                    </a-col>
                                    <a-col :span="12">
                                        <a-form-item label="测试点位">
                                            <a-select
                                                v-model:value="testPropertyIds"
                                                mode="multiple"
                                                allow-clear
                                                :options="readablePropertyOptions"
                                                placeholder="默认测试第一个可读点位"
                                            />
                                        </a-form-item>
                                    </a-col>
                                </a-row>

                                <div class="table-toolbar">
                                    <a-space>
                                        <a-upload accept=".csv,.txt" :show-upload-list="false" :before-upload="beforeRegisterUpload">
                                            <a-button>
                                                <template #icon><AIcon type="UploadOutlined" /></template>
                                                导入 CSV
                                            </a-button>
                                        </a-upload>
                                        <a-button @click="openPaste('register')">
                                            <template #icon><AIcon type="CopyOutlined" /></template>
                                            粘贴导入
                                        </a-button>
                                        <a-button type="dashed" @click="addRegisterRow">
                                            <template #icon><AIcon type="PlusOutlined" /></template>
                                            添加点位
                                        </a-button>
                                    </a-space>
                                    <span class="toolbar-note">
                                        表头支持 propertyId、fc/functionCode、addr/address、qty/quantity。
                                    </span>
                                </div>

                                <a-table
                                    row-key="key"
                                    size="small"
                                    bordered
                                    :pagination="false"
                                    :columns="registerColumns"
                                    :data-source="registerRows"
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
                                                <a-select-option :value="1">0x01 线圈读</a-select-option>
                                                <a-select-option :value="2">0x02 离散输入读</a-select-option>
                                                <a-select-option :value="3">0x03 保持寄存器读</a-select-option>
                                                <a-select-option :value="4">0x04 输入寄存器读</a-select-option>
                                                <a-select-option :value="5">0x05 单线圈写</a-select-option>
                                                <a-select-option :value="6">0x06 单寄存器写</a-select-option>
                                                <a-select-option :value="15">0x0F 多线圈写</a-select-option>
                                                <a-select-option :value="16">0x10 多寄存器写</a-select-option>
                                            </a-select>
                                        </template>
                                        <template v-else-if="column.key === 'address'">
                                            <a-input-number v-model:value="record.address" size="small" :min="0" :max="65535" style="width: 100%" />
                                        </template>
                                        <template v-else-if="column.key === 'quantity'">
                                            <a-input-number v-model:value="record.quantity" size="small" :min="1" :max="125" style="width: 100%" />
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
                                            <a-input-number v-model:value="record.scale" size="small" :precision="6" style="width: 100%" />
                                        </template>
                                        <template v-else-if="column.key === 'offset'">
                                            <a-input-number v-model:value="record.offset" size="small" :precision="6" style="width: 100%" />
                                        </template>
                                        <template v-else-if="column.key === 'writable'">
                                            <a-checkbox v-model:checked="record.writable" />
                                        </template>
                                        <template v-else-if="column.key === 'unit'">
                                            <a-input v-model:value="record.unit" size="small" placeholder="单位" />
                                        </template>
                                        <template v-else-if="column.key === 'action'">
                                            <a-button type="link" danger size="small" @click="removeRegisterRow(index)">删除</a-button>
                                        </template>
                                    </template>
                                </a-table>
                            </section>

                            <section v-show="currentStep === 3" class="step-panel">
                                <div class="section-title">从机列表</div>
                                <div class="table-toolbar">
                                    <a-space>
                                        <a-upload accept=".csv,.txt" :show-upload-list="false" :before-upload="beforeSlaveUpload">
                                            <a-button>
                                                <template #icon><AIcon type="UploadOutlined" /></template>
                                                导入 CSV
                                            </a-button>
                                        </a-upload>
                                        <a-button @click="openPaste('slave')">
                                            <template #icon><AIcon type="CopyOutlined" /></template>
                                            粘贴导入
                                        </a-button>
                                        <a-button type="dashed" @click="addSlaveRow">
                                            <template #icon><AIcon type="PlusOutlined" /></template>
                                            添加从机
                                        </a-button>
                                    </a-space>
                                    <span class="toolbar-note">
                                        表头支持 slaveId、deviceId、deviceName、description；deviceId/name 可自动生成。
                                    </span>
                                </div>

                                <a-table
                                    row-key="key"
                                    size="small"
                                    bordered
                                    :pagination="false"
                                    :columns="slaveColumns"
                                    :data-source="slaveRows"
                                    :scroll="{ x: 960 }"
                                >
                                    <template #bodyCell="{ column, record, index }">
                                        <template v-if="column.key === 'slaveId'">
                                            <a-input-number
                                                v-model:value="record.slaveId"
                                                size="small"
                                                :min="1"
                                                :max="247"
                                                style="width: 100%"
                                                @change="fillSlaveGeneratedFields(record)"
                                            />
                                        </template>
                                        <template v-else-if="column.key === 'deviceId'">
                                            <a-input
                                                v-model:value="record.deviceId"
                                                size="small"
                                                placeholder="设备ID"
                                                @change="record.autoDeviceId = false"
                                            />
                                        </template>
                                        <template v-else-if="column.key === 'deviceName'">
                                            <a-input
                                                v-model:value="record.deviceName"
                                                size="small"
                                                placeholder="设备名称"
                                                @change="record.autoDeviceName = false"
                                            />
                                        </template>
                                        <template v-else-if="column.key === 'description'">
                                            <a-input v-model:value="record.description" size="small" placeholder="说明" />
                                        </template>
                                        <template v-else-if="column.key === 'action'">
                                            <a-button type="link" danger size="small" @click="removeSlaveRow(index)">删除</a-button>
                                        </template>
                                    </template>
                                </a-table>
                            </section>

                            <section v-show="currentStep === 4" class="step-panel">
                                <div class="section-title">保存后通讯测试</div>
                                <a-row :gutter="16">
                                    <a-col :span="12">
                                        <a-form-item label="测试从机">
                                            <a-select
                                                v-model:value="testDeviceIds"
                                                mode="multiple"
                                                allow-clear
                                                :options="slaveDeviceOptions"
                                                placeholder="默认测试全部导入从机"
                                            />
                                        </a-form-item>
                                    </a-col>
                                    <a-col :span="12">
                                        <a-form-item label="测试属性">
                                            <a-select
                                                v-model:value="testPropertyIds"
                                                mode="multiple"
                                                allow-clear
                                                :options="readablePropertyOptions"
                                                placeholder="默认测试第一个可读点位"
                                            />
                                        </a-form-item>
                                    </a-col>
                                </a-row>

                                <a-alert
                                    show-icon
                                    type="info"
                                    message="点击“保存并测试”后会先保存产品、物模型、从机设备和 slaveId，再调用属性读取接口发起标准 Modbus RTU 读请求。"
                                />

                                <a-table
                                    class="test-table"
                                    row-key="key"
                                    size="small"
                                    :columns="testColumns"
                                    :data-source="testResults"
                                    :pagination="{ pageSize: 8 }"
                                    :scroll="{ x: 920 }"
                                >
                                    <template #bodyCell="{ column, record }">
                                        <template v-if="column.key === 'status'">
                                            <a-tag :color="record.status === 'success' ? 'green' : 'red'">
                                                {{ record.status === 'success' ? '成功' : '失败' }}
                                            </a-tag>
                                        </template>
                                        <template v-else-if="column.key === 'value'">
                                            <span class="mono">{{ formatValue(record.value) }}</span>
                                        </template>
                                    </template>
                                </a-table>
                            </section>
                        </a-form>
                    </a-spin>
                </div>

                <div class="footer-actions">
                    <a-space>
                        <a-button :disabled="currentStep === 0" @click="currentStep -= 1">上一步</a-button>
                        <a-button v-if="currentStep < 4" type="primary" @click="goNext">下一步</a-button>
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

                <a-modal
                    v-model:open="pasteState.visible"
                    :title="pasteState.type === 'register' ? '粘贴 registerMap' : '粘贴从机列表'"
                    width="760px"
                    ok-text="导入"
                    cancel-text="取消"
                    @ok="applyPaste"
                >
                    <a-textarea
                        v-model:value="pasteState.text"
                        :rows="12"
                        placeholder="从 Excel 复制后直接粘贴，第一行需要是表头。"
                    />
                </a-modal>

                <a-modal
                    v-model:open="importErrorVisible"
                    title="导入/校验提示"
                    width="760px"
                    :footer="null"
                >
                    <a-alert
                        v-for="item in importErrors"
                        :key="item"
                        class="error-item"
                        type="error"
                        show-icon
                        :message="item"
                    />
                </a-modal>
            </div>
        </FullPage>
    </j-page-container>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { onlyMessage } from '@jetlinks-web/utils'
import {
    addProduct,
    detail as getProductDetail,
    modify as modifyProduct,
    queryGatewayList,
    queryNoPagingPost as queryProductNoPaging,
    updateDevice as updateProductAccess,
} from '../../../api/product'
import {
    addDevice,
    detail as getDeviceDetail,
    editDevice,
    isExists,
    queryNoPagingPost as queryDeviceNoPaging,
    saveDeviceConfig,
    testReadProperties,
} from '../../../api/instance'
import type { RegisterMappingRow, SlaveRow, TestResultRow } from './types'
import {
    BYTE_ORDER_OPTIONS,
    DATA_TYPE_OPTIONS,
    DEFAULT_COMMUNICATION_CONFIG,
    MODBUS_PROTOCOL_ID,
    buildMetadataFromRegisterMap,
    buildSlaveDeviceId,
    createRegisterRow,
    createSlaveRow,
    getReadablePropertyIds,
    parseRegisterMapText,
    parseRegisterMapValue,
    parseSlaveText,
    serializeRegisterMap,
    validateRegisterRows,
    validateSlaveRows,
} from './utils'

const currentStep = ref(0)
const loading = ref(false)
const saving = ref(false)
const testing = ref(false)
const accessList = ref<any[]>([])
const productList = ref<any[]>([])
const gatewayDeviceList = ref<any[]>([])
const slaveDeviceList = ref<any[]>([])
const selectedAccessId = ref<string>()
const selectedGatewayProductId = ref<string>()
const selectedGatewayDeviceId = ref<string>()
const selectedSlaveProductId = ref<string>()
const gatewayProductMode = ref<'select' | 'create'>('select')
const gatewayDeviceMode = ref<'select' | 'create'>('select')
const slaveProductMode = ref<'select' | 'create'>('select')
const registerRows = ref<RegisterMappingRow[]>([createRegisterRow()])
const slaveRows = ref<SlaveRow[]>([createSlaveRow(1)])
const testPropertyIds = ref<string[]>([])
const testDeviceIds = ref<string[]>([])
const testResults = ref<TestResultRow[]>([])
const importErrors = ref<string[]>([])
const importErrorVisible = ref(false)

const gatewayProductForm = reactive({
    id: '',
    name: 'Modbus 网关产品',
})

const gatewayDeviceForm = reactive({
    id: '',
    name: 'Modbus 网关 1',
})

const slaveProductForm = reactive({
    id: '',
    name: 'Modbus 从机产品',
})

const communicationForm = reactive({ ...DEFAULT_COMMUNICATION_CONFIG })

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
    { title: 'slaveId', key: 'slaveId', width: 120 },
    { title: '设备ID', key: 'deviceId', width: 240 },
    { title: '设备名称', key: 'deviceName', width: 180 },
    { title: '说明', key: 'description', width: 220 },
    { title: '操作', key: 'action', width: 80, fixed: 'right' },
]

const testColumns = [
    { title: '设备ID', dataIndex: 'deviceId', key: 'deviceId', width: 220 },
    { title: '属性ID', dataIndex: 'propertyId', key: 'propertyId', width: 160 },
    { title: '状态', dataIndex: 'status', key: 'status', width: 90 },
    { title: '值', dataIndex: 'value', key: 'value', width: 220 },
    { title: '消息', dataIndex: 'message', key: 'message' },
]

const selectedAccess = computed(() => accessList.value.find(item => item.id === selectedAccessId.value))

const selectedAccessProtocol = computed(() => {
    const access = selectedAccess.value
    return access?.protocolDetail?.name || access?.protocolName || access?.protocol || MODBUS_PROTOCOL_ID
})

const modbusAccessList = computed(() => accessList.value.filter(isModbusAccess))

const gatewayProducts = computed(() => productList.value.filter(item => isProductType(item, 'gateway') && isProductForSelectedAccess(item)))

const slaveProducts = computed(() => productList.value.filter(item => isProductType(item, 'childrenDevice') && isProductForSelectedAccess(item)))

const readablePropertyIds = computed(() => getReadablePropertyIds(registerRows.value))

const readablePropertyOptions = computed(() => readablePropertyIds.value.map(id => ({
    label: registerRows.value.find(item => item.propertyId === id)?.propertyName || id,
    value: id,
})))

const slaveDeviceOptions = computed(() => slaveRows.value.map(item => ({
    label: item.deviceName ? `${item.deviceName}（${item.deviceId}）` : item.deviceId,
    value: item.deviceId,
})).filter(item => item.value))

function normalizeResultList(resp: any) {
    if (Array.isArray(resp?.result?.data)) return resp.result.data
    if (Array.isArray(resp?.result)) return resp.result
    return []
}

function isOk(resp: any) {
    if (resp?.success === false) return false
    return resp?.success || resp?.status === 200
}

function getDeviceType(value: any) {
    return value?.deviceType?.value || value?.deviceType
}

function getProductProtocol(value: any) {
    return value?.messageProtocol || value?.protocol || value?.protocolId
}

function isProductType(value: any, type: string) {
    return getDeviceType(value) === type
}

function isProductForSelectedAccess(value: any) {
    if (!selectedAccessId.value) return true
    return !value.accessId || value.accessId === selectedAccessId.value || getProductProtocol(value) === MODBUS_PROTOCOL_ID
}

function isModbusAccess(value: any) {
    const raw = [
        value?.name,
        value?.provider,
        value?.protocol,
        value?.messageProtocol,
        value?.protocolId,
        value?.protocolDetail?.id,
        value?.protocolDetail?.name,
        value?.transportDetail?.id,
        value?.transportDetail?.name,
    ].filter(Boolean).join(' ').toLowerCase()

    return raw.includes(MODBUS_PROTOCOL_ID) || raw.includes('modbus')
}

function termEq(column: string, value: any) {
    return {
        column,
        termType: 'eq',
        value,
    }
}

async function loadBaseData() {
    loading.value = true
    try {
        const [accessResp, productResp] = await Promise.all([
            queryGatewayList({
                paging: false,
                sorts: [{ name: 'createTime', order: 'desc' }],
            }),
            queryProductNoPaging({
                paging: false,
                sorts: [{ name: 'createTime', order: 'desc' }],
            }),
        ])
        accessList.value = normalizeResultList(accessResp)
        productList.value = normalizeResultList(productResp)
        if (!selectedAccessId.value && modbusAccessList.value.length) {
            selectedAccessId.value = modbusAccessList.value[0].id
        }
    } finally {
        loading.value = false
    }
}

async function loadGatewayDevices(productId?: string) {
    if (!productId) {
        gatewayDeviceList.value = []
        return
    }
    const resp = await queryDeviceNoPaging({
        paging: false,
        terms: [termEq('productId', productId)],
    })
    gatewayDeviceList.value = normalizeResultList(resp)
}

async function loadSlaveDevices(productId?: string, parentId?: string) {
    if (!productId) {
        slaveDeviceList.value = []
        return
    }
    const terms = [termEq('productId', productId)]
    if (parentId) terms.push(termEq('parentId', parentId))
    const resp = await queryDeviceNoPaging({ paging: false, terms })
    const devices = normalizeResultList(resp)
    slaveDeviceList.value = devices
    if (parentId && devices.length) {
        slaveRows.value = await Promise.all(devices.map(toSlaveRow))
    }
}

async function toSlaveRow(device: any): Promise<SlaveRow> {
    let detail = device
    if (!detail?.configuration && !detail?.cachedConfiguration) {
        const detailResp = await getDeviceDetail(device.id, true).catch(() => undefined)
        detail = detailResp?.result || device
    }
    const config = detail?.configuration || detail?.cachedConfiguration || {}
    const slaveId = Number(config.slaveId)
    return {
        key: device.id,
        slaveId: Number.isInteger(slaveId) ? slaveId : undefined,
        deviceId: device.id,
        deviceName: device.name,
        description: device.describe || device.description || '',
        autoDeviceId: false,
        autoDeviceName: false,
    }
}

async function loadProductDetail(id: string) {
    const resp = await getProductDetail(id)
    return resp?.result || productList.value.find(item => item.id === id) || { id }
}

function applyCommunicationConfig(product: any) {
    const configuration = product?.configuration || {}
    communicationForm.responseTimeoutMs = Number(configuration.responseTimeoutMs || DEFAULT_COMMUNICATION_CONFIG.responseTimeoutMs)
    communicationForm.probeIntervalMs = Number(configuration.probeIntervalMs || DEFAULT_COMMUNICATION_CONFIG.probeIntervalMs)
    communicationForm.keepOnlineTimeout = Number(configuration.keepOnlineTimeout || DEFAULT_COMMUNICATION_CONFIG.keepOnlineTimeout)
}

watch(selectedGatewayProductId, async (id) => {
    selectedGatewayDeviceId.value = undefined
    await loadGatewayDevices(id)
    if (id) {
        const product = await loadProductDetail(id)
        applyCommunicationConfig(product)
    }
})

watch(selectedSlaveProductId, async (id) => {
    await loadSlaveDevices(id, selectedGatewayDeviceId.value)
    if (!id) return
    const product = await loadProductDetail(id)
    const rows = parseRegisterMapValue(product?.configuration?.registerMap)
    registerRows.value = rows.length ? rows : [createRegisterRow()]
    if (!testPropertyIds.value.length) {
        testPropertyIds.value = readablePropertyIds.value.slice(0, 1)
    }
})

watch(selectedGatewayDeviceId, async (id) => {
    slaveRows.value.forEach(row => fillSlaveGeneratedFields(row))
    await loadSlaveDevices(selectedSlaveProductId.value, id)
})

watch(registerRows, () => {
    testPropertyIds.value = testPropertyIds.value.filter(id => readablePropertyIds.value.includes(id))
}, { deep: true })

function addRegisterRow() {
    registerRows.value.push(createRegisterRow())
}

function removeRegisterRow(index: number) {
    registerRows.value.splice(index, 1)
}

function addSlaveRow() {
    const nextSlaveId = findNextSlaveId()
    slaveRows.value.push(createSlaveRow(nextSlaveId, selectedGatewayDeviceId.value || gatewayDeviceForm.id))
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

function fillSlaveGeneratedFields(row: SlaveRow, gatewayDeviceId?: string) {
    if (!row.slaveId) return
    const gatewayId = gatewayDeviceId || selectedGatewayDeviceId.value || gatewayDeviceForm.id || 'modbus_gateway'
    if (!row.deviceId || row.autoDeviceId) row.deviceId = buildSlaveDeviceId(gatewayId, row.slaveId)
    if (!row.deviceName || row.autoDeviceName) row.deviceName = `从机${row.slaveId}`
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

function showImportErrors(errors: string[]) {
    importErrors.value = errors
    importErrorVisible.value = !!errors.length
    if (errors.length) {
        onlyMessage('导入存在校验问题，请查看提示并修正', 'warning')
    }
}

function applyRegisterImport(text: string) {
    const result = parseRegisterMapText(text)
    if (result.rows.length) {
        registerRows.value = result.rows
    }
    showImportErrors(result.errors)
    if (!result.errors.length) onlyMessage('registerMap 导入成功')
}

function applySlaveImport(text: string) {
    const result = parseSlaveText(text, selectedGatewayDeviceId.value || gatewayDeviceForm.id)
    if (result.rows.length) {
        slaveRows.value = result.rows
    }
    showImportErrors(result.errors)
    if (!result.errors.length) onlyMessage('从机列表导入成功')
}

function validateStep(step: number) {
    if (step === 0 && !selectedAccessId.value) return ['请选择 Modbus 接入网关']

    if (step === 1) {
        const errors: string[] = []
        if (gatewayProductMode.value === 'select' && !selectedGatewayProductId.value) errors.push('请选择网关产品')
        if (gatewayProductMode.value === 'create' && !gatewayProductForm.name) errors.push('请输入网关产品名称')
        if (gatewayDeviceMode.value === 'select' && !selectedGatewayDeviceId.value) errors.push('请选择网关设备')
        if (gatewayDeviceMode.value === 'create' && !gatewayDeviceForm.name) errors.push('请输入网关设备名称')
        return errors
    }

    if (step === 2) {
        const errors: string[] = []
        if (slaveProductMode.value === 'select' && !selectedSlaveProductId.value) errors.push('请选择从机产品')
        if (slaveProductMode.value === 'create' && !slaveProductForm.name) errors.push('请输入从机产品名称')
        return [...errors, ...validateRegisterRows(registerRows.value)]
    }

    if (step === 3) {
        slaveRows.value.forEach(fillSlaveGeneratedFields)
        return validateSlaveRows(slaveRows.value)
    }

    return []
}

function validateAll() {
    return [0, 1, 2, 3].flatMap(validateStep)
}

function goNext() {
    const errors = validateStep(currentStep.value)
    if (errors.length) {
        showImportErrors(errors)
        return
    }
    currentStep.value += 1
}

async function ensureProduct(mode: 'select' | 'create', productId: string | undefined, form: { id: string; name: string }, deviceType: string) {
    if (mode === 'select') {
        return loadProductDetail(productId!)
    }
    const payload: Record<string, any> = {
        name: form.name,
        deviceType,
    }
    if (form.id) payload.id = form.id
    const resp = await addProduct(payload)
    if (!isOk(resp)) throw new Error(`创建产品 ${form.name} 失败`)
    const id = resp?.result?.id || form.id
    if (!id) throw new Error(`创建产品 ${form.name} 后未返回产品ID`)
    return loadProductDetail(id)
}

async function saveProductAccessAndConfig(product: any, configuration: Record<string, any>, metadata?: string) {
    const access = selectedAccess.value
    if (!access) throw new Error('未选择接入网关')
    const productId = product.id
    const mergedConfiguration = {
        ...(product.configuration || {}),
        ...configuration,
    }

    const accessResp = await updateProductAccess({
        ...product,
        id: productId,
        metadata: metadata ?? product.metadata,
        transportProtocol: access.transport || access.transportProtocol || 'TCP',
        protocolName: access.protocolDetail?.name || access.protocolName || 'Modbus RTU (TCP 透传)',
        accessId: access.id,
        accessName: access.name,
        accessProvider: access.provider,
        messageProtocol: access.protocol || access.messageProtocol || MODBUS_PROTOCOL_ID,
    })
    if (!isOk(accessResp)) throw new Error(`保存产品 ${productId} 接入信息失败`)

    const configResp = await modifyProduct(productId, {
        id: productId,
        configuration: mergedConfiguration,
        storePolicy: product.storePolicy,
        metadata: metadata ?? product.metadata,
    })
    if (!isOk(configResp)) throw new Error(`保存产品 ${productId} 配置失败`)

    return loadProductDetail(productId)
}

async function ensureGatewayDevice(productId: string) {
    if (gatewayDeviceMode.value === 'select') {
        return selectedGatewayDeviceId.value!
    }

    const payload: Record<string, any> = {
        name: gatewayDeviceForm.name,
        productId,
    }
    if (gatewayDeviceForm.id) payload.id = gatewayDeviceForm.id
    const resp = await addDevice(payload)
    if (!isOk(resp)) throw new Error(`创建网关设备 ${gatewayDeviceForm.name} 失败`)
    const id = resp?.result?.id || gatewayDeviceForm.id
    if (!id) throw new Error(`创建网关设备 ${gatewayDeviceForm.name} 后未返回设备ID`)
    return id
}

async function saveSlaveDevices(productId: string, gatewayDeviceId: string) {
    const savedIds: string[] = []
    for (const row of slaveRows.value) {
        fillSlaveGeneratedFields(row, gatewayDeviceId)
        const deviceId = row.deviceId!
        const existsResp = await isExists(deviceId)
        const exists = !!existsResp?.result
        const payload = {
            id: deviceId,
            name: row.deviceName || `从机${row.slaveId}`,
            productId,
            parentId: gatewayDeviceId,
            describe: row.description,
        }
        const saveResp = exists ? await editDevice(payload) : await addDevice(payload)
        if (!isOk(saveResp)) throw new Error(`保存从机设备 ${deviceId} 失败`)

        let oldConfig = {}
        if (exists) {
            const detailResp = await getDeviceDetail(deviceId, true).catch(() => undefined)
            oldConfig = detailResp?.result?.configuration || detailResp?.result?.cachedConfiguration || {}
        }
        const configResp = await saveDeviceConfig(deviceId, {
            ...oldConfig,
            slaveId: Number(row.slaveId),
        })
        if (!isOk(configResp)) throw new Error(`保存从机 ${deviceId} slaveId 失败`)
        savedIds.push(deviceId)
    }
    return savedIds
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
        const commonConfig = { ...communicationForm }
        const gatewayProduct = await ensureProduct(
            gatewayProductMode.value,
            selectedGatewayProductId.value,
            gatewayProductForm,
            'gateway',
        )
        const savedGatewayProduct = await saveProductAccessAndConfig(gatewayProduct, commonConfig, gatewayProduct.metadata)
        const gatewayDeviceId = await ensureGatewayDevice(savedGatewayProduct.id)
        selectedGatewayDeviceId.value = gatewayDeviceId

        const slaveProduct = await ensureProduct(
            slaveProductMode.value,
            selectedSlaveProductId.value,
            slaveProductForm,
            'childrenDevice',
        )
        const metadata = buildMetadataFromRegisterMap(registerRows.value, slaveProduct.metadata)
        const savedSlaveProduct = await saveProductAccessAndConfig(
            slaveProduct,
            {
                ...commonConfig,
                registerMap: serializeRegisterMap(registerRows.value),
            },
            JSON.stringify(metadata),
        )
        selectedSlaveProductId.value = savedSlaveProduct.id

        const savedSlaveDeviceIds = await saveSlaveDevices(savedSlaveProduct.id, gatewayDeviceId)
        await loadBaseData()
        await loadGatewayDevices(savedGatewayProduct.id)
        await loadSlaveDevices(savedSlaveProduct.id, gatewayDeviceId)
        await runCommunicationTest(savedSlaveDeviceIds)
        onlyMessage('Modbus 配置已保存')
        currentStep.value = 4
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

async function runCommunicationTest(savedDeviceIds: string[]) {
    const propertyIds = resolveTestPropertyIds()
    const deviceIds = testDeviceIds.value.length ? testDeviceIds.value : savedDeviceIds
    if (!propertyIds.length || !deviceIds.length) return

    testing.value = true
    const rows: TestResultRow[] = []
    try {
        for (const deviceId of deviceIds) {
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

function formatValue(value: unknown) {
    if (value === undefined || value === null) return '--'
    if (typeof value === 'string') return value
    return JSON.stringify(value)
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

onMounted(() => {
    loadBaseData()
})
</script>

<style scoped lang="less">
.modbus-access-page {
    min-height: 100%;
    padding: 24px;
    background: #f5f7fb;
}

.page-head,
.content-panel,
.footer-actions,
.steps-wrap {
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

.page-subtitle,
.toolbar-note {
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
    margin-top: 8px;
}

.form-row {
    margin-top: 16px;
}

.table-toolbar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    margin: 8px 0 12px;
}

.footer-actions {
    display: flex;
    justify-content: flex-end;
    padding: 16px 0 0;
}

.error-item + .error-item {
    margin-top: 8px;
}

.test-table {
    margin-top: 16px;
}

.mono {
    font-family: Consolas, Monaco, monospace;
}

:deep(.ant-table-cell) {
    vertical-align: middle;
}

@media (max-width: 768px) {
    .modbus-access-page {
        padding: 12px;
    }

    .page-head,
    .table-toolbar {
        align-items: flex-start;
        flex-direction: column;
    }

    .content-panel {
        padding: 16px;
    }
}
</style>

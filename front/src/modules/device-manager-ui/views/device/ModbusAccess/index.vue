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

                <section class="guide-panel" aria-label="Modbus 页面操作指南">
                    <div class="guide-head">
                        <div>
                            <div class="guide-label">页面操作指南</div>
                            <div class="guide-title">{{ currentGuide.title }}</div>
                        </div>
                        <a-button type="link" size="small" @click="guideExpanded = !guideExpanded">
                            {{ guideExpanded ? '收起说明' : '展开说明' }}
                        </a-button>
                    </div>
                    <div class="guide-summary">{{ currentGuide.summary }}</div>
                    <div v-if="guideExpanded" class="guide-body">
                        <ol class="guide-list">
                            <li v-for="item in currentGuide.items" :key="item">{{ item }}</li>
                        </ol>
                        <div v-if="currentGuide.example" class="guide-example">
                            <span class="guide-example-label">填写提示</span>
                            <span>{{ currentGuide.example }}</span>
                        </div>
                        <div class="guide-next">
                            完成后：{{ currentGuide.next }}
                        </div>
                    </div>
                </section>

                <div class="content-panel">
                    <a-spin :spinning="loading || saving || testing">
                        <a-form layout="vertical">
                            <section v-show="currentStep === 0" class="step-panel">
                                <div class="section-title">配置 Modbus 接入网关</div>
                                <a-form-item label="配置方式">
                                    <a-radio-group
                                        v-model:value="accessMode"
                                        button-style="solid"
                                        :disabled="!!quickCreated.networkId || !!quickCreated.accessId"
                                    >
                                        <a-radio-button value="existing">选择已有</a-radio-button>
                                        <a-radio-button value="tcp-server" :disabled="!tcpServerSupported">
                                            新建 TCP 服务端
                                        </a-radio-button>
                                        <a-radio-button value="tcp-client" :disabled="!tcpClientSupported">
                                            新建 TCP 客户端
                                        </a-radio-button>
                                    </a-radio-group>
                                </a-form-item>
                                <a-alert
                                    v-if="accessMode === 'existing'"
                                    show-icon
                                    type="info"
                                    message="选择已有接入网关时，本向导不会修改或自动启动它，请先确认其网络组件和网关已启动。"
                                />
                                <a-alert
                                    v-else-if="!currentQuickModeSupported"
                                    show-icon
                                    type="error"
                                    :message="quickModeUnsupportedMessage"
                                />

                                <template v-if="accessMode === 'existing'">
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
                                        description="未查询到 Modbus RTU 接入网关，可切换为 TCP 服务端或客户端在本页创建。"
                                    />
                                </template>

                                <template v-else>
                                    <a-row :gutter="16" class="form-row">
                                        <a-col :span="12">
                                            <a-form-item label="网络组件名称">
                                                <a-input
                                                    v-model:value="tcpQuickForm.networkName"
                                                    :disabled="!!quickCreated.networkId"
                                                    :maxlength="64"
                                                    placeholder="例如：Modbus TCP 网络组件"
                                                />
                                            </a-form-item>
                                        </a-col>
                                        <a-col :span="12">
                                            <a-form-item label="接入网关名称">
                                                <a-input
                                                    v-model:value="tcpQuickForm.accessName"
                                                    :disabled="!!quickCreated.accessId"
                                                    :maxlength="64"
                                                    placeholder="例如：Modbus TCP 接入网关"
                                                />
                                            </a-form-item>
                                        </a-col>
                                    </a-row>
                                    <a-row :gutter="16">
                                        <a-col :span="12">
                                            <a-form-item :label="accessMode === 'tcp-server' ? '监听地址' : '远程主机'">
                                                <a-input
                                                    v-model:value="tcpQuickForm.host"
                                                    :disabled="!!quickCreated.networkId"
                                                    :placeholder="accessMode === 'tcp-server' ? '0.0.0.0' : '设备网关 IP 或域名'"
                                                />
                                            </a-form-item>
                                        </a-col>
                                        <a-col :span="12">
                                            <a-form-item :label="accessMode === 'tcp-server' ? '监听端口' : '远程端口'">
                                                <a-input-number
                                                    v-model:value="tcpQuickForm.port"
                                                    :disabled="!!quickCreated.networkId"
                                                    :min="1"
                                                    :max="65535"
                                                    style="width: 100%"
                                                />
                                            </a-form-item>
                                        </a-col>
                                    </a-row>
                                    <a-row v-if="accessMode === 'tcp-server'" :gutter="16">
                                        <a-col :span="12">
                                            <a-form-item label="公网地址">
                                                <a-input
                                                    v-model:value="tcpQuickForm.publicHost"
                                                    :disabled="!!quickCreated.networkId"
                                                    placeholder="设备实际连接的平台地址"
                                                />
                                            </a-form-item>
                                        </a-col>
                                        <a-col :span="12">
                                            <a-form-item label="公网端口">
                                                <a-input-number
                                                    v-model:value="tcpQuickForm.publicPort"
                                                    :disabled="!!quickCreated.networkId"
                                                    :min="1"
                                                    :max="65535"
                                                    style="width: 100%"
                                                />
                                            </a-form-item>
                                        </a-col>
                                    </a-row>
                                    <a-form-item label="说明">
                                        <a-textarea
                                            v-model:value="tcpQuickForm.description"
                                            :disabled="!!quickCreated.networkId && !!quickCreated.accessId"
                                            :maxlength="200"
                                            :rows="2"
                                            show-count
                                        />
                                    </a-form-item>

                                    <a-collapse ghost>
                                        <a-collapse-panel key="tls" header="TLS（可选）">
                                            <a-row :gutter="16">
                                                <a-col :span="8">
                                                    <a-form-item label="启用 TLS">
                                                        <a-switch
                                                            v-model:checked="tcpQuickForm.tlsEnabled"
                                                            :disabled="!!quickCreated.networkId"
                                                        />
                                                    </a-form-item>
                                                </a-col>
                                                <a-col :span="16">
                                                    <a-form-item v-if="tcpQuickForm.tlsEnabled" label="证书">
                                                        <a-select
                                                            v-model:value="tcpQuickForm.certId"
                                                            :disabled="!!quickCreated.networkId"
                                                            show-search
                                                            option-filter-prop="label"
                                                            :options="certificateOptions"
                                                            placeholder="请选择已有网络证书"
                                                        />
                                                    </a-form-item>
                                                </a-col>
                                            </a-row>
                                        </a-collapse-panel>
                                    </a-collapse>

                                    <a-alert
                                        show-icon
                                        type="info"
                                        message="Modbus RTU over TCP 固定使用 DIRECT 负载解析器；网络组件和接入网关会在“保存并测试”时创建并启动。"
                                    />
                                    <a-alert
                                        v-if="quickCreated.networkId || quickCreated.accessId"
                                        class="form-row"
                                        show-icon
                                        type="success"
                                        :message="quickCreatedMessage"
                                        description="后续步骤失败时将保留这些资源，本页面重试会复用已有 ID。"
                                    />
                                </template>
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

                                <div class="section-title sub">轮询计划</div>
                                <ModbusPollingConfigEditor
                                    v-model="pollingForm"
                                    mode="product"
                                    :readable-properties="readablePropertyOptions"
                                />
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
                                                @change="handleSlaveIdChange(record)"
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
                                        <template v-else-if="column.key === 'pollOverrideEnabled'">
                                            <a-switch v-model:checked="record.pollOverrideEnabled" />
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
                                <a-descriptions class="access-summary" bordered size="small" :column="2">
                                    <a-descriptions-item label="接入方式">
                                        {{ accessModeLabel }}
                                    </a-descriptions-item>
                                    <a-descriptions-item label="连接地址">
                                        {{ accessEndpointSummary }}
                                    </a-descriptions-item>
                                    <a-descriptions-item label="网络组件">
                                        {{ quickCreated.networkId || selectedAccess?.channelInfo?.id || selectedAccess?.channelId || '--' }}
                                        <a-tag v-if="quickCreated.networkState" color="green">
                                            {{ quickCreated.networkState }}
                                        </a-tag>
                                    </a-descriptions-item>
                                    <a-descriptions-item label="接入网关">
                                        {{ selectedAccess?.name || tcpQuickForm.accessName || '--' }}
                                        <a-tag v-if="quickCreated.accessState" color="green">
                                            {{ quickCreated.accessState }}
                                        </a-tag>
                                    </a-descriptions-item>
                                </a-descriptions>
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
import type { TableColumnsType } from 'ant-design-vue'
import ModbusPollingConfigEditor from '../../../../../components/ModbusPollingConfigEditor/index.vue'
import {
    addProduct,
    detail as getProductDetail,
    modify as modifyProduct,
    queryGatewayList,
    queryNoPagingPost as queryProductNoPaging,
    updateDevice as updateProductAccess,
    _deploy as deployProduct,
} from '../../../api/product'
import {
    addDevice,
    detail as getDeviceDetail,
    editDevice,
    isExists,
    queryNoPagingPost as queryDeviceNoPaging,
    saveDeviceConfig,
    bindDevice,
    _deploy as deployDevice,
    testReadProperties,
} from '../../../api/instance'
import {
    certificates as queryCertificates,
    detail as getNetworkDetail,
    save as saveNetwork,
    start as startNetwork,
    supports as queryNetworkSupports,
} from '../../../api/link/type'
import {
    deploy as startAccessGateway,
    detail as getAccessGatewayDetail,
    getProviders as queryAccessProviders,
    save as saveAccessGateway,
    update as updateAccessGateway,
} from '../../../api/link/accessConfig'
import type {
    AccessMode,
    QuickCreatedResources,
    RegisterMappingRow,
    SlaveRow,
    TcpQuickConfigForm,
    TcpQuickCreateMode,
    TestResultRow,
} from './types'
import {
    BYTE_ORDER_OPTIONS,
    DATA_TYPE_OPTIONS,
    DEFAULT_COMMUNICATION_CONFIG,
    MODBUS_PROTOCOL_ID,
    TCP_GATEWAY_PROVIDER,
    TCP_NETWORK_TYPE,
    buildTcpAccessPayload,
    buildTcpNetworkPayload,
    buildMetadataFromRegisterMap,
    buildSlaveDeviceId,
    createRegisterRow,
    createSlaveRow,
    ensureQuickResourceId,
    formatTcpEndpoint,
    getReadablePropertyIds,
    parseRegisterMapText,
    parseRegisterMapValue,
    parseSlaveText,
    serializeRegisterMap,
    validateRegisterRows,
    validateSlaveRows,
    validateTcpQuickConfig,
} from './utils'

const currentStep = ref(0)
const guideExpanded = ref(true)
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
const accessMode = ref<AccessMode>('existing')
const networkSupportIds = ref<string[]>([])
const accessProviderIds = ref<string[]>([])
const certificateOptions = ref<Array<{ label: string; value: string }>>([])
const capabilityLoadFailed = ref(false)
const savingStage = ref('')
const quickCreated = reactive<QuickCreatedResources>({})

const tcpQuickForm = reactive<TcpQuickConfigForm>({
    networkName: 'Modbus TCP 网络组件',
    accessName: 'Modbus TCP 接入网关',
    description: '',
    host: '0.0.0.0',
    port: 502,
    publicHost: '',
    publicPort: 502,
    tlsEnabled: false,
    certId: undefined,
})

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
const pollingForm = reactive<Record<string, any>>({
    pollEnabled: false,
    pollScheduleType: 'FIXED_DELAY',
    pollIntervalMs: 30000,
    pollCron: '0/30 * * * * ?',
    pollDeviceIntervalMs: 100,
    pollFrameIntervalMs: 100,
    pollPropertyIds: [],
    pollRetryCount: 0,
    maxReadRegistersPerRequest: 60,
    maxReadBitsPerRequest: 512,
    maxReadAddressGap: 2,
})

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

const guideSteps = [
    {
        title: '选择已有接入，或快速创建 TCP 接入',
        summary: '本页可复用已有 Modbus 接入，也可创建并启动专用的 TCP 网络组件和接入网关。',
        items: [
            '从机主动连接平台时，网络组件选择 TCP 服务端；平台主动连接现场网关时，选择 TCP 客户端。',
            'TCP 负载解析器使用 DIRECT；不要把 Modbus CRC 配置成 TCP 分隔符。',
            '新建服务端时填写监听地址、监听端口及设备实际访问的公网地址；新建客户端时填写远程主机和端口。',
            'TLS 默认关闭；开启后必须选择已有网络证书。新建资源会在最后保存时自动启动。',
        ],
        example: 'TCP 服务端/客户端只表示连接建立方向，不代表 Modbus 主从身份。当前平台在 Modbus 层作为 Master。',
        next: '点击“下一步”配置网关产品和网关设备。',
    },
    {
        title: '配置网关产品、网关设备和通信参数',
        summary: '网关是父设备，负责承载 TCP 会话；同一 TCP 连接下的从机设备都挂在它下面。',
        items: [
            '选择已有网关产品/设备，或切换为“新建”并填写产品名称、设备名称。',
            '确认网关设备是父设备，不填写 parentId。',
            '响应超时先使用 3000ms；探测周期可先使用 30000ms；保活超时可先使用 120s。',
            '这些通信参数会同步保存到网关产品和从机产品。',
        ],
        example: '不要把 TCP 连接超时、Modbus 响应超时和轮询间隔当成同一个参数。',
        next: '点击“下一步”配置从机产品和点位映射。',
    },
    {
        title: '配置从机产品和点位映射',
        summary: '每一行点位描述一个物模型属性如何读取 Modbus 地址。先配置可读点位，再决定是否加入轮询。',
        items: [
            '选择已有从机产品，或新建从机产品并填写产品名称。',
            '每行填写属性 ID、功能码、地址、数量和数据类型；多寄存器数据再核对字节序、比例和偏移。',
            'FC1/2/3/4 用于读取，FC5/6/15/16 用于写入；读点位不要勾选“可写”。',
            '需要自动采集时，在“轮询计划”中启用轮询并选择采集项；采集项留空表示全部可读点位。',
        ],
        example: '常见保持寄存器读取使用 FC3，地址按设备协议填写；平台地址通常从 0 开始。',
        next: '点击“下一步”逐台填写从机地址和设备信息。',
    },
    {
        title: '添加从机并绑定父网关',
        summary: '每台现场从机对应一台平台子设备，必须绑定当前网关并配置唯一 slaveId。',
        items: [
            '填写 slaveId，范围为 1~247；同一父网关下不能重复。',
            '填写平台设备 ID 和设备名称；设备 ID 只使用字母、数字、下划线和中划线。',
            '确认每台从机都绑定到当前网关，不能把从机当作独立 TCP 网关。',
            '设备需要独立轮询参数时勾选“覆盖产品轮询”，否则保持产品继承。',
        ],
        example: '5 台模拟从机可以使用 slaveId 1、2、3、4、5，共用一个父网关 TCP 连接。',
        next: '点击“下一步”选择测试范围并保存。',
    },
    {
        title: '保存配置并执行人工读取测试',
        summary: '保存会依次写入产品、物模型、从机设备和 slaveId，然后发起人工读取测试。',
        items: [
            '选择要测试的从机和可读属性；不选择时默认测试全部从机的第一个可读点位。',
            '点击“保存并测试”，等待每个测试结果返回。',
            '读取成功后检查返回值、数据类型、字节序、比例和属性更新时间。',
            '读取失败时先检查 TCP 连接、slaveId、功能码、地址、数量和 DIRECT 拆包配置。',
        ],
        example: '人工读取返回 ReadPropertyMessageReply；启用轮询不会改变人工读取的返回类型。',
        next: '查看测试结果；确认成功后再启用或调整轮询周期。',
    },
] as const

const currentGuide = computed(() => guideSteps[currentStep.value] || guideSteps[0])

const registerColumns: TableColumnsType<RegisterMappingRow> = [
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

const slaveColumns: TableColumnsType<SlaveRow> = [
    { title: 'slaveId', key: 'slaveId', width: 120 },
    { title: '设备ID', key: 'deviceId', width: 240 },
    { title: '设备名称', key: 'deviceName', width: 180 },
    { title: '说明', key: 'description', width: 220 },
    { title: '操作', key: 'action', width: 80, fixed: 'right' },
]

slaveColumns.splice(slaveColumns.length - 1, 0, {
    title: '覆盖产品轮询',
    key: 'pollOverrideEnabled',
    width: 130,
})

const testColumns = [
    { title: '设备ID', dataIndex: 'deviceId', key: 'deviceId', width: 220 },
    { title: '属性ID', dataIndex: 'propertyId', key: 'propertyId', width: 160 },
    { title: '状态', dataIndex: 'status', key: 'status', width: 90 },
    { title: '值', dataIndex: 'value', key: 'value', width: 220 },
    { title: '消息', dataIndex: 'message', key: 'message' },
]

const selectedAccess = computed(() => accessList.value.find(item => item.id === selectedAccessId.value))

const quickMode = computed<TcpQuickCreateMode | undefined>(() => (
    accessMode.value === 'existing' ? undefined : accessMode.value
))

const tcpServerSupported = computed(() => (
    networkSupportIds.value.includes(TCP_NETWORK_TYPE['tcp-server'])
    && accessProviderIds.value.includes(TCP_GATEWAY_PROVIDER['tcp-server'])
))

const tcpClientSupported = computed(() => (
    networkSupportIds.value.includes(TCP_NETWORK_TYPE['tcp-client'])
    && accessProviderIds.value.includes(TCP_GATEWAY_PROVIDER['tcp-client'])
))

const currentQuickModeSupported = computed(() => {
    if (accessMode.value === 'tcp-server') return tcpServerSupported.value
    if (accessMode.value === 'tcp-client') return tcpClientSupported.value
    return true
})

const quickModeUnsupportedMessage = computed(() => {
    if (capabilityLoadFailed.value) return '未能读取网络组件或接入网关能力，请刷新后重试。'
    return accessMode.value === 'tcp-client'
        ? '当前后端未同时提供 TCP_CLIENT 和 tcp-client-gateway。'
        : '当前后端未同时提供 TCP_SERVER 和 tcp-server-gateway。'
})

const quickCreatedMessage = computed(() => [
    quickCreated.networkId ? `网络组件：${quickCreated.networkId}` : '',
    quickCreated.accessId ? `接入网关：${quickCreated.accessId}` : '',
].filter(Boolean).join('；'))

const accessModeLabel = computed(() => ({
    existing: '选择已有',
    'tcp-server': 'TCP 服务端（设备主动连接平台）',
    'tcp-client': 'TCP 客户端（平台主动连接设备）',
}[accessMode.value]))

const accessEndpointSummary = computed(() => {
    if (quickMode.value) return formatTcpEndpoint(quickMode.value, tcpQuickForm)
    const addresses = selectedAccess.value?.channelInfo?.addresses || selectedAccess.value?.addresses || []
    if (Array.isArray(addresses) && addresses.length) {
        return addresses.map((item: any) => item.address || item).filter(Boolean).join('、')
    }
    return selectedAccess.value?.channelId || '--'
})

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

function normalizeResultList(resp: any): any[] {
    if (Array.isArray(resp?.result?.data)) return resp.result.data
    if (Array.isArray(resp?.result)) return resp.result
    return []
}

function isOk(resp: any) {
    if (resp?.success === false) return false
    return resp?.success || resp?.status === 200
}

function extractResultId(resp: any) {
    if (typeof resp?.result === 'string') return resp.result
    return resp?.result?.id || resp?.id
}

function stateValue(value: any) {
    const state = value?.state?.value ?? value?.state ?? value
    return state === undefined || state === null ? '' : String(state)
}

function isEnabledState(value: any) {
    return stateValue(value).toLowerCase() === 'enabled'
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
        const [accessResp, productResp, networkSupportResp, accessProviderResp, certificateResp] = await Promise.all([
            queryGatewayList({
                paging: false,
                sorts: [{ name: 'createTime', order: 'desc' }],
            }),
            queryProductNoPaging({
                paging: false,
                sorts: [{ name: 'createTime', order: 'desc' }],
            }),
            queryNetworkSupports().catch(() => undefined),
            queryAccessProviders().catch(() => undefined),
            queryCertificates().catch(() => undefined),
        ])
        accessList.value = normalizeResultList(accessResp)
        productList.value = normalizeResultList(productResp)
        const networkSupports = normalizeResultList(networkSupportResp)
        const accessProviders = normalizeResultList(accessProviderResp)
        networkSupportIds.value = networkSupports.map(item => item.id).filter(Boolean)
        accessProviderIds.value = accessProviders.map(item => item.id).filter(Boolean)
        certificateOptions.value = normalizeResultList(certificateResp).map(item => ({
            label: item.name || item.id,
            value: item.id,
        }))
        capabilityLoadFailed.value = !networkSupportResp || !accessProviderResp
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
        pollOverrideEnabled: !!config.pollOverrideEnabled,
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
    Object.keys(pollingForm).forEach(key => {
        if (product?.configuration?.[key] !== undefined) {
            pollingForm[key] = product.configuration[key]
        }
    })
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

watch(accessMode, (mode) => {
    if (quickCreated.networkId || quickCreated.accessId) return
    if (mode === 'tcp-server') {
        if (!tcpQuickForm.host) tcpQuickForm.host = '0.0.0.0'
        if (!tcpQuickForm.publicPort) tcpQuickForm.publicPort = tcpQuickForm.port
    } else if (mode === 'tcp-client' && tcpQuickForm.host === '0.0.0.0') {
        tcpQuickForm.host = ''
    }
})

watch(() => tcpQuickForm.tlsEnabled, (enabled) => {
    if (!enabled && !quickCreated.networkId) tcpQuickForm.certId = undefined
})

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

function handleSlaveIdChange(row: Record<string, any>) {
    fillSlaveGeneratedFields(row as SlaveRow)
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
    if (step === 0) {
        if (accessMode.value === 'existing') {
            return selectedAccessId.value ? [] : ['请选择 Modbus 接入网关']
        }
        if (!currentQuickModeSupported.value) return [quickModeUnsupportedMessage.value]
        return validateTcpQuickConfig(quickMode.value!, tcpQuickForm)
    }

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
        slaveRows.value.forEach(row => fillSlaveGeneratedFields(row))
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

function upsertAccess(access: any) {
    const index = accessList.value.findIndex(item => item.id === access.id)
    if (index >= 0) {
        accessList.value.splice(index, 1, access)
    } else {
        accessList.value.unshift(access)
    }
}

async function ensureQuickAccess(gatewayDeviceId: string, childDeviceIds: string[]) {
    if (!quickMode.value) return selectedAccess.value

    const mode = quickMode.value
    quickCreated.networkId = await ensureQuickResourceId(quickCreated.networkId, async () => {
        savingStage.value = '创建 TCP 网络组件'
        const networkResp = await saveNetwork(buildTcpNetworkPayload(mode, tcpQuickForm))
        if (!isOk(networkResp)) throw new Error((networkResp as any)?.message || '创建 TCP 网络组件失败')
        const networkId = extractResultId(networkResp)
        if (!networkId) throw new Error('创建 TCP 网络组件后未返回 ID')
        return networkId
    })

    quickCreated.accessId = await ensureQuickResourceId(quickCreated.accessId, async () => {
        savingStage.value = '创建设备接入网关'
        const payload = buildTcpAccessPayload(
            mode,
            tcpQuickForm,
            quickCreated.networkId!,
            gatewayDeviceId,
            childDeviceIds,
        )
        const accessResp = await saveAccessGateway(payload)
        if (!isOk(accessResp)) throw new Error((accessResp as any)?.message || '创建设备接入网关失败')
        const accessId = extractResultId(accessResp)
        if (!accessId) throw new Error('创建设备接入网关后未返回 ID')
        upsertAccess({
            ...payload,
            ...(typeof accessResp?.result === 'object' ? accessResp.result : {}),
            id: accessId,
            protocolDetail: { id: MODBUS_PROTOCOL_ID, name: 'Modbus RTU (TCP 透传)' },
        })
        return accessId
    })

    selectedAccessId.value = quickCreated.accessId
    return selectedAccess.value
}

async function startAndVerifyQuickAccess() {
    if (!quickMode.value) return
    if (!quickCreated.networkId || !quickCreated.accessId) {
        throw new Error('TCP 网络组件或接入网关尚未创建')
    }

    savingStage.value = '启动 TCP 网络组件'
    const networkStartResp = await startNetwork(quickCreated.networkId)
    if (!isOk(networkStartResp)) throw new Error((networkStartResp as any)?.message || '启动 TCP 网络组件失败')
    const networkDetailResp = await getNetworkDetail(quickCreated.networkId)
    if (!isOk(networkDetailResp) || !isEnabledState(networkDetailResp?.result)) {
        throw new Error('TCP 网络组件启动后状态未变为 enabled')
    }
    quickCreated.networkState = '已启动'

    savingStage.value = '启动设备接入网关'
    const accessStartResp = await startAccessGateway(quickCreated.accessId)
    if (!isOk(accessStartResp)) throw new Error((accessStartResp as any)?.message || '启动设备接入网关失败')
    const accessDetailResp = await getAccessGatewayDetail(quickCreated.accessId)
    if (!isOk(accessDetailResp) || !isEnabledState(accessDetailResp?.result)) {
        throw new Error('设备接入网关启动后状态未变为 enabled')
    }
    quickCreated.accessState = '已启动'
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
            configuration: {
                slaveId: Number(row.slaveId),
                pollOverrideEnabled: !!row.pollOverrideEnabled,
                ...(row.pollOverrideEnabled ? { ...pollingForm } : {}),
            },
            describe: row.description,
        }
        let saveResp: any
        try {
            saveResp = exists ? await editDevice(payload) : await addDevice(payload)
        } catch (error) {
            saveResp = { status: 500, message: (error as any)?.message }
        }
        if (!isOk(saveResp)) {
            // 部分后端版本会先落库、再在 SaveResult 响应转换时返回 500。
            // 重新查询确认已落库后继续，避免重试重复创建。
            const persistedResp = await isExists(deviceId).catch(() => undefined)
            if (!persistedResp?.result) throw new Error(`保存从机设备 ${deviceId} 失败`)
            saveResp = persistedResp
        }

        let oldConfig = {}
        if (exists) {
            const detailResp = await getDeviceDetail(deviceId, true).catch(() => undefined)
            oldConfig = detailResp?.result?.configuration || detailResp?.result?.cachedConfiguration || {}
        }
        if (exists) {
            const configResp = await saveDeviceConfig(deviceId, {
                ...oldConfig,
                ...payload.configuration,
            })
            if (!isOk(configResp)) throw new Error(`保存从机 ${deviceId} slaveId 失败`)
        }
        savedIds.push(deviceId)
    }
    if (savedIds.length) {
        const bindResp = await bindDevice(gatewayDeviceId, savedIds)
        if (!isOk(bindResp)) throw new Error(`绑定 ${savedIds.length} 台从机到网关失败`)
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
        savingStage.value = '准备网关产品'
        const gatewayProduct = await ensureProduct(
            gatewayProductMode.value,
            selectedGatewayProductId.value,
            gatewayProductForm,
            'gateway',
        )
        savingStage.value = '准备网关设备'
        // 快速 TCP 模式先准备接入配置使用的父设备 ID，再保存产品接入方式；
        // 否则先创建设备后修改产品接入会被后端拒绝。
        let gatewayDeviceId: string
        if (quickMode.value && gatewayDeviceMode.value === 'create') {
            if (!gatewayDeviceForm.id) gatewayDeviceForm.id = `modbus_gateway_${Date.now()}`
            gatewayDeviceId = gatewayDeviceForm.id
        } else {
            gatewayDeviceId = await ensureGatewayDevice(gatewayProduct.id)
        }
        selectedGatewayDeviceId.value = gatewayDeviceId

        savingStage.value = '准备从机产品'
        const slaveProduct = await ensureProduct(
            slaveProductMode.value,
            selectedSlaveProductId.value,
            slaveProductForm,
            'childrenDevice',
        )
        const metadata = buildMetadataFromRegisterMap(registerRows.value, slaveProduct.metadata)
        slaveRows.value.forEach(row => fillSlaveGeneratedFields(row, gatewayDeviceId))
        savingStage.value = '保存并绑定从机设备'
        // childrenDevice 保存时后端会读取产品接入配置，必须先完成 TCP
        // 接入网关和两个产品的接入/物模型配置，再创建从机实例。
        await ensureQuickAccess(gatewayDeviceId, [])
        if (!selectedAccess.value) throw new Error('未能取得 Modbus 接入网关')

        savingStage.value = '保存网关产品接入配置'
        const savedGatewayProduct = await saveProductAccessAndConfig(gatewayProduct, commonConfig, gatewayProduct.metadata)
        if (quickMode.value && gatewayDeviceMode.value === 'create') {
            savingStage.value = '鍑嗗缃戝叧璁惧'
            await ensureGatewayDevice(savedGatewayProduct.id)
        }
        if (quickMode.value) {
            savingStage.value = '鍚敤 Modbus浜у搧'
            const gatewayProductDeployResp = await deployProduct(savedGatewayProduct.id)
            if (!isOk(gatewayProductDeployResp)) throw new Error('启用 Modbus 网关产品失败')
        }

        savingStage.value = '保存从机产品和点位配置'
        const savedSlaveProduct = await saveProductAccessAndConfig(
            slaveProduct,
            {
                ...commonConfig,
                ...pollingForm,
                registerMap: serializeRegisterMap(registerRows.value),
            },
            JSON.stringify(metadata),
        )
        selectedSlaveProductId.value = savedSlaveProduct.id
        if (quickMode.value) {
            const slaveProductDeployResp = await deployProduct(savedSlaveProduct.id)
            if (!isOk(slaveProductDeployResp)) throw new Error('启用 Modbus 从机产品失败')
        }

        const savedSlaveDeviceIds = await saveSlaveDevices(slaveProduct.id, gatewayDeviceId)
        if (quickMode.value && quickCreated.accessId) {
            const accessUpdateResp = await updateAccessGateway({
                ...buildTcpAccessPayload(
                    quickMode.value,
                    tcpQuickForm,
                    quickCreated.networkId!,
                    gatewayDeviceId,
                    savedSlaveDeviceIds,
                ),
                id: quickCreated.accessId,
            })
            if (!isOk(accessUpdateResp)) throw new Error('更新 TCP 接入网关从机列表失败')
        }

        if (quickMode.value) {
            savingStage.value = '鍚敤 Modbus 缃戝叧璁惧'
            const gatewayDeployResp = await deployDevice(gatewayDeviceId)
            if (!isOk(gatewayDeployResp)) throw new Error('启用 Modbus 网关设备失败')
            for (const deviceId of savedSlaveDeviceIds) {
                const deployResp = await deployDevice(deviceId)
                if (!isOk(deployResp)) throw new Error(`启用从机设备 ${deviceId} 失败`)
            }
        }

        await startAndVerifyQuickAccess()
        savingStage.value = '刷新配置状态'
        await loadBaseData()
        await loadGatewayDevices(savedGatewayProduct.id)
        await loadSlaveDevices(savedSlaveProduct.id, gatewayDeviceId)
        savingStage.value = '执行 Modbus 通讯测试'
        await runCommunicationTest(savedSlaveDeviceIds)
        onlyMessage('Modbus 配置已保存')
        currentStep.value = 4
    } catch (error: any) {
        const message = error?.message || '保存失败'
        onlyMessage(savingStage.value ? `${savingStage.value}失败：${message}` : message, 'error')
    } finally {
        saving.value = false
        savingStage.value = ''
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
                        message: isOk(resp) ? '读取成功' : ((resp as any)?.message || '读取失败'),
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
.steps-wrap,
.guide-panel {
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

.guide-panel {
    padding: 16px 20px;
    margin-bottom: 16px;
    background: #f8fbff;
    border: 1px solid #dbeafe;
    border-radius: 6px;
}

.guide-head {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 16px;
}

.guide-label {
    margin-bottom: 4px;
    color: #2563eb;
    font-size: 12px;
    font-weight: 600;
}

.guide-title {
    color: #1f2937;
    font-size: 16px;
    font-weight: 600;
    line-height: 24px;
}

.guide-summary {
    margin-top: 6px;
    color: rgba(0, 0, 0, 0.65);
    line-height: 22px;
}

.guide-body {
    margin-top: 10px;
}

.guide-list {
    margin: 0;
    padding-left: 20px;
    color: #374151;
    line-height: 24px;
}

.guide-example,
.guide-next {
    padding: 8px 10px;
    margin-top: 10px;
    border-radius: 4px;
    line-height: 22px;
}

.guide-example {
    background: #fff;
    border: 1px solid #e5e7eb;
}

.guide-example-label {
    margin-right: 8px;
    color: #2563eb;
    font-weight: 600;
}

.guide-next {
    color: #166534;
    background: #f0fdf4;
    border: 1px solid #bbf7d0;
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

.access-summary {
    margin-bottom: 20px;
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
    .table-toolbar,
    .guide-head {
        align-items: flex-start;
        flex-direction: column;
    }

    .content-panel {
        padding: 16px;
    }
}
</style>

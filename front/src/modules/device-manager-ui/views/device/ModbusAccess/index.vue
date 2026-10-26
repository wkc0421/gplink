<template>
    <j-page-container>
        <FullPage :fixed="false">
            <div class="modbus-access-page">
                <div class="page-head">
                    <div>
                        <div class="page-title">Modbus 接入向导</div>
                        <div class="page-subtitle">
                            集中配置一个采集器下的多类产品，各产品独立维护点位、轮询和从机列表。
                        </div>
                    </div>
                    <a-space>
                        <a-button
                            href="/docs/modbus-tcp-client-config-guide.html"
                            target="_blank"
                            rel="noopener noreferrer"
                        >
                            <template #icon><AIcon type="ReadOutlined" /></template>
                            新手配置说明
                        </a-button>
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
                        <a-step title="接入连接" />
                        <a-step title="采集器" />
                        <a-step title="产品与从机" />
                        <a-step title="确认与测试" />
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
                                        :disabled="!!quickCreated.networkId || !!quickCreated.accessId || !!gatewayCreated.productId"
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
                                    message="复用已有接入时会自动同步当前采集器的从机列表，并保留原有配置和启停状态。停用资源的通讯测试将跳过。"
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
                                                    :value="selectedAccessId"
                                                    @change="value => chooseContext('access', value)"
                                                    :disabled="!!gatewayCreated.productId"
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
                                                    :disabled="saving || loading || testing"
                                                    :placeholder="accessMode === 'tcp-server' ? '0.0.0.0' : '设备网关 IP 或域名'"
                                                />
                                            </a-form-item>
                                        </a-col>
                                        <a-col :span="12">
                                            <a-form-item :label="accessMode === 'tcp-server' ? '监听端口' : '远程端口'">
                                                <a-input-number
                                                    v-model:value="tcpQuickForm.port"
                                                    :disabled="saving || loading || testing"
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
                                                    :disabled="saving || loading || testing"
                                                    placeholder="设备实际连接的平台地址"
                                                />
                                            </a-form-item>
                                        </a-col>
                                        <a-col :span="12">
                                            <a-form-item label="公网端口">
                                                <a-input-number
                                                    v-model:value="tcpQuickForm.publicPort"
                                                    :disabled="saving || loading || testing"
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
                                                            :disabled="saving || loading || testing"
                                                        />
                                                    </a-form-item>
                                                </a-col>
                                                <a-col :span="16">
                                                    <a-form-item v-if="tcpQuickForm.tlsEnabled" label="证书">
                                                        <a-select
                                                            v-model:value="tcpQuickForm.certId"
                                                            :disabled="saving || loading || testing"
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
                                        description="后续步骤失败时将保留这些资源，本页面重试会复用已有 ID。修改连接配置后重试，会先停止本次创建的接入和网络，再应用配置并重新启动。"
                                    />
                                </template>
                            </section>

                            <section v-show="currentStep === 1" class="step-panel">
                                <div class="section-title">网关产品与网关设备</div>
                                <a-row :gutter="16">
                                    <a-col :span="12">
                                        <a-form-item label="网关产品">
                                            <a-radio-group v-model:value="gatewayProductMode" button-style="solid" :disabled="!!gatewayCreated.productId">
                                                <a-radio-button value="select">选择已有</a-radio-button>
                                                <a-radio-button value="create">新建</a-radio-button>
                                            </a-radio-group>
                                        </a-form-item>
                                        <a-form-item v-if="gatewayProductMode === 'select'" label="选择网关产品">
                                            <a-select
                                                :value="selectedGatewayProductId"
                                                @change="value => chooseContext('product', value)"
                                                :disabled="!!gatewayCreated.productId"
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
                                                <a-input v-model:value="gatewayProductForm.id" :disabled="!!gatewayCreated.productId" placeholder="留空由系统生成" />
                                            </a-form-item>
                                            <a-form-item label="产品名称">
                                                <a-input v-model:value="gatewayProductForm.name" :disabled="!!gatewayCreated.productId" placeholder="例如：Modbus 网关产品" />
                                            </a-form-item>
                                        </template>
                                    </a-col>

                                    <a-col :span="12">
                                        <a-form-item label="网关设备">
                                            <a-radio-group v-model:value="gatewayDeviceMode" button-style="solid" :disabled="!!gatewayCreated.productId" @change="handleGatewayDeviceModeChange">
                                                <a-radio-button value="select">选择已有</a-radio-button>
                                                <a-radio-button value="create">新建</a-radio-button>
                                            </a-radio-group>
                                        </a-form-item>
                                        <a-form-item v-if="gatewayDeviceMode === 'select'" label="选择网关设备">
                                            <a-select
                                                :value="selectedGatewayDeviceId"
                                                @change="value => chooseContext('device', value)"
                                                :disabled="!!gatewayCreated.productId"
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
                                                <a-input v-model:value="gatewayDeviceForm.id" :disabled="!!gatewayCreated.productId" placeholder="留空由系统生成" />
                                            </a-form-item>
                                            <a-form-item label="设备名称">
                                                <a-input v-model:value="gatewayDeviceForm.name" :disabled="!!gatewayCreated.productId" placeholder="例如：Modbus 网关 1" />
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
                                <div class="product-group-toolbar">
                                    <a-tabs v-model:activeKey="activeGroupKey" type="card">
                                        <a-tab-pane v-for="group in productGroups" :key="group.key" :tab="groupName(group)" />
                                    </a-tabs>
                                    <a-space>
                                        <a-button @click="addProductGroup">添加产品</a-button>
                                        <a-button danger :disabled="productGroups.length === 1" @click="removeProductGroup">移除当前产品组</a-button>
                                    </a-space>
                                </div>
                                <a-alert v-if="activeGroup.sourceProduct" type="info" show-icon
                                    message="点位、物模型和轮询为共享产品配置，修改会影响其他使用该产品的设备；移除从机仅解除当前采集器绑定。" />
                                <div class="section-title">{{ groupName(activeGroup) }} · 产品与点位</div>
                                <a-row :gutter="16">
                                    <a-col :span="12">
                                        <a-form-item label="从机产品">
                                            <a-radio-group v-model:value="slaveProductMode" button-style="solid" :disabled="activeGroup.locked" @change="handleGroupModeChange">
                                                <a-radio-button value="select">选择已有</a-radio-button>
                                                <a-radio-button value="create">新建</a-radio-button>
                                            </a-radio-group>
                                        </a-form-item>
                                        <a-form-item v-if="slaveProductMode === 'select'" label="选择从机产品">
                                            <a-select
                                                v-model:value="selectedSlaveProductId"
                                                :disabled="activeGroup.locked"
                                                :loading="activeGroup.loading"
                                                @change="handleProductSelection"
                                                show-search
                                                allow-clear
                                                option-filter-prop="label"
                                                placeholder="请选择 deviceType=childrenDevice 的产品"
                                            >
                                                <a-select-option
                                                    v-for="item in availableSlaveProducts"
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
                                </a-row>

                                <div class="table-toolbar">
                                    <a-space>
                                        <a-upload accept=".csv,.txt" :show-upload-list="false" :before-upload="beforeRegisterUpload">
                                            <a-button>
                                                <template #icon><AIcon type="UploadOutlined" /></template>
                                                导入 CSV
                                            </a-button>
                                        </a-upload>
                                        <a-button @click="downloadRegisterTemplate">
                                            <template #icon><AIcon type="DownloadOutlined" /></template>
                                            下载点位模板
                                        </a-button>
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
                                <div class="section-title sub">{{ groupName(activeGroup) }} · 从机列表</div>
                                <a-alert type="info" show-icon message="所有产品共用同一通道，slaveId 在整个采集器下不可重复。CSV 导入替换当前组列表，移除的已有从机将在确认页列出。" />
                                <div class="table-toolbar">
                                    <a-space>
                                        <a-upload accept=".csv,.txt" :show-upload-list="false" :before-upload="beforeSlaveUpload">
                                            <a-button>
                                                <template #icon><AIcon type="UploadOutlined" /></template>
                                                导入 CSV
                                            </a-button>
                                        </a-upload>
                                        <a-button @click="downloadSlaveTemplate">
                                            <template #icon><AIcon type="DownloadOutlined" /></template>
                                            下载从机模板
                                        </a-button>
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
                                                :disabled="record.persisted"
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

                            <section v-show="currentStep === 3" class="step-panel">
                                <div class="section-title">保存后通讯测试</div>
                                <a-alert v-if="savingStage" type="info" show-icon :message="savingStage" />
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
                                <a-card v-for="group in productGroups" :key="group.key" class="group-summary" :title="groupName(group)" size="small">
                                    <p>{{ group.registerRows.length }} 个点位 · {{ group.slaveRows.length }} 台从机 · 新增 {{ group.slaveRows.filter(row => !row.persisted).length }} 台 · 修改 {{ changedSlaveCount(group) }} 台 · 待解绑 {{ pendingRemovals.filter(row => row.sourceDevice?.productId === group.productId).length }} 台 · {{ group.status }}</p>
                                    <p v-if="group.sourceProduct && groupSignature(group) !== group.savedSignature">将修改共享产品的点位 / 轮询配置</p>
                                    <a-row :gutter="16">
                                        <a-col :span="12"><a-form-item label="测试从机">
                                            <a-select v-model:value="group.testDeviceIds" mode="multiple" allow-clear :options="deviceOptions(group)" placeholder="默认测试本产品全部从机" />
                                        </a-form-item></a-col>
                                        <a-col :span="12"><a-form-item label="测试属性">
                                            <a-select v-model:value="group.testPropertyIds" mode="multiple" allow-clear :options="propertyOptions(group)" placeholder="默认测试本产品第一个可读点位" />
                                        </a-form-item></a-col>
                                    </a-row>
                                </a-card>
                                <a-alert v-if="pendingRemovals.length" type="warning" show-icon
                                    :message="`保存时将解绑 ${pendingRemovals.length} 台已有从机（保留设备和历史数据）`"
                                    :description="removalDescription" />
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
                                            <a-tag :color="record.status === 'success' ? 'green' : record.status === 'error' ? 'red' : 'orange'">
                                                {{ ({ success: '成功', error: '失败', waiting: '等待连接', skipped: '已跳过' })[record.status] }}
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
                        <a-button v-if="currentStep < 3" type="primary" @click="goNext">下一步</a-button>
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
import { Modal } from 'ant-design-vue'
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
    unbindBatchDevice,
    _deploy as deployDevice,
    testReadProperties,
} from '../../../api/instance'
import {
    certificates as queryCertificates,
    detail as getNetworkDetail,
    save as saveNetwork,
    shutdown as shutdownNetwork,
    start as startNetwork,
    update as updateNetwork,
    supports as queryNetworkSupports,
} from '../../../api/link/type'
import {
    deploy as startAccessGateway,
    detail as getAccessGatewayDetail,
    getProviders as queryAccessProviders,
    save as saveAccessGateway,
    undeploy as shutdownAccessGateway,
    update as updateAccessGateway,
} from '../../../api/link/accessConfig'
import type {
    AccessMode,
    ProductGroup,
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
    buildTcpNetworkConfigurationUpdate,
    normalizeTcpConnectionConfiguration,
    assertQuickTcpResourceOwnership,
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
    validateTcpQuickConfig,
    waitForDeviceState,
} from './utils'

import { createProductGroup, defaultPollingConfig, groupName, groupSignature, pendingUnbind,
    nextSlaveId, validateProductGroups, mergeChildDeviceIds, sameConfiguration, assertDeviceOwnership, assertProductAccess, deviceRowSignature } from './groups'

const productGroups = ref<ProductGroup[]>([createProductGroup()])
const activeGroupKey = ref(productGroups.value[0].key)
const activeGroup = computed(() => productGroups.value.find(group => group.key === activeGroupKey.value) || productGroups.value[0])
const baselineDevices = ref<SlaveRow[]>([])
const pendingRemovals = computed(() => pendingUnbind(baselineDevices.value, productGroups.value))
const removalDescription = computed(() => {
    const grouped = new Map<string, string[]>()
    pendingRemovals.value.forEach(row => {
        const productId = row.sourceDevice?.productId || ''
        const name = productList.value.find(product => product.id === productId)?.name || productId
        grouped.set(name, [...(grouped.get(name) || []), `${row.deviceName || row.deviceId}（${row.deviceId}）`])
    })
    return [...grouped].map(([name, rows]) => `${name}：${rows.join('、')}`).join('；')
})
const selectedSlaveProductId = computed({ get: () => activeGroup.value.productId, set: value => { activeGroup.value.productId = value } })
const slaveProductMode = computed({ get: () => activeGroup.value.mode, set: value => { activeGroup.value.mode = value } })
const slaveProductForm = computed(() => activeGroup.value.form)
const registerRows = computed({ get: () => activeGroup.value.registerRows, set: value => { activeGroup.value.registerRows = value } })
const slaveRows = computed({ get: () => activeGroup.value.slaveRows, set: value => { activeGroup.value.slaveRows = value } })
const pollingForm = computed({ get: () => activeGroup.value.pollingConfig, set: value => { activeGroup.value.pollingConfig = value } })
const availableSlaveProducts = computed(() => slaveProducts.value.filter(product =>
    !productGroups.value.some(group => group.key !== activeGroupKey.value && group.productId === product.id)))
let collectorLoadVersion = 0
let accessLoadVersion = 0
let contextChoiceVersion = 0
const gatewayCreated = reactive({ productId: '', deviceId: '', productNew: false, deviceNew: false })
const initialCommunication = ref(JSON.stringify(DEFAULT_COMMUNICATION_CONFIG))
const collectorLoadError = ref('')
const currentStep = ref(0)
const guideExpanded = ref(true)
const loading = ref(false)
const saving = ref(false)
const testing = ref(false)
const accessList = ref<any[]>([])
const productList = ref<any[]>([])
const gatewayDeviceList = ref<any[]>([])
const selectedAccessId = ref<string>()
const selectedGatewayProductId = ref<string>()
const selectedGatewayDeviceId = ref<string>()
const gatewayProductMode = ref<'select' | 'create'>('select')
const gatewayDeviceMode = ref<'select' | 'create'>('select')
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



const communicationForm = reactive({ ...DEFAULT_COMMUNICATION_CONFIG })


const pasteState = reactive<{
    visible: boolean
    type: 'register' | 'slave'
    text: string
    groupKey?: string
}>({
    visible: false,
    type: 'register',
    text: '',
})

const dataTypeOptions = DATA_TYPE_OPTIONS
const byteOrderOptions = BYTE_ORDER_OPTIONS

const guideSteps = [
    { title: '配置接入连接', summary: '复用已有 Modbus 接入，或新建 TCP 服务端 / 客户端。',
      items: ['TCP 负载解析器使用 DIRECT。', '新建资源在保存时自动启用；已有停用资源保持原状态。'],
      example: 'TCP 建连方向与 Modbus 主从身份不同，平台在 Modbus 层作为 Master。', next: '选择或新建采集器。' },
    { title: '配置采集器', summary: '同一采集器下的所有产品共用一条 Modbus 通道。',
      items: ['选择已有网关产品和网关设备后，自动加载其全部产品与从机。', '公共通信参数仅在明确修改后同步到各产品。'],
      example: '已有接入网关不可重新指向其他采集器。', next: '逐类配置产品与从机。' },
    { title: '配置多类产品及各自从机', summary: '通过产品列表切换，每组独立配置点位、轮询及从机。',
      items: ['每组单独导入点位与从机 CSV，导入替换当前组列表。', '所有产品的 slaveId 在采集器下保持唯一，范围 1–247。', '共享产品配置的修改会影响其他使用该产品的设备。', '移除已有项在保存时解除绑定，保留资源和历史数据。'],
      example: '电表使用地址 1–13，水表使用地址 14–20，可共用同一个采集器。', next: '查看修改、待解绑列表和各产品测试范围。' },
    { title: '确认并测试', summary: '统一保存各产品与从机，并自动维护接入网关的完整从机列表。',
      items: ['每个产品使用自己的可读点位测试。', '保存失败、通讯失败、等待连接和停用跳过分别显示。', '部分失败时保留已创建资源 ID，修正后可重试。'],
      example: '未选择测试范围时，默认读取本产品每台可测试从机的第一个可读点位。', next: '检查各产品测试结果。' },
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
    { title: '产品', dataIndex: 'productName', key: 'productName', width: 160 },
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

function deviceOptions(group: ProductGroup) {
    return group.slaveRows.map(row => ({ label: `${row.deviceName || row.deviceId}（${row.deviceId}）`, value: row.deviceId }))
}
function propertyOptions(group: ProductGroup) {
    return group.registerRows.filter(row => getReadablePropertyIds(group.registerRows).includes(row.propertyId))
        .map(row => ({ label: row.propertyName || row.propertyId, value: row.propertyId }))
}
function changedSlaveCount(group: ProductGroup) {
    return group.slaveRows.filter(row => row.persisted && row.sourceDevice && deviceRowSignature(row) !== deviceRowSignature(sourceToRow(row.sourceDevice))).length
}
function addProductGroup() {
    const group = createProductGroup()
    productGroups.value.push(group)
    activeGroupKey.value = group.key
}
function removeProductGroup() {
    if (productGroups.value.length <= 1) return
    productGroups.value = productGroups.value.filter(group => group.key !== activeGroupKey.value)
    activeGroupKey.value = productGroups.value[0].key
}
function confirmAction(title: string, content: string): Promise<boolean> {
    return new Promise(resolve => Modal.confirm({ title, content, onOk: () => { resolve(true) }, onCancel: () => resolve(false) }))
}
async function chooseContext(field: 'access' | 'product' | 'device', value?: string) {
    const current = field === 'access' ? selectedAccessId.value : field === 'product' ? selectedGatewayProductId.value : selectedGatewayDeviceId.value
    if (current === value) return
    const version = ++contextChoiceVersion
    const dirty = productGroups.value.some(group => groupSignature(group) !== group.savedSignature && (group.form.name || group.registerRows.some(row => row.propertyId)))
        || productGroups.value.some(group => group.slaveRows.some(row => !row.persisted || (row.sourceDevice && deviceRowSignature(row) !== deviceRowSignature(sourceToRow(row.sourceDevice)))))
        || pendingRemovals.value.length > 0 || JSON.stringify(communicationForm) !== initialCommunication.value
    if (dirty && !await confirmAction('切换采集器配置', '当前有未保存内容，切换后将重新加载。是否继续？')) return
    if (version !== contextChoiceVersion) return
    ++collectorLoadVersion
    ++accessLoadVersion
    loading.value = false
    collectorLoadError.value = ''
    baselineDevices.value = []
    productGroups.value = [createProductGroup()]
    activeGroupKey.value = productGroups.value[0].key
    if (field === 'access') {
        selectedGatewayDeviceId.value = undefined
        selectedGatewayProductId.value = undefined
        selectedAccessId.value = value
    } else if (field === 'product') {
        selectedGatewayDeviceId.value = undefined
        selectedGatewayProductId.value = value
    } else selectedGatewayDeviceId.value = value
}
function handleGroupModeChange() {
    const group = activeGroup.value
    group.loadVersion++
    group.loading = false
    group.productId = undefined
    group.sourceProduct = undefined
    group.form = { id: '', name: '' }
    group.registerRows = [createRegisterRow()]
    group.pollingConfig = defaultPollingConfig()
    group.savedSignature = undefined
}
function handleGatewayDeviceModeChange() {
    if (gatewayDeviceMode.value !== 'create' || currentStep.value !== 1) return
    ++collectorLoadVersion
    collectorLoadError.value = ''
    selectedGatewayDeviceId.value = undefined
    baselineDevices.value = []
    productGroups.value = [createProductGroup()]
    activeGroupKey.value = productGroups.value[0].key
}
async function handleProductSelection(id?: string) {
    const group = activeGroup.value
    const version = ++group.loadVersion
    group.loading = false
    if (!id) { group.sourceProduct = undefined; return }
    group.loading = true
    try {
        const product = await loadProductDetail(id)
        if (version !== group.loadVersion || group.productId !== id || !productGroups.value.includes(group)) return
        assertProductAccess(product, quickMode.value ? quickCreated.accessId : selectedAccessId.value)
        applyProductToGroup(group, product)
    } catch (error: any) {
        if (version === group.loadVersion && group.productId === id && productGroups.value.includes(group)) {
            group.productId = undefined
            group.sourceProduct = undefined
            group.savedSignature = undefined
            onlyMessage(error.message, 'error')
        }
    }
    finally { if (version === group.loadVersion) group.loading = false }
}
function applyProductToGroup(group: ProductGroup, product: any) {
    group.sourceProduct = product
    group.productId = product.id
    group.form = { id: product.id, name: product.name }
    const rows = parseRegisterMapValue(product.configuration?.registerMap)
    group.registerRows = rows.length ? rows : [createRegisterRow()]
    const defaults = defaultPollingConfig()
    group.pollingConfig = Object.fromEntries(Object.entries(defaults).map(([key, value]) => [key, product.configuration?.[key] ?? value]))
    group.savedSignature = groupSignature(group)
    group.status = '已加载'
}
function sourceToRow(device: any): SlaveRow {
    const config = device.configuration || device.cachedConfiguration || {}
    return { key: device.id, deviceId: device.id, slaveId: config.slaveId === undefined ? undefined : Number(config.slaveId),
        deviceName: device.name, description: device.describe || device.description || '', autoDeviceId: false,
        autoDeviceName: false, pollOverrideEnabled: !!config.pollOverrideEnabled, persisted: true, sourceDevice: device }
}

function normalizeResultList(resp: any): any[] {
    if (Array.isArray(resp?.result?.data)) return resp.result.data
    if (Array.isArray(resp?.result)) return resp.result
    return []
}

function isOk(resp: any) {
    if (resp?.success === false || resp?.result?.success === false) return false
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

function isProductType(value: any, type: string) {
    return getDeviceType(value) === type
}

function isProductForSelectedAccess(value: any) {
    if (!selectedAccessId.value) return true
    return !value.accessId || value.accessId === selectedAccessId.value
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
    if (selectedGatewayProductId.value === productId || saving.value) gatewayDeviceList.value = normalizeResultList(resp)
}

async function loadCollectorGroups(parentId: string, version: number) {
    loading.value = true
    collectorLoadError.value = ''
    try {
        const resp = await queryDeviceNoPaging({ paging: false, terms: [termEq('parentId', parentId)] })
        if (!isOk(resp)) throw new Error('加载采集器从机失败，请重试')
        const rows = await Promise.all(normalizeResultList(resp).map(async device => {
            if (!device.configuration && !device.cachedConfiguration) {
                const detail = await getDeviceDetail(device.id, true)
                if (!isOk(detail)) throw new Error(`加载从机 ${device.id} 配置失败`)
                return sourceToRow(detail.result)
            }
            return sourceToRow(device)
        }))
        const productIds = [...new Set(rows.map(row => row.sourceDevice!.productId))]
        const groups = await Promise.all(productIds.map(async id => {
            const group = createProductGroup()
            const product = await loadProductDetail(id)
            group.mode = 'select'
            group.locked = true
            applyProductToGroup(group, product)
            group.slaveRows = rows.filter(row => row.sourceDevice!.productId === id)
            return group
        }))
        if (version !== collectorLoadVersion) return
        baselineDevices.value = JSON.parse(JSON.stringify(rows))
        productGroups.value = groups.length ? groups : [createProductGroup()]
        activeGroupKey.value = productGroups.value[0].key
    } finally { if (version === collectorLoadVersion) loading.value = false }
}

async function loadProductDetail(id: string) {
    const resp = await getProductDetail(id)
    if (!isOk(resp) || !resp?.result?.id) throw new Error(`加载产品 ${id} 失败`)
    return resp.result
}

function applyCommunicationConfig(product: any) {
    const configuration = product?.configuration || {}
    communicationForm.responseTimeoutMs = Number(configuration.responseTimeoutMs || DEFAULT_COMMUNICATION_CONFIG.responseTimeoutMs)
    communicationForm.probeIntervalMs = Number(configuration.probeIntervalMs || DEFAULT_COMMUNICATION_CONFIG.probeIntervalMs)
    communicationForm.keepOnlineTimeout = Number(configuration.keepOnlineTimeout || DEFAULT_COMMUNICATION_CONFIG.keepOnlineTimeout)
    initialCommunication.value = JSON.stringify(communicationForm)
}

watch(selectedGatewayProductId, async (id) => {
    if (saving.value) return
    selectedGatewayDeviceId.value = undefined
    await loadGatewayDevices(id)
    if (id && selectedGatewayProductId.value === id) {
        try {
            const product = await loadProductDetail(id)
            if (id === selectedGatewayProductId.value) applyCommunicationConfig(product)
        } catch (error: any) { onlyMessage(error.message, 'error') }
    }
})
watch(selectedGatewayDeviceId, async (id) => {
    if (saving.value) return
    const version = ++collectorLoadVersion
    if (!id) { loading.value = false; productGroups.value = [createProductGroup()]; activeGroupKey.value = productGroups.value[0].key; baselineDevices.value = []; return }
    try { await loadCollectorGroups(id, version) } catch (error: any) {
        if (version === collectorLoadVersion) { collectorLoadError.value = error.message; showImportErrors([error.message]) }
    }
})
watch(selectedAccessId, async (id) => {
    if (saving.value || accessMode.value !== 'existing' || !id) return
    const version = ++accessLoadVersion
    try {
        const detail = await getAccessGatewayDetail(id)
        if (!isOk(detail)) throw new Error('加载接入网关失败')
        if (version !== accessLoadVersion) return
        upsertAccess(detail.result)
        const deviceId = detail.result?.configuration?.deviceId
        if (deviceId) {
            const device = await getDeviceDetail(deviceId, true)
            if (version !== accessLoadVersion) return
            if (!isOk(device)) throw new Error(`加载采集器 ${deviceId} 失败`)
            selectedGatewayProductId.value = device.result.productId
            await loadGatewayDevices(device.result.productId)
            if (version === accessLoadVersion) selectedGatewayDeviceId.value = deviceId
        }
    } catch (error: any) { onlyMessage(error.message, 'error') }
})

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
    if (!enabled) tcpQuickForm.certId = undefined
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

function findNextSlaveId() { return nextSlaveId(productGroups.value) }

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

function downloadCsvTemplate(fileName: string, headers: string[]) {
    const blob = new Blob([`\uFEFF${headers.join(',')}\r\n`], { type: 'text/csv;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = fileName
    link.style.display = 'none'
    document.body.appendChild(link)
    link.click()
    link.remove()
    window.setTimeout(() => URL.revokeObjectURL(url), 1000)
}

function downloadRegisterTemplate() {
    downloadCsvTemplate('modbus-register-map-template.csv', [
        'propertyId', 'propertyName', 'functionCode', 'address', 'quantity',
        'dataType', 'byteOrder', 'scale', 'offset', 'writable', 'unit',
    ])
}

function downloadSlaveTemplate() {
    downloadCsvTemplate('modbus-slave-list-template.csv', [
        'slaveId', 'deviceId', 'deviceName', 'description',
    ])
}

function beforeRegisterUpload(file: File) {
    const group = activeGroup.value
    readFileAsText(file).then(text => applyRegisterImport(text, group)).catch(error => onlyMessage(error.message, 'error'))
    return false
}

function beforeSlaveUpload(file: File) {
    const group = activeGroup.value
    readFileAsText(file).then(text => applySlaveImport(text, group)).catch(error => onlyMessage(error.message, 'error'))
    return false
}

function openPaste(type: 'register' | 'slave') {
    pasteState.groupKey = activeGroupKey.value
    pasteState.type = type
    pasteState.text = ''
    pasteState.visible = true
}

function applyPaste() {
    const group = productGroups.value.find(group => group.key === pasteState.groupKey)
    if (!group) return
    if (pasteState.type === 'register') {
        applyRegisterImport(pasteState.text, group)
    } else {
        applySlaveImport(pasteState.text, group)
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

function applyRegisterImport(text: string, group = activeGroup.value) {
    if (!productGroups.value.includes(group)) return
    const result = parseRegisterMapText(text)
    if (result.rows.length) {
        group.registerRows = result.rows
    }
    showImportErrors(result.errors)
    if (!result.errors.length) onlyMessage('registerMap 导入成功')
}

function applySlaveImport(text: string, group = activeGroup.value) {
    if (!productGroups.value.includes(group)) return
    const result = parseSlaveText(text, selectedGatewayDeviceId.value || gatewayDeviceForm.id)
    if (result.rows.length) {
        group.slaveRows = result.rows.map(row => {
            const original = baselineDevices.value.find(item => item.deviceId === row.deviceId && item.sourceDevice?.productId === group.productId)
            return original ? { ...row, persisted: true, autoDeviceId: false, pollOverrideEnabled: original.pollOverrideEnabled, sourceDevice: original.sourceDevice } : row
        })
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
        if (collectorLoadError.value) errors.push(collectorLoadError.value)
        if (gatewayProductMode.value === 'select' && !selectedGatewayProductId.value) errors.push('请选择网关产品')
        if (gatewayProductMode.value === 'create' && !gatewayProductForm.name) errors.push('请输入网关产品名称')
        if (gatewayDeviceMode.value === 'select' && !selectedGatewayDeviceId.value) errors.push('请选择网关设备')
        if (gatewayDeviceMode.value === 'create' && !gatewayDeviceForm.name) errors.push('请输入网关设备名称')
        return errors
    }

    if (step === 2) {
        productGroups.value.forEach(group => group.slaveRows.forEach(row => fillSlaveGeneratedFields(row)))
        return validateProductGroups(productGroups.value)
    }


    return []
}

function validateAll() {
    return [0, 1, 2].flatMap(validateStep)
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

async function updateQuickNetworkConnection(mode: TcpQuickCreateMode, gatewayDeviceId: string) {
    const networkId = quickCreated.networkId
    if (!networkId) return
    const accessId = quickCreated.accessId
    const loadOwnedResources = async () => {
        const networkResp = await getNetworkDetail(networkId)
        if (!isOk(networkResp)) throw new Error('读取本次创建的网络组件失败')
        const accessResp = accessId ? await getAccessGatewayDetail(accessId) : undefined
        if (accessId && !isOk(accessResp)) throw new Error('读取本次创建的接入网关失败')
        assertQuickTcpResourceOwnership(mode, { networkId, accessId }, gatewayDeviceId, networkResp.result, accessResp?.result)
        return { network: networkResp.result, access: accessResp?.result }
    }
    let { network, access } = await loadOwnedResources()
    const desired = normalizeTcpConnectionConfiguration(mode, buildTcpNetworkPayload(mode, tcpQuickForm).configuration)
    const removeUnusedCertificate = !tcpQuickForm.tlsEnabled && network.configuration.certId != null
    if (!removeUnusedCertificate && sameConfiguration(normalizeTcpConnectionConfiguration(mode, network.configuration), desired)) return

    const references = await queryGatewayList({ paging: false, terms: [termEq('channelId', networkId)] })
    if (!isOk(references)) throw new Error('核对网络组件引用失败')
    if (normalizeResultList(references).some(item => item.channelId === networkId && item.id !== accessId)) {
        throw new Error('网络组件已被其他接入网关引用，不能在向导中修改')
    }
    if (accessId) {
        savingStage.value = '停止本次创建的接入网关'
        if (!isOk(await shutdownAccessGateway(accessId))) throw new Error('停止接入网关失败')
        ;({ network, access } = await loadOwnedResources())
        if (stateValue(access).toLowerCase() !== 'disabled') throw new Error('接入网关尚未停止，未修改网络配置')
        quickCreated.accessState = '已停止，等待重新启动'
        upsertAccess(access)
    }
    savingStage.value = '停止本次创建的 TCP 网络组件'
    if (!isOk(await shutdownNetwork(networkId))) throw new Error('停止 TCP 网络组件失败')
    ;({ network, access } = await loadOwnedResources())
    if (stateValue(network).toLowerCase() !== 'disabled'
        || (accessId && stateValue(access).toLowerCase() !== 'disabled')) {
        throw new Error('网络组件或接入网关尚未停止，未修改网络配置')
    }
    quickCreated.networkState = '已停止，等待重新启动'
    savingStage.value = '更新本次创建的 TCP 连接配置'
    const configuration = buildTcpNetworkConfigurationUpdate(mode, tcpQuickForm, network.configuration)
    if (!isOk(await updateNetwork({
        id: networkId, name: network.name, type: network.type,
        shareCluster: network.shareCluster, state: 'disabled', configuration,
    }))) {
        throw new Error('更新 TCP 连接配置失败')
    }
    ;({ network, access } = await loadOwnedResources())
    if (stateValue(network).toLowerCase() !== 'disabled'
        || (accessId && stateValue(access).toLowerCase() !== 'disabled')
        || !sameConfiguration(normalizeTcpConnectionConfiguration(mode, network.configuration), desired)
        || (!tcpQuickForm.tlsEnabled && network.configuration.certId)) {
        throw new Error('TCP 连接配置保存结果不一致，请重试')
    }
}

async function ensureQuickAccess(gatewayDeviceId: string, childDeviceIds: string[]) {
    if (!quickMode.value) return selectedAccess.value

    const mode = quickMode.value
    await updateQuickNetworkConnection(mode, gatewayDeviceId)
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

async function startAndVerifyQuickAccess(gatewayDeviceId: string) {
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
    upsertAccess(accessDetailResp.result)
    savingStage.value = '等待网关设备上线'
    const online = await waitForDeviceState(async () => {
        const detailResp = await getDeviceDetail(gatewayDeviceId, true)
        return isOk(detailResp) ? stateValue(detailResp?.result) : ''
    })
    if (!online.matched) {
        quickCreated.accessState = '已启动，等待采集器连接'
    }
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

    assertProductAccess(product, access.id)
    if (product.accessId !== access.id) {
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
    }

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

function productActive(product: any) { return ['1', 'enabled', 'deployed'].includes(stateValue(product).toLowerCase()) }
function deviceActive(device: any) { return !['notActive', 'disabled', '0'].includes(stateValue(device)) }
function checkpointDevice(row: SlaveRow, device: any) {
    row.persisted = true
    row.autoDeviceId = false
    row.sourceDevice = device
    const index = baselineDevices.value.findIndex(item => item.deviceId === row.deviceId)
    const source = sourceToRow(device)
    if (index < 0) baselineDevices.value.push(source)
    else baselineDevices.value[index] = source
}
async function readExistingDevice(id: string) {
    const exists = await isExists(id)
    if (!isOk(exists)) throw new Error(`检查设备 ${id} 是否存在失败`)
    if (!exists.result) return undefined
    const detail = await getDeviceDetail(id, true)
    if (!isOk(detail) || !detail.result?.id) throw new Error(`读取设备 ${id} 失败`)
    return detail.result
}
async function saveGroupDevices(group: ProductGroup, gatewayId: string, canActivate: boolean) {
    for (const row of group.slaveRows) {
        const id = row.deviceId!
        let existing = await readExistingDevice(id)
        if (existing) assertDeviceOwnership(existing, group.productId!, gatewayId)
        else if (row.persisted) throw new Error(`已有设备 ${id} 已不存在，请重新加载采集器`)
        const config = {
            ...(existing?.configuration || existing?.cachedConfiguration || {}),
            ...(!existing && row.pollOverrideEnabled ? group.pollingConfig : {}),
            slaveId: Number(row.slaveId), pollOverrideEnabled: !!row.pollOverrideEnabled,
        }
        const changed = !existing || deviceRowSignature(sourceToRow(existing)) !== deviceRowSignature(row)
        const isNew = !existing
        if (isNew) row.createdDevice = true
        if (changed) {
            const payload = { id, name: row.deviceName || `从机${row.slaveId}`, describe: row.description || '',
                ...(!existing ? { productId: group.productId, parentId: gatewayId, configuration: config } : {}) }
            let result: any
            try { result = existing ? await editDevice(payload) : await addDevice(payload) }
            catch (error: any) { result = { status: 500, message: error.message } }
            if (!isOk(result)) {
                // Some backend versions persist before response conversion fails. Verify the actual record.
                const persisted = await readExistingDevice(id)
                if (!persisted) throw new Error(`保存从机 ${id} 失败：${result?.message || '未落库'}`)
                assertDeviceOwnership(persisted, group.productId!, gatewayId)
                if (persisted.name !== payload.name || (persisted.describe || '') !== payload.describe) throw new Error(`从机 ${id} 保存未完成，请重试`)
                existing = persisted
            }
            if (!isNew) {
                const configResp = await saveDeviceConfig(id, config)
                if (!isOk(configResp)) throw new Error(`保存从机 ${id} 通信配置失败`)
            }
        }
        const detail = await getDeviceDetail(id, true)
        if (!isOk(detail)) throw new Error(`确认从机 ${id} 保存结果失败`)
        assertDeviceOwnership(detail.result, group.productId!, gatewayId)
        const stored = sourceToRow(detail.result)
        if (deviceRowSignature(stored) !== deviceRowSignature(row)) throw new Error(`从机 ${id} 的保存结果与配置不一致，请重试`)
        checkpointDevice(row, detail.result)
        if ((isNew || row.createdDevice) && canActivate && !deviceActive(detail.result)) {
            row.createdDevice = true
            const deploy = await deployDevice(id)
            if (!isOk(deploy)) throw new Error(`启用新增从机 ${id} 失败`)
            row.createdDevice = false
            const enabled = await getDeviceDetail(id, true)
            if (!isOk(enabled)) throw new Error(`确认新增从机 ${id} 启用状态失败`)
            checkpointDevice(row, enabled.result)
        } else if (isNew && !canActivate) row.createdDevice = true
    }
}
async function syncAccessChildren(gatewayId: string, desired: string[], removed: string[]) {
    const response = await getAccessGatewayDetail(selectedAccessId.value!)
    if (!isOk(response)) throw new Error('读取最新接入网关配置失败')
    const access = response.result
    if (!['tcp-server-gateway', 'tcp-client-gateway'].includes(access.provider)) return
    const previous = access.configuration || {}
    if (previous.deviceId && previous.deviceId !== gatewayId) throw new Error('接入网关已指向其他采集器，不能覆盖')
    const configuration = { ...previous, deviceId: gatewayId,
        ...(access.provider === 'tcp-server-gateway' ? { childDeviceIds: mergeChildDeviceIds(previous.childDeviceIds, desired, removed) } : {}) }
    if (!sameConfiguration(configuration, previous)) {
        const result = await updateAccessGateway({
            id: access.id,
            name: access.name,
            provider: access.provider,
            channel: access.channel,
            channelId: access.channelId,
            protocol: access.protocol,
            transport: access.transport,
            description: access.description,
            configuration,
        })
        if (!isOk(result)) throw new Error('同步接入网关从机列表失败')
        const saved = await getAccessGatewayDetail(access.id)
        if (!isOk(saved) || !sameConfiguration(saved.result.configuration, configuration)) throw new Error('接入网关配置保存结果不一致，请重试')
        upsertAccess(saved.result)
    } else upsertAccess(access)
}
async function preflight(gatewayId: string) {
    const targetAccessId = quickMode.value ? quickCreated.accessId : selectedAccessId.value
    const gatewayProductId = gatewayCreated.productId || (gatewayProductMode.value === 'select' ? selectedGatewayProductId.value : undefined)
    if (gatewayProductId) assertProductAccess(await loadProductDetail(gatewayProductId), targetAccessId)
    if (selectedAccess.value && (!quickMode.value || quickCreated.accessId)) {
        const access = await getAccessGatewayDetail(selectedAccessId.value!)
        if (!isOk(access)) throw new Error('读取接入网关失败')
        if (access.result.configuration?.deviceId && access.result.configuration.deviceId !== gatewayId) throw new Error('接入网关已指向其他采集器')
    }
    for (const group of productGroups.value) {
        if (group.mode === 'select') {
            const product = await loadProductDetail(group.productId!)
            assertProductAccess(product, targetAccessId)
            if (group.sourceProduct?.id !== group.productId) throw new Error(`产品「${groupName(group)}」尚未加载完成`)
        }
        for (const row of group.slaveRows) {
            const device = await readExistingDevice(row.deviceId!)
            if (device) assertDeviceOwnership(device, group.productId || group.form.id, gatewayId)
            if (row.persisted && !device) throw new Error(`已有设备 ${row.deviceId} 已不存在，请重新加载`)
        }
    }
    const latest = await queryDeviceNoPaging({ paging: false, terms: [termEq('parentId', gatewayId)] })
    if (!isOk(latest)) throw new Error('核对当前采集器从机列表失败')
    const known = new Set([...baselineDevices.value.map(row => row.deviceId), ...productGroups.value.flatMap(group => group.slaveRows.map(row => row.deviceId))])
    for (const device of normalizeResultList(latest).filter(device => !known.has(device.id))) {
        const detail = await getDeviceDetail(device.id, true)
        if (!isOk(detail)) throw new Error(`读取新增从机 ${device.id} 失败`)
        if (productGroups.value.some(group => group.slaveRows.some(row => Number(row.slaveId) === Number(sourceToRow(detail.result).slaveId)))) {
            throw new Error(`采集器新增从机 ${device.id} 与本次配置的 slaveId 冲突，请重新加载`)
        }
    }
}
async function handleSaveAndTest() {
    if (saving.value || loading.value || testing.value) return
    if (productGroups.value.some(group => group.loading)) { onlyMessage('请等待产品配置加载完成', 'warning'); return }
    const errors = validateAll()
    if (errors.length) { showImportErrors(errors); return }
    const removed = pendingRemovals.value.map(row => row.deviceId!)
    if (removed.length && !await confirmAction('确认解除从机绑定', `将解绑以下设备并保留历史数据：${removed.join('、')}`)) return
    saving.value = true
    testResults.value = []
    currentStep.value = 3
    let currentGroup: ProductGroup | undefined
    try {
        if (gatewayDeviceMode.value === 'create' && !gatewayDeviceForm.id) gatewayDeviceForm.id = `modbus_gateway_${Date.now()}`
        const gatewayId = gatewayCreated.deviceId || (gatewayDeviceMode.value === 'select' ? selectedGatewayDeviceId.value! : gatewayDeviceForm.id)
        productGroups.value.forEach(group => group.slaveRows.forEach(row => fillSlaveGeneratedFields(row, gatewayId)))
        const finalErrors = validateProductGroups(productGroups.value)
        if (finalErrors.length) throw new Error(finalErrors.join('；'))
        savingStage.value = '核对资源归属与从机地址'
        await preflight(gatewayId)
        if (gatewayDeviceMode.value === 'select') {
            const device = await getDeviceDetail(gatewayId, true)
            if (!isOk(device) || device.result.productId !== selectedGatewayProductId.value || device.result.parentId) throw new Error('采集器与所选网关产品不匹配')
        }
        savingStage.value = '准备网关产品'
        let gatewayProduct = await ensureProduct(gatewayCreated.productId ? 'select' : gatewayProductMode.value,
            gatewayCreated.productId || selectedGatewayProductId.value, gatewayProductForm, 'gateway')
        if (gatewayProductMode.value === 'create' && !gatewayCreated.productId) gatewayCreated.productNew = true
        gatewayCreated.productId = gatewayProduct.id
        const commonChanged = JSON.stringify(communicationForm) !== initialCommunication.value
        await ensureQuickAccess(gatewayId, [])
        if (!selectedAccess.value) throw new Error('未能取得 Modbus 接入网关')
        if (gatewayCreated.productNew || commonChanged || gatewayProduct.accessId !== selectedAccessId.value) {
            gatewayProduct = await saveProductAccessAndConfig(gatewayProduct, { ...communicationForm }, gatewayProduct.metadata)
        }
        if (gatewayCreated.productNew && !productActive(gatewayProduct)) {
            const deploy = await deployProduct(gatewayProduct.id)
            if (!isOk(deploy)) throw new Error('启用新增网关产品失败')
            gatewayProduct = await loadProductDetail(gatewayProduct.id)
        }
        if (!gatewayCreated.deviceId && gatewayDeviceMode.value === 'create') {
            const alreadyCreated = await readExistingDevice(gatewayId)
            if (alreadyCreated && (alreadyCreated.productId !== gatewayProduct.id || alreadyCreated.parentId)) throw new Error(`采集器 ID ${gatewayId} 已被其他设备占用`)
            if (!alreadyCreated) {
                gatewayCreated.deviceNew = true
                await ensureGatewayDevice(gatewayProduct.id)
            }
        }
        gatewayCreated.deviceId = gatewayId
        selectedGatewayDeviceId.value = gatewayId
        if (gatewayCreated.deviceNew && productActive(gatewayProduct)) {
            const deploy = await deployDevice(gatewayId)
            if (!isOk(deploy)) throw new Error('启用新增采集器失败')
            gatewayCreated.deviceNew = false
        }
        const gatewayDetail = await getDeviceDetail(gatewayId, true)
        if (!isOk(gatewayDetail)) throw new Error('读取采集器状态失败')
        for (const group of productGroups.value) {
            currentGroup = group
            group.status = '保存中'
            savingStage.value = `产品「${groupName(group)}」：准备产品`
            const isNew = group.mode === 'create'
            let product = await ensureProduct(group.productId ? 'select' : group.mode, group.productId, group.form, 'childrenDevice')
            group.productId = product.id
            group.form.id = product.id
            group.mode = 'select'
            group.locked = true
            group.sourceProduct = product
            if (isNew) group.createdProduct = true
            const signature = groupSignature(group)
            if (isNew || signature !== group.savedSignature || commonChanged || product.accessId !== selectedAccessId.value) {
                savingStage.value = `产品「${groupName(group)}」：保存物模型与轮询`
                product = await saveProductAccessAndConfig(product, {
                    ...(isNew || group.createdProduct || commonChanged ? { ...communicationForm } : {}),
                    ...group.pollingConfig, registerMap: serializeRegisterMap(group.registerRows),
                }, JSON.stringify(buildMetadataFromRegisterMap(group.registerRows, product.metadata)))
                group.savedSignature = signature
            }
            group.sourceProduct = product
            if (group.createdProduct && productActive(gatewayProduct) && !productActive(product)) {
                const deploy = await deployProduct(product.id)
                if (!isOk(deploy)) throw new Error(`启用新增产品「${groupName(group)}」失败`)
                product = await loadProductDetail(product.id)
                group.sourceProduct = product
            }
            savingStage.value = `产品「${groupName(group)}」：保存从机`
            await saveGroupDevices(group, gatewayId, productActive(gatewayProduct) && productActive(product) && deviceActive(gatewayDetail.result))
            group.status = '配置已保存'
        }
        currentGroup = undefined
        const desired = productGroups.value.flatMap(group => group.slaveRows.map(row => row.deviceId!))
        savingStage.value = '统一绑定从机'
        if (!isOk(await bindDevice(gatewayId, desired))) throw new Error('绑定从机失败')
        savingStage.value = '同步接入网关完整从机列表'
        await syncAccessChildren(gatewayId, desired, removed)
        if (removed.length) {
            savingStage.value = '解除已移除从机的绑定'
            if (!isOk(await unbindBatchDevice(gatewayId, removed))) throw new Error('解除从机绑定失败')
        }
        baselineDevices.value = productGroups.value.flatMap(group => group.slaveRows.map(row => sourceToRow({ ...row.sourceDevice, parentId: gatewayId })))
        initialCommunication.value = JSON.stringify(communicationForm)
        if (quickMode.value && deviceActive(gatewayDetail.result) && productActive(gatewayProduct)) {
            await startAndVerifyQuickAccess(gatewayId)
        }
        savingStage.value = '按产品执行通讯测试'
        await runCommunicationTest(gatewayId)
        selectedGatewayProductId.value = gatewayProduct.id
        gatewayProductMode.value = 'select'
        gatewayDeviceMode.value = 'select'
        accessMode.value = 'existing'
        Object.keys(quickCreated).forEach(key => delete quickCreated[key as keyof QuickCreatedResources])
        Object.assign(gatewayCreated, { productId: '', deviceId: '', productNew: false, deviceNew: false })
        productGroups.value.forEach(group => { if (productActive(group.sourceProduct)) group.createdProduct = false })
        await loadBaseData()
        await loadGatewayDevices(gatewayProduct.id)
        onlyMessage('各产品配置已保存，请查看通讯测试结果')
    } catch (error: any) {
        if (currentGroup) currentGroup.status = `保存失败：${error.message}`
        onlyMessage(`${savingStage.value || '保存'}失败：${error.message}`, 'error')
    } finally {
        saving.value = false
        savingStage.value = ''
    }
}
async function runCommunicationTest(gatewayId: string) {
    testing.value = true
    const rows: TestResultRow[] = []
    try {
        const parent = await getDeviceDetail(gatewayId, true)
        const parentOnline = isOk(parent) && stateValue(parent.result) === 'online'
        for (const group of productGroups.value) {
            const readable = getReadablePropertyIds(group.registerRows)
            const properties = group.testPropertyIds.length ? group.testPropertyIds.filter(id => readable.includes(id)) : readable.slice(0, 1)
            const devices = group.testDeviceIds.length ? group.slaveRows.filter(row => group.testDeviceIds.includes(row.deviceId!)) : group.slaveRows
            for (const device of devices) {
                const resultBase = { productId: group.productId, productName: groupName(group), deviceId: device.deviceId! }
                const disabled = !productActive(group.sourceProduct) || !deviceActive(device.sourceDevice) || !deviceActive(parent.result) || !isEnabledState(selectedAccess.value)
                const reason = disabled ? '产品、设备或接入处于停用状态，保留原状态' : !properties.length ? '本产品没有可读测试点位' : !parentOnline ? '配置已保存，等待采集器连接' : ''
                if (reason) {
                    rows.push({ ...resultBase, key: `${group.key}_${device.deviceId}_skip`, propertyId: '--',
                        status: !disabled && properties.length ? 'waiting' : 'skipped', message: reason })
                    continue
                }
                for (const propertyId of properties) {
                    try {
                        const resp = await testReadProperties(device.deviceId!, [propertyId])
                        rows.push({ ...resultBase, key: `${group.key}_${device.deviceId}_${propertyId}`, propertyId,
                            status: isOk(resp) ? 'success' : 'error', value: pickPropertyValue(resp?.result, propertyId),
                            message: isOk(resp) ? '读取成功' : (resp as any)?.message || '读取失败' })
                    } catch (error: any) {
                        rows.push({ ...resultBase, key: `${group.key}_${device.deviceId}_${propertyId}`, propertyId, status: 'error', message: error.message || '读取异常' })
                    }
                }
            }
        }
        testResults.value = rows
    } finally { testing.value = false }
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
.product-group-toolbar { margin-bottom: 20px; }
.group-summary { margin: 16px 0; }
.modbus-access-page {
    min-height: 100%;
    padding: 24px;
    background: var(--app-info-bg);
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
    color: var(--app-text);
    font-weight: 600;
    font-size: 22px;
    line-height: 30px;
}

.page-subtitle,
.toolbar-note {
    color: var(--app-text-secondary);
    font-size: 13px;
}

.steps-wrap {
    padding: 18px 24px;
    margin-bottom: 16px;
    background: var(--app-surface);
    border: 1px solid var(--app-border);
    border-radius: 6px;
}

.guide-panel {
    padding: 16px 20px;
    margin-bottom: 16px;
    background: var(--app-info-bg);
    border: 1px solid var(--app-border);
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
    color: var(--app-text);
    font-size: 12px;
    font-weight: 600;
}

.guide-title {
    color: var(--app-text);
    font-size: 16px;
    font-weight: 600;
    line-height: 24px;
}

.guide-summary {
    margin-top: 6px;
    color: var(--app-text-secondary);
    line-height: 22px;
}

.guide-body {
    margin-top: 10px;
}

.guide-list {
    margin: 0;
    padding-left: 20px;
    color: var(--app-text);
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
    background: var(--app-surface);
    border: 1px solid var(--app-border);
}

.guide-example-label {
    margin-right: 8px;
    color: var(--app-text);
    font-weight: 600;
}

.guide-next {
    color: var(--app-text);
    background: var(--app-success-bg);
    border: 1px solid var(--app-border);
}

.content-panel {
    min-height: 560px;
    padding: 24px;
    background: var(--app-surface);
    border: 1px solid var(--app-border);
    border-radius: 6px;
}

.step-panel {
    min-height: 500px;
}

.section-title {
    margin-bottom: 16px;
    color: var(--app-text);
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

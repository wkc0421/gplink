<template>
  <j-page-container>
    <div class="mqtt-wizard">
      <header class="page-head">
        <div><h2>{{ isTcp ? 'TCP 服务端接入向导' : 'MQTT 客户端接入向导' }}</h2><p>配置接入、创建产品、导入设备，三步完成接入准备。</p></div>
        <a-space>
          <a-button :href="isTcp ? '/docs/tcp-server-config-guide.html' : '/docs/mqtt-client-config-guide.html'" target="_blank" rel="noopener noreferrer">配置说明</a-button>
          <a-popconfirm title="开始新的接入配置？已保存的资源会保留。" @confirm="resetWizard"><a-button :disabled="busy || uploading">新建接入流程</a-button></a-popconfirm>
        </a-space>
      </header>
      <a-steps :current="step" class="steps"><a-step title="接入配置" /><a-step title="创建产品" /><a-step title="导入设备列表" /></a-steps>
      <a-alert v-if="error" class="notice" type="error" show-icon :message="error" />
      <a-spin :spinning="loading">
        <a-form layout="vertical" :disabled="busy" class="panel">
          <section v-show="step === 0">
            <template v-if="accessComplete">
              <a-alert type="success" show-icon message="接入配置已保存并通过启用校验" />
              <a-descriptions bordered :column="1" class="notice">
                <a-descriptions-item label="网络组件">{{ saved.networkId }}</a-descriptions-item>
                <a-descriptions-item label="接入网关">{{ saved.accessId }}</a-descriptions-item>
                <a-descriptions-item label="协议">{{ saved.protocolId }}</a-descriptions-item>
              </a-descriptions>
            </template>
            <template v-else>
              <a-form-item label="接入网关配置方式"><a-radio-group v-model:value="accessMode" :disabled="busy || uploading || accessLocked || protocolLocked"><a-radio value="create">新建接入网关</a-radio><a-radio value="select">选择已有接入网关</a-radio></a-radio-group></a-form-item>
              <template v-if="accessMode === 'select'">
                <a-form-item label="已有接入网关" required><a-select v-model:value="selectedAccessId" aria-label="已有接入网关" :options="options(availableGateways)" :disabled="busy || uploading || accessLocked" placeholder="请选择已有接入网关" /></a-form-item>
                <p class="hint">直接使用该网关绑定的网络组件和协议，无需填写{{ idLabel }}、连接参数或重新选择协议。</p>
                <a-descriptions v-if="selectedGateway" class="reuse-summary" bordered :column="1">
                  <a-descriptions-item label="网络组件">{{ reusedNetwork?.name || gatewayNetworkId(selectedGateway) || '未绑定网络组件' }}<template v-if="reusedNetwork?.name">（{{ reusedNetwork.id }}）</template></a-descriptions-item>
                  <a-descriptions-item label="协议">{{ reusedProtocol?.name || gatewayProtocolId(selectedGateway) || '未绑定协议' }}<template v-if="reusedProtocol?.name">（{{ reusedProtocol.id }}）</template></a-descriptions-item>
                </a-descriptions>
              </template>
              <template v-else>
                <a-collapse v-if="!isTcp || protocolMode === 'upload'" v-model:activeKey="accessCustom" class="custom-config" ghost><a-collapse-panel key="custom" header="自定义配置">
                  <a-button class="notice" :disabled="accessLocked || protocolLocked" @click="restoreDefaults">恢复默认</a-button>
                  <div v-if="networkMode === 'create' && !isTcp" class="fields">
                    <a-form-item label="组件名称"><a-input v-model:value="names.networkName" aria-label="组件名称" :maxlength="64" :disabled="accessLocked" /></a-form-item>
                    <a-form-item label="客户端 ID"><a-input v-model:value="names.clientId" aria-label="客户端 ID" :maxlength="64" :disabled="accessLocked" /></a-form-item>
                    <a-form-item label="Broker 地址"><a-input v-model:value="networkForm.remoteHost" aria-label="Broker 地址" :disabled="accessLocked" /></a-form-item>
                    <a-form-item label="Broker 端口"><a-input-number v-model:value="networkForm.remotePort" aria-label="Broker 端口" :min="1" :max="65535" :disabled="accessLocked" /></a-form-item>
                    <a-form-item label="用户名"><a-input v-model:value="names.username" aria-label="用户名" :maxlength="64" :disabled="accessLocked" placeholder="匿名连接时与密码一起留空" /></a-form-item>
                    <a-form-item label="密码"><a-input-password v-model:value="names.password" aria-label="密码" :maxlength="64" :disabled="accessLocked" autocomplete="new-password" /></a-form-item>
                    <a-form-item label="主题前缀"><a-input v-model:value="networkForm.topicPrefix" :disabled="accessLocked" placeholder="普通订阅留空" /></a-form-item>
                    <a-form-item label="最大消息长度（字节）"><a-input-number v-model:value="networkForm.maxMessageSize" :min="1024" :max="1073741824" :disabled="accessLocked" /></a-form-item>
                    <a-form-item label="TLS"><a-switch v-model:checked="networkForm.secure" :disabled="accessLocked" /></a-form-item>
                    <a-form-item v-if="networkForm.secure" label="网络证书" required><a-select v-model:value="networkForm.certId" :options="options(certs)" :disabled="accessLocked" /></a-form-item>
                  </div>
                  <a-form-item v-if="!isTcp" label="接入网关名称"><a-input v-model:value="names.accessName" aria-label="接入网关名称" :maxlength="64" :disabled="accessLocked" /></a-form-item>
                  <a-form-item v-if="protocolMode === 'upload'" :label="`${transport} 协议名称`"><a-input v-model:value="names.protocolName" :aria-label="`${transport} 协议名称`" :maxlength="64" :disabled="!!saved.protocolId || accessLocked" /></a-form-item>
                </a-collapse-panel></a-collapse>
                <a-form-item label="网络组件配置方式"><a-radio-group v-model:value="networkMode" :disabled="accessLocked"><a-radio value="create">新建网络组件</a-radio><a-radio value="select">选择已有网络组件</a-radio></a-radio-group></a-form-item>
                <a-form-item v-if="networkMode === 'select'" label="已有网络组件" required><a-select v-model:value="selectedNetworkId" aria-label="已有网络组件" :options="options(networks)" :disabled="accessLocked" placeholder="请选择已有网络组件" /></a-form-item>
                <p v-if="networkMode === 'select'" class="hint">使用已有组件的连接配置，只需为新网关填写{{ idLabel }}并选择协议。</p>
                <a-form-item :label="idLabel" required help="2～32 位，必须包含英文字母和数字，例如 abc123。"><a-input v-model:value="accessId" :aria-label="idLabel" :maxlength="32" :disabled="accessLocked || protocolLocked" placeholder="例如 abc123" /></a-form-item>
                <template v-if="isTcp">
                  <div v-if="networkMode === 'create'" class="fields">
                    <a-form-item label="公网 IP" required><a-input v-model:value="tcpForm.publicHost" aria-label="公网 IP" :disabled="accessLocked" placeholder="例如 203.0.113.10" /></a-form-item>
                    <a-form-item label="端口" required><a-input-number v-model:value="tcpForm.port" aria-label="端口" :min="1" :max="65535" :precision="0" :disabled="accessLocked" placeholder="设备连接端口" /></a-form-item>
                  </div>
                  <p v-if="networkMode === 'create'" class="hint">平台监听本机所有网卡，监听端口与公网端口相同。设备通过公网 IP 和端口连接；端口映射与防火墙需已配置。</p>
                  <a-descriptions v-if="accessId" size="small" :column="1" class="notice">
                    <a-descriptions-item v-if="networkMode === 'create'" label="组件 ID / 名称">{{ tcpIds.networkId }} / {{ tcpIds.networkName }}</a-descriptions-item>
                    <a-descriptions-item label="网关 ID / 名称">{{ tcpIds.accessId }} / {{ tcpIds.accessName }}</a-descriptions-item>
                  </a-descriptions>
                </template>
                <p v-else-if="networkMode === 'create'" class="hint">默认 Broker：{{ networkForm.remoteHost }}:{{ networkForm.remotePort }}。127.0.0.1 指 GpLink 后端所在机器。Broker 账号需预先配置。</p>
                <a-form-item :label="`${transport} 协议`" required><a-radio-group v-model:value="protocolMode" :disabled="accessLocked || uploading"><a-radio-button value="select">选择已有</a-radio-button><a-radio-button value="upload">上传 JAR</a-radio-button></a-radio-group></a-form-item>
                <a-form-item v-if="protocolMode === 'select'" :label="`选择 ${transport} 协议`"><a-select v-model:value="selectedProtocolId" :aria-label="`选择 ${transport} 协议`" :options="options(protocols)" show-search option-filter-prop="label" :disabled="accessLocked" :placeholder="`选择支持 ${transport} 的协议`" /></a-form-item>
                <a-form-item v-else label="协议 JAR 文件">
                  <a-upload :key="uploadKey" :action="FileStaticPath" :headers="uploadHeaders" accept=".jar" :max-count="1" :show-upload-list="false" :before-upload="beforeJarUpload" :disabled="busy || accessLocked || uploading" @change="jarUploaded">
                    <j-permission-button :hasPermission="isTcp ? 'device/TcpServerAccess:uploadProtocol' : 'link/Protocol:add'" :disabled="busy || accessLocked || uploading" :loading="uploading">上传协议 JAR</j-permission-button>
                  </a-upload><span class="file-name">{{ jarFile?.name || '尚未上传' }}</span>
                  <p class="hint">名称默认 {{ names.protocolName }}。上传后点击下方按钮保存并检查协议。</p>
                </a-form-item>
              </template>
              <a-collapse v-if="!isTcp && routes.length" ghost><a-collapse-panel key="routes" header="协议主题预览"><a-table size="small" :data-source="routes" :columns="routeColumns" :pagination="false" :row-key="(_, index) => String(index)"><template #bodyCell="{ column, record }"><template v-if="column.key === 'direction'">{{ record.upstream ? '上行订阅' : '' }} {{ record.downstream ? '下行发布' : '' }}</template></template></a-table></a-collapse-panel></a-collapse>
              <p v-if="saved.networkId" class="hint">已保存网络组件 {{ saved.networkId }}，重试将继续使用该资源。需要修改配置请开始新建接入流程。</p>
            </template>
            <div class="actions"><j-permission-button type="primary" :hasPermission="savePermission" :loading="busy" :disabled="uploading || loading" @click="saveAccessStep">{{ accessComplete ? '继续创建产品' : accessMode === 'select' ? '使用已有网关并继续' : '保存接入并继续' }}</j-permission-button></div>
          </section>
          <section v-show="step === 1">
            <a-alert v-if="productComplete" type="success" show-icon :message="`产品 ${saved.productId} 已保存并启用`" />
            <template v-else>
              <a-alert v-if="saved.productId" class="notice" type="info" show-icon :message="`产品 ${saved.productId} 已保存；完成物模型检查后点击下方按钮启用并继续。`" />
              <a-form-item label="产品配置方式"><a-radio-group v-model:value="productMode" :disabled="!!saved.productId"><a-radio value="create">新建产品</a-radio><a-radio value="select">选择已有产品</a-radio></a-radio-group></a-form-item>
              <template v-if="productMode === 'create'">
                <a-form-item label="产品名称" required><a-input v-model:value="names.productName" aria-label="产品名称" :maxlength="64" :disabled="!!saved.productId" /></a-form-item>
                <a-form-item label="产品 ID（可选）"><a-input v-model:value="productId" aria-label="产品 ID" :disabled="!!saved.productId" placeholder="留空由系统生成" /></a-form-item>
              </template>
              <a-form-item v-else label="已有普通设备产品" required><a-select v-model:value="selectedProductId" aria-label="已有普通设备产品" :options="options(products)" :disabled="!!saved.productId" placeholder="请选择已有产品" /></a-form-item>
              <a-alert v-if="productMode === 'select'" class="notice" type="warning" show-icon :message="`继续后将把所选产品绑定到当前 ${transport} 接入网关和协议，并启用该产品。`" />
            </template>
            <div class="metadata-section">
              <h3>产品物模型</h3>
              <p class="hint">可导入 Excel 或 CSV 属性物模型，导入后检查下方属性；不需要时可跳过。未配置的地址和精度显示为“—”。</p>
              <template v-if="!productComplete">
                <a-alert v-if="productMode === 'select'" class="notice" type="warning" show-icon message="导入会替换所选产品的全部属性，其他使用该产品的设备也会受到影响。" />
                <a-space wrap><a-button :disabled="busy || uploading" @click="downloadMetadataTemplate('xlsx')">下载物模型 Excel 模板</a-button><a-button :disabled="busy || uploading" @click="downloadMetadataTemplate('csv')">下载物模型 CSV 模板</a-button></a-space>
                <a-form-item class="notice" label="物模型文件"><a-upload :key="uploadKey" :action="FileStaticPath" :headers="uploadHeaders" accept=".xls,.xlsx,.csv" :max-count="1" :show-upload-list="false" :disabled="busy || uploading" :before-upload="beforeMetadataUpload" @change="metadataFileUploaded"><a-button :disabled="busy || uploading" :loading="uploading">上传物模型文件</a-button></a-upload><span class="file-name">{{ metadataFile?.name || '尚未上传' }}</span></a-form-item>
                <a-space><j-permission-button :hasPermission="savePermission" :disabled="!metadataFile || uploading || modelLoading" :loading="busy" @click="importMetadata">{{ metadataFile && metadataFile.id === importedMetadataFileId ? '刷新物模型' : '导入物模型' }}</j-permission-button><a-button v-if="metadataFile" :disabled="busy || uploading" @click="metadataFile = undefined; importedMetadataFileId = ''">移除文件</a-button></a-space>
                <p v-if="importedMetadataFileId" class="hint">物模型已保存到产品；移除文件不会撤销已导入的属性。</p>
              </template>
              <a-table class="notice" size="small" :loading="modelLoading" :columns="metadataColumns" :data-source="metadataRows" :row-key="(_, index) => String(index)" :pagination="{ pageSize: 10 }" :scroll="{ x: 640 }" />
            </div>
            <div class="actions"><a-button :disabled="busy || uploading || modelLoading" @click="step = 0">上一步</a-button><j-permission-button type="primary" :hasPermission="savePermission" :loading="busy" :disabled="uploading || modelLoading" @click="saveProductStep">{{ productComplete ? '继续导入设备' : productMode === 'create' ? '创建产品并继续' : '绑定产品并继续' }}</j-permission-button></div>
          </section>
          <section v-show="step === 2">
            <a-collapse class="custom-config" ghost><a-collapse-panel key="custom" header="自定义配置">
              <a-form-item label="设备添加方式"><a-radio-group v-model:value="deviceMode" :disabled="busy || !!saved.deviceId"><a-radio value="import">批量导入</a-radio><a-radio value="create">手动添加单台设备</a-radio><a-radio value="select">选择已有设备</a-radio></a-radio-group></a-form-item>
              <a-checkbox v-model:checked="autoDeploy" :disabled="busy">保存后启用设备</a-checkbox>
            </a-collapse-panel></a-collapse>
            <a-alert type="info" show-icon :message="`当前产品：${saved.productId || '--'}。设备 ID 必须与实际协议报文一致。已存在的设备不会被覆盖。`" />
            <template v-if="deviceMode === 'import'">
              <h3>下载设备导入模板</h3>
              <a-space><a-button :disabled="busy" @click="downloadTemplate('xlsx')">下载 Excel 模板</a-button><a-button :disabled="busy" @click="downloadTemplate('csv')">下载 CSV 模板</a-button></a-space>
              <p class="hint">设备 ID、设备名称为必填项，其他列根据当前产品和协议生成。请保留表头；带前导零的 ID 按文本填写。</p>
              <a-form-item label="设备列表文件"><a-upload :key="uploadKey" :action="FileStaticPath" :headers="uploadHeaders" accept=".xlsx,.csv" :max-count="1" :show-upload-list="false" :disabled="busy || uploading" :before-upload="beforeDeviceUpload" @change="deviceFileUploaded"><a-button :disabled="busy || uploading" :loading="uploading">上传设备列表</a-button></a-upload><span class="file-name">{{ deviceFile?.name || '尚未上传' }}</span></a-form-item>
            </template>
            <template v-else-if="deviceMode === 'create'">
              <a-form-item label="设备 ID" required><a-input v-model:value="deviceForm.id" aria-label="设备 ID" :disabled="!!saved.deviceId" /></a-form-item>
              <a-form-item label="设备名称" required><a-input v-model:value="deviceForm.name" aria-label="设备名称" :maxlength="64" :disabled="!!saved.deviceId" /></a-form-item>
            </template>
            <a-form-item v-else label="产品下已有设备"><a-select v-model:value="selectedDeviceId" aria-label="产品下已有设备" :options="options(devices)" :disabled="!!saved.deviceId" /></a-form-item>
            <div class="actions"><a-button :disabled="busy || uploading" @click="step = 1">上一步</a-button><j-permission-button type="primary" :hasPermission="savePermission" :loading="busy" :disabled="uploading || (deviceMode === 'import' && (!deviceFile || fileAttempted))" @click="saveDevices">{{ deviceMode === 'import' ? (autoDeploy ? '导入并启用' : '导入设备') : '保存设备' }}</j-permission-button></div>
            <a-alert class="notice" :type="resultStatus === 'success' ? 'success' : ['partial', 'interrupted', 'unconfirmed'].includes(resultStatus) ? 'warning' : 'info'" show-icon :message="resultLabel" />
            <p v-if="legacyImport" class="hint">当前后端返回旧版导入结果，以下为接口报告的处理数量；完成状态、仅新增和启用结果需核对。请使用匹配的后端版本。</p>
            <p v-if="resultStatus !== 'idle'">{{ legacyImport ? '已处理' : '成功' }} {{ successCount }} 条，失败 {{ failureCount }} 条。<a v-if="detailFile" :href="detailFile" target="_blank" rel="noopener noreferrer">下载导入结果与错误详情</a></p>
            <a-table v-if="importErrors.length" size="small" :columns="errorColumns" :data-source="importErrors" :row-key="(_, index) => String(index)" :pagination="{ pageSize: 10 }" />
            <p class="hint">启用成功不代表设备已在线；{{ isTcp ? '需要真实设备连接 TCP 服务端并发送符合协议的报文。' : '需要真实设备向 Broker 发送符合协议的消息。' }}中断或部分失败后，请核对结果，仅重传失败行。</p>
          </section>
        </a-form>
      </a-spin>
    </div>
  </j-page-container>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { LocalStore } from '@jetlinks-web/utils'
import { TOKEN_KEY } from '@jetlinks-web/constants'
import { isNoCommunity } from '@/utils/utils'
import { FileStaticPath } from '../../api/comm'
import { certificates, queryNetworkConfig, resourcesCurrent, save as saveNetwork, start as startNetwork } from '../../api/link/type'
import { deploy as deployAccess, getNetworkList, getProtocolList, save as saveAccess } from '../../api/link/accessConfig'
import { save as saveProtocol } from '../../api/link/protocol'
import { _deploy as deployProduct, addProduct, detail as productDetail, downloadPropertyMetadataTemplate, getConfigView, importProductPropertyMetadata, queryGatewayList, queryNoPagingPost as queryProducts, updateDevice as updateProduct } from '../../api/product'
import { _deploy as deployDevice, addDevice, detail as deviceDetail, queryNoPagingPost as queryDevices, isExists, templateDownload, createOnlyDeviceImport } from '../../api/instance'
import { consumeImportStream, defaultNames, updateDefaults, validAccessId, type ImportEvent } from './MqttClientAccess/utils'
import { buildTcpServerPayload, tcpIdentifiers, validateTcpEndpoint } from './TcpServerAccess/utils'

const props = withDefaults(defineProps<{ mode?: 'mqtt-client' | 'tcp-server' }>(), { mode: 'mqtt-client' })
const isTcp = props.mode === 'tcp-server'
const transport = isTcp ? 'TCP' : 'MQTT'
const networkType = isTcp ? 'TCP_SERVER' : 'MQTT_CLIENT'
const provider = isTcp ? 'tcp-server-gateway' : 'mqtt-client-gateway'
const idLabel = isTcp ? '辨识 ID' : '接入 ID'
const savePermission = isTcp ? 'device/TcpServerAccess:save' : 'device/MqttClientAccess:save'
const generatedNames = (id: string) => {
  const base = defaultNames(id), tcp = tcpIdentifiers(id)
  return isTcp ? { ...base, networkName: tcp.networkName, accessName: tcp.accessName, protocolName: tcp.protocolName, productName: tcp.productName } : base
}

type Mode = 'create' | 'select'
type UploadedFile = { id: string; name: string; accessUrl: string }
const step = ref(0), loading = ref(false), busy = ref(false), uploading = ref(false), error = ref('')
const accessId = ref('')
const names = reactive(generatedNames(''))
const tcpForm = reactive({ publicHost: '', port: undefined as number | undefined })
const tcpIds = computed(() => tcpIdentifiers(accessId.value))
const networkDefaults = () => ({ remoteHost: '127.0.0.1', remotePort: 2883, secure: false, certId: undefined as string | undefined, topicPrefix: '', maxMessageSize: 8192 })
const networkForm = reactive(networkDefaults())
const networkMode = ref<Mode>('create'), accessMode = ref<Mode>('create'), productMode = ref<Mode>('create')
const protocolMode = ref<'select' | 'upload'>('select'), deviceMode = ref<'import' | 'create' | 'select'>('import')
const accessCustom = ref<string[]>([])
const protocols = ref<any[]>([]), networks = ref<any[]>([]), gateways = ref<any[]>([]), products = ref<any[]>([]), devices = ref<any[]>([]), certs = ref<any[]>([]), nodes = ref<any[]>([])
const selectedNetworkId = ref<string>(), selectedAccessId = ref<string>(), selectedProtocolId = ref<string>(), selectedProductId = ref<string>(), selectedDeviceId = ref<string>()
const productId = ref(''), deviceForm = reactive({ id: '', name: '' })
const saved = reactive({ protocolId: '', networkId: '', accessId: '', productId: '', deviceId: '' })
const started = reactive({ network: false, access: false, product: false, device: false })
const accessComplete = ref(false), productComplete = ref(false)
const accessLocked = computed(() => !!saved.networkId)
const protocolLocked = computed(() => accessMode.value === 'create' && protocolMode.value === 'upload' && !!saved.protocolId)
const jarFile = ref<UploadedFile>(), deviceFile = ref<UploadedFile>(), metadataFile = ref<UploadedFile>(), uploadKey = ref(0)
const importedMetadataFileId = ref(''), modelLoading = ref(false)
const metadataRows = ref<{ id: string; name: string; address: string | number; sort: string | number; scale: string | number }[]>([])
const uploadHeaders = computed(() => ({ [TOKEN_KEY]: LocalStore.get(TOKEN_KEY) }))
const routes = ref<any[]>([]), autoDeploy = ref(true), fileAttempted = ref(false)
const resultStatus = ref<'idle' | 'running' | 'success' | 'partial' | 'interrupted' | 'empty' | 'unconfirmed'>('idle')
const legacyImport = ref(false)
const successCount = ref(0), failureCount = ref(0), detailFile = ref(''), importErrors = ref<ImportEvent[]>([])
let importController: AbortController | undefined
let routeVersion = 0, modelLoadVersion = 0
const availableGateways = computed(() => gateways.value.filter(item => item.provider === provider))
const gatewayNetworkId = (gateway: any) => gateway?.channelId || gateway?.channelDetail?.id
const gatewayProtocolId = (gateway: any) => gateway?.protocol || gateway?.messageProtocol || gateway?.protocolId || gateway?.protocolDetail?.id
const selectedGateway = computed(() => availableGateways.value.find(item => item.id === selectedAccessId.value))
const reusedNetwork = computed(() => networks.value.find(item => item.id === gatewayNetworkId(selectedGateway.value)))
const reusedProtocol = computed(() => protocols.value.find(item => item.id === gatewayProtocolId(selectedGateway.value)))
const effectiveProtocolId = computed(() => accessMode.value === 'select' ? gatewayProtocolId(selectedGateway.value) : protocolMode.value === 'select' ? selectedProtocolId.value : undefined)
const nameDefaults = computed(() => ({
  ...generatedNames(accessId.value),
  productName: generatedNames(accessMode.value === 'select' ? selectedAccessId.value || '' : accessId.value).productName.slice(0, 64),
}))
const resultLabel = computed(() => ({ idle: '未导入设备', running: '正在导入，请勿关闭页面', success: '全部成功', partial: '部分或全部设备导入失败，请查看错误详情', interrupted: '导入中断，已成功的数据保留', empty: '未导入设备：文件没有有效数据行', unconfirmed: '已收到导入结果，完成状态待核对（旧版接口未返回完成标记）' }[resultStatus.value]))
const routeColumns = [{ title: 'Topic', dataIndex: 'topic' }, { title: '方向', key: 'direction' }, { title: 'QoS', dataIndex: 'qos' }]
const errorColumns = [{ title: '文件行号', dataIndex: 'row' }, { title: '设备 ID', dataIndex: 'deviceId' }, { title: '失败原因', dataIndex: 'message' }]
const metadataColumns = [{ title: '属性 ID', dataIndex: 'id' }, { title: '名称', dataIndex: 'name' }, { title: '地址', dataIndex: 'address' }, { title: '排序', dataIndex: 'sort' }, { title: '精度', dataIndex: 'scale' }]
const options = (items: any[]) => items.map(item => ({ value: item.id, label: `${item.name || item.id}（${item.id}）` }))
const list = (resp: any): any[] => Array.isArray(resp?.result) ? resp.result : resp?.result?.data || []
const eq = (column: string, value: string) => ({ column, termType: 'eq', value })
function checked(resp: any, message: string) {
  if (resp?.success === false || !(resp?.status === 200 || resp?.success === true)) throw new Error(resp?.message || message)
  return resp.result
}
function checkedAction(resp: any, message: string) {
  const result = checked(resp, message)
  if (result === false || result?.success === false) throw new Error(result?.message || message)
  return result
}
function returnedId(resp: any, message: string) {
  const result = checked(resp, message)
  const id = typeof result === 'string' ? result : result?.id
  if (!id) throw new Error(`${message}：未返回 ID，请先核对资源列表，避免重复创建`)
  return id
}
async function run(action: () => Promise<void>) {
  if (busy.value || uploading.value) return
  busy.value = true; error.value = ''
  try { await action() } catch (err: any) { error.value = err?.message || '操作失败，请重试' } finally { busy.value = false }
}
watch(nameDefaults, (next, previous) => updateDefaults(names, previous, next))
watch([accessMode, protocolMode], () => { if (!accessLocked.value) saved.protocolId = '' })
watch(effectiveProtocolId, async id => {
  const version = ++routeVersion; routes.value = []
  if (!id) return
  try { const resp = await getConfigView(id, transport); if (version === routeVersion) routes.value = resp?.result?.routes || [] }
  catch { /* Save performs authoritative validation and reports errors. */ }
})
watch(deviceMode, async mode => {
  if (mode === 'select' && saved.productId) {
    try { devices.value = list(await queryDevices({ paging: false, terms: [eq('productId', saved.productId)] })) }
    catch { error.value = '加载产品下设备失败' }
  }
})
watch([productMode, selectedProductId], async ([mode, id]) => {
  const version = ++modelLoadVersion
  if (saved.productId) return
  metadataRows.value = []
  if (mode !== 'select' || !id) { modelLoading.value = false; return }
  modelLoading.value = true
  try {
    const product = checked(await productDetail(id), '读取产品物模型失败')
    if (version === modelLoadVersion) setMetadataRows(product)
  } catch (err: any) {
    if (version === modelLoadVersion) error.value = err?.message || '读取产品物模型失败'
  } finally {
    if (version === modelLoadVersion) modelLoading.value = false
  }
})
function restoreDefaults() { Object.assign(names, nameDefaults.value); Object.assign(networkForm, networkDefaults()) }
function resetWizard() {
  if (busy.value || uploading.value) return
  accessId.value = ''; restoreDefaults()
  tcpForm.publicHost = ''; tcpForm.port = undefined
  Object.assign(saved, { protocolId: '', networkId: '', accessId: '', productId: '', deviceId: '' })
  Object.assign(started, { network: false, access: false, product: false, device: false })
  networkMode.value = accessMode.value = productMode.value = 'create'; protocolMode.value = 'select'; deviceMode.value = 'import'
  selectedNetworkId.value = selectedAccessId.value = selectedProtocolId.value = selectedProductId.value = selectedDeviceId.value = undefined
  accessComplete.value = productComplete.value = false; jarFile.value = deviceFile.value = metadataFile.value = undefined
  modelLoadVersion++; modelLoading.value = false; metadataRows.value = []; importedMetadataFileId.value = ''
  productId.value = ''; deviceForm.id = ''; deviceForm.name = ''; error.value = ''; step.value = 0
  autoDeploy.value = true; accessCustom.value = []; uploadKey.value++; clearImportResult(); void loadBaseData()
}
async function loadBaseData() {
  loading.value = true
  try {
    const results = await Promise.all([queryNetworkConfig({ paging: false, terms: [eq('type', networkType)] }), queryGatewayList({ paging: false }), getProtocolList(transport, {}), queryProducts({ paging: false }), isTcp ? Promise.resolve({ status: 200, result: [] }) : certificates({ paging: false }), resourcesCurrent()])
    results.forEach(resp => checked(resp, '加载基础配置失败'))
    networks.value = list(results[0]); gateways.value = list(results[1]); protocols.value = list(results[2])
    products.value = list(results[3]).filter(item => (item.deviceType?.value || item.deviceType) === 'device'); certs.value = list(results[4]); nodes.value = list(results[5])
  } catch (err: any) { error.value = err?.message || '加载基础配置失败' } finally { loading.value = false }
}
function beforeJarUpload(file: File) {
  if (!/\.jar$/i.test(file.name)) { error.value = '请选择 .jar 协议包'; return false }
  if (!validAccessId(accessId.value)) { error.value = '请先填写 2～32 位且同时包含字母和数字的接入 ID'; return false }
  jarFile.value = undefined; saved.protocolId = ''; uploading.value = true; return true
}
function uploaded(info: any): UploadedFile | undefined {
  if (info.file.status === 'error') { uploading.value = false; throw new Error('文件上传失败，请检查文件上传权限和网络') }
  if (info.file.status !== 'done') return
  uploading.value = false
  const result = checked(info.file.response, '文件上传失败')
  if (!result?.id || !result?.accessUrl) throw new Error('上传响应缺少文件 ID 或访问地址')
  return { id: result.id, name: result.name || info.file.name, accessUrl: result.accessUrl }
}
function jarUploaded(info: any) {
  try { const file = uploaded(info); if (file) { jarFile.value = file; saved.protocolId = ''; routes.value = []; error.value = '' } }
  catch (err: any) { error.value = err.message }
}
function beforeDeviceUpload(file: File) {
  if (!/\.(xlsx|csv)$/i.test(file.name)) { error.value = '请选择 .xlsx 或 .csv 设备列表'; return false }
  uploading.value = true; deviceFile.value = undefined; return true
}
function deviceFileUploaded(info: any) {
  try { const file = uploaded(info); if (file) { deviceFile.value = file; clearImportResult(); error.value = '' } }
  catch (err: any) { error.value = err.message }
}
function beforeMetadataUpload(file: File) {
  if (!/\.(xls|xlsx|csv)$/i.test(file.name)) { error.value = '请选择 .xls、.xlsx 或 .csv 物模型文件'; return false }
  uploading.value = true; metadataFile.value = undefined; return true
}
function metadataFileUploaded(info: any) {
  try { const file = uploaded(info); if (file) { metadataFile.value = file; importedMetadataFileId.value = ''; error.value = '' } }
  catch (err: any) { error.value = err.message }
}
function clearImportResult() { fileAttempted.value = false; legacyImport.value = false; successCount.value = failureCount.value = 0; importErrors.value = []; detailFile.value = ''; resultStatus.value = 'idle' }
async function ensureProtocol() {
  if (accessMode.value === 'select') {
    saved.protocolId = gatewayProtocolId(selectedGateway.value)
    if (!saved.protocolId) throw new Error('该网关未绑定协议，无法复用')
  } else if (protocolMode.value === 'upload') {
    if (!jarFile.value) throw new Error('请先上传协议 JAR')
    if (!names.protocolName.trim()) throw new Error('请输入协议名称')
    if (!saved.protocolId) saved.protocolId = returnedId(await saveProtocol({ name: names.protocolName.trim(), type: 'jar', state: 1, configuration: { location: jarFile.value.accessUrl, fileId: jarFile.value.id, fileName: jarFile.value.name } }), '创建协议失败')
  } else {
    if (!selectedProtocolId.value) throw new Error(`请选择 ${transport} 协议`)
    saved.protocolId = selectedProtocolId.value
  }
  const supported = list(await getProtocolList(transport, {}))
  if (!supported.some(item => item.id === saved.protocolId)) throw new Error(`协议尚未加载或不支持 ${transport}，请检查协议包后重试`)
  protocols.value = supported
  const config = checked(await getConfigView(saved.protocolId, transport), `读取 ${transport} 协议配置失败`)
  routes.value = config?.routes || []
  if (!isTcp && !routes.value.some(route => route.upstream && route.topic)) throw new Error('协议未声明 MQTT 上行 routes，无法订阅设备消息')
}
async function checkTcpResourceIds() {
  if (!isTcp) return
  const [network, gateway] = await Promise.all([
    networkMode.value === 'create' && !saved.networkId
      ? queryNetworkConfig({ paging: false, terms: [eq('id', tcpIds.value.networkId)] }) : undefined,
    accessMode.value === 'create' && !saved.accessId
      ? queryGatewayList({ paging: false, terms: [eq('id', tcpIds.value.accessId)] }) : undefined,
  ])
  if (network) {
    checked(network, '检查网络组件 ID 失败')
    if (list(network).some(item => item.id === tcpIds.value.networkId)) throw new Error(`组件 ID ${tcpIds.value.networkId} 已存在，请更换辨识 ID 或选择已有组件`)
  }
  if (gateway) {
    checked(gateway, '检查接入网关 ID 失败')
    if (list(gateway).some(item => item.id === tcpIds.value.accessId)) throw new Error(`网关 ID ${tcpIds.value.accessId} 已存在，请更换辨识 ID 或选择已有网关`)
  }
}
function saveAccessStep() {
  return run(async () => {
    if (accessComplete.value) { step.value = 1; return }
    const reuseGateway = accessMode.value === 'select'
    if (reuseGateway) {
      const gateway = selectedGateway.value
      if (!gateway) throw new Error('请选择已有接入网关')
      const networkId = gatewayNetworkId(gateway)
      if (!networkId || !networks.value.some(item => item.id === networkId)) throw new Error('该网关绑定的网络组件不存在或当前账号无权查看，无法复用')
    } else {
      if (!validAccessId(accessId.value)) throw new Error(`${idLabel} 必须为 2～32 位英文字母和数字，且两者都包含`)
      if (networkMode.value === 'select' && !networks.value.some(item => item.id === selectedNetworkId.value)) throw new Error('请选择已有网络组件')
      if (!names.accessName.trim()) throw new Error('接入网关名称不能为空')
    }
    if (!reuseGateway && networkMode.value === 'create') {
      if (isTcp) validateTcpEndpoint(tcpForm.publicHost, tcpForm.port)
      else {
        if (!names.networkName.trim() || !names.clientId.trim() || !networkForm.remoteHost.trim()) throw new Error('组件名称、客户端 ID 和 Broker 地址不能为空')
        if (!Number.isInteger(networkForm.remotePort) || networkForm.remotePort < 1 || networkForm.remotePort > 65535) throw new Error('Broker 端口必须为 1～65535')
        if (!!names.username.trim() !== !!names.password) throw new Error('用户名和密码必须同时填写或同时留空')
        if (networkForm.secure && !networkForm.certId) throw new Error('TLS 模式请选择网络证书')
      }
      if (isNoCommunity && !nodes.value.length) throw new Error('未查询到当前集群节点，无法创建节点独立的网络组件')
    }
    if (!reuseGateway) await checkTcpResourceIds()
    await ensureProtocol()
    if (!saved.networkId) {
      if (reuseGateway) saved.networkId = gatewayNetworkId(selectedGateway.value)
      else if (networkMode.value === 'select') saved.networkId = selectedNetworkId.value!
      else {
        const nodeId = isNoCommunity ? nodes.value[0].clusterNodeId || nodes.value[0].id : undefined
        let payload: any
        if (isTcp) payload = buildTcpServerPayload(accessId.value, tcpForm.publicHost, tcpForm.port!, nodeId)
        else {
          const configuration = { ...networkForm, remoteHost: networkForm.remoteHost.trim(), clientId: names.clientId.trim(), username: names.username.trim(), password: names.password }
          if (!configuration.secure) delete configuration.certId
          payload = { name: names.networkName.trim(), type: networkType, shareCluster: !isNoCommunity }
          if (isNoCommunity) payload.cluster = [{ serverId: nodeId, configuration }]
          else payload.configuration = configuration
        }
        saved.networkId = returnedId(await saveNetwork(payload), '创建网络组件失败')
      }
    }
    if (!started.network) { checkedAction(await startNetwork(saved.networkId), `启动 ${transport} 网络组件失败`); started.network = true }
    if (!saved.accessId) saved.accessId = accessMode.value === 'select' ? selectedAccessId.value! : returnedId(await saveAccess({ ...(isTcp ? { id: tcpIds.value.accessId } : {}), name: names.accessName.trim(), protocol: saved.protocolId, channel: 'network', channelId: saved.networkId, provider, transport }), '创建接入网关失败')
    if (!started.access) { checkedAction(await deployAccess(saved.accessId), '启动接入网关失败'); started.access = true }
    if (!list(await getNetworkList(networkType, saved.networkId, {})).some(item => item.id === saved.networkId)) { started.network = false; throw new Error(isTcp ? 'TCP 服务端未通过可用性校验，请检查监听端口占用和服务端日志' : '网络组件未通过可用性校验，请检查 Broker 地址和凭据') }
    const gateway = list(await queryGatewayList({ paging: false, terms: [eq('id', saved.accessId)] }))[0]
    if (!gateway || !['enabled', 'started', 'running'].includes(gateway.state?.value || gateway.state)) { started.access = false; throw new Error('接入网关未通过启用校验') }
    accessComplete.value = true; step.value = 1
  })
}
function setMetadataRows(product: any) {
  let metadata = product?.metadata
  if (typeof metadata === 'string') {
    try { metadata = metadata.trim() ? JSON.parse(metadata) : {} } catch { throw new Error('产品物模型格式无效，无法展示属性') }
  }
  if (metadata?.properties != null && !Array.isArray(metadata.properties)) throw new Error('产品物模型属性格式无效，无法展示')
  metadataRows.value = (metadata?.properties || []).map((item: any) => ({
    id: item.id ?? '', name: item.name ?? '', address: item.expands?.registerAddr ?? '—',
    sort: item.sortsIndex ?? '—', scale: item.valueType?.scale ?? '—',
  }))
}
async function ensureProduct() {
  if (!accessComplete.value) throw new Error('请先完成接入配置')
  if (!saved.productId) {
    if (productMode.value === 'select') { if (!selectedProductId.value) throw new Error('请选择产品'); saved.productId = selectedProductId.value }
    else {
      if (!names.productName.trim()) throw new Error('请输入产品名称')
      saved.productId = returnedId(await addProduct({ ...(productId.value.trim() ? { id: productId.value.trim() } : {}), name: names.productName.trim(), deviceType: 'device', storePolicy: 'timescaledb-row' }), '创建产品失败')
    }
  }
  const product = checked(await productDetail(saved.productId), '读取产品失败')
  if ((product?.deviceType?.value || product?.deviceType) !== 'device') throw new Error('请选择普通设备产品')
  checked(await updateProduct({ ...product, id: saved.productId, transportProtocol: transport, messageProtocol: saved.protocolId, protocolName: protocols.value.find(item => item.id === saved.protocolId)?.name || names.protocolName, accessId: saved.accessId, accessName: accessMode.value === 'select' ? gateways.value.find(item => item.id === saved.accessId)?.name : names.accessName, accessProvider: provider }), '保存产品接入配置失败')
}
function importMetadata() {
  return run(async () => {
    if (!metadataFile.value) throw new Error('请先上传物模型文件')
    await ensureProduct()
    if (importedMetadataFileId.value !== metadataFile.value.id) {
      checked(await importProductPropertyMetadata(saved.productId, metadataFile.value.accessUrl), '导入物模型失败')
      importedMetadataFileId.value = metadataFile.value.id
    }
    setMetadataRows(checked(await productDetail(saved.productId), '读取导入后的产品物模型失败'))
  })
}
function saveProductStep() {
  return run(async () => {
    if (productComplete.value) { step.value = 2; return }
    if (metadataFile.value && importedMetadataFileId.value !== metadataFile.value.id) throw new Error('物模型文件已上传，请先点击“导入物模型”或移除文件')
    await ensureProduct()
    if (!started.product) { checkedAction(await deployProduct(saved.productId), '启用产品失败'); started.product = true }
    const verified = checked(await productDetail(saved.productId), '校验产品失败')
    if (verified?.accessId !== saved.accessId || verified?.messageProtocol !== saved.protocolId) throw new Error('产品接入绑定校验失败')
    setMetadataRows(verified)
    productComplete.value = true; step.value = 2
  })
}
async function downloadMetadataTemplate(format: 'csv' | 'xlsx') {
  await run(async () => {
    const data: any = await downloadPropertyMetadataTemplate(format)
    const url = URL.createObjectURL(new Blob([data]))
    const anchor = document.createElement('a'); anchor.href = url; anchor.download = `产品物模型模板.${format}`; anchor.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  })
}
async function downloadTemplate(format: 'csv' | 'xlsx') {
  await run(async () => {
    const data: any = await templateDownload(saved.productId, format)
    const url = URL.createObjectURL(new Blob([data]))
    const anchor = document.createElement('a'); anchor.href = url; anchor.download = `${transport}设备导入模板.${format}`; anchor.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  })
}
function receiveImport(event: ImportEvent) {
  if (event.type === 'legacyProgress') {
    legacyImport.value = true
    successCount.value += event.successCount || 0; failureCount.value += event.failureCount || 0
    if (event.detailFile) detailFile.value = event.detailFile
    if (!event.success && event.message) importErrors.value.push(event)
  }
  else if (event.type === 'row') { if (event.success) successCount.value++; else { failureCount.value++; importErrors.value.push(event) } }
  else if (event.type === 'detailFile') detailFile.value = event.detailFile || ''
  else { successCount.value = event.successCount || 0; failureCount.value = event.failureCount || 0; resultStatus.value = failureCount.value ? 'partial' : successCount.value ? 'success' : 'empty' }
}
function saveDevices() {
  return run(async () => {
    if (!productComplete.value) throw new Error('请先创建并启用产品')
    if (deviceMode.value === 'import') {
      if (!deviceFile.value || fileAttempted.value) throw new Error('请上传尚未导入的设备列表；重试时请仅上传失败行')
      clearImportResult(); fileAttempted.value = true; resultStatus.value = 'running'; importController = new AbortController()
      try {
        const result = await consumeImportStream(await createOnlyDeviceImport(saved.productId, deviceFile.value.accessUrl, autoDeploy.value, importController.signal), receiveImport)
        if (result === 'legacy') resultStatus.value = 'unconfirmed'
      }
      catch (err) { resultStatus.value = 'interrupted'; throw err } finally { importController = undefined }
      return
    }
    if (deviceMode.value === 'select') {
      if (!selectedDeviceId.value) throw new Error('请选择设备')
      const device = checked(await deviceDetail(selectedDeviceId.value), '读取设备失败')
      if (device?.productId !== saved.productId) throw new Error('设备不属于当前产品')
      saved.deviceId = selectedDeviceId.value
    } else if (!saved.deviceId) {
      if (!deviceForm.id.trim() || !deviceForm.name.trim()) throw new Error('请输入设备 ID 和名称')
      if (checked(await isExists(deviceForm.id.trim()), '检查设备 ID 失败')) throw new Error('设备 ID 已存在，请选择已有设备或修改 ID')
      saved.deviceId = returnedId(await addDevice({ id: deviceForm.id.trim(), name: deviceForm.name.trim(), productId: saved.productId }), '创建设备失败')
    }
    if (autoDeploy.value && !started.device) { checkedAction(await deployDevice(saved.deviceId), '启用设备失败'); started.device = true }
    const verified = checked(await deviceDetail(saved.deviceId), '设备保存校验失败')
    if (verified?.productId !== saved.productId) throw new Error('设备所属产品校验失败')
    successCount.value = 1; failureCount.value = 0; importErrors.value = []; detailFile.value = ''; resultStatus.value = 'success'
  })
}
onMounted(loadBaseData)
onBeforeUnmount(() => importController?.abort())
</script>

<style scoped lang="less">
.mqtt-wizard { max-width: 1200px; margin: 0 auto; padding: 24px; color: var(--app-text); }
.page-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; margin-bottom: 24px; }
h2 { margin: 0 0 8px; color: var(--app-text); } h3 { margin-top: 24px; color: var(--app-text); }
.steps, .panel { padding: 24px; margin-bottom: 20px; border: 1px solid var(--app-border); border-radius: 10px; background: var(--app-surface); }
.fields { display: grid; grid-template-columns: 1fr 1fr; gap: 0 20px; }
.notice { margin: 16px 0; } .hint { color: var(--app-text-secondary); line-height: 1.8; margin: 12px 0 20px; }
.custom-config { margin-bottom: 20px; }
.file-name { margin-left: 12px; overflow-wrap: anywhere; }
.actions { display: flex; justify-content: flex-end; gap: 12px; border-top: 1px solid var(--app-border); padding-top: 20px; margin-top: 24px; }
:deep(.ant-input-number) { width: 100%; }
@media (max-width: 760px) { .fields { grid-template-columns: 1fr; } .page-head { flex-direction: column; } .mqtt-wizard { padding: 12px; } }
</style>

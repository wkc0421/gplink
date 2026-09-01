<template>
  <j-page-container>
    <FullPage :fixed="false">
      <div class="mqtt-client-access-page">
        <div class="page-head">
          <div>
            <div class="page-title">MQTT 客户端接入向导</div>
            <div class="page-subtitle">
              集中配置外部 Broker 连接、MQTT 接入网关、设备产品和设备，并在保存后完成启用校验。
            </div>
          </div>
          <a-space>
            <a-button
              href="/docs/mqtt-client-config-guide.html"
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
              hasPermission="device/MqttClientAccess:save"
              @click="handleSaveAndEnable"
            >
              <template #icon><AIcon type="SaveOutlined" /></template>
              保存并启用
            </j-permission-button>
          </a-space>
        </div>

        <div class="steps-wrap">
          <a-steps :current="currentStep">
            <a-step title="Broker 连接" />
            <a-step title="接入网关" />
            <a-step title="产品设备" />
            <a-step title="保存校验" />
          </a-steps>
        </div>

        <section class="guide-panel" aria-label="MQTT 客户端向导操作说明">
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
            <div class="guide-next">完成后：{{ currentGuide.next }}</div>
          </div>
        </section>

        <div class="content-panel">
          <a-spin :spinning="loading || saving">
            <a-form layout="vertical">
              <section v-show="currentStep === 0" class="step-panel">
                <div class="section-title">MQTT Broker 客户端连接</div>
                <a-form-item label="配置方式">
                  <a-radio-group v-model:value="networkMode" button-style="solid">
                    <a-radio-button value="select">选择已有</a-radio-button>
                    <a-radio-button value="create">新建客户端</a-radio-button>
                  </a-radio-group>
                </a-form-item>

                <template v-if="networkMode === 'select'">
                  <a-row :gutter="16" class="form-row">
                    <a-col :span="16">
                      <a-form-item label="MQTT_CLIENT 网络组件">
                        <a-select
                          v-model:value="selectedNetworkId"
                          show-search
                          option-filter-prop="label"
                          placeholder="请选择 MQTT 客户端网络组件"
                        >
                          <a-select-option
                            v-for="item in mqttNetworkList"
                            :key="item.id"
                            :value="item.id"
                            :label="`${item.name} ${item.id}`"
                          >
                            {{ item.name }}（{{ item.id }}）
                            <a-tag :color="isEnabled(item) ? 'green' : 'default'" class="option-state">
                              {{ isEnabled(item) ? '已启用' : '未启用' }}
                            </a-tag>
                          </a-select-option>
                        </a-select>
                      </a-form-item>
                    </a-col>
                    <a-col :span="8">
                      <a-form-item label="类型">
                        <a-input value="MQTT_CLIENT" disabled />
                      </a-form-item>
                    </a-col>
                  </a-row>
                  <a-empty
                    v-if="!mqttNetworkList.length"
                    description="未查询到 MQTT_CLIENT 网络组件，可切换到“新建客户端”"
                  />
                </template>

                <template v-else>
                  <a-alert
                    show-icon
                    type="info"
                    message="新建网络组件会在保存时启动；集群版本固定落在当前节点，避免同一 clientId 在多个节点重复连接 Broker。"
                    class="step-alert"
                  />
                  <a-row :gutter="16">
                    <a-col :span="12">
                      <a-form-item label="组件名称" required>
                        <a-input v-model:value="networkForm.name" placeholder="例如：生产 Broker 客户端" :maxlength="64" />
                      </a-form-item>
                    </a-col>
                    <a-col :span="12">
                      <a-form-item label="客户端 ID" required>
                        <a-input v-model:value="networkForm.clientId" placeholder="Broker 侧唯一 clientId" :maxlength="64" />
                      </a-form-item>
                    </a-col>
                    <a-col :span="16">
                      <a-form-item label="Broker 地址" required>
                        <a-input v-model:value="networkForm.remoteHost" placeholder="域名或 IP，不要填写 mqtt:// 前缀" />
                      </a-form-item>
                    </a-col>
                    <a-col :span="8">
                      <a-form-item label="Broker 端口" required>
                        <a-input-number v-model:value="networkForm.remotePort" :min="1" :max="65535" style="width: 100%" />
                      </a-form-item>
                    </a-col>
                    <a-col :span="12">
                      <a-form-item label="用户名">
                        <a-input v-model:value="networkForm.username" placeholder="匿名连接时留空" :maxlength="64" />
                      </a-form-item>
                    </a-col>
                    <a-col :span="12">
                      <a-form-item label="密码">
                        <a-input-password v-model:value="networkForm.password" placeholder="匿名连接时留空" :maxlength="64" />
                      </a-form-item>
                    </a-col>
                    <a-col :span="12">
                      <a-form-item>
                        <template #label>
                          主题前缀
                          <a-tooltip title="平台订阅协议上行 Topic 前会直接拼接此前缀；普通场景留空，共享订阅可填 $share/组名。">
                            <AIcon type="QuestionCircleOutlined" class="label-help" />
                          </a-tooltip>
                        </template>
                        <a-input v-model:value="networkForm.topicPrefix" placeholder="例如：$share/gplink" :maxlength="64" />
                      </a-form-item>
                    </a-col>
                    <a-col :span="12">
                      <a-form-item label="最大消息长度（字节）" required>
                        <a-input-number
                          v-model:value="networkForm.maxMessageSize"
                          :min="1024"
                          :max="1073741824"
                          style="width: 100%"
                        />
                      </a-form-item>
                    </a-col>
                    <a-col :span="8">
                      <a-form-item label="TLS">
                        <a-switch v-model:checked="networkForm.secure" @change="handleSecureChange" />
                        <span class="switch-tip">{{ networkForm.secure ? 'mqtts' : 'mqtt' }}</span>
                      </a-form-item>
                    </a-col>
                    <a-col v-if="networkForm.secure" :span="16">
                      <a-form-item label="网络证书" required>
                        <a-select
                          v-model:value="networkForm.certId"
                          show-search
                          option-filter-prop="label"
                          placeholder="请选择网络证书"
                        >
                          <a-select-option v-for="item in certificateList" :key="item.id" :value="item.id" :label="item.name">
                            {{ item.name }}（{{ item.id }}）
                          </a-select-option>
                        </a-select>
                      </a-form-item>
                    </a-col>
                  </a-row>
                </template>
              </section>

              <section v-show="currentStep === 1" class="step-panel">
                <div class="section-title">MQTT Broker 接入网关与协议</div>
                <a-alert
                  show-icon
                  type="info"
                  message="接入网关负责按协议中的上行 MQTT routes 订阅消息；主题不是在本页手工另建。"
                  class="step-alert"
                />
                <a-form-item label="配置方式">
                  <a-radio-group v-model:value="accessMode" button-style="solid">
                    <a-radio-button value="select">选择已有</a-radio-button>
                    <a-radio-button value="create">新建接入网关</a-radio-button>
                  </a-radio-group>
                </a-form-item>

                <a-row :gutter="16">
                  <a-col :span="12">
                    <a-form-item v-if="accessMode === 'select'" label="MQTT 客户端接入网关" required>
                      <a-select
                        v-model:value="selectedAccessId"
                        show-search
                        option-filter-prop="label"
                        placeholder="请选择使用当前网络组件的接入网关"
                      >
                        <a-select-option
                          v-for="item in availableAccessList"
                          :key="item.id"
                          :value="item.id"
                          :label="`${item.name} ${item.id}`"
                        >
                          {{ item.name }}（{{ item.id }}）
                        </a-select-option>
                      </a-select>
                    </a-form-item>
                    <a-form-item v-else label="接入网关名称" required>
                      <a-input v-model:value="accessForm.name" placeholder="例如：生产 Broker 接入" :maxlength="64" />
                    </a-form-item>
                  </a-col>
                  <a-col :span="12">
                    <a-form-item label="MQTT 协议" required>
                      <a-select
                        v-model:value="selectedProtocolId"
                        show-search
                        option-filter-prop="label"
                        :disabled="accessMode === 'select'"
                        placeholder="请选择支持 MQTT 的协议"
                      >
                        <a-select-option
                          v-for="item in protocolList"
                          :key="item.id"
                          :value="item.id"
                          :label="`${item.name} ${item.id}`"
                        >
                          {{ item.name }}（{{ item.id }}）
                        </a-select-option>
                      </a-select>
                    </a-form-item>
                  </a-col>
                  <a-col v-if="accessMode === 'create'" :span="24">
                    <a-form-item label="说明">
                      <a-textarea v-model:value="accessForm.description" :rows="2" :maxlength="200" show-count />
                    </a-form-item>
                  </a-col>
                </a-row>

                <div class="section-title sub">协议主题预览</div>
                <a-table
                  size="small"
                  bordered
                  :pagination="false"
                  :columns="routeColumns"
                  :data-source="protocolRoutes"
                  :row-key="(record, index) => `${record.topic || 'route'}_${index}`"
                  :scroll="{ x: 720 }"
                >
                  <template #bodyCell="{ column, record }">
                    <template v-if="column.key === 'direction'">
                      <a-tag v-if="record.upstream" color="blue">上行订阅</a-tag>
                      <a-tag v-if="record.downstream" color="purple">下行发布</a-tag>
                    </template>
                    <template v-else-if="column.key === 'qos'">QoS {{ record.qos ?? 0 }}</template>
                  </template>
                </a-table>
                <a-empty v-if="selectedProtocolId && !protocolRoutes.length" description="该协议没有返回 MQTT routes，接入网关启动后不会自动订阅上行主题" />
              </section>

              <section v-show="currentStep === 2" class="step-panel">
                <div class="section-title">设备产品与设备</div>
                <a-alert
                  v-if="selectedProductConflict"
                  show-icon
                  type="warning"
                  message="所选产品当前绑定了其他接入方式，保存会改为本向导选择的 MQTT 客户端接入网关。"
                  class="step-alert"
                />
                <a-row :gutter="24">
                  <a-col :span="12">
                    <a-form-item label="产品">
                      <a-radio-group v-model:value="productMode" button-style="solid" @change="handleProductModeChange">
                        <a-radio-button value="select">选择已有</a-radio-button>
                        <a-radio-button value="create">新建产品</a-radio-button>
                      </a-radio-group>
                    </a-form-item>
                    <a-form-item v-if="productMode === 'select'" label="普通设备产品" required>
                      <a-select
                        v-model:value="selectedProductId"
                        show-search
                        option-filter-prop="label"
                        placeholder="请选择 deviceType=device 的产品"
                      >
                        <a-select-option
                          v-for="item in directProductList"
                          :key="item.id"
                          :value="item.id"
                          :label="`${item.name} ${item.id}`"
                        >
                          {{ item.name }}（{{ item.id }}）
                        </a-select-option>
                      </a-select>
                    </a-form-item>
                    <template v-else>
                      <a-form-item label="产品 ID">
                        <a-input v-model:value="productForm.id" placeholder="留空由系统生成" />
                      </a-form-item>
                      <a-form-item label="产品名称" required>
                        <a-input v-model:value="productForm.name" placeholder="例如：MQTT 电表产品" :maxlength="64" />
                      </a-form-item>
                    </template>
                  </a-col>

                  <a-col :span="12">
                    <a-form-item label="设备">
                      <a-radio-group v-model:value="deviceMode" button-style="solid" :disabled="productMode === 'create'">
                        <a-radio-button value="select">选择已有</a-radio-button>
                        <a-radio-button value="create">新建设备</a-radio-button>
                      </a-radio-group>
                    </a-form-item>
                    <a-form-item v-if="deviceMode === 'select'" label="产品下设备" required>
                      <a-select
                        v-model:value="selectedDeviceId"
                        show-search
                        option-filter-prop="label"
                        placeholder="请选择设备"
                      >
                        <a-select-option
                          v-for="item in deviceList"
                          :key="item.id"
                          :value="item.id"
                          :label="`${item.name} ${item.id}`"
                        >
                          {{ item.name }}（{{ item.id }}）
                        </a-select-option>
                      </a-select>
                    </a-form-item>
                    <template v-else>
                      <a-form-item label="设备 ID">
                        <a-input v-model:value="deviceForm.id" placeholder="建议与协议 Topic 中的 deviceId 一致；留空由系统生成" />
                      </a-form-item>
                      <a-form-item label="设备名称" required>
                        <a-input v-model:value="deviceForm.name" placeholder="例如：MQTT 设备 1" :maxlength="64" />
                      </a-form-item>
                    </template>
                  </a-col>
                </a-row>
                <a-divider />
                <a-form-item label="保存后状态">
                  <a-checkbox v-model:checked="enableAfterSave">启用网络组件、接入网关、产品和设备</a-checkbox>
                </a-form-item>
                <a-alert
                  show-icon
                  type="info"
                  message="设备 ID 必须能被所选协议从上行 Topic 或报文中解析出来；本向导不会修改协议编解码逻辑。"
                />
              </section>

              <section v-show="currentStep === 3" class="step-panel">
                <div class="section-title">配置确认与启用校验</div>
                <a-descriptions bordered :column="2" size="small" class="review-table">
                  <a-descriptions-item label="网络组件">{{ reviewData.network }}</a-descriptions-item>
                  <a-descriptions-item label="Broker">{{ reviewData.broker }}</a-descriptions-item>
                  <a-descriptions-item label="接入网关">{{ reviewData.access }}</a-descriptions-item>
                  <a-descriptions-item label="协议">{{ reviewData.protocol }}</a-descriptions-item>
                  <a-descriptions-item label="产品">{{ reviewData.product }}</a-descriptions-item>
                  <a-descriptions-item label="设备">{{ reviewData.device }}</a-descriptions-item>
                  <a-descriptions-item label="主题前缀">{{ reviewData.topicPrefix }}</a-descriptions-item>
                  <a-descriptions-item label="保存后启用">{{ enableAfterSave ? '是' : '否' }}</a-descriptions-item>
                </a-descriptions>

                <a-alert
                  show-icon
                  type="warning"
                  message="保存按“网络组件 → 接入网关 → 产品接入 → 设备”的顺序执行；若中途失败，已成功创建的资源不会自动删除。"
                  class="step-alert review-alert"
                />

                <template v-if="verificationRows.length">
                  <div class="section-title sub">最近一次校验结果</div>
                  <a-table
                    size="small"
                    bordered
                    :pagination="false"
                    :columns="verificationColumns"
                    :data-source="verificationRows"
                    row-key="key"
                  >
                    <template #bodyCell="{ column, record }">
                      <template v-if="column.key === 'status'">
                        <a-tag :color="record.status === 'success' ? 'green' : 'red'">
                          {{ record.status === 'success' ? '通过' : '失败' }}
                        </a-tag>
                      </template>
                    </template>
                  </a-table>
                </template>
              </section>
            </a-form>
          </a-spin>

          <div class="step-actions">
            <a-button v-if="currentStep > 0" @click="currentStep -= 1">上一步</a-button>
            <a-button v-if="currentStep < 3" type="primary" @click="goNext">下一步</a-button>
            <j-permission-button
              v-else
              type="primary"
              :loading="saving"
              hasPermission="device/MqttClientAccess:save"
              @click="handleSaveAndEnable"
            >
              保存并启用
            </j-permission-button>
          </div>
        </div>

        <a-modal v-model:open="errorVisible" title="请先修正以下配置" :footer="null">
          <a-alert
            v-for="item in validationErrors"
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
import { isNoCommunity } from '@/utils/utils'
import {
  certificates,
  queryNetworkConfig,
  resourcesCurrent,
  save as saveNetwork,
  start as startNetwork,
} from '../../../api/link/type'
import {
  deploy as deployAccess,
  getNetworkList,
  getProtocolList,
  save as saveAccess,
} from '../../../api/link/accessConfig'
import {
  _deploy as deployProduct,
  addProduct,
  detail as getProductDetail,
  getConfigView,
  queryGatewayList,
  queryNoPagingPost as queryProductNoPaging,
  updateDevice as updateProductAccess,
} from '../../../api/product'
import {
  _deploy as deployDevice,
  addDevice,
  detail as getDeviceDetail,
  queryNoPagingPost as queryDeviceNoPaging,
} from '../../../api/instance'

const MQTT_NETWORK_TYPE = 'MQTT_CLIENT'
const MQTT_ACCESS_PROVIDER = 'mqtt-client-gateway'
const MQTT_TRANSPORT = 'MQTT'

type Mode = 'select' | 'create'
type VerificationRow = {
  key: string
  target: string
  resourceId: string
  status: 'success' | 'error'
  message: string
}

const currentStep = ref(0)
const guideExpanded = ref(true)
const loading = ref(false)
const saving = ref(false)
const errorVisible = ref(false)
const validationErrors = ref<string[]>([])

const networkMode = ref<Mode>('select')
const accessMode = ref<Mode>('select')
const productMode = ref<Mode>('select')
const deviceMode = ref<Mode>('select')
const enableAfterSave = ref(true)

const networkList = ref<any[]>([])
const accessList = ref<any[]>([])
const protocolList = ref<any[]>([])
const productList = ref<any[]>([])
const deviceList = ref<any[]>([])
const certificateList = ref<any[]>([])
const currentResources = ref<any[]>([])
const protocolConfig = ref<any>({})

const selectedNetworkId = ref<string>()
const selectedAccessId = ref<string>()
const selectedProtocolId = ref<string>()
const selectedProductId = ref<string>()
const selectedDeviceId = ref<string>()
const verificationRows = ref<VerificationRow[]>([])

const networkForm = reactive({
  name: 'MQTT Broker 客户端',
  remoteHost: '',
  remotePort: 1883,
  clientId: `gplink_${Date.now().toString(36)}`,
  username: '',
  password: '',
  topicPrefix: '',
  maxMessageSize: 8192,
  secure: false,
  certId: undefined as string | undefined,
})

const accessForm = reactive({
  name: 'MQTT Broker 接入网关',
  description: '通过平台 MQTT_CLIENT 网络组件接入外部 Broker',
})

const productForm = reactive({ id: '', name: 'MQTT 设备产品' })
const deviceForm = reactive({ id: '', name: 'MQTT 设备 1' })

const guideSteps = [
  {
    title: '连接外部 MQTT Broker',
    summary: '选择已有 MQTT_CLIENT 网络组件，或填写 Broker 地址和客户端身份新建专用连接。',
    items: [
      'Broker 地址只填域名或 IP，端口通常为 1883；启用 TLS 时通常使用 8883。',
      'clientId 必须在 Broker 侧唯一；集群环境不要让多个节点使用同一 clientId。',
      '用户名和密码必须同时填写或同时留空，TLS 模式还需要选择已上传的网络证书。',
      '主题前缀会直接拼在协议 Topic 前，普通订阅留空，共享订阅可填写 $share/组名。',
    ],
    example: '协议 Topic 为 /product/device/properties/report，前缀 $share/gplink 会订阅 $share/gplink/product/device/properties/report。',
    next: '点击“下一步”选择或创建 MQTT Broker 接入网关。',
  },
  {
    title: '把网络组件绑定到 MQTT 接入网关',
    summary: '接入网关使用 mqtt-client-gateway provider，并从协议声明的上行 routes 自动订阅 Topic。',
    items: [
      '选择已有接入网关时，只显示绑定当前 MQTT_CLIENT 网络组件的网关。',
      '新建时选择一个支持 MQTT transport 的协议，保存后网关会自动启动。',
      '检查主题预览中的上行订阅和下行发布方向，确认协议与外部设备报文格式一致。',
      '如果主题预览为空，需要先在协议包中声明 MQTT routes。',
    ],
    example: 'MQTT 客户端表示平台主动连接外部 Broker，不表示设备直接连接平台内置 MQTT Server。',
    next: '点击“下一步”绑定普通设备产品并准备设备。',
  },
  {
    title: '绑定产品接入并准备设备',
    summary: '产品保存为普通直连设备类型，并绑定当前 MQTT 客户端接入网关和协议。',
    items: [
      '选择已有普通设备产品，或新建 deviceType=device 的产品。',
      '选择已有设备，或填写设备 ID 和名称新建设备。',
      '设备 ID 应与协议从 Topic 或报文中解析出的 deviceId 一致。',
      '默认保存后启用全部资源；如只想保存草稿，可取消“保存后状态”。',
    ],
    example: '官方 MQTT 协议通常从 Topic 的 productId/deviceId 段定位设备，设备 ID 不一致会导致消息无法归属。',
    next: '点击“下一步”复核配置并保存。',
  },
  {
    title: '按依赖顺序保存并校验',
    summary: '向导先确保网络连接可用，再启动接入网关，最后保存产品接入和设备。',
    items: [
      '复核 Broker、接入网关、协议、产品和设备是否属于同一条链路。',
      '点击“保存并启用”后等待四层资源逐项返回。',
      '校验通过只代表配置和启用接口成功；真实收发仍需外部 Broker 和设备发送符合协议的消息。',
      '若中途失败，修正配置后可以复用已创建资源再次执行。',
    ],
    example: '最终联调时从外部 Broker 发布一条符合协议上行 Topic 和 payload 的消息，再检查设备上线与属性数据。',
    next: '查看校验结果，并使用真实 Broker 消息完成端到端联调。',
  },
] as const

const currentGuide = computed(() => guideSteps[currentStep.value] || guideSteps[0])
const mqttNetworkList = computed(() => networkList.value.filter(item => item?.type === MQTT_NETWORK_TYPE))
const selectedNetwork = computed(() => mqttNetworkList.value.find(item => item.id === selectedNetworkId.value))
const selectedAccess = computed(() => accessList.value.find(item => item.id === selectedAccessId.value))
const selectedProtocol = computed(() => protocolList.value.find(item => item.id === selectedProtocolId.value))
const selectedProduct = computed(() => productList.value.find(item => item.id === selectedProductId.value))
const selectedDevice = computed(() => deviceList.value.find(item => item.id === selectedDeviceId.value))

const availableAccessList = computed(() => accessList.value.filter(item => {
  if (item?.provider !== MQTT_ACCESS_PROVIDER) return false
  if (!selectedNetworkId.value) return true
  return item.channelId === selectedNetworkId.value || item.channelDetail?.id === selectedNetworkId.value
}))

const directProductList = computed(() => productList.value.filter(item => getDeviceType(item) === 'device'))
const selectedProductConflict = computed(() => {
  const product = selectedProduct.value
  const accessId = accessMode.value === 'select' ? selectedAccessId.value : undefined
  return !!(product?.accessId && accessId && product.accessId !== accessId)
})

const protocolRoutes = computed(() => Array.isArray(protocolConfig.value?.routes) ? protocolConfig.value.routes : [])
const routeColumns = [
  { title: 'Topic', dataIndex: 'topic', key: 'topic', width: 360 },
  { title: '方向', key: 'direction', width: 180 },
  { title: 'QoS', key: 'qos', width: 100 },
  { title: '说明', dataIndex: 'description', key: 'description', width: 260 },
]
const verificationColumns = [
  { title: '资源', dataIndex: 'target', key: 'target', width: 150 },
  { title: '资源 ID', dataIndex: 'resourceId', key: 'resourceId', width: 240 },
  { title: '状态', key: 'status', width: 90 },
  { title: '说明', dataIndex: 'message', key: 'message' },
]

const reviewData = computed(() => ({
  network: networkMode.value === 'select'
    ? formatResource(selectedNetwork.value, selectedNetworkId.value)
    : `${networkForm.name}（新建）`,
  broker: networkMode.value === 'select'
    ? formatBroker(selectedNetwork.value)
    : `${networkForm.secure ? 'mqtts' : 'mqtt'}://${networkForm.remoteHost || '--'}:${networkForm.remotePort}`,
  access: accessMode.value === 'select'
    ? formatResource(selectedAccess.value, selectedAccessId.value)
    : `${accessForm.name}（新建）`,
  protocol: formatResource(selectedProtocol.value, selectedProtocolId.value),
  product: productMode.value === 'select'
    ? formatResource(selectedProduct.value, selectedProductId.value)
    : `${productForm.name}（新建）`,
  device: deviceMode.value === 'select'
    ? formatResource(selectedDevice.value, selectedDeviceId.value)
    : `${deviceForm.name}（新建）`,
  topicPrefix: networkMode.value === 'create'
    ? (networkForm.topicPrefix || '无')
    : (getNetworkConfiguration(selectedNetwork.value)?.topicPrefix || '无'),
}))

function normalizeResultList(resp: any) {
  if (Array.isArray(resp?.result?.data)) return resp.result.data
  if (Array.isArray(resp?.result)) return resp.result
  return []
}

function isOk(resp: any) {
  if (resp?.success === false) return false
  return resp?.success === true || resp?.status === 200
}

function stateValue(value: any) {
  return value?.state?.value || value?.state || ''
}

function isEnabled(value: any) {
  return ['enabled', 'started', 'running', 'deployed'].includes(String(stateValue(value)).toLowerCase())
}

function getDeviceType(value: any) {
  return value?.deviceType?.value || value?.deviceType
}

function getAccessProtocol(value: any) {
  return value?.protocol || value?.messageProtocol || value?.protocolId || value?.protocolDetail?.id
}

function getNetworkConfiguration(value: any) {
  return value?.configuration || value?.cluster?.[0]?.configuration || {}
}

function formatResource(value: any, fallback?: string) {
  if (!value) return fallback || '--'
  return value.name ? `${value.name}（${value.id}）` : (value.id || fallback || '--')
}

function formatBroker(value: any) {
  const config = getNetworkConfiguration(value)
  if (!config.remoteHost) return '--'
  return `${config.secure ? 'mqtts' : 'mqtt'}://${config.remoteHost}:${config.remotePort || '--'}`
}

function termEq(column: string, value: any) {
  return { column, termType: 'eq', value }
}

async function loadBaseData() {
  loading.value = true
  try {
    const [networkResp, accessResp, protocolResp, productResp, certResp, resourceResp] = await Promise.all([
      queryNetworkConfig({
        paging: false,
        terms: [termEq('type', MQTT_NETWORK_TYPE)],
        sorts: [{ name: 'createTime', order: 'desc' }],
      }),
      queryGatewayList({ paging: false, sorts: [{ name: 'createTime', order: 'desc' }] }),
      getProtocolList(MQTT_TRANSPORT, {
        'sorts[0].name': 'createTime',
        'sorts[0].order': 'desc',
      }),
      queryProductNoPaging({ paging: false, sorts: [{ name: 'createTime', order: 'desc' }] }),
      certificates({ paging: false, sorts: [{ name: 'createTime', order: 'desc' }] }),
      resourcesCurrent(),
    ])
    networkList.value = normalizeResultList(networkResp)
    accessList.value = normalizeResultList(accessResp)
    protocolList.value = normalizeResultList(protocolResp)
    productList.value = normalizeResultList(productResp)
    certificateList.value = normalizeResultList(certResp)
    currentResources.value = normalizeResultList(resourceResp)

    if (!selectedNetworkId.value && mqttNetworkList.value.length) {
      selectedNetworkId.value = mqttNetworkList.value[0].id
    }
    if (!selectedProtocolId.value && protocolList.value.length) {
      selectedProtocolId.value = protocolList.value[0].id
    }
    if (!selectedProductId.value && directProductList.value.length) {
      selectedProductId.value = directProductList.value[0].id
    }
  } catch (error: any) {
    onlyMessage(error?.message || 'MQTT 客户端向导基础数据加载失败', 'error')
  } finally {
    loading.value = false
  }
}

async function loadDevices(productId?: string) {
  selectedDeviceId.value = undefined
  deviceList.value = []
  if (!productId) return
  const resp = await queryDeviceNoPaging({ paging: false, terms: [termEq('productId', productId)] })
  deviceList.value = normalizeResultList(resp)
  if (deviceList.value.length) selectedDeviceId.value = deviceList.value[0].id
}

async function loadProtocolRoutes(protocolId?: string) {
  protocolConfig.value = {}
  if (!protocolId) return
  const resp = await getConfigView(protocolId, MQTT_TRANSPORT).catch(() => undefined)
  if (isOk(resp)) protocolConfig.value = resp?.result || {}
}

watch(selectedNetworkId, () => {
  if (accessMode.value !== 'select') return
  if (!availableAccessList.value.some(item => item.id === selectedAccessId.value)) {
    selectedAccessId.value = availableAccessList.value[0]?.id
  }
})

watch(selectedAccessId, (id) => {
  if (accessMode.value !== 'select' || !id) return
  const protocolId = getAccessProtocol(selectedAccess.value)
  if (protocolId) selectedProtocolId.value = protocolId
})

watch(selectedProtocolId, loadProtocolRoutes, { immediate: true })
watch(selectedProductId, id => {
  if (productMode.value === 'select') loadDevices(id)
})

watch(accessMode, (mode) => {
  if (mode === 'select') {
    selectedAccessId.value = availableAccessList.value[0]?.id
  } else if (!selectedProtocolId.value && protocolList.value.length) {
    selectedProtocolId.value = protocolList.value[0].id
  }
})

function handleSecureChange(secure: boolean) {
  if (secure && networkForm.remotePort === 1883) networkForm.remotePort = 8883
  if (!secure && networkForm.remotePort === 8883) networkForm.remotePort = 1883
  if (!secure) networkForm.certId = undefined
}

function handleProductModeChange() {
  if (productMode.value === 'create') {
    deviceMode.value = 'create'
    selectedDeviceId.value = undefined
    deviceList.value = []
  } else {
    loadDevices(selectedProductId.value)
  }
}

function validateStep(step: number) {
  const errors: string[] = []
  if (step === 0) {
    if (networkMode.value === 'select' && !selectedNetworkId.value) errors.push('请选择 MQTT_CLIENT 网络组件')
    if (networkMode.value === 'create') {
      if (!networkForm.name.trim()) errors.push('请输入网络组件名称')
      if (!networkForm.remoteHost.trim()) errors.push('请输入 Broker 地址')
      if (!networkForm.remotePort) errors.push('请输入 Broker 端口')
      if (!networkForm.clientId.trim()) errors.push('请输入客户端 ID')
      if (!!networkForm.username !== !!networkForm.password) errors.push('Broker 用户名和密码必须同时填写或同时留空')
      if (networkForm.secure && !networkForm.certId) errors.push('TLS 模式请选择网络证书')
      if (isNoCommunity && !currentResources.value.length) errors.push('未查询到当前集群节点，无法创建节点独立的 MQTT 客户端网络组件')
    }
  }
  if (step === 1) {
    if (accessMode.value === 'select' && !selectedAccessId.value) errors.push('请选择 MQTT 客户端接入网关')
    if (accessMode.value === 'create' && !accessForm.name.trim()) errors.push('请输入接入网关名称')
    if (!selectedProtocolId.value) errors.push('请选择 MQTT 协议')
  }
  if (step === 2) {
    if (productMode.value === 'select' && !selectedProductId.value) errors.push('请选择普通设备产品')
    if (productMode.value === 'create' && !productForm.name.trim()) errors.push('请输入产品名称')
    if (deviceMode.value === 'select' && !selectedDeviceId.value) errors.push('请选择设备')
    if (deviceMode.value === 'create' && !deviceForm.name.trim()) errors.push('请输入设备名称')
  }
  return errors
}

function validateAll() {
  return [0, 1, 2].flatMap(validateStep)
}

function showValidationErrors(errors: string[]) {
  validationErrors.value = errors
  errorVisible.value = !!errors.length
}

function goNext() {
  const errors = validateStep(currentStep.value)
  if (errors.length) {
    showValidationErrors(errors)
    return
  }
  currentStep.value += 1
}

function buildNetworkConfiguration() {
  return {
    remoteHost: networkForm.remoteHost.trim(),
    remotePort: Number(networkForm.remotePort),
    secure: networkForm.secure,
    clientId: networkForm.clientId.trim(),
    username: networkForm.username.trim(),
    password: networkForm.password,
    topicPrefix: networkForm.topicPrefix.trim(),
    maxMessageSize: Number(networkForm.maxMessageSize),
    ...(networkForm.secure ? { certId: networkForm.certId } : {}),
  }
}

async function ensureNetwork() {
  if (networkMode.value === 'select') return selectedNetworkId.value!

  const configuration = buildNetworkConfiguration()
  const currentNodeId = currentResources.value[0]?.clusterNodeId || currentResources.value[0]?.id
  const payload: Record<string, any> = {
    name: networkForm.name.trim(),
    type: MQTT_NETWORK_TYPE,
    description: `MQTT 客户端连接 ${networkForm.remoteHost}:${networkForm.remotePort}`,
    shareCluster: !isNoCommunity,
  }
  if (isNoCommunity) {
    payload.cluster = [{ serverId: currentNodeId, configuration }]
  } else {
    payload.configuration = configuration
  }

  const resp = await saveNetwork(payload)
  if (!isOk(resp)) throw new Error(resp?.message || '创建 MQTT 客户端网络组件失败')
  const networkId = resp?.result?.id || resp?.result
  if (!networkId) throw new Error('创建 MQTT 客户端网络组件后未返回 ID')
  selectedNetworkId.value = networkId
  return networkId
}

async function ensureAccess(networkId: string) {
  if (accessMode.value === 'select') return selectedAccessId.value!
  const resp = await saveAccess({
    name: accessForm.name.trim(),
    description: accessForm.description.trim(),
    protocol: selectedProtocolId.value,
    channel: 'network',
    channelId: networkId,
    provider: MQTT_ACCESS_PROVIDER,
    transport: MQTT_TRANSPORT,
  })
  if (!isOk(resp)) throw new Error(resp?.message || '创建 MQTT 客户端接入网关失败')
  const accessId = resp?.result?.id || resp?.result
  if (!accessId) throw new Error('创建 MQTT 客户端接入网关后未返回 ID')
  selectedAccessId.value = accessId
  return accessId
}

async function ensureProduct() {
  if (productMode.value === 'select') return getProductDetail(selectedProductId.value!).then(resp => resp?.result)
  const payload: Record<string, any> = {
    name: productForm.name.trim(),
    deviceType: 'device',
    storePolicy: 'timescaledb-row',
  }
  if (productForm.id.trim()) payload.id = productForm.id.trim()
  const resp = await addProduct(payload)
  if (!isOk(resp)) throw new Error(resp?.message || '创建 MQTT 设备产品失败')
  const productId = resp?.result?.id || productForm.id.trim()
  if (!productId) throw new Error('创建 MQTT 设备产品后未返回产品 ID')
  selectedProductId.value = productId
  return getProductDetail(productId).then(detailResp => detailResp?.result || { ...payload, id: productId })
}

async function saveProductAccess(product: any, accessId: string) {
  const accessName = accessMode.value === 'select' ? selectedAccess.value?.name : accessForm.name.trim()
  const protocolName = selectedProtocol.value?.name || selectedProtocolId.value
  const resp = await updateProductAccess({
    ...product,
    id: product.id,
    transportProtocol: MQTT_TRANSPORT,
    protocolName,
    accessId,
    accessName,
    accessProvider: MQTT_ACCESS_PROVIDER,
    messageProtocol: selectedProtocolId.value,
  })
  if (!isOk(resp)) throw new Error(resp?.message || `保存产品 ${product.id} 的 MQTT 接入配置失败`)
  return product.id as string
}

async function ensureDevice(productId: string) {
  if (deviceMode.value === 'select') return selectedDeviceId.value!
  const payload: Record<string, any> = {
    name: deviceForm.name.trim(),
    productId,
  }
  if (deviceForm.id.trim()) payload.id = deviceForm.id.trim()
  const resp = await addDevice(payload)
  if (!isOk(resp)) throw new Error(resp?.message || '创建 MQTT 设备失败')
  const deviceId = resp?.result?.id || deviceForm.id.trim()
  if (!deviceId) throw new Error('创建 MQTT 设备后未返回设备 ID')
  selectedDeviceId.value = deviceId
  return deviceId
}

async function verifySavedResources(networkId: string, accessId: string, productId: string, deviceId: string) {
  const rows: VerificationRow[] = []
  const checks = [
    {
      key: 'network',
      target: 'MQTT 网络组件',
      resourceId: networkId,
      run: async () => {
        const resp = enableAfterSave.value
          ? await getNetworkList(MQTT_NETWORK_TYPE, networkId, {})
          : await queryNetworkConfig({ paging: false, terms: [termEq('id', networkId)] })
        const found = normalizeResultList(resp).some(item => item.id === networkId)
        if (!found) {
          throw new Error(enableAfterSave.value
            ? '未出现在 MQTT_CLIENT 可用列表中，请检查 Broker 地址、凭据和服务端日志'
            : '未查询到已保存的 MQTT_CLIENT 网络组件')
        }
        return enableAfterSave.value ? '网络组件已启动并可被接入网关选择' : '网络组件已保存'
      },
    },
    {
      key: 'access',
      target: 'MQTT 接入网关',
      resourceId: accessId,
      run: async () => {
        const resp = await queryGatewayList({ paging: false, terms: [termEq('id', accessId)] })
        const gateway = normalizeResultList(resp).find(item => item.id === accessId)
        if (!gateway) throw new Error('未查询到接入网关')
        if (enableAfterSave.value && !isEnabled(gateway)) throw new Error(`接入网关状态为 ${stateValue(gateway) || '未知'}`)
        return enableAfterSave.value ? '接入网关已启用' : '接入网关已保存'
      },
    },
    {
      key: 'product',
      target: '设备产品',
      resourceId: productId,
      run: async () => {
        const resp = await getProductDetail(productId)
        if (!resp?.result) throw new Error('未查询到设备产品')
        if (resp.result.accessId !== accessId) throw new Error('产品接入网关未正确绑定')
        return '产品已绑定 MQTT 客户端接入网关'
      },
    },
    {
      key: 'device',
      target: '设备',
      resourceId: deviceId,
      run: async () => {
        const resp = await getDeviceDetail(deviceId, true)
        if (!resp?.result) throw new Error('未查询到设备')
        if (resp.result.productId !== productId) throw new Error('设备所属产品不正确')
        return enableAfterSave.value ? '设备已保存并执行启用' : '设备已保存'
      },
    },
  ]

  for (const check of checks) {
    try {
      rows.push({
        key: check.key,
        target: check.target,
        resourceId: check.resourceId,
        status: 'success',
        message: await check.run(),
      })
    } catch (error: any) {
      rows.push({
        key: check.key,
        target: check.target,
        resourceId: check.resourceId,
        status: 'error',
        message: error?.message || '校验失败',
      })
    }
  }
  verificationRows.value = rows
  return rows.every(item => item.status === 'success')
}

async function handleSaveAndEnable() {
  const errors = validateAll()
  if (errors.length) {
    showValidationErrors(errors)
    return
  }

  saving.value = true
  verificationRows.value = []
  try {
    const networkId = await ensureNetwork()
    if (enableAfterSave.value) {
      const startResp = await startNetwork(networkId)
      if (!isOk(startResp)) throw new Error(startResp?.message || '启动 MQTT 客户端网络组件失败')
    }

    const accessId = await ensureAccess(networkId)
    if (enableAfterSave.value) {
      const deployResp = await deployAccess(accessId)
      if (!isOk(deployResp)) throw new Error(deployResp?.message || '启动 MQTT 客户端接入网关失败')
    }

    const product = await ensureProduct()
    if (!product?.id) throw new Error('设备产品信息不完整')
    const productId = await saveProductAccess(product, accessId)
    if (enableAfterSave.value && !isEnabled(product)) {
      const deployResp = await deployProduct(productId)
      if (!isOk(deployResp)) throw new Error(deployResp?.message || '启用设备产品失败')
    }

    const deviceId = await ensureDevice(productId)
    if (enableAfterSave.value && !isEnabled(selectedDevice.value)) {
      const deployResp = await deployDevice(deviceId)
      if (!isOk(deployResp)) throw new Error(deployResp?.message || '启用设备失败')
    }

    const verified = await verifySavedResources(networkId, accessId, productId, deviceId)
    currentStep.value = 3
    networkMode.value = 'select'
    accessMode.value = 'select'
    productMode.value = 'select'
    deviceMode.value = 'select'
    await loadBaseData()
    await loadDevices(productId)
    selectedNetworkId.value = networkId
    selectedAccessId.value = accessId
    selectedProductId.value = productId
    selectedDeviceId.value = deviceId
    onlyMessage(verified ? 'MQTT 客户端接入配置已保存并通过校验' : '配置已保存，但部分启用校验未通过', verified ? 'success' : 'warning')
  } catch (error: any) {
    onlyMessage(error?.message || 'MQTT 客户端接入配置保存失败', 'error')
  } finally {
    saving.value = false
  }
}

onMounted(loadBaseData)
</script>

<style scoped lang="less">
.mqtt-client-access-page {
  min-height: 100%;
  padding: 24px;
  background: var(--app-info-bg);
}

.page-head,
.steps-wrap,
.guide-panel,
.content-panel {
  max-width: 1440px;
  margin: 0 auto;
}

.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 18px;
}

.page-title {
  color: var(--app-text);
  font-size: 24px;
  font-weight: 600;
  line-height: 1.35;
}

.page-subtitle {
  margin-top: 6px;
  color: var(--app-text);
  line-height: 1.7;
}

.steps-wrap,
.guide-panel,
.content-panel {
  border: 1px solid var(--app-border);
  border-radius: 10px;
  background: var(--app-surface);
  box-shadow: 0 4px 18px rgb(31 41 55 / 4%);
}

.steps-wrap {
  padding: 22px 30px;
  margin-bottom: 16px;
}

.guide-panel {
  padding: 18px 22px;
  margin-bottom: 16px;
  border-color: var(--app-border);
  background: linear-gradient(135deg, var(--app-elevated) 0%, var(--app-surface) 70%);
}

.guide-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.guide-label {
  color: var(--app-primary);
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.08em;
}

.guide-title {
  margin-top: 3px;
  color: var(--app-text);
  font-size: 17px;
  font-weight: 600;
}

.guide-summary {
  margin-top: 9px;
  color: var(--app-text);
  line-height: 1.7;
}

.guide-body {
  padding-top: 12px;
  margin-top: 12px;
  border-top: 1px dashed var(--app-border);
}

.guide-list {
  padding-left: 20px;
  margin: 0;
  color: var(--app-text);
  line-height: 1.9;
}

.guide-example,
.guide-next {
  display: flex;
  gap: 10px;
  padding: 9px 12px;
  margin-top: 10px;
  border-radius: 6px;
  color: var(--app-text);
  background: var(--app-info-bg);
}

.guide-example-label {
  flex: 0 0 auto;
  color: var(--app-primary);
  font-weight: 600;
}

.content-panel {
  padding: 24px;
}

.step-panel {
  min-height: 410px;
}

.section-title {
  padding-left: 10px;
  margin-bottom: 18px;
  border-left: 3px solid var(--app-primary);
  color: var(--app-text);
  font-size: 17px;
  font-weight: 600;
}

.section-title.sub {
  margin-top: 24px;
  font-size: 15px;
}

.step-alert {
  margin-bottom: 18px;
}

.form-row {
  margin-top: 14px;
}

.option-state {
  float: right;
  margin-top: 2px;
}

.label-help {
  margin-left: 5px;
  color: var(--app-text-secondary);
}

.switch-tip {
  margin-left: 10px;
  color: var(--app-text);
}

.review-table {
  margin-bottom: 18px;
}

.review-alert {
  margin-top: 18px;
}

.step-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding-top: 20px;
  margin-top: 22px;
  border-top: 1px solid var(--app-border);
}

.error-item + .error-item {
  margin-top: 10px;
}

@media (max-width: 900px) {
  .mqtt-client-access-page {
    padding: 14px;
  }

  .page-head {
    flex-direction: column;
  }

  :deep(.ant-col) {
    flex: 0 0 100%;
    max-width: 100%;
  }
}
</style>

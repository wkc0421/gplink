<template>
  <div class="metadata-base" :class="{ 'metadata-base-inline': type !== 'properties' }">
    <EditTable
      v-if="!heavyLoad"
      ref="tableRef"
      :data-source="dataSource"
      :columns="displayColumns"
      :validation-columns="columns"
      :should-validate-row="type === 'properties' ? hasPropertyDraftContent : hasDefinitionDraftContent"
      :height="560"
      :disableMenu="!hasOperate('add', type)"
      :openGroup="type === 'properties'"
      :scroll="{ x: 'max-content' }"
      :rowSelection="{
        selectedRowKeys: selectedRowKeys,
      }"
      :readonly="hasOperate('add', type)"
      :target="_target"
      @scrollDown="scrollDown"
      @rightMenuClick="rightMenuClick"
      @groupEdit="groupEdit"
      @groupDelete="groupDelete"
      @groupChange="propertyPage = 1"
      @validationError="onPropertyValidationError"
    >
      <template #extra="{ isFullscreen, fullScreenToggle }">
        <div class="extra-header">
          <div class="extra-left">
            <a-space>
              <template v-if="type === 'properties'">
                <a-button type="primary" :disabled="catalogEditingBlocked" @click="libraryPickerOpen = true">从采集项库添加</a-button>
                <a-button :disabled="catalogEditingBlocked" @click="addCustomProperty">自定义采集项</a-button>
              </template>
              <a-button v-else type="primary" :disabled="definitionAddBlocked" @click="addDefinition">新增{{ definitionName }}</a-button>
              <Import
                v-if="type === 'properties'"
                :target="target"
                :metadata="dataSource"
                @ok="importMetadata"
                :permission="permission"
                :disabled="hasOperate('add', type)"
              />
              <a-tooltip v-if="type === 'properties' && _target === 'product'" title="仅导出已保存属性的导入模板 13 个字段，重新导入可能丢失其他属性配置">
                <a-button :loading="exporting" @click="exportProperties">导出 Excel</a-button>
              </a-tooltip>
              <span v-if="searchData.show">
                {{ $t("Base.Base.640395-0") }}
                <span style="color: var(--app-text-secondary)">{{ searchData.len }}</span>
                {{ $t("Base.Base.640395-1") }}
              </span>
            </a-space>
          </div>
          <div class="extra-center">
            <div v-if="copyDetail.index" class="extra-copy-tip">
              <div class="extra-copy-tip-context">
                <span> {{ $t("Base.Base.640395-2") }} </span>
                <template v-if="type === 'properties'">
                  <j-ellipsis style="max-width: 120px">
                    {{ copyDetail.groupName }}
                  </j-ellipsis>
                  <span>,</span>
                </template>
                <span>
                  {{ $t("Base.Base.640395-3", [copyDetail.index]) }}
                </span>
              </div>
              <div></div>
              <div class="extra-copy-tip-icon" @click="copyDetail.index = 0">
                <AIcon type="CloseOutlined" />
              </div>
            </div>
          </div>
          <div class="extra-right">
            <a-button @click="() => fullToggle(isFullscreen, fullScreenToggle)">
              <template #icon>
                <AIcon
                  :type="
                    isFullscreen
                      ? 'FullscreenExitOutlined'
                      : 'FullscreenOutlined'
                  "
                />
              </template>
            </a-button>

            <j-permission-button
              type="primary"
              key="update"
              placement="topRight"
              :hasPermission="`${permission}:update`"
              :loading="loading"
              :disabled="saveBlocked"
              :tooltip="{
                title: saveBlocked
                  ? $t('Base.Base.640395-5')
                  : $t('Base.Base.640395-6'),
                placement: saveBlocked ? 'topRight' : 'top',
                getPopupContainer: getPopupContainer,
              }"
              @click="handleSaveClick()"
            >
              {{ $t("Base.Base.640395-6") }}
            </j-permission-button>
          </div>
        </div>
      </template>
      <template v-if="type === 'properties'" #displayBody="{ records }">
        <div class="property-batch-toolbar">
          <a-input-search v-model:value="propertyKeyword" aria-label="搜索属性名称或 ID" placeholder="搜索当前分组的属性名称或 ID" allow-clear style="width: 260px" />
          <a-space>
            <span>已选 {{ propertySelectedKeys.length }} 项</span>
            <a-button :disabled="catalogEditingBlocked || !propertySelectedKeys.length" @click="openPropertyBatch">批量设置</a-button>
            <a-button type="link" :disabled="!propertySelectedKeys.length" @click="propertySelectedKeys = []">清空选择</a-button>
          </a-space>
        </div>
        <a-table
          class="property-summary-table"
          :columns="propertySummaryColumns"
          :data-source="filterPropertyRows(records)"
          :row-key="propertyRowKey"
          :row-selection="propertyRowSelection"
          :pagination="{ current: propertyPage, pageSize: propertyPageSize, showSizeChanger: true, showTotal: total => `共 ${total} 项` }"
          :scroll="{ x: 900 }"
          @change="onPropertyPageChange"
        >
          <template #bodyCell="{ column, record, index }">
            <template v-if="column.key === 'serial'">{{ (propertyPage - 1) * propertyPageSize + index + 1 }}</template>
            <template v-else-if="column.key === 'name'">{{ record.name || '未填写' }}</template>
            <template v-else-if="column.key === 'id'">{{ record.id || '未填写' }}</template>
            <template v-else-if="column.key === 'type'">{{ record.valueType?.type || '待配置' }}</template>
            <template v-else-if="column.key === 'unit'">{{ record.valueType?.unit || '—' }}</template>
            <template v-else-if="column.key === 'operation'">{{ (record.expands?.type || []).join(' / ') || '—' }}</template>
            <template v-else-if="column.key === 'status'">
              <a-tag v-if="propertyInvalidIndices.has(record.__dataIndex)" color="error">请完善</a-tag>
              <a-tag v-else-if="propertyNeedsConfiguration(record)" color="warning">待配置</a-tag>
              <span v-else>已配置</span>
            </template>
            <template v-else-if="column.key === 'other'">
              <OtherSetting
                v-if="metadata.some(item => item.id === record.id) && record.valueType?.type"
                v-model:value="record.expands"
                :type="record.valueType.type"
                :id="record.id"
                :name="record.name"
                metadata-type="properties"
                :is-product="record.expands?.isProduct"
                :target="target"
                :record="record"
                :disabled="catalogEditingBlocked || (record.expands?.isProduct && !['int', 'long', 'float', 'double'].includes(record.valueType.type))"
                @change="metadataChange"
              />
              <span v-else>保存后可配置</span>
            </template>
            <template v-else-if="column.key === 'action'">
              <a-space>
                <a-button type="link" size="small" :disabled="catalogEditingBlocked || record.expands?.isProduct" @click="openPropertyEditor(record)">编辑</a-button>
                <a-button type="link" size="small" :disabled="catalogEditingBlocked || record.expands?.isProduct" @click="copyProperty(record)">复制</a-button>
                <a-popconfirm title="确认删除此属性？" @confirm="removeProperty(record)">
                  <a-button type="link" size="small" danger :disabled="catalogEditingBlocked || record.expands?.isProduct">删除</a-button>
                </a-popconfirm>
              </a-space>
            </template>
          </template>
        </a-table>
      </template>
      <template v-else #displayBody="{ records }">
        <div class="property-batch-toolbar">
          <a-input-search v-model:value="definitionKeyword" aria-label="搜索名称或 ID" placeholder="搜索名称或 ID" allow-clear style="width: 260px" />
        </div>
        <a-table
          class="definition-summary-table"
          :columns="definitionSummaryColumns"
          :data-source="filterDefinitionRows(records)"
          :row-key="propertyRowKey"
          :pagination="{ current: definitionPage, pageSize: definitionPageSize, showSizeChanger: true, showTotal: total => `共 ${total} 项` }"
          :scroll="{ x: 900 }"
          @change="onDefinitionPageChange"
        >
          <template #bodyCell="{ column, record, index }">
            <template v-if="column.key === 'serial'">{{ (definitionPage - 1) * definitionPageSize + index + 1 }}</template>
            <template v-else-if="column.key === 'name'">{{ record.name || '未填写' }}</template>
            <template v-else-if="column.key === 'id'">{{ record.id || '未填写' }}</template>
            <template v-else-if="column.key === 'async'">{{ record.async ? '异步' : '同步' }}</template>
            <template v-else-if="column.key === 'inputs'">{{ record.inputs?.length || 0 }} 项</template>
            <template v-else-if="column.key === 'output'">{{ type === 'events' ? `${record.valueType?.properties?.length || 0} 项` : (record.output?.type || '无') }}</template>
            <template v-else-if="column.key === 'level'">{{ EventLevel.find(item => item.value === record.expands?.level)?.label || record.expands?.level || '—' }}</template>
            <template v-else-if="column.key === 'valueType'">{{ record.valueType?.type || '待配置' }}</template>
            <template v-else-if="column.key === 'description'">{{ record.description || '—' }}</template>
            <template v-else-if="column.key === 'status'"><a-tag v-if="definitionInvalidRows.has(record)" color="error">请完善</a-tag><span v-else>已配置</span></template>
            <template v-else-if="column.key === 'other'">
              <OtherSetting
                v-model:value="record.expands"
                :type="['functions', 'events'].includes(type) ? 'object' : record.valueType?.type"
                :id="record.id"
                :name="record.name"
                :metadata-type="type"
                :is-product="record.expands?.isProduct"
                :target="target"
                :record="record"
                :disabled="definitionUpdateBlocked || (record.expands?.isProduct && !['int', 'long', 'float', 'double'].includes(record.valueType?.type))"
                @change="metadataChange"
              />
            </template>
            <template v-else-if="column.key === 'action'">
              <a-space>
                <a-button type="link" size="small" @click="detailData.data = record; detailData.visible = true">查看</a-button>
                <a-button type="link" size="small" :disabled="definitionUpdateBlocked || record.expands?.isProduct" @click="openDefinitionEditor(record)">编辑</a-button>
                <a-button type="link" size="small" :disabled="definitionAddBlocked || record.expands?.isProduct" @click="copyDefinition(record)">复制</a-button>
                <a-popconfirm title="确认删除此项？" @confirm="removeDefinition(record)"><a-button type="link" size="small" danger :disabled="definitionUpdateBlocked || record.expands?.isProduct">删除</a-button></a-popconfirm>
              </a-space>
            </template>
          </template>
        </a-table>
      </template>
      <template #id="{ record, index }">
        <EditTableFormItem :name="[index, 'id']" @change="metadataChange">
          <LibraryItemInput
            v-if="type === 'properties'"
            v-model:value="record.id"
            field="id"
            :library-id="activeLibraryId"
            :existing-codes="otherPropertyCodes(record)"
            :disabled="catalogEditingBlocked || record.expands?.isProduct"
            :get-popup-container="getPopupContainer"
            @select="selectLibraryItem(record, $event)"
            @change="metadataChange"
          />
          <a-input
            v-else
            v-model:value="record.id"
            :placeholder="$t('Base.Base.640395-9')"
            :disabled="record.expands?.isProduct"
          />
        </EditTableFormItem>
      </template>
      <template #name="{ record, index }">
        <EditTableFormItem :name="[index, 'name']" @change="metadataChange">
          <LibraryItemInput
            v-if="type === 'properties'"
            v-model:value="record.name"
            field="name"
            :library-id="activeLibraryId"
            :existing-codes="otherPropertyCodes(record)"
            :disabled="catalogEditingBlocked || record.expands?.isProduct"
            :get-popup-container="getPopupContainer"
            @select="selectLibraryItem(record, $event)"
            @change="metadataChange"
          />
          <a-input
            v-else
            v-model:value="record.name"
            :placeholder="$t('Base.Base.640395-10')"
            :disabled="record.expands?.isProduct"
          />
        </EditTableFormItem>
      </template>
      <template #sortsIndex="{ record, index }">
        <EditTableFormItem :name="[index, 'sortsIndex']" @change="metadataChange">
          <a-input-number
            v-model:value="record.sortsIndex"
            :min="0"
            style="width: 100%"
            :disabled="record.expands?.isProduct"
          />
        </EditTableFormItem>
      </template>
      <template #registerAddr="{ record, index }">
        <EditTableFormItem :name="[index, 'expands', 'registerAddr']" @change="metadataChange">
          <a-input v-model:value="record.expands.registerAddr" :disabled="record.expands?.isProduct" />
        </EditTableFormItem>
      </template>
      <template #registerNum="{ record, index }">
        <EditTableFormItem :name="[index, 'expands', 'registerNum']" @change="metadataChange">
          <a-input v-model:value="record.expands.registerNum" :disabled="record.expands?.isProduct" />
        </EditTableFormItem>
      </template>
      <template #parseMethod="{ record, index }">
        <EditTableFormItem :name="[index, 'expands', 'parseMethod']" @change="metadataChange">
          <a-select
            v-model:value="record.expands.parseMethod"
            :options="parseMethodOptions"
            allow-clear
            show-search
            style="width: 100%"
            :disabled="record.expands?.isProduct"
          />
        </EditTableFormItem>
      </template>
      <template #functionCode="{ record, index }">
        <EditTableFormItem :name="[index, 'expands', 'functionCode']" @change="metadataChange">
          <a-input v-model:value="record.expands.functionCode" :disabled="record.expands?.isProduct" />
        </EditTableFormItem>
      </template>
      <template #source="{ record, index }">
        <EditTableFormItem :name="[index, 'expands']" @change="metadataChange">
          <Source
            v-model:value="record.expands"
            :isProduct="record.expands?.isProduct"
            :target="target"
            :record="record"
            :disabled="record.expands?.isProduct"
          />
        </EditTableFormItem>
      </template>
      <template #operationType="{ record, index }">
        <EditTableFormItem :name="[index, 'expands', 'type']" @change="metadataChange">
          <a-select
            v-model:value="record.expands.type"
            mode="multiple"
            :options="operationTypeOptions"
            style="width: 100%"
            :disabled="record.expands?.isProduct"
          />
        </EditTableFormItem>
      </template>
      <template #scale="{ record, index }">
        <EditTableFormItem :name="[index, 'valueType', 'scale']" @change="metadataChange">
          <a-input-number
            v-model:value="record.valueType.scale"
            :min="0"
            :max="99"
            style="width: 100%"
            :disabled="record.expands?.isProduct"
          />
        </EditTableFormItem>
      </template>
      <template #unit="{ record, index }">
        <EditTableFormItem :name="[index, 'valueType', 'unit']" @change="metadataChange">
          <UnitSelect v-model:value="record.valueType.unit" :disabled="record.expands?.isProduct" />
        </EditTableFormItem>
      </template>
      <template #formula="{ record, index }">
        <EditTableFormItem :name="[index, 'expands', 'formula']" @change="metadataChange">
          <a-input v-model:value="record.expands.formula" :disabled="record.expands?.isProduct" />
        </EditTableFormItem>
      </template>
      <template #valueType="{ record, index }">
        <EditTableFormItem
          :name="[index, 'valueType']"
          @change="metadataChange"
        >
          <div
            style="display: flex; align-items: center"
            v-if="['properties', 'tags'].includes(type)"
          >
            <TypeSelect
              v-model:value="record.valueType"
              style="flex: 1 1 0; min-width: 0"
              :disabled="record.expands?.isProduct"
              @valueChange="onTypeChange(index)"
            />
            <IntegerParams
              v-if="type !== 'properties' && ['int', 'long'].includes(record.valueType.type)"
              v-model:value="record.valueType.unit"
              :disabled="record.expands?.isProduct"
            />
            <DoubleParams
              v-else-if="type !== 'properties' && ['float', 'double'].includes(record.valueType.type)"
              v-model:value="record.valueType"
              :disabled="record.expands?.isProduct"
            />
            <StringParams
              v-else-if="type !== 'properties' && ['string', 'password'].includes(record.valueType.type)"
              v-model:value="record.valueType"
              :disabled="record.expands?.isProduct"
            />
            <DateParams
              v-else-if="type !== 'properties' && record.valueType.type === 'date'"
              v-model:value="record.valueType.format"
              :disabled="record.expands?.isProduct"
            />
            <FileParams
              v-else-if="type !== 'properties' && record.valueType.type === 'file'"
              v-model:value="record.valueType"
              :disabled="record.expands?.isProduct"
            />
            <EnumParams
              v-else-if="type !== 'properties' && record.valueType.type === 'enum'"
              v-model:value="record.valueType.elements"
              :disabled="record.expands?.isProduct"
            />
            <BooleanParams
              v-else-if="type !== 'properties' && record.valueType.type === 'boolean'"
              v-model:falseText="record.valueType.falseText"
              v-model:falseValue="record.valueType.falseValue"
              v-model:trueText="record.valueType.trueText"
              v-model:trueValue="record.valueType.trueValue"
              :disabled="record.expands?.isProduct"
            />
            <ObjectParams
              v-else-if="type !== 'properties' && record.valueType.type === 'object'"
              v-model:value="record.valueType.properties"
              :disabled="record.expands?.isProduct"
              :level="2"
            />
            <ArrayParams
              v-else-if="type !== 'properties' && record.valueType.type === 'array'"
              v-model:value="record.valueType.elementType"
              :disabled="record.expands?.isProduct"
              :level="2"
            />
          </div>
          <div v-else-if="type === 'events'">
            <ObjectParams v-model:value="record.valueType.properties" :level="2">
              <a-button
                type="primary"
                :disabled="record.expands?.isProduct"
                :danger="!record.valueType.properties?.length && record.id"
              >
                <template #icon>
                  <AIcon
                    type="EditOutlined"
                    :class="{
                      'table-form-required-aicon':
                        !record.valueType.properties?.length,
                    }"
                  />
                </template>
                {{ $t("Base.Base.640395-11") }}
              </a-button>
            </ObjectParams>
          </div>
        </EditTableFormItem>
      </template>
      <template #expands="{ record, index }">
        <EditTableFormItem :name="[index, 'expands']" @change="metadataChange">
          <Source
            v-if="props.type === 'properties'"
            v-model:value="record.expands"
            :isProduct="record.expands?.isProduct"
            :target="target"
            :record="record"
            :disabled="record.expands?.isProduct"
          />
          <a-select
            v-else-if="props.type === 'events' && !_isFullscreen"
            v-model:value="record.expands.level"
            style="width: 100%"
            :options="EventLevel"
            :disabled="record.expands?.isProduct"
          />
          <a-select
            v-else-if="props.type === 'events' && _isFullscreen"
            v-model:value="record.expands.level"
            style="width: 100%"
            :options="EventLevel"
            :getPopupContainer="getPopupContainer"
            :disabled="record.expands?.isProduct"
          />
        </EditTableFormItem>
      </template>
      <template #other="{ record }">
        <div style='z-index: 6; position: relative'>
          <OtherSetting
            v-model:value="record.expands"
            :type="
              ['functions', 'events'].includes(props.type)
                ? 'object'
                : record.valueType?.type
            "
            :id="record.id"
            :name="record.name"
            :metadataType="props.type"
            :isProduct="record.expands?.isProduct"
            :target="props.target"
            :record="record"
            :disabled="
              record.expands?.isProduct &&
              !['int', 'long', 'float', 'double'].includes(
                record.valueType?.type,
              )
            "
            @change="metadataChange"
          />
        </div>
      </template>
      <template #async="{ record }">
        <BooleanSelect
          v-model:value="record.async"
          style="width: 100%"
          :trueLabel="$t('Base.Base.640395-12')"
          :falseLabel="$t('Base.Base.640395-13')"
          :true-value="true"
          :false-value="false"
          :disabled="record.expands?.isProduct"
          @change="metadataChange"
        />
      </template>
      <template #inputs="{ record, index }">
        <EditTableFormItem :name="[index, 'inputs']" @change="metadataChange">
          <ObjectParams v-model:value="record.inputs" :type="type" :level="2">
            <a-button type="primary" :disabled="record.expands?.isProduct">
              <template #icon>
                <AIcon
                  type="EditOutlined"
                  :class="{
                    'table-form-required-aicon': !record.inputs.length,
                  }"
                />
              </template>
              {{ $t("Base.Base.640395-11") }}
            </a-button>
          </ObjectParams>
        </EditTableFormItem>
      </template>
      <template #output="{ record, index }">
        <EditTableFormItem :name="[index, 'output']" @change="metadataChange">
          <div style="display: flex; align-items: center">
            <TypeSelect
              v-model:value="record.output"
              style="flex: 1 1 0; min-width: 0"
              :disabled="record.expands?.isProduct"
            />
            <IntegerParams
              v-if="['int', 'long'].includes(record.output.type)"
              v-model:value="record.output.unit"
              :disabled="record.expands?.isProduct"
            />
            <DoubleParams
              v-else-if="['float', 'double'].includes(record.output.type)"
              v-model:value="record.output"
              :disabled="record.expands?.isProduct"
            />
            <StringParams
              v-else-if="['string', 'password'].includes(record.output.type)"
              v-model:value="record.output.maxLength"
              :disabled="record.expands?.isProduct"
            />
            <DateParams
              v-else-if="record.output.type === 'date'"
              v-model:value="record.output.format"
              :disabled="record.expands?.isProduct"
            />
            <FileParams
              v-else-if="record.output.type === 'file'"
              v-model:value="record.output"
              :disabled="record.expands?.isProduct"
            />
            <EnumParams
              v-else-if="record.output.type === 'enum'"
              v-model:value="record.output.elements"
              :disabled="record.expands?.isProduct"
            />
            <BooleanParams
              v-else-if="record.output.type === 'boolean'"
              v-model:falseText="record.output.falseText"
              v-model:falseValue="record.output.falseValue"
              v-model:trueText="record.output.trueText"
              v-model:trueValue="record.output.trueValue"
              :disabled="record.expands?.isProduct"
            />
            <ObjectParams
              v-else-if="record.output.type === 'object'"
              v-model:value="record.output.properties"
              :disabled="record.expands?.isProduct"
              :level="2"
            />
            <ArrayParams
              v-else-if="record.output.type === 'array'"
              v-model:value="record.output.elementType"
              :disabled="record.expands?.isProduct"
              :level="2"
            />
          </div>
        </EditTableFormItem>
      </template>
      <template #description="{ record, index }">
        <EditTableFormItem :name="[index, 'description']">
          <a-input
            v-model:value="record.description"
            :placeholder="$t('Base.Base.640395-14')"
            :disabled="record.expands?.isProduct"
            @change="metadataChange"
          />
        </EditTableFormItem>
      </template>
      <template #properties="{ record, index }">
        <EditTableFormItem
          :name="[index, 'properties']"
          @change="metadataChange"
        >
          <ObjectParams
            v-model:value="record.valueType.properties"
            :disabled="record.expands?.isProduct"
            :level="1"
          />
        </EditTableFormItem>
      </template>
      <template #group="{ record }">
        <GroupSelect
          v-model:value="record.expands.group"
          :disabled="record.expands?.isProduct"
          @change="metadataChange"
        />
      </template>
    </EditTable>
    <div>
      {{ $t("Base.Base.640395-15") }}
      <span class="metadata-result-total">{{ effectiveDataLength }}</span>
      {{ $t("Base.Base.640395-16") }}
    </div>
    <PropertiesModal
      v-if="type === 'properties' && detailData.visible"
      :data="detailData.data"
      :type="target"
      :getPopupContainer="getPopupContainer"
      :unitOptions="unitOptions"
      @cancel="cancelDetailModal"
    />
    <FunctionModal
      v-else-if="type === 'functions' && detailData.visible"
      :data="detailData.data"
      :getPopupContainer="getPopupContainer"
      @cancel="cancelDetailModal"
    />
    <EventModal
      v-else-if="type === 'events' && detailData.visible"
      :data="detailData.data"
      :getPopupContainer="getPopupContainer"
      @cancel="cancelDetailModal"
    />
    <TagsModal
      v-else-if="type === 'tags' && detailData.visible"
      :data="detailData.data"
      :getPopupContainer="getPopupContainer"
      :unitOptions="unitOptions"
      @cancel="cancelDetailModal"
    />
    <LibraryPicker
      v-if="type === 'properties'"
      v-model:open="libraryPickerOpen"
      v-model:library-id="activeLibraryId"
      :existing-codes="propertyCodes"
      :disabled="catalogEditingBlocked"
      :get-container="getPopupContainer"
      @add="addLibraryProperties"
    />
    <PropertyEditor
      v-if="type === 'properties'"
      :open="propertyEditorOpen"
      :record="propertyEditorRecord"
      :library-id="activeLibraryId"
      :existing-codes="otherPropertyCodes(propertyEditorOriginal)"
      :group-options="propertyGroups"
      :target="target"
      @update:open="propertyEditorOpen = $event"
      @save="savePropertyEditor"
    />
    <DefinitionEditor
      v-if="type !== 'properties'"
      :open="definitionEditorOpen"
      :record="definitionEditorRecord"
      :type="type"
      :target="target"
      :existing-ids="otherDefinitionIds"
      @update:open="definitionEditorOpen = $event"
      @save="saveDefinitionEditor"
    />
    <a-modal v-if="type === 'properties'" v-model:open="propertyBatchOpen" title="批量设置采集项" :get-container="getPopupContainer" @ok="applyPropertyBatch">
      <p>已选 {{ propertySelectedKeys.length }} 项。仅修改勾选的字段。类型发生变化时将清除原类型专属配置和指标配置；类型不变时保留。枚举、数组等类型需逐项完善配置。</p>
      <a-form layout="vertical">
        <a-form-item>
          <template #label><a-checkbox v-model:checked="propertyBatch.changeType">设置数据类型</a-checkbox></template>
          <TypeSelect v-model:value="propertyBatch.valueType" :disabled="!propertyBatch.changeType" />
        </a-form-item>
        <a-form-item>
          <template #label><a-checkbox v-model:checked="propertyBatch.changeUnit">设置单位</a-checkbox></template>
          <UnitSelect v-model:value="propertyBatch.unit" :disabled="!propertyBatch.changeUnit" close-on-change />
          <div v-if="propertyBatch.changeUnit" class="property-batch-hint">留空将清除所选属性的单位。</div>
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts" name="MetadataBase">
import type {
  MetadataItem,
  MetadataType,
  ProductItem,
} from "../../../Product/typings";
import type { PropType } from "vue";
import type { DeviceInstance } from "../../../Instance/typings";
import { useRouter, onBeforeRouteUpdate } from "vue-router";
import { useMetadata, useOperateLimits, useGroup } from "./hooks";
import { useColumns, useSaveUnit, parseMethodOptions, operationTypeOptions } from "./columns";
import { getMetadataItemByType, limitsMap } from "./utils";
import { Source, OtherSetting } from "./components";
import { TABLE_WRAPPER } from "../../../../../components/Metadata/consts";
import {exportProductPropertyMetadata, saveProductVirtualProperty} from "../../../../../api/product";
import { saveDeviceVirtualProperty } from "../../../../../api/instance";
import { useInstanceStore } from "../../../../../store/instance";
import { useProductStore } from "../../../../../store/product";
import { asyncUpdateMetadata, updateMetadata } from "../metadata";
import { onlyMessage, getToken, EventEmitter } from "@jetlinks-web/utils";
import { omit } from "lodash-es";
import {
  PropertiesModal,
  FunctionModal,
  EventModal,
  TagsModal,
} from "./DetailModal";
import {Modal, notification} from "ant-design-vue";
import { watch } from "vue";
import { useSystemStore, useMenuStore, useAuthStore } from "@/store";
import { storeToRefs } from "pinia";
import {
  EditTable,
  TypeSelect,
  IntegerParams,
  StringParams,
  DateParams,
  FileParams,
  EnumParams,
  BooleanParams,
  ObjectParams,
  ArrayParams,
  DoubleParams,
  GroupSelect,
  EditTableFormItem,
  BooleanSelect,
  UnitSelect,
} from "../../../../../components/Metadata";
import { EventLevel } from "../../../data";
import { message } from "ant-design-vue";
import { Import } from "./components/Import";
import { useI18n } from "vue-i18n";
import LibraryPicker from '../../../../../components/PropertyLibrary/LibraryPicker.vue';
import LibraryItemInput from '../../../../../components/PropertyLibrary/LibraryItemInput.vue';
import PropertyEditor from './PropertyEditor.vue';
import DefinitionEditor from './DefinitionEditor.vue';
import { applyPropertyLibraryItem, hasPropertyDraftContent, uniquePropertyLibraryItems, propertyDisplayColumns } from '../../../../../components/PropertyLibrary/metadataCatalog';
import type { PropertyLibraryItem } from '../../../../../api/propertyLibrary';

const { t: $t } = useI18n();

const props = defineProps({
  target: {
    type: String as PropType<"device" | "product">,
    default: "product",
  },
  type: {
    type: String as PropType<MetadataType>,
    default: undefined,
  },
  permission: {
    type: [String, Array] as PropType<string | string[]>,
    default: undefined,
  },
});

const _target = inject<"device" | "product">("_metadataType", props.target);

const system = useSystemStore();
const { layout: basicLayout } = storeToRefs(system);
const router = useRouter();

const { unitOptions } = useSaveUnit();

const {
  data: metadata,
  noEdit,
  productNoEdit,
} = useMetadata(_target, props.type);
const { data: tagsMetadata } = useMetadata(_target, "tags");
const { hasOperate } = useOperateLimits(_target);

const permissionStore = useAuthStore();
const instanceStore = useInstanceStore();
const productStore = useProductStore();

const dataSource = ref<MetadataItem[]>(
  JSON.parse(JSON.stringify(metadata.value || "[]")) || [],
);
const tableRef = ref();
const loading = ref(false);
const exporting = ref(false);
const editStatus = ref(false); // 编辑表格的编辑状态
const selectedRowKeys = ref<string[]>([]);
const libraryPickerOpen = ref(false);
const activeLibraryId = ref('standard');
const showAllColumns = ref(false);
const propertyEditorOpen = ref(false);
const propertyPage = ref(1);
const propertyPageSize = ref(10);
const propertyKeyword = ref('');
const propertySelectedKeys = ref<string[]>([]);
const propertyBatchOpen = ref(false);
const propertyBatch = reactive<{ changeType: boolean; changeUnit: boolean; valueType: Record<string, any>; unit?: string }>({
  changeType: false, changeUnit: false, valueType: {}, unit: undefined,
});
const propertyRowKeys = new WeakMap<object, string>();
let nextPropertyRowKey = 0;
const propertyRowKey = (record: object): string => {
  let key = propertyRowKeys.get(record);
  if (!key) { key = `property-row-${++nextPropertyRowKey}`; propertyRowKeys.set(record, key); }
  return key;
};
const filterPropertyRows = (records: MetadataItem[]) => {
  const keyword = propertyKeyword.value.trim().toLocaleLowerCase();
  return records.filter(record => hasPropertyDraftContent(record) && (!keyword ||
    [record.name, record.id].some(value => String(value || '').toLocaleLowerCase().includes(keyword))));
};
const propertyNeedsConfiguration = (record: MetadataItem) => {
  const value: any = record.valueType || {};
  if (!record.id || !record.name || !value.type || !record.expands?.source ||
      (record.expands.source === 'device' && !record.expands.type?.length)) return true;
  return (value.type === 'enum' && !value.elements?.length) ||
    (value.type === 'array' && !value.elementType?.type) ||
    (value.type === 'object' && !value.properties?.length) ||
    (value.type === 'date' && !value.format) ||
    (value.type === 'file' && (!value.bodyType || (typeof value.bodyType === 'object' && !Object.keys(value.bodyType).length)));
};
const propertyInvalidIndices = ref(new Set<number>());
const propertyEditorRecord = ref<any>({});
const propertyEditorOriginal = ref<any>();
const definitionEditorOpen = ref(false);
const definitionEditorRecord = ref<any>({});
const definitionEditorOriginal = ref<any>();
const definitionKeyword = ref('');
const definitionPage = ref(1);
const definitionPageSize = ref(10);
const definitionInvalidRows = ref(new Set<MetadataItem>());
const definitionName = computed(() => ({ functions: '功能', events: '事件', tags: '标签' } as Record<string, string>)[props.type || ''] || '物模型');
const definitionAddBlocked = computed(() => hasOperate('add', props.type!) || !permissionStore.hasPermission(`${props.permission}:update`));
const definitionUpdateBlocked = computed(() => hasOperate('update', props.type!) || !permissionStore.hasPermission(`${props.permission}:update`));
const saveBlocked = computed(() => props.type === 'properties'
  ? hasOperate('add', props.type)
  : hasOperate('add', props.type!) && hasOperate('update', props.type!));
const hasDefinitionDraftContent = (record: any) => !!(
  record.id || record.name || record.description || record.inputs?.length ||
  record.output?.type || record.valueType?.properties?.length ||
  (props.type === 'tags' && record.valueType?.type)
);
const filterDefinitionRows = (records: MetadataItem[]) => {
  const keyword = definitionKeyword.value.trim().toLocaleLowerCase();
  return records.filter(record => hasDefinitionDraftContent(record) && (!keyword ||
    [record.name, record.id].some(value => String(value || '').toLocaleLowerCase().includes(keyword))));
};
const definitionSummaryColumns = computed(() => [
  { title: '序号', key: 'serial', width: 75 },
  { title: `${definitionName.value}名称`, key: 'name', width: 170 },
  { title: `${definitionName.value} ID`, key: 'id', width: 160 },
  ...(props.type === 'functions' ? [
    { title: '调用方式', key: 'async', width: 100 },
    { title: '输入参数', key: 'inputs', width: 100 },
    { title: '输出参数', key: 'output', width: 120 },
  ] : props.type === 'events' ? [
    { title: '事件级别', key: 'level', width: 120 },
    { title: '输出参数', key: 'output', width: 110 },
  ] : [{ title: '数据类型', key: 'valueType', width: 130 }]),
  { title: '说明', key: 'description', width: 180 },
  { title: '状态', key: 'status', width: 100 },
  { title: '其他配置', key: 'other', width: 175 },
  { title: '操作', key: 'action', width: 230 },
]);
const otherDefinitionIds = computed(() => dataSource.value
  .filter(item => item !== definitionEditorOriginal.value)
  .map(item => item.id).filter(Boolean) as string[]);
const onDefinitionPageChange = (pagination: { current?: number; pageSize?: number }) => {
  definitionPage.value = pagination.current || 1;
  definitionPageSize.value = pagination.pageSize || 10;
};
watch(definitionKeyword, () => { definitionPage.value = 1; }, { flush: 'sync' });
const propertySummaryColumns = [
  { title: '序号', key: 'serial', width: 75 },
  { title: '属性名称', key: 'name', width: 180 },
  { title: '属性 ID', key: 'id', width: 170 },
  { title: '数据类型', key: 'type', width: 150 },
  { title: '单位', key: 'unit', width: 120 },
  { title: '操作类型', key: 'operation', width: 160 },
  { title: '状态', key: 'status', width: 100 },
  { title: '其它配置（独立保存）', key: 'other', width: 175 },
  { title: '操作', key: 'action', width: 180 },
];
const propertyGroups = computed(() => tableRef.value?.getGroupOptions() || []);
const onPropertyPageChange = (pagination: { current?: number; pageSize?: number }) => {
  propertyPage.value = pagination.current || 1;
  propertyPageSize.value = pagination.pageSize || 10;
};
const onPropertyValidationError = (errors: any[][]) => {
  if (props.type !== 'properties') {
    definitionKeyword.value = '';
    const flat = errors.flat().sort((a, b) => a.__dataIndex - b.__dataIndex);
    definitionInvalidRows.value = new Set(flat.map(error => dataSource.value[error.__dataIndex]).filter(Boolean));
    const record = dataSource.value[flat[0]?.__dataIndex];
    const index = dataSource.value.filter(hasDefinitionDraftContent).indexOf(record);
    definitionPage.value = Math.floor(Math.max(0, index) / definitionPageSize.value) + 1;
    return;
  }
  // A validation error can belong to a row hidden by the current search.
  propertyKeyword.value = '';
  const flat = errors.flat();
  propertyInvalidIndices.value = new Set(flat.map(error => error.__dataIndex));
  const first = flat[0];
  if (!first) return;
  const record = dataSource.value[first.__dataIndex];
  if (!record) return;
  const groupRows = dataSource.value.filter(item => item.expands?.groupId === record.expands?.groupId && hasPropertyDraftContent(item));
  const index = groupRows.indexOf(record);
  propertyPage.value = Math.floor(Math.max(0, index) / propertyPageSize.value) + 1;
};
const catalogEditingBlocked = computed(() =>
  hasOperate('add', 'properties') || hasOperate('update', 'properties') ||
  !permissionStore.hasPermission(`${props.permission}:update`),
);
const propertyRowSelection = computed(() => ({
  selectedRowKeys: propertySelectedKeys.value,
  preserveSelectedRowKeys: true,
  onChange: (keys: (string | number)[]) => { propertySelectedKeys.value = keys.map(String); },
  getCheckboxProps: (record: MetadataItem) => ({ disabled: catalogEditingBlocked.value || !!record.expands?.isProduct }),
}));
watch(propertyKeyword, () => { propertyPage.value = 1; }, { flush: 'sync' });
watch(() => dataSource.value.map(propertyRowKey), keys => {
  const existing = new Set(keys);
  propertySelectedKeys.value = propertySelectedKeys.value.filter(key => existing.has(key));
});
watch(catalogEditingBlocked, blocked => {
  if (blocked) { propertySelectedKeys.value = []; propertyBatchOpen.value = false; }
});
const openPropertyBatch = () => {
  if (catalogEditingBlocked.value || !propertySelectedKeys.value.length) return;
  Object.assign(propertyBatch, { changeType: false, changeUnit: false, valueType: {}, unit: undefined });
  propertyBatchOpen.value = true;
};
const applyPropertyBatch = () => {
  if (catalogEditingBlocked.value) return;
  if (!propertyBatch.changeType && !propertyBatch.changeUnit) {
    onlyMessage('请选择需要批量设置的字段', 'warning');
    return;
  }
  if (propertyBatch.changeType && !propertyBatch.valueType?.type) {
    onlyMessage('请选择数据类型', 'warning');
    return;
  }
  const selected = new Set(propertySelectedKeys.value);
  const rows = dataSource.value.filter(record => selected.has(propertyRowKey(record)) && hasPropertyDraftContent(record) && !record.expands?.isProduct);
  if (!rows.length) { onlyMessage('没有可编辑的已选采集项', 'warning'); return; }
  rows.forEach(record => {
    const currentType = record.valueType || {};
    const typeChanged = propertyBatch.changeType && currentType.type !== propertyBatch.valueType.type;
    if (typeChanged) {
      // Preserve each row's unit, precision and protocol values. Follow the existing
      // type-change behavior for metrics and nested dynamic configuration.
      const commonType = omit(currentType, ['elements', 'elementType', 'properties', 'format', 'bodyType', 'fileType', 'trueText', 'trueValue', 'falseText', 'falseValue']);
      if (['string', 'password'].includes(currentType.type) && commonType.expands) {
        commonType.expands = omit(commonType.expands, ['maxLength']);
      }
      record.valueType = { ...commonType, ...propertyBatch.valueType };
      record.expands = omit(record.expands || {}, ['metrics', 'expands']);
    }
    if (propertyBatch.changeUnit) {
      record.valueType = { ...(record.valueType || {}), unit: propertyBatch.unit || undefined };
    }
  });
  propertyInvalidIndices.value = new Set();
  editStatus.value = true;
  propertySelectedKeys.value = [];
  propertyBatchOpen.value = false;
};
const propertyCodes = computed(() => dataSource.value.map(item => item.id).filter(Boolean) as string[]);
const otherPropertyCodes = (record: MetadataItem) => dataSource.value.filter(item => item !== record).map(item => item.id).filter(Boolean) as string[];

const _isFullscreen = ref(false);

const copyDetail = reactive({
  key: undefined,
  index: 0,
  groupName: undefined,
});

const searchData = reactive({
  len: 0,
  show: false,
});

const { initOptions } = useGroup();

const { columns } = useColumns(
  dataSource,
  props.type,
  _target,
  noEdit,
  productNoEdit,
);
const displayColumns = computed(() => props.type === 'properties' ? propertyDisplayColumns(columns.value, showAllColumns.value) : columns.value);

const selectLibraryItem = (record: MetadataItem, item: PropertyLibraryItem) => {
  try {
    if (applyPropertyLibraryItem(record, item, dataSource.value, catalogEditingBlocked.value)) editStatus.value = true;
  } catch (error) { onlyMessage(getSaveErrorMessage(error), 'warning'); }
};

const addLibraryProperties = (items: PropertyLibraryItem[]) => {
  if (catalogEditingBlocked.value) return;
  const { accepted, skipped } = uniquePropertyLibraryItems(items, dataSource.value);
  const groupId = tableRef.value?.getGroupActive();
  const group = dataSource.value.find(item => item.expands?.groupId === groupId)?.expands;
  const groupName = group?.groupName || propertyGroups.value.find(item => item.value === groupId)?.label;
  const rows = accepted.map(item => {
    const row = getMetadataItemByType('properties') as MetadataItem;
    row.id = item.code;
    row.name = item.name;
    row.expands = { ...row.expands, groupId, groupName };
    return row;
  });
  const blankIndex = dataSource.value.findIndex(item => !hasPropertyDraftContent(item) && item.expands?.groupId === groupId);
  dataSource.value.splice(blankIndex < 0 ? dataSource.value.length : blankIndex, 0, ...rows);
  if (rows.length) {
    editStatus.value = true;
    nextTick(() => tableRef.value?.scrollToById(rows[0].id));
  }
  if (skipped.length) onlyMessage(`已跳过 ${skipped.length} 个重复或不可用的采集项`, 'warning');
};

const addCustomProperty = () => {
  if (catalogEditingBlocked.value) return;
  const groupId = tableRef.value?.getGroupActive();
  const row = getMetadataItemByType('properties') as MetadataItem;
  const group = dataSource.value.find(item => item.expands?.groupId === groupId)?.expands;
  row.expands = { ...row.expands, groupId, groupName: group?.groupName || propertyGroups.value.find(item => item.value === groupId)?.label };
  propertyEditorOriginal.value = undefined;
  propertyEditorRecord.value = row;
  propertyEditorOpen.value = true;
};

const openPropertyEditor = (record: MetadataItem) => {
  if (catalogEditingBlocked.value || record.expands?.isProduct) return;
  propertyEditorOriginal.value = record;
  propertyEditorRecord.value = JSON.parse(JSON.stringify(record));
  propertyEditorOpen.value = true;
};
const savePropertyEditor = (record: MetadataItem) => {
  if (catalogEditingBlocked.value) return;
  const index = dataSource.value.indexOf(propertyEditorOriginal.value);
  if (index >= 0) dataSource.value.splice(index, 1, record);
  else dataSource.value.push(record);
  propertyInvalidIndices.value = new Set();
  editStatus.value = true;
  propertyEditorOpen.value = false;
};
const removeProperty = (record: MetadataItem) => {
  if (catalogEditingBlocked.value || record.expands?.isProduct) return;
  const index = dataSource.value.indexOf(record);
  if (index >= 0) { dataSource.value.splice(index, 1); editStatus.value = true; }
};
const copyProperty = (record: MetadataItem) => {
  if (catalogEditingBlocked.value || record.expands?.isProduct) return;
  const clone = JSON.parse(JSON.stringify(record));
  clone.id = '';
  delete clone.__key;
  propertyEditorOriginal.value = undefined;
  propertyEditorRecord.value = clone;
  propertyEditorOpen.value = true;
};

const addDefinition = () => {
  if (definitionAddBlocked.value) return;
  definitionEditorOriginal.value = undefined;
  definitionEditorRecord.value = getMetadataItemByType(props.type!);
  definitionEditorOpen.value = true;
};
const openDefinitionEditor = (record: MetadataItem) => {
  if (definitionUpdateBlocked.value || record.expands?.isProduct) return;
  definitionEditorOriginal.value = record;
  definitionEditorRecord.value = JSON.parse(JSON.stringify(record));
  definitionEditorOpen.value = true;
};
const copyDefinition = (record: MetadataItem) => {
  if (definitionAddBlocked.value || record.expands?.isProduct) return;
  const clone = JSON.parse(JSON.stringify(record));
  clone.id = '';
  delete clone.__key;
  definitionEditorOriginal.value = undefined;
  definitionEditorRecord.value = clone;
  definitionEditorOpen.value = true;
};
const saveDefinitionEditor = (record: MetadataItem) => {
  if ((definitionEditorOriginal.value ? definitionUpdateBlocked.value : definitionAddBlocked.value) || definitionEditorOriginal.value?.expands?.isProduct) return;
  const index = dataSource.value.indexOf(definitionEditorOriginal.value);
  if (index >= 0) dataSource.value.splice(index, 1, record);
  else dataSource.value.push(record);
  if (definitionEditorOriginal.value) definitionInvalidRows.value.delete(definitionEditorOriginal.value);
  editStatus.value = true;
  definitionEditorOpen.value = false;
};
const removeDefinition = (record: MetadataItem) => {
  if (definitionUpdateBlocked.value || record.expands?.isProduct) return;
  const index = dataSource.value.indexOf(record);
  if (index >= 0) {
    dataSource.value.splice(index, 1);
    definitionInvalidRows.value.delete(record);
    editStatus.value = true;
    const count = filterDefinitionRows(dataSource.value).length;
    definitionPage.value = Math.min(definitionPage.value, Math.max(1, Math.ceil(count / definitionPageSize.value)));
  }
};

const detailData = reactive({
  data: {},
  visible: false,
});

const heavyLoad = ref<Boolean>(false);

const effectiveDataLength = computed(() => {
  return dataSource.value.filter((item) => item.id).length;
});

const getPopupContainer = () => {
  if (_isFullscreen.value) {
    return tableRef.value.getTableWrapperRef() || document.body;
  }
  return document.body;
};

// The batch dialog is outside EditTable, so its selectors need the same
// popup container as the dialog (including when the table is fullscreen).
provide(TABLE_WRAPPER, computed(() => getPopupContainer()));
provide("_tagsDataSource", tagsMetadata);
provide("metadataSource", dataSource);

const cancelDetailModal = () => {
  detailData.data = {};
  detailData.visible = false;
};

const operateLimits = (action: "add" | "updata", types: MetadataType) => {
  return (
    _target === "device" &&
    (instanceStore.detail.features || []).find(
      (item: { id: string; name: string }) =>
        item.id === limitsMap.get(`${types}-${action}`),
    )
  );
};

// const handleSearch = (searchValue: string) => {
//   const keys: string[] = []
//   if (searchValue) {
//     dataSource.value.forEach(item => {
//       if (item.name && item.name.includes(searchValue)) {
//         keys.push(item.id)
//       }
//     })
//   }
//
//   if (keys.length) {
//     tableRef.value.scrollToById(keys[0])
//   }
//   selectedRowKeys.value = keys
//
//   searchData.len = keys.length
//   searchData.show = true
// };

const scrollDown = (len: number = 5) => {
  if (!hasOperate("add", props.type)) {
    dataSource.value.push(
      ...new Array(len).fill(1).map(() => getMetadataItemByType(props.type!)),
    );
  }
};

const rightMenuClick = (
  type: string,
  record: Record<string, any>,
  copyRecord: Record<string, any>,
) => {
  const _index = record.__dataIndex;
  switch (type) {
    case "add":
      dataSource.value.splice(
        _index + 1,
        0,
        getMetadataItemByType(props.type!),
      );
      editStatus.value = true;
      nextTick(() => {
        if (copyDetail.key) {
          const copyItem = dataSource.value.find(
            (item) => item.__key === copyDetail.key,
          );
          copyDetail.index = copyItem!.__serial;
          copyDetail.groupName = copyItem!.expands.groupName;
        }
      });
      break;
    case "paste":
      const cloneRecord = JSON.parse(JSON.stringify(copyRecord));
      cloneRecord.id = `copy_${cloneRecord.id}`;
      if (props.type === "properties") {
        // 获取当前分组id和name
        const expandsItem = dataSource.value[_index + 1].expands;
        cloneRecord.expands.groupName = expandsItem.groupName;
        cloneRecord.expands.groupId = expandsItem.groupId;
      }

      if (record.id) {
        dataSource.value.splice(_index + 1, 0, cloneRecord);
        // Modal.confirm({
        //   title: '当前行存在数据',
        //   onOk() {
        //     dataSource.value.splice(_index, 1, cloneRecord)
        //   },
        //   onCancel() {
        //     console.log('Cancel');
        //   },
        // })
      } else {
        dataSource.value.splice(_index, 1, cloneRecord);
      }

      editStatus.value = true;
      break;
    case "copy":
      copyDetail.index = record.__serial;
      copyDetail.key = record.__key;
      copyDetail.groupName =
        dataSource.value[record.__dataIndex].expands.groupName;
      selectedRowKeys.value = [record.id];
      break;
    case "detail":
      detailData.data = record;
      detailData.visible = true;
      break;
    case "delete":
      // Modal.confirm({
      //   title: `确认删除【${record.id}】？`,
      //   onOk() {
      //     dataSource.value.splice(_index, 1)
      //   },
      //   onCancel() {
      //     console.log('Cancel');
      //   },
      // })
      if (copyDetail.key === record.__key) {
        copyDetail.key = undefined;
        copyDetail.groupName = undefined;
        copyDetail.index = 0;
      }
      dataSource.value.splice(_index, 1);
      editStatus.value = true;
      break;
  }
};

const getSaveErrorMessage = (error: any): string => {
  if (Array.isArray(error)) {
    return getSaveErrorMessage(error.find(Boolean));
  }

  if (typeof error === "string" && error.trim()) {
    return error;
  }

  if (error?.message) {
    return String(error.message);
  }

  return "物模型保存失败，请检查填写内容后重试";
};

const getExportErrorMessage = async (error: any): Promise<string> => {
  let data = error?.response?.data ?? error;
  if (data instanceof Blob) {
    try {
      const text = await data.text();
      try { data = JSON.parse(text); } catch { data = data.type.startsWith('text/') ? text : undefined; }
    } catch {
      data = undefined;
    }
  }
  if (typeof data === 'string' && data.trim()) return data.trim().slice(0, 200);
  return data?.message || error?.message || '导出失败，请稍后重试';
};

const exportProperties = async () => {
  const productId = productStore.current?.id;
  if (!productId) {
    onlyMessage('无法获取产品 ID，导出失败', 'error');
    return;
  }
  if (editStatus.value) onlyMessage('存在未保存的修改，导出文件仅包含已保存的属性', 'warning');
  exporting.value = true;
  try {
    const data: any = await exportProductPropertyMetadata(productId);
    if (!(data instanceof Blob)) throw data;
    const signature = new Uint8Array(await data.slice(0, 4).arrayBuffer());
    if (signature.length !== 4 || signature[0] !== 0x50 || signature[1] !== 0x4b || signature[2] !== 0x03 || signature[3] !== 0x04) {
      throw data;
    }
    const url = URL.createObjectURL(data);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = '产品物模型.xlsx';
    anchor.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  } catch (error) {
    onlyMessage(await getExportErrorMessage(error), 'error');
  } finally {
    exporting.value = false;
  }
};

const handleSaveClick = async (next?: Function) => {
  try {
    const resp = await tableRef.value?.validate();

    if (!resp) {
      return;
    }

    // Validation runs asynchronously; restore the displayed order before saving.
    const order = new Map(dataSource.value.map((item, index) => [item.id, index]));
    const ordered = props.type === 'properties' ? resp : [...resp].sort((a: any, b: any) =>
      (order.get(a.id) ?? Number.MAX_SAFE_INTEGER) - (order.get(b.id) ?? Number.MAX_SAFE_INTEGER));
    const virtual: any[] = [];
    const arr = ordered.map((item: any) => {
      if (item.expands?.virtualRule) {
        const triggerProperties = item.expands.virtualRule.triggerProperties;
        const rule = omit(item.expands.virtualRule, ["triggerProperties"]);
        virtual.push({
          triggerProperties,
          rule,
          type: rule.type,
          propertyId: item.id,
        });
      }
      return {
        ...item,
        expands: {
          ...omit(item.expands, ["virtualRule"]),
        },
      };
      // return item
    });
    loading.value = true;

    // 保存规则
    if (virtual.length) {
      let res = undefined;
      if (_target === "device") {
        res = await saveDeviceVirtualProperty(
          instanceStore.current.productId,
          instanceStore.current.id,
          virtual,
        );
      } else {
        res = await saveProductVirtualProperty(
          productStore.current.id,
          virtual,
        );
      }
    }
    // 保存属性
    const updateStore = (metadata: string) => {
      if (_target === "device") {
        const detail = instanceStore.current;
        detail.metadata = metadata;
        instanceStore.setCurrent(detail);
      } else {
        const detail = productStore.current || ({} as ProductItem);
        detail.metadata = metadata;
        productStore.setCurrent(detail);
      }
    };

    const _detail: ProductItem | DeviceInstance =
      _target === "device" ? instanceStore.detail : productStore.current;
    // Keep the store on the persisted model until the save request succeeds.
    const _data = updateMetadata(props.type!, arr, { ..._detail });

    const result = await asyncUpdateMetadata(_target, _data);
    if (!result?.success) {
      throw new Error(result?.message || "物模型保存失败，请稍后重试");
    }
    updateStore(_data.metadata);

    // dataSource.value = resp
    // tableRef.value.cleanEditStatus()
    editStatus.value = false;
    if (props.type !== 'properties') definitionInvalidRows.value = new Set();
    message.config({
      getContainer() {
        return getPopupContainer();
      },
    });
    onlyMessage($t("Base.Base.640395-17"));
    next?.();
  } catch (error) {
    if (props.type === 'properties' && Array.isArray(error)) showAllColumns.value = true;
    const orderedErrors = props.type !== 'properties' && Array.isArray(error)
      ? [...error].sort((a: any[], b: any[]) => (a[0]?.__dataIndex ?? 0) - (b[0]?.__dataIndex ?? 0))
      : error;
    onlyMessage(getSaveErrorMessage(orderedErrors), "error");
  } finally {
    loading.value = false;
  }
};

const metadataChange = () => {
  editStatus.value = true;
};

const jumpProduct = () => {
  useMenuStore().jumpPage("device/Product/Detail", {
    params: { id: instanceStore.detail.productId, tab: "Device" },
  });
};

const parentTabsChange = (next?: Function) => {
  if (
    editStatus.value &&
    permissionStore.hasPermission(`${props.permission}:update`) &&
    getToken()
  ) {
    const modal = Modal.confirm({
      content: $t("Base.Base.640395-18"),
      okText: $t("Base.Base.640395-6"),
      cancelText: $t("Base.Base.640395-19"),
      zIndex: 1400,
      closable: true,
      onOk: () => {
        handleSaveClick(next as Function);
      },
      onCancel: (e: any) => {
        if (!e.triggerCancel) {
          // 取消按钮
          modal.destroy();
          (next as Function)?.();
        } else {
          // 右上角取消按钮
          const paths = router.currentRoute.value.matched;
          // basicLayout.value.selectedKeys = paths.map(item => item.path)
          basicLayout.value.openKeys = paths.map((item) => item.path);
        }
      },
    });
  } else {
    (next as Function)?.();
  }
};

const fullToggle = (type: boolean, cb: Function) => {
  cb();
  _isFullscreen.value = !type;
  notification.destroy()
  notification.config({
    getContainer() {
      return getPopupContainer();
    },
  });
};

const groupEdit = (record: { value: string; label: string, oldValue: string }) => {
  dataSource.value.forEach((item) => {
    if (item.expands?.groupId === record.oldValue) {
      item.expands.groupName = record.label;
      item.expands.groupId = record.value;
    }
  });
};

const groupDelete = (id: string) => {
  dataSource.value = dataSource.value.filter(
    (item) => item.expands?.groupId !== id || item.expands?.isProduct,
  );
};

const importMetadata = (_metadata: any[]) => {
  dataSource.value = _metadata;
};

EventEmitter.subscribe(["MetadataTabs"], parentTabsChange);

onUnmounted(() => {
  message.config({
    getContainer() {
      return document.body;
    },
  });
  notification.destroy()
  notification.config({
    getContainer() {
      return document.body;
    },
  });
  EventEmitter.unSubscribe(["MetadataTabs"], parentTabsChange);
});

watch(
  () => metadata.value,
  () => {
    dataSource.value = JSON.parse(JSON.stringify(metadata.value || "[]")).map(
      (item) => {
        if (!item.expands) {
          item["expands"] = {
            group: undefined,
          };
        }
        if (props.type === "functions" && !item.output) {
          item["output"] = {};
        }
        return item;
      },
    );
    initOptions(dataSource.value);
  },
  { immediate: true },
);

onBeforeRouteUpdate((to, from, next) => {
  // 设备管理内路由跳转
  parentTabsChange(next as Function);
});

onBeforeRouteLeave((to, from, next) => {
  // 设备管理外路由跳转
  parentTabsChange(next as Function);
});

const onTypeChange = (index) => {
  // 清除其他配置和指标
  dataSource.value[index] = {
    ...dataSource.value[index],
    expands: {
      ...omit(dataSource.value[index]?.expands, ["metrics", "expands"]),
    },
  };
};
</script>

<style scoped lang="less">
.property-batch-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 0 0 12px;
}
.property-batch-hint { color: var(--app-text-secondary); font-size: 12px; }
.extra-header {
  display: flex;
  justify-content: space-between;
  padding-bottom: 16px;
  position: relative;
}

.extra-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.extra-copy-tip {
  position: absolute;
  display: flex;
  align-items: center;
  width: 300px;
  justify-content: space-between;
  padding: 8px 24px;
  background-color: var(--app-surface);
  box-shadow:
    0 6px 16px 0 rgba(0, 0, 0, 0.08),
    0 3px 6px -4px rgba(0, 0, 0, 0.12),
    0px 9px 28px 8px rgba(0, 0, 0, 0.05);
  font-size: 14px;
  transform: translateX(-150px);

  .extra-copy-tip-icon {
    font-size: 12px;
    color: var(--app-text-secondary);
  }

  .extra-copy-tip-context {
    display: flex;
    gap: 4px;
  }
}

.noEdit-tip {
  position: absolute;
  display: flex;
  flex-direction: column;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  font-size: 22px;
  color: var(--app-text);
  justify-content: center;
  align-items: center;
  pointer-events: none;
  z-index: 5;
}

.metadata-base {
  :deep(.ant-message) {
    z-index: 1073;
  }
}

.metadata-base-inline {
  :deep(.metadata-edit-table-header) {
    color: @app-text-table-header;
    background: @app-elevated;
    border-bottom: 1px solid @app-border;
    border-radius: 8px 8px 0 0;
  }

  :deep(.metadata-edit-table-body) {
    color: @app-text-secondary;
    background: @app-surface;
  }

  :deep(.metadata-edit-table-row:hover),
  :deep(.metadata-edit-table-row.metadata-edit-table-row-selected) {
    background: rgba(47, 128, 255, .08);
  }
}

.metadata-result-total {
  color: @primary-color;
}
</style>

<template>
  <div class="device-relationship">
    <div class="sections-container">
      <!-- 正向关系部分 -->
      <div class="section-card">
        <div class="section-header">
          <div class="header-left">
            <div class="section-title">
              <TitleComponent data="正向关系">
                <template #extra>
                  <a-tooltip title="管理设备与其他业务的关联关系，关系来源于关系配置">
                    <AIcon type="QuestionCircleOutlined" class="help-icon"/>
                  </a-tooltip>
                </template>
              </TitleComponent>
            </div>
          </div>
          <j-permission-button
            hasPermission="device/Instance:update"
            type="text"
            @click="handleEditRelationship"
            class="edit-icon"
          >
            <AIcon type="FormOutlined"/>
          </j-permission-button>
        </div>
        <div class="section-content">
          <div class="relation-count"><div>已配置关系</div> <div class="count-num">{{ relationCount }}</div></div>
          <div class="relation-list" v-if='relationshipData.length > 0'>
            <div
              v-for="item in relationshipData"
              :key="item.objectId"
              :class="{'relation-item': true, 'relation-item-unrelated': !item.related}"
            >
              <div class="relation-label">{{ item.relationName }}</div>
              <div class="relation-value">{{ item.related?.map(it => it.name).join('、') }}</div>
            </div>
          </div>
          <div v-else style='margin-top: 50px'>
            <j-empty />
          </div>
        </div>
      </div>

      <!-- 组织部分 -->
      <div class="section-card">
        <div class="section-header">
          <div class="header-left">
            <div class="section-title">
              <TitleComponent data="组织"/>
            </div>
          </div>
          <j-permission-button
            hasPermission="device/Instance:update"
            type="text"
            @click="handleEditOrganization"
            class="edit-icon"
          >
            <AIcon type="FormOutlined"/>
          </j-permission-button>
        </div>
        <div class="section-content">
          <TreeList
            :data="organizationTreeData"
            :loading="loadingOrganization"
            :bindOrgList="bindOrgList"
            @view-change="handleOrganizationViewChange"
          >
            <template #extra-controls>
              <AIcon type="UnorderedListOutlined" class="list-icon"/>
            </template>
            <template #header-extra>
              <div class="organization-count">已加入组织节点 <span class="count-num">{{ bindOrgList?.length }}</span></div>
            </template>
            <template #actions="{ item, level }">
              <a-tag
                v-for="permission in bindOrgAuthList?.find(it => it.targetId === item.id || item.key)?.grantedPermissions || []"
                :key="permission"
              >
                {{ permissionMap[permission] }}
              </a-tag>
            </template>
            <template #empty>
              <div class="empty-text">暂无组织数据</div>
            </template>
          </TreeList>
        </div>
      </div>

      <!-- 分组部分 -->
      <!-- <div class="section-card">
        <div class="section-header">
          <div class="header-left">
            <div class="section-title">
              <TitleComponent data="分组">
                <template #extra>
                  <AIcon type="QuestionCircleOutlined" class="help-icon"/>
                </template>
              </TitleComponent>
            </div>
          </div>
          <AIcon type="FormOutlined" class="edit-icon" @click="handleEditGroup"/>
        </div>
        <div class="section-content">
          <TreeList
            :data="groupTreeData"
            :loading="loading.groups"
            @view-change="handleGroupViewChange"
          >
            <template #header-extra>
              <div class="group-count">已加入组织节点 <span class="count-num">{{ groupCount }}</span></div>
            </template>
            <template #actions="{ item, level }">
              <a-button type="text" size="small" class="action-view">
                查看
              </a-button>
            </template>
            <template #empty>
              <div class="empty-text">暂无分组数据</div>
            </template>
          </TreeList>
        </div>
      </div> -->
    </div>
  </div>

  <RelationshipEditModal
      v-if="showRelationshipModal"
      @close="showRelationshipModal = false"
      @save="handleSaveRelationship"
  />

  <OrganizationEditModal
      v-if="showOrganizationModal"
      :bindOrgList="bindOrgListWithAuth"
      @close="showOrganizationModal = false"
      @save="handleSaveOrganization"
  />

  <GroupEditModal
      v-if="showGroupModal"
      @close="showGroupModal = false"
      @save="handleSaveGroup"
  />

</template>

<script lang="ts" setup>
import RelationshipEditModal from './components/RelationshipEditModal.vue'
import OrganizationEditModal from './components/OrganizationEditModal.vue'
import GroupEditModal from './components/GroupEditModal.vue'
import TreeList from './components/TreeList.vue'
import {useInstanceStore} from "@device-manager-ui/store/instance";
import { getOrgList, getBindOrgAuthList } from '@device-manager-ui/api/instance'
import { useRequest } from '@jetlinks-web/hooks'
import { moduleRegistry } from '@/utils/module-registry'

const getTreeData_api = moduleRegistry.getResourceItem('authentication-manager-ui', 'apis', 'getTreeData_api')
const instanceStore = useInstanceStore();
const treeView = ref('tree')
const groupTreeView = ref(true)
const showRelationshipModal = ref(false)
const showOrganizationModal = ref(false)
const showGroupModal = ref(false)

// 数据状态
const loading = reactive({
  relations: false,
  organizations: false,
  groups: false
})

const permissionMap = {
  'read': '查看',
  'save': '编辑',
  'delete': '删除',
  'share': '共享',
}

const organizationData = ref<any[]>([])
const groupData = ref<any[]>([])

const { data: organizationList, loading: loadingOrganization, reload: reloadOrganizationList } = useRequest(getTreeData_api, {
  defaultParams: {
    paging: false,
    sorts: [
      {
        name: "sortIndex",
        order: "asc"
      },
      {
        name: "name",
        order: "asc"
      }
    ]
  }
})

const { data: bindOrgAuthList, reload: reloadBindOrgAuthList } = useRequest(getBindOrgAuthList, {
  defaultParams: ['device', instanceStore.current?.id || '', 'org']
})

const { data: bindOrgList, reload: reloadBindOrgList } = useRequest(getOrgList, {
  defaultParams: {
    terms: [
      {
        column: 'id',
        termType: 'assets-dim',
        value: {
          assetType: 'device',
          assetIds: [
            instanceStore.current?.id
          ],
          dimensionType: 'org'
        }
      }
    ]
  }
})

const bindOrgListWithAuth = computed(() => {
  return bindOrgList.value?.map(it => ({
    ...it,
    actions: bindOrgAuthList.value?.find(auth => auth.targetId === it.id)?.grantedPermissions || []
  })) || []
})
// 树形数据结构
const organizationTreeData = computed(() => {
  return organizationList.value || []
})

// const groupTreeData = computed(() => [
//   {
//     id: '1',
//     name: '智慧工厂',
//     children: [
//       {
//         id: '2',
//         name: '重庆/月亮湾项目'
//       },
//       {
//         id: '3',
//         name: '重庆月亮湾项目'
//       }
//     ]
//   },
//   {
//     id: '4',
//     name: '物联网产品基地/A栋/5楼'
//   }
// ])

// 统计数据
const relationshipData = computed(() => instanceStore.current?.relations || [])
const relationCount = computed(() => {
  return relationshipData.value?.reduce((acc, cur) => {
    if(cur.related) {
      acc++
    }
    return acc
  }, 0)
})

// 视图切换事件
const handleOrganizationViewChange = (view: 'tree' | 'flat') => {
  treeView.value = view
}

const handleGroupViewChange = (view: 'tree' | 'flat') => {
  groupTreeView.value = view === 'tree'
}

// 默认操作按钮
const getDefaultActions = () => [
  { type: 'view', label: '查看' },
  { type: 'edit', label: '编辑' },
  { type: 'delete', label: '删除' },
  { type: 'share', label: '共享' }
]

const handleEditRelationship = () => {
  showRelationshipModal.value = true
}

const handleEditOrganization = () => {
  showOrganizationModal.value = true
}

const handleEditGroup = () => {
  showGroupModal.value = true
}

const handleSaveRelationship = () => {
  showRelationshipModal.value = false
  instanceStore.refresh(instanceStore.current?.id || '')
}

const handleSaveOrganization = () => {
  showOrganizationModal.value = false
  reloadOrganizationList()
  reloadBindOrgList()
  reloadBindOrgAuthList()
}

const handleSaveGroup = () => {
  showGroupModal.value = false
}

// 数据获取方法
const fetchDeviceInfo = async () => {
  // try {
  //   loading.relations = true
  //   const response = await detail(props.deviceId)
  //   if (response.success) {
  //     deviceInfo.value = response.result
  //     // 处理设备关系数据
  //     parseRelationshipData(response.result)
  //   }
  // } catch (error) {
  //   console.error('获取设备信息失败:', error)
  // } finally {
  //   loading.relations = false
  // }
}

const fetchOrganizationData = async () => {
  // try {
  //   loading.organizations = true
  //   // 获取设备组织绑定信息
  //   const response = await getBindingsPermission('device', [props.deviceId])
  //   if (response.success && response.result) {
  //     organizationData.value = response.result.map((item: any) => ({
  //       id: item.targetId,
  //       name: item.targetName || item.target,
  //       type: item.targetType,
  //       actions: parseActions(item.permissions || [])
  //     }))
  //   }
  // } catch (error) {
  //   console.error('获取组织数据失败:', error)
  // } finally {
  //   loading.organizations = false
  // }
}

const fetchGroupData = async () => {
  try {
    loading.groups = true
    // 模拟获取分组数据，实际应根据API调整
    // 这里可以调用具体的分组API
    groupData.value = [
      { id: '1', name: '智慧工厂-重庆/月亮湾项目' },
      { id: '2', name: '智慧工厂-重庆月亮湾项目' },
      { id: '3', name: '物联网产品基地/A栋/5楼' }
    ]
  } catch (error) {
    console.error('获取分组数据失败:', error)
  } finally {
    loading.groups = false
  }
}

// 初始化数据
onMounted(() => {
  if (instanceStore.current?.id) {
    fetchDeviceInfo()
    fetchOrganizationData()
    fetchGroupData()
  }
})
</script>

<style lang="less" scoped>
.device-relationship {
  background: var(--app-surface);
  height: 100%;

  .sections-container {
    display: flex;
    gap: 16px;
    height: 100%;

    .section-card {
      flex: 1;
      min-height: 0;
      display: flex;
      flex-direction: column;
      border-radius: 6px;
      box-shadow: 0 2px 4px rgba(0, 0, 0, 0.02);

      .section-header {
        display: flex;
        justify-content: space-between;
        align-items: center;
        padding: 12px 0;

        .header-left {
          display: flex;
          align-items: center;
          gap: 6px;

          .section-title {
            color: var(--app-primary);

            :deep(.title) {
              margin-bottom: 0;
            }
          }

          .help-icon {
            color: var(--app-text-secondary);
            margin-left: 8px;
          }
        }

        .edit-icon {
          color: var(--app-text-secondary);
          font-size: 14px;
          cursor: pointer;

          &:hover {
            color: var(--app-primary);
          }
        }
      }

      .section-content {
        padding: 12px 16px;
        flex: 1;
        min-height: 0;
        overflow-y: auto;
        background-color: var(--app-elevated);
        display: flex;
        flex-direction: column;
      }
    }

    // 正向关系样式
    .section-card:first-child {
      .relation-count {
        margin-bottom: 12px;
        color: var(--app-text);
        display: flex;
        align-items: center;
        justify-content: flex-end;

        .count-num {
          background-color: var(--app-elevated);
          color: var(--app-text);
          margin-left: 10px;
          padding: 4px;
        }
      }

      .relation-list {
        flex: 1;
        min-height: 0;
        overflow-y: auto;
        .relation-item {
          display: flex;
          justify-content: space-between;
          align-items: center;
          padding: 10px;
          background-color: var(--app-info-bg);
          border: 1px solid var(--app-border);
          &.relation-item-unrelated {
            background-color: var(--app-elevated);
            border-color: var(--app-border);
          }
          &:not(:last-child) {
            margin-bottom: 10px;
          }

          .relation-label {
            color: var(--app-text-secondary);
            max-width: 50%;
          }

          .relation-value {
            color: var(--app-text);
            text-align: right;
            flex: 1;
          }
        }
      }
    }

    // 组织部分样式
    .section-card:nth-child(2) {
      .view-controls {
        display: flex;
        align-items: center;
        gap: 8px;
        margin-bottom: 8px;

        .ant-btn-sm {
          height: 24px;
          padding: 0 8px;
          border-radius: 2px;
        }

        .list-icon {
          color: var(--app-text-secondary);
          font-size: 14px;
          margin-left: 4px;
        }
      }

      .organization-count {
        margin-bottom: 12px;
        color: var(--app-text);
        display: flex;
        align-items: center;
        justify-content: flex-end;

        .count-num {
          background-color: var(--app-elevated);
          color: var(--app-text);
          margin-left: 10px;
          padding: 4px;
        }
      }

      .organization-content {
        .org-item {
          display: flex;
          align-items: center;
          justify-content: space-between;
          padding: 4px 0;
          line-height: 20px;

          &.sub-item {
            padding-left: 16px;
          }

          &.sub-sub-item {
            padding-left: 32px;
            background: var(--app-info-bg);
            border-left: 3px solid var(--app-primary);
            margin: 2px 0;
            padding-right: 8px;
            border-radius: 0 4px 4px 0;
          }

          .expand-icon {
            width: 12px;
            height: 12px;
            margin-right: 4px;
            color: var(--app-text-secondary);
            font-size: 10px;
            transform: translateY(0);

            &.expanded {
              color: var(--app-primary);
            }
          }

          .org-name {
            flex: 1;
            color: var(--app-text);
          }

          .org-actions {
            display: flex;
            gap: 2px;

            .ant-btn {
              height: 20px;
              padding: 0 4px;
              border: none;
              border-radius: 2px;

              &.action-view {
                color: var(--app-primary);
                background: var(--app-info-bg);
              }

              &.action-edit {
                color: var(--app-text-secondary);
                background: var(--app-success-bg);
              }

              &.action-delete {
                color: var(--app-text-secondary);
                background: var(--app-error-bg);
              }

              &.action-share {
                color: var(--app-text);
                background: var(--app-purple-bg);
              }

              &:hover {
                opacity: 0.8;
              }
            }
          }
        }
      }
    }

    // 分组部分样式
    .section-card:last-child {
      .view-controls {
        display: flex;
        align-items: center;
        gap: 8px;
        margin-bottom: 8px;

        .ant-btn-sm {
          height: 24px;
          padding: 0 8px;
          border-radius: 2px;
        }
      }

      .group-count {
        margin-bottom: 8px;
        color: var(--app-text-secondary);

        .count-num {
          color: var(--app-primary);
          font-weight: 500;
        }
      }

      .group-list {
        .group-item {
          padding: 6px 8px;
          margin-bottom: 4px;
          background: var(--app-elevated);
          border-radius: 4px;
          color: var(--app-text);
        }
      }
    }

    // 通用样式
    .list-icon {
      color: var(--app-text-secondary);
      font-size: 14px;
      margin-left: 4px;
    }

    .organization-count,
    .group-count {
      color: var(--app-text);
      display: flex;
      align-items: center;
      justify-content: flex-end;

      .count-num {
        background-color: var(--app-elevated);
        color: var(--app-text);
        margin-left: 10px;
        padding: 4px;
        border-radius: 2px;
      }
    }

    .empty-text {
      color: var(--app-text-secondary);
      font-size: 14px;
      text-align: center;
      padding: 20px;
    }

    // 操作按钮样式
    :deep(.ant-btn) {
      &.action-view {
        color: var(--app-primary);
        background: var(--app-info-bg);
        border: none;
        height: 20px;
        padding: 0 4px;
        border-radius: 2px;
      }

      &.action-edit {
        color: var(--app-text-secondary);
        background: var(--app-success-bg);
        border: none;
        height: 20px;
        padding: 0 4px;
        border-radius: 2px;
      }

      &.action-delete {
        color: var(--app-text-secondary);
        background: var(--app-error-bg);
        border: none;
        height: 20px;
        padding: 0 4px;
        border-radius: 2px;
      }

      &.action-share {
        color: var(--app-text);
        background: var(--app-purple-bg);
        border: none;
        height: 20px;
        padding: 0 4px;
        border-radius: 2px;
      }

      &:hover {
        opacity: 0.8;
      }
    }
  }
}
</style>

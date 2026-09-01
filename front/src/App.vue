<template>
  <ConfigProvider
    :locale="language[systemStore.language]"
    :componentsLocale="componentsLocale[systemStore.language]"
    :IconConfig="{
      scriptUrl: '//at.alicdn.com/t/c/font_4035907_u9qt3der4l.js'
    }"
    :theme="themeConfig"
  >
    <router-view />
  </ConfigProvider>
</template>
<script setup lang="ts">
import { ConfigProvider } from '@jetlinks-web/components'
import { theme as antdTheme } from 'ant-design-vue'
import zhCN from 'ant-design-vue/es/locale/zh_CN'
import enUs from 'ant-design-vue/es/locale/en_US'
import componentsZhCN from '@jetlinks-web/components/es/locale/zh-CN'
import componentsEnUS from '@jetlinks-web/components/es/locale/en-US'
import theme from '../configs/theme'
import { useAuthStore, useSystemStore } from '@/store'
import { ComponentsEnum, LOCAL_BASE_API, BASE_API } from '@jetlinks-web/constants'
import { initPackages } from '@/package'
import { setToken } from '@jetlinks-web/utils'
import { initPersonal } from '@/utils'

const route = useRoute()

const systemStore = useSystemStore()

const language = {
  en: enUs,
  zh: zhCN
}

const componentsLocale = {
  en: componentsEnUS,
  zh: componentsZhCN
}
// 为公共hooks提供权限校验方法
const { hasPermission } = useAuthStore()

const themeConfig = {
  algorithm: antdTheme.darkAlgorithm,
  token: theme,
  components: {
    Layout: {
      headerBg: '#0B192A',
      bodyBg: '#0A1422',
      siderBg: '#0B192A',
      triggerBg: '#172F49'
    },
    Menu: {
      darkItemBg: '#0B192A',
      darkSubMenuItemBg: '#0B192A',
      darkItemColor: '#C4D0DE',
      darkItemHoverColor: '#F4F7FC',
      darkItemSelectedColor: '#F4F7FC',
      darkItemSelectedBg: 'rgba(47, 128, 255, 0.2)',
      darkGroupTitleColor: '#8799AD',
      itemBorderRadius: 7,
      itemHeight: 39,
      itemMarginBlock: 3,
      itemMarginInline: 0,
      itemPaddingInline: 11
    },
    Card: {
      colorBgContainer: '#102238',
      colorBorderSecondary: '#35516F',
      headerBg: '#102238'
    },
    Table: {
      colorBgContainer: '#102238',
      headerBg: '#172F49',
      headerColor: '#B9C7D8',
      rowHoverBg: 'rgba(47, 128, 255, 0.08)',
      borderColor: '#35516F',
      cellPaddingBlock: 12,
      cellPaddingInline: 16
    },
    Modal: {
      contentBg: '#102238',
      headerBg: '#102238',
      footerBg: '#102238',
      titleColor: '#F4F7FC'
    },
    Drawer: {
      colorBgElevated: '#102238'
    },
    Tabs: {
      cardBg: '#102238',
      itemColor: '#C4D0DE',
      itemSelectedColor: '#52A0FF',
      inkBarColor: '#2F80FF'
    }
  }
}

provide(ComponentsEnum.Permission, { hasPermission })

initPersonal()
initPackages()

if (import.meta.env.DEV) {
  localStorage.setItem(LOCAL_BASE_API, BASE_API)
}

const getUrlParams = () => {
  const regex = /^token=([a-fA-F0-9]+)/
  const match = window.location.href.match(regex)
  if (match && match[1]) {
    setToken(match[1])
  }
}

getUrlParams()

window.addEventListener('vite:preloadError', (event) => {
  console.error('资源版本不对，请清除浏览器缓存')
})
</script>
<style scoped></style>

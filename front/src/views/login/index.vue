<template>
  <a-spin :spinning="loading" :delay="300">
    <div class="container">
      <div class="left">
        <!-- Keep the login artwork local and versioned so an older cached
             bright image cannot silently reappear after a theme deployment. -->
        <img :src="bgImage" alt="" />
        <a
          v-if="basis?.showRecordNumber"
          href="https://beian.miit.gov.cn/#/Integrated/index"
          target="_blank"
          rel="noopener noreferrer"
          class="records"
        >
          {{ $t('login.index.102238-0') }}{{ basis?.recordNumber }}
        </a>
      </div>
      <div class="right">
        <Right
          :logo="gpMonogram"
          :title="layout?.title"
          :bindings="bindings"
          v-model:loading="loading"
        />
      </div>
    </div>
  </a-spin>
</template>
<script setup name="Login" lang="ts">
import { getImage, LocalStore } from "@jetlinks-web/utils";
import { useSystemStore } from "@/store/system";
import { storeToRefs } from "pinia";
import Right from "./right.vue";
import { bindInfo } from "@/api/login";
import {useI18n} from "vue-i18n";
import gpMonogram from "@/assets/theme-icons/gp-monogram.svg";

const { t: $t } = useI18n();
const systemStore = useSystemStore();
const { systemInfo, layout } = storeToRefs(systemStore);
const loading = ref(false);

// The query string intentionally changes when the login artwork changes. It
// keeps browsers from reusing the previous light login image while preserving
// the existing image helper and public asset layout.
const bgImage = getImage("/login/login.png?theme=dark-v2");
const bindings = ref([]);

const basis: any = computed(() => {
  return systemInfo.value.front || {};
});

const getOpen = async () => {
  await systemStore.queryVersion();
  const version = LocalStore.get("version_code");
  if (version !== "community") {
    bindInfo().then((res: any) => {
      if (res.success) {
        bindings.value = res.result
      }
    });
  }
  await systemStore.querySingleInfo("front");
};

getOpen();
</script>

<style scoped lang="less">
.container {
  display: flex;
  height: 100vh;
  color: @app-text;
  background-color: @app-bg;
  > div {
    height: 100%;
  }

  .left {
    position: relative;
    overflow: hidden;
    background: @app-bg;
    flex: 1;
    img {
      display: block;
      height: 100%;
      width: 100%;
      object-fit: cover;
      object-position: center;
    }
    .records {
      position: absolute;
      top: 96%;
      left: 35%;
      color: @app-text-tertiary;
      font-size: 14px;
    }
  }

  .right {
    min-width: 400px;
    width: 27%;
    display: flex;
    padding-top: 10%;
    flex-direction: column;
    justify-content: space-between;
    background: @app-surface;
    border-left: 1px solid @app-border;
  }
}
</style>

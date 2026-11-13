<template>
  <Detail :key="formKey" />
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import Detail from './index.vue';

const route = useRoute();
const formKey = computed(() => JSON.stringify([route.params.id, route.query.view, route.query.type]));
</script>

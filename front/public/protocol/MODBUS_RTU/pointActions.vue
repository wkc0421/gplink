<!--
  Deprecated: legacy data-collect Modbus RTU point actions.
  This project now uses jetlinks-modbus-protocol + product registerMap
  for Modbus device access. Keep this file only as historical/reference UI.
-->
<script setup>
import { inject } from "vue";

const pointActions = inject("point-actions")
pointActions.add = true

</script>

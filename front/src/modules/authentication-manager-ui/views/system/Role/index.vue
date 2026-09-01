<template>
    <j-page-container>
         <FullPage>
             <div class="dictionary_contain">
                 <div class="dictionary_left">
                     <Left @select-data="selectData"/>
                 </div>
                 <div class="dictionary_right">
                     <Right :groupId="groupId"/>
                 </div>
             </div>
         </FullPage>
    </j-page-container>
 </template>

 <script lang="ts" setup name="Role">
 import Left from './RoleLeft/index.vue'
 import Right from './RoleRight/index.vue'
 const groupId = ref()
 const selectData = (data:any)=>{
    groupId.value = data[0]
 }
 </script>
 <style lang="less" scoped>
 .dictionary_contain{
     display: flex;
     background-color: @app-surface;
     border: 1px solid @app-border;
     padding: 24px;
     height: 100%;
     min-width: 0;
     box-sizing: border-box;
     overflow: hidden;
 }
 .dictionary_left{
     border-right: 1px solid @app-border;
     padding-right: 24px;
     height:100%;
   width: 320px;
 }
 .dictionary_right{
     flex:1 1 0;
      min-width: 0;
     overflow: hidden;
     height:100%
 }
 </style>

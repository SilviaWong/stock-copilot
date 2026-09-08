<template>
  <div style="padding: 24px; max-width: 800px; margin: 0 auto;">
    <el-card shadow="hover">
      <template #header>
        <div style="display: flex; align-items: center; justify-content: space-between;">
          <span style="font-weight: bold; font-size: 18px;">📈 Stock Copilot 投资助手</span>
          <el-tag type="success">系统就绪</el-tag>
        </div>
      </template>
      <p>恭喜！Vue 3 + Element Plus 前端脚手架已成功跑通。</p>
      <el-button type="primary" :icon="Plus">记一笔买入</el-button>
      <el-button type="success" :icon="Refresh">刷新实时行情</el-button>
    </el-card>
  </div>
</template>

<script setup>
import { Plus, Refresh } from '@element-plus/icons-vue'
</script>
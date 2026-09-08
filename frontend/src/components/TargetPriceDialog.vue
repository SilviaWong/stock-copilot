<template>
  <el-dialog
    v-model="visible"
    title="🎯 设置目标止盈与止损预警线"
    width="480px"
    destroy-on-close
  >
    <el-form label-width="110px" label-position="right">
      <el-form-item label="标的信息">
        <span style="font-weight: bold;">{{ symbol }} - {{ name }}</span>
        <span style="color: #909399; margin-left: 12px;">成本均价: {{ costPrice }} 元</span>
      </el-form-item>

      <el-form-item label="目标止盈价(元)">
        <el-input-number
          v-model="targetTakeProfit"
          :precision="4"
          :step="0.01"
          :min="0"
          style="width: 100%"
          placeholder="如 +15% 目标位"
        />
        <div v-if="profitRate" style="font-size: 12px; color: #f56c6c; margin-top: 4px;">
          预期达到该价位收益率：+{{ profitRate }}%
        </div>
      </el-form-item>

      <el-form-item label="目标止损价(元)">
        <el-input-number
          v-model="targetStopLoss"
          :precision="4"
          :step="0.01"
          :min="0"
          style="width: 100%"
          placeholder="如 -8% 风控止损位"
        />
        <div v-if="lossRate" style="font-size: 12px; color: #67c23a; margin-top: 4px;">
          触及该价位最大回撤：{{ lossRate }}%
        </div>
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="handleSave">
        保存设置
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { updateTargetPrices } from '../api'

const emit = defineEmits(['success'])

const visible = ref(false)
const saving = ref(false)
const symbol = ref('')
const name = ref('')
const costPrice = ref(0)
const targetTakeProfit = ref(null)
const targetStopLoss = ref(null)

function open(row) {
  visible.value = true
  symbol.value = row.symbol
  name.value = row.name
  costPrice.value = Number(row.costPrice) || 0
  targetTakeProfit.value = row.targetTakeProfit || null
  targetStopLoss.value = row.targetStopLoss || null
}

const profitRate = computed(() => {
  if (costPrice.value > 0 && targetTakeProfit.value > costPrice.value) {
    return (((targetTakeProfit.value - costPrice.value) / costPrice.value) * 100).toFixed(2)
  }
  return null
})

const lossRate = computed(() => {
  if (costPrice.value > 0 && targetStopLoss.value && targetStopLoss.value < costPrice.value) {
    return (((targetStopLoss.value - costPrice.value) / costPrice.value) * 100).toFixed(2)
  }
  return null
})

async function handleSave() {
  saving.value = true
  try {
    await updateTargetPrices(symbol.value, targetTakeProfit.value, targetStopLoss.value)
    ElMessage.success('止盈止损线设置成功！')
    visible.value = false
    emit('success')
  } catch {
    // handled
  } finally {
    saving.value = false
  }
}

defineExpose({
  open,
})
</script>

<template>
  <el-dialog
    v-model="visible"
    title="✏️ 修改交易流水记录"
    width="520px"
    destroy-on-close
  >
    <div style="background: #f4f4f5; padding: 8px 12px; border-radius: 4px; font-size: 13px; color: #909399; margin-bottom: 16px;">
      ℹ️ 修改历史流水后，系统将自动重新按时间正序回放该标的的全量交易，重新校准持仓保本线与已落袋收益。
    </div>

    <el-form
      ref="formRef"
      :model="formData"
      label-width="100px"
      label-position="right"
    >
      <el-form-item label="标的信息">
        <span style="font-weight: bold;">{{ formData.name }} ({{ formData.symbol }})</span>
        <el-tag size="small" style="margin-left: 8px;">{{ formData.action }}</el-tag>
      </el-form-item>

      <el-form-item label="成交单价(元)">
        <el-input-number
          v-model="formData.price"
          :precision="4"
          :step="0.001"
          style="width: 100%"
        />
      </el-form-item>

      <el-form-item label="成交数量(股)">
        <el-input-number
          v-model="formData.quantity"
          :step="100"
          :min="1"
          style="width: 100%"
        />
      </el-form-item>

      <el-form-item label="交易税费(元)">
        <el-input-number
          v-model="formData.fee"
          :precision="2"
          :step="1"
          :min="0"
          style="width: 100%"
        />
      </el-form-item>

      <el-form-item label="成交时间">
        <el-date-picker
          v-model="formData.tradeTime"
          type="datetime"
          format="YYYY-MM-DD HH:mm:ss"
          value-format="YYYY-MM-DD HH:mm:ss"
          style="width: 100%"
        />
      </el-form-item>

      <el-form-item label="策略标签">
        <el-input v-model="formData.strategyTag" placeholder="如 定投加仓、止盈卖出" />
      </el-form-item>

      <el-form-item label="心得备注">
        <el-input v-model="formData.notes" type="textarea" :rows="2" />
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="handleSave">
        保存并重算持仓
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { ElMessage } from 'element-plus'
import { updateTrade } from '../api'

const emit = defineEmits(['success'])

const visible = ref(false)
const submitting = ref(false)
const recordId = ref(null)

const formData = reactive({
  symbol: '',
  name: '',
  action: 'BUY',
  price: null,
  quantity: null,
  fee: 0,
  tradeTime: '',
  strategyTag: '',
  notes: '',
})

function open(row) {
  visible.value = true
  recordId.value = row.id
  formData.symbol = row.symbol
  formData.name = row.name
  formData.action = row.action
  formData.price = Number(row.price)
  formData.quantity = row.quantity
  formData.fee = Number(row.fee || 0)
  formData.tradeTime = row.tradeTime
  formData.strategyTag = row.strategyTag || ''
  formData.notes = row.notes || ''
}

async function handleSave() {
  submitting.value = true
  try {
    await updateTrade(recordId.value, { ...formData })
    ElMessage.success('交易记录已修改，持仓账目已自动重算校准！')
    visible.value = false
    emit('success')
  } catch {
    // handled
  } finally {
    submitting.value = false
  }
}

defineExpose({
  open,
})
</script>

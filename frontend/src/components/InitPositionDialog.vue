<template>
  <el-dialog
    v-model="visible"
    :title="isEdit ? '✏️ 修改持仓数据' : '📥 录入当前已有持仓 (底仓建档)'"
    width="540px"
    destroy-on-close
    :close-on-click-modal="false"
  >
    <div style="background: #fdf6ec; border-left: 4px solid #e6a23c; padding: 10px 14px; border-radius: 4px; font-size: 13px; color: #e6a23c; margin-bottom: 18px;">
      💡 适用场景：您在券商 App 已经持有的股票/ETF，直接录入当前股数和保本成本均价，免去逐笔翻找历史流水的麻烦。
    </div>

    <el-form
      ref="formRef"
      :model="formData"
      :rules="rules"
      label-width="110px"
      label-position="right"
      status-icon
    >
      <el-form-item label="标的代码" prop="symbol">
        <el-autocomplete
          v-model="formData.symbol"
          :disabled="isEdit"
          :fetch-suggestions="queryStockSuggestions"
          placeholder="如 510300 或 159915"
          style="width: 100%"
          @select="handleStockSelect"
        >
          <template #default="{ item }">
            <span style="font-weight: bold; margin-right: 8px;">{{ item.symbol }}</span>
            <span style="color: #909399;">{{ item.name }}</span>
          </template>
        </el-autocomplete>
      </el-form-item>

      <el-form-item label="标的名称" prop="name">
        <el-input v-model="formData.name" placeholder="如 沪深300ETF" />
      </el-form-item>

      <el-form-item label="当前持仓数量" prop="holdQuantity">
        <el-input-number
          v-model="formData.holdQuantity"
          :step="100"
          :min="0"
          style="width: 100%"
          placeholder="股数/份额，如 3000"
        />
      </el-form-item>

      <el-form-item label="持仓成本均价" prop="costPrice">
        <el-input-number
          v-model="formData.costPrice"
          :precision="4"
          :step="0.001"
          :min="0"
          style="width: 100%"
          placeholder="券商App显示的持仓成本价(元)"
        />
      </el-form-item>

      <el-form-item label="当前投入本金">
        <div style="font-size: 16px; font-weight: bold; color: #409eff;">
          ¥ {{ computedTotalCost }} 元
        </div>
      </el-form-item>

      <el-form-item label="目标止盈价">
        <el-input-number
          v-model="formData.targetTakeProfit"
          :precision="4"
          :step="0.01"
          :min="0"
          style="width: 100%"
          placeholder="可选，达到该价位提示止盈"
        />
      </el-form-item>

      <el-form-item label="目标止损价">
        <el-input-number
          v-model="formData.targetStopLoss"
          :precision="4"
          :step="0.01"
          :min="0"
          style="width: 100%"
          placeholder="可选，跌破该价位提示风控"
        />
      </el-form-item>

      <el-form-item label="建档备注" prop="notes">
        <el-input
          v-model="formData.notes"
          type="textarea"
          :rows="2"
          placeholder="如：华泰证券现有底仓，已持有半年"
        />
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="handleSubmit">
        保存持仓
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, reactive, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { initHolding, searchStocks, getQuoteDetail } from '../api'

const emit = defineEmits(['success'])

const visible = ref(false)
const isEdit = ref(false)
const submitting = ref(false)
const formRef = ref(null)

const formData = reactive({
  symbol: '',
  name: '',
  holdQuantity: 0,
  costPrice: null,
  targetTakeProfit: null,
  targetStopLoss: null,
  notes: '',
})

const rules = {
  symbol: [{ required: true, message: '请输入标的代码', trigger: 'blur' }],
  name: [{ required: true, message: '请输入标的名称', trigger: 'blur' }],
  holdQuantity: [{ required: true, message: '请输入持仓数量', trigger: 'blur' }],
  costPrice: [{ required: true, message: '请输入成本均价', trigger: 'blur' }],
}

const computedTotalCost = computed(() => {
  if (formData.holdQuantity && formData.costPrice) {
    return (formData.holdQuantity * formData.costPrice).toFixed(2)
  }
  return '0.00'
})

async function queryStockSuggestions(query, cb) {
  try {
    const list = await searchStocks(query)
    cb(list || [])
  } catch {
    cb([])
  }
}

async function handleStockSelect(item) {
  formData.symbol = item.symbol
  formData.name = item.name
  // 尝试自动获取现价作为参考
  try {
    const q = await getQuoteDetail(item.symbol)
    if (q && !formData.costPrice) {
      formData.costPrice = Number(q.currentPrice)
    }
  } catch {
    // ignore
  }
}

function open(pos = null) {
  visible.value = true
  if (pos) {
    isEdit.value = true
    formData.symbol = pos.symbol
    formData.name = pos.name
    formData.holdQuantity = pos.holdQuantity || 0
    formData.costPrice = pos.costPrice ? Number(pos.costPrice) : null
    formData.targetTakeProfit = pos.targetTakeProfit ? Number(pos.targetTakeProfit) : null
    formData.targetStopLoss = pos.targetStopLoss ? Number(pos.targetStopLoss) : null
    formData.notes = pos.notes || ''
  } else {
    isEdit.value = false
    formData.symbol = ''
    formData.name = ''
    formData.holdQuantity = 1000
    formData.costPrice = null
    formData.targetTakeProfit = null
    formData.targetStopLoss = null
    formData.notes = '录入已有底仓'
  }
}

async function handleSubmit() {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    submitting.value = true
    try {
      await initHolding({ ...formData })
      ElMessage.success(isEdit.value ? '持仓数据已更新！' : '已有持仓建档成功！')
      visible.value = false
      emit('success')
    } catch {
      // error handled by axios interceptor
    } finally {
      submitting.value = false
    }
  })
}

defineExpose({
  open,
})
</script>

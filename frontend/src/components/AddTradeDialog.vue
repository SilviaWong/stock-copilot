<template>
  <el-dialog
    v-model="visible"
    :title="dialogTitle"
    width="560px"
    destroy-on-close
    :close-on-click-modal="false"
  >
    <el-form
      ref="formRef"
      :model="formData"
      :rules="rules"
      label-width="100px"
      label-position="right"
      status-icon
    >
      <el-form-item label="交易类型" prop="action">
        <el-radio-group v-model="formData.action" @change="onActionChange">
          <el-radio-button value="BUY">🟢 买入 / 加仓</el-radio-button>
          <el-radio-button value="SELL">🔴 卖出 / 减仓</el-radio-button>
          <el-radio-button value="DIVIDEND">💰 现金分红</el-radio-button>
        </el-radio-group>
      </el-form-item>

      <el-form-item label="标的代码" prop="symbol">
        <el-autocomplete
          v-model="formData.symbol"
          :fetch-suggestions="queryStockSuggestions"
          placeholder="输入6位代码，如 510300 或 600519"
          style="width: 100%"
          clearable
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

      <el-form-item
        :label="formData.action === 'DIVIDEND' ? '每份分红(元)' : '成交单价(元)'"
        prop="price"
      >
        <el-input-number
          v-model="formData.price"
          :precision="4"
          :step="0.001"
          :min="0.0001"
          style="width: 100%"
          placeholder="请输入成交单价"
          @change="recalculateAmount"
        />
      </el-form-item>

      <el-form-item
        :label="formData.action === 'DIVIDEND' ? '分红份数' : '成交数量(股)'"
        prop="quantity"
      >
        <el-input-number
          v-model="formData.quantity"
          :step="100"
          :min="1"
          style="width: 100%"
          placeholder="A股通常100起"
          @change="recalculateAmount"
        />
        <div v-if="formData.action === 'SELL' && maxSellQuantity !== null" style="font-size: 12px; color: #e6a23c; margin-top: 4px;">
          当前持仓：{{ maxSellQuantity }} 股（最多可卖出该数量）
        </div>
      </el-form-item>

      <!-- 动态预估成交金额 -->
      <el-form-item label="成交总额">
        <div style="font-size: 16px; font-weight: bold; color: #409eff;">
          ¥ {{ estimatedAmount }} 元
        </div>
      </el-form-item>

      <el-form-item label="税费佣金(元)" prop="fee">
        <el-input-number
          v-model="formData.fee"
          :precision="2"
          :step="1"
          :min="0"
          style="width: 100%"
          placeholder="券商佣金、过户费、印花税等"
        />
      </el-form-item>

      <el-form-item label="交易时间" prop="tradeTime">
        <el-date-picker
          v-model="formData.tradeTime"
          type="datetime"
          placeholder="选择实际成交时间"
          format="YYYY-MM-DD HH:mm:ss"
          value-format="YYYY-MM-DD HH:mm:ss"
          style="width: 100%"
        />
      </el-form-item>

      <el-form-item label="策略分类" prop="strategyTag">
        <el-select
          v-model="formData.strategyTag"
          filterable
          allow-create
          default-first-option
          placeholder="选择或输入策略标签"
          style="width: 100%"
        >
          <el-option label="网格底仓" value="网格底仓" />
          <el-option label="定投加仓" value="定投加仓" />
          <el-option label="均线突破" value="均线突破" />
          <el-option label="低位补仓摊薄" value="低位补仓摊薄" />
          <el-option label="达到目标止盈" value="达到目标止盈" />
          <el-option label="破位风控止损" value="破位风控止损" />
        </el-select>
      </el-form-item>

      <el-form-item label="买卖理由/心得" prop="notes">
        <el-input
          v-model="formData.notes"
          type="textarea"
          :rows="2"
          placeholder="记录为什么要在这个点位操作？是否符合既定纪律？（复盘核心财富）"
        />
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="handleSubmit">
        确认入账
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, reactive, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { recordTrade, searchStocks } from '../api'

const emit = defineEmits(['success'])

const visible = ref(false)
const submitting = ref(false)
const formRef = ref(null)
const maxSellQuantity = ref(null)

const formData = reactive({
  action: 'BUY',
  symbol: '',
  name: '',
  price: null,
  quantity: null,
  fee: 5.0, // 常见单笔基础佣金默认值
  tradeTime: '',
  strategyTag: '',
  notes: '',
})

const dialogTitle = computed(() => {
  if (formData.action === 'BUY') return '➕ 记一笔买入 / 加仓'
  if (formData.action === 'SELL') return '➖ 记一笔卖出 / 减仓'
  return '💰 记一笔现金分红'
})

const estimatedAmount = computed(() => {
  if (formData.price && formData.quantity) {
    return (formData.price * formData.quantity).toFixed(2)
  }
  return '0.00'
})

const rules = {
  action: [{ required: true, message: '请选择交易动作', trigger: 'change' }],
  symbol: [{ required: true, message: '请输入标的代码', trigger: 'blur' }],
  price: [{ required: true, message: '请输入单价', trigger: 'blur' }],
  quantity: [{ required: true, message: '请输入数量', trigger: 'blur' }],
}

// 自动补齐当前时间（格式: yyyy-MM-dd HH:mm:ss）
function formatNow() {
  const now = new Date()
  const pad = (n) => String(n).padStart(2, '0')
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())} ${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())}`
}

function onActionChange(val) {
  if (val === 'SELL') {
    formData.strategyTag = formData.strategyTag || '达到目标止盈'
  } else if (val === 'BUY') {
    formData.strategyTag = formData.strategyTag || '定投加仓'
  } else if (val === 'DIVIDEND') {
    formData.fee = 0
  }
}

// 自动估算手续费
function recalculateAmount() {
  if (formData.price && formData.quantity && formData.action !== 'DIVIDEND') {
    const total = formData.price * formData.quantity
    // ETF 免印花税，佣金万分之2左右（最低通常5元或0.2元，这里简易默认预估）
    if (formData.symbol.startsWith('51') || formData.symbol.startsWith('15')) {
      formData.fee = Math.max(0.5, +(total * 0.0002).toFixed(2))
    } else {
      // 股票通常万2.5，卖出有万5印花税
      const rate = formData.action === 'SELL' ? 0.00075 : 0.00025
      formData.fee = Math.max(5, +(total * rate).toFixed(2))
    }
  }
}

// 代码联想建议
async function queryStockSuggestions(queryString, cb) {
  try {
    const res = await searchStocks(queryString)
    cb(res || [])
  } catch {
    cb([])
  }
}

function handleStockSelect(item) {
  formData.symbol = item.symbol
  formData.name = item.name
  recalculateAmount()
}

// 打开弹窗（支持从外部传入初始预填数据）
function open(initData = {}) {
  visible.value = true
  maxSellQuantity.value = initData.maxHoldQuantity !== undefined ? initData.maxHoldQuantity : null

  formData.action = initData.action || 'BUY'
  formData.symbol = initData.symbol || ''
  formData.name = initData.name || ''
  formData.price = initData.price || null
  formData.quantity = initData.quantity || null
  formData.fee = initData.fee !== undefined ? initData.fee : 5.0
  formData.tradeTime = formatNow()
  formData.strategyTag = initData.strategyTag || (formData.action === 'BUY' ? '定投加仓' : '达到目标止盈')
  formData.notes = ''
}

async function handleSubmit() {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    submitting.value = true
    try {
      await recordTrade({ ...formData })
      ElMessage.success('交易记录已成功录入并同步更新持仓！')
      visible.value = false
      emit('success')
    } catch (err) {
      // 错误已被 axios 拦截器处理
    } finally {
      submitting.value = false
    }
  })
}

defineExpose({
  open,
})
</script>

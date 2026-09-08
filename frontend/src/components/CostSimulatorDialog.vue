<template>
  <el-dialog
    v-model="visible"
    title="🧮 加仓成本摊薄与回本测算器 (购买决策辅助)"
    width="640px"
    destroy-on-close
  >
    <div style="background: #f0f7ff; padding: 14px 18px; border-radius: 8px; margin-bottom: 20px; border-left: 4px solid #409eff;">
      <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px;">
        <span style="font-weight: bold; font-size: 16px; color: #303133;">
          {{ positionData.name }} ({{ positionData.symbol }})
        </span>
        <div v-if="realQuote" style="font-size: 13px;">
          实时现价: <b :class="realQuote.changePercent >= 0 ? 'up-color' : 'down-color'">
            ¥ {{ realQuote.currentPrice }} ({{ realQuote.changePercent >= 0 ? '+' : '' }}{{ realQuote.changePercent }}%)
          </b>
        </div>
      </div>
      <div style="display: flex; gap: 24px; font-size: 13px; color: #606266;">
        <div>当前持仓: <b style="color: #303133;">{{ positionData.holdQuantity }}</b> 股</div>
        <div>当前保本均价: <b style="color: #303133;">{{ positionData.costPrice }}</b> 元</div>
        <div>当前累计投入: <b style="color: #303133;">¥ {{ positionData.totalCost }}</b> 元</div>
      </div>
    </div>

    <el-form label-width="110px" label-position="right">
      <el-row :gutter="16">
        <el-col :span="12">
          <el-form-item label="拟加仓价格(元)">
            <el-input-number
              v-model="simPrice"
              :precision="4"
              :step="0.001"
              :min="0.0001"
              style="width: 100%"
              placeholder="拟买入价"
            />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="拟加仓数量(股)">
            <el-input-number
              v-model="simQuantity"
              :step="100"
              :min="1"
              style="width: 100%"
              placeholder="100起"
            />
          </el-form-item>
        </el-col>
      </el-row>
    </el-form>

    <!-- 测算推演结果看板 -->
    <div v-if="result" style="background: #fafafa; border: 1px solid #ebeef5; border-radius: 8px; padding: 16px; margin-top: 10px;">
      <div style="font-weight: bold; color: #303133; margin-bottom: 12px; font-size: 14px;">
        📊 测算结果分析
      </div>
      <el-row :gutter="12" style="text-align: center;">
        <el-col :span="8">
          <div style="color: #909399; font-size: 12px;">本次加仓需投入</div>
          <div style="font-size: 18px; font-weight: bold; color: #409eff; margin-top: 4px;">
            ¥ {{ result.additionalCost }}
          </div>
        </el-col>
        <el-col :span="8">
          <div style="color: #909399; font-size: 12px;">加仓后新持仓均价</div>
          <div style="font-size: 20px; font-weight: bold; color: #67c23a; margin-top: 4px;">
            {{ result.newCostPrice }} <span style="font-size: 12px;">元</span>
          </div>
        </el-col>
        <el-col :span="8">
          <div style="color: #909399; font-size: 12px;">回本所需反弹幅度</div>
          <div style="font-size: 18px; font-weight: bold; color: #e6a23c; margin-top: 4px;">
            +{{ result.reboundRateNeeded }}%
          </div>
        </el-col>
      </el-row>

      <el-divider style="margin: 14px 0;" />

      <div style="font-size: 13px; color: #606266; line-height: 1.8;">
        <div>• <b>均价摊薄效果</b>：均价从 {{ positionData.costPrice }} 元拉低到 <b>{{ result.newCostPrice }}</b> 元（降低了 <b>{{ result.costReducedPct }}%</b>）。</div>
        <div>• <b>新持仓总量</b>：持仓从 {{ positionData.holdQuantity }} 股增加到 <b>{{ result.newTotalQuantity }}</b> 股，总投入增至 <b>¥ {{ result.newTotalCost }}</b> 元。</div>
        <div>• <b>辅助决策评估</b>：
          <span v-if="result.isEffective" style="color: #67c23a; font-weight: bold;">
            本次加仓显著压低了保本线，回本难度从原先的 +{{ result.oldReboundNeeded }}% 降低至 +{{ result.reboundRateNeeded }}%！
          </span>
          <span v-else style="color: #909399;">
            加仓数量相对已有底仓较小，对均价摊薄效果有限，建议根据网格节奏分配弹药。
          </span>
        </div>
      </div>
    </div>

    <template #footer>
      <el-button @click="visible = false">关闭</el-button>
      <el-button type="success" :disabled="!result" @click="applyToTrade">
        以测算值去记账买入
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, reactive, computed } from 'vue'
import { getQuoteDetail } from '../api'

const emit = defineEmits(['apply-trade'])

const visible = ref(false)
const realQuote = ref(null)
const positionData = reactive({
  symbol: '',
  name: '',
  holdQuantity: 0,
  costPrice: 0,
  totalCost: 0,
})

const simPrice = ref(null)
const simQuantity = ref(null)

async function open(pos) {
  visible.value = true
  realQuote.value = null
  positionData.symbol = pos.symbol
  positionData.name = pos.name
  positionData.holdQuantity = pos.holdQuantity || 0
  positionData.costPrice = pos.costPrice || 0
  positionData.totalCost = pos.totalCost || 0

  // 默认拟加仓价格设为当前现价，如果没获取到则用成本价 95%
  if (pos.currentPrice && pos.currentPrice > 0) {
    simPrice.value = Number(pos.currentPrice)
  } else {
    simPrice.value = +(positionData.costPrice * 0.95).toFixed(3) || null
  }
  simQuantity.value = positionData.holdQuantity > 0 ? positionData.holdQuantity : 1000

  // 异步拉取最新行情展示
  try {
    const q = await getQuoteDetail(pos.symbol)
    if (q && q.currentPrice) {
      realQuote.value = q
      // 如果之前没有准确现价，以实时行情现价更新
      if (!pos.currentPrice) {
        simPrice.value = Number(q.currentPrice)
      }
    }
  } catch {
    // 忽略异常
  }
}

const result = computed(() => {
  if (!simPrice.value || !simQuantity.value || simQuantity.value <= 0) return null

  const q0 = positionData.holdQuantity
  const c0 = Number(positionData.totalCost)
  const p0 = Number(positionData.costPrice)

  const q1 = simQuantity.value
  const p1 = simPrice.value

  const addCost = +(p1 * q1).toFixed(2)
  const newTotalCost = +(c0 + addCost).toFixed(2)
  const newTotalQuantity = q0 + q1
  const newCostPrice = +(newTotalCost / newTotalQuantity).toFixed(4)

  // 成本拉低百分比
  const costReducedPct = p0 > 0 ? +(((p0 - newCostPrice) / p0) * 100).toFixed(2) : 0

  // 新均价相比加仓买入价所需反弹幅度
  const reboundRateNeeded = p1 > 0 ? +(((newCostPrice - p1) / p1) * 100).toFixed(2) : 0
  const oldReboundNeeded = p1 > 0 && p0 > 0 ? +(((p0 - p1) / p1) * 100).toFixed(2) : 0

  return {
    additionalCost: addCost,
    newTotalCost,
    newTotalQuantity,
    newCostPrice,
    costReducedPct: Math.max(0, costReducedPct),
    reboundRateNeeded: Math.max(0, reboundRateNeeded),
    oldReboundNeeded: Math.max(0, oldReboundNeeded),
    isEffective: costReducedPct >= 3.0,
  }
})

function applyToTrade() {
  visible.value = false
  emit('apply-trade', {
    action: 'BUY',
    symbol: positionData.symbol,
    name: positionData.name,
    price: simPrice.value,
    quantity: simQuantity.value,
    strategyTag: '低位补仓摊薄',
  })
}

defineExpose({
  open,
})
</script>

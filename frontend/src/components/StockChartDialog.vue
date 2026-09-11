<template>
  <el-dialog
    v-model="visible"
    :title="dialogTitle"
    width="1120px"
    top="2.5vh"
    destroy-on-close
    class="stock-chart-dialog"
    @opened="handleDialogOpened"
    @closed="handleDialogClosed"
  >
    <!-- 标的实时行情概览与持仓生命线看板 -->
    <div class="quote-header-card">
      <div class="header-main-row">
        <div class="stock-title-box">
          <span class="stock-name">{{ currentName || symbol }}</span>
          <span class="stock-code num-font">{{ symbol }}</span>
          <el-tag size="small" :type="marketTagType" effect="plain" class="market-tag">
            {{ resolveMarketTag(symbol) }}
          </el-tag>

          <!-- 大盘基准对照与相对强弱 (Alpha) -->
          <div v-if="benchmark && benchmark.benchmarkName" class="benchmark-pill-tag">
            <span class="bm-label">大盘基准:</span>
            <span class="bm-name">{{ benchmark.benchmarkName }}</span>
            <span class="bm-chg num-font" :class="Number(benchmark.benchmarkChangePercent) >= 0 ? 'up-color' : 'down-color'">
              {{ Number(benchmark.benchmarkChangePercent) >= 0 ? '+' : '' }}{{ Number(benchmark.benchmarkChangePercent || 0).toFixed(2) }}%
            </span>
            <span class="bm-divider">|</span>
            <span class="bm-label">相对大盘:</span>
            <el-tag size="small" :type="benchmark.relativeStrengthLevel || 'info'" effect="dark" class="bm-tag">
              {{ benchmark.relativeStrengthStatus || '同步大盘' }}
              <span v-if="benchmark.relativeStrength !== undefined && benchmark.relativeStrength !== null">
                {{ Number(benchmark.relativeStrength) > 0 ? '+' : '' }}{{ Number(benchmark.relativeStrength).toFixed(2) }}%
              </span>
            </el-tag>
          </div>
        </div>


        <div v-if="quoteInfo.latestPrice" class="latest-price-box">
          <div class="price-val num-font" :class="priceColorClass">
            ¥ {{ Number(quoteInfo.latestPrice).toFixed(3) }}
          </div>
          <div class="price-diff num-font" :class="priceColorClass">
            <span>{{ quoteInfo.changeAmount >= 0 ? '+' : '' }}{{ Number(quoteInfo.changeAmount).toFixed(3) }}</span>
            <span style="margin-left: 6px;">({{ quoteInfo.changePercent >= 0 ? '+' : '' }}{{ Number(quoteInfo.changePercent).toFixed(2) }}%)</span>
          </div>
        </div>

        <div class="quote-meta-grid">
          <div class="meta-item"><span class="meta-label">今开</span><span class="meta-val num-font">{{ quoteInfo.open ? Number(quoteInfo.open).toFixed(3) : '-' }}</span></div>
          <div class="meta-item"><span class="meta-label">昨收</span><span class="meta-val num-font">{{ quoteInfo.preClose ? Number(quoteInfo.preClose).toFixed(3) : '-' }}</span></div>
          <div class="meta-item"><span class="meta-label">最高</span><span class="meta-val num-font up-color">{{ quoteInfo.high ? Number(quoteInfo.high).toFixed(3) : '-' }}</span></div>
          <div class="meta-item"><span class="meta-label">最低</span><span class="meta-val num-font down-color">{{ quoteInfo.low ? Number(quoteInfo.low).toFixed(3) : '-' }}</span></div>
          <div class="meta-item"><span class="meta-label">总手</span><span class="meta-val num-font">{{ formatVolume(quoteInfo.totalVolume) }}</span></div>
          <div class="meta-item"><span class="meta-label">总额</span><span class="meta-val num-font">{{ formatAmount(quoteInfo.totalAmount) }}</span></div>
        </div>
      </div>

      <!-- 专属持仓成本生命线横幅 -->
      <div v-if="positionInfo.holdQuantity > 0 || positionInfo.costPrice" class="position-lifeline-bar">
        <div class="lifeline-title">
          <span class="pulse-dot"></span>
          <span>持仓生命线:</span>
        </div>
        <div class="lifeline-items">
          <div class="lifeline-item">
            持仓股数: <b class="num-font">{{ positionInfo.holdQuantity }}</b> 股
          </div>
          <div class="lifeline-item">
            买入均价线: <b class="num-font cost-blue">¥ {{ Number(positionInfo.costPrice).toFixed(4) }}</b>
          </div>
          <div v-if="positionInfo.dilutedCostPrice !== null" class="lifeline-item">
            做T保本线: <b class="num-font t-yellow">¥ {{ Number(positionInfo.dilutedCostPrice).toFixed(3) }}</b>
            <span class="sub-tip">(已抵扣做T落袋利润)</span>
          </div>
          <div v-if="historyMarkersCount > 0" class="lifeline-item">
            实战买卖打点: <b class="num-font" style="color: #67c23a;">{{ buyCount }} 买</b> / <b class="num-font" style="color: #f56c6c;">{{ sellCount }} 卖</b>
          </div>
        </div>
      </div>
    </div>

    <!-- 次日 Pivot Points 轴心点网格预测折叠卡片 (日K线系统模式下展示) -->
    <div v-if="activeChartTab === 'kline' && currentPivotPoints" class="pivot-grid-card">
      <div class="pivot-header-row" @click="showPivotCard = !showPivotCard">
        <div class="pivot-title-left">
          <span class="pivot-icon">🎯</span>
          <span class="pivot-title-text">次日多空轴心网格预测 (Floor Trader Pivot)</span>
          <el-tag size="small" :type="getPivotStatusTag(currentPivotPoints.status)" effect="light" class="pivot-tag">
            {{ currentPivotPoints.status || '中性震荡' }}
          </el-tag>
          <span class="pivot-date-tip num-font">基准交易日: {{ currentPivotPoints.baseDate }}</span>
        </div>
        <div class="pivot-header-right">
          <span class="pivot-collapse-btn">
            {{ showPivotCard ? '收起网格' : '展开网格' }}
            <el-icon class="collapse-arrow"><ArrowUp v-if="showPivotCard" /><ArrowDown v-else /></el-icon>
          </span>
        </div>
      </div>

      <el-collapse-transition>
        <div v-if="showPivotCard" class="pivot-body">
          <div class="pivot-levels-grid">
            <div class="pivot-level-item s2-box">
              <div class="level-badge">S2 极限防守</div>
              <div class="level-price num-font">¥ {{ Number(currentPivotPoints.s2).toFixed(3) }}</div>
              <div class="level-sub">恐慌底 / 破位止损线</div>
            </div>
            <div class="pivot-level-item s1-box">
              <div class="level-badge">S1 低吸支撑</div>
              <div class="level-price num-font">¥ {{ Number(currentPivotPoints.s1).toFixed(3) }}</div>
              <div class="level-sub">回踩企稳 / 做T买点</div>
            </div>
            <div class="pivot-level-item p-box">
              <div class="level-badge">P 多空轴心</div>
              <div class="level-price num-font">¥ {{ Number(currentPivotPoints.pivot).toFixed(3) }}</div>
              <div class="level-sub">强弱分水岭 / 争夺中枢</div>
            </div>
            <div class="pivot-level-item r1-box">
              <div class="level-badge">R1 高抛阻力</div>
              <div class="level-price num-font">¥ {{ Number(currentPivotPoints.r1).toFixed(3) }}</div>
              <div class="level-sub">冲高遇阻 / 做T卖点</div>
            </div>
            <div class="pivot-level-item r2-box">
              <div class="level-badge">R2 多头极值</div>
              <div class="level-price num-font">¥ {{ Number(currentPivotPoints.r2).toFixed(3) }}</div>
              <div class="level-sub">强势拉升 / 超买减仓</div>
            </div>
          </div>
          <div class="pivot-tip-bar">
            <span class="tip-bulb">💡 挂单锦囊:</span>
            <span class="tip-content">{{ currentPivotPoints.actionTip }}</span>
          </div>
        </div>
      </el-collapse-transition>
    </div>

    <!-- 图表类型与量化指标切换控制栏 -->
    <div class="chart-controls-bar">
      <div class="controls-left">
        <el-radio-group v-model="activeChartTab" size="default" @change="handleTabChange">
          <el-radio-button label="minute">1日分时</el-radio-button>
          <el-radio-button label="fiveDay">5日分时</el-radio-button>
          <el-radio-button label="kline">日K线系统</el-radio-button>
        </el-radio-group>

        <!-- K线模式下：主图指标切换 (MA / BOLL) -->
        <div v-if="activeChartTab === 'kline'" class="indicator-toggle-box">
          <span class="indicator-label">主图:</span>
          <el-radio-group v-model="klineMainIndicator" size="small" @change="handleIndicatorChange">
            <el-radio-button label="MA">MA 均线</el-radio-button>
            <el-radio-button label="BOLL">BOLL 布林带</el-radio-button>
          </el-radio-group>
        </div>

        <!-- K线模式下：副图指标切换 (VOL / MACD / KDJ) -->
        <div v-if="activeChartTab === 'kline'" class="indicator-toggle-box">
          <span class="indicator-label">副图:</span>
          <el-radio-group v-model="klineSubIndicator" size="small" @change="handleIndicatorChange">
            <el-radio-button label="VOL">VOL 成交量</el-radio-button>
            <el-radio-button label="MACD">MACD 指标</el-radio-button>
            <el-radio-button label="KDJ">KDJ 随机</el-radio-button>
          </el-radio-group>
        </div>
      </div>

      <div class="controls-right">
        <!-- K线模式下动态指标图例提示 -->
        <div v-if="activeChartTab === 'kline'" class="kline-indicator-legends">
          <template v-if="klineMainIndicator === 'MA'">
            <span class="ma-legend ma5"><i class="legend-color-box ma5-bg"></i>MA5</span>
            <span class="ma-legend ma10"><i class="legend-color-box ma10-bg"></i>MA10</span>
            <span class="ma-legend ma20"><i class="legend-color-box ma20-bg"></i>MA20</span>
            <span class="ma-legend ma60"><i class="legend-color-box ma60-bg"></i>MA60</span>
          </template>
          <template v-else-if="klineMainIndicator === 'BOLL'">
            <span class="ma-legend boll-upper"><i class="legend-color-box boll-upper-bg"></i>上轨 UPPER</span>
            <span class="ma-legend boll-mid"><i class="legend-color-box boll-mid-bg"></i>中轨 MID</span>
            <span class="ma-legend boll-lower"><i class="legend-color-box boll-lower-bg"></i>下轨 LOWER</span>
          </template>
        </div>

        <!-- 🔮 AI 次日走势推演按钮 -->
        <el-button
          type="primary"
          class="ai-predict-btn"
          :icon="Cpu"
          :loading="predictLoading"
          @click="handleOpenAiPredict"
        >
          🔮 AI 次日走势推演
        </el-button>

        <el-button size="small" :icon="Refresh" :loading="loading" @click="fetchCurrentChartData">
          刷新走势
        </el-button>
      </div>
    </div>

    <!-- ECharts 走势图画布容器 -->
    <div v-loading="loading" element-loading-text="正在拉取金融行情与买卖复盘打点..." class="chart-canvas-wrapper">
      <div ref="chartDom" class="chart-dom"></div>
      <div v-if="noData && !loading" class="no-data-mask">
        <el-empty description="当前暂无分时或K线行情数据，可能处于休市期或接口通讯中" />
      </div>
    </div>

    <!-- 底部图例说明提示：采用现代化芯片指示器，拒绝字符重叠与拥挤 -->
    <div class="chart-footer-legend">
      <div class="legend-chips">
        <template v-if="activeChartTab !== 'kline' || klineSubIndicator === 'VOL'">
          <span class="legend-chip"><i class="chip-line line-blue"></i>分时走势</span>
          <span class="legend-chip"><i class="chip-line line-yellow"></i>均价黄线</span>
          <span class="legend-chip"><i class="chip-line line-dashed-gray"></i>昨收中轴</span>
        </template>
        <template v-else-if="klineSubIndicator === 'MACD'">
          <span class="legend-chip"><i class="chip-line line-yellow"></i>DIF 快线</span>
          <span class="legend-chip"><i class="chip-line line-purple"></i>DEA 慢线</span>
          <span class="legend-chip"><i class="chip-line line-red-green"></i>MACD 红绿动能柱</span>
        </template>
        <template v-else-if="klineSubIndicator === 'KDJ'">
          <span class="legend-chip"><i class="chip-line line-yellow"></i>K 线</span>
          <span class="legend-chip"><i class="chip-line line-blue"></i>D 线</span>
          <span class="legend-chip"><i class="chip-line line-pink"></i>J 超买超卖敏感线</span>
        </template>

        <span v-if="positionInfo.costPrice" class="legend-chip cost-chip">
          <i class="chip-line line-cost"></i>买入均价线 (¥{{ Number(positionInfo.costPrice).toFixed(3) }})
        </span>
        <span v-if="positionInfo.dilutedCostPrice" class="legend-chip t-chip">
          <i class="chip-line line-t"></i>做T保本线 (¥{{ Number(positionInfo.dilutedCostPrice).toFixed(3) }})
        </span>
      </div>
      <div v-if="activeChartTab === 'kline'" class="legend-kline-tips">
        <span class="marker-chip buy"><i class="marker-dot buy-dot"></i>B 买入</span>
        <span class="marker-chip sell"><i class="marker-dot sell-dot"></i>S 卖出</span>
        <span class="tip-text">💡 提示：支持滚轮缩放、拖拽平移，鼠标悬停打点可查阅详细复盘</span>
      </div>
    </div>

    <!-- 🔮 AI 次日走势推演报告弹窗 -->
    <el-dialog
      v-model="aiPredictVisible"
      :title="'🔮 ' + (currentName || symbol) + ' (' + symbol + ') AI 次日多空推演与实战锦囊'"
      width="840px"
      append-to-body
      class="ai-predict-dialog"
    >
      <div v-loading="predictLoading" element-loading-text="AI 大模型与量化引擎正在结合您的真实持仓与技术指标推演明日多空..." class="ai-predict-body">
        <template v-if="predictData">
          <!-- 核心定调与胜率横幅 -->
          <div class="verdict-banner" :class="getVerdictBannerClass(predictData.trendVerdict)">
            <div class="verdict-left">
              <div class="verdict-tag-row">
                <span class="verdict-title">明日多空定调:</span>
                <el-tag size="large" effect="dark" :type="getVerdictTagType(predictData.trendVerdict)" class="verdict-badge">
                  {{ predictData.trendVerdict }}
                </el-tag>
              </div>
              <div class="verdict-meta">
                <span>决策引擎: <b>{{ predictData.modelUsed }}</b></span>
                <span v-if="predictData.latencyMs">耗时: <b>{{ predictData.latencyMs }}ms</b></span>
                <span>推演时间: <b>{{ predictData.timestamp }}</b></span>
              </div>
            </div>

            <div class="verdict-prob-box">
              <div class="prob-num num-font">{{ predictData.bullishProbability }}%</div>
              <div class="prob-label">多头预估胜率</div>
            </div>
          </div>

          <!-- 用户真实持仓对比基准卡片 -->
          <div class="predict-position-card">
            <div class="card-col">
              <span class="col-label">最新收盘/现价</span>
              <span class="col-val num-font">¥ {{ predictData.currentPrice ? Number(predictData.currentPrice).toFixed(3) : '-' }}</span>
            </div>
            <div class="card-col">
              <span class="col-label">持仓股数</span>
              <span class="col-val num-font">{{ predictData.holdQuantity || 0 }} 股</span>
            </div>
            <div class="card-col">
              <span class="col-label">买入均价线</span>
              <span class="col-val num-font cost-blue">¥ {{ predictData.holdCostPrice ? Number(predictData.holdCostPrice).toFixed(4) : '-' }}</span>
            </div>
            <div class="card-col highlight-col">
              <span class="col-label">做T摊薄保本价</span>
              <span class="col-val num-font t-yellow">¥ {{ predictData.dilutedCostPrice ? Number(predictData.dilutedCostPrice).toFixed(3) : '-' }}</span>
            </div>
            <div v-if="predictData.pivotPoints" class="card-col">
              <span class="col-label">明日多空轴心 P</span>
              <span class="col-val num-font">¥ {{ Number(predictData.pivotPoints.pivot).toFixed(3) }}</span>
            </div>
          </div>

          <!-- AI Markdown 推演报告内容 -->
          <div class="predict-report-content" v-html="renderMarkdown(predictData.analysisReport)"></div>
        </template>
      </div>

      <template #footer>
        <div class="predict-dialog-footer">
          <div class="footer-tip">
            💡 提示：AI 推演结果融合了布林带、MACD、KDJ、Pivot 网格与您的真实持仓保本线，请严格遵守交易纪律。
          </div>
          <div class="footer-btns">
            <el-button size="default" :loading="predictLoading" @click="fetchAiPrediction">
              重新推演
            </el-button>
            <el-button type="primary" size="default" @click="aiPredictVisible = false">
              我知道了
            </el-button>
          </div>
        </div>
      </template>
    </el-dialog>
  </el-dialog>
</template>

<script setup>
import { ref, computed, nextTick, onBeforeUnmount, shallowRef } from 'vue'
import { Refresh, Cpu, ArrowUp, ArrowDown } from '@element-plus/icons-vue'
import { getMinuteChart, getFiveDayChart, getKlineChart, predictNextDayTrend } from '../api'

const props = defineProps({
  modelValue: {
    type: Boolean,
    default: false,
  },
  symbol: {
    type: String,
    required: true,
  },
  name: {
    type: String,
    default: '',
  },
  benchmark: {
    type: Object,
    default: null,
  },
})


const emit = defineEmits(['update:modelValue'])

const visible = computed({
  get: () => props.modelValue,
  set: (val) => emit('update:modelValue', val),
})

const dialogTitle = computed(() => {
  return `📈 ${currentName.value || props.name || props.symbol} (${props.symbol}) 行情走势与买卖打点复盘`
})

const activeChartTab = ref('minute')
const loading = ref(false)
const noData = ref(false)
const currentName = ref(props.name || '')
const chartDom = ref(null)
const chartInstance = shallowRef(null)

// 量化技术指标切换系统 (主图 MA/BOLL，副图 VOL/MACD/KDJ)
const klineMainIndicator = ref('MA')
const klineSubIndicator = ref('VOL')
const cachedKlineData = shallowRef(null)

// 次日 Pivot Points 轴心点系统
const showPivotCard = ref(true)
const currentPivotPoints = ref(null)

// AI 次日多空走势推演
const aiPredictVisible = ref(false)
const predictLoading = ref(false)
const predictData = ref(null)

// 头部行情摘要
const quoteInfo = ref({
  latestPrice: null,
  changeAmount: 0,
  changePercent: 0,
  open: null,
  preClose: null,
  high: null,
  low: null,
  totalVolume: null,
  totalAmount: null,
})

// 持仓与交易图钉概览
const positionInfo = ref({
  holdQuantity: 0,
  costPrice: null,
  dilutedCostPrice: null,
})
const allMarkers = ref([])

const historyMarkersCount = computed(() => allMarkers.value.length)
const buyCount = computed(() => allMarkers.value.filter(m => m.action === 'BUY').length)
const sellCount = computed(() => allMarkers.value.filter(m => m.action === 'SELL').length)

const priceColorClass = computed(() => {
  if (!quoteInfo.value.latestPrice || !quoteInfo.value.preClose) return ''
  const diff = quoteInfo.value.latestPrice - quoteInfo.value.preClose
  return diff > 0 ? 'up-color' : diff < 0 ? 'down-color' : ''
})

const marketTagType = computed(() => {
  const m = resolveMarketTag(props.symbol)
  return m === 'SH' ? 'danger' : m === 'SZ' ? 'primary' : 'warning'
})

function resolveMarketTag(sym) {
  if (!sym) return 'SH'
  if (sym.startsWith('6') || sym.startsWith('5')) return 'SH'
  if (sym.startsWith('0') || sym.startsWith('3') || sym.startsWith('1')) return 'SZ'
  return 'BJ'
}

function formatVolume(vol) {
  if (!vol) return '-'
  if (vol >= 100000000) return (vol / 100000000).toFixed(2) + '亿手'
  if (vol >= 10000) return (vol / 10000).toFixed(1) + '万手'
  return vol + '手'
}

function formatAmount(amt) {
  if (!amt) return '-'
  if (amt >= 100000000) return (amt / 100000000).toFixed(2) + '亿元'
  if (amt >= 10000) return (amt / 10000).toFixed(1) + '万元'
  return '¥' + Number(amt).toFixed(0)
}

function handleDialogOpened() {
  currentName.value = props.name || ''
  nextTick(() => {
    initChartInstance()
    fetchCurrentChartData()
  })
}

function handleDialogClosed() {
  if (chartInstance.value) {
    chartInstance.value.dispose()
    chartInstance.value = null
  }
}

function handleTabChange() {
  fetchCurrentChartData()
}

function initChartInstance() {
  if (!chartDom.value) return
  if (chartInstance.value) {
    chartInstance.value.dispose()
  }
  if (window.echarts) {
    chartInstance.value = window.echarts.init(chartDom.value)
    window.addEventListener('resize', handleResize)
  }
}

function handleResize() {
  chartInstance.value?.resize()
}

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  if (chartInstance.value) {
    chartInstance.value.dispose()
    chartInstance.value = null
  }
})

// 请求并渲染对应走势图
async function fetchCurrentChartData() {
  if (!props.symbol) return
  loading.value = true
  noData.value = false

  try {
    if (activeChartTab.value === 'minute') {
      const res = await getMinuteChart(props.symbol)
      renderMinuteChart(res)
    } else if (activeChartTab.value === 'fiveDay') {
      const res = await getFiveDayChart(props.symbol)
      renderFiveDayChart(res)
    } else if (activeChartTab.value === 'kline') {
      const res = await getKlineChart(props.symbol)
      renderKlineChart(res)
    }
  } catch (err) {
    console.error('获取走势图失败:', err)
    noData.value = true
  } finally {
    loading.value = false
  }
}

// -------------------------------------------------------------
// 1. 绘制 1 日分时图
// -------------------------------------------------------------
function renderMinuteChart(data) {
  if (!data || !data.times || data.times.length === 0) {
    noData.value = true
    return
  }

  // 更新顶部基础指标
  currentName.value = data.name || currentName.value
  quoteInfo.value = {
    latestPrice: data.latestPrice,
    changeAmount: data.changeAmount,
    changePercent: data.changePercent,
    open: data.prices && data.prices.length > 0 ? data.prices[0] : null,
    preClose: data.preClose,
    high: data.high,
    low: data.low,
    totalVolume: data.totalVolume,
    totalAmount: data.totalAmount,
  }
  positionInfo.value = {
    holdQuantity: data.holdQuantity || 0,
    costPrice: data.costPrice,
    dilutedCostPrice: data.dilutedCostPrice,
  }
  allMarkers.value = data.tradeMarkers || []

  if (!chartInstance.value) initChartInstance()
  const chart = chartInstance.value
  if (!chart) return

  const preClose = Number(data.preClose) || (data.prices.length > 0 ? Number(data.prices[0]) : 1.0)
  const prices = data.prices.map(p => Number(p))
  const avgPrices = data.avgPrices.map(p => Number(p))
  const volumes = data.volumes || []
  const costPrice = data.costPrice ? Number(data.costPrice) : null
  const dilutedCostPrice = data.dilutedCostPrice ? Number(data.dilutedCostPrice) : null

  // 计算对称 Y 轴区间 (围绕昨收盘价 preClose 上下对称)
  let priceMaxDiff = 0
  prices.forEach(p => {
    const d = Math.abs(p - preClose)
    if (d > priceMaxDiff) priceMaxDiff = d
  })
  priceMaxDiff = Math.max(priceMaxDiff, preClose * 0.015)
  let maxDiff = priceMaxDiff * 1.18

  // 智能容纳成本线：仅当成本线在 7% 差异内时容纳，避免极端值压平分时图
  const maxAllowedCostDiff = preClose * 0.07
  if (costPrice && Math.abs(costPrice - preClose) <= maxAllowedCostDiff) {
    const cd = Math.abs(costPrice - preClose) * 1.08
    if (cd > maxDiff) maxDiff = cd
  }
  if (dilutedCostPrice && Math.abs(dilutedCostPrice - preClose) <= maxAllowedCostDiff) {
    const dd = Math.abs(dilutedCostPrice - preClose) * 1.08
    if (dd > maxDiff) maxDiff = dd
  }

  const yMin = Number((preClose - maxDiff).toFixed(3))
  const yMax = Number((preClose + maxDiff).toFixed(3))
  const maxPercent = Number((maxDiff / preClose * 100).toFixed(2))

  // 标的持仓生命线 MarkLines (使用内部徽章，避免右侧溢出截断)
  const markLineData = [
    {
      name: '昨收中轴',
      yAxis: preClose,
      lineStyle: { color: '#909399', type: 'dashed', width: 1 },
      label: {
        show: true,
        position: 'insideStartTop',
        formatter: `昨收: ${preClose.toFixed(3)}`,
        fontSize: 11,
        color: '#64748b',
        backgroundColor: 'rgba(241, 245, 249, 0.85)',
        padding: [2, 5],
        borderRadius: 2,
      },
    },
  ]
  if (costPrice && costPrice >= yMin && costPrice <= yMax) {
    markLineData.push({
      name: '买入均价线',
      yAxis: costPrice,
      lineStyle: { color: '#2563eb', type: 'solid', width: 1.8 },
      label: {
        show: true,
        position: 'insideEndTop',
        formatter: `买入均价 ¥${costPrice.toFixed(3)}`,
        fontSize: 11,
        fontWeight: 'bold',
        color: '#ffffff',
        backgroundColor: 'rgba(37, 99, 235, 0.9)',
        padding: [3, 6],
        borderRadius: 3,
        distance: 5,
      },
    })
  }
  if (dilutedCostPrice && dilutedCostPrice >= yMin && dilutedCostPrice <= yMax) {
    markLineData.push({
      name: '做T保本线',
      yAxis: dilutedCostPrice,
      lineStyle: { color: '#d97706', type: 'dashed', width: 1.8 },
      label: {
        show: true,
        position: 'insideEndBottom',
        formatter: `做T保本 ¥${dilutedCostPrice.toFixed(3)}`,
        fontSize: 11,
        fontWeight: 'bold',
        color: '#ffffff',
        backgroundColor: 'rgba(217, 119, 6, 0.9)',
        padding: [3, 6],
        borderRadius: 3,
        distance: 5,
      },
    })
  }

  // 当日买卖打点 markPoint
  const markPointData = []
  if (data.tradeMarkers && data.tradeMarkers.length > 0) {
    data.tradeMarkers.forEach(m => {
      // 提取时分并寻找在 times 中的最佳匹配下标
      const markerTime = m.tradeTime && m.tradeTime.length >= 16 ? m.tradeTime.substring(11, 16) : ''
      let matchIdx = data.times.indexOf(markerTime)
      if (matchIdx === -1 && data.times.length > 0) {
        matchIdx = data.times.length - 1
      }
      if (matchIdx >= 0) {
        const isBuy = m.action === 'BUY'
        markPointData.push({
          name: isBuy ? '当日买入' : '当日卖出',
          value: m.label || (isBuy ? 'B' : 'S'),
          coord: [data.times[matchIdx], Number(m.price)],
          itemStyle: {
            color: isBuy ? '#67c23a' : '#f56c6c',
          },
          label: {
            show: true,
            color: '#fff',
            fontWeight: 'bold',
            fontSize: 10,
          },
          rawMarker: m,
        })
      }
    })
  }

  const option = {
    animation: false,
    tooltip: {
      trigger: 'axis',
      axisPointer: {
        type: 'cross',
        lineStyle: { color: '#409eff', width: 1, type: 'dashed' },
      },
      backgroundColor: 'rgba(255, 255, 255, 0.95)',
      borderColor: '#dcdfe6',
      borderWidth: 1,
      textStyle: { color: '#303133', fontSize: 12 },
      formatter: (params) => {
        if (!params || params.length === 0) return ''
        const time = params[0].axisValue
        let price = null
        let avg = null
        let vol = null

        params.forEach(p => {
          if (p.seriesName === '分时现价') price = p.value
          if (p.seriesName === '分时均价') avg = p.value
          if (p.seriesName === '成交量') vol = p.value
        })

        const diff = price !== null ? price - preClose : 0
        const pct = price !== null ? (diff / preClose * 100) : 0
        const color = diff >= 0 ? '#f56c6c' : '#67c23a'
        const sign = diff >= 0 ? '+' : ''

        let html = `<div style="font-weight: bold; margin-bottom: 4px; border-bottom: 1px solid #ebeef5; padding-bottom: 2px;">⏰ 时间: ${time}</div>`
        if (price !== null) {
          html += `<div>现价: <b style="color: ${color};">${price.toFixed(3)}</b> (${sign}${diff.toFixed(3)}, ${sign}${pct.toFixed(2)}%)</div>`
        }
        if (avg !== null) {
          html += `<div>均价: <b style="color: #e6a23c;">${avg.toFixed(3)}</b></div>`
        }
        if (vol !== null) {
          html += `<div>量: <b style="color: #606266;">${vol} 手</b></div>`
        }
        return html
      },
    },
    axisPointer: {
      link: [{ xAxisIndex: 'all' }],
    },
    grid: [
      { left: '68px', right: '72px', top: '9%', height: '57%' },
      { left: '68px', right: '72px', top: '74%', height: '17%' },
    ],
    xAxis: [
      {
        type: 'category',
        data: data.times,
        boundaryGap: false,
        axisLine: { lineStyle: { color: '#dcdfe6' } },
        axisLabel: {
          color: '#94a3b8',
          fontSize: 11,
          interval: (index, value) => {
            return value === '09:30' || value === '10:30' || value === '11:30' || value === '14:00' || value === '15:00'
          },
          formatter: (val) => {
            if (val === '11:30') return '11:30/13:00'
            return val
          },
        },
        splitLine: { show: true, lineStyle: { color: '#f1f5f9', type: 'dashed' } },
      },
      {
        type: 'category',
        gridIndex: 1,
        data: data.times,
        boundaryGap: false,
        axisLine: { lineStyle: { color: '#dcdfe6' } },
        axisLabel: { show: false },
        splitLine: { show: false },
      },
    ],
    yAxis: [
      {
        type: 'value',
        min: yMin,
        max: yMax,
        interval: maxDiff,
        axisLine: { lineStyle: { color: '#dcdfe6' } },
        axisLabel: {
          color: '#606266',
          fontSize: 11,
          formatter: (v) => v.toFixed(3),
        },
        splitLine: { show: true, lineStyle: { color: '#f1f5f9' } },
      },
      {
        type: 'value',
        min: -maxPercent,
        max: maxPercent,
        interval: maxPercent,
        axisLine: { lineStyle: { color: '#dcdfe6' } },
        axisLabel: {
          color: '#94a3b8',
          fontSize: 11,
          formatter: (v) => (v >= 0 ? '+' : '') + v.toFixed(2) + '%',
        },
        splitLine: { show: false },
      },
      {
        type: 'value',
        gridIndex: 1,
        axisLine: { lineStyle: { color: '#dcdfe6' } },
        axisLabel: {
          color: '#94a3b8',
          fontSize: 10,
          formatter: (v) => formatVolume(v),
        },
        splitLine: { show: false },
      },
    ],
    series: [
      {
        name: '分时现价',
        type: 'line',
        data: prices,
        smooth: false,
        showSymbol: false,
        lineStyle: { color: '#3388ff', width: 1.6 },
        areaStyle: {
          color: new window.echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: 'rgba(51, 136, 255, 0.22)' },
            { offset: 1, color: 'rgba(51, 136, 255, 0.01)' },
          ]),
        },
        markLine: {
          silent: false,
          symbol: ['none', 'none'],
          data: markLineData,
        },
        markPoint: {
          symbol: 'pin',
          symbolSize: 28,
          data: markPointData,
        },
      },
      {
        name: '分时均价',
        type: 'line',
        data: avgPrices,
        smooth: true,
        showSymbol: false,
        lineStyle: { color: '#e6a23c', width: 1.2 },
      },
      {
        name: '成交量',
        type: 'bar',
        xAxisIndex: 1,
        yAxisIndex: 2,
        data: volumes.map((v, i) => ({
          value: v,
          itemStyle: {
            color: prices[i] >= (prices[i - 1] || preClose) ? '#f56c6c' : '#67c23a',
          },
        })),
      },
    ],
  }

  chart.setOption(option, true)
}

// -------------------------------------------------------------
// 2. 绘制连续 5 日分时图
// -------------------------------------------------------------
function renderFiveDayChart(data) {
  if (!data || !data.times || data.times.length === 0) {
    noData.value = true
    return
  }

  currentName.value = data.name || currentName.value
  positionInfo.value = {
    holdQuantity: data.holdQuantity || 0,
    costPrice: data.costPrice,
    dilutedCostPrice: data.dilutedCostPrice,
  }

  if (!chartInstance.value) initChartInstance()
  const chart = chartInstance.value
  if (!chart) return

  const prices = data.prices.map(p => Number(p))
  const avgPrices = data.avgPrices.map(p => Number(p))
  const volumes = data.volumes || []
  const basePrice = Number(data.basePrice) || (prices.length > 0 ? prices[0] : 1.0)
  const costPrice = data.costPrice ? Number(data.costPrice) : null
  const dilutedCostPrice = data.dilutedCostPrice ? Number(data.dilutedCostPrice) : null

  // 对称 Y 轴计算 (基于实际价格波动，7% 以内平滑容纳成本线)
  let priceMaxDiff = 0
  prices.forEach(p => {
    const d = Math.abs(p - basePrice)
    if (d > priceMaxDiff) priceMaxDiff = d
  })
  priceMaxDiff = Math.max(priceMaxDiff, basePrice * 0.015)
  let maxDiff = priceMaxDiff * 1.18

  const maxAllowedCostDiff = basePrice * 0.07
  if (costPrice && Math.abs(costPrice - basePrice) <= maxAllowedCostDiff) {
    const cd = Math.abs(costPrice - basePrice) * 1.08
    if (cd > maxDiff) maxDiff = cd
  }
  if (dilutedCostPrice && Math.abs(dilutedCostPrice - basePrice) <= maxAllowedCostDiff) {
    const dd = Math.abs(dilutedCostPrice - basePrice) * 1.08
    if (dd > maxDiff) maxDiff = dd
  }

  const yMin = Number((basePrice - maxDiff).toFixed(3))
  const yMax = Number((basePrice + maxDiff).toFixed(3))

  // 垂直分割线 (5天开盘点)
  const splitMarkLines = []
  if (data.splitIndexes && data.splitIndexes.length > 0) {
    data.splitIndexes.forEach((idx) => {
      if (idx > 0 && idx < data.times.length) {
        splitMarkLines.push({
          xAxis: data.times[idx],
          lineStyle: { color: '#cbd5e1', type: 'dashed', width: 1 },
          label: { show: false }, // 避免在垂直分割线上放置文字与底部日期发生碰撞重叠
        })
      }
    })
  }

  // 持仓生命线 (内部标签防截断)
  if (costPrice && costPrice >= yMin && costPrice <= yMax) {
    splitMarkLines.push({
      yAxis: costPrice,
      lineStyle: { color: '#2563eb', type: 'solid', width: 1.8 },
      label: {
        show: true,
        position: 'insideEndTop',
        formatter: `买入均价 ¥${costPrice.toFixed(3)}`,
        fontSize: 11,
        fontWeight: 'bold',
        color: '#ffffff',
        backgroundColor: 'rgba(37, 99, 235, 0.9)',
        padding: [3, 6],
        borderRadius: 3,
        distance: 5,
      },
    })
  }
  if (dilutedCostPrice && dilutedCostPrice >= yMin && dilutedCostPrice <= yMax) {
    splitMarkLines.push({
      yAxis: dilutedCostPrice,
      lineStyle: { color: '#d97706', type: 'dashed', width: 1.8 },
      label: {
        show: true,
        position: 'insideEndBottom',
        formatter: `做T保本 ¥${dilutedCostPrice.toFixed(3)}`,
        fontSize: 11,
        fontWeight: 'bold',
        color: '#ffffff',
        backgroundColor: 'rgba(217, 119, 6, 0.9)',
        padding: [3, 6],
        borderRadius: 3,
        distance: 5,
      },
    })
  }

  const option = {
    animation: false,
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'cross', lineStyle: { color: '#409eff', type: 'dashed' } },
      formatter: (params) => {
        if (!params || params.length === 0) return ''
        const time = params[0].axisValue
        let price = null
        let avg = null
        let vol = null
        params.forEach(p => {
          if (p.seriesName === '五日走势') price = p.value
          if (p.seriesName === '均价') avg = p.value
          if (p.seriesName === '成交量') vol = p.value
        })
        const diff = price !== null ? price - basePrice : 0
        const pct = price !== null ? (diff / basePrice * 100) : 0
        const color = diff >= 0 ? '#f56c6c' : '#67c23a'
        return `
          <div style="font-weight: bold; border-bottom: 1px solid #ebeef5; padding-bottom: 2px;">📅 ${time}</div>
          <div>价格: <b style="color: ${color};">${price !== null ? price.toFixed(3) : '-'}</b> (${pct >= 0 ? '+' : ''}${pct.toFixed(2)}%)</div>
          ${avg !== null ? `<div>均价: <b style="color: #e6a23c;">${avg.toFixed(3)}</b></div>` : ''}
          ${vol !== null ? `<div>量: <b>${vol} 手</b></div>` : ''}
        `
      },
    },
    grid: [
      { left: '68px', right: '72px', top: '9%', height: '57%' },
      { left: '68px', right: '72px', top: '74%', height: '17%' },
    ],
    xAxis: [
      {
        type: 'category',
        data: data.times,
        boundaryGap: false,
        axisLine: { lineStyle: { color: '#dcdfe6' } },
        axisLabel: {
          color: '#475569',
          fontSize: 11,
          fontWeight: '600',
          interval: (index) => {
            return data.splitIndexes && data.splitIndexes.includes(index)
          },
          formatter: (val) => {
            return val ? val.split(' ')[0] : ''
          },
        },
        splitLine: { show: false },
      },
      {
        type: 'category',
        gridIndex: 1,
        data: data.times,
        boundaryGap: false,
        axisLabel: { show: false },
        splitLine: { show: false },
      },
    ],
    yAxis: [
      {
        type: 'value',
        min: yMin,
        max: yMax,
        interval: maxDiff,
        axisLine: { lineStyle: { color: '#dcdfe6' } },
        axisLabel: {
          color: '#606266',
          fontSize: 11,
          formatter: (v) => v.toFixed(3),
        },
        splitLine: { show: true, lineStyle: { color: '#f1f5f9' } },
      },
      {
        type: 'value',
        gridIndex: 1,
        axisLine: { lineStyle: { color: '#dcdfe6' } },
        axisLabel: {
          color: '#94a3b8',
          fontSize: 10,
          formatter: (v) => formatVolume(v),
        },
        splitLine: { show: false },
      },
    ],
    series: [
      {
        name: '五日走势',
        type: 'line',
        data: prices,
        showSymbol: false,
        lineStyle: { color: '#3388ff', width: 1.5 },
        markLine: {
          silent: false,
          symbol: ['none', 'none'],
          data: splitMarkLines,
        },
      },
      {
        name: '均价',
        type: 'line',
        data: avgPrices,
        showSymbol: false,
        lineStyle: { color: '#e6a23c', width: 1.2 },
      },
      {
        name: '成交量',
        type: 'bar',
        xAxisIndex: 1,
        yAxisIndex: 1,
        data: volumes.map((v, i) => ({
          value: v,
          itemStyle: {
            color: prices[i] >= (prices[i - 1] || basePrice) ? '#f56c6c' : '#67c23a',
          },
        })),
      },
    ],
  }

  chart.setOption(option, true)
}

// -------------------------------------------------------------
// 量化技术指标切换与 AI 推演交互辅助方法
// -------------------------------------------------------------
function handleIndicatorChange() {
  if (cachedKlineData.value) {
    renderKlineChart(cachedKlineData.value)
  }
}

function getPivotStatusTag(status) {
  if (!status) return 'info'
  if (status.includes('强') || status.includes('多')) return 'success'
  if (status.includes('弱') || status.includes('空') || status.includes('破')) return 'danger'
  return 'warning'
}

function getVerdictTagType(verdict) {
  if (!verdict) return 'primary'
  if (verdict.includes('多') || verdict.includes('攻')) return 'success'
  if (verdict.includes('空') || verdict.includes('防守') || verdict.includes('破')) return 'danger'
  return 'warning'
}

function getVerdictBannerClass(verdict) {
  if (!verdict) return 'banner-neutral'
  if (verdict.includes('多') || verdict.includes('攻')) return 'banner-bullish'
  if (verdict.includes('空') || verdict.includes('防守') || verdict.includes('破')) return 'banner-bearish'
  return 'banner-neutral'
}

function loadSavedAiConfig() {
  try {
    const saved = localStorage.getItem('stock_copilot_ocr_config')
    if (saved) return JSON.parse(saved)
  } catch (e) {
    console.error('加载本地 AI 配置失败:', e)
  }
  return null
}

function handleOpenAiPredict() {
  aiPredictVisible.value = true
  if (!predictData.value || predictData.value.symbol !== props.symbol) {
    fetchAiPrediction()
  }
}

async function fetchAiPrediction() {
  predictLoading.value = true
  try {
    const config = loadSavedAiConfig()
    const res = await predictNextDayTrend({
      symbol: props.symbol,
      config: config,
    })
    predictData.value = res
  } catch (err) {
    console.error('AI 趋势推演失败:', err)
  } finally {
    predictLoading.value = false
  }
}

/**
 * 规范格式化 Markdown 文本 (带样式高亮)
 */
function renderMarkdown(md) {
  if (!md) return ''

  let html = md
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')

  // 标题
  html = html.replace(/^### (.*$)/gim, '<h4 class="md-h4">$1</h4>')
  html = html.replace(/^## (.*$)/gim, '<h3 class="md-h3">$1</h3>')
  html = html.replace(/^# (.*$)/gim, '<h2 class="md-h2">$1</h2>')

  // 加粗与斜体
  html = html.replace(/\*\*(.*?)\*\*/gim, '<strong>$1</strong>')
  html = html.replace(/\*(.*?)\*/gim, '<em>$1</em>')

  // 行内代码
  html = html.replace(/`([^`]+)`/gim, '<code class="md-code">$1</code>')

  // 引用块
  html = html.replace(/^&gt; (.*$)/gim, '<blockquote class="md-quote">$1</blockquote>')

  // 无序列表项
  html = html.replace(/^\s*[-*]\s+(.*$)/gim, '<li class="md-li">$1</li>')

  // 换行
  html = html.replace(/\n/gim, '<br />')

  return html
}

// -------------------------------------------------------------
// 3. 绘制日 K 线图 (蜡烛图 + MA均线 / BOLL布林带 + VOL / MACD / KDJ + 历史买卖打点图钉)
// -------------------------------------------------------------
function renderKlineChart(data) {
  if (!data || !data.dates || data.dates.length === 0) {
    noData.value = true
    return
  }

  cachedKlineData.value = data
  currentPivotPoints.value = data.pivotPoints || null
  currentName.value = data.name || currentName.value
  positionInfo.value = {
    holdQuantity: data.holdQuantity || 0,
    costPrice: data.costPrice,
    dilutedCostPrice: data.dilutedCostPrice,
  }
  allMarkers.value = data.tradeMarkers || []

  if (!chartInstance.value) initChartInstance()
  const chart = chartInstance.value
  if (!chart) return

  const dates = data.dates
  const values = data.values // [open, close, low, high]
  const volumes = data.volumes
  const costPrice = data.costPrice ? Number(data.costPrice) : null
  const dilutedCostPrice = data.dilutedCostPrice ? Number(data.dilutedCostPrice) : null

  // 持仓生命线 markLine (内部防截断徽章)
  const markLineData = []
  if (costPrice && costPrice > 0) {
    markLineData.push({
      name: '持仓买入均价线',
      yAxis: costPrice,
      lineStyle: { color: '#2563eb', type: 'solid', width: 2 },
      label: {
        show: true,
        position: 'insideEndTop',
        formatter: `买入均价 ¥${costPrice.toFixed(3)}`,
        fontSize: 11,
        fontWeight: 'bold',
        color: '#ffffff',
        backgroundColor: 'rgba(37, 99, 235, 0.9)',
        padding: [3, 6],
        borderRadius: 3,
        distance: 5,
      },
    })
  }
  if (dilutedCostPrice && dilutedCostPrice > 0) {
    markLineData.push({
      name: '做T摊薄保本线',
      yAxis: dilutedCostPrice,
      lineStyle: { color: '#d97706', type: 'dashed', width: 2 },
      label: {
        show: true,
        position: 'insideEndBottom',
        formatter: `做T保本 ¥${dilutedCostPrice.toFixed(3)}`,
        fontSize: 11,
        fontWeight: 'bold',
        color: '#ffffff',
        backgroundColor: 'rgba(217, 119, 6, 0.9)',
        padding: [3, 6],
        borderRadius: 3,
        distance: 5,
      },
    })
  }

  // 历史买卖图钉打点 markPoint (B / S)
  const markPointData = []
  if (data.tradeMarkers && data.tradeMarkers.length > 0) {
    data.tradeMarkers.forEach(m => {
      const isBuy = m.action === 'BUY'
      const dateIdx = dates.indexOf(m.tradeDate)
      if (dateIdx !== -1) {
        markPointData.push({
          name: isBuy ? '买入' : '卖出',
          value: m.label || (isBuy ? 'B' : 'S'),
          coord: [m.tradeDate, Number(m.price)],
          symbol: 'pin',
          symbolSize: 32,
          symbolOffset: [0, -10],
          itemStyle: {
            color: isBuy ? '#67c23a' : '#f56c6c',
            borderColor: '#fff',
            borderWidth: 1,
            shadowBlur: 4,
            shadowColor: 'rgba(0,0,0,0.3)',
          },
          label: {
            show: true,
            color: '#fff',
            fontWeight: 'bold',
            fontSize: 11,
          },
          rawMarker: m,
        })
      }
    })
  }

  // 默认定位在最近 80 个交易日 (约 4 个月)
  const defaultStartPercent = dates.length > 80 ? Math.floor((1 - 80 / dates.length) * 100) : 0

  // 1. 主图指标系列 (MA 或 BOLL)
  const mainSeries = []
  if (klineMainIndicator.value === 'MA') {
    mainSeries.push(
      { name: 'MA5', type: 'line', data: data.ma5, smooth: true, showSymbol: false, lineStyle: { color: '#e6a23c', width: 1.2 } },
      { name: 'MA10', type: 'line', data: data.ma10, smooth: true, showSymbol: false, lineStyle: { color: '#8e44ad', width: 1.2 } },
      { name: 'MA20', type: 'line', data: data.ma20, smooth: true, showSymbol: false, lineStyle: { color: '#27ae60', width: 1.2 } },
      { name: 'MA60', type: 'line', data: data.ma60, smooth: true, showSymbol: false, lineStyle: { color: '#2980b9', width: 1.2 } },
    )
  } else if (klineMainIndicator.value === 'BOLL') {
    mainSeries.push(
      { name: 'BOLL上轨', type: 'line', data: data.bollUpper, smooth: true, showSymbol: false, lineStyle: { color: '#8b5cf6', width: 1.3 } },
      { name: 'BOLL中轨', type: 'line', data: data.bollMid, smooth: true, showSymbol: false, lineStyle: { color: '#f59e0b', width: 1.3 } },
      { name: 'BOLL下轨', type: 'line', data: data.bollLower, smooth: true, showSymbol: false, lineStyle: { color: '#06b6d4', width: 1.3 } },
    )
  }

  // 2. 副图指标系列 (VOL, MACD, KDJ) 与副图 Y 轴配置
  const subSeries = []
  let subYAxisConfig = {}

  if (klineSubIndicator.value === 'VOL') {
    subYAxisConfig = {
      scale: true,
      gridIndex: 1,
      axisLabel: { formatter: (v) => formatVolume(v), fontSize: 10, color: '#64748b' },
      splitLine: { show: false },
    }
    subSeries.push({
      name: '成交量',
      type: 'bar',
      xAxisIndex: 1,
      yAxisIndex: 1,
      data: volumes.map((v, i) => {
        const val = values[i]
        const isUp = val ? val[1] >= val[0] : true
        return {
          value: v,
          itemStyle: { color: isUp ? '#f56c6c' : '#67c23a' },
        }
      }),
    })
  } else if (klineSubIndicator.value === 'MACD') {
    subYAxisConfig = {
      scale: true,
      gridIndex: 1,
      axisLabel: { formatter: (v) => Number(v).toFixed(2), fontSize: 10, color: '#64748b' },
      splitLine: { show: true, lineStyle: { color: '#f1f5f9', type: 'dashed' } },
    }
    subSeries.push(
      {
        name: 'MACD柱',
        type: 'bar',
        xAxisIndex: 1,
        yAxisIndex: 1,
        data: (data.macdBar || []).map(b => ({
          value: b,
          itemStyle: { color: Number(b) >= 0 ? '#f56c6c' : '#67c23a' },
        })),
      },
      {
        name: 'DIF',
        type: 'line',
        xAxisIndex: 1,
        yAxisIndex: 1,
        data: data.macdDif,
        smooth: true,
        showSymbol: false,
        lineStyle: { color: '#e6a23c', width: 1.2 },
      },
      {
        name: 'DEA',
        type: 'line',
        xAxisIndex: 1,
        yAxisIndex: 1,
        data: data.macdDea,
        smooth: true,
        showSymbol: false,
        lineStyle: { color: '#8e44ad', width: 1.2 },
      }
    )
  } else if (klineSubIndicator.value === 'KDJ') {
    subYAxisConfig = {
      scale: true,
      gridIndex: 1,
      min: (val) => Math.min(0, Math.floor(val.min)),
      max: (val) => Math.max(100, Math.ceil(val.max)),
      axisLabel: { fontSize: 10, color: '#64748b' },
      splitLine: { show: true, lineStyle: { color: '#f1f5f9', type: 'dashed' } },
    }
    subSeries.push(
      {
        name: 'K',
        type: 'line',
        xAxisIndex: 1,
        yAxisIndex: 1,
        data: data.kdjK,
        smooth: true,
        showSymbol: false,
        lineStyle: { color: '#f59e0b', width: 1.2 },
      },
      {
        name: 'D',
        type: 'line',
        xAxisIndex: 1,
        yAxisIndex: 1,
        data: data.kdjD,
        smooth: true,
        showSymbol: false,
        lineStyle: { color: '#3b82f6', width: 1.2 },
      },
      {
        name: 'J',
        type: 'line',
        xAxisIndex: 1,
        yAxisIndex: 1,
        data: data.kdjJ,
        smooth: true,
        showSymbol: false,
        lineStyle: { color: '#ec4899', width: 1.2 },
      }
    )
  }

  const option = {
    animation: false,
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'cross', lineStyle: { color: '#409eff', type: 'dashed' } },
      backgroundColor: 'rgba(255, 255, 255, 0.96)',
      borderColor: '#dcdfe6',
      borderWidth: 1,
      textStyle: { color: '#303133', fontSize: 12 },
      formatter: (params) => {
        if (!params || params.length === 0) return ''
        const date = params[0].axisValue
        let kItem = null
        let ma5 = null, ma10 = null, ma20 = null, ma60 = null
        let bollUpper = null, bollMid = null, bollLower = null
        let vol = null
        let dif = null, dea = null, macdBar = null
        let kdjK = null, kdjD = null, kdjJ = null

        params.forEach(p => {
          if (p.seriesName === '日K') kItem = p.value // [open, close, low, high]
          if (p.seriesName === 'MA5') ma5 = p.value
          if (p.seriesName === 'MA10') ma10 = p.value
          if (p.seriesName === 'MA20') ma20 = p.value
          if (p.seriesName === 'MA60') ma60 = p.value
          if (p.seriesName === 'BOLL上轨') bollUpper = p.value
          if (p.seriesName === 'BOLL中轨') bollMid = p.value
          if (p.seriesName === 'BOLL下轨') bollLower = p.value
          if (p.seriesName === '成交量') vol = p.value
          if (p.seriesName === 'DIF') dif = p.value
          if (p.seriesName === 'DEA') dea = p.value
          if (p.seriesName === 'MACD柱') macdBar = p.value
          if (p.seriesName === 'K') kdjK = p.value
          if (p.seriesName === 'D') kdjD = p.value
          if (p.seriesName === 'J') kdjJ = p.value
        })

        let html = `<div style="font-weight: bold; border-bottom: 1px solid #ebeef5; padding-bottom: 4px; margin-bottom: 4px;">📅 日期: ${date}</div>`
        if (kItem && kItem.length >= 5) {
          const open = Number(kItem[1])
          const close = Number(kItem[2])
          const low = Number(kItem[3])
          const high = Number(kItem[4])
          const chg = close - open
          const chgPct = (chg / open * 100).toFixed(2)
          const color = close >= open ? '#f56c6c' : '#67c23a'
          const sign = chg >= 0 ? '+' : ''

          html += `
            <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 4px 12px; margin-bottom: 4px;">
              <div>开盘: <b>${open.toFixed(3)}</b></div>
              <div>收盘: <b style="color: ${color};">${close.toFixed(3)}</b> (${sign}${chgPct}%)</div>
              <div>最高: <b style="color: #f56c6c;">${high.toFixed(3)}</b></div>
              <div>最低: <b style="color: #67c23a;">${low.toFixed(3)}</b></div>
            </div>
          `
        }

        // 检查该日是否有买卖打点
        const dayTrades = (data.tradeMarkers || []).filter(m => m.tradeDate === date)
        if (dayTrades.length > 0) {
          html += `<div style="margin-top: 4px; padding-top: 4px; border-top: 1px dashed #dcdfe6; background: #fdf6ec; padding: 4px 6px; border-radius: 4px;">`
          html += `<div style="font-weight: bold; color: #e6a23c; margin-bottom: 2px;">🎯 本日实战成交 (${dayTrades.length}笔):</div>`
          dayTrades.forEach((t) => {
            const isB = t.action === 'BUY'
            html += `<div style="font-size: 11px;">
              <span style="color: ${isB ? '#67c23a' : '#f56c6c'}; font-weight: bold;">[${isB ? '买入' : '卖出'}]</span>
              单价: ¥${Number(t.price).toFixed(3)} | 股数: ${t.quantity}股
              ${t.realizedPnl ? ` | 盈亏: <b style="color:${Number(t.realizedPnl)>=0?'#f56c6c':'#67c23a'}">${Number(t.realizedPnl)>=0?'+':''}${t.realizedPnl}元</b>` : ''}
              ${t.strategyTag ? ` (${t.strategyTag})` : ''}
            </div>`
          })
          html += `</div>`
        }

        // 主图指标数据浮层
        html += `<div style="margin-top: 4px; font-size: 11px; color: #909399;">`
        if (klineMainIndicator.value === 'MA') {
          if (ma5 !== null && ma5 !== undefined) html += `<span style="color: #e6a23c; margin-right: 8px;">MA5: ${Number(ma5).toFixed(3)}</span>`
          if (ma10 !== null && ma10 !== undefined) html += `<span style="color: #8e44ad; margin-right: 8px;">MA10: ${Number(ma10).toFixed(3)}</span>`
          if (ma20 !== null && ma20 !== undefined) html += `<span style="color: #27ae60; margin-right: 8px;">MA20: ${Number(ma20).toFixed(3)}</span>`
          if (ma60 !== null && ma60 !== undefined) html += `<span style="color: #2980b9;">MA60: ${Number(ma60).toFixed(3)}</span>`
        } else if (klineMainIndicator.value === 'BOLL') {
          if (bollUpper !== null && bollUpper !== undefined) html += `<span style="color: #8b5cf6; margin-right: 8px;">UPPER: ${Number(bollUpper).toFixed(3)}</span>`
          if (bollMid !== null && bollMid !== undefined) html += `<span style="color: #f59e0b; margin-right: 8px;">MID: ${Number(bollMid).toFixed(3)}</span>`
          if (bollLower !== null && bollLower !== undefined) html += `<span style="color: #06b6d4;">LOWER: ${Number(bollLower).toFixed(3)}</span>`
        }
        html += `</div>`

        // 副图指标数据浮层
        if (klineSubIndicator.value === 'VOL' && vol !== null) {
          html += `<div style="font-size: 11px; color: #606266; margin-top: 2px;">成交量: ${formatVolume(vol)}</div>`
        } else if (klineSubIndicator.value === 'MACD') {
          html += `<div style="font-size: 11px; margin-top: 2px;">`
          if (dif !== null) html += `<span style="color: #e6a23c; margin-right: 8px;">DIF: ${Number(dif).toFixed(3)}</span>`
          if (dea !== null) html += `<span style="color: #8e44ad; margin-right: 8px;">DEA: ${Number(dea).toFixed(3)}</span>`
          if (macdBar !== null) html += `<span style="color: ${Number(macdBar) >= 0 ? '#f56c6c' : '#67c23a'}; font-weight: bold;">MACD: ${Number(macdBar).toFixed(3)}</span>`
          html += `</div>`
        } else if (klineSubIndicator.value === 'KDJ') {
          html += `<div style="font-size: 11px; margin-top: 2px;">`
          if (kdjK !== null) html += `<span style="color: #f59e0b; margin-right: 8px;">K: ${Number(kdjK).toFixed(2)}</span>`
          if (kdjD !== null) html += `<span style="color: #3b82f6; margin-right: 8px;">D: ${Number(kdjD).toFixed(2)}</span>`
          if (kdjJ !== null) html += `<span style="color: #ec4899; font-weight: bold;">J: ${Number(kdjJ).toFixed(2)}</span>`
          html += `</div>`
        }

        return html
      },
    },
    axisPointer: { link: [{ xAxisIndex: 'all' }] },
    dataZoom: [
      {
        type: 'inside',
        xAxisIndex: [0, 1],
        start: defaultStartPercent,
        end: 100,
      },
      {
        show: true,
        xAxisIndex: [0, 1],
        type: 'slider',
        top: '92%',
        height: 20,
        start: defaultStartPercent,
        end: 100,
        borderColor: '#dcdfe6',
        fillerColor: 'rgba(64, 158, 255, 0.15)',
      },
    ],
    grid: [
      { left: '68px', right: '72px', top: '9%', height: '57%' },
      { left: '68px', right: '72px', top: '74%', height: '16%' },
    ],
    xAxis: [
      {
        type: 'category',
        data: dates,
        boundaryGap: true,
        axisLine: { lineStyle: { color: '#dcdfe6' } },
        axisLabel: { color: '#909399', fontSize: 11 },
        splitLine: { show: true, lineStyle: { color: '#f2f6fc', type: 'dashed' } },
      },
      {
        type: 'category',
        gridIndex: 1,
        data: dates,
        boundaryGap: true,
        axisLabel: { show: false },
      },
    ],
    yAxis: [
      {
        scale: true,
        axisLine: { lineStyle: { color: '#dcdfe6' } },
        axisLabel: { color: '#606266', fontSize: 11, formatter: (v) => v.toFixed(3) },
        splitLine: { show: true, lineStyle: { color: '#f2f6fc' } },
      },
      subYAxisConfig,
    ],
    series: [
      {
        name: '日K',
        type: 'candlestick',
        data: values,
        itemStyle: {
          color: '#f56c6c',
          color0: '#67c23a',
          borderColor: '#f56c6c',
          borderColor0: '#67c23a',
        },
        markLine: {
          silent: false,
          symbol: ['none', 'none'],
          data: markLineData,
        },
        markPoint: {
          data: markPointData,
        },
      },
      ...mainSeries,
      ...subSeries,
    ],
  }

  chart.setOption(option, true)
}
</script>

<style scoped>
.stock-chart-dialog :deep(.el-dialog__body) {
  padding: 12px 20px 16px 20px;
}

.quote-header-card {
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  padding: 12px 16px;
  margin-bottom: 12px;
}

.header-main-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 16px;
}

.stock-title-box {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.stock-name {
  font-size: 20px;
  font-weight: bold;
  color: #1e293b;
}

.stock-code {
  font-size: 15px;
  font-weight: 600;
  color: #64748b;
}

.market-tag {
  font-size: 11px;
  padding: 0 4px;
}

.latest-price-box {
  display: flex;
  align-items: baseline;
  gap: 10px;
}

.price-val {
  font-size: 26px;
  font-weight: bold;
  letter-spacing: -0.5px;
}

.price-diff {
  font-size: 14px;
  font-weight: 600;
}

.quote-meta-grid {
  display: flex;
  gap: 14px;
  flex-wrap: wrap;
}

.meta-item {
  display: flex;
  flex-direction: column;
  align-items: center;
}

.meta-label {
  font-size: 11px;
  color: #94a3b8;
}

.meta-val {
  font-size: 13px;
  font-weight: 600;
  color: #334155;
  margin-top: 1px;
}

/* 持仓生命线横幅 */
.position-lifeline-bar {
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px dashed #cbd5e1;
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
  font-size: 12px;
}

.lifeline-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-weight: bold;
  color: #0f172a;
}

.pulse-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #3b82f6;
  box-shadow: 0 0 6px #3b82f6;
  display: inline-block;
}

.lifeline-items {
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
  color: #475569;
}

.cost-blue {
  color: #2563eb;
  font-size: 13px;
}

.t-yellow {
  color: #d97706;
  font-size: 13px;
}

.sub-tip {
  font-size: 11px;
  color: #94a3b8;
  font-weight: normal;
  margin-left: 2px;
}

/* 图表控制栏 */
.chart-controls-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.controls-right {
  display: flex;
  align-items: center;
  gap: 14px;
}

.ma-legends {
  display: flex;
  gap: 10px;
  font-size: 11px;
}

.ma-legend {
  display: flex;
  align-items: center;
  gap: 4px;
  font-weight: 500;
}

.legend-color-box {
  width: 10px;
  height: 3px;
  border-radius: 1px;
  display: inline-block;
}

.ma5 { color: #e6a23c; }
.ma5-bg { background: #e6a23c; }
.ma10 { color: #8e44ad; }
.ma10-bg { background: #8e44ad; }
.ma20 { color: #27ae60; }
.ma20-bg { background: #27ae60; }
.ma60 { color: #2980b9; }
.ma60-bg { background: #2980b9; }

/* 画布容器 */
.chart-canvas-wrapper {
  position: relative;
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  height: 530px;
  width: 100%;
}

.chart-dom {
  width: 100%;
  height: 100%;
}

.no-data-mask {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(255, 255, 255, 0.9);
}

/* 底部图例与提示 */
.chart-footer-legend {
  margin-top: 12px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  font-size: 12px;
  color: #64748b;
  background: #f8fafc;
  padding: 8px 16px;
  border-radius: 6px;
  border: 1px solid #e2e8f0;
}

.legend-chips {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
}

.legend-chip {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #475569;
}

.chip-line {
  width: 16px;
  height: 2px;
  display: inline-block;
  border-radius: 1px;
}

.line-blue { background: #3388ff; }
.line-yellow { background: #e6a23c; }
.line-dashed-gray {
  border-top: 2px dashed #909399;
  height: 0;
}
.line-cost {
  background: #2563eb;
  height: 3px;
}
.line-t {
  border-top: 2px dashed #d97706;
  height: 0;
}

.cost-chip {
  color: #2563eb;
  font-weight: 600;
}

.t-chip {
  color: #d97706;
  font-weight: 600;
}

.legend-kline-tips {
  display: flex;
  align-items: center;
  gap: 12px;
}

.marker-chip {
  font-weight: 600;
  display: flex;
  align-items: center;
  gap: 4px;
}

.marker-chip.buy { color: #16a34a; }
.marker-chip.sell { color: #dc2626; }

.marker-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  display: inline-block;
}

.buy-dot { background: #16a34a; }
.sell-dot { background: #dc2626; }

.tip-text {
  color: #94a3b8;
  font-size: 11px;
}

.up-color {
  color: #ef4444 !important;
}

.down-color {
  color: #22c55e !important;
}

.num-font {
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
}

/* ============================================================= */
/* 次日 Pivot Points 轴心点系统网格样式                           */
/* ============================================================= */
.pivot-grid-card {
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  margin-bottom: 12px;
  overflow: hidden;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.04);
}

.pivot-header-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 14px;
  background: #f1f5f9;
  cursor: pointer;
  user-select: none;
  border-bottom: 1px solid #e2e8f0;
  transition: background-color 0.15s ease;
}

.pivot-header-row:hover {
  background: #e2e8f0;
}

.pivot-title-left {
  display: flex;
  align-items: center;
  gap: 8px;
}

.pivot-icon {
  font-size: 15px;
}

.pivot-title-text {
  font-size: 13px;
  font-weight: 700;
  color: #1e293b;
}

.pivot-tag {
  font-size: 11px;
  font-weight: 600;
  padding: 0 6px;
}

.pivot-date-tip {
  font-size: 11px;
  color: #64748b;
  margin-left: 4px;
}

.pivot-collapse-btn {
  font-size: 12px;
  color: #64748b;
  display: flex;
  align-items: center;
  gap: 4px;
}

.collapse-arrow {
  font-size: 12px;
}

.pivot-body {
  padding: 10px 14px 12px;
  background: #ffffff;
}

.pivot-levels-grid {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 10px;
  margin-bottom: 10px;
}

.pivot-level-item {
  border-radius: 6px;
  padding: 8px 10px;
  text-align: center;
  border: 1px solid #e2e8f0;
  transition: transform 0.15s ease;
}

.pivot-level-item:hover {
  transform: translateY(-1px);
}

.level-badge {
  font-size: 11px;
  font-weight: 700;
  margin-bottom: 4px;
}

.level-price {
  font-size: 16px;
  font-weight: 800;
  margin-bottom: 2px;
}

.level-sub {
  font-size: 10px;
  color: #64748b;
}

/* 5 档点位专属视觉配色 */
.s2-box {
  background: #fef2f2;
  border-color: #fecaca;
  color: #991b1b;
}
.s2-box .level-price { color: #dc2626; }

.s1-box {
  background: #f0fdf4;
  border-color: #bbf7d0;
  color: #166534;
}
.s1-box .level-price { color: #16a34a; }

.p-box {
  background: #eff6ff;
  border-color: #bfdbfe;
  color: #1e40af;
}
.p-box .level-price { color: #2563eb; }

.r1-box {
  background: #fffbeb;
  border-color: #fde68a;
  color: #92400e;
}
.r1-box .level-price { color: #d97706; }

.r2-box {
  background: #faf5ff;
  border-color: #e9d5ff;
  color: #6b21a8;
}
.r2-box .level-price { color: #9333ea; }

.pivot-tip-bar {
  background: #f8fafc;
  border: 1px dashed #cbd5e1;
  border-radius: 6px;
  padding: 6px 12px;
  font-size: 12px;
  display: flex;
  align-items: center;
  gap: 6px;
}

.tip-bulb {
  font-weight: 700;
  color: #d97706;
  white-space: nowrap;
}

.tip-content {
  color: #475569;
  line-height: 1.5;
}

/* ============================================================= */
/* 控制栏与量化指标切换器                                          */
/* ============================================================= */
.chart-controls-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 12px;
}

.controls-left {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.controls-right {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.indicator-toggle-box {
  display: flex;
  align-items: center;
  gap: 6px;
}

.indicator-label {
  font-size: 12px;
  font-weight: 600;
  color: #64748b;
}

.kline-indicator-legends {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 11px;
}

.boll-upper { color: #8b5cf6; }
.boll-upper-bg { background: #8b5cf6; }
.boll-mid { color: #f59e0b; }
.boll-mid-bg { background: #f59e0b; }
.boll-lower { color: #06b6d4; }
.boll-lower-bg { background: #06b6d4; }

.line-red-green {
  background: linear-gradient(to right, #f56c6c 50%, #67c23a 50%);
}
.line-purple { background: #8e44ad; }
.line-pink { background: #ec4899; }

/* 🔮 AI 次日走势推演按钮 */
.ai-predict-btn {
  background: linear-gradient(135deg, #4f46e5 0%, #7c3aed 50%, #db2777 100%) !important;
  border: none !important;
  color: #ffffff !important;
  font-weight: 600;
  box-shadow: 0 2px 8px rgba(124, 58, 237, 0.35);
  transition: all 0.2s ease;
}

.ai-predict-btn:hover {
  opacity: 0.92;
  transform: translateY(-1px);
  box-shadow: 0 4px 12px rgba(124, 58, 237, 0.45);
}

/* ============================================================= */
/* 🔮 AI 次日走势推演弹窗样式                                      */
/* ============================================================= */
.ai-predict-dialog :deep(.el-dialog__body) {
  padding: 14px 20px 18px;
  max-height: 72vh;
  overflow-y: auto;
}

.ai-predict-body {
  min-height: 220px;
}

/* 顶部趋势定调横幅 */
.verdict-banner {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 18px;
  border-radius: 8px;
  margin-bottom: 12px;
}

.banner-bullish {
  background: linear-gradient(135deg, #f0fdf4 0%, #dcfce7 100%);
  border: 1px solid #86efac;
}

.banner-bearish {
  background: linear-gradient(135deg, #fef2f2 0%, #fee2e2 100%);
  border: 1px solid #fca5a5;
}

.banner-neutral {
  background: linear-gradient(135deg, #f8fafc 0%, #f1f5f9 100%);
  border: 1px solid #cbd5e1;
}

.verdict-tag-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}

.verdict-title {
  font-size: 14px;
  font-weight: 700;
  color: #1e293b;
}

.verdict-badge {
  font-size: 14px;
  font-weight: bold;
  padding: 2px 10px;
  border-radius: 4px;
}

.verdict-meta {
  font-size: 11px;
  color: #64748b;
  display: flex;
  gap: 12px;
}

.verdict-prob-box {
  text-align: right;
}

.prob-num {
  font-size: 28px;
  font-weight: 800;
  color: #2563eb;
  line-height: 1;
}

.prob-label {
  font-size: 11px;
  font-weight: 600;
  color: #64748b;
  margin-top: 3px;
}

/* 持仓生命线横向数据卡 */
.predict-position-card {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 10px;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  padding: 10px 14px;
  margin-bottom: 14px;
}

.card-col {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
}

.card-col .col-label {
  font-size: 11px;
  color: #64748b;
  margin-bottom: 2px;
}

.card-col .col-val {
  font-size: 14px;
  font-weight: 700;
  color: #1e293b;
}

.highlight-col {
  background: #fef3c7;
  border-radius: 4px;
  padding: 2px 4px;
}

/* Markdown 推演报告排版 */
.predict-report-content {
  line-height: 1.7;
  color: #334155;
  font-size: 13px;
}

.predict-report-content :deep(.md-h2) {
  font-size: 16px;
  font-weight: 800;
  color: #0f172a;
  margin: 14px 0 8px;
  padding-bottom: 4px;
  border-bottom: 2px solid #e2e8f0;
}

.predict-report-content :deep(.md-h3) {
  font-size: 15px;
  font-weight: 700;
  color: #1e293b;
  margin: 12px 0 6px;
  padding-bottom: 3px;
  border-bottom: 1px solid #f1f5f9;
}

.predict-report-content :deep(.md-h4) {
  font-size: 14px;
  font-weight: 700;
  color: #2563eb;
  margin: 10px 0 4px;
}

.predict-report-content :deep(.md-code) {
  background: #f1f5f9;
  color: #db2777;
  padding: 2px 6px;
  border-radius: 4px;
  font-family: monospace;
  font-size: 12px;
}

.predict-report-content :deep(.md-quote) {
  border-left: 3px solid #3b82f6;
  background: #eff6ff;
  padding: 6px 12px;
  margin: 8px 0;
  border-radius: 0 4px 4px 0;
  color: #1e40af;
}

.predict-report-content :deep(.md-li) {
  margin-left: 18px;
  margin-bottom: 4px;
}

.predict-report-content :deep(strong) {
  color: #0f172a;
  font-weight: 700;
}

.predict-dialog-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  width: 100%;
}

.footer-tip {
  font-size: 12px;
  color: #64748b;
}

.footer-btns {
  display: flex;
  gap: 10px;
}

.benchmark-pill-tag {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  background: #f0f7ff;
  border: 1px solid #d0e7ff;
  border-radius: 6px;
  padding: 2px 8px;
  font-size: 12px;
  color: #606266;
  margin-left: 10px;
}

.benchmark-pill-tag .bm-label {
  color: #909399;
}

.benchmark-pill-tag .bm-name {
  font-weight: bold;
  color: #303133;
}

.benchmark-pill-tag .bm-divider {
  color: #dcdfe6;
  margin: 0 2px;
}

.benchmark-pill-tag .bm-tag {
  font-weight: bold;
}
</style>


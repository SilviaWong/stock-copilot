<template>
  <el-dialog
    v-model="visible"
    :title="dialogTitle"
    width="1060px"
    top="3vh"
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

    <!-- 图表类型切换控制栏 -->
    <div class="chart-controls-bar">
      <el-radio-group v-model="activeChartTab" size="default" @change="handleTabChange">
        <el-radio-button label="minute">1日分时</el-radio-button>
        <el-radio-button label="fiveDay">5日分时</el-radio-button>
        <el-radio-button label="kline">日K线系统</el-radio-button>
      </el-radio-group>

      <div class="controls-right">
        <!-- K线模式下显示 MA 图例提示 -->
        <div v-if="activeChartTab === 'kline'" class="ma-legends">
          <span class="ma-legend ma5"><i class="legend-color-box ma5-bg"></i>MA5</span>
          <span class="ma-legend ma10"><i class="legend-color-box ma10-bg"></i>MA10</span>
          <span class="ma-legend ma20"><i class="legend-color-box ma20-bg"></i>MA20</span>
          <span class="ma-legend ma60"><i class="legend-color-box ma60-bg"></i>MA60</span>
        </div>

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

    <!-- 底部图例说明提示 -->
    <div class="chart-footer-legend">
      <div class="legend-group">
        <span class="legend-pill" style="border-left: 3px solid #3388ff;">分时走势线</span>
        <span class="legend-pill" style="border-left: 3px solid #e6a23c;">分时均价黄线</span>
        <span class="legend-pill" style="border-left: 3px dashed #909399;">昨收中轴线</span>
        <span v-if="positionInfo.costPrice" class="legend-pill" style="border-left: 3px solid #409eff; font-weight: bold; color: #409eff;">
          🟦 买入均价线 (¥{{ Number(positionInfo.costPrice).toFixed(3) }})
        </span>
        <span v-if="positionInfo.dilutedCostPrice" class="legend-pill" style="border-left: 3px solid #e6a23c; font-weight: bold; color: #e6a23c;">
          🟧 做T保本线 (¥{{ Number(positionInfo.dilutedCostPrice).toFixed(3) }})
        </span>
      </div>
      <div v-if="activeChartTab === 'kline'" class="legend-group">
        <span class="marker-pill buy">🟢 B 买入打点</span>
        <span class="marker-pill sell">🔴 S 卖出打点</span>
        <span class="tip-text">提示：支持滚轮缩放、拖拽平移，鼠标悬停打点可查阅当时买卖金额与复盘</span>
      </div>
    </div>
  </el-dialog>
</template>

<script setup>
import { ref, computed, nextTick, onBeforeUnmount, shallowRef } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { getMinuteChart, getFiveDayChart, getKlineChart } from '../api'

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
  let maxDiff = preClose * 0.015
  prices.forEach(p => {
    const d = Math.abs(p - preClose)
    if (d > maxDiff) maxDiff = d
  })
  if (costPrice) {
    const cd = Math.abs(costPrice - preClose)
    if (cd > maxDiff && cd < preClose * 0.3) maxDiff = cd
  }
  if (dilutedCostPrice) {
    const dd = Math.abs(dilutedCostPrice - preClose)
    if (dd > maxDiff && dd < preClose * 0.3) maxDiff = dd
  }
  maxDiff = maxDiff * 1.05

  const yMin = Number((preClose - maxDiff).toFixed(3))
  const yMax = Number((preClose + maxDiff).toFixed(3))
  const maxPercent = Number((maxDiff / preClose * 100).toFixed(2))

  // 标的持仓生命线 MarkLines
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
        color: '#909399',
      },
    },
  ]
  if (costPrice && costPrice > 0) {
    markLineData.push({
      name: '买入均价线',
      yAxis: costPrice,
      lineStyle: { color: '#409eff', type: 'solid', width: 1.8 },
      label: {
        show: true,
        position: 'end',
        formatter: `买入均价: ¥${costPrice.toFixed(3)}`,
        fontSize: 11,
        color: '#409eff',
      },
    })
  }
  if (dilutedCostPrice && dilutedCostPrice > 0) {
    markLineData.push({
      name: '做T保本线',
      yAxis: dilutedCostPrice,
      lineStyle: { color: '#e6a23c', type: 'dashed', width: 1.8 },
      label: {
        show: true,
        position: 'insideEndBottom',
        formatter: `做T保本: ¥${dilutedCostPrice.toFixed(3)}`,
        fontSize: 11,
        color: '#e6a23c',
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
      { left: '60px', right: '65px', top: '10%', height: '60%' },
      { left: '60px', right: '65px', top: '75%', height: '18%' },
    ],
    xAxis: [
      {
        type: 'category',
        data: data.times,
        boundaryGap: false,
        axisLine: { lineStyle: { color: '#dcdfe6' } },
        axisLabel: { color: '#909399', fontSize: 11 },
        splitLine: { show: true, lineStyle: { color: '#f2f6fc', type: 'dashed' } },
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
        splitLine: { show: true, lineStyle: { color: '#f2f6fc' } },
      },
      {
        type: 'value',
        min: -maxPercent,
        max: maxPercent,
        interval: maxPercent,
        axisLine: { lineStyle: { color: '#dcdfe6' } },
        axisLabel: {
          color: '#909399',
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
          color: '#909399',
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
            { offset: 0, color: 'rgba(51, 136, 255, 0.25)' },
            { offset: 1, color: 'rgba(51, 136, 255, 0.02)' },
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

  // 对称 Y 轴计算
  let maxDiff = basePrice * 0.02
  prices.forEach(p => {
    const d = Math.abs(p - basePrice)
    if (d > maxDiff) maxDiff = d
  })
  if (costPrice) {
    const cd = Math.abs(costPrice - basePrice)
    if (cd > maxDiff && cd < basePrice * 0.4) maxDiff = cd
  }
  if (dilutedCostPrice) {
    const dd = Math.abs(dilutedCostPrice - basePrice)
    if (dd > maxDiff && dd < basePrice * 0.4) maxDiff = dd
  }
  maxDiff = maxDiff * 1.05

  const yMin = Number((basePrice - maxDiff).toFixed(3))
  const yMax = Number((basePrice + maxDiff).toFixed(3))

  // 垂直分割线 (5天开盘点)
  const splitMarkLines = []
  if (data.splitIndexes && data.splitIndexes.length > 0) {
    data.splitIndexes.forEach((idx, dayI) => {
      if (idx > 0 && idx < data.times.length) {
        splitMarkLines.push({
          xAxis: data.times[idx],
          lineStyle: { color: '#c0c4cc', type: 'dashed', width: 1 },
          label: {
            show: true,
            position: 'start',
            formatter: data.dayLabels && data.dayLabels[dayI] ? data.dayLabels[dayI] : '',
            fontSize: 11,
            color: '#606266',
          },
        })
      }
    })
  }

  // 持仓生命线
  if (costPrice && costPrice > 0) {
    splitMarkLines.push({
      yAxis: costPrice,
      lineStyle: { color: '#409eff', type: 'solid', width: 1.8 },
      label: {
        show: true,
        position: 'end',
        formatter: `买入均价: ¥${costPrice.toFixed(3)}`,
        fontSize: 11,
        color: '#409eff',
      },
    })
  }
  if (dilutedCostPrice && dilutedCostPrice > 0) {
    splitMarkLines.push({
      yAxis: dilutedCostPrice,
      lineStyle: { color: '#e6a23c', type: 'dashed', width: 1.8 },
      label: {
        show: true,
        position: 'insideEndBottom',
        formatter: `做T保本: ¥${dilutedCostPrice.toFixed(3)}`,
        fontSize: 11,
        color: '#e6a23c',
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
      { left: '60px', right: '60px', top: '10%', height: '60%' },
      { left: '60px', right: '60px', top: '75%', height: '18%' },
    ],
    xAxis: [
      {
        type: 'category',
        data: data.times,
        boundaryGap: false,
        axisLine: { lineStyle: { color: '#dcdfe6' } },
        axisLabel: {
          color: '#909399',
          fontSize: 10,
          interval: Math.floor(data.times.length / 5) || 'auto',
        },
      },
      {
        type: 'category',
        gridIndex: 1,
        data: data.times,
        boundaryGap: false,
        axisLabel: { show: false },
      },
    ],
    yAxis: [
      {
        type: 'value',
        min: yMin,
        max: yMax,
        axisLine: { lineStyle: { color: '#dcdfe6' } },
        axisLabel: { formatter: (v) => v.toFixed(3) },
        splitLine: { show: true, lineStyle: { color: '#f2f6fc' } },
      },
      {
        type: 'value',
        gridIndex: 1,
        axisLabel: { formatter: (v) => formatVolume(v), fontSize: 10 },
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
// 3. 绘制日 K 线图 (蜡烛图 + MA均线 + 历史买卖打点图钉)
// -------------------------------------------------------------
function renderKlineChart(data) {
  if (!data || !data.dates || data.dates.length === 0) {
    noData.value = true
    return
  }

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

  // 持仓生命线 markLine
  const markLineData = []
  if (costPrice && costPrice > 0) {
    markLineData.push({
      name: '持仓买入均价线',
      yAxis: costPrice,
      lineStyle: { color: '#409eff', type: 'solid', width: 2 },
      label: {
        show: true,
        position: 'end',
        formatter: `买入均价: ¥${costPrice.toFixed(3)}`,
        fontSize: 11,
        color: '#409eff',
      },
    })
  }
  if (dilutedCostPrice && dilutedCostPrice > 0) {
    markLineData.push({
      name: '做T摊薄保本线',
      yAxis: dilutedCostPrice,
      lineStyle: { color: '#e6a23c', type: 'dashed', width: 2 },
      label: {
        show: true,
        position: 'insideEndBottom',
        formatter: `做T保本: ¥${dilutedCostPrice.toFixed(3)}`,
        fontSize: 11,
        color: '#e6a23c',
      },
    })
  }

  // 历史买卖图钉打点 markPoint (B / S)
  const markPointData = []
  if (data.tradeMarkers && data.tradeMarkers.length > 0) {
    data.tradeMarkers.forEach(m => {
      const isBuy = m.action === 'BUY'
      // 在 dates 数组中匹配日期
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
        let ma5 = null
        let ma10 = null
        let ma20 = null
        let ma60 = null
        let vol = null

        params.forEach(p => {
          if (p.seriesName === '日K') kItem = p.value // [open, close, low, high]
          if (p.seriesName === 'MA5') ma5 = p.value
          if (p.seriesName === 'MA10') ma10 = p.value
          if (p.seriesName === 'MA20') ma20 = p.value
          if (p.seriesName === 'MA60') ma60 = p.value
          if (p.seriesName === '成交量') vol = p.value
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
          dayTrades.forEach((t, i) => {
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

        html += `<div style="margin-top: 4px; font-size: 11px; color: #909399;">`
        if (ma5 !== null && ma5 !== undefined) html += `<span style="color: #e6a23c; margin-right: 8px;">MA5: ${Number(ma5).toFixed(3)}</span>`
        if (ma10 !== null && ma10 !== undefined) html += `<span style="color: #8e44ad; margin-right: 8px;">MA10: ${Number(ma10).toFixed(3)}</span>`
        if (ma20 !== null && ma20 !== undefined) html += `<span style="color: #27ae60; margin-right: 8px;">MA20: ${Number(ma20).toFixed(3)}</span>`
        if (ma60 !== null && ma60 !== undefined) html += `<span style="color: #2980b9;">MA60: ${Number(ma60).toFixed(3)}</span>`
        html += `</div>`

        if (vol !== null) {
          html += `<div style="font-size: 11px; color: #606266; margin-top: 2px;">成交量: ${formatVolume(vol)}</div>`
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
      { left: '60px', right: '65px', top: '8%', height: '62%' },
      { left: '60px', right: '65px', top: '74%', height: '16%' },
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
      {
        scale: true,
        gridIndex: 1,
        axisLabel: { formatter: (v) => formatVolume(v), fontSize: 10 },
        splitLine: { show: false },
      },
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
      {
        name: 'MA5',
        type: 'line',
        data: data.ma5,
        smooth: true,
        showSymbol: false,
        lineStyle: { color: '#e6a23c', width: 1.2 },
      },
      {
        name: 'MA10',
        type: 'line',
        data: data.ma10,
        smooth: true,
        showSymbol: false,
        lineStyle: { color: '#8e44ad', width: 1.2 },
      },
      {
        name: 'MA20',
        type: 'line',
        data: data.ma20,
        smooth: true,
        showSymbol: false,
        lineStyle: { color: '#27ae60', width: 1.2 },
      },
      {
        name: 'MA60',
        type: 'line',
        data: data.ma60,
        smooth: true,
        showSymbol: false,
        lineStyle: { color: '#2980b9', width: 1.2 },
      },
      {
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
      },
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
  height: 500px;
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
  margin-top: 10px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  font-size: 11px;
  color: #64748b;
}

.legend-group {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.legend-pill {
  padding-left: 6px;
}

.marker-pill {
  font-weight: 600;
}

.marker-pill.buy {
  color: #16a34a;
}

.marker-pill.sell {
  color: #dc2626;
}

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
</style>

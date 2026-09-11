import axios from 'axios'
import { ElMessage } from 'element-plus'

const instance = axios.create({
  baseURL: '/api',
  timeout: 10000,
})

// 响应拦截器
instance.interceptors.response.use(
  (response) => {
    const res = response.data
    if (res.code === 200) {
      return res.data
    } else {
      ElMessage.error(res.message || '请求处理失败')
      return Promise.reject(new Error(res.message || 'Error'))
    }
  },
  (error) => {
    console.error('API Error:', error)
    const errorMsg = error.response?.data?.message || error.message || '网络连接异常'
    ElMessage.error(errorMsg)
    return Promise.reject(error)
  }
)

/**
 * 获取账户资产统计总览
 */
export function getAccountSummary() {
  return instance.get('/position/summary')
}

/**
 * 获取持仓列表
 * @param {boolean} onlyHolding 是否只看持仓中
 */
export function getPositionList(onlyHolding = true) {
  return instance.get('/position/list', {
    params: { onlyHolding },
  })
}

/**
 * 录入一笔交易记录
 */
export function recordTrade(data) {
  return instance.post('/trade/record', data)
}

/**
 * 获取交易流水历史
 * @param {string} symbol 标的代码(可选)
 */
export function getTradeHistory(symbol) {
  return instance.get('/trade/history', {
    params: { symbol },
  })
}

/**
 * 撤销/删除单笔流水并全量重算持仓
 * @param {number} id 流水ID
 */
export function deleteTrade(id) {
  return instance.delete(`/trade/${id}`)
}

/**
 * 更新目标止盈与止损线
 */
export function updateTargetPrices(symbol, targetTakeProfit, targetStopLoss) {
  return instance.put('/position/targets', {
    symbol,
    targetTakeProfit,
    targetStopLoss,
  })
}

/**
 * 搜索或查询标的
 */
export function searchStocks(keyword) {
  return instance.get('/stock/search', {
    params: { keyword },
  })
}

/**
 * 获取标的实时行情
 */
export function getQuoteDetail(symbol) {
  return instance.get('/quote/detail', {
    params: { symbol },
  })
}

/**
 * 直接录入已有底仓或就地调整持仓
 */
export function initHolding(data) {
  return instance.post('/position/init', data)
}

/**
 * 修改单笔交易流水
 */
export function updateTrade(id, data) {
  return instance.put(`/trade/${id}`, data)
}

/**
 * 删除某个标的的所有持仓与交易流水
 */
export function deletePosition(symbol) {
  return instance.delete(`/position/${symbol}`)
}

/**
 * 一键重置清空全部数据
 */
export function resetAllData() {
  return instance.post('/position/reset')
}

/**
 * 测试大模型代理配置连通性
 */
export function testAiConnection(config) {
  return instance.post('/ocr/test-connection', config, { timeout: 20000 })
}

/**
 * 上传截图并调用多模态视觉大模型解析交易列表 (设置90秒超时)
 */
export function parseScreenshotTrades(payload) {
  return instance.post('/ocr/parse-trades', payload, { timeout: 90000 })
}

/**
 * 粘贴券商文本解析交易流水
 */
export function parseTextTrades(rawText) {
  return instance.post('/ocr/parse-text', rawText, {
    headers: { 'Content-Type': 'text/plain' },
  })
}

/**
 * 批量入库已核对的交易流水
 */
export function batchRecordTrades(trades) {
  return instance.post('/trade/batch', trades)
}

/**
 * 与 AI 投资副驾交互对话
 */
export function chatWithCopilot(payload) {
  return instance.post('/copilot/chat', payload, { timeout: 60000 })
}

/**
 * 一键全盘持仓与风险诊断
 */
export function diagnoseAccount(config) {
  return instance.post('/copilot/diagnose', config, { timeout: 60000 })
}

/**
 * 获取标的 1 日分时图数据
 */
export function getMinuteChart(symbol) {
  return instance.get('/quote/minute', {
    params: { symbol },
  })
}

/**
 * 获取标的 5 日分时走势数据
 */
export function getFiveDayChart(symbol) {
  return instance.get('/quote/five-day', {
    params: { symbol },
  })
}

/**
 * 获取标的日 K 线走势数据 (含蜡烛图、均线与买卖打点)
 */
export function getKlineChart(symbol) {
  return instance.get('/quote/kline', {
    params: { symbol },
  })
}

/**
 * AI 次日走势推演 (结合量化指标、Pivot Points 与持仓生命线)
 */
export function predictNextDayTrend(payload) {
  return instance.post('/copilot/predict', payload, { timeout: 60000 })
}

/**
 * 获取全市场宏观大盘指数全景与情绪晴雨表
 */
export function getMarketOverview() {
  return instance.get('/quote/market-overview')
}



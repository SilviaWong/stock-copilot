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


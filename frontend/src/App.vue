<template>
  <div class="app-container">
    <!-- 顶部导航栏 -->
    <header class="app-header">
      <div class="header-left">
        <div class="logo-icon">📈</div>
        <div>
          <h1 class="app-title">Stock Copilot 投资助手</h1>
          <p class="app-subtitle">A股 / 场内ETF · 真实持仓建档 · 实时行情 · 成本摊薄 · 辅助决策</p>
        </div>
      </div>
      <div class="header-right">
        <div class="auto-refresh-wrap">
          <span style="font-size: 13px; color: #909399; margin-right: 8px;">自动刷新 (30s):</span>
          <el-switch v-model="autoRefresh" @change="toggleAutoRefresh" />
        </div>
        <el-button :icon="Refresh" circle :loading="refreshing" @click="loadAllData" title="刷新最新行情与资产" />
        
        <!-- AI 投资副驾 (智能投顾 & 做T决策) -->
        <el-button type="primary" size="large" @click="openCopilotDrawer()">
          🤖 AI 投资副驾
        </el-button>

        <!-- 截图导入流水 (多模态视觉识别) -->
        <el-button type="warning" size="large" :icon="Camera" @click="openScreenshotDialog()">
          📷 截图导入流水
        </el-button>

        <!-- 录入已有持仓底仓 (最省事) -->
        <el-button type="success" size="large" :icon="DocumentAdd" @click="openInitHoldingDialog()">
          录入已有底仓
        </el-button>

        <!-- 记一笔买卖流水 -->
        <el-button type="primary" size="large" :icon="Plus" @click="openAddTradeDialog()">
          记一笔交易
        </el-button>

        <!-- 危险操作：清空重置 -->
        <el-popconfirm
          title="确定要清空全部交易记录和持仓数据吗？清空后不可恢复！"
          confirm-button-text="确定清空"
          cancel-button-text="取消"
          confirm-button-type="danger"
          @confirm="handleResetAll"
        >
          <template #reference>
            <el-button type="danger" plain circle :icon="Delete" title="清空重置所有数据" />
          </template>
        </el-popconfirm>
      </div>
    </header>

    <!-- 账户资产统计看板卡片 (实时市值与浮盈) -->
    <div class="summary-cards">
      <el-card class="stat-card" shadow="hover">
        <div class="stat-label">当前持仓总市值</div>
        <div class="stat-value num-font" style="color: #303133;">
          ¥ {{ summary.totalMarketValue ? Number(summary.totalMarketValue).toFixed(2) : '0.00' }}
        </div>
        <div class="stat-sub">
          投入本金: ¥ {{ summary.totalHoldCost ? Number(summary.totalHoldCost).toFixed(2) : '0.00' }}
        </div>
      </el-card>

      <el-card class="stat-card" shadow="hover">
        <div class="stat-label">持仓浮动盈亏 (未实现)</div>
        <div
          class="stat-value num-font"
          :class="getAmountColorClass(summary.totalFloatingPnl)"
        >
          {{ formatPnl(summary.totalFloatingPnl) }}
          <span v-if="summary.totalFloatingPnlRate" style="font-size: 16px; font-weight: normal; margin-left: 4px;">
            ({{ formatRate(summary.totalFloatingPnlRate) }})
          </span>
        </div>
        <div class="stat-sub">
          今日持仓波动:
          <span :class="getAmountColorClass(summary.totalDailyPnl)">
            {{ formatPnl(summary.totalDailyPnl) }}
          </span>
        </div>
      </el-card>

      <el-card class="stat-card" shadow="hover">
        <div class="stat-label">累计已落袋盈利 (已实现净盈亏)</div>
        <div
          class="stat-value num-font"
          :class="getAmountColorClass(summary.totalRealizedPnl)"
        >
          {{ formatPnl(summary.totalRealizedPnl) }}
        </div>
        <div class="stat-sub">
          清仓/减仓 {{ summary.totalSells || 0 }} 笔，其中盈利 {{ summary.profitableSells || 0 }} 笔
        </div>
        <div v-if="summary.totalNetProfit !== undefined" class="stat-sub" style="margin-top: 4px; border-top: 1px dashed #ebeef5; padding-top: 4px;">
          综合总收益(含浮盈):
          <b :class="getAmountColorClass(summary.totalNetProfit)">
            {{ formatPnl(summary.totalNetProfit) }}
          </b>
        </div>
      </el-card>

      <el-card class="stat-card" shadow="hover">
        <div class="stat-label">实战操作胜率</div>
        <div class="stat-value num-font" style="color: #409eff;">
          {{ summary.winRate || '0.00' }}%
        </div>
        <div class="stat-sub">正在持有 {{ summary.holdingCount || 0 }} 支标的</div>
      </el-card>
    </div>

    <!-- 主体区域：Tab 分组 -->
    <el-card class="main-content-card" shadow="never">
      <el-tabs v-model="activeTab" class="custom-tabs">
        <!-- Tab 1: 当前持仓看板 -->
        <el-tab-pane label="📊 当前持仓看板" name="positions">
          <div class="table-toolbar">
            <div class="toolbar-left">
              <el-switch
                v-model="onlyHolding"
                active-text="仅显示当前持仓"
                inactive-text="显示全部历史标的"
                @change="loadPositions"
              />
            </div>
            <div class="toolbar-right">
              <span v-if="lastUpdateTime" style="font-size: 12px; color: #909399; margin-right: 12px;">
                行情刷新时间: {{ lastUpdateTime }}
              </span>
              <el-button size="small" :icon="Refresh" :loading="loadingPositions" @click="loadPositions">
                刷新行情
              </el-button>
            </div>
          </div>

          <el-table
            v-loading="loadingPositions"
            :data="positions"
            stripe
            border
            style="width: 100%"
            empty-text="暂无真实持仓记录，可点击上方「录入已有底仓」或「记一笔交易」快速建档"
          >
            <el-table-column prop="symbol" label="标的代码" width="120">
              <template #default="{ row }">
                <el-tag :type="row.market === 'SH' ? 'danger' : 'primary'" size="small" disable-transitions>
                  {{ row.market }}
                </el-tag>
                <span class="num-font" style="font-weight: bold; margin-left: 6px;">{{ row.symbol }}</span>
              </template>
            </el-table-column>

            <el-table-column prop="name" label="标的名称" min-width="150">
              <template #default="{ row }">
                <div style="display: flex; align-items: center; gap: 4px;">
                  <span
                    class="clickable-stock-name"
                    title="点击打开行情分时/五日/日K走势图与打点复盘"
                    @click="openChartDialog(row)"
                  >
                    {{ row.name }}
                  </span>
                  <el-icon
                    style="color: #409eff; cursor: pointer;"
                    title="查看行情分时与K线复盘"
                    @click="openChartDialog(row)"
                  >
                    <TrendCharts />
                  </el-icon>
                </div>
                <div style="margin-top: 2px;">
                  <el-tag v-if="row.takeProfitAlert" size="small" type="danger" effect="dark">
                    🎉 触及止盈价
                  </el-tag>
                  <el-tag v-if="row.stopLossAlert" size="small" type="warning" effect="dark" style="margin-left: 4px;">
                    ⚠️ 跌破止损线
                  </el-tag>
                </div>
              </template>
            </el-table-column>

            <!-- 实时现价 & 今日涨跌 -->
            <el-table-column label="实时现价" width="130" align="right">
              <template #default="{ row }">
                <div class="num-font" style="font-weight: bold; font-size: 15px;">
                  ¥ {{ Number(row.currentPrice).toFixed(3) }}
                </div>
                <div v-if="row.changePercent !== undefined && row.holdQuantity > 0" style="font-size: 12px;">
                  <span :class="row.changePercent >= 0 ? 'up-color' : 'down-color'">
                    {{ row.changePercent >= 0 ? '+' : '' }}{{ Number(row.changePercent).toFixed(2) }}%
                  </span>
                </div>
              </template>
            </el-table-column>

            <!-- 智能决策推荐信号 (网格做T / 加仓止盈) -->
            <el-table-column label="💡 决策建议 / 信号" min-width="260">
              <template #default="{ row }">
                <div v-if="row.tradeSignal && row.holdQuantity > 0">
                  <div style="display: flex; align-items: center; gap: 6px; margin-bottom: 4px; flex-wrap: wrap;">
                    <el-tag :type="row.tradeSignal.level || 'info'" size="small" effect="dark" style="font-weight: bold;">
                      {{ row.tradeSignal.title }}
                    </el-tag>
                    <el-button
                      v-if="row.tradeSignal.signalType === 'BUY'"
                      link
                      type="success"
                      size="small"
                      style="font-size: 12px; font-weight: bold;"
                      @click="applySignalToTrade(row, 'BUY')"
                    >
                      ⚡ 一键低吸
                    </el-button>
                    <el-button
                      v-else-if="row.tradeSignal.signalType === 'SELL'"
                      link
                      type="warning"
                      size="small"
                      style="font-size: 12px; font-weight: bold;"
                      @click="applySignalToTrade(row, 'SELL')"
                    >
                      ⚡ 一键高抛
                    </el-button>
                    <el-button
                      link
                      type="primary"
                      size="small"
                      style="font-size: 11px;"
                      @click="askCopilotForSymbol(row)"
                    >
                      问AI副驾 🤖
                    </el-button>
                  </div>
                  <div style="font-size: 12px; color: #606266; line-height: 1.45;">
                    {{ row.tradeSignal.description }}
                  </div>
                </div>
                <div v-else-if="row.tradeSignal">
                  <el-tag size="small" type="info">{{ row.tradeSignal.title }}</el-tag>
                  <span style="font-size: 12px; color: #909399; margin-left: 6px;">{{ row.tradeSignal.description }}</span>
                </div>
                <span v-else style="color: #c0c4cc;">-</span>
              </template>
            </el-table-column>

            <!-- 买入均价 -->
            <el-table-column prop="costPrice" width="125" align="right">
              <template #header>
                <span>买入均价</span>
                <el-tooltip content="当前持仓筹码的实际加权平均买入成本（不掺杂已落袋做T利润）" placement="top">
                  <el-icon style="margin-left: 2px; vertical-align: middle; cursor: pointer; color: #909399;"><QuestionFilled /></el-icon>
                </el-tooltip>
              </template>
              <template #default="{ row }">
                <span class="num-font" style="font-weight: bold; color: #409eff;">
                  ¥ {{ Number(row.costPrice).toFixed(4) }}
                </span>
              </template>
            </el-table-column>

            <!-- 摊薄成本价 (券商保本价) -->
            <el-table-column prop="dilutedCostPrice" width="140" align="right">
              <template #header>
                <span style="color: #e6a23c; font-weight: bold;">摊薄成本(券商)</span>
                <el-tooltip content="扣除历史做T已落袋利润后的保本成本单价，与券商App成本价口径完全一致" placement="top">
                  <el-icon style="margin-left: 2px; vertical-align: middle; cursor: pointer; color: #e6a23c;"><QuestionFilled /></el-icon>
                </el-tooltip>
              </template>
              <template #default="{ row }">
                <div v-if="row.holdQuantity > 0">
                  <span class="num-font" style="font-weight: bold; color: #e6a23c; font-size: 14px;">
                    ¥ {{ row.dilutedCostPrice !== undefined && row.dilutedCostPrice !== null ? Number(row.dilutedCostPrice).toFixed(3) : Number(row.costPrice).toFixed(3) }}
                  </span>
                  <div style="font-size: 11px; color: #909399;">保本底线</div>
                </div>
                <span v-else style="color: #c0c4cc;">-</span>
              </template>
            </el-table-column>

            <el-table-column prop="holdQuantity" label="持仓数量" width="110" align="right">
              <template #default="{ row }">
                <span class="num-font" :style="{ color: row.holdQuantity > 0 ? '#303133' : '#909399', fontWeight: 'bold' }">
                  {{ row.holdQuantity }}
                </span>
                <span style="font-size: 12px; color: #909399; margin-left: 2px;">{{ row.holdQuantity === 0 ? '(已清)' : '股' }}</span>
              </template>
            </el-table-column>

            <!-- 持仓市值 -->
            <el-table-column label="当前市值" width="125" align="right">
              <template #default="{ row }">
                <span class="num-font" style="font-weight: 600;">
                  ¥ {{ Number(row.marketValue).toFixed(2) }}
                </span>
              </template>
            </el-table-column>

            <!-- 实时持仓浮动盈亏 -->
            <el-table-column label="持仓浮盈(率)" width="135" align="right">
              <template #header>
                <span>持仓浮盈(率)</span>
                <el-tooltip content="仅计算当前仍持有的筹码相比买入均价的未实现浮动盈亏" placement="top">
                  <el-icon style="margin-left: 2px; vertical-align: middle; cursor: pointer; color: #909399;"><QuestionFilled /></el-icon>
                </el-tooltip>
              </template>
              <template #default="{ row }">
                <div v-if="row.holdQuantity > 0">
                  <div class="num-font" :class="getAmountColorClass(row.floatingPnl)" style="font-size: 13px; font-weight: bold;">
                    {{ formatPnl(row.floatingPnl) }}
                  </div>
                  <div style="font-size: 12px;" :class="getAmountColorClass(row.floatingPnlRate)">
                    {{ formatRate(row.floatingPnlRate) }}
                  </div>
                </div>
                <span v-else style="color: #c0c4cc;">-</span>
              </template>
            </el-table-column>

            <!-- 标的累计总盈亏 (含做T落袋，与券商App大红字一致) -->
            <el-table-column label="累计总盈亏(券商)" width="150" align="right">
              <template #header>
                <span style="color: #e6a23c; font-weight: bold;">累计总盈亏(券商)</span>
                <el-tooltip content="标的当前市值 - 摊薄总成本（包含历史做T已落袋盈利+持仓浮盈），与券商App显示的标的总盈亏完全一致" placement="top">
                  <el-icon style="margin-left: 2px; vertical-align: middle; cursor: pointer; color: #e6a23c;"><QuestionFilled /></el-icon>
                </el-tooltip>
              </template>
              <template #default="{ row }">
                <div class="num-font" :class="getAmountColorClass(row.totalPnl)" style="font-size: 14px; font-weight: bold;">
                  {{ formatPnl(row.totalPnl) }}
                </div>
                <div v-if="row.totalPnlRate" style="font-size: 12px;" :class="getAmountColorClass(row.totalPnlRate)">
                  {{ formatRate(row.totalPnlRate) }}
                </div>
              </template>
            </el-table-column>

            <el-table-column prop="totalCost" label="投入成本" width="120" align="right">
              <template #default="{ row }">
                <span class="num-font" style="color: #606266;">¥ {{ Number(row.totalCost).toFixed(2) }}</span>
              </template>
            </el-table-column>

            <el-table-column label="目标止盈/止损" width="140">
              <template #default="{ row }">
                <div v-if="row.targetTakeProfit || row.targetStopLoss" style="font-size: 12px; line-height: 1.5;">
                  <div v-if="row.targetTakeProfit" style="color: #f56c6c;">
                    止盈: ¥{{ Number(row.targetTakeProfit).toFixed(3) }}
                  </div>
                  <div v-if="row.targetStopLoss" style="color: #67c23a;">
                    止损: ¥{{ Number(row.targetStopLoss).toFixed(3) }}
                  </div>
                </div>
                <el-button v-else link type="info" size="small" @click="openTargetDialog(row)">
                  + 设置目标
                </el-button>
              </template>
            </el-table-column>

            <el-table-column label="操作" width="380" fixed="right">
              <template #default="{ row }">
                <el-button type="success" plain size="small" :icon="TrendCharts" @click="openChartDialog(row)">
                  走势图
                </el-button>
                <el-button link type="primary" size="small" @click="handleBuyMore(row)">
                  加仓
                </el-button>
                <el-button
                  link
                  type="warning"
                  size="small"
                  :disabled="row.holdQuantity <= 0"
                  @click="handleSellMore(row)"
                >
                  减仓
                </el-button>
                <el-button
                  link
                  type="success"
                  size="small"
                  :disabled="row.holdQuantity <= 0"
                  @click="handleDividend(row)"
                >
                  分红
                </el-button>
                <el-button
                  type="primary"
                  plain
                  size="small"
                  :disabled="row.holdQuantity <= 0"
                  @click="openSimulator(row)"
                >
                  🧮 测算
                </el-button>
                <el-button link type="warning" size="small" @click="openScreenshotDialog(row)">
                  📷 导入流水
                </el-button>
                <el-button link type="info" size="small" @click="editHolding(row)">
                  ✏️ 编辑
                </el-button>
                <el-popconfirm
                  title="确定删除此标的全部持仓及历史记录？"
                  confirm-button-text="删除"
                  cancel-button-text="取消"
                  confirm-button-type="danger"
                  @confirm="handleDeletePosition(row.symbol)"
                >
                  <template #reference>
                    <el-button link type="danger" size="small">
                      🗑️
                    </el-button>
                  </template>
                </el-popconfirm>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- Tab 2: 历史交易流水与复盘 -->
        <el-tab-pane label="📜 交易流水与复盘" name="history">
          <div class="table-toolbar">
            <div class="toolbar-left">
              <el-input
                v-model="searchSymbol"
                placeholder="按标的代码筛选，如 510300"
                clearable
                style="width: 260px"
                @clear="loadHistory"
                @keyup.enter="loadHistory"
              >
                <template #append>
                  <el-button :icon="Search" @click="loadHistory" />
                </template>
              </el-input>
            </div>
            <div class="toolbar-right">
              <el-button size="small" :icon="Refresh" @click="loadHistory">刷新流水</el-button>
            </div>
          </div>

          <el-table
            v-loading="loadingHistory"
            :data="historyList"
            stripe
            border
            style="width: 100%"
            empty-text="暂无历史交易流水"
          >
            <el-table-column prop="tradeTime" label="成交时间" width="160" />

            <el-table-column prop="symbol" label="标的代码/名称" min-width="160">
              <template #default="{ row }">
                <span class="num-font" style="font-weight: bold; margin-right: 6px;">{{ row.symbol }}</span>
                <span>{{ row.name }}</span>
              </template>
            </el-table-column>

            <el-table-column prop="action" label="动作" width="90" align="center">
              <template #default="{ row }">
                <el-tag :type="getActionTagType(row.action)" disable-transitions>
                  {{ getActionLabel(row.action) }}
                </el-tag>
              </template>
            </el-table-column>

            <el-table-column prop="price" label="成交单价" width="110" align="right">
              <template #default="{ row }">
                <span class="num-font">¥ {{ Number(row.price).toFixed(4) }}</span>
              </template>
            </el-table-column>

            <el-table-column prop="quantity" label="成交数量" width="100" align="right">
              <template #default="{ row }">
                <span class="num-font">{{ row.quantity }}</span>
              </template>
            </el-table-column>

            <el-table-column prop="amount" label="成交金额" width="120" align="right">
              <template #default="{ row }">
                <span class="num-font">¥ {{ Number(row.amount).toFixed(2) }}</span>
              </template>
            </el-table-column>

            <el-table-column prop="fee" label="税费佣金" width="90" align="right">
              <template #default="{ row }">
                <span class="num-font" style="color: #909399;">¥ {{ Number(row.fee).toFixed(2) }}</span>
              </template>
            </el-table-column>

            <el-table-column prop="realizedPnl" label="已实现盈亏" width="130" align="right">
              <template #default="{ row }">
                <span
                  v-if="row.action === 'SELL'"
                  class="num-font"
                  :class="getAmountColorClass(row.realizedPnl)"
                  style="font-size: 14px;"
                >
                  {{ formatPnl(row.realizedPnl) }}
                </span>
                <span v-else style="color: #c0c4cc;">-</span>
              </template>
            </el-table-column>

            <el-table-column prop="strategyTag" label="策略标签" width="120">
              <template #default="{ row }">
                <el-tag v-if="row.strategyTag" size="small" type="info" effect="plain">
                  {{ row.strategyTag }}
                </el-tag>
              </template>
            </el-table-column>

            <el-table-column prop="notes" label="买卖复盘理由" min-width="180" show-overflow-tooltip>
              <template #default="{ row }">
                <span style="color: #606266;">{{ row.notes || '-' }}</span>
              </template>
            </el-table-column>

            <el-table-column label="操作" width="170" align="center" fixed="right">
              <template #default="{ row }">
                <el-button link type="success" size="small" :icon="TrendCharts" @click="openChartDialog(row)">走势</el-button>
                <el-button link type="primary" size="small" @click="editTrade(row)">编辑</el-button>
                <el-popconfirm
                  title="确定撤销此笔流水？系统将自动重新回放持仓并校准账目。"
                  confirm-button-text="确定"
                  cancel-button-text="取消"
                  @confirm="handleDeleteTrade(row.id)"
                >
                  <template #reference>
                    <el-button link type="danger" size="small">撤销</el-button>
                  </template>
                </el-popconfirm>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- 弹窗组件群 -->
    <AddTradeDialog ref="addTradeDialogRef" @success="loadAllData" />
    <InitPositionDialog ref="initPositionDialogRef" @success="loadAllData" />
    <EditTradeDialog ref="editTradeDialogRef" @success="loadAllData" />
    <ScreenshotImportDialog ref="screenshotImportDialogRef" @success="loadAllData" />
    <CostSimulatorDialog ref="simulatorDialogRef" @apply-trade="openAddTradeWithValues" />
    <TargetPriceDialog ref="targetDialogRef" @success="loadPositions" />
    <AiCopilotDrawer ref="copilotDrawerRef" />
    <StockChartDialog
      v-model="chartDialogVisible"
      :symbol="currentChartSymbol"
      :name="currentChartName"
    />

    <!-- 悬浮触发按钮：随叫随到的 AI 投资副驾 -->
    <div class="floating-copilot-btn" title="点击唤醒 AI 投资副驾" @click="openCopilotDrawer()">
      <div class="copilot-pulse"></div>
      <span class="btn-icon">🤖</span>
      <span class="btn-text">AI 投资副驾</span>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { Plus, Refresh, Search, DocumentAdd, Delete, Camera, QuestionFilled, TrendCharts } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { getAccountSummary, getPositionList, getTradeHistory, deleteTrade, deletePosition, resetAllData } from './api'
import AddTradeDialog from './components/AddTradeDialog.vue'
import InitPositionDialog from './components/InitPositionDialog.vue'
import EditTradeDialog from './components/EditTradeDialog.vue'
import ScreenshotImportDialog from './components/ScreenshotImportDialog.vue'
import CostSimulatorDialog from './components/CostSimulatorDialog.vue'
import TargetPriceDialog from './components/TargetPriceDialog.vue'
import AiCopilotDrawer from './components/AiCopilotDrawer.vue'
import StockChartDialog from './components/StockChartDialog.vue'

const activeTab = ref('positions')
const onlyHolding = ref(true)
const searchSymbol = ref('')
const autoRefresh = ref(false)
const refreshing = ref(false)
const lastUpdateTime = ref('')

const summary = ref({})
const positions = ref([])
const historyList = ref([])

const loadingPositions = ref(false)
const loadingHistory = ref(false)

const addTradeDialogRef = ref(null)
const initPositionDialogRef = ref(null)
const editTradeDialogRef = ref(null)
const screenshotImportDialogRef = ref(null)
const simulatorDialogRef = ref(null)
const targetDialogRef = ref(null)

// 走势图弹窗状态
const chartDialogVisible = ref(false)
const currentChartSymbol = ref('')
const currentChartName = ref('')

function openChartDialog(row) {
  if (!row || !row.symbol) return
  currentChartSymbol.value = row.symbol
  currentChartName.value = row.name || ''
  chartDialogVisible.value = true
}

let timer = null

onMounted(() => {
  loadAllData()
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
})

function toggleAutoRefresh(val) {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
  if (val) {
    ElMessage.info('已开启自动行情刷新 (每 30 秒更新一次)')
    timer = setInterval(() => {
      loadAllData(false)
    }, 30000)
  } else {
    ElMessage.info('已关闭自动刷新')
  }
}

async function loadAllData(showLoading = true) {
  if (showLoading) refreshing.value = true
  try {
    await Promise.all([loadSummary(), loadPositions(), loadHistory()])
    const d = new Date()
    const pad = (n) => String(n).padStart(2, '0')
    lastUpdateTime.value = `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
  } finally {
    if (showLoading) refreshing.value = false
  }
}

async function loadSummary() {
  try {
    const data = await getAccountSummary()
    summary.value = data || {}
  } catch (err) {
    console.error(err)
  }
}

async function loadPositions() {
  loadingPositions.value = true
  try {
    const list = await getPositionList(onlyHolding.value)
    positions.value = list || []
  } catch (err) {
    console.error(err)
  } finally {
    loadingPositions.value = false
  }
}

async function loadHistory() {
  loadingHistory.value = true
  try {
    const list = await getTradeHistory(searchSymbol.value)
    historyList.value = list || []
  } catch (err) {
    console.error(err)
  } finally {
    loadingHistory.value = false
  }
}

function openAddTradeDialog() {
  addTradeDialogRef.value?.open()
}

function openScreenshotDialog(stock) {
  screenshotImportDialogRef.value?.open(stock)
}

function openInitHoldingDialog() {
  initPositionDialogRef.value?.open()
}

function editHolding(row) {
  initPositionDialogRef.value?.open(row)
}

function editTrade(row) {
  editTradeDialogRef.value?.open(row)
}

function handleBuyMore(row) {
  addTradeDialogRef.value?.open({
    action: 'BUY',
    symbol: row.symbol,
    name: row.name,
    price: row.currentPrice,
    strategyTag: '下跌加仓',
  })
}

function handleSellMore(row) {
  addTradeDialogRef.value?.open({
    action: 'SELL',
    symbol: row.symbol,
    name: row.name,
    price: row.currentPrice,
    quantity: row.holdQuantity,
    maxHoldQuantity: row.holdQuantity,
    strategyTag: '达到目标止盈',
  })
}

function handleDividend(row) {
  addTradeDialogRef.value?.open({
    action: 'DIVIDEND',
    symbol: row.symbol,
    name: row.name,
    quantity: row.holdQuantity,
    fee: 0,
  })
}

function openSimulator(row) {
  simulatorDialogRef.value?.open(row)
}

function openAddTradeWithValues(params) {
  addTradeDialogRef.value?.open(params)
}

function openTargetDialog(row) {
  targetDialogRef.value?.open(row)
}

const copilotDrawerRef = ref(null)

function openCopilotDrawer(initialPrompt) {
  copilotDrawerRef.value?.openDrawer(initialPrompt)
}

function askCopilotForSymbol(row) {
  const signalDesc = row.tradeSignal ? `系统决策信号: 【${row.tradeSignal.title}】 (${row.tradeSignal.description})` : '暂无特定信号'
  const prompt = `请帮我重点深度推演分析标的 【${row.name} (${row.symbol})】。\n当前实时现价: ¥${row.currentPrice}，买入均价: ¥${row.costPrice}，做T保本价: ¥${row.dilutedCostPrice || row.costPrice}，持仓数量: ${row.holdQuantity} 股，累计总盈亏: ¥${row.totalPnl}。\n${signalDesc}。\n请给出具体的实操指令：今天适合加仓、减仓做T还是继续观望？建议的具体挂单价位与手数是多少？`
  openCopilotDrawer(prompt)
}

function applySignalToTrade(row, action) {
  if (!row.tradeSignal) return
  const suggestedPrice = row.tradeSignal.suggestedPrice || row.currentPrice || row.costPrice
  const suggestedQty = row.tradeSignal.suggestedQuantity || 1000
  openAddTradeWithValues({
    symbol: row.symbol,
    name: row.name,
    action: action,
    price: suggestedPrice,
    quantity: suggestedQty,
    strategyTag: action === 'BUY' ? '网格低吸加仓' : '波段高抛做T',
    notes: `跟随系统推荐信号: ${row.tradeSignal.title}`,
  })
}

async function handleDeletePosition(symbol) {
  try {
    await deletePosition(symbol)
    ElMessage.success(`标的 [${symbol}] 及其全部流水已删除！`)
    loadAllData()
  } catch (err) {
    console.error(err)
  }
}

async function handleDeleteTrade(id) {
  try {
    await deleteTrade(id)
    ElMessage.success('流水已撤销，持仓已自动回放重算完成！')
    loadAllData()
  } catch (err) {
    console.error(err)
  }
}

async function handleResetAll() {
  try {
    await resetAllData()
    ElMessage.success('所有交易与持仓数据已全部重置清空！')
    loadAllData()
  } catch (err) {
    console.error(err)
  }
}

function getActionLabel(action) {
  if (action === 'BUY') return '买入'
  if (action === 'SELL') return '卖出'
  if (action === 'DIVIDEND') return '分红'
  return action
}

function getActionTagType(action) {
  if (action === 'BUY') return 'success'
  if (action === 'SELL') return 'danger'
  if (action === 'DIVIDEND') return 'warning'
  return 'info'
}

function getAmountColorClass(val) {
  if (!val) return ''
  const num = Number(val)
  if (num > 0) return 'up-color'
  if (num < 0) return 'down-color'
  return ''
}

function formatPnl(val) {
  if (val === null || val === undefined) return '¥ 0.00'
  const num = Number(val)
  const sign = num > 0 ? '+' : ''
  return `${sign}¥ ${num.toFixed(2)}`
}

function formatRate(val) {
  if (val === null || val === undefined) return '0.00%'
  const num = Number(val)
  const sign = num > 0 ? '+' : ''
  return `${sign}${num.toFixed(2)}%`
}
</script>

<style scoped>
.app-container {
  max-width: 1360px;
  margin: 0 auto;
  padding: 24px 20px 48px;
}

.app-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 24px;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 14px;
}

.logo-icon {
  font-size: 34px;
}

.app-title {
  font-size: 22px;
  font-weight: bold;
  color: #1a1a1a;
  line-height: 1.2;
}

.app-subtitle {
  font-size: 13px;
  color: #909399;
  margin-top: 4px;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.auto-refresh-wrap {
  display: flex;
  align-items: center;
  background: #fff;
  padding: 6px 12px;
  border-radius: 20px;
  border: 1px solid #ebeef5;
}

.summary-cards {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: 24px;
}

.stat-card {
  border-radius: 8px;
  transition: transform 0.2s;
}

.stat-card:hover {
  transform: translateY(-2px);
}

.stat-label {
  font-size: 13px;
  color: #909399;
  margin-bottom: 6px;
}

.stat-value {
  font-size: 24px;
  font-weight: 700;
  color: #303133;
}

.stat-sub {
  font-size: 12px;
  color: #a8abb2;
  margin-top: 6px;
}

.main-content-card {
  border-radius: 8px;
  background: #fff;
  min-height: 520px;
}

.custom-tabs :deep(.el-tabs__item) {
  font-size: 16px;
  font-weight: 600;
  padding: 0 20px;
  height: 48px;
  line-height: 48px;
}

.table-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
  margin-top: 8px;
}

.floating-copilot-btn {
  position: fixed;
  right: 24px;
  bottom: 28px;
  z-index: 1000;
  display: flex;
  align-items: center;
  gap: 8px;
  background: linear-gradient(135deg, #409eff 0%, #2b73d2 100%);
  color: #fff;
  padding: 10px 18px;
  border-radius: 30px;
  box-shadow: 0 4px 16px rgba(64, 158, 255, 0.4);
  cursor: pointer;
  transition: all 0.3s cubic-bezier(0.25, 0.8, 0.25, 1);
  user-select: none;
}

.floating-copilot-btn:hover {
  transform: translateY(-3px) scale(1.03);
  box-shadow: 0 6px 20px rgba(64, 158, 255, 0.55);
}

.floating-copilot-btn .btn-icon {
  font-size: 20px;
}

.floating-copilot-btn .btn-text {
  font-weight: bold;
  font-size: 14px;
  letter-spacing: 0.5px;
}

.copilot-pulse {
  position: absolute;
  top: -2px;
  right: -2px;
  width: 10px;
  height: 10px;
  background: #67c23a;
  border-radius: 50%;
  border: 2px solid #fff;
  box-shadow: 0 0 6px #67c23a;
}

.clickable-stock-name {
  font-weight: 600;
  color: #303133;
  cursor: pointer;
  transition: color 0.2s;
}

.clickable-stock-name:hover {
  color: #409eff;
  text-decoration: underline;
}
</style>
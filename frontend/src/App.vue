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

    <!-- 全市场宏观大盘晴雨表与量能情绪雷达 -->
    <div v-if="marketOverview && marketOverview.indices && marketOverview.indices.length" class="market-overview-banner">
      <div class="market-banner-left">
        <div class="market-tag-title">
          <span class="pulse-dot"></span>
          <b>全市场大势</b>
        </div>
        <div class="indices-list">
          <div
            v-for="idx in marketOverview.indices"
            :key="idx.symbol"
            class="index-pill"
            :class="idx.changePercent >= 0 ? 'pill-up' : 'pill-down'"
          >
            <span class="idx-name">{{ idx.name }}</span>
            <span class="idx-points num-font">{{ Number(idx.currentPoints).toFixed(2) }}</span>
            <span class="idx-chg num-font">
              {{ idx.changePercent >= 0 ? '+' : '' }}{{ Number(idx.changePercent).toFixed(2) }}%
            </span>
          </div>
        </div>
      </div>

      <div class="market-banner-right">
        <!-- 两市总成交额 -->
        <div class="market-metric">
          <span class="metric-label">两市成交额:</span>
          <span class="metric-val num-font">¥{{ (Number(marketOverview.totalTurnover) / 10000).toFixed(2) }}万亿</span>
          <el-tag size="small" type="info" effect="plain" class="metric-tag">
            {{ marketOverview.turnoverStatus }}
          </el-tag>
        </div>

        <!-- 市场多空情绪温度计 -->
        <div class="market-metric sentiment-box">
          <span class="metric-label">情绪温度:</span>
          <span class="sentiment-score num-font" :style="{ color: getSentimentColor(marketOverview.sentimentScore) }">
            {{ marketOverview.sentimentScore }}°
          </span>
          <el-tag size="small" :type="getSentimentTagType(marketOverview.sentimentLevel)" effect="dark" class="sentiment-badge">
            {{ marketOverview.sentimentTitle }}
          </el-tag>
          <el-tooltip :content="marketOverview.sentimentDesc" placement="bottom" effect="light">
            <el-icon class="sentiment-tip-icon"><QuestionFilled /></el-icon>
          </el-tooltip>
        </div>
      </div>
    </div>

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

    <!-- 🎯 今日操盘行动看板 (极简行动指引 & 躺平待机看板) -->
    <div class="today-action-card">
      <div class="action-card-header">
        <div class="action-card-title-group">
          <div class="action-card-title">
            <span class="action-title-icon">🎯</span>
            <span class="action-title-text">今日操盘行动建议</span>
            <el-tag
              :type="actionablePositions.length > 0 ? 'danger' : 'success'"
              effect="dark"
              size="small"
              class="action-badge"
            >
              {{ actionablePositions.length > 0 ? `${actionablePositions.length} 支标的触发调仓建议` : '持仓标的处于平稳期 · 建议安心待机' }}
            </el-tag>
          </div>
          <div class="action-card-subtitle">
            系统已融合次日 Pivot 支撑阻力位、网格做T点位、止盈止损线及全市场情绪量能完成智能测算
          </div>
        </div>
      </div>

      <!-- 待办场景：有触发调仓建议的标的 -->
      <div v-if="actionablePositions.length > 0" class="action-items-grid">
        <div
          v-for="item in actionablePositions"
          :key="item.symbol"
          class="action-item-card"
          :class="'action-card-' + (item.tradeSignal?.signalType?.toLowerCase() || 'default')"
        >
          <div class="action-item-top">
            <div class="action-item-stock">
              <span class="stock-name" @click="openChartDialog(item)" title="点击查看走势图与复盘">{{ item.name }}</span>
              <span class="stock-symbol num-font">{{ item.symbol }}</span>
              <el-tag :type="item.market === 'SH' ? 'danger' : 'primary'" size="small" effect="plain">{{ item.market }}</el-tag>
            </div>
            <el-tag
              :type="item.tradeSignal.level || (item.tradeSignal.signalType === 'BUY' ? 'success' : 'danger')"
              effect="dark"
              class="action-signal-tag"
            >
              {{ item.tradeSignal.title }}
            </el-tag>
          </div>

          <div class="action-item-desc">
            {{ item.tradeSignal.description }}
          </div>

          <div class="action-item-metrics">
            <div class="metric-block">
              <div class="m-label">建议执行动作</div>
              <div class="m-val" :class="item.tradeSignal.signalType === 'BUY' ? 'up-color' : 'down-color'" style="font-weight: 800;">
                {{ item.tradeSignal.signalType === 'BUY' ? '🟢 挂单低吸' : item.tradeSignal.signalType === 'SELL' ? '🔴 挂单高抛' : '⚠️ 关注风险' }}
              </div>
            </div>
            <div class="metric-block">
              <div class="m-label">建议参考价位</div>
              <div class="m-val num-font">
                ¥ {{ (item.tradeSignal.suggestedPrice || item.currentPrice || item.costPrice) ? Number(item.tradeSignal.suggestedPrice || item.currentPrice || item.costPrice).toFixed(3) : '市价' }}
              </div>
            </div>
            <div class="metric-block">
              <div class="m-label">建议执行手数</div>
              <div class="m-val num-font">
                {{ item.tradeSignal.suggestedQuantity || 1000 }} 股
              </div>
            </div>
            <div class="metric-block">
              <div class="m-label">实时现价</div>
              <div class="m-val num-font" style="color: #606266;">
                ¥ {{ Number(item.currentPrice).toFixed(3) }}
              </div>
            </div>
          </div>

          <div class="action-item-actions">
            <el-button
              v-if="item.tradeSignal.signalType === 'BUY'"
              type="success"
              size="default"
              class="btn-execute"
              @click="applySignalToTrade(item, 'BUY')"
            >
              ⚡ 一键低吸买入 (自动预填)
            </el-button>
            <el-button
              v-else-if="item.tradeSignal.signalType === 'SELL'"
              type="warning"
              size="default"
              class="btn-execute"
              @click="applySignalToTrade(item, 'SELL')"
            >
              ⚡ 一键高抛卖出 (自动预填)
            </el-button>
            <el-button
              v-else
              type="primary"
              plain
              size="default"
              class="btn-execute"
              @click="openAddTradeWithValues({ symbol: item.symbol, name: item.name, price: item.currentPrice, quantity: item.holdQuantity })"
            >
              去处理调仓
            </el-button>

            <div class="action-extra-links">
              <el-button link type="primary" size="small" @click="askCopilotForSymbol(item)">
                问AI副驾 🤖
              </el-button>
              <el-button link type="info" size="small" @click="openChartDialog(item)">
                走势与指标 📈
              </el-button>
            </div>
          </div>
        </div>
      </div>

      <!-- 躺平场景：无买卖信号触发，全市场/标的安稳观望 -->
      <div v-else class="zen-relax-banner">
        <div class="zen-left">
          <span class="zen-tea-emoji">☕</span>
          <div class="zen-text-content">
            <div class="zen-headline">
              {{ quietPositions.length > 0 ? '持仓标的均在健康区间波动，今日无需频繁操作，安心享受时间复利' : '暂无在持标的，系统已就绪，随时可建档或记账' }}
            </div>
            <div class="zen-quote">
              “会买的是徒弟，会卖的是师傅，会空仓和等待的是祖师爷。” 系统正实时盯盘，一旦触及网格挂单点位或止盈止损线，闹钟将第一时间在此提醒。
            </div>
          </div>
        </div>
        <div v-if="quietPositions.length > 0" class="zen-right">
          <div class="zen-monitor-title">实时盯盘中 ({{ quietPositions.length }} 支持仓):</div>
          <div class="zen-tag-list">
            <span
              v-for="qp in quietPositions"
              :key="qp.symbol"
              class="zen-stock-pill"
              @click="openChartDialog(qp)"
              title="点击查看分时与日K走势图"
            >
              <span class="pill-name">{{ qp.name }}</span>
              <span class="pill-price num-font">¥{{ Number(qp.currentPrice).toFixed(3) }}</span>
              <span class="pill-chg num-font" :class="qp.changePercent >= 0 ? 'up-color' : 'down-color'">
                {{ qp.changePercent >= 0 ? '+' : '' }}{{ Number(qp.changePercent).toFixed(2) }}%
              </span>
            </span>
          </div>
        </div>
      </div>
    </div>

    <!-- 主体区域：Tab 分组 -->
    <el-card class="main-content-card" shadow="never">
      <el-tabs v-model="activeTab" class="custom-tabs">
        <!-- Tab 1: 当前持仓看板 -->
        <el-tab-pane label="📊 当前持仓看板" name="positions">
          <div class="table-toolbar">
            <div class="toolbar-left">
              <!-- 视图模式切换 -->
              <el-radio-group v-model="viewMode" size="small" @change="saveViewMode" style="margin-right: 16px;">
                <el-radio-button label="simple">💡 极简小白模式</el-radio-button>
                <el-radio-button label="pro">📊 专业投研模式</el-radio-button>
              </el-radio-group>
              <el-switch
                v-model="onlyHolding"
                active-text="仅显示当前持仓"
                inactive-text="显示全部历史标的"
                @change="loadPositions"
              />
              <span v-if="viewMode === 'simple'" style="font-size: 12px; color: #909399; margin-left: 12px;">
                （已隐藏复杂的量化摊薄与Alpha参数，直观展示今日指引与收益）
              </span>
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
                <!-- 相对大盘表现与共振标签 (Relative Strength) - 专业投研模式展示 -->
                <div v-if="viewMode === 'pro' && row.relativeStrengthStatus && row.holdQuantity > 0" style="margin-top: 3px;">
                  <el-tooltip
                    :content="'归属大盘基准: ' + (row.benchmarkName || '大盘') + ' (' + (row.benchmarkChangePercent >= 0 ? '+' : '') + (row.benchmarkChangePercent || 0) + '%)，相对强弱超额收益 (Alpha): ' + (row.relativeStrength > 0 ? '+' : '') + (row.relativeStrength || 0) + '%'"
                    placement="top"
                  >
                    <el-tag
                      size="small"
                      :type="row.relativeStrengthLevel || 'info'"
                      effect="plain"
                      class="rs-badge"
                    >
                      {{ row.relativeStrengthStatus }}
                      <span v-if="row.relativeStrength !== undefined && row.relativeStrength !== null">
                        {{ Number(row.relativeStrength) > 0 ? '+' : '' }}{{ Number(row.relativeStrength).toFixed(2) }}%
                      </span>
                    </el-tag>
                  </el-tooltip>
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

            <!-- 极简模式专享：我的持仓与盈亏 (合并持仓数、累计总盈亏、保本单价、市值) -->
            <el-table-column v-if="viewMode === 'simple'" label="我的持仓与盈亏" min-width="250">
              <template #default="{ row }">
                <div v-if="row.holdQuantity > 0">
                  <div style="display: flex; justify-content: space-between; align-items: baseline; gap: 8px;">
                    <span style="font-size: 14px; font-weight: bold; color: #303133; white-space: nowrap;">
                      {{ row.holdQuantity }} <span style="font-size: 12px; font-weight: normal; color: #909399;">股</span>
                    </span>
                    <span class="num-font" :class="getAmountColorClass(row.totalPnl)" style="font-size: 15px; font-weight: bold; white-space: nowrap;">
                      {{ formatPnl(row.totalPnl) }}
                      <span style="font-size: 12px; font-weight: normal;">({{ formatRate(row.totalPnlRate) }})</span>
                    </span>
                  </div>
                  <div style="display: flex; justify-content: space-between; font-size: 12px; color: #909399; margin-top: 4px; gap: 8px;">
                    <span style="white-space: nowrap;">市值: ¥{{ Number(row.marketValue).toFixed(2) }}</span>
                    <span style="white-space: nowrap;">保本价: ¥{{ (row.dilutedCostPrice !== undefined && row.dilutedCostPrice !== null ? Number(row.dilutedCostPrice) : Number(row.costPrice)).toFixed(3) }}</span>
                  </div>
                </div>
                <div v-else style="color: #909399; font-size: 12px;">
                  已清仓 (历史净盈亏: <b :class="getAmountColorClass(row.totalPnl)">{{ formatPnl(row.totalPnl) }}</b>)
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

            <!-- 买入均价 (专业投研模式) -->
            <el-table-column v-if="viewMode === 'pro'" prop="costPrice" width="125" align="right">
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

            <!-- 摊薄成本价 (券商保本价) (专业投研模式) -->
            <el-table-column v-if="viewMode === 'pro'" prop="dilutedCostPrice" width="140" align="right">
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

            <el-table-column v-if="viewMode === 'pro'" prop="holdQuantity" label="持仓数量" width="110" align="right">
              <template #default="{ row }">
                <span class="num-font" :style="{ color: row.holdQuantity > 0 ? '#303133' : '#909399', fontWeight: 'bold' }">
                  {{ row.holdQuantity }}
                </span>
                <span style="font-size: 12px; color: #909399; margin-left: 2px;">{{ row.holdQuantity === 0 ? '(已清)' : '股' }}</span>
              </template>
            </el-table-column>

            <!-- 持仓市值 (专业投研模式) -->
            <el-table-column v-if="viewMode === 'pro'" label="当前市值" width="125" align="right">
              <template #default="{ row }">
                <span class="num-font" style="font-weight: 600;">
                  ¥ {{ Number(row.marketValue).toFixed(2) }}
                </span>
              </template>
            </el-table-column>

            <!-- 实时持仓浮动盈亏 (专业投研模式) -->
            <el-table-column v-if="viewMode === 'pro'" label="持仓浮盈(率)" width="135" align="right">
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

            <!-- 标的累计总盈亏 (含做T落袋，与券商App大红字一致) (专业投研模式) -->
            <el-table-column v-if="viewMode === 'pro'" label="累计总盈亏(券商)" width="150" align="right">
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

            <el-table-column v-if="viewMode === 'pro'" prop="totalCost" label="投入成本" width="120" align="right">
              <template #default="{ row }">
                <span class="num-font" style="color: #606266;">¥ {{ Number(row.totalCost).toFixed(2) }}</span>
              </template>
            </el-table-column>

            <el-table-column v-if="viewMode === 'pro'" label="目标止盈/止损" width="140">
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

            <el-table-column label="操作" :width="viewMode === 'simple' ? 320 : 380" fixed="right">
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
                  v-if="viewMode === 'pro'"
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
      :benchmark="currentChartBenchmark"
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
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { Plus, Refresh, Search, DocumentAdd, Delete, Camera, QuestionFilled, TrendCharts } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { getAccountSummary, getPositionList, getTradeHistory, deleteTrade, deletePosition, resetAllData, getMarketOverview } from './api'
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

// 视图模式：极简小白模式 (simple) vs 专业投研模式 (pro)
const viewMode = ref(localStorage.getItem('stock_copilot_view_mode') || 'simple')

function saveViewMode(val) {
  localStorage.setItem('stock_copilot_view_mode', val)
}

const summary = ref({})
const positions = ref([])
const historyList = ref([])
const marketOverview = ref(null)

// 今日操盘待办标的 (有买入/卖出/风险预警等明确动作建议的在持标的)
const actionablePositions = computed(() => {
  return positions.value.filter(p =>
    p.holdQuantity > 0 &&
    p.tradeSignal &&
    (p.tradeSignal.signalType === 'BUY' || p.tradeSignal.signalType === 'SELL' || p.tradeSignal.signalType === 'ALERT')
  )
})

// 安心持股/待机标的 (处于持股观望或无紧急操作的在持标的)
const quietPositions = computed(() => {
  return positions.value.filter(p =>
    p.holdQuantity > 0 &&
    (!p.tradeSignal || p.tradeSignal.signalType === 'HOLD')
  )
})

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
const currentChartBenchmark = ref(null)

function openChartDialog(row) {
  if (!row || !row.symbol) return
  currentChartSymbol.value = row.symbol
  currentChartName.value = row.name || ''
  currentChartBenchmark.value = {
    benchmarkSymbol: row.benchmarkSymbol,
    benchmarkName: row.benchmarkName,
    benchmarkChangePercent: row.benchmarkChangePercent,
    relativeStrength: row.relativeStrength,
    relativeStrengthStatus: row.relativeStrengthStatus,
    relativeStrengthLevel: row.relativeStrengthLevel,
  }
  chartDialogVisible.value = true
}

function getSentimentColor(score) {
  if (!score) return '#909399'
  if (score >= 75) return '#f56c6c'
  if (score >= 58) return '#67c23a'
  if (score >= 45) return '#409eff'
  if (score >= 30) return '#e6a23c'
  return '#909399'
}

function getSentimentTagType(level) {
  switch (level) {
    case 'FEVER': return 'danger'
    case 'BULLISH': return 'success'
    case 'NEUTRAL': return 'primary'
    case 'BEARISH': return 'warning'
    case 'PANIC': return 'info'
    default: return 'info'
  }
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
    await Promise.all([loadMarketOverview(), loadSummary(), loadPositions(), loadHistory()])
    const d = new Date()
    const pad = (n) => String(n).padStart(2, '0')
    lastUpdateTime.value = `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
  } finally {
    if (showLoading) refreshing.value = false
  }
}

async function loadMarketOverview() {
  try {
    const data = await getMarketOverview()
    marketOverview.value = data || null
  } catch (err) {
    console.error('拉取大盘晴雨表失败:', err)
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

/* 全市场宏观大盘晴雨表 */
.market-overview-banner {
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: #ffffff;
  border-radius: 8px;
  padding: 8px 16px;
  margin-bottom: 16px;
  border: 1px solid #ebeef5;
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.02);
  flex-wrap: wrap;
  gap: 12px;
}

.market-banner-left {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.market-tag-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: #303133;
  padding-right: 12px;
  border-right: 1px solid #ebeef5;
}

.market-tag-title .pulse-dot {
  width: 8px;
  height: 8px;
  background: #409eff;
  border-radius: 50%;
  display: inline-block;
  box-shadow: 0 0 6px rgba(64, 158, 255, 0.6);
}

.indices-list {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.index-pill {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  border-radius: 6px;
  font-size: 12px;
  background: #f8f9fb;
  border: 1px solid #ebeef5;
  transition: all 0.2s;
}

.index-pill.pill-up {
  background: #fef0f0;
  border-color: #fde2e2;
}

.index-pill.pill-down {
  background: #f0f9eb;
  border-color: #e1f3d8;
}

.index-pill .idx-name {
  font-weight: bold;
  color: #303133;
}

.index-pill .idx-points {
  font-weight: 600;
}

.index-pill.pill-up .idx-points,
.index-pill.pill-up .idx-chg {
  color: #f56c6c;
}

.index-pill.pill-down .idx-points,
.index-pill.pill-down .idx-chg {
  color: #67c23a;
}

.market-banner-right {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
}

.market-metric {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: #606266;
}

.metric-label {
  color: #909399;
}

.metric-val {
  font-weight: bold;
  color: #303133;
}

.sentiment-box {
  background: #fafafa;
  padding: 3px 8px;
  border-radius: 6px;
  border: 1px solid #ebeef5;
}

.sentiment-score {
  font-size: 15px;
  font-weight: 800;
}

.sentiment-badge {
  font-weight: bold;
  letter-spacing: 0.2px;
}

.sentiment-tip-icon {
  font-size: 13px;
  color: #909399;
  cursor: pointer;
}

.rs-badge {
  font-size: 11px;
  font-weight: 600;
  line-height: 18px;
  height: 20px;
}

/* 🎯 今日操盘行动看板 */
.today-action-card {
  background: #ffffff;
  border-radius: 8px;
  padding: 18px 20px;
  margin-bottom: 20px;
  border: 1px solid #ebeef5;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.03);
}

.action-card-header {
  margin-bottom: 4px;
}

.action-card-title-group {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.action-card-title {
  display: flex;
  align-items: center;
  gap: 8px;
}

.action-title-icon {
  font-size: 20px;
}

.action-title-text {
  font-size: 16px;
  font-weight: 700;
  color: #1a1a1a;
}

.action-badge {
  font-weight: 600;
  border-radius: 12px;
}

.action-card-subtitle {
  font-size: 12px;
  color: #909399;
}

/* 待办网格 */
.action-items-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(380px, 1fr));
  gap: 16px;
  margin-top: 14px;
}

.action-item-card {
  background: #fbfcfe;
  border-radius: 8px;
  border: 1px solid #e4e7ed;
  padding: 16px;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  transition: all 0.2s;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.02);
}

.action-item-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.06);
}

.action-card-buy {
  border-left: 4px solid #67c23a;
  background: linear-gradient(135deg, #f6ffed 0%, #ffffff 30%);
}

.action-card-sell {
  border-left: 4px solid #f56c6c;
  background: linear-gradient(135deg, #fff1f0 0%, #ffffff 30%);
}

.action-card-alert {
  border-left: 4px solid #e6a23c;
  background: linear-gradient(135deg, #fffbe6 0%, #ffffff 30%);
}

.action-card-default {
  border-left: 4px solid #409eff;
}

.action-item-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 10px;
}

.action-item-stock {
  display: flex;
  align-items: center;
  gap: 6px;
}

.stock-name {
  font-size: 15px;
  font-weight: 700;
  color: #303133;
  cursor: pointer;
}

.stock-name:hover {
  color: #409eff;
  text-decoration: underline;
}

.stock-symbol {
  font-size: 13px;
  color: #606266;
  font-weight: 600;
}

.action-signal-tag {
  font-weight: bold;
}

.action-item-desc {
  font-size: 12px;
  color: #606266;
  line-height: 1.5;
  margin-bottom: 12px;
  background: rgba(255, 255, 255, 0.7);
  padding: 8px 10px;
  border-radius: 6px;
  border: 1px dashed #e4e7ed;
}

.action-item-metrics {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 8px;
  background: #ffffff;
  padding: 8px 10px;
  border-radius: 6px;
  margin-bottom: 14px;
  border: 1px solid #ebeef5;
}

.metric-block {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.m-label {
  font-size: 11px;
  color: #909399;
}

.m-val {
  font-size: 13px;
  font-weight: 700;
  color: #303133;
}

.action-item-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-top: 4px;
}

.btn-execute {
  font-weight: bold;
  letter-spacing: 0.3px;
}

.action-extra-links {
  display: flex;
  align-items: center;
  gap: 4px;
}

/* ☕ 躺平待机看板 */
.zen-relax-banner {
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: linear-gradient(135deg, #f8fafc 0%, #f1f5f9 100%);
  border-radius: 8px;
  padding: 16px 20px;
  margin-top: 10px;
  border: 1px dashed #cbd5e1;
  gap: 24px;
  flex-wrap: wrap;
}

.zen-left {
  display: flex;
  align-items: center;
  gap: 16px;
  flex: 1;
  min-width: 320px;
}

.zen-tea-emoji {
  font-size: 36px;
  line-height: 1;
}

.zen-headline {
  font-size: 15px;
  font-weight: 700;
  color: #334155;
  margin-bottom: 4px;
}

.zen-quote {
  font-size: 12px;
  color: #64748b;
  line-height: 1.5;
}

.zen-right {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.zen-monitor-title {
  font-size: 12px;
  font-weight: 600;
  color: #64748b;
}

.zen-tag-list {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.zen-stock-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  font-size: 12px;
  cursor: pointer;
  transition: all 0.2s;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.02);
}

.zen-stock-pill:hover {
  border-color: #409eff;
  transform: translateY(-1px);
}

.zen-stock-pill .pill-name {
  font-weight: 600;
  color: #334155;
}

.zen-stock-pill .pill-price {
  font-weight: 600;
  color: #475569;
}
</style>
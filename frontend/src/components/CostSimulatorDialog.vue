<template>
  <el-dialog
    v-model="visible"
    title="🧮 持仓成本摊薄与买卖做T决策测算器"
    width="720px"
    destroy-on-close
    class="cost-simulator-dialog"
  >
    <!-- 标的当前持仓与行情全貌横幅 -->
    <div class="position-profile-banner">
      <div class="banner-top-row">
        <div class="stock-name-box">
          <span class="stock-title">{{ positionData.name || positionData.symbol }}</span>
          <span class="stock-code num-font">({{ positionData.symbol }})</span>
          <el-tag size="small" :type="resolveMarketTag(positionData.symbol) === 'SH' ? 'danger' : 'primary'" effect="plain">
            {{ resolveMarketTag(positionData.symbol) }}
          </el-tag>
        </div>
        <div v-if="realQuote && realQuote.currentPrice" class="stock-real-price">
          <span class="price-label">实时现价:</span>
          <b class="price-num num-font" :class="Number(realQuote.changePercent) >= 0 ? 'up-color' : 'down-color'">
            ¥ {{ Number(realQuote.currentPrice).toFixed(3) }}
            <span style="font-size: 12px; margin-left: 4px;">
              ({{ Number(realQuote.changePercent) >= 0 ? '+' : '' }}{{ Number(realQuote.changePercent).toFixed(2) }}%)
            </span>
          </b>
        </div>
      </div>

      <div class="banner-stats-grid">
        <div class="stat-col">
          <span class="stat-label">当前持仓股数</span>
          <span class="stat-val num-font">{{ positionData.holdQuantity }} <span class="unit">股</span></span>
        </div>
        <div class="stat-col">
          <span class="stat-label">买入均价线</span>
          <span class="stat-val num-font cost-blue">¥ {{ Number(positionData.costPrice).toFixed(4) }}</span>
        </div>
        <div class="stat-col highlight-col">
          <span class="stat-label">做T摊薄保本价</span>
          <span class="stat-val num-font t-yellow">¥ {{ Number(positionData.dilutedCostPrice || positionData.costPrice).toFixed(3) }}</span>
        </div>
        <div class="stat-col">
          <span class="stat-label">投入本金</span>
          <span class="stat-val num-font">¥ {{ Number(positionData.totalCost).toFixed(2) }}</span>
        </div>
        <div v-if="savedProfit > 0" class="stat-col">
          <span class="stat-label">做T已降本/落袋</span>
          <span class="stat-val num-font up-color">+¥ {{ Number(savedProfit).toFixed(2) }}</span>
        </div>
      </div>
    </div>

    <!-- 三大核心测算模式切换 Tab -->
    <div class="mode-tabs-container">
      <el-radio-group v-model="calcMode" size="default" class="mode-radio-group">
        <el-radio-button label="buy">
          <span class="tab-btn-content">⬇️ 低吸/加仓摊薄测算</span>
        </el-radio-button>
        <el-radio-button label="sell">
          <span class="tab-btn-content">⬆️ 做T高抛减仓测算</span>
        </el-radio-button>
        <el-radio-button label="reverse">
          <span class="tab-btn-content">🎯 目标成本反向倒推</span>
        </el-radio-button>
      </el-radio-group>
    </div>

    <!-- ========================================================= -->
    <!-- 模式一：加仓/补仓买入测算                                   -->
    <!-- ========================================================= -->
    <div v-if="calcMode === 'buy'" class="mode-calc-content">
      <el-form label-width="110px" label-position="right" size="default">
        <!-- 价格输入与快捷点位 -->
        <el-form-item label="拟加仓单价(元)">
          <div class="input-with-pills">
            <el-input-number
              v-model="simBuyPrice"
              :precision="4"
              :step="0.001"
              :min="0.0001"
              style="width: 220px;"
              placeholder="拟买入价"
            />
            <div class="quick-pill-group">
              <span v-if="currentPriceNum" class="quick-pill" @click="simBuyPrice = currentPriceNum">现价 ¥{{ currentPriceNum.toFixed(3) }}</span>
              <span v-if="currentPriceNum" class="quick-pill" @click="simBuyPrice = +(currentPriceNum * 0.99).toFixed(3)">-1% ¥{{ (currentPriceNum * 0.99).toFixed(3) }}</span>
              <span v-if="currentPriceNum" class="quick-pill" @click="simBuyPrice = +(currentPriceNum * 0.98).toFixed(3)">-2% ¥{{ (currentPriceNum * 0.98).toFixed(3) }}</span>
              <span v-if="pivotPoints && pivotPoints.s1" class="quick-pill pivot-pill" @click="simBuyPrice = Number(pivotPoints.s1)">
                🎯 S1支撑 ¥{{ Number(pivotPoints.s1).toFixed(3) }}
              </span>
              <span v-if="pivotPoints && pivotPoints.s2" class="quick-pill pivot-pill" @click="simBuyPrice = Number(pivotPoints.s2)">
                🛡️ S2极限 ¥{{ Number(pivotPoints.s2).toFixed(3) }}
              </span>
            </div>
          </div>
        </el-form-item>

        <!-- 数量输入与快捷档位 -->
        <el-form-item label="拟加仓数量(股)">
          <div class="input-with-pills">
            <el-input-number
              v-model="simBuyQuantity"
              :step="1000"
              :min="100"
              style="width: 220px;"
              placeholder="100起"
            />
            <div class="quick-pill-group">
              <span class="quick-pill" @click="simBuyQuantity = 1000">+1000 股</span>
              <span class="quick-pill" @click="simBuyQuantity = 2000">+2000 股</span>
              <span class="quick-pill" @click="simBuyQuantity = 5000">+5000 股</span>
              <span v-if="positionData.holdQuantity > 0" class="quick-pill" @click="simBuyQuantity = getQuarterHolding(positionData.holdQuantity)">1/4仓 (+{{ getQuarterHolding(positionData.holdQuantity) }})</span>
              <span v-if="positionData.holdQuantity > 0" class="quick-pill" @click="simBuyQuantity = getHalfHolding(positionData.holdQuantity)">半仓 (+{{ getHalfHolding(positionData.holdQuantity) }})</span>
              <span v-if="positionData.holdQuantity > 0" class="quick-pill" @click="simBuyQuantity = positionData.holdQuantity">加倍 (+{{ positionData.holdQuantity }})</span>
            </div>
          </div>
        </el-form-item>
      </el-form>

      <!-- 加仓推演结果卡片 -->
      <div v-if="buyResult" class="result-dashboard-card">
        <div class="dashboard-header">
          <span class="dash-title">📊 加仓推演结果分析</span>
          <el-tag :type="buyResult.isCostLowered ? 'success' : 'warning'" effect="dark" size="small">
            {{ buyResult.isCostLowered ? '🟢 成本有效摊薄' : '🟡 追高成本抬高' }}
          </el-tag>
        </div>

        <div class="dash-metrics-grid">
          <div class="metric-item">
            <div class="metric-label">本次需投入本金</div>
            <div class="metric-val num-font blue-text">¥ {{ buyResult.additionalCost.toFixed(2) }}</div>
          </div>
          <div class="metric-item">
            <div class="metric-label">加仓后新买入均价</div>
            <div class="metric-val num-font" :class="buyResult.isCostLowered ? 'green-text' : 'orange-text'">
              {{ buyResult.newCostPrice.toFixed(4) }} <span class="unit">元</span>
            </div>
            <div class="metric-sub">
              {{ buyResult.isCostLowered ? '拉低' : '抬高' }} {{ Math.abs(buyResult.costDiffPct) }}%
            </div>
          </div>
          <div class="metric-item">
            <div class="metric-label">加仓后新做T保本价</div>
            <div class="metric-val num-font t-yellow">
              {{ buyResult.newDilutedCostPrice.toFixed(3) }} <span class="unit">元</span>
            </div>
            <div class="metric-sub">综合保本生命线</div>
          </div>
          <div class="metric-item">
            <div class="metric-label">{{ buyResult.isPriceAboveCost ? '当前浮盈安全垫' : '回本所需反弹' }}</div>
            <div class="metric-val num-font" :class="buyResult.isPriceAboveCost ? 'green-text' : 'orange-text'">
              {{ buyResult.isPriceAboveCost ? '+' : '+' }}{{ buyResult.cushionOrReboundPct }}%
            </div>
            <div class="metric-sub">{{ buyResult.isPriceAboveCost ? '现价高于新均价' : '需反弹至保本' }}</div>
          </div>
        </div>

        <div class="dash-details-box">
          <div class="detail-row">
            • <b>均价变化轨迹</b>：从 ¥{{ Number(positionData.costPrice).toFixed(4) }}
            <span v-if="buyResult.isCostLowered" class="green-text">有效拉低至 <b>¥{{ buyResult.newCostPrice.toFixed(4) }}</b> (下降了 {{ Math.abs(buyResult.costDiffPct) }}%)</span>
            <span v-else class="orange-text">被抬高至 <b>¥{{ buyResult.newCostPrice.toFixed(4) }}</b> (上升了 +{{ buyResult.costDiffPct }}%)</span>。
          </div>
          <div class="detail-row">
            • <b>做T保本线轨迹</b>：做T摊薄保本价从 ¥{{ Number(positionData.dilutedCostPrice || positionData.costPrice).toFixed(3) }} 变为 <b>¥{{ buyResult.newDilutedCostPrice.toFixed(3) }}</b>。
          </div>
          <div class="detail-row">
            • <b>仓位总体规模</b>：持仓从 {{ positionData.holdQuantity }} 股增至 <b>{{ buyResult.newTotalQuantity }}</b> 股，总投入本金由 ¥{{ Number(positionData.totalCost).toFixed(2) }} 增至 <b>¥{{ buyResult.newTotalCost.toFixed(2) }}</b>。
          </div>
          <div class="detail-row eval-row">
            • <b>辅助决策评估</b>：
            <span v-if="buyResult.isCostLowered && Math.abs(buyResult.costDiffPct) >= 1.5" class="green-text bold">
              🎯 本次加仓能有效压低持仓成本线，极具摊薄价值，建议结合 S1 支撑位挂单买入。
            </span>
            <span v-else-if="buyResult.isCostLowered" class="blue-text bold">
              💡 本次加仓对成本有小幅优化，符合波段定投节奏。
            </span>
            <span v-else class="orange-text bold">
              ⚠️ 注意：拟买入价高于当前买入均价，属于向上追涨加仓，会抬高平均成本，请做好止盈止损规划。
            </span>
          </div>
        </div>
      </div>
    </div>

    <!-- ========================================================= -->
    <!-- 模式二：做T高抛减仓测算                                     -->
    <!-- ========================================================= -->
    <div v-else-if="calcMode === 'sell'" class="mode-calc-content">
      <el-form label-width="110px" label-position="right" size="default">
        <!-- 价格输入与快捷高抛点位 -->
        <el-form-item label="拟卖出单价(元)">
          <div class="input-with-pills">
            <el-input-number
              v-model="simSellPrice"
              :precision="4"
              :step="0.001"
              :min="0.0001"
              style="width: 220px;"
              placeholder="拟卖出价"
            />
            <div class="quick-pill-group">
              <span v-if="currentPriceNum" class="quick-pill" @click="simSellPrice = currentPriceNum">现价 ¥{{ currentPriceNum.toFixed(3) }}</span>
              <span v-if="currentPriceNum" class="quick-pill" @click="simSellPrice = +(currentPriceNum * 1.01).toFixed(3)">+1% 冲高 ¥{{ (currentPriceNum * 1.01).toFixed(3) }}</span>
              <span v-if="currentPriceNum" class="quick-pill" @click="simSellPrice = +(currentPriceNum * 1.02).toFixed(3)">+2% 冲高 ¥{{ (currentPriceNum * 1.02).toFixed(3) }}</span>
              <span v-if="pivotPoints && pivotPoints.r1" class="quick-pill pivot-pill r-pill" @click="simSellPrice = Number(pivotPoints.r1)">
                🎯 R1阻力 ¥{{ Number(pivotPoints.r1).toFixed(3) }}
              </span>
              <span v-if="pivotPoints && pivotPoints.r2" class="quick-pill pivot-pill r-pill" @click="simSellPrice = Number(pivotPoints.r2)">
                🚀 R2极值 ¥{{ Number(pivotPoints.r2).toFixed(3) }}
              </span>
            </div>
          </div>
        </el-form-item>

        <!-- 数量输入与快捷减仓比例 -->
        <el-form-item label="拟卖出数量(股)">
          <div class="input-with-pills">
            <el-input-number
              v-model="simSellQuantity"
              :step="1000"
              :min="100"
              :max="positionData.holdQuantity || 1000000"
              style="width: 220px;"
              placeholder="不超过持仓"
            />
            <div class="quick-pill-group">
              <span class="quick-pill" @click="simSellQuantity = Math.min(1000, positionData.holdQuantity)">-1000 股</span>
              <span class="quick-pill" @click="simSellQuantity = Math.min(2000, positionData.holdQuantity)">-2000 股</span>
              <span class="quick-pill" @click="simSellQuantity = Math.min(5000, positionData.holdQuantity)">-5000 股</span>
              <span v-if="positionData.holdQuantity > 0" class="quick-pill" @click="simSellQuantity = getQuarterHolding(positionData.holdQuantity)">减 1/4 ({{ getQuarterHolding(positionData.holdQuantity) }})</span>
              <span v-if="positionData.holdQuantity > 0" class="quick-pill" @click="simSellQuantity = getHalfHolding(positionData.holdQuantity)">减 半仓 ({{ getHalfHolding(positionData.holdQuantity) }})</span>
              <span v-if="positionData.holdQuantity > 0" class="quick-pill danger-pill" @click="simSellQuantity = positionData.holdQuantity">全部清仓 ({{ positionData.holdQuantity }})</span>
            </div>
          </div>
        </el-form-item>
      </el-form>

      <!-- 做T高抛推演结果卡片 -->
      <div v-if="sellResult" class="result-dashboard-card">
        <div class="dashboard-header">
          <span class="dash-title">📊 做T高抛落袋推演分析</span>
          <el-tag :type="sellResult.isProfitable ? 'danger' : 'info'" effect="dark" size="small">
            {{ sellResult.isProfitable ? '💰 止盈锁定利润' : '🛡️ 减仓防守避险' }}
          </el-tag>
        </div>

        <div class="dash-metrics-grid">
          <div class="metric-item">
            <div class="metric-label">本次收回现金</div>
            <div class="metric-val num-font blue-text">¥ {{ sellResult.cashReturned.toFixed(2) }}</div>
          </div>
          <div class="metric-item">
            <div class="metric-label">本次锁定落袋利润</div>
            <div class="metric-val num-font" :class="sellResult.profitOnTrade >= 0 ? 'red-text' : 'green-text'">
              {{ sellResult.profitOnTrade >= 0 ? '+' : '' }}{{ sellResult.profitOnTrade.toFixed(2) }} <span class="unit">元</span>
            </div>
            <div class="metric-sub">相对买入均价</div>
          </div>
          <div class="metric-item">
            <div class="metric-label">剩余持仓做T保本价</div>
            <div class="metric-val num-font t-yellow">
              {{ sellResult.isAllSold ? '已清仓' : '¥ ' + sellResult.newDilutedCostPrice.toFixed(3) }}
            </div>
            <div v-if="!sellResult.isAllSold" class="metric-sub green-text">
              保本线再砸低 ¥{{ sellResult.dilutedDrop.toFixed(4) }}
            </div>
            <div v-else class="metric-sub">本轮交易圆满结束</div>
          </div>
          <div class="metric-item">
            <div class="metric-label">剩余持仓股数</div>
            <div class="metric-val num-font">
              {{ sellResult.remainQuantity }} <span class="unit">股</span>
            </div>
            <div class="metric-sub">减持 {{ sellResult.soldRatio }}% 仓位</div>
          </div>
        </div>

        <div class="dash-details-box">
          <div class="detail-row">
            • <b>现金回笼与利润锁定</b>：本次减仓成功收回现金 <b>¥{{ sellResult.cashReturned.toFixed(2) }}</b>，锁定落袋净利 <b>{{ sellResult.profitOnTrade >= 0 ? '+' : '' }}{{ sellResult.profitOnTrade.toFixed(2) }} 元</b>。
          </div>
          <div v-if="!sellResult.isAllSold" class="detail-row">
            • <b>做T神效（保本线大幅拉低）</b>：由于本次落袋利润冲抵了总成本，剩余 <b>{{ sellResult.remainQuantity }} 股</b> 的做T保本价从 ¥{{ Number(positionData.dilutedCostPrice || positionData.costPrice).toFixed(3) }} 进一步下移至 <b>¥{{ sellResult.newDilutedCostPrice.toFixed(3) }}</b>，心理优势大幅提升！
          </div>
          <div v-else class="detail-row">
            • <b>清仓结算</b>：当前持仓已全部清空，本标的累计总净利润全部锁死落袋！
          </div>
          <div class="detail-row eval-row">
            • <b>辅助决策评估</b>：
            <span v-if="sellResult.isProfitable" class="green-text bold">
              🎉 经典高抛做T策略！逢高落袋部分利润，让剩余筹码处于极低保本位（¥{{ sellResult.newDilutedCostPrice.toFixed(3) }}），进可攻退可守！
            </span>
            <span v-else class="orange-text bold">
              🛡️ 减仓降低风险敞口，收回现金以备低位再次寻机回补。
            </span>
          </div>
        </div>
      </div>
    </div>

    <!-- ========================================================= -->
    <!-- 模式三：目标成本反向倒推 (Goal-Seeking)                     -->
    <!-- ========================================================= -->
    <div v-else-if="calcMode === 'reverse'" class="mode-calc-content">
      <el-alert
        title="🎯 目标倒推说明：输入您期望达到的目标买入均价与拟买入单价，系统自动反算出所需补仓手数与准备资金！"
        type="info"
        show-icon
        :closable="false"
        style="margin-bottom: 14px;"
      />

      <el-form label-width="120px" label-position="right" size="default">
        <el-form-item label="期望目标均价(元)">
          <div class="input-with-pills">
            <el-input-number
              v-model="reverseTargetPrice"
              :precision="4"
              :step="0.005"
              :min="0.0001"
              style="width: 220px;"
              placeholder="期望拉低到的均价"
            />
            <div class="quick-pill-group">
              <span v-if="positionData.costPrice > 0" class="quick-pill" @click="reverseTargetPrice = +(positionData.costPrice * 0.98).toFixed(3)">下移2% ¥{{ (positionData.costPrice * 0.98).toFixed(3) }}</span>
              <span v-if="positionData.costPrice > 0" class="quick-pill" @click="reverseTargetPrice = +(positionData.costPrice * 0.95).toFixed(3)">下移5% ¥{{ (positionData.costPrice * 0.95).toFixed(3) }}</span>
              <span v-if="positionData.costPrice > 0" class="quick-pill" @click="reverseTargetPrice = +(positionData.costPrice * 0.90).toFixed(3)">下移10% ¥{{ (positionData.costPrice * 0.90).toFixed(3) }}</span>
            </div>
          </div>
        </el-form-item>

        <el-form-item label="预估拟买入价(元)">
          <div class="input-with-pills">
            <el-input-number
              v-model="reverseBuyPrice"
              :precision="4"
              :step="0.001"
              :min="0.0001"
              style="width: 220px;"
              placeholder="加仓执行价格"
            />
            <div class="quick-pill-group">
              <span v-if="currentPriceNum" class="quick-pill" @click="reverseBuyPrice = currentPriceNum">现价 ¥{{ currentPriceNum.toFixed(3) }}</span>
              <span v-if="pivotPoints && pivotPoints.s1" class="quick-pill pivot-pill" @click="reverseBuyPrice = Number(pivotPoints.s1)">
                🎯 S1支撑 ¥{{ Number(pivotPoints.s1).toFixed(3) }}
              </span>
              <span v-if="pivotPoints && pivotPoints.s2" class="quick-pill pivot-pill" @click="reverseBuyPrice = Number(pivotPoints.s2)">
                🛡️ S2极限 ¥{{ Number(pivotPoints.s2).toFixed(3) }}
              </span>
            </div>
          </div>
        </el-form-item>
      </el-form>

      <!-- 反向倒推结果看板 -->
      <div v-if="reverseResult" class="result-dashboard-card">
        <template v-if="reverseResult.possible">
          <div class="dashboard-header">
            <span class="dash-title">🎯 反向倒推达成方案</span>
            <el-tag type="success" effect="dark" size="small">计算成功</el-tag>
          </div>

          <div class="dash-metrics-grid">
            <div class="metric-item">
              <div class="metric-label">所需补仓股数</div>
              <div class="metric-val num-font blue-text">{{ reverseResult.requiredQuantity }} <span class="unit">股</span></div>
              <div class="metric-sub">约 {{ reverseResult.requiredQuantity / 100 }} 手</div>
            </div>
            <div class="metric-item">
              <div class="metric-label">需准备资金</div>
              <div class="metric-val num-font orange-text">¥ {{ reverseResult.requiredFunds.toFixed(2) }}</div>
              <div class="metric-sub">按买入价 ¥{{ reverseBuyPrice }}</div>
            </div>
            <div class="metric-item">
              <div class="metric-label">达成后新持仓均价</div>
              <div class="metric-val num-font green-text">¥ {{ reverseResult.actualResultCost.toFixed(4) }}</div>
              <div class="metric-sub">成功锁定目标均价</div>
            </div>
            <div class="metric-item">
              <div class="metric-label">达成后总持仓</div>
              <div class="metric-val num-font">{{ reverseResult.newTotalQuantity }} <span class="unit">股</span></div>
              <div class="metric-sub">总投入 ¥{{ reverseResult.newTotalCost.toFixed(2) }}</div>
            </div>
          </div>

          <div class="dash-details-box">
            <div class="detail-row">
              • <b>执行路径</b>：若在 <b>¥{{ reverseBuyPrice }}</b> 挂单买入 <b>{{ reverseResult.requiredQuantity }} 股</b>（需资金 <b>¥{{ reverseResult.requiredFunds.toFixed(2) }}</b>），您的持仓均价将从 ¥{{ Number(positionData.costPrice).toFixed(4) }} 精准压低至 <b>¥{{ reverseResult.actualResultCost.toFixed(4) }}</b>！
            </div>
          </div>
        </template>

        <template v-else>
          <el-alert
            :title="reverseResult.reason"
            type="warning"
            show-icon
            :closable="false"
          />
        </template>
      </div>
    </div>

    <!-- 弹窗底部操作按钮 -->
    <template #footer>
      <div class="dialog-footer-row">
        <el-button @click="visible = false">关闭</el-button>
        <el-button
          v-if="calcMode === 'buy' && buyResult"
          type="success"
          @click="applyTrade('BUY', simBuyPrice, simBuyQuantity, '低位补仓摊薄')"
        >
          以测算值去记账买入 ({{ simBuyQuantity }}股 / ¥{{ simBuyPrice }})
        </el-button>
        <el-button
          v-else-if="calcMode === 'sell' && sellResult"
          type="danger"
          @click="applyTrade('SELL', simSellPrice, simSellQuantity, '做T高抛减仓')"
        >
          以测算值去记账卖出 ({{ simSellQuantity }}股 / ¥{{ simSellPrice }})
        </el-button>
        <el-button
          v-else-if="calcMode === 'reverse' && reverseResult && reverseResult.possible"
          type="primary"
          @click="applyTrade('BUY', reverseBuyPrice, reverseResult.requiredQuantity, '目标成本低吸')"
        >
          以倒推结果去记账买入 ({{ reverseResult.requiredQuantity }}股 / ¥{{ reverseBuyPrice }})
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, reactive, computed } from 'vue'
import { getQuoteDetail, getKlineChart } from '../api'

const emit = defineEmits(['apply-trade'])

const visible = ref(false)
const calcMode = ref('buy') // 'buy' | 'sell' | 'reverse'
const realQuote = ref(null)
const pivotPoints = ref(null)

const positionData = reactive({
  symbol: '',
  name: '',
  holdQuantity: 0,
  costPrice: 0,
  totalCost: 0,
  dilutedCostPrice: null,
  dilutedTotalCost: null,
})

// 模式一：买入拟输入
const simBuyPrice = ref(null)
const simBuyQuantity = ref(null)

// 模式二：卖出拟输入
const simSellPrice = ref(null)
const simSellQuantity = ref(null)

// 模式三：目标倒推拟输入
const reverseTargetPrice = ref(null)
const reverseBuyPrice = ref(null)

const currentPriceNum = computed(() => {
  if (realQuote.value && realQuote.value.currentPrice) {
    return Number(realQuote.value.currentPrice)
  }
  return null
})

const savedProfit = computed(() => {
  const c0 = Number(positionData.totalCost) || 0
  const d0 = Number(positionData.dilutedTotalCost) || c0
  return Math.max(0, +(c0 - d0).toFixed(2))
})

function resolveMarketTag(sym) {
  if (!sym) return 'SH'
  if (sym.startsWith('6') || sym.startsWith('5')) return 'SH'
  if (sym.startsWith('0') || sym.startsWith('3') || sym.startsWith('1')) return 'SZ'
  return 'BJ'
}

function getQuarterHolding(qty) {
  return Math.max(100, Math.round(qty * 0.25 / 100) * 100)
}

function getHalfHolding(qty) {
  return Math.max(100, Math.round(qty * 0.5 / 100) * 100)
}

async function open(pos) {
  visible.value = true
  calcMode.value = 'buy'
  realQuote.value = null
  pivotPoints.value = null

  positionData.symbol = pos.symbol
  positionData.name = pos.name
  positionData.holdQuantity = pos.holdQuantity || 0
  positionData.costPrice = pos.costPrice || 0
  positionData.totalCost = pos.totalCost || 0
  positionData.dilutedCostPrice = pos.dilutedCostPrice || pos.costPrice
  positionData.dilutedTotalCost = pos.dilutedTotalCost || pos.totalCost

  const initialPrice = pos.currentPrice ? Number(pos.currentPrice) : +(positionData.costPrice * 0.98).toFixed(3)
  simBuyPrice.value = initialPrice
  // 默认买入手数：优先建议 1000 股或 1/4 仓位，不再粗暴默认全仓
  simBuyQuantity.value = pos.holdQuantity >= 4000 ? getQuarterHolding(pos.holdQuantity) : 1000

  simSellPrice.value = +(initialPrice * 1.015).toFixed(3)
  simSellQuantity.value = pos.holdQuantity >= 4000 ? getQuarterHolding(pos.holdQuantity) : Math.min(1000, pos.holdQuantity)

  reverseTargetPrice.value = +(positionData.costPrice * 0.96).toFixed(3)
  reverseBuyPrice.value = initialPrice

  // 异步获取最新实时行情与次日 Pivot Points 网格点位
  try {
    const qPromise = getQuoteDetail(pos.symbol)
    const klinePromise = getKlineChart(pos.symbol)
    const [q, kline] = await Promise.allSettled([qPromise, klinePromise])

    if (q.status === 'fulfilled' && q.value && q.value.currentPrice) {
      realQuote.value = q.value
      simBuyPrice.value = Number(q.value.currentPrice)
      simSellPrice.value = +(Number(q.value.currentPrice) * 1.015).toFixed(3)
      reverseBuyPrice.value = Number(q.value.currentPrice)
    }

    if (kline.status === 'fulfilled' && kline.value && kline.value.pivotPoints) {
      pivotPoints.value = kline.value.pivotPoints
      // 若处于空头或回踩，自动建议以 S1 支撑位作为拟加仓买入价
      if (pivotPoints.value.s1) {
        simBuyPrice.value = Number(pivotPoints.value.s1)
      }
      if (pivotPoints.value.r1) {
        simSellPrice.value = Number(pivotPoints.value.r1)
      }
    }
  } catch (err) {
    console.warn('获取测算辅助行情失败:', err)
  }
}

// -------------------------------------------------------------
// 1. 买入加仓测算计算逻辑 (彻底修复倒挂与判定 Bug)
// -------------------------------------------------------------
const buyResult = computed(() => {
  if (!simBuyPrice.value || !simBuyQuantity.value || simBuyQuantity.value <= 0) return null

  const q0 = Number(positionData.holdQuantity) || 0
  const c0 = Number(positionData.totalCost) || 0
  const p0 = Number(positionData.costPrice) || 0
  const d0 = Number(positionData.dilutedTotalCost) || c0

  const q1 = Number(simBuyQuantity.value)
  const p1 = Number(simBuyPrice.value)

  const addCost = +(p1 * q1).toFixed(2)
  const newTotalQuantity = q0 + q1
  const newTotalCost = +(c0 + addCost).toFixed(2)
  const newCostPrice = +(newTotalCost / newTotalQuantity).toFixed(4)

  const newDilutedTotalCost = +(d0 + addCost).toFixed(2)
  const newDilutedCostPrice = +(newDilutedTotalCost / newTotalQuantity).toFixed(4)

  // 均价变动幅度
  const costDiff = +(newCostPrice - p0).toFixed(4)
  const costDiffPct = p0 > 0 ? +((costDiff / p0) * 100).toFixed(2) : 0
  const isCostLowered = costDiff < 0

  // 现价对比与回本/安全垫
  const currPrice = currentPriceNum.value || p1
  let isPriceAboveCost = currPrice >= newCostPrice
  let cushionOrReboundPct = 0

  if (isPriceAboveCost) {
    cushionOrReboundPct = newCostPrice > 0 ? +(((currPrice - newCostPrice) / newCostPrice) * 100).toFixed(2) : 0
  } else {
    cushionOrReboundPct = currPrice > 0 ? +(((newCostPrice - currPrice) / currPrice) * 100).toFixed(2) : 0
  }

  return {
    additionalCost: addCost,
    newTotalCost,
    newTotalQuantity,
    newCostPrice,
    newDilutedCostPrice,
    costDiff,
    costDiffPct,
    isCostLowered,
    isPriceAboveCost,
    cushionOrReboundPct: Math.abs(cushionOrReboundPct),
  }
})

// -------------------------------------------------------------
// 2. 做T高抛减仓测算计算逻辑 (核心亮点：保本线砸低测算)
// -------------------------------------------------------------
const sellResult = computed(() => {
  if (!simSellPrice.value || !simSellQuantity.value || simSellQuantity.value <= 0) return null

  const q0 = Number(positionData.holdQuantity) || 0
  const c0 = Number(positionData.totalCost) || 0
  const p0 = Number(positionData.costPrice) || 0
  const d0 = Number(positionData.dilutedTotalCost) || c0
  const pd0 = Number(positionData.dilutedCostPrice) || p0

  const qSell = Math.min(q0, Number(simSellQuantity.value))
  const pSell = Number(simSellPrice.value)

  const cashReturned = +(pSell * qSell).toFixed(2)
  const profitOnTrade = +((pSell - p0) * qSell).toFixed(2)
  const remainQuantity = q0 - qSell
  const isAllSold = remainQuantity <= 0

  let newDilutedCostPrice = 0
  let dilutedDrop = 0
  if (!isAllSold) {
    const newDilutedTotalCost = +(d0 - cashReturned).toFixed(2)
    newDilutedCostPrice = +(newDilutedTotalCost / remainQuantity).toFixed(4)
    dilutedDrop = +(pd0 - newDilutedCostPrice).toFixed(4)
  }

  const soldRatio = q0 > 0 ? +((qSell / q0) * 100).toFixed(1) : 100

  return {
    cashReturned,
    profitOnTrade,
    remainQuantity,
    isAllSold,
    newDilutedCostPrice,
    dilutedDrop,
    isProfitable: profitOnTrade >= 0,
    soldRatio,
  }
})

// -------------------------------------------------------------
// 3. 目标成本反向倒推计算逻辑 (Goal-Seeking)
// -------------------------------------------------------------
const reverseResult = computed(() => {
  if (!reverseTargetPrice.value || !reverseBuyPrice.value) return null

  const q0 = Number(positionData.holdQuantity) || 0
  const c0 = Number(positionData.totalCost) || 0
  const p0 = Number(positionData.costPrice) || 0

  const targetP = Number(reverseTargetPrice.value)
  const buyP = Number(reverseBuyPrice.value)

  if (targetP >= p0) {
    return {
      possible: false,
      reason: `期望目标均价 (¥${targetP}) 大于或等于当前成本均价 (¥${p0.toFixed(4)})，无需补仓拉低。`,
    }
  }

  if (targetP <= buyP) {
    return {
      possible: false,
      reason: `在拟买入价为 ¥${buyP} 时，加仓后的理论极限成本无限逼近于 ¥${buyP}，数学上不可能将均价压低到 ¥${buyP} 或更低。建议降低拟买入价或适当放宽目标均价。`,
    }
  }

  // 严格数学公式: Q1 = Q0 * (P0 - TargetP) / (TargetP - BuyP)
  const rawQ1 = (q0 * (p0 - targetP)) / (targetP - buyP)
  const reqQty = Math.max(100, Math.ceil(rawQ1 / 100) * 100)
  const reqFunds = +(reqQty * buyP).toFixed(2)
  const newTotalCost = +(c0 + reqFunds).toFixed(2)
  const newTotalQuantity = q0 + reqQty
  const actualResultCost = +(newTotalCost / newTotalQuantity).toFixed(4)

  return {
    possible: true,
    requiredQuantity: reqQty,
    requiredFunds: reqFunds,
    newTotalQuantity,
    newTotalCost,
    actualResultCost,
  }
})

function applyTrade(action, price, quantity, strategyTag) {
  visible.value = false
  emit('apply-trade', {
    action,
    symbol: positionData.symbol,
    name: positionData.name,
    price: Number(price),
    quantity: Number(quantity),
    strategyTag,
  })
}

defineExpose({
  open,
})
</script>

<style scoped>
.cost-simulator-dialog :deep(.el-dialog__body) {
  padding: 12px 20px 18px;
}

/* 顶部标的信息与持仓生命线横幅 */
.position-profile-banner {
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-left: 4px solid #3b82f6;
  border-radius: 8px;
  padding: 12px 16px;
  margin-bottom: 14px;
}

.banner-top-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 10px;
}

.stock-name-box {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.stock-title {
  font-size: 17px;
  font-weight: bold;
  color: #0f172a;
}

.stock-code {
  font-size: 14px;
  font-weight: 600;
  color: #64748b;
}

.stock-real-price {
  font-size: 13px;
  display: flex;
  align-items: baseline;
  gap: 6px;
}

.price-label {
  color: #64748b;
}

.price-num {
  font-size: 16px;
  font-weight: 700;
}

.banner-stats-grid {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 8px;
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  padding: 8px 12px;
}

.stat-col {
  display: flex;
  flex-direction: column;
}

.stat-label {
  font-size: 11px;
  color: #64748b;
  margin-bottom: 2px;
}

.stat-val {
  font-size: 13px;
  font-weight: 700;
  color: #1e293b;
}

.highlight-col {
  background: #fffbeb;
  border-radius: 4px;
  padding: 2px 6px;
}

.cost-blue { color: #2563eb; }
.t-yellow { color: #d97706; }
.unit { font-size: 11px; font-weight: normal; color: #64748b; }

/* 模式切换器 */
.mode-tabs-container {
  display: flex;
  justify-content: center;
  margin-bottom: 16px;
}

.mode-radio-group :deep(.el-radio-button__inner) {
  font-weight: 600;
  padding: 8px 20px;
}

.tab-btn-content {
  font-size: 13px;
}

/* 输入项与快捷胶囊组合 */
.input-with-pills {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  width: 100%;
}

.quick-pill-group {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.quick-pill {
  font-size: 11px;
  font-weight: 500;
  color: #475569;
  background: #f1f5f9;
  border: 1px solid #cbd5e1;
  padding: 2px 8px;
  border-radius: 12px;
  cursor: pointer;
  user-select: none;
  transition: all 0.15s ease;
}

.quick-pill:hover {
  background: #e2e8f0;
  color: #0f172a;
  border-color: #94a3b8;
}

.pivot-pill {
  background: #eff6ff;
  border-color: #bfdbfe;
  color: #1e40af;
}
.pivot-pill:hover {
  background: #dbeafe;
}

.r-pill {
  background: #fffbeb;
  border-color: #fde68a;
  color: #b45309;
}
.r-pill:hover {
  background: #fef3c7;
}

.danger-pill {
  background: #fef2f2;
  border-color: #fecaca;
  color: #b91c1c;
}
.danger-pill:hover {
  background: #fee2e2;
}

/* 测算结果看板 */
.result-dashboard-card {
  background: #fafafa;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  padding: 14px 16px;
  margin-top: 14px;
}

.dashboard-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.dash-title {
  font-size: 14px;
  font-weight: 700;
  color: #1e293b;
}

.dash-metrics-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  text-align: center;
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  padding: 12px 10px;
  margin-bottom: 12px;
}

.metric-item {
  display: flex;
  flex-direction: column;
  align-items: center;
}

.metric-label {
  font-size: 11px;
  color: #64748b;
  margin-bottom: 4px;
}

.metric-val {
  font-size: 19px;
  font-weight: 800;
  line-height: 1.2;
}

.metric-sub {
  font-size: 10px;
  color: #94a3b8;
  margin-top: 2px;
}

.blue-text { color: #2563eb; }
.green-text { color: #16a34a; }
.orange-text { color: #d97706; }
.red-text { color: #dc2626; }
.bold { font-weight: 700; }

.dash-details-box {
  font-size: 12.5px;
  color: #475569;
  line-height: 1.7;
}

.detail-row {
  margin-bottom: 4px;
}

.eval-row {
  margin-top: 6px;
  padding-top: 6px;
  border-top: 1px dashed #e2e8f0;
}

.dialog-footer-row {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  width: 100%;
}

.up-color { color: #ef4444 !important; }
.down-color { color: #22c55e !important; }

.num-font {
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
}
</style>

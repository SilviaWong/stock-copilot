<template>
  <el-dialog
    v-model="visible"
    title="📷 智能导入交易流水 (截图视觉解析 / 文本对账单)"
    width="880px"
    destroy-on-close
    :close-on-click-modal="false"
  >
    <!-- 顶部识别引擎设置抽屉/折叠面板 -->
    <div class="model-config-bar">
      <div class="config-summary">
        <span style="font-size: 13px; color: #606266;">
          当前代理引擎:
          <el-tag size="small" type="primary" effect="plain" style="margin: 0 4px;">
            {{ getProtocolLabel(aiConfig.protocol) }}
          </el-tag>
          <b>{{ aiConfig.model || '未配置' }}</b>
          <span style="color: #909399; margin-left: 6px;">
            ({{ aiConfig.protocol === 'CUSTOM' ? (aiConfig.customEndpoint || '自定义端点') : (aiConfig.baseUrl || '默认地址') }})
          </span>
        </span>
      </div>
      <el-button link type="primary" size="small" @click="showConfig = !showConfig">
        ⚙️ {{ showConfig ? '收起设置' : '自定义代理模型配置与协议选择' }}
      </el-button>
    </div>

    <!-- 展开的模型配置区 -->
    <el-collapse-transition>
      <div v-if="showConfig" class="config-box">
        <el-form label-width="110px" size="small">
          <el-row :gutter="12">
            <el-col :span="12">
              <el-form-item label="代理请求协议">
                <el-select v-model="aiConfig.protocol" placeholder="选择协议" style="width: 100%;">
                  <el-option label="OpenAI 兼容协议 (OneAPI/中转站聚合网关推荐)" value="OPENAI" />
                  <el-option label="Google Gemini 协议 (/v1beta 官方或原生反代)" value="GEMINI" />
                  <el-option label="Anthropic Claude 协议 (/v1/messages)" value="CLAUDE" />
                  <el-option label="Ollama 本地视觉模型 (/api/chat)" value="OLLAMA" />
                  <el-option label="自定义完整 URL (手动输入精确路径)" value="CUSTOM" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="视觉模型名称">
                <el-input
                  v-model="aiConfig.model"
                  :placeholder="getModelPlaceholder()"
                />
              </el-form-item>
            </el-col>
          </el-row>

          <el-form-item v-if="aiConfig.protocol !== 'CUSTOM'" label="代理 Base URL">
            <el-input
              v-model="aiConfig.baseUrl"
              placeholder="如 https://api.openai.com/v1 或自己的中转域名"
            />
          </el-form-item>

          <el-form-item v-else label="自定义完整 URL">
            <el-input
              v-model="aiConfig.customEndpoint"
              placeholder="如 https://my-proxy.com/api/v2/chat/completions"
            />
          </el-form-item>

          <!-- 实时展示计算出的最终请求地址，排查 404 -->
          <div class="endpoint-preview-alert">
            <div class="preview-title">
              <span style="font-weight: bold;">🌐 最终请求端点 (Target Endpoint):</span>
              <span style="margin-left: 8px; font-size: 12px; color: #67c23a;">
                (系统已根据协议智能规范化 URL 路径，自动防止缺少 /v1 导致的 404)
              </span>
            </div>
            <div class="preview-url num-font">{{ targetUrlPreview }}</div>
          </div>

          <el-form-item label="API Key" style="margin-top: 10px;">
            <el-input
              v-model="aiConfig.apiKey"
              type="password"
              show-password
              placeholder="sk-xxxxxx (保存在浏览器本地，绝不泄露)"
            />
          </el-form-item>

          <!-- 连通性测试报告面板 -->
          <el-alert
            v-if="testResult"
            :title="testResult.success ? `✅ 连通性测试通过 (HTTP ${testResult.statusCode || 200}, 耗时 ${testResult.latencyMs}ms)` : `❌ 连通性测试未通过 (HTTP ${testResult.statusCode || '异常'})`"
            :type="testResult.success ? 'success' : 'error'"
            :closable="false"
            show-icon
            style="margin-bottom: 12px;"
          >
            <div style="font-size: 12px; line-height: 1.6; white-space: pre-wrap;">
              <div><b>测试地址:</b> {{ testResult.targetUrl || targetUrlPreview }}</div>
              <div><b>诊断信息:</b> {{ testResult.message }}</div>
              <div v-if="testResult.raw" style="color: #909399; margin-top: 4px;">
                <b>服务端原始响应片段:</b> {{ testResult.raw }}
              </div>
            </div>
          </el-alert>

          <div style="display: flex; justify-content: space-between; align-items: center;">
            <el-button
              size="small"
              type="warning"
              plain
              :loading="testingConnection"
              @click="handleTestConnection"
            >
              🔍 测试连通性 (诊断 404 / 401)
            </el-button>
            <div style="display: flex; gap: 8px;">
              <el-button size="small" @click="showConfig = false">收起</el-button>
              <el-button size="small" type="primary" @click="saveAiConfig">保存设置</el-button>
            </div>
          </div>
        </el-form>
      </div>
    </el-collapse-transition>

    <!-- 标的归属指定区 (解决单股成交明细截图无标的代码/名称问题) -->
    <div class="target-stock-selector-box">
      <div class="target-selector-header">
        <span style="font-weight: bold; font-size: 13px; color: #303133;">🎯 流水归属标的：</span>
        <el-radio-group v-model="targetMode" size="small">
          <el-radio-button value="SPECIFIC">🏢 指定具体标的 (单股明细截图推荐)</el-radio-button>
          <el-radio-button value="AUTO">🌐 自动识别标的 (多标的综合对账单)</el-radio-button>
        </el-radio-group>
      </div>

      <!-- 指定具体标的时的输入与选择区 -->
      <div v-if="targetMode === 'SPECIFIC'" class="specific-target-panel">
        <div style="display: flex; gap: 10px; align-items: center; flex-wrap: wrap;">
          <el-autocomplete
            v-model="targetStock.symbol"
            :fetch-suggestions="queryStockSuggestions"
            placeholder="搜索或输入6位代码，如 510300"
            size="small"
            style="width: 250px;"
            clearable
            @select="handleStockSelect"
          >
            <template #default="{ item }">
              <span style="font-weight: bold; margin-right: 8px;">{{ item.symbol }}</span>
              <span style="color: #909399;">{{ item.name }}</span>
            </template>
          </el-autocomplete>

          <el-input
            v-model="targetStock.name"
            placeholder="标的名称，如 沪深300ETF"
            size="small"
            style="width: 170px;"
          />

          <div v-if="targetStock.symbol" class="selected-badge">
            <span style="color: #67c23a; font-weight: bold;">✔ 当前已绑定:</span>
            <span style="margin-left: 4px; font-weight: bold;">{{ targetStock.symbol }}</span>
            <span style="margin-left: 4px; color: #606266;">{{ targetStock.name }}</span>
          </div>
        </div>

        <!-- 快速选择已有持仓 -->
        <div v-if="existingPositions.length > 0" class="quick-position-tags">
          <span style="font-size: 12px; color: #909399;">快速选择已有持仓:</span>
          <el-tag
            v-for="pos in existingPositions"
            :key="pos.symbol"
            size="small"
            class="pos-tag-btn"
            :type="targetStock.symbol === pos.symbol ? 'success' : 'info'"
            :effect="targetStock.symbol === pos.symbol ? 'dark' : 'plain'"
            @click="selectQuickStock(pos)"
          >
            {{ pos.symbol }} {{ pos.name }}
          </el-tag>
        </div>

        <div class="target-tip">
          💡 <b>小提示</b>：手机券商单只股票的“成交流水/成交明细”页面截图通常<b>不含标的代码与名称</b>。在此指定后，系统将自动把截图识别出的所有买入/卖出流水全部归属于该标的！
        </div>
      </div>
    </div>

    <!-- 输入方式 Tab：截图识别 vs 文本对账单 -->
    <el-tabs v-model="inputTab" class="import-tabs">
      <el-tab-pane label="🖼️ 手机券商截图识别" name="screenshot">
        <div
          class="drop-area"
          tabindex="0"
          @paste="handlePaste"
          @drop.prevent="handleDrop"
          @dragover.prevent
        >
          <div v-if="!imagePreview" class="drop-placeholder">
            <div style="font-size: 36px; margin-bottom: 8px;">📋</div>
            <div style="font-weight: bold; font-size: 15px; color: #303133;">
              直接在此处按 <kbd>Cmd + V</kbd> 或 <kbd>Ctrl + V</kbd> 粘贴截图
            </div>
            <div style="color: #909399; font-size: 13px; margin-top: 6px;">
              或者将券商 App 成交记录截图拖拽至此处，支持常见同花顺、华泰、招商、东财等
            </div>
            <el-upload
              action="#"
              :auto-upload="false"
              :show-file-list="false"
              :on-change="handleFileChange"
              style="margin-top: 12px;"
            >
              <el-button size="small" type="primary" plain>选择本地图片文件</el-button>
            </el-upload>
          </div>

          <!-- 已有图片预览 -->
          <div v-else class="preview-wrap">
            <img :src="imagePreview" alt="截图预览" class="preview-img" />
            <div class="preview-actions">
              <span v-if="imageSizeKb" style="font-size: 12px; color: #67c23a; margin-right: 12px; font-weight: bold;">
                ⚡ 图像已智能高清优化 (约 {{ imageSizeKb }} KB，杜绝超限 400 错误)
              </span>
              <el-button size="small" type="danger" plain @click="clearImage">重新选择</el-button>
              <el-button
                size="small"
                type="primary"
                :loading="parsing"
                @click="startOcrParse"
              >
                🚀 开始智能多模态识别
              </el-button>
            </div>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="📝 复制对账单文本解析" name="text">
        <div style="padding: 10px 0;">
          <el-input
            v-model="rawText"
            type="textarea"
            :rows="5"
            placeholder="直接复制券商电脑端或网页版对账单/成交流水文本粘贴至此处，例如：&#10;2024-03-15 10:24:12 证券买入 510300 沪深300ETF 3.850 1000股 成交金额3850.00&#10;2024-03-18 14:10:05 证券买入 159915 创业板ETF 1.820 2000股"
          />
          <div style="margin-top: 10px; text-align: right;">
            <el-button type="primary" size="small" :loading="parsing" @click="startTextParse">
              ⚡ 解析纯文本
            </el-button>
          </div>
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- 识别结果确认与校对表格 (Human-in-the-loop) -->
    <div v-if="parsedList.length > 0" class="result-section">
      <div class="result-header">
        <div style="font-weight: bold; font-size: 14px; color: #303133;">
          📋 识别结果校对 (共 {{ parsedList.length }} 笔，已选中 {{ selectedItems.length }} 笔)
        </div>
        <div style="display: flex; gap: 8px;">
          <el-button size="small" :icon="Plus" @click="addNewRow">手动补录一行</el-button>
          <el-button size="small" type="danger" plain @click="parsedList = []">清空重来</el-button>
        </div>
      </div>

      <el-table
        ref="tableRef"
        :data="parsedList"
        size="small"
        border
        stripe
        style="width: 100%; margin-top: 10px;"
        max-height="340px"
        @selection-change="handleSelectionChange"
      >
        <el-table-column type="selection" width="45" align="center" />

        <el-table-column label="动作" width="100" align="center">
          <template #default="{ row }">
            <el-select v-model="row.action" size="small">
              <el-option label="🟢 买入" value="BUY" />
              <el-option label="🔴 卖出" value="SELL" />
            </el-select>
          </template>
        </el-table-column>

        <el-table-column label="标的代码" width="110">
          <template #default="{ row }">
            <el-input v-model="row.symbol" size="small" placeholder="6位代码" />
          </template>
        </el-table-column>

        <el-table-column label="标的名称" width="120">
          <template #default="{ row }">
            <el-input v-model="row.name" size="small" placeholder="名称" />
          </template>
        </el-table-column>

        <el-table-column label="成交单价(元)" width="120">
          <template #default="{ row }">
            <el-input-number
              v-model="row.price"
              :precision="4"
              :step="0.001"
              :min="0.0001"
              size="small"
              style="width: 100%"
              :controls="false"
            />
          </template>
        </el-table-column>

        <el-table-column label="成交数量(股)" width="110">
          <template #default="{ row }">
            <el-input-number
              v-model="row.quantity"
              :step="100"
              :min="1"
              size="small"
              style="width: 100%"
              :controls="false"
            />
          </template>
        </el-table-column>

        <el-table-column label="手续费(元)" width="95">
          <template #default="{ row }">
            <el-input-number
              v-model="row.fee"
              :precision="2"
              :min="0"
              size="small"
              style="width: 100%"
              :controls="false"
            />
          </template>
        </el-table-column>

        <el-table-column label="成交时间" min-width="160">
          <template #default="{ row }">
            <el-input v-model="row.tradeTime" size="small" placeholder="YYYY-MM-DD HH:mm:ss" />
          </template>
        </el-table-column>

        <el-table-column label="操作" width="60" align="center">
          <template #default="{ $index }">
            <el-button link type="danger" size="small" @click="removeRow($index)">
              ✕
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button
        type="primary"
        :disabled="selectedItems.length === 0"
        :loading="submitting"
        @click="handleBatchSubmit"
      >
        确认批量入库 ({{ selectedItems.length }} 笔)
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, reactive, computed, nextTick } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { parseScreenshotTrades, parseTextTrades, batchRecordTrades, testAiConnection, searchStocks, getPositionList } from '../api'

const emit = defineEmits(['success'])

const visible = ref(false)
const inputTab = ref('screenshot')
const showConfig = ref(false)
const parsing = ref(false)
const submitting = ref(false)
const testingConnection = ref(false)
const testResult = ref(null)

const imagePreview = ref('')
const imageSizeKb = computed(() => {
  if (!imagePreview.value) return 0
  return Math.round((imagePreview.value.length * 3) / 4 / 1024)
})
const rawText = ref('')
const parsedList = ref([])
const selectedItems = ref([])
const tableRef = ref(null)

// 标的归属指定
const targetMode = ref('SPECIFIC') // 'SPECIFIC' (指定具体标的) or 'AUTO' (截图自动识别)
const targetStock = reactive({
  symbol: '',
  name: '',
})
const existingPositions = ref([])

async function queryStockSuggestions(queryString, cb) {
  try {
    const res = await searchStocks(queryString)
    cb(res || [])
  } catch {
    cb([])
  }
}

function handleStockSelect(item) {
  targetStock.symbol = item.symbol
  targetStock.name = item.name
}

function selectQuickStock(pos) {
  targetStock.symbol = pos.symbol
  targetStock.name = pos.name
}

async function loadExistingPositions() {
  try {
    const list = await getPositionList(false)
    existingPositions.value = list || []
    if (targetMode.value === 'SPECIFIC' && !targetStock.symbol && list && list.length > 0) {
      targetStock.symbol = list[0].symbol
      targetStock.name = list[0].name
    }
  } catch {
    existingPositions.value = []
  }
}

// 代理大模型配置（保存在本地 localStorage）
const aiConfig = reactive({
  protocol: localStorage.getItem('stock_ai_protocol') || 'OPENAI',
  baseUrl: localStorage.getItem('stock_ai_base_url') || 'https://api.openai.com/v1',
  apiKey: localStorage.getItem('stock_ai_api_key') || '',
  model: localStorage.getItem('stock_ai_model') || 'gpt-4o-mini',
  customEndpoint: localStorage.getItem('stock_ai_custom_endpoint') || '',
})

function getProtocolLabel(proto) {
  switch (proto) {
    case 'CLAUDE': return 'Claude'
    case 'GEMINI': return 'Gemini'
    case 'OLLAMA': return 'Ollama'
    case 'CUSTOM': return '自定义URL'
    case 'OPENAI':
    default:
      return 'OpenAI'
  }
}

function getModelPlaceholder() {
  switch (aiConfig.protocol) {
    case 'CLAUDE': return '如 claude-3-5-sonnet-20241022, claude-3-haiku-20240307'
    case 'GEMINI': return '如 gemini-1.5-flash, gemini-2.0-flash'
    case 'OLLAMA': return '如 llava, minicpm-v, qwen2-vl'
    case 'CUSTOM': return '根据您自定义服务填入模型名称'
    case 'OPENAI':
    default:
      return '如 gpt-4o-mini, qwen-vl-plus, glm-4v'
  }
}

// 实时计算最终请求端点预览 (帮助用户一眼看出是否缺少 /v1)
const targetUrlPreview = computed(() => {
  const protocol = aiConfig.protocol || 'OPENAI'
  if (protocol === 'CUSTOM') {
    return aiConfig.customEndpoint?.trim() || '请输入完整调用 URL'
  }
  let base = (aiConfig.baseUrl || '').trim()
  if (!base) return '请输入代理 Base URL'
  while (base.endsWith('/')) {
    base = base.substring(0, base.length - 1)
  }

  if (protocol === 'CLAUDE') {
    if (base.endsWith('/messages')) return base
    if (base.endsWith('/v1')) return `${base}/messages`
    return `${base}/v1/messages`
  }
  if (protocol === 'GEMINI') {
    const m = aiConfig.model || 'gemini-1.5-flash'
    if (base.includes(':generateContent')) return base
    if (base.endsWith('/v1beta')) return `${base}/models/${m}:generateContent`
    return `${base}/v1beta/models/${m}:generateContent`
  }
  if (protocol === 'OLLAMA') {
    if (base.endsWith('/api/chat')) return base
    return `${base}/api/chat`
  }
  // OPENAI
  if (base.endsWith('/chat/completions')) return base
  if (base.endsWith('/v1')) return `${base}/chat/completions`
  if (base.includes('/v1/')) return `${base}/chat/completions`
  return `${base}/v1/chat/completions`
})

// 测试代理连通性
async function handleTestConnection() {
  testResult.value = null
  testingConnection.value = true
  try {
    const res = await testAiConnection({ ...aiConfig })
    testResult.value = res
    if (res.success) {
      ElMessage.success(`连通测试通过！耗时 ${res.latencyMs}ms`)
    } else {
      ElMessage.error(`连通性测试未通过 (HTTP ${res.statusCode || '异常'})`)
    }
  } catch (err) {
    testResult.value = {
      success: false,
      statusCode: err.response?.status || 'Error',
      message: err.response?.data?.message || err.message || '网络连接超时或无法触达目标服务器',
      targetUrl: targetUrlPreview.value,
    }
  } finally {
    testingConnection.value = false
  }
}

function saveAiConfig() {
  localStorage.setItem('stock_ai_protocol', aiConfig.protocol)
  localStorage.setItem('stock_ai_base_url', aiConfig.baseUrl)
  localStorage.setItem('stock_ai_api_key', aiConfig.apiKey)
  localStorage.setItem('stock_ai_model', aiConfig.model)
  localStorage.setItem('stock_ai_custom_endpoint', aiConfig.customEndpoint || '')
  ElMessage.success('代理模型配置已保存到本地！')
  showConfig.value = false
}

function open(initData = {}) {
  visible.value = true
  imagePreview.value = ''
  rawText.value = ''
  parsedList.value = []
  selectedItems.value = []
  testResult.value = null

  if (initData && initData.symbol) {
    targetMode.value = 'SPECIFIC'
    targetStock.symbol = initData.symbol
    targetStock.name = initData.name || ''
  }

  loadExistingPositions()

  // 如果没有配置 Key，自动展开设置提示
  if (!aiConfig.apiKey && aiConfig.protocol !== 'OLLAMA') {
    showConfig.value = true
  }
}

// 处理剪贴板粘贴
function handlePaste(event) {
  const items = (event.clipboardData || window.clipboardData)?.items
  if (!items) return

  for (let i = 0; i < items.length; i++) {
    if (items[i].type.indexOf('image') !== -1) {
      const file = items[i].getAsFile()
      readImageFile(file)
      event.preventDefault()
      break
    }
  }
}

// 处理拖拽
function handleDrop(event) {
  const files = event.dataTransfer?.files
  if (files && files.length > 0 && files[0].type.startsWith('image/')) {
    readImageFile(files[0])
  }
}

function handleFileChange(uploadFile) {
  readImageFile(uploadFile.raw)
}

function readImageFile(file) {
  if (!file) return
  const reader = new FileReader()
  reader.onload = (e) => {
    const rawDataUrl = e.target.result
    // 智能高保真压缩（最大边限制 1600px，转为 quality 0.88 的 JPEG）
    // 从原始 8~15MB 骤降至 200~350KB，既保证数字与文字极度清晰，又彻底根治上游 400 INVALID_ARGUMENT 限制
    compressImage(rawDataUrl, 1600, 0.88, (compressedDataUrl) => {
      imagePreview.value = compressedDataUrl
    })
  }
  reader.readAsDataURL(file)
}

function compressImage(dataUrl, maxSide, quality, callback) {
  const img = new Image()
  img.onload = () => {
    let width = img.width
    let height = img.height

    if (width > maxSide || height > maxSide) {
      if (width > height) {
        height = Math.round((height * maxSide) / width)
        width = maxSide
      } else {
        width = Math.round((width * maxSide) / height)
        height = maxSide
      }
    }

    const canvas = document.createElement('canvas')
    canvas.width = width
    canvas.height = height
    const ctx = canvas.getContext('2d')

    // 填充白底，防止 PNG 等透明通道在转为 JPEG 时背景变黑
    ctx.fillStyle = '#FFFFFF'
    ctx.fillRect(0, 0, width, height)
    ctx.drawImage(img, 0, 0, width, height)

    try {
      const compressed = canvas.toDataURL('image/jpeg', quality)
      callback(compressed)
    } catch {
      callback(dataUrl)
    }
  }
  img.onerror = () => {
    callback(dataUrl)
  }
  img.src = dataUrl
}

function clearImage() {
  imagePreview.value = ''
  parsedList.value = []
}

// 发起多模态截图解析
async function startOcrParse() {
  if (!imagePreview.value) {
    ElMessage.warning('请先粘贴或上传截图！')
    return
  }
  if (!aiConfig.apiKey && aiConfig.protocol !== 'OLLAMA') {
    showConfig.value = true
    ElMessage.warning('请先填入您的代理模型 API Key！')
    return
  }

  parsing.value = true
  try {
    const list = await parseScreenshotTrades({
      imageBase64: imagePreview.value,
      config: { ...aiConfig },
      targetSymbol: targetMode.value === 'SPECIFIC' ? targetStock.symbol : undefined,
      targetName: targetMode.value === 'SPECIFIC' ? targetStock.name : undefined,
    })
    if (!list || list.length === 0) {
      ElMessage.warning('未能从截图中识别出有效的交易记录，请检查图片清晰度或配置。')
      return
    }
    // 前端二次兜底：若指定了标的，确保每一行都有该标的代码和名称
    if (targetMode.value === 'SPECIFIC' && targetStock.symbol) {
      for (const item of list) {
        if (!item.symbol || !item.symbol.match(/^\d{6}$/)) {
          item.symbol = targetStock.symbol
        }
        if (!item.name && targetStock.name) {
          item.name = targetStock.name
        }
      }
    }
    parsedList.value = list
    ElMessage.success(`识别成功！共提取出 ${list.length} 笔交易，请核对。`)
    // 默认全选
    await nextTick()
    tableRef.value?.toggleAllSelection()
  } catch {
    // handled
  } finally {
    parsing.value = false
  }
}

// 发起纯文本解析
async function startTextParse() {
  if (!rawText.value.trim()) {
    ElMessage.warning('请先粘贴文本内容！')
    return
  }
  parsing.value = true
  try {
    const list = await parseTextTrades(rawText.value)
    if (!list || list.length === 0) {
      ElMessage.warning('未能匹配到标准格式的交易信息')
      return
    }
    if (targetMode.value === 'SPECIFIC' && targetStock.symbol) {
      for (const item of list) {
        if (!item.symbol || !item.symbol.match(/^\d{6}$/)) {
          item.symbol = targetStock.symbol
        }
        if (!item.name && targetStock.name) {
          item.name = targetStock.name
        }
      }
    }
    parsedList.value = list
    ElMessage.success(`解析出 ${list.length} 条记录，请核对。`)
    await nextTick()
    tableRef.value?.toggleAllSelection()
  } catch {
    // handled
  } finally {
    parsing.value = false
  }
}

function handleSelectionChange(val) {
  selectedItems.value = val
}

function addNewRow() {
  parsedList.value.unshift({
    symbol: targetMode.value === 'SPECIFIC' ? targetStock.symbol : '',
    name: targetMode.value === 'SPECIFIC' ? targetStock.name : '',
    action: 'BUY',
    price: null,
    quantity: 1000,
    fee: 5.0,
    tradeTime: new Date().toISOString().replace('T', ' ').substring(0, 19),
    strategyTag: '手动补录',
  })
}

function removeRow(index) {
  parsedList.value.splice(index, 1)
}

// 批量入库提交
async function handleBatchSubmit() {
  if (selectedItems.value.length === 0) {
    ElMessage.warning('请勾选至少一条需要导入的交易记录')
    return
  }

  // 基础数据完整性校验
  for (const item of selectedItems.value) {
    if (!item.symbol || !item.price || !item.quantity) {
      ElMessage.error(`标的 [${item.symbol || '未知'}] 的代码、单价或数量不完整，请检查修正`)
      return
    }
  }

  submitting.value = true
  try {
    const payload = selectedItems.value.map((item) => ({
      symbol: item.symbol,
      name: item.name,
      action: item.action,
      price: item.price,
      quantity: item.quantity,
      fee: item.fee || 0,
      tradeTime: item.tradeTime,
      strategyTag: item.strategyTag || '截图导入',
      notes: item.notes || '截图自动批量入账',
    }))

    await batchRecordTrades(payload)
    ElMessage.success(`成功批量入库 ${payload.length} 笔交易，持仓已自动重算校准！`)
    visible.value = false
    emit('success')
  } catch {
    // error handled
  } finally {
    submitting.value = false
  }
}

defineExpose({
  open,
})
</script>

<style scoped>
.model-config-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: #f8f9fa;
  padding: 8px 14px;
  border-radius: 6px;
  margin-bottom: 12px;
}

.config-box {
  background: #f0f4f8;
  padding: 14px;
  border-radius: 6px;
  margin-bottom: 14px;
  border: 1px solid #dcdfe6;
}

.target-stock-selector-box {
  background: #fdf6ec;
  border: 1px solid #faecd8;
  border-radius: 6px;
  padding: 12px 14px;
  margin-bottom: 14px;
}

.target-selector-header {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.specific-target-panel {
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px dashed #f5dab1;
}

.selected-badge {
  font-size: 13px;
  background: #fff;
  border: 1px solid #e1f3d8;
  padding: 4px 10px;
  border-radius: 4px;
}

.quick-position-tags {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 8px;
}

.pos-tag-btn {
  cursor: pointer;
  transition: all 0.2s;
}

.pos-tag-btn:hover {
  transform: translateY(-1px);
}

.target-tip {
  font-size: 12px;
  color: #b88230;
  margin-top: 8px;
  line-height: 1.5;
}

.endpoint-preview-alert {
  background: #ffffff;
  border: 1px solid #dcdfe6;
  border-left: 4px solid #409eff;
  border-radius: 4px;
  padding: 8px 12px;
  margin-bottom: 12px;
}

.endpoint-preview-alert .preview-title {
  font-size: 12px;
  color: #303133;
  margin-bottom: 4px;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
}

.endpoint-preview-alert .preview-url {
  font-family: monospace;
  font-size: 12px;
  color: #409eff;
  word-break: break-all;
  background: #ecf5ff;
  padding: 4px 8px;
  border-radius: 3px;
}

.import-tabs :deep(.el-tabs__header) {
  margin-bottom: 12px;
}

.drop-area {
  border: 2px dashed #dcdfe6;
  border-radius: 8px;
  padding: 24px 16px;
  text-align: center;
  background: #fafafa;
  transition: all 0.2s;
  outline: none;
}

.drop-area:hover,
.drop-area:focus {
  border-color: #409eff;
  background: #fdfefe;
}

kbd {
  background: #eee;
  border-radius: 3px;
  border: 1px solid #b4b4b4;
  box-shadow: 0 1px 1px rgba(0, 0, 0, 0.2);
  color: #333;
  display: inline-block;
  font-size: 0.85em;
  font-weight: 700;
  line-height: 1;
  padding: 2px 5px;
  white-space: nowrap;
}

.preview-wrap {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
}

.preview-img {
  max-width: 100%;
  max-height: 260px;
  border-radius: 6px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.1);
  object-fit: contain;
}

.preview-actions {
  display: flex;
  gap: 12px;
}

.result-section {
  margin-top: 16px;
  border-top: 1px solid #ebeef5;
  padding-top: 14px;
}

.result-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
</style>

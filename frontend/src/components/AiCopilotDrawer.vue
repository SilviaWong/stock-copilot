<template>
  <el-drawer
    v-model="visible"
    title="🤖 Stock Copilot · AI 投资副驾"
    direction="rtl"
    size="620px"
    destroy-on-close
    class="copilot-drawer"
  >
    <!-- 顶部大模型引擎状态指示条 -->
    <div class="copilot-top-bar">
      <div class="model-info">
        <span class="status-dot online"></span>
        <span class="model-label">当前决策引擎:</span>
        <el-tag size="small" type="primary" effect="plain">
          {{ getProtocolLabel(aiConfig.protocol) }}
        </el-tag>
        <b class="model-name">{{ aiConfig.model || '未配置模型' }}</b>
      </div>
      <el-button link type="primary" size="small" @click="showConfig = !showConfig">
        ⚙️ {{ showConfig ? '收起设置' : '配置引擎' }}
      </el-button>
    </div>

    <!-- 可折叠的配置面板 (与截图OCR共用一套配置) -->
    <el-collapse-transition>
      <div v-if="showConfig" class="copilot-config-panel">
        <el-form label-width="95px" size="small">
          <el-row :gutter="10">
            <el-col :span="12">
              <el-form-item label="请求协议">
                <el-select v-model="aiConfig.protocol" style="width: 100%;">
                  <el-option label="OpenAI 兼容协议" value="OPENAI" />
                  <el-option label="Google Gemini 协议" value="GEMINI" />
                  <el-option label="Anthropic Claude" value="CLAUDE" />
                  <el-option label="Ollama 本地模型" value="OLLAMA" />
                  <el-option label="自定义完整 URL" value="CUSTOM" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="模型名称">
                <el-input v-model="aiConfig.model" placeholder="如 gemini-1.5-flash / gpt-4o-mini" />
              </el-form-item>
            </el-col>
          </el-row>

          <el-form-item v-if="aiConfig.protocol !== 'CUSTOM'" label="Base URL">
            <el-input v-model="aiConfig.baseUrl" placeholder="如 https://api.openai.com/v1 或自己的中转地址" />
          </el-form-item>

          <el-form-item v-else label="完整 URL">
            <el-input v-model="aiConfig.customEndpoint" placeholder="如 https://my-proxy.com/api/v2/chat/completions" />
          </el-form-item>

          <el-form-item label="API Key">
            <el-input v-model="aiConfig.apiKey" type="password" show-password placeholder="填入您的 API Key" />
          </el-form-item>

          <div style="display: flex; justify-content: flex-end; gap: 8px;">
            <el-button size="small" :loading="testing" @click="testConnection">🔍 测试连通性</el-button>
            <el-button size="small" type="primary" @click="saveConfig">保存配置</el-button>
          </div>
        </el-form>
      </div>
    </el-collapse-transition>

    <!-- 快捷决策 Prompt 胶囊卡片 -->
    <div class="quick-prompts-bar">
      <div class="quick-label">⚡ 快捷诊断与提问：</div>
      <div class="quick-tags">
        <el-tag
          v-for="(item, idx) in quickPrompts"
          :key="idx"
          class="prompt-tag"
          effect="light"
          @click="sendQuickPrompt(item.prompt)"
        >
          {{ item.title }}
        </el-tag>
      </div>
    </div>

    <!-- 对话消息流区域 -->
    <div ref="messagesContainer" class="messages-container">
      <!-- 欢迎引言卡片 -->
      <div class="welcome-card">
        <div class="welcome-header">
          <span class="welcome-icon">📈</span>
          <div>
            <div class="welcome-title">您好！我是您的专属投资决策副驾</div>
            <div class="welcome-sub">实时结合真实持仓本金、摊薄保本价与盘中最新行情，提供理性客观的操作指引</div>
          </div>
        </div>
        <div class="welcome-features">
          <div class="feature-item">🎯 <b>知行合一</b>：直接读取您的持仓数量与买卖流水，绝不提供虚浮套话</div>
          <div class="feature-item">💡 <b>具体点位</b>：给出明确的建议挂单买卖价位、做T手数与防守底线</div>
          <div class="feature-item">🛡️ <b>严守纪律</b>：基于盈亏摊薄保本法，帮您测算最大允许回撤与安全垫</div>
        </div>
      </div>

      <!-- 消息循环 -->
      <div
        v-for="(msg, index) in messages"
        :key="index"
        class="message-row"
        :class="msg.role === 'user' ? 'row-user' : 'row-assistant'"
      >
        <div class="avatar">
          {{ msg.role === 'user' ? '👤' : '🤖' }}
        </div>
        <div class="bubble-wrap">
          <div class="bubble-header">
            <span class="sender-name">{{ msg.role === 'user' ? '我' : 'AI 投资副驾' }}</span>
            <span class="msg-time">{{ msg.time }}</span>
            <el-tag v-if="msg.model" size="small" type="info" class="model-tag">{{ msg.model }}</el-tag>
          </div>
          <div class="bubble-content" v-html="renderMarkdown(msg.content)"></div>
        </div>
      </div>

      <!-- 加载思考中动画 -->
      <div v-if="loading" class="message-row row-assistant">
        <div class="avatar">🤖</div>
        <div class="bubble-wrap">
          <div class="bubble-header">
            <span class="sender-name">AI 投资副驾</span>
            <span class="msg-time">正在综合全盘行情与持仓分析...</span>
          </div>
          <div class="bubble-content thinking-bubble">
            <span class="dot"></span>
            <span class="dot"></span>
            <span class="dot"></span>
            <span style="font-size: 13px; color: #909399; margin-left: 8px;">正在推演做T买卖点与仓位风险...</span>
          </div>
        </div>
      </div>
    </div>

    <!-- 底部提问输入栏 -->
    <template #footer>
      <div class="copilot-input-area">
        <div class="input-tools">
          <el-button link type="info" size="small" @click="clearChat">
            🗑️ 清空会话
          </el-button>
          <span style="font-size: 12px; color: #909399;">按 Enter 发送，Shift + Enter 换行</span>
        </div>
        <div class="input-box-row">
          <el-input
            v-model="inputMessage"
            type="textarea"
            :rows="2"
            placeholder="问问 AI 副驾：如「159242 今天可以加仓吗？什么点位加合适？」"
            resize="none"
            :disabled="loading"
            @keydown.enter.exact.prevent="handleSendMessage"
          />
          <el-button
            type="primary"
            class="send-btn"
            :loading="loading"
            :disabled="!inputMessage.trim()"
            @click="handleSendMessage"
          >
            发送
          </el-button>
        </div>
      </div>
    </template>
  </el-drawer>
</template>

<script setup>
import { ref, reactive, nextTick, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { chatWithCopilot, diagnoseAccount, testAiConnection } from '../api'

const visible = ref(false)
const showConfig = ref(false)
const loading = ref(false)
const testing = ref(false)
const inputMessage = ref('')
const messagesContainer = ref(null)

const STORAGE_KEY = 'stock_copilot_ocr_config'

// AI 配置 (与截图导入共享同一个 localStorage 配置)
const aiConfig = reactive({
  protocol: 'OPENAI',
  baseUrl: 'https://api.openai.com/v1',
  apiKey: '',
  model: 'gpt-4o-mini',
  customEndpoint: '',
})

// 快捷推荐问题
const quickPrompts = [
  {
    title: '📊 全盘持仓与健康体检',
    prompt: '请对我当前整体账户资产、持仓分布和历史做T战绩做一次全面的体检。指出当前仓位风险如何，以及哪些标的需要重点关注。',
  },
  {
    title: '🎯 创业板人工智能 159242 今日策略',
    prompt: '请重点帮我推演 创业板人工智能 (159242)。结合我的买入均价 ¥1.9964、摊薄保本价 ¥1.570 和当前行情，今天适合加仓还是减仓做T？建议什么点位操作？',
  },
  {
    title: '🛡️ 防守止损线与安全垫测算',
    prompt: '如果接下来大盘出现连续回调，依据我的摊薄保本价和浮盈垫，我手里的各个标的应该如何设置防守底线？最大允许回撤是多少？',
  },
  {
    title: '💰 仓位配置与网格节奏优化',
    prompt: '根据我当前持仓市值和投入本金，我的仓位控制是否合理？网格做T的步长建议设为几个点？',
  },
]

// 对话消息列表
const messages = ref([])

onMounted(() => {
  loadConfig()
})

function loadConfig() {
  try {
    const saved = localStorage.getItem(STORAGE_KEY)
    if (saved) {
      const parsed = JSON.parse(saved)
      Object.assign(aiConfig, parsed)
    }
  } catch (e) {
    console.error('加载 AI 配置失败:', e)
  }
}

function saveConfig() {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(aiConfig))
    ElMessage.success('AI 引擎配置已更新并持久化保存！')
    showConfig.value = false
  } catch (e) {
    ElMessage.error('保存失败: ' + e.message)
  }
}

async function testConnection() {
  testing.value = true
  try {
    const res = await testAiConnection({ ...aiConfig })
    if (res.success) {
      ElMessage.success(res.message || '大模型连通测试成功！')
    } else {
      ElMessage.warning(res.message || '连接失败，请检查配置')
    }
  } catch (e) {
    ElMessage.error('测试异常: ' + e.message)
  } finally {
    testing.value = false
  }
}

function openDrawer(initialPrompt) {
  loadConfig()
  visible.value = true
  if (initialPrompt) {
    nextTick(() => {
      sendQuickPrompt(initialPrompt)
    })
  }
}

function sendQuickPrompt(promptText) {
  inputMessage.value = promptText
  handleSendMessage()
}

async function handleSendMessage() {
  const content = inputMessage.value.trim()
  if (!content || loading.value) return

  const nowStr = new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })

  // 1. 推入用户消息
  messages.value.push({
    role: 'user',
    content: content,
    time: nowStr,
  })

  inputMessage.value = ''
  loading.value = true
  scrollToBottom()

  // 2. 组装对话历史
  const history = messages.value.slice(0, -1).map((m) => ({
    role: m.role,
    content: m.content,
  }))

  try {
    const payload = {
      message: content,
      history: history,
      config: { ...aiConfig },
    }

    const res = await chatWithCopilot(payload)

    messages.value.push({
      role: 'assistant',
      content: res.reply || '无回答内容',
      time: new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }),
      model: res.modelUsed,
    })
  } catch (err) {
    messages.value.push({
      role: 'assistant',
      content: `> ⚠️ **请求失败**：${err.message || '大模型响应超时或网络异常'}\n\n请检查大模型 API Key 是否正确，或点击右上角「配置引擎」选择匹配的请求协议。`,
      time: new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }),
    })
  } finally {
    loading.value = false
    scrollToBottom()
  }
}

function clearChat() {
  messages.value = []
  ElMessage.info('会话历史已清空')
}

function scrollToBottom() {
  nextTick(() => {
    if (messagesContainer.value) {
      messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight
    }
  })
}

function getProtocolLabel(protocol) {
  switch (protocol) {
    case 'OPENAI': return 'OpenAI 兼容'
    case 'GEMINI': return 'Google Gemini'
    case 'CLAUDE': return 'Anthropic Claude'
    case 'OLLAMA': return 'Ollama 本地'
    case 'CUSTOM': return '自定义完整 URL'
    default: return 'OpenAI 兼容'
  }
}

/**
 * 零依赖的高性能 Markdown 格式化渲染器
 */
function renderMarkdown(md) {
  if (!md) return ''

  let html = md
    // 转义 HTML 基础符号避免注入
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')

  // 标题
  html = html.replace(/^### (.*$)/gim, '<h4 style="margin: 10px 0 6px; color: #303133; font-weight: bold;">$1</h4>')
  html = html.replace(/^## (.*$)/gim, '<h3 style="margin: 12px 0 8px; color: #1f2328; font-weight: bold; border-bottom: 1px solid #ebeef5; padding-bottom: 4px;">$1</h3>')
  html = html.replace(/^# (.*$)/gim, '<h2 style="margin: 14px 0 8px; color: #1f2328; font-weight: bold;">$1</h2>')

  // 加粗与斜体
  html = html.replace(/\*\*(.*?)\*\*/gim, '<strong style="color: #303133; font-weight: 600;">$1</strong>')
  html = html.replace(/\*(.*?)\*/gim, '<em>$1</em>')

  // 行内代码
  html = html.replace(/`([^`]+)`/gim, '<code style="background: #f0f2f5; padding: 2px 5px; border-radius: 4px; font-family: monospace; color: #e65d00; font-size: 12px;">$1</code>')

  // 引用块 (Blockquote)
  html = html.replace(/^\&gt; (.*$)/gim, '<blockquote style="border-left: 4px solid #409eff; background: #ecf5ff; padding: 6px 12px; margin: 8px 0; color: #606266; border-radius: 0 4px 4px 0;">$1</blockquote>')

  // 无序列表项
  html = html.replace(/^\s*[-*]\s+(.*$)/gim, '<li style="margin-left: 18px; line-height: 1.6;">$1</li>')

  // 换行符
  html = html.replace(/\n/gim, '<br />')

  return html
}

defineExpose({
  openDrawer,
})
</script>

<style scoped>
.copilot-top-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 12px;
  background: #f8f9fa;
  border-radius: 8px;
  margin-bottom: 12px;
}

.model-info {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: #606266;
}

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  display: inline-block;
}

.status-dot.online {
  background: #67c23a;
  box-shadow: 0 0 6px #67c23a;
}

.model-name {
  color: #303133;
}

.copilot-config-panel {
  background: #fdfdfd;
  border: 1px solid #ebeef5;
  border-radius: 8px;
  padding: 14px;
  margin-bottom: 12px;
}

.quick-prompts-bar {
  margin-bottom: 14px;
}

.quick-label {
  font-size: 12px;
  color: #909399;
  margin-bottom: 6px;
}

.quick-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.prompt-tag {
  cursor: pointer;
  border-radius: 12px;
  padding: 5px 10px;
  font-size: 12px;
  transition: all 0.2s ease;
}

.prompt-tag:hover {
  background: #ecf5ff;
  border-color: #409eff;
  color: #409eff;
  transform: translateY(-1px);
}

.messages-container {
  height: calc(100vh - 310px);
  min-height: 380px;
  overflow-y: auto;
  padding: 4px 6px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.welcome-card {
  background: linear-gradient(135deg, #f0f7ff 0%, #e6f1fc 100%);
  border: 1px solid #d9ecff;
  border-radius: 10px;
  padding: 16px;
}

.welcome-header {
  display: flex;
  gap: 10px;
  align-items: center;
  margin-bottom: 12px;
}

.welcome-icon {
  font-size: 26px;
}

.welcome-title {
  font-weight: bold;
  font-size: 15px;
  color: #1f2328;
}

.welcome-sub {
  font-size: 12px;
  color: #606266;
  margin-top: 2px;
}

.welcome-features {
  font-size: 12px;
  color: #4b5563;
  line-height: 1.8;
  border-top: 1px dashed #dcdfe6;
  padding-top: 10px;
}

.feature-item {
  margin-bottom: 4px;
}

.message-row {
  display: flex;
  gap: 10px;
  width: 100%;
}

.row-user {
  flex-direction: row-reverse;
}

.avatar {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  background: #f2f3f5;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
  flex-shrink: 0;
}

.bubble-wrap {
  max-width: 82%;
}

.row-user .bubble-wrap {
  text-align: right;
}

.bubble-header {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 4px;
  font-size: 12px;
  color: #909399;
}

.row-user .bubble-header {
  justify-content: flex-end;
}

.sender-name {
  font-weight: 600;
  color: #606266;
}

.model-tag {
  font-size: 11px;
  height: 18px;
  padding: 0 4px;
}

.bubble-content {
  background: #f4f6f8;
  color: #303133;
  padding: 12px 16px;
  border-radius: 12px;
  font-size: 14px;
  line-height: 1.7;
  text-align: left;
  word-break: break-word;
  box-shadow: 0 1px 3px rgba(0,0,0,0.03);
}

.row-user .bubble-content {
  background: #409eff;
  color: #ffffff;
  border-radius: 12px 4px 12px 12px;
}

.row-assistant .bubble-content {
  background: #ffffff;
  border: 1px solid #ebeef5;
  border-radius: 4px 12px 12px 12px;
}

.thinking-bubble {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 10px 14px;
}

.dot {
  width: 6px;
  height: 6px;
  background: #409eff;
  border-radius: 50%;
  animation: blink 1.2s infinite ease-in-out both;
}

.dot:nth-child(1) { animation-delay: -0.32s; }
.dot:nth-child(2) { animation-delay: -0.16s; }

@keyframes blink {
  0%, 80%, 100% { transform: scale(0); opacity: 0.3; }
  40% { transform: scale(1); opacity: 1; }
}

.copilot-input-area {
  border-top: 1px solid #ebeef5;
  padding-top: 10px;
}

.input-tools {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 6px;
}

.input-box-row {
  display: flex;
  gap: 10px;
  align-items: flex-end;
}

.send-btn {
  height: 52px;
  padding: 0 20px;
  font-weight: bold;
}
</style>

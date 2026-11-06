<template>
  <section class="conversation-panel" :class="{ compact }">
    <div class="panel-header">
      <div>
        <strong>会话列表</strong>
        <p>支持新建与切换历史会话</p>
      </div>
      <div class="header-actions">
        <el-button size="small" @click="aiStore.refreshConversationList()" :disabled="aiStore.pending">刷新</el-button>
        <el-button size="small" type="primary" @click="aiStore.startNewConversation()" :loading="aiStore.conversationCreating" :disabled="aiStore.pending || aiStore.conversationCreating">新建</el-button>
      </div>
    </div>

    <p v-if="aiStore.conversationListError" class="conversation-error" role="status">
      {{ aiStore.conversationListError }}，可点击“刷新”重试
    </p>
    <p v-if="aiStore.conversationSwitchError" class="conversation-error" role="status">
      {{ aiStore.conversationSwitchError }}
      <el-button link type="primary" @click="retrySwitch">重试打开会话</el-button>
    </p>
    <p v-if="aiStore.conversationCreateError" class="conversation-error" role="status">
      {{ aiStore.conversationCreateError }}
      <el-button link type="primary" :disabled="aiStore.conversationCreating" @click="aiStore.startNewConversation()">重试新建</el-button>
    </p>

    <div class="conversation-list" v-if="aiStore.conversations.length">
      <button
        v-for="item in aiStore.conversations"
        :key="item.id"
        class="conversation-item"
        :class="{ active: Number(aiStore.conversationId) === Number(item.id) }"
        :disabled="aiStore.pending"
        @click="aiStore.switchConversation(item.id)"
      >
        <strong>{{ item.title || '新会话' }}</strong>
        <span>{{ formatTime(item.updatedTime || item.createdTime) }}</span>
      </button>
    </div>

    <div v-if="!aiStore.conversations.length && !aiStore.conversationListError" class="empty-block">
      暂无历史会话，点击右上角可创建新会话
    </div>
  </section>
</template>

<script setup>
import { useAiStore } from '@/store/ai';

defineProps({
  compact: {
    type: Boolean,
    default: false
  }
});

const aiStore = useAiStore();

function formatTime(value) {
  if (!value) {
    return '刚刚';
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  const month = `${date.getMonth() + 1}`.padStart(2, '0');
  const day = `${date.getDate()}`.padStart(2, '0');
  const hour = `${date.getHours()}`.padStart(2, '0');
  const minute = `${date.getMinutes()}`.padStart(2, '0');
  return `${month}-${day} ${hour}:${minute}`;
}

function retrySwitch() {
  if (aiStore.failedConversationId != null) {
    void aiStore.switchConversation(aiStore.failedConversationId);
  }
}
</script>

<style scoped lang="scss">
.conversation-panel {
  display: grid;
  gap: 12px;
  padding: 14px;
  background: #fffaf6;
  border: 1px solid #f4e1d2;
  border-radius: 14px;
}

.conversation-panel.compact {
  padding: 12px;
}

.panel-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
}

.panel-header p {
  margin: 6px 0 0;
  font-size: 12px;
  color: #999;
}

.conversation-error {
  margin: 0;
  color: #c45656;
  font-size: 13px;
}

.header-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.conversation-list {
  display: grid;
  gap: 10px;
  max-height: 220px;
  overflow-y: auto;
}

.conversation-item {
  display: grid;
  gap: 6px;
  padding: 12px;
  text-align: left;
  border: 1px solid #f0e0d4;
  border-radius: 12px;
  background: #fff;
  cursor: pointer;
  transition: all 0.2s ease;
}

.conversation-item:hover {
  border-color: #ffb27a;
  box-shadow: 0 8px 18px rgba(255, 103, 0, 0.08);
}

.conversation-item.active {
  border-color: #ff6700;
  background: #fff4eb;
}

.conversation-item strong {
  color: #333;
}

.conversation-item span {
  font-size: 12px;
  color: #999;
}
</style>

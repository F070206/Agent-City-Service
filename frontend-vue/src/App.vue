<template>
  <div class="app-root">
    <RouterView v-slot="{ Component, route }">
      <Transition name="route-fade" mode="out-in">
        <component :is="Component" :key="route.fullPath" />
      </Transition>
    </RouterView>
    <AiAssistantDrawer />
    <LoginDialog />
  </div>
</template>

<script setup>
import { watch } from 'vue';
import { ElMessage } from 'element-plus';
import AiAssistantDrawer from '@/components/ai/AiAssistantDrawer.vue';
import LoginDialog from '@/components/auth/LoginDialog.vue';
import { useAiStore } from '@/store/ai';
import { useUserStore } from '@/store/user';

const userStore = useUserStore();
const aiStore = useAiStore();

watch(
  () => userStore.token,
  () => aiStore.syncSessionScope(),
  { immediate: true }
);

onMounted(async () => {
  window.addEventListener('hm-auth-expired', handleAuthExpired);
  window.addEventListener('hm-auth-check-unavailable', handleAuthCheckUnavailable);
  await userStore.initAuth();
});

onBeforeUnmount(() => {
  window.removeEventListener('hm-auth-expired', handleAuthExpired);
  window.removeEventListener('hm-auth-check-unavailable', handleAuthCheckUnavailable);
});

function handleAuthExpired() {
  userStore.clearAuth();
  userStore.openLoginDialog();
}

function handleAuthCheckUnavailable() {
  ElMessage.warning('暂时无法验证登录状态，网络恢复后可刷新页面重试');
}
</script>

<style scoped>
.app-root {
  min-height: 100vh;
  background: #f7f7f7;
}

.route-fade-enter-active,
.route-fade-leave-active {
  transition: all 0.24s ease;
}

.route-fade-enter-from {
  opacity: 0;
  transform: translateY(8px);
}

.route-fade-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}
</style>

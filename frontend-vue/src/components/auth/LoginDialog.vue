<template>
  <el-dialog v-model="visible" title="手机号登录" width="420px" @close="resetForm">
    <el-form label-position="top">
      <el-form-item label="手机号">
        <el-input v-model="form.phone" maxlength="11" placeholder="请输入手机号" />
      </el-form-item>
      <el-form-item label="验证码">
        <div class="code-row">
          <el-input v-model="form.code" maxlength="6" placeholder="请输入验证码" @keyup.enter="submitLogin" />
          <el-button :loading="sendingCode" :disabled="countdown > 0 || !form.phone || sendingCode" @click="sendCode">
            {{ countdown > 0 ? `${countdown}s 后重试` : '发送验证码' }}
          </el-button>
        </div>
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="userStore.closeLoginDialog()">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submitLogin">登录</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ElMessage } from 'element-plus';
import { useUserStore } from '@/store/user';
import { resetLoginCodeCountdown } from '@/utils/login-code-countdown';

const userStore = useUserStore();
const submitting = ref(false);
const sendingCode = ref(false);
const countdown = ref(0);
const timer = ref(null);
let codeRequestId = 0;
const form = reactive({
  phone: '',
  code: ''
});

const visible = computed({
  get: () => userStore.loginDialogVisible,
  set: (value) => {
    if (!value) {
      userStore.closeLoginDialog();
    }
  }
});

onBeforeUnmount(() => {
  codeRequestId += 1;
  resetLoginCodeCountdown({ timer, countdown }, clearInterval);
});

watch(() => form.phone, (phone, previousPhone) => {
  if (phone === previousPhone) return;
  codeRequestId += 1;
  sendingCode.value = false;
  form.code = '';
  resetLoginCodeCountdown({ timer, countdown }, clearInterval);
});

async function sendCode() {
  if (!form.phone || sendingCode.value || countdown.value > 0) {
    if (!form.phone) {
      ElMessage.warning('请先输入手机号');
    }
    return;
  }
  const requestId = ++codeRequestId;
  const requestedPhone = form.phone;
  sendingCode.value = true;
  try {
    const mockCode = await userStore.sendCode(requestedPhone);
    if (requestId !== codeRequestId || !visible.value || form.phone !== requestedPhone) return;
    if (typeof mockCode === 'string' && /^\d{6}$/.test(mockCode)) {
      form.code = mockCode;
      ElMessage.success(`本地模拟验证码：${mockCode}`);
    } else {
      ElMessage.success('验证码已发送');
    }
    countdown.value = 60;
    const countdownTimer = setInterval(() => {
      if (timer.value !== countdownTimer) return;
      countdown.value -= 1;
      if (countdown.value <= 0) {
        clearInterval(countdownTimer);
        timer.value = null;
      }
    }, 1000);
    timer.value = countdownTimer;
  } catch (error) {
    if (requestId === codeRequestId) {
      ElMessage.error(error?.message || '验证码发送失败，请稍后重试');
    }
  } finally {
    if (requestId === codeRequestId) sendingCode.value = false;
  }
}

async function submitLogin() {
  if (!form.phone || !form.code) {
    ElMessage.warning('请输入手机号和验证码');
    return;
  }
  submitting.value = true;
  try {
    await userStore.login({ phone: form.phone, code: form.code });
    ElMessage.success('登录成功');
    resetForm();
  } catch (error) {
    ElMessage.error(error?.message || '登录失败');
  } finally {
    submitting.value = false;
  }
}

function resetForm() {
  codeRequestId += 1;
  sendingCode.value = false;
  resetLoginCodeCountdown({ timer, countdown }, clearInterval);
  form.phone = '';
  form.code = '';
}
</script>

<style scoped lang="scss">
.code-row {
  display: grid;
  grid-template-columns: 1fr 124px;
  gap: 10px;
  width: 100%;
}
</style>


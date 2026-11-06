export function resetLoginCodeCountdown(state, clearTimer = clearInterval) {
  if (state.timer.value) clearTimer(state.timer.value);
  state.timer.value = null;
  state.countdown.value = 0;
}

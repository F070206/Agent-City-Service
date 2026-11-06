<template>
  <div class="page-shell">
    <div class="page-container detail-page" v-if="detail">
      <div class="detail-top-bar">
        <el-button circle @click="router.back()">
          <el-icon><ArrowLeft /></el-icon>
        </el-button>
        <div class="right-actions">
          <el-button round @click="toggleFavorite">
            {{ userStore.isFavorite(detail.id) ? '已收藏' : '收藏' }}
          </el-button>
          <el-button type="primary" round @click="askAiForShop">AI推荐菜品</el-button>
        </div>
      </div>

      <section class="hero card-panel">
        <el-carousel height="300px">
          <el-carousel-item v-for="image in carouselImages" :key="image">
            <img class="hero-image" :src="image" :alt="detail.name" />
          </el-carousel-item>
        </el-carousel>

        <div class="hero-info">
          <div>
            <h1>{{ detail.name }}</h1>
            <div class="score-row">
              <el-rate v-if="detail.score != null" :model-value="detail.score" disabled allow-half />
              <span>{{ detail.score == null ? '暂无评分' : detail.score }}</span>
              <span>{{ detail.price > 0 ? `人均 ¥${detail.price}` : '暂无人均价格' }}</span>
              <span>{{ detail.distance }}</span>
              <span class="tag-chip">{{ detail.status }}</span>
            </div>
          </div>

          <div class="meta-list">
            <div><strong>地址：</strong>{{ detail.address }}</div>
            <div><strong>电话：</strong>{{ detail.phone }}</div>
            <div><strong>营业时间：</strong>{{ detail.openHours }}</div>
            <div><strong>推荐：</strong>{{ detail.recommendText }}</div>
          </div>

          <div class="action-row">
            <el-button @click="toggleFavorite">收藏</el-button>
            <el-button @click="openNavigation">导航</el-button>
            <el-button type="primary" @click="askAiForShop">AI助手推荐</el-button>
          </div>
        </div>
      </section>

      <section class="content-grid">
        <article class="card-panel dishes-panel">
          <div class="section-title">
            <h2>招牌菜品</h2>
            <p>{{ dishTip }}</p>
          </div>
          <div class="dish-list">
            <div v-for="dish in detail.dishes" :key="dish.name" class="dish-item hover-lift">
              <img :src="dish.image" :alt="dish.name" />
              <div>
                <strong>{{ dish.name }}</strong>
                <p>{{ dish.desc }}</p>
                <span>¥{{ dish.price }}</span>
              </div>
            </div>
            <div v-if="!detail.dishes.length" class="empty-block">暂无菜品信息，可咨询商家</div>
          </div>
        </article>

        <article class="card-panel offer-panel">
          <div class="section-title">
            <h2>优惠活动</h2>
            <p>普通券直购，秒杀券走异步秒杀与支付接口</p>
          </div>
          <div class="offer-list">
            <div v-for="voucher in vouchers" :key="voucher.id" class="offer-item hover-lift" :class="{ disabled: !canPurchaseVoucher(voucher) }">
              <div class="offer-main">
                <div class="offer-head">
                  <strong>{{ voucher.title }}</strong>
                  <div class="offer-badges">
                    <span class="voucher-badge" :class="isSeckillVoucher(voucher) ? 'is-seckill' : 'is-normal'">
                      {{ isSeckillVoucher(voucher) ? '秒杀券' : '普通券' }}
                    </span>
                    <span class="voucher-badge is-light" :class="voucherStatusClass(voucher)">
                      {{ getVoucherStatusText(voucher) }}
                    </span>
                  </div>
                </div>
                <span>支付 ¥{{ formatMoney(voucher.payValue) }} / 抵扣 ¥{{ formatMoney(voucher.actualValue) }}</span>
                <span>{{ formatVoucherMeta(voucher) }}</span>
              </div>
              <div class="offer-actions">
                <el-button
                  size="small"
                  type="primary"
                  :loading="ordering && activeVoucherId === voucher.id"
                  :disabled="ordering || !canPurchaseVoucher(voucher)"
                  @click="submitVoucherOrder(voucher)"
                >
                  {{ getVoucherActionText(voucher) }}
                </el-button>
              </div>
            </div>
            <div v-if="voucherLoadError" class="voucher-load-error" role="status">
              <span>优惠券加载失败，请检查网络后重试。</span>
              <el-button size="small" :loading="loadingVouchers" :disabled="loadingVouchers" @click="loadVouchers">
                重试
              </el-button>
            </div>
            <div v-if="loadingVouchers && !vouchers.length" class="empty-block">正在加载优惠券…</div>
            <div v-else-if="!loadingVouchers && !voucherLoadError && !vouchers.length" class="empty-block">
              暂无可领取优惠券
            </div>
          </div>

          <div v-if="latestOrderId && userStore.isLoggedIn" class="payment-box">
            <strong>当前订单：{{ latestOrderId }}</strong>
            <span class="order-status-text">{{ latestOrderStatusText }}</span>
            <div class="payment-actions">
              <el-button size="small" :disabled="checkingOrder || paying || checkingPaymentStatus" :loading="checkingOrder || checkingPaymentStatus" @click="checkPaymentStatus">查状态</el-button>
              <el-button size="small" :disabled="!canPayLatestOrder || paying || checkingOrder || checkingPaymentStatus" :loading="paying && activePaymentAction === 'simulate'" @click="simulatePayment">模拟支付</el-button>
              <el-button size="small" :disabled="!canPayLatestOrder || paying || checkingOrder || checkingPaymentStatus" :loading="paying && activePaymentAction === 'alipay'" @click="openPayUrl('alipay')">支付宝</el-button>
              <el-button size="small" :disabled="!canPayLatestOrder || paying || checkingOrder || checkingPaymentStatus" :loading="paying && activePaymentAction === 'wechat'" @click="openPayUrl('wechat')">微信</el-button>
            </div>
          </div>
        </article>
      </section>

      <section class="card-panel comments-panel">
        <div class="section-title">
          <h2>探店笔记</h2>
          <p>{{ commentTip }}</p>
        </div>
        <CommentItem v-for="comment in detail.comments" :key="comment.id" :comment="comment" />
        <div v-if="shopBlogState === 'failed'" class="comment-load-error" role="status">
          <span>探店笔记加载失败，请检查网络后重试。</span>
          <el-button size="small" :loading="loadingShopBlogs" :disabled="loadingShopBlogs" @click="retryShopBlogs">
            重试
          </el-button>
        </div>
        <div v-else-if="shopBlogState === 'loading'" class="empty-block">正在加载探店笔记…</div>
        <div v-else-if="shopBlogState === 'empty'" class="empty-block">当前暂无探店笔记</div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { ElMessage } from 'element-plus';
import { ArrowLeft } from '@element-plus/icons-vue';
import CommentItem from '@/components/common/CommentItem.vue';
import { shopApi } from '@/api/modules/shop';
import { voucherApi } from '@/api/modules/voucher';
import { paymentApi } from '@/api/modules/payment';
import { blogApi } from '@/api/modules/blog';
import { canPayOrder, findLatestOrderForShop } from '@/utils/orders';
import { getShopBlogState } from '@/utils/shop-blog-state';
import { isCurrentShopRequest } from '@/utils/shop-request';
import { createUserScopeGuard } from '@/utils/user-scope';
import { aiApi } from '@/api/modules/ai';
import { useAiStore } from '@/store/ai';
import { useUserStore } from '@/store/user';

const route = useRoute();
const router = useRouter();
const aiStore = useAiStore();
const userStore = useUserStore();
const orderScopeGuard = createUserScopeGuard();
const detail = ref(null);
const detailRequestId = ref(0);
const vouchers = ref([]);
const voucherRequestId = ref(0);
const loadingVouchers = ref(false);
const voucherLoadError = ref(false);
const latestOrderId = ref(null);
const latestOrderMeta = ref(null);
const usedRealComments = ref(false);
const loadingShopBlogs = ref(false);
const shopBlogsRequestId = ref(0);
const usedAiInsight = ref(false);
const orderReady = ref(false);
const canPayLatestOrder = computed(() => canPayOrder(latestOrderMeta.value, orderReady.value));
const latestOrderStatusText = computed(() => {
  if (!orderReady.value) return '订单创建中，请稍候后再支付';
  return `当前订单状态：${formatOrderStatus(latestOrderMeta.value?.status)}`;
});
const ordering = ref(false);
const paying = ref(false);
const checkingOrder = ref(false);
const checkingPaymentStatus = ref(false);
const activeVoucherId = ref(null);
const activePaymentAction = ref(null);

const carouselImages = computed(() => {
  if (!detail.value) return [];
  return [detail.value.cover, ...detail.value.comments.flatMap((comment) => comment.images || [])]
    .filter(Boolean)
    .slice(0, 3);
});

const dishTip = computed(() => (usedAiInsight.value ? '优先展示 AI 店铺分析得到的招牌菜结果' : '当前暂无商家菜品数据'));
const commentTip = computed(() => {
  if (usedRealComments.value) return '以下内容来自用户发布的真实探店笔记';
  if (commentsLoadFailed.value) return '探店笔记暂时加载失败';
  return '这里展示关联此店铺的探店笔记';
});
const shopBlogState = computed(() => getShopBlogState({
  failed: commentsLoadFailed.value,
  loading: loadingShopBlogs.value,
  blogs: detail.value?.comments || []
}));
const commentsLoadFailed = ref(false);

onMounted(() => loadDetail(String(route.params.id)));

watch(
  () => String(route.params.id),
  (shopId) => {
    detailRequestId.value += 1;
    voucherRequestId.value += 1;
    orderScopeGuard.invalidate();
    detail.value = null;
    vouchers.value = [];
    voucherLoadError.value = false;
    loadingVouchers.value = false;
    latestOrderId.value = null;
    latestOrderMeta.value = null;
    orderReady.value = false;
    usedRealComments.value = false;
    commentsLoadFailed.value = false;
    loadingShopBlogs.value = false;
    usedAiInsight.value = false;
    loadDetail(shopId);
  }
);

watch(
  () => JSON.stringify([currentOrderScope(), userStore.scopedStateVersion]),
  () => syncLatestOrderForCurrentScope()
);

async function loadDetail(shopId) {
  const requestId = ++detailRequestId.value;
  try {
    const shopDetail = await shopApi.getDetail(shopId);
    if (!isCurrentShopRequest(requestId, detailRequestId.value, shopId, route.params.id)) return;
    const [, insight, shopBlogResult] = await Promise.all([
      loadVouchers(shopId),
      aiApi.analyzeShop(shopId).catch(() => null),
      loadShopBlogs(shopId)
    ]);
    if (!isCurrentShopRequest(requestId, detailRequestId.value, shopId, route.params.id)) return;

    commentsLoadFailed.value = shopBlogResult.failed;
    detail.value = mergeDetail(shopDetail, insight, shopBlogResult.blogs);
    aiStore.setCurrentShop(detail.value);
    const latestOrder = findLatestOrder(detail.value.id);
    orderScopeGuard.invalidate();
    latestOrderId.value = latestOrder?.orderId || null;
    latestOrderMeta.value = latestOrder || null;
    if (latestOrderId.value) {
      const ticket = orderScopeGuard.begin(currentOrderScope());
      const shouldWaitAsync = isAsyncPendingOrder(latestOrderMeta.value);
      orderReady.value = !shouldWaitAsync;
      if (shouldWaitAsync) {
        await ensureOrderReady(latestOrderId.value, false, requestId, ticket);
      }
    }
  } catch (error) {
    if (isCurrentShopRequest(requestId, detailRequestId.value, shopId, route.params.id)) {
      ElMessage.error(error?.message || '加载商家详情失败');
    }
  }
}

async function loadVouchers(shopId = String(route.params.id)) {
  const requestId = ++voucherRequestId.value;
  loadingVouchers.value = true;
  voucherLoadError.value = false;
  try {
    const list = await voucherApi.listByShop(shopId);
    if (requestId !== voucherRequestId.value || String(route.params.id) !== String(shopId)) return;
    vouchers.value = Array.isArray(list) ? list : [];
  } catch {
    if (requestId === voucherRequestId.value && String(route.params.id) === String(shopId)) {
      voucherLoadError.value = true;
    }
  } finally {
    if (requestId === voucherRequestId.value) loadingVouchers.value = false;
  }
}

async function loadShopBlogs(shopId) {
  const requestId = ++shopBlogsRequestId.value;
  loadingShopBlogs.value = true;
  try {
    const list = await blogApi.getByShop(shopId, 1);
    return { blogs: (Array.isArray(list) ? list : []).slice(0, 3), failed: false };
  } catch {
    return { blogs: [], failed: true };
  } finally {
    if (requestId === shopBlogsRequestId.value) loadingShopBlogs.value = false;
  }
}

async function retryShopBlogs() {
  if (loadingShopBlogs.value || !detail.value) return;
  const shopId = String(route.params.id);
  const requestId = detailRequestId.value;
  commentsLoadFailed.value = false;
  const result = await loadShopBlogs(shopId);
  if (requestId !== detailRequestId.value || String(route.params.id) !== shopId) return;
  commentsLoadFailed.value = result.failed;
  if (result.failed) return;
  if (result.blogs.length) {
    detail.value.comments = result.blogs.map((blog) => ({
      id: blog.id,
      user: blog.name || '匿名用户',
      avatar: blog.icon || 'https://dummyimage.com/80x80/ffb36d/ffffff&text=U',
      score: calculateBlogScore(blog),
      content: blog.content || blog.title || '这位用户没有留下更多文字评价。',
      images: parseImages(blog.images)
    }));
    usedRealComments.value = true;
  } else {
    detail.value.comments = [];
    usedRealComments.value = false;
  }
}

function mergeDetail(baseDetail, insight, shopBlogs) {
  const nextDetail = {
    ...baseDetail,
    dishes: baseDetail.dishes,
    comments: baseDetail.comments,
    recommendText: baseDetail.recommendText
  };

  if (Array.isArray(shopBlogs) && shopBlogs.length) {
    nextDetail.comments = shopBlogs.map((blog) => ({
      id: blog.id,
      user: blog.name || '匿名用户',
      avatar: blog.icon || 'https://dummyimage.com/80x80/ffb36d/ffffff&text=U',
      score: calculateBlogScore(blog),
      content: blog.content || blog.title || '这位用户没有留下更多文字评价。',
      images: parseImages(blog.images)
    }));
    usedRealComments.value = true;
  } else {
    usedRealComments.value = false;
  }

  if (insight?.signatureDishes?.length) {
    nextDetail.dishes = insight.signatureDishes.map((name, index) => ({
      name,
      price: baseDetail.price,
      desc: index === 0 ? insight.summary || 'AI 认为这是当前门店最值得尝试的菜品。' : `AI 推荐指数 Top ${index + 1}`,
      image: baseDetail.cover
    }));
    nextDetail.recommendText = insight.summary || baseDetail.recommendText;
    usedAiInsight.value = true;
  } else {
    usedAiInsight.value = false;
  }

  return nextDetail;
}

function parseImages(images) {
  return String(images || '')
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean)
    .slice(0, 3);
}

function calculateBlogScore(blog) {
  const liked = Number(blog?.liked || 0);
  if (liked >= 100) return 5;
  if (liked >= 50) return 4.8;
  if (liked >= 20) return 4.6;
  if (liked >= 5) return 4.3;
  return 4;
}

function findLatestOrder(shopId) {
  return findLatestOrderForShop(userStore.orderHistory, shopId);
}

function currentOrderScope() {
  return JSON.stringify([userStore.token, userStore.profile?.id ?? '']);
}

function isCurrentOrderScope(ticket, orderId, pageRequestId = detailRequestId.value) {
  return pageRequestId === detailRequestId.value
    && orderScopeGuard.isCurrent(ticket, currentOrderScope())
    && String(latestOrderId.value) === String(orderId);
}

function syncLatestOrderForCurrentScope() {
  orderScopeGuard.invalidate();
  latestOrderId.value = null;
  latestOrderMeta.value = null;
  orderReady.value = false;
  if (!detail.value || !userStore.isLoggedIn) return;

  const latestOrder = findLatestOrder(detail.value.id);
  if (!latestOrder) return;
  latestOrderId.value = String(latestOrder.orderId);
  latestOrderMeta.value = latestOrder;
  const ticket = orderScopeGuard.begin(currentOrderScope());
  const shouldWaitAsync = isAsyncPendingOrder(latestOrderMeta.value);
  orderReady.value = !shouldWaitAsync;
  if (shouldWaitAsync) {
    const orderId = latestOrderId.value;
    const pageRequestId = detailRequestId.value;
    void ensureOrderReady(orderId, false, pageRequestId, ticket).catch((error) => {
      if (isCurrentOrderScope(ticket, orderId, pageRequestId)) {
        ElMessage.error(error?.message || '查询订单状态失败');
      }
    });
  }
}

function toggleFavorite() {
  const isFavorite = userStore.toggleFavorite(detail.value.id);
  ElMessage.success(isFavorite ? '已加入收藏' : '已取消收藏');
}

function openNavigation() {
  const keyword = encodeURIComponent(`${detail.value.name} ${detail.value.address}`);
  window.open(`https://uri.amap.com/search?keyword=${keyword}`, '_blank');
}

async function askAiForShop() {
  aiStore.setCurrentShop(detail.value);
  aiStore.openDrawer();
  await aiStore.sendShopInsight(detail.value);
}

function isSeckillVoucher(voucher) {
  return Number(voucher?.type) === 1;
}

function toTime(value) {
  if (!value) return null;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date.getTime();
}

function getVoucherStatus(voucher) {
  if (voucher?.status != null && Number(voucher.status) !== 1) {
    return 'offline';
  }
  if (isSeckillVoucher(voucher)) {
    const now = Date.now();
    const beginTime = toTime(voucher.beginTime);
    const endTime = toTime(voucher.endTime);
    if (beginTime && now < beginTime) {
      return 'upcoming';
    }
    if (endTime && now > endTime) {
      return 'expired';
    }
    if (Number(voucher.stock ?? 0) <= 0) {
      return 'soldout';
    }
  }
  return 'available';
}

function getVoucherStatusText(voucher) {
  switch (getVoucherStatus(voucher)) {
    case 'offline': return '已下架';
    case 'upcoming': return '未开始';
    case 'expired': return '已结束';
    case 'soldout': return '已抢光';
    default: return '可购买';
  }
}

function voucherStatusClass(voucher) {
  return `is-${getVoucherStatus(voucher)}`;
}

function canPurchaseVoucher(voucher) {
  return getVoucherStatus(voucher) === 'available';
}

function getVoucherActionText(voucher) {
  if (!canPurchaseVoucher(voucher)) {
    return getVoucherStatusText(voucher);
  }
  return isSeckillVoucher(voucher) ? '抢券' : '立即购买';
}

function formatVoucherMeta(voucher) {
  if (isSeckillVoucher(voucher)) {
    return `秒杀券 ｜ 库存 ${voucher.stock ?? '不限'} ｜ ${voucher.beginTime || '即时生效'}`;
  }
  return '普通券 ｜ 即时购买 ｜ 下单后可直接支付';
}

function isAsyncPendingOrder(order) {
  if (!order) return false;
  const asyncCreated = order.asyncCreated === true;
  const seckillType = Number(order.voucherType) === 1;
  return (asyncCreated || seckillType) && Number(order.status ?? 1) === 1;
}

async function submitVoucherOrder(voucher) {
  const shop = detail.value;
  if (!shop) return;
  if (!canPurchaseVoucher(voucher)) {
    ElMessage.warning(`${getVoucherStatusText(voucher)}，暂不可下单`);
    return;
  }
  activeVoucherId.value = voucher.id;
  try {
    if (isSeckillVoucher(voucher)) {
      await seckillVoucher(voucher, shop);
      return;
    }
    await buyVoucher(voucher, shop);
  } finally {
    activeVoucherId.value = null;
  }
}

async function buyVoucher(voucher, shop) {
  if (!userStore.isLoggedIn) {
    userStore.openLoginDialog();
    return;
  }
  const requestScope = currentOrderScope();
  ordering.value = true;
  try {
    const orderId = String(await voucherApi.buy(voucher.id));
    if (requestScope !== currentOrderScope()) return;
    if (String(route.params.id) === String(shop.id)) {
      orderScopeGuard.invalidate();
      latestOrderId.value = orderId;
      latestOrderMeta.value = {
        orderId,
        shopId: shop.id,
        voucherId: voucher.id,
        voucherType: voucher.type,
        asyncCreated: false,
        status: 1
      };
      orderReady.value = true;
    }
    userStore.recordOrder({
      orderId,
      shopId: shop.id,
      shopName: shop.name,
      voucherId: voucher.id,
      voucherTitle: voucher.title,
      voucherType: voucher.type,
      asyncCreated: false,
      payAmount: formatMoney(voucher.payValue),
      actualAmount: formatMoney(voucher.actualValue),
      status: 1
    });
    ElMessage.success(`普通券下单成功，订单ID：${orderId}${String(route.params.id) === String(shop.id) ? '，可以直接支付' : ''}`);
  } catch (error) {
    if (requestScope === currentOrderScope()) {
      ElMessage.error(error?.message || '普通券购买失败');
    }
  } finally {
    ordering.value = false;
  }
}

async function seckillVoucher(voucher, shop) {
  if (!userStore.isLoggedIn) {
    userStore.openLoginDialog();
    return;
  }
  const requestScope = currentOrderScope();
  ordering.value = true;
  try {
    let orderId;
    let needsConfirmation = false;
    try {
      orderId = await voucherApi.seckill(voucher.id);
    } catch (error) {
      if (error?.data?.pending !== true || error.data.orderId == null) throw error;
      orderId = error.data.orderId;
      needsConfirmation = true;
    }
    if (requestScope !== currentOrderScope()) return;
    const createdOrderId = String(orderId);
    const isCurrentShop = String(route.params.id) === String(shop.id);
    if (isCurrentShop) {
      orderScopeGuard.invalidate();
      latestOrderId.value = createdOrderId;
      latestOrderMeta.value = {
        orderId: createdOrderId,
        shopId: shop.id,
        voucherId: voucher.id,
        voucherType: voucher.type,
        asyncCreated: true,
        creationPending: true,
        status: 1
      };
      orderReady.value = false;
    }
    userStore.recordOrder({
      orderId: createdOrderId,
      shopId: shop.id,
      shopName: shop.name,
      voucherId: voucher.id,
      voucherTitle: voucher.title,
      voucherType: voucher.type,
      asyncCreated: true,
      creationPending: true,
      payAmount: formatMoney(voucher.payValue),
      actualAmount: formatMoney(voucher.actualValue),
      status: 1
    });
    ElMessage.info(needsConfirmation
      ? `订单受理结果待确认，订单ID：${createdOrderId}`
      : `抢券成功，订单ID：${createdOrderId}，正在等待订单落库`);
    if (isCurrentShop) {
      const ticket = orderScopeGuard.begin(requestScope);
      await ensureOrderReady(createdOrderId, true, detailRequestId.value, ticket);
    }
  } catch (error) {
    if (requestScope === currentOrderScope()) {
      ElMessage.error(error?.message || '抢券失败');
    }
  } finally {
    ordering.value = false;
  }
}

async function ensureOrderReady(
  orderId,
  notifyWhenReady = false,
  pageRequestId = detailRequestId.value,
  ticket
) {
  if (!ticket) return false;
  checkingOrder.value = true;
  try {
    const targetOrderId = String(orderId);
    for (let attempt = 0; attempt < 10; attempt += 1) {
      if (!isCurrentOrderScope(ticket, targetOrderId, pageRequestId)) return false;
      try {
        const status = await paymentApi.status(targetOrderId);
        if (!isCurrentOrderScope(ticket, targetOrderId, pageRequestId)) return false;
        if (Number(status) === 0) {
          await wait(800);
          continue;
        }
        userStore.updateOrder(targetOrderId, { status: Number(status), creationPending: false });
        if (latestOrderMeta.value && String(latestOrderMeta.value.orderId) === targetOrderId) {
          orderReady.value = true;
          latestOrderMeta.value = {
            ...latestOrderMeta.value,
            status: Number(status)
          };
        }
        if (notifyWhenReady) {
          ElMessage.success('订单已创建完成，可以继续支付');
        }
        return true;
      } catch (error) {
        if (!isCurrentOrderScope(ticket, targetOrderId, pageRequestId)) return false;
        if (!String(error?.message || '').includes('订单不存在') && !String(error?.message || '').includes('订单创建中')) {
          throw error;
        }
        await wait(800);
      }
    }
    if (isCurrentOrderScope(ticket, targetOrderId, pageRequestId)) {
      ElMessage.warning('订单正在排队创建，请稍后再点击支付');
    }
    return false;
  } finally {
    checkingOrder.value = false;
  }
}

async function checkPaymentStatus() {
  if (!latestOrderId.value || checkingPaymentStatus.value || paying.value) return;
  const orderId = String(latestOrderId.value);
  const pageRequestId = detailRequestId.value;
  const ticket = orderScopeGuard.begin(currentOrderScope());
  checkingPaymentStatus.value = true;
  try {
    const ready = isAsyncPendingOrder(latestOrderMeta.value)
      ? await ensureOrderReady(orderId, false, pageRequestId, ticket)
      : true;
    if (!ready || !isCurrentOrderScope(ticket, orderId, pageRequestId)) return;
    const status = await paymentApi.status(orderId);
    if (!isCurrentOrderScope(ticket, orderId, pageRequestId)) return;
    userStore.updateOrder(orderId, { status: Number(status) });
    if (latestOrderMeta.value) {
      latestOrderMeta.value = {
        ...latestOrderMeta.value,
        status: Number(status)
      };
    }
    ElMessage.success(`当前订单状态：${formatOrderStatus(status)}`);
  } catch (error) {
    if (isCurrentOrderScope(ticket, orderId, pageRequestId)) {
      ElMessage.error(error?.message || '查询状态失败');
    }
  } finally {
    checkingPaymentStatus.value = false;
  }
}

async function simulatePayment() {
  if (!latestOrderId.value || paying.value || checkingOrder.value || checkingPaymentStatus.value) return;
  const orderId = String(latestOrderId.value);
  const pageRequestId = detailRequestId.value;
  const ticket = orderScopeGuard.begin(currentOrderScope());
  paying.value = true;
  activePaymentAction.value = 'simulate';
  try {
    const ready = isAsyncPendingOrder(latestOrderMeta.value)
      ? await ensureOrderReady(orderId, false, pageRequestId, ticket)
      : true;
    if (!ready || !isCurrentOrderScope(ticket, orderId, pageRequestId)) return;
    const result = await paymentApi.simulate(orderId);
    if (!isCurrentOrderScope(ticket, orderId, pageRequestId)) return;
    userStore.updateOrder(orderId, { status: 2, payType: 1 });
    orderReady.value = true;
    if (latestOrderMeta.value) {
      latestOrderMeta.value = {
        ...latestOrderMeta.value,
        status: 2,
        payType: 1
      };
    }
    ElMessage.success(typeof result === 'string' ? result : '模拟支付成功');
  } catch (error) {
    if (isCurrentOrderScope(ticket, orderId, pageRequestId)) {
      ElMessage.error(error?.message || '模拟支付失败');
    }
  } finally {
    paying.value = false;
    activePaymentAction.value = null;
  }
}

async function openPayUrl(type) {
  if (!latestOrderId.value || paying.value || checkingOrder.value || checkingPaymentStatus.value) return;
  const orderId = String(latestOrderId.value);
  const pageRequestId = detailRequestId.value;
  const ticket = orderScopeGuard.begin(currentOrderScope());
  paying.value = true;
  activePaymentAction.value = type;
  try {
    const ready = isAsyncPendingOrder(latestOrderMeta.value)
      ? await ensureOrderReady(orderId, false, pageRequestId, ticket)
      : true;
    if (!ready || !isCurrentOrderScope(ticket, orderId, pageRequestId)) return;
    const result = type === 'alipay'
      ? await paymentApi.alipay(orderId)
      : await paymentApi.wechat(orderId);
    if (!isCurrentOrderScope(ticket, orderId, pageRequestId)) return;
    userStore.updateOrder(orderId, { payType: type === 'alipay' ? 2 : 3 });
    if (latestOrderMeta.value) {
      latestOrderMeta.value = {
        ...latestOrderMeta.value,
        payType: type === 'alipay' ? 2 : 3
      };
    }
    ElMessage.success(result?.payUrl ? `支付链接：${result.payUrl}` : '支付链接已生成');
  } catch (error) {
    if (isCurrentOrderScope(ticket, orderId, pageRequestId)) {
      ElMessage.error(error?.message || '创建支付单失败');
    }
  } finally {
    paying.value = false;
    activePaymentAction.value = null;
  }
}

function formatMoney(value) {
  const amount = Number(value || 0);
  return (amount / 100).toFixed(2);
}

function formatOrderStatus(status) {
  switch (Number(status)) {
    case 1: return '待支付';
    case 2: return '已支付';
    case 3: return '已核销';
    case 4: return '已取消';
    case 5: return '退款中';
    case 6: return '已退款';
    default: return '未知状态';
  }
}

function wait(duration) {
  return new Promise((resolve) => setTimeout(resolve, duration));
}
</script>

<style scoped lang="scss">
.detail-page { padding-top: 18px; }
.detail-top-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 14px;
}
.right-actions { display: flex; gap: 10px; }
.hero {
  display: grid;
  grid-template-columns: 1.05fr 0.95fr;
  gap: 18px;
  padding: 18px;
}
.hero-image {
  width: 100%;
  height: 300px;
  object-fit: cover;
  border-radius: 14px;
  background: #fff4eb;
}
.hero-info {
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  gap: 14px;
}
.hero-info h1 { margin: 0; font-size: 30px; }
.score-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 10px;
  color: #666;
}
.meta-list {
  display: grid;
  gap: 12px;
  line-height: 1.8;
  color: #555;
}
.action-row { display: flex; flex-wrap: wrap; gap: 10px; }
.content-grid {
  display: grid;
  grid-template-columns: 1.1fr 0.9fr;
  gap: 16px;
  margin-top: 18px;
}
.dishes-panel,
.offer-panel,
.comments-panel { padding: 18px; }
.dish-list { display: grid; gap: 14px; }
.dish-item {
  display: grid;
  grid-template-columns: 110px 1fr;
  gap: 14px;
  padding: 12px;
  border-radius: 12px;
  background: #fffaf7;
}
.dish-item img {
  width: 100%;
  height: 88px;
  object-fit: cover;
  border-radius: 10px;
}
.dish-item p { margin: 8px 0; color: #666; }
.dish-item span { color: var(--dp-primary); font-weight: 700; }
.offer-list { display: grid; gap: 12px; }
.voucher-load-error { display: flex; align-items: center; gap: 10px; color: #c45656; }
.offer-item {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 12px;
  padding: 14px;
  border-radius: 12px;
  color: #8a4a14;
  background: #fff5ec;
}
.offer-main { display: grid; gap: 6px; }
.offer-main span { font-size: 13px; color: #8a4a14; }
.offer-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
}
.offer-badges {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.voucher-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 24px;
  padding: 0 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 600;
}
.voucher-badge.is-seckill {
  color: #fff;
  background: linear-gradient(135deg, #ff6700, #ff8a3d);
}
.voucher-badge.is-normal {
  color: #ff6700;
  background: #fff1e7;
}
.voucher-badge.is-light {
  color: #8a4a14;
  background: #fde6d5;
}
.voucher-badge.is-available {
  color: #1f8f55;
  background: #e9f8ef;
}
.voucher-badge.is-upcoming {
  color: #9a6b00;
  background: #fff4d9;
}
.voucher-badge.is-expired,
.voucher-badge.is-offline,
.voucher-badge.is-soldout {
  color: #999;
  background: #f1f1f1;
}
.offer-item.disabled {
  opacity: 0.82;
  background: #faf7f4;
}
.offer-actions,
.payment-actions { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
.payment-box {
  display: grid;
  gap: 12px;
  margin-top: 14px;
  padding: 14px;
  border-radius: 12px;
  background: #f8f8f8;
}
.order-status-text { color: #999; font-size: 13px; }
.empty-block { color: #999; }
.comments-panel { margin-top: 18px; }
@media (max-width: 767px) {
  .detail-top-bar,
  .hero,
  .content-grid,
  .offer-item { grid-template-columns: 1fr; flex-direction: column; }
  .detail-top-bar { align-items: flex-start; gap: 10px; }
  .right-actions { width: 100%; flex-wrap: wrap; }
  .hero-info h1 { font-size: 24px; }
  .dish-item { grid-template-columns: 1fr; }
}
</style>


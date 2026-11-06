<template>
  <div
    class="page-shell"
    @touchstart.passive="handleTouchStart"
    @touchmove.passive="handleTouchMove"
    @touchend="handleTouchEnd"
  >
    <div class="page-container">
      <AppHeader v-model:keyword="keyword" :cities="homeData.cityOptions || []" @search="loadHomeData(true)" />

      <div class="pull-indicator" :class="{ ready: pullDistance > 70 }">
        {{ pullHint }}
      </div>

      <section v-if="homeData.banners.length" class="banner-section card-panel">
        <div class="section-title">
          <h2>爆款推荐</h2>
          <p>基于当前真实店铺接口生成首页推荐</p>
        </div>
        <el-carousel height="220px">
          <el-carousel-item v-for="item in homeData.banners" :key="item.id">
            <div class="banner-card" :style="{ backgroundImage: `url(${item.cover})` }">
              <div class="banner-mask">
                <h3>{{ item.name }}</h3>
                <p>{{ item.recommendText }}</p>
              </div>
            </div>
          </el-carousel-item>
        </el-carousel>
      </section>

      <section>
        <div class="section-title">
          <h2>精选分类</h2>
          <p>点击分类可联调后端商铺列表</p>
        </div>
        <CategoryGrid :categories="homeData.categories || []" />
      </section>

      <section class="shop-section">
        <div class="section-title">
          <h2>猜你喜欢</h2>
          <p>支持搜索、下拉刷新与滚动加载更多</p>
        </div>

        <div class="shop-list">
          <ShopCard v-for="shop in mergedShops" :key="shop.id" :shop="shop" @ai="handleAiRecommend" />
        </div>

        <div class="load-box">
          <div v-if="homeLoadError" class="blog-load-error" role="status">
            <span>{{ homeLoadError }}</span>
            <el-button size="small" :loading="loading" :disabled="loading" @click="retryHomeData">重试</el-button>
          </div>
          <el-button v-if="homeData.hasMore" plain :loading="loading" :disabled="loading || homeSearchDirty || Boolean(homeLoadError)" @click="loadMore">
            加载更多
          </el-button>
          <p v-if="homeSearchDirty">条件已更改，请点击搜索查看结果</p>
          <p v-else-if="!homeData.hasMore && !homeLoadError">已经到底啦</p>
          <p v-else-if="homeData.hasMore && !homeLoadError">继续下拉或点击按钮加载更多</p>
        </div>
      </section>

      <section class="blog-section card-panel">
        <div class="section-title">
          <h2>探店热榜</h2>
          <p>真实联调 `/blog/hot` 与点赞接口</p>
        </div>
        <div v-if="hotBlogsError" class="blog-load-error" role="status">
          <span>热榜加载失败，请检查网络后重试。</span>
          <el-button size="small" :loading="loadingHotBlogs" @click="loadHotBlogs">重试</el-button>
        </div>
        <p v-if="loadingHotBlogs && !hotBlogs.length" class="blog-list-hint">正在加载热榜…</p>
        <p v-else-if="!loadingHotBlogs && !hotBlogsError && !hotBlogs.length" class="blog-list-hint">
          暂无探店笔记
        </p>
        <div class="blog-list">
          <article v-for="blog in hotBlogs" :key="blog.id" class="blog-card hover-lift">
            <div class="blog-main">
              <div class="blog-head">
                <strong>{{ blog.title || '无标题笔记' }}</strong>
                <span class="tag-chip">{{ blog.name || '匿名用户' }}</span>
              </div>
              <p>{{ blog.content || '这篇笔记暂无内容摘要。' }}</p>
              <div class="blog-meta">
                <span>点赞 {{ blog.liked || 0 }}</span>
                <span>评论 {{ blog.comments || 0 }}</span>
                <span>店铺ID {{ blog.shopId || '-' }}</span>
              </div>
            </div>
            <div class="blog-actions">
              <el-button size="small" @click="openBlogDetail(blog)">查看详情</el-button>
              <el-button
                size="small"
                :type="blog.isLike ? 'success' : 'primary'"
                :loading="likingBlogIds.has(Number(blog.id))"
                :disabled="likingBlogIds.has(Number(blog.id))"
                plain
                @click="toggleBlogLike(blog)"
              >
                {{ blog.isLike ? '已点赞' : '点赞' }}
              </el-button>
            </div>
          </article>
        </div>
      </section>
    </div>

    <el-dialog v-model="detailDialogVisible" width="720px" class="blog-detail-dialog" destroy-on-close>
      <template #header>
        <div class="dialog-header" v-if="activeBlog">
          <div>
            <h3>{{ activeBlog.title || '无标题笔记' }}</h3>
            <p>{{ activeBlog.name || '匿名用户' }} ｜ 点赞 {{ activeBlog.liked || 0 }} ｜ 评论 {{ activeBlog.comments || 0 }}</p>
          </div>
          <span class="tag-chip">店铺ID {{ activeBlog.shopId || '-' }}</span>
        </div>
      </template>

      <div v-if="activeBlog" class="detail-dialog-body">
        <div class="detail-images" v-if="activeBlog.imageList?.length">
          <img v-for="image in activeBlog.imageList" :key="image" :src="image" :alt="activeBlog.title" />
        </div>
        <p class="detail-content">{{ activeBlog.content || '暂无详细内容' }}</p>
        <div class="detail-footer-actions">
          <el-button v-if="activeBlog.shopId" @click="goShop(activeBlog.shopId)">去店铺</el-button>
          <el-button
            :type="activeBlog.isLike ? 'success' : 'primary'"
            :loading="likingBlogIds.has(Number(activeBlog.id))"
            :disabled="likingBlogIds.has(Number(activeBlog.id))"
            @click="toggleBlogLike(activeBlog)"
          >
            {{ activeBlog.isLike ? '已点赞' : '点赞' }}
          </el-button>
        </div>
      </div>
    </el-dialog>

    <BottomNav current-tab="home" />
  </div>
</template>

<script setup>
import { ElMessage } from 'element-plus';
import AppHeader from '@/components/layout/AppHeader.vue';
import BottomNav from '@/components/layout/BottomNav.vue';
import CategoryGrid from '@/components/home/CategoryGrid.vue';
import ShopCard from '@/components/common/ShopCard.vue';
import { homeApi } from '@/api/modules/home';
import { blogApi } from '@/api/modules/blog';
import { createUserScopeGuard } from '@/utils/user-scope';
import { useAiStore } from '@/store/ai';
import { useUserStore } from '@/store/user';

const router = useRouter();
const aiStore = useAiStore();
const userStore = useUserStore();

const loading = ref(false);
let refreshQueued = false;
const page = ref(1);
const keyword = ref('');
const loadedKeyword = ref('');
const loadedCity = ref(userStore.city);
const homeLoadError = ref('');
const homeData = ref({
  banners: [],
  categories: [],
  shops: [],
  cityOptions: []
});
const mergedShops = ref([]);
const hotBlogs = ref([]);
const hotBlogsError = ref(false);
const loadingHotBlogs = ref(false);
let hotBlogsRefreshQueued = false;
const hotBlogsSessionGuard = createUserScopeGuard();
const likingBlogIds = ref(new Set());
const detailDialogVisible = ref(false);
const activeBlog = ref(null);
const homeSearchDirty = computed(() => keyword.value.trim() !== loadedKeyword.value || userStore.city !== loadedCity.value);

const touchStartY = ref(0);
const pullDistance = ref(0);

const pullHint = computed(() => {
  if (loading.value) return '正在刷新推荐列表...';
  if (pullDistance.value > 70) return '松开立即刷新';
  if (pullDistance.value > 0) return '继续下拉即可刷新';
  return '下拉刷新推荐列表';
});

onMounted(() => {
  loadHomeData(true);
  loadHotBlogs();
  window.addEventListener('scroll', handleScroll, { passive: true });
});

onBeforeUnmount(() => {
  window.removeEventListener('scroll', handleScroll);
});

async function loadHomeData(
  refresh = false,
  requestedPage = refresh ? 1 : page.value,
  requestedKeyword = refresh ? keyword.value.trim() : loadedKeyword.value,
  requestedCity = refresh ? userStore.city : loadedCity.value
) {
  if (loading.value) {
    if (refresh) refreshQueued = true;
    return false;
  }
  loading.value = true;
  homeLoadError.value = '';
  try {
    const data = await homeApi.getHomeData({
      page: requestedPage,
      keyword: requestedKeyword,
      city: requestedCity
    });
    homeData.value = data;
    if (refresh) {
      mergedShops.value = data.shops;
    } else {
      const ids = new Set(mergedShops.value.map((item) => item.id));
      mergedShops.value = [...mergedShops.value, ...data.shops.filter((item) => !ids.has(item.id))];
    }
    page.value = requestedPage;
    if (refresh) {
      loadedKeyword.value = requestedKeyword;
      loadedCity.value = requestedCity;
    }
    return true;
  } catch (error) {
    homeLoadError.value = error?.message || '加载首页数据失败';
    return false;
  } finally {
    loading.value = false;
    pullDistance.value = 0;
    if (refreshQueued) {
      refreshQueued = false;
      void loadHomeData(true, 1);
    }
  }
}

async function loadMore() {
  if (!homeData.value.hasMore || loading.value || homeLoadError.value || homeSearchDirty.value) return;
  await loadHomeData(false, page.value + 1);
}

function retryHomeData() {
  void loadHomeData(true, 1);
}

async function loadHotBlogs() {
  if (loadingHotBlogs.value) {
    hotBlogsRefreshQueued = true;
    return;
  }
  const requestedToken = userStore.token;
  const sessionTicket = hotBlogsSessionGuard.begin(requestedToken);
  loadingHotBlogs.value = true;
  try {
    const list = await blogApi.getHot(1);
    if (!hotBlogsSessionGuard.isCurrent(sessionTicket, userStore.token)) {
      hotBlogsRefreshQueued = true;
      return;
    }
    hotBlogs.value = (list || []).map(normalizeBlog);
    hotBlogsError.value = false;
  } catch {
    if (hotBlogsSessionGuard.isCurrent(sessionTicket, userStore.token)) hotBlogsError.value = true;
    else hotBlogsRefreshQueued = true;
  } finally {
    loadingHotBlogs.value = false;
    if (hotBlogsRefreshQueued) {
      hotBlogsRefreshQueued = false;
      void loadHotBlogs();
    }
  }
}

function normalizeBlog(blog) {
  return {
    ...blog,
    isLike: Boolean(blog?.isLike),
    imageList: String(blog?.images || '')
      .split(',')
      .map((item) => item.trim())
      .filter(Boolean)
  };
}

function handleAiRecommend(shop) {
  aiStore.setCurrentShop(shop);
  aiStore.openDrawer();
  aiStore.sendShopInsight(shop);
}

async function toggleBlogLike(blog) {
  const blogId = Number(blog?.id);
  if (!Number.isFinite(blogId) || likingBlogIds.value.has(blogId)) return;
  if (!userStore.isLoggedIn) {
    userStore.openLoginDialog();
    return;
  }
  const nextIsLike = !blog.isLike;
  const requestToken = userStore.token;
  likingBlogIds.value = new Set([...likingBlogIds.value, blogId]);
  try {
    await blogApi.toggleLike(blogId);
    if (requestToken !== userStore.token) {
      void loadHotBlogs();
      return;
    }
    const nextLiked = Math.max(0, Number(blog.liked || 0) + (nextIsLike ? 1 : -1));
    patchBlog(blogId, { isLike: nextIsLike, liked: nextLiked });
    ElMessage.success(nextIsLike ? '点赞成功' : '已取消点赞');
  } catch (error) {
    if (requestToken === userStore.token) {
      ElMessage.error(error?.message || '更新点赞状态失败');
    } else {
      void loadHotBlogs();
    }
  } finally {
    const pending = new Set(likingBlogIds.value);
    pending.delete(blogId);
    likingBlogIds.value = pending;
  }
}

function patchBlog(blogId, patch) {
  hotBlogs.value = hotBlogs.value.map((item) => (Number(item.id) === Number(blogId) ? { ...item, ...patch } : item));
  if (activeBlog.value && Number(activeBlog.value.id) === Number(blogId)) {
    activeBlog.value = { ...activeBlog.value, ...patch };
  }
}

async function openBlogDetail(blog) {
  try {
    const detail = await blogApi.getDetail(blog.id);
    activeBlog.value = normalizeBlog({ ...blog, ...detail });
    detailDialogVisible.value = true;
  } catch (error) {
    ElMessage.error(error?.message || '加载笔记详情失败');
  }
}

function goShop(shopId) {
  detailDialogVisible.value = false;
  router.push(`/shop/${shopId}`);
}

function handleTouchStart(event) {
  if (window.scrollY > 0) return;
  touchStartY.value = event.touches[0].clientY;
}

function handleTouchMove(event) {
  if (window.scrollY > 0 || !touchStartY.value) return;
  const currentY = event.touches[0].clientY;
  pullDistance.value = Math.max(0, currentY - touchStartY.value);
}

function handleTouchEnd() {
  if (pullDistance.value > 70) {
    loadHomeData(true);
    loadHotBlogs();
  } else {
    pullDistance.value = 0;
  }
  touchStartY.value = 0;
}

function handleScroll() {
  if (!homeData.value.hasMore || loading.value) return;
  const scrollBottom = window.innerHeight + window.scrollY;
  const pageHeight = document.documentElement.scrollHeight;
  if (scrollBottom >= pageHeight - 120) {
    loadMore();
  }
}
</script>

<style scoped lang="scss">
.banner-section { padding: 18px; margin-bottom: 18px; }
.banner-card {
  position: relative;
  height: 220px;
  overflow: hidden;
  border-radius: 16px;
  background-position: center;
  background-size: cover;
}
.banner-mask {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
  padding: 20px;
  color: #fff;
  background: linear-gradient(180deg, rgba(0, 0, 0, 0.08), rgba(0, 0, 0, 0.58));
}
.pull-indicator {
  display: flex;
  justify-content: center;
  align-items: center;
  height: 24px;
  margin-bottom: 8px;
  font-size: 13px;
  color: #999;
  transition: all 0.2s ease;
}
.pull-indicator.ready { color: var(--dp-primary); }
.shop-section { margin-top: 18px; }
.shop-list { display: grid; gap: 14px; }
.load-box {
  display: grid;
  justify-items: center;
  gap: 10px;
  padding: 20px 0;
  color: #999;
}
.blog-section { padding: 18px; margin-top: 8px; }
.blog-load-error { display: flex; align-items: center; gap: 10px; margin: 0 0 12px; color: #c45656; }
.blog-list-hint { margin: 0; color: #999; }
.blog-list { display: grid; gap: 12px; }
.blog-card {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 12px;
  padding: 14px;
  border-radius: 12px;
  background: #fffaf7;
}
.blog-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
.blog-main p {
  margin: 10px 0;
  color: #666;
  line-height: 1.7;
}
.blog-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  color: #999;
  font-size: 13px;
}
.blog-actions {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.dialog-header h3 { margin: 0 0 8px; font-size: 22px; }
.dialog-header p { margin: 0; color: #999; }
.detail-dialog-body { display: grid; gap: 18px; }
.detail-images {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 12px;
}
.detail-images img {
  width: 100%;
  height: 160px;
  object-fit: cover;
  border-radius: 12px;
  background: #f5f5f5;
}
.detail-content {
  margin: 0;
  font-size: 15px;
  line-height: 1.9;
  color: #444;
  white-space: pre-wrap;
}
.detail-footer-actions { display: flex; justify-content: flex-end; gap: 10px; }
@media (max-width: 767px) {
  .blog-card { grid-template-columns: 1fr; }
  .blog-actions { flex-direction: row; flex-wrap: wrap; }
}
</style>

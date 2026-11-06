<template>
  <div class="page-shell">
    <div class="page-container">
      <AppHeader v-model:keyword="keyword" :cities="cityOptions" @search="handleSearch" />

      <section class="category-banner card-panel">
        <div>
          <h1>{{ currentCategory?.name || '全部分类' }}</h1>
          <p>{{ currentCategory?.description || '切换不同分类，发现更多本地生活好店。' }}</p>
        </div>
        <span class="banner-icon">{{ currentCategory?.icon || '🧭' }}</span>
      </section>

      <section v-if="categoriesError" class="category-error card-panel" role="status">
        <span>{{ categoriesError }}</span>
        <el-button :loading="loadingCategories" :disabled="loadingCategories" @click="loadCategories">重试加载分类</el-button>
      </section>

      <div class="category-tabs card-panel">
        <button
          v-for="item in categories"
          :key="item.id"
          class="tab-item hover-lift"
          :class="{ active: Number(item.id) === Number(currentCategoryId) }"
          @click="changeCategory(item.id)"
        >
          {{ item.icon }} {{ item.name }}
        </button>
      </div>

      <section>
        <div class="section-title">
          <h2>分类商家</h2>
          <p>{{ hasMoreShops ? `已加载 ${shopList.length} 家商户` : `共 ${shopList.length} 家商户` }}</p>
        </div>

        <div class="shop-list">
          <ShopCard v-for="shop in shopList" :key="shop.id" :shop="shop" @ai="openAiForShop" />
        </div>
        <div v-if="shopsError" class="category-error" role="status">
          <span>{{ shopsError }}</span>
          <el-button link type="primary" :loading="loadingShops || loadingMoreShops" :disabled="loadingShops || loadingMoreShops" @click="retryCategoryShops">重试</el-button>
        </div>
        <p v-else-if="loadingShops && !shopList.length" class="category-hint">正在加载商家…</p>
        <p v-else-if="!loadingShops && !shopList.length" class="category-hint">当前分类暂无商家</p>
        <div v-if="shopList.length" class="load-box">
          <el-button v-if="hasMoreShops" plain :loading="loadingMoreShops" :disabled="loadingMoreShops || loadingShops" @click="loadCategoryShops(false)">
            加载更多
          </el-button>
          <p v-else>已经到底啦</p>
        </div>
      </section>
    </div>

    <BottomNav current-tab="category" />
  </div>
</template>

<script setup>
import AppHeader from '@/components/layout/AppHeader.vue';
import BottomNav from '@/components/layout/BottomNav.vue';
import ShopCard from '@/components/common/ShopCard.vue';
import { homeApi } from '@/api/modules/home';
import { resolveCategorySearchKeyword } from '@/utils/category-search';
import { useAiStore } from '@/store/ai';
import { cityOptions } from '@/mock/data';

const route = useRoute();
const router = useRouter();
const aiStore = useAiStore();

const categories = ref([]);
const shopList = ref([]);
const keyword = ref('');
const loadingCategories = ref(false);
const categoriesError = ref('');
const loadingShops = ref(false);
const loadingMoreShops = ref(false);
const shopsError = ref('');
const shopPage = ref(1);
const hasMoreShops = ref(false);
const failedShopPage = ref(1);
let shopRequestId = 0;

const currentCategoryId = computed(() => route.params.id || categories.value[0]?.id || '');
const currentCategory = computed(() => categories.value.find((item) => Number(item.id) === Number(currentCategoryId.value)));

onMounted(loadCategories);

async function loadCategories() {
  if (loadingCategories.value) return;
  loadingCategories.value = true;
  categoriesError.value = '';
  try {
    categories.value = await homeApi.getCategories();
    if (!route.params.id && categories.value[0]?.id) {
      router.replace(`/category/${categories.value[0].id}`);
      return;
    }
    if (categories.value.length) await loadCategoryShops();
  } catch (error) {
    categoriesError.value = error?.message || '分类加载失败，请重试';
  } finally {
    loadingCategories.value = false;
  }
}

watch(
  () => route.params.id,
  () => {
    shopRequestId += 1;
    shopList.value = [];
    shopPage.value = 1;
    hasMoreShops.value = false;
    shopsError.value = '';
    failedShopPage.value = 1;
    loadingMoreShops.value = false;
    if (categories.value.length) {
      loadCategoryShops();
    } else {
      loadingShops.value = false;
    }
  }
);

async function loadCategoryShops(reset = true) {
  if (!categories.value.length) return;
  if (!reset && (loadingShops.value || loadingMoreShops.value || !hasMoreShops.value)) return;
  if (reset) loadingMoreShops.value = false;
  const requestedPage = reset ? 1 : shopPage.value + 1;
  const requestId = ++shopRequestId;
  if (reset) loadingShops.value = true;
  else loadingMoreShops.value = true;
  shopsError.value = '';
  try {
    const result = await homeApi.searchShops({
      category: currentCategoryId.value,
      keyword: keyword.value,
      page: requestedPage
    });
    if (requestId === shopRequestId) {
      if (reset) {
        shopList.value = result.list;
      } else {
        const existingIds = new Set(shopList.value.map((shop) => String(shop.id)));
        shopList.value = [...shopList.value, ...result.list.filter((shop) => {
          const id = String(shop.id);
          if (existingIds.has(id)) return false;
          existingIds.add(id);
          return true;
        })];
      }
      shopPage.value = requestedPage;
      hasMoreShops.value = result.hasMore;
      failedShopPage.value = 1;
    }
  } catch (error) {
    if (requestId === shopRequestId) {
      failedShopPage.value = requestedPage;
      shopsError.value = error?.message || '加载分类商家失败，请重试';
    }
  } finally {
    if (requestId === shopRequestId) {
      loadingShops.value = false;
      loadingMoreShops.value = false;
    }
  }
}

function retryCategoryShops() {
  void loadCategoryShops(failedShopPage.value === 1);
}

function handleSearch(searchKeyword = keyword.value) {
  keyword.value = resolveCategorySearchKeyword(searchKeyword, keyword.value);
  shopList.value = [];
  shopPage.value = 1;
  hasMoreShops.value = false;
  failedShopPage.value = 1;
  void loadCategoryShops(true);
}

function changeCategory(categoryId) {
  if (Number(categoryId) === Number(currentCategoryId.value)) return;
  router.push(`/category/${categoryId}`);
}

function openAiForShop(shop) {
  aiStore.setCurrentShop(shop);
  aiStore.openDrawer();
  aiStore.sendShopInsight(shop);
}
</script>

<style scoped lang="scss">
.category-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 20px;
  margin-bottom: 16px;
  background: linear-gradient(135deg, #fff5ec 0%, #ffffff 100%);
}

.category-banner h1 {
  margin: 0 0 10px;
}

.category-banner p {
  margin: 0;
  color: #666;
}

.banner-icon {
  font-size: 56px;
}

.category-tabs {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  padding: 16px;
  margin-bottom: 16px;
}

.tab-item {
  padding: 10px 14px;
  border: 1px solid #eee;
  border-radius: 999px;
  background: #fff;
  cursor: pointer;
}

.tab-item.active {
  color: #fff;
  border-color: var(--dp-primary);
  background: var(--dp-primary);
}

.shop-list {
  display: grid;
  gap: 14px;
}
.load-box { display: grid; justify-items: center; gap: 10px; padding: 20px 0; color: #999; }
.load-box p { margin: 0; }
.category-error { display: flex; align-items: center; gap: 12px; padding: 14px; color: #c45656; }
.category-hint { color: #999; text-align: center; }
</style>

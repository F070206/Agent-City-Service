import { categoryList } from '@/mock/data';


function fallbackCategoryMeta(name = '', index = 0) {
  const preset = categoryList.find((item) => name.includes(item.name) || item.name.includes(name));
  return preset || categoryList[index % categoryList.length];
}

export function normalizeCategory(type, index = 0) {
  const meta = fallbackCategoryMeta(type?.name || '', index);
  return {
    id: Number(type?.id),
    name: type?.name || meta.name,
    icon: meta.icon,
    description: meta.description,
    rawIcon: type?.icon || ''
  };
}

export function normalizeCategories(list = []) {
  return list.map((item, index) => normalizeCategory(item, index));
}

export function normalizeShop(raw) {
  const shopId = Number(raw?.id);
  const firstImage = raw?.images?.split(',')?.map((item) => item.trim())?.find(Boolean);
  const normalizedScore = raw?.score == null ? null : Number(raw.score) > 5 ? Number(raw.score) / 10 : Number(raw.score);
  const distanceValue = raw?.distance == null ? '距离未知' : formatDistance(raw.distance);

  return {
    id: shopId,
    typeId: Number(raw?.typeId || 0),
    category: '',
    name: raw?.name || '未命名商铺',
    score: normalizedScore == null ? null : Number(normalizedScore.toFixed(1)),
    price: Number(raw?.avgPrice ?? raw?.price ?? 0),
    distance: distanceValue,
    tags: [raw?.area, raw?.openHours].filter(Boolean),
    cover: normalizeImage(firstImage),
    status: raw?.status || '营业状态未知',
    phone: raw?.phone || '暂未提供',
    address: raw?.address || '暂无地址',
    recommendText: raw?.recommendText || buildRecommendText(raw),
    dishes: Array.isArray(raw?.dishes) ? raw.dishes : [],
    offers: Array.isArray(raw?.offers) ? raw.offers : [],
    comments: Array.isArray(raw?.commentList) ? raw.commentList : [],
    sold: Number(raw?.sold || 0),
    commentCount: Number(raw?.comments ?? raw?.commentCount ?? 0),
    openHours: raw?.openHours || '营业时间以门店为准',
    raw
  };
}

export function mergeShopDetail(raw) {
  return normalizeShop(raw);
}

function normalizeImage(image) {
  if (!image) {
    return '';
  }
  if (/^https?:\/\//i.test(image)) {
    return image;
  }
  return image;
}

function formatDistance(distance) {
  const value = Number(distance);
  if (Number.isNaN(value)) {
    return String(distance);
  }
  if (value < 1000) {
    return `${Math.round(value)}m`;
  }
  return `${(value / 1000).toFixed(1)}km`;
}

function buildRecommendText(raw) {
  const facts = [];
  if (raw?.sold != null) facts.push(`销量 ${raw.sold}`);
  if (raw?.comments != null) facts.push(`评论 ${raw.comments}`);
  return facts.length ? facts.join('，') : '暂无商家介绍';
}

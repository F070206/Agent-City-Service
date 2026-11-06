import request from '@/api/request';

export const blogApi = {
  getHot(current = 1) {
    return request({
      url: '/blog/hot',
      method: 'get',
      params: { current }
    });
  },
  getByShop(shopId, current = 1) {
    return request({
      url: '/blog/of/shop',
      method: 'get',
      params: { shopId, current }
    });
  },
  getMy(current = 1) {
    return request({
      url: '/blog/of/me',
      method: 'get',
      params: { current }
    });
  },
  create(data, authToken) {
    return request({
      url: '/blog',
      method: 'post',
      data,
      ...(typeof authToken === 'string' ? { __authTokenOverride: authToken } : {})
    });
  },
  uploadImage(file, authToken) {
    const formData = new FormData();
    formData.append('file', file);
    return request({
      url: '/upload/blog',
      method: 'post',
      data: formData,
      ...(typeof authToken === 'string' ? { __authTokenOverride: authToken } : {})
    });
  },
  deleteImage(name, authToken) {
    return request({
      url: '/upload/blog',
      method: 'delete',
      params: { name },
      ...(typeof authToken === 'string' ? { __authTokenOverride: authToken } : {})
    });
  },
  toggleLike(id) {
    return request({
      url: `/blog/like/${id}`,
      method: 'put'
    });
  },
  getLikes(id) {
    return request({
      url: `/blog/likes/${id}`,
      method: 'get'
    });
  },
  getDetail(id) {
    return request({
      url: `/blog/${id}`,
      method: 'get'
    });
  }
};

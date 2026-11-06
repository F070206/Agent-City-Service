export function getShopBlogState({ failed, loading, blogs }) {
  if (failed) return 'failed';
  if (loading && !blogs.length) return 'loading';
  if (!blogs.length) return 'empty';
  return 'loaded';
}

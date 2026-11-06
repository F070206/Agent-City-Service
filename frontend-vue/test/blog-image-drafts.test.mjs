import assert from 'node:assert/strict';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { createServer } from 'vite';

test('manual image paths cannot be combined with uploaded draft images', async () => {
  const server = await createServer({
    configFile: fileURLToPath(new URL('../vite.config.cjs', import.meta.url)),
    server: { middlewareMode: true }
  });

  try {
    const { canUploadBlogImages, mergeUploadedBlogImages } =
      await server.ssrLoadModule('/src/utils/blog-image-drafts.js');

    assert.equal(canUploadBlogImages('/manual/image.png', []), false);
    assert.equal(canUploadBlogImages('  ', []), true);
    assert.equal(canUploadBlogImages('/blogs/uploaded.png', ['/blogs/uploaded.png']), true);
    assert.equal(mergeUploadedBlogImages('/blogs/first.png', '/blogs/second.png'),
      '/blogs/first.png,/blogs/second.png');
    assert.equal(mergeUploadedBlogImages('', '/blogs/first.png'), '/blogs/first.png');
    assert.equal(mergeUploadedBlogImages('/blogs/first.png', '/blogs/first.png'),
      '/blogs/first.png');
  } finally {
    await server.close();
  }
});

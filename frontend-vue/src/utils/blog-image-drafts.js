export function canUploadBlogImages(images, uploadedImages) {
  return uploadedImages.length > 0 || !String(images || '').trim();
}

export function mergeUploadedBlogImages(images, filename) {
  const currentImages = String(images || '').split(',').map((image) => image.trim()).filter(Boolean);
  if (currentImages.includes(filename)) return currentImages.join(',');
  return [...currentImages, filename].join(',');
}

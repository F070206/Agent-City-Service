export function resolveCategorySearchKeyword(emittedKeyword, currentKeyword) {
  return typeof emittedKeyword === 'string' ? emittedKeyword : currentKeyword;
}

import assert from 'node:assert/strict';
import test from 'node:test';
import { resolveCategorySearchKeyword } from '../src/utils/category-search.js';

test('category search uses the keyword emitted by the search input immediately', () => {
  assert.equal(resolveCategorySearchKeyword('刚输入的关键词', '上一次的关键词'), '刚输入的关键词');
});

test('category search keeps the current keyword when invoked without an event value', () => {
  assert.equal(resolveCategorySearchKeyword(undefined, '当前关键词'), '当前关键词');
});

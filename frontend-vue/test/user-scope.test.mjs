import assert from 'node:assert/strict';
import test from 'node:test';
import { createUserScopeGuard } from '../src/utils/user-scope.js';

test('user-scoped requests become stale when the account changes or logs out', () => {
  const guard = createUserScopeGuard();
  const firstUser = guard.begin(1);

  assert.equal(guard.isCurrent(firstUser, 1), true);
  assert.equal(guard.isCurrent(firstUser, 2), false);

  const secondUser = guard.begin(2);
  assert.equal(guard.isCurrent(firstUser, 1), false);
  assert.equal(guard.isCurrent(secondUser, 2), true);

  guard.invalidate();
  assert.equal(guard.isCurrent(secondUser, 2), false);
});

test('a new session invalidates requests from the same user id', () => {
  const guard = createUserScopeGuard();
  const oldSession = guard.begin(JSON.stringify(['7', 'old-token']));
  const newSession = guard.begin(JSON.stringify(['7', 'new-token']));

  assert.equal(guard.isCurrent(oldSession, JSON.stringify(['7', 'old-token'])), false);
  assert.equal(guard.isCurrent(newSession, JSON.stringify(['7', 'new-token'])), true);
});

test('a response is stale when the current token no longer matches its request scope', () => {
  const guard = createUserScopeGuard();
  const request = guard.begin('old-token');

  assert.equal(guard.isCurrent(request, 'old-token'), true);
  assert.equal(guard.isCurrent(request, 'new-token'), false);
});

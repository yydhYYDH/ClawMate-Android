const { test } = require('node:test');
const assert = require('node:assert/strict');
const { createRoutes, version } = require('./navigation.js');
const catalog = require('./app-manifest.json');
const ids = catalog.chapters.map(chapter => chapter.id);
const routes = createRoutes(ids);

test('native manifest and route contract have matching versions', () => {
  assert.equal(version, catalog.schemaVersion);
  assert.equal(routes.format({ page: 'home' }), catalog.homeRoute);
});
test('all 15 chapter links round trip, including legacy bookmarks', () => {
  for (const chapter of catalog.chapters) {
    const expected = { page: 'chapter', id: chapter.id };
    assert.deepEqual(routes.parse(chapter.route), expected);
    assert.deepEqual(routes.parse('#' + chapter.id), expected);
    assert.deepEqual(routes.parse('#title-' + chapter.id), expected);
    assert.equal(routes.format(expected), chapter.route);
  }
});
test('handbook home is distinct from the product home chapter', () => {
  for (const hash of ['', '#', '#/', '#top', '#content']) assert.deepEqual(routes.parse(hash), { page: 'home' });
  assert.deepEqual(routes.parse('#home'), { page: 'chapter', id: 'home' });
});
test('invalid routes and untrusted URL-like values never become navigable chapters', () => {
  for (const hash of ['#/chapter/missing', '#/chapter/../start', '#/chapter/start?x=1', '#javascript:alert(1)', '#https://example.com', '#/search?q=%E0%A4%A', '#/search?x=1', '#/search?q=one&q=two']) {
    assert.deepEqual(routes.parse(hash), { page: 'not-found' }, hash);
  }
  assert.equal(routes.hasChapter('__proto__'), false);
});
test('search survives URL encoding with Chinese, punctuation and literal plus signs', () => {
  for (const query of ['图片 & API Key', 'a+b=c?#', '<img src=x>', '', '多 字 词']) {
    assert.deepEqual(routes.parse(routes.format({ page: 'search', query })), { page: 'search', query });
  }
  assert.deepEqual(routes.parse('#/search?q=API+Key'), { page: 'search', query: 'API Key' });
});
test('empty and excessive search queries are bounded consistently', () => {
  assert.deepEqual(routes.parse('#/search'), { page: 'search', query: '' });
  const route = routes.parse('#/search?q=' + '字'.repeat(300));
  assert.equal(route.query.length, 200);
  assert.equal(routes.parse(routes.format(route)).query.length, 200);
  const emoji = '字'.repeat(199) + '😀尾';
  assert.equal(routes.parse(routes.format({ page: 'search', query: emoji })).query, '字'.repeat(199) + '😀');
});
test('previous and next stop at the two chapter boundaries', () => {
  assert.deepEqual(routes.neighbors(ids[0]), { previous: null, next: ids[1] });
  assert.deepEqual(routes.neighbors(ids.at(-1)), { previous: ids.at(-2), next: null });
  assert.deepEqual(routes.neighbors('invalid'), { previous: null, next: null });
  for (let i = 1; i < ids.length - 1; i++) assert.deepEqual(routes.neighbors(ids[i]), { previous: ids[i - 1], next: ids[i + 1] });
});
test('invalid or duplicate content IDs fail during integration', () => {
  assert.throws(() => createRoutes(['start', 'start']));
  assert.throws(() => createRoutes(['../start']));
});

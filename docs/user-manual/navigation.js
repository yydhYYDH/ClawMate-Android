/* Platform-independent route contract. Also loadable by Node without a DOM. */
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.ClawMateManualNavigation = factory();
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';
  const normalizeQuery = value => Array.from(String(value || '').trim()).slice(0, 200).join('');
  function createRoutes(chapterIds) {
    const ids = new Set(chapterIds);
    if (ids.size !== chapterIds.length || chapterIds.some(id => !/^[a-z]+$/.test(id))) {
      throw new Error('Chapter IDs must be unique lowercase words');
    }
    function parse(hash) {
      const raw = typeof hash === 'string' ? hash.replace(/^#/, '') : '';
      if (!raw || ['/', 'top', 'content'].includes(raw)) return { page: 'home' };
      const legacy = raw.replace(/^title-/, '');
      if (ids.has(legacy)) return { page: 'chapter', id: legacy };
      const match = /^\/chapter\/([a-z]+)$/.exec(raw);
      if (match && ids.has(match[1])) return { page: 'chapter', id: match[1] };
      if (raw === '/search' || raw.startsWith('/search?')) {
        try {
          const query = raw.includes('?') ? raw.slice(raw.indexOf('?') + 1) : '';
          const pairs = query.split('&').filter(Boolean).map(pair => pair.split('='));
          if (pairs.length > 1 || (pairs[0] && pairs[0][0] !== 'q')) return { page: 'not-found' };
          const q = normalizeQuery(decodeURIComponent((pairs[0]?.slice(1).join('=') || '').replace(/\+/g, ' ')));
          return { page: 'search', query: q };
        } catch (_) { return { page: 'not-found' }; }
      }
      return { page: 'not-found' };
    }
    function format(route) {
      if (route.page === 'home') return '#/';
      if (route.page === 'chapter' && ids.has(route.id)) return '#/chapter/' + route.id;
      if (route.page === 'search') return '#/search?q=' + encodeURIComponent(normalizeQuery(route.query));
      return '#/not-found';
    }
    function neighbors(id) {
      const index = chapterIds.indexOf(id);
      return index < 0 ? { previous: null, next: null } : {
        previous: chapterIds[index - 1] || null, next: chapterIds[index + 1] || null
      };
    }
    return Object.freeze({ parse, format, neighbors, hasChapter: id => ids.has(id) });
  }
  return Object.freeze({ version: 1, createRoutes });
});

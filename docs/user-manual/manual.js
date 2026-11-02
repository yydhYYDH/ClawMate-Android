/* Offline browser adapter. Content, route codec and host entry API stay separate. */
(function () {
  'use strict';
  const catalog = JSON.parse(document.querySelector('#manual-catalog').textContent);
  const routes = ClawMateManualNavigation.createRoutes(catalog.chapters.map(chapter => chapter.id));
  const byId = new Map(catalog.chapters.map(chapter => [chapter.id, chapter]));
  const search = document.querySelector('#search');
  const sections = [...document.querySelectorAll('#chapters > section')];
  const navLinks = [...document.querySelectorAll('#chapter-nav a')];
  const viewer = document.querySelector('#image-viewer');
  const large = document.querySelector('#large-image');
  const session = Date.now().toString(36) + Math.random().toString(36).slice(2);
  let route = routes.parse(location.hash);
  let index = 0;
  let printing = false;
  let detailsBeforePrint = [];
  let renderKey = '';
  let scrollTimer = 0;
  const owner = () => history.state?.clawmateManual;
  function stateFor(scrollY = 0) {
    return { ...history.state, clawmateManual: { version: 1, session, index, scrollY } };
  }
  function getState() {
    return { version: 1, route: { ...route }, hash: routes.format(route),
      canGoBack: Boolean(viewer.open) || (owner()?.session === session && index > 0) };
  }
  function notify() {
    window.dispatchEvent(new CustomEvent('clawmate-manual:routechange', { detail: getState() }));
  }
  function closeMenu() {
    document.body.classList.remove('menu-open');
    document.querySelector('#menu-toggle').setAttribute('aria-expanded', 'false');
  }
  function renderResults(query) {
    const terms = query.toLocaleLowerCase().split(/\s+/).filter(Boolean);
    const results = sections.filter(section => {
      const chapter = byId.get(section.id);
      const text = [chapter.title, chapter.summary, section.querySelector('.instructions').textContent].join(' ').toLocaleLowerCase();
      return terms.every(term => text.includes(term));
    });
    const list = document.querySelector('#search-results');
    list.replaceChildren();
    for (const section of results) {
      const chapter = byId.get(section.id);
      const link = document.createElement('a');
      link.href = '#' + chapter.id;
      link.className = 'result-card';
      const title = document.createElement('strong');
      title.textContent = chapter.title;
      const summary = document.createElement('span');
      summary.textContent = chapter.summary;
      link.append(title, summary);
      list.append(link);
    }
    document.querySelector('#empty').hidden = results.length > 0;
    document.querySelector('#result-count').textContent = query ? `“${query}”：找到 ${results.length} 个相关章节` : `全部 ${results.length} 个章节`;
    document.querySelector('#search-status').textContent = `找到 ${results.length} 个相关章节。`;
  }
  function render({ focus = true, restore = false } = {}) {
    if (viewer.open) viewer.close();
    const chapter = route.page === 'chapter' ? byId.get(route.id) : null;
    document.querySelector('#home-page').hidden = route.page !== 'home';
    document.querySelector('#search-page').hidden = route.page !== 'search';
    document.querySelector('#not-found').hidden = route.page !== 'not-found';
    document.querySelector('#chapters').hidden = !chapter;
    sections.forEach(section => { section.hidden = section.id !== chapter?.id; });
    navLinks.forEach(link => {
      if (link.hash === '#' + chapter?.id) link.setAttribute('aria-current', 'page');
      else link.removeAttribute('aria-current');
    });
    if (focus || document.activeElement !== search) search.value = route.page === 'search' ? route.query : '';
    if (route.page === 'search') renderResults(route.query);
    else document.querySelector('#search-status').textContent = '按任务查阅，随时回来。';
    const label = chapter?.title || (route.page === 'search' ? '搜索结果' : route.page === 'home' ? '手册首页' : '页面未找到');
    document.querySelector('#current-page').textContent = label;
    document.querySelector('#page-position').textContent = chapter ? `${catalog.chapters.indexOf(chapter) + 1} / ${catalog.chapters.length}` : '';
    document.querySelector('#reader-back').disabled = !getState().canGoBack;
    document.title = label + ' · ' + catalog.title;
    if (focus) closeMenu();
    renderKey = location.hash;
    if (focus) {
      const heading = chapter ? document.querySelector('#title-' + chapter.id) :
        document.querySelector(route.page === 'search' ? '#search-title' : route.page === 'home' ? '#home-title' : '#not-found-title');
      heading.focus({ preventScroll: true });
      window.scrollTo(0, restore ? owner()?.scrollY || 0 : 0);
    }
    notify();
  }
  function navigate(next, { replace = false, focus = true } = {}) {
    const hash = routes.format(next);
    if (hash !== routes.format(route)) {
      if (!replace) {
        history.replaceState(stateFor(window.scrollY), '', location.href);
        index++;
      }
      history[replace ? 'replaceState' : 'pushState'](stateFor(), '', hash);
    }
    route = next;
    render({ focus });
  }
  function goBack() {
    if (viewer.open) { viewer.close(); return true; }
    if (owner()?.session !== session || index === 0) return false;
    history.replaceState(stateFor(window.scrollY), '', location.href);
    history.back();
    return true;
  }
  function onHistory() {
    if (printing) return;
    const previous = owner();
    if (location.hash === renderKey && previous?.session === session && previous.index === index) return;
    route = routes.parse(location.hash);
    index = previous?.session === session ? previous.index : 0;
    if (previous?.session !== session) history.replaceState(stateFor(), '', location.href);
    render({ restore: true });
  }
  document.addEventListener('click', event => {
    const anchor = event.target.closest('a[href^="#"]');
    if (!anchor || anchor.classList.contains('skip') || event.defaultPrevented ||
      event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
    event.preventDefault();
    navigate(routes.parse(anchor.hash));
  });
  function searchInput(event) {
    if (event.isComposing) return;
    const startingSearch = route.page !== 'search';
    navigate(routes.parse(routes.format({ page: 'search', query: search.value })), {
      replace: route.page === 'search', focus: false
    });
    if (startingSearch) window.scrollTo(0, 0);
  }
  search.addEventListener('input', searchInput);
  search.addEventListener('compositionend', searchInput);
  document.querySelector('#search-form').addEventListener('submit', event => {
    event.preventDefault();
    navigate(routes.parse(routes.format({ page: 'search', query: search.value })));
  });
  document.querySelector('#reader-back').addEventListener('click', goBack);
  document.querySelector('#menu-toggle').addEventListener('click', () => {
    const open = document.body.classList.toggle('menu-open');
    document.querySelector('#menu-toggle').setAttribute('aria-expanded', String(open));
    if (open) search.focus();
  });
  window.addEventListener('popstate', onHistory);
  window.addEventListener('hashchange', onHistory);
  window.addEventListener('scroll', () => {
    clearTimeout(scrollTimer);
    scrollTimer = setTimeout(() => {
      if (!printing && owner()?.session === session) history.replaceState(stateFor(window.scrollY), '', location.href);
    }, 250);
  }, { passive: true });
  window.addEventListener('beforeprint', () => {
    printing = true;
    document.querySelector('#home-page').hidden = false;
    document.querySelector('#chapters').hidden = false;
    sections.forEach(section => { section.hidden = false; });
    detailsBeforePrint = [...document.querySelectorAll('details')].map(detail => [detail, detail.open]);
    detailsBeforePrint.forEach(([detail]) => { detail.open = true; });
  });
  window.addEventListener('afterprint', () => {
    detailsBeforePrint.forEach(([detail, open]) => { detail.open = open; });
    printing = false;
    render({ focus: false });
  });
  document.querySelector('#print').addEventListener('click', () => window.print());
  if (typeof viewer.showModal === 'function') {
    document.querySelectorAll('.zoom').forEach(anchor => anchor.addEventListener('click', event => {
      if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
      event.preventDefault();
      large.src = anchor.href;
      large.alt = anchor.querySelector('img').alt;
      viewer.showModal();
      notify();
    }));
    document.querySelector('#close-image').addEventListener('click', () => viewer.close());
    viewer.addEventListener('click', event => { if (event.target === viewer) viewer.close(); });
    viewer.addEventListener('close', notify);
  }
  // Host entry is restricted to known chapters. No native capability is exposed.
  window.ClawMateManual = Object.freeze({
    version: 1, getState, back: goBack,
    openChapter(id) {
      if (!routes.hasChapter(id)) return false;
      navigate({ page: 'chapter', id }); return true;
    },
    openHome() { navigate({ page: 'home' }); }
  });
  history.replaceState(stateFor(), '', routes.format(route));
  history.scrollRestoration = 'manual';
  document.body.classList.add('manual-enhanced');
  document.querySelectorAll('[data-enhanced]').forEach(element => { element.hidden = false; });
  render();
})();

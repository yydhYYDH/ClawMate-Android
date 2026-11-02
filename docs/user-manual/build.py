"""Build the offline handbook with Python's standard library; --check is read-only."""
import argparse
import hashlib
import html
from html.parser import HTMLParser
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parent


class Links(HTMLParser):
    def __init__(self):
        super().__init__()
        self.ids = []
        self.refs = []
        self.images = []

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if 'id' in attrs:
            self.ids.append(attrs['id'])
        for key in ('href', 'src'):
            if key in attrs:
                self.refs.append(attrs[key])
        if tag == 'img' and 'src' in attrs:
            assert attrs.get('alt'), 'Image needs alternative text'
            self.images.append(attrs['src'])


def render():
    data = json.loads((ROOT / 'content.json').read_text(encoding='utf-8'))
    esc = html.escape
    nav, sections, cards = [], [], []
    for number, chapter in enumerate(data['chapters'], 1):
        cid, title = chapter['id'], esc(chapter['title'])
        assert re.fullmatch(r'[a-z]+', cid), cid
        nav.append(f'<a href="#{cid}"><span>{number:02}</span>{title}</a>')
        cards.append(f'<a class="guide-card" href="#{cid}"><span class="eyebrow">{number:02}</span>'
                     f'<strong>{title}</strong><span>{esc(chapter["summary"])}</span><b aria-hidden="true">阅读指南 →</b></a>')
        pager = []
        for offset, label in [(-1, '上一章'), (1, '下一章')]:
            adjacent = number - 1 + offset
            if 0 <= adjacent < len(data['chapters']):
                item = data['chapters'][adjacent]
                pager.append(f'<a href="#{item["id"]}"><span>{label}</span><strong>{esc(item["title"])}</strong></a>')
            else:
                pager.append('<a href="#top"><span>返回目录</span><strong>手册首页</strong></a>')
        figure = ''
        if chapter.get('image'):
            figure = (f'<figure><a class="zoom" href="assets/{esc(chapter["image"])}" '
                      f'aria-label="放大：{title}"><img src="assets/{esc(chapter["image"])}" '
                      f'alt="{esc(chapter["caption"])}" loading="lazy" width="1080" height="2374"></a>'
                      f'<figcaption>{esc(chapter["caption"])}<br>点击图片查看原图</figcaption></figure>')
        sections.append(f'<section id="{cid}" aria-labelledby="title-{cid}">'
                        f'<header><p class="eyebrow">使用指南 / {number:02}</p>'
                        f'<h2 id="title-{cid}" tabindex="-1">{title}</h2><p class="lead">{esc(chapter["summary"])}</p></header>'
                        f'<div class="chapter {"illustrated" if figure else ""}">{figure}'
                        f'<div class="instructions">{chapter["body"]}</div></div>'
                        f'<nav class="chapter-pager" aria-label="章节翻页">{"".join(pager)}</nav></section>')
    catalog = json.dumps(manifest(data), ensure_ascii=False).replace('<', '\\u003c')
    return f'''<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="description" content="ClawMate Android 共享版图文用户手册：聊天、图库、记忆、工作流、提醒、会议与分身。">
<title>{esc(data['title'])}</title><link rel="stylesheet" href="manual.css">
<script id="manual-catalog" type="application/json">{catalog}</script>
<script src="navigation.js" defer></script><script src="manual.js" defer></script></head>
<body><a class="skip" href="#content">跳到正文</a><div class="layout">
<div class="mobile-bar" data-enhanced hidden><a href="#top">ClawMate 手册</a><button id="menu-toggle" type="button" aria-controls="manual-sidebar" aria-expanded="false">目录 / 搜索</button></div>
<aside id="manual-sidebar" aria-label="手册目录"><a class="brand" href="#top">ClawMate<span>使用手册 / USER GUIDE</span></a>
<form id="search-form" role="search"><label for="search">查找功能或问题</label><div class="search-row"><input id="search" type="search" maxlength="200" placeholder="试试：图片、密钥、提醒" aria-controls="search-results"><button type="submit" data-enhanced hidden>搜索</button></div></form>
<p id="search-status" role="status" aria-live="polite">按任务查阅，随时回来。</p><nav id="chapter-nav">{''.join(nav)}</nav>
<button id="print" type="button" data-enhanced hidden>打印 / 保存为 PDF</button><p class="aside-note">离线可读 · 无需登录<br>截图可点击放大</p></aside>
<main id="content"><div class="reader-toolbar" data-enhanced hidden><button id="reader-back" type="button" disabled>← 返回</button><a href="#top">手册首页</a><span id="current-page"></span><span id="page-position"></span></div>
<div id="home-page"><header class="hero" id="top"><p class="eyebrow">{esc(data['edition'])}</p>
<h1 id="home-title" tabindex="-1">让 ClawMate<br>帮上你的忙。</h1><p class="intro">{esc(data['intro'])}</p>
<p class="meta">{esc(data['title'])} · 更新于 {esc(data['updated'])}</p>
<div class="quick"><a href="#start">开始第一次聊天 ↗</a><a href="#gallery">整理我的图片 ↗</a><a href="#help">解决使用问题 ↗</a></div>
<p class="version-note">截图来自已核看的测试设备页面，不含私人消息或密钥。主导航截图采于 SH-18；图库图为更早版本，变化已在图注说明。分身等新功能以文字步骤为准。当前仍有平台能力限制，请查看对应章节。</p></header>
<div class="guide-directory"><h2>按你想做的事，选一篇指南</h2><div class="guide-grid">{''.join(cards)}</div></div></div>
<section id="search-page" hidden aria-labelledby="search-title"><h2 id="search-title" tabindex="-1">搜索结果</h2><p id="result-count" role="status" aria-live="polite"></p><p id="empty" hidden>没有匹配的章节。试试“模型”“图库”，或清空搜索查看全部章节。</p><div id="search-results"></div><a href="#top">返回手册首页</a></section>
<section id="not-found" hidden aria-labelledby="not-found-title"><h2 id="not-found-title" tabindex="-1">页面未找到</h2><p>这个章节地址无效或已不再提供，请从目录重新选择。</p><a href="#top">返回手册首页</a></section>
<div id="chapters">{''.join(sections)}</div>
<footer>本手册随功能变化更新。普通用户从“第一次使用”开始即可；维护说明见 <a href="README.md">README</a>。<br>截图属于界面说明，不代表真实模型质量或所有设备均已验收。</footer></main></div>
<dialog id="image-viewer" aria-label="查看界面原图"><button id="close-image" type="button" aria-label="关闭原图">关闭 ×</button><img id="large-image" alt=""></dialog>
</body></html>
'''


def manifest(data):
    return {
        'schemaVersion': 1, 'title': data['title'], 'edition': data['edition'],
        'updated': data['updated'], 'entryFile': 'index.html', 'contentFile': 'content.json',
        'homeRoute': '#/', 'searchRoute': '#/search?q=',
        'chapters': [dict(id=c['id'], title=c['title'], summary=c['summary'],
                          route='#/chapter/' + c['id']) for c in data['chapters']]
    }


def validate(output):
    parser = Links()
    parser.feed(output)
    assert len(parser.ids) == len(set(parser.ids)), 'Duplicate anchors'
    for ref in parser.refs:
        if ref.startswith('#'):
            assert ref[1:] in parser.ids, f'Broken anchor: {ref}'
        else:
            path = (ROOT / ref).resolve()
            assert path.is_relative_to(ROOT), f'Nonlocal reference: {ref}'
            assert path.is_file(), f'Missing file: {ref}'
    manifest = json.loads((ROOT / 'screenshots.json').read_text(encoding='utf-8'))
    assert set(parser.images) == {item['file'] for item in manifest}, 'Screenshot inventory drift'
    for item in manifest:
        assert item['reviewed'] and item['source'] and item['limitations']
        digest = hashlib.sha256((ROOT / item['file']).read_bytes()).hexdigest()
        assert digest == item['sha256'], f'Changed screenshot: {item["file"]}'
    assert not re.search(r'https?://', output), 'Offline guide must not load remote resources'
    return len(parser.images)


if __name__ == '__main__':
    args = argparse.ArgumentParser()
    args.add_argument('--check', action='store_true')
    options = args.parse_args()
    output = render()
    count = validate(output)
    data = json.loads((ROOT / 'content.json').read_text(encoding='utf-8'))
    outputs = {'index.html': output,
               'app-manifest.json': json.dumps(manifest(data), ensure_ascii=False, indent=2) + '\n'}
    for filename, content in outputs.items():
        target = ROOT / filename
        if options.check:
            assert target.read_text(encoding='utf-8') == content, 'Run python docs/user-manual/build.py'
        else:
            with target.open('w', encoding='utf-8', newline='\n') as stream:
                stream.write(content)
    chapters = len(json.loads((ROOT / 'content.json').read_text(encoding='utf-8'))['chapters'])
    print(f'PASS: {chapters} chapters, {count} reviewed screenshots, local links and anchors, generated HTML consistency')

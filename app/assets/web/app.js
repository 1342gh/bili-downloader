/* =========================================================
   Bili 下载 · WebView 逻辑 + 插件系统
   Java 侧通过 window.bili.* 回调本文件
   ========================================================= */

const $ = id => document.getElementById(id);
const logEl = $('log');
const barEl = $('progress').querySelector('i');
const btn = $('go');

/* ---------------- 插件系统 ---------------- */

const PluginHost = {
  list: [],        // [{ name, ok, version, author }]
  hooks: { start: [], done: [], error: [] },

  reset() {
    this.list = [];
    this.hooks = { start: [], done: [], error: [] };
    $('pluginActions').innerHTML = '';
    $('pluginList').innerHTML = '';
  },

  render() {
    const box = $('pluginList');
    if (!this.list.length) {
      box.innerHTML = '<span class="err">未加载插件</span>';
      return;
    }
    box.innerHTML = this.list.map(p => {
      if (!p.ok) {
        return `<div class="err">${p.name} 加载失败：${p.error || '未知错误'}</div>`;
      }
      const meta = `v${p.version || '1.0'}${p.author ? ' · ' + p.author : ''}`;
      const desc = p.description ? `<div class="plugin-desc">${p.description}</div>` : '';
      const del = p.file ? `<button class="plugin-del" data-del="${p.file}">删除</button>` : '';
      return `<div><span class="name">${p.name}</span> <span class="ver">${meta}</span>${del}${desc}</div>`;
    }).join('');

    box.querySelectorAll('[data-del]').forEach(btn => {
      btn.addEventListener('click', () => {
        if (window.Android && Android.deletePlugin && Android.deletePlugin(btn.dataset.del)) {
          window.bili.log('已删除插件：' + btn.dataset.del, 'dim');
          PluginHost.loadAll();
        }
      });
    });
  },

  /** 执行一个插件源码 */
  exec(fileName, code) {
    const self = this;
    const meta = { name: fileName.replace(/\.js$/i, ''), version: '1.0', author: '', ok: true };

    const ctx = {
      log: (msg) => window.bili.log('[' + meta.name + '] ' + msg, 'dim'),
      button: (label, fn) => {
        const b = document.createElement('button');
        b.textContent = label;
        b.addEventListener('click', () => {
          try { fn(); } catch (e) { ctx.log('按钮出错：' + e.message); }
        });
        $('pluginActions').appendChild(b);
      },
      on: (event, fn) => {
        if (self.hooks[event]) self.hooks[event].push(fn);
      },
      options: () => ({
        url: $('url').value.trim(),
        cookie: $('cookie').value.trim(),
        content: parseInt($('content').value, 10),
        format: $('format').value,
        resHeight: parseInt($('res').value, 10),
        fps: parseInt($('fps').value, 10),
        interp: $('interp').value === '1'
      }),
      setUrl: (u) => { $('url').value = u; },
      setOption: (key, value) => {
        const el = $(key === 'res' ? 'res' : key === 'fps' ? 'fps' : key);
        if (!el) return;
        if (key === 'interp') el.value = value ? '1' : '0';
        else if (key === 'format') {
          for (let i = 0; i < el.options.length; i++) {
            if (el.options[i].text === value) { el.selectedIndex = i; break; }
          }
        } else {
          el.value = String(value);
        }
      }
    };

    const Bili = {
      plugin(decl) {
        if (!decl) return;
        if (decl.name) meta.name = decl.name;
        if (decl.version) meta.version = decl.version;
        if (decl.author) meta.author = decl.author;
        if (decl.description) meta.description = decl.description;
        if (typeof decl.onLoad === 'function') decl.onLoad(ctx);
        if (typeof decl.onStart === 'function') ctx.on('start', decl.onStart);
        if (typeof decl.onDone === 'function') ctx.on('done', decl.onDone);
        if (typeof decl.onError === 'function') ctx.on('error', decl.onError);
      }
    };

    meta.file = fileName;
    this.list.push(meta);

    try {
      // 包一层函数作用域，避免插件之间串变量
      new Function('Bili', 'ctx', code)(Bili, ctx);
    } catch (e) {
      meta.ok = false;
      meta.error = e.message;
      window.bili.log('插件 ' + meta.name + ' 出错：' + e.message, 'err');
    }
  },

  /** 从 Java 拿插件源码并全部加载 */
  loadAll() {
    this.reset();
    if (!window.Android || !Android.plugins) {
      this.render();
      return;
    }
    let raw = '[]';
    try { raw = Android.plugins(); } catch (e) { raw = '[]'; }
    let arr = [];
    try { arr = JSON.parse(raw); } catch (e) { arr = []; }
    for (const p of arr) this.exec(p.name, p.code);
    this.render();
  },

  fire(event, info) {
    for (const fn of (this.hooks[event] || [])) {
      try { fn(info); } catch (e) { /* 插件自身错误不影响主流程 */ }
    }
  }
};

/* ---------------- Java 调用的接口 ---------------- */

window.bili = {
  /** 追加一行日志 */
  log(text, cls) {
    if (logEl.dataset.first !== '1') {
      logEl.innerHTML = '';
      logEl.dataset.first = '1';
    }
    const span = document.createElement('span');
    if (cls) span.className = cls;
    span.textContent = text + '\n';
    logEl.appendChild(span);
    logEl.scrollTop = logEl.scrollHeight;
    window.scrollTo(0, document.body.scrollHeight);
  },

  clear() {
    logEl.dataset.first = '';
    logEl.textContent = '等待开始…';
  },

  progress(pct, show) {
    $('progress').classList.toggle('show', !!show);
    barEl.style.width = Math.max(0, Math.min(100, pct)) + '%';
  },

  running(on) {
    btn.disabled = !!on;
    btn.textContent = on ? '处理中…' : '开始下载';
  },

  setUrl(url) {
    if (url) $('url').value = url;
  },

  setCookie(v) {
    if (!v) return;
    $('cookie').value = v;
    try { localStorage.setItem('sessdata', v); } catch (e) {}
    if (window.Android && Android.checkLogin) Android.checkLogin(v);
  },

  device(soc, aiOk) {
    $('device').textContent = '设备：' + soc + (aiOk ? ' · AI 插帧可用' : ' · AI 插帧不可用');
    if (!aiOk) {
      const opt = $('interp').querySelector('option[value="1"]');
      if (opt) {
        opt.disabled = true;
        opt.textContent = 'AI 插帧（需 7+ Gen 2 及以上）';
      }
    }
  },

  autoStart() {
    setTimeout(startDownload, 250);
  },

  /** Java 回调：深色模式（跟随系统） */
  setTheme(dark, mode) {
    document.documentElement.dataset.theme = dark ? 'dark' : 'light';
    if (mode) syncThemeSwitch(mode);
  },

  /** Java 回调：账号状态（常驻显示） */
  account(name, vip, logged, failed) {
    const el = $('account');
    if (!el) return;
    if (failed) {
      el.textContent = '账号：检查失败（网络）';
      return;
    }
    if (logged && name) {
      el.innerHTML = '账号：<b>' + name + '</b>' + (vip ? ' · 大会员' : ' · 普通用户');
    } else {
      el.textContent = '账号：未登录（最高 480P）';
    }
  },

  /** 由 Java 调用：用浏览器 fetch 请求 API（走 Chromium 网络栈，避免 412） */
  fetchJson(id, url) {
    fetch(url, { credentials: 'include' })
      .then(r => r.text())
      .then(text => Android.onFetch(id, text))
      .catch(e => Android.onFetch(id, '{"code":-1,"message":' + JSON.stringify(String(e)) + '}'));
  },
  /** Java 触发的插件钩子：'start' | 'done' | 'error' */
  hook(event, info) {
    PluginHost.fire(event, info || {});
  }
};

/* ---------------- 交互 ---------------- */

function startDownload() {
  const url = $('url').value.trim();
  if (!url) {
    window.bili.clear();
    window.bili.log('先把链接粘进来。', 'err');
    return;
  }
  if (!window.Android || !Android.start) {
    window.bili.clear();
    window.bili.log('当前不在 App 内，无法下载。', 'err');
    return;
  }
  const opts = {
    url: url,
    cookie: $('cookie').value.trim(),
    content: parseInt($('content').value, 10),
    format: $('format').value,
    resHeight: parseInt($('res').value, 10),
    fps: parseInt($('fps').value, 10),
    interp: $('interp').value === '1'
  };
  try { localStorage.setItem('sessdata', opts.cookie); } catch (e) {}
  if (window.Android && Android.checkLogin) Android.checkLogin(opts.cookie);
  PluginHost.fire('start', opts);
  Android.start(opts.url, opts.cookie, opts.content, opts.format, opts.resHeight, opts.fps, opts.interp);
}

btn.addEventListener('click', startDownload);

/* 插件：从链接安装 */
$('pluginInstall').addEventListener('click', () => {
  const u = $('pluginUrl').value.trim();
  if (!u) { window.bili.log('先粘贴插件 .js 链接', 'err'); return; }
  if (!window.Android || !Android.installPlugin) { window.bili.log('当前不在 App 内', 'err'); return; }
  window.bili.log('正在安装插件…', 'dim');
  if (Android.installPlugin(u)) {
    $('pluginUrl').value = '';
    PluginHost.loadAll();
  }
});

/* 插件：生成模板 */
$('pluginTemplate').addEventListener('click', () => {
  if (window.Android && Android.createPluginTemplate && Android.createPluginTemplate()) {
    PluginHost.loadAll();
  }
});

/* 账号行：点击刷新 */
$('account').addEventListener('click', () => {
  const v = $('cookie').value.trim();
  $('account').textContent = '账号：检测中…';
  if (window.Android && Android.checkLogin) {
    Android.checkLogin(v);
  } else {
    $('account').textContent = '账号：浏览器预览模式';
  }
});

$('cookie').addEventListener('change', () => {
  const v = $('cookie').value.trim();
  try { localStorage.setItem('sessdata', v); } catch (e) {}
  if (window.Android && Android.checkLogin) Android.checkLogin(v);
});
$('pluginReload').addEventListener('click', () => {
  PluginHost.loadAll();
  window.bili.log('插件已重新加载（' + PluginHost.list.length + ' 个）', 'dim');
});

/* 使用说明折叠 */
function resetHelp() {
  const body = document.getElementById('helpBody');
  const chip = document.getElementById('helpChip');
  if (body) body.hidden = true;
  if (chip) chip.textContent = '展开';
  document.querySelectorAll('.sec-body').forEach(b => { b.hidden = true; });
  document.querySelectorAll('.sec-head .chip').forEach(c => { c.textContent = '展开'; });
  window.scrollTo(0, 0);
}

window.addEventListener('pageshow', resetHelp);

// 打开时回到顶部（Chromium 会恢复上次滚动位置，这里压两次）
window.addEventListener('load', () => {
  window.scrollTo(0, 0);
  setTimeout(() => window.scrollTo(0, 0), 120);
});

document.addEventListener('DOMContentLoaded', () => {
  const head = document.getElementById('helpToggle');
  if (head) {
    head.addEventListener('click', () => {
      const body = document.getElementById('helpBody');
      const chip = document.getElementById('helpChip');
      if (!body) return;
      body.hidden = !body.hidden;
      if (chip) chip.textContent = body.hidden ? '展开' : '收起';
    });
  }
});

/* 初始化 */
window.addEventListener('DOMContentLoaded', () => {
  try {
    const saved = localStorage.getItem('sessdata');
    if (saved) $('cookie').value = saved;
  } catch (e) {}

  resetHelp();

  if (window.Android) {
    try {
      window.bili.device(Android.soc(), Android.aiOk());
    } catch (e) {
      $('device').textContent = '';
    }
    PluginHost.loadAll();

    const ck = $('cookie').value.trim();
    if (ck && Android.checkLogin) Android.checkLogin(ck);
  } else {
    $('device').textContent = '浏览器预览模式';
    PluginHost.render();
  }
});

/* 供 Java 调用 */
window.reloadPlugins = () => PluginHost.loadAll();
/* ---------------- 登录 / 过滤 / 崩溃日志 ---------------- */

document.addEventListener('DOMContentLoaded', () => {
  const login = document.getElementById('biliLogin');
  if (login) login.addEventListener('click', () => {
    if (window.Android && Android.openBiliLogin) Android.openBiliLogin();
    else window.bili.log('当前不在 App 内', 'err');
  });

  // 载入过滤规则
  try {
    if (window.Android && Android.filterKeywords) {
      const k = document.getElementById('filterKeywords');
      const c = document.getElementById('filterChannels');
      if (k) k.value = Android.filterKeywords() || '';
      if (c) c.value = Android.filterChannels() || '';
    }
  } catch (e) {}

  const save = document.getElementById('filterSave');
  if (save) save.addEventListener('click', () => {
    if (!window.Android || !Android.saveFilters) return;
    const k = document.getElementById('filterKeywords').value || '';
    const c = document.getElementById('filterChannels').value || '';
    Android.saveFilters(k, c);
    window.bili.log('过滤规则已保存（关键词 ' + k.split('\n').filter(Boolean).length +
        ' 条，UP ' + c.split('\n').filter(Boolean).length + ' 条）', 'dim');
  });
});
/* 通用折叠区块 */
document.addEventListener('DOMContentLoaded', () => {
  document.querySelectorAll('.sec-head').forEach(head => {
    head.addEventListener('click', () => {
      const body = document.getElementById(head.dataset.target);
      if (!body) return;
      body.hidden = !body.hidden;
      const chip = head.querySelector('.chip');
      if (chip) chip.textContent = body.hidden ? '展开' : '收起';
    });
  });
});
/* ---------------- 主题三选开关 ---------------- */

function syncThemeSwitch(mode) {
  document.querySelectorAll('#themeSwitch button').forEach(b => {
    b.classList.toggle('on', b.dataset.mode === mode);
  });
}

document.addEventListener('DOMContentLoaded', () => {
  const box = document.getElementById('themeSwitch');
  if (!box) return;
  box.querySelectorAll('button').forEach(btn => {
    btn.addEventListener('click', () => {
      const mode = btn.dataset.mode;
      syncThemeSwitch(mode);
      if (window.Android && Android.setThemeMode) Android.setThemeMode(mode);
    });
  });
  try {
    if (window.Android && Android.themeMode) syncThemeSwitch(Android.themeMode());
  } catch (e) {}
});
/* ---------------- 播放 ---------------- */
document.addEventListener('DOMContentLoaded', () => {
  const play = document.getElementById('play');
  if (!play) return;
  play.addEventListener('click', () => {
    const url = document.getElementById('url').value.trim();
    if (!url) { window.bili.log('先把链接粘进来。', 'err'); return; }
    if (!window.Android || !Android.play) { window.bili.log('当前不在 App 内', 'err'); return; }
    Android.play(url, document.getElementById('cookie').value.trim());
  });
});
/* ---------------- 浏览 / 搜索 ---------------- */
(function () {
  var feedPage = 1;
  var mode = 'popular';
  var keyword = '';

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"]/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c];
    });
  }

  function fmtViews(n) {
    n = parseInt(n, 10) || 0;
    if (!n) return '';
    if (n >= 100000000) return (n / 100000000).toFixed(1) + '亿';
    if (n >= 10000) return (n / 10000).toFixed(1) + '万';
    return String(n);
  }

  function fmtDur(sec) {
    sec = parseInt(sec, 10) || 0;
    if (!sec) return '';
    var m = Math.floor(sec / 60), s = sec % 60;
    return m + ':' + (s < 10 ? '0' + s : s);
  }

  function fixPic(p) {
    if (!p) return '';
    if (p.indexOf('//') === 0) return 'https:' + p;
    return p.replace(/^http:/, 'https:');
  }

  function fromPopular(v) {
    return {
      bvid: v.bvid || '',
      cid: v.cid || 0,
      title: v.title || '',
      pic: fixPic(v.pic),
      owner: (v.owner && v.owner.name) || '',
      meta: [v.stat ? fmtViews(v.stat.view) : '', fmtDur(v.duration)].filter(Boolean).join(' · ')
    };
  }

  function fromSearch(v) {
    return {
      bvid: v.bvid || '',
      title: String(v.title || '').replace(/<[^>]+>/g, ''),
      pic: fixPic(v.pic),
      owner: v.author || '',
      meta: [v.play || '', v.duration || ''].filter(Boolean).join(' · ')
    };
  }

  function render(items) {
    var box = document.getElementById('feedList');
    if (!box) return;
    items = (items || []).filter(function (v) { return v.bvid; });
    if (!items.length) {
      box.innerHTML = '<div class="feed-empty">没加载到内容，换个词或点「换一批」</div>';
      return;
    }
    var html = '';
    items.forEach(function (v) {
      html += '<div class="vcard">' +
        '<img class="cover" loading="lazy" src="' + esc(v.pic) + '"' +
          ' onerror="this.style.background=\'#2a2f3a\';this.removeAttribute(\'src\')" alt="">' +
        '<div class="info">' +
          '<div class="title">' + esc(v.title) + '</div>' +
          '<div class="sub">' + esc(v.owner) + (v.meta ? ' · ' + esc(v.meta) : '') + '</div>' +
          '<div class="ops">' +
            '<button class="pl" data-play="' + esc(v.bvid) + '" data-cid="' + (v.cid || '') + '" data-title="' + esc(v.title) + '">播放</button>' +
            '<button class="dl" data-dl="' + esc(v.bvid) + '">下载</button>' +
          '</div>' +
        '</div></div>';
    });
    box.innerHTML = html;

    box.querySelectorAll('[data-dl]').forEach(function (b) {
      b.addEventListener('click', function () {
        var url = 'https://www.bilibili.com/video/' + b.dataset.dl;
        document.getElementById('url').value = url;
        window.bili.log('加入下载：' + url, 'dim');
        activateTab('down');
        if (!window.Android) return;
        Android.start(url, document.getElementById('cookie').value.trim(),
          parseInt(document.getElementById('content').value, 10),
          document.getElementById('format').value,
          parseInt(document.getElementById('res').value, 10),
          parseInt(document.getElementById('fps').value, 10),
          document.getElementById('interp').value === '1');
      });
    });

    box.querySelectorAll('[data-play]').forEach(function (b) {
      b.addEventListener('click', function () {
        var cookie = document.getElementById('cookie').value.trim();
        var url = 'https://www.bilibili.com/video/' + b.dataset.play;
        // 带上 cid 和标题：Java 侧可以跳过 view 接口，起播更快
        if (window.Android && Android.play2 && b.dataset.cid) {
          Android.play2(url, cookie, parseInt(b.dataset.cid, 10) || 0, b.dataset.title || '');
          return;
        }
        if (!window.Android || !Android.play) return;
        Android.play(url, cookie);
      });
    });

    // 预取会触发 B 站 412 风控（实测），这里不预取；靠 cid 直传 + 地址缓存加速

  }

  window.bili.feed = function (body) {
    try {
      var d = JSON.parse(body);
      var list = (d.data && (d.data.list || d.data.archives)) || [];
      render(list.map(fromPopular));
    } catch (e) { render([]); }
  };

  window.bili.searchResult = function (body) {
    try {
      var d = JSON.parse(body);
      var list = (d.data && d.data.result) || [];
      render(list.filter(function (x) { return x.type === 'video'; }).map(fromSearch));
    } catch (e) { render([]); }
  };

  function activateTab(tab) {
    document.querySelectorAll('#tabbar button').forEach(function (b) {
      b.classList.toggle('on', b.dataset.tab === tab);
    });
    document.getElementById('tabDown').hidden = tab !== 'down';
    document.getElementById('tabFeed').hidden = tab !== 'feed';
    var bar = document.querySelector('.actionbar');
    if (bar) bar.hidden = tab !== 'down';
    window.scrollTo(0, 0);
  }

  function setTitle(t) {
    var el = document.getElementById('feedTitle');
    if (el) el.textContent = t;
  }

  window.loadFeed = function (reset) {
    mode = 'popular';
    keyword = '';
    setTitle('热门视频');
    if (reset) feedPage = 1;
    var box = document.getElementById('feedList');
    if (box) box.innerHTML = '<div class="feed-empty">加载中…</div>';
    if (window.Android && Android.loadFeed) Android.loadFeed('popular', feedPage);
  };

  function doSearch() {
    var input = document.getElementById('searchInput');
    var kw = input ? input.value.trim() : '';
    if (!kw) { window.bili.log('先输入搜索词', 'err'); return; }
    mode = 'search';
    keyword = kw;
    feedPage = 1;
    setTitle('搜索：' + kw);
    var box = document.getElementById('feedList');
    if (box) box.innerHTML = '<div class="feed-empty">搜索中…</div>';
    if (window.Android && Android.search) Android.search(kw, 1);
  }

  document.addEventListener('DOMContentLoaded', function () {
    document.querySelectorAll('#tabbar button').forEach(function (btn) {
      btn.addEventListener('click', function () {
        var tab = btn.dataset.tab;
        activateTab(tab);
        if (tab === 'feed') loadFeed(true);
      });
    });

    var btn = document.getElementById('searchBtn');
    if (btn) btn.addEventListener('click', doSearch);

    var input = document.getElementById('searchInput');
    if (input) input.addEventListener('keydown', function (e) {
      if (e.key === 'Enter') { e.preventDefault(); doSearch(); }
    });

    var refresh = document.getElementById('feedRefresh');
    if (refresh) refresh.addEventListener('click', function () {
      if (mode === 'search') {
        feedPage += 1;
        var box = document.getElementById('feedList');
        if (box) box.innerHTML = '<div class="feed-empty">搜索中…</div>';
        if (window.Android && Android.search) Android.search(keyword, feedPage);
      } else {
        feedPage += 1;
        loadFeed(false);
      }
    });
  });
})();
/* ---------------- 应用内更新 ---------------- */
(function () {
  var pending = null;   // {versionCode, versionName, url, changelog}

  window.bili.updateResult = function (body, curCode, curName, quiet) {
    var banner = document.getElementById('updateBanner');
    var text = document.getElementById('updateText');
    if (!body) {
      if (!quiet) window.bili.log('检查更新失败（更新源不可用或未配置）', 'err');
      return;
    }
    var info;
    try { info = JSON.parse(body); } catch (e) {
      if (!quiet) window.bili.log('更新清单格式不对', 'err');
      return;
    }
    var latest = parseInt(info.versionCode, 10) || 0;
    if (latest <= (curCode || 0)) {
      if (!quiet) window.bili.log('已是最新版本 v' + curName, 'ok');
      if (banner) banner.hidden = true;
      return;
    }
    pending = info;
    var msg = '发现新版本 v' + (info.versionName || latest) +
      '（当前 v' + curName + '）';
    if (info.changelog) msg += '\n' + info.changelog;
    if (text) text.textContent = msg;
    if (banner) banner.hidden = false;
  };

  document.addEventListener('DOMContentLoaded', function () {
    var check = document.getElementById('updateCheck');
    var banner = document.getElementById('updateBanner');

    // 更新源固定在代码里（GitHub Release 的 latest.json），界面只留一个按钮
    if (check) check.addEventListener('click', function () {
      if (!window.Android || !Android.checkUpdate) return;
      window.bili.log('检查更新…', 'dim');
      Android.checkUpdate();
    });

    var go = document.getElementById('updateGo');
    if (go) go.addEventListener('click', function () {
      if (!pending || !window.Android || !Android.downloadUpdate) return;
      if (banner) banner.hidden = true;
      Android.downloadUpdate(pending.url,
        'BiliDownload_' + (pending.versionName || pending.versionCode) + '.apk');
    });

    var later = document.getElementById('updateLater');
    if (later) later.addEventListener('click', function () {
      if (banner) banner.hidden = true;
    });

    // 启动时的自动检查交给 Java 侧（checkUpdateAsync(false)），这里不再重复触发
  });
})();
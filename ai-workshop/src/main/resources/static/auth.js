// ===== 阶段 9：前端认证公共脚本 =====
// 用法：每个页面 <head> 里 <script src="auth.js"></script>，
//      页面加载时调用 requireAuth() + renderUserNav()。
// 核心：monkey-patch fetch —— 自动给所有 /api 请求带 Authorization: Bearer <token>，
//       收到 401 自动清 token 跳登录页（登录/注册接口除外）。各页面原有的 fetch 调用零改动。

const AIW_TOKEN = 'aiw_token';

function getToken() {
  return localStorage.getItem(AIW_TOKEN);
}
function setToken(t) {
  localStorage.setItem(AIW_TOKEN, t);
}
function clearToken() {
  localStorage.removeItem(AIW_TOKEN);
}

/** 页面加载检查：未登录直接跳登录页 */
function requireAuth() {
  if (!getToken()) {
    location.href = '/login.html';
  }
}

/** 退出登录 */
function logout() {
  clearToken();
  location.href = '/login.html';
}

/** 在导航栏右侧渲染 用户名 + 角色 + 退出（每个页面 nav 结构不同，动态插入最稳） */
function renderUserNav() {
  const nav = document.querySelector('nav');
  if (!nav) return;
  document.querySelectorAll('.user-area').forEach(e => e.remove());
  if (!getToken()) return;
  fetch('/api/auth/me')
    .then(r => r.json())
    .then(u => {
      if (!u || u.message) return;
      const span = document.createElement('span');
      span.className = 'user-area';
      span.style.cssText =
        'margin-left:auto;font-size:13px;display:flex;align-items:center;gap:10px;white-space:nowrap;color:var(--muted,#8a94a6);';
      span.innerHTML =
        '<b style="color:var(--text,#1f2733)">' + (u.displayName || u.username) + '</b>' +
        (u.role === 'ADMIN' ? '<span style="color:#d97706">管理员</span>' : '') +
        '<a href="#" onclick="logout();return false;" style="color:#e5484d;text-decoration:none">退出</a>';
      nav.appendChild(span);
    })
    .catch(() => {});
}

// ===== monkey-patch fetch：自动带 token + 401 统一处理 =====
(function () {
  const _fetch = window.fetch;
  window.fetch = function (url, options) {
    options = options || {};
    options.headers = new Headers(options.headers || {});
    const urlStr = String(url);
    // 除登录/注册接口外，自动带 token
    if (getToken() && !urlStr.includes('/api/auth/login') && !urlStr.includes('/api/auth/register')) {
      options.headers.set('Authorization', 'Bearer ' + getToken());
    }
    return _fetch(url, options).then(res => {
      // 401 = token 失效/未登录：清 token 跳登录（登录/注册接口的 401 由页面自己处理）
      if (res.status === 401
          && !urlStr.includes('/api/auth/login')
          && !urlStr.includes('/api/auth/register')
          && !location.pathname.endsWith('login.html')) {
        clearToken();
        location.href = '/login.html';
      }
      return res;
    });
  };
})();

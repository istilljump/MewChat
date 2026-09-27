/* ============================================================================
 * 小喵 · 运营后台脚本（无框架、无构建，与对话页 app.js 同一套约定）。
 *
 * 覆盖四块工作面（接口在 /api/admin/**，权限由服务端把关）：
 *   总览     管理员     —— 统计卡片 + 7 日消息量 + 待优化 Top 10
 *   工单     客服+管理员 —— 全部/我的、接单、结单、关闭、（管理员）指派、看对话
 *   对话记录 客服+管理员 —— 分页浏览、查看完整对话
 *   知识库   管理员     —— 录入（含 .txt/.md 导入）、重建索引、删除
 *   待优化   管理员     —— 飞轮清单：去补文档 → 标记已优化（闭环的收口）
 *
 * 两条贯穿全局的约定：
 * 1. 服务端返回 code!=0 或 401/403 时如实呈现，不假装成功；
 * 2. 用户消息与工单描述都是不可信内容 —— 渲染前一律 escapeHtml。
 * ========================================================================= */

(function () {
    'use strict';

    var TOKEN_KEY = 'mewchat.token';   // 与对话页共用：一边登录，两边可用
    var USER_KEY = 'mewchat.user';
    var PAGE_SIZE = 10;

    var state = {
        token: localStorage.getItem(TOKEN_KEY) || '',
        username: localStorage.getItem(USER_KEY) || '',
        role: 0,            // 1客户 2客服 3管理员，来自令牌 payload
        activeTab: '',
        tickets: { scope: 'all', status: '', page: 1, total: 0 },
        conversations: { status: '', page: 1, total: 0 },
        documents: { page: 1, total: 0 }
    };

    var el = {
        loginLayer: document.getElementById('login-layer'),
        loginForm: document.getElementById('login-form'),
        loginUser: document.getElementById('login-username'),
        loginPass: document.getElementById('login-password'),
        loginError: document.getElementById('login-error'),
        app: document.getElementById('app'),
        nav: document.getElementById('nav'),
        who: document.getElementById('who'),
        roleBadge: document.getElementById('role-badge'),
        logout: document.getElementById('logout'),
        toast: document.getElementById('toast'),
        modalMask: document.getElementById('modal-mask'),
        modalTitle: document.getElementById('modal-title'),
        modalBody: document.getElementById('modal-body'),
        modalClose: document.getElementById('modal-close')
    };

    /* ==================== 基础工具 ==================== */

    function toast(text) {
        el.toast.textContent = text;
        el.toast.hidden = false;
        clearTimeout(toast._timer);
        toast._timer = setTimeout(function () { el.toast.hidden = true; }, 3000);
    }

    /** 用户输入与业务数据都不可信：任何进 innerHTML 的内容先过这里 */
    function escapeHtml(text) {
        return String(text == null ? '' : text)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;');
    }

    /** 相对时间（刚刚 / 12 分钟前 / 昨天 09:30 …），解析失败原样返回 */
    function humanTime(text) {
        if (!text) { return '—'; }
        var time = new Date(String(text).replace(' ', 'T'));
        if (isNaN(time.getTime())) { return text; }

        var now = new Date();
        var seconds = (now.getTime() - time.getTime()) / 1000;
        if (seconds < 60) { return '刚刚'; }
        if (seconds < 3600) { return Math.floor(seconds / 60) + ' 分钟前'; }

        var pad = function (n) { return (n < 10 ? '0' : '') + n; };
        var clock = pad(time.getHours()) + ':' + pad(time.getMinutes());
        if (time.toDateString() === now.toDateString()) { return '今天 ' + clock; }
        var yesterday = new Date(now.getTime() - 24 * 3600 * 1000);
        if (time.toDateString() === yesterday.toDateString()) { return '昨天 ' + clock; }
        return (time.getMonth() + 1) + '-' + time.getDate() + ' ' + clock;
    }

    /**
     * 统一请求入口：401 回登录层、业务码非 0 抛出 message。
     *
     * <p>权限不足（403）不回登录层：那说明"登着但不该看"，把人踢回登录页
     * 只会让人反复登录然后反复 403。如实弹出原因即可 —— 真正的越权拦截在服务端。
     */
    async function api(path, options) {
        options = options || {};
        var headers = options.headers || {};
        headers['Accept'] = 'application/json';
        if (state.token) { headers['Authorization'] = 'Bearer ' + state.token; }
        if (options.body !== undefined) { headers['Content-Type'] = 'application/json; charset=UTF-8'; }

        var resp = await fetch(path, {
            method: options.method || 'GET',
            headers: headers,
            body: options.body === undefined ? undefined : JSON.stringify(options.body)
        });
        if (resp.status === 401) {
            signOut('登录已过期，请重新登录');
            throw new Error('unauthorized');
        }
        var json = await resp.json();
        if (json.code !== 0) {
            throw new Error(json.message || ('请求失败（code=' + json.code + '）'));
        }
        return json.data;
    }

    /** 与 api() 相同，但返回完整响应（code/message/data）—— 有些接口的 message 本身就是要展示的结果 */
    async function apiFull(path, options) {
        options = options || {};
        var headers = options.headers || {};
        headers['Accept'] = 'application/json';
        if (state.token) { headers['Authorization'] = 'Bearer ' + state.token; }
        if (options.body !== undefined) { headers['Content-Type'] = 'application/json; charset=UTF-8'; }
        var resp = await fetch(path, { method: options.method || 'GET', headers: headers,
            body: options.body === undefined ? undefined : JSON.stringify(options.body) });
        if (resp.status === 401) {
            signOut('登录已过期，请重新登录');
            throw new Error('unauthorized');
        }
        var json = await resp.json();
        if (json.code !== 0) { throw new Error(json.message || ('请求失败（code=' + json.code + '）')); }
        return json;
    }

    function signOut(message) {
        localStorage.removeItem(TOKEN_KEY);
        localStorage.removeItem(USER_KEY);
        state.token = '';
        state.username = '';
        state.role = 0;
        el.app.hidden = true;
        el.loginLayer.hidden = false;
        if (message) {
            el.loginError.textContent = message;
            el.loginError.hidden = false;
        }
    }

    /**
     * 从令牌 payload 解出用户类型。
     *
     * <p>payload 只是 Base64URL（userId|过期|userType|用户名），不是加密 ——
     * 前端解码只为决定显示哪些页签；就算本地脚本改成全显示，
     * 服务端对每个 /api/admin/** 请求的权限校验才是真闸门。
     */
    function readUserType(token) {
        try {
            var part = (token || '').split('.')[0].replace(/-/g, '+').replace(/_/g, '/');
            return parseInt(atob(part).split('|')[2], 10) || 1;
        } catch (e) {
            return 1;
        }
    }

    /* ==================== 登录与页签 ==================== */

    el.loginForm.addEventListener('submit', async function (event) {
        event.preventDefault();
        el.loginError.hidden = true;
        try {
            var data = await api('/api/auth/login', {
                method: 'POST',
                body: { username: el.loginUser.value.trim(), password: el.loginPass.value }
            });
            var role = readUserType(data.token);
            if (role < 2) {
                // 客户账号在对话页使用；后台对它整个是 403，提前告知比进门后四处碰壁好
                el.loginError.textContent = '当前账号没有后台权限（需要客服或管理员）。客户咨询请回到对话页。';
                el.loginError.hidden = false;
                return;
            }
            state.token = data.token;
            state.username = data.nickname || data.username || el.loginUser.value.trim();
            state.role = role;
            localStorage.setItem(TOKEN_KEY, state.token);
            localStorage.setItem(USER_KEY, state.username);
            await enterApp();
        } catch (e) {
            if (e.message !== 'unauthorized') {
                el.loginError.textContent = e.message;
                el.loginError.hidden = false;
            }
        }
    });

    el.logout.addEventListener('click', function () { signOut(); });

    async function enterApp() {
        el.loginLayer.hidden = true;
        el.app.hidden = false;
        el.who.textContent = state.username;
        el.roleBadge.textContent = state.role === 3 ? '管理员' : '客服';

        // 页签按角色显隐（data-roles 里列出允许的用户类型）。
        // 服务端仍然逐请求鉴权 —— 这里隐藏只是让界面与权限一致
        var items = el.nav.querySelectorAll('.nav-item');
        items.forEach(function (item) {
            var roles = (item.dataset.roles || '').split(',');
            item.hidden = roles.indexOf(String(state.role)) < 0;
        });

        // 默认进第一个可见页签：管理员看总览，客服直达工单
        var first = Array.prototype.find.call(items, function (item) { return !item.hidden; });
        if (first) { switchTab(first.dataset.tab); }
    }

    function switchTab(name) {
        state.activeTab = name;
        el.nav.querySelectorAll('.nav-item').forEach(function (item) {
            item.classList.toggle('active', item.dataset.tab === name);
        });
        document.querySelectorAll('.tab').forEach(function (tab) {
            tab.hidden = tab.id !== 'tab-' + name;
        });
        if (name === 'overview') { loadOverview(); }
        if (name === 'tickets') { loadTickets(); }
        if (name === 'conversations') { loadConversations(); }
        if (name === 'knowledge') { loadDocuments(); }
        if (name === 'flywheel') { loadChecklist(); }
    }

    el.nav.addEventListener('click', function (event) {
        var item = event.target.closest('.nav-item');
        if (item && !item.hidden) { switchTab(item.dataset.tab); }
    });

    /* ==================== 弹层 ==================== */

    function openModal(title, node) {
        el.modalTitle.textContent = title;
        el.modalBody.innerHTML = '';
        el.modalBody.appendChild(node);
        el.modalMask.hidden = false;
    }

    function closeModal() { el.modalMask.hidden = true; }
    el.modalClose.addEventListener('click', closeModal);
    // 点遮罩空白处关闭；点弹窗内部不关（避免误触丢掉正在填的表单）
    el.modalMask.addEventListener('click', function (event) {
        if (event.target === el.modalMask) { closeModal(); }
    });

    function emptyHint(text) {
        var p = document.createElement('p');
        p.className = 'empty-hint';
        p.textContent = text;
        return p;
    }

    /* ==================== 总览 ==================== */

    async function loadOverview() {
        var grid = document.getElementById('overview-cards');
        grid.innerHTML = '';
        grid.appendChild(emptyHint('正在加载…'));
        var trendEl = document.getElementById('overview-trend');
        var topqEl = document.getElementById('overview-topq');

        var data;
        try {
            data = await api('/api/admin/analytics/overview');
        } catch (e) {
            grid.innerHTML = '';
            grid.appendChild(emptyHint('加载失败：' + e.message));
            return;
        }

        var m = data.messages;
        var feedbackTotal = m.feedbackUp + m.feedbackDown;
        var rate = feedbackTotal > 0 ? Math.round(m.feedbackUp * 100 / feedbackTotal) : null;

        grid.innerHTML = '';
        grid.appendChild(statCard('会话', [
            ['总数', data.conversations.total],
            ['进行中', data.conversations.active],
            ['已结束', data.conversations.closed],
            ['已转人工', data.conversations.handoff]
        ]));
        grid.appendChild(statCard('回答质量', [
            ['平均置信度', m.avgConfidence == null ? '—' : Number(m.avgConfidence).toFixed(2)],
            ['低置信回答', m.lowConfidenceCount, m.lowConfidenceCount > 0],
            ['平均耗时', m.avgCostMs == null ? '—' : m.avgCostMs + ' ms'],
            ['累计 token', m.totalTokens == null ? '—' : m.totalTokens]
        ]));
        grid.appendChild(statCard('用户反馈', [
            ['👍 有用', m.feedbackUp],
            ['👎 没用', m.feedbackDown],
            ['点赞率', rate == null ? '暂无反馈' : rate + '%']
        ]));
        grid.appendChild(statCard('工单', [
            ['总数', data.tickets.total],
            ['待处理', data.tickets.pending, data.tickets.pending > 0],
            ['处理中', data.tickets.processing],
            ['已解决', data.tickets.resolved],
            ['已关闭', data.tickets.closed]
        ]));
        grid.appendChild(statCard('知识库', [
            ['文档', data.knowledge.documents],
            ['切片', data.knowledge.chunks],
            ['入库失败', data.knowledge.failedDocuments, data.knowledge.failedDocuments > 0]
        ]));
        grid.appendChild(statCard('数据飞轮', [
            ['待优化问题', data.flywheel.pendingQuestions, data.flywheel.pendingQuestions > 0],
            ['已聚类问题', data.flywheel.clusteredQuestions],
            ['累计命中', data.flywheel.totalHits]
        ]));

        renderTrend(trendEl, data.dailyMessages);
        renderTopQuestions(topqEl, data.topUnresolvedQuestions);
    }

    function statCard(title, rows) {
        var card = document.createElement('div');
        card.className = 'stat-card';
        var h = document.createElement('h4');
        h.textContent = title;
        card.appendChild(h);
        rows.forEach(function (row) {
            var line = document.createElement('div');
            line.className = 'stat-row';
            var name = document.createElement('span');
            name.textContent = row[0];
            var value = document.createElement('b');
            value.textContent = String(row[1]);
            // 第三位参数表示"这个数值得被看见"（待处理 > 0 之类）：标红提示
            if (row[2]) { value.classList.add('warn'); }
            line.appendChild(name);
            line.appendChild(value);
            card.appendChild(line);
        });
        return card;
    }

    /** 7 日消息量：纯 CSS 柱状图，只画有数据的日子 */
    function renderTrend(container, daily) {
        container.innerHTML = '';
        if (!daily || !daily.length) {
            container.appendChild(emptyHint('近 7 天没有消息'));
            return;
        }
        var max = 0;
        daily.forEach(function (d) { if (d.count > max) { max = d.count; } });
        daily.forEach(function (d) {
            var wrap = document.createElement('div');
            wrap.className = 'bar-wrap';
            var num = document.createElement('span');
            num.className = 'bar-num';
            num.textContent = d.count;
            var bar = document.createElement('div');
            bar.className = 'bar';
            bar.style.height = Math.max(4, Math.round(d.count * 100 / (max || 1))) + '%';
            var day = document.createElement('span');
            day.className = 'bar-day';
            day.textContent = String(d.date).slice(5); // MM-dd
            day.title = d.date;
            wrap.appendChild(num);
            wrap.appendChild(bar);
            wrap.appendChild(day);
            container.appendChild(wrap);
        });
    }

    function renderTopQuestions(container, list) {
        container.innerHTML = '';
        if (!list || !list.length) {
            container.appendChild(emptyHint('没有待优化的问题 —— 知识库当前覆盖住了用户的提问'));
            return;
        }
        list.forEach(function (item) {
            var row = document.createElement('div');
            row.className = 'topq-item';
            var hits = document.createElement('span');
            hits.className = 'hits';
            hits.textContent = '×' + (item.hitCount || 0);
            hits.title = '命中次数' + (item.confidence == null ? '' : '，最低置信度 ' + Number(item.confidence).toFixed(2));
            var q = document.createElement('span');
            q.className = 'q';
            q.textContent = item.question;
            row.appendChild(hits);
            row.appendChild(q);
            container.appendChild(row);
        });
    }

    /* ==================== 工单 ==================== */

    document.getElementById('ticket-status').addEventListener('change', function (event) {
        state.tickets.status = event.target.value;
        state.tickets.page = 1;
        loadTickets();
    });
    document.getElementById('ticket-refresh').addEventListener('click', function () { loadTickets(); });
    document.getElementById('ticket-scope').addEventListener('click', function (event) {
        var button = event.target.closest('button[data-scope]');
        if (!button || button.classList.contains('active')) { return; }
        document.querySelectorAll('#ticket-scope button').forEach(function (b) {
            b.classList.toggle('active', b === button);
        });
        state.tickets.scope = button.dataset.scope;
        state.tickets.page = 1;
        loadTickets();
    });
    document.getElementById('ticket-prev').addEventListener('click', function () {
        if (state.tickets.page > 1) { state.tickets.page--; loadTickets(); }
    });
    document.getElementById('ticket-next').addEventListener('click', function () {
        if (state.tickets.page * PAGE_SIZE < state.tickets.total) { state.tickets.page++; loadTickets(); }
    });

    async function loadTickets() {
        var list = document.getElementById('ticket-list');
        list.innerHTML = '';
        list.appendChild(emptyHint('正在加载…'));

        var mine = state.tickets.scope === 'my';
        var path = (mine ? '/api/admin/tickets/my' : '/api/admin/tickets')
            + '?page=' + state.tickets.page + '&size=' + PAGE_SIZE
            + (state.tickets.status !== '' ? '&status=' + state.tickets.status : '');

        var page;
        try {
            page = await api(path);
        } catch (e) {
            list.innerHTML = '';
            list.appendChild(emptyHint('加载失败：' + e.message));
            return;
        }

        state.tickets.total = page.total;
        list.innerHTML = '';
        if (!page.records.length) {
            list.appendChild(emptyHint(mine ? '你的名下没有符合条件的工单' : '没有符合条件的工单'));
        }
        page.records.forEach(function (ticket) {
            list.appendChild(ticketCard(ticket, mine));
        });
        renderPager('ticket', state.tickets, page.total);
        document.getElementById('ticket-count').textContent = '共 ' + page.total + ' 张';
    }

    function ticketCard(ticket, mine) {
        var card = document.createElement('div');
        card.className = 'ticket-card';

        var head = document.createElement('div');
        head.className = 'ticket-head';
        var id = document.createElement('span');
        id.className = 'tid';
        id.textContent = '#' + ticket.id;
        var badge = document.createElement('span');
        badge.className = 'badge s' + ticket.status;
        badge.textContent = ticket.statusLabel || statusText(ticket.status);
        head.appendChild(id);
        head.appendChild(badge);
        var spacer = document.createElement('span');
        spacer.className = 'spacer';
        head.appendChild(spacer);
        var created = document.createElement('span');
        created.className = 'meta';
        created.title = ticket.createTime || '';
        created.textContent = humanTime(ticket.createTime);
        head.appendChild(created);
        card.appendChild(head);

        var desc = document.createElement('div');
        desc.className = 'ticket-desc';
        desc.textContent = ticket.description || '（无描述）';
        card.appendChild(desc);

        var foot = document.createElement('div');
        foot.className = 'ticket-foot';
        foot.appendChild(smallText('用户 ' + (ticket.userId == null ? '（游客）' : '#' + ticket.userId)));
        foot.appendChild(smallText('处理人 ' + (ticket.handlerId == null ? '未指派' : '#' + ticket.handlerId)));
        if (ticket.finishTime) { foot.appendChild(smallText('完结 ' + humanTime(ticket.finishTime))); }

        var view = document.createElement('button');
        view.type = 'button';
        view.className = 'link-btn';
        view.textContent = '看对话';
        view.addEventListener('click', function () { showConversation(ticket.sessionId); });
        foot.appendChild(view);

        // 操作按钮按状态给：只有待处理能接（接单语义就是"从待处理队列拿走"），
        // 处理中才能结/关，已解决还可以补一个关闭归档
        if (ticket.status === 0) {
            foot.appendChild(actionLink('接单', function () {
                return api('/api/admin/tickets/' + ticket.id + '/claim', { method: 'POST' });
            }, '已接单，工单进入处理中', loadTickets));
        }
        if (ticket.status === 1) {
            foot.appendChild(actionLink('结单（已解决）', function () {
                return api('/api/admin/tickets/' + ticket.id + '/resolve', { method: 'POST' });
            }, '已标记为已解决', loadTickets));
            foot.appendChild(actionLink('关闭', function () {
                return api('/api/admin/tickets/' + ticket.id + '/close', { method: 'POST' });
            }, '已关闭', loadTickets));
        }
        if (ticket.status === 2) {
            foot.appendChild(actionLink('关闭归档', function () {
                return api('/api/admin/tickets/' + ticket.id + '/close', { method: 'POST' });
            }, '已关闭', loadTickets));
        }
        card.appendChild(foot);
        return card;
    }

    function smallText(text) {
        var span = document.createElement('span');
        span.textContent = text;
        return span;
    }

    function statusText(status) {
        return ['待处理', '处理中', '已解决', '已关闭'][status] || ('未知(' + status + ')');
    }

    /**
     * 生成一个"点了就调接口"的操作链接。
     *
     * <p>请求若用 apiFull() 发出，服务端的 message（如重建索引的如实回执）
     * 优先于静态文案 —— 那句话里可能带着"向量库未启用"这类关键信息。
     */
    function actionLink(label, request, fallbackMessage, after) {
        var link = document.createElement('button');
        link.type = 'button';
        link.className = 'link-btn';
        link.textContent = label;
        link.addEventListener('click', async function () {
            link.disabled = true;
            try {
                var result = await request();
                var message = (result && result.message) || fallbackMessage;
                if (message) { toast(message); }
                if (after) { after(); }
            } catch (e) {
                // 接单被同事抢先、结单已完结这类竞争失败是正常业务，如实原样呈现
                toast(e.message);
                link.disabled = false;
            }
        });
        return link;
    }

    /* ==================== 对话记录 ==================== */

    document.getElementById('conv-status').addEventListener('change', function (event) {
        state.conversations.status = event.target.value;
        state.conversations.page = 1;
        loadConversations();
    });
    document.getElementById('conv-refresh').addEventListener('click', function () { loadConversations(); });
    document.getElementById('conv-prev').addEventListener('click', function () {
        if (state.conversations.page > 1) { state.conversations.page--; loadConversations(); }
    });
    document.getElementById('conv-next').addEventListener('click', function () {
        if (state.conversations.page * PAGE_SIZE < state.conversations.total) { state.conversations.page++; loadConversations(); }
    });

    async function loadConversations() {
        var list = document.getElementById('conv-list');
        list.innerHTML = '';
        list.appendChild(emptyHint('正在加载…'));

        var path = '/api/admin/conversations?page=' + state.conversations.page + '&size=' + PAGE_SIZE
            + (state.conversations.status !== '' ? '&status=' + state.conversations.status : '');
        var page;
        try {
            page = await api(path);
        } catch (e) {
            list.innerHTML = '';
            list.appendChild(emptyHint('加载失败：' + e.message));
            return;
        }

        state.conversations.total = page.total;
        list.innerHTML = '';
        if (!page.records.length) {
            list.appendChild(emptyHint('没有符合条件的会话'));
        }
        page.records.forEach(function (conversation) {
            var row = document.createElement('div');
            row.className = 'conv-row';
            row.addEventListener('click', function () { showConversation(conversation.sessionId); });

            var badge = document.createElement('span');
            badge.className = 'badge';
            badge.textContent = conversation.statusLabel || ('状态' + conversation.status);
            row.appendChild(badge);

            var title = document.createElement('span');
            title.className = 'title';
            title.textContent = conversation.title || '（无标题）';
            row.appendChild(title);

            var meta = document.createElement('span');
            meta.className = 'meta';
            meta.textContent = (conversation.messageCount || 0) + ' 条 · 用户 #' + (conversation.userId == null ? '?' : conversation.userId)
                + ' · ' + humanTime(conversation.lastMessageTime || conversation.startTime);
            row.appendChild(meta);

            var sid = document.createElement('span');
            sid.className = 'sid';
            sid.textContent = conversation.sessionId.slice(0, 8) + '…';
            sid.title = conversation.sessionId;
            row.appendChild(sid);

            list.appendChild(row);
        });
        renderPager('conv', state.conversations, page.total);
    }

    /**
     * 在弹层里查看一段完整对话（工单与对话列表共用）。
     *
     * <p>这是系统里权限最敏感的能力（能读任意用户的完整对话），
     * 所以弹层顶部明确提示"仅工作需要时查看"，把合规意识带在界面上。
     */
    async function showConversation(sessionId) {
        var wrap = document.createElement('div');
        wrap.appendChild(emptyHint('正在加载对话…'));
        openModal('对话详情 · ' + sessionId.slice(0, 8) + '…', wrap);

        var messages;
        try {
            messages = await api('/api/admin/conversations/' + encodeURIComponent(sessionId) + '/messages');
        } catch (e) {
            wrap.innerHTML = '';
            wrap.appendChild(emptyHint('加载失败：' + e.message));
            return;
        }

        wrap.innerHTML = '';
        if (!messages.length) {
            wrap.appendChild(emptyHint('这段会话还没有消息'));
            return;
        }
        var notice = document.createElement('p');
        notice.className = 'hint';
        notice.textContent = '仅限工作需要时查看用户对话；请不要外传。';
        wrap.appendChild(notice);

        messages.forEach(function (message) {
            var row = document.createElement('div');
            row.className = 'dm ' + (message.role === 'user' ? 'user' : 'assistant');
            var bubble = document.createElement('div');
            bubble.className = 'dm-bubble';
            bubble.textContent = message.content;
            var meta = document.createElement('div');
            meta.className = 'dm-meta';
            var parts = [(message.role === 'user' ? '用户' : '助手'), humanTime(message.createTime)];
            if (message.agentName) { parts.push(message.agentName); }
            if (message.confidence != null) { parts.push('置信度 ' + Number(message.confidence).toFixed(2)); }
            if (message.totalTokens != null) { parts.push(message.totalTokens + ' tokens'); }
            if (message.costMs != null) { parts.push(message.costMs + ' ms'); }
            meta.textContent = parts.join(' · ');
            // 失败原因单独标红：它解释了"为什么这段对话没有回答"
            if (message.errorMsg) {
                var err = document.createElement('span');
                err.className = 'err';
                err.textContent = ' · 失败：' + message.errorMsg;
                meta.appendChild(err);
            }
            bubble.appendChild(meta);
            row.appendChild(bubble);
            wrap.appendChild(row);
        });
    }

    /* ==================== 知识库 ==================== */

    document.getElementById('doc-refresh').addEventListener('click', function () { loadDocuments(); });
    document.getElementById('doc-prev').addEventListener('click', function () {
        if (state.documents.page > 1) { state.documents.page--; loadDocuments(); }
    });
    document.getElementById('doc-next').addEventListener('click', function () {
        if (state.documents.page * PAGE_SIZE < state.documents.total) { state.documents.page++; loadDocuments(); }
    });

    /**
     * 从本地文件导入正文。
     *
     * <p>用 FileReader 在浏览器里读成文本再走既有的 JSON 录入接口，
     * 而不是新开一个 multipart 上传端点：服务端对内容的全部校验
     * （长度、分片、入库）都在那条路径上，这里复用它就不需要第二套校验。
     * 只接受 .txt / .md —— PDF/Word 的正文抽取需要解析库，当前不支持，如实告知。
     */
    document.getElementById('doc-file').addEventListener('change', function (event) {
        var file = event.target.files && event.target.files[0];
        var info = document.getElementById('doc-file-info');
        if (!file) { info.textContent = ''; return; }
        if (!/\.(txt|md|markdown)$/i.test(file.name)) {
            info.textContent = '';
            toast('只支持 .txt / .md 文件（PDF/Word 需要先转成文本）');
            event.target.value = '';
            return;
        }
        var reader = new FileReader();
        reader.onload = function () {
            var text = String(reader.result || '').trim();
            if (!text) {
                info.textContent = '';
                toast('文件是空的');
                return;
            }
            if (text.length > 500000) {
                // 与 CreateDocumentRequest 的 @Size(500_000) 同源：超限到服务端也是被拒
                info.textContent = '';
                toast('正文超过 50 万字符上限（当前 ' + text.length + '），请拆分后导入');
                return;
            }
            document.getElementById('doc-content').value = text;
            if (!document.getElementById('doc-title').value.trim()) {
                // 文件名去掉扩展名当标题候选：用户可以直接改
                document.getElementById('doc-title').value = file.name.replace(/\.(txt|md|markdown)$/i, '');
            }
            info.textContent = '已导入「' + file.name + '」（' + text.length + ' 字符）';
        };
        reader.onerror = function () {
            info.textContent = '';
            toast('读取文件失败');
        };
        reader.readAsText(file, 'utf-8');
    });

    document.getElementById('doc-submit').addEventListener('click', async function () {
        var button = this;
        var title = document.getElementById('doc-title').value.trim();
        var content = document.getElementById('doc-content').value.trim();
        if (!title) { toast('标题不能为空'); return; }
        if (!content) { toast('正文不能为空'); return; }
        button.disabled = true;
        try {
            var full = await apiFull('/api/admin/knowledge/documents', {
                method: 'POST',
                body: { title: title, content: content, category: document.getElementById('doc-category').value.trim() || null }
            });
            // 回执是服务端斟酌过的话术（关键词可用 / 向量化是否完成），原样呈现而不是自己编一句"成功"
            toast(full.message || '已入库');
            document.getElementById('doc-title').value = '';
            document.getElementById('doc-category').value = '';
            document.getElementById('doc-content').value = '';
            document.getElementById('doc-file').value = '';
            document.getElementById('doc-file-info').textContent = '';
            state.documents.page = 1;
            loadDocuments();
        } catch (e) {
            toast('录入失败：' + e.message);
        } finally {
            button.disabled = false;
        }
    });

    async function loadDocuments() {
        var list = document.getElementById('doc-list');
        list.innerHTML = '';
        list.appendChild(emptyHint('正在加载…'));

        var page;
        try {
            page = await api('/api/admin/knowledge/documents?page=' + state.documents.page + '&size=' + PAGE_SIZE);
        } catch (e) {
            list.innerHTML = '';
            list.appendChild(emptyHint('加载失败：' + e.message));
            return;
        }

        state.documents.total = page.total;
        list.innerHTML = '';
        if (!page.records.length) {
            list.appendChild(emptyHint('知识库还是空的 —— 用上面的表单录入第一篇文档'));
        }
        page.records.forEach(function (doc) {
            var row = document.createElement('div');
            row.className = 'doc-row';

            var badge = document.createElement('span');
            badge.className = 'badge k' + (doc.embedStatus == null ? 0 : doc.embedStatus);
            badge.textContent = doc.embedStatusLabel || ('状态' + doc.embedStatus);
            row.appendChild(badge);

            var title = document.createElement('span');
            title.className = 'title';
            title.textContent = doc.title;
            title.title = doc.embedError || '';
            row.appendChild(title);

            var meta = document.createElement('span');
            meta.className = 'meta';
            meta.textContent = (doc.category || '未分类') + ' · ' + (doc.chunkCount || 0) + ' 片 · ' + humanTime(doc.updateTime || doc.createTime);
            row.appendChild(meta);

            // 重建索引：向量化依赖外部服务，上传那一刻不可用就会失败，
            // 运营需要能对单个文档手动重试（结果说明由服务端给）
            var reindex = actionLink('重建索引', function () {
                return apiFull('/api/admin/knowledge/documents/' + doc.id + '/reindex', { method: 'POST' });
            }, '重建完成', loadDocuments);
            row.appendChild(reindex);

            // 删除是"删干净"（MySQL 与向量库两侧一起删）且不可恢复：给一次两步确认的刹车
            var del = document.createElement('button');
            del.type = 'button';
            del.className = 'link-btn';
            del.textContent = '删除';
            del.addEventListener('click', function () {
                if (!del.classList.contains('armed')) {
                    del.classList.add('armed');
                    del.textContent = '确认删除？';
                    clearTimeout(del._timer);
                    del._timer = setTimeout(function () {
                        del.classList.remove('armed');
                        del.textContent = '删除';
                    }, 3000);
                    return;
                }
                clearTimeout(del._timer);
                deleteDocument(doc);
            });
            row.appendChild(del);

            list.appendChild(row);
        });
        renderPager('doc', state.documents, page.total);
    }

    async function deleteDocument(doc) {
        try {
            await api('/api/admin/knowledge/documents/' + doc.id, { method: 'DELETE' });
            toast('已删除「' + doc.title + '」');
            loadDocuments();
        } catch (e) {
            toast('删除失败：' + e.message);
        }
    }

    /* ==================== 待优化清单（飞轮） ==================== */

    document.getElementById('recluster').addEventListener('click', async function () {
        var button = this;
        button.disabled = true;
        try {
            var result = await api('/api/admin/analytics/clustering/recluster', { method: 'POST' });
            toast('聚类完成：参与 ' + result.pendingQuestions + ' 条，聚出 ' + result.clusterCount + ' 个簇');
            loadChecklist();
        } catch (e) {
            toast('重算失败：' + e.message);
        } finally {
            button.disabled = false;
        }
    });

    async function loadChecklist() {
        var list = document.getElementById('cluster-list');
        var summary = document.getElementById('flywheel-summary');
        list.innerHTML = '';
        summary.textContent = '';
        list.appendChild(emptyHint('正在加载…'));

        var data;
        try {
            data = await api('/api/admin/analytics/optimization-checklist');
        } catch (e) {
            list.innerHTML = '';
            list.appendChild(emptyHint('加载失败：' + e.message));
            return;
        }

        summary.textContent = data.totalClusters + ' 个簇 / ' + data.totalQuestions + ' 条问题待优化';
        list.innerHTML = '';
        if (!data.clusters.length) {
            list.appendChild(emptyHint('清单是空的 —— 用户的问题当前都能答上，或已被标记优化'));
            return;
        }
        data.clusters.forEach(function (cluster) {
            list.appendChild(clusterCard(cluster));
        });
    }

    function clusterCard(cluster) {
        var card = document.createElement('div');
        card.className = 'cluster-card';

        var head = document.createElement('div');
        head.className = 'cluster-head';
        var q = document.createElement('span');
        q.className = 'cluster-q';
        q.textContent = cluster.representativeQuestion;
        head.appendChild(q);
        var meta = document.createElement('span');
        meta.className = 'cluster-meta';
        meta.textContent = cluster.size + ' 条问法 · 命中 ×' + cluster.totalHits
            + (cluster.worstConfidence == null ? '' : ' · 最低置信度 ' + Number(cluster.worstConfidence).toFixed(2))
            + ' · 最近 ' + humanTime(cluster.lastSeenTime);
        head.appendChild(meta);
        card.appendChild(head);

        var actions = document.createElement('div');
        actions.className = 'cluster-actions';

        // 去补文档：跳到知识库页签，把代表问题预填进标题 ——
        // 运营不用在两个界面之间手抄问题原文
        var goDoc = document.createElement('button');
        goDoc.type = 'button';
        goDoc.className = 'btn btn-ghost';
        goDoc.textContent = '✍️ 去补文档';
        goDoc.addEventListener('click', function () {
            document.getElementById('doc-title').value = cluster.representativeQuestion;
            document.getElementById('doc-content').focus();
            switchTab('knowledge');
            toast('标题已带入，正文补完后回来标记已优化');
        });
        actions.appendChild(goDoc);

        // 展开成员：簇只是"字面相似"的归并，同义改写要靠人工确认
        var toggle = document.createElement('button');
        toggle.type = 'button';
        toggle.className = 'btn btn-ghost';
        toggle.textContent = '▸ 展开成员（' + (cluster.questions ? cluster.questions.length : 0) + '）';
        toggle.addEventListener('click', function () {
            var members = card.querySelector('.member-list');
            var open = members.classList.toggle('open');
            toggle.textContent = (open ? '▾ 收起成员' : '▸ 展开成员（' + cluster.questions.length + '）');
        });
        actions.appendChild(toggle);
        card.appendChild(actions);

        var members = document.createElement('div');
        members.className = 'member-list';
        cluster.questions.forEach(function (question) {
            var row = document.createElement('div');
            row.className = 'member-row';
            row.dataset.questionId = question.id;

            var mq = document.createElement('span');
            mq.className = 'q';
            mq.textContent = question.question;
            row.appendChild(mq);

            var mhits = document.createElement('span');
            mhits.className = 'hits';
            mhits.textContent = '×' + (question.hitCount || 0);
            row.appendChild(mhits);

            // 标记已优化必须挂到一个真实存在的知识文档上（服务端校验）：
            // 这一步的意义是让"这篇文档解决了哪些问题"可追溯
            var mark = actionLink('标记已优化', function () {
                return openMarkOptimized(question, row);
            }, '', null);
            row.appendChild(mark);
            members.appendChild(row);
        });
        card.appendChild(members);
        return card;
    }

    /**
     * 标记已优化：弹层里选定"这个问题由哪篇文档解决"。
     *
     * <p>文档候选从知识库最近 50 篇里选，也允许手输ID ——
     * 运营刚补完的文档一定在第一页，手输是为旧文档留的口子。
     * 标记成功后把该行划掉：清单会收敛，飞轮才算闭环。
     */
    async function openMarkOptimized(question, row) {
        var form = document.createElement('div');
        form.className = 'optimize-form';
        form.innerHTML = '<p style="word-break:break-all"><b>' + escapeHtml(question.question) + '</b></p>'
            + '<label>由哪篇知识文档解决？'
            + '<select id="opt-doc"><option value="">加载文档列表…</option></select></label>'
            + '<label>或直接输入文档ID <input id="opt-doc-id" type="text" placeholder="留空则用上面选择的文档"></label>'
            + '<p class="hint">标记后该问题从待优化清单消失；只对"待优化"的行生效，重复标记无害。</p>';

        var actions = document.createElement('div');
        actions.style.marginTop = '12px';
        var confirm = document.createElement('button');
        confirm.type = 'button';
        confirm.className = 'btn btn-primary';
        confirm.textContent = '标记已优化';
        actions.appendChild(confirm);
        form.appendChild(actions);
        openModal('标记已优化', form);

        // 拉文档候选（最近 50 篇）
        var select = form.querySelector('#opt-doc');
        try {
            var page = await api('/api/admin/knowledge/documents?page=1&size=50');
            select.innerHTML = '';
            if (!page.records.length) {
                select.innerHTML = '<option value="">知识库是空的 —— 请先去补文档</option>';
            } else {
                select.innerHTML = '<option value="">— 选择文档 —</option>';
                page.records.forEach(function (doc) {
                    var option = document.createElement('option');
                    option.value = doc.id;
                    option.textContent = '#' + doc.id + ' ' + doc.title;
                    select.appendChild(option);
                });
            }
        } catch (e) {
            select.innerHTML = '<option value="">文档列表加载失败</option>';
        }

        confirm.addEventListener('click', async function () {
            var docId = form.querySelector('#opt-doc-id').value.trim() || select.value;
            if (!docId) { toast('请选择或输入解决该问题的知识文档ID'); return; }
            confirm.disabled = true;
            try {
                await api('/api/admin/analytics/questions/' + question.id + '/optimize', {
                    method: 'POST',
                    // 刻意保持字符串：文档ID是 19 位雪花ID，超出 JS Number 的安全整数范围，
                    // Number() 转一圈会静默丢精度（实测 2103026848315940865 → …900）——
                    // 全项目的 Long 都按字符串传输（JacksonConfig），这里必须延续同一约定，
                    // Jackson 会把合法的数字字符串无损地反序列化成 Long
                    body: { knowledgeDocId: docId }
                });
                closeModal();
                row.classList.add('done');
                row.querySelector('.link-btn').disabled = true;
                toast('已标记为已优化');
                loadChecklist();
            } catch (e) {
                toast('标记失败：' + e.message);
                confirm.disabled = false;
            }
        });
    }

    /* ==================== 分页 ==================== */

    function renderPager(prefix, pageState, total) {
        var pages = Math.max(1, Math.ceil(total / PAGE_SIZE));
        document.getElementById(prefix + '-page-label').textContent = '第 ' + pageState.page + ' / ' + pages + ' 页';
        document.getElementById(prefix + '-prev').disabled = pageState.page <= 1;
        document.getElementById(prefix + '-next').disabled = pageState.page >= pages;
    }

    /* ==================== 启动 ==================== */

    (async function boot() {
        if (!state.token) {
            el.loginLayer.hidden = false;
            return;
        }
        // 带着旧令牌进来：先看角色。客户角色没有后台权限，直接回登录层
        var role = readUserType(state.token);
        if (role < 2) {
            signOut();
            return;
        }
        state.role = role;
        try {
            await enterApp();
        } catch (e) {
            signOut();
        }
    })();
})();

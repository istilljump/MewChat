/* ============================================================================
 * 小喵 · 智能客服 —— 对话前端脚本（无框架、无构建）
 *
 * 三件必须按服务端约定来做的事：
 *
 * 1. **SSE 用 fetch + ReadableStream，不用 EventSource。**
 *    EventSource 无法自定义请求头，而本项目所有接口都要 Authorization: Bearer。
 *    把令牌塞进查询参数也能跑，但令牌会进访问日志 —— 这里选择自己解析事件流。
 *
 * 2. **done 事件是权威结果，替换而不是追加。**
 *    推送中途失败时已经到客户端的片段收不回来，此时服务端会降级为兜底话术；
 *    若前端把片段当最终答案，用户看到的就和落库的内容不一致（刷新后"回答变了"）。
 *
 * 3. **会话列表与历史都只认令牌。**
 *    接口不接受"查谁的会话"这类参数，前端也不需要提供 —— 归属天然由登录态决定。
 *
 * 其余是纯展示：状态进度、引用来源、置信度与转人工提示。
 * ========================================================================= */

(function () {
    'use strict';

    var TOKEN_KEY = 'mewchat.token';
    var USER_KEY = 'mewchat.user';

    var state = {
        token: localStorage.getItem(TOKEN_KEY) || '',
        username: localStorage.getItem(USER_KEY) || '',
        currentSessionId: '',
        sessions: [],
        sending: false
    };

    var el = {
        loginLayer: document.getElementById('login-layer'),
        loginForm: document.getElementById('login-form'),
        loginUser: document.getElementById('login-username'),
        loginPass: document.getElementById('login-password'),
        loginError: document.getElementById('login-error'),
        app: document.getElementById('app'),
        who: document.getElementById('who'),
        logout: document.getElementById('logout'),
        sessions: document.getElementById('session-list'),
        newSession: document.getElementById('new-session'),
        messages: document.getElementById('messages'),
        emptyState: document.getElementById('empty-state'),
        input: document.getElementById('input'),
        send: document.getElementById('send'),
        toast: document.getElementById('toast')
    };

    /* ==================== 基础工具 ==================== */

    function toast(text) {
        el.toast.textContent = text;
        el.toast.hidden = false;
        clearTimeout(toast._timer);
        toast._timer = setTimeout(function () { el.toast.hidden = true; }, 2600);
    }

    function scrollToBottom() {
        el.messages.scrollTop = el.messages.scrollHeight;
    }

    /** 退出登录：清掉本地令牌并回到登录层（服务端是无状态令牌，没有"登出接口"） */
    function signOut(message) {
        localStorage.removeItem(TOKEN_KEY);
        localStorage.removeItem(USER_KEY);
        state.token = '';
        state.username = '';
        state.currentSessionId = '';
        el.app.hidden = true;
        el.loginLayer.hidden = false;
        if (message) {
            el.loginError.textContent = message;
            el.loginError.hidden = false;
        }
    }

    /**
     * 统一请求入口。
     *
     * @param path 路径
     * @param options fetch 选项（body 传对象即可，这里负责序列化）
     */
    async function api(path, options) {
        options = options || {};
        var headers = options.headers || {};
        headers['Accept'] = 'application/json';
        if (state.token) {
            headers['Authorization'] = 'Bearer ' + state.token;
        }
        if (options.body !== undefined) {
            headers['Content-Type'] = 'application/json; charset=UTF-8';
        }
        var resp = await fetch(path, {
            method: options.method || 'GET',
            headers: headers,
            body: options.body === undefined ? undefined : JSON.stringify(options.body)
        });

        // 401/403 是安全过滤器给出的真实 HTTP 状态码；业务失败是 200 + 非 0 业务码。
        // 两种都要处理：前者代表登录态失效，后者代表这次操作没成功。
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

    /* ==================== 登录 ==================== */

    el.loginForm.addEventListener('submit', async function (event) {
        event.preventDefault();
        el.loginError.hidden = true;
        try {
            var data = await api('/api/auth/login', {
                method: 'POST',
                body: { username: el.loginUser.value.trim(), password: el.loginPass.value }
            });
            state.token = data.token;
            // 优先显示昵称（如"演示客户"），没有昵称时退回登录名
            state.username = data.nickname || data.username || el.loginUser.value.trim();
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
        el.emptyState.hidden = false;
        el.messages.innerHTML = '';
        el.messages.appendChild(el.emptyState);
        await loadSessions();
    }

    /* ==================== 会话列表 ==================== */

    async function loadSessions() {
        state.sessions = await api('/api/chat/sessions');
        renderSessions();
    }

    function renderSessions() {
        el.sessions.innerHTML = '';
        if (!state.sessions.length) {
            var tip = document.createElement('p');
            tip.className = 'hint';
            tip.style.padding = '0 4px';
            tip.textContent = '还没有历史会话。';
            el.sessions.appendChild(tip);
            return;
        }
        state.sessions.forEach(function (session, index) {
            var item = document.createElement('button');
            item.type = 'button';
            item.className = 'session-item' + (session.sessionId === state.currentSessionId ? ' active' : '');
            item.dataset.sessionId = session.sessionId;

            var no = document.createElement('div');
            no.className = 'session-no';
            no.textContent = '#' + (state.sessions.length - index);

            var title = document.createElement('span');
            title.className = 'session-title';
            title.textContent = session.title || '（无标题）';

            var meta = document.createElement('div');
            meta.className = 'session-meta';
            meta.textContent = (session.messageCount || 0) + ' 条 · ' + (session.lastMessageTime || '');

            item.appendChild(no);
            item.appendChild(title);
            item.appendChild(meta);
            item.addEventListener('click', function () { openSession(session.sessionId); });
            el.sessions.appendChild(item);
        });
    }

    /* ==================== 历史与渲染 ==================== */

    function clearMessages() {
        el.messages.innerHTML = '';
        el.emptyState.hidden = true;
    }

    async function openSession(sessionId) {
        if (state.sending) { return; }
        state.currentSessionId = sessionId;
        renderSessions();
        clearMessages();

        var history;
        try {
            history = await api('/api/chat/session/' + encodeURIComponent(sessionId) + '/history');
        } catch (e) {
            toast(e.message);
            return;
        }
        if (!history.length) {
            el.emptyState.hidden = false;
            el.messages.appendChild(el.emptyState);
            return;
        }
        history.forEach(function (message) {
            if (message.role === 'user') {
                appendUserBubble(message.content);
            } else {
                var card = appendAnswerCard();
                card.body.textContent = message.content;
                renderCitations(card, message.citations);
                renderMeta(card, {
                    agentName: message.agentName,
                    confidence: message.confidence
                });
            }
        });
        scrollToBottom();
    }

    function appendUserBubble(text) {
        var row = document.createElement('div');
        row.className = 'msg user';
        var bubble = document.createElement('div');
        bubble.className = 'bubble-user';
        bubble.textContent = text;
        row.appendChild(bubble);
        el.messages.appendChild(row);
        scrollToBottom();
    }

    /** 新建一条助手回答卡片，返回可继续填充的几个节点 */
    function appendAnswerCard() {
        var row = document.createElement('div');
        row.className = 'msg assistant';

        var card = document.createElement('div');
        card.className = 'answer-card';

        var progress = document.createElement('div');
        progress.className = 'progress-line';
        progress.hidden = true;

        var body = document.createElement('div');
        body.className = 'answer-body';

        var sources = document.createElement('div');
        sources.className = 'sources';

        var meta = document.createElement('div');
        meta.className = 'answer-meta';

        var actions = document.createElement('div');
        actions.className = 'answer-actions';
        actions.hidden = true;

        card.appendChild(progress);
        card.appendChild(body);
        card.appendChild(sources);
        card.appendChild(meta);
        card.appendChild(actions);
        row.appendChild(card);
        el.messages.appendChild(row);
        scrollToBottom();

        return { row: row, card: card, progress: progress, body: body, sources: sources, meta: meta, actions: actions };
    }

    /**
     * 渲染引用来源。
     *
     * 两处来源的字段名一致（chunkId / docTitle / chunkNo / score），
     * 因此历史消息（CitationView）与 done 事件（RetrievedChunk）可以共用这一段。
     * 角标只做"编号 → 文档与段落"的对应，不编造正文里的引用位置：
     * 当前提示词并没有要求模型在正文里标 [n]，凭空插入角标等于伪造出处位置。
     */
    function renderCitations(card, citations) {
        if (!citations || !citations.length) { return; }
        var line = document.createElement('div');
        citations.forEach(function (citation, index) {
            var chip = document.createElement('span');
            chip.className = 'cite-chip';
            chip.textContent = String(index + 1);
            chip.title = '《' + (citation.docTitle || '未知文档') + '》第 ' + (citation.chunkNo || '?') + ' 段'
                + (citation.score == null ? '' : '（相关度 ' + Number(citation.score).toFixed(2) + '）');
            line.appendChild(chip);
        });
        card.sources.appendChild(line);

        var list = document.createElement('ol');
        citations.forEach(function (citation) {
            var item = document.createElement('li');
            item.textContent = '《' + (citation.docTitle || '未知文档') + '》第 ' + (citation.chunkNo || '?') + ' 段'
                + (citation.score == null ? '' : '，相关度 ' + Number(citation.score).toFixed(2));
            list.appendChild(item);
        });
        card.sources.appendChild(list);
    }

    function renderMeta(card, info) {
        card.meta.innerHTML = '';
        if (info.agentName) {
            var agent = document.createElement('span');
            agent.textContent = '处理：' + info.agentName;
            card.meta.appendChild(agent);
        }
        if (info.confidence != null) {
            var conf = document.createElement('span');
            conf.textContent = '置信度 ' + Number(info.confidence).toFixed(2);
            card.meta.appendChild(conf);
        }
        if (info.costMs != null) {
            var cost = document.createElement('span');
            cost.textContent = '耗时 ' + info.costMs + ' ms';
            card.meta.appendChild(cost);
        }
        if (info.handoffRequired || info.finalState === 'FALLBACK') {
            var badge = document.createElement('span');
            badge.className = 'notice-badge';
            badge.textContent = '已转人工';
            card.meta.appendChild(badge);
        }
    }

    /** 点赞/点踩：接口尚未实现，如实告知而不是假装提交成功 */
    function renderActions(card) {
        card.actions.innerHTML = '';
        ['👍', '👎'].forEach(function (icon) {
            var button = document.createElement('button');
            button.type = 'button';
            button.className = 'icon-btn';
            button.textContent = icon;
            button.addEventListener('click', function () {
                toast('反馈功能尚未接入后端（当前版本不做静默假成功）');
            });
            card.actions.appendChild(button);
        });
        card.actions.hidden = false;
    }

    /* ==================== 新对话 ==================== */

    el.newSession.addEventListener('click', async function () {
        if (state.sending) { return; }
        try {
            // 会话ID由服务端生成（不接受前端自造）：这样"ID 不可枚举"这条性质由服务端保证
            var sessionId = await api('/api/chat/session', { method: 'POST' });
            state.currentSessionId = sessionId;
            renderSessions();
            clearMessages();
            el.emptyState.hidden = false;
            el.messages.appendChild(el.emptyState);
            el.input.focus();
        } catch (e) {
            toast(e.message);
        }
    });

    /* ==================== 发送与流式接收 ==================== */

    async function ensureSession() {
        if (state.currentSessionId) { return state.currentSessionId; }
        state.currentSessionId = await api('/api/chat/session', { method: 'POST' });
        return state.currentSessionId;
    }

    async function send() {
        var text = el.input.value.trim();
        if (!text || state.sending) { return; }
        var sessionId;
        try {
            sessionId = await ensureSession();
        } catch (e) {
            toast(e.message);
            return;
        }

        state.sending = true;
        el.send.disabled = true;
        el.input.value = '';
        el.input.style.height = 'auto';
        clearMessages();
        appendUserBubble(text);

        var card = appendAnswerCard();
        card.progress.hidden = false;
        card.progress.textContent = '正在处理';

        try {
            await streamReply(sessionId, text, card);
        } catch (e) {
            card.progress.hidden = true;
            card.body.textContent = '本轮处理失败：' + e.message;
        } finally {
            state.sending = false;
            el.send.disabled = false;
            renderActions(card);
            card.progress.hidden = true;
            await loadSessions().catch(function () { /* 列表刷新失败不影响本轮对话 */ });
            scrollToBottom();
            el.input.focus();
        }
    }

    /**
     * 发起一轮对话并解析 SSE 事件流。
     *
     * 帧格式与服务端 SseStreamListener 的约定一致：
     *   event: session|state|message|done|error
     *   data:  Result 的 JSON（code/message/data）
     */
    async function streamReply(sessionId, text, card) {
        var resp = await fetch('/api/chat/send', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json; charset=UTF-8',
                'Accept': 'text/event-stream',
                'Authorization': 'Bearer ' + state.token
            },
            body: JSON.stringify({ sessionId: sessionId, message: text })
        });

        if (resp.status === 401) {
            signOut('登录已过期，请重新登录');
            throw new Error('登录已过期');
        }
        if (!resp.ok) {
            // 归属校验失败等业务错误会以非流式 JSON 返回（HTTP 200 + 业务码走下面的解析）
            throw new Error('HTTP ' + resp.status);
        }

        var reader = resp.body.getReader();
        var decoder = new TextDecoder('utf-8');
        var buffer = '';
        var finalText = '';
        var gotFragment = false;
        var failure = null;

        for (;;) {
            var chunk = await reader.read();
            if (chunk.done) { break; }
            buffer += decoder.decode(chunk.value, { stream: true });

            var sep;
            while ((sep = buffer.indexOf('\n\n')) >= 0) {
                var frame = buffer.slice(0, sep);
                buffer = buffer.slice(sep + 2);
                var event = parseFrame(frame);
                if (!event) { continue; }

                var payload = safeParse(event.data);
                if (!payload) { continue; }

                if (event.name === 'state') {
                    if (!gotFragment) { card.progress.textContent = payload.data || '正在处理'; }
                } else if (event.name === 'message') {
                    gotFragment = true;
                    card.body.textContent += (payload.data || '');
                    scrollToBottom();
                } else if (event.name === 'done') {
                    // 权威结果：整体替换（流式过程中可能推过半句话，且可能已被降级覆盖）
                    finalText = (payload.data && payload.data.content) || finalText;
                    card.body.textContent = finalText;
                    renderCitations(card, payload.data && payload.data.citations);
                    renderMeta(card, payload.data || {});
                } else if (event.name === 'error') {
                    failure = payload.message || '处理失败';
                }
            }
        }

        if (failure) { throw new Error(failure); }
        if (!finalText && !gotFragment) {
            card.body.textContent = '本轮没有收到内容，请重试。';
        }
    }

    /** 解析一帧事件：取 event 名与 data 内容（多行 data 按 SSE 规范拼接） */
    function parseFrame(frame) {
        var name = 'message';
        var dataLines = [];
        frame.split('\n').forEach(function (line) {
            line = line.replace(/\r$/, '');
            if (line.indexOf('event:') === 0) {
                name = line.slice(6).trim();
            } else if (line.indexOf('data:') === 0) {
                dataLines.push(line.slice(5).replace(/^ /, ''));
            }
        });
        if (!dataLines.length) { return null; }
        return { name: name, data: dataLines.join('\n') };
    }

    function safeParse(text) {
        try { return JSON.parse(text); } catch (e) { return null; }
    }

    /* ==================== 输入框行为 ==================== */

    el.send.addEventListener('click', send);

    el.input.addEventListener('keydown', function (event) {
        if (event.key === 'Enter' && !event.shiftKey) {
            event.preventDefault();
            send();
        }
    });

    // 输入框随内容长高（上限由 CSS 的 max-height 控制）
    el.input.addEventListener('input', function () {
        el.input.style.height = 'auto';
        el.input.style.height = Math.min(el.input.scrollHeight, 140) + 'px';
    });

    /* ==================== 启动 ==================== */

    // 带着旧令牌直接进来：先验证令牌还能用（拉一次会话列表），401 会自动回到登录层
    (async function boot() {
        if (!state.token) {
            el.loginLayer.hidden = false;
            return;
        }
        try {
            await enterApp();
        } catch (e) {
            signOut();
        }
    })();
})();

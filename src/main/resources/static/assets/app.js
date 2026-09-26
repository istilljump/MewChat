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
        clearAll: document.getElementById('clear-all'),
        charCount: document.getElementById('char-count'),
        menuToggle: document.getElementById('menu-toggle'),
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

    /**
     * 把服务端的时间串转成人话。
     *
     * <p>服务端给的是 {@code yyyy-MM-dd HH:mm:ss}，直接铺在侧边栏里既长又难扫读；
     * "刚刚 / 12 分钟前 / 今天 15:04 / 昨天 09:30 / 09-24 16:12" 扫一眼就知道先后。
     * 解析失败时原样返回 —— 时间显示不出来不该让整个列表崩掉。
     *
     * @param text 服务端时间串
     * @return 展示用的相对时间
     */
    function humanTime(text) {
        if (!text) { return ''; }
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
     * 复制文本到剪贴板。
     *
     * <p>优先用 Clipboard API；不可用时退回"临时 textarea + execCommand"——
     * 后者虽已过时，但在非安全上下文（用局域网 IP 打开页面）里是唯一可行的办法，
     * 而用户复制回答正文的需求与此无关，不该因为环境而点不动。
     *
     * @param text 待复制文本
     * @return Promise，成功 resolve、失败 reject
     */
    function copyText(text) {
        if (navigator.clipboard && navigator.clipboard.writeText) {
            return navigator.clipboard.writeText(text);
        }
        return new Promise(function (resolve, reject) {
            var area = document.createElement('textarea');
            area.value = text;
            area.style.position = 'fixed';
            area.style.opacity = '0';
            document.body.appendChild(area);
            area.select();
            try {
                document.execCommand('copy') ? resolve() : reject(new Error('浏览器拒绝了复制'));
            } catch (e) {
                reject(e);
            } finally {
                document.body.removeChild(area);
            }
        });
    }

    function scrollToBottom() {
        el.messages.scrollTop = el.messages.scrollHeight;
    }

    /**
     * 是否贴着消息区底部。
     *
     * 流式输出期间的"自动滚到底"只应发生在用户本来就在看最新内容时 ——
     * 用户正往上翻历史，却被每秒几次的滚动拽回底部，是聊天界面最招人烦的行为之一。
     * 阈值取 80px：比一行文字略高，既不误判也不需要精确贴底。
     */
    function nearBottom() {
        return el.messages.scrollHeight - el.messages.scrollTop - el.messages.clientHeight < 80;
    }

    /** 仅当用户贴底时才跟随滚动（见 nearBottom 的说明） */
    function autoScroll() {
        if (nearBottom()) {
            el.messages.scrollTop = el.messages.scrollHeight;
        }
    }

    /**
     * 转义全部 HTML 特殊字符。
     *
     * 回答正文可能来自模型输出、也可能来自知识库片段 —— 都是不可直接信任的内容。
     * 一律先转义，后续的"格式化"只往安全的方向加标签，不保留原文里的任何标签。
     */
    function escapeHtml(text) {
        return text
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;');
    }

    /**
     * 极简 Markdown 渲染（自实现，不引库）。
     *
     * 为什么只支持一个子集：客服回答里实际出现的是加粗、列表、行内代码、分段这几样；
     * 完整 Markdown 要引入解析库（一个外部依赖 + 一份要跟踪的安全公告），
     * 而其中大部分能力在这个场景里永远用不上。自实现约 60 行，行为完全可预期。
     *
     * 安全模型：输入先经 escapeHtml 全量转义，之后只做"文本 -> 白名单标签"的替换 ——
     * 原文里的任何 HTML 都只会以文字形式出现，不存在二次解析。
     * 流式过程中未闭合的标记（如只有一个 **）按字面显示，闭合后自然恢复格式。
     *
     * @param text 回答原文（未转义）
     * @return 可安全 innerHTML 的 HTML 片段
     */
    function renderMarkdown(text) {
        if (!text) { return ''; }
        var lines = escapeHtml(text).split(/\r?\n/);
        var out = [];
        var listTag = null;

        // 结束进行中的列表（遇到非列表行或文末时调用）
        function closeList() {
            if (listTag) {
                out.push('</' + listTag + '>');
                listTag = null;
            }
        }

        // 行内格式：`代码` 与 **加粗**。先代码后加粗，避免代码内部的星号被误判
        function inline(line) {
            return line
                .replace(/`([^`]+)`/g, '<code>$1</code>')
                .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
        }

        for (var i = 0; i < lines.length; i++) {
            var line = lines[i];
            var bullet = line.match(/^\s*[-*]\s+(.*)$/);
            // 有序序号：ASCII "1. "（必须有空格，否则 "1.5万元" 会被误判成列表项）；
            // 中文 "1、第一条"（顿号后通常不空格，因此不要求）
            var ordered = line.match(/^\s*\d+(?:[.]\s+|、\s*)(.*)$/);

            if (bullet) {
                if (listTag !== 'ul') {
                    closeList();
                    out.push('<ul>');
                    listTag = 'ul';
                }
                out.push('<li>' + inline(bullet[1]) + '</li>');
            } else if (ordered) {
                if (listTag !== 'ol') {
                    closeList();
                    out.push('<ol>');
                    listTag = 'ol';
                }
                out.push('<li>' + inline(ordered[1]) + '</li>');
            } else if (!line.trim()) {
                // 空行 = 分段；列表在这里结束
                closeList();
            } else {
                closeList();
                out.push('<p>' + inline(line) + '</p>');
            }
        }
        closeList();
        return out.join('');
    }

    /** 意图枚举名到中文名的映射（服务端 done 事件里带的是枚举名） */
    var INTENT_LABELS = {
        KNOWLEDGE_QA: '知识问答',
        ORDER_QUERY: '订单查询',
        LOGISTICS_QUERY: '物流查询',
        PRODUCT_QUERY: '商品查询',
        REFUND_ASK: '退款咨询',
        COMPLAINT: '投诉建议',
        UNKNOWN: '无法识别'
    };

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

    // 清空全部会话：与单项删除同一套两步确认
    el.clearAll.addEventListener('click', function () {
        if (!el.clearAll.classList.contains('armed')) {
            el.clearAll.classList.add('armed');
            el.clearAll.textContent = '确认清空？';
            clearTimeout(el.clearAll._timer);
            el.clearAll._timer = setTimeout(function () {
                el.clearAll.classList.remove('armed');
                el.clearAll.textContent = '清空';
            }, 3000);
            return;
        }
        clearTimeout(el.clearAll._timer);
        el.clearAll.classList.remove('armed');
        el.clearAll.textContent = '清空';
        clearAllSessions();
    });

    async function enterApp() {
        el.loginLayer.hidden = true;
        el.app.hidden = false;
        el.who.textContent = state.username;
        el.emptyState.hidden = false;
        el.messages.innerHTML = '';
        el.messages.appendChild(el.emptyState);
        renderEmptyStateExamples();
        await loadSessions();

        // 自动打开最近一段会话：刷新页面后"接着上次聊"是默认期望，
        // 让用户自己再点一下才能看到上文，等于把恢复现场的成本转嫁给用户。
        // 没有任何历史会话时保持空状态（示例问题正好派上用场）
        if (!state.currentSessionId && state.sessions.length) {
            await openSession(state.sessions[0].sessionId);
        }
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
            // 用 div 而不是 button：里面还要放"删除"按钮，按钮套按钮是无效 HTML
            var item = document.createElement('div');
            item.className = 'session-item' + (session.sessionId === state.currentSessionId ? ' active' : '');
            item.dataset.sessionId = session.sessionId;
            item.title = (session.title || '未命名会话');
            item.addEventListener('click', function () { openSession(session.sessionId); });

            var no = document.createElement('div');
            no.className = 'session-no';
            no.textContent = '#' + (state.sessions.length - index);

            var title = document.createElement('span');
            title.className = 'session-title';
            title.textContent = session.title || '（未命名会话）';

            var meta = document.createElement('div');
            meta.className = 'session-meta';
            meta.textContent = (session.messageCount || 0) + ' 条 · ' + humanTime(session.lastMessageTime);

            var del = document.createElement('button');
            del.type = 'button';
            del.className = 'session-del';
            del.textContent = '×';
            del.title = '删除这段会话';
            // 两步确认而不是 window.confirm：原生弹窗打断操作流、样式也无法统一；
            // 这里点一次变成"确认删除"、再点一次才真删，3 秒不动自动恢复
            del.addEventListener('click', function (event) {
                event.stopPropagation();
                if (!del.classList.contains('armed')) {
                    del.classList.add('armed');
                    del.textContent = '确认删除';
                    clearTimeout(del._timer);
                    del._timer = setTimeout(function () {
                        del.classList.remove('armed');
                        del.textContent = '×';
                    }, 3000);
                    return;
                }
                clearTimeout(del._timer);
                deleteSession(session);
            });

            item.appendChild(no);
            item.appendChild(title);
            item.appendChild(meta);
            item.appendChild(del);
            el.sessions.appendChild(item);
        });
    }

    /**
     * 删除一段会话（连同它的消息）。
     *
     * <p>删的是"正在看的这一段"时，视图要回到空状态 —— 否则屏幕还留着一段
     * 服务端已经不存在的内容，再发消息会因为会话已被删除而失败。
     *
     * @param session 会话列表项
     */
    async function deleteSession(session) {
        if (state.sending) {
            toast('正在回答中，稍后再删');
            return;
        }
        try {
            var messages = await api('/api/chat/session/' + encodeURIComponent(session.sessionId), {
                method: 'DELETE'
            });
            if (state.currentSessionId === session.sessionId) {
                state.currentSessionId = '';
                clearMessages();
                el.emptyState.hidden = false;
                el.messages.appendChild(el.emptyState);
            }
            await loadSessions();
            toast('已删除该会话（含 ' + messages + ' 条消息）');
        } catch (e) {
            toast('删除失败：' + e.message);
        }
    }

    /** 清空自己的全部会话（同样两步确认，见按钮上的 armed 逻辑） */
    async function clearAllSessions() {
        if (state.sending) {
            toast('正在回答中，稍后再清空');
            return;
        }
        try {
            var deleted = await api('/api/chat/sessions', { method: 'DELETE' });
            state.currentSessionId = '';
            clearMessages();
            el.emptyState.hidden = false;
            el.messages.appendChild(el.emptyState);
            await loadSessions();
            toast(deleted > 0 ? ('已清空 ' + deleted + ' 段会话') : '没有可清空的会话');
        } catch (e) {
            toast('清空失败：' + e.message);
        }
    }

    /* ==================== 历史与渲染 ==================== */

    function clearMessages() {
        el.messages.innerHTML = '';
        el.emptyState.hidden = true;
    }

    /**
     * 首次发言时把"空状态提示"收起来，但<b>不动已有的消息</b>。
     *
     * <p>不能用 clearMessages()：那会把整屏消息清掉 —— 一条条聊下去时，
     * 每次发言都会把之前的问答全部抹掉，用户只看得见最后一条。
     * （这个 bug 正是真实浏览器里发现的：连问两次后页面上只剩一轮问答。）
     */
    function hideEmptyState() {
        el.emptyState.hidden = true;
        if (el.emptyState.parentNode) {
            el.emptyState.parentNode.removeChild(el.emptyState);
        }
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
                // 回答按 Markdown 渲染（模型经常输出加粗与列表）；内部已做全量转义
                card.body.innerHTML = renderMarkdown(message.content);
                renderCitations(card, message.citations);
                renderMeta(card, {
                    agentName: message.agentName,
                    confidence: message.confidence
                });
                // 历史里带着已有反馈，按钮要回显当时的选择（否则刷新后看起来像没反馈过）
                renderActions(card, message.id, message.feedback);
                renderCopyAction(card);
                // 引用来源的标题也顺手记下来，便于"复制"时一并带走（正文已含来源列表）
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

    /**
     * 空状态：可点击的示例问题。
     *
     * <p>把例子做成"点一下就直接问"而不是纯文字提示：第一次用的人不用想"我该问什么"，
     * 也顺手示范了这台客服能答哪几类问题（订单 / 物流 / 知识 / 商品）。
     */
    function renderEmptyStateExamples() {
        var examples = el.emptyState.querySelector('.examples');
        if (!examples || examples.dataset.ready === '1') { return; }
        examples.dataset.ready = '1';
        ['MC202409240001 这单到哪了', '七天无理由退货怎么操作', '耳机多少钱', '你们支持开发票吗']
            .forEach(function (question) {
                var button = document.createElement('button');
                button.type = 'button';
                button.className = 'example-q';
                button.textContent = question;
                button.addEventListener('click', function () { sendText(question); });
                examples.appendChild(button);
            });
    }

    /**
     * 回答卡片底部的一排小工具：复制正文。
     *
     * <p>客服回答里常有订单号、金额、政策期限，用户要拿去用 —— 让他手抄是最不人性的做法。
     * 复制失败如实提示，不假装成功。
     *
     * @param card 回答卡片节点
     */
    function renderCopyAction(card) {
        if (card.tools.querySelector('.copy-btn')) { return; }
        var button = document.createElement('button');
        button.type = 'button';
        button.className = 'icon-btn copy-btn';
        button.textContent = '⧉';
        button.title = '复制这段回答';
        button.addEventListener('click', function () {
            copyText(card.body.textContent)
                .then(function () { toast('回答已复制'); })
                .catch(function () { toast('复制失败，可手动选中文字复制'); });
        });
        card.tools.appendChild(button);
        card.tools.hidden = false;
    }

    /**
     * 渲染"重试"：本轮失败时把同一条问题再发一次。
     *
     * <p>失败后要用户重新打一遍字，是最容易被骂的那种设计 —— 问题原文就在手上，直接重发。
     *
     * @param card 本轮的回答卡片
     * @param text 用户原始提问
     */
    function renderRetryAction(card, text) {
        var button = document.createElement('button');
        button.type = 'button';
        button.className = 'icon-btn retry-btn';
        button.textContent = '↻';
        button.title = '重新发送这条问题';
        button.addEventListener('click', function () {
            button.disabled = true;
            sendText(text);
        });
        card.tools.appendChild(button);
        card.tools.hidden = false;
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

        var tools = document.createElement('div');
        tools.className = 'answer-actions';
        tools.hidden = true;

        card.appendChild(progress);
        card.appendChild(body);
        card.appendChild(sources);
        card.appendChild(meta);
        card.appendChild(tools);
        row.appendChild(card);
        el.messages.appendChild(row);
        scrollToBottom();

        return { row: row, card: card, progress: progress, body: body, sources: sources,
                 meta: meta, actions: tools, tools: tools };
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
        // 意图显示中文名（done 事件里是枚举名）：用户不该需要知道 KNOWLEDGE_QA 是什么。
        // 映射表缺项时显示原文而不是隐藏 —— 少一条信息好过静默吞掉
        if (info.intent) {
            var intent = document.createElement('span');
            intent.textContent = INTENT_LABELS[info.intent] || info.intent;
            card.meta.appendChild(intent);
        }
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

    /**
     * 渲染点赞/点踩。
     *
     * @param card      回答卡片的节点集合
     * @param messageId 该条回答落库后的消息ID；为空表示没落库，此时不渲染按钮 ——
     *                  点了也只会收到"消息不存在"，不如不给
     * @param current   已有反馈（"up"/"down"），来自历史接口，用于回显
     */
    function renderActions(card, messageId, current) {
        card.actions.innerHTML = '';
        if (!messageId) {
            card.actions.hidden = true;
            return;
        }

        var buttons = {};
        [['up', '👍', '这条回答有用'], ['down', '👎', '这条回答没用']].forEach(function (item) {
            var vote = item[0];
            var button = document.createElement('button');
            button.type = 'button';
            button.className = 'icon-btn' + (current === vote ? ' voted' : '');
            button.textContent = item[1];
            button.title = item[2] + (current === vote ? '（已反馈，可改）' : '');
            button.addEventListener('click', function () { submitFeedback(card, messageId, vote, buttons); });
            buttons[vote] = button;
            card.actions.appendChild(button);
        });
        card.actions.hidden = false;
    }

    /**
     * 提交反馈。
     *
     * <p>成功就更新高亮（可改主意，服务端以后写覆盖先写）；
     * 失败则如实提示错误、<b>不改动高亮</b> —— 界面上显示"已反馈"而服务端没存下来，
     * 是比报错更糟的结果（用户以为自己的意见被记录了）。
     */
    async function submitFeedback(card, messageId, vote, buttons) {
        try {
            var saved = await api('/api/chat/message/' + encodeURIComponent(messageId) + '/feedback', {
                method: 'POST',
                body: { vote: vote }
            });
            Object.keys(buttons).forEach(function (key) {
                buttons[key].className = 'icon-btn' + (key === saved ? ' voted' : '');
                buttons[key].title = (key === 'up' ? '这条回答有用' : '这条回答没用')
                    + (key === saved ? '（已反馈，可改）' : '');
            });
            toast(saved === 'down' ? '已记录：这条回答没帮上忙' : '已记录：这条回答有用');
        } catch (e) {
            toast('反馈提交失败：' + e.message);
        }
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
        if (!text) { return; }
        // 先清空输入框：发送失败时也有"重试"按钮兜底，不必把文字留在框里占地方
        el.input.value = '';
        el.input.style.height = 'auto';
        await sendText(text);
    }

    /**
     * 发送一条消息并渲染流式回答。
     *
     * <p>与 {@link send} 分开，是因为"重试"与"点示例问题"都要绕过输入框直接发送，
     * 而它们与手动发送走的是同一条路径（同一套渲染与错误处理）。
     *
     * @param text 用户消息文本
     */
    async function sendText(text) {
        if (state.sending || !text) { return; }
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
        // 只收起空状态提示，不清屏：连续对话必须看得见前面的问答
        hideEmptyState();
        appendUserBubble(text);

        var card = appendAnswerCard();
        card.progress.hidden = false;
        card.progress.textContent = '正在处理';

        var succeeded = false;
        try {
            await streamReply(sessionId, text, card);
            succeeded = true;
        } catch (e) {
            card.progress.hidden = true;
            card.body.textContent = '本轮处理失败：' + e.message;
            // 失败给"重试"而不是让用户把问题重打一遍；原文就在手上
            renderRetryAction(card, text);
        } finally {
            state.sending = false;
            el.send.disabled = false;
            if (succeeded) {
                // 答出来了才给点赞/点踩：对一条没答出来的回复收集"满意度"没有意义
                renderActions(card, card.messageId, null);
                renderCopyAction(card);
            }
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
                // 必须同时声明两种：成功是事件流、失败是统一 JSON。
                // 只写 text/event-stream 的话，参数校验失败时服务端写不出 JSON 错误体
                // （内容协商失败），客户端只拿到一个空的 HTTP 400，看不到原因
                'Accept': 'text/event-stream, application/json',
                'Authorization': 'Bearer ' + state.token
            },
            body: JSON.stringify({ sessionId: sessionId, message: text })
        });

        if (resp.status === 401) {
            signOut('登录已过期，请重新登录');
            throw new Error('登录已过期');
        }
        // 服务端并非总会回 SSE：参数校验失败（消息过长、会话ID非法等）走的是统一 JSON 结构。
        // 一律优先采用响应体里的 message —— 只报"HTTP 400"等于把"哪里不合法"丢掉，
        // 用户只能猜；连响应体都没有时才退回状态码
        if ((resp.headers.get('Content-Type') || '').indexOf('text/event-stream') < 0) {
            var rawBody = await resp.text();
            var parsedError = rawBody ? safeParse(rawBody) : null;
            if (parsedError && parsedError.message) {
                throw new Error(parsedError.message);
            }
            throw new Error('服务返回了非预期的响应（HTTP ' + resp.status + '）');
        }
        if (!resp.ok) {
            throw new Error('HTTP ' + resp.status);
        }

        var reader = resp.body.getReader();
        var decoder = new TextDecoder('utf-8');
        var buffer = '';
        var finalText = '';
        var gotFragment = false;
        var accumulated = '';
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
                    // 流式期间维护"累积原文"并整段重渲染：片段可能把一个 ** 切成两半，
                    // 追加渲染会让半截标记永远留在屏幕上；整段重渲则闭合后自然恢复。
                    // 渲染内部已全量转义，此处 innerHTML 是安全的
                    accumulated += (payload.data || '');
                    card.body.innerHTML = renderMarkdown(accumulated);
                    autoScroll();
                } else if (event.name === 'done') {
                    // 权威结果：整体替换（流式过程中可能推过半句话，且可能已被降级覆盖）
                    finalText = (payload.data && payload.data.content) || finalText;
                    card.body.innerHTML = renderMarkdown(finalText);
                    renderCitations(card, payload.data && payload.data.citations);
                    renderMeta(card, payload.data || {});
                    // 记下落库消息ID：点赞/点踩要指出"给哪条消息反馈"。
                    // 服务端落库失败时它是空的，那种情况下不渲染反馈按钮
                    card.messageId = payload.data && payload.data.messageId;
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
    el.input.addEventListener('input', updateCharCount);

    /**
     * 字数计数：服务端对单条消息有 2000 字上限，超限会被整条拒绝。
     * 与其让用户发出去才收到报错，不如在接近上限时就看见数字、超限时直接禁发。
     * 平时不显示计数（多数消息远够不着上限，常驻数字只是噪声）。
     */
    function updateCharCount() {
        var length = el.input.value.length;
        var over = length > 2000;
        var near = length > 1800;
        el.charCount.hidden = !near && !over;
        el.charCount.textContent = over ? (length + ' / 2000（超限，删减后再发）') : (length + ' / 2000');
        el.charCount.className = 'char-count' + (over ? ' over' : (near ? ' near' : ''));
        el.send.disabled = state.sending || over;
    }

    // 移动端：侧边栏默认隐藏（见 CSS），用顶栏按钮唤起；点主区任意处收回
    el.menuToggle.addEventListener('click', function (event) {
        event.stopPropagation();
        document.body.classList.toggle('sidebar-open');
    });
    el.messages.addEventListener('click', function () {
        document.body.classList.remove('sidebar-open');
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

/**
 * 对话前端渲染器的最小单元测试（node render.test.js 直接运行，无测试框架）。
 *
 * 为什么存在：本项目的测试全是 Java 侧的，前端此前完全靠浏览器人工验证 ——
 * 而"回答正文怎么被渲染"恰恰是最容易藏安全问题的地方（innerHTML + 不可信文本）。
 * 这里把 app.js 里的 escapeHtml / renderMarkdown 原样取出、在 node 里直接执行，
 * 用断言钉住两条底线：
 *   1. 任何 HTML 都不能以标签形式存活（XSS 防线）；
 *   2. 白名单格式（加粗/列表/行内代码）确实被渲染出来。
 *
 * 取函数的方式是"按标记切片 + eval"：app.js 是一个 IIFE，函数不对外导出；
 * 为测试而导出它们（改产品代码迁就测试）不如让测试来适配产品代码。
 * 切片标记是稳定的注释锚点，app.js 重构时若挪动了函数，这里会立刻失败提醒。
 */
const fs = require('fs');
const path = require('path');

const source = fs.readFileSync(
    path.join(__dirname, '..', '..', 'src', 'main', 'resources', 'static', 'assets', 'app.js'),
    'utf8');

// 从 app.js 里切出 escapeHtml 与 renderMarkdown（含两者之间的全部内容）
const start = source.indexOf('function escapeHtml');
const end = source.indexOf('var INTENT_LABELS');
if (start < 0 || end < 0 || end <= start) {
    console.error('未能从 app.js 定位渲染函数（锚点变了？）');
    process.exit(2);
}
// eslint-disable-next-line no-eval
eval(source.slice(start, end));

let failures = 0;
function check(name, condition, detail) {
    if (condition) {
        console.log('  [OK] ' + name);
    } else {
        failures++;
        console.error('  [失败] ' + name + (detail ? '\n        ' + detail : ''));
    }
}

console.log('== XSS：原文里的 HTML 一律按文字显示 ==');
const script = renderMarkdown('<script>alert(1)</script>');
check('<script> 被转义', script.includes('&lt;script&gt;') && !script.includes('<script>'), script);
const img = renderMarkdown('<img src=x onerror=alert(1)>');
check('<img onerror> 被转义', !img.includes('<img'), img);
const attr = renderMarkdown('**x** "<b>&</b>"');
check('引号与 & 被转义', attr.includes('&quot;') && attr.includes('&amp;'), attr);
// 加粗语法与 HTML 混排：只有加粗成为标签
const mixed = renderMarkdown('**安全** <b>不安全</b>');
check('混合内容只放行加粗', mixed.includes('<strong>安全</strong>') && !mixed.includes('<b>不安全</b>'), mixed);

console.log('== 白名单格式 ==');
check('加粗', renderMarkdown('**重要**') === '<p><strong>重要</strong></p>', renderMarkdown('**重要**'));
check('行内代码', renderMarkdown('单号 `MC123`') === '<p>单号 <code>MC123</code></p>', renderMarkdown('单号 `MC123`'));
const ul = renderMarkdown('- 第一条\n- 第二条');
check('无序列表', ul === '<ul><li>第一条</li><li>第二条</li></ul>', ul);
const ol = renderMarkdown('1. 第一条\n2、第二条');
check('有序列表（. 与 、都认）', ol === '<ol><li>第一条</li><li>第二条</li></ol>', ol);
const mixed_list = renderMarkdown('前言\n\n- 甲\n- 乙\n\n结尾');
check('列表与段落分界', mixed_list === '<p>前言</p><ul><li>甲</li><li>乙</li></ul><p>结尾</p>', mixed_list);

console.log('== 流式过程中的未闭合标记 ==');
check('单个 ** 按字面显示', renderMarkdown('**加粗') === '<p>**加粗</p>', renderMarkdown('**加粗'));
check('空文本 -> 空输出', renderMarkdown('') === '');
check('多行分段', renderMarkdown('第一段\n\n第二段') === '<p>第一段</p><p>第二段</p>', renderMarkdown('第一段\n\n第二段'));

if (failures) {
    console.error('\n' + failures + ' 项失败');
    process.exit(1);
}
console.log('\n全部通过');

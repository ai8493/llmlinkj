// 客户端配置页面 JS
// 复用现有 app.js 的 csrfToken() 和 toast() 函数（app.js 全局加载）
// 但本页面单独引入 app-clients.js，不引入 app.js（避免 backend/protocol 逻辑干扰）
// 所以这里重新定义 csrfToken 和 toast

function csrfToken() {
    var meta = document.querySelector('meta[name="_csrf"]');
    return meta ? meta.getAttribute('content') : '';
}

function toast(msg, type) {
    type = type || 'success';
    var container = document.createElement('div');
    container.className = 'toast-container';
    container.innerHTML = '<div class="toast show align-items-center text-bg-' + type +
        ' border-0" role="alert"><div class="d-flex"><div class="toast-body">' + msg +
        '</div><button type="button" class="btn-close me-2 m-auto" onclick="this.closest(\'.toast-container\').remove()"></button></div></div>';
    document.body.appendChild(container);
    setTimeout(function() { container.remove(); }, 3000);
}

// 未登录时 Spring Security 会 302 重定向到 /admin/login，fetch 默认跟随重定向，
// 调用方拿到的 resp 已是登录页 HTML。检测 resp.redirected 后跳转登录页，
// 避免把 HTML 当作文件内容塞进编辑器或误判保存成功
async function authFetch(url, opts) {
    var resp = await fetch(url, opts || {});
    if (resp.redirected) {
        window.location.href = '/admin/login';
        return null;
    }
    return resp;
}

// 通用确认弹窗 (替代原生 confirm),返回 Promise<boolean>
var confirmResolve = null;
function showConfirm(message) {
    return new Promise(function(resolve) {
        confirmResolve = resolve;
        document.getElementById('confirm-modal-message').textContent = message;
        document.getElementById('confirm-modal').classList.add('show');
    });
}
function confirmModalOk() {
    document.getElementById('confirm-modal').classList.remove('show');
    if (confirmResolve) { confirmResolve(true); confirmResolve = null; }
}
function confirmModalCancel() {
    document.getElementById('confirm-modal').classList.remove('show');
    if (confirmResolve) { confirmResolve(false); confirmResolve = null; }
}

var currentClient = null;
var currentFile = null;
var originalContent = '';
var currentFileExists = true;
var currentFileFilled = false;
var clientsCache = [];

var cmEditor = null;

document.addEventListener('DOMContentLoaded', function() {
    loadClients();
    document.getElementById('save-btn').addEventListener('click', save);
    document.getElementById('update-btn').addEventListener('click', updateProxyDefaults);
    // CodeMirror 接管 textarea。mode 在 selectFile 时按文件 language 切换
    cmEditor = CodeMirror.fromTextArea(document.getElementById('editor'), {
        lineNumbers: true,
        lineWrapping: false,
        matchBrackets: true,
        styleActiveLine: true,
        theme: 'nord',
        mode: 'toml'
    });
    cmEditor.on('change', markDirty);
});

async function loadClients() {
    var resp = await authFetch('/admin/clients/api/clients', {
        headers: { 'X-XSRF-TOKEN': csrfToken() }
    });
    if (!resp) return;
    clientsCache = await resp.json();
    renderClientList();
    if (clientsCache.length > 0) {
        selectClient(clientsCache[0].id);
    }
}

function renderClientList() {
    var ul = document.getElementById('client-list');
    ul.innerHTML = '';
    clientsCache.forEach(function(c) {
        var li = document.createElement('li');
        li.className = 'list-group-item list-group-item-action';
        li.style.cursor = 'pointer';
        li.textContent = c.displayName;
        li.dataset.id = c.id;
        li.onclick = function() { selectClient(c.id); };
        ul.appendChild(li);
    });
}

async function selectClient(clientId) {
    if (isDirty() && !await showConfirm('当前有未保存修改，是否丢弃？')) {
        return;
    }
    currentClient = clientId;
    // 高亮选中项
    document.querySelectorAll('#client-list li').forEach(function(li) {
        li.classList.toggle('active', li.dataset.id === clientId);
    });
    // 渲染 Tab
    var client = clientsCache.find(function(c) { return c.id === clientId; });
    renderTabs(client.files);
    if (client.files.length > 0) {
        selectFile(client.files[0].filename);
    }
}

function renderTabs(files) {
    var ul = document.getElementById('file-tabs');
    ul.innerHTML = '';
    files.forEach(function(f, idx) {
        var li = document.createElement('li');
        li.className = 'nav-item';
        var btn = document.createElement('button');
        btn.className = 'nav-link' + (idx === 0 ? ' active' : '');
        btn.textContent = f.filename;
        btn.dataset.filename = f.filename;
        btn.dataset.language = f.language;
        btn.onclick = function() { selectFile(f.filename); };
        li.appendChild(btn);
        ul.appendChild(li);
    });
}

async function selectFile(filename) {
    if (isDirty() && !await showConfirm('当前有未保存修改，是否丢弃？')) {
        return;
    }
    currentFile = filename;
    updateFilePath();
    // 高亮当前 Tab
    document.querySelectorAll('#file-tabs button').forEach(function(btn) {
        btn.classList.toggle('active', btn.dataset.filename === filename);
    });
    // 加载文件内容
    var resp = await authFetch('/admin/clients/api/clients/' + encodeURIComponent(currentClient) + '/files/' + encodeURIComponent(filename), {
        headers: { 'X-XSRF-TOKEN': csrfToken() }
    });
    if (!resp) return;
    // X-File-Exists 响应头标识磁盘是否存在该文件,缺失时按 true 处理保持原 dirty 逻辑
    currentFileExists = resp.headers.get('X-File-Exists') !== 'false';
    currentFileFilled = resp.headers.get('X-Filled') === 'true';
    var content = await resp.text();
    originalContent = normalizeLineEndings(content);
    // 切换 mode 后再 setValue，避免 json 内容被 toml mode 解析闪烁
    cmEditor.setOption('mode', cmMode(currentFileLanguage()));
    cmEditor.setValue(content);
    cmEditor.clearHistory();
    clearDirty();
}

// 把当前 client + filename 对应的绝对路径填到 #file-path
// textContent 防注入，title 让鼠标悬停时显示完整路径（text-truncate 截断时尤其有用）
function updateFilePath() {
    var client = clientsCache.find(function(c) { return c.id === currentClient; });
    var file = client && client.files.find(function(f) { return f.filename === currentFile; });
    var path = file ? file.absolutePath : '';
    var pathEl = document.getElementById('file-path');
    pathEl.textContent = path;
    pathEl.title = path;
}

// 文件 language（来自后端 ClientFile.language）→ CodeMirror mode 名
// ini 用 properties mode（键值对语法最接近，CodeMirror 5 无独立 ini mode）
function cmMode(language) {
    if (language === 'toml') return 'toml';
    if (language === 'json') return { name: 'javascript', json: true };
    if (language === 'ini') return 'properties';
    return 'toml';
}

function currentFileLanguage() {
    var activeBtn = document.querySelector('#file-tabs button.active');
    return activeBtn ? activeBtn.dataset.language : 'toml';
}

function markDirty() {
    // 文件不存在 OR readFile 补缺过 → 按钮恒可点(让用户能保存补缺后的内容)
    // 文件已存在且未补缺 → 仅内容变化才可点
    var dirty = !currentFileExists || currentFileFilled || (cmEditor.getValue() !== originalContent);
    document.getElementById('save-btn').disabled = !dirty;
    document.getElementById('dirty-flag').textContent = dirty ? '未保存' : '';
}

function isDirty() {
    return cmEditor.getValue() !== originalContent;
}

function clearDirty() {
    markDirty();
}

// Jackson INDENT_OUTPUT 在 Windows 输出 CRLF, CodeMirror setValue 会规范化为 \n,
// originalContent 须同步规范化, 否则 getValue() !== originalContent 误判 dirty
function normalizeLineEndings(s) {
    return s.replace(/\r\n/g, '\n');
}

async function save() {
    var content = cmEditor.getValue();
    var resp = await authFetch('/admin/clients/api/clients/' + encodeURIComponent(currentClient) + '/files/' + encodeURIComponent(currentFile), {
        method: 'PUT',
        headers: {
            'X-XSRF-TOKEN': csrfToken(),
            'Content-Type': 'text/plain'
        },
        body: content
    });
    if (!resp) return;
    if (resp.ok) {
        var savedContent = await resp.text();
        originalContent = normalizeLineEndings(savedContent);
        currentFileExists = true;
        currentFileFilled = false;
        cmEditor.setValue(savedContent);
        clearDirty();
        toast('已保存');
    } else {
        toast('保存失败: ' + await resp.text(), 'danger');
    }
}

// 用本代理默认值覆盖当前文件的代理相关字段（base_url/api_key/model），其他字段保留
async function updateProxyDefaults() {
    if (!currentClient || !currentFile) return;
    if (!await showConfirm('将用本代理的默认值覆盖当前文件的代理相关字段（base_url/api_key/model），其他字段保留。是否继续？')) return;
    var resp = await authFetch('/admin/clients/api/clients/' + encodeURIComponent(currentClient)
        + '/files/' + encodeURIComponent(currentFile) + '/apply-defaults', {
        method: 'POST',
        headers: { 'X-XSRF-TOKEN': csrfToken() }
    });
    if (!resp) return;
    if (resp.ok) {
        var newContent = await resp.text();
        originalContent = normalizeLineEndings(newContent);
        // apply-defaults 内部写盘,文件必已存在
        currentFileExists = true;
        currentFileFilled = false;
        cmEditor.setValue(newContent);
        clearDirty();
        var updated = resp.headers.get('X-Updated') !== 'false';
        toast(updated ? '已更新' : '当前文件无代理字段');
    } else {
        toast('更新失败: ' + await resp.text(), 'danger');
    }
}

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

async function apiCall(url, method, body) {
    var opts = { method: method, headers: { 'X-XSRF-TOKEN': csrfToken() } };
    if (body) {
        opts.headers['Content-Type'] = 'application/json';
        opts.body = JSON.stringify(body);
    }
    var resp = await fetch(url, opts);
    if (!resp.ok) {
        var msg = await resp.text();
        throw new Error(msg || resp.statusText);
    }
    // POST/DELETE 等无返回体时跳过 json 解析，避免空 body 解析报错
    if (resp.status === 204) return null;
    var text = await resp.text();
    if (!text) return null;
    return JSON.parse(text);
}

// 协议 code 与显示名映射，列表页与下拉框保持一致
var PROTOCOL_LABELS = {
    openai: 'Openai Chat',
    anthropic: 'Claude',
    responses: 'Openai Responses',
    gemini: 'Gemini'
};
function protocolLabel(code) {
    return PROTOCOL_LABELS[code] || code || '';
}

// 填充协议下拉框（统一来源，避免 HTML 硬编码 option 与列表显示名脱节）
function populateProtocolSelect(id) {
    var sel = document.getElementById(id);
    if (!sel) return;
    sel.innerHTML = '';
    Object.keys(PROTOCOL_LABELS).forEach(function(code) {
        var opt = document.createElement('option');
        opt.value = code;
        opt.textContent = PROTOCOL_LABELS[code];
        sel.appendChild(opt);
    });
}

// 填充协议搜索下拉框（保留"全部"option，与 populateProtocolSelect 清空行为区分）
function populateProtocolSearchSelect(id) {
    var sel = document.getElementById(id);
    if (!sel) return;
    var current = sel.value;
    sel.innerHTML = '<option value="">全部</option>';
    Object.keys(PROTOCOL_LABELS).forEach(function(code) {
        var opt = document.createElement('option');
        opt.value = code;
        opt.textContent = PROTOCOL_LABELS[code];
        sel.appendChild(opt);
    });
    sel.value = current || '';
}

// ===== 协议映射 =====
var protocolPage = { content: [], totalElements: 0, totalPages: 0, number: 0, size: 10 };
var protocolSearchClient = '';
var protocolPageSize = 10;

function onProtocolSearchChange(value) {
    protocolSearchClient = value;
    loadProtocols(0);
}

function onProtocolPageSizeChange(value) {
    protocolPageSize = parseInt(value) || 10;
    loadProtocols(0);
}

function loadProtocols(page) {
    if (page < 0) page = 0;
    var url = '/admin/api/protocols?page=' + page + '&size=' + protocolPageSize;
    if (protocolSearchClient) url += '&clientProtocol=' + encodeURIComponent(protocolSearchClient);
    fetch(url, { headers: { 'X-XSRF-TOKEN': csrfToken() } })
        .then(function(r) { return r.json(); })
        .then(function(p) {
            // 当前页空（如删除最后一页唯一条后），回退到上一页
            if (p.content.length === 0 && p.number > 0) {
                loadProtocols(p.number - 1);
                return;
            }
            protocolPage = p;
            renderProtocolTable();
            renderProtocolPagination();
        });
}

function renderProtocolTable() {
    var tbody = document.getElementById('protocol-tbody');
    if (!tbody) return;
    if (protocolPage.content.length === 0) {
        tbody.innerHTML = '<tr><td colspan="4" class="text-center text-muted py-3">无数据</td></tr>';
        return;
    }
    var html = '';
    protocolPage.content.forEach(function(m) {
        var client = escapeHtml(m.clientProtocol);
        var clientLabel = escapeHtml(protocolLabel(m.clientProtocol));
        var backend = escapeHtml(m.backendCfgName);
        var enabled = m.enabled;
        html += '<tr>'
            + '<td>' + clientLabel + '</td>'
            + '<td>' + backend + '</td>'
            + '<td><div class="btn-group btn-group-sm">'
            + '<button type="button" class="btn btn-sm ' + (enabled ? 'btn-primary' : 'btn-outline-secondary') + '" data-client="' + client + '" data-backend="' + backend + '" data-enabled="true" onclick="toggleProtocol(this)">启用</button>'
            + '<button type="button" class="btn btn-sm ' + (enabled ? 'btn-outline-secondary' : 'btn-primary') + '" data-client="' + client + '" data-backend="' + backend + '" data-enabled="false" onclick="toggleProtocol(this)">禁用</button>'
            + '</div></td>'
            + '<td>'
            + '<button class="btn btn-sm btn-outline-primary" data-client="' + client + '" data-backend="' + backend + '" onclick="editRow(this)">编辑</button> '
            + '<button class="btn btn-sm btn-outline-danger" data-client="' + client + '" data-backend="' + backend + '" onclick="deleteRow(this)">删除</button>'
            + '</td>'
            + '</tr>';
    });
    tbody.innerHTML = html;
}

function renderProtocolPagination() {
    var infoEl = document.getElementById('protocol-page-info');
    var pagEl = document.getElementById('protocol-pagination');
    if (infoEl) {
        infoEl.textContent = protocolPage.totalElements === 0 ? '' : '共 ' + protocolPage.totalElements + ' 条';
    }
    if (!pagEl) return;
    if (protocolPage.totalElements === 0) {
        pagEl.innerHTML = '';
        return;
    }
    var cur = protocolPage.number;
    var total = protocolPage.totalPages;
    var html = '';
    html += '<li class="page-item ' + (cur === 0 ? 'disabled' : '') + '">'
        + '<a class="page-link" href="#" onclick="loadProtocols(' + (cur - 1) + '); return false;">&lsaquo; 上一页</a></li>';
    pageRange(cur, total).forEach(function(p) {
        if (p === -1) {
            html += '<li class="page-item disabled"><span class="page-link">…</span></li>';
        } else {
            html += '<li class="page-item ' + (p === cur ? 'active' : '') + '">'
                + '<a class="page-link" href="#" onclick="loadProtocols(' + p + '); return false;">' + (p + 1) + '</a></li>';
        }
    });
    html += '<li class="page-item ' + (cur >= total - 1 ? 'disabled' : '') + '">'
        + '<a class="page-link" href="#" onclick="loadProtocols(' + (cur + 1) + '); return false;">下一页 &rsaquo;</a></li>';
    pagEl.innerHTML = html;
}

// 大模型配置名称下拉框用 Tom Select 实例支持模糊搜索
var backendSelect = null;
var editingEnabled = true;
var protocolModelMappings = [];
// 编辑模式下保存当前协议映射主键，供添加/删除模型映射即时调后端用；新增模式下为 null
var editingClient = null;
var editingBackend = null;
function initBackendSelect() {
    var sel = document.getElementById('backendCfgName');
    // 仅协议映射页面有该元素；Tom Select 库未加载时跳过避免报错
    if (!sel || typeof TomSelect === 'undefined') return;
    if (backendSelect) return;
    backendSelect = new TomSelect(sel, {
        valueField: 'value',
        labelField: 'text',
        searchField: ['text'],
        placeholder: '请选择',
        allowEmptyOption: true,
        create: false,
        maxItems: 1
    });
}
initBackendSelect();

function openProtocolModal(isEdit) {
    document.getElementById('protocol-modal-title').textContent = isEdit ? '编辑协议映射' : '新增协议映射';
    document.getElementById('protocol-modal').classList.add('show');
}

function closeProtocolModal() {
    document.getElementById('protocol-modal').classList.remove('show');
}

function renderModelMappingTable() {
    var tbody = document.getElementById('model-mapping-tbody');
    if (!tbody) return;
    if (protocolModelMappings.length === 0) {
        tbody.innerHTML = '<tr><td colspan="3" class="text-center text-muted py-2">无数据</td></tr>';
        return;
    }
    var html = '';
    protocolModelMappings.forEach(function(m, idx) {
        html += '<tr>'
            + '<td>' + escapeHtml(m.requestModel) + '</td>'
            + '<td>' + escapeHtml(m.actualModel) + '</td>'
            + '<td><button type="button" class="btn btn-sm btn-outline-danger" onclick="removeModelMappingRow(' + idx + ')">删除</button></td>'
            + '</tr>';
    });
    tbody.innerHTML = html;
}

function addModelMappingRow() {
    var req = document.getElementById('mm-requestModel').value.trim();
    var act = document.getElementById('mm-actualModel').value.trim();
    if (!req || !act) {
        toast('请求模型名和实际模型名都必填', 'danger');
        return;
    }
    // 同一协议映射下 request_model 唯一
    for (var i = 0; i < protocolModelMappings.length; i++) {
        if (protocolModelMappings[i].requestModel === req) {
            toast('请求模型名 ' + req + ' 已存在', 'danger');
            return;
        }
    }
    // 编辑模式：即时落库；新增模式：仅前端数组，随"保存"整体提交
    if (editingClient) {
        apiCall('/admin/api/protocols/' + encodeURIComponent(editingClient) + '/' + encodeURIComponent(editingBackend) + '/model-mappings', 'POST', { requestModel: req, actualModel: act })
            .then(function() {
                protocolModelMappings.push({ requestModel: req, actualModel: act });
                document.getElementById('mm-requestModel').value = '';
                document.getElementById('mm-actualModel').value = '';
                renderModelMappingTable();
            })
            .catch(function(e) { toast(e.message, 'danger'); });
        return;
    }
    protocolModelMappings.push({ requestModel: req, actualModel: act });
    document.getElementById('mm-requestModel').value = '';
    document.getElementById('mm-actualModel').value = '';
    renderModelMappingTable();
}

function removeModelMappingRow(idx) {
    var m = protocolModelMappings[idx];
    // 编辑模式：即时落库；新增模式：仅前端数组
    if (editingClient) {
        apiCall('/admin/api/protocols/' + encodeURIComponent(editingClient) + '/' + encodeURIComponent(editingBackend) + '/model-mappings/' + encodeURIComponent(m.requestModel), 'DELETE')
            .then(function() {
                protocolModelMappings.splice(idx, 1);
                renderModelMappingTable();
            })
            .catch(function(e) { toast(e.message, 'danger'); });
        return;
    }
    protocolModelMappings.splice(idx, 1);
    renderModelMappingTable();
}

async function addProtocol() {
    document.getElementById('protocol-form').reset();
    // select 无 readOnly 属性，改用 disabled 控制可编辑性；新增时启用
    document.getElementById('clientProtocol').disabled = false;
    // Tom Select 实例的 enable/clear 等价于原 disabled=false + form.reset 清空
    if (backendSelect) {
        backendSelect.enable();
        backendSelect.clear();
    }
    document.getElementById('clientProtocol').value = 'openai';
    editingEnabled = true;
    editingClient = null;
    editingBackend = null;
    protocolModelMappings = [];
    renderModelMappingTable();
    await loadBackendNames();
    openProtocolModal(false);
}

async function editRow(btn) {
    // 原值从按钮 dataset 取，避免依赖单元格文本（第一列文本会被转码替换为中文显示名）
    var origClient = btn.dataset.client;
    var origBackend = btn.dataset.backend;
    editingClient = origClient;
    editingBackend = origBackend;
    // 并行：拉后端名下拉 + 拉协议映射详情（含模型映射）
    var p1 = loadBackendNames();
    var p2 = fetch('/admin/api/protocols/' + encodeURIComponent(origClient) + '/' + encodeURIComponent(origBackend), { headers: { 'X-XSRF-TOKEN': csrfToken() } })
        .then(function(r) { return r.json(); });
    var results = await Promise.all([p1, p2]);
    var detail = results[1];
    protocolModelMappings = (detail.modelMappings || []).map(function(m) {
        return { requestModel: m.requestModel, actualModel: m.actualModel };
    });
    renderModelMappingTable();
    var selClient = document.getElementById('clientProtocol');
    // 移除上次 editRow 追加的"已失效"option，避免累积（clientProtocol 的 option 是硬编码，不会被 loadBackendNames 清空）
    selClient.querySelectorAll('option').forEach(function(o) {
        if (o.textContent.endsWith(' (已失效)')) o.remove();
    });
    selClient.value = origClient;
    if (selClient.value !== origClient) {
        // 原值不在下拉选项中（历史脏数据），加入"已失效"option 防止主键被静默改写
        var opt = document.createElement('option');
        opt.value = origClient;
        opt.textContent = origClient + ' (已失效)';
        selClient.appendChild(opt);
        selClient.value = origClient;
    }
    if (backendSelect) {
        // loadBackendNames 已 clearOptions，原值若不在选项中说明后端已被删除，追加"已失效"option 防止主键被静默改写
        // getOption 第二参 e=true 强制渲染并返回 DOM，否则未点开过下拉时 $div 还没渲染会被误判为"不存在"
        if (!backendSelect.getOption(origBackend, true)) {
            backendSelect.addOption({ value: origBackend, text: origBackend + ' (已失效)' });
        }
        // silent=true 不触发 change 事件，避免与表单其他逻辑耦合
        backendSelect.setValue(origBackend, true);
        // 编辑时主键不可改（保留原 disabled=true 语义）。Tom Select 的 disable 不影响 getValue/.value 取值。
        backendSelect.disable();
    }
    var row = btn.closest('tr');
    var enableBtn = row.cells[2].querySelector('button[data-enabled="true"]');
    editingEnabled = enableBtn.classList.contains('btn-primary');
    document.getElementById('clientProtocol').disabled = true;
    openProtocolModal(true);
}

var pendingDelete = null;
function deleteRow(btn) {
    pendingDelete = { client: btn.dataset.client, backend: btn.dataset.backend };
    document.getElementById('protocol-delete-tip').textContent = '确认删除 ' + pendingDelete.client + ' → ' + pendingDelete.backend + '？';
    document.getElementById('protocol-delete-modal').classList.add('show');
}
function closeProtocolDeleteModal() {
    pendingDelete = null;
    document.getElementById('protocol-delete-modal').classList.remove('show');
}
var protocolDeleteConfirmBtn = document.getElementById('protocol-delete-confirm');
if (protocolDeleteConfirmBtn) {
    protocolDeleteConfirmBtn.addEventListener('click', function() {
        if (!pendingDelete) return;
        var client = pendingDelete.client, backend = pendingDelete.backend;
        closeProtocolDeleteModal();
        apiCall('/admin/api/protocols/' + encodeURIComponent(client) + '/' + encodeURIComponent(backend), 'DELETE')
            .then(function() { toast('已删除'); loadProtocols(protocolPage.number); })
            .catch(function(e) { toast(e.message, 'danger'); });
    });
}

function loadBackendNames() {
    return fetch('/admin/api/backends?page=0&size=1000', { headers: { 'X-XSRF-TOKEN': csrfToken() } })
        .then(function(r) { return r.json(); })
        .then(function(p) {
            if (!backendSelect) return;
            backendSelect.clear();
            backendSelect.clearOptions();
            // 空选项作为 placeholder 触发"请选择"提示（配合 initBackendSelect 中的 placeholder 配置）
            backendSelect.addOption({ value: '', text: '请选择' });
            p.content.forEach(function(b) {
                backendSelect.addOption({ value: b.name, text: b.name });
            });
        })
        .catch(function(e) { toast('加载后端列表失败: ' + e.message, 'danger'); });
}

var protocolForm = document.getElementById('protocol-form');
if (protocolForm) {
    protocolForm.addEventListener('submit', function(e) {
        e.preventDefault();
        var body = {
            clientProtocol: document.getElementById('clientProtocol').value,
            backendCfgName: document.getElementById('backendCfgName').value,
            enabled: editingEnabled,
            modelMappings: protocolModelMappings,
            updatedAt: null
        };
        apiCall('/admin/api/protocols', 'POST', body)
            .then(function() { closeProtocolModal(); toast('已保存，缓存已刷新'); loadProtocols(protocolPage.number); })
            .catch(function(e) { toast(e.message, 'danger'); });
    });
}

function toggleProtocol(btn) {
    // 当前态按钮已含 btn-primary，点击它无意义，直接返回
    if (btn.classList.contains('btn-primary')) return;
    var client = btn.dataset.client;
    var backend = btn.dataset.backend;
    // data-enabled 是字符串 "true"/"false"，Boolean("false") 为 true，必须显式比较
    var targetEnabled = btn.dataset.enabled === 'true';
    var group = btn.parentElement;
    var buttons = group.querySelectorAll('button');
    // 乐观更新：被点的变 btn-primary，另一个变 btn-outline-secondary
    buttons.forEach(function(b) {
        b.classList.remove('btn-primary', 'btn-outline-secondary');
        b.classList.add(b === btn ? 'btn-primary' : 'btn-outline-secondary');
    });
    apiCall('/admin/api/protocols', 'POST', {
        clientProtocol: client,
        backendCfgName: backend,
        enabled: targetEnabled,
        updatedAt: null
    }).then(function() {
        if (targetEnabled) {
            promptUpdateClientConfig(client);
        } else {
            toast('已保存，缓存已刷新');
        }
        loadProtocols(protocolPage.number);
    }).catch(function(e) {
        // 失败回滚：恢复原高亮（targetEnabled=true 时启用按钮高亮，否则禁用按钮高亮）
        buttons.forEach(function(b) {
            var bEnabled = b.dataset.enabled === 'true';
            b.classList.remove('btn-primary', 'btn-outline-secondary');
            b.classList.add(bEnabled === !targetEnabled ? 'btn-primary' : 'btn-outline-secondary');
        });
        toast(e.message, 'danger');
    });
}

// 协议映射启用后联动：弹确认对话框，确认则更新对应客户端的所有配置文件
async function promptUpdateClientConfig(clientProtocol) {
    var clientName = mapProtocolToClientName(clientProtocol);
    if (!clientName) return;
    if (!await showConfirm('已启用该协议映射。是否同步更新 ' + clientName + ' 的配置文件（base_url/api_key/model）？')) return;
    updateAllClientFiles(clientName).catch(function(e) {
        toast('更新失败: ' + e.message, 'danger');
    });
}

// 协议→客户端 ID 硬编码映射。新增客户端时需同步更新此函数
function mapProtocolToClientName(protocol) {
    if (protocol === 'responses') return 'codex';
    if (protocol === 'gemini') return 'gemini-cli';
    return null;
}

// 检测 fetch 是否被重定向到登录页（SESSION 过期）。重定向则跳登录页返回 false，否则 true
function authCheck(resp) {
    if (resp.redirected) {
        window.location.href = '/admin/login';
        return false;
    }
    return true;
}

async function updateAllClientFiles(clientId) {
    var resp = await fetch('/admin/clients/api/clients', {
        headers: { 'X-XSRF-TOKEN': csrfToken() }
    });
    if (!authCheck(resp)) return;
    var clients = await resp.json();
    var client = clients.find(function(c) { return c.id === clientId; });
    if (!client) {
        toast('未找到客户端: ' + clientId, 'danger');
        return;
    }
    var failures = [];
    for (var i = 0; i < client.files.length; i++) {
        var f = client.files[i];
        var r = await fetch('/admin/clients/api/clients/' + encodeURIComponent(clientId)
            + '/files/' + encodeURIComponent(f.filename) + '/apply-defaults', {
            method: 'POST',
            headers: { 'X-XSRF-TOKEN': csrfToken() }
        });
        if (!authCheck(r)) return;
        if (!r.ok) failures.push(f.filename);
    }
    if (failures.length === 0) {
        toast(clientId + ' 配置已更新');
    } else {
        toast('部分文件更新失败: ' + failures.join(', '), 'danger');
    }
}

// ===== 大模型配置 =====
var backendPage = { content: [], totalElements: 0, totalPages: 0, number: 0, size: 10 };
var backendSearchName = '';
var backendPageSize = 10;
var backendSearchTimer = null;
var backendCache = {};

function onBackendSearchInput(value) {
    backendSearchName = value.trim();
    clearTimeout(backendSearchTimer);
    backendSearchTimer = setTimeout(function() { loadBackends(0); }, 300);
}

function onBackendPageSizeChange(value) {
    backendPageSize = parseInt(value) || 10;
    loadBackends(0);
}

function loadBackends(page) {
    if (page < 0) page = 0;
    var url = '/admin/api/backends?page=' + page + '&size=' + backendPageSize;
    if (backendSearchName) url += '&name=' + encodeURIComponent(backendSearchName);
    fetch(url, { headers: { 'X-XSRF-TOKEN': csrfToken() } })
        .then(function(r) { return r.json(); })
        .then(function(p) {
            // 当前页空（如删除最后一页唯一条后），回退到上一页
            if (p.content.length === 0 && p.number > 0) {
                loadBackends(p.number - 1);
                return;
            }
            backendPage = p;
            backendCache = {};
            p.content.forEach(function(b) { backendCache[b.name] = b; });
            renderBackendTable();
            renderBackendPagination();
        });
}

function renderBackendTable() {
    var tbody = document.getElementById('backend-tbody');
    if (!tbody) return;
    if (backendPage.content.length === 0) {
        tbody.innerHTML = '<tr><td colspan="5" class="text-center text-muted py-3">无数据</td></tr>';
        return;
    }
    var html = '';
    backendPage.content.forEach(function(b) {
        html += '<tr>'
            + '<td>' + escapeHtml(b.name) + '</td>'
            + '<td>' + escapeHtml(protocolLabel(b.protocol)) + '</td>'
            + '<td>' + escapeHtml(b.baseUrl || '') + '</td>'
            + '<td>' + escapeHtml(b.defaultModel || '') + '</td>'
            + '<td>'
            + '<button class="btn btn-sm btn-outline-primary" data-name="' + escapeHtml(b.name) + '" onclick="editBackendRow(this)">编辑</button> '
            + '<button class="btn btn-sm btn-outline-danger" data-name="' + escapeHtml(b.name) + '" onclick="deleteBackendRow(this)">删除</button>'
            + '</td>'
            + '</tr>';
    });
    tbody.innerHTML = html;
}

function renderBackendPagination() {
    var infoEl = document.getElementById('backend-page-info');
    var pagEl = document.getElementById('backend-pagination');
    if (infoEl) {
        infoEl.textContent = backendPage.totalElements === 0 ? '' : '共 ' + backendPage.totalElements + ' 条';
    }
    if (!pagEl) return;
    if (backendPage.totalElements === 0) {
        pagEl.innerHTML = '';
        return;
    }
    var cur = backendPage.number;
    var total = backendPage.totalPages;
    var html = '';
    html += '<li class="page-item ' + (cur === 0 ? 'disabled' : '') + '">'
        + '<a class="page-link" href="#" onclick="loadBackends(' + (cur - 1) + '); return false;">&lsaquo; 上一页</a></li>';
    pageRange(cur, total).forEach(function(p) {
        if (p === -1) {
            html += '<li class="page-item disabled"><span class="page-link">…</span></li>';
        } else {
            html += '<li class="page-item ' + (p === cur ? 'active' : '') + '">'
                + '<a class="page-link" href="#" onclick="loadBackends(' + p + '); return false;">' + (p + 1) + '</a></li>';
        }
    });
    html += '<li class="page-item ' + (cur >= total - 1 ? 'disabled' : '') + '">'
        + '<a class="page-link" href="#" onclick="loadBackends(' + (cur + 1) + '); return false;">下一页 &rsaquo;</a></li>';
    pagEl.innerHTML = html;
}

// 生成页码数组，-1 表示省略号
function pageRange(cur, total) {
    var pages = [];
    if (total <= 7) {
        for (var i = 0; i < total; i++) pages.push(i);
        return pages;
    }
    pages.push(0);
    if (cur > 2) pages.push(-1);
    var start = Math.max(1, cur - 1);
    var end = Math.min(total - 2, cur + 1);
    for (var j = start; j <= end; j++) pages.push(j);
    if (cur < total - 3) pages.push(-1);
    pages.push(total - 1);
    return pages;
}

function escapeHtml(s) {
    if (s == null) return '';
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

function openBackendModal(isEdit) {
    document.getElementById('modal-title').textContent = isEdit ? '编辑大模型' : '新增大模型';
    // 每次打开弹窗强制还原 api-key 为掩码状态，避免上次切到明文后被带入
    var apiKeyInput = document.getElementById('apiKey');
    apiKeyInput.type = 'password';
    apiKeyInput.parentElement.querySelector('button').innerHTML = '<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" fill="currentColor" viewBox="0 0 16 16"><path d="M16 8s-3-5.5-8-5.5S0 8 0 8s3 5.5 8 5.5S16 8 16 8zM8 12a4 4 0 1 1 0-8 4 4 0 0 1 0 8zm0-1.5a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5z"/></svg>';
    // 每次打开弹窗强制收起"更多"区域，避免上次展开状态被带入
    var advancedToggle = document.querySelector('.advanced-toggle');
    if (advancedToggle) {
        advancedToggle.classList.remove('expanded');
        var advancedSection = advancedToggle.nextElementSibling;
        if (advancedSection) advancedSection.classList.remove('show');
        var toggleText = advancedToggle.querySelector('.toggle-text');
        if (toggleText) toggleText.textContent = '更多';
    }
    document.getElementById('backend-modal').classList.add('show');
}

function closeBackendModal() {
    document.getElementById('backend-modal').classList.remove('show');
}

function toggleAdvanced(el) {
    var expanded = el.classList.toggle('expanded');
    el.nextElementSibling.classList.toggle('show', expanded);
    var textEl = el.querySelector('.toggle-text');
    if (textEl) {
        textEl.textContent = expanded ? '收起' : '更多';
    }
}

function toggleApiKeyVisible(btn) {
    var input = document.getElementById('apiKey');
    var showing = input.type === 'text';
    input.type = showing ? 'password' : 'text';
    btn.innerHTML = showing
        ? '<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" fill="currentColor" viewBox="0 0 16 16"><path d="M16 8s-3-5.5-8-5.5S0 8 0 8s3 5.5 8 5.5S16 8 16 8zM8 12a4 4 0 1 1 0-8 4 4 0 0 1 0 8zm0-1.5a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5z"/></svg>'
        : '<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" fill="currentColor" viewBox="0 0 16 16"><path d="M16 8s-3-5.5-8-5.5S0 8 0 8s3 5.5 8 5.5S16 8 16 8zM8 12a4 4 0 1 1 0-8 4 4 0 0 1 0 8zm0-1.5a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5z"/><path d="M2.146 2.854a.5.5 0 1 1 .708-.708l11 11a.5.5 0 0 1-.708.708l-2.339-2.339A8.36 8.36 0 0 1 8 13.5C1.355 13.5.029 8 0 8c.029 0 1.875-.5 4.293-.707L2.146 2.854z"/></svg>';
}

function addBackend() {
    document.getElementById('backend-form').reset();
    document.getElementById('name').readOnly = false;
    openBackendModal(false);
}

function editBackendRow(btn) {
    var b = backendCache[btn.dataset.name];
    if (!b) return;
    document.getElementById('name').value = b.name;
    document.getElementById('name').readOnly = true;
    document.getElementById('protocol').value = b.protocol;
    document.getElementById('defaultModel').value = b.defaultModel;
    document.getElementById('baseUrl').value = b.baseUrl;
    document.getElementById('apiKey').value = b.apiKey || '';
    document.getElementById('defaultMaxTokens').value = b.defaultMaxTokens || '';
    document.getElementById('connectTimeout').value = b.connectTimeout;
    document.getElementById('readTimeout').value = b.readTimeout;
    document.getElementById('writeTimeout').value = b.writeTimeout;
    document.getElementById('maxIdleConnections').value = b.maxIdleConnections;
    document.getElementById('keepAliveDuration').value = b.keepAliveDuration;
    openBackendModal(true);
}

function deleteBackendRow(btn) {
    pendingBackendDelete = btn.dataset.name;
    document.getElementById('backend-delete-tip').textContent = '确认删除大模型 ' + pendingBackendDelete + '？';
    document.getElementById('backend-delete-modal').classList.add('show');
}
function closeBackendDeleteModal() {
    pendingBackendDelete = null;
    document.getElementById('backend-delete-modal').classList.remove('show');
}
var pendingBackendDelete = null;
var backendDeleteConfirmBtn = document.getElementById('backend-delete-confirm');
if (backendDeleteConfirmBtn) {
    backendDeleteConfirmBtn.addEventListener('click', function() {
        if (!pendingBackendDelete) return;
        var name = pendingBackendDelete;
        closeBackendDeleteModal();
        apiCall('/admin/api/backends/' + name, 'DELETE')
            .then(function() { toast('已删除'); loadBackends(backendPage.number); })
            .catch(function(e) { toast(e.message, 'danger'); });
    });
}

var backendForm = document.getElementById('backend-form');
if (backendForm) {
    backendForm.addEventListener('submit', function(e) {
        e.preventDefault();
        var body = {
            name: document.getElementById('name').value,
            protocol: document.getElementById('protocol').value,
            apiKey: document.getElementById('apiKey').value,
            baseUrl: document.getElementById('baseUrl').value,
            defaultModel: document.getElementById('defaultModel').value,
            defaultMaxTokens: parseInt(document.getElementById('defaultMaxTokens').value) || null,
            connectTimeout: parseInt(document.getElementById('connectTimeout').value) || 10,
            readTimeout: parseInt(document.getElementById('readTimeout').value) || 600,
            writeTimeout: parseInt(document.getElementById('writeTimeout').value) || 30,
            maxIdleConnections: parseInt(document.getElementById('maxIdleConnections').value) || 20,
            keepAliveDuration: parseInt(document.getElementById('keepAliveDuration').value) || 300,
            updatedAt: null
        };
        apiCall('/admin/api/backends', 'POST', body)
            .then(function() { closeBackendModal(); toast('已保存'); loadBackends(backendPage.number); })
            .catch(function(e) { toast(e.message, 'danger'); });
    });
}

// 页面加载时初始化大模型列表
document.addEventListener('DOMContentLoaded', function() {
    if (document.getElementById('backend-tbody')) {
        loadBackends(0);
    }
    if (document.getElementById('protocol-tbody')) {
        loadProtocols(0);
    }
    // 填充三个协议下拉框（弹窗内的硬编码 option 已移除，统一从 PROTOCOL_LABELS 取）
    populateProtocolSelect('protocol');
    populateProtocolSelect('clientProtocol');
    populateProtocolSearchSelect('protocol-search');
});

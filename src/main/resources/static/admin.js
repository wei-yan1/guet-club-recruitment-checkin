const token = localStorage.getItem('adminToken');
if (!token) window.location.replace('/');

const state = { students: [], filtered: [], editing: null, selected: new Set(), sort: { field: null, dir: 'asc' } };
const $ = (selector) => document.querySelector(selector);
const table = $('#studentTable');
const totalCount = $('#totalCount');
const summary = $('#tableSummary');
const searchInput = $('#searchInput');
const clearSearch = $('#clearSearch');
const modalBackdrop = $('#modalBackdrop');
const studentForm = $('#studentForm');
const formStatus = $('#formStatus');
const selectAll = $('#selectAll');
const selectionActions = $('#selectionActions');
const selectionSummary = $('#selectionSummary');
const batchDeleteButton = $('#batchDeleteButton');
const logoutButton = $('#logoutButton');
const syncScoresButton = $('#syncScoresButton');
const ojConfigButton = $('#ojConfigButton');
const ojConfigBackdrop = $('#ojConfigBackdrop');
const ojConfigForm = $('#ojConfigForm');
const qrBackdrop = $('#qrBackdrop');
const autoMatchInput = $('#autoMatch');
let matchTimer = null;
let matchRequestId = 0;
let pendingSyncResults = null;
let pendingSyncStudentId = null;

function apiHeaders() {
  return { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token };
}

function toast(message, error) {
  const el = $('#toast');
  el.textContent = message;
  el.className = error ? 'toast show error' : 'toast show';
  window.setTimeout(() => el.classList.remove('show'), 2800);
}

function formatDate(value) {
  if (!value) return '—';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).replaceAll('/', '.');
}

function escapeHtml(value) {
  return String(value ?? '').replace(/[&<>"']/g, (char) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#039;'
  }[char]));
}

function scoreCell(score, emptyText) {
  return score === null || score === undefined || score === ''
    ? '<span class="no-score">' + emptyText + '</span>'
    : '<span class="score">' + Number(score).toFixed(2) + '</span>';
}

function accountText(account) {
  return account
    ? '<code class="oj-account">' + escapeHtml(account) + '</code>'
    : '<span class="no-score">未绑定</span>';
}

function syncSelectionUi() {
  const selectedCount = state.selected.size;
  selectionActions.hidden = selectedCount === 0;
  selectionSummary.textContent = '已选择 ' + selectedCount + ' 名学生';
  const visibleIds = state.filtered.map((student) => String(student.id));
  selectAll.checked = visibleIds.length > 0 && visibleIds.every((id) => state.selected.has(id));
  selectAll.indeterminate = visibleIds.some((id) => state.selected.has(id)) && !selectAll.checked;
}

function renderRows() {
  if (!state.filtered.length) {
    table.innerHTML = '<tr><td colspan="8" class="empty-state">没有找到匹配的学生档案</td></tr>';
    summary.textContent = '共 0 条记录';
    syncSelectionUi();
    return;
  }

  table.innerHTML = state.filtered.map((student) => {
    const checked = state.selected.has(String(student.id)) ? ' checked' : '';
    return '<tr>' +
      '<td class="select-cell"><input class="row-checkbox" type="checkbox" data-select="' + student.id + '"' + checked + ' aria-label="选择学生"></td>' +
      '<td><div class="student-cell"><span class="student-avatar">' + escapeHtml((student.name || '?').slice(0, 1)) +
      '</span><div><div class="student-name">' + escapeHtml(student.name) +
      '</div></div></div></td>' +
      '<td><b>' + escapeHtml(student.studentNo) + '</b></td>' +
      '<td>' + accountText(student.ojAccount) + '</td>' +
      '<td>' + scoreCell(student.scoreAm, '未同步') + '</td>' +
      '<td>' + scoreCell(student.scorePm, '未同步') + '</td>' +
      '<td>' + formatDate(student.registerTime) + '</td>' +
      '<td><div class="row-actions"><button class="row-button" data-qr="' + student.publicId + '">二维码</button><button class="row-button" data-edit="' + student.id + '">编辑</button><button class="row-button delete" data-delete="' + student.id + '">删除</button></div></td>' +
      '</tr>';
  }).join('');
  summary.textContent = '显示 ' + state.filtered.length + ' 条记录';
  updateSortIndicators();
  syncSelectionUi();
}

function applySearch() {
  const keyword = searchInput.value.trim().toLowerCase();
  if (!keyword) {
    state.filtered = state.students.slice();
  } else {
    state.filtered = state.students.filter((student) =>
      (student.studentNo || '').toLowerCase().includes(keyword)
      || (student.name || '').toLowerCase().includes(keyword)
      || (student.ojAccount || '').toLowerCase().includes(keyword));
  }
  sortStudents();
  clearSearch.hidden = !keyword;
  renderRows();
}

/** 按当前场次分数排序：未同步（null）始终排到最末，不受升降序影响。 */
function sortStudents() {
  if (!state.sort.field) return;
  const field = state.sort.field;
  const sign = state.sort.dir === 'asc' ? 1 : -1;
  state.filtered.sort((a, b) => {
    const av = a[field], bv = b[field];
    if (av == null && bv == null) return 0;
    if (av == null) return 1;
    if (bv == null) return -1;
    return (Number(av) - Number(bv)) * sign;
  });
}

function updateSortIndicators() {
  document.querySelectorAll('th.sortable').forEach((th) => {
    const f = th.dataset.sort;
    const ind = th.querySelector('.sort-ind');
    if (state.sort.field !== f) {
      th.classList.remove('active');
      if (ind) ind.textContent = '↕';
      return;
    }
    th.classList.add('active');
    if (ind) ind.textContent = state.sort.dir === 'asc' ? '↑' : '↓';
  });
}

function setSort(field) {
  if (state.sort.field !== field) state.sort = { field, dir: 'asc' };
  else if (state.sort.dir === 'asc') state.sort = { field, dir: 'desc' };
  else state.sort = { field: null, dir: 'asc' };
  applySearch();
}

async function request(url, options) {
  const response = await fetch(url, { ...(options || {}), headers: { ...apiHeaders(), ...((options || {}).headers || {}) } });
  const result = await response.json().catch(() => ({}));
  if (response.status === 401 || result.code === 401) {
    localStorage.removeItem('adminToken');
    localStorage.removeItem('adminInfo');
    window.location.replace('/');
    throw new Error('登录已失效');
  }
  if (!response.ok || result.code !== 200) throw new Error(result.message || '请求失败');
  return result;
}

async function loadStudents() {
  table.innerHTML = '<tr><td colspan="8" class="empty-state">正在加载学生档案…</td></tr>';
  try {
    const result = await request('/api/students');
    state.students = Array.isArray(result.data) ? result.data : [];
  } catch (error) {
    state.students = [];
    toast('加载失败：' + error.message, true);
  }
  const allIds = new Set(state.students.map((student) => String(student.id)));
  state.selected.forEach((id) => { if (!allIds.has(id)) state.selected.delete(id); });
  totalCount.textContent = state.students.length;
  applySearch();
}

function openModal(student) {
  const isEditing = Boolean(student);
  state.editing = student || null;
  $('#modalEyebrow').textContent = isEditing ? 'EDIT RECORD' : 'NEW RECORD';
  $('#modalTitle').textContent = isEditing ? '编辑学生' : '新增学生';
  $('#modalSubtitle').textContent = isEditing ? '修改学生信息后保存。' : '输入学号后自动从候选名单匹配姓名和 OJ 凭据。';
  $('#autoMatchWrap').hidden = isEditing;
  autoMatchInput.checked = true;
  $('#editingId').value = isEditing ? student.id : '';
  $('#studentNo').value = isEditing ? student.studentNo : '';
  $('#ojAccount').value = isEditing && student.ojAccount ? student.ojAccount : '';
  $('#ojPassword').value = isEditing && student.ojPassword ? student.ojPassword : '';
  $('#studentName').value = isEditing ? student.name : '';
  $('#scoreAm').value = isEditing && student.scoreAm != null ? student.scoreAm : '';
  $('#scorePm').value = isEditing && student.scorePm != null ? student.scorePm : '';
  $('#matchStatus').textContent = '';
  setProfileFieldsLocked(!isEditing && autoMatchInput.checked);
  $('#syncOneButton').hidden = !isEditing;
  formStatus.textContent = '';
  document.querySelectorAll('[data-error]').forEach((el) => { el.textContent = ''; });
  modalBackdrop.hidden = false;
  (isEditing ? $('#studentName') : $('#studentNo')).focus();
}

function closeModal() { modalBackdrop.hidden = true; state.editing = null; }

function setProfileFieldsLocked(locked) {
  $('#studentName').readOnly = false;
  $('#studentName').placeholder = '请填写或修改学生姓名';
  $('#ojAccount').readOnly = true;
  $('#ojPassword').readOnly = true;
}

async function matchCandidate(studentNo) {
  const requestId = ++matchRequestId;
  const status = $('#matchStatus');
  if (!autoMatchInput.checked || studentNo.length !== 10) {
    status.textContent = '';
    return;
  }
  status.textContent = '正在匹配候选名单…';
  try {
    const result = await request('/api/students/match?studentNo=' + encodeURIComponent(studentNo));
    if (requestId !== matchRequestId || $('#studentNo').value.trim() !== studentNo) return;
    if (!$('#studentName').value.trim()) $('#studentName').value = result.data.name || '';
    $('#ojAccount').value = result.data.ojAccount || '';
    $('#ojPassword').value = result.data.ojPassword || '';
    status.textContent = result.data.ojAccount && result.data.ojPassword
      ? '已匹配：' + (result.data.name || studentNo)
      : '档案账号信息不完整，保存时将分配预留账号';
  } catch (error) {
    if (requestId !== matchRequestId) return;
    $('#ojAccount').value = '';
    $('#ojPassword').value = '';
    if (error.message.includes('No candidate profile')) {
      $('#studentName').readOnly = false;
      $('#studentName').placeholder = '候选名单未收录，请手动输入姓名';
      status.textContent = '候选名单中没有这个学号，请手动填写姓名';
    } else {
      status.textContent = '匹配失败：' + error.message;
    }
  }
}

function onStudentNoInput() {
  window.clearTimeout(matchTimer);
  matchRequestId++;
  if (!autoMatchInput.checked) return;
  const studentNo = $('#studentNo').value.trim();
  if (studentNo.length !== 10) {
    $('#studentName').value = '';
    $('#ojAccount').value = '';
    $('#ojPassword').value = '';
    $('#matchStatus').textContent = '';
    return;
  }
  matchTimer = window.setTimeout(() => matchCandidate(studentNo), 250);
}

function openQrModal(publicId) {
  const student = state.students.find((item) => item.publicId === publicId);
  if (!student) { toast('未找到该学生', true); return; }
  $('#qrTitle').textContent = student.name + ' 的二维码';
  $('#qrSubtitle').textContent = '扫码即可查询该学生的成绩';
  $('#qrId').textContent = '查询码 · ' + student.publicId;
  const url = '/api/public/scores/' + encodeURIComponent(publicId) + '/qrcode';
  $('#qrImage').src = url;
  $('#qrZoomLink').href = url;
  $('#qrDownloadLink').href = url;
  $('#qrDownloadLink').download = student.publicId + '.png';
  qrBackdrop.hidden = false;
}

function closeQrModal() { qrBackdrop.hidden = true; }

function validateForm() {
  document.querySelectorAll('[data-error]').forEach((el) => { el.textContent = ''; });
  let ok = true;
  const no = $('#studentNo').value.trim();
  const name = $('#studentName').value.trim();
  const scoreAm = $('#scoreAm').value;
  const scorePm = $('#scorePm').value;
  if (!/^(25|26)\d{8}$/.test(no)) { $('[data-error="studentNo"]').textContent = '请填写 10 位学号（25/26 级开头）'; ok = false; }
  if (!name) { $('[data-error="studentName"]').textContent = '请输入学生姓名'; ok = false; }
  if (scoreAm !== '' && (Number(scoreAm) < 0 || Number.isNaN(Number(scoreAm)))) { $('[data-error="scoreAm"]').textContent = '上午成绩不能为负数'; ok = false; }
  if (scorePm !== '' && (Number(scorePm) < 0 || Number.isNaN(Number(scorePm)))) { $('[data-error="scorePm"]').textContent = '下午成绩不能为负数'; ok = false; }
  return ok;
}

async function saveStudent(event) {
  event.preventDefault();
  if (!validateForm()) return;
  const editingId = $('#editingId').value;
  const isEditing = Boolean(editingId);
  const payload = {
    studentNo: $('#studentNo').value.trim(),
    autoMatch: !isEditing && autoMatchInput.checked,
    name: $('#studentName').value.trim(),
    scoreAm: $('#scoreAm').value === '' ? null : Number($('#scoreAm').value),
    scorePm: $('#scorePm').value === '' ? null : Number($('#scorePm').value)
  };
  $('#saveButton').disabled = true;
  formStatus.textContent = '正在保存…';
  try {
    const saved = await request(isEditing ? '/api/students/' + encodeURIComponent(editingId) : '/api/students', { method: isEditing ? 'PUT' : 'POST', body: JSON.stringify(payload) });
    if (saved.data) {
      $('#ojAccount').value = saved.data.ojAccount || '';
      $('#ojPassword').value = saved.data.ojPassword || '';
      $('#studentName').value = saved.data.name || $('#studentName').value;
    }
    toast(isEditing ? '学生档案已更新，可继续修改后再次保存。' : '学生档案已创建，可继续新增下一位学生。');
    if (!isEditing) { $('#editingId').value = ''; $('#studentNo').value = ''; $('#ojAccount').value = ''; $('#ojPassword').value = ''; $('#studentName').value = ''; $('#scoreAm').value = ''; $('#scorePm').value = ''; $('#studentNo').focus(); }
    formStatus.textContent = '保存成功，点击取消可关闭窗口。';
    await loadStudents();
  } catch (error) { formStatus.textContent = error.message; } finally { $('#saveButton').disabled = false; }
}

async function deleteStudent(id) {
  if (!window.confirm('确定删除该学生记录吗？此操作不可撤销。')) return;
  try { await request('/api/students/' + encodeURIComponent(id), { method: 'DELETE' }); toast('学生档案已删除'); await loadStudents(); } catch (error) { toast('删除失败：' + error.message, true); }
}

async function batchDeleteStudents() {
  const ids = Array.from(state.selected);
  if (!ids.length) return;
  if (!window.confirm('确定删除选中的 ' + ids.length + ' 条学生记录吗？此操作不可撤销。')) return;
  batchDeleteButton.disabled = true;
  try {
    const results = await Promise.allSettled(ids.map((id) => request('/api/students/' + encodeURIComponent(id), { method: 'DELETE' })));
    const failed = results.filter((result) => result.status === 'rejected');
    const deletedCount = ids.length - failed.length;
    state.selected.clear();
    if (failed.length) throw new Error('成功删除 ' + deletedCount + ' 条，另有 ' + failed.length + ' 条删除失败');
    toast('已删除 ' + deletedCount + ' 条记录');
    await loadStudents();
  } catch (error) { toast('批量删除失败：' + error.message, true); await loadStudents(); } finally { batchDeleteButton.disabled = false; }
}

async function syncScores() {
  syncScoresButton.disabled = true;
  try {
    const result = await request('/api/students/sync-scores', { method: 'POST' });
    const data = result.data || {};
    if (!data.amEnabled && !data.pmEnabled) { toast('未配置 OJ 比赛，无法同步', true); return; }
    pendingSyncStudentId = null;
    showSyncResult(data);
    await loadStudents();
  } catch (error) { toast('同步失败：' + error.message, true); }
  finally { syncScoresButton.disabled = false; }
}

function fmtScore(value) { return value == null ? '—' : Number(value).toFixed(2); }

function sessionCell(result, studentId, session) {
  const status = result.status;
  const label = status === 'BLOCKED_CHANGE' ? 'blocked' : (status === 'APPLIED' ? 'applied' : 'plain');
  const text = status === 'APPLIED' ? (result.storedScore == null ? '首次保存 ' : '已保存 ') + fmtScore(result.fetchedScore)
    : status === 'UNCHANGED' ? '无变化 ' + fmtScore(result.storedScore)
    : status === 'BLOCKED_CHANGE' ? '待审核 ' + fmtScore(result.storedScore) + ' → ' + fmtScore(result.fetchedScore)
    : status === 'NOT_FOUND' ? '未爬到' : '未参与';
  const checkbox = status === 'BLOCKED_CHANGE'
    ? '<label class="review-score"><input type="checkbox" data-review-score data-student="' + studentId + '" data-session="' + session + '">采用 ' + fmtScore(result.fetchedScore) + '</label>' : '';
  return '<span class="sync-cell ' + label + '">' + text + '</span>' + checkbox;
}

function showSyncResult(data) {
  pendingSyncResults = data.students || [];
  const rows = pendingSyncResults.map((item) => '<tr><td>' + escapeHtml(item.name || '') + '</td><td><code>' + escapeHtml(item.studentNo || '') + '</code></td>'
    + '<td>' + sessionCell(item.am, item.studentId, 'AM') + '</td><td>' + sessionCell(item.pm, item.studentId, 'PM') + '</td></tr>').join('');
  $('#syncResultList').innerHTML = '<table><thead><tr><th>姓名</th><th>学号</th><th>上午场</th><th>下午场</th></tr></thead><tbody>'
    + (rows || '<tr><td colspan="4" class="empty-state">没有学生</td></tr>') + '</tbody></table>';
  const blocked = data.blockedChangeCount || 0;
  $('#syncResultStatus').textContent = '上午匹配 ' + (data.amMatched || 0) + '/' + data.studentCount + '；下午匹配 ' + (data.pmMatched || 0) + '/' + data.studentCount
    + (blocked ? '；' + blocked + ' 人存在成绩变化，原值已保留。勾选要采用的新分数后应用。' : '；无待审核变动。');
  $('#syncApplyButton').disabled = blocked === 0;
  syncResultBackdrop.hidden = false;
}

async function syncOneStudent() {
  const id = $('#editingId').value; if (!id) return;
  const button = $('#syncOneButton'); button.disabled = true;
  try {
    const result = await request('/api/students/' + encodeURIComponent(id) + '/sync-score', { method: 'POST' });
    const data = result.data || {};
    if (!data.amEnabled && !data.pmEnabled) { formStatus.textContent = '未配置 OJ 比赛，无法同步'; return; }
    pendingSyncStudentId = id; showSyncResult(data); await loadStudents(); refreshEditingScores(id);
  } catch (error) { formStatus.textContent = '同步失败：' + error.message; }
  finally { button.disabled = false; }
}

function refreshEditingScores(id) {
  const student = state.students.find((row) => String(row.id) === String(id));
  if (!student) return;
  state.editing = student;
  $('#scoreAm').value = student.scoreAm == null ? '' : student.scoreAm;
  $('#scorePm').value = student.scorePm == null ? '' : student.scorePm;
}

async function applyReviewedScores() {
  const changes = Array.from(document.querySelectorAll('[data-review-score]:checked')).map((input) => {
    const student = pendingSyncResults.find((row) => String(row.studentId) === input.dataset.student);
    const session = student[input.dataset.session.toLowerCase()];
    return { studentId: Number(input.dataset.student), session: input.dataset.session, expectedScore: session.storedScore, score: session.fetchedScore };
  });
  if (!changes.length) { $('#syncResultStatus').textContent = '请先勾选要采用的变化成绩。'; return; }
  const button = $('#syncApplyButton'); button.disabled = true;
  try {
    const result = await request('/api/students/sync-scores/apply', { method: 'POST', body: JSON.stringify({ changes }) });
    $('#syncResultStatus').textContent = '已应用 ' + result.data + ' 项；未勾选项保留原成绩。';
    document.querySelectorAll('[data-review-score]').forEach((input) => { input.disabled = true; });
    button.disabled = true;
    await loadStudents();
    if (pendingSyncStudentId) refreshEditingScores(pendingSyncStudentId);
  } catch (error) { $('#syncResultStatus').textContent = '应用失败：' + error.message; }
  finally { button.disabled = false; }
}

function openOjConfig() {
  request('/api/admin/oj-config').then((result) => {
    const data = result.data || {};
    $('#ojBaseUrl').value = data.baseUrl || '';
    $('#ojContestIdAm').value = data.contestIdAm || '';
    $('#ojContestIdPm').value = data.contestIdPm || '';
    $('#ojContestPasswordAm').value = data.contestPasswordAm || '';
    $('#ojContestPasswordPm').value = data.contestPasswordPm || '';
    $('#ojSessionCookie').value = data.sessionCookie || '';
    $('#ojConfigStatus').textContent = '';
    ojConfigBackdrop.hidden = false;
  }).catch((error) => toast('加载爬虫配置失败：' + error.message, true));
}

function closeOjConfig() { ojConfigBackdrop.hidden = true; }

async function saveOjConfig(event) {
  event.preventDefault();
  document.querySelectorAll('[data-error]').forEach((el) => { el.textContent = ''; });
  const baseUrl = $('#ojBaseUrl').value.trim();
  const idAm = $('#ojContestIdAm').value.trim();
  const idPm = $('#ojContestIdPm').value.trim();
  let ok = true;
  if (baseUrl && !/^https?:\/\//.test(baseUrl)) { $('[data-error="ojBaseUrl"]').textContent = '地址需以 http:// 或 https:// 开头'; ok = false; }
  if (idAm && !/^\d+$/.test(idAm)) { $('[data-error="ojContestIdAm"]').textContent = '比赛 ID 必须是数字'; ok = false; }
  if (idPm && !/^\d+$/.test(idPm)) { $('[data-error="ojContestIdPm"]').textContent = '比赛 ID 必须是数字'; ok = false; }
  if (!ok) return;
  let sessionCookie = $('#ojSessionCookie').value.trim();
  if (sessionCookie && !sessionCookie.includes('=')) sessionCookie = 'sessionid=' + sessionCookie;
  const button = $('#ojConfigSaveButton');
  button.disabled = true;
  try {
    await request('/api/admin/oj-config', {
      method: 'PUT',
      body: JSON.stringify({
        baseUrl: baseUrl,
        contestIdAm: idAm,
        contestIdPm: idPm,
        contestPasswordAm: $('#ojContestPasswordAm').value,
        contestPasswordPm: $('#ojContestPasswordPm').value,
        sessionCookie: sessionCookie
      })
    });
    $('#ojConfigStatus').textContent = '已保存，配置立即生效。';
    toast('爬虫配置已保存');
    window.setTimeout(closeOjConfig, 600);
  } catch (error) {
    $('#ojConfigStatus').textContent = error.message;
  } finally {
    button.disabled = false;
  }
}

async function logout() {
  if (!window.confirm('确定退出当前登录吗？')) return;
  logoutButton.disabled = true;
  try { await fetch('/api/admin/logout', { method: 'POST', headers: { Authorization: 'Bearer ' + token } }); } finally { localStorage.removeItem('adminToken'); localStorage.removeItem('adminInfo'); window.location.replace('/'); }
}

table.addEventListener('click', (event) => {
  const edit = event.target.closest('[data-edit]');
  const del = event.target.closest('[data-delete]');
  const qr = event.target.closest('[data-qr]');
  if (qr) openQrModal(qr.dataset.qr);
  if (edit) openModal(state.students.find((student) => String(student.id) === edit.dataset.edit));
  if (del) deleteStudent(del.dataset.delete);
});
table.addEventListener('change', (event) => {
  const checkbox = event.target.closest('[data-select]');
  if (!checkbox) return;
  if (checkbox.checked) state.selected.add(checkbox.dataset.select); else state.selected.delete(checkbox.dataset.select);
  syncSelectionUi();
});
selectAll.addEventListener('change', () => { state.filtered.forEach((student) => { if (selectAll.checked) state.selected.add(String(student.id)); else state.selected.delete(String(student.id)); }); renderRows(); });
batchDeleteButton.addEventListener('click', batchDeleteStudents);
$('#addButton').addEventListener('click', () => openModal());
$('#studentNo').addEventListener('input', onStudentNoInput);
autoMatchInput.addEventListener('change', () => {
  setProfileFieldsLocked(autoMatchInput.checked);
  if (autoMatchInput.checked) onStudentNoInput();
  else $('#matchStatus').textContent = '自动匹配已关闭，请手动填写姓名；保存时将分配预留账号和密码。';
});
$('#refreshButton').addEventListener('click', loadStudents);
syncScoresButton.addEventListener('click', syncScores);
$('#syncOneButton').addEventListener('click', syncOneStudent);
$('#syncApplyButton').addEventListener('click', applyReviewedScores);
$('#syncCloseButton').addEventListener('click', () => { syncResultBackdrop.hidden = true; });
$('#searchButton').addEventListener('click', applySearch);
searchInput.addEventListener('keydown', (event) => { if (event.key === 'Enter') applySearch(); });
clearSearch.addEventListener('click', () => { searchInput.value = ''; applySearch(); });
$('#cancelButton').addEventListener('click', closeModal);
ojConfigButton.addEventListener('click', openOjConfig);
$('#ojConfigCancelButton').addEventListener('click', closeOjConfig);
ojConfigForm.addEventListener('submit', saveOjConfig);
$('#qrCloseButton').addEventListener('click', closeQrModal);
studentForm.addEventListener('submit', saveStudent);
logoutButton.addEventListener('click', logout);
document.querySelectorAll('th.sortable').forEach((th) => {
  th.addEventListener('click', () => setSort(th.dataset.sort));
});
const info = JSON.parse(localStorage.getItem('adminInfo') || '{}');
$('#avatar').textContent = (info.username || '管').slice(0, 1);
loadStudents();






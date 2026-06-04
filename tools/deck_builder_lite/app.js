"use strict";

const APP_VERSION = "moz-lite-0.1.0";
const DEFAULT_DELIMITERS = "。！？!?．.：:";
const DETECTABLE_DELIMITERS = "。！？!?．.：:；;｡…";
const CLOZE_RE = /\{\{c(\d+)::([^:}]+)(?:::([^}]+))?\}\}/g;

const state = {
  mode: "vocab",
  activePane: "text1",
  pendingLoadPane: "text1",
  cards: [],
  undo: [],
  findMatches: [],
  findIndex: -1,
  draggedIndex: -1,
  createdAt: new Date().toISOString()
};

const el = {
  text1: document.getElementById("text1"),
  text2: document.getElementById("text2"),
  text1Info: document.getElementById("text1-info"),
  text2Info: document.getElementById("text2-info"),
  draftTitle: document.getElementById("draft-title"),
  modeHint: document.getElementById("mode-hint"),
  vocabDraft: document.getElementById("vocab-draft"),
  clozeTools: document.getElementById("cloze-tools"),
  draftQ: document.getElementById("draft-question"),
  draftA: document.getElementById("draft-answer"),
  draftExcludes: document.getElementById("draft-excludes"),
  draftGroup: document.getElementById("draft-group"),
  delimiter: document.getElementById("delimiter-input"),
  cardsBody: document.getElementById("cards-body"),
  cardCount: document.getElementById("card-count"),
  status: document.getElementById("status-text"),
  sourceFile: document.getElementById("source-file-input"),
  workFile: document.getElementById("work-file-input"),
  findbar: document.getElementById("findbar"),
  findInput: document.getElementById("find-input"),
  findScope: document.getElementById("find-scope"),
  findCount: document.getElementById("find-count"),
  sourceLock: document.getElementById("source-lock-input")
};

function setStatus(message) {
  el.status.textContent = message;
}

function timestamp() {
  const d = new Date();
  const pad = (n) => String(n).padStart(2, "0");
  return `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}_${pad(d.getHours())}${pad(d.getMinutes())}`;
}

function cloneCards(cards = state.cards) {
  return JSON.parse(JSON.stringify(cards));
}

function pushUndo() {
  state.undo.push(JSON.stringify({
    mode: state.mode,
    cards: state.cards,
    draftQ: el.draftQ.value,
    draftA: el.draftA.value,
    draftExcludes: el.draftExcludes.value,
    draftGroup: el.draftGroup.value
  }));
  if (state.undo.length > 80) state.undo.shift();
}

function undo() {
  const snapshot = state.undo.pop();
  if (!snapshot) {
    setStatus("取り消す操作がありません");
    return;
  }
  const data = JSON.parse(snapshot);
  state.cards = Array.isArray(data.cards) ? data.cards : [];
  el.draftQ.value = data.draftQ || "";
  el.draftA.value = data.draftA || "";
  el.draftExcludes.value = data.draftExcludes || "";
  el.draftGroup.value = data.draftGroup || "";
  setMode(data.mode === "cloze" ? "cloze" : "vocab", { silent: true });
  renderCards();
  setStatus("直前の操作を取り消しました");
}

function activeTextarea() {
  return state.activePane === "text2" ? el.text2 : el.text1;
}

function selectedTextFrom(source) {
  return source.value.slice(source.selectionStart, source.selectionEnd).trim();
}

function selectedSourceText() {
  return selectedTextFrom(activeTextarea());
}

function setActivePane(paneId) {
  state.activePane = paneId === "text2" ? "text2" : "text1";
  document.querySelectorAll(".source-pane").forEach((pane) => {
    pane.classList.toggle("active", pane.dataset.pane === state.activePane);
  });
}

function setMode(mode, options = {}) {
  state.mode = mode === "cloze" ? "cloze" : "vocab";
  document.querySelectorAll("input[name='builder-mode']").forEach((input) => {
    input.checked = input.value === state.mode;
  });
  const cloze = state.mode === "cloze";
  el.vocabDraft.hidden = cloze;
  el.clozeTools.hidden = !cloze;
  el.draftTitle.textContent = cloze ? "穴埋め作成" : "単語帳draft";
  el.modeHint.textContent = cloze
    ? "D/0でカード化、カード欄で1-9を押して穴埋め化"
    : "A/Sで候補を入れてDでカード化";
  if (!options.silent) setStatus(cloze ? "穴埋めモードに切り替えました" : "単語帳モードに切り替えました");
}

function normalizeNewlines(text) {
  return String(text || "").replace(/\r\n?/g, "\n");
}

function cleanMaterialText(text) {
  return normalizeNewlines(text)
    .replace(/^\uFEFF/, "")
    .replace(/[​﻿]/g, "")
    .replace(/［＃[^］]*］/g, "")
    .replace(/｜/g, "")
    .replace(/《[^》]*》/g, "")
    .replace(/[ \t]+\n/g, "\n")
    .replace(/\n{3,}/g, "\n\n")
    .replace(/[ \t]{2,}/g, " ")
    .trim();
}

function stripHtml(html) {
  const doc = new DOMParser().parseFromString(String(html || ""), "text/html");
  doc.querySelectorAll("script,style,noscript,iframe,svg,canvas,template").forEach((node) => node.remove());
  return doc.body ? doc.body.innerText : doc.documentElement.textContent || "";
}

function uniqueChars(text) {
  return [...new Set(Array.from(text || ""))].join("");
}

function delimiterTextFor() {
  const configured = el.delimiter.value.trim();
  return uniqueChars(configured || DEFAULT_DELIMITERS);
}

function splitDisplayText(text, delimitersText = "") {
  const delimiters = [...new Set(Array.from(delimitersText || delimiterTextFor()))];
  const delimiterSet = new Set(delimiters);
  const lines = [];
  let current = "";
  for (const ch of normalizeNewlines(text)) {
    current += ch;
    if (delimiterSet.has(ch) || ch === "\n") {
      const line = current.trim();
      if (line) lines.push(line);
      current = "";
    }
  }
  const rest = current.trim();
  if (rest) lines.push(rest);
  return lines.join("\n");
}

function lineCount(text) {
  return String(text || "").split("\n").filter((line) => line.trim()).length;
}

function updatePaneInfo(paneId, message) {
  const info = paneId === "text2" ? el.text2Info : el.text1Info;
  info.textContent = message;
}

function cleanSourcePane(paneId) {
  const box = paneId === "text2" ? el.text2 : el.text1;
  if (!box.value.trim()) {
    setStatus(`${paneId === "text2" ? "テキスト2" : "テキスト1"} は空です`);
    return false;
  }
  box.value = cleanMaterialText(box.value);
  updatePaneInfo(paneId, `整理済み / ${lineCount(box.value)}行`);
  return true;
}

function cleanSources(scope) {
  if (scope === "both") {
    const done1 = cleanSourcePane("text1");
    const done2 = cleanSourcePane("text2");
    if (done1 || done2) setStatus("青空文庫記号・特殊記号を整理しました");
    return;
  }
  if (cleanSourcePane(scope)) {
    setActivePane(scope);
    setStatus(`${scope === "text2" ? "テキスト2" : "テキスト1"} を整理しました`);
  }
}

function formatSourcePane(paneId, options = {}) {
  const box = paneId === "text2" ? el.text2 : el.text1;
  if (!box.value.trim()) {
    setStatus(`${paneId === "text2" ? "テキスト2" : "テキスト1"} は空です`);
    return false;
  }
  const delimiters = options.delimiters || delimiterTextFor();
  box.value = splitDisplayText(cleanMaterialText(box.value), delimiters);
  updatePaneInfo(paneId, `整形済み / ${lineCount(box.value)}行 / 区切り: ${delimiters}`);
  return true;
}

function formatSources(scope) {
  if (scope === "both") {
    const delimiters = delimiterTextFor();
    const done1 = formatSourcePane("text1", { delimiters });
    const done2 = formatSourcePane("text2", { delimiters });
    if (done1 || done2) setStatus(`テキストを文ごとに整形しました（区切り: ${delimiters}）`);
    return;
  }
  if (formatSourcePane(scope)) {
    setActivePane(scope);
    setStatus(`${scope === "text2" ? "テキスト2" : "テキスト1"} を文ごとに整形しました`);
  }
}

function sourceTextForScope(scope) {
  if (scope === "text1") return el.text1.value;
  if (scope === "text2") return el.text2.value;
  return `${el.text1.value}\n${el.text2.value}`;
}

function detectDelimiterCandidates() {
  const source = sourceTextForScope(state.activePane);
  const detected = uniqueChars(Array.from(DETECTABLE_DELIMITERS).filter((char) => source.includes(char)).join(""));
  if (!detected) {
    setStatus("本文から区切り文字候補を見つけられませんでした");
    return;
  }
  el.delimiter.value = detected;
  setStatus(`区切り文字候補を設定しました: ${detected}`);
}

function resetDelimiters() {
  el.delimiter.value = DEFAULT_DELIMITERS;
  setStatus("区切り文字を初期値に戻しました");
}

async function decodeFile(file) {
  const buffer = await file.arrayBuffer();
  const bytes = new Uint8Array(buffer);
  if (bytes[0] === 0xef && bytes[1] === 0xbb && bytes[2] === 0xbf) {
    return new TextDecoder("utf-8").decode(bytes.slice(3));
  }
  try {
    return new TextDecoder("utf-8", { fatal: true }).decode(bytes);
  } catch {
    try {
      return new TextDecoder("shift_jis").decode(bytes);
    } catch {
      return new TextDecoder("utf-8").decode(bytes);
    }
  }
}

async function loadSourceFile(file, paneId) {
  let text = await decodeFile(file);
  if (/\.html?$/i.test(file.name)) text = stripHtml(text);
  text = cleanMaterialText(text);
  const box = paneId === "text2" ? el.text2 : el.text1;
  box.value = text;
  updatePaneInfo(paneId, `${file.name} / ${lineCount(text)}行`);
  setActivePane(paneId);
  setStatus(`${file.name} を読み込みました`);
}

function applySourceLock() {
  const locked = el.sourceLock.checked;
  el.text1.readOnly = locked;
  el.text2.readOnly = locked;
  setStatus(locked ? "本文編集ロック中: 選択してショートカットを使えます" : "本文編集ロック解除中: 本文を直接編集できます");
}

function appendSelectionToDraft(source, target) {
  const selected = selectedTextFrom(source);
  if (!selected) {
    setStatus("本文でテキストを選択してください");
    return false;
  }
  pushUndo();
  const box = target === "q" ? el.draftQ : el.draftA;
  box.value = box.value.trim() ? `${box.value.trim()} ${selected}` : selected;
  setStatus(target === "q" ? "問題文draftへ追加しました" : "解答文draftへ追加しました");
  return true;
}

function clearDraft() {
  if (!el.draftQ.value && !el.draftA.value && !el.draftExcludes.value && !el.draftGroup.value) return;
  pushUndo();
  el.draftQ.value = "";
  el.draftA.value = "";
  el.draftExcludes.value = "";
  el.draftGroup.value = "";
  setStatus("draftをクリアしました");
}

function metadataFromDraft() {
  return {
    choiceExcludeWords: el.draftExcludes.value.trim(),
    choiceGroup: el.draftGroup.value.trim()
  };
}

function cardHasCloze(card) {
  return /\{\{c\d+::[^:}]+(?:::[^}]+)?\}\}/.test(card?.q || "");
}

function addVocabCardFromDraft() {
  const q = el.draftQ.value.trim();
  const a = el.draftA.value.trim();
  if (!q || !a) {
    alert("単語帳カードには問題文draftと解答文draftの両方が必要です。");
    return;
  }
  pushUndo();
  state.cards.push({
    kind: "vocab",
    q,
    a,
    ...metadataFromDraft()
  });
  el.draftQ.value = "";
  el.draftA.value = "";
  renderCards();
  setStatus("単語帳カードを追加しました");
}

function addClozeCard(text, options = {}) {
  const q = cleanMaterialText(text);
  if (!q) {
    setStatus("Clozeカードにする本文がありません");
    return false;
  }
  if (!options.skipUndo) pushUndo();
  state.cards.push({
    kind: "cloze",
    q,
    a: "",
    ...metadataFromDraft()
  });
  renderCards();
  setStatus(cardHasCloze(state.cards[state.cards.length - 1]) ? "Clozeカードを追加しました" : "Cloze候補カードを追加しました。カード内で隠す箇所を選んで1-9を押してください");
  return true;
}

function addSelectedClozeCard() {
  return addClozeCard(selectedSourceText());
}

function linesFromText(text) {
  return String(text || "").split("\n").map((line) => line.trim()).filter(Boolean);
}

function bulkAddClozeFromText(text) {
  const lines = linesFromText(text);
  if (!lines.length) {
    setStatus("追加できる行がありません");
    return;
  }
  pushUndo();
  lines.forEach((line) => addClozeCard(line, { skipUndo: true }));
  renderCards();
  setStatus(`${lines.length}件のCloze候補カードを追加しました`);
}

function bulkAddActiveCloze() {
  bulkAddClozeFromText(activeTextarea().value);
}

function bulkAddSelectedCloze() {
  const selected = selectedSourceText();
  if (!selected) {
    setStatus("本文で範囲を選択してください");
    return;
  }
  bulkAddClozeFromText(splitDisplayText(selected, delimiterTextFor()));
}

function addCardFromDraft() {
  if (state.mode === "cloze") {
    addSelectedClozeCard();
  } else {
    addVocabCardFromDraft();
  }
}

function addEmptyCard() {
  pushUndo();
  state.cards.push({
    kind: state.mode,
    q: "",
    a: state.mode === "vocab" ? "" : "",
    choiceExcludeWords: "",
    choiceGroup: ""
  });
  renderCards();
  setStatus("空行を追加しました");
}

function deleteCard(index) {
  if (!state.cards[index]) return;
  if (!confirm(`${index + 1}番のカードを削除しますか？`)) return;
  pushUndo();
  state.cards.splice(index, 1);
  renderCards();
  setStatus("カードを削除しました");
}

function duplicateCard(index) {
  if (!state.cards[index]) return;
  pushUndo();
  state.cards.splice(index + 1, 0, JSON.parse(JSON.stringify(state.cards[index])));
  renderCards();
  setStatus("カードを複製しました");
}

function reorderCard(from, to) {
  if (from < 0 || to < 0 || from === to || !state.cards[from]) return;
  pushUndo();
  const [card] = state.cards.splice(from, 1);
  state.cards.splice(to, 0, card);
  renderCards();
  setStatus("カードの順番を変更しました");
}

function updateCard(index, key, value) {
  const card = state.cards[index];
  if (!card) return;
  card[key] = value;
  if (key === "kind" && value === "cloze") card.a = "";
}

function clearCards() {
  if (!state.cards.length) return;
  if (!confirm("作成済みカード一覧をすべて削除しますか？")) return;
  pushUndo();
  state.cards = [];
  renderCards();
  setStatus("カード一覧をクリアしました");
}

function wrapSelectionAsCloze(area, number) {
  const start = area.selectionStart;
  const end = area.selectionEnd;
  const selected = area.value.slice(start, end);
  if (!selected.trim()) {
    setStatus("カードの問題欄で穴埋めにする箇所を選択してください");
    return false;
  }
  pushUndo();
  const wrapped = `{{c${number}::${selected}}}`;
  area.value = `${area.value.slice(0, start)}${wrapped}${area.value.slice(end)}`;
  area.dispatchEvent(new Event("input", { bubbles: true }));
  area.focus();
  area.setSelectionRange(start, start + wrapped.length);
  setStatus(`選択箇所を c${number} の穴埋めにしました`);
  return true;
}

function renderCards() {
  el.cardsBody.innerHTML = "";
  el.cardCount.textContent = `${state.cards.length}件`;
  if (!state.cards.length) {
    const row = document.createElement("tr");
    const cell = document.createElement("td");
    cell.colSpan = 7;
    cell.className = "empty";
    cell.textContent = "まだカードがありません";
    row.appendChild(cell);
    el.cardsBody.appendChild(row);
    return;
  }

  state.cards.forEach((card, index) => {
    const row = document.createElement("tr");
    row.draggable = true;
    row.className = card.kind === "cloze" ? "kind-cloze" : "kind-vocab";
    if (card.kind === "cloze" && !cardHasCloze(card)) row.classList.add("invalid");
    row.addEventListener("dragstart", () => {
      state.draggedIndex = index;
      row.classList.add("dragging");
    });
    row.addEventListener("dragend", () => row.classList.remove("dragging"));
    row.addEventListener("dragover", (event) => event.preventDefault());
    row.addEventListener("drop", (event) => {
      event.preventDefault();
      reorderCard(state.draggedIndex, index);
    });

    row.appendChild(td(String(index + 1)));
    row.appendChild(kindCell(index, card));
    row.appendChild(textareaCell(card.q, (value) => updateCard(index, "q", value), { clozeHotkeys: card.kind === "cloze", index }));
    row.appendChild(answerCell(index, card));
    row.appendChild(textareaCell(card.choiceExcludeWords || "", (value) => updateCard(index, "choiceExcludeWords", value), { small: true }));
    row.appendChild(inputCell(card.choiceGroup || "", (value) => updateCard(index, "choiceGroup", value)));
    row.appendChild(actionsCell(index));
    el.cardsBody.appendChild(row);
  });
}

function td(text) {
  const cell = document.createElement("td");
  cell.textContent = text;
  return cell;
}

function kindCell(index, card) {
  const cell = document.createElement("td");
  const select = document.createElement("select");
  select.innerHTML = '<option value="vocab">単語帳</option><option value="cloze">穴埋め</option>';
  select.value = card.kind === "cloze" ? "cloze" : "vocab";
  select.addEventListener("change", () => {
    pushUndo();
    updateCard(index, "kind", select.value);
    renderCards();
  });
  const badge = document.createElement("span");
  badge.className = "kind-badge";
  badge.textContent = select.value === "cloze" ? "穴埋め" : "単語帳";
  cell.append(select, badge);
  return cell;
}

function textareaCell(value, onInput, options = {}) {
  const cell = document.createElement("td");
  const area = document.createElement("textarea");
  area.value = value || "";
  if (options.small) area.rows = 2;
  area.addEventListener("focus", () => {
    if (!area.dataset.undoFocus) {
      pushUndo();
      area.dataset.undoFocus = "1";
    }
  });
  area.addEventListener("blur", () => {
    delete area.dataset.undoFocus;
  });
  area.addEventListener("input", () => onInput(area.value));
  if (options.clozeHotkeys) {
    area.addEventListener("keydown", (event) => {
      const digit = digitFromEvent(event);
      if (!digit || digit === "0") return;
      if (area.selectionStart === area.selectionEnd) return;
      event.preventDefault();
      wrapSelectionAsCloze(area, digit);
    });
  }
  cell.appendChild(area);
  return cell;
}

function answerCell(index, card) {
  if (card.kind !== "cloze") {
    return textareaCell(card.a || "", (value) => updateCard(index, "a", value));
  }
  const cell = document.createElement("td");
  const box = document.createElement("div");
  box.className = "auto-answer";
  box.textContent = cardHasCloze(card)
    ? "Cloze内の答えをMOZが自動抽出します"
    : "未指定: 問題欄で隠す箇所を選び、1-9を押してください";
  cell.appendChild(box);
  return cell;
}

function inputCell(value, onInput) {
  const cell = document.createElement("td");
  const input = document.createElement("input");
  input.type = "text";
  input.value = value || "";
  input.addEventListener("focus", () => pushUndo());
  input.addEventListener("input", () => onInput(input.value));
  cell.appendChild(input);
  return cell;
}

function actionsCell(index) {
  const cell = document.createElement("td");
  const wrap = document.createElement("div");
  wrap.className = "row-actions";
  const duplicate = document.createElement("button");
  duplicate.type = "button";
  duplicate.textContent = "複製";
  duplicate.addEventListener("click", () => duplicateCard(index));
  const del = document.createElement("button");
  del.type = "button";
  del.className = "danger";
  del.textContent = "削除";
  del.addEventListener("click", () => deleteCard(index));
  wrap.append(duplicate, del);
  cell.appendChild(wrap);
  return cell;
}

function normalizeMetadata(value) {
  return String(value || "").replace(/\s+/g, " ").trim();
}

function sanitizeTsvCell(value) {
  return String(value || "").replace(/\r\n?/g, "\n").replace(/\t/g, " ").trim();
}

function csvCell(value) {
  const text = String(value || "").replace(/\r\n?/g, "\n");
  return `"${text.replace(/"/g, '""')}"`;
}

function validExportCards() {
  const skipped = [];
  const rows = [];
  state.cards.forEach((card, index) => {
    const kind = card.kind === "cloze" ? "cloze" : "vocab";
    const q = String(card.q || "").trim();
    const a = kind === "cloze" ? "" : String(card.a || "").trim();
    if (!q || (kind === "vocab" && !a) || (kind === "cloze" && !cardHasCloze(card))) {
      skipped.push(index + 1);
      return;
    }
    rows.push({
      q,
      a,
      choiceExcludeWords: normalizeMetadata(card.choiceExcludeWords),
      choiceGroup: normalizeMetadata(card.choiceGroup)
    });
  });
  return { rows, skipped };
}

function exportTsv() {
  const { rows, skipped } = validExportCards();
  if (!rows.length) {
    alert("出力できるカードがありません。単語帳は問題と解答、穴埋めはCloze指定が必要です。");
    return;
  }
  if (skipped.length && !confirm(`未完成カード ${skipped.join(", ")} 番をスキップして出力しますか？`)) return;
  const table = [
    ["question", "answer", "choice_exclude_words", "choice_group"],
    ...rows.map((card) => [card.q, card.a, card.choiceExcludeWords, card.choiceGroup])
  ];
  const text = "\ufeff" + table.map((row) => row.map(sanitizeTsvCell).join("\t")).join("\n") + "\n";
  downloadBlob(new Blob([text], { type: "text/tab-separated-values;charset=utf-8" }), `moz_deck_${timestamp()}.tsv`);
  setStatus(`TSVを出力しました: ${rows.length}件`);
}

function exportCsv() {
  const { rows, skipped } = validExportCards();
  if (!rows.length) {
    alert("出力できるカードがありません。単語帳は問題と解答、穴埋めはCloze指定が必要です。");
    return;
  }
  if (skipped.length && !confirm(`未完成カード ${skipped.join(", ")} 番をスキップして出力しますか？`)) return;
  const table = [
    ["question", "answer", "choice_exclude_words", "choice_group"],
    ...rows.map((card) => [card.q, card.a, card.choiceExcludeWords, card.choiceGroup])
  ];
  const text = "\ufeff" + table.map((row) => row.map(csvCell).join(",")).join("\n") + "\n";
  downloadBlob(new Blob([text], { type: "text/csv;charset=utf-8" }), `moz_deck_${timestamp()}.csv`);
  setStatus(`CSVを出力しました: ${rows.length}件`);
}

function collectWorkState() {
  return {
    appVersion: APP_VERSION,
    createdAt: state.createdAt,
    updatedAt: new Date().toISOString(),
    mode: state.mode,
    text1: el.text1.value,
    text2: el.text2.value,
    text1Info: el.text1Info.textContent,
    text2Info: el.text2Info.textContent,
    draftQuestion: el.draftQ.value,
    draftAnswer: el.draftA.value,
    draftExcludes: el.draftExcludes.value,
    draftGroup: el.draftGroup.value,
    delimiter: el.delimiter.value,
    sourceLocked: el.sourceLock.checked,
    activePane: state.activePane,
    cards: cloneCards()
  };
}

function saveWork() {
  const json = JSON.stringify(collectWorkState(), null, 2);
  downloadBlob(new Blob([json], { type: "application/json;charset=utf-8" }), `moz_deck_work_${timestamp()}.mozbuild.json`);
  setStatus("作業を保存しました");
}

async function openWork(file) {
  const text = await file.text();
  const data = JSON.parse(text);
  el.text1.value = data.text1 || "";
  el.text2.value = data.text2 || "";
  el.text1Info.textContent = data.text1Info || "復元済み";
  el.text2Info.textContent = data.text2Info || "復元済み";
  el.draftQ.value = data.draftQuestion || "";
  el.draftA.value = data.draftAnswer || "";
  el.draftExcludes.value = data.draftExcludes || "";
  el.draftGroup.value = data.draftGroup || "";
  el.delimiter.value = data.delimiter || DEFAULT_DELIMITERS;
  el.sourceLock.checked = data.sourceLocked !== false;
  state.cards = Array.isArray(data.cards) ? data.cards : [];
  state.createdAt = data.createdAt || state.createdAt;
  setMode(data.mode === "cloze" ? "cloze" : "vocab", { silent: true });
  setActivePane(data.activePane === "text2" ? "text2" : "text1");
  applySourceLock();
  renderCards();
  setStatus("作業を開きました");
}

function downloadBlob(blob, filename) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

function openFind() {
  el.findbar.hidden = false;
  el.findScope.value = state.activePane;
  updateFindMatches();
  el.findInput.focus();
  el.findInput.select();
}

function closeFind() {
  el.findbar.hidden = true;
  state.findMatches = [];
  state.findIndex = -1;
  activeTextarea().focus();
}

function panesForFind() {
  const scope = el.findScope.value;
  return scope === "both" ? ["text1", "text2"] : [scope];
}

function updateFindMatches() {
  const query = el.findInput.value;
  state.findMatches = [];
  state.findIndex = -1;
  if (!query) {
    updateFindCount();
    return;
  }
  const needle = query.toLowerCase();
  for (const paneId of panesForFind()) {
    const text = (paneId === "text2" ? el.text2 : el.text1).value.toLowerCase();
    let start = 0;
    while (true) {
      const index = text.indexOf(needle, start);
      if (index < 0) break;
      state.findMatches.push({ paneId, start: index, end: index + query.length });
      start = index + Math.max(query.length, 1);
    }
  }
  moveFind(1);
}

function updateFindCount() {
  const total = state.findMatches.length;
  el.findCount.textContent = total ? `${state.findIndex + 1} / ${total}` : "0 / 0";
}

function moveFind(direction) {
  if (!state.findMatches.length) {
    updateFindCount();
    return;
  }
  state.findIndex = (state.findIndex + direction + state.findMatches.length) % state.findMatches.length;
  const match = state.findMatches[state.findIndex];
  setActivePane(match.paneId);
  const box = match.paneId === "text2" ? el.text2 : el.text1;
  box.focus();
  box.setSelectionRange(match.start, match.end);
  updateFindCount();
}

function isSourceTextarea(target) {
  return target === el.text1 || target === el.text2;
}

function isEditingTarget(target) {
  return target.closest && target.closest("td, .draft-panel, .findbar");
}

function digitFromEvent(event) {
  if (/^Digit[0-9]$/.test(event.code || "")) return event.code.slice(5);
  if (/^Numpad[0-9]$/.test(event.code || "")) return event.code.slice(6);
  if (/^[0-9]$/.test(event.key || "")) return event.key;
  return "";
}

function shortcutKey(event) {
  if (event.ctrlKey || event.metaKey || event.altKey || event.isComposing) return "";
  if (/^Key[ASDZX]$/.test(event.code || "")) return event.code.slice(3);
  const key = String(event.key || "").toUpperCase();
  return ["A", "S", "D", "Z", "X"].includes(key) ? key : "";
}

function handleSourceShortcut(event, source) {
  if (state.mode === "cloze") {
    const digit = digitFromEvent(event);
    if (digit === "0") {
      if (!selectedTextFrom(source)) return false;
      event.preventDefault();
      event.stopPropagation();
      setActivePane(source.id);
      return addClozeCard(selectedTextFrom(source));
    }
  }

  const key = shortcutKey(event);
  if (!key) return false;

  if (state.mode === "vocab" && (key === "A" || key === "S") && !selectedTextFrom(source)) {
    return false;
  }
  if (state.mode === "cloze" && key === "D" && !selectedTextFrom(source)) {
    return false;
  }

  event.preventDefault();
  event.stopPropagation();
  setActivePane(source.id);

  if (state.mode === "vocab") {
    if (key === "A") return appendSelectionToDraft(source, "q");
    if (key === "S") return appendSelectionToDraft(source, "a");
    if (key === "D") addVocabCardFromDraft();
  } else if (key === "D") {
    addClozeCard(selectedTextFrom(source));
  }
  if (key === "Z") undo();
  if (key === "X") clearDraft();
  return true;
}

document.querySelectorAll("[data-load]").forEach((button) => {
  button.addEventListener("click", () => {
    state.pendingLoadPane = button.dataset.load;
    el.sourceFile.click();
  });
});

document.querySelectorAll("[data-clean]").forEach((button) => {
  button.addEventListener("click", () => cleanSources(button.dataset.clean));
});

document.querySelectorAll("[data-format]").forEach((button) => {
  button.addEventListener("click", () => formatSources(button.dataset.format));
});

document.querySelectorAll("input[name='builder-mode']").forEach((input) => {
  input.addEventListener("change", () => setMode(input.value));
});

document.querySelectorAll(".source-pane textarea").forEach((box) => {
  box.addEventListener("focus", () => setActivePane(box.id));
  box.addEventListener("keydown", (event) => handleSourceShortcut(event, box), true);
  box.addEventListener("beforeinput", (event) => {
    const data = String(event.data || "").toUpperCase();
    if (state.mode === "vocab" && (data === "A" || data === "S") && selectedTextFrom(box)) {
      event.preventDefault();
      event.stopPropagation();
      setActivePane(box.id);
      appendSelectionToDraft(box, data === "A" ? "q" : "a");
    }
  });
});

el.sourceFile.addEventListener("change", async () => {
  const file = el.sourceFile.files[0];
  el.sourceFile.value = "";
  if (file) await loadSourceFile(file, state.pendingLoadPane);
});

el.workFile.addEventListener("change", async () => {
  const file = el.workFile.files[0];
  el.workFile.value = "";
  if (file) await openWork(file);
});

document.getElementById("add-card-button").addEventListener("click", addCardFromDraft);
document.getElementById("add-empty-button").addEventListener("click", addEmptyCard);
document.getElementById("clear-draft-button").addEventListener("click", clearDraft);
document.getElementById("undo-button").addEventListener("click", undo);
document.getElementById("clear-cards-button").addEventListener("click", clearCards);
document.getElementById("save-work-button").addEventListener("click", saveWork);
document.getElementById("open-work-button").addEventListener("click", () => el.workFile.click());
document.getElementById("export-tsv-button").addEventListener("click", exportTsv);
document.getElementById("export-csv-button").addEventListener("click", exportCsv);
document.getElementById("add-selected-cloze-button").addEventListener("click", addSelectedClozeCard);
document.getElementById("bulk-cloze-button").addEventListener("click", bulkAddActiveCloze);
document.getElementById("bulk-selected-cloze-button").addEventListener("click", bulkAddSelectedCloze);
document.getElementById("find-close-button").addEventListener("click", closeFind);
document.getElementById("find-prev-button").addEventListener("click", () => moveFind(-1));
document.getElementById("find-next-button").addEventListener("click", () => moveFind(1));
document.getElementById("detect-delimiters-button").addEventListener("click", detectDelimiterCandidates);
document.getElementById("reset-delimiters-button").addEventListener("click", resetDelimiters);
el.findInput.addEventListener("input", updateFindMatches);
el.findScope.addEventListener("change", updateFindMatches);
el.sourceLock.addEventListener("change", applySourceLock);

document.addEventListener("keydown", (event) => {
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "f") {
    event.preventDefault();
    openFind();
    return;
  }
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "s") {
    event.preventDefault();
    saveWork();
    return;
  }
  if (!el.findbar.hidden && event.key === "Enter") {
    event.preventDefault();
    moveFind(event.shiftKey ? -1 : 1);
    return;
  }
  if (!el.findbar.hidden && event.key === "Escape") {
    event.preventDefault();
    closeFind();
    return;
  }

  if (isSourceTextarea(event.target)) {
    handleSourceShortcut(event, event.target);
    return;
  }
  if (isEditingTarget(event.target)) return;
  const key = shortcutKey(event);
  if (key === "D") {
    event.preventDefault();
    addCardFromDraft();
  } else if (key === "Z") {
    event.preventDefault();
    undo();
  } else if (key === "X") {
    event.preventDefault();
    clearDraft();
  }
});

applySourceLock();
setMode("vocab", { silent: true });
renderCards();

/* ──────────────────────────────────────────────────────────────────────────
 * knot serve prototype — single-column "Stack" layout.
 *
 * Three collapsible groups (In progress / Ready / Blocked) rendered as a
 * single column; click a row to expand inline with body + acceptance +
 * blockers. Mirrors knot.el's Magit-style list and the terminal's
 * `knot ready` shape. See docs/adr/0005-knot-serve-stack-layout.md.
 * ────────────────────────────────────────────────────────────────────────── */

// ── State ──────────────────────────────────────────────────────────────────
const state = {
  ready: null,
  inProgress: null,
  blocked: null,
  showCache: new Map(),        // id -> single-ticket envelope.data
  loading: false,
};

const collapsedGroups = new Set();
const expandedRows = new Set();

// ── Fetch helpers ──────────────────────────────────────────────────────────
async function fetchJson(url) {
  const r = await fetch(url);
  const env = await r.json();
  // knot envelope: {schema_version, ok, data, error?}
  if (env.schema_version !== 1) {
    console.warn("unexpected schema_version", env.schema_version);
  }
  if (!env.ok) {
    throw new Error(env.error?.message || "unknown error");
  }
  return env.data;
}

async function loadAll() {
  state.loading = true;
  showLoader("loading…");
  try {
    const [ready, ip, blocked] = await Promise.all([
      fetchJson("/api/ready"),
      fetchJson("/api/in-progress"),
      fetchJson("/api/blocked"),
    ]);
    state.ready = ready;
    state.inProgress = ip;
    state.blocked = blocked;
    state.loading = false;
    hideLoader();
    render();
  } catch (err) {
    showError(err.message);
  }
}

async function loadShow(id) {
  if (state.showCache.has(id)) return state.showCache.get(id);
  const data = await fetchJson("/api/show/" + encodeURIComponent(id));
  state.showCache.set(id, data);
  return data;
}

// ── DOM helpers ────────────────────────────────────────────────────────────
function el(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (k === "class") node.className = v;
    else if (k === "html") node.innerHTML = v;
    else if (k.startsWith("on") && typeof v === "function") node.addEventListener(k.slice(2), v);
    else if (v === true) node.setAttribute(k, "");
    else if (v != null && v !== false) node.setAttribute(k, v);
  }
  for (const c of children) {
    if (c == null || c === false) continue;
    if (Array.isArray(c)) c.forEach(x => x != null && node.append(x.nodeType ? x : document.createTextNode(String(x))));
    else if (c.nodeType) node.append(c);
    else node.append(document.createTextNode(String(c)));
  }
  return node;
}

function showLoader(msg) {
  document.getElementById("loader").textContent = msg;
  document.getElementById("loader").hidden = false;
}
function hideLoader() { document.getElementById("loader").hidden = true; }
function showError(msg) {
  hideLoader();
  const e = document.getElementById("error");
  e.textContent = "Error: " + msg;
  e.hidden = false;
}

// ── Chip builders ──────────────────────────────────────────────────────────
function priChip(p) {
  return el("span", { class: `chip chip-pri chip-pri-${p}`, title: `priority ${p}` }, "P" + p);
}
function typeChip(t) {
  return el("span", { class: "chip chip-type" }, t);
}
function modeChip(m) {
  return el("span", { class: `chip chip-mode-${m}` }, m);
}
function tagChips(tags) {
  return (tags || []).map(t => el("span", { class: "chip chip-tag" }, "#" + t));
}
function idMono(id) {
  // show the suffix only — the "kno-" prefix is constant per project
  const suffix = id.replace(/^[a-z]+-/, "");
  return el("span", { class: "id-mono", title: id }, suffix);
}

// ── Render ─────────────────────────────────────────────────────────────────
function render() {
  const root = document.getElementById("ticket-stack");
  root.hidden = false;
  root.replaceChildren();

  const groups = [
    { key: "in_progress", title: "In progress", tickets: state.inProgress },
    { key: "ready",       title: "Ready",       tickets: state.ready },
    { key: "blocked",     title: "Blocked",     tickets: state.blocked },
  ];

  for (const g of groups) {
    root.append(renderGroup(g));
  }
}

function renderGroup(g) {
  const collapsed = collapsedGroups.has(g.key);
  const node = el("section", { class: "group" + (collapsed ? " collapsed" : "") });

  const header = el("div", { class: "group-header" },
    el("span", { class: "group-title" }, g.title),
    el("span", { class: "group-count" }, (g.tickets || []).length),
    el("span", { class: "group-caret" }, collapsed ? "▶" : "▼"),
  );
  header.addEventListener("click", () => {
    if (collapsedGroups.has(g.key)) collapsedGroups.delete(g.key);
    else collapsedGroups.add(g.key);
    render();
  });
  node.append(header);

  const body = el("div", { class: "group-body" });
  if (!g.tickets || g.tickets.length === 0) {
    body.append(el("div", { class: "empty" }, "— empty —"));
  } else {
    for (const t of g.tickets) {
      body.append(renderRow(t));
      if (expandedRows.has(t.id)) {
        body.append(renderRowDetail(t));
      }
    }
  }
  node.append(body);
  return node;
}

function renderRow(t) {
  const expanded = expandedRows.has(t.id);
  const row = el("div", { class: "row" + (expanded ? " expanded" : "") },
    priChip(t.priority),
    idMono(t.id),
    el("div", { class: "row-title" }, t.title),
    el("div", { class: "row-meta" },
      typeChip(t.type),
      modeChip(t.mode),
      ...tagChips(t.tags),
    ),
  );
  row.addEventListener("click", async () => {
    if (expandedRows.has(t.id)) expandedRows.delete(t.id);
    else {
      expandedRows.add(t.id);
      // pre-fetch detail if not cached
      try { await loadShow(t.id); } catch (e) { /* leave detail empty */ }
    }
    render();
  });
  return row;
}

function renderRowDetail(t) {
  const wrap = el("div", { class: "row-detail" });
  const detail = state.showCache.get(t.id);

  if (t.acceptance && t.acceptance.length) {
    wrap.append(el("h4", {}, `Acceptance (${t.acceptance.filter(a => a.done).length}/${t.acceptance.length})`));
    const ul = el("ul", { class: "ac-list" });
    for (const a of t.acceptance) {
      ul.append(el("li", { class: a.done ? "ac-done" : "ac-open" }, (a.done ? "[x] " : "[ ] ") + a.title));
    }
    wrap.append(ul);
  }

  if (detail) {
    if (detail.blockers && detail.blockers.length) {
      wrap.append(el("h4", {}, "Blocked by"));
      const ul = el("ul", { class: "ac-list" });
      for (const b of detail.blockers) {
        ul.append(el("li", { class: b.missing ? "ac-open" : "" },
          (b.title || b.id) + " — " + (b.status || (b.missing ? "missing" : ""))));
      }
      wrap.append(ul);
    }
    if (detail.body) {
      wrap.append(el("h4", {}, "Body"));
      wrap.append(el("pre", {}, detail.body));
    }
  } else {
    wrap.append(el("div", { class: "empty" }, "loading…"));
  }
  return wrap;
}

// ── Init ───────────────────────────────────────────────────────────────────
function wireUI() {
  document.getElementById("refresh-btn").addEventListener("click", () => {
    state.showCache.clear();
    loadAll();
  });
}

wireUI();
loadAll();

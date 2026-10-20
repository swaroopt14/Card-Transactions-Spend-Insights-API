const state = {
  token: sessionStorage.getItem("ledgerToken") || "",
  email: sessionStorage.getItem("ledgerEmail") || "",
  cards: [],
  selected: null,
  txns: [],
  insights: null,
  error: "",
  busy: false,
  showIssue: false,
  mode: "enter",
  challenge: null
};

const money = new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR", maximumFractionDigits: 2 });
const app = document.getElementById("app");
const categories = ["DINING", "GROCERIES", "TRAVEL", "SHOPPING", "UTILITIES", "ENTERTAINMENT", "OTHER"];
const channels = [["API", "API"], ["PHONE", "Phone"], ["FLIPKART", "Flipkart"], ["AMAZON", "Amazon"]];

function esc(value) {
  return String(value ?? "").replace(/[&<>"']/g, (ch) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"
  }[ch]));
}

async function api(path, options = {}) {
  const headers = { "Content-Type": "application/json" };
  if (state.token) headers.Authorization = "Bearer " + state.token;
  if (options.key) headers["Idempotency-Key"] = options.key;
  const response = await fetch(path, {
    method: options.method || "GET",
    headers,
    body: options.body ? JSON.stringify(options.body) : undefined
  });
  const text = await response.text();
  const data = text ? JSON.parse(text) : null;
  if (!response.ok) {
    if (response.status === 401 && !path.startsWith("/api/v1/auth")) signOut();
    throw new Error((data && data.message) || "Request failed");
  }
  return { status: response.status, data };
}

function signOut() {
  state.token = "";
  state.email = "";
  sessionStorage.removeItem("ledgerToken");
  sessionStorage.removeItem("ledgerEmail");
  render();
}

async function refresh() {
  state.cards = (await api("/api/v1/cards")).data;
  if (!state.cards.length) {
    state.selected = null;
    state.txns = [];
    state.insights = (await api("/api/v1/insights/monthly?month=" + monthOf(new Date()))).data;
    return;
  }
  if (!state.selected || !state.cards.some((card) => card.id === state.selected)) {
    state.selected = state.cards[0].id;
  }
  const month = monthOf(new Date());
  const [txns, insights] = await Promise.all([
    api("/api/v1/cards/" + state.selected + "/transactions?page=0&size=20"),
    api("/api/v1/insights/monthly?month=" + month)
  ]);
  state.txns = txns.data.content;
  state.insights = insights.data;
}

function monthOf(date) {
  return date.toISOString().slice(0, 7);
}

function monthLabel(value) {
  const [year, month] = String(value || monthOf(new Date())).split("-").map(Number);
  return new Intl.DateTimeFormat("en-IN", { month: "long", year: "numeric", timeZone: "UTC" })
    .format(new Date(Date.UTC(year, month - 1, 1)));
}

function when(value) {
  if (!value) return "";
  return new Intl.DateTimeFormat("en-IN", {
    day: "2-digit", month: "short", hour: "2-digit", minute: "2-digit", hour12: false, timeZone: "UTC"
  }).format(new Date(value));
}

function render() {
  app.innerHTML = state.token ? workspace() : gate();
}

function gate() {
  const creating = state.mode === "create";
  return `
    <main class="shell">
      <section class="stage">
        <div class="mark">Ledger</div>
        ${plate({ last4: "••••", nickname: "Card reference", creditLimit: null, status: "ACTIVE" })}
        <p class="empty-note">PAN and CVV are not stored.</p>
      </section>
      <section class="work">
        <header class="bar"><div class="mark">Ledger</div></header>
        <div class="pane gate-pane">
        <p class="kicker">${creating ? "Registration" : "Access"}</p>
        <h1>${creating ? "Create account" : "Sign in"}</h1>
        <p class="lede">${creating ? "Password of at least 8 characters. This form creates a user account." : "Record purchases against a card reference. The full number stays with the issuer."}</p>
        <form id="gate">
          <label>Email <input name="email" type="email" autocomplete="username" required value="${esc(state.email)}"></label>
          <label>Password <input name="password" type="password" autocomplete="${creating ? "new-password" : "current-password"}" required minlength="8"></label>
          <div class="actions">
            <button class="primary" type="submit" ${state.busy ? "disabled" : ""}>${creating ? "Create account" : "Sign in"}</button>
            <button class="text" type="button" data-action="switch">${creating ? "I already have an account" : "Create an account"}</button>
          </div>
          <p class="error">${esc(state.error)}</p>
        </form>
        </div>
      </section>
    </main>`;
}

function workspace() {
  const card = state.cards.find((item) => item.id === state.selected);
  const total = state.insights ? state.insights.grandTotal : 0;
  const cats = (state.insights && state.insights.categories) || [];
  return `
    <main class="shell">
      <section class="stage">
        <div class="mark">Ledger</div>
        ${card ? plate(card) : plate({ last4: "••••", nickname: "No reference yet", creditLimit: null, status: "ACTIVE" })}
        <div>
          <p class="month-label">${esc(monthLabel(state.insights ? state.insights.month : monthOf(new Date())))} · this account</p>
          <p class="month-total">${money.format(total)}</p>
          ${card && Number(total) > Number(card.creditLimit) ? `<p class="warn">Above the ${money.format(card.creditLimit)} limit.</p>` : ""}
          ${cats.length ? `<ul class="cats">${cats.map((row) => `<li><span>${esc(row.category)}<span class="count">${esc(row.count)}</span></span><span>${money.format(row.total)}</span></li>`).join("")}</ul>` : `<p class="empty-note">No spend recorded this month.</p>`}
          <div class="refs">
            ${state.cards.map((item) => `<button type="button" data-action="select" data-id="${item.id}" class="${item.id === state.selected ? "on" : ""}">${esc(item.last4)} · ${esc(item.nickname)}</button>`).join("")}
            <button class="add" type="button" data-action="issue">Add reference</button>
          </div>
        </div>
      </section>
      <section class="work">
        <header class="bar">
          <div class="mark">Ledger</div>
          <div class="actions">
            <p class="who">${esc(state.email)}</p>
            <button class="text" type="button" data-action="signout">Sign out</button>
          </div>
        </header>
        <div class="pane">
        <div class="title-row">
          <div>
        <p class="kicker">${card ? "Reference · " + esc(card.last4) + " · " + esc(card.status) : "Reference"}</p>
        <h1>${card ? esc(card.nickname) : "Add a reference"}</h1>
          </div>
          ${card ? `<button class="text" type="button" data-action="status">${card.status === "BLOCKED" ? "Unblock reference" : "Block reference"}</button>` : ""}
        </div>
        <p class="lede">${card && card.status === "BLOCKED" ? "New purchases are refused. Earlier rows stay on this ledger." : "A score under 70 is saved, with a flag when a rule fires. A score of 70 or more waits for the step-up code before it is saved."}</p>
        ${state.challenge ? stepUpForm() : ""}
        ${state.showIssue || !card ? issueForm() : ""}
        ${card ? spendForm(card) : ""}
        <p class="error">${esc(state.error)}</p>
        ${ledgerTable()}
        </div>
      </section>
    </main>`;
}

function plate(card) {
  const blocked = card.status === "BLOCKED";
  return `
    <article class="plate ${blocked ? "blocked" : ""}">
      <div class="plate-top">
        <div class="chip" aria-hidden="true"></div>
        ${card.last4 === "••••" ? "" : `<span class="status">${blocked ? "Blocked" : "Active"}</span>`}
      </div>
      <div>
        <p class="plate-kicker">Reference</p>
        <p class="last4">${esc(card.last4)}</p>
      </div>
      <div class="plate-bottom">
        <p class="meta">${esc(card.nickname)}</p>
        <p class="meta">${card.creditLimit != null ? money.format(card.creditLimit) : ""}</p>
      </div>
    </article>`;
}

function channelLabel(value) {
  const match = channels.find((item) => item[0] === value);
  return match ? match[1] : (value || "API");
}

function ledgerTable() {
  const rows = state.txns || [];
  if (!rows.length) return `<p class="none">No purchases on this reference.</p>`;
  return `
    <div class="table-wrap">
      <table>
        <thead>
          <tr><th>When</th><th>Merchant</th><th>Place</th><th>Where</th><th>Category</th><th class="num">Amount</th></tr>
        </thead>
        <tbody>
          ${rows.map((txn) => `
            <tr>
              <td class="when">${esc(when(txn.occurredAt))}</td>
              <td><span class="merchant">${esc(txn.merchant)}</span>${txn.flagged ? `<span class="why">${esc(txn.flagReason)}</span>` : ""}</td>
              <td class="where">${esc(txn.location || "")}</td>
              <td class="where">${esc(channelLabel(txn.channel))}</td>
              <td class="cat">${esc(txn.category)}</td>
              <td class="num">${money.format(txn.amount)}</td>
            </tr>`).join("")}
        </tbody>
      </table>
    </div>`;
}

function issueForm() {
  return `
    <form id="issue">
      <label>Last four <input name="last4" inputmode="numeric" pattern="\\d{4}" maxlength="4" required placeholder="4321"></label>
      <label>Name <input name="nickname" maxlength="40" required placeholder="Gold"></label>
      <label>Limit <input name="creditLimit" inputmode="decimal" required value="40000"></label>
      <div class="actions"><button class="primary" ${state.busy ? "disabled" : ""}>Save reference</button></div>
    </form>`;
}

function stepUpForm() {
  const challenge = state.challenge;
  return `
    <form id="stepup" class="composer">
      <p class="warn">Step-up required. Score ${esc(challenge.riskScore)}. ${esc(challenge.reasons || "")}</p>
      <label>Code <input name="challengeCode" inputmode="numeric" pattern="\\d{6}" maxlength="6" required value="${esc(challenge.challengeCode || "")}"></label>
      <button class="primary" ${state.busy ? "disabled" : ""}>Verify step-up</button>
    </form>`;
}

function spendForm(card) {
  const blocked = card.status === "BLOCKED";
  return `
    <form id="spend" class="composer">
      <label>Amount <input name="amount" inputmode="decimal" required placeholder="250.50"></label>
      <label>Merchant <input name="merchant" maxlength="80" required placeholder="Cafe"></label>
      <label>Place <input name="location" maxlength="40" placeholder="Mumbai"></label>
      <label>Category <select name="category">${categories.map((item) => `<option>${item}</option>`).join("")}</select></label>
      <label>Where <select name="channel">${channels.map((item) => `<option value="${item[0]}">${item[1]}</option>`).join("")}</select></label>
      <button class="primary" ${state.busy ? "disabled" : ""}>${blocked ? "Blocked" : "Record"}</button>
    </form>`;
}

app.addEventListener("click", async (event) => {
  const button = event.target.closest("button");
  if (!button) return;
  const action = button.dataset.action;
  if (action === "switch") {
    state.mode = state.mode === "create" ? "enter" : "create";
    state.error = "";
    render();
  } else if (action === "signout") {
    signOut();
  } else if (action === "issue") {
    state.showIssue = true;
    state.error = "";
    render();
  } else if (action === "select") {
    state.selected = Number(button.dataset.id);
    state.error = "";
    state.showIssue = false;
    await refresh();
    render();
  } else if (action === "status") {
    const card = state.cards.find((item) => item.id === state.selected);
    await run(async () => {
      const path = card.status === "BLOCKED" ? "unblock" : "block";
      await api("/api/v1/cards/" + card.id + "/" + path, { method: "PATCH" });
      await refresh();
    });
  }
});

app.addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = event.target;
  const data = Object.fromEntries(new FormData(form));
  if (form.id === "gate") {
    await run(async () => {
      const path = state.mode === "create" ? "/register" : "/login";
      const session = (await api("/api/v1/auth" + path, {
        method: "POST",
        body: { email: data.email, password: data.password }
      })).data;
      state.token = session.token;
      state.email = data.email;
      sessionStorage.setItem("ledgerToken", session.token);
      sessionStorage.setItem("ledgerEmail", data.email);
      state.error = "";
      await refresh();
    });
  } else if (form.id === "issue") {
    await run(async () => {
      const created = (await api("/api/v1/cards", {
        method: "POST",
        body: { last4: data.last4, nickname: data.nickname, creditLimit: data.creditLimit }
      })).data;
      state.selected = created.id;
      state.showIssue = false;
      await refresh();
    });
  } else if (form.id === "spend") {
    await run(async () => {
      const posted = await api("/api/v1/cards/" + state.selected + "/transactions", {
        method: "POST",
        key: crypto.randomUUID(),
        body: {
          amount: data.amount,
          merchant: data.merchant,
          category: data.category,
          channel: data.channel,
          location: data.location && data.location.trim() ? data.location.trim() : undefined
        }
      });
      if (posted.status === 202) {
        state.challenge = posted.data;
        return;
      }
      state.challenge = null;
      await refresh();
    });
  } else if (form.id === "stepup") {
    await run(async () => {
      await api("/api/v1/cards/" + state.challenge.cardId + "/step-up/" + state.challenge.challengeId, {
        method: "POST",
        body: { challengeCode: data.challengeCode }
      });
      state.challenge = null;
      await refresh();
    });
  }
});

async function run(work) {
  state.busy = true;
  state.error = "";
  render();
  try {
    await work();
  } catch (error) {
    state.error = error.message;
  } finally {
    state.busy = false;
    render();
  }
}

if (state.token) {
  refresh().then(render).catch((error) => {
    state.error = state.token ? error.message : "";
    render();
  });
} else {
  render();
}

/**
 * The example page, written against the generated client and nothing else.
 *
 * Every call below is `api.…`: no URL is spelled here, no response is cast, and no shape is
 * declared twice. Change a Kotlin resource, regenerate, and the compiler points at what broke.
 */
import {
  api,
  type Book,
  type Instant,
  type BookListResponse,
  type Condition,
  type Shelf,
  type SortField,
} from "./generated/client.js";
import { ApiError } from "./generated/client-runtime.js";
import { toTemporal } from "./generated/client-temporal.js";

type State = {
  shelves: Shelf[];
  shelf: string | null;
  list: BookListResponse | null;
  detailOf: string | null;
};

const state: State = { shelves: [], shelf: null, list: null, detailOf: null };

const el = <T extends HTMLElement>(id: string): T => {
  const found = document.getElementById(id);
  if (found == null) throw new Error(`no #${id} in the page`);
  return found as T;
};

const shelfBar = el<HTMLDivElement>("shelves");
const bookList = el<HTMLDivElement>("books");
const summary = el<HTMLParagraphElement>("summary");
const tagBar = el<HTMLDivElement>("tags");
const detail = el<HTMLDivElement>("detail");
const log = el<HTMLOListElement>("log");
const search = el<HTMLInputElement>("q");
const conditionFilter = el<HTMLSelectElement>("condition");
const sortField = el<HTMLSelectElement>("sort");
const limitField = el<HTMLInputElement>("limit");
const borrowerField = el<HTMLInputElement>("borrower");
const addForm = el<HTMLFormElement>("add");

/** Every call goes through here, so the page can show what it did and what came back. */
async function call<T>(what: string, run: () => Promise<T>): Promise<T | null> {
  const started = performance.now();
  try {
    const result = await run();
    note(what, `${Math.round(performance.now() - started)} ms`, "ok");
    return result;
  } catch (error) {
    if (error instanceof ApiError) {
      // `body` is `unknown`: an error shape is not part of the generated contract.
      const said = (error.body as { message?: string } | undefined)?.message;
      note(what, said != null ? `${error.status} — ${said}` : String(error.status), "bad");
    } else {
      note(what, String(error), "bad");
    }
    return null;
  }
}

function note(what: string, detail: string, kind: "ok" | "bad"): void {
  const line = document.createElement("li");
  line.className = kind;
  line.innerHTML = `<code>${what}</code><span>${detail}</span>`;
  log.prepend(line);
  while (log.children.length > 12) log.lastElementChild?.remove();
}

async function loadShelves(): Promise<void> {
  const shelves = await call("api.shelves.get()", () => api.shelves.get());
  if (shelves == null) return;
  // A bare `Shelf[]`: the endpoint has nothing to say alongside the list, so it does not wrap one.
  state.shelves = shelves;
  state.shelf ??= shelves[0]?.slug ?? null;
  renderShelves();
  await loadBooks();
}

async function loadBooks(): Promise<void> {
  const shelf = state.shelf;
  if (shelf == null) return;
  // The arguments object is the wire's query string: `q`, `condition`, `sort`, `limit`. An omitted
  // one is absent from the URL rather than sent as "undefined".
  const args = {
    q: search.value.trim() || undefined,
    condition: (conditionFilter.value || undefined) as Condition | undefined,
    sort: sortField.value as SortField,
    limit: Number(limitField.value) || undefined,
  };
  const response = await call(`api.shelves.shelf("${shelf}").books.get(…)`, () =>
    api.shelves.shelf(shelf).books.get(args),
  );
  if (response == null) return;
  state.list = response;
  renderBooks();
}

async function showDetail(isbn: string): Promise<void> {
  const shelf = state.shelf;
  if (shelf == null) return;
  const response = await call(`…books.isbn("${isbn}").get()`, () =>
    api.shelves.shelf(shelf).books.isbn(isbn).get(),
  );
  if (response == null) return;
  state.detailOf = isbn;
  // `note` is optional in the generated type — `@JsonInclude(NON_NULL)` on the Kotlin class is the
  // only reason a field may be absent, and the client says so rather than guessing.
  detail.innerHTML = `
    <h3>${escape(response.book.title)}</h3>
    <p class="muted">${escape(response.book.author)} · ${response.book.condition} · added ${date(response.book.addedAt)}</p>
    ${response.note != null ? `<p class="note">${escape(response.note)}</p>` : ""}
    <p class="muted">Also on this shelf: ${
      response.alsoOnThisShelf.map((book) => escape(book.title)).join(", ") || "nothing else"
    }</p>`;
}

async function borrow(isbn: string): Promise<void> {
  const shelf = state.shelf;
  if (shelf == null) return;
  const borrower = borrowerField.value.trim() || "someone";

  const result = await call(`…books.isbn("${isbn}").borrow.post(…)`, () =>
    api.shelves.shelf(shelf).books.isbn(isbn).borrow.post({ borrower }),
    // `days` is omitted: the Kotlin constructor defaults it, so the generated request type marks
    // it optional and the server decides.
  );
  if (result == null) return;

  // The sealed Kotlin hierarchy is a discriminated union here, so this narrows — and adding a
  // variant in Kotlin makes this switch fail to compile until it is handled.
  switch (result.outcome) {
    case "accepted":
      note("borrow → accepted", `${result.borrower}, due ${date(result.dueAt)}`, "ok");
      break;
    case "refused":
      note("borrow → refused", `${result.reason} (${result.borrowedBy})`, "bad");
      break;
  }
  await loadBooks();
}

async function returnCopy(isbn: string): Promise<void> {
  const shelf = state.shelf;
  if (shelf == null) return;
  // `return` is a reserved word, and a perfectly good property name.
  await call(`…books.isbn("${isbn}").return.post()`, () =>
    api.shelves.shelf(shelf).books.isbn(isbn).return.post(),
  );
  await loadBooks();
}

async function remove(isbn: string): Promise<void> {
  const shelf = state.shelf;
  if (shelf == null) return;
  // Bodyless: `Promise<void>`, resolved by the 204 itself.
  await call(`…books.isbn("${isbn}").delete()`, () =>
    api.shelves.shelf(shelf).books.isbn(isbn).delete(),
  );
  if (state.detailOf === isbn) detail.innerHTML = "";
  await loadShelves();
}

addForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  const shelf = state.shelf;
  if (shelf == null) return;
  const form = new FormData(addForm);
  const tags = String(form.get("tags") ?? "")
    .split(",")
    .map((tag) => tag.trim())
    .filter((tag) => tag.length > 0);

  const added = await call(`api.shelves.shelf("${shelf}").books.post(…)`, () =>
    api.shelves.shelf(shelf).books.post({
      isbn: String(form.get("isbn")),
      title: String(form.get("title")),
      author: String(form.get("author")),
      condition: String(form.get("condition")) as Condition,
      // `tags` may be omitted entirely; sending an empty list is also fine.
      tags,
    }),
  );
  if (added == null) return;
  addForm.reset();
  await loadShelves();
});

function renderShelves(): void {
  shelfBar.replaceChildren(
    ...state.shelves.map((shelf) => {
      const button = document.createElement("button");
      button.textContent = `${shelf.name} (${shelf.bookCount})`;
      button.className = shelf.slug === state.shelf ? "chip selected" : "chip";
      button.addEventListener("click", async () => {
        state.shelf = shelf.slug;
        detail.innerHTML = "";
        renderShelves();
        await loadBooks();
      });
      return button;
    }),
  );
}

function renderBooks(): void {
  const list = state.list;
  if (list == null) return;
  summary.textContent = `${list.books.length} of ${list.total} shown`;
  tagBar.replaceChildren(
    ...Object.entries(list.tagCounts).map(([tag, count]) => {
      const badge = document.createElement("button");
      badge.className = "chip small";
      badge.textContent = `${tag} · ${count}`;
      badge.addEventListener("click", async () => {
        search.value = tag;
        await loadBooks();
      });
      return badge;
    }),
  );
  bookList.replaceChildren(...list.books.map(bookRow));
}

function bookRow(book: Book): HTMLElement {
  const row = document.createElement("article");
  row.className = "book";
  row.innerHTML = `
    <div>
      <strong>${escape(book.title)}</strong>
      <span class="muted">${escape(book.author)}</span>
      <div class="meta">
        <span class="pill ${book.condition}">${book.condition}</span>
        ${book.tags.map((tag) => `<span class="pill tag">${escape(tag)}</span>`).join("")}
        <span class="muted" title="added ${date(book.addedAt)}">added ${age(book.addedAt)}</span>
        ${book.borrowedBy != null ? `<span class="pill out">out with ${escape(book.borrowedBy)}</span>` : ""}
      </div>
    </div>
    <div class="actions"></div>`;

  const actions = row.querySelector<HTMLDivElement>(".actions")!;
  actions.append(
    action("Detail", () => showDetail(book.isbn)),
    book.borrowedBy == null
      ? action("Borrow", () => borrow(book.isbn))
      : action("Return", () => returnCopy(book.isbn)),
    action("Remove", () => remove(book.isbn), "danger"),
  );
  return row;
}

function action(label: string, run: () => Promise<void>, kind = ""): HTMLButtonElement {
  const button = document.createElement("button");
  button.textContent = label;
  button.className = kind;
  button.addEventListener("click", () => void run());
  return button;
}

/**
 * `Instant` reaches the client as an ISO-8601 string — branded, so it cannot be confused with a
 * title, and still a string, so `new Date` takes it unchanged.
 */
function date(iso: Instant): string {
  return new Date(iso).toLocaleDateString();
}

/**
 * The same value through `Temporal`, which is what the generated conversion module is for. The
 * wire format the server writes is the interchange format `Temporal` parses, so this is one call
 * and no conversion layer.
 */
function age(addedAt: Instant): string {
  // An Instant has no calendar, so "days" is not a unit it can answer in — the same reason
  // java.time makes you pick a zone. Through the zone to a plain date, and the difference is
  // calendar days, which is what "3 days ago" means.
  const added = toTemporal.instant(addedAt).toZonedDateTimeISO(Temporal.Now.timeZoneId()).toPlainDate();
  const days = added.until(Temporal.Now.plainDateISO(), { largestUnit: "day" }).days;
  if (days === 0) return "today";
  return days === 1 ? "1 day ago" : `${days} days ago`;
}

function escape(text: string): string {
  const holder = document.createElement("span");
  holder.textContent = text;
  return holder.innerHTML;
}

/**
 * Nothing here is polyfilled or transpiled — the point of the example is that what runs is what was
 * generated — so a browser missing something the page needs would otherwise show a blank panel and
 * an error in a console nobody has open. Say so instead.
 *
 * These are what the page actually uses — including `Temporal`, which this page does depend on
 * because it converts timestamps through the generated conversion module.
 */
function missingCapabilities(): string[] {
  const missing: string[] = [];
  const signals = typeof AbortSignal !== "undefined";
  if (!signals || typeof AbortSignal.any !== "function") missing.push("AbortSignal.any");
  if (!signals || typeof AbortSignal.timeout !== "function") missing.push("AbortSignal.timeout");
  if (typeof Element.prototype.replaceChildren !== "function") missing.push("replaceChildren");
  // The page converts timestamps with the generated Temporal module, so here it really is required
  // — https://caniuse.com/temporal had it at 69% of global usage in July 2026.
  if (typeof Temporal === "undefined") missing.push("Temporal");
  return missing;
}

const missing = missingCapabilities();
if (missing.length > 0) {
  const warning = document.createElement("section");
  warning.innerHTML =
    `<h2>This browser is missing something</h2><p>The page needs <code>${missing.join("</code>, <code>")}</code>,` +
    ` and nothing here is polyfilled — the point of the example is that what runs is what was` +
    ` generated. See <a href="https://caniuse.com">caniuse</a>.</p>`;
  document.querySelector("main")?.prepend(warning);
} else {
  for (const control of [search, conditionFilter, sortField, limitField]) {
    control.addEventListener("change", () => void loadBooks());
  }
  search.addEventListener("input", () => void loadBooks());

  void loadShelves();
}

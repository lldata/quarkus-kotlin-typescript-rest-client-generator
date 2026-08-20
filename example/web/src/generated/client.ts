// Generated from the Kotlin resources. Do not edit: fix the resources, or
// the generator, and re-run `./gradlew :example:backend:generateApiClient`.
import {
  apiRequest,
  type ApiConfig,
  type RequestOptions,
} from "./client-runtime.js";

export const API_STAMP: string = "56031b59";

export const API_STAMP_HEADER: string = "x-api-stamp";

export interface WireScalar<Raw, T extends Raw> {
  from: (raw: Raw) => T;
}

export interface CheckedScalar<Raw, T extends Raw> extends WireScalar<Raw, T> {
  is: (raw: Raw) => raw is T;
}

export type Instant = string & { readonly __brand: "Instant" };

export const Instant: CheckedScalar<string, Instant> = {
  from: (raw: string): Instant => {
    if (!Instant.is(raw)) throw new TypeError(`invalid Instant: ${raw}`);
    return raw;
  },
  is: (raw: string): raw is Instant =>
    /^(?:\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d+)?)?(?:Z|[+-]\d{2}:\d{2}))$/u.test(
      raw,
    ),
};

export type ShelfSlug = string;

export interface Shelf {
  slug: ShelfSlug;
  name: string;
  bookCount: number;
}

export type Condition = "new" | "used" | "worn";

export type Isbn = string;

export interface Book {
  isbn: Isbn;
  title: string;
  author: string;
  condition: Condition;
  tags: string[];
  addedAt: Instant;
  borrowedBy: string | null;
}

export interface BookListResponse {
  books: Book[];
  total: number;
  tagCounts: Record<string, number>;
}

export interface BookDetailResponse {
  book: Book;
  note?: string | null;
  alsoOnThisShelf: Book[];
}

export type BorrowOutcome = "accepted" | "refused";

export interface BorrowAccepted {
  borrower: string;
  dueAt: Instant;
  outcome: "accepted";
}

export interface BorrowRefused {
  reason: string;
  borrowedBy: string;
  outcome: "refused";
}

export type BorrowResponse = BorrowAccepted | BorrowRefused;

export interface AddBookRequest {
  isbn: Isbn;
  title: string;
  author: string;
  condition?: Condition;
  tags?: string[];
}

export interface BorrowRequest {
  borrower: string;
  days?: number;
}

export type SortField = "TITLE" | "AUTHOR" | "ADDED";

function shelvesShelfBooksIsbnBorrowNode(path: string, config: ApiConfig) {
  return {
    post: (
      body: BorrowRequest,
      options?: RequestOptions,
    ): Promise<BorrowResponse> =>
      apiRequest<BorrowResponse>(
        { method: "POST", path, body },
        config,
        options,
      ),
  };
}

function shelvesShelfBooksIsbnReturnNode(path: string, config: ApiConfig) {
  return {
    post: (options?: RequestOptions): Promise<BookDetailResponse> =>
      apiRequest<BookDetailResponse>({ method: "POST", path }, config, options),
  };
}

function shelvesShelfBooksIsbnNode(path: string, config: ApiConfig) {
  return {
    get borrow() {
      return shelvesShelfBooksIsbnBorrowNode(`${path}/borrow`, config);
    },
    get return() {
      return shelvesShelfBooksIsbnReturnNode(`${path}/return`, config);
    },
    delete: (options?: RequestOptions): Promise<void> =>
      apiRequest<void>({ method: "DELETE", path }, config, options),
    get: (options?: RequestOptions): Promise<BookDetailResponse> =>
      apiRequest<BookDetailResponse>({ method: "GET", path }, config, options),
  };
}

function shelvesShelfBooksNode(path: string, config: ApiConfig) {
  return {
    isbn: (isbn: string) =>
      shelvesShelfBooksIsbnNode(
        `${path}/${encodeURIComponent(String(isbn))}`,
        config,
      ),
    get: (
      args?: {
        q?: string;
        condition?: Condition;
        /**
         * Server default: `TITLE`.
         */
        sort?: SortField;
        /**
         * Server default: `50`.
         */
        limit?: number;
      },
      options?: RequestOptions,
    ): Promise<BookListResponse> =>
      apiRequest<BookListResponse>(
        {
          method: "GET",
          path,
          query: {
            q: args?.q,
            condition: args?.condition,
            sort: args?.sort,
            limit: args?.limit,
          },
        },
        config,
        options,
      ),
    post: (
      body: AddBookRequest,
      options?: RequestOptions,
    ): Promise<BookDetailResponse> =>
      apiRequest<BookDetailResponse>(
        { method: "POST", path, body },
        config,
        options,
      ),
  };
}

function shelvesShelfNode(path: string, config: ApiConfig) {
  return {
    get books() {
      return shelvesShelfBooksNode(`${path}/books`, config);
    },
  };
}

function shelvesNode(path: string, config: ApiConfig) {
  return {
    shelf: (shelf: string) =>
      shelvesShelfNode(`${path}/${encodeURIComponent(String(shelf))}`, config),
    get: (options?: RequestOptions): Promise<Shelf[]> =>
      apiRequest<Shelf[]>({ method: "GET", path }, config, options),
  };
}

export function createApi(baseConfig: ApiConfig = {}) {
  const config: ApiConfig = {
    ...baseConfig,
    headers: { "x-api-stamp": API_STAMP, ...baseConfig.headers },
  };
  return {
    get shelves() {
      return shelvesNode("/api/shelves", config);
    },
  };
}

export const api: ReturnType<typeof createApi> = createApi();

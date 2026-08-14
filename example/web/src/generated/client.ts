// Generated from the Kotlin resources. Do not edit: fix the resources, or
// the generator, and re-run `./gradlew :example:backend:generateApiClient`.
import { ApiConfig, RequestOptions, apiRequest } from "./client-runtime.js";

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
  addedAt: string;
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
  dueAt: string;
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

function shelvesShelfBooksIsbnRemoveNode(path: string, config: ApiConfig) {
  return {
    post: (options?: RequestOptions): Promise<void> =>
      apiRequest<void>({ method: "POST", path }, config, options),
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
    get remove() {
      return shelvesShelfBooksIsbnRemoveNode(`${path}/remove`, config);
    },
    get return() {
      return shelvesShelfBooksIsbnReturnNode(`${path}/return`, config);
    },
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

export function createApi(config: ApiConfig = {}) {
  return {
    get shelves() {
      return shelvesNode("/api/shelves", config);
    },
  };
}

export const api: ReturnType<typeof createApi> = createApi();

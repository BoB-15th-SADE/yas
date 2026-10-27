import { SearchParams } from '../models/SearchParams';
import { SearchProductResponse } from '../models/SearchProductResponse';
import { ProductSearchSuggestions } from '../models/ProductSearchSuggestions';
import { ESortType } from '../models/SortType';

interface CatalogClient {
  get(url: string): Promise<{ ok: boolean; status: number; json(): Promise<any> }>;
}

interface CatalogItem {
  id: number; name: string; slug: string; price: number;
  thumbnailUrl: string; thumbnailId: number;
  brand: string; categories: string[]; attributes: string[];
}

const caches = new WeakMap<CatalogClient, { expires: number; promise: Promise<CatalogItem[]> }>();
const normalize = (value: string) => value.normalize('NFKC').trim().toLocaleLowerCase();
const selections = (value?: string) => (value || '').split(',').map(normalize).filter(Boolean);

async function readJson(client: CatalogClient, url: string) {
  const response = await client.get(url);
  if (!response.ok) throw new Error(`Product catalog returned HTTP ${response.status}`);
  return response.json();
}

async function loadCatalog(client: CatalogClient): Promise<CatalogItem[]> {
  const items: any[] = [];
  let pages = 1;
  for (let page = 0; page < pages; page++) {
    const result = await readJson(client, `/api/product/storefront/products?pageNo=${page}&pageSize=100`);
    if (!Array.isArray(result.productContent) || !Number.isInteger(result.totalPages)) {
      throw new Error('Invalid product catalog response');
    }
    pages = Math.max(1, result.totalPages);
    // This adapter is for the small PoC catalog; it must not silently truncate results.
    if (pages > 100) throw new Error('Catalog exceeds the PoC search limit');
    items.push(...result.productContent);
  }

  const results: Array<CatalogItem | undefined> = new Array(items.length);
  let next = 0;
  const worker = async () => {
    while (next < items.length) {
      const index = next++;
      const item = items[index];
      if (!Number.isSafeInteger(item.id) || item.id <= 0) throw new Error('Invalid product identifier');
      const meta = await readJson(client, `/api/product/storefront/products-es/${item.id}`);
      if (meta.id !== item.id || !Array.isArray(meta.categories)) {
        throw new Error('Invalid product metadata response');
      }
      if (meta.isPublished === false || meta.isVisibleIndividually === false) continue;
      results[index] = {
        id: item.id, name: item.name, slug: item.slug, price: item.price,
        thumbnailUrl: item.thumbnailUrl || '', thumbnailId: meta.thumbnailMediaId,
        brand: meta.brand || '', categories: meta.categories, attributes: meta.attributes || [],
      };
    }
  };
  await Promise.all(Array.from({ length: Math.min(4, items.length) }, worker));
  return results.filter((item): item is CatalogItem => !!item);
}

function catalog(client: CatalogClient) {
  const cached = caches.get(client);
  if (cached && cached.expires > Date.now()) return cached.promise;
  const entry = { expires: Number.POSITIVE_INFINITY, promise: loadCatalog(client) };
  caches.set(client, entry);
  entry.promise.then(
    () => { entry.expires = Date.now() + 30000; },
    () => { if (caches.get(client) === entry) caches.delete(client); }
  );
  return entry.promise;
}

function matches(item: CatalogItem, keyword: string) {
  const text = normalize([item.name, item.brand, ...item.categories, ...item.attributes].join(' '));
  return normalize(keyword).split(/\s+/).filter(Boolean).every((word) => text.includes(word));
}

function selected(values: string[], selectedValues: string[]) {
  return !selectedValues.length || values.some((value) => selectedValues.includes(normalize(value)));
}

export async function searchCatalog(client: CatalogClient, params: SearchParams): Promise<SearchProductResponse> {
  const keywordMatches = (await catalog(client)).filter((item) => matches(item, params.keyword));
  const aggregations: SearchProductResponse['aggregations'] = { brands: {}, categories: {}, attributes: {} };
  for (const item of keywordMatches) {
    for (const [field, values] of Object.entries({
      brands: item.brand ? [item.brand] : [], categories: item.categories, attributes: item.attributes,
    })) {
      for (const value of Array.from(new Set(values))) {
        aggregations[field][value] = (aggregations[field][value] || 0) + 1;
      }
    }
  }
  const filtered = keywordMatches.filter((item) =>
    selected([item.brand], selections(params.brand)) &&
    selected(item.categories, selections(params.category)) &&
    selected(item.attributes, selections(params.attribute)) &&
    (params.minPrice === undefined || item.price >= params.minPrice) &&
    (params.maxPrice === undefined || item.price <= params.maxPrice)
  );
  if (params.sortType === ESortType.priceAsc) filtered.sort((a, b) => a.price - b.price || a.id - b.id);
  else if (params.sortType === ESortType.priceDesc) filtered.sort((a, b) => b.price - a.price || a.id - b.id);
  const pageSize = Number.isFinite(params.pageSize) ? Math.max(1, Math.min(100, Math.floor(params.pageSize!))) : 10;
  const totalPages = Math.ceil(filtered.length / pageSize);
  const requestedPage = Number.isFinite(params.page) ? Math.max(0, Math.floor(params.page!)) : 0;
  const pageNo = Math.min(requestedPage, Math.max(0, totalPages - 1));
  return {
    products: filtered.slice(pageNo * pageSize, (pageNo + 1) * pageSize),
    totalElements: filtered.length, totalPages, pageNo, pageSize,
    isLast: pageNo >= totalPages - 1, aggregations,
  };
}

export async function suggestCatalog(client: CatalogClient, keyword: string): Promise<ProductSearchSuggestions> {
  if (!normalize(keyword)) return { productNames: [] };
  const products = (await catalog(client)).filter((item) => matches(item, keyword));
  return { productNames: products.slice(0, 8).map(({ name }) => ({ name })) };
}

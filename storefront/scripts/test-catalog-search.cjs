const assert = require('node:assert/strict');
const { execFileSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'yas-search-test-'));
execFileSync(process.execPath, [require.resolve('typescript/bin/tsc'),
  '--target', 'ES5', '--lib', 'ES2020,DOM', '--module', 'commonjs', '--skipLibCheck', '--rootDir', '.',
  '--outDir', temporary, 'modules/search/services/CatalogSearch.ts'], { stdio: 'inherit' });
const { searchCatalog, suggestCatalog } = require(path.join(temporary, 'modules/search/services/CatalogSearch.js'));

const items = [
  { id: 1, name: 'iPhone 15', slug: 'iphone-15', price: 799, thumbnailUrl: 'https://images.test/1' },
  { id: 2, name: 'Galaxy', slug: 'galaxy', price: 999, thumbnailUrl: 'https://images.test/2' },
  { id: 3, name: 'iPhone Pro', slug: 'iphone-pro', price: 899, thumbnailUrl: 'https://images.test/3' },
  { id: 4, name: 'Hidden iPhone', slug: 'hidden', price: 1, thumbnailUrl: '' },
];
const requests = [];
const client = { get: async (url) => {
  requests.push(url);
  assert.ok(url.startsWith('/api/product/storefront/'), 'all calls stay on existing product APIs');
  const parsed = new URL(url, 'https://shop.test');
  if (parsed.pathname.endsWith('/products')) {
    const page = Number(parsed.searchParams.get('pageNo'));
    return { ok: true, status: 200, json: async () => ({ totalPages: 2, productContent: items.slice(page * 2, page * 2 + 2) }) };
  }
  const id = Number(parsed.pathname.split('/').pop());
  const samsung = id === 2;
  return { ok: true, status: 200, json: async () => ({ id, brand: samsung ? 'Samsung' : 'Apple',
    categories: ['Phone', samsung ? 'Galaxy' : 'iPhone'], attributes: ['Screen'],
    thumbnailMediaId: id, isPublished: id !== 4, isVisibleIndividually: true }) };
} };

async function main() {
  const phones = await searchCatalog(client, { keyword: ' IPHONE ' });
  assert.deepEqual(phones.products.map(x => x.id), [1, 3], 'case-insensitive search spans all catalog pages');
  assert.equal(phones.aggregations.brands.Apple, 2);
  assert.equal(phones.products[0].thumbnailUrl, items[0].thumbnailUrl);
  const sorted = await searchCatalog(client, { keyword: '', sortType: 'PRICE_DESC', pageSize: 1, page: 1 });
  assert.equal(sorted.products[0].id, 3, 'global price sort runs before pagination');
  assert.equal(sorted.totalElements, 3, 'unpublished products are excluded');
  const filtered = await searchCatalog(client, { keyword: '', brand: 'apple', minPrice: 800, maxPrice: 1000 });
  assert.deepEqual(filtered.products.map(x => x.id), [3]);
  const category = await searchCatalog(client, { keyword: 'phone', category: 'GALAXY' });
  assert.deepEqual(category.products.map(x => x.id), [2]);
  assert.deepEqual(await suggestCatalog(client, 'Samsung'), { productNames: [{ name: 'Galaxy' }] });
  assert.equal((await searchCatalog(client, { keyword: 'not-a-real-product' })).totalElements, 0);
  assert.equal(requests.length, 6, 'subsequent searches reuse the published catalog cache');
  const failed = { get: async () => ({ ok: false, status: 502, json: async () => ({}) }) };
  await assert.rejects(searchCatalog(failed, { keyword: 'iPhone' }), /502/, 'API failure is not reported as an empty result');
  const malformed = { get: async () => ({ ok: true, status: 200, json: async () => ({}) }) };
  await assert.rejects(searchCatalog(malformed, { keyword: 'iPhone' }), /Invalid product catalog/);
  console.log('Catalog search regression checks passed: pagination, filters, sorting, suggestions, visibility, images, errors.');
}

main().catch(error => { console.error(error); process.exitCode = 1; }).finally(() => {
  if (path.dirname(temporary) === path.resolve(os.tmpdir()) && path.basename(temporary).startsWith('yas-search-test-')) {
    fs.rmSync(temporary, { recursive: true });
  }
});

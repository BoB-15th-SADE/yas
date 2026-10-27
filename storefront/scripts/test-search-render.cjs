const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
const React = require('react');
const { act, create } = require('react-test-renderer');
const ESortType = { default: 'DEFAULT', priceAsc: 'PRICE_ASC', priceDesc: 'PRICE_DESC' };
const SortType = { DEFAULT: 'default', PRICE_ASC: 'priceAsc', PRICE_DESC: 'priceDesc' };
const query = Object.freeze({ keyword: 'iPhone', brand: 'Apple', page: '3', sortType: 'priceAsc' });
const routes = [];
const router = { pathname: '/search', query, replace: (...args) => {
  routes.push(args); return Promise.resolve(true);
} };
const filename = process.env.SEARCH_SORT_SOURCE || path.resolve('modules/search/components/SearchSort.tsx');
const compiled = ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.React, esModuleInterop: true },
}).outputText;
const moduleFixture = { exports: {} };
vm.runInNewContext(compiled, {
  module: moduleFixture, exports: moduleFixture.exports, console, React,
  require(name) {
    if (name === 'react') return React;
    if (name === 'next/router') return { useRouter: () => router };
    if (name === '../models/SortType') return { ESortType, SortType };
    if (name.endsWith('.css')) return {};
    if (name === 'clsx') return (...parts) => parts.filter(Boolean).join(' ');
    throw new Error('Unexpected component dependency: ' + name);
  },
}, { filename });
const SearchSort = moduleFixture.exports.default;
const searches = [];
const pageChanges = [];
let currentParams = { keyword: 'iPhone', brand: 'Apple', sortType: ESortType.priceAsc, page: 3 };
const props = () => ({
  totalElements: 4, keyword: currentParams.keyword, searchParams: currentParams, isFilter: true,
  setSearchParams: params => searches.push(params), setPageNo: page => pageChanges.push(page),
});
let tree;
act(() => { tree = create(React.createElement(SearchSort, props())); });
assert.equal(searches.length, 0, 'mounting search results must not trigger another search');
assert.equal(pageChanges.length, 0, 'mounting results preserves the current page');
act(() => tree.unmount());
act(() => { tree = create(React.createElement(SearchSort, props())); });
assert.equal(searches.length, 0, 'remount after loading must not restart the search loop');
currentParams = { ...currentParams, sortType: ESortType.priceDesc };
act(() => tree.update(React.createElement(SearchSort, props())));
assert.equal(searches.length, 0, 'URL-driven sort changes must not write back to search state');
const currentLabel = tree.root.findAllByType('span').find(node => node.children.includes('Price: High to Low'));
assert.ok(currentLabel, 'sort label follows the current search props after browser navigation');
const choose = label => tree.root.findAllByType('div').find(node =>
  typeof node.props.onClick === 'function' && node.children.includes(label));
act(() => choose('Price: High to Low').props.onClick());
assert.equal(searches.length, 0, 'selecting the current sort is a no-op');
act(() => choose('Price: Low to High').props.onClick());
assert.equal(searches.length, 1, 'one user sort change triggers one search state update');
assert.deepEqual(JSON.parse(JSON.stringify(searches[0])), {
  keyword: 'iPhone', brand: 'Apple', sortType: 'PRICE_ASC',
});
assert.equal(searches[0].page, undefined, 'a different sort resets pagination');
assert.deepEqual(pageChanges, [0]);
assert.equal(routes.length, 1);
assert.equal(routes[0][0].query.sortType, 'priceAsc');
assert.equal(routes[0][0].query.keyword, 'iPhone');
assert.equal(routes[0][0].query.brand, 'Apple');
assert.equal(routes[0][0].query.page, undefined);
assert.equal(routes[0][2].shallow, true);
assert.equal(router.query.page, '3', 'sorting does not mutate Next router.query');
act(() => tree.unmount());
console.log('Search render regression passed: stable mount/remount, URL state, same-sort no-op, one event update, pagination and filters.');

import test from 'node:test';
import assert from 'node:assert/strict';
import {totals, csv, escapeHtml, validState} from '../showcase/domain.js';
import {customers, products, initialOrders} from '../showcase/demo-data.js';

test('integer-cent totals include the demonstration tax once',()=>{
  assert.deepEqual(totals([{quantity:3,price:1800},{quantity:2,price:4200}]),{subtotal:13800,tax:2484,total:16284});
});
test('empty, fractional, negative, infinite and excessive quantities are rejected',()=>{
  assert.throws(()=>totals([]));
  for(const quantity of [0,-1,1.5,NaN,Infinity,101]) assert.throws(()=>totals([{quantity,price:100}]));
  for(const price of [0,-1,Infinity,NaN,1.1,1000001]) assert.throws(()=>totals([{quantity:1,price}]));
});
test('untrusted text is escaped before HTML insertion',()=>{
  assert.equal(escapeHtml('<img src=x onerror="alert(1)">'), '&lt;img src=x onerror=&quot;alert(1)&quot;&gt;');
});
test('CSV cells cannot start spreadsheet formulas and quotes are escaped',()=>{
  assert.match(csv([['=1+1',' +cmd','@SUM(1)','-5','a"b']]),/"'=1\+1","' \+cmd","'@SUM\(1\)","'-5","a""b"/);
});
test('only recognized synthetic identities, products and statuses can be restored',()=>{
  const state={version:1,orders:initialOrders()};
  assert.equal(validState(state,{customers,products}),true);
  for(const patch of [{customer:999},{status:'<script>'},{id:'<img>'},{lines:[{product:1,quantity:-1,price:1}]}]) {
    const bad=structuredClone(state);Object.assign(bad.orders[0],patch);assert.equal(validState(bad,{customers,products}),false);
  }
  assert.equal(validState({version:1,orders:Array(201).fill(state.orders[0])},{customers,products}),false);
});

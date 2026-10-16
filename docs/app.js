import {customers, products, initialOrders} from './demo-data.js';
import {money, totals, escapeHtml as esc, csv, validState} from './domain.js';

const STORE = 'nexo-showcase-v1';
const data = {customers, products};
const $ = selector => document.querySelector(selector);
const icon = (name, extra='') => `<img src="assets/icons/${name}.svg" alt="" ${extra}>`;
const customer = id => customers.find(c=>c.id===id);
const product = id => products.find(p=>p.id===id);
const amount = cents => `<span data-amount>${money(cents)}</span>`;
const badge = state => `<span class="status ${esc(state)}">${esc(state)}</span>`;
const dateLabel = date => new Date(date+'T12:00:00').toLocaleDateString('es-PE',{day:'2-digit',month:'short'});
const identity = c => `<span class="table-client"><span class="client-avatar ${c.color}">${c.initials}</span>${esc(c.name)}</span>`;
let state = {version:1,orders:initialOrders()};
try { const saved=JSON.parse(localStorage.getItem(STORE)); if(validState(saved,data)) state=saved; } catch {}
let view='overview', query='', statusFilter='', page=1, toastTimer;
let draftLines=[];
const nav = [
  ['overview','Resumen','house','WORKSPACE'],['orders','Cotizaciones y pedidos','clipboard-list'],
  ['customers','Clientes','users'],['products','Productos','package','OPERACIONES'],
  ['inventory','Inventario','warehouse'],['documents','Documentos demo','receipt'],['reports','Reportes','chart-no-axes-combined']
];
$('#navigation').innerHTML=nav.map(([id,label,img,section])=>`${section?`<p>${section}</p>`:''}<a href="#${id}" data-view="${id}">${icon(img)}<span>${label}</span>${id==='orders'?'<small>16</small>':''}</a>`).join('');

function save() { try { localStorage.setItem(STORE,JSON.stringify(state)); } catch { toast('No se pudo conservar el cambio en este navegador.'); } }
function toast(message) { clearTimeout(toastTimer); $('#toast').textContent=message; $('#toast').hidden=false; toastTimer=setTimeout(()=>$('#toast').hidden=true,3500); }
function dialog(title,body) { $('#modal-title').textContent=title; $('#modal-content').innerHTML=body; $('#modal').showModal(); }
function closeDialog() { $('#modal').close(); }
function menu(open) { document.body.classList.toggle('menu-open',open); $('#scrim').hidden=!open; $('#menu').setAttribute('aria-expanded',String(open)); }
function button(action,label,img='plus',primary=false) { return `<button class="button${primary?' primary':''}" data-action="${action}">${icon(img)}${label}</button>`; }
function heading(title,description,actions='') { return `<section class="heading"><div><p class="eyebrow">NEXO WORKSPACE</p><h1>${title}</h1><p>${description}</p></div><div class="heading-actions">${actions}</div></section>`; }
function metrics() {
  const active=state.orders.filter(o=>o.status!=='Cancelado');
  const sales=active.reduce((sum,o)=>sum+totals(o.lines).total,0);
  return `<section class="metrics" aria-label="Indicadores del espacio">${[
    ['Valor de cotizaciones',amount(sales),'wallet',`${active.length} operaciones de ejemplo`],
    ['Pedidos en curso',active.filter(o=>['Confirmado','Por despachar'].includes(o.status)).length,'shopping-cart','Seguimiento comercial'],
    ['Clientes activos',customers.length,'users','Cartera de demostracion'],
    ['Productos disponibles',products.length,'package','1 producto con stock bajo']
  ].map(([label,value,img,note],i)=>`<div class="metric"><div class="metric-label">${label}</div>${icon(img,'class="metric-icon"')}<div class="metric-value">${value}</div><div class="metric-bottom">${i===0?'<span class="trend">PEN</span>':''}${note}</div></div>`).join('')}</section>`;
}
function chart() {
  const active=state.orders.filter(o=>o.status!=='Cancelado');
  const weeks=Array.from({length:12},(_,i)=>active.filter((_,index)=>index%12===i).reduce((sum,o)=>sum+totals(o.lines).total,0));
  const max=Math.max(...weeks,1);
  return `<div class="chart-section"><div class="section-heading"><h2>Actividad comercial</h2><small>Datos de demostracion</small></div><div class="chart-total">${amount(weeks.reduce((a,b)=>a+b,0))}</div><div class="legend"><span></span>Valor de cotizaciones por grupo</div><div class="chart" role="img" aria-label="Comparacion del valor de los pedidos de demostracion">${weeks.map((n,i)=>`<div class="chart-column${i===11?' current':''}" title="Grupo ${i+1}: ${money(n)}"><i class="level-${Math.max(10,Math.ceil(n/max*10)*10)}"></i><span>${String(i+1).padStart(2,'0')}</span></div>`).join('')}</div><div class="chart-caption"></div></div>`;
}
function activity() {
  return `<aside class="activity-section"><div class="section-heading"><h2>Ultimos movimientos</h2><small>Workspace</small></div>${state.orders.slice(0,4).map((o,i)=>`<div class="activity"><span class="activity-symbol">${icon(['file-check','users','package','clipboard-list'][i])}</span><div><p><b>${esc(o.id)}</b><br>${esc(customer(o.customer).name)}</p><small>${esc(o.status)} · ${dateLabel(o.date)}</small></div></div>`).join('')}</aside>`;
}
function toolbar(placeholder,options=[]) {
  return `<div class="table-toolbar"><label class="search">${icon('search')}<input id="search" type="search" aria-label="Buscar" placeholder="${placeholder}" value="${esc(query)}"></label>${options.length?`<select id="filter" aria-label="Filtrar por estado"><option value="">Todos los estados</option>${options.map(s=>`<option${statusFilter===s?' selected':''}>${s}</option>`).join('')}</select>`:''}<span class="table-count" id="result-count"></span></div>`;
}
function empty() { return `<div class="empty">${icon('search')}No se encontraron resultados.</div>`; }
function orderRows(orders) {
  return orders.map(o=>`<tr><td><button class="table-id text-button" data-order="${o.id}">${o.id}</button></td><td>${identity(customer(o.customer))}</td><td>${dateLabel(o.date)}</td><td>${badge(o.status)}</td><td class="num">${amount(totals(o.lines).total)}</td><td class="num"><button class="icon-button" data-order="${o.id}" title="Ver pedido ${o.id}" aria-label="Ver pedido ${o.id}">${icon('arrow-up-right')}</button></td></tr>`).join('');
}
function orderTable(orders,compact=false) {
  const shown=compact?orders.slice(0,5):orders.slice((page-1)*8,page*8);
  return `${orders.length?`<div class="table-scroll"><table><thead><tr><th>Referencia</th><th>Cliente</th><th>Fecha</th><th>Estado</th><th class="num">Importe total</th><th></th></tr></thead><tbody>${orderRows(shown)}</tbody></table></div>`:empty()}${compact?'':`<div class="table-footer"><span>${orders.length?`${(page-1)*8+1}–${Math.min(page*8,orders.length)} de ${orders.length} pedidos`:'Sin resultados'}</span><div class="pagination"><button class="icon-button" data-page="-1" title="Anterior" aria-label="Pagina anterior" ${page<=1?'disabled':''}>${icon('chevron-left')}</button><span>${page}</span><button class="icon-button" data-page="1" title="Siguiente" aria-label="Pagina siguiente" ${page*8>=orders.length?'disabled':''}>${icon('chevron-right')}</button></div></div>`}`;
}
function filteredOrders() { const q=query.toLowerCase(); return state.orders.filter(o=>(!statusFilter||o.status===statusFilter)&&`${o.id} ${customer(o.customer).name}`.toLowerCase().includes(q)); }
function overview() {
  return heading('Resumen de operaciones','Actividad comercial del espacio de demostracion.',`<span class="period">Octubre 2026</span>${button('export','Exportar resumen','download')}${button('new','Nueva cotizacion','plus',true)}`)+metrics()+`<div class="overview-grid">${chart()}${activity()}</div><section class="table-section"><div class="section-heading"><h2>Pedidos recientes</h2><a class="section-link" href="#orders">Ver todos los pedidos ${icon('arrow-up-right')}</a></div>${orderTable(state.orders,true)}</section>`;
}
function ordersView() { return heading('Cotizaciones y pedidos','Del primer acuerdo al siguiente despacho.',`${button('export','Exportar','download')}${button('new','Nueva cotizacion','plus',true)}`)+toolbar('Buscar referencia o cliente',['Borrador','Cotizado','Confirmado','Por despachar','Cancelado'])+'<div id="results"></div>'; }
function customersView() { return heading('Clientes','Una cartera ficticia, lista para explorar.',button('export-customers','Exportar clientes','download'))+toolbar('Buscar cliente, ciudad o sector')+'<div id="results"></div>'; }
function productsView(inventory=false) { return heading(inventory?'Inventario':'Productos',inventory?'Disponibilidad y alertas del catalogo de ejemplo.':'Un catalogo sencillo. Cada detalle en su lugar.',button('export-products','Exportar catalogo','download'))+toolbar('Buscar producto o categoria')+'<div id="results"></div>'; }
function documentsView() { return heading('Documentos de demostracion','Representaciones de ejemplo. No son comprobantes fiscales.')+`<div class="notice">Sin firma digital, sin CDR y sin envio a SUNAT. Cada documento esta marcado como demostracion.</div><br>`+toolbar('Buscar documento o cliente')+'<div id="results"></div>'; }
function reportsView() {
  const active=state.orders.filter(o=>o.status!=='Cancelado');
  const total=active.reduce((sum,o)=>sum+totals(o.lines).total,0);
  return heading('Reportes','Una lectura clara del escenario de demostracion.',button('export','Descargar CSV','download'))+metrics()+`<div class="report-summary">${chart()}<section class="report-section"><div class="section-heading"><h2>Distribucion de pedidos</h2><small>${active.length} activos</small></div>${['Borrador','Cotizado','Confirmado','Por despachar'].map(s=>`<div class="report-row"><span>${badge(s)}</span><strong>${active.filter(o=>o.status===s).length}</strong></div>`).join('')}<div class="report-row"><span>Valor total</span><strong>${amount(total)}</strong></div></section></div>`;
}
function renderResults() {
  const q=query.toLowerCase(); let count=0, html='';
  if(view==='orders'){const found=filteredOrders();count=found.length;html=orderTable(found);}
  if(view==='customers') {
    const found=customers.filter(c=>`${c.name} ${c.city} ${c.sector}`.toLowerCase().includes(q));count=found.length;
    html=found.length?`<div class="table-scroll"><table><thead><tr><th>Cliente</th><th>Referencia demo</th><th>Sector</th><th>Ubicacion</th><th>Estado</th><th></th></tr></thead><tbody>${found.map(c=>`<tr><td><span class="table-client"><span class="client-avatar ${c.color}">${c.initials}</span><span class="customer-identity"><b>${c.name}</b><small>${c.email}</small></span></span></td><td>DEMO-${String(c.id).padStart(3,'0')}</td><td>${c.sector}</td><td>${c.city}</td><td>${badge('Activo')}</td><td><button class="icon-button" data-customer="${c.id}" title="Ver ${c.name}" aria-label="Ver ${c.name}">${icon('arrow-up-right')}</button></td></tr>`).join('')}</tbody></table></div>`:empty();
  }
  if(['products','inventory'].includes(view)) {
    const found=products.filter(p=>`${p.name} ${p.sku} ${p.category}`.toLowerCase().includes(q));count=found.length;
    html=view==='products'?`<div class="products">${found.map(p=>`<article class="product"><div class="product-image ${p.tone}">${icon(p.icon)}</div><div class="product-body"><div class="product-sku">${p.sku}</div><h3>${p.name}</h3><p class="category">${p.category} · Unidad</p><div class="product-bottom"><strong>${amount(p.price)}</strong><span class="stock-label${p.stock<10?' low':''}">${p.stock} en stock</span></div></div></article>`).join('')}</div>`:
      `<div class="table-scroll"><table><thead><tr><th>SKU</th><th>Producto</th><th>Categoria</th><th>Disponibilidad</th><th>Estado</th><th class="num">Valor unitario</th></tr></thead><tbody>${found.map(p=>`<tr><td class="table-id">${p.sku}</td><td>${p.name}</td><td>${p.category}</td><td><span class="inventory-fill"><span class="stock-track"><i class="level-${Math.min(100,Math.ceil(p.stock/20)*10)}"></i></span>${p.stock} unidades</span></td><td>${badge(p.stock<10?'Por reponer':'Activo')}</td><td class="num">${amount(p.price)}</td></tr>`).join('')}</tbody></table></div>`;
    if(!found.length)html=empty();
  }
  if(view==='documents') {
    const found=state.orders.filter(o=>`${o.id} ${customer(o.customer).name}`.toLowerCase().includes(q));count=found.length;
    html=`<div class="table-scroll"><table><thead><tr><th>Documento</th><th>Cliente</th><th>Fecha</th><th>Entorno</th><th class="num">Importe</th><th></th></tr></thead><tbody>${found.map(o=>`<tr><td class="table-id">DEMO-${o.id.slice(4)}</td><td>${identity(customer(o.customer))}</td><td>${dateLabel(o.date)}</td><td><span class="status Cotizado">Simulado</span></td><td class="num">${amount(totals(o.lines).total)}</td><td><button class="icon-button" data-order="${o.id}" title="Ver documento" aria-label="Ver documento DEMO-${o.id.slice(4)}">${icon('file-down')}</button></td></tr>`).join('')}</tbody></table></div>`;
  }
  $('#results').innerHTML=html;$('#result-count').textContent=`${count} registros`;
}
function render() {
  $('#breadcrumb-title').textContent=nav.find(n=>n[0]===view)[1];
  document.querySelectorAll('[data-view]').forEach(a=>{a.classList.toggle('active',a.dataset.view===view);if(a.dataset.view===view)a.setAttribute('aria-current','page');else a.removeAttribute('aria-current');});
  $('#navigation small').textContent=state.orders.length;
  $('#main').innerHTML=({overview,orders:ordersView,customers:customersView,products:()=>productsView(),inventory:()=>productsView(true),documents:documentsView,reports:reportsView})[view]();
  if($('#results'))renderResults();
}
function navigate() { const requested=location.hash.slice(1);view=nav.some(n=>n[0]===requested)?requested:'overview';query='';statusFilter='';page=1;menu(false);render();window.scrollTo({top:0}); }
function totalBlock(lines) { const t=totals(lines);return `<div class="totals"><span>Subtotal sin IGV</span><span>${money(t.subtotal)}</span><span>IGV de ejemplo (18%)</span><span>${money(t.tax)}</span><strong>Total</strong><strong>${money(t.total)}</strong></div>`; }
function viewOrder(id) {
  const o=state.orders.find(order=>order.id===id);if(!o)return;
  dialog(o.id,`<article class="print-document"><p class="eyebrow">NEXO ERP / DOCUMENTO DE DEMOSTRACION</p><h2>${o.id}</h2><br><div class="detail-summary"><div><small>Cliente ficticio</small>${esc(customer(o.customer).name)}</div><div><small>Estado</small>${badge(o.status)}</div></div><div class="table-scroll"><table><thead><tr><th>Producto</th><th class="num">Cant.</th><th class="num">Valor unit.</th><th class="num">Subtotal</th></tr></thead><tbody>${o.lines.map(line=>`<tr><td>${product(line.product).name}</td><td class="num">${line.quantity}</td><td class="num">${money(line.price)}</td><td class="num">${money(line.price*line.quantity)}</td></tr>`).join('')}</tbody></table></div>${totalBlock(o.lines)}<hr class="form-divider"><p class="notice">DATOS FICTICIOS · SIN VALIDEZ FISCAL<br>Este documento no se envia a SUNAT ni representa una venta real.</p></article><div class="form-actions">${o.status!=='Cancelado'?`<button class="button danger" data-cancel="${id}">${icon('ban')}Cancelar ejemplo</button>`:''}${o.status==='Borrador'?`<button class="button primary" data-confirm="${id}">${icon('check')}Confirmar ejemplo</button>`:''}<button class="button" data-action="print">${icon('printer')}Imprimir / PDF</button></div>`);
}
function viewCustomer(id) {
  const c=customer(id);if(!c)return;
  dialog(c.name,`<div class="detail-summary"><div><small>Sector</small>${c.sector}</div><div><small>Ciudad</small>${c.city}</div><div><small>Referencia ficticia</small>DEMO-${String(id).padStart(3,'0')}</div><div><small>Contacto de ejemplo</small>${c.contact}</div><div class="span-2"><small>Correo no operativo</small>${c.email}</div></div><div class="notice">Identidad de demostracion. No corresponde a una empresa real.</div>`);
}
function newOrder() {
  if(state.orders.length>=200){toast('Limite de la demo alcanzado. Puedes restablecer los ejemplos.');return;}
  draftLines=[{product:1,quantity:1,price:products[0].price}];
  dialog('Nueva cotizacion',`<form id="order-form"><div class="form-grid"><label>Cliente ficticio<select name="customer" required>${customers.map(c=>`<option value="${c.id}">${c.name}</option>`).join('')}</select></label><label>Fecha de ejemplo<input name="date" type="date" value="2026-10-16" min="2020-01-01" max="2035-12-31" required></label></div><hr class="form-divider"><div class="section-heading"><h3>Productos</h3><button type="button" class="button" data-action="add-line">${icon('plus')}Agregar linea</button></div><div id="draft-lines"></div><div id="draft-total"></div><p class="error" id="form-error" role="alert"></p><div class="form-actions"><button type="button" class="button" data-action="close">Cancelar</button><button type="submit" class="button primary">Guardar cotizacion demo</button></div></form>`);
  renderDraft();
}
function renderDraft() {
  $('#draft-lines').innerHTML=draftLines.map((line,i)=>`<div class="line-editor"><label>Producto<select data-line="${i}" data-field="product">${products.map(p=>`<option value="${p.id}"${p.id===line.product?' selected':''}>${p.name} · ${money(p.price)}</option>`).join('')}</select></label><label>Cantidad<input type="number" data-line="${i}" data-field="quantity" value="${line.quantity}" min="1" max="100" step="1" required></label><button class="icon-button" type="button" data-remove="${i}" title="Quitar linea" aria-label="Quitar linea ${i+1}" ${draftLines.length===1?'disabled':''}>${icon('trash-2')}</button></div>`).join('');
  $('#draft-total').innerHTML=totalBlock(draftLines);
}
function download(name,rows) {
  const blob=new Blob([csv(rows)],{type:'text/csv;charset=utf-8;'});const url=URL.createObjectURL(blob);
  const link=document.createElement('a');link.href=url;link.download=name;link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);toast('CSV de demostracion descargado.');
}
document.addEventListener('click',event=>{
  const target=event.target.closest('button');if(!target)return;
  if(target.dataset.order)viewOrder(target.dataset.order);
  if(target.dataset.customer)viewCustomer(Number(target.dataset.customer));
  if(target.dataset.page){page+=Number(target.dataset.page);renderResults();}
  if(target.dataset.remove){draftLines.splice(Number(target.dataset.remove),1);renderDraft();}
  if(target.dataset.cancel){const o=state.orders.find(o=>o.id===target.dataset.cancel);if(o)o.status='Cancelado';save();closeDialog();render();toast('Ejemplo cancelado solo en este navegador.');}
  if(target.dataset.confirm){const o=state.orders.find(o=>o.id===target.dataset.confirm);if(o)o.status='Confirmado';save();closeDialog();render();toast('Pedido de ejemplo confirmado.');}
  const action=target.dataset.action;
  if(action==='new')newOrder();
  if(action==='close')closeDialog();
  if(action==='print')window.print();
  if(action==='add-line'&&draftLines.length<30){draftLines.push({product:1,quantity:1,price:products[0].price});renderDraft();}
  if(action==='export')download('nexo-pedidos-demo.csv',[['Referencia','Cliente ficticio','Fecha','Estado','Total PEN'],...filteredOrders().map(o=>[o.id,customer(o.customer).name,o.date,o.status,(totals(o.lines).total/100).toFixed(2)])]);
  if(action==='export-customers')download('nexo-clientes-demo.csv',[['Referencia','Nombre ficticio','Sector','Ciudad'],...customers.map(c=>[`DEMO-${c.id}`,c.name,c.sector,c.city])]);
  if(action==='export-products')download('nexo-catalogo-demo.csv',[['SKU','Producto ficticio','Precio sin IGV','Stock de ejemplo'],...products.map(p=>[p.sku,p.name,(p.price/100).toFixed(2),p.stock])]);
  if(action==='reset-confirm'){state={version:1,orders:initialOrders()};save();closeDialog();query='';page=1;render();toast('Demo restablecida.');}
});
document.addEventListener('input',event=>{
  if(event.target.id==='search'){query=event.target.value.slice(0,120);page=1;renderResults();}
  if(event.target.dataset.field==='quantity') {
    const line=draftLines[Number(event.target.dataset.line)];if(!line)return;
    line.quantity=Number(event.target.value);
    try{$('#draft-total').innerHTML=totalBlock(draftLines);$('#form-error').textContent='';}
    catch(error){$('#form-error').textContent=error.message;}
  }
});
document.addEventListener('change',event=>{
  const input=event.target;
  if(input.id==='filter'){statusFilter=input.value;page=1;renderResults();}
  if(input.dataset.line!==undefined){const line=draftLines[Number(input.dataset.line)];if(!line)return;
    if(input.dataset.field==='product'){line.product=Number(input.value);line.price=product(line.product).price;}
    if(input.dataset.field==='quantity')line.quantity=Number(input.value);
    try{$('#draft-total').innerHTML=totalBlock(draftLines);$('#form-error').textContent='';}catch(error){$('#form-error').textContent=error.message;}
  }
});
document.addEventListener('submit',event=>{
  event.preventDefault();if(event.target.id!=='order-form')return;
  try{totals(draftLines);const form=new FormData(event.target);const id=`COT-${Math.max(1048,...state.orders.map(o=>Number(o.id.slice(4))))+1}`;
    const order={id,customer:Number(form.get('customer')),date:String(form.get('date')),status:'Borrador',lines:structuredClone(draftLines)};
    if(!validState({version:1,orders:[order]},data))throw new Error('Revisa los datos del ejemplo.');
    state.orders.unshift(order);save();closeDialog();query='';statusFilter='';page=1;if(view==='overview'||view==='orders')render();else location.hash='orders';toast('Cotizacion demo guardada en este navegador.');
  }catch(error){$('#form-error').textContent=error.message;}
});
$('#menu').addEventListener('click',()=>menu(!document.body.classList.contains('menu-open')));
$('#scrim').addEventListener('click',()=>menu(false));
$('#close-modal').addEventListener('click',closeDialog);
$('#privacy').addEventListener('click',()=>{const on=document.body.classList.toggle('privacy-on');$('#privacy').setAttribute('aria-pressed',String(on));$('#privacy').setAttribute('aria-label',on?'Mostrar importes':'Ocultar importes');$('#privacy').title=on?'Mostrar importes':'Ocultar importes';$('#privacy').innerHTML=icon(on?'eye-off':'eye');});
$('#reset').addEventListener('click',()=>dialog('Restablecer demostracion',`<p>Se eliminaran los cambios de esta demo guardados en este navegador y se recuperaran los ejemplos iniciales.</p><div class="form-actions">${button('close','Conservar cambios','x')}${button('reset-confirm','Restablecer','rotate-ccw',true)}</div>`));
$('#workspace-info').addEventListener('click',()=>dialog('Nexo Demo',`<div class="notice">Edicion publica de portfolio. Todos los nombres, productos e importes son ficticios.<br>No se solicitan credenciales, no hay consultas externas y los cambios solo se guardan en este navegador.</div>`));
window.addEventListener('hashchange',navigate);
window.addEventListener('storage',event=>{if(event.key===STORE){try{const saved=JSON.parse(event.newValue);if(validState(saved,data)){state=saved;render();}}catch{}}});
navigate();

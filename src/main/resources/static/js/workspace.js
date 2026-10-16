(() => {
  'use strict';
  const normalize = value => String(value || '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase().trim();
  const matches = (value, query) => normalize(query).split(/\s+/).every(term => normalize(value).includes(term));
  const icon = name => { const img = document.createElement('img'); img.src = `/icons/${name}.svg`; img.alt = ''; img.className = 'nc-icon'; img.setAttribute('aria-hidden','true'); return img; };
  const button = (label, name) => { const b=document.createElement('button'); b.type='button'; b.className='icon-button'; b.title=label; b.setAttribute('aria-label',label); b.append(icon(name)); return b; };
  const element = (tag, className, text) => { const n=document.createElement(tag); if(className)n.className=className; if(text)n.textContent=text; return n; };

  const sidebar=document.querySelector('.sidebar__nav');
  if(sidebar){
    const key='nexo.sidebar.scroll';
    const saved=Number(sessionStorage.getItem(key));
    requestAnimationFrame(()=>{if(Number.isFinite(saved)&&saved>0)sidebar.scrollTop=saved;else sidebar.querySelector('.nav-item--active')?.scrollIntoView({block:'nearest'});});
    let scrollTimer;sidebar.addEventListener('scroll',()=>{clearTimeout(scrollTimer);scrollTimer=setTimeout(()=>sessionStorage.setItem(key,String(sidebar.scrollTop)),80);},{passive:true});
    sidebar.querySelectorAll('a').forEach(link=>link.addEventListener('click',()=>sessionStorage.setItem(key,String(sidebar.scrollTop))));
  }

  const privateLabel=/\b(ruc|dni|documento|cliente|proveedor|producto|contacto|correo|telefono|direccion|domicilio|usuario|transportista|conductor|placa|licencia|precio|subtotal|total|saldo|credito|stock|existencia|hash|huella|certificado|clave|secreto)\b/i;
  document.querySelectorAll('input[name],textarea[name]').forEach(field=>{if(privateLabel.test(`${field.name} ${field.closest('label')?.textContent||''}`))field.dataset.private='';});
  document.querySelectorAll('dl').forEach(list=>list.querySelectorAll('dt').forEach(term=>{if(privateLabel.test(term.textContent))term.nextElementSibling?.setAttribute('data-private','');}));
  document.querySelectorAll('table').forEach(table=>{
    const headers=[...(table.tHead?.rows[0]?.cells||[])];
    headers.forEach((header,index)=>{if(!privateLabel.test(header.textContent))return;[...(table.tBodies||[])].flatMap(body=>[...body.rows]).forEach(row=>row.cells[index]?.setAttribute('data-private',''));});
  });
  document.querySelectorAll('.metric-card strong,.compact-metrics strong,.detail-metrics strong,.commercial-metrics strong').forEach(value=>value.setAttribute('data-private',''));
  const privacyButton=document.querySelector('[data-privacy-toggle]');
  const setPrivacy=hidden=>{
    document.body.classList.toggle('privacy-mode',hidden);localStorage.setItem('nexo.privacy',hidden?'hidden':'visible');
    if(!privacyButton)return;privacyButton.setAttribute('aria-pressed',String(hidden));privacyButton.title=hidden?'Mostrar datos protegidos':'Ocultar datos protegidos';
    privacyButton.querySelector('span').textContent=hidden?'Datos ocultos':'Ocultar datos';privacyButton.querySelector('img').src=hidden?'/icons/eye-off.svg':'/icons/eye.svg';
  };
  if(privacyButton){setPrivacy(localStorage.getItem('nexo.privacy')!=='visible');privacyButton.addEventListener('click',()=>setPrivacy(!document.body.classList.contains('privacy-mode')));}

  document.querySelectorAll('[data-transport-form]').forEach(form=>{
    const profile=form.querySelector('[data-transport-profile]');
    const mapping={mode:'mode',carrierRuc:'carrierRuc',carrierName:'carrierName',driverName:'driverName',driverDocument:'driverDocument',driverLicense:'driverLicense',vehiclePlate:'vehiclePlate',vehicleBrand:'vehicleBrand'};
    profile?.addEventListener('change',()=>{const option=profile.selectedOptions[0];if(!option?.value)return;Object.entries(mapping).forEach(([data,name])=>{const field=form.elements.namedItem(name);if(field)field.value=option.dataset[data]||'';});});
    const save=form.querySelector('[data-save-transport]'),name=form.querySelector('[data-transport-name]');
    const reveal=()=>{if(name)name.hidden=!save?.checked;};save?.addEventListener('change',reveal);reveal();
  });
  window.ncMoney=(input,value=input.value)=>{
    input.dataset.preciseValue=value;
    input.value=value!==''&&Number.isFinite(Number(value))?Number(value).toFixed(2):'';
    input.dataset.displayValue=input.value;
  };
  document.addEventListener('formdata',event=>{
    const names=new Set([...event.target.querySelectorAll('[data-precise-value][name]')].map(i=>i.name));
    for(const name of names){event.formData.delete(name);event.target.querySelectorAll(`[name="${name}"]`).forEach(i=>event.formData.append(name,i.value===i.dataset.displayValue?i.dataset.preciseValue:i.value));}
  });
  document.addEventListener('focusout',event=>{if(event.target.matches('input[data-precise-value]')){const i=event.target;window.ncMoney(i,i.value===i.dataset.displayValue?i.dataset.preciseValue:i.value);}});

  const navIcons=['house','clipboard-list','receipt','wallet','file-check','file-down','archive','receipt','plus','shopping-cart','warehouse','package','layers','chart-no-axes-combined','sliders-horizontal','package','users','building-2','chart-column','settings-2'];
  const navByPath={'/':'house','/modulos/comercial':'clipboard-list','/modulos/comercial/tarifas':'receipt','/modulos/comercial/gestion':'wallet','/modulos/comprobantes/emitidos':'file-check','/modulos/comprobantes/recibidos':'file-down','/modulos/comprobantes':'archive','/modulos/ventas':'receipt','/modulos/ventas/nueva':'plus','/modulos/compras':'shopping-cart','/modulos/inventario':'warehouse','/modulos/inventario/existencias':'package','/modulos/inventario/lotes':'layers','/modulos/inventario/kardex':'chart-no-axes-combined','/modulos/inventario/ajustes':'sliders-horizontal','/modulos/inventario/productos':'package','/modulos/clientes':'users','/modulos/proveedores':'building-2','/modulos/reportes':'chart-column','/modulos/configuracion':'settings-2'};
  document.querySelectorAll('.nav-item').forEach(link=>{link.querySelector('svg')?.remove();link.prepend(icon(navByPath[link.getAttribute('href')]||navIcons[0]));});
  document.querySelectorAll('[data-nav-open],[data-nav-close].icon-button,[data-close-catalog],[data-product-picker-close]').forEach(b=>b.replaceChildren(icon(b.hasAttribute('data-nav-open')?'menu':'x')));
  document.querySelectorAll('td:has([data-edit-product])').forEach(cell=>cell.classList.add('table-actions'));
  document.querySelectorAll('.button,.table-button').forEach(b=>{
    const label=normalize(b.textContent); let name;
    if(/^(nuevo|nueva|agregar|crear)/.test(label)) name='plus';
    else if(label==='buscar'||label==='filtrar')name='search';
    else if(label==='editar')name='pencil';
    else if(label==='quitar')name='trash-2';
    else if(label.includes('imprimir'))name='printer';
    else if(label.includes('descargar')||label.includes('exportar'))name='download';
    if(name){
      if(b.closest('.table-actions') && (label==='editar'||label==='quitar')){b.title=b.textContent.trim();b.setAttribute('aria-label',b.title);b.replaceChildren(icon(name));}
      else b.prepend(icon(name));
    }
  });
  document.querySelectorAll('.table-actions form .icon-action').forEach(b=>{
    const label=b.getAttribute('aria-label')||b.title||'Cambiar estado';
    b.title=label;b.setAttribute('aria-label',label);b.replaceChildren(icon(normalize(label).includes('desactivar')?'ban':'rotate-ccw'));
    b.form.dataset.confirm='Se cambiara la disponibilidad de este registro. Los documentos anteriores se conservaran.';
  });

  document.querySelectorAll('input[type=number]').forEach(input=>{
    const money=/^(netPrice|salePrice|purchasePrice|unitCost|amount|limit|exchangeRate)$/.test(input.name);
    if(money && input.name!=='exchangeRate') {
      window.ncMoney(input);
      const wrap=element('span','money-field');input.before(wrap);wrap.append(element('span','','S/'),input);
    }
    if(input.name==='quantity' && input.step==='1') {
      const wrap=element('span','quantity-stepper'); const minus=button('Disminuir cantidad','minus'),plus=button('Aumentar cantidad','plus');
      input.before(wrap);wrap.append(minus,input,plus);
      minus.dataset.step='-1';plus.dataset.step='1';
    }
  });
  document.addEventListener('click',event=>{const step=event.target.closest('[data-step]');if(!step)return;const input=step.closest('.quantity-stepper').querySelector('input');if(step.dataset.step==='1')input.stepUp();else input.stepDown();input.dispatchEvent(new Event('input',{bubbles:true}));});

  // Native dialog supplies focus containment, Escape and focus restoration.
  document.querySelectorAll('select').forEach(select=>{
    const customer=select.name==='customerId' || select.name==='cliente';
    const supplier=select.name==='supplierId';
    if((select.options.length<12 && !customer && !supplier) || select.multiple || select.closest('dialog'))return;
    const trigger=element('button','picker-trigger');trigger.type='button';trigger.setAttribute('aria-haspopup','dialog');
    const title=[...(select.labels?.[0]?.childNodes||[])].filter(n=>n.nodeType===Node.TEXT_NODE).map(n=>n.textContent).join('').trim()||(customer?'Cliente':'Seleccionar');
    trigger.setAttribute('aria-label',title);const caption=element('span');trigger.append(caption,icon('search'));select.after(trigger);
    const dialog=element('dialog','selection-dialog');dialog.setAttribute('aria-label',title);
    const header=element('header'),close=button('Cerrar selector','x');header.append(element('h2','',customer?'Seleccionar cliente':title),close);
    const tools=element('div','selection-tools'),search=element('input');search.type='search';search.maxLength=120;search.autocomplete='off';search.placeholder=customer?'RUC, nombre, telefono o correo':'Buscar';search.setAttribute('aria-label',search.placeholder);tools.append(search);
    const results=element('div','selection-results'),footer=element('footer');footer.setAttribute('role','status');footer.setAttribute('aria-live','polite');
    dialog.append(header,tools,results,footer);document.body.append(dialog);select.classList.add('native-select-enhanced');select.tabIndex=-1;
    const update=()=>{caption.textContent=select.selectedOptions[0]?.textContent||'Seleccionar';};update();select.addEventListener('change',update);
    let request,sequence=0,timer;
    const choose=(value,address)=>{select.value=value;select.dispatchEvent(new Event('change',{bubbles:true}));if(address){const field=select.form?.querySelector('[name=address]');if(field&&!field.value)field.value=address;}dialog.close();};
    if(customer||supplier){dialog.dataset.entity=customer?'customer':'supplier';dialog.ncChoose=item=>{select.add(new Option(`${item.name} · ${item.document}`,String(item.id)));choose(String(item.id),item.address);};}
    const render=items=>{
      results.replaceChildren();for(const item of items){const option=element('button','selection-option');option.type='button';option.append(element('span','',item.document||''),element('strong','',item.name),element('small','',[item.phone,item.email,item.address].filter(Boolean).join(' · ')));option.addEventListener('click',()=>choose(String(item.id),item.address));results.append(option);}
      if(!items.length)results.append(element('p','empty-results','No se encontraron coincidencias.'));
      footer.textContent=`${items.length} resultados${items.length===50?' · Refina la busqueda':''}`;
    };
    const load=async()=>{
      const current=++sequence;request?.abort();
      if(customer && location.pathname.startsWith('/modulos/comercial')) {
        request=new AbortController();results.replaceChildren(element('div','loading-state','Buscando clientes'));footer.textContent='';
        try {const response=await fetch(`/modulos/comercial/clientes/buscar?q=${encodeURIComponent(search.value)}`,{signal:request.signal,headers:{Accept:'application/json'}});if(!response.ok||!response.headers.get('content-type')?.includes('json'))throw new Error();const items=await response.json();if(current!==sequence)return;
          // A newly-created, authorized customer may not be in the original option set.
          for(const item of items)if(![...select.options].some(o=>o.value===String(item.id))){select.add(new Option(`${item.name} · ${item.document}`,String(item.id)));}
          render(items);
        }catch(error){if(error.name!=='AbortError'&&current===sequence){results.replaceChildren(element('p','empty-results','No se pudo cargar. Cierra el selector e intenta nuevamente.'));footer.textContent='Consulta no disponible';}}
      }else render([...select.options].filter(o=>o.value&&matches(o.textContent,search.value)).map(o=>({id:o.value,name:o.textContent})));
    };
    trigger.addEventListener('click',()=>{search.value='';dialog.showModal();search.focus();load();});close.addEventListener('click',()=>dialog.close());dialog.addEventListener('close',()=>{request?.abort();clearTimeout(timer);++sequence;trigger.focus();});
    search.addEventListener('input',()=>{clearTimeout(timer);request?.abort();++sequence;results.replaceChildren(element('div','loading-state','Buscando'));timer=setTimeout(load,160);});
    search.addEventListener('keydown',event=>{if(event.key==='ArrowDown'){event.preventDefault();results.querySelector('button')?.focus();}if(event.key==='Enter'){event.preventDefault();results.querySelector('button')?.click();}});
    results.addEventListener('keydown',event=>{const options=[...results.querySelectorAll('button')],i=options.indexOf(document.activeElement);if(event.key==='ArrowDown'||event.key==='ArrowUp'){event.preventDefault();options[Math.max(0,Math.min(options.length-1,i+(event.key==='ArrowDown'?1:-1)))]?.focus();}});
    select.addEventListener('invalid',event=>{event.preventDefault();trigger.focus();trigger.setAttribute('aria-invalid','true');});
    select.addEventListener('change',()=>trigger.removeAttribute('aria-invalid'));
  });
  document.querySelector('select[name=cliente]')?.addEventListener('change',event=>{if(event.target.value)event.target.form.requestSubmit();});

  document.querySelectorAll('.data-panel table,.commercial-page .work-section> .responsive-table>table').forEach(table=>{
    if(table.closest('.data-panel')?.querySelector('.pagination'))return;
    if(table.querySelector('input[name=quantity]')||table.closest('dialog')||table.closest('.commercial-print'))return;
    const tbody=table.tBodies[0];if(!tbody)return;
    let rows=[...tbody.rows].filter(r=>!r.querySelector('.empty-results'));if(rows.length<2)return;
    let page=0,size=25,query='';
    const controls=element('div','table-controls'),info=element('span'),group=element('div'),prev=button('Pagina anterior','chevron-left'),next=button('Pagina siguiente','chevron-right'),sizes=element('select');sizes.setAttribute('aria-label','Filas por pagina');[10,25,50,100].forEach(n=>sizes.add(new Option(String(n),String(n),false,n===25)));group.append(sizes,prev,next);controls.append(info,group);table.closest('.responsive-table').after(controls);
    const existingSearch=table.closest('.data-panel')?.querySelector('[data-table-search]');
    const search=existingSearch||element('input','live-filter');search.type='search';
    if(!existingSearch){search.placeholder='Buscar en los resultados';controls.prepend(search);}
    search.setAttribute('aria-label',search.placeholder||'Buscar en los resultados');table.dataset.clientTable='true';
    const refresh=()=>{const filtered=rows.filter(r=>matches(r.textContent,query));page=Math.min(page,Math.max(0,Math.ceil(filtered.length/size)-1));rows.forEach(r=>r.hidden=true);filtered.slice(page*size,(page+1)*size).forEach(r=>r.hidden=false);info.textContent=`${filtered.length? page*size+1:0}-${Math.min((page+1)*size,filtered.length)} de ${filtered.length}`;prev.disabled=page===0;next.disabled=(page+1)*size>=filtered.length;};
    search.addEventListener('input',()=>{query=search.value;page=0;refresh();});prev.addEventListener('click',()=>{page--;refresh();});next.addEventListener('click',()=>{page++;refresh();});sizes.addEventListener('change',()=>{size=Number(sizes.value);page=0;refresh();});
    [...(table.tHead?.rows[0]?.cells||[])].forEach((th,index)=>{
      if(!th.textContent.trim()||rows.some(r=>!r.cells[index]||r.cells[index].colSpan>1||r.cells[index]?.querySelector('form,input,select,button')))return;
      const sort=element('button','table-sort',th.textContent);sort.type='button';sort.title=`Ordenar por ${th.textContent}`;sort.append(icon('chevrons-up-down'));th.replaceChildren(sort);let asc=true;
      sort.addEventListener('click',()=>{const cell=r=>r.cells[index]?.textContent.trim()||'';rows.sort((a,b)=>{const x=cell(a),y=cell(b),nx=x.replace(/^S\/\s*/, '').replace(/,/g,''),ny=y.replace(/^S\/\s*/, '').replace(/,/g,'');return (asc?1:-1)*(/^[-+]?\d+(\.\d+)?$/.test(nx)&&/^[-+]?\d+(\.\d+)?$/.test(ny)?Number(nx)-Number(ny):x.localeCompare(y,'es',{numeric:true,sensitivity:'base'}));});table.querySelectorAll('[aria-sort]').forEach(n=>n.removeAttribute('aria-sort'));th.setAttribute('aria-sort',asc?'ascending':'descending');asc=!asc;rows.forEach(r=>tbody.append(r));page=0;refresh();});
    });refresh();
  });

  // Table overflow containers clip absolute popovers; use the dialog top layer instead.
  document.querySelectorAll('details.row-popover').forEach(details=>{
    const content=details.querySelector(':scope > div'),summary=details.querySelector('summary');
    if(!content||!summary)return;
    const trigger=element('button','table-button',summary.textContent);trigger.type='button';trigger.setAttribute('aria-haspopup','dialog');
    const modal=element('dialog','entity-dialog user-access-dialog');modal.setAttribute('aria-label','Administrar usuario');
    const header=element('header'),close=button('Cerrar','x');
    header.append(element('h2','','Administrar usuario'),close);content.className='entity-body user-access-forms';
    modal.append(header,content);document.body.append(modal);details.replaceWith(trigger);
    trigger.addEventListener('click',()=>modal.showModal());close.addEventListener('click',()=>modal.close());
    modal.addEventListener('close',()=>trigger.focus());
  });

  const confirm=element('dialog','confirm-dialog');confirm.setAttribute('aria-labelledby','confirmation-title');const heading=element('h2','','Confirmar cambio');heading.id='confirmation-title';const header=element('header');header.append(heading);const message=element('p'),footer=element('footer'),cancel=element('button','button button--secondary','Volver'),accept=element('button','button button--primary','Confirmar');cancel.type=accept.type='button';footer.append(cancel,accept);confirm.append(header,message,footer);document.body.append(confirm);let pending;
  cancel.addEventListener('click',()=>confirm.close());accept.addEventListener('click',()=>{const task=pending;confirm.close();task.form.dataset.confirmed='true';task.form.requestSubmit(task.submitter);});
  document.querySelectorAll('form').forEach(form=>form.addEventListener('submit',event=>{
    if(form.getAttribute('method')?.toLowerCase()!=='post')return;
    if(form.dataset.confirm && form.dataset.confirmed!=='true'){event.preventDefault();pending={form,submitter:event.submitter};message.textContent=form.dataset.confirm;confirm.showModal();cancel.focus();return;}
    if(form.getAttribute('aria-busy')==='true'){event.preventDefault();return;}
    queueMicrotask(()=>{if(event.defaultPrevented)return;form.setAttribute('aria-busy','true');document.body.classList.add('is-submitting');});
  }));
  window.addEventListener('pageshow',()=>{document.querySelectorAll('form[aria-busy]').forEach(f=>f.removeAttribute('aria-busy'));document.body.classList.remove('is-submitting');});
  document.querySelectorAll('.notice').forEach(n=>{n.setAttribute('role',n.classList.contains('notice--error')?'alert':'status');});
  document.querySelectorAll('dialog').forEach(d=>d.addEventListener('click',event=>{if(event.target!==d)return;const r=d.getBoundingClientRect();if(event.clientX<r.left||event.clientX>r.right||event.clientY<r.top||event.clientY>r.bottom)d.close();}));
})();

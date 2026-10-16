(() => {
  const el=(tag,text,cls)=>{const e=document.createElement(tag);if(text)e.textContent=text;if(cls)e.className=cls;return e;};
  const button=(text,cls='button button--secondary')=>{const b=el('button',text,cls);b.type='button';return b;};
  const post=async(path,body)=>{
    const header=document.querySelector('meta[name=csrf-header]').content;
    const r=await fetch(path,{method:'POST',body,headers:{[header]:document.querySelector('meta[name=csrf-token]').content,Accept:'application/json'}});
    if(!r.headers.get('content-type')?.includes('json'))throw new Error('Sesion vencida o acceso no permitido. Vuelve a iniciar sesion.');
    const data=await r.json();if(!r.ok)throw new Error(data.message||'No fue posible completar la operacion.');return data;
  };
  const iconButton=(title,icon)=>{const b=button(null,'icon-button');b.title=title;b.setAttribute('aria-label',title);const img=el('img',null,'nc-icon');img.src=`/icons/${icon}.svg`;img.alt='';b.append(img);return b;};
  const dialog=(title)=>{const d=el('dialog',null,'entity-dialog');d.setAttribute('aria-label',title);const h=el('header');const close=iconButton('Cerrar','x');h.append(el('h2',title),close);close.onclick=()=>d.close();d.append(h);document.body.append(d);d.addEventListener('close',()=>d.remove());return d;};
  const labels={estado:'Estado del contribuyente',condicion:'Condicion de domicilio',departamento:'Departamento',provincia:'Provincia',distrito:'Distrito',tipo:'Tipo de contribuyente',actividad_economica:'Actividad economica',tipo_facturacion:'Facturacion',tipo_contabilidad:'Contabilidad',comercio_exterior:'Comercio exterior',es_agente_retencion:'Agente de retencion',es_buen_contribuyente:'Buen contribuyente',numero_trabajadores:'Trabajadores declarados',first_name:'Nombres',first_last_name:'Apellido paterno',second_last_name:'Apellido materno'};
  function lookup(form){
    const number=form.querySelector('[name=documentNumber]'),type=form.querySelector('[name=documentType]');if(!number||!type)return;
    const action=iconButton('Consultar RUC / DNI','search');action.classList.add('identity-lookup');const row=el('span',null,'identity-number');number.before(row);row.append(number,action);
    const status=el('p',null,'lookup-status');status.setAttribute('role','status');number.closest('label').append(status);
    action.onclick=async()=>{
      action.disabled=true;status.textContent='Consultando proveedor externo…';const doc=number.value.trim(),kind=type.value;
      try{
        const data=await post('/modulos/contexto/identidad',new URLSearchParams({documentType:kind,documentNumber:doc}));
        if(number.value.trim()!==doc||type.value!==kind){status.textContent='El documento cambio. Consulta nuevamente.';return;}
        status.textContent='Consulta recibida';const preview=dialog('Revisar datos consultados');
        const body=el('div',null,'entity-body');body.append(el('h3',data.legalName),el('p',`${data.documentNumber} · ${data.provider} · ${new Date(data.consultedAt).toLocaleString('es-PE')}`));
        if(data.address)body.append(el('p',data.address));const list=el('dl',null,'lookup-details');
        for(const [key,value] of Object.entries(data.details))list.append(el('dt',labels[key]||key),el('dd',value==='true'?'Si':value==='false'?'No':value));
        body.append(list,el('p','Datos de un proveedor externo, no certificacion de SUNAT/RENIEC. Revisa antes de aplicar. Se reemplazaran nombre, direccion y ubigeo cuando esten disponibles.','lookup-status'));
        const apply=button('Aplicar al formulario','button button--primary');apply.onclick=()=>{
          if(number.value.trim()!==doc||type.value!==kind){preview.close();status.textContent='El documento cambio; no se aplicaron datos.';return;}
          for(const key of ['legalName','tradeName','address','ubigeo']){const field=form.querySelector(`[name=${key}]`);if(field&&data[key])field.value=data[key];}
          preview.close();status.textContent=`Datos aplicados · ${data.provider}`;
        };body.append(apply);preview.append(body);preview.showModal();
      }catch(e){status.textContent=e.message;}finally{action.disabled=false;}
    };
  }
  document.querySelectorAll('form.entity-form').forEach(lookup);
  const selectors=[...document.querySelectorAll('dialog[data-entity]')];if(!selectors.length)return;
  fetch('/modulos/contexto/permisos',{headers:{Accept:'application/json'}}).then(r=>r.ok?r.json():{}).then(permissions=>{
    for(const selector of selectors){const entity=selector.dataset.entity;if(!permissions[entity])continue;
      const create=button(entity==='customer'?'Nuevo cliente':'Nuevo proveedor');selector.querySelector('header').append(create);
      create.onclick=()=>{
        const d=dialog(entity==='customer'?'Registrar cliente':'Registrar proveedor');const form=el('form',null,'entity-body');form.method='post';
        const grid=el('div',null,'form-grid');const typeLabel=el('label','Tipo de documento'),type=el('select');type.name='documentType';type.add(new Option('RUC','6'));type.add(new Option('DNI','1'));typeLabel.append(type);grid.append(typeLabel);
        for(const [name,label,max,required,inputType] of [['documentNumber','Numero de documento',20,true,'text'],['legalName','Nombre o razon social',220,true,'text'],['tradeName','Nombre comercial',220,false,'text'],['contactName','Contacto',160,false,'text'],['email','Correo',254,false,'email'],['phone','Telefono',25,false,'tel'],['address','Direccion fiscal',300,false,'text'],['ubigeo','Ubigeo',6,false,'text']]){
          const l=el('label',label),i=el('input');i.name=name;i.type=inputType;i.maxLength=max;i.required=required;if(name==='ubigeo')i.pattern='[0-9]{6}';if(name==='legalName'||name==='address')l.classList.add('field-span-2');l.append(i);grid.append(l);
        }
        const status=el('p',null,'lookup-status');status.setAttribute('role','alert');const save=button('Registrar y seleccionar','button button--primary');save.type='submit';
        const actions=el('div',null,'form-actions');const cancel=button('Cancelar');cancel.onclick=()=>d.close();actions.append(cancel,save);form.append(grid,status,actions);d.append(form);lookup(form);
        form.addEventListener('submit',async event=>{event.preventDefault();if(save.disabled)return;save.disabled=true;status.textContent='Registrando…';
          try{const item=await post(`/modulos/contexto/${entity}`,new FormData(form));d.close();selector.ncChoose(item);}catch(e){status.textContent=e.message;}finally{save.disabled=false;}
        });d.showModal();form.querySelector('[name=documentNumber]').focus();
      };
    }
  }).catch(()=>{});
})();

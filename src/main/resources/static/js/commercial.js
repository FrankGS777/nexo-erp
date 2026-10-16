(() => {
  const dialog = document.querySelector('#commercial-catalog');
  document.querySelector('[data-open-catalog]')?.addEventListener('click', () => {
    dialog.showModal(); document.querySelector('#catalog-query')?.focus();
  });
  document.querySelector('[data-close-catalog]')?.addEventListener('click', () => dialog.close());
  const input = document.querySelector('#catalog-query');
  const normalize = value => value.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase();
  function filter() {
    const term = normalize(input?.value || ''); let count = 0;
    document.querySelectorAll('[data-search]').forEach(item => {
      item.hidden = !term.split(/\s+/).every(word=>normalize(item.dataset.search).includes(word));
      if (!item.hidden) count++;
    });
    const counter = document.querySelector('#catalog-count');
    if (counter) counter.textContent = `${count} presentaciones`;
    const empty = document.querySelector('#catalog-empty');
    if (empty) empty.hidden = count !== 0;
  }
  input?.addEventListener('input', filter); filter();
  document.querySelectorAll('[data-edit-product]').forEach(button=>button.addEventListener('click',()=>{
    const item=[...dialog.querySelectorAll('[data-product-key]')].find(p=>p.dataset.productKey===button.dataset.editProduct);
    if(!item)return;
    input.value='';filter();
    item.querySelector('[name=quantity]').value=Number(button.dataset.quantity);
    window.ncMoney(item.querySelector('[name=netPrice]'),button.dataset.netPrice);
    dialog.showModal();item.scrollIntoView({block:'center'});item.querySelector('[name=quantity]').focus();
  }));
  input?.addEventListener('keydown',event=>{
    if(event.key==='ArrowDown'||event.key==='Enter') {
      event.preventDefault();dialog.querySelector('[data-search]:not([hidden]) input[name=quantity]')?.focus();
    }
  });
})();

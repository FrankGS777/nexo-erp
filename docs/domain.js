export const money = cents => new Intl.NumberFormat('es-PE', {style:'currency', currency:'PEN'}).format(cents / 100);
export const escapeHtml = value => String(value).replace(/[&<>"']/g, char => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[char]));
export function totals(lines) {
  if (!Array.isArray(lines) || !lines.length || lines.length > 30) throw new Error('Selecciona al menos un producto.');
  let subtotal = 0;
  for (const line of lines) {
    if (!Number.isSafeInteger(line.quantity) || line.quantity < 1 || line.quantity > 100) throw new Error('La cantidad debe estar entre 1 y 100.');
    if (!Number.isSafeInteger(line.price) || line.price < 1 || line.price > 1000000) throw new Error('Precio fuera del rango de demostracion.');
    subtotal += line.quantity * line.price;
  }
  const tax = Math.round(subtotal * 18 / 100);
  return {subtotal, tax, total:subtotal + tax};
}
export function csv(rows) {
  return '\uFEFF' + rows.map(row => row.map(value => {
    let text = String(value ?? '');
    if (/^[\s]*[=+@-]/.test(text)) text = "'" + text;
    return '"' + text.replace(/"/g,'""') + '"';
  }).join(',')).join('\r\n');
}
export function validState(state, data) {
  return state?.version === 1 && Array.isArray(state.orders) && state.orders.length <= 200 && state.orders.every(order =>
    typeof order.id === 'string' && /^COT-\d{4}$/.test(order.id) && data.customers.some(c=>c.id===order.customer) &&
    ['Borrador','Cotizado','Confirmado','Por despachar','Cancelado'].includes(order.status) &&
    /^\d{4}-\d{2}-\d{2}$/.test(order.date) && Array.isArray(order.lines) && order.lines.length > 0 && order.lines.length <= 30 &&
    order.lines.every(line => data.products.some(p=>p.id===line.product) && Number.isInteger(line.quantity) && line.quantity>=1 && line.quantity<=100 &&
      Number.isInteger(line.price) && line.price>=1 && line.price<=1000000));
}

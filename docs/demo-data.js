export const customers = [
  {id:1,name:'Estudio Horizonte',initials:'EH',sector:'Servicios',contact:'Equipo Horizonte',email:'horizonte@example.invalid',city:'Lima',color:'emerald'},
  {id:2,name:'Mercado Aurora',initials:'MA',sector:'Comercio',contact:'Equipo Aurora',email:'aurora@example.invalid',city:'Arequipa',color:'blue'},
  {id:3,name:'Hotel Brisa',initials:'HB',sector:'Hoteleria',contact:'Equipo Brisa',email:'brisa@example.invalid',city:'Cusco',color:'gold'},
  {id:4,name:'Taller Prisma',initials:'TP',sector:'Servicios',contact:'Equipo Prisma',email:'prisma@example.invalid',city:'Lima',color:'rose'},
  {id:5,name:'Cafe Alameda',initials:'CA',sector:'Comercio',contact:'Equipo Alameda',email:'alameda@example.invalid',city:'Trujillo',color:'blue'},
  {id:6,name:'Oficinas Central',initials:'OC',sector:'Servicios',contact:'Equipo Central',email:'central@example.invalid',city:'Lima',color:'emerald'},
  {id:7,name:'Distribuidora Delta',initials:'DD',sector:'Distribucion',contact:'Equipo Delta',email:'delta@example.invalid',city:'Piura',color:'gold'},
  {id:8,name:'Libreria Norte',initials:'LN',sector:'Comercio',contact:'Equipo Norte',email:'norte@example.invalid',city:'Chiclayo',color:'rose'}
];
export const products = [
  {id:1,sku:'NX-001',name:'Cuaderno A5',category:'Papeleria',price:1800,stock:182,icon:'notebook-pen',tone:'blue'},
  {id:2,sku:'NX-002',name:'Organizador de escritorio',category:'Oficina',price:4200,stock:93,icon:'layout-grid',tone:'emerald'},
  {id:3,sku:'NX-003',name:'Lampara de mesa',category:'Oficina',price:8900,stock:36,icon:'lamp-desk',tone:'gold'},
  {id:4,sku:'NX-004',name:'Botella termica',category:'Accesorios',price:5500,stock:4,icon:'bottle-wine',tone:'rose'},
  {id:5,sku:'NX-005',name:'Mochila urbana',category:'Accesorios',price:12900,stock:48,icon:'backpack',tone:'blue'},
  {id:6,sku:'NX-006',name:'Set de marcadores',category:'Papeleria',price:2400,stock:126,icon:'pencil-ruler',tone:'gold'},
  {id:7,sku:'NX-007',name:'Soporte para laptop',category:'Oficina',price:9500,stock:27,icon:'laptop',tone:'emerald'},
  {id:8,sku:'NX-008',name:'Agenda semanal',category:'Papeleria',price:3200,stock:64,icon:'calendar-days',tone:'rose'}
];
export function initialOrders() {
  const statuses = ['Confirmado','Cotizado','Por despachar','Borrador','Confirmado','Cotizado','Por despachar','Borrador'];
  return Array.from({length:16},(_,i)=>({id:`COT-${String(1048-i).padStart(4,'0')}`,customer:i%8+1,status:statuses[i%8],
    date:`2026-10-${String(16-i%12).padStart(2,'0')}`,lines:[{product:i%8+1,quantity:4+i,price:products[i%8].price},
    {product:(i+2)%8+1,quantity:2+i%4,price:products[(i+2)%8].price}]}));
}

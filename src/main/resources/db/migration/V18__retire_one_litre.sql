-- Preserve historical evidence and document references; do not delete products.
update product set active=false where volume_litres=1 or sku like 'NC-%-1L';

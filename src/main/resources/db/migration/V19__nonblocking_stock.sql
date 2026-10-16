-- Availability is informative. Preserve quantities, reservations and the audit trail.
update commercial_order set status='CONFIRMED',updated_at=current_timestamp,version=version+1
where status='WAITING_STOCK';

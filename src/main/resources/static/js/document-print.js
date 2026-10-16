(() => {
  const button = document.querySelector('[data-print]');
  if (!button) return;

  const waitForDocument = async () => {
    if (document.fonts?.ready) await document.fonts.ready;
    await Promise.all([...document.images].map(image => {
      if (image.complete) return image.decode?.().catch(() => undefined);
      return new Promise(resolve => {
        image.addEventListener('load', resolve, { once: true });
        image.addEventListener('error', resolve, { once: true });
      });
    }));
    await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
  };

  const finishPrinting = () => {
    document.documentElement.classList.remove('is-printing');
    button.disabled = false;
    button.textContent = button.dataset.label || 'Imprimir / guardar PDF';
  };

  window.addEventListener('afterprint', finishPrinting);
  button.addEventListener('click', async () => {
    button.dataset.label ||= button.textContent;
    button.disabled = true;
    button.textContent = 'Preparando documento...';
    try {
      await waitForDocument();
      document.documentElement.classList.add('is-printing');
      window.print();
    } catch {
      finishPrinting();
      window.alert('No se pudo abrir la impresión. Actualiza la página e inténtalo nuevamente.');
    }
    window.setTimeout(finishPrinting, 1500);
  });
})();

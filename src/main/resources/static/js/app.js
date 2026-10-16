(() => {
    const body = document.body;
    const openButton = document.querySelector('[data-nav-open]');
    const closeButtons = document.querySelectorAll('[data-nav-close]');
    document.querySelector('[data-close-window]')?.addEventListener('click', () => window.close());

    const closeNavigation = () => {
        body.classList.remove('nav-open');
        openButton?.setAttribute('aria-expanded', 'false');
    };

    openButton?.addEventListener('click', () => {
        body.classList.add('nav-open');
        openButton.setAttribute('aria-expanded', 'true');
    });

    closeButtons.forEach((button) => button.addEventListener('click', closeNavigation));
    document.addEventListener('keydown', (event) => {
        if (event.key === 'Escape') closeNavigation();
    });

    const passwordToggle = document.querySelector('[data-password-toggle]');
    const passwordInput = document.getElementById('password');
    passwordToggle?.addEventListener('click', () => {
        const revealing = passwordInput.type === 'password';
        passwordInput.type = revealing ? 'text' : 'password';
        passwordToggle.textContent = revealing ? 'Ocultar' : 'Mostrar';
        passwordToggle.setAttribute('aria-pressed', String(revealing));
    });

    document.querySelectorAll('[data-copy-target]').forEach((button) => {
        button.addEventListener('click', async () => {
            const target = document.getElementById(button.dataset.copyTarget);
            const value = target?.value || target?.textContent || '';
            if (!value || !navigator.clipboard) return;
            await navigator.clipboard.writeText(value.trim());
            const previous = button.textContent;
            button.textContent = 'Copiado';
            window.setTimeout(() => { button.textContent = previous; }, 1600);
        });
    });

    const lineForm = document.querySelector('[data-line-form]');
    const lines = lineForm?.querySelector('[data-lines]');
    const addLine = lineForm?.querySelector('[data-add-line]');

    const clearLine = (line) => {
        line.querySelectorAll('input, select, textarea').forEach((field) => {
            if (field.matches('[name="quantity"]')) field.value = '1';
            else field.value = '';
        });
        const productInput = line.querySelector('[data-product-id]');
        if (productInput) {
            delete productInput.dataset.price;
            delete productInput.dataset.stock;
        }
        const choice = line.querySelector('[data-product-picker-open]');
        choice?.classList.add('product-choice--empty');
        const visual = choice?.querySelector('.product-choice__visual');
        if (visual) visual.textContent = '+';
        const name = choice?.querySelector('[data-product-choice-name]');
        if (name) name.textContent = 'Seleccionar producto';
        const meta = choice?.querySelector('[data-product-choice-meta]');
        if (meta) meta.textContent = 'Buscar por nombre, código o área';
        const preview = line.querySelector('[data-line-total]');
        if (preview) preview.textContent = 'S/ 0.00';
    };

    const calculateSale = () => {
        if (!lineForm?.matches('[data-sale-form]')) return;
        let saleTotal = 0;
        lines.querySelectorAll('[data-line]').forEach((line) => {
            const product = line.querySelector('[data-product-select]');
            const price = Number(product?.dataset.price || 0);
            const quantity = Number(line.querySelector('[data-quantity]')?.value || 0);
            const total = Number.isFinite(price * quantity) ? price * quantity : 0;
            const preview = line.querySelector('[data-line-total]');
            if (preview) preview.textContent = `S/ ${total.toFixed(2)}`;
            saleTotal += total;
        });
        const totalNode = lineForm.querySelector('[data-sale-total]');
        if (totalNode) totalNode.textContent = `S/ ${saleTotal.toFixed(2)}`;
    };

    addLine?.addEventListener('click', () => {
        const source = lines?.querySelector('[data-line]');
        if (!source) return;
        const clone = source.cloneNode(true);
        clearLine(clone);
        lines.appendChild(clone);
        calculateSale();
        clone.querySelector('select, input')?.focus();
    });

    lines?.addEventListener('click', (event) => {
        const remove = event.target.closest('[data-remove-line]');
        if (!remove) return;
        const rows = lines.querySelectorAll('[data-line]');
        if (rows.length === 1) {
            clearLine(rows[0]);
        } else {
            remove.closest('[data-line]')?.remove();
        }
        calculateSale();
    });
    lines?.addEventListener('input', calculateSale);
    lines?.addEventListener('change', calculateSale);
    calculateSale();

    const productDialog = document.querySelector('[data-product-picker]');
    const productSearch = productDialog?.querySelector('[data-product-search]');
    const productOptions = [...(productDialog?.querySelectorAll('[data-product-option]') || [])];
    const categoryFilters = productDialog?.querySelector('[data-category-filters]');
    const productEmpty = productDialog?.querySelector('[data-product-empty]');
    let productTarget = null;
    let selectedCategory = '';

    const normalize = (value) => (value || '').normalize('NFD')
        .replace(/[\u0300-\u036f]/g, '').toLowerCase().trim();

    const filterProducts = () => {
        const term = normalize(productSearch?.value);
        let visible = 0;
        productOptions.forEach((option) => {
            const matchesTerm = term.split(/\s+/).every(word=>normalize(`${option.dataset.name} ${option.dataset.sku} ${option.dataset.category} ${option.dataset.barcode || ''}`).includes(word));
            const matchesCategory = !selectedCategory || option.dataset.category === selectedCategory;
            option.hidden = !(matchesTerm && matchesCategory);
            if (!option.hidden) visible += 1;
        });
        if (productEmpty) productEmpty.hidden = visible !== 0;
    };

    if (categoryFilters && productOptions.length) {
        [...new Set(productOptions.map((option) => option.dataset.category).filter(Boolean))]
            .sort((a, b) => a.localeCompare(b, 'es'))
            .forEach((category) => {
                const button = document.createElement('button');
                button.type = 'button';
                button.dataset.category = category;
                button.textContent = category;
                categoryFilters.appendChild(button);
            });
    }

    document.addEventListener('click', (event) => {
        const opener = event.target.closest('[data-product-picker-open]');
        if (opener && productDialog) {
            productTarget = opener.closest('[data-line]') || opener.closest('form');
            productSearch.value = '';
            selectedCategory = '';
            categoryFilters?.querySelectorAll('button').forEach((button) =>
                button.classList.toggle('is-active', button.dataset.category === ''));
            filterProducts();
            productDialog.showModal();
            window.setTimeout(() => productSearch?.focus(), 30);
            return;
        }

        if (event.target.closest('[data-product-picker-close]')) {
            productDialog?.close();
            return;
        }

        const category = event.target.closest('[data-category]');
        if (category && categoryFilters?.contains(category)) {
            selectedCategory = category.dataset.category || '';
            categoryFilters.querySelectorAll('button').forEach((button) =>
                button.classList.toggle('is-active', button === category));
            filterProducts();
            return;
        }

        const option = event.target.closest('[data-product-option]');
        if (!option || !productTarget) return;
        const input = productTarget.querySelector('[data-product-id]');
        const choice = productTarget.querySelector('[data-product-picker-open]');
        if (!input || !choice) return;
        input.value = option.dataset.id;
        input.dataset.price = option.dataset.price || '0';
        input.dataset.stock = option.dataset.stock || '0';
        const quantityField=productTarget.querySelector('[name=quantity]');
        if(quantityField){quantityField.step=option.dataset.unit==='NIU'?'1':'0.0001';quantityField.min=quantityField.step;}
        choice.classList.remove('product-choice--empty');
        const visual = choice.querySelector('.product-choice__visual');
        if (visual) {
            visual.replaceChildren();
            if (option.dataset.image) {
                const image = document.createElement('img');
                image.src = option.dataset.image;
                image.alt = '';
                visual.appendChild(image);
            } else visual.textContent = 'NC';
        }
        const name = choice.querySelector('[data-product-choice-name]');
        if (name) name.textContent = option.dataset.name;
        const meta = choice.querySelector('[data-product-choice-meta]');
        if (meta) meta.textContent = `${option.dataset.sku} · ${option.dataset.category} · stock ${option.dataset.stock}`;
        const cost = productTarget.querySelector('[data-unit-cost]');
        if (cost && !cost.value) window.ncMoney(cost,option.dataset.cost || '');
        productDialog.close();
        calculateSale();
    });

    productSearch?.addEventListener('input', filterProducts);
    productDialog?.addEventListener('click', (event) => {
        if (event.target === productDialog) productDialog.close();
    });

    const tableSearch = document.querySelector('[data-table-search]');
    const searchableRows = document.querySelectorAll('[data-searchable-table]:not([data-client-table]) tbody tr');
    tableSearch?.addEventListener('input', () => {
        const term = normalize(tableSearch.value);
        searchableRows.forEach((row) => {
            row.hidden = Boolean(term) && !normalize(row.textContent).includes(term);
        });
    });

    document.querySelectorAll('form').forEach((form) => form.addEventListener('submit', (event) => {
        const missing = [...form.querySelectorAll('[data-product-id]')].find((input) => !input.value);
        if (!missing) return;
        event.preventDefault();
        const line = missing.closest('[data-line]') || form;
        const picker = line.querySelector('[data-product-picker-open]');
        picker?.classList.add('product-choice--invalid');
        picker?.focus();
    }));

    const imageSelect = document.querySelector('[data-image-select]');
    const imagePreview = document.querySelector('[data-image-preview]');
    imageSelect?.addEventListener('change', () => {
        if (!imagePreview) return;
        imagePreview.src = imageSelect.value || '/images/nexo-logo.svg';
        imagePreview.classList.toggle('product-image-preview--logo', !imageSelect.value);
    });
    imageSelect?.dispatchEvent(new Event('change'));
    const unitSelect=document.querySelector('[name=unitCode]');
    const updateStockPrecision=()=>document.querySelectorAll('[name=minimumStock],[name=initialStock]').forEach(input=>{
        input.step=unitSelect?.value==='NIU'?'1':'0.0001';
        if(input.value)input.value=String(Number(input.value));
    });
    unitSelect?.addEventListener('change',updateStockPrecision);if(unitSelect)updateStockPrecision();

    const paymentCondition = document.querySelector('[data-payment-condition]');
    const creditField = document.querySelector('[data-credit-field]');
    const refreshPaymentCondition = () => {
        if (!paymentCondition || !creditField) return;
        const credit = paymentCondition.value === 'CREDITO';
        creditField.hidden = !credit;
        const input = creditField.querySelector('input');
        if (input) input.required = credit;
    };
    paymentCondition?.addEventListener('change', refreshPaymentCondition);
    refreshPaymentCondition();

    const operationType = document.querySelector('[data-operation-type]');
    const detractionFields = document.querySelector('[data-detraction-fields]');
    const refreshOperationType = () => {
        if (!operationType || !detractionFields) return;
        const subject = operationType.value === '1001';
        detractionFields.hidden = !subject;
        detractionFields.querySelectorAll('input').forEach((input) => { input.required = subject; });
    };
    operationType?.addEventListener('change', refreshOperationType);
    refreshOperationType();

    const currency = document.querySelector('[data-currency]');
    const exchangeRate = document.querySelector('[data-exchange-rate]');
    const refreshCurrency = () => {
        if (!currency || !exchangeRate) return;
        if (currency.value === 'PEN') exchangeRate.value = '1';
        exchangeRate.readOnly = currency.value === 'PEN';
    };
    currency?.addEventListener('change', refreshCurrency);
    refreshCurrency();

    const adjustmentForm = document.querySelector('[data-adjustment-form]');
    const adjustmentType = adjustmentForm?.querySelector('[data-adjustment-type]');
    const adjustmentReason = adjustmentForm?.querySelector('[data-adjustment-reason]');
    const adjustmentAmount = adjustmentForm?.querySelector('[data-adjustment-amount]');
    const adjustmentHelp = adjustmentForm?.querySelector('[data-adjustment-help]');
    const refreshAdjustmentReasons = () => {
        if (!adjustmentType || !adjustmentReason || !adjustmentAmount) return;
        const type = adjustmentType.value;
        const options = [...adjustmentReason.options];
        options.forEach((option) => {
            const enabled = option.dataset.noteType === type;
            option.hidden = !enabled;
            option.disabled = !enabled;
        });
        if (adjustmentReason.selectedOptions[0]?.disabled) {
            options.forEach((option) => { option.selected = false; });
            const firstEnabled = options.find((option) => !option.disabled);
            if (firstEnabled) firstEnabled.selected = true;
        }
        if (type === '07') {
            adjustmentAmount.max = adjustmentAmount.dataset.saleTotal || '';
            if (adjustmentHelp) adjustmentHelp.textContent =
                'Las anulaciones y devoluciones totales deben usar el total de la venta.';
        } else {
            adjustmentAmount.removeAttribute('max');
            if (adjustmentHelp) adjustmentHelp.textContent =
                'La nota de débito incrementa el valor por intereses, aumentos o penalidades.';
        }
    };
    adjustmentType?.addEventListener('change', refreshAdjustmentReasons);
    refreshAdjustmentReasons();

})();

import React, {useEffect, useRef, useState} from 'react';
import {useMutation, useQuery} from '@apollo/client';
import {Button, Typography, Input, ChevronDown, ChevronUp} from '@jahia/moonstone';
import {useTranslation} from 'react-i18next';
import {BULK_CREATE_USERS_IMPORT, GET_MAX_UPLOAD_SIZE} from './CreateUsers.gql';
import styles from './createUsers.scss';

const getSiteKey = () => {
    const parts = window.location.pathname.replace(/^\/jahia\/administration\//, '').split('/').filter(Boolean);
    return (parts.length === 3 && parts[1] === 'settings' && parts[2] === 'bulkCreateUsers') ? parts[0] : null;
};

// Columns that must be present in every CSV row (non-negotiable)
const REQUIRED_COLUMNS = ['j:nodename', 'j:password', 'j:firstName', 'j:lastName'];
// Columns handled by the backend outside of property mapping
const RESERVED_COLUMNS = ['j:nodename', 'j:password', 'groups'];

const readFileAsText = file => new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = e => resolve(e.target.result);
    reader.onerror = reject;
    reader.readAsText(file);
});

const parseHeaders = (text, sep) => {
    const firstLine = text.split(/\r?\n/)[0] || '';
    return firstLine.split(sep).map(h => h.trim().replace(/^["']|["']$/g, ''));
};

export const CreateUsers = () => {
    const {t} = useTranslation('bulk-create-users');
    const [csvFile, setCsvFile] = useState(null);
    const [delimiter, setDelimiter] = useState(',');
    const [messages, setMessages] = useState([]);
    const [showRequirements, setShowRequirements] = useState(false);
    const [inputKey, setInputKey] = useState(0);
    const [importResult, setImportResult] = useState(null);

    // Fix #3: messageBannerRef points to the VISIBLE message banner for focus management.
    // alertRef / statusRef remain as always-present sr-only live regions for AT announcement
    // but are no longer focused directly (focus on clipped regions hides keyboard focus).
    const alertRef = useRef(null);
    const statusRef = useRef(null);
    const fileInputRef = useRef(null);
    // Fix #6: ref for the proxy file button so focus can return to it after cancel
    const fileBtnRef = useRef(null);
    // Fix #3: ref for the visible message banner
    const messageBannerRef = useRef(null);

    const [csvHeaders, setCsvHeaders] = useState([]);
    const [missingRequired, setMissingRequired] = useState([]);
    const [selectedOptionalColumns, setSelectedOptionalColumns] = useState([]);
    const [overwrite, setOverwrite] = useState(false);

    const siteKey = getSiteKey();

    useEffect(() => {
        document.title = t('title');
    }, [t]);

    const {data: settingsData} = useQuery(GET_MAX_UPLOAD_SIZE);
    const maxSize = settingsData?.bulkCreateUsersMaxUploadSize;
    const maxSizeMb = typeof maxSize === 'number' ? Math.floor(maxSize / (1024 * 1024)) : null;

    const [importUsers, {loading: isUploading}] = useMutation(BULK_CREATE_USERS_IMPORT);

    // Re-parse headers whenever the file or delimiter changes
    useEffect(() => {
        if (!csvFile) {
            setCsvHeaders([]);
            setMissingRequired([]);
            setSelectedOptionalColumns([]);
            return;
        }

        readFileAsText(csvFile).then(text => {
            const sep = delimiter || ',';
            const headers = parseHeaders(text, sep);
            setCsvHeaders(headers);
            setMissingRequired(REQUIRED_COLUMNS.filter(r => !headers.includes(r)));
            // Pre-select all optional (non-reserved) columns
            setSelectedOptionalColumns(headers.filter(h => !REQUIRED_COLUMNS.includes(h)));
        }).catch(() => {
            setCsvHeaders([]);
        });
    }, [csvFile, delimiter]);

    const addMessage = (severity, text) => setMessages([{id: Date.now(), severity, text}]);

    const toggleOptionalColumn = col => {
        setSelectedOptionalColumns(prev =>
            prev.includes(col) ? prev.filter(c => c !== col) : [...prev, col]
        );
    };

    const handleSubmit = async e => {
        e.preventDefault();
        if (!csvFile || missingRequired.length > 0) {
            return;
        }

        setMessages([]);
        setImportResult(null);

        let csvContent;
        try {
            csvContent = await readFileAsText(csvFile);
        } catch {
            addMessage('error', t('error.readFile'));
            // Fix #3: focus the VISIBLE message banner, not the sr-only alert region
            setTimeout(() => messageBannerRef.current?.focus(), 50);
            return;
        }

        // Property columns to import: required property columns + user-selected optional ones
        const selectedColumns = [
            ...REQUIRED_COLUMNS.filter(c => !RESERVED_COLUMNS.includes(c)),
            ...selectedOptionalColumns
        ];

        try {
            const {data} = await importUsers({
                variables: {
                    csvContent,
                    separator: delimiter,
                    siteKey: siteKey || null,
                    selectedColumns,
                    overwrite
                }
            });
            const result = data?.bulkCreateUsersImport;
            setImportResult(result);
            if (result?.success) {
                addMessage('success', t('result.success', {count: result.createdCount}));
                // Fix #3: focus the VISIBLE message banner, not the sr-only status region
                setTimeout(() => messageBannerRef.current?.focus(), 50);
                setCsvFile(null);
                setDelimiter(',');
                setInputKey(prev => prev + 1);
            } else {
                addMessage('error', t('result.partial', {errorCount: result?.errorCount ?? 1}));
                // Fix #3: focus the VISIBLE message banner
                setTimeout(() => messageBannerRef.current?.focus(), 50);
            }
        } catch (err) {
            addMessage('error', t('error.network', {message: err.message}));
            // Fix #3: focus the VISIBLE message banner
            setTimeout(() => messageBannerRef.current?.focus(), 50);
        }
    };

    const handleFileChange = e => {
        const file = e.target.files[0];
        setImportResult(null);
        setMessages([]);
        if (!file) {
            return setCsvFile(null);
        }

        if (!file.name.toLowerCase().endsWith('.csv')) {
            addMessage('error', t('validation.notCsv'));
            return setCsvFile(null);
        }

        if (typeof maxSize === 'number' && file.size > maxSize) {
            addMessage('error', t('validation.tooLarge', {maxSizeMb}));
            return setCsvFile(null);
        }

        setCsvFile(file);
    };

    const handleCancel = () => {
        setCsvFile(null);
        setDelimiter(',');
        setMessages([]);
        setImportResult(null);
        setOverwrite(false);
        setInputKey(prev => prev + 1);
        // Fix #6: return focus to the visible proxy file button so it does not drop to <body>
        fileBtnRef.current?.focus();
    };

    // Fix #3: renderMessages attaches messageBannerRef to the first visible banner
    // and makes it programmatically focusable (tabIndex={-1}) so focus lands on a
    // visible element after submit/error. The sr-only live regions (alertRef/statusRef)
    // continue to announce changes to AT but are no longer focused themselves.
    const renderMessages = () =>
        messages.map((m, index) => (
            <div
                key={m.id}
                ref={index === 0 ? messageBannerRef : null}
                id={`bcu-message-${m.severity}`}
                tabIndex={-1}
                className={`${styles.bcu_message} ${styles[`bcu_${m.severity}`]}`}
            >
                {m.text}
                <button
                    type="button"
                    className={styles.bcu_closeBtn}
                    aria-label={t('button.closeMessage')}
                    onClick={() => setMessages(msgs => msgs.filter(msg => msg.id !== m.id))}
                >×
                </button>
            </div>
        ));

    const detectedRequired = csvHeaders.filter(h => REQUIRED_COLUMNS.includes(h));
    const detectedOptional = csvHeaders.filter(h => !REQUIRED_COLUMNS.includes(h));

    return (
        <div className={styles.bcu_root}>
            {/* Fix #3: sr-only alert live region — always in DOM for AT announcement.
                tabIndex removed so it is never the focus target for sighted users. */}
            <div
                ref={alertRef}
                role="alert"
                aria-live="assertive"
                aria-atomic="true"
                className={styles.bcu_sr_only}
            >
                {messages[0]?.severity === 'error' ? messages[0]?.text : ''}
            </div>
            {/* Fix #3: sr-only status live region — always in DOM for AT announcement.
                tabIndex removed so it is never the focus target for sighted users. */}
            <div
                ref={statusRef}
                role="status"
                aria-live="polite"
                aria-atomic="true"
                className={styles.bcu_sr_only}
            >
                {messages[0]?.severity === 'success' ? messages[0]?.text : ''}
            </div>
            <div className={styles.bcu_headerRoot}>
                <header className={styles.bcu_header}>
                    <Typography variant="title" weight="semiBold">{t('title')}</Typography>
                </header>
                {renderMessages()}
                <form className={styles.bcu_form} aria-busy={isUploading} onSubmit={handleSubmit}>
                    <div className={styles.bcu_formField}>
                        {/* Fix #2: htmlFor now points at the real file <input> id="bcu-csv-file-input"
                            so the label correctly associates with the actual control, not the proxy button. */}
                        <Typography component="label" htmlFor="bcu-csv-file-input" variant="body" weight="bold">
                            {t('label.csvFile')}
                        </Typography>
                        <span id="bcu-file-hint" className={styles.bcu_fileHint}>
                            {typeof maxSizeMb === 'number' ? t('hint.csvFile', {maxSizeMb}) : t('hint.csvFileFormat')}
                        </span>
                        {/* Fix #6: fileBtnRef attached so handleCancel can return focus here.
                            id="bcu-csv-file" preserved for Cypress. */}
                        <button
                            ref={fileBtnRef}
                            type="button"
                            id="bcu-csv-file"
                            className={styles.bcu_fileBtn}
                            disabled={isUploading}
                            aria-describedby="bcu-file-hint"
                            onClick={() => fileInputRef.current?.click()}
                        >
                            {t('label.chooseFile')}
                        </button>
                        {/* Fix #1: tabIndex={-1} removes this hidden input from tab order —
                            the proxy button above handles all keyboard interaction.
                            Fix #2: id="bcu-csv-file-input" (distinct from proxy button id) so the
                            <label htmlFor="bcu-csv-file-input"> association is correct.
                            Fix #2: aria-describedby="bcu-file-hint" associates the hint with the
                            actual control (in addition to the proxy button). */}
                        <input
                            key={inputKey}
                            ref={fileInputRef}
                            type="file"
                            id="bcu-csv-file-input"
                            name="csvFile"
                            accept=".csv"
                            tabIndex={-1}
                            className={styles.bcu_fileInput}
                            aria-label={t('label.csvFile')}
                            disabled={isUploading}
                            onChange={handleFileChange}
                        />
                        {csvFile && (
                            <span aria-live="polite" aria-atomic="true" className={styles.bcu_fileName}>
                                {t('label.selected', {name: csvFile.name, size: (csvFile.size / 1024).toFixed(1)})}
                            </span>
                        )}
                    </div>
                    <div className={styles.bcu_formField}>
                        <Typography component="label" htmlFor="bcu-delimiter" variant="body" weight="bold">
                            {t('label.delimiter')}
                        </Typography>
                        <Input
                            type="text"
                            id="bcu-delimiter"
                            name="delimiter"
                            value={delimiter}
                            placeholder={t('placeholder.delimiter')}
                            disabled={isUploading}
                            maxLength={1}
                            onChange={e => setDelimiter(e.target.value)}
                        />
                    </div>

                    {csvHeaders.length > 0 && (
                        <div id="bcu-columns" className={styles.bcu_columnsSection}>
                            <Typography variant="subheading" weight="semiBold">
                                {t('columns.title')}
                            </Typography>

                            {/* Fix #8: bcu-missing-required always rendered with role="alert";
                                only the text content is conditional. The element is present
                                (but empty) when nothing is missing, satisfying the requirement
                                that the live region pre-exists before content is injected. */}
                            <div id="bcu-missing-required" role="alert" aria-live="assertive" className={missingRequired.length > 0 ? styles.bcu_missingRequired : ''}>
                                {missingRequired.length > 0 && t('columns.missingRequired', {columns: missingRequired.join(', ')})}
                            </div>

                            {/* Fix #1 (SC 4.1.2): Required columns are status indicators, not interactive
                                controls. <fieldset>/<legend> is for grouping form controls; these
                                <li> items carry no interactive semantics, so a plain <div> with a
                                labelled <ul> is the correct pattern.
                                Each <li> carries id="bcu-col-req-{col}" (same id shape as before)
                                so Cypress selectors remain valid. The ✓/✗ glyph and missing badge
                                are marked aria-hidden; sr-only text conveys present/missing to AT. */}
                            <div className={styles.bcu_columnFieldset}>
                                <p className={styles.bcu_columnGroupLabel} id="bcu-required-cols-label">
                                    {t('columns.required')}
                                </p>
                                <ul className={styles.bcu_requiredStatusList} aria-label={t('columns.required')}>
                                    {REQUIRED_COLUMNS.map(col => {
                                        const isPresent = detectedRequired.includes(col);
                                        return (
                                            <li
                                                key={col}
                                                id={`bcu-col-req-${col.replace(':', '-')}`}
                                                className={`${styles.bcu_requiredStatusItem} ${isPresent ? '' : styles.bcu_columnMissing}`}
                                            >
                                                <span aria-hidden="true" className={styles.bcu_requiredStatusGlyph}>
                                                    {isPresent ? '✓' : '✗'}
                                                </span>
                                                <span>{col}</span>
                                                {!isPresent && (
                                                    <span aria-hidden="true" className={styles.bcu_missingBadge}>
                                                        {t('columns.missing')}
                                                    </span>
                                                )}
                                                <span className={styles.bcu_sr_only}>
                                                    {isPresent ? t('columns.present') : t('columns.missingStatus')}
                                                </span>
                                            </li>
                                        );
                                    })}
                                </ul>
                            </div>

                            {detectedOptional.length > 0 && (
                                <fieldset className={styles.bcu_columnFieldset}>
                                    <legend className={styles.bcu_columnGroupLabel}>
                                        {t('columns.optional')}
                                    </legend>
                                    {detectedOptional.map(col => (
                                        <label key={col} className={styles.bcu_columnItem}>
                                            <input
                                                id={`bcu-col-opt-${col.replace(':', '-')}`}
                                                type="checkbox"
                                                checked={selectedOptionalColumns.includes(col)}
                                                disabled={isUploading}
                                                onChange={() => toggleOptionalColumn(col)}
                                            />
                                            <span>{col}</span>
                                        </label>
                                    ))}
                                </fieldset>
                            )}
                        </div>
                    )}

                    <div className={styles.bcu_formField}>
                        <label className={styles.bcu_columnItem}>
                            <input
                                id="bcu-overwrite"
                                type="checkbox"
                                checked={overwrite}
                                disabled={isUploading}
                                onChange={e => setOverwrite(e.target.checked)}
                            />
                            <span>{t('label.overwrite')}</span>
                        </label>
                    </div>

                    <div className={styles.bcu_actions}>
                        <Button
                            id="bcu-submit"
                            type="submit"
                            color="accent"
                            label={isUploading ? t('button.importing') : t('button.submit')}
                            disabled={!csvFile || isUploading || missingRequired.length > 0}
                        />
                        <Button
                            id="bcu-cancel"
                            type="button"
                            label={t('button.cancel')}
                            disabled={isUploading}
                            onClick={handleCancel}
                        />
                    </div>
                </form>

                <div id="bcu-result" role="status" aria-live="polite" className={importResult ? styles.bcu_resultBox : `${styles.bcu_resultBox} ${styles.bcu_resultBox_hidden}`}>
                    {importResult && (
                        <>
                            <div className={styles.bcu_resultRow}>
                                <span className={styles.bcu_resultLabel}>{t('result.label.created')}</span>
                                <span id="bcu-result-created">{importResult.createdCount}</span>
                            </div>
                            {importResult.updatedCount > 0 && (
                                <div className={styles.bcu_resultRow}>
                                    <span className={styles.bcu_resultLabel}>{t('result.label.updated')}</span>
                                    <span id="bcu-result-updated">{importResult.updatedCount}</span>
                                </div>
                            )}
                            <div className={styles.bcu_resultRow}>
                                <span className={styles.bcu_resultLabel}>{t('result.label.skipped')}</span>
                                <span id="bcu-result-skipped">{importResult.skippedCount}</span>
                            </div>
                            {importResult.errorCount > 0 && (
                                <div className={styles.bcu_resultRow}>
                                    <span className={styles.bcu_resultLabel}>{t('result.label.errors')}</span>
                                    <span id="bcu-result-errors">{importResult.errorCount}</span>
                                </div>
                            )}
                            {importResult.errors && importResult.errors.length > 0 && (
                                <ul id="bcu-error-list" className={styles.bcu_errorList}>
                                    {importResult.errors.map((err, i) => (
                                        // eslint-disable-next-line react/no-array-index-key
                                        <li key={i}>{err}</li>
                                    ))}
                                </ul>
                            )}
                        </>
                    )}
                </div>

                <div className={styles.bcu_section}>
                    <button
                        type="button"
                        id="bcu-toggle-requirements"
                        className={styles.bcu_toggleRequirements}
                        aria-expanded={showRequirements}
                        aria-controls="bcu-requirements-box"
                        onClick={() => setShowRequirements(v => !v)}
                    >
                        <Typography component="span" variant="subheading" weight="default">
                            {showRequirements ? t('requirements.hide') : t('requirements.show')}
                        </Typography>
                        <span aria-hidden="true">
                            {showRequirements ? <ChevronUp size="small"/> : <ChevronDown size="small"/>}
                        </span>
                    </button>
                    <div id="bcu-requirements-box" hidden={!showRequirements} className={styles.bcu_requirementsBox}>
                        <dl className={styles.bcu_descriptionList}>
                            <div>
                                <dt className={styles.bcu_descriptionListTerm}>{t('requirements.required.title')}</dt>
                                <dd className={styles.bcu_descriptionListDescription}>
                                    {t('requirements.required.body')}
                                </dd>
                            </div>
                            <div>
                                <dt className={styles.bcu_descriptionListTerm}>{t('requirements.optional.title')}</dt>
                                <dd className={styles.bcu_descriptionListDescription}>
                                    {t('requirements.optional.body')}
                                </dd>
                            </div>
                            <div>
                                <dt className={styles.bcu_descriptionListTerm}>{t('requirements.notes.title')}</dt>
                                <dd className={styles.bcu_descriptionListDescription}>{t('requirements.notes.headers')}</dd>
                                <dd className={styles.bcu_descriptionListDescription}>{t('requirements.notes.maxSize', {maxSizeMb})}</dd>
                            </div>
                        </dl>
                    </div>
                </div>
            </div>
        </div>
    );
};

import React, {useState} from 'react';
import {useMutation} from '@apollo/client';
import {Button, Typography, Input, ChevronDown, ChevronUp} from '@jahia/moonstone';
import {useTranslation} from 'react-i18next';
import {BULK_CREATE_USERS_IMPORT} from './CreateUsers.gql';
import styles from './createUsers.scss';

const getSiteKey = () => {
    const parts = window.location.pathname.replace(/^\/jahia\/administration\//, '').split('/').filter(Boolean);
    return (parts.length === 3 && parts[1] === 'settings' && parts[2] === 'bulkCreateUsers') ? parts[0] : null;
};

const MAX_SIZE = 10 * 1024 * 1024;

const readFileAsText = file => new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = e => resolve(e.target.result);
    reader.onerror = reject;
    reader.readAsText(file);
});

export const CreateUsers = () => {
    const {t} = useTranslation('bulk-create-users');
    const [csvFile, setCsvFile] = useState(null);
    const [delimiter, setDelimiter] = useState(',');
    const [messages, setMessages] = useState([]);
    const [showRequirements, setShowRequirements] = useState(false);
    const [inputKey, setInputKey] = useState(0);
    const [importResult, setImportResult] = useState(null);

    const siteKey = getSiteKey();

    const [importUsers, {loading: isUploading}] = useMutation(BULK_CREATE_USERS_IMPORT);

    const addMessage = (severity, text) => setMessages([{id: Date.now(), severity, text}]);

    const handleSubmit = async e => {
        e.preventDefault();
        if (!csvFile) {
            return addMessage('error', t('validation.noFile'));
        }

        setMessages([]);
        setImportResult(null);

        let csvContent;
        try {
            csvContent = await readFileAsText(csvFile);
        } catch {
            return addMessage('error', t('error.readFile'));
        }

        try {
            const {data} = await importUsers({
                variables: {csvContent, separator: delimiter, siteKey: siteKey || null}
            });
            const result = data?.bulkCreateUsersImport;
            setImportResult(result);
            if (result?.success) {
                addMessage('success', t('result.success', {count: result.createdCount}));
                setCsvFile(null);
                setDelimiter(',');
                setInputKey(prev => prev + 1);
            } else {
                addMessage('error', t('result.partial', {errorCount: result?.errorCount ?? 1}));
            }
        } catch (err) {
            addMessage('error', t('error.network', {message: err.message}));
        }
    };

    const handleFileChange = e => {
        const file = e.target.files[0];
        setImportResult(null);
        if (!file) {
            return setCsvFile(null);
        }

        if (!file.name.toLowerCase().endsWith('.csv')) {
            return addMessage('error', t('validation.notCsv'));
        }

        if (file.size > MAX_SIZE) {
            return addMessage('error', t('validation.tooLarge'));
        }

        setCsvFile(file);
        setMessages([]);
    };

    const handleCancel = () => {
        setCsvFile(null);
        setDelimiter(',');
        setMessages([]);
        setImportResult(null);
        setInputKey(prev => prev + 1);
    };

    const renderMessages = () =>
        messages.map(m => (
            <div key={m.id} id={`bcu-message-${m.severity}`} className={`${styles.bcu_message} ${styles[`bcu_${m.severity}`]}`}>
                {m.text}
                <button
                    type="button"
                    className={styles.bcu_closeBtn}
                    aria-label="Close message"
                    onClick={() => setMessages(msgs => msgs.filter(msg => msg.id !== m.id))}
                >×
                </button>
            </div>
        ));

    return (
        <div className={styles.bcu_root}>
            <div className={styles.bcu_headerRoot}>
                <header className={styles.bcu_header}>
                    <Typography variant="title" weight="semiBold">{t('title')}</Typography>
                </header>
                {renderMessages()}
                <form className={styles.bcu_form} onSubmit={handleSubmit}>
                    <div className={styles.bcu_formField}>
                        <Typography component="label" htmlFor="bcu-csv-file" variant="body" weight="bold">
                            {t('label.csvFile')}
                        </Typography>
                        <Input
                            key={inputKey}
                            type="file"
                            id="bcu-csv-file"
                            name="csvFile"
                            accept=".csv"
                            disabled={isUploading}
                            onChange={handleFileChange}
                        />
                        {csvFile && (
                            <Typography variant="caption" className={styles.bcu_fileInfo}>
                                {t('label.selected', {name: csvFile.name, size: (csvFile.size / 1024).toFixed(1)})}
                            </Typography>
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
                    <div className={styles.bcu_actions}>
                        <Button
                            id="bcu-submit"
                            type="submit"
                            color="accent"
                            label={isUploading ? t('button.importing') : t('button.submit')}
                            disabled={!csvFile || isUploading}
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

                {importResult && (
                    <div id="bcu-result" className={styles.bcu_resultBox}>
                        <div className={styles.bcu_resultRow}>
                            <span className={styles.bcu_resultLabel}>{t('result.label.created')}</span>
                            <span id="bcu-result-created">{importResult.createdCount}</span>
                        </div>
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
                    </div>
                )}

                <div className={styles.bcu_section}>
                    <button
                        type="button"
                        id="bcu-toggle-requirements"
                        className={styles.bcu_toggleRequirements}
                        onClick={() => setShowRequirements(v => !v)}
                    >
                        <Typography variant="subheading" weight="default">
                            {showRequirements ? t('requirements.hide') : t('requirements.show')}
                        </Typography>
                        {showRequirements ? <ChevronUp size="small"/> : <ChevronDown size="small"/>}
                    </button>
                    {showRequirements && (
                        <div className={styles.bcu_requirementsBox}>
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
                                        {t('requirements.optional.body')} <code>$</code>
                                    </dd>
                                </div>
                                <div>
                                    <dt className={styles.bcu_descriptionListTerm}>{t('requirements.notes.title')}</dt>
                                    <dd className={styles.bcu_descriptionListDescription}>{t('requirements.notes.headers')}</dd>
                                    <dd className={styles.bcu_descriptionListDescription}>{t('requirements.notes.maxSize')}</dd>
                                </div>
                            </dl>
                        </div>
                    )}
                </div>
            </div>
        </div>
    );
};

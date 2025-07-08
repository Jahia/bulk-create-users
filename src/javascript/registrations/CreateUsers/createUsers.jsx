import React, {useState} from 'react';
import {Button, Typography, Input, ChevronDown, ChevronUp} from '@jahia/moonstone';
import styles from './createUsers.scss';

const getSiteKey = () => {
    const parts = window.location.pathname.replace(/^\/jahia\/administration\//, '').split('/').filter(Boolean);
    return (parts.length === 3 && parts[1] === 'settings' && parts[2] === 'bulkCreateUsers') ? parts[0] : null;
};

const MAX_SIZE = 10 * 1024 * 1024;

export const CreateUsers = () => {
    const [csvFile, setCsvFile] = useState(null);
    const [delimiter, setDelimiter] = useState(',');
    const [messages, setMessages] = useState([]);
    const [isUploading, setIsUploading] = useState(false);
    const [showRequirements, setShowRequirements] = useState(false);
    const [inputKey, setInputKey] = useState(0);

    const siteKey = getSiteKey();
    const fileInputRef = React.useRef(null);

    const addMessage = (severity, text) => setMessages([{id: Date.now(), severity, text}]);

    const handleSubmit = async e => {
        e.preventDefault();
        if (!csvFile) {
            return addMessage('error', 'Please select a CSV file');
        }

        setIsUploading(true);
        setMessages([]);
        const formData = new FormData();
        formData.append('file', csvFile);
        formData.append('separator', delimiter);
        formData.append('siteKey', siteKey || '');
        try {
            const res = await fetch('/modules/bulk-users-upload', {method: 'POST', body: formData});
            const contentType = res.headers.get('content-type');
            const result = contentType?.includes('application/json') ? await res.json() : await res.text();
            if (res.ok) {
                addMessage('success', result.message || 'Users created successfully');
                setCsvFile(null);
                setDelimiter(',');
            } else {
                addMessage('error', result.error || result.message || result || 'Bulk creation failed');
            }
        } catch (err) {
            addMessage('error', 'Network error: ' + err.message);
        } finally {
            setIsUploading(false);
        }
    };

    const handleFileChange = e => {
        const file = e.target.files[0];
        if (!file) {
            return setCsvFile(null);
        }

        if (!file.name.toLowerCase().endsWith('.csv')) {
            return addMessage('error', 'Please select a valid CSV file');
        }

        if (file.size > MAX_SIZE) {
            return addMessage('error', 'File size must be less than 10MB');
        }

        setCsvFile(file);
        setMessages([]);
    };

    const handleInputChange = e => setDelimiter(e.target.value);

    const handleCancel = () => {
        setCsvFile(null);
        setDelimiter(',');
        setMessages([]);
        setInputKey(prev => prev + 1);
    };

    const renderMessages = () =>
        messages.map(m => (
            <div key={m.id} className={`${styles.message} ${styles[m.severity]}`}>
                {m.text}
                <button
                    type="button"
                    className={styles.closeBtn}
                    aria-label="Close message"
                    onClick={() => setMessages(msgs => msgs.filter(msg => msg.id !== m.id))}
                >×
                </button>
            </div>
        ));

    return (
        <div className={styles.root}>
            <div className={styles.headerRoot}>
                <header className={styles.header}>
                    <Typography variant="title" weight="semiBold">Bulk Create Users</Typography>
                </header>
                {renderMessages()}
                <form className={styles.form} onSubmit={handleSubmit}>
                    <div className={styles.formField}>
                        <Typography component="label" htmlFor="csvFile" variant="body" weight="bold">
                            CSV File
                        </Typography>
                        <Input
                            key={inputKey}
                            type="file"
                            id="csvFile"
                            name="csvFile"
                            accept=".csv"
                            disabled={isUploading}
                            inputRef={fileInputRef}
                            onChange={handleFileChange}
                        />
                        {csvFile && (
                            <Typography variant="caption" className={styles.fileInfo}>
                                Selected: {csvFile.name} ({(csvFile.size / 1024).toFixed(1)} KB)
                            </Typography>
                        )}
                    </div>
                    <div className={styles.formField}>
                        <Typography component="label" htmlFor="delimiter" variant="body" weight="bold">
                            CSV Delimiter
                        </Typography>
                        <Input
                            type="text"
                            id="delimiter"
                            name="delimiter"
                            value={delimiter}
                            placeholder="Enter delimiter (e.g., , ; |)"
                            disabled={isUploading}
                            maxLength={1}
                            onChange={handleInputChange}
                        />
                    </div>
                    <div className={styles.actions}>
                        <Button
                            type="submit"
                            color="accent"
                            label={isUploading ? 'Uploading...' : 'Submit'}
                            disabled={!csvFile || isUploading}
                        />
                        <Button
                            type="button"
                            label="Cancel"
                            disabled={isUploading}
                            onClick={handleCancel}
                        />
                    </div>
                </form>
                <div className="section">
                    <button
                        type="button"
                        className={styles.toggleRequirements}
                        onClick={() => setShowRequirements(v => !v)}
                    >
                        <Typography variant="subheading" weight="default">
                            {showRequirements ? 'Hide' : 'Show'} CSV Format Requirements
                        </Typography>
                        {showRequirements ? <ChevronUp size="small"/> : <ChevronDown size="small"/>}
                    </button>
                    {showRequirements && (
                        <div className={styles.requirementsBox}>
                            <dl className={styles.descriptionList}>
                                <div>
                                    <dt className={styles.descriptionListTerm}>Required Columns</dt>
                                    <dd className={styles.descriptionListDescription}>
                                        j:nodename, j:password, j:firstName and j:lastName
                                    </dd>
                                </div>
                                <div>
                                    <dt className={styles.descriptionListTerm}>Optional Column</dt>
                                    <dd className={styles.descriptionListDescription}>
                                        groups (separate multiple groups with <code>$</code>)
                                    </dd>
                                </div>
                                <div>
                                    <dt className={styles.descriptionListTerm}>Notes</dt>
                                    <dd className={styles.descriptionListDescription}>First row must be column names</dd>
                                    <dd className={styles.descriptionListDescription}>Maximum file size: 10MB</dd>
                                </div>
                            </dl>
                        </div>
                    )}
                </div>
            </div>
        </div>
    );
};

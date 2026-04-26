import {gql} from '@apollo/client';

export const GET_MAX_UPLOAD_SIZE = gql`
    query BulkCreateUsersMaxUploadSize {
        bulkCreateUsersMaxUploadSize
    }
`;

export const BULK_CREATE_USERS_IMPORT = gql`
    mutation BulkCreateUsersImport($csvContent: String!, $separator: String, $siteKey: String, $selectedColumns: [String], $overwrite: Boolean) {
        bulkCreateUsersImport(csvContent: $csvContent, separator: $separator, siteKey: $siteKey, selectedColumns: $selectedColumns, overwrite: $overwrite) {
            success
            createdCount
            updatedCount
            skippedCount
            errorCount
            errors
        }
    }
`;

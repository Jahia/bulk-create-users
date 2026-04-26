import {gql} from '@apollo/client';

export const BULK_CREATE_USERS_IMPORT = gql`
    mutation BulkCreateUsersImport($csvContent: String!, $separator: String, $siteKey: String, $selectedColumns: [String]) {
        bulkCreateUsersImport(csvContent: $csvContent, separator: $separator, siteKey: $siteKey, selectedColumns: $selectedColumns) {
            success
            createdCount
            skippedCount
            errorCount
            errors
        }
    }
`;

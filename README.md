# Bulk Create Users

A Jahia community module that provides a UI and GraphQL API to bulk-create users from a CSV file, with optional group assignment.

## Features

- Bulk user creation from a CSV file via a React admin UI
- Column selection: choose which optional properties to import per run
- Group assignment using `[group1],[group2]` bracket notation
- Optional `overwrite` flag: when enabled, properties of existing users are updated; otherwise existing users are skipped (the `root` user is always skipped regardless of the flag)
- Detailed import result: created, updated, skipped, error counts and per-row error messages
- Max upload size enforced from Jahia's own `SettingsBean` configuration
- GraphQL mutation API (`bulkCreateUsersImport`) for programmatic imports
- Site-scoped or global user creation

## Requirements

- Jahia `8.2.1.0` or later
- Module dependencies: `default`, `graphql-dxm-provider`

## Installation

Deploy the module on your Jahia environment. The **Bulk Create Users** option becomes available under **Administration → [site] → Settings → Bulk Create Users**.

## CSV Format

### Required columns

| Column | Description |
|---|---|
| `j:nodename` | Username (must be unique) |
| `j:password` | Initial password |
| `j:firstName` | First name |
| `j:lastName` | Last name |

### Optional columns

| Column | Description |
|---|---|
| `j:email` | Email address |
| `groups` | Bracket-separated group names: `[group1],[group2]` |
| Any `j:*` property | Any other user property supported by Jahia |

The UI displays all optional columns detected in the CSV header and lets you toggle each one before importing. Unselected columns are ignored by the backend.

### Delimiter

The default delimiter is `,`. Any single character is accepted (e.g. `;`, `|`). Set it in the UI before uploading.

### Example

```csv
j:nodename,j:password,j:firstName,j:lastName,j:email,groups
alice,Secret1234!,Alice,Smith,alice@example.com,[editors],[privileged]
bob,Secret1234!,Bob,Jones,bob@example.com,[editors]
```

## GraphQL API

### Query

```graphql
query {
  bulkCreateUsersMaxUploadSize   # returns the configured max size in bytes
}
```

### Mutation

```graphql
mutation {
  bulkCreateUsersImport(
    csvContent: "...",
    separator: ",",
    siteKey: "mySite",          # omit for global users
    selectedColumns: ["j:firstName", "j:lastName", "j:email", "groups"],
    overwrite: false            # when true, update existing users instead of skipping (root is always skipped)
  ) {
    success
    createdCount
    updatedCount
    skippedCount
    errorCount
    errors
  }
}
```

### Authorization

Authorization is enforced in two layers:

1. **Annotation gate.** Both the `bulkCreateUsersImport` mutation and the `bulkCreateUsersMaxUploadSize` query carry `@GraphQLRequiresPermission("adminUsersBulkCreate")`. This fine-grained, dedicated permission is the first-pass gate evaluated by the GraphQL engine, so any unauthenticated caller — or any caller lacking `adminUsersBulkCreate` — is rejected before the method body runs.
2. **Scope-aware re-check.** Because the annotation is not scope-aware, the mutation re-verifies the caller's permission against the resolved target, evaluated through the caller's own ACL-respecting session (not the system session used for the writes), in `isAuthorizedForScope`:
   - **Global import** (`siteKey` omitted): requires `adminUsersBulkCreate` on the repository root `/`.
   - **Site-scoped import** (`siteKey` provided): requires `siteAdminUsers` **or** `adminUsers` on `/sites/<siteKey>`.

   The re-check **fails closed**: any repository error, or an unknown site, denies the import.

**Granting the permission.** Add `adminUsersBulkCreate` to the role(s) that should be allowed to bulk-import users. A role that grants only `adminUsersBulkCreate` can perform a global import end-to-end — no broader `adminUsers` grant is required. For site-scoped imports the caller additionally needs `siteAdminUsers` (or `adminUsers`) on the target site.

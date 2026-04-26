# bulk-create-users — CLAUDE.md

## Architecture

Full-stack Jahia module (OSGi bundle + React MF remote):

| Layer | Location | Notes |
|---|---|---|
| GraphQL mutations | `src/main/java/.../graphql/BulkCreateUsersMutationExtension.java` | `@GraphQLTypeExtension(Mutation.class)` |
| GraphQL queries | `src/main/java/.../graphql/BulkCreateUsersQueryExtension.java` | `@GraphQLTypeExtension(Query.class)` |
| Result type | `src/main/java/.../graphql/BulkCreateUsersResult.java` | fields: success, createdCount, updatedCount, skippedCount, errorCount, errors |
| Business logic | `src/main/java/.../users/UsersHandler.java` | OSGi DS `@Component(service=UsersHandler.class)` |
| React UI | `src/javascript/registrations/CreateUsers/createUsers.jsx` | Apollo `useMutation` + `useQuery` |
| GQL definitions | `src/javascript/registrations/CreateUsers/CreateUsers.gql.js` | `BULK_CREATE_USERS_IMPORT`, `GET_MAX_UPLOAD_SIZE` |
| i18n | `src/main/resources/javascript/locales/en.json` | namespace `bulk-create-users` |

GraphQL extensions are auto-discovered via `@GraphQLTypeExtension` — `BulkCreateUsersGraphQLExtensionsProvider` intentionally stays empty.

## Key constants

```java
// UsersHandler.java
REQUIRED_PROPERTY_COLUMNS = {"j:firstName", "j:lastName"}  // throw on empty
GROUP_PATTERN = Pattern.compile("\\[([^\\]]+)\\]")          // [group1],[group2]
```

```js
// createUsers.jsx
REQUIRED_COLUMNS = ['j:nodename', 'j:password', 'j:firstName', 'j:lastName']
RESERVED_COLUMNS = ['j:nodename', 'j:password', 'groups']   // excluded from selectedColumns
```

## Business rules

- `selectedColumns` sent to backend = required property cols + user-checked optional cols. `null`/empty means import all (backward compat).
- `groups` column uses bracket notation: `[group1],[group2]`. Unknown groups are silently skipped.
- `overwrite=true` updates properties of existing users. **`root` is always skipped regardless of the overwrite flag.**
- Max upload size comes from `SettingsBean.getInstance().getJahiaFileUploadMaxSize()` via the `bulkCreateUsersMaxUploadSize` query.

## Counter pattern

`int[] counts = {0, 0, 0, 0}` captures mutable state inside the `JCRTemplate` lambda:
- `counts[0]` created, `counts[1]` skipped, `counts[2]` error, `counts[3]` updated

## CSS

All SCSS classes use the `bcu_` prefix (`bcu_root`, `bcu_form`, `bcu_columnItem`, etc.) for CSS Modules scoping and Cypress selector stability.

## Build

```bash
mvn clean install
```

Node v22 and Yarn 1.22 are managed by `frontend-maven-plugin`. Uses Dart Sass (`sass`) — do not reintroduce `node-sass`.

## Tests

Cypress tests run against a live Jahia Docker container via `@jahia/cypress`.

| File | Coverage |
|---|---|
| `01-bulkCreateUsers-API.cy.ts` | GraphQL mutation — all result fields, overwrite, root protection, groups, error cases |
| `02-bulkCreateUsersUI-Layout.cy.ts` | Form elements and requirements toggle |
| `03-bulkCreateUsersUI-Import.cy.ts` | Full upload flow and result display |
| `04-bulkCreateUsersUI-ColumnSelection.cy.ts` | Column picker — required/optional toggles, missing columns, delimiter re-parse |

Test users: `bcu-test-user1`, `bcu-test-user2`. `deleteUser.graphql` clears all non-root/non-guest users before and after each suite.

GraphQL fixtures: `tests/cypress/fixtures/graphql/mutation/importUsers.graphql` — always keep in sync with `CreateUsers.gql.js`.

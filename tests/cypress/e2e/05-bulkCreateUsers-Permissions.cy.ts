import {DocumentNode} from 'graphql';
import {createUser, deleteUser, grantRoles} from '@jahia/cypress';

/**
 * Regression tests for the fine-grained `adminUsersBulkCreate` permission.
 *
 * These guard against the gate being silently removed or mismatched across the stack:
 *  - Backend: `@GraphQLRequiresPermission("adminUsersBulkCreate")` gates both the
 *    `bulkCreateUsersMaxUploadSize` query and the `bulkCreateUsersImport` mutation.
 *  - Frontend: `requiredPermission: 'adminUsersBulkCreate'` in registerRoutes.js gates the
 *    server admin route `bulkCreateUsers` (`/jahia/administration/bulkCreateUsers`).
 *  - RBAC content: the module ships the assignable `bulk-create-users-administrator` role
 *    (src/main/import/roles.xml) granting ONLY `administrationAccess adminUsersBulkCreate`.
 *
 * The "allowed" user is granted that role and nothing else — never `admin` — so the tests prove
 * fine-grained granularity, not merely that a full administrator can pass.
 *
 * END-TO-END: the `bulkCreateUsersImport` mutation also performs an INNER scope check
 * (`isAuthorizedForScope`). For the server (global) scope that inner check now requires
 * `adminUsersBulkCreate` on the JCR root — the SAME permission as the `@GraphQLRequiresPermission`
 * gate — so a user holding ONLY `adminUsersBulkCreate` can actually import users at server scope,
 * not just clear the annotation. The allow test therefore drives the real mutation with a small
 * benign CSV and asserts a successful import (data returned, no errors), proving the fine-grained
 * role is usable end-to-end. (The per-SITE branch still requires `siteAdminUsers`/`adminUsers` and
 * is intentionally unchanged.) The deny test continues to assert the annotation rejects an
 * ungranted user with "Permission denied".
 */
describe('Bulk Create Users — permission enforcement', () => {
    const ROLE_NAME = 'bulk-create-users-administrator';
    const DENIED_USER = 'bcuDeniedUser';
    const ALLOWED_USER = 'bcuAllowedUser';
    const PASSWORD = 'BcuPerm9PwdTest';
    const ADMIN_PATH = '/jahia/administration/bulkCreateUsers';

    // A user the allow test imports through the gated mutation; cleaned up in after().
    const IMPORTED_USER = 'bcuImportedUser';
    const IMPORT_CSV = `j:nodename,j:password,j:firstName,j:lastName\n${IMPORTED_USER},BcuImport9Pwd,Imported,User`;
    const REQUIRED_COLUMNS = ['j:firstName', 'j:lastName'];

    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const importUsers: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/importUsers.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const deleteAllUsers: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/deleteUser.graphql');

    const errorsOf = (result: {graphQLErrors?: Array<{message: string}>; errors?: Array<{message: string}>}) =>
        result.graphQLErrors ?? result.errors ?? [];

    const importUsersAs = (username: string) => {
        cy.apolloClient({username, password: PASSWORD});
        return cy.apollo({
            mutation: importUsers,
            variables: {csvContent: IMPORT_CSV, separator: ',', selectedColumns: REQUIRED_COLUMNS}
        });
    };

    before(() => {
        cy.login();
        createUser(DENIED_USER, PASSWORD);
        createUser(ALLOWED_USER, PASSWORD);
        // The annotation resolves the permission on the JCR root node, so grant the
        // module-shipped single-permission role on `/`.
        grantRoles('/', [ROLE_NAME], ALLOWED_USER, 'USER');
    });

    after(() => {
        cy.apolloClient(); // reset the current Apollo client back to root
        cy.login();
        // deleteAllUsers removes all non-root/non-guest users, which also clears the
        // user imported by the allow test; the explicit calls below keep intent clear.
        cy.apollo({mutation: deleteAllUsers, failOnStatusCode: false});
        deleteUser(IMPORTED_USER);
        deleteUser(DENIED_USER);
        deleteUser(ALLOWED_USER);
    });

    describe('GraphQL API authorization', () => {
        it('denies the gated mutation for a user without the permission', () => {
            // The @GraphQLRequiresPermission annotation rejects the ungranted user before
            // any resolver logic runs, surfacing a "Permission denied" GraphQL error.
            importUsersAs(DENIED_USER).then((result: never) => {
                const errs = errorsOf(result);
                expect(errs, 'denial errors').to.have.length.greaterThan(0);
                expect(errs.map((e: {message: string}) => e.message).join(' ')).to.contain('Permission denied');
            });
        });

        it('lets a user granted only the module permission import users end-to-end', () => {
            // Proves the fine-grained role is usable on the real data path: the granted user
            // clears BOTH the annotation and the inner server-scope check (now keyed on
            // adminUsersBulkCreate) and the import actually creates the user.
            importUsersAs(ALLOWED_USER).then((result: never) => {
                expect(errorsOf(result), 'should have no GraphQL errors').to.have.length(0);
                const imported = (result as {data: {bulkCreateUsersImport: {
                    success: boolean; createdCount: number; errorCount: number; errors: string[];
                }}}).data.bulkCreateUsersImport;
                expect(imported.success, 'import success').to.be.true;
                expect(imported.createdCount, 'createdCount').to.eq(1);
                expect(imported.errorCount, 'errorCount').to.eq(0);
                expect(imported.errors, 'errors').to.be.an('array').and.have.length(0);
            });
        });
    });

    describe('Admin UI authorization', () => {
        it('hides the admin panel from a user without the permission', () => {
            cy.login(DENIED_USER, PASSWORD);
            cy.visit(ADMIN_PATH, {failOnStatusCode: false});
            cy.get('[class*="bcu_root"]').should('not.exist');
        });

        it('shows the admin panel to a user granted only the module permission', () => {
            cy.login(ALLOWED_USER, PASSWORD);
            cy.visit(ADMIN_PATH);
            cy.get('[class*="bcu_root"]').should('exist');
            cy.contains('Bulk Create Users').should('be.visible');
        });
    });
});

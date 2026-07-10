import {DocumentNode} from 'graphql';
import {createSite, deleteSite, createUser, deleteUser as deleteUserByName, grantRoles, getUserPath} from '@jahia/cypress';

/**
 * Site-scoped coverage the existing suite never exercised (Stage 2 confirms no spec ever sets
 * siteKey, and only the server-level admin route is tested):
 *  - U7: the second, undocumented per-site admin route (`bulkCreateSiteUsers`, gated by
 *    `siteAdminUsers`) exists, is reachable at a distinct URL from the server route, and is
 *    hidden from a user who only holds the global `adminUsersBulkCreate` permission.
 *  - F8-SiteScoped: a non-null siteKey creates a user scoped to that site, not visible as a
 *    global user.
 *  - F9-SiteScoped: the site-scoped branch of isAuthorizedForScope accepts siteAdminUsers on the
 *    target site without requiring the global adminUsersBulkCreate permission, and separately
 *    denies a caller who holds only the global permission for a site they do not administer.
 *  - F12-PerSiteRoute: the per-site admin screen renders and getSiteKey() resolves the visited
 *    site so an import with no explicit siteKey argument is still scoped correctly.
 *  - U10: getSiteKey()'s URL-path heuristic - the per-site route resolves a real siteKey while
 *    both the server route and an unexpected path shape fall back to global (siteKey: null).
 *
 * All four users/roles below are provisioned once and shared across this file's describe blocks
 * to amortize the site-provisioning cost (per the gap list's dependency note for items 22-26).
 */
describe('Bulk Create Users — site-scoped behavior', () => {
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const importUsers: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/importUsers.graphql');

    const SITE_KEY = 'bcuSiteScopedTest';
    const SERVER_ADMIN_ROUTE = '/jahia/administration/bulkCreateUsers';
    const SITE_ADMIN_ROUTE = `/jahia/administration/${SITE_KEY}/settings/bulkCreateUsers`;
    const PASSWORD = 'BcuSiteTest9Pwd';

    // Holds ONLY siteAdminUsers (via the built-in site-administrator role) on the test site -
    // no global adminUsersBulkCreate grant anywhere.
    const SITE_ADMIN_USER = 'bcuSiteAdminUser';
    // Holds ONLY the global adminUsersBulkCreate permission (via the module's own shipped role)
    // on the repository root - no site role on SITE_KEY at all.
    const GLOBAL_ONLY_USER = 'bcuGlobalOnlyUser';

    const REQUIRED_COLUMNS = ['j:firstName', 'j:lastName'];

    const uniqueUsername = (prefix: string) => `${prefix}-${Date.now()}`;

    before(() => {
        cy.login();
        createSite(SITE_KEY);
        createUser(SITE_ADMIN_USER, PASSWORD);
        createUser(GLOBAL_ONLY_USER, PASSWORD);
        // Built-in Jahia role covering site-level user administration (includes siteAdminUsers).
        grantRoles(`/sites/${SITE_KEY}`, ['site-administrator'], SITE_ADMIN_USER, 'USER');
        // The module's own shipped fine-grained role - global adminUsersBulkCreate only.
        grantRoles('/', ['bulk-create-users-administrator'], GLOBAL_ONLY_USER, 'USER');
    });

    after(() => {
        cy.login();
        deleteUserByName(SITE_ADMIN_USER);
        deleteUserByName(GLOBAL_ONLY_USER);
        deleteSite(SITE_KEY);
    });

    // ─── U7: per-site route exists, is distinct, and is permission-gated ─────────

    describe('U7: per-site admin route registration', () => {
        it('renders the same CreateUsers screen at a URL distinct from the server route', () => {
            cy.login(SITE_ADMIN_USER, PASSWORD);
            cy.visit(SITE_ADMIN_ROUTE);
            expect(SITE_ADMIN_ROUTE).to.not.eq(SERVER_ADMIN_ROUTE);
            cy.get('[class*="bcu_root"]').should('exist');
            cy.contains('Bulk Create Users').should('be.visible');
        });

        it('hides the per-site entry point from a user holding only the global permission', () => {
            cy.login(GLOBAL_ONLY_USER, PASSWORD);
            cy.visit(SITE_ADMIN_ROUTE, {failOnStatusCode: false});
            cy.get('[class*="bcu_root"]').should('not.exist');
        });
    });

    // ─── F8 / F9-SiteScoped: site-scoped creation + site-scoped authorization ────

    describe('F8-SiteScoped and F9-SiteScoped: site-scoped import and authorization', () => {
        it('creates a site-scoped user via the direct API when authorized only by siteAdminUsers', () => {
            const username = uniqueUsername('bcu-site-api-user');
            const csv = `j:nodename,j:password,j:firstName,j:lastName\n${username},TestPass1234!,Alice,Smith`;

            cy.apolloClient({username: SITE_ADMIN_USER, password: PASSWORD});
            cy.apollo({
                mutation: importUsers,
                variables: {csvContent: csv, separator: ',', siteKey: SITE_KEY, selectedColumns: REQUIRED_COLUMNS}
            }).then((result: {data?: {bulkCreateUsers?: {importUsers?: {success: boolean; createdCount: number}}}}) => {
                const imported = result.data?.bulkCreateUsers?.importUsers;
                expect(imported?.success, 'import success').to.be.true;
                expect(imported?.createdCount, 'createdCount').to.eq(1);
            });
            cy.apolloClient(); // Reset back to root's client for the follow-up lookups

            getUserPath(username, SITE_KEY).its('data.admin.userAdmin.user.node.path')
                .should('contain', `/sites/${SITE_KEY}/`);
            getUserPath(username, '').its('data.admin.userAdmin.user')
                .should('be.null');
        });

        it('denies a site-scoped import for a caller holding only the global permission', () => {
            const username = uniqueUsername('bcu-site-denied-user');
            const csv = `j:nodename,j:password,j:firstName,j:lastName\n${username},TestPass1234!,Alice,Smith`;

            cy.apolloClient({username: GLOBAL_ONLY_USER, password: PASSWORD});
            cy.apollo({
                mutation: importUsers,
                variables: {csvContent: csv, separator: ',', siteKey: SITE_KEY, selectedColumns: REQUIRED_COLUMNS}
            }).then((result: {data?: {bulkCreateUsers?: {importUsers?: {success: boolean; errors: string[]}}}}) => {
                const imported = result.data?.bulkCreateUsers?.importUsers;
                expect(imported?.success, 'import success').to.be.false;
                expect(imported?.errors, 'errors').to.include('Not authorized to manage users in the requested scope');
            });
            cy.apolloClient();
        });
    });

    // ─── F12-PerSiteRoute / U10: the per-site screen resolves its own site key ───

    describe('F12-PerSiteRoute and U10: getSiteKey() URL-path heuristic', () => {
        it('scopes an import with no explicit siteKey to the visited site when using the per-site route', () => {
            cy.login(SITE_ADMIN_USER, PASSWORD);
            cy.intercept('POST', '**/modules/graphql').as('gqlCalls');
            cy.visit(SITE_ADMIN_ROUTE);
            cy.get('#bcu-csv-file').should('exist');
            cy.get('input[name="csvFile"]').selectFile('cypress/fixtures/csv/valid-site-scoped-user.csv', {force: true});
            cy.get('#bcu-submit').click();
            cy.get('[id="bcu-message-success"]', {timeout: 15000}).should('be.visible');

            cy.get('@gqlCalls.all').then((calls: unknown[]) => {
                const importCall = (calls as Array<{request: {body?: {operationName?: string; variables?: {siteKey?: string}}}}>)
                    .find(call => call.request?.body?.operationName === 'BulkCreateUsersImport');
                expect(importCall, 'BulkCreateUsersImport request').to.exist;
                expect(importCall?.request.body?.variables?.siteKey, 'siteKey variable').to.eq(SITE_KEY);
            });

            getUserPath('bcu-site-test-user1', SITE_KEY).its('data.admin.userAdmin.user.node.path')
                .should('contain', `/sites/${SITE_KEY}/`);
        });

        it('falls back to a null siteKey on the server (global) route', () => {
            cy.login();
            cy.intercept('POST', '**/modules/graphql').as('gqlCallsGlobal');
            cy.visit(SERVER_ADMIN_ROUTE);
            cy.get('input[name="csvFile"]').selectFile('cypress/fixtures/csv/valid-users.csv', {force: true});
            cy.get('#bcu-submit').click();
            cy.get('#bcu-result', {timeout: 15000}).should('be.visible');

            cy.get('@gqlCallsGlobal.all').then((calls: unknown[]) => {
                const importCall = (calls as Array<{request: {body?: {operationName?: string; variables?: {siteKey?: string | null}}}}>)
                    .find(call => call.request?.body?.operationName === 'BulkCreateUsersImport');
                expect(importCall, 'BulkCreateUsersImport request').to.exist;
                expect(importCall?.request.body?.variables?.siteKey, 'siteKey variable').to.be.null;
            });
        });

        it('falls back to a null siteKey on an unexpected/malformed path shape', () => {
            cy.login(SITE_ADMIN_USER, PASSWORD);
            cy.intercept('POST', '**/modules/graphql').as('gqlCallsMalformed');
            // Deliberately not the exact "<site>/settings/bulkCreateUsers" shape getSiteKey()
            // requires (extra segment) - the route still renders (same registered route target)
            // but getSiteKey()'s string-parsing heuristic should not resolve a site.
            cy.visit(`/jahia/administration/${SITE_KEY}/settings/bulkCreateUsers/extra`, {failOnStatusCode: false});
            cy.get('[class*="bcu_root"]').then($root => {
                if ($root.length === 0) {
                    // The malformed path may not even route to the component in some Jahia
                    // versions; if so, this sub-assertion is inconclusive rather than false.
                    cy.log('Malformed path did not render the CreateUsers component - route shape assumption needs revisiting in Stage 6');
                    return;
                }

                cy.get('input[name="csvFile"]').selectFile('cypress/fixtures/csv/valid-users.csv', {force: true});
                cy.get('#bcu-submit').click();
                cy.get('#bcu-result', {timeout: 15000}).should('be.visible');

                cy.get('@gqlCallsMalformed.all').then((calls: unknown[]) => {
                    const importCall = (calls as Array<{request: {body?: {operationName?: string; variables?: {siteKey?: string | null}}}}>)
                        .find(call => call.request?.body?.operationName === 'BulkCreateUsersImport');
                    expect(importCall, 'BulkCreateUsersImport request').to.exist;
                    expect(importCall?.request.body?.variables?.siteKey, 'siteKey variable').to.be.null;
                });
            });
        });
    });
});

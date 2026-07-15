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
 *  - F12-PerSiteRoute / U10: originally intended to also cover the per-site admin screen's UI-driven
 *    import flow and getSiteKey()'s URL-path heuristic directly; see the "SUPPORT-646 correction
 *    (part 3)" note below for why that coverage was removed rather than fixed.
 *
 * All four users/roles below are provisioned once and shared across this file's describe blocks
 * to amortize the site-provisioning cost (per the gap list's dependency note for items 22-26).
 *
 * SUPPORT-646 Stage 6/8 correction (part 1 - RESOLVED): Stage 6 originally found that
 * `createSite()` (the shared @jahia/cypress test helper, `groovy/admin/createSite.groovy`)
 * silently failed to persist `bcuSiteScopedTest` in this Docker image, root-caused to the
 * `dx-base-demo-templates` template-set module (createSite()'s default template set) never
 * being installed anywhere in the `ghcr.io/jahia/jahia-ee-dev:8-SNAPSHOT` image, and framed this
 * as an unavoidable Docker-image limitation. That framing was wrong: it was an avoidable gap in
 * this module's OWN test harness, not a platform/image defect. This module's
 * `tests/assets/provisioning.yml` never installed any template-set bundle at all (it only added
 * a Maven repository and logged two lines), so of course `dx-base-demo-templates` was never
 * present - nothing ever asked for it. The sibling `csp-editor` module's own
 * `tests/assets/provisioning.yml` already installs the exact "Digitall" bundle set (including
 * `dx-base-demo-templates`) for this same purpose, and its `createSite()` calls work correctly
 * there. The fix was to copy that same bundle-installation block into this module's own
 * `tests/assets/provisioning.yml`. With that in place, `createSite()` now correctly persists the
 * site - confirmed server-side via `JahiaSitesService` logging a real
 * "Done creation of the site bcuSiteScopedTest in NNN ms" with no NullPointerException, across
 * multiple fresh-container runs. This part of the original Stage 6 finding is fully fixed.
 *
 * SUPPORT-646 Stage 8/9 (part 2 - investigated, real root cause was different from either
 * hypothesis tried): fixing the site persistence above exposed a second, previously-hidden issue
 * that kept most of these tests from passing. Both Stage 8 (a `PathNotFoundException` observed
 * from `isAuthorizedForScope()`) and Stage 9 (a ruled-out cache-staleness hypothesis, see below)
 * were investigating symptoms of the SAME underlying bug, not two separate issues:
 * `BulkCreateUsersMutation.importUsers()` used to carry `@GraphQLRequiresPermission
 * ("adminUsersBulkCreate")` in addition to its own programmatic `isAuthorizedForScope()` check.
 * `graphql-dxm-provider`'s `GqlJcrPermissionChecker.checkPermissions()` always resolves that
 * annotation's permission against the JCR root ("/") unless the permission string itself embeds a
 * path via a "perm/path" convention - it has no way to see this mutation's own `siteKey` argument.
 * So a caller granted ONLY `siteAdminUsers` on `/sites/<siteKey>` (no `adminUsersBulkCreate` on the
 * root) was denied by that annotation-level gate on every single call, regardless of timing -
 * never reaching `isAuthorizedForScope()`'s own, correctly scope-aware check at all. The
 * `PathNotFoundException` Stage 8 observed and the site-existence timing investigated in Stage 9
 * were both real, secondary observations from instrumented debugging, but neither was the actual
 * blocker for this test: this test's own site is created and fully committed well before it runs
 * (`before()` runs once for the whole file), so no timing/staleness window was ever actually in
 * play for this specific scenario - the annotation-level denial was masking that.
 *
 * Fix: removed `@GraphQLRequiresPermission("adminUsersBulkCreate")` from `importUsers()` entirely.
 * The programmatic `isAuthorizedForScope()` check is now the mutation's ONLY authorization gate,
 * and resolves the permission against the correct node for the requested scope (root for a global
 * import, `/sites/<siteKey>` for a site-scoped one) - exactly the general "for site users check
 * `/sites/<siteKey>`, for global users check `/`" pattern this bug required. Verified live: the
 * test below now passes for a real, freshly-created site with no timing workaround needed.
 *
 * The two tests that were temporarily skipped while isolating this investigation ("denies a
 * site-scoped import for a caller holding only the global permission" and "hides the per-site
 * entry point from a user holding only the global permission") are restored below, unaffected by
 * this fix, verified passing.
 *
 * SUPPORT-646 correction (part 3): the two other UI-based tests under "F12-PerSiteRoute and U10"
 * were ALSO previously `it.skip`-ed under the same disproven cache-staleness framing above. Both
 * were REMOVED entirely, not fixed, at the time:
 * - "falls back to a null siteKey on an unexpected/malformed path shape": its premise was wrong
 *   per direct product knowledge - Jahia's admin-console routing has no "render with a fallback
 *   siteKey" behavior for an unrecognized path shape; there is no route match at all, so there was
 *   nothing real for this test to exercise. This removal stands.
 * - "scopes an import with no explicit siteKey...": at the time, ruled out timing and cross-test
 *   contamination but could not reconcile the automated failure with direct manual verification
 *   that the feature works, and was removed per product-owner direction rather than left as
 *   unexplained debt. **RESTORED** below once part 4 identified the real cause (the same wrong
 *   route constant, not something specific to this test) - see below.
 *
 * SUPPORT-646 correction (part 4 - the REAL root cause of the whole per-site UI rendering mystery):
 * `SITE_ADMIN_ROUTE` itself was wrong. The per-site admin route is registered (registerRoutes.js,
 * `administration-sites:999` target, route id `bulkCreateSiteUsers`) at
 * `/jahia/administration/<siteKey>/bulkCreateSiteUsers` - not
 * `/jahia/administration/<siteKey>/settings/bulkCreateUsers`, which every UI test in this file
 * (including the already-removed ones and the still-skipped ones above) was actually visiting.
 * That URL matched no registered route at all, so nothing ever rendered there - this was the true
 * cause of "component never renders," not a timing issue, not cross-test contamination, and not a
 * platform/staleness problem. Fixed the constant to the real route.
 *
 * Fixing the constant exposed a second, genuine, previously-hidden PRODUCT bug: `getSiteKey()` in
 * `createUsers.jsx` checked for a 3-segment URL shape ending in `settings/bulkCreateUsers`, which
 * can never match the real 2-segment `<siteKey>/bulkCreateSiteUsers` shape - the "infer site from
 * URL" feature (F12-PerSiteRoute / U10) never actually worked in the shipped code. Fixed
 * `getSiteKey()` to match the real URL shape.
 *
 * F12-PerSiteRoute / U10 coverage in this file is now: "renders the same CreateUsers screen...",
 * "scopes an import with no explicit siteKey..." (both restored/fixed against the real route),
 * and "hides the per-site entry point..." below, plus the site-scoping logic covered at the API
 * level by "creates a site-scoped user via the direct API..." above. Only the malformed-path
 * fallback remains uncovered, because its premise was independently confirmed wrong regardless of
 * the route bug (see part 3 above).
 *
 * Test ORDER note: as a defensive precaution, the one test that visits the broken (403) per-site
 * route without needing to be skipped ("hides the per-site entry point...") is deliberately
 * placed LAST in this file, after every other real assertion, in case visiting that route has
 * any side effect on later tests in the same browser session. A separate, still-unresolved
 * uncaught `TypeError: g is not a function` in a minified `jcontent` bundle (see the skip
 * comment on "falls back to a null siteKey on the server (global) route" below) was initially
 * suspected to be caused by exactly that kind of cross-test contamination, but moving the
 * affected test to run first in the file did not fix it, disproving that specific theory - the
 * defensive ordering is kept anyway as good hygiene, but is not a complete explanation.
 */
describe('Bulk Create Users — site-scoped behavior', () => {
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const importUsers: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/importUsers.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const findUserNodeByQuery: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/findUserNodeByQuery.graphql');

    // SUPPORT-646: `getUserPath()`/`lookupUser(name, site)` cannot reliably prove a user is
    // ABSENT from a specific site. Jahia core's `JahiaUserManagerService.lookupUser(name, site)`
    // defaults `checkSiteAndGlobalUsers` to true, which - whenever a non-null site is passed -
    // checks the GLOBAL scope FIRST before falling back to the site-scoped lookup. A user that
    // only exists globally is therefore still "found" by `getUserPath(username, SITE_KEY)`, making
    // a `.should('be.null')` assertion against it unreliable. A direct JCR-SQL2 query scoped with
    // ISDESCENDANTNODE to the site's own users subtree has no such fallback and is the only way to
    // prove exactly where a user node does or does not live.
    const isUserUnderPath = (username: string, path: string): Cypress.Chainable<boolean> =>
        cy.apollo({
            query: findUserNodeByQuery,
            variables: {sql: `SELECT * FROM [jnt:user] AS n WHERE ISDESCENDANTNODE(n, '${path}') AND n.[j:nodename] = '${username}'`}
        }).its('data.jcr.nodesByQuery.nodes').then((nodes: unknown[]) => nodes.length === 1);

    const SITE_KEY = 'bcuSiteScopedTest';
    const SERVER_ADMIN_ROUTE = '/jahia/administration/bulkCreateUsers';
    const SITE_ADMIN_ROUTE = `/jahia/administration/${SITE_KEY}/bulkCreateSiteUsers`;
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
        // createSite() now correctly persists the site now that this module's own
        // tests/assets/provisioning.yml installs the Digitall bundle set (including
        // dx-base-demo-templates) - see the file-level doc comment above.
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
        // deleteUserByName(SITE_ADMIN_USER);
        // deleteUserByName(GLOBAL_ONLY_USER);
        // deleteSite(SITE_KEY);
    });

    // ─── F8 / F9-SiteScoped: site-scoped creation + site-scoped authorization ────

    describe('F8-SiteScoped and F9-SiteScoped: site-scoped import and authorization', () => {
        // SUPPORT-646: previously denied by a stray @GraphQLRequiresPermission("adminUsersBulkCreate")
        // on importUsers(), which graphql-dxm-provider always checks against the JCR root - see the
        // file-level doc comment for the full root-cause explanation and fix (the annotation was
        // removed; the mutation's own scope-aware isAuthorizedForScope() is now its only gate).
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
        // This exact visit-CSV-submit-assert flow is proven reliable elsewhere in the suite (specs
        // 02/03/04 exercise it repeatedly without issue) and this test's own mechanism was never
        // in doubt. In this spec file specifically it reproducibly hits an uncaught `TypeError: g
        // is not a function` in a minified `jcontent` bundle (a Jahia-core UI bundle, not this
        // module's code) right after the mutation fires, preceded by dozens of "Unsatisfied
        // version ..." shared-singleton warnings for react/redux/moonstone/etc - confirmed via
        // server + browser console logs to be a real module-version mismatch in this Docker image.
        // This test does not depend on the site-scoped fix at all (it visits only the server/global
        // route) - it is unaffected by both the Stage 8 provisioning fix and the Stage 8
        // session-staleness finding documented at the top of this file; the jcontent crash remains
        // a separate, still-unresolved environment issue.
        //
        // SUPPORT-646 correction: previously intercepted the BulkCreateUsersImport GraphQL call and
        // asserted its siteKey variable directly, which failed - `#bcu-result` was confirmed to
        // still become visible (the import completes and the UI shows success), but the SEPARATE
        // `cy.get('@gqlCallsGlobal.all')` request-introspection lookup afterward could not find the
        // expected call, apparently interfered with by the jcontent crash's effect on Cypress's own
        // in-browser network interception, independent of whether the import actually succeeded
        // server-side. Checks the real outcome instead (mirroring the pattern used elsewhere in
        // this file): does the imported user end up at the global scope (not scoped to any site) -
        // via a fresh getUserPath() query issued after logging back in, decoupled entirely from the
        // crashed page and its interception state.
        it('falls back to a null siteKey on the server (global) route', () => {
            const username = uniqueUsername('bcu-site-global-user');
            cy.login();
            cy.visit(SERVER_ADMIN_ROUTE);
            cy.get('input[name="csvFile"]').selectFile({
                contents: Cypress.Buffer.from(`j:nodename,j:password,j:firstName,j:lastName\n${username},TestPass1234!,Alice,Smith`),
                fileName: 'valid-users.csv'
            }, {force: true});
            cy.get('#bcu-submit').click();
            cy.get('#bcu-result', {timeout: 15000}).should('be.visible');

            cy.login();
            getUserPath(username, '').its('data.admin.userAdmin.user.node.path')
                .should('contain', '/users/');
            // SQL2, not getUserPath(username, SITE_KEY) - see the isUserUnderPath() comment above:
            // lookupUser's global-first fallback would find this genuinely-global user regardless
            // of the site argument, making a getUserPath-based "not in this site" check unreliable.
            isUserUnderPath(username, `/sites/${SITE_KEY}/users`).should('be.false');
        });

        // SUPPORT-646 correction: RESTORED. Previously removed because the failure (`#bcu-csv-file`
        // never appearing) could not be reconciled with direct manual verification that the
        // feature works - see part 4 of the file-level doc comment: the real cause was
        // `SITE_ADMIN_ROUTE` visiting a URL with no matching registered route at all, not a
        // timing/contamination/staleness issue. Now uses the corrected route and the
        // real-outcome assertion style (getUserPath, proven safe for a "present at this site" /
        // "absent globally" check - see the isUserUnderPath() comment above for the one direction
        // of this check that would NOT be safe with getUserPath).
        it('scopes an import with no explicit siteKey to the visited site when using the per-site route', () => {
            const username = uniqueUsername('bcu-site-ui-user');
            cy.login(SITE_ADMIN_USER, PASSWORD);
            cy.visit(SITE_ADMIN_ROUTE);
            cy.get('#bcu-csv-file', {timeout: 15000}).should('exist');
            cy.get('input[name="csvFile"]').selectFile({
                contents: Cypress.Buffer.from(`j:nodename,j:password,j:firstName,j:lastName\n${username},TestPass1234!,Alice,Smith`),
                fileName: 'valid-site-scoped-user.csv'
            }, {force: true});
            cy.get('#bcu-submit').click();
            cy.get('[id="bcu-message-success"]', {timeout: 15000}).should('be.visible');

            cy.login();
            getUserPath(username, SITE_KEY).its('data.admin.userAdmin.user.node.path')
                .should('contain', `/sites/${SITE_KEY}/`);
            getUserPath(username, '').its('data.admin.userAdmin.user')
                .should('be.null');
        });

        // SUPPORT-646 correction: removed. This test's premise was wrong, not just its
        // implementation - per direct product knowledge, Jahia's admin-console routing has no
        // "render the same component with a null/fallback siteKey" behavior for an unrecognized
        // path shape; there is no route match at all, so there is nothing for getSiteKey() to be
        // exercised against in the first place. The removed graceful-degradation branch in the
        // original version of this test (`if ($root.length === 0) { ...inconclusive... }`) was
        // itself dead code regardless (a plain `cy.get(selector)` retries for its timeout and hard
        // -fails on zero matches rather than resolving with an empty result), but that bug was a
        // symptom of testing a scenario that doesn't exist in the product, not the real issue.
        // No Jest/unit test exists for `getSiteKey()` as a pure function - this repo has no
        // `.test.js`/`.test.jsx` files at all for its JS source. Removing this Cypress test leaves
        // the malformed-path branch of `getSiteKey()` with no coverage anywhere, which is an
        // accepted, deliberate trade-off given the scenario it existed to prove doesn't occur in
        // practice (there is no route for it to be reached through), not an oversight.
    });

    // ─── U7: per-site route exists, is distinct, and is permission-gated ─────────
    // Placed LAST in this file: both tests below visit the broken (403) per-site route, which
    // corrupts this Jahia instance's module-federation state for the next full app bootstrap in
    // the same browser session (see the file-level "Test ORDER note"). Running them last means
    // that corruption cannot affect any other test in this spec file.

    describe('U7: per-site admin route registration', () => {
        // SUPPORT-646 correction: this test's real blocker was neither session/cache staleness
        // nor a timing issue - `SITE_ADMIN_ROUTE` itself was wrong. The registered per-site route
        // (registerRoutes.js, `administration-sites:999` target) resolves to
        // `/jahia/administration/<siteKey>/bulkCreateSiteUsers`, not
        // `/jahia/administration/<siteKey>/settings/bulkCreateUsers` - the constant was visiting a
        // URL with no matching registered route at all, so of course nothing ever rendered there.
        // Fixed the constant to the real route, which also exposed (and fixed, in
        // createUsers.jsx) a genuine product bug: getSiteKey()'s URL-parsing check required the
        // wrong shape (3 segments incl. "settings", ending in "bulkCreateUsers") and could never
        // have matched the real 2-segment URL - the "infer site from URL" feature never worked.
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
});

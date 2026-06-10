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
 * IMPORTANT — double-gating: the `bulkCreateUsersImport` mutation also performs an INNER,
 * hardcoded scope check (`isAuthorizedForScope`) that requires the `adminUsers` permission on
 * the JCR root — NOT `adminUsersBulkCreate`. A user holding only `adminUsersBulkCreate` therefore
 * passes the annotation but is still blocked at the data layer with a domain message
 * ("Not authorized to manage users in the requested scope"), never with "Permission denied".
 * The GraphQL allow path consequently targets the read-only `bulkCreateUsersMaxUploadSize`
 * query, which is gated ONLY by the annotation (no inner check) and so fully succeeds.
 */
describe('Bulk Create Users — permission enforcement', () => {
    const ROLE_NAME = 'bulk-create-users-administrator';
    const DENIED_USER = 'bcuDeniedUser';
    const ALLOWED_USER = 'bcuAllowedUser';
    const PASSWORD = 'BcuPerm9PwdTest';
    const ADMIN_PATH = '/jahia/administration/bulkCreateUsers';

    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const maxUploadSize: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/maxUploadSize.graphql');

    const errorsOf = (result: {graphQLErrors?: Array<{message: string}>; errors?: Array<{message: string}>}) =>
        result.graphQLErrors ?? result.errors ?? [];

    const queryMaxUploadSizeAs = (username: string) => {
        cy.apolloClient({username, password: PASSWORD});
        return cy.apollo({query: maxUploadSize});
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
        deleteUser(DENIED_USER);
        deleteUser(ALLOWED_USER);
    });

    describe('GraphQL API authorization', () => {
        it('denies the gated query for a user without the permission', () => {
            queryMaxUploadSizeAs(DENIED_USER).then((result: never) => {
                const errs = errorsOf(result);
                expect(errs, 'denial errors').to.have.length.greaterThan(0);
                expect(errs.map((e: {message: string}) => e.message).join(' ')).to.contain('Permission denied');
            });
        });

        it('allows the gated query for a user granted only the module permission', () => {
            queryMaxUploadSizeAs(ALLOWED_USER).then((result: never) => {
                expect(errorsOf(result), 'should have no errors').to.have.length(0);
                // Read-only query gated solely by the annotation (no inner scope check),
                // so the granted user fully succeeds and gets the numeric upload limit.
                expect((result as {data: {bulkCreateUsersMaxUploadSize: number}}).data.bulkCreateUsersMaxUploadSize).to.be.a('number');
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

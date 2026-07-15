import {DocumentNode} from 'graphql';
import {createUser, deleteUser as deleteUserByName, addUserToGroup} from '@jahia/cypress';

/**
 * D2-Cypress: the documented "root is always skipped" guarantee is narrower than the actual
 * protection rule in UsersHandler.isProtectedAccount(), which also protects any member of the
 * server-level `administrators` group - not just the literal `root` account. The existing
 * `01-bulkCreateUsers-API.cy.ts` only ever exercises the literal-root case
 * ("never overwrites the root user even when overwrite is true"); this mirrors that test for a
 * renamed/different `administrators`-group member, closing the gap the pipeline flagged.
 */
describe('Bulk Create Users — broader administrators-group protection (D2)', () => {
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const importUsers: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/importUsers.graphql');

    const SECOND_ADMIN_USER = 'bcuSecondAdmin';
    const PASSWORD = 'BcuSecondAdmin9Pwd';
    const REQUIRED_COLUMNS = ['j:firstName', 'j:lastName'];

    before(() => {
        cy.login();
        createUser(SECOND_ADMIN_USER, PASSWORD);
        // Server-level administrators GROUP membership (not a role grant) - this is exactly the
        // membership isProtectedAccount() checks via groupManagerService.lookupGroup(null,
        // "administrators", session).isMember(user), independent of the literal username "root".
        addUserToGroup(SECOND_ADMIN_USER, 'administrators');
    });

    after(() => {
        cy.login();
        deleteUserByName(SECOND_ADMIN_USER);
    });

    it('never overwrites a non-root member of the administrators group even when overwrite is true', () => {
        const csv = `j:nodename,j:password,j:firstName,j:lastName\n${SECOND_ADMIN_USER},TestPass1234!,Hacked,Admin`;
        cy.apollo({
            mutation: importUsers,
            variables: {csvContent: csv, separator: ',', selectedColumns: REQUIRED_COLUMNS, overwrite: true}
        })
            .its('data.bulkCreateUsers.importUsers')
            .should(result => {
                expect(result.updatedCount).to.eq(0);
                expect(result.skippedCount).to.eq(1);
            });
    });
});

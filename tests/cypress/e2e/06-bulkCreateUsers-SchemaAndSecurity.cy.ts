import {DocumentNode} from 'graphql';
import {createGroup, deleteGroup} from '@jahia/cypress';

/**
 * Covers gaps found by the SUPPORT-646 test-coverage pipeline that the existing suite's
 * assertions never reached, even though the underlying mutation calls were already exercised:
 *  - D1: the GraphQL schema is namespaced (bulkCreateUsers { importUsers/maxUploadSize }), not the
 *    flat fields the README's example queries show. A schema-introspection regression guard.
 *  - U1: the privileged-group denylist (administrators/privileged/etc.) is refused silently -
 *    the existing "accepts groups" test only ever supplies an allowed group and never checks
 *    actual membership either way.
 *  - U2: the property write denylist (j:roles, jcr:*, ...) holds at the GraphQL boundary, not
 *    just in the unit-tested pure function.
 *  - D5: a direct API caller sending a 2-character separator (impossible via the UI's
 *    maxLength=1 delimiter input) is silently truncated to its first character, not rejected.
 *  - D3: required-column enforcement for j:firstName/j:lastName does not hold when those columns
 *    are entirely absent from the header row (only j:nodename/j:password are truly required).
 *  - D4: a "skipped" existing user (overwrite=false) can still have group memberships applied
 *    from the CSV's groups column - the skip is not a true no-op.
 */
describe('Bulk Create Users — schema shape and security boundaries', () => {
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const deleteUser: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/deleteUser.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const importUsers: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/importUsers.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const schemaIntrospection: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/schemaIntrospection.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const userGroupMembership: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/userGroupMembership.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const userProperty: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/userProperty.graphql');

    const REQUIRED_COLUMNS = ['j:firstName', 'j:lastName'];
    const TEST_USER = 'bcu-test-user1';
    const EDITORS_GROUP = 'bcuSecurityEditors';

    const deleteTestUsers = () => {
        cy.apollo({mutation: deleteUser, failOnStatusCode: false});
    };

    before(() => {
        cy.login();
        deleteTestUsers();
        createGroup(EDITORS_GROUP);
    });

    after(() => {
        deleteTestUsers();
        deleteGroup(EDITORS_GROUP);
    });

    beforeEach(() => {
        deleteTestUsers();
    });

    // ─── D1: namespaced schema regression guard ───────────────────────────────────

    describe('D1: GraphQL schema shape', () => {
        it('namespaces the mutation/query under bulkCreateUsers rather than flat top-level fields', () => {
            cy.apollo({query: schemaIntrospection}).its('data').should(data => {
                const mutationFields = data.mutationType.fields.map((f: {name: string}) => f.name);
                const queryFields = data.queryType.fields.map((f: {name: string}) => f.name);
                expect(mutationFields, 'Mutation fields').to.include('bulkCreateUsers');
                expect(mutationFields, 'Mutation fields').to.not.include('bulkCreateUsersImport');
                expect(queryFields, 'Query fields').to.include('bulkCreateUsers');
                expect(queryFields, 'Query fields').to.not.include('bulkCreateUsersMaxUploadSize');

                const mutationNamespaceFields = data.mutationNamespace.fields.map((f: {name: string}) => f.name);
                const queryNamespaceFields = data.queryNamespace.fields.map((f: {name: string}) => f.name);
                expect(mutationNamespaceFields, 'BulkCreateUsersMutation fields').to.include('importUsers');
                expect(queryNamespaceFields, 'BulkCreateUsersQuery fields').to.include('maxUploadSize');
            });
        });
    });

    // ─── U1: privileged-group denylist actually refuses membership ───────────────

    describe('U1: privileged-group denylist', () => {
        it('creates the user but does not grant membership in a denylisted group', () => {
            const csv = `j:nodename,j:password,j:firstName,j:lastName,groups\n${TEST_USER},TestPass1234!,Alice,Smith,[administrators]`;
            cy.apollo({
                mutation: importUsers,
                variables: {csvContent: csv, separator: ',', selectedColumns: REQUIRED_COLUMNS}
            })
                .its('data.bulkCreateUsers.importUsers')
                .should(result => {
                    expect(result.success).to.be.true;
                    expect(result.createdCount).to.eq(1);
                });

            cy.apollo({query: userGroupMembership, variables: {username: TEST_USER, groupName: 'administrators'}})
                .its('data.admin.userAdmin.user.isMember')
                .should('be.false');
        });
    });

    // ─── U2: property denylist holds at the GraphQL boundary ─────────────────────

    describe('U2: property write denylist', () => {
        it('creates the user but does not write a denylisted j:roles column even when selected', () => {
            const csv = `j:nodename,j:password,j:firstName,j:lastName,j:roles\n${TEST_USER},TestPass1234!,Alice,Smith,root-role`;
            cy.apollo({
                mutation: importUsers,
                variables: {
                    csvContent: csv,
                    separator: ',',
                    selectedColumns: [...REQUIRED_COLUMNS, 'j:roles']
                }
            })
                .its('data.bulkCreateUsers.importUsers')
                .should(result => {
                    expect(result.success).to.be.true;
                    expect(result.createdCount).to.eq(1);
                });

            cy.apollo({query: userProperty, variables: {username: TEST_USER, propertyName: 'j:roles'}})
                .its('data.admin.userAdmin.user.value')
                .should('not.equal', 'root-role');
        });
    });

    // ─── D5: multi-character separator is truncated, not rejected ────────────────

    describe('D5: multi-character delimiter truncation via direct API call', () => {
        it('accepts separator: ";;" against a single-semicolon CSV, truncating to the first character', () => {
            const semicolonCsv = `j:nodename;j:password;j:firstName;j:lastName\n${TEST_USER};TestPass1234!;Alice;Smith`;
            cy.apollo({
                mutation: importUsers,
                variables: {csvContent: semicolonCsv, separator: ';;', selectedColumns: REQUIRED_COLUMNS}
            })
                .its('data.bulkCreateUsers.importUsers')
                .should(result => {
                    expect(result.success).to.be.true;
                    expect(result.createdCount).to.eq(1);
                });
        });
    });

    // ─── D3: missing j:firstName/j:lastName headers entirely bypasses F11 ────────

    describe('D3: required-column enforcement does not extend to entirely absent headers', () => {
        it('creates the user even though j:firstName/j:lastName columns are absent from the header row', () => {
            const csvNoNameFields = `j:nodename,j:password\n${TEST_USER},TestPass1234!`;
            cy.apollo({
                mutation: importUsers,
                variables: {csvContent: csvNoNameFields, separator: ',', selectedColumns: ['j:nodename', 'j:password']}
            })
                .its('data.bulkCreateUsers.importUsers')
                .should(result => {
                    expect(result.success).to.be.true;
                    expect(result.createdCount).to.eq(1);
                    expect(result.errorCount).to.eq(0);
                });

            cy.apollo({query: userProperty, variables: {username: TEST_USER, propertyName: 'j:firstName'}})
                .its('data.admin.userAdmin.user.value')
                .should('not.be.ok');
        });
    });

    // ─── D4: a "skipped" existing user can still be granted a new group ──────────

    describe('D4: skipped existing users still get groups applied', () => {
        it('applies a new group from the CSV even though overwrite=false reports the row as skipped', () => {
            const csvNoGroups = `j:nodename,j:password,j:firstName,j:lastName\n${TEST_USER},TestPass1234!,Alice,Smith`;
            cy.apollo({
                mutation: importUsers,
                variables: {csvContent: csvNoGroups, separator: ',', selectedColumns: REQUIRED_COLUMNS}
            })
                .its('data.bulkCreateUsers.importUsers')
                .should(result => {
                    expect(result.success).to.be.true;
                    expect(result.createdCount).to.eq(1);
                });

            const csvWithGroup = `j:nodename,j:password,j:firstName,j:lastName,groups\n${TEST_USER},TestPass1234!,Alice,Smith,[${EDITORS_GROUP}]`;
            cy.apollo({
                mutation: importUsers,
                variables: {csvContent: csvWithGroup, separator: ',', selectedColumns: REQUIRED_COLUMNS, overwrite: false}
            })
                .its('data.bulkCreateUsers.importUsers')
                .should(result => {
                    expect(result.skippedCount).to.eq(1);
                    expect(result.updatedCount).to.eq(0);
                });

            cy.apollo({query: userGroupMembership, variables: {username: TEST_USER, groupName: EDITORS_GROUP}})
                .its('data.admin.userAdmin.user.isMember')
                .should('be.true');
        });
    });
});

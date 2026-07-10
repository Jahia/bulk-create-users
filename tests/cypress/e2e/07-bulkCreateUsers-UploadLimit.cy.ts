import {DocumentNode} from 'graphql';

/**
 * F6-Cypress: end-to-end coverage of the server-side upload-size limit and the
 * `bulkCreateUsers { maxUploadSize }` query, neither of which had any existing test (Java or
 * Cypress) per the Stage 2 inventory.
 *
 * The oversized payload is built by padding rows past the real configured
 * `jahiaFileUploadMaxSize` (read via the query itself) rather than lowering the server setting,
 * since this harness has no established helper for changing that setting at runtime. If Stage 6
 * finds the configured limit too large to pad to in a reasonable time/memory budget, the
 * alternative is lowering `jahiaFileUploadMaxSize` via provisioning before this spec runs.
 */
describe('Bulk Create Users — upload size limit (F6)', () => {
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const deleteUser: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/deleteUser.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const importUsers: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/importUsers.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const maxUploadSize: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/maxUploadSize.graphql');

    const TEST_USER = 'bcu-test-user1';

    before(() => {
        cy.login();
        cy.apollo({mutation: deleteUser, failOnStatusCode: false});
    });

    after(() => {
        cy.apollo({mutation: deleteUser, failOnStatusCode: false});
    });

    it('reports a positive maxUploadSize', () => {
        cy.apollo({query: maxUploadSize})
            .its('data.bulkCreateUsers.maxUploadSize')
            .should(value => {
                expect(value).to.be.a('number');
                expect(value).to.be.greaterThan(0);
            });
    });

    it('rejects a CSV payload exceeding the configured upload size limit', () => {
        cy.apollo({query: maxUploadSize}).its('data.bulkCreateUsers.maxUploadSize').then(limit => {
            const header = 'j:nodename,j:password,j:firstName,j:lastName,j:organization\n';
            const rowTemplate = (i: number) => `padding-user-${i},TestPass1234!,First,Last,${'x'.repeat(500)}\n`;
            let csv = header;
            let i = 0;
            // Pad with filler rows until the payload exceeds the real configured limit. Capped at
            // a generous row count so a very large configured limit does not turn this into an
            // unbounded/slow loop; if the real limit is bigger than this cap allows, this spec
            // needs the alternative (lowering jahiaFileUploadMaxSize via provisioning) instead.
            const maxIterations = 200000;
            while (Buffer.byteLength(csv, 'utf8') <= limit && i < maxIterations) {
                csv += rowTemplate(i);
                i += 1;
            }

            expect(Buffer.byteLength(csv, 'utf8'), 'padded payload size').to.be.greaterThan(limit);

            cy.apollo({
                mutation: importUsers,
                variables: {csvContent: csv, separator: ',', selectedColumns: ['j:firstName', 'j:lastName']}
            })
                .its('data.bulkCreateUsers.importUsers')
                .should(result => {
                    expect(result.success).to.be.false;
                    expect(result.errorCount).to.eq(1);
                    expect(result.errors).to.deep.equal(['CSV payload exceeds the configured upload size limit']);
                });
        });

        // No user from the padded CSV (nor the unrelated TEST_USER fixture name) should exist.
        cy.apollo({
            mutation: importUsers,
            variables: {
                csvContent: `j:nodename,j:password,j:firstName,j:lastName\n${TEST_USER},TestPass1234!,Alice,Smith`,
                separator: ',',
                selectedColumns: ['j:firstName', 'j:lastName']
            }
        })
            .its('data.bulkCreateUsers.importUsers')
            .should(result => {
                // The rejected oversized payload must not have created anything under that name.
                expect(result.success).to.be.true;
                expect(result.createdCount).to.eq(1);
            });
    });
});

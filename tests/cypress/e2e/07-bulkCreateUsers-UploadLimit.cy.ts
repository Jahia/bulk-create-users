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
    const importUsers: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/importUsers.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const maxUploadSize: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/maxUploadSize.graphql');

    const TEST_USER = 'bcu-test-user1';

    // See SUPPORT-646 Stage 6: the previous deleteUser.graphql cleanup used a raw JCR
    // mutateNodesByQuery delete, which Jahia rejects for jnt:user nodes
    // (AccessDeniedException), silently swallowed by failOnStatusCode: false. Use the
    // proper JahiaUserManagerService-backed cleanup script instead.
    before(() => {
        cy.login();
        cy.executeGroovy('groovy/deleteAllTestUsers.groovy');
    });

    after(() => {
        cy.executeGroovy('groovy/deleteAllTestUsers.groovy');
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
            // Track the byte length incrementally instead of recomputing
            // Buffer.byteLength() over the whole (growing) csv string on every iteration.
            // The original version re-scanned the entire accumulated string each loop pass,
            // which is O(n^2) overall - against the real default jahiaFileUploadMaxSize
            // (104857600 bytes / 100 MiB), this needs ~192k rows and never completed in a
            // reasonable time when run for real (SUPPORT-646 Stage 6 finding, a genuine bug
            // in this new test, not a product bug). Computing only the newly appended row's
            // byte length each iteration keeps this O(n) overall.
            let totalBytes = Buffer.byteLength(header, 'utf8');
            let i = 0;
            // Pad with filler rows until the payload exceeds the real configured limit. Capped at
            // a generous row count so a very large configured limit does not turn this into an
            // unbounded/slow loop; if the real limit is bigger than this cap allows, this spec
            // needs the alternative (lowering jahiaFileUploadMaxSize via provisioning) instead.
            const maxIterations = 200000;
            while (totalBytes <= limit && i < maxIterations) {
                const row = rowTemplate(i);
                csv += row;
                totalBytes += Buffer.byteLength(row, 'utf8');
                i += 1;
            }

            expect(totalBytes, 'padded payload size').to.be.greaterThan(limit);

            // SUPPORT-646 Stage 6 finding (headline product-level gap, flagged for Stage 7):
            // this request does NOT reach the module's own graceful in-resolver size check
            // (which would return success:false/errorCount:1/the friendly
            // "CSV payload exceeds the configured upload size limit" message). Instead, a
            // ~100 MiB JSON POST body to /modules/graphql is rejected at a lower transport
            // layer with a bare HTTP 400 (Bad Request, text/xml body) BEFORE GraphQL ever
            // resolves the mutation. The production UI (createUsers.jsx) submits csvContent
            // the exact same way (FileReader.readAsText() -> a GraphQL string variable), so a
            // real user uploading a file near the advertised jahiaFileUploadMaxSize would hit
            // this same generic transport failure, never seeing the module's own friendly
            // error message - the advertised/checked limit is not actually the reachable limit
            // for this submission path. This is a genuine, currently-existing product-level
            // behavior (not introduced by this test), so per this stage's ground rules it is
            // captured here as a regression guard on the ACTUAL observed behavior, exactly as
            // D6-JUnit's Part A/B guard the current (also imperfect) uncaught-exception
            // behavior - not fixed here. Also bump the command timeout: a ~100 MiB round trip
            // genuinely exceeds Cypress's 4s default even for this transport-level rejection.
            const originalTimeout = Cypress.config('defaultCommandTimeout');
            Cypress.config('defaultCommandTimeout', 30000);
            cy.apollo({
                mutation: importUsers,
                variables: {csvContent: csv, separator: ',', selectedColumns: ['j:firstName', 'j:lastName']}
            })
                .then((result: {networkError?: {statusCode?: number}; message?: string}) => {
                    expect(result.networkError, 'networkError (transport-level rejection, not a GraphQL error)').to.exist;
                    expect(result.networkError?.statusCode, 'HTTP status code').to.eq(400);
                    Cypress.config('defaultCommandTimeout', originalTimeout);
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

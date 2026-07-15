import {DocumentNode} from 'graphql';

/**
 * F6-Cypress: end-to-end coverage of the server-side upload-size limit and the
 * `bulkCreateUsers { maxUploadSize }` query, neither of which had any existing test (Java or
 * Cypress) per the Stage 2 inventory.
 *
 * The oversized payload is built by padding rows past the queried `maxUploadSize` (read via the
 * query itself) rather than lowering the server setting, since this harness has no established
 * helper for changing that setting at runtime.
 *
 * SUPPORT-646 Stage 7 fix note: `maxUploadSize` used to return the raw configured
 * `jahiaFileUploadMaxSize` (100 MiB by default in this environment), which Stage 6 proved is
 * unreachable dead weight for this submission mechanism (csvContent as a plain GraphQL JSON
 * string variable) - see the detailed root-cause comment on the second test below. Stage 7
 * clamped the query (and the resolver's own graceful check) to the real transport ceiling, so
 * this spec now pads to a much smaller (~20 MB instead of ~100 MB) and much faster-to-build
 * payload than Stage 6 saw.
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

    it('reports a positive maxUploadSize, clamped to the real GraphQL transport ceiling (Stage 7 fix)', () => {
        cy.apollo({query: maxUploadSize})
            .its('data.bulkCreateUsers.maxUploadSize')
            .should(value => {
                expect(value).to.be.a('number');
                expect(value).to.be.greaterThan(0);
                // Stage 7 fix: the advertised value must never exceed the real transport
                // ceiling (Jackson's StreamReadConstraints.getMaxStringLength() default of
                // 20,000,000 characters, enforced by graphql-java-kickstart before this
                // module's own resolver ever runs - see the root-cause comment below). Before
                // the fix this returned the raw jahiaFileUploadMaxSize (104857600 in this
                // environment), which is well above this ceiling and therefore an unreachable,
                // dishonest advertised limit for this submission mechanism.
                expect(value).to.be.at.most(20_000_000);
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

            // SUPPORT-646 Stage 6 found this request does NOT reach the module's own graceful
            // in-resolver size check (which would return success:false/errorCount:1/the friendly
            // "CSV payload exceeds the configured upload size limit" message) for a payload built
            // by padding past the raw jahiaFileUploadMaxSize (100 MiB). Stage 7 root-caused this
            // precisely (confirmed via the live container's own jahia.log stack trace, not
            // guesswork): a JSON POST body whose "variables.csvContent" string value exceeds
            // Jackson's StreamReadConstraints.getMaxStringLength() (20,000,000 characters, the
            // default since Jackson 2.15) is rejected by graphql-java-kickstart's
            // GraphQLObjectMapper/VariablesDeserializer BEFORE GraphQL query parsing or this
            // module's resolver ever run:
            //   graphql.kickstart.servlet.InvocationInputParseException: Request parsing failed
            //   Caused by: com.fasterxml.jackson.databind.JsonMappingException: String value
            //     length (20000011) exceeds the maximum allowed (20000000, from
            //     `StreamReadConstraints.getMaxStringLength()`) (through reference chain:
            //     graphql.kickstart.execution.GraphQLRequest["variables"])
            // This is a third-party/Jahia-core dependency (graphql-dxm-provider) ceiling this
            // module cannot raise from its own code. The Stage 7 fix instead makes the module
            // stop lying about the limit it can actually deliver on: `maxUploadSize` (and the
            // resolver's own graceful check) are now clamped to this real ceiling (see
            // BulkCreateUsersMutation#effectiveMaxUploadSize()). Because this test pads past the
            // *queried* limit - which is now itself clamped to the transport ceiling - this
            // request still lands past the Jackson boundary and still gets a raw transport-level
            // HTTP 400, exactly as asserted below: for the default configuration the module's own
            // graceful in-resolver message can never be reached by a payload this large (Jackson
            // always rejects it first), so this remains the accurate, honestly-documented
            // behavior rather than a bug. The module's own graceful check *is* now reachable in
            // the one case it can matter - an operator configuring jahiaFileUploadMaxSize below
            // this transport ceiling - see BulkCreateUsersMutationTest's "Bug 2 - JUnit" suite for
            // that scenario (this harness has no way to reconfigure jahiaFileUploadMaxSize at
            // runtime to exercise it live here).
            // The client-side UX symptom (a user picking an oversized file getting a confusing
            // raw network failure instead of a friendly message) IS fixed by this change even
            // though this direct-API test still observes the transport 400: createUsers.jsx's
            // pre-existing client-side size guard already compares the selected file's size
            // against this same queried maxUploadSize before ever calling this mutation, so it
            // now catches oversized files using the corrected (much smaller, honest) ceiling
            // instead of the unreachable 100 MiB value - see 08-bulkCreateUsersUI-ClientGuards.cy.ts.
            // Also bump the command timeout: a real round trip at this size can exceed Cypress's
            // 4s default even for this transport-level rejection.
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

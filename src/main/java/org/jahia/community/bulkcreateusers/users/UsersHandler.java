package org.jahia.community.bulkcreateusers.users;

import au.com.bytecode.opencsv.CSVReader;
import org.jahia.community.bulkcreateusers.graphql.BulkCreateUsersResult;
import org.jahia.services.content.*;
import org.jahia.services.content.decorator.JCRGroupNode;
import org.jahia.services.content.decorator.JCRUserNode;
import org.jahia.services.usermanager.JahiaGroupManagerService;
import org.jahia.services.usermanager.JahiaUserManagerService;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.jahia.api.Constants;

import javax.jcr.RepositoryException;
import java.io.IOException;
import java.io.StringReader;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component(service = UsersHandler.class)
public class UsersHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(UsersHandler.class);

    private static final int RESULT_CREATED = 0;
    private static final int RESULT_SKIPPED = 1;
    private static final int RESULT_UPDATED = 2;
    private static final int RESULT_ERROR = -1;

    // Properties that must have a value in every row
    private static final Set<String> REQUIRED_PROPERTY_COLUMNS = new HashSet<>(Arrays.asList("j:firstName", "j:lastName"));

    // Matches each [groupName] token in the groups cell
    private static final Pattern GROUP_PATTERN = Pattern.compile("\\[([^\\]]+)\\]");

    // Group names that grant administrative privileges - assignment is refused regardless of the caller's permissions.
    // Names are compared lower-case against the resolved JCR group name (not the CSV value).
    private static final Set<String> DENIED_GROUPS = new HashSet<>(Arrays.asList(
            "administrators", "server-administrators", "root-administrators",
            "site-administrators", "system-administrators", "compliance-managers"));

    // User-node properties that must never be written from CSV input.
    // Includes role/permission grants, account-lock flips, external-account flags, and the password field
    // (which createUser handles via its dedicated parameter, never via setProperty).
    private static final Set<String> DENIED_PROPERTIES = new HashSet<>(Arrays.asList(
            JCRUserNode.J_PASSWORD, Constants.NODENAME,
            "j:accountLocked", "j:external", "j:externalSource",
            "j:roles", "j:permissions"));
    private static final String[] DENIED_PROPERTY_PREFIXES = {"jcr:", "j:rolesIn", "j:account"};

    // Maximum number of characters echoed into a log line for attacker-controlled fields.
    private static final int LOG_FIELD_MAX_LEN = 200;

    private JahiaUserManagerService userManagerService;
    private JahiaGroupManagerService groupManagerService;

    public BulkCreateUsersResult importUsers(final String csvContent, final String separator,
            final String siteKey, final List<String> selectedColumns, final boolean overwrite) throws RepositoryException {
        if (siteKey != null) {
            LOGGER.info("Bulk adding users for site: {}", lazy(siteKey));
        }
        final long start = System.currentTimeMillis();
        final int[] counts = {0, 0, 0, 0}; // [created, skipped, error, updated]
        final List<String> errors = new ArrayList<>();
        final ImportRequest request = new ImportRequest(csvContent, separator, siteKey,
                buildAllowedColumns(selectedColumns), overwrite);

        JCRTemplate.getInstance().doExecuteWithSystemSession(session -> {
            runImport(session, request, counts, errors);
            return null;
        });

        LOGGER.info("Bulk user import completed in {} ms — created={}, skipped={}, errors={}",
                System.currentTimeMillis() - start, counts[0], counts[1], counts[2]);
        return new BulkCreateUsersResult(counts[2] == 0, counts[0], counts[3], counts[1], counts[2], errors);
    }

    private static Set<String> buildAllowedColumns(List<String> selectedColumns) {
        // Restrictive by default: when no selectedColumns is provided, only the required user properties
        // are written. Callers must opt in explicitly to import any other column.
        final Set<String> allowedColumns = new HashSet<>(REQUIRED_PROPERTY_COLUMNS);
        if (selectedColumns != null) {
            allowedColumns.addAll(selectedColumns);
        }
        return allowedColumns;
    }

    private void runImport(JCRSessionWrapper session, ImportRequest request, int[] counts, List<String> errors) {
        try (CSVReader reader = new CSVReader(new StringReader(request.csvContent), request.separator.charAt(0), '"')) {
            final String[] headers = reader.readNext();
            if (headers == null) {
                LOGGER.error("Missing headers in CSV file");
                counts[2]++;
                errors.add("Missing headers in CSV file");
                return;
            }
            final CsvLayout layout = CsvLayout.from(headers);
            if (layout == null) {
                LOGGER.error("Invalid CSV: missing required columns j:nodename or j:password");
                counts[2]++;
                errors.add("Invalid CSV: missing required columns j:nodename or j:password");
                return;
            }
            processRows(reader, new RowContext(request, layout, session, errors), counts);
        } catch (IOException e) {
            LOGGER.error("Error during bulk user creation", e);
            counts[2]++;
            errors.add("Fatal error: " + e.getMessage());
        }
    }

    private void processRows(CSVReader reader, RowContext ctx, int[] counts) throws IOException {
        String[] row;
        while ((row = reader.readNext()) != null) {
            final int result = processUser(row, ctx);
            tally(counts, commitRow(ctx.session, result, ctx.errors));
        }
    }

    private static void tally(int[] counts, int committed) {
        switch (committed) {
            case RESULT_CREATED: counts[0]++; break;
            case RESULT_SKIPPED: counts[1]++; break;
            case RESULT_UPDATED: counts[3]++; break;
            default: counts[2]++; break;
        }
    }

    private int processUser(String[] row, RowContext ctx) {
        final CsvLayout layout = ctx.layout;
        if (layout.userIdx >= row.length || layout.passIdx >= row.length) {
            LOGGER.warn("Skipping malformed CSV row: fewer cells than required columns");
            ctx.errors.add("Malformed row: fewer cells than required columns");
            return RESULT_ERROR;
        }
        final List<String> values = Arrays.asList(row);
        final String username = values.get(layout.userIdx);
        final String password = values.get(layout.passIdx);
        final String groups = (layout.groupIdx >= 0 && layout.groupIdx < values.size()) ? values.get(layout.groupIdx) : null;

        final Properties props = buildPropertiesOrReport(values, ctx, username);
        if (props == null) {
            return RESULT_ERROR;
        }

        final JCRUserNode existing = userManagerService.lookupUser(username, ctx.request.siteKey, ctx.session);
        if (existing != null) {
            return handleExistingUser(existing, props, groups, ctx);
        }
        return handleNewUser(username, password, props, groups, ctx);
    }

    private Properties buildPropertiesOrReport(List<String> values, RowContext ctx, String username) {
        try {
            return buildProperties(ctx.layout.headers, values, ctx.request.allowedColumns);
        } catch (IllegalArgumentException ex) {
            LOGGER.error("Skipping user due to invalid data: {}", lazy(ex.getMessage()));
            ctx.errors.add("Row for '" + sanitizeForLog(username) + "': " + ex.getMessage());
            return null;
        }
    }

    private int handleExistingUser(JCRUserNode existing, Properties props, String groups, RowContext ctx) {
        final boolean canAssignGroups = ctx.layout.groupIdx >= 0;
        if (ctx.request.overwrite && !"root".equalsIgnoreCase(existing.getName())) {
            try {
                for (final Map.Entry<Object, Object> entry : props.entrySet()) {
                    existing.setProperty((String) entry.getKey(), (String) entry.getValue());
                }
            } catch (RepositoryException e) {
                LOGGER.error("Failed to update properties for user {}: {}", lazy(existing.getName()), e.getMessage());
                ctx.errors.add("Failed to update user: " + sanitizeForLog(existing.getName()));
                return RESULT_ERROR;
            }
            if (canAssignGroups) {
                addUserToGroups(existing, groups, ctx.request.siteKey, ctx.session);
            }
            return RESULT_UPDATED;
        }
        if (canAssignGroups) {
            addUserToGroups(existing, groups, ctx.request.siteKey, ctx.session);
        }
        return RESULT_SKIPPED;
    }

    private int handleNewUser(String username, String password, Properties props, String groups, RowContext ctx) {
        if (!userManagerService.isUsernameSyntaxCorrect(username)) {
            LOGGER.error("Invalid username syntax: {}", lazy(username));
            ctx.errors.add("Invalid username syntax: " + sanitizeForLog(username));
            return RESULT_ERROR;
        }
        final JCRUserNode created = userManagerService.createUser(username, ctx.request.siteKey, password, props, ctx.session);
        if (created == null) {
            LOGGER.error("Failed to create user: {}", lazy(username));
            ctx.errors.add("Failed to create user: " + sanitizeForLog(username));
            return RESULT_ERROR;
        }
        LOGGER.info("Created user: {}", lazy(username));
        if (ctx.layout.groupIdx >= 0) {
            addUserToGroups(created, groups, ctx.request.siteKey, ctx.session);
        }
        return RESULT_CREATED;
    }

    /**
     * Per-row commit boundary: skipped rows need no save; processed rows are persisted individually
     * so that a single failure cannot poison the whole import. On commit failure the session is rolled
     * back via {@code refresh(false)} and the row is reported as an error.
     */
    private int commitRow(JCRSessionWrapper session, int result, List<String> errors) {
        if (result == RESULT_SKIPPED) {
            return result;
        }
        if (result == RESULT_ERROR) {
            // Some row-level failure paths (e.g. setProperty mid-overwrite) may leave partial mutations
            // staged - discard them before processing the next row.
            safeRefresh(session);
            return result;
        }
        try {
            session.save();
            return result;
        } catch (RepositoryException e) {
            LOGGER.error("Failed to commit row: {}", lazy(e.getMessage()));
            errors.add("Failed to commit row");
            safeRefresh(session);
            return RESULT_ERROR;
        }
    }

    private static void safeRefresh(JCRSessionWrapper session) {
        try {
            session.refresh(false);
        } catch (RepositoryException ignored) {
            // refresh failure cannot worsen the result we already report
        }
    }

    private Properties buildProperties(List<String> headers, List<String> values, Set<String> allowedColumns) {
        final Properties props = new Properties();
        for (int i = 0; i < headers.size(); i++) {
            final String rawKey = headers.get(i);
            final String key = rawKey == null ? "" : rawKey.trim();
            if (isWritableColumn(key, allowedColumns)) {
                final String value = i < values.size() ? values.get(i) : null;
                if (value != null && !value.trim().isEmpty()) {
                    props.setProperty(key, value);
                } else if (REQUIRED_PROPERTY_COLUMNS.contains(key)) {
                    throw new IllegalArgumentException("Empty value for required column: " + key);
                }
            }
        }
        return props;
    }

    private static boolean isWritableColumn(String key, Set<String> allowedColumns) {
        if (key.isEmpty() || "groups".equals(key) || isPropertyDenied(key)) {
            return false;
        }
        return allowedColumns == null || allowedColumns.contains(key);
    }

    private static boolean isPropertyDenied(String key) {
        if (DENIED_PROPERTIES.contains(key)) {
            return true;
        }
        for (String prefix : DENIED_PROPERTY_PREFIXES) {
            if (key.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private void addUserToGroups(JCRUserNode user, String groups, String siteKey, JCRSessionWrapper session) {
        if (groups == null || groups.trim().isEmpty()) {
            return;
        }
        final Matcher matcher = GROUP_PATTERN.matcher(groups);
        while (matcher.find()) {
            final String groupName = matcher.group(1).trim();
            if (!groupName.isEmpty()) {
                tryAddToGroup(user, groupName, siteKey, session);
            }
        }
    }

    private void tryAddToGroup(JCRUserNode user, String groupName, String siteKey, JCRSessionWrapper session) {
        final JCRGroupNode jahiaGroup = groupManagerService.lookupGroup(siteKey, groupName, session);
        if (jahiaGroup == null) {
            LOGGER.warn("Group {} not found{}", lazy(groupName),
                    siteKey != null ? " for site " + sanitizeForLog(siteKey) : "");
            return;
        }
        // Compare against the resolved JCR name so a CSV alias cannot bypass the denylist.
        if (DENIED_GROUPS.contains(jahiaGroup.getName().toLowerCase(Locale.ROOT))) {
            LOGGER.warn("Refusing to add user {} to privileged group {}",
                    lazy(user.getName()), lazy(jahiaGroup.getName()));
            return;
        }
        jahiaGroup.addMember(user);
        LOGGER.info("Added user {} to group {}", lazy(user.getName()), lazy(jahiaGroup.getName()));
    }

    /**
     * Defers {@link #sanitizeForLog(String)} until the logger actually formats the message,
     * avoiding the work when the corresponding log level is disabled (rule java:S2629).
     */
    private static Object lazy(final String value) {
        return new Object() {
            @Override
            public String toString() {
                return sanitizeForLog(value);
            }
        };
    }

    private static String sanitizeForLog(String value) {
        if (value == null) {
            return null;
        }
        final String stripped = value.replaceAll("[\\r\\n\\t]", "_");
        return stripped.length() <= LOG_FIELD_MAX_LEN ? stripped : stripped.substring(0, LOG_FIELD_MAX_LEN) + "...";
    }

    /** Immutable inputs of a single import invocation. */
    private static final class ImportRequest {
        final String csvContent;
        final String separator;
        final String siteKey;
        final Set<String> allowedColumns;
        final boolean overwrite;

        ImportRequest(String csvContent, String separator, String siteKey, Set<String> allowedColumns, boolean overwrite) {
            this.csvContent = csvContent;
            this.separator = separator;
            this.siteKey = siteKey;
            this.allowedColumns = allowedColumns;
            this.overwrite = overwrite;
        }
    }

    /** Parsed CSV header layout: column list plus the indices we care about. */
    private static final class CsvLayout {
        final List<String> headers;
        final int userIdx;
        final int passIdx;
        final int groupIdx;

        private CsvLayout(List<String> headers, int userIdx, int passIdx, int groupIdx) {
            this.headers = headers;
            this.userIdx = userIdx;
            this.passIdx = passIdx;
            this.groupIdx = groupIdx;
        }

        static CsvLayout from(String[] headers) {
            final List<String> headerList = Arrays.asList(headers);
            final int userIdx = headerList.indexOf(Constants.NODENAME);
            final int passIdx = headerList.indexOf(JCRUserNode.J_PASSWORD);
            if (userIdx < 0 || passIdx < 0) {
                return null;
            }
            return new CsvLayout(headerList, userIdx, passIdx, headerList.indexOf("groups"));
        }
    }

    /** Per-row carrier so row-level helpers stay below the parameter-count ceiling. */
    private static final class RowContext {
        final ImportRequest request;
        final CsvLayout layout;
        final JCRSessionWrapper session;
        final List<String> errors;

        RowContext(ImportRequest request, CsvLayout layout, JCRSessionWrapper session, List<String> errors) {
            this.request = request;
            this.layout = layout;
            this.session = session;
            this.errors = errors;
        }
    }

    @Reference
    public void setUserManagerService(JahiaUserManagerService userManagerService) {
        this.userManagerService = userManagerService;
    }

    @Reference
    public void setGroupManagerService(JahiaGroupManagerService groupManagerService) {
        this.groupManagerService = groupManagerService;
    }
}

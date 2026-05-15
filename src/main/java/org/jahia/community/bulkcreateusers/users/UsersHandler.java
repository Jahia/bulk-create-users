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
import java.io.StringReader;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component(service = UsersHandler.class)
public class UsersHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(UsersHandler.class);

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
            LOGGER.info("Bulk adding users for site: {}", sanitizeForLog(siteKey));
        }
        final long start = System.currentTimeMillis();
        final int[] counts = {0, 0, 0, 0}; // [created, skipped, error, updated]
        final List<String> errors = new ArrayList<>();
        final Set<String> allowedColumns = (selectedColumns != null && !selectedColumns.isEmpty())
                ? new HashSet<>(selectedColumns) : null;

        JCRTemplate.getInstance().doExecuteWithSystemSession(session -> {
            try (CSVReader reader = new CSVReader(new StringReader(csvContent), separator.charAt(0), '"')) {
                final String[] headers = reader.readNext();
                if (headers == null) {
                    LOGGER.error("Missing headers in CSV file");
                    counts[2]++;
                    errors.add("Missing headers in CSV file");
                    return null;
                }
                final List<String> headerList = Arrays.asList(headers);
                final int userIdx = headerList.indexOf(Constants.NODENAME);
                final int passIdx = headerList.indexOf(JCRUserNode.J_PASSWORD);
                final int groupIdx = headerList.indexOf("groups");
                if (userIdx < 0 || passIdx < 0) {
                    LOGGER.error("Invalid CSV: missing required columns j:nodename or j:password");
                    counts[2]++;
                    errors.add("Invalid CSV: missing required columns j:nodename or j:password");
                    return null;
                }

                int batch = 0;
                String[] row;
                while ((row = reader.readNext()) != null) {
                    if (batch++ == 100) {
                        session.save();
                        batch = 1;
                    }
                    final int result = processUser(row, headerList, userIdx, passIdx, groupIdx, siteKey, session, errors, allowedColumns, overwrite);
                    if (result == 0) {
                        counts[0]++;
                    } else if (result == 1) {
                        counts[1]++;
                    } else if (result == 2) {
                        counts[3]++;
                    } else {
                        counts[2]++;
                    }
                }
                session.save();
            } catch (Exception e) {
                LOGGER.error("Error during bulk user creation", e);
                counts[2]++;
                errors.add("Fatal error: " + e.getMessage());
            }
            return null;
        });

        LOGGER.info("Bulk user import completed in {} ms — created={}, skipped={}, errors={}", System.currentTimeMillis() - start, counts[0], counts[1], counts[2]);
        return new BulkCreateUsersResult(counts[2] == 0, counts[0], counts[3], counts[1], counts[2], errors);
    }

    private int processUser(String[] row, List<String> headerList, int userIdx, int passIdx,
            int groupIdx, String siteKey, JCRSessionWrapper session, List<String> errors,
            Set<String> allowedColumns, boolean overwrite) {
        if (userIdx >= row.length || passIdx >= row.length) {
            LOGGER.warn("Skipping malformed CSV row: fewer cells than required columns");
            errors.add("Malformed row: fewer cells than required columns");
            return -1;
        }
        final List<String> values = Arrays.asList(row);
        final String username = values.get(userIdx);
        final String password = values.get(passIdx);
        final String groups = (groupIdx >= 0 && groupIdx < values.size()) ? values.get(groupIdx) : null;

        Properties props;
        try {
            props = buildProperties(headerList, values, allowedColumns);
        } catch (IllegalArgumentException ex) {
            LOGGER.error("Skipping user due to invalid data: {}", sanitizeForLog(ex.getMessage()));
            errors.add("Row for '" + sanitizeForLog(username) + "': " + ex.getMessage());
            return -1;
        }

        final boolean assignGroups = groupIdx >= 0 && (allowedColumns == null || allowedColumns.contains("groups"));
        final JCRUserNode existing = userManagerService.lookupUser(username, siteKey, session);
        if (existing != null) {
            final boolean isRoot = "root".equalsIgnoreCase(existing.getName());
            if (overwrite && !isRoot) {
                try {
                    for (final Map.Entry<Object, Object> entry : props.entrySet()) {
                        existing.setProperty((String) entry.getKey(), (String) entry.getValue());
                    }
                } catch (RepositoryException e) {
                    LOGGER.error("Failed to update properties for user {}: {}", sanitizeForLog(username), e.getMessage());
                    errors.add("Failed to update user: " + sanitizeForLog(username));
                    return -1;
                }
                if (assignGroups) {
                    addUserToGroups(existing, groups, siteKey, session);
                }
                return 2; // updated
            }
            if (assignGroups) {
                addUserToGroups(existing, groups, siteKey, session);
            }
            return 1; // skipped
        }
        if (!userManagerService.isUsernameSyntaxCorrect(username)) {
            LOGGER.error("Invalid username syntax: {}", sanitizeForLog(username));
            errors.add("Invalid username syntax: " + sanitizeForLog(username));
            return -1;
        }
        final JCRUserNode created = userManagerService.createUser(username, siteKey, password, props, session);
        if (created != null) {
            LOGGER.info("Created user: {}", sanitizeForLog(username));
            if (assignGroups) {
                addUserToGroups(created, groups, siteKey, session);
            }
            return 0;
        }
        LOGGER.error("Failed to create user: {}", sanitizeForLog(username));
        errors.add("Failed to create user: " + sanitizeForLog(username));
        return -1;
    }

    private Properties buildProperties(List<String> headers, List<String> values, Set<String> allowedColumns) {
        final Properties props = new Properties();
        for (int i = 0; i < headers.size(); i++) {
            final String rawKey = headers.get(i);
            final String key = rawKey == null ? "" : rawKey.trim();
            if (key.isEmpty() || "groups".equals(key)) {
                continue;
            }
            if (isPropertyDenied(key)) {
                continue;
            }
            if (allowedColumns != null && !allowedColumns.contains(key)) {
                continue;
            }
            final boolean isEmpty = i >= values.size() || values.get(i) == null || values.get(i).trim().isEmpty();
            if (isEmpty) {
                if (REQUIRED_PROPERTY_COLUMNS.contains(key)) {
                    throw new IllegalArgumentException("Empty value for required column: " + key);
                }
                continue;
            }
            props.setProperty(key, values.get(i));
        }
        return props;
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
            if (groupName.isEmpty()) {
                continue;
            }
            final JCRGroupNode jahiaGroup = groupManagerService.lookupGroup(siteKey, groupName, session);
            if (jahiaGroup == null) {
                LOGGER.warn("Group {} not found{}", sanitizeForLog(groupName),
                        siteKey != null ? " for site " + sanitizeForLog(siteKey) : "");
                continue;
            }
            // Compare against the resolved JCR name so a CSV alias cannot bypass the denylist.
            if (DENIED_GROUPS.contains(jahiaGroup.getName().toLowerCase(Locale.ROOT))) {
                LOGGER.warn("Refusing to add user {} to privileged group {}",
                        sanitizeForLog(user.getName()), sanitizeForLog(jahiaGroup.getName()));
                continue;
            }
            jahiaGroup.addMember(user);
            LOGGER.info("Added user {} to group {}", sanitizeForLog(user.getName()), sanitizeForLog(jahiaGroup.getName()));
        }
    }

    private static String sanitizeForLog(String value) {
        if (value == null) {
            return null;
        }
        final String stripped = value.replaceAll("[\\r\\n\\t]", "_");
        return stripped.length() <= LOG_FIELD_MAX_LEN ? stripped : stripped.substring(0, LOG_FIELD_MAX_LEN) + "...";
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

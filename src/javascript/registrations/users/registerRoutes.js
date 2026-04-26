import {registry} from '@jahia/ui-extender';
import {Person} from '@jahia/moonstone';
import React from 'react';
import CreateUsers from '../CreateUsers';

export const registerRoutes = function () {
    registry.add('adminRoute', 'bulkCreateUsers', {
        targets: ['administration-server-usersAndRoles:999'],
        requiredPermission: 'adminUsers',
        icon: null,
        label: 'bulk-create-users:users.label',
        isSelectable: true,
        render: () => <CreateUsers/>
    });

    registry.add('adminRoute', 'bulkCreateSiteUsers', {
        targets: ['administration-sites:999'],
        requiredPermission: 'siteAdminUsers',
        icon: <Person/>,
        label: 'bulk-create-users:users.label',
        isSelectable: true,
        render: () => <CreateUsers/>
    });
};

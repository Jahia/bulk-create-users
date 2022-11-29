import registrations from './registrations';
import {registry} from '@jahia/ui-extender';
import i18next from 'i18next';

export default function () {
    registry.add('callback', 'bulk-create-users', {
        targets: ['jahiaApp-init:50'],
        callback: () => {
            i18next.loadNamespaces('bulk-create-users');
            registrations();
            console.log('%c Bulk Create Users routes have been registered', 'color: #3c8cba');
        }
    });
}

// IPsiphonClientCallback.aidl
package ca.psiphon.library;

import ca.psiphon.library.IPsiphonClientCallback;

interface IPsiphonService {
    void registerClient(IPsiphonClientCallback client);
    void unregisterClient(IPsiphonClientCallback client);
}
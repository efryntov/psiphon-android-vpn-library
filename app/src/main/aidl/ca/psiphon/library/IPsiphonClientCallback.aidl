// IPsiphonClientCallback.aidl
package ca.psiphon.library;

interface IPsiphonClientCallback {
    void onStateUpdated(in Bundle state);
}
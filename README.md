# cordova-plugin-play-integrity

A modern Cordova plugin for the [Google Play Integrity API](https://developer.android.com/google/play/integrity) standard request flow.

The plugin is intentionally a thin bridge to Google's official Android library. It prepares a standard integrity token provider, requests tokens bound to a `requestHash`, and optionally presents Google's remediation dialogs. Integrity verdicts must always be decoded and enforced by a trusted backend.

## Features

- Standard requests using `StandardIntegrityManager`
- Promise-based JavaScript API
- Automatic one-time provider refresh when Google reports an invalid provider
- Structured native errors with recovery metadata
- Google Play remediation dialogs
- No local pass/fail decision and no bundled backend service

## Requirements

- Cordova Android 12 or newer
- Android 6.0 (API level 23) or newer
- An app distributed through Google Play
- A Google Cloud project linked to the app in Play Console
- A backend capable of decoding and verifying integrity tokens with Google

## Installation

From npm after a release is published:

```bash
cordova plugin add cordova-plugin-play-integrity
```

Directly from a tagged GitHub release:

```bash
cordova plugin add https://github.com/andreszs/cordova-plugin-play-integrity.git#v1.0.0
```

## Usage

Call the plugin after Cordova's `deviceready` event.

```javascript
document.addEventListener('deviceready', async function () {
    try {
        await cordova.plugins.playIntegrity.prepare({
            cloudProjectNumber: '123456789012'
        });
    } catch (error) {
        console.error('Play Integrity preparation failed', error);
    }
});
```

For every protected action, compute a stable SHA-256 digest of all relevant request values. Encode the digest as unpadded Base64 URL-safe text and request a token as close as possible to the protected server call.

```javascript
const result = await cordova.plugins.playIntegrity.requestToken({
    requestHash: requestHash
});

await sendProtectedRequest({
    integrityToken: result.token,
    requestData: requestData
});
```

Do not include sensitive values directly in `requestHash`. Hash a stable serialization of the protected data instead.

## Backend verification

The token is not a local boolean. Send it to a trusted backend, decode it with Google's `decodeIntegrityToken` endpoint, and reject the protected action unless all required checks pass.

At minimum, verify:

1. `requestDetails.requestPackageName` is the expected package.
2. `requestDetails.requestHash` equals a server-side recomputation.
3. `requestDetails.timestampMillis` is inside a short freshness window.
4. `appIntegrity.appRecognitionVerdict` is `PLAY_RECOGNIZED`.
5. The package name, certificate digest, and version are expected.
6. `accountDetails.appLicensingVerdict` satisfies your policy.
7. `deviceIntegrity.deviceRecognitionVerdict` contains the labels required by your policy.

Standard requests include automatic replay mitigation, but content binding and server-side freshness checks are still required.

See Google's documentation for the complete [standard request flow](https://developer.android.com/google/play/integrity/standard) and [verdict handling](https://developer.android.com/google/play/integrity/verdicts).

## Remediation dialogs

After requesting a token, a backend may instruct the app to show an appropriate Google Play remediation dialog:

```javascript
await cordova.plugins.playIntegrity.showDialog({
    type: cordova.plugins.playIntegrity.DIALOG_TYPES.GET_LICENSED
});
```

Supported values:

- `GET_LICENSED`
- `CLOSE_UNKNOWN_ACCESS_RISK`
- `CLOSE_ALL_ACCESS_RISK`
- `GET_INTEGRITY`
- `GET_STRONG_INTEGRITY`

Google permits at most one dialog call per token. Request a new token before attempting another dialog.

## API

### `prepare(options)`

Warms up and caches a standard integrity token provider.

```typescript
prepare(options: {
    cloudProjectNumber: string | number;
}): Promise<{
    prepared: true;
    cloudProjectNumber: string;
}>;
```

Call this when the app starts and refresh it periodically if your app remains open for a long time. The plugin automatically reprepares once when a token provider becomes invalid.

### `requestToken(options)`

```typescript
requestToken(options: {
    requestHash: string;
}): Promise<{
    token: string;
}>;
```

`requestHash` must be a non-empty string no larger than 500 UTF-8 bytes.

### `showDialog(options)`

```typescript
showDialog(options: {
    type: DialogType;
}): Promise<{
    responseCode: number;
}>;
```

### `reset()`

Clears the in-memory provider and token. Call `prepare()` again before requesting another token.

## Error handling

Rejected promises are `Error` instances with additional fields when supplied by the native API:

```javascript
try {
    await cordova.plugins.playIntegrity.requestToken({ requestHash });
} catch (error) {
    console.error(error.code);
    console.error(error.nativeCode);
    console.error(error.recoverable);
    console.error(error.remediable);
}
```

Transient failures should be retried with exponential backoff. Never silently treat an API failure as a successful integrity verdict.

## Testing

Configure test responses in Play Console and install the app from an internal, closed, open, or production Play track using a configured tester account. A sideloaded debug APK normally will not receive the same app recognition and licensing verdicts as a Play-installed build.

## Security

Please review [SECURITY.md](SECURITY.md) before reporting a vulnerability. Do not include real integrity tokens, service-account keys, signing keys, or other credentials in public issues.

## License

[MIT](LICENSE)

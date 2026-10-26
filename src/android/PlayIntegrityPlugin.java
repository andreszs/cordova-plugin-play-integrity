package io.github.andreszs.cordova.playintegrity;

import android.app.Activity;
import android.content.Context;

import com.google.android.play.core.integrity.IntegrityManagerFactory;
import com.google.android.play.core.integrity.StandardIntegrityException;
import com.google.android.play.core.integrity.StandardIntegrityManager;
import com.google.android.play.core.integrity.model.IntegrityDialogTypeCode;
import com.google.android.play.core.integrity.model.StandardIntegrityErrorCode;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

public final class PlayIntegrityPlugin extends CordovaPlugin {
    private static final int MAX_REQUEST_HASH_BYTES = 500;

    private final Object stateLock = new Object();

    private StandardIntegrityManager integrityManager;
    private StandardIntegrityManager.StandardIntegrityTokenProvider tokenProvider;
    private StandardIntegrityManager.StandardIntegrityToken lastToken;
    private Long cloudProjectNumber;
    private boolean preparing;

    private interface ProviderListener {
        void onSuccess(StandardIntegrityManager.StandardIntegrityTokenProvider provider);

        void onFailure(Exception exception);
    }

    @Override
    protected void pluginInitialize() {
        Context context = cordova.getActivity().getApplicationContext();
        integrityManager = IntegrityManagerFactory.createStandard(context);
    }

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) {
        try {
            JSONObject options = args.optJSONObject(0);
            if (options == null) {
                options = new JSONObject();
            }

            switch (action) {
                case "prepare":
                    prepare(options, callbackContext);
                    return true;
                case "requestToken":
                    requestToken(options, callbackContext);
                    return true;
                case "showDialog":
                    showDialog(options, callbackContext);
                    return true;
                case "reset":
                    reset(callbackContext);
                    return true;
                default:
                    return false;
            }
        } catch (Exception exception) {
            sendError(callbackContext, "INVALID_ARGUMENT", exception.getMessage(), exception);
            return true;
        }
    }

    private void prepare(JSONObject options, CallbackContext callbackContext) throws JSONException {
        final long projectNumber = parseProjectNumber(options);

        prepareProvider(projectNumber, new ProviderListener() {
            @Override
            public void onSuccess(StandardIntegrityManager.StandardIntegrityTokenProvider provider) {
                JSONObject result = new JSONObject();
                try {
                    result.put("prepared", true);
                    result.put("cloudProjectNumber", Long.toString(projectNumber));
                } catch (JSONException exception) {
                    sendError(callbackContext, "SERIALIZATION_ERROR", exception.getMessage(), exception);
                    return;
                }
                callbackContext.success(result);
            }

            @Override
            public void onFailure(Exception exception) {
                sendNativeError(callbackContext, exception);
            }
        });
    }

    private void prepareProvider(long projectNumber, ProviderListener listener) {
        synchronized (stateLock) {
            if (preparing) {
                listener.onFailure(new IllegalStateException("A token provider preparation is already in progress."));
                return;
            }
            preparing = true;
            tokenProvider = null;
            lastToken = null;
        }

        StandardIntegrityManager.PrepareIntegrityTokenRequest request =
            StandardIntegrityManager.PrepareIntegrityTokenRequest.builder()
                .setCloudProjectNumber(projectNumber)
                .build();

        integrityManager.prepareIntegrityToken(request)
            .addOnSuccessListener(provider -> {
                synchronized (stateLock) {
                    preparing = false;
                    cloudProjectNumber = projectNumber;
                    tokenProvider = provider;
                }
                listener.onSuccess(provider);
            })
            .addOnFailureListener(exception -> {
                synchronized (stateLock) {
                    preparing = false;
                }
                listener.onFailure(exception);
            });
    }

    private void requestToken(JSONObject options, CallbackContext callbackContext) throws JSONException {
        final String requestHash = options.getString("requestHash");
        int requestHashBytes = requestHash.getBytes(StandardCharsets.UTF_8).length;

        if (requestHash.isEmpty()) {
            throw new IllegalArgumentException("requestHash must be a non-empty string.");
        }
        if (requestHashBytes > MAX_REQUEST_HASH_BYTES) {
            throw new IllegalArgumentException("requestHash must not exceed 500 UTF-8 bytes.");
        }

        final StandardIntegrityManager.StandardIntegrityTokenProvider provider;
        synchronized (stateLock) {
            provider = tokenProvider;
        }

        if (provider == null) {
            sendError(
                callbackContext,
                "NOT_PREPARED",
                "Call prepare() successfully before requesting an integrity token.",
                null
            );
            return;
        }

        requestToken(provider, requestHash, callbackContext, false);
    }

    private void requestToken(
        StandardIntegrityManager.StandardIntegrityTokenProvider provider,
        String requestHash,
        CallbackContext callbackContext,
        boolean retried
    ) {
        StandardIntegrityManager.StandardIntegrityTokenRequest request =
            StandardIntegrityManager.StandardIntegrityTokenRequest.builder()
                .setRequestHash(requestHash)
                .build();

        provider.request(request)
            .addOnSuccessListener(token -> {
                synchronized (stateLock) {
                    lastToken = token;
                }

                JSONObject result = new JSONObject();
                try {
                    result.put("token", token.token());
                } catch (JSONException exception) {
                    sendError(callbackContext, "SERIALIZATION_ERROR", exception.getMessage(), exception);
                    return;
                }
                callbackContext.success(result);
            })
            .addOnFailureListener(exception -> {
                if (!retried && getNativeErrorCode(exception) ==
                    StandardIntegrityErrorCode.INTEGRITY_TOKEN_PROVIDER_INVALID) {
                    reprepareAndRetry(requestHash, callbackContext);
                    return;
                }
                sendNativeError(callbackContext, exception);
            });
    }

    private void reprepareAndRetry(String requestHash, CallbackContext callbackContext) {
        final Long projectNumber;
        synchronized (stateLock) {
            projectNumber = cloudProjectNumber;
        }

        if (projectNumber == null) {
            sendError(callbackContext, "NOT_PREPARED", "The token provider cannot be refreshed.", null);
            return;
        }

        prepareProvider(projectNumber, new ProviderListener() {
            @Override
            public void onSuccess(StandardIntegrityManager.StandardIntegrityTokenProvider provider) {
                requestToken(provider, requestHash, callbackContext, true);
            }

            @Override
            public void onFailure(Exception exception) {
                sendNativeError(callbackContext, exception);
            }
        });
    }

    private void showDialog(JSONObject options, CallbackContext callbackContext) throws JSONException {
        final int dialogType = parseDialogType(options.getString("type"));
        final StandardIntegrityManager.StandardIntegrityToken token;

        synchronized (stateLock) {
            token = lastToken;
            lastToken = null;
        }

        if (token == null) {
            sendError(
                callbackContext,
                "TOKEN_NOT_AVAILABLE",
                "Request a token before showing a remediation dialog. Each token can show at most one dialog.",
                null
            );
            return;
        }

        Activity activity = cordova.getActivity();
        activity.runOnUiThread(() -> {
            StandardIntegrityManager.StandardIntegrityDialogRequest.StandardIntegrityResponse.TokenResponse
                tokenResponse = new StandardIntegrityManager.StandardIntegrityDialogRequest
                    .StandardIntegrityResponse.TokenResponse(token);

            StandardIntegrityManager.StandardIntegrityDialogRequest dialogRequest =
                StandardIntegrityManager.StandardIntegrityDialogRequest.builder()
                    .setActivity(activity)
                    .setStandardIntegrityResponse(tokenResponse)
                    .setTypeCode(dialogType)
                    .build();

            integrityManager.showDialog(dialogRequest)
                .addOnSuccessListener(responseCode -> {
                    JSONObject result = new JSONObject();
                    try {
                        result.put("responseCode", responseCode);
                    } catch (JSONException exception) {
                        sendError(callbackContext, "SERIALIZATION_ERROR", exception.getMessage(), exception);
                        return;
                    }
                    callbackContext.success(result);
                })
                .addOnFailureListener(exception -> sendNativeError(callbackContext, exception));
        });
    }

    private void reset(CallbackContext callbackContext) {
        clearState();
        JSONObject result = new JSONObject();
        try {
            result.put("reset", true);
        } catch (JSONException exception) {
            sendError(callbackContext, "SERIALIZATION_ERROR", exception.getMessage(), exception);
            return;
        }
        callbackContext.success(result);
    }

    private long parseProjectNumber(JSONObject options) throws JSONException {
        Object value = options.opt("cloudProjectNumber");
        if (value == null || value == JSONObject.NULL) {
            throw new IllegalArgumentException("cloudProjectNumber is required.");
        }

        String raw = String.valueOf(value).trim();
        if (!raw.matches("^[1-9][0-9]*$")) {
            throw new IllegalArgumentException("cloudProjectNumber must be a positive integer or numeric string.");
        }

        try {
            long projectNumber = Long.parseLong(raw);
            if (projectNumber <= 0) {
                throw new IllegalArgumentException("cloudProjectNumber must be positive.");
            }
            return projectNumber;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("cloudProjectNumber is outside the supported range.", exception);
        }
    }

    private int parseDialogType(String type) {
        switch (type) {
            case "GET_LICENSED":
                return IntegrityDialogTypeCode.GET_LICENSED;
            case "CLOSE_UNKNOWN_ACCESS_RISK":
                return IntegrityDialogTypeCode.CLOSE_UNKNOWN_ACCESS_RISK;
            case "CLOSE_ALL_ACCESS_RISK":
                return IntegrityDialogTypeCode.CLOSE_ALL_ACCESS_RISK;
            case "GET_INTEGRITY":
                return IntegrityDialogTypeCode.GET_INTEGRITY;
            case "GET_STRONG_INTEGRITY":
                return IntegrityDialogTypeCode.GET_STRONG_INTEGRITY;
            default:
                throw new IllegalArgumentException("Unsupported dialog type: " + type);
        }
    }

    private int getNativeErrorCode(Exception exception) {
        if (exception instanceof StandardIntegrityException) {
            return ((StandardIntegrityException) exception).getErrorCode();
        }
        return 0;
    }

    private void sendNativeError(CallbackContext callbackContext, Exception exception) {
        int nativeCode = getNativeErrorCode(exception);
        String code = nativeCode == 0 ? "NATIVE_ERROR" : errorName(nativeCode);
        sendError(callbackContext, code, exception.getMessage(), exception);
    }

    private void sendError(
        CallbackContext callbackContext,
        String code,
        String message,
        Throwable throwable
    ) {
        JSONObject error = new JSONObject();
        try {
            error.put("code", code);
            error.put("message", message == null ? "Play Integrity request failed." : message);

            if (throwable instanceof StandardIntegrityException) {
                StandardIntegrityException integrityException = (StandardIntegrityException) throwable;
                int nativeCode = integrityException.getErrorCode();
                error.put("nativeCode", nativeCode);
                error.put("recoverable", isRecoverable(nativeCode));
                error.put("remediable", integrityException.isRemediable());
            } else {
                error.put("recoverable", false);
                error.put("remediable", false);
            }
        } catch (JSONException ignored) {
            callbackContext.error(message == null ? "Play Integrity request failed." : message);
            return;
        }

        callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.ERROR, error));
    }

    private String errorName(int errorCode) {
        switch (errorCode) {
            case StandardIntegrityErrorCode.API_NOT_AVAILABLE:
                return "API_NOT_AVAILABLE";
            case StandardIntegrityErrorCode.PLAY_STORE_NOT_FOUND:
                return "PLAY_STORE_NOT_FOUND";
            case StandardIntegrityErrorCode.NETWORK_ERROR:
                return "NETWORK_ERROR";
            case StandardIntegrityErrorCode.APP_NOT_INSTALLED:
                return "APP_NOT_INSTALLED";
            case StandardIntegrityErrorCode.PLAY_SERVICES_NOT_FOUND:
                return "PLAY_SERVICES_NOT_FOUND";
            case StandardIntegrityErrorCode.APP_UID_MISMATCH:
                return "APP_UID_MISMATCH";
            case StandardIntegrityErrorCode.TOO_MANY_REQUESTS:
                return "TOO_MANY_REQUESTS";
            case StandardIntegrityErrorCode.CANNOT_BIND_TO_SERVICE:
                return "CANNOT_BIND_TO_SERVICE";
            case StandardIntegrityErrorCode.GOOGLE_SERVER_UNAVAILABLE:
                return "GOOGLE_SERVER_UNAVAILABLE";
            case StandardIntegrityErrorCode.PLAY_STORE_VERSION_OUTDATED:
                return "PLAY_STORE_VERSION_OUTDATED";
            case StandardIntegrityErrorCode.PLAY_SERVICES_VERSION_OUTDATED:
                return "PLAY_SERVICES_VERSION_OUTDATED";
            case StandardIntegrityErrorCode.CLOUD_PROJECT_NUMBER_IS_INVALID:
                return "CLOUD_PROJECT_NUMBER_IS_INVALID";
            case StandardIntegrityErrorCode.REQUEST_HASH_TOO_LONG:
                return "REQUEST_HASH_TOO_LONG";
            case StandardIntegrityErrorCode.CLIENT_TRANSIENT_ERROR:
                return "CLIENT_TRANSIENT_ERROR";
            case StandardIntegrityErrorCode.INTEGRITY_TOKEN_PROVIDER_INVALID:
                return "INTEGRITY_TOKEN_PROVIDER_INVALID";
            case StandardIntegrityErrorCode.INTERNAL_ERROR:
                return "INTERNAL_ERROR";
            default:
                return "UNKNOWN_ERROR";
        }
    }

    private boolean isRecoverable(int errorCode) {
        switch (errorCode) {
            case StandardIntegrityErrorCode.NETWORK_ERROR:
            case StandardIntegrityErrorCode.TOO_MANY_REQUESTS:
            case StandardIntegrityErrorCode.CANNOT_BIND_TO_SERVICE:
            case StandardIntegrityErrorCode.GOOGLE_SERVER_UNAVAILABLE:
            case StandardIntegrityErrorCode.PLAY_STORE_VERSION_OUTDATED:
            case StandardIntegrityErrorCode.PLAY_SERVICES_VERSION_OUTDATED:
            case StandardIntegrityErrorCode.CLIENT_TRANSIENT_ERROR:
            case StandardIntegrityErrorCode.INTEGRITY_TOKEN_PROVIDER_INVALID:
            case StandardIntegrityErrorCode.INTERNAL_ERROR:
                return true;
            default:
                return false;
        }
    }

    private void clearState() {
        synchronized (stateLock) {
            tokenProvider = null;
            lastToken = null;
            cloudProjectNumber = null;
            preparing = false;
        }
    }

    @Override
    public void onReset() {
        clearState();
    }

    @Override
    public void onDestroy() {
        clearState();
    }
}

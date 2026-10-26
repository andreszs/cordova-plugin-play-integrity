'use strict';

var exec = require('cordova/exec');
var SERVICE = 'PlayIntegrity';
var DIALOG_TYPES = [
    'GET_LICENSED',
    'CLOSE_UNKNOWN_ACCESS_RISK',
    'CLOSE_ALL_ACCESS_RISK',
    'GET_INTEGRITY',
    'GET_STRONG_INTEGRITY'
];

function createError(code, message, details) {
    var error = new Error(message);
    error.code = code;

    if (details && typeof details === 'object') {
        Object.keys(details).forEach(function (key) {
            if (key !== 'message' && key !== 'code') {
                error[key] = details[key];
            }
        });
    }

    return error;
}

function normalizeNativeError(value) {
    if (value && typeof value === 'object') {
        return createError(value.code || 'NATIVE_ERROR', value.message || 'Play Integrity request failed.', value);
    }

    return createError('NATIVE_ERROR', typeof value === 'string' ? value : 'Play Integrity request failed.');
}

function invoke(action, options) {
    return new Promise(function (resolve, reject) {
        exec(resolve, function (error) {
            reject(normalizeNativeError(error));
        }, SERVICE, action, [options || {}]);
    });
}

function utf8Length(value) {
    var length = 0;

    for (var i = 0; i < value.length; i += 1) {
        var code = value.charCodeAt(i);

        if (code <= 0x7f) {
            length += 1;
        } else if (code <= 0x7ff) {
            length += 2;
        } else if (code >= 0xd800 && code <= 0xdbff && i + 1 < value.length &&
                   value.charCodeAt(i + 1) >= 0xdc00 && value.charCodeAt(i + 1) <= 0xdfff) {
            length += 4;
            i += 1;
        } else {
            length += 3;
        }
    }

    return length;
}

function normalizeProjectNumber(value) {
    var projectNumber = String(value == null ? '' : value).trim();

    if (!/^[1-9][0-9]*$/.test(projectNumber)) {
        throw createError('INVALID_ARGUMENT', 'cloudProjectNumber must be a positive integer or numeric string.');
    }

    return projectNumber;
}

exports.prepare = function (options) {
    try {
        return invoke('prepare', {
            cloudProjectNumber: normalizeProjectNumber(options && options.cloudProjectNumber)
        });
    } catch (error) {
        return Promise.reject(error);
    }
};

exports.requestToken = function (options) {
    var requestHash = options && options.requestHash;

    if (typeof requestHash !== 'string' || requestHash.length === 0) {
        return Promise.reject(createError('INVALID_ARGUMENT', 'requestHash must be a non-empty string.'));
    }

    if (utf8Length(requestHash) > 500) {
        return Promise.reject(createError('INVALID_ARGUMENT', 'requestHash must not exceed 500 UTF-8 bytes.'));
    }

    return invoke('requestToken', { requestHash: requestHash });
};

exports.showDialog = function (options) {
    var type = options && options.type;

    if (DIALOG_TYPES.indexOf(type) === -1) {
        return Promise.reject(createError(
            'INVALID_ARGUMENT',
            'type must be one of: ' + DIALOG_TYPES.join(', ') + '.'
        ));
    }

    return invoke('showDialog', { type: type });
};

exports.reset = function () {
    return invoke('reset');
};

exports.DIALOG_TYPES = Object.freeze({
    GET_LICENSED: 'GET_LICENSED',
    CLOSE_UNKNOWN_ACCESS_RISK: 'CLOSE_UNKNOWN_ACCESS_RISK',
    CLOSE_ALL_ACCESS_RISK: 'CLOSE_ALL_ACCESS_RISK',
    GET_INTEGRITY: 'GET_INTEGRITY',
    GET_STRONG_INTEGRITY: 'GET_STRONG_INTEGRITY'
});

'use strict';

const assert = require('node:assert/strict');
const Module = require('node:module');
const test = require('node:test');

const modulePath = require.resolve('../www/play-integrity');
const originalLoad = Module._load;
let calls;
let nativeResult;
let nativeError;

function loadPlugin() {
    calls = [];
    nativeResult = { ok: true };
    nativeError = null;

    Module._load = function (request, parent, isMain) {
        if (request === 'cordova/exec') {
            return function (success, error, service, action, args) {
                calls.push({ service, action, args });
                if (nativeError) {
                    error(nativeError);
                } else {
                    success(nativeResult);
                }
            };
        }
        return originalLoad(request, parent, isMain);
    };

    delete require.cache[modulePath];
    const plugin = require(modulePath);
    Module._load = originalLoad;
    return plugin;
}

test.afterEach(() => {
    Module._load = originalLoad;
    delete require.cache[modulePath];
});

test('prepare forwards a normalized project number', async () => {
    const plugin = loadPlugin();
    await plugin.prepare({ cloudProjectNumber: 123456789012 });

    assert.deepEqual(calls, [{
        service: 'PlayIntegrity',
        action: 'prepare',
        args: [{ cloudProjectNumber: '123456789012' }]
    }]);
});

test('prepare rejects an invalid project number without invoking native code', async () => {
    const plugin = loadPlugin();

    await assert.rejects(
        plugin.prepare({ cloudProjectNumber: 'not-a-number' }),
        error => error.code === 'INVALID_ARGUMENT'
    );
    assert.equal(calls.length, 0);
});

test('requestToken forwards a request hash', async () => {
    const plugin = loadPlugin();
    nativeResult = { token: 'encrypted-token' };

    const result = await plugin.requestToken({ requestHash: 'base64url-hash' });

    assert.equal(result.token, 'encrypted-token');
    assert.equal(calls[0].action, 'requestToken');
    assert.equal(calls[0].args[0].requestHash, 'base64url-hash');
});

test('requestToken rejects hashes larger than 500 UTF-8 bytes', async () => {
    const plugin = loadPlugin();

    await assert.rejects(
        plugin.requestToken({ requestHash: 'é'.repeat(251) }),
        error => error.code === 'INVALID_ARGUMENT'
    );
    assert.equal(calls.length, 0);
});

test('native structured errors become Error instances with metadata', async () => {
    const plugin = loadPlugin();
    nativeError = {
        code: 'NETWORK_ERROR',
        message: 'Network unavailable.',
        nativeCode: -3,
        recoverable: true,
        remediable: false
    };

    await assert.rejects(plugin.requestToken({ requestHash: 'hash' }), error => {
        assert.equal(error.code, 'NETWORK_ERROR');
        assert.equal(error.nativeCode, -3);
        assert.equal(error.recoverable, true);
        return true;
    });
});

test('showDialog accepts documented dialog types', async () => {
    const plugin = loadPlugin();
    await plugin.showDialog({ type: plugin.DIALOG_TYPES.GET_LICENSED });

    assert.equal(calls[0].action, 'showDialog');
    assert.equal(calls[0].args[0].type, 'GET_LICENSED');
});

test('showDialog rejects unknown dialog types', async () => {
    const plugin = loadPlugin();

    await assert.rejects(
        plugin.showDialog({ type: 'UNKNOWN' }),
        error => error.code === 'INVALID_ARGUMENT'
    );
    assert.equal(calls.length, 0);
});

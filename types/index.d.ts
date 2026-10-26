declare namespace playIntegrity {
    type DialogType =
        | 'GET_LICENSED'
        | 'CLOSE_UNKNOWN_ACCESS_RISK'
        | 'CLOSE_ALL_ACCESS_RISK'
        | 'GET_INTEGRITY'
        | 'GET_STRONG_INTEGRITY';

    interface PrepareOptions {
        cloudProjectNumber: string | number;
    }

    interface PrepareResult {
        prepared: true;
        cloudProjectNumber: string;
    }

    interface RequestTokenOptions {
        requestHash: string;
    }

    interface TokenResult {
        token: string;
    }

    interface ShowDialogOptions {
        type: DialogType;
    }

    interface DialogResult {
        responseCode: number;
    }

    interface ResetResult {
        reset: true;
    }

    interface PlayIntegrityError extends Error {
        code: string;
        nativeCode?: number;
        recoverable?: boolean;
        remediable?: boolean;
    }

    interface PlayIntegrityPlugin {
        prepare(options: PrepareOptions): Promise<PrepareResult>;
        requestToken(options: RequestTokenOptions): Promise<TokenResult>;
        showDialog(options: ShowDialogOptions): Promise<DialogResult>;
        reset(): Promise<ResetResult>;
        readonly DIALOG_TYPES: Readonly<Record<DialogType, DialogType>>;
    }
}

declare const playIntegrity: playIntegrity.PlayIntegrityPlugin;
export = playIntegrity;

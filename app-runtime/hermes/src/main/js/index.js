// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

const bundles = new Map();

// The settings screen offers a restart after changes, so one read per process is enough.
function isEnabled(bundle, key, defaultValue) {
    if (!bundles.has(bundle)) bundles.set(bundle, new Map());
    const values = bundles.get(bundle);
    if (!values.has(key)) {
        values.set(key, globalThis.nativeModuleProxy.ReseamSettings.isEnabled(bundle, key, defaultValue));
    }
    return values.get(key);
}

exports.skipWhen = function (bundle, key, defaultValue, original, ...args) {
    return isEnabled(bundle, key, defaultValue) ? undefined : original(...args);
};

exports.returnNullWhen = function (bundle, key, defaultValue, original, ...args) {
    return isEnabled(bundle, key, defaultValue) ? null : original(...args);
};

exports.returnWhen = function (bundle, key, defaultValue, value, original, ...args) {
    return isEnabled(bundle, key, defaultValue) ? value : original(...args);
};

exports.wrapWhen = function (bundle, key, defaultValue, wrapper, original, ...args) {
    return isEnabled(bundle, key, defaultValue) ? wrapper.call(this, original, ...args) : original(...args);
};

function withProperty(object, names, value) {
    const [name, ...rest] = names;
    return { ...object, [name]: rest.length === 0 ? value : withProperty(object[name], rest, value) };
}

exports.setArgumentWhen = function (bundle, key, defaultValue, index, path, value, original, ...args) {
    if (!isEnabled(bundle, key, defaultValue)) return original(...args);
    const changed = [...args];
    changed[index] = withProperty(args[index], path.split("."), value);
    return original(...changed);
};

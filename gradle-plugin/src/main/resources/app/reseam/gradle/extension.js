(function () {
    "use strict";
    return {
        imports: /*imports*/,
        link: function (modules) {
            var namespaces = {};
            var extension;
            // An app module may still be initializing when the extension loads, so each import
            // reads it through Metro on first use, as Metro's inline requires do. The engine names
            // the module's exports, which esbuild's interop copies as getters.
            function require(specifier) {
                var module = modules[specifier];
                var exports;
                function read() {
                    if (exports === undefined) exports = globalThis.__r(module.id);
                    return exports;
                }
                return namespaces[specifier] || (namespaces[specifier] = new Proxy({}, {
                    get: function (_, key) { return key === "__esModule" ? module.esModule : read()[key]; },
                    has: function (_, key) { return key in read(); },
                    ownKeys: function () { return module.keys.slice(); },
                    getOwnPropertyDescriptor: function (_, key) {
                        return module.keys.indexOf(key) === -1
                            ? undefined
                            : { configurable: true, enumerable: true, writable: false, value: undefined };
                    },
                    getPrototypeOf: function () { return Object.prototype; },
                }));
            }
            // App modules exist only once the app's own global code has run, so the extension
            // loads on its first call.
            function load() {
                if (extension === undefined) {
                    /*bundle*/
                    extension = /*global*/;
                }
                return extension;
            }
            return {
                /*exports*/
            };
        },
    };
})();

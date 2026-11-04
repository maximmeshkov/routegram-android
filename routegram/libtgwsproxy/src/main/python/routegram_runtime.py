"""Android packaging/lifecycle adapter for unmodified Flowseal proxy sources."""
import importlib.machinery
import importlib.util
import os
import sys

_thread = None
_loop = None
_stop_event = None
_error = None
_secret = ''
_configured = False


def configure(native_dir):
    global _configured
    if _configured:
        return

    class NativeExtensions:
        def find_spec(self, fullname, path=None, target=None):
            if '.' in fullname:
                return None
            library = os.path.join(native_dir, 'libpython_ext_' + fullname + '.so')
            if os.path.isfile(library):
                loader = importlib.machinery.ExtensionFileLoader(fullname, library)
                return importlib.util.spec_from_file_location(fullname, library, loader=loader)
            return None

    sys.meta_path.insert(0, NativeExtensions())
    import ctypes.util
    import certifi
    # urllib and OpenSSL must use our public CA bundle, not Android private APIs.
    os.environ['SSL_CERT_FILE'] = certifi.where()
    find_library = ctypes.util.find_library
    try:
        ctypes.util.find_library = lambda name: (
            os.path.join(native_dir, 'libcrypto_python.so') if name == 'crypto'
            else find_library(name))
        from proxy import _aes  # noqa: F401 — bind packaged OpenSSL, then restore.
    finally:
        ctypes.util.find_library = find_library
    _configured = True


def _redact(value):
    return str(value).replace(_secret, '[redacted]') if _secret else str(value)


def _status():
    from proxy import tg_ws_proxy
    server = tg_ws_proxy._server_instance
    running = bool(_thread and _thread.is_alive())
    return {'ok': True, 'running': running,
            'ready': running and server is not None and server.is_serving(),
            'error': _error}


def _stop():
    from proxy import config, tg_ws_proxy
    config._refresh_stop.set()
    if _thread and _thread.is_alive():
        if _loop and _stop_event and not _loop.is_closed():
            _loop.call_soon_threadsafe(_stop_event.set)
        _thread.join(timeout=10)
        if _thread.is_alive():
            raise TimeoutError('Proxy did not stop; refusing to start a second instance')
    # Also close a listener if upstream failed before entering its cleanup block.
    if tg_ws_proxy._server_instance is not None:
        raise RuntimeError('Proxy listener cleanup incomplete')
    return _status()


def _start(settings):
    global _thread, _error, _secret, _loop, _stop_event
    import asyncio
    import logging
    import threading
    from proxy import config, tg_ws_proxy

    _stop()
    _secret = settings['secret']
    if len(bytes.fromhex(_secret)) != 16:
        raise ValueError('MTProto secret must contain 16 bytes')
    if settings['host'] not in ('127.0.0.1', '::1'):
        raise ValueError('Embedded proxy must bind to loopback')
    if not 1 <= settings['port'] <= 65535:
        raise ValueError('Invalid proxy port')

    proxy = config.proxy_config
    proxy.host = settings['host']
    proxy.port = settings['port']
    proxy.secret = _secret
    proxy.pool_size = max(2, min(16, settings['pool_size']))
    proxy.fallback_cfproxy = settings['cf_enabled']
    proxy.cfproxy_user_domains = config.coerce_domain_list(settings['cf_domains'])
    proxy.dc_redirects = (config.parse_dc_ip_list(settings['dc_ips'].split(','))
                          if settings['dc_ips'] else config.ProxyConfig().dc_redirects)
    proxy.disable_secure = False
    proxy.fake_tls_domain = ''

    class Redact(logging.Filter):
        def filter(self, record):
            record.msg = _redact(record.getMessage())
            record.args = ()
            return True

    handler = logging.StreamHandler()
    handler.addFilter(Redact())
    logging.basicConfig(level=logging.DEBUG if settings['verbose'] else logging.WARNING,
                        handlers=[handler], force=True)
    _error = None
    _loop = asyncio.new_event_loop()
    _stop_event = asyncio.Event()

    def worker():
        global _error
        asyncio.set_event_loop(_loop)
        try:
            _loop.run_until_complete(tg_ws_proxy._run(_stop_event))
        except BaseException as error:
            _error = _redact(error)
        finally:
            config._refresh_stop.set()
            server = tg_ws_proxy._server_instance
            if server is not None:
                server.close()
                _loop.run_until_complete(server.wait_closed())
                tg_ws_proxy._server_instance = None
            tasks = asyncio.all_tasks(_loop)
            for task in tasks:
                task.cancel()
            if tasks:
                _loop.run_until_complete(asyncio.gather(*tasks, return_exceptions=True))
            _loop.run_until_complete(_loop.shutdown_asyncgens())
            _loop.close()

    _thread = threading.Thread(target=worker, name='RouteGram-Flowseal', daemon=True)
    _thread.start()
    return _status()


def command_json(request):
    import json
    try:
        message = json.loads(request)
        operation = message['operation']
        if operation == 'start':
            result = _start(message['settings'])
        elif operation == 'stop':
            result = _stop()
        elif operation == 'status':
            result = _status()
        elif operation == 'diagnostics':
            import ssl
            from proxy import __version__, _aes
            from proxy.stats import stats
            from proxy.pool import ws_pool
            result = {'ok': True, 'python': sys.version, 'flowseal': __version__,
                      'openssl': ssl.OPENSSL_VERSION,
                      'connections_ws': stats.connections_ws,
                      'connections_tcp_fallback': stats.connections_tcp_fallback,
                      'connections_cfproxy': stats.connections_cfproxy,
                      'ws_pool_ready': {str(dc): len(bucket)
                          for (dc, media, test), bucket in list(ws_pool._idle.items())
                          if not media and not test},
                      'aes': _aes.Cipher(_aes.algorithms.AES(bytes(16)),
                                         _aes.modes.CTR(bytes(16))).encryptor().update(bytes(16)).hex()}
        else:
            raise ValueError('Unknown operation')
        return json.dumps(result)
    except BaseException as error:
        return json.dumps({'ok': False, 'error': _redact(error)})

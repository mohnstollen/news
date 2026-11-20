#!/usr/bin/python3
"""News – Linux-Anwendung (GTK 3 + WebKitGTK).

Zeigt dieselbe index.html wie PWA und Android-App in einem eigenen Fenster und stellt ihr
über linux/bridge.js die Plugins bereit, die unter Android die App-Hülle liefert:
HTTP ohne CORS (Feeds, Volltext, Nextcloud), Browser, Speichern-Dialog, Benachrichtigungen
und das helle oder dunkle Design des Desktops.

Aufruf:  newsreader [--version]
"""
import base64
import gzip
import json
import mimetypes
import os
import shutil
import sys
import threading
import time
import urllib.error
import urllib.request
import zlib
from urllib.parse import unquote, urlparse

import gi

gi.require_version('Gdk', '3.0')
gi.require_version('Gtk', '3.0')
try:
    gi.require_version('WebKit2', '4.1')
except ValueError:                      # ältere Distributionen (libsoup 2)
    gi.require_version('WebKit2', '4.0')
from gi.repository import Gdk, GdkPixbuf, Gio, GLib, Gtk, WebKit2  # noqa: E402

APP_ID = 'io.github.mohnstollen.newsreader'
SCHEME = 'newsreader'
START_URL = SCHEME + '://app/index.html'
HERE = os.path.dirname(os.path.abspath(__file__))
INSTALLED = os.path.isdir(os.path.join(HERE, 'www'))
WWW = os.path.join(HERE, 'www') if INSTALLED else os.path.dirname(HERE)
DATA_DIR = os.path.join(GLib.get_user_data_dir(), 'newsreader')
CACHE_DIR = os.path.join(GLib.get_user_cache_dir(), 'newsreader')
CONFIG_DIR = os.path.join(GLib.get_user_config_dir(), 'newsreader')
WINDOW_STATE = os.path.join(CONFIG_DIR, 'window.json')
MAX_RESPONSE = 8 * 1024 * 1024
RESUME_INTERVAL = 60            # Sekunden: so oft löst ein Fensterwechsel höchstens eine Prüfung aus


def read_text(path, default=''):
    try:
        with open(path, encoding='utf-8') as f:
            return f.read().strip()
    except OSError:
        return default


def app_version():
    if INSTALLED:
        return read_text(os.path.join(HERE, 'version'), '0')
    try:
        return json.loads(read_text(os.path.join(WWW, 'package.json')))['version']
    except (ValueError, KeyError):
        return '0'


class BridgeError(Exception):
    pass


class NoRedirectOnPost(urllib.request.HTTPRedirectHandler):
    """Weiterleitungen nur bei GET/HEAD folgen (wie OkHttp unter Android)."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if req.get_method() not in ('GET', 'HEAD'):
            return None
        return super().redirect_request(req, fp, code, msg, headers, newurl)


OPENER = urllib.request.build_opener(NoRedirectOnPost)


def http_request(args):
    """Gegenstück zu NewsHttpPlugin.java der Android-App."""
    url = args.get('url') or ''
    if urlparse(url).scheme not in ('http', 'https'):
        raise BridgeError('Ungültige Adresse: ' + url)
    method = (args.get('method') or 'GET').upper()
    body = args.get('body')
    data = body.encode('utf-8') if body is not None else (b'' if method in ('POST', 'PUT', 'PATCH') else None)
    headers = {str(k): str(v) for k, v in (args.get('headers') or {}).items() if v is not None}
    if not any(k.lower() == 'user-agent' for k in headers):
        headers['User-Agent'] = 'Mozilla/5.0 (X11; Linux) NewsReader/2'
    headers.setdefault('Accept-Encoding', 'gzip, deflate')
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        resp = OPENER.open(req, timeout=60)
    except urllib.error.HTTPError as e:     # 4xx/5xx (und 304) sind für die App normale Antworten
        resp = e
    except urllib.error.URLError as e:
        raise BridgeError(str(e.reason)) from e
    except (OSError, ValueError) as e:
        raise BridgeError(str(e)) from e
    with resp:
        try:
            raw = resp.read(MAX_RESPONSE + 1)
        except OSError as e:
            raise BridgeError(str(e)) from e
        if len(raw) > MAX_RESPONSE:
            raise BridgeError('Antwort ist zu groß')
        merged = {}
        for k, v in resp.headers.items():
            k = k.lower()
            merged[k] = merged[k] + ', ' + v if k in merged else v
        encoding = merged.get('content-encoding', '').lower()
        try:
            if encoding == 'gzip':
                raw = gzip.decompress(raw)
            elif encoding == 'deflate':
                raw = zlib.decompress(raw, -zlib.MAX_WBITS)
        except (OSError, EOFError, zlib.error):
            pass
        final_url = resp.geturl() if hasattr(resp, 'geturl') else url
        if args.get('binary'):
            out = base64.b64encode(raw).decode('ascii')
        else:
            charset = resp.headers.get_content_charset() if hasattr(resp.headers, 'get_content_charset') else None
            out = raw.decode(charset or 'utf-8', 'replace')
        return {'status': resp.status if hasattr(resp, 'status') else resp.code, 'headers': merged, 'url': final_url or url, 'data': out}


def export_dir():
    return os.path.join(CACHE_DIR, 'export')


def fs_write(args):
    """Datei für den Speichern-Dialog ablegen (nur im eigenen Cache-Ordner)."""
    if args.get('directory') != 'CACHE':
        raise BridgeError('Nur der Cache-Ordner ist erlaubt')
    base = os.path.realpath(export_dir())
    path = os.path.realpath(os.path.join(base, str(args.get('path') or '')))
    if not path.startswith(base + os.sep):
        raise BridgeError('Pfad außerhalb des erlaubten Ordners')
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8') as f:
        f.write(args.get('data') or '')
    return {'uri': GLib.filename_to_uri(path, None)}


def desktop_is_dark(settings):
    """Dunkles Design des Desktops: GNOME-Farbschema, „bevorzugt dunkel“ oder ein GTK-Thema mit „dark“ im Namen."""
    try:
        source = Gio.SettingsSchemaSource.get_default()
        if source and source.lookup('org.gnome.desktop.interface', True):
            iface = Gio.Settings.new('org.gnome.desktop.interface')
            if 'color-scheme' in iface.list_keys() and iface.get_string('color-scheme') == 'prefer-dark':
                return True
    except GLib.Error:
        pass
    if settings.get_property('gtk-application-prefer-dark-theme'):
        return True
    name = (settings.get_property('gtk-theme-name') or '') + ' ' + (os.environ.get('GTK_THEME') or '')
    return 'dark' in name.lower()


class Window(Gtk.ApplicationWindow):
    def __init__(self, app):
        super().__init__(application=app, title='News')
        self.app = app
        self.loaded = False
        self.closing = False
        self.last_resume = time.monotonic()
        self.state = self.load_state()
        self.set_default_size(self.state.get('width', 1280), self.state.get('height', 820))
        if self.state.get('maximized'):
            self.maximize()
        self.set_icon_name(APP_ID)
        if not INSTALLED:
            try:
                self.set_icon(GdkPixbuf.Pixbuf.new_from_file(os.path.join(WWW, 'icons', 'icon-192.png')))
            except GLib.Error:
                pass

        for d in (DATA_DIR, CACHE_DIR, CONFIG_DIR):
            os.makedirs(d, mode=0o700, exist_ok=True)
        manager = WebKit2.WebsiteDataManager(base_data_directory=DATA_DIR, base_cache_directory=CACHE_DIR)
        context = WebKit2.WebContext.new_with_website_data_manager(manager)
        context.register_uri_scheme(SCHEME, self.on_scheme_request)
        security = context.get_security_manager()
        security.register_uri_scheme_as_secure(SCHEME)
        security.register_uri_scheme_as_cors_enabled(SCHEME)

        ucm = WebKit2.UserContentManager()
        self.gtk_settings = Gtk.Settings.get_default()
        dark = desktop_is_dark(self.gtk_settings)
        ucm.add_script(WebKit2.UserScript.new(
            'window.__newsSystemDark=%s;\n' % ('true' if dark else 'false') + read_text(os.path.join(HERE, 'bridge.js')),
            WebKit2.UserContentInjectedFrames.TOP_FRAME, WebKit2.UserScriptInjectionTime.START, None, None))
        ucm.register_script_message_handler('newsreader')
        ucm.connect('script-message-received::newsreader', self.on_message)

        self.view = WebKit2.WebView(web_context=context, user_content_manager=ucm)
        settings = self.view.get_settings()
        debug = bool(os.environ.get('NEWSREADER_DEBUG'))
        settings.set_enable_developer_extras(debug)
        settings.set_enable_write_console_messages_to_stdout(debug)
        settings.set_javascript_can_access_clipboard(True)
        settings.set_enable_back_forward_navigation_gestures(False)
        # Software-Darstellung: vermeidet leere Fenster mit manchen Grafiktreibern (z. B. Nvidia, VMs)
        if not os.environ.get('NEWSREADER_GPU'):
            settings.set_hardware_acceleration_policy(WebKit2.HardwareAccelerationPolicy.NEVER)
        bg = (0.102, 0.075, 0.067) if dark else (1, 0.984, 1)
        self.view.set_background_color(Gdk.RGBA(*bg, 1))
        self.view.connect('decide-policy', self.on_decide_policy)
        self.view.connect('permission-request', self.on_permission_request)
        self.view.connect('context-menu', self.on_context_menu)
        self.view.connect('load-changed', self.on_load_changed)
        self.view.connect('web-process-terminated', lambda *_: self.view.load_uri(START_URL))
        self.add(self.view)

        for prop in ('gtk-theme-name', 'gtk-application-prefer-dark-theme'):
            self.gtk_settings.connect('notify::' + prop, self.on_theme_changed)

        self.connect('size-allocate', self.on_size_allocate)
        self.connect('window-state-event', self.on_window_state)
        self.connect('delete-event', self.on_delete)
        self.connect('focus-in-event', self.on_focus_in)
        self.view.load_uri(START_URL)
        self.show_all()

    # ----- Fenstergröße merken -----
    def load_state(self):
        try:
            s = json.loads(read_text(WINDOW_STATE, '{}'))
            return s if isinstance(s, dict) else {}
        except ValueError:
            return {}

    def on_size_allocate(self, *_):
        if not self.is_maximized():
            self.state['width'], self.state['height'] = self.get_size()

    def on_window_state(self, _w, event):
        self.state['maximized'] = bool(event.new_window_state & Gdk.WindowState.MAXIMIZED)

    def on_delete(self, *_):
        try:
            with open(WINDOW_STATE, 'w', encoding='utf-8') as f:
                json.dump(self.state, f)
        except OSError:
            pass
        if self.closing:
            return False
        # WebKit schreibt gespeicherte Daten (IndexedDB) im Hintergrund weg: das Fenster sofort
        # ausblenden, aber erst kurz danach schließen, damit nichts verloren geht.
        self.closing = True
        self.hide()
        GLib.timeout_add(800, self.destroy)
        return True

    def on_theme_changed(self, *_):
        self.fire('systemTheme', desktop_is_dark(self.gtk_settings))

    # ----- Seite ausliefern -----
    def on_scheme_request(self, request):
        rel = unquote(request.get_path() or '/').lstrip('/') or 'index.html'
        root = os.path.realpath(WWW)
        path = os.path.realpath(os.path.join(root, rel))
        allowed = ('index.html', 'manifest.webmanifest', 'icons' + os.sep, 'vendor' + os.sep)
        try:
            if not path.startswith(root + os.sep) or not os.path.relpath(path, root).startswith(allowed):
                raise OSError('nicht erlaubt')
            with open(path, 'rb') as f:
                data = f.read()
        except OSError as e:
            request.finish_error(GLib.Error.new_literal(Gio.io_error_quark(), str(e), Gio.IOErrorEnum.NOT_FOUND))
            return
        mime = mimetypes.guess_type(path)[0] or 'application/octet-stream'
        if path.endswith('.html'):
            mime = 'text/html'
        elif path.endswith('.js'):
            mime = 'text/javascript'
        request.finish(Gio.MemoryInputStream.new_from_bytes(GLib.Bytes.new(data)), len(data), mime)

    def on_decide_policy(self, _view, decision, kind):
        if kind in (WebKit2.PolicyDecisionType.NAVIGATION_ACTION, WebKit2.PolicyDecisionType.NEW_WINDOW_ACTION):
            uri = decision.get_navigation_action().get_request().get_uri() or ''
            if uri.startswith(SCHEME + '://') and kind == WebKit2.PolicyDecisionType.NAVIGATION_ACTION:
                return False
            # alles andere (Artikel-Links, Nextcloud-Anmeldung) öffnet der Standardbrowser
            self.open_external(uri)
            decision.ignore()
            return True
        return False

    def open_external(self, uri):
        if urlparse(uri).scheme in ('http', 'https', 'mailto'):
            try:
                Gtk.show_uri_on_window(self, uri, Gdk.CURRENT_TIME)
            except GLib.Error as e:
                print('Konnte %s nicht öffnen: %s' % (uri, e.message), file=sys.stderr)

    def on_permission_request(self, _view, request):
        # Kamera nur für den QR-Code der Nextcloud-Anmeldung
        if isinstance(request, WebKit2.UserMediaPermissionRequest):
            request.allow()
        else:
            request.deny()
        return True

    def on_context_menu(self, _view, _menu, _event, hit):
        # Kontextmenü nur in Eingabefeldern und markiertem Text, kein „Neu laden“ / „Zurück“
        return not (hit.context_is_editable() or hit.context_is_selection())

    def on_load_changed(self, _view, event):
        if event == WebKit2.LoadEvent.FINISHED:
            self.loaded = True
            test = os.environ.get('NEWSREADER_TEST_JS')       # nur für linux/test.mjs
            if test:
                self.run_js('(async()=>{%s})().then(r=>({ok:r}), e=>({error:String(e)+" | "+String(e && e.stack || "")}))'
                            '.then(r=>window.webkit.messageHandlers.newsreader.postMessage(JSON.stringify({test:r})))'
                            % read_text(test))

    def on_focus_in(self, *_):
        if time.monotonic() - self.last_resume >= RESUME_INTERVAL:
            self.last_resume = time.monotonic()
            self.fire('appStateChange', {'isActive': True})
        return False

    # ----- Brücke -----
    def run_js(self, script):
        if hasattr(self.view, 'evaluate_javascript'):
            self.view.evaluate_javascript(script, -1, None, None, None, None, None)
        else:
            self.view.run_javascript(script, None, None, None)

    def fire(self, event, arg=None):
        if self.loaded:
            self.run_js('window.__newsBridge && window.__newsBridge.fire(%s, %s)' % (json.dumps(event), json.dumps(arg)))

    def reply(self, call_id, error=None, result=None):
        self.run_js('window.__newsBridge.done(%s, %s, %s)' % (json.dumps(call_id), json.dumps(error), json.dumps(result)))
        return False

    def on_message(self, _ucm, message):
        value = message.get_js_value() if hasattr(message, 'get_js_value') else message
        try:
            msg = json.loads(value.to_string())
            if 'test' in msg and os.environ.get('NEWSREADER_TEST_JS'):
                print('TEST ' + json.dumps(msg['test'], ensure_ascii=False), flush=True)
                self.close()
                return
            call_id, plugin, method, args = msg['id'], msg['plugin'], msg['method'], msg.get('args') or {}
        except (ValueError, KeyError, TypeError):
            return
        blocking = {('NewsHttp', 'request'): http_request, ('Filesystem', 'writeFile'): fs_write}
        handler = blocking.get((plugin, method))
        if handler:                         # Netz und Dateien nicht im GUI-Thread
            def work():
                try:
                    result, error = handler(args), None
                except BridgeError as e:
                    result, error = None, str(e)
                except Exception as e:      # noqa: BLE001 – jeder Fehler muss als Ablehnung in der App ankommen
                    result, error = None, '%s: %s' % (type(e).__name__, e)
                GLib.idle_add(self.reply, call_id, error, result)
            threading.Thread(target=work, daemon=True).start()
            return
        try:
            result = self.handle_ui(plugin, method, args)
        except BridgeError as e:
            self.reply(call_id, str(e))
        else:
            self.reply(call_id, None, result)

    def handle_ui(self, plugin, method, args):
        if (plugin, method) == ('App', 'getInfo'):
            return {'version': app_version(), 'platform': 'linux'}
        if (plugin, method) == ('App', 'minimizeApp'):
            self.iconify()
            return {}
        if (plugin, method) == ('Browser', 'open'):
            self.open_external(str(args.get('url') or ''))
            return {}
        if (plugin, method) == ('Browser', 'close'):
            self.present()
            return {}
        if (plugin, method) == ('Share', 'share'):
            return self.save_as(args)
        if (plugin, method) == ('LocalNotifications', 'requestPermissions'):
            return {'display': 'granted'}
        if (plugin, method) == ('LocalNotifications', 'schedule'):
            for n in args.get('notifications') or []:
                note = Gio.Notification.new(str(n.get('title') or 'News'))
                note.set_body(str(n.get('body') or ''))
                note.set_icon(Gio.ThemedIcon.new(APP_ID))
                note.set_default_action_and_target('app.open-article', GLib.Variant('x', int((n.get('extra') or {}).get('articleId') or 0)))
                self.app.send_notification('new-articles', note)
            return {}
        raise BridgeError('Unbekannter Aufruf %s.%s' % (plugin, method))

    def save_as(self, args):
        """Export: statt des Android-Teilen-Menüs ein Speichern-Dialog."""
        files = args.get('files') or []
        src = GLib.filename_from_uri(files[0])[0] if files else None
        root = os.path.realpath(export_dir())
        if not src or not os.path.realpath(src).startswith(root + os.sep):
            raise BridgeError('Keine Datei zum Speichern')
        dialog = Gtk.FileChooserNative.new('Datei speichern', self, Gtk.FileChooserAction.SAVE, '_Speichern', '_Abbrechen')
        dialog.set_do_overwrite_confirmation(True)
        dialog.set_current_name(os.path.basename(src))
        folder = self.state.get('exportDir') or GLib.get_user_special_dir(GLib.UserDirectory.DIRECTORY_DOWNLOAD)
        if folder and os.path.isdir(folder):
            dialog.set_current_folder(folder)
        response = dialog.run()
        target = dialog.get_filename()
        dialog.destroy()
        try:
            if response != Gtk.ResponseType.ACCEPT or not target:
                raise BridgeError('cancelled')
            shutil.copyfile(src, target)
        except OSError as e:
            raise BridgeError(str(e)) from e
        finally:
            os.remove(src)
        self.state['exportDir'] = os.path.dirname(target)
        return {}


class App(Gtk.Application):
    def __init__(self):
        super().__init__(application_id=APP_ID, flags=Gio.ApplicationFlags.DEFAULT_FLAGS
                         if hasattr(Gio.ApplicationFlags, 'DEFAULT_FLAGS') else Gio.ApplicationFlags.FLAGS_NONE)
        self.window = None

    def do_startup(self):
        Gtk.Application.do_startup(self)
        quit_action = Gio.SimpleAction.new('quit', None)
        quit_action.connect('activate', lambda *_: (self.window.close() if self.window else self.quit()))
        self.add_action(quit_action)
        self.set_accels_for_action('app.quit', ['<Primary>q'])
        close_action = Gio.SimpleAction.new('close', None)
        close_action.connect('activate', lambda *_: self.window and self.window.close())
        self.add_action(close_action)
        self.set_accels_for_action('app.close', ['<Primary>w'])
        # Klick auf eine Benachrichtigung: Fenster zeigen und den Artikel öffnen
        open_article = Gio.SimpleAction.new('open-article', GLib.VariantType.new('x'))
        open_article.connect('activate', self.on_open_article)
        self.add_action(open_article)

    def on_open_article(self, _action, param):
        self.activate()
        article = param.get_int64() if param else 0
        if self.window and article:
            self.window.run_js('typeof openArticle==="function" && arts.has(%d) && openArticle(%d)' % (article, article))

    def do_activate(self):
        if self.window is None:
            self.window = Window(self)
        self.window.present()


def main():
    if len(sys.argv) > 1 and sys.argv[1] in ('--version', '-V'):
        print('News', app_version())
        return 0
    if len(sys.argv) > 1 and sys.argv[1] in ('--help', '-h'):
        print(__doc__.strip())
        return 0
    GLib.set_prgname(APP_ID)        # WM_CLASS / Wayland-App-ID → richtiges Symbol in Leiste und Dock
    GLib.set_application_name('News')
    return App().run(sys.argv[:1])


if __name__ == '__main__':
    sys.exit(main())

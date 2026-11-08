package dev.ghostmason.core.link;

import dev.ghostmason.core.ai.ClaudeCodeProvider;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * A small one-purpose web page on 127.0.0.1 where the player connects an AI: Claude through
 * their own Claude Code install, or an API key that never has to go through Minecraft chat.
 * Only reachable from this computer; the URL carries a random token, and requests with a
 * foreign Host or Origin header are refused.
 */
public final class KeyLinkServer {
	/** Checks and stores a choice. Returns null on success, otherwise an error to show on the page. */
	public interface Handler {
		/**
		 * @param provider "claudecode", "claude" or "deepseek"
		 * @param key      the pasted key, or "" for claudecode
		 */
		String onLink(String provider, String key);
	}

	private static final Set<String> PROVIDERS = Set.of("claudecode", "claude", "deepseek");
	private static final int MAX_HEADER_BYTES = 16 * 1024;
	private static final int MAX_BODY_BYTES = 4096;

	private final ServerSocket socket;
	private final String token;
	private final Handler handler;
	private final Supplier<ClaudeCodeProvider.Status> claudeCode;
	private final String defaultProvider;
	private final long deadline;
	private volatile boolean open = true;

	private KeyLinkServer(ServerSocket socket, String defaultProvider, Supplier<ClaudeCodeProvider.Status> claudeCode,
			Handler handler, Duration lifetime) {
		this.socket = socket;
		byte[] bytes = new byte[16];
		new SecureRandom().nextBytes(bytes);
		this.token = HexFormat.of().formatHex(bytes);
		this.handler = handler;
		this.claudeCode = claudeCode;
		this.defaultProvider = PROVIDERS.contains(defaultProvider) ? defaultProvider : "claude";
		this.deadline = System.currentTimeMillis() + lifetime.toMillis();
	}

	public static KeyLinkServer start(String defaultProvider, Supplier<ClaudeCodeProvider.Status> claudeCode,
			Handler handler, Duration lifetime) throws IOException {
		ServerSocket socket = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
		socket.setSoTimeout(1000);
		KeyLinkServer server = new KeyLinkServer(socket, defaultProvider, claudeCode, handler, lifetime);
		Thread thread = new Thread(server::run, "Ghost Mason link page");
		thread.setDaemon(true);
		thread.start();
		return server;
	}

	public URI uri() {
		return URI.create("http://127.0.0.1:" + socket.getLocalPort() + "/" + token);
	}

	public boolean isOpen() {
		return open;
	}

	public void close() {
		open = false;
		try {
			socket.close();
		} catch (IOException ignored) {
			// already closed
		}
	}

	private void run() {
		try {
			while (open && System.currentTimeMillis() < deadline) {
				try (Socket conn = socket.accept()) {
					conn.setSoTimeout(10_000);
					handle(conn);
				} catch (SocketTimeoutException ignored) {
					// check deadline and loop
				} catch (IOException e) {
					if (!open) {
						break;
					}
				}
			}
		} finally {
			close();
		}
	}

	// --- HTTP ----------------------------------------------------------------------------------

	private void handle(Socket conn) throws IOException {
		InputStream in = conn.getInputStream();
		OutputStream out = conn.getOutputStream();

		String head = readHead(in);
		if (head == null) {
			respond(out, 400, "Bad request", "text/plain");
			return;
		}
		String[] lines = head.split("\r\n");
		String[] requestLine = lines[0].split(" ");
		if (requestLine.length < 2) {
			respond(out, 400, "Bad request", "text/plain");
			return;
		}
		String method = requestLine[0];
		String path = requestLine[1];
		Map<String, String> headers = new HashMap<>();
		for (int i = 1; i < lines.length; i++) {
			int colon = lines[i].indexOf(':');
			if (colon > 0) {
				headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT), lines[i].substring(colon + 1).trim());
			}
		}

		// Refuse requests addressed to another host name (DNS rebinding) or sent by other sites.
		int port = socket.getLocalPort();
		String host = headers.getOrDefault("host", "");
		if (!host.equals("127.0.0.1:" + port) && !host.equals("localhost:" + port)) {
			respond(out, 403, "Forbidden", "text/plain");
			return;
		}
		// Browsers may send "Origin: null" for form posts; the secret token in the path is what
		// keeps other sites out, so only a different real origin is refused here.
		String origin = headers.get("origin");
		if (origin != null && !origin.equals("null")
				&& !origin.equals("http://127.0.0.1:" + port) && !origin.equals("http://localhost:" + port)) {
			respond(out, 403, "Forbidden", "text/plain");
			return;
		}
		String base = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
		if (!base.equals("/" + token)) {
			respond(out, 404, "Not found", "text/plain");
			return;
		}

		if (method.equals("GET")) {
			String query = path.contains("?") ? path.substring(path.indexOf('?') + 1) : "";
			String selected = parseForm(query).getOrDefault("p", defaultProvider);
			respond(out, 200, page(selected, null), "text/html; charset=utf-8");
		} else if (method.equals("POST")) {
			int length;
			try {
				length = Integer.parseInt(headers.getOrDefault("content-length", "-1"));
			} catch (NumberFormatException e) {
				length = -1;
			}
			if (length < 0 || length > MAX_BODY_BYTES) {
				respond(out, 413, "Too large", "text/plain");
				return;
			}
			Map<String, String> form = parseForm(new String(in.readNBytes(length), StandardCharsets.UTF_8));
			String provider = form.getOrDefault("provider", "");
			if (!PROVIDERS.contains(provider)) {
				respond(out, 400, "Bad request", "text/plain");
				return;
			}
			String key = form.getOrDefault("key", "").trim();
			String error;
			if (!provider.equals("claudecode")
					&& (key.isEmpty() || key.length() > 512 || key.chars().anyMatch(Character::isWhitespace))) {
				error = "That does not look like an API key.";
			} else {
				error = handler.onLink(provider, key);
			}
			if (error == null) {
				respond(out, 200, donePage(provider), "text/html; charset=utf-8");
				open = false;
			} else {
				respond(out, 200, page(provider, error), "text/html; charset=utf-8");
			}
		} else {
			respond(out, 405, "Method not allowed", "text/plain");
		}
	}

	private static String readHead(InputStream in) throws IOException {
		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		int matched = 0;
		byte[] end = {'\r', '\n', '\r', '\n'};
		while (buf.size() < MAX_HEADER_BYTES) {
			int b = in.read();
			if (b < 0) {
				return null;
			}
			buf.write(b);
			matched = b == end[matched] ? matched + 1 : (b == '\r' ? 1 : 0);
			if (matched == 4) {
				String s = buf.toString(StandardCharsets.ISO_8859_1);
				return s.substring(0, s.length() - 4);
			}
		}
		return null;
	}

	private static Map<String, String> parseForm(String body) {
		Map<String, String> form = new HashMap<>();
		for (String pair : body.split("&")) {
			int eq = pair.indexOf('=');
			if (eq > 0) {
				form.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
						URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
			}
		}
		return form;
	}

	private static void respond(OutputStream out, int status, String body, String contentType) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		String head = "HTTP/1.1 " + status + " " + (status == 200 ? "OK" : "Error") + "\r\n"
				+ "Content-Type: " + contentType + "\r\n"
				+ "Content-Length: " + bytes.length + "\r\n"
				+ "Cache-Control: no-store\r\n"
				+ "Referrer-Policy: same-origin\r\n"
				+ "X-Frame-Options: DENY\r\n"
				+ "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'\r\n"
				+ "Connection: close\r\n\r\n";
		out.write(head.getBytes(StandardCharsets.ISO_8859_1));
		out.write(bytes);
		out.flush();
	}

	// --- Pages ---------------------------------------------------------------------------------

	private static String escape(String s) {
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}

	private static final String GHOST_ICON = """
			<svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
			<path d="M5 20V10a7 7 0 0 1 14 0v10l-2.33-1.75L14.33 20 12 18.25 9.67 20l-2.34-1.75z"/>
			<circle cx="9.5" cy="10.5" r="1" fill="currentColor" stroke="none"/><circle cx="14.5" cy="10.5" r="1" fill="currentColor" stroke="none"/>
			</svg>""";

	private static final String EXTERNAL_ICON =
			"<svg viewBox=\"0 0 24 24\" width=\"14\" height=\"14\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><path d=\"M14 4h6v6\"/><path d=\"M20 4 10 14\"/><path d=\"M19 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1h5\"/></svg>";

	private static final String LOCK_ICON =
			"<svg viewBox=\"0 0 24 24\" width=\"14\" height=\"14\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><rect x=\"4\" y=\"11\" width=\"16\" height=\"10\" rx=\"2\"/><path d=\"M8 11V7a4 4 0 0 1 8 0v4\"/></svg>";

	private static final String STYLE = """
			<style>
			:root{
			  color-scheme:dark light;
			  --bg:#090b0e; --card:rgba(20,24,30,.78); --raised:rgba(255,255,255,.035);
			  --line:rgba(255,255,255,.08); --line-strong:rgba(255,255,255,.16);
			  --ink:#eef1f5; --muted:#97a3b0; --faint:#66727f;
			  --accent:#7be3c4; --accent-ink:#04261d; --accent-soft:rgba(123,227,196,.12);
			  --glow-a:rgba(80,200,165,.20); --glow-b:rgba(120,150,255,.16);
			  --ok:#5fd69c; --warn:#f3c76a; --err:#ff8d82;
			  --shadow:0 1px 0 rgba(255,255,255,.04) inset, 0 40px 90px -30px rgba(0,0,0,.75);
			  --mono:ui-monospace,"Cascadia Code","SF Mono",Consolas,monospace;
			}
			@media (prefers-color-scheme:light){:root{
			  --bg:#f2f4f7; --card:rgba(255,255,255,.82); --raised:rgba(15,23,32,.03);
			  --line:rgba(15,23,32,.08); --line-strong:rgba(15,23,32,.18);
			  --ink:#0e1620; --muted:#556270; --faint:#8793a0;
			  --accent:#0f8a6a; --accent-ink:#ffffff; --accent-soft:rgba(15,138,106,.10);
			  --glow-a:rgba(15,138,106,.14); --glow-b:rgba(70,110,230,.12);
			  --ok:#14864f; --warn:#9a6400; --err:#c2352a;
			  --shadow:0 1px 0 rgba(255,255,255,.7) inset, 0 40px 90px -40px rgba(15,23,32,.28);
			}}
			*{box-sizing:border-box}
			html,body{margin:0}
			body{min-height:100vh;display:grid;place-items:center;padding:40px 16px;color:var(--ink);
			  font:15px/1.55 "Inter",ui-sans-serif,system-ui,-apple-system,"Segoe UI",sans-serif;-webkit-font-smoothing:antialiased;
			  background:radial-gradient(900px 520px at 12% -8%,var(--glow-a),transparent 62%),
			             radial-gradient(800px 480px at 105% 4%,var(--glow-b),transparent 60%),var(--bg);}
			main{width:100%;max-width:540px;background:var(--card);border:1px solid var(--line);border-radius:20px;
			  padding:28px;box-shadow:var(--shadow);backdrop-filter:blur(18px) saturate(1.2);-webkit-backdrop-filter:blur(18px) saturate(1.2)}
			header{display:flex;align-items:center;gap:14px;margin-bottom:22px}
			.mark{width:44px;height:44px;flex:none;display:grid;place-items:center;border-radius:12px;color:var(--accent);
			  background:linear-gradient(145deg,var(--accent-soft),transparent);border:1px solid var(--line-strong)}
			.eyebrow{font-size:12px;letter-spacing:.08em;text-transform:uppercase;color:var(--faint);margin:0}
			h1{font-size:21px;line-height:1.25;letter-spacing:-.01em;margin:2px 0 0;font-weight:650}
			.picker{display:grid;grid-template-columns:repeat(3,1fr);gap:10px;margin-bottom:18px}
			.tile{padding-top:36px!important}
			.tile .badge{left:12px;right:auto}
			@media (max-width:560px){.picker{grid-template-columns:1fr}.tile{padding-top:14px!important}.tile .badge{left:auto;right:12px}main{padding:22px}}
			.tile{position:relative;display:block;padding:14px 14px 13px;border:1px solid var(--line);border-radius:14px;
			  background:var(--raised);cursor:pointer;transition:border-color .15s,background .15s,transform .15s}
			.tile:hover{border-color:var(--line-strong)}
			.tile input{position:absolute;opacity:0;pointer-events:none}
			.tile:has(input:checked){border-color:var(--accent);background:var(--accent-soft)}
			.tile:has(input:focus-visible){outline:2px solid var(--accent);outline-offset:2px}
			.tile b{display:block;font-weight:600;font-size:15px}
			.tile small{display:block;color:var(--muted);font-size:13px;margin-top:2px}
			.badge{position:absolute;top:12px;right:12px;font-size:11px;font-weight:600;letter-spacing:.02em;
			  padding:2px 8px;border-radius:999px;border:1px solid var(--line-strong);color:var(--muted)}
			.tile:has(input:checked) .badge{border-color:transparent;background:var(--accent);color:var(--accent-ink)}
			.panel{display:none;animation:in .2s ease-out}
			body:has(#pv-claude:checked) .panel.claude,body:has(#pv-claudecode:checked) .panel.claudecode,body:has(#pv-deepseek:checked) .panel.deepseek{display:block}
			@keyframes in{from{opacity:0;transform:translateY(4px)}to{opacity:1;transform:none}}
			.status{display:flex;gap:12px;align-items:flex-start;padding:14px;border:1px solid var(--line);border-radius:14px;background:var(--raised);margin-bottom:16px}
			.dot{width:10px;height:10px;border-radius:50%;margin-top:6px;flex:none;background:var(--c);box-shadow:0 0 0 4px color-mix(in srgb,var(--c) 18%,transparent)}
			.status b{display:block;font-weight:600}
			.status span{color:var(--muted);font-size:13.5px}
			ol.steps{margin:0 0 16px;padding:0;list-style:none;counter-reset:s;display:grid;gap:12px}
			ol.steps li{counter-increment:s;display:grid;grid-template-columns:26px 1fr;gap:10px;align-items:start}
			ol.steps li::before{content:counter(s);width:26px;height:26px;border-radius:50%;display:grid;place-items:center;
			  font-size:12.5px;font-weight:600;color:var(--muted);border:1px solid var(--line-strong)}
			code,.cmd{font-family:var(--mono);font-size:12.5px}
			.cmd{display:block;margin-top:6px;padding:9px 11px;border-radius:9px;border:1px solid var(--line);background:rgba(0,0,0,.22);
			  color:var(--ink);overflow-x:auto;white-space:nowrap;user-select:all}
			@media (prefers-color-scheme:light){.cmd{background:rgba(15,23,32,.04)}}
			.os{color:var(--faint);font-size:12px;margin-top:8px;display:block}
			input[type=password]{width:100%;padding:12px 14px;border-radius:11px;border:1px solid var(--line-strong);background:transparent;
			  color:var(--ink);font:500 14px var(--mono);letter-spacing:.02em;transition:border-color .15s,box-shadow .15s}
			input[type=password]:focus{outline:none;border-color:var(--accent);box-shadow:0 0 0 4px var(--accent-soft)}
			.btn{display:inline-flex;align-items:center;justify-content:center;gap:8px;width:100%;margin-top:12px;padding:12px 16px;
			  border-radius:11px;border:1px solid transparent;font-family:inherit;font-weight:600;font-size:15px;cursor:pointer;text-decoration:none;
			  transition:filter .15s,transform .05s,background .15s}
			.btn:active{transform:translateY(1px)}
			.btn.primary{background:var(--accent);color:var(--accent-ink)}
			.btn.primary:hover{filter:brightness(1.07)}
			.btn.ghost{background:transparent;color:var(--ink);border-color:var(--line-strong)}
			.btn.ghost:hover{background:var(--raised)}
			.btn:focus-visible{outline:2px solid var(--accent);outline-offset:2px}
			a{color:var(--accent);text-underline-offset:3px}
			.alert{display:flex;gap:10px;padding:11px 13px;border-radius:12px;margin-bottom:14px;font-size:14px;
			  color:var(--err);background:color-mix(in srgb,var(--err) 10%,transparent);border:1px solid color-mix(in srgb,var(--err) 30%,transparent)}
			details{margin-top:18px;border-top:1px solid var(--line);padding-top:14px}
			summary{cursor:pointer;color:var(--muted);font-size:14px;list-style:none;display:flex;align-items:center;gap:8px}
			summary::-webkit-details-marker{display:none}
			summary::before{content:"";width:6px;height:6px;border-right:1.6px solid currentColor;border-bottom:1.6px solid currentColor;transform:rotate(-45deg);transition:transform .15s}
			details[open] summary::before{transform:rotate(45deg)}
			details form{margin-top:12px}
			.hint{color:var(--muted);font-size:13.5px;margin:0 0 10px}
			footer{display:flex;align-items:center;gap:8px;margin-top:22px;padding-top:16px;border-top:1px solid var(--line);color:var(--faint);font-size:12.5px}
			.done{text-align:center;padding:12px 0 4px}
			.check{width:64px;height:64px;margin:0 auto 16px;border-radius:50%;display:grid;place-items:center;
			  background:var(--accent-soft);color:var(--accent);border:1px solid color-mix(in srgb,var(--accent) 40%,transparent)}
			.check path{stroke-dasharray:24;stroke-dashoffset:24;animation:draw .5s .15s ease-out forwards}
			@keyframes draw{to{stroke-dashoffset:0}}
			.done p{color:var(--muted);margin:6px 0 0}
			@media (prefers-reduced-motion:reduce){*{animation:none!important;transition:none!important}.check path{stroke-dashoffset:0}}
			</style>
			""";

	private static String shell(String title, String body) {
		return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
				+ "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
				+ "<title>" + title + "</title>" + STYLE + "</head><body><main>" + body
				+ "<footer>" + LOCK_ICON + "<span>Served by Minecraft on this computer (127.0.0.1). Nothing is sent anywhere except to the AI you pick.</span></footer>"
				+ "</main></body></html>";
	}

	private String page(String selected, String error) {
		String sel = PROVIDERS.contains(selected) ? selected : "claude";
		String self = "/" + token;
		String alert = error == null ? "" : "<div class=\"alert\" role=\"alert\">" + escape(error) + "</div>";

		StringBuilder b = new StringBuilder();
		b.append("<header><div class=\"mark\">").append(GHOST_ICON).append("</div><div>")
				.append("<p class=\"eyebrow\">Ghost Mason</p><h1>Connect an AI architect</h1></div></header>");

		b.append("<div class=\"picker\" role=\"radiogroup\" aria-label=\"AI provider\">")
				.append(tile("claude", sel, "API key", "Claude", "Anthropic API, billed per use"))
				.append(tile("claudecode", sel, "Local CLI", "Claude Code", "Your own Claude Code install"))
				.append(tile("deepseek", sel, "API key", "DeepSeek", "DeepSeek API, billed per use"))
				.append("</div>");

		// Claude with an API key
		b.append("<section class=\"panel claude\">");
		if (sel.equals("claude")) {
			b.append(alert);
		}
		b.append("<ol class=\"steps\">")
				.append("<li><div>Create an API key in the Claude Console.")
				.append("<a class=\"btn ghost\" href=\"https://platform.claude.com/settings/keys\" target=\"_blank\" rel=\"noopener noreferrer\">Open Claude Console ").append(EXTERNAL_ICON).append("</a></div></li>")
				.append("<li><div>Paste it here. Ghost Mason tests it once before saving.")
				.append("<form method=\"post\" action=\"").append(self).append("\" style=\"margin-top:8px\"><input type=\"hidden\" name=\"provider\" value=\"claude\">")
				.append("<input type=\"password\" name=\"key\" placeholder=\"sk-ant-...\" autocomplete=\"off\" aria-label=\"Anthropic API key\" required>")
				.append("<button class=\"btn primary\" type=\"submit\">Link Claude</button></form></div></li>")
				.append("</ol></section>");

		// Claude through the player's own Claude Code install
		b.append("<section class=\"panel claudecode\">");
		if (sel.equals("claudecode")) {
			b.append(alert);
		}
		ClaudeCodeProvider.Status cc = claudeCode.get();
		if (cc.installed() && cc.loggedIn()) {
			b.append(status("var(--ok)", "Claude Code " + escape(cc.version()) + " is ready",
					"Ghost Mason will run it on this computer, with the account it is signed in to."));
			b.append("<form method=\"post\" action=\"").append(self).append("\"><input type=\"hidden\" name=\"provider\" value=\"claudecode\">")
					.append("<button class=\"btn primary\" type=\"submit\">Use Claude Code</button></form>");
		} else if (cc.installed()) {
			b.append(status("var(--warn)", "Claude Code " + escape(cc.version()) + " is not signed in",
					"Sign in once, then come back to this page."));
			b.append("<ol class=\"steps\"><li><div>Open a terminal and start Claude Code:<span class=\"cmd\">claude</span></div></li>")
					.append("<li><div>Type <code>/login</code> and sign in.</div></li></ol>");
			b.append("<a class=\"btn ghost\" href=\"").append(self).append("?p=claudecode\">Check again</a>");
		} else {
			b.append(status("var(--err)", "Claude Code was not found",
					"Install it on this computer to use this option, or pick another provider."));
			b.append("<ol class=\"steps\"><li><div>Install Claude Code:")
					.append("<span class=\"os\">Windows (PowerShell)</span><span class=\"cmd\">irm https://claude.ai/install.ps1 | iex</span>")
					.append("<span class=\"os\">macOS / Linux</span><span class=\"cmd\">curl -fsSL https://claude.ai/install.sh | bash</span></div></li>")
					.append("<li><div>Run <code>claude</code>, then type <code>/login</code> to sign in.</div></li>")
					.append("<li><div>Come back here. <a href=\"https://code.claude.com/docs/en/setup\" target=\"_blank\" rel=\"noopener noreferrer\">Setup guide</a></div></li></ol>");
			b.append("<a class=\"btn ghost\" href=\"").append(self).append("?p=claudecode\">Check again</a>");
		}
		b.append("</section>");

		// DeepSeek
		b.append("<section class=\"panel deepseek\">");
		if (sel.equals("deepseek")) {
			b.append(alert);
		}
		b.append("<ol class=\"steps\">")
				.append("<li><div>Create an API key on the DeepSeek platform.")
				.append("<a class=\"btn ghost\" href=\"https://platform.deepseek.com/api_keys\" target=\"_blank\" rel=\"noopener noreferrer\">Open DeepSeek ").append(EXTERNAL_ICON).append("</a></div></li>")
				.append("<li><div>Paste it here. Ghost Mason tests it once before saving.")
				.append("<form method=\"post\" action=\"").append(self).append("\" style=\"margin-top:8px\"><input type=\"hidden\" name=\"provider\" value=\"deepseek\">")
				.append("<input type=\"password\" name=\"key\" placeholder=\"sk-...\" autocomplete=\"off\" aria-label=\"DeepSeek API key\" required>")
				.append("<button class=\"btn primary\" type=\"submit\">Link DeepSeek</button></form></div></li>")
				.append("</ol></section>");

		return shell("Connect an AI · Ghost Mason", b.toString());
	}

	private static String tile(String id, String selected, String badge, String name, String caption) {
		return "<label class=\"tile\"><input type=\"radio\" name=\"pv\" id=\"pv-" + id + "\"" + (id.equals(selected) ? " checked" : "") + ">"
				+ "<span class=\"badge\">" + badge + "</span><b>" + name + "</b><small>" + caption + "</small></label>";
	}

	private static String status(String color, String title, String text) {
		return "<div class=\"status\"><span class=\"dot\" style=\"--c:" + color + "\"></span><div><b>" + title + "</b><span>" + text + "</span></div></div>";
	}

	private static String donePage(String provider) {
		String name = switch (provider) {
			case "deepseek" -> "DeepSeek";
			case "claude" -> "Your Anthropic API key";
			default -> "Claude Code";
		};
		String body = "<div class=\"done\"><div class=\"check\"><svg viewBox=\"0 0 24 24\" width=\"30\" height=\"30\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.4\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><path d=\"M5 12.5l4.5 4.5L19 7.5\"/></svg></div>"
				+ "<h1>" + name + " is connected</h1>"
				+ "<p>You can close this tab and head back to Minecraft.</p></div>";
		return shell("Connected · Ghost Mason", body);
	}
}

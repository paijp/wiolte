#include "WioOTA.h"
#include "wioota_layout.h"
#include "wioota_flash.h"

#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define AIRCR	(*(volatile uint32_t *)0xe000ed0cUL)

static char s_buf[WIOOTA_CHUNK + 1];	/* shared by log POST / poll / chunk GET */
static char s_rx[WIOOTA_CHUNK + 2048];	/* raw HTTP response (headers + body) */

static void ota_logf(Print *out, const char *fmt, ...)
{
	char b[96];
	va_list ap;

	va_start(ap, fmt);
	vsnprintf(b, sizeof(b), fmt, ap);
	va_end(ap);
	out->print(b);
}

static void system_reset(void)
{
	__asm volatile("dsb");
	AIRCR = 0x05fa0004UL;		/* VECTKEY | SYSRESETREQ */
	for (;;)
		;
}

WioOTA::WioOTA()
	: _wio(NULL), _base(NULL), _interval(10000), _last(0), _first(true),
	  _handler(NULL), _echo(NULL), _timing(false), _head(0), _tail(0)
{
}

void WioOTA::begin(WioLTE &wio, const char *baseUrl, unsigned long intervalMs)
{
	_wio = &wio;
	_base = baseUrl;
	parseUrl(baseUrl);
	_interval = intervalMs;
	_first = true;
	bootReport();
}

/* Split "http://host[:port][/path]" into _host, _port, _path. */
void WioOTA::parseUrl(const char *u)
{
	const char *p = u, *h;
	int n;

	if (strncmp(p, "http://", 7) == 0)
		p += 7;
	h = p;
	while (*p && *p != ':' && *p != '/')
		p++;
	n = p - h < (int)sizeof(_host) - 1 ? p - h : (int)sizeof(_host) - 1;
	memcpy(_host, h, n);
	_host[n] = 0;
	_port = 80;
	if (*p == ':') {
		_port = atoi(p + 1);
		while (*p && *p != '/')
			p++;
	}
	snprintf(_path, sizeof(_path), "%s", *p ? p : "/");
}

static int ci_prefix(const char *s, const char *pre)
{
	for (; *pre; s++, pre++)
		if ((*s | 0x20) != (*pre | 0x20))
			return 0;
	return 1;
}

/*
 * One HTTP/1.1 request over a plain TCP socket (AT+QIOPEN etc.).
 * The module's own HTTP client (AT+QHTTPGET/POST) was seen to wait ~30 s
 * before connecting on every other request; raw sockets do not.
 * Returns the body length copied to out (NUL-terminated), or -1.
 */
int WioOTA::http(const char *method, const char *query, const char *body, int bodyLen,
	char *out, int outSize, int *status)
{
	char hdr[256];
	int id, n, got = 0, hl, clen = -1, off;
	unsigned long t0;
	char *p, *end;

	*status = -1;
	id = _wio->SocketOpen(_host, _port, WioLTE::SOCKET_TCP);
	if (id < 0)
		return -1;
	n = snprintf(hdr, sizeof(hdr),
		"%s %s%s%s HTTP/1.1\r\nHost: %s:%d\r\nConnection: close\r\n"
		"Content-Type: text/plain\r\nContent-Length: %d\r\n\r\n",
		method, _path, query ? (strchr(_path, '?') ? "&" : "?") : "", query ? query : "",
		_host, _port, bodyLen);
	if (n >= (int)sizeof(hdr) || !_wio->SocketSend(id, (const byte *)hdr, n))
		goto fail;
	for (off = 0; off < bodyLen; off += n) {
		n = bodyLen - off < 1024 ? bodyLen - off : 1024;
		if (!_wio->SocketSend(id, (const byte *)body + off, n))
			goto fail;
	}
	/* Read until Content-Length is satisfied (the server always sends it). */
	t0 = millis();
	while (millis() - t0 < 30000) {
		if ((int)sizeof(s_rx) - 1 - got < 1500)
			break;			/* QIRD may return up to 1500 bytes */
		n = _wio->SocketReceive(id, (byte *)s_rx + got, sizeof(s_rx) - 1 - got);
		if (n < 0)
			goto fail;
		if (n == 0) {
			delay(10);
			continue;
		}
		got += n;
		s_rx[got] = 0;
		end = strstr(s_rx, "\r\n\r\n");
		if (end == NULL)
			continue;
		hl = end + 4 - s_rx;
		if (clen < 0) {
			*status = strncmp(s_rx, "HTTP/1.", 7) == 0 ? atoi(s_rx + 9) : -1;
			for (p = s_rx; p && p < end; p = strstr(p, "\r\n") ? strstr(p, "\r\n") + 2 : NULL)
				if (ci_prefix(p, "content-length:"))
					clen = atoi(p + 15);
			if (clen < 0)
				goto fail;	/* no chunked support; the server sends a length */
		}
		if (got - hl >= clen)
			break;
	}
	_wio->SocketClose(id);
	end = strstr(s_rx, "\r\n\r\n");
	if (got == 0 || end == NULL || clen < 0 || got - (end + 4 - s_rx) < clen || clen > outSize - 1)
		return -1;
	memcpy(out, end + 4, clen);
	out[clen] = 0;
	return clen;
fail:
	_wio->SocketClose(id);
	return -1;
}

/* Describe what the bootloader did with the last update record. */
void WioOTA::bootReport()
{
	const struct wioota_meta *m = (const struct wioota_meta *)WIOOTA_META_ADDR;

	if (m->magic != WIOOTA_MAGIC)
		print("wioota: boot (no update record; written by DFU)\n");
	else if (m->fail == 0)
		ota_logf(this, "wioota: boot (update REJECTED by bootloader: staging crc != %08lx)\n",
			(unsigned long)m->crc);
	else if (m->done == 0)
		ota_logf(this, "wioota: boot (running image %lu bytes crc %08lx, installed by bootloader)\n",
			(unsigned long)m->len, (unsigned long)m->crc);
	else
		print("wioota: boot (update record pending; bootloader did not copy it)\n");
}

uint32_t WioOTA::currentCrc() const
{
	const struct wioota_meta *m = (const struct wioota_meta *)WIOOTA_META_ADDR;

	if (m->magic == WIOOTA_MAGIC && m->done == 0 && m->fail != 0)
		return m->crc;
	return 0;
}

size_t WioOTA::write(uint8_t c)
{
	if (_echo)
		_echo->write(c);
	if (_head - _tail >= WIOOTA_LOG_SIZE)
		return 0;		/* full: drop newest */
	_log[_head % WIOOTA_LOG_SIZE] = c ? c : '?';
	_head++;
	return 1;
}

void WioOTA::url(char *buf, int size, const char *query)
{
	const char *sep = strchr(_base, '?') ? "&" : "?";

	snprintf(buf, size, "%s%s%s", _base, sep, query);
}

bool WioOTA::postLog()
{
	char rb[16];
	uint32_t n = _head - _tail, i;
	int code = 0;

	if (n == 0)
		return true;
	if (n > WIOOTA_POST_MAX)
		n = WIOOTA_POST_MAX;
	for (i = 0; i < n; i++)
		s_buf[i] = _log[(_tail + i) % WIOOTA_LOG_SIZE];
	s_buf[n] = 0;
	unsigned long t0 = millis();
	bool posted = http("POST", "a=log", s_buf, n, rb, sizeof(rb), &code) >= 0;
	if (_timing)
		ota_logf(this, "timing: log POST %lu ms ok=%d code=%d bytes=%lu\n",
			millis() - t0, posted, code, (unsigned long)n);
	if (!posted) {
		diag("wioota: log POST failed (no HTTP response; check LTE / Beam)");
		return false;
	}
	if (code != 200) {
		char m[64];

		snprintf(m, sizeof(m), "wioota: log POST HTTP %d", code);
		diag(m);
		return false;
	}
	_tail += n;
	return true;
}

bool WioOTA::fetch(char *resp, int size)
{
	char q[48];
	int len, code;

	snprintf(q, sizeof(q), "a=poll&crc=%08lx", (unsigned long)currentCrc());
	unsigned long t0 = millis();
	len = http("GET", q, NULL, 0, resp, size, &code);
	if (len >= 0 && code != 200) {
		char m[48];

		snprintf(m, sizeof(m), "wioota: poll GET HTTP %d", code);
		diag(m);
		return false;
	}
	if (_timing)
		ota_logf(this, "timing: poll GET %lu ms len=%d\n", millis() - t0, len);
	if (len < 0) {
		diag("wioota: poll GET failed (no HTTP response; check LTE / Beam)");
		return false;
	}
	resp[len] = 0;
	/* Every server reply starts with "ok"; anything else is e.g. an error page. */
	if (strncmp(resp, "ok\n", 3) != 0) {
		char m[100];
		int i;

		snprintf(m, sizeof(m), "wioota: unexpected poll reply (%d bytes): %.60s", len, resp);
		for (i = 0; m[i]; i++)
			if (m[i] == '\r' || m[i] == '\n')
				m[i] = ' ';
		diag(m);
		return false;
	}
	memmove(resp, resp + 3, len - 2);
	return true;
}

/*
 * Diagnostics go to the echo stream (USB serial) only: they describe
 * why the server could not be reached, so queueing them for the
 * server would be pointless.  Repeats are suppressed.
 */
void WioOTA::diag(const char *msg)
{
	static char last[100];

	if (_echo == NULL || strcmp(last, msg) == 0)
		return;
	strncpy(last, msg, sizeof(last) - 1);
	_echo->println(msg);
}

void WioOTA::handleLine(char *line)
{
	if (strncmp(line, "fw ", 3) == 0) {
		char *p;
		uint32_t len = strtoul(line + 3, &p, 10);
		uint32_t crc = strtoul(p, NULL, 16);

		if (crc != currentCrc())
			update(len, crc);	/* resets on success */
		return;
	}
	if (strcmp(line, "reset") == 0) {
		print("wioota: reset by command\n");
		postLog();
		system_reset();
	}
	if (strncmp(line, "interval ", 9) == 0) {
		long sec = atol(line + 9);

		if (sec >= 1 && sec <= 86400) {
			_interval = sec * 1000UL;
			ota_logf(this, "wioota: interval %ld s\n", sec);
		} else {
			print("wioota: interval must be 1..86400 s\n");
		}
		return;
	}
	if (_handler)
		_handler(line);
}

bool WioOTA::poll(bool force)
{
	char *p, *line;

	if (_wio == NULL)
		return false;
	if (!force && !_first && millis() - _last < _interval)
		return false;
	_first = false;
	_last = millis();

	postLog();		/* a failed upload must not block updates */
	if (!fetch(s_buf, 1024))
		return false;
	diag("wioota: server OK");

	/* Split before dispatching: an update reuses s_buf. */
	static char resp[1025];
	strcpy(resp, s_buf);
	for (p = resp; (line = strsep(&p, "\n")) != NULL; ) {
		size_t l = strlen(line);

		if (l > 0 && line[l - 1] == '\r')
			line[--l] = 0;
		if (l > 0)
			handleLine(line);
	}
	_last = millis();
	return true;
}

bool WioOTA::update(uint32_t len, uint32_t crc)
{
	char q[64];
	uint32_t off;
	struct wioota_meta m;

	if (len == 0 || len > WIOOTA_APP_SIZE) {
		ota_logf(this, "wioota: bad image size %lu\n", (unsigned long)len);
		return false;
	}
	ota_logf(this, "wioota: update %lu bytes crc %08lx\n", (unsigned long)len, (unsigned long)crc);
	postLog();

	wf_unlock();
	if (wf_erase_range(WIOOTA_STAGE_ADDR, len)) {
		wf_lock();
		print("wioota: erase failed\n");
		return false;
	}
	for (off = 0; off < len; ) {
		uint32_t n = len - off;
		int got = -1, retry;

		if (n > WIOOTA_CHUNK)
			n = WIOOTA_CHUNK;
		snprintf(q, sizeof(q), "a=fw&o=%lu&n=%lu&crc=%08lx",
			(unsigned long)off, (unsigned long)n, (unsigned long)crc);
		for (retry = 0; retry < 3; retry++) {
			int code;

			got = http("GET", q, NULL, 0, s_buf, sizeof(s_buf), &code);
			if (code != 200)
				got = -1;
			if (got == (int)n)
				break;
		}
		if (got != (int)n || wf_program(WIOOTA_STAGE_ADDR + off, s_buf, n)) {
			wf_lock();
			ota_logf(this, "wioota: download failed at %lu\n", (unsigned long)off);
			return false;
		}
		off += n;
	}
	if (wf_crc32(WIOOTA_STAGE_ADDR, len) != crc) {
		wf_lock();
		print("wioota: crc mismatch\n");
		return false;
	}
	m.magic = WIOOTA_MAGIC;
	m.len = len;
	m.crc = crc;
	m.done = 0xffffffffUL;
	m.fail = 0xffffffffUL;
	if (wf_erase_sector(WIOOTA_META_SECTOR) ||
	    wf_program(WIOOTA_META_ADDR, &m, sizeof(m))) {
		wf_lock();
		print("wioota: record write failed\n");
		return false;
	}
	wf_lock();
	print("wioota: rebooting into new firmware\n");
	postLog();
	system_reset();
	return true;
}

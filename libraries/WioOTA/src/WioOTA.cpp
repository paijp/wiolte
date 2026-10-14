#include "WioOTA.h"
#include "wioota_layout.h"
#include "wioota_flash.h"

#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define AIRCR	(*(volatile uint32_t *)0xe000ed0cUL)

static char s_buf[WIOOTA_CHUNK + 1];	/* shared by log POST / poll / chunk GET */

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
	  _handler(NULL), _echo(NULL), _head(0), _tail(0)
{
}

void WioOTA::begin(WioLTE &wio, const char *baseUrl, unsigned long intervalMs)
{
	_wio = &wio;
	_base = baseUrl;
	_interval = intervalMs;
	_first = true;
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
	char u[256];
	uint32_t n = _head - _tail, i;
	int code = 0;

	if (n == 0)
		return true;
	if (n > WIOOTA_POST_MAX)
		n = WIOOTA_POST_MAX;
	for (i = 0; i < n; i++)
		s_buf[i] = _log[(_tail + i) % WIOOTA_LOG_SIZE];
	s_buf[n] = 0;
	url(u, sizeof(u), "a=log");
	if (!_wio->HttpPost(u, s_buf, &code) || code != 200)
		return false;
	_tail += n;
	return true;
}

bool WioOTA::fetch(char *resp, int size)
{
	char u[256], q[48];
	int len;

	snprintf(q, sizeof(q), "a=poll&crc=%08lx", (unsigned long)currentCrc());
	url(u, sizeof(u), q);
	len = _wio->HttpGet(u, resp, size);
	if (len < 0)
		return false;
	resp[len] = 0;
	return true;
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
	char u[256], q[64];
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
		url(u, sizeof(u), q);
		for (retry = 0; retry < 3; retry++) {
			got = _wio->HttpGet(u, s_buf, sizeof(s_buf));
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

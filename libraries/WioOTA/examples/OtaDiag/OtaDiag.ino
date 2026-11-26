/*
 * WioOTA diagnostic sketch: finds where HTTP requests through SORACOM
 * Beam stall.
 *
 * - polls every second ("fast on", default) or every 10 s ("fast off")
 * - logs how long each log POST / poll GET took ("timing: ..."),
 *   to USB serial and to the server log
 * - every 30 s logs the signal strength ("rssi ...")
 * - "probe" (and every 2 minutes) opens a raw TCP connection to Beam and
 *   logs connect / first byte / total times separately, so DNS + TCP
 *   connect can be told apart from the HTTP exchange
 * - built with WIO_DEBUG (CI), the WioLTE library's AT command trace goes
 *   to USB serial, each line prefixed with millis()
 *
 * Commands (server cmd.txt or USB serial): fast on, fast off, probe,
 * led R G B, ping, reset (server only).
 */
#include <WioLTEforArduino.h>
#include <WioOTA.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>

#ifndef OTA_URL
#define OTA_URL	"http://beam.soracom.io:18080/"
#endif
#define PROBE_HOST	"beam.soracom.io"
#define PROBE_PORT	18080

WioLTE Wio;
WioOTA Ota;

static bool probeNow;

static void dlog(const char *fmt, ...)
{
	char b[160];
	va_list ap;

	va_start(ap, fmt);
	vsnprintf(b, sizeof(b), fmt, ap);
	va_end(ap);
	Ota.print(b);
}

/* Raw HTTP over a TCP socket, timed step by step. */
static void probe()
{
	static char buf[512];
	unsigned long t0 = millis(), t1, t2 = 0;
	int id, n, total = 0;

	id = Wio.SocketOpen(PROBE_HOST, PROBE_PORT, WioLTE::SOCKET_TCP);
	t1 = millis();
	if (id < 0) {
		dlog("probe: open failed after %lu ms (err %d)\n", t1 - t0, (int)Wio.GetLastError());
		return;
	}
	Wio.SocketSend(id, "GET /?a=poll&crc=probe HTTP/1.1\r\nHost: " PROBE_HOST "\r\nConnection: close\r\n\r\n");
	while (millis() - t1 < 60000) {
		n = Wio.SocketReceive(id, buf, sizeof(buf) - 1, 2000);
		if (n > 0) {
			if (!t2)
				t2 = millis();
			total += n;
		} else if (t2) {
			break;		/* nothing more after the reply started */
		}
	}
	Wio.SocketClose(id);
	dlog("probe: open %lu ms, first byte %lu ms, total %lu ms, %d bytes\n",
		t1 - t0, t2 ? t2 - t1 : 0UL, millis() - t0, total);
}

static void onCommand(const char *line)
{
	int r, g, b;

	if (sscanf(line, "led %d %d %d", &r, &g, &b) == 3) {
		Wio.LedSetRGB(r, g, b);
		Ota.print("led ok\n");
	} else if (strcmp(line, "ping") == 0) {
		Ota.print("pong\n");
	} else if (strcmp(line, "fast on") == 0) {
		Ota.setInterval(1000);
		Ota.print("fast on\n");
	} else if (strcmp(line, "fast off") == 0) {
		Ota.setInterval(10000);
		Ota.print("fast off\n");
	} else if (strcmp(line, "probe") == 0) {
		probeNow = true;
	} else {
		Ota.print("unknown command: ");
		Ota.println(line);
	}
}

void setup()
{
	char s[32];

	delay(200);
	SerialUSB.begin(115200);
	Wio.Init();
	Wio.PowerSupplyLTE(true);
	delay(500);
	Ota.setEcho(&SerialUSB);
	Ota.setTiming(true);
	Ota.onCommand(onCommand);

	Wio.LedSetRGB(10, 10, 0);
	SerialUSB.println("lte: power on");
	while (!Wio.TurnOnOrReset()) {
		SerialUSB.println("lte: module not responding, retry");
		delay(1000);
	}
	SerialUSB.println("lte: attach soracom.io");
	unsigned long t0 = millis();
	while (!Wio.Activate("soracom.io", "sora", "sora")) {
		SerialUSB.println("lte: attach failed (SIM / coverage?), retry");
		delay(1000);
	}
	SerialUSB.println("lte: connected, server " OTA_URL);
	Wio.LedSetRGB(0, 10, 0);

	Ota.begin(Wio, OTA_URL, 1000);
	Ota.print("OtaDiag boot, crc=");
	Ota.println(Ota.currentCrc(), HEX);
	if (Wio.GetRevision(s, sizeof(s)) > 0)
		dlog("module %s\n", s);
	dlog("attach took %lu ms, rssi %d\n", millis() - t0, Wio.GetReceivedSignalStrength());
}

void loop()
{
	static unsigned long lastRssi, lastProbe;

	if (millis() - lastRssi >= 30000) {
		lastRssi = millis();
		dlog("uptime %lu rssi %d\n", millis() / 1000, Wio.GetReceivedSignalStrength());
	}
	if (probeNow || millis() - lastProbe >= 120000) {
		probeNow = false;
		lastProbe = millis();
		probe();
	}
	/* Commands also arrive line by line from USB serial. */
	static char line[80];
	static int len;
	while (SerialUSB.available()) {
		int c = SerialUSB.read();

		if (c == '\r' || c == '\n') {
			line[len] = 0;
			if (len > 0)
				onCommand(line);
			len = 0;
		} else if (len < (int)sizeof(line) - 1) {
			line[len++] = c;
		}
	}
	Ota.poll();
}

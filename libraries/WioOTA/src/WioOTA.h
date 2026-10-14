/*
 * WioOTA - log upload, command polling and self firmware update for
 * Wio LTE over SORACOM (Beam / HTTP).
 *
 *	#include <WioLTEforArduino.h>
 *	#include <WioOTA.h>
 *
 *	WioLTE Wio;
 *	WioOTA Ota;
 *
 *	void setup() {
 *		... Wio.Init(), TurnOnOrReset(), Activate() ...
 *		Ota.begin(Wio, "http://beam.soracom.io:8888/wiolte/");
 *		Ota.onCommand(handler);
 *	}
 *	void loop() {
 *		Ota.println("hello");	// buffered, sent on the next poll
 *		Ota.poll();		// call often; talks to the server every interval
 *	}
 *
 * The sketch must be linked for the bootloader (start 0x08010000); see
 * the repository README for the arduino-cli build properties.
 */
#ifndef WIOOTA_H
#define WIOOTA_H

#include <Arduino.h>
#include <WioLTEforArduino.h>

#ifndef WIOOTA_LOG_SIZE
#define WIOOTA_LOG_SIZE		4096
#endif
#ifndef WIOOTA_POST_MAX
#define WIOOTA_POST_MAX		1024	/* log bytes per request */
#endif
#ifndef WIOOTA_CHUNK
#define WIOOTA_CHUNK		2048	/* firmware bytes per request */
#endif

class WioOTA : public Print {
public:
	typedef void (*CommandHandler)(const char *line);

	WioOTA();

	/* baseUrl: server endpoint, e.g. "http://beam.soracom.io:8888/wiolte/" */
	void begin(WioLTE &wio, const char *baseUrl, unsigned long intervalMs = 10000);
	void setInterval(unsigned long intervalMs) { _interval = intervalMs; }
	void onCommand(CommandHandler handler) { _handler = handler; }
	/* Also copy log output to another stream (e.g. SerialUSB). */
	void setEcho(Print *echo) { _echo = echo; }

	/*
	 * Call from loop().  When the interval has elapsed (or force is
	 * true) it uploads buffered log, fetches commands and, if the
	 * server offers a different firmware, downloads it and resets.
	 * Returns true if the server was reached.
	 */
	bool poll(bool force = false);

	/* CRC-32 of the running image as recorded by the bootloader (0 if unknown). */
	uint32_t currentCrc() const;

	virtual size_t write(uint8_t c);
	using Print::write;

private:
	bool postLog();
	bool fetch(char *resp, int size);
	void handleLine(char *line);
	bool update(uint32_t len, uint32_t crc);
	void url(char *buf, int size, const char *query);

	WioLTE *_wio;
	const char *_base;
	unsigned long _interval;
	unsigned long _last;
	bool _first;
	CommandHandler _handler;
	Print *_echo;

	char _log[WIOOTA_LOG_SIZE];
	volatile uint32_t _head, _tail;	/* free-running indices */
};

#endif

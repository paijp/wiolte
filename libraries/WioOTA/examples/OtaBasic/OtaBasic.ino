/*
 * WioOTA basic example.
 *
 * - Prints a counter every 5 seconds to the server log.
 * - Commands from the server: "led R G B", "ping", "reset" (built in).
 * - When a different firmware is placed on the server it is
 *   downloaded, the board resets and the bootloader installs it.
 */
#include <WioLTEforArduino.h>
#include <WioOTA.h>
#include <stdio.h>
#include <string.h>

#ifndef OTA_URL
#define OTA_URL	"http://beam.soracom.io:18080/wiolte/index.php"
#endif

WioLTE Wio;
WioOTA Ota;

static void onCommand(const char *line)
{
	int r, g, b;

	if (sscanf(line, "led %d %d %d", &r, &g, &b) == 3) {
		Wio.LedSetRGB(r, g, b);
		Ota.print("led ok\n");
	} else if (strcmp(line, "ping") == 0) {
		Ota.print("pong\n");
	} else {
		Ota.print("unknown command: ");
		Ota.println(line);
	}
}

void setup()
{
	delay(200);
	SerialUSB.begin(115200);
	Wio.Init();
	Wio.PowerSupplyLTE(true);
	delay(500);
	Ota.setEcho(&SerialUSB);

	Wio.LedSetRGB(10, 10, 0);
	while (!Wio.TurnOnOrReset())
		delay(1000);
	while (!Wio.Activate("soracom.io", "sora", "sora"))
		delay(1000);
	Wio.LedSetRGB(0, 10, 0);

	Ota.begin(Wio, OTA_URL, 10000);
	Ota.onCommand(onCommand);
	Ota.print("boot, crc=");
	Ota.println(Ota.currentCrc(), HEX);
}

void loop()
{
	static unsigned long last;

	if (millis() - last >= 5000) {
		last = millis();
		Ota.print("uptime ");
		Ota.println(millis() / 1000);
	}
	Ota.poll();
}

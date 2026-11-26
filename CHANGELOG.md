# Changelog

## v0.1.0

- Bootloader (644 bytes): installs a CRC-checked image from the staging area.
- WioOTA library: log upload, commands and self update over SORACOM Beam
  (website entry point, `http://beam.soracom.io:18080/`).
  - HTTP is spoken over a plain TCP socket. The module's own HTTP client
    (`AT+QHTTPGET/POST`) waited ~30 s before connecting on about every other
    request; the socket path connects in ~70 ms (median request 0.24 s).
  - Firmware is fetched in 16KB chunks: a 128KB image downloads in ~12 s.
  - USB serial diagnostics (`wioota: ...`), optional request timing.
  - Polls every 15 s by default; the built-in `interval <s>` command
    changes it from the server.
- OtaBasic (starter app) and OtaDiag (timing, TCP probe, AT trace) examples.
- `wiolte-full.bin`: bootloader + starter app for a blank board.
- Web page (WebUSB): DFU write with automatic load address, verify, start,
  then the sketch's USB serial; optional upload of the log to the server.
- Server: `index.php` (firmware / commands / log), `serial.php` and
  `wiolte-serial` for the uploaded serial log.

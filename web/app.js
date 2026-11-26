/*
 * Wio LTE DFU - write a .bin to Wio LTE (STM32F4 system bootloader, USB
 * DFU with ST DfuSe extensions) from the browser over WebUSB, then show
 * the sketch's USB serial (WebUSB on Android, Web Serial on desktops).
 *
 * Port of android/.../DfuSe.java.  The load address comes from the
 * image's vector table: bootloader 0x08000000, application 0x08010000.
 */
"use strict";
(function () {
	var DFU = { vendorId: 0x0483, productId: 0xdf11 };
	var CDC = { vendorId: 0x0483, productId: 0x5740 };
	var BOOT_ADDR = 0x08000000, APP_ADDR = 0x08010000;
	var XFER = 2048;
	var LOG_LIMIT = 240000, LOG_KEEP = 160000;

	var $ = function (id) { return document.getElementById(id); };
	var msg = $("msg"), logEl = $("log"), bar = $("bar"), imageEl = $("image");
	var img = null, imgName = "";
	var busy = false;
	var serial = null;		/* { write(Uint8Array), close() } */
	var received = [];

	/* ---------------- log ---------------- */

	function append(text) {
		var atBottom = logEl.scrollTop + logEl.clientHeight >= logEl.scrollHeight - 4;
		logEl.textContent += text;
		if (logEl.textContent.length > LOG_LIMIT) {
			var t = logEl.textContent, cut = t.length - LOG_KEEP, nl = t.indexOf("\n", cut);
			logEl.textContent = t.slice(nl >= cut ? nl + 1 : cut);
		}
		if (atBottom)
			logEl.scrollTop = logEl.scrollHeight;
	}
	/* The page's own lines are marked with a dot, the board's are not. */
	function line(s) { append("\n· " + s + "\n"); }
	function status(s) { msg.textContent = s; }
	function hex(n) { return "0x" + ("0000000" + (n >>> 0).toString(16)).slice(-8); }

	/* ---------------- image ---------------- */

	function le32(b, o) { return (b[o] | b[o + 1] << 8 | b[o + 2] << 16 | b[o + 3] << 24) >>> 0; }

	function detectAddress(b) {
		if (b.length < 8)
			return -1;
		var sp = le32(b, 0), pc = le32(b, 4);
		if (sp < 0x20000000 || sp > 0x20030000 || !(pc & 1))
			return -1;
		if (pc >= BOOT_ADDR && pc < APP_ADDR)
			return BOOT_ADDR;
		if (pc >= APP_ADDR && pc < 0x08080000)
			return APP_ADDR;
		return -1;
	}

	function setImage(bytes, name) {
		img = bytes;
		imgName = name;
		var a = detectAddress(bytes);
		var kind = a === APP_ADDR ? "application"
			: a !== BOOT_ADDR ? "不明なイメージ"
			: bytes.length > APP_ADDR - BOOT_ADDR ? "bootloader + application" : "bootloader";
		imageEl.textContent = name + ", " + bytes.length + " bytes, " + kind + (a < 0 ? "" : " @ " + hex(a));
		$("program").disabled = a < 0;
	}

	/* ---------------- DfuSe over WebUSB ---------------- */

	function sleep(ms) { return new Promise(function (r) { setTimeout(r, ms); }); }

	function sectorAddr(s) {
		if (s < 4) return 0x08000000 + s * 0x4000;
		if (s === 4) return 0x08010000;
		return 0x08000000 + (s - 4) * 0x20000;
	}
	function sectorOf(a) {
		var off = a - 0x08000000;
		if (off < 0x10000) return Math.floor(off / 0x4000);
		if (off < 0x20000) return 4;
		return 4 + Math.floor(off / 0x20000);
	}

	function Dfu(dev) {
		this.dev = dev;
		this.ifn = -1;
	}
	Dfu.prototype.open = async function () {
		var d = this.dev;
		await d.open();
		if (!d.configuration)
			await d.selectConfiguration(1);
		var self = this;
		d.configuration.interfaces.forEach(function (it) {
			it.alternates.forEach(function (alt) {
				/* DFU: class 0xfe subclass 1; alt 0 is internal flash */
				if (alt.interfaceClass === 0xfe && alt.interfaceSubclass === 1 &&
				    alt.alternateSetting === 0 && self.ifn < 0)
					self.ifn = it.interfaceNumber;
			});
		});
		if (this.ifn < 0)
			throw new Error("DFU interface not found");
		await d.claimInterface(this.ifn);
		await d.selectAlternateInterface(this.ifn, 0);
	};
	Dfu.prototype.close = async function () {
		try { await this.dev.close(); } catch (e) { /* already gone */ }
	};
	Dfu.prototype.out = async function (req, value, data) {
		var r = await this.dev.controlTransferOut({ requestType: "class", recipient: "interface",
			request: req, value: value, index: this.ifn }, data || new Uint8Array(0));
		if (r.status !== "ok")
			throw new Error("control out " + req + ": " + r.status);
	};
	Dfu.prototype.getStatus = async function () {
		var r = await this.dev.controlTransferIn({ requestType: "class", recipient: "interface",
			request: 3, value: 0, index: this.ifn }, 6);
		if (r.status !== "ok" || r.data.byteLength < 6)
			throw new Error("GETSTATUS failed");
		var b = new Uint8Array(r.data.buffer);
		return { status: b[0], poll: b[1] | b[2] << 8 | b[3] << 16, state: b[4] };
	};
	Dfu.prototype.toIdle = async function () {
		for (var i = 0; i < 5; i++) {
			var s = await this.getStatus();
			if (s.state === 2)		/* dfuIDLE */
				return;
			await this.out(s.state === 10 ? 4 : 6, 0);	/* CLRSTATUS : ABORT */
		}
		throw new Error("cannot reach dfuIDLE");
	};
	Dfu.prototype.download = async function (block, data) {
		await this.out(1, block, data);
		for (var i = 0; i < 100; i++) {
			var s = await this.getStatus();
			if (s.status !== 0)
				throw new Error("DFU status " + s.status + " state " + s.state);
			if (s.state === 5 || s.state === 2 || s.state === 7)	/* DNLOAD_IDLE, IDLE, MANIFEST */
				return;
			await sleep(Math.max(s.poll, 1));
		}
		throw new Error("DFU busy timeout");
	};
	Dfu.prototype.command = function (cmd, addr) {
		return this.download(0, new Uint8Array([cmd, addr & 0xff, addr >>> 8 & 0xff, addr >>> 16 & 0xff, addr >>> 24 & 0xff]));
	};
	Dfu.prototype.write = async function (addr, data) {
		if (addr < 0x08000000 || addr + data.length > 0x08100000)
			throw new Error("image outside flash");
		await this.toIdle();
		var first = sectorOf(addr), last = sectorOf(addr + data.length - 1), s, off;
		for (s = first; s <= last; s++) {
			line("erase sector " + s + " (" + hex(sectorAddr(s)) + ")");
			await this.command(0x41, sectorAddr(s));
		}
		line("write " + data.length + " bytes at " + hex(addr));
		for (off = 0; off < data.length; off += XFER) {
			await this.command(0x21, addr + off);
			await this.download(2, data.subarray(off, Math.min(off + XFER, data.length)));
			bar.max = data.length;
			bar.value = Math.min(off + XFER, data.length);
		}
		line("verify");
		await this.toIdle();
		await this.command(0x21, addr);
		await this.toIdle();
		for (off = 0, s = 2; off < data.length; off += XFER, s++) {
			var n = Math.min(XFER, data.length - off);
			var r = await this.dev.controlTransferIn({ requestType: "class", recipient: "interface",
				request: 2, value: s, index: this.ifn }, XFER);
			var got = new Uint8Array(r.data.buffer, r.data.byteOffset, r.data.byteLength);
			if (r.status !== "ok" || got.length < n)
				throw new Error("UPLOAD failed at " + off);
			for (var i = 0; i < n; i++)
				if (got[i] !== data[off + i])
					throw new Error("verify mismatch at " + hex(addr + off + i));
			bar.value = off + n;
		}
		await this.toIdle();
		line("done");
	};
	/* Leave DFU and start the code whose vector table is at addr. */
	Dfu.prototype.leave = async function (addr) {
		await this.toIdle();
		await this.command(0x21, addr);
		await this.out(1, 0);
		try { await this.getStatus(); } catch (e) { /* the device may detach first */ }
		line("started at " + hex(addr));
	};

	async function program() {
		if (busy || !img)
			return;
		var addr = detectAddress(img);
		if (addr < 0) {
			line("書き込み先を判別できません（Wio LTE のイメージではありません）");
			return;
		}
		var dev;
		try {
			dev = await navigator.usb.requestDevice({ filters: [DFU] });
		} catch (e) {
			line("DFU デバイスが選ばれませんでした。BOOT を押しながら RST を押して離し、DFU モードにしてください");
			return;
		}
		busy = true;
		$("program").disabled = true;
		await closeSerial();
		status("書き込み中…");
		line("program " + imgName + " (" + img.length + " bytes) @ " + hex(addr));
		var dfu = new Dfu(dev), ok = false;
		try {
			await dfu.open();
			await dfu.write(addr, img);
			await dfu.leave(BOOT_ADDR);
			ok = true;
		} catch (e) {
			line("failed: " + e.message);
		}
		await dfu.close();
		busy = false;
		$("program").disabled = false;
		if (ok) {
			status("書き込み完了。USB シリアルを待っています…");
			waitForSerial();
		} else {
			status("書き込みに失敗しました");
		}
	}

	/* ---------------- USB serial of the running sketch ---------------- */

	async function openUsbCdc(d) {
		await d.open();
		if (!d.configuration)
			await d.selectConfiguration(1);
		var ctl = -1, dat = -1, epIn = 0, epOut = 0;
		d.configuration.interfaces.forEach(function (it) {
			var alt = it.alternates[0];
			if (alt.interfaceClass === 0x02 && ctl < 0)
				ctl = it.interfaceNumber;
			if (alt.interfaceClass === 0x0a && dat < 0) {
				dat = it.interfaceNumber;
				alt.endpoints.forEach(function (e) {
					if (e.type === "bulk" && e.direction === "in") epIn = e.endpointNumber;
					if (e.type === "bulk" && e.direction === "out") epOut = e.endpointNumber;
				});
			}
		});
		if (dat < 0 || !epIn || !epOut)
			throw new Error("CDC interface not found");
		/* Android 16 refuses the data interface unless control is claimed first. */
		if (ctl >= 0)
			try { await d.claimInterface(ctl); } catch (e) { /* not fatal */ }
		await d.claimInterface(dat);
		var ifn = ctl >= 0 ? ctl : 0;
		try {
			await d.controlTransferOut({ requestType: "class", recipient: "interface", request: 0x20, value: 0, index: ifn },
				new Uint8Array([0x00, 0xc2, 0x01, 0x00, 0, 0, 8]));	/* 115200 8N1 */
			await d.controlTransferOut({ requestType: "class", recipient: "interface", request: 0x22, value: 3, index: ifn });
		} catch (e) { /* some stacks ignore line coding */ }
		var open = true;
		(async function () {
			while (open) {
				try {
					var r = await d.transferIn(epIn, 512);
					if (r.data && r.data.byteLength)
						onData(new Uint8Array(r.data.buffer, r.data.byteOffset, r.data.byteLength));
				} catch (e) {
					if (open) lost("USB serial: " + e.message);
					return;
				}
			}
		})();
		return {
			dev: d,
			write: function (b) { return d.transferOut(epOut, b); },
			close: async function () { open = false; try { await d.close(); } catch (e) { /* gone */ } }
		};
	}

	async function openWebSerial(port) {
		await port.open({ baudRate: 115200 });
		var reader = port.readable.getReader(), open = true;
		(async function () {
			while (open) {
				try {
					var r = await reader.read();
					if (r.done) break;
					onData(r.value);
				} catch (e) {
					if (open) lost("serial: " + e.message);
					return;
				}
			}
		})();
		return {
			port: port,
			write: async function (b) { var w = port.writable.getWriter(); await w.write(b); w.releaseLock(); },
			close: async function () {
				open = false;
				try { await reader.cancel(); reader.releaseLock(); await port.close(); } catch (e) { /* gone */ }
			}
		};
	}

	var decoder = new TextDecoder();
	function onData(b) {
		received.push(b.slice());
		append(decoder.decode(b, { stream: true }));
	}

	function lost(why) {
		serial = null;
		line(why);
		status("シリアル切断");
	}

	async function closeSerial() {
		var s = serial;
		serial = null;
		if (s)
			await s.close();
	}

	/* Web Serial on desktops (the OS CDC driver owns the port), WebUSB on Android. */
	var useWebSerial = !!navigator.serial && !/Android/i.test(navigator.userAgent);

	async function connectSerial(ask) {
		if (serial)
			return;
		try {
			if (useWebSerial) {
				var ports = await navigator.serial.getPorts();
				var port = ports.filter(function (p) {
					var i = p.getInfo();
					return i.usbVendorId === CDC.vendorId && i.usbProductId === CDC.productId;
				})[0];
				if (!port && ask)
					port = await navigator.serial.requestPort({ filters: [{ usbVendorId: CDC.vendorId, usbProductId: CDC.productId }] });
				if (!port)
					return false;
				serial = await openWebSerial(port);
			} else {
				var ds = await navigator.usb.getDevices();
				var d = ds.filter(function (x) { return x.vendorId === CDC.vendorId && x.productId === CDC.productId; })[0];
				if (!d && ask)
					d = await navigator.usb.requestDevice({ filters: [CDC] });
				if (!d)
					return false;
				serial = await openUsbCdc(d);
			}
			line("monitoring USB serial");
			status("Wio LTE 実行中 — USB シリアル表示中");
			return true;
		} catch (e) {
			if (ask)
				line("serial: " + e.message);
			return false;
		}
	}

	/* After DFU leaves, the sketch enumerates as USB serial; follow it if permitted. */
	async function waitForSerial() {
		for (var i = 0; i < 75; i++) {
			if (await connectSerial(false))
				return;
			await sleep(200);
		}
		line("15 秒以内に USB シリアルが見つかりません。初回は「シリアル接続」を押して選んでください（ブートローダーのみの場合は出ません）");
		status("書き込み完了");
	}

	async function send(newline) {
		var t = $("input").value;
		if (!t && !newline)
			return;
		if (!serial) {
			line("シリアル未接続です");
			return;
		}
		try {
			await serial.write(new TextEncoder().encode(newline ? t + "\n" : t));
			line("sent: " + t + (newline ? "\\n" : ""));
			$("input").value = "";
		} catch (e) {
			line("send failed: " + e.message);
		}
	}

	/* ---------------- wiring ---------------- */

	$("pick").onclick = function () { $("file").click(); };
	$("file").onchange = async function () {
		var f = this.files[0];
		if (f)
			setImage(new Uint8Array(await f.arrayBuffer()), f.name);
	};
	/* Published next to this page by the Pages workflow; raw GitHub as fallback. */
	async function loadPublished(name) {
		var urls = [name, "https://raw.githubusercontent.com/paijp/wiolte/main/rel/" + name];
		for (var i = 0; i < urls.length; i++) {
			try {
				var r = await fetch(urls[i], { cache: "no-store" });
				if (!r.ok)
					continue;
				setImage(new Uint8Array(await r.arrayBuffer()), name);
				line("loaded " + urls[i]);
				return;
			} catch (e) { /* try the next */ }
		}
		line(name + " を取得できませんでした（オフラインでは「ファイルを開く」を使ってください）");
	}
	$("useRel").onclick = function () { loadPublished("wiolte-full.bin"); };
	$("program").onclick = program;
	$("serial").onclick = function () {
		if (serial)
			closeSerial().then(function () { line("シリアル切断"); status("シリアル切断"); });
		else
			connectSerial(true);
	};
	$("send").onclick = function () { send(false); };
	$("input").addEventListener("keydown", function (e) {
		if (e.key === "Enter") {
			e.preventDefault();
			send(true);
		}
	});
	$("save").onclick = function () {
		var a = document.createElement("a");
		a.href = URL.createObjectURL(new Blob(received, { type: "application/octet-stream" }));
		a.download = "wiolte-serial-" + new Date().toISOString().replace(/[:.]/g, "-") + ".log";
		a.click();
	};

	if (!navigator.usb) {
		status("このブラウザは WebUSB に対応していません。Chrome か Edge で開いてください。");
		["useRel", "pick", "program", "serial", "send"].forEach(function (id) { $(id).disabled = true; });
		return;
	}
	navigator.usb.addEventListener("disconnect", function (e) {
		if (serial && serial.dev === e.device)
			lost("board detached");
	});
	navigator.usb.addEventListener("connect", function (e) {
		if (!busy && !serial && e.device.productId === CDC.productId)
			connectSerial(false);
	});
	status("ファイルを選び、DFU モードの Wio LTE を接続して program を押してください");
	connectSerial(false);
	/* ?f=OtaDiag.ino.bin preloads one of the published images. */
	var f = new URLSearchParams(location.search).get("f");
	if (f && /^[A-Za-z0-9._-]+\.bin$/.test(f))
		loadPublished(f);
})();

<?php
/*
 * WioOTA server endpoint (reached through SORACOM Beam).
 *
 * Per-device directory  data/<IMSI>/ :
 *	firmware.bin	image offered to the device (application, linked at 0x08010000)
 *	cmd.txt		commands, one per line; delivered once, then cleared
 *	log.txt		log uploaded by the device (appended)
 *
 * Requests (all from the device):
 *	POST ?a=log			body: log text -> appended to log.txt
 *	GET  ?a=poll&crc=XXXXXXXX	-> "fw <len> <crc>\n" if firmware.bin differs,
 *					   followed by the pending commands
 *	GET  ?a=fw&o=OFF&n=LEN&crc=XXXXXXXX -> LEN bytes of firmware.bin from OFF
 */

$config = array(
	"datadir" => __DIR__."/data",
	/* SORACOM Beam pre-shared key for the signature header; "" disables the check. */
	"beam_secret" => "",
	/* Allow ?id=... instead of SORACOM headers (local testing only). */
	"allow_query_id" => false,
	"max_log" => 4 * 1024 * 1024,
);
if (is_readable(__DIR__."/config.php"))
	include(__DIR__."/config.php");

function fail($code, $msg)
{
	http_response_code($code);
	header("Content-Type: text/plain");
	header("Content-Length: ".strlen($msg));
	print $msg;
	exit;
}

function reply($body, $type = "text/plain")
{
	header("Content-Type: ".$type);
	header("Content-Length: ".strlen($body));
	print $body;
	exit;
}

function hdr($name)
{
	$k = "HTTP_".strtoupper(str_replace("-", "_", $name));
	return isset($_SERVER[$k]) ? $_SERVER[$k] : null;
}

/*
 * SORACOM Beam signature: sha256(secret + "x-soracom-imei=..." +
 * "x-soracom-imsi=..." + "x-soracom-timestamp=...") over the headers
 * Beam added, in that order.
 */
function check_beam($secret)
{
	if ($secret === "")
		return;
	$sig = hdr("X-Soracom-Signature");
	$ts = hdr("X-Soracom-Timestamp");
	if ($sig === null || $ts === null)
		fail(403, "no signature");
	$s = $secret;
	foreach (array("imei", "imsi") as $k)
		if (($v = hdr("X-Soracom-".$k)) !== null)
			$s .= "x-soracom-{$k}={$v}";
	$s .= "x-soracom-timestamp={$ts}";
	if (!hash_equals(hash("sha256", $s), strtolower($sig)))
		fail(403, "bad signature");
	if (abs(microtime(true) * 1000 - (float)$ts) > 5 * 60 * 1000)
		fail(403, "stale timestamp");
}

check_beam($config["beam_secret"]);

$id = hdr("X-Soracom-IMSI");
if ($id === null)
	$id = hdr("X-Soracom-IMEI");
if ($id === null && $config["allow_query_id"] && isset($_GET["id"]))
	$id = $_GET["id"];
if ($id === null || !preg_match('/^[0-9A-Za-z]{1,32}$/', $id))
	fail(403, "no device id");

$dir = $config["datadir"]."/".$id;
if (!is_dir($dir) && !@mkdir($dir, 0770, true))
	fail(500, "mkdir");

$fw = "$dir/firmware.bin";
$a = isset($_GET["a"]) ? $_GET["a"] : "";

switch ($a) {
case "log":
	$body = file_get_contents("php://input");
	$log = "$dir/log.txt";
	if (is_file($log) && filesize($log) > $config["max_log"])
		rename($log, "$dir/log.old.txt");
	file_put_contents($log, $body, FILE_APPEND | LOCK_EX);
	reply("ok\n");

case "poll":
	$out = "";
	$devcrc = isset($_GET["crc"]) ? strtolower($_GET["crc"]) : "";
	if (is_readable($fw) && ($len = filesize($fw)) > 0) {
		$crc = sprintf("%08x", crc32(file_get_contents($fw)));
		if ($crc !== $devcrc)
			$out .= "fw $len $crc\n";
	}
	$cf = "$dir/cmd.txt";
	if (is_file($cf) && ($fp = fopen($cf, "r+"))) {
		flock($fp, LOCK_EX);
		$out .= stream_get_contents($fp);
		ftruncate($fp, 0);
		flock($fp, LOCK_UN);
		fclose($fp);
	}
	if (strlen($out) > 1000)
		fail(500, "command queue too long");
	reply($out);

case "fw":
	if (!is_readable($fw))
		fail(404, "no firmware");
	$data = file_get_contents($fw);
	if (isset($_GET["crc"]) && strtolower($_GET["crc"]) !== sprintf("%08x", crc32($data)))
		fail(409, "firmware changed");
	$o = (int)$_GET["o"];
	$n = (int)$_GET["n"];
	if ($o < 0 || $n <= 0 || $n > 4096 || $o + $n > strlen($data))
		fail(416, "range");
	reply(substr($data, $o, $n), "application/octet-stream");

default:
	fail(400, "unknown action");
}
